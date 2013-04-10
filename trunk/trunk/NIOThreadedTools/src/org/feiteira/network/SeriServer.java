package org.feiteira.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SeriServer implements Runnable {

	public static final int DEFAULT_THREAD_COUNT = 10;
	public static final int DEFAULT_SLEEP_TIME = 10;
	public static final int DEFAULT_TIMEOUT = 15000;
	public static final int DEFAULT_SELECTOR_TIMEOUT = 1000;

	private Selector selector;
	private ServerSocketChannel server;
	private int nthreads;
	private int sleep_time;
	private int timeout;

	private ExecutorService executor;
	private int port;
	private boolean running;
	Vector<SeriDataPackage> dataStore;
	private Thread tread;
	private SeriProcessor processor = null;

	private Object shutdownLock = null;

	private boolean shutdownFinished;

	public SeriServer() {
		this.shutdownFinished = false;
		shutdownLock = new Object();
	}

	public SeriServer(int port) throws IOException {
		this(port, DEFAULT_THREAD_COUNT);
	}

	public SeriServer(int port, int nthreads) throws IOException {
		this();
		this.port = port;
		this.sleep_time = DEFAULT_SLEEP_TIME;
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

		this.running = true;
		this.tread = new Thread(this);
		tread.start();
	}

	@Override
	public void run() {

		while (this.running || !selector.selectedKeys().isEmpty()) {
			try {
				selector.select(DEFAULT_SELECTOR_TIMEOUT);
			} catch (IOException e) {
				this.shutdownFinished = true;
				this.running = false;
				throw new RuntimeException(e);
			}
			try {
				for (Iterator<SelectionKey> i = selector.selectedKeys()
						.iterator(); i.hasNext();) {
					SelectionKey key = i.next();
					i.remove();

					SeriWorker worker = new SeriWorker(key);
					this.executor.execute(worker);
				}
			} catch (java.nio.channels.ClosedSelectorException e) {
				if (this.running) {
					this.shutdownFinished = true;
					this.running = false;
					throw new RuntimeException(e);
				}
			}

			// rest for a bit
			try {
				Thread.sleep(DEFAULT_SLEEP_TIME);
			} catch (InterruptedException e) {
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
		this.shutdownFinished = true;
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
			synchronized (shutdownLock) {
				this.running = false;
				shutdownLock.wait();
			}
			System.out.println("Done " + this.toString());

		} catch (InterruptedException e) {
			// Best effort here..
			e.printStackTrace();
		}
	}

	public void shutdownASYNC() {
		this.running = false;
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
					SeriDataPackage object = read(key);
					if (object != null)
						if (outer.getProcessor() == null)
							dataStore.add(object);
						else
							outer.getProcessor().process(object);
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
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
		return this.shutdownFinished;
	}

}
