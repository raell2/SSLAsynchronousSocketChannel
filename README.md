### The Java class SSLAsynchronousSocketChannel

In the following, an overview of the Java class `nio2.ssl.SSLAsynchronousSocketChannel` is given. The class enhances the JDK class java.nio.channels.AsynchronousSocketChannel by a layer for processing the SSL/TLS protocol. This layer is transparent to the user. 

**Table of Contents**

- [An AsynchronousSocketChannel with SSL/TLS support](#SSLAsynchronousSocketChannel) 
- [API Documentation](#api)
- [Requirements and Dependencies](#requirements)
- [Jar-Files](#jarfiles)
- [Usage](#usage)
- [Running the Example](#example)
- [Logging](#logging)
- [Design of SSLAsynchronousSocketChannel](#design)

<a name="SSLAsynchronousSocketChannel" />

### An AsynchronousSocketChannel with SSL/TLS support

The channel [AsynchronousSocketChannel](https://docs.oracle.com/javase/10/docs/api/java/nio/channels/AsynchronousSocketChannel.html)  supports asynchronous, non-blocking communication over a TCP/IP network. Its programming model is based on an event handler architecture, i.e. the user defines completion handlers for connecting the channel and writing and reading data to resp. from the channel. If one of 
these events occurs, the corresponding handler's completed method is invoked by the Java runtime. This approach is much more up to date than the previous channel [SocketChannel](https://docs.oracle.com/javase/10/docs/api/java/nio/channels/SocketChannel.html) where the user needed to code all selector stuff by hand. 

However, class [AsynchronousSocketChannel](https://docs.oracle.com/javase/10/docs/api/java/nio/channels/AsynchronousSocketChannel.html)  has a serious downside: **It doesn't support SSL/TLS**. For blocking sockets there are always two versions: one for plaintext and one for SSL/TLS, like [Socket](https://docs.oracle.com/javase/10/docs/api/java/net/Socket.html) and [SSLSocket](https://docs.oracle.com/javase/10/docs/api/javax/net/ssl/SSLSocket.html). Unfortunately, for plaintext-based class [AsynchronousSocketChannel](https://docs.oracle.com/javase/10/docs/api/java/nio/channels/AsynchronousSocketChannel.html), there is no  corresponding SSLAsynchronousSocketChannel class. The only solution that the JDK platform offers 
in this case is to treat SSL/TLS processing by help of the [javax.net.ssl.SSLEngine](https://docs.oracle.com/javase/10/docs/api/javax/net/ssl/SSLEngine.html) API. 
The problem is, that this API is notoriously hard to work with. 

I use asynchronous socket channels in a high-performance web-crawler application. Because the crawler needs to access web sites over http as well as over https, I decided to implement an asynchronous socket channel that can be used for both, plaintext and SSL/TLS with the same user interface such that SSL/TLS processing is completely transparent to the user. 

The channel is named **`nio2.ssl.SSLAsynchronousSocketChannel`**. Its basic properties are: 

 - capable for plaintext as well as for SSL/TLS connections

 - same user interface as JDK's AsynchronousSocketChannel class

 - SSL/TLS processing completely transparent to the user 

 - supports all TLS versions including TLS 1.3 

 - free software under MIT license 

Generally, there are cryptographic limits on the amount of plaintext which can be safely encrypted under a given set of keys. That's why  TLS enables each peer to request a rehandshake (TLS 1.2 and earlier) or a key update (TLS 1.3) in order to renew the keys. Rehandshaking is a difficult task (see [Design](#rehandshake) for some subtleties) , that is often neglected by implementations using the javax.net.ssl.SSLEngine API. However, if not handled properly, results can be unpredicatable. Therefore, particular care was taken for  programming and testing rehandshaking and key updates in class `SSLASynchronousSocketChannel`. 

<a name="api" />

### API Documentation 

There is a comprehensive documentation of the [SSLAsynchronousSocketChannel API](https://raell2.github.io/SSLAsynchronousSocketChannel/docs/api) created by Javadoc on Github at [https://raell2.github.io/SSLAsynchronousSocketChannel/docs/api](https://raell2.github.io/SSLAsynchronousSocketChannel/docs/api). 

<a name="requirements" /> 

### Requirements and Dependencies 

- Java 8 or newer is required. 

- The only dependency is for logging. It uses [SLF4J](https://www.slf4j.org/) as facade and as logger I have chosen [Logback](https://logback.qos.ch). The jar files I worked with are slf4j-api-1.7.26.jar, logback-classic-1.2.3.jar, logback-core-1.2.3.jar. They can be found in the directory [externalFiles](externalFiles) or downloaded on the linked web pages of the corresponding projects. 

<a name="jarfiles" />

### Jar-Files 

The `nio2.ssl.SSLAsynchronousSocketChannel` API is packed in a jar file. There are three versions 
that can be downloaded from [lib](lib). 

|Version                              |Description                                                                  |
|-------------------------------------|-----------------------------------------------------------------------------|
|sslasync-1.0-java12.jar              |Compiled with Openjdk Java 12                                                |
|sslasync-1.0-java12-logback-slf4j.jar|Compiled with Openjdk Java 12, includes logback and slf4j                    |
|sslasync-1.0-java8.jar               |Compiled with Java 8 (jdk1.8.0_181). Note that Java 8 doesn't support TLS 1.3|

Usage of the API and the jar files is explained in the next two sections. 

<a name="usage" />

### Usage 

Usage of the SSLAsynchronousSocketChannel channel is quite similar to that of the JDK's 
AsynchronousSocketChannel class: First we construct a channel group, open the channel 
and connect to the remote server by providing an attachement and a connect handler:  

````Java
String host = "github.com";
int port = 443; 
SSLAsynchronousChannelGroup group = new SSLAsynchronousChannelGroup(); 
SSLAsynchronousSocketChannel channel = SSLAsynchronousSocketChannel.open(group); 
channel.connect(new InetSocketAddress(host, port), attachement, connectHandler);
````

There are two options for opening the channel. In the first option, shown above, the 
channel uses the port number in order to decide if it uses SSL/TLS or plaintext: If the 
port is 443, SSL/TLS will be used, otherwise plaintext. 

The second option tells the channel explicitly to use or not to use SSL/TLS: 

````Java
String host = "localhost";
int port = 8181; 
SSLAsynchronousChannelGroup group = new SSLAsynchronousChannelGroup(); 
SSLAsynchronousSocketChannel channel = SSLAsynchronousSocketChannel.open(group, true); 
channel.connect(new InetSocketAddress(host, port), attachement, connectHandler);
````

If the second parameter of open() is true, SSL/TLS will be used, otherwise plaintext. 

After the connect handler completed, data can be written to and read from the channel.

````Java
channel.write(writeBuffer, 10, TimeUnit.Seconds, writeAttachement, writeHandler); 
channel.read(readBuffer, 0, TimeUnit.Seconds, readAttachement, readHandler);
````
 
The figure is the number of seconds until a timeout occurs. 0 means that there is no timeout constraint.

The channel is a duplex channel, i.e. it is possible to write and read in parallel. However, it isn't allowed to write resp. read as long as the completion handler of a previous write resp. read operation hasn't completed (or failed). Doing so, will throw a WritePendingException resp. ReadPendingException. 

There is a complete use case [HttpGetClient](src/nio2/ssl/examples/HttpGetClient.java) in the package that sends a Http Get request to a server and displays the response on the screen. This application can be taken as a basis for programming with the `SSLAsynchronousSocketChannel`class. The subsequent section shows how to run this example from the jar files. 

<a name="example" />

### Running the Example 

The example [HttpGetClient](src/nio2/ssl/examples/HttpGetClient.java) sends a Http Get request to the server and prints the response onto the screen. By default it connects to http&#58;//w<i></i>ww.github.com, but you can also provide a parameter containing the desired URL. Run `nio2.ssl.examples.HttpGetClient -help` for more information. 

In order to run the example, in its simplest form just execute:  

````
c:\>java -cp path-to-jar\sslasync-1.0-java12-logback-slf4j.jar nio2.ssl.examlples.HttpGetClient 
````

You can also append an URL, like:  

````
c:\>java -cp path-to-jar\sslasync-1.0-java12-logback-slf4j.jar nio2.ssl.examlples.HttpGetClient https://www.oracle.com 
````
###

In the next case we use `sslasync-1.0-java12.jar`. Since this version doesn't include the classes for logback and slf4j, we must put their jar files on the classpath, too. Suppose all jar files are in the same directory. Then the example runs in Windows by:  

````
c:\>set j=path-to-jar-files
c:\>set l=path-to-logback.xml
c:\>java -cp %l%;%j%\logback-classic-1.2.3.jar;%j%\logback-core-1.2.3.jar;%j%\slf4j-api-1.7.26.jar;%j%\sslasync-1.0-java12.jar nio2.ssl.examples.HttpGetClient https://www.oracle.com 
````

In Linux use similarly: 

````
# j=path-to-jar-files
# l=path-to-logback.xml 
# java -cp $l;$j\logback-classic-1.2.3.jar;$j\logback-core-1.2.3.jar;$j\slf4j-api-1.7.26.jar;$j\sslasync-1.0-java12.jar nio2.ssl.examples.HttpGetClient https://www.oracle.com 
````

<a name="logging" /> 

### Logging ###

There are two possibilities for obtaining logging information: 

- **`SSLAsynchronousSocketChannel`** and one more classes from package **`nio2.ssl`** use SLF4J for logging. In the configuration described here, the logger is Logback with its config file [logback.xml](config/logback.xml) stored in directory [config](config). Setting the logging level to `TRACE` provides runtime information about the channel: 

````
<logger name="nio2.ssl.SSLAsynchronousSocketChannel" level="TRACE" additivity="false">
  <appender-ref ref="STDOUT" />
</logger>
````

- In JSSE there is the system property `javax.net.debug`. It can take values `all` and `ssl` (and refinements thereof). Setting this system property prints detailed information on the SSL/TLS processing of the JDK classes. If we modify the example above as follows:

````
c:\>set j=path-to-jar-files
c:\>set l=path-to-logback.xml
c:\>java -Djavax.net.debug=all -cp %l%;%j%\logback-classic-1.2.3.jar;%j%\logback-core-1.2.3.jar;%j%\slf4j-api-1.7.26.jar;%j%\sslasync-1.0-java12.jar nio2.ssl.examples.HttpGetClient https://www.oracle.com 
````
then all JSSE information about SSL/TLS processing are printed. 

More details on this system property can be found in the JSSE documentation: [Debugging Utilities](https://docs.oracle.com/en/java/javase/12/security/java-secure-socket-extension-jsse-reference-guide.html#GUID-31B7E142-B874-46E9-8DD0-4E18EC0EB2CF) 

<a name="design" /> 

### Design of SSLAsynchronousSocketChannel 

The class **`SSLAsynchronousSocketChannel`** uses an instance of the JDK class `AsynchronousSocketChannel` for asynchronous communication. On top there is a new layer that is responsible for SSL/TLS processing like handshaking, rehandshaking / key update, handling post-handshake messages like NewSessionTicket from TLS 1.3 and for encrypting and decrypting application data.  This layer is based on the JDK class `SSLEngine`. 

The image below shows the design when used for transfering data over https protocol. 

![Design of class SSLAsynchronousSocketChannel](images/SSLAsyncDesign.jpg?raw=true "Design SSLAsynchronousSocketChannel")

However, class **`SSLAsynchronousSocketChannel`** can also transfer data as plaintext without SSL/TLS encryption. In this case the class acts just as a wrapper that delegates its method calls to the internal AsynchronousSocketChannel instance. 

<a name="rehandshake" /> 

There is further functionality related to rehandshaking: 

- Rehandshaking can lead to conflicts when a user wants to write data while a write operation (hidden to the user) is just in progress because of a rehandshake. Consider for instance the following scenario: 

1. server sends HelloRequest in order to initiate a rehandshake 
2. user invokes channel.read() in order to read application data from the channel 
3. channel reads the HelloRequest 
4. channel writes ClientHello according to TLS 1.2 protocol 
5. user invokes channel.write() in order to send application data to the server 

In step 5 normally the user would receive a WritePendingException exception because the write process from step 4 might be still in progress. But from the user's point of view, a WritePendingException exception doesn't make sense, because no write operation 
invoked by the user is pending and the SSL/TLS process is hidden from the user. As a solution we save the user's write command from step 5 until the write process from step 4 finished and proceed it afterwards. 

- Similarly, a read operation by the user can conflict with an ongoing read operations that was initiated by a rehandshake. In this case, the user's read call is saved and defered until the active read operation terminated. This process is transparent to the user. 

- Another subtlety is that the channel could receive application data during a rehandshake, while the user hasn't invoked a read command. This happens in the next scenario:  

1. user calls channel.read() in order to read application data 
2. server sends application data followed by a HelloRequest 
3. channel reveives the application data and the HelloRequest 
4. channel returns application data to the user, writes a ClientHello and 
   waits for incoming data from the handshake
5. user requests data from server by calling channel.write()  
6. server sends application data as reply to 5. 

Now the channel receives the application data from step 6, but there is no corresponding channel.read() call by the user. That means the channel has neither a buffer from the user where it can write the decrypted application data to, nor has it a completion handler from the user that can be invoked by the channel. As a solution, the channel saves the decrypted application data and as soon as the user calls channel.read() these data are returned to the user. 

