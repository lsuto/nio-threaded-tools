# Introduction #

This will allow you to seamlessly use non-blocking NIO sockets in a thread pool.

Sample code below.

# Details #
```

	@Test
	public void testSingleThread() throws IOException, InterruptedException {

		String msg = "Simple Test!";
		SeriServer server = new SeriServer(5000, 1);

		SeriClient client = new SeriClient("localhost", 5000);

		client.send(msg);

		SeriDataPackage fromClient = server.read();

		System.out.println("From Client: " + fromClient.getObject());
		assertEquals(msg, fromClient.getObject());

		server.reply(fromClient, msg + msg);

		SeriDataPackage fromServer = client.read();

		System.out.println("From Server: " + fromServer.getObject());

		assertEquals(msg + msg, fromServer.getObject());

		client.shutdown();
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

```