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
	public static final int DEFAULT_TIMEOUT = 5000;

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

	public SeriServer(int port) throws IOException {
		this(port, DEFAULT_THREAD_COUNT);
	}

	public SeriServer(int port, int nthreads) throws IOException {
		this.port = port;
		this.sleep_time = DEFAULT_SLEEP_TIME;
		this.timeout = DEFAULT_TIMEOUT;

		this.nthreads = nthreads;
		this.dataStore = new Vector<SeriDataPackage>();

		start();
	}

	public void start() throws IOException {
		selector = Selector.open();
		server = ServerSocketChannel.open();
		server.socket().bind(new InetSocketAddress(port));
		server.configureBlocking(false);
		server.register(selector, SelectionKey.OP_ACCEPT);
		executor = Executors.newFixedThreadPool(this.nthreads);

		this.running = true;
		this.tread = new Thread(this);
		tread.start();
	}

	@Override
	public void run() {

		while (this.running) {
			try {
				selector.select();
			} catch (IOException e) {
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
				try {
					Thread.sleep(DEFAULT_SLEEP_TIME);
				} catch (InterruptedException e) {
				}

			} catch (java.nio.channels.ClosedSelectorException e) {
				if (this.running)
					throw new RuntimeException(e);
			}
		}
	}

	public void reply(SeriDataPackage datapack, Serializable objectToSend)
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

	public void shutdown() throws IOException {
		this.running = false;
		executor.shutdownNow();
		selector.close();
		server.socket().close();
		server.close();
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
					System.out.println("Connection established: " + key);
					((SocketChannel) key.channel()).finishConnect();
				}
				if (key.isAcceptable()) {
					System.out.println("Accepted connection: " + key);

					// accept connection
					SocketChannel client = server.accept();
					client.configureBlocking(false);
					client.socket().setTcpNoDelay(true);
					client.register(selector, SelectionKey.OP_READ);
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

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(lengthByteBuffer
				.getInt(0));

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

}
