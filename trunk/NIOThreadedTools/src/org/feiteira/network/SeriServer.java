package org.feiteira.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SeriServer implements Runnable {

	public static final int STATE_NOT_STARTED = 100;
	public static final int STATE_STARTING = 200;
	public static final int STATE_RUNNING = 300;
	public static final int STATE_SHUTTING_DOWN_1 = 400;
	public static final int STATE_SHUTTING_DOWN_2 = 410;
	public static final int STATE_SHUTDOWN = 500;

	public static final int DEFAULT_THREAD_COUNT = 10;
	public static final int DEFAULT_TIMEOUT = 15000;
	public static final int DEFAULT_SELECTOR_TIMEOUT = 1000;
	public static final int DEFAULT_SHUTDOWN_GRACE_PERIOD = 3000;

	private Selector selector;
	private ServerSocketChannel server;
	private int nthreads;
	private int sleep_time;
	private int timeout;

	private ExecutorService executor;
	private int port;
	Vector<SeriDataPackage> dataStore;
	private Thread tread;
	private SeriProcessor processor = null;

	private Object shutdownLock = null;

	// private boolean shutdownFinished;
	// private boolean running;

	private int state;

	public SeriServer() {
		this.state = STATE_NOT_STARTED;
		shutdownLock = new Object();
	}

	public SeriServer(int port) throws IOException {
		this(port, DEFAULT_THREAD_COUNT);
	}

	public SeriServer(int port, int nthreads) throws IOException {
		this();
		this.port = port;
		this.timeout = DEFAULT_TIMEOUT;

		this.nthreads = nthreads;
		this.dataStore = new Vector<SeriDataPackage>();

		selector = Selector.open();
		server = ServerSocketChannel.open();

		server.socket().bind(new InetSocketAddress(port));

		this.port = server.socket().getLocalPort();

		server.configureBlocking(false);
		server.register(selector, SelectionKey.OP_ACCEPT);
		executor = Executors.newFixedThreadPool(this.nthreads);

		this.state = STATE_RUNNING;
		this.tread = new Thread(this);
		tread.start();

	}

	@Override
	public void run() {
		long startShuttingDownTime = 0;

		while (this.state == STATE_RUNNING
				|| this.state == STATE_SHUTTING_DOWN_1
				|| this.state == STATE_SHUTTING_DOWN_2) {

			// System.out.println("State: " + state);

			if (this.state == STATE_SHUTTING_DOWN_1) {
				this.state = STATE_SHUTTING_DOWN_2;
				// stops accepting new connections
				try {
					this.server.register(selector, 0);
				} catch (ClosedChannelException e1) {
					// don't really care, I'm shutting down
					break;
				}
				startShuttingDownTime = System.currentTimeMillis();
			}

			try {
				selector.select(DEFAULT_SELECTOR_TIMEOUT);
			} catch (IOException e) {
				this.state = STATE_SHUTDOWN;
				throw new RuntimeException(e);
			}

			Set<SelectionKey> keys = selector.selectedKeys();
			if (this.state == STATE_SHUTTING_DOWN_2 && keys.size() == 0) {
				// shutting donw and no pending data
				System.out.println("SeriServer: No more data, shutting down.");
				break;
			}

			try {
				for (Iterator<SelectionKey> i = selector.selectedKeys()
						.iterator(); i.hasNext();) {
					SelectionKey key = i.next();
					i.remove();

					try {
						handleConnection(key);
					} catch (IOException e) {
						key.cancel();
						e.printStackTrace();
					}
				}
			} catch (java.nio.channels.ClosedSelectorException e) {
				if (this.state == STATE_RUNNING) {
					this.state = STATE_SHUTDOWN;
					throw new RuntimeException(e);
				}
			}

			if (this.state == STATE_SHUTTING_DOWN_2
					&& System.currentTimeMillis() - startShuttingDownTime > DEFAULT_SHUTDOWN_GRACE_PERIOD) {
				System.err.println("GRACE ENDED");
				break;
			}

		}
		// CLOSING server...
		executor.shutdown();
		try {
			selector.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			server.socket().close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			server.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

		synchronized (shutdownLock) {
			shutdownLock.notify();
		}
		this.state = STATE_SHUTDOWN;
	}

	public static void reply(SeriDataPackage datapack, Serializable objectToSend)
			throws IOException {

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		for (int i = 0; i < 4; i++)
			baos.write(0);
		ObjectOutputStream oos = new ObjectOutputStream(baos);
		oos.writeObject(objectToSend);
		oos.close();
		final ByteBuffer wrap = ByteBuffer.wrap(baos.toByteArray());
		wrap.putInt(0, baos.size() - 4);
		datapack.getSocket().write(wrap);
	}

	public SeriDataPackage read() throws InterruptedException {
		synchronized (this.dataStore) {
			if (this.dataStore.size() == 0) {
				this.dataStore.wait(this.timeout);
			}
			try {
				System.out.println(this.dataStore.get(0));
				SeriDataPackage object = (SeriDataPackage) this.dataStore
						.remove(0);
				return object;
			} catch (ArrayIndexOutOfBoundsException e) {
				return null;
			}

		}

	}

	public void shutdown() {
		try {
			this.state = STATE_SHUTTING_DOWN_1;
			do {
				synchronized (shutdownLock) {
					shutdownLock.wait();
				}
			} while (this.state != STATE_SHUTDOWN);
		} catch (InterruptedException e) {
			// best effort here.. hey! we tried :)
			this.state = STATE_SHUTDOWN;
			e.printStackTrace();
		}

	}

	public void shutdownASYNC() {
		this.state = STATE_SHUTTING_DOWN_1;
	}

	private void handleConnection(SelectionKey key) throws IOException {

		if (key.isConnectable()) {
			((SocketChannel) key.channel()).finishConnect();
		}
		if (key.isAcceptable()) {

			// accept connection
			SocketChannel client = server.accept();
			if (client != null) {
				client.configureBlocking(false);
				client.socket().setTcpNoDelay(true);
				client.register(selector, SelectionKey.OP_READ);
			}
		}
		if (key.isReadable()) {
			SeriWorker worker = new SeriWorker(key);
			this.executor.execute(worker);
		}
	}

	private SeriDataPackage read(SelectionKey key) throws IOException {
		SocketChannel socket = (SocketChannel) key.channel();
		ByteBuffer lengthByteBuffer = ByteBuffer.wrap(new byte[4]);

		// read from socket, should return the data size
		int err = socket.read(lengthByteBuffer);
		if (err == -1) {
			socket.close();
			key.cancel();
			return null;
		}

		int serisize = lengthByteBuffer.getInt(0);
		if (serisize == 0)
			return null;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(serisize);

		long startTime = System.currentTimeMillis();
		while (true) {
			err = socket.read(dataByteBuffer);
			if (err == -1) {
				socket.close();
				key.cancel();
				return null;
			}

			if (dataByteBuffer.remaining() == 0) {

				ObjectInputStream ois = new ObjectInputStream(
						new ByteArrayInputStream(dataByteBuffer.array()));
				Serializable retObj;
				try {
					retObj = (Serializable) ois.readObject();
				} catch (ClassNotFoundException e) {
					throw new RuntimeException(
							"Serializable not found? Really weird!", e);
				}
				// clean up
				dataByteBuffer = null;

				SeriDataPackage ret = new SeriDataPackage();
				ret.setObject(retObj);
				ret.setKey(key);
				ret.setSocket(socket);
				return ret;
			}

			// Socket times out
			if (System.currentTimeMillis() - startTime > this.timeout) {
				socket.close();
				key.cancel();
				return null;
			}

			try {
				Thread.sleep(this.sleep_time);
			} catch (InterruptedException e) {
			}
		}
	}

	public int getNthreads() {
		return nthreads;
	}

	public void setNthreads(int nthreads) {
		this.nthreads = nthreads;
	}

	public int getSleep_time() {
		return sleep_time;
	}

	public void setSleep_time(int sleep_time) {
		this.sleep_time = sleep_time;
	}

	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int timeout) {
		this.timeout = timeout;
	}

	public SeriProcessor getProcessor() {
		return processor;
	}

	public void setProcessor(SeriProcessor processor) {
		this.processor = processor;
	}

	public int getPort() {
		return port;
	}

	public boolean isShutdown() {
		return this.state == STATE_SHUTDOWN;
	}

	public boolean isRunning() {
		return this.state == STATE_RUNNING;
	}

	public class SeriWorker implements Runnable {
		final SeriServer outer = SeriServer.this;

		private SelectionKey key;

		public SeriWorker(SelectionKey key) {
			this.key = key;

		}

		@Override
		public void run() {
				try {
					SeriDataPackage object = read(key);
					if (object != null)
						if (outer.getProcessor() == null)
							dataStore.add(object);
						else
							outer.getProcessor().process(object);

				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		
	}
}
