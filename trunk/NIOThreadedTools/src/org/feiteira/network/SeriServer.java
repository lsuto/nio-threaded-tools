package org.feiteira.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.Vector;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

public class SeriServer implements Runnable {
	private static Logger log = Logger.getLogger(SeriServer.class);

	public static final int STATE_NOT_STARTED = 100;
	public static final int STATE_STARTING = 200;
	public static final int STATE_RUNNING = 300;
	public static final int STATE_SHUTTING_DOWN_1 = 400;
	public static final int STATE_SHUTTING_DOWN_2 = 410;
	public static final int STATE_SHUTDOWN = 500;

	public static final int DEFAULT_THREAD_COUNT = 10;
	public static final int DEFAULT_TIMEOUT = 15000;
	public static final int DEFAULT_SELECTOR_TIMEOUT = 1000;
	public static final int DEFAULT_SHUTDOWN_GRACE_PERIOD = 5000;
	public static final int DEFAULT_REST_AND_WAIT_PERIOD = 300;

	private Selector accept_selector;
	private ServerSocketChannel server;
	private int nthreads;
	private int sleep_time;
	private int timeout;

	private int port;
	Vector<SeriDataPackage> dataStore;
	private Thread tread;
	private SeriEventHandler processor = null;
	private SeriWorker workers[];

	private Object shutdownLock = null;

	private int state;

	private String tag = "";

	private long shutdownRequestTime;

	public static int nullcount;

	public SeriServer() {

		this.state = STATE_NOT_STARTED;
		shutdownLock = new Object();

		log.setLevel(Level.WARN);
	}

	public SeriServer(int port) throws IOException {
		this(port, DEFAULT_THREAD_COUNT);
	}

	public SeriServer(int port, int nthreads, String tag) throws IOException {
		this();
		this.setLogTag(tag);
		this.port = port;
		this.timeout = DEFAULT_TIMEOUT;

		this.nthreads = nthreads;
		this.dataStore = new Vector<SeriDataPackage>();

		accept_selector = Selector.open();
		server = ServerSocketChannel.open();

		server.socket().bind(new InetSocketAddress(port));

		this.port = server.socket().getLocalPort();

		server.configureBlocking(false);
		server.register(accept_selector, SelectionKey.OP_ACCEPT);
		this.state = STATE_RUNNING;
		this.tread = new Thread(this);

		tread.start();

		workers = new SeriWorker[nthreads];
		for (int cnt = 0; cnt < nthreads; cnt++) {
			// selectors[cnt] = Selector.open();
			workers[cnt] = new SeriWorker(cnt);
			workers[cnt].start();
		}

	}

	public SeriServer(int port, int nthreads) throws IOException {
		this(port, nthreads, null);
	}

