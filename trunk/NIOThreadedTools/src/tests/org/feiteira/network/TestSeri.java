package tests.org.feiteira.network;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;

import org.feiteira.network.SeriConnection;
import org.feiteira.network.SeriDataPackage;
import org.feiteira.network.SeriEventHandler;
import org.feiteira.network.SeriServer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestSeri {

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
		SeriConnection client = new SeriConnection("localhost", port);

		server.setProcessor(new SeriEventHandler() {

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

		System.out.println("[Client] Sending message: " + msg);
		client.send(msg);

		SeriDataPackage fromServer = client.read();

		System.out.println("From Server: " + fromServer.getObject());

		assertEquals(msg + msg, fromServer.getObject());

		// test sending nulls
		client.send(null);

		assertNull(client.read().getObject());

		server.setProcessor(new SeriEventHandler() {

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
			client.send(new Integer(cnt));
			SeriDataPackage pack = client.read();
			System.out.println("Client [" + cnt + "]: " + pack.getObject());
			assertEquals(cnt * cnt, pack.getObject());
		}

		System.out.println("Client shutting down");
		client.shutdown();
		System.out.println("Server shutting down");
		server.shutdown();
	}
}
