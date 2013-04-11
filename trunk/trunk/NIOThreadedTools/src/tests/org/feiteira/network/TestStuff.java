package tests.org.feiteira.network;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;

import org.feiteira.network.SeriClient;
import org.feiteira.network.SeriDataPackage;
import org.feiteira.network.SeriProcessor;
import org.feiteira.network.SeriServer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestStuff {

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}

	@Before
	public void setUp() throws Exception {
	}

	@After
	public void tearDown() throws Exception {
	}

	@Test
	public void testSingleThread() throws IOException, InterruptedException {
		int port = 5100;

		String msg = "Simple Test!";

		System.out.println("Starting server.");
		SeriServer server = new SeriServer(port, 2);

		System.out.println("Starting client.");
		SeriClient client = new SeriClient("localhost", port);

		System.out.println("[Client] Sending message: " + msg);
		client.send(msg);
		System.out.println("[Server] Reading message: " + msg);

		SeriDataPackage fromClient = server.read();

		System.out.println("From Client: " + fromClient.getObject());
		
		assertEquals(msg, fromClient.getObject());

		server.reply(fromClient, msg + msg);

		SeriDataPackage fromServer = client.read();

		System.out.println("From Server: " + fromServer.getObject());

		assertEquals(msg + msg, fromServer.getObject());

		// test sending nulls
		client.send(null);
		SeriDataPackage shouldBeNull = server.read();

		System.out.println("Should be null: " + shouldBeNull.getObject());

		int MX = 10;
		for (int cnt = 0; cnt < MX; cnt++) {
			client.send(new Integer(cnt));
			SeriDataPackage pack = server.read();
			int ret = (Integer) pack.getObject();
			SeriServer.reply(pack, new Integer(ret * ret));
			pack = client.read();
			System.out.println("Client [" + cnt + "]: " + pack.getObject());
			assertEquals(cnt*cnt, pack.getObject());
		}

		System.out.println("Client shutting down");
		client.shutdown();
		System.out.println("Server shutting down");
		server.shutdown();
	}

	@Test
	public void testProcessor() throws IOException, InterruptedException {
		int port = 6500;
		String msg = "wildcards all around please!";
		final SeriServer server = new SeriServer(port, 3);
		server.setProcessor(new SeriProcessor() {
			@Override
			public void process(SeriDataPackage pack) {
				try {
					System.out.println("Processing: " + pack.getObject());
					server.reply(pack, "*" + pack.getObject().toString() + "*");
				} catch (IOException e) {
					e.printStackTrace();
					fail();
				}
			}
		});

		Thread.sleep(150);
		SeriClient client = new SeriClient("localhost", port);
		client.send(msg);

		SeriDataPackage response = client.read();

		System.out.println(response.getObject());
		assertEquals("*" + msg + "*", response.getObject());

		client.send("A");
		response = client.read();
		System.out.println(response.getObject());
		assertEquals("*A*", response.getObject());

		server.shutdown();
	}

	@Test
	public void testMultipleThreads() throws IOException, InterruptedException {
		final int nthreads = 1;
		final int nclients = 50;
		final int nsends = 2000;

		SeriServer server = new SeriServer(6001, nthreads);

		final SeriClient client[] = new SeriClient[nclients];

		for (int cnt = 0; cnt < nclients; cnt++) {
			final int counterVal = cnt;
			client[cnt] = new SeriClient("localhost", 6001);
			Thread t = new Thread(new Runnable() {

				@Override
				public void run() {
					try {
						for (int cnt2 = 0; cnt2 < nsends; cnt2++) {
							String msg = "[" + counterVal + "]";
							// System.out.println("Sending:" + msg);
							client[counterVal].send(msg);
						}
					} catch (IOException e) {
						e.printStackTrace();
						fail();
					}
				}
			});
			t.start();
		}

		HashMap<String, Integer> counts = new HashMap<String, Integer>();
		int nullCounts = 0;
		for (int cnt = 0; cnt < nsends * nclients;) {
			SeriDataPackage data = server.read();
			if (data == null) {
				nullCounts++;
				System.out.println("NULL");
				continue;
			}

			if (counts.containsKey(data.getObject())) {
				Integer c = counts.get(data.getObject());
				counts.put((String) data.getObject(), c + 1);
			} else {
				counts.put((String) data.getObject(), 1);
			}
			cnt++;
			System.out.println("Received: " + data.getObject());
		}

		Collection<Integer> values = counts.values();
		for (Integer value : values) {
			int ivalue = value;
			assertEquals(nsends, ivalue);
			System.out.println(value);
		}
		assertEquals(counts.size(), nclients);
		System.out.println(counts.size());

		System.out.println("Finishing");
		server.shutdown();

	}
}
