# Introduction #
I was trying to find a non-blocking multi-threaded socket server in Java and could not find anything awesome. The best I could find was this hack on stackoverflow (http://stackoverflow.com/questions/5862971/java-readobject-with-nio).

I took the hack and basically used it as inspiration for this tools. Enjoy.

# Quick start #
This will allow you to seamlessly use non-blocking NIO sockets in a thread pool.

## Server ##
**Starting the server:**
`SeriServer server = new SeriServer(6001); // port 6001, uses the default 10 threads`

_Starting the server in single thread:_
`SeriServer server = new SeriServer(6001,1);// 1 thread`

Accessing data read from server:
` System.out.println(objectFromClient.getObject().toString()); `

**Sending data back to the client:**
` server.reply(objectFromClient, message); // message must be serializable `



## Client ##

**Starting a client:**
` client = new SeriClient("localhost", 6001); `

**Sending data to server:**
` client.send(message); // message must be serializable `

**Reading message from server:**
` SeriDataPackage objectFromServer = client.read(); `

Accessing data read from server:
` System.out.println(objectFromServer.getObject().toString()); `


## Server processing ##

You can also do automatic processing from the server threads, via the `SeriServer.setProcessor`:

```
final SeriServer server = new SeriServer(5003, 3);
server.setProcessor(new SeriProcessor() {
	public void process(SeriDataPackage pack) {
		try {					
			server.reply(pack, "*" + pack.getObject().toString() + "*");
		} catch (IOException e) {
		}				
		}
	});
				

SeriClient client = new SeriClient("localhost", 5003);
client.send("TEST");
SeriDataPackage response = client.read();

System.out.println(response.getObject());
```

This will output: `*TEST*`