	@Override
	public void run() {

		while (this.state == STATE_RUNNING) {

			try {
				accept_selector.select(DEFAULT_SELECTOR_TIMEOUT);
			} catch (IOException e) {
				this.state = STATE_SHUTDOWN;
				throw new RuntimeException(e);
			}
			Set<SelectionKey> keys = accept_selector.selectedKeys();

			try {
				for (Iterator<SelectionKey> i = keys.iterator(); i.hasNext();) {
					SelectionKey key = i.next();
					i.remove();

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

								int workerId = (int) (Math.random() * nthreads);
								// log.debug("Choose worker: " + workerId);
								// client.register(
								// this.workers[workerId].getSelector(),
								// SelectionKey.OP_READ);
								this.workers[workerId].registerClient(client);
							}
						}
					} catch (IOException e) {
						key.cancel();
						log.warn("Failed to handle connection", e);
					} catch (java.nio.channels.CancelledKeyException e) {
						log.info(tag + "Connection closed by client.");
					}
				}
			} catch (java.nio.channels.ClosedSelectorException e) {
				log.debug("Selector closed.", e);
				if (this.state == STATE_RUNNING) {
					this.state = STATE_SHUTDOWN;
					throw new RuntimeException(e);
				}
			}
		}

		// CLOSING server...
		internalFullShutdownCleanup();
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

	public void shutdown() {
		this.shutdownRequestTime = System.currentTimeMillis();

		try {
			do {
				synchronized (shutdownLock) {
					// the state has to change inside the lock
					// otherwise we risk notifying close before entering the
					// wait
					this.state = STATE_SHUTTING_DOWN_1;
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
		this.shutdownRequestTime = System.currentTimeMillis();
		this.state = STATE_SHUTTING_DOWN_1;
	}

	private boolean graceEnded() {
		return (System.currentTimeMillis() - this.shutdownRequestTime) > DEFAULT_SHUTDOWN_GRACE_PERIOD;
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

	public SeriEventHandler getProcessor() {
		return processor;
	}

	public void setProcessor(SeriEventHandler processor) {
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

	public class SeriWorker extends Thread {
		final SeriServer outer = SeriServer.this;

		private Selector selector = null;

		private int id;
		private Vector<SocketChannel> incommingSockets = null;

		private boolean gracefullShutdown;

		public SeriWorker(int id) throws IOException {
			super();
			this.id = id;
			this.selector = Selector.open();
			incommingSockets = new Vector<SocketChannel>();
			this.gracefullShutdown = false;
		}

		public Selector getSelector() {
			return this.selector;
		}

		public boolean finishedGracefullShutdown() {
			return this.gracefullShutdown;
		}

		public void registerClient(SocketChannel incomming) {
			incommingSockets.add(incomming);
		}

		@Override
		public void run() {
			log.debug("Running " + id);
			boolean shuttingDown = false;

			while (outer.isRunning() || !outer.graceEnded()) {
				try {
					this.selector.select(DEFAULT_SELECTOR_TIMEOUT);
				} catch (IOException e1) {
					log.error("Selector raised exception", e1);
					break;
				}

				Set<SelectionKey> keys = this.selector.selectedKeys();

				if (incommingSockets.size() > 0) {
					while (incommingSockets.size() > 0) {
						try {
							incommingSockets.remove(0).register(this.selector,
									SelectionKey.OP_READ);
						} catch (ClosedChannelException e) {
							log.info("Failed to registor socket", e);
						}
					}
					/*
					 * this else below is here for the scenario where a player
					 * may try to shutdown and register almost simultaneously.
					 * If so, we skip the break call to give it an opportunity
					 * for a graceful exit
					 */
				} else if (!outer.isRunning() && keys.size() == 0) {
					break;
				}

				try {
					for (Iterator<SelectionKey> i = keys.iterator(); i
							.hasNext();) {
						SelectionKey key = i.next();
						i.remove();

						SeriDataPackage object = null;

						object = read(key);

						// log.debug(id + " OBJ " + object.getObject());
						if (object != null)
							outer.getProcessor().messageArrived(object);
					}
				} catch (ClosedChannelException e) {
					// closed anyway
					log.warn(tag + "Client closed connection ");
				} catch (Exception e) {
					log.error(
							tag
									+ "SeriWorker failed. Exiting and logging exception",
							e);
					return;
				}
			}

			this.gracefullShutdown = true;
		}

	}

	public String getLogTag() {
		return tag;
	}

	public void setLogTag(String logTag) {
		if (logTag == null)
			return;

		this.tag = "[ " + logTag + " ] ";
	}

	public void setLogLevel(Level level) {
		log.setLevel(level);
	}

	private void internalFullShutdownCleanup() {

		try {
			accept_selector.close();
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

		// gives an oportunity for workers to shutdown correctly
		boolean workersGracefullshutdown = true;
		do {
			workersGracefullshutdown = true;

			for (int cnt = 0; cnt < this.nthreads; cnt++) {
				if (!workers[cnt].finishedGracefullShutdown()) {
					workersGracefullshutdown = false;
					break;
				}
			}

			sleep(DEFAULT_REST_AND_WAIT_PERIOD);
		} while (workersGracefullshutdown == false);

		this.state = STATE_SHUTDOWN;
		synchronized (shutdownLock) {
			shutdownLock.notify();
		}

		this.processor.shutdownCompleted();
	}

	private void sleep(long m) {
		try {
			Thread.sleep(m);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
