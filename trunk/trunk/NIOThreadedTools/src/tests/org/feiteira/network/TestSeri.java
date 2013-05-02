package tests.org.feiteira.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.apache.log4j.Logger;
import org.feiteira.network.SeriClient;
import org.feiteira.network.SeriDataPackage;
import org.feiteira.network.SeriEventHandler;
import org.feiteira.network.SeriServer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestSeri {
	private static Logger log = Logger.getLogger(TestSeri.class);

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
	public void testThreads() throws IOException, InterruptedException {
		int port = 5100;

		String msg = "Simple Test!";

		System.out.println("Starting server.");
		SeriServer server = new SeriServer(port, 3);

		System.out.println("Starting client.");
		SeriClient client = new SeriClient("localhost", port);

		server.setEventListner(new SeriEventHandler() {

			@Override
			public void messageArrived(SeriDataPackage pack) {
				System.out.println("[Server] Reading message: "
						+ pack.getObject());
				try {

					if (pack.getObject() != null) {
						String msg = pack.getObject().toString();
						SeriServer.reply(pack, msg + msg);
					} else
						SeriServer.reply(pack, null);

				} catch (IOException e) {
					e.printStackTrace();
					fail();
				}

			}

			@Override
			public void shutdownCompleted() {
				// TODO Auto-generated method stub

			}
		});

		log.info("[Client] Sending message: " + msg);
		client.send(msg);
		log.debug("Sent");

		SeriDataPackage fromServer = client.read();

		log.info("From Server: " + fromServer.getObject());

		assertEquals(msg + msg, fromServer.getObject());

		// test sending nulls
		client.send(null);

		assertNull(client.read().getObject());

		server.setEventListner(new SeriEventHandler() {

			@Override
			public void messageArrived(SeriDataPackage pack) {
				int v = (Integer) pack.getObject();
				try {
					SeriServer.reply(pack, new Integer(v * v));
				} catch (IOException e) {
					e.printStackTrace();
					fail();
				}

			}

			@Override
			public void shutdownCompleted() {
				System.out.println("Shutdown event");
			}
		});

		int MX = 100;
		for (int cnt = 0; cnt < MX; cnt++) {
			log.debug("Sending: " + cnt);
			client.send(new Integer(cnt));
			SeriDataPackage pack = client.read();
			assertEquals(cnt * cnt, pack.getObject());
		}

		log.info("Client shutting down");
		client.close();
		log.info("Server shutting down");
		server.shutdown();
	}
}
