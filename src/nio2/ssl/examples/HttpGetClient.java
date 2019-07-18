/**
* Copyright (c) 2015-2016, Ralph Ellinger
* All rights reserved.
*
* Permission is hereby granted, free  of charge, to any person obtaining
* a  copy  of this  software  and  associated  documentation files  (the
* "Software"), to  deal in  the Software without  restriction, including
* without limitation  the rights to  use, copy, modify,  merge, publish,
* distribute,  sublicense, and/or sell  copies of  the Software,  and to
* permit persons to whom the Software  is furnished to do so, subject to
* the following conditions:
*
* The  above  copyright  notice  and  this permission  notice  shall  be
* included in all copies or substantial portions of the Software.
*
* THE  SOFTWARE IS  PROVIDED  "AS  IS", WITHOUT  WARRANTY  OF ANY  KIND,
* EXPRESS OR  IMPLIED, INCLUDING  BUT NOT LIMITED  TO THE  WARRANTIES OF
* MERCHANTABILITY,    FITNESS    FOR    A   PARTICULAR    PURPOSE    AND
* NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
* LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
* OF CONTRACT, TORT OR OTHERWISE,  ARISING FROM, OUT OF OR IN CONNECTION
* WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*
*
* Author: Ralph Ellinger
*
**/
package nio2.ssl.examples;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.CompletionHandler;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.InterruptedByTimeoutException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.ReadPendingException;
import java.nio.channels.ShutdownChannelGroupException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.channels.UnsupportedAddressTypeException;
import java.nio.channels.WritePendingException;
import java.util.concurrent.TimeUnit;
import java.util.Scanner;
import javax.net.ssl.SSLException;
import nio2.ssl.SSLAsynchronousChannelGroup;
import nio2.ssl.SSLAsynchronousSocketChannel;
/**
 * The class demonstrates the usage of an ssl asynchronous socket channel by reading a web page. The basic steps are:
 *
 * <ol>
 *   <li>Connect to the remote server</li>
 *   <li>After the connect handler completes, write the GET request to the channel. We also start reading from the channel
 *       (the read process waits non-blocking until data are received).</li>
 *   <li>After the write handler completes, we check if all data for the GET request have been writen. If there are data left,
 *       we write again, otherwise we are done with writing.</li>
 *   <li>After the read handler completes, we print the received data to the screen and read again. This scheme continues
 *       until we receive an end of file or a timeout. Then, we close the channel.</li>
 * </ol>
 *
 */
public class HttpGetClient {

    private static String url = "";
    private static String host = "github.com"; // without protocol, e.g. without "http://" or "https://"
    private static int port = 443;
    private static boolean useSSL = true;
    //private static final String host = "localhost";
    //private static final int port = 8181;

    private static SSLAsynchronousChannelGroup group;

    class ConnectHandler implements CompletionHandler<Void, SSLAsynchronousSocketChannel> {

        @Override
        public void completed(Void v, SSLAsynchronousSocketChannel channel) {

            System.out.println("HttpGetClient. ConnectHandler.completed");

            try {

                // make GET request
                String request = "GET /" + url + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: Keep-Alive\r\n\r\n";
                ByteBuffer writeBuffer = ByteBuffer.wrap(request.getBytes());
                WriteHandler writeHandler = new WriteHandler(channel);
                channel.write(writeBuffer, 10, TimeUnit.SECONDS, writeBuffer, writeHandler);

                // start non-blocking read of the channel
                ByteBuffer readBuffer = ByteBuffer.allocate(16384);
                ReadHandler readHandler = new ReadHandler(channel);
                channel.read(readBuffer, 10, TimeUnit.SECONDS, readBuffer, readHandler);

            } catch(Exception e) {
                e.printStackTrace(System.out);
                finish(channel);
            }
        }

        @Override
        public void failed(Throwable t, SSLAsynchronousSocketChannel channel) {

            System.out.println("HttpGetClient. ConnectHandler.failed");
            t.printStackTrace(System.out);
            finish(channel);
        }
    }

    class WriteHandler implements CompletionHandler<Integer, ByteBuffer> {

        SSLAsynchronousSocketChannel channel;

        WriteHandler(SSLAsynchronousSocketChannel channel) {
            this.channel = channel;
        }

        @Override
        public void completed(Integer bytesWritten, ByteBuffer buffer) {

            System.out.println("HttpGetClient. WriteHandler.completed bytesWritten: " + bytesWritten);

            if (buffer.hasRemaining()) {
                // write again
                try {
                    channel.write(buffer, 10, TimeUnit.SECONDS, buffer, this);
                } catch(IllegalArgumentException |
                        NotYetConnectedException | 
                        WritePendingException | 
                        ShutdownChannelGroupException | 
                        SSLException e) {
                    e.printStackTrace(System.out);
                    finish(channel);
                }
            }
        }

        @Override
        public void failed(Throwable t, ByteBuffer buffer) {

            System.out.println("HttpGetClient. WriteHandler.failed");
            t.printStackTrace(System.out);
            finish(channel);
        }
    }

    class ReadHandler implements CompletionHandler<Integer, ByteBuffer> {

        SSLAsynchronousSocketChannel channel;
        int readCount;

        ReadHandler(SSLAsynchronousSocketChannel channel) {
            this.channel = channel;
            readCount = 0;
        }

        @Override
        public void completed(Integer bytesRead, ByteBuffer buffer) {

            System.out.println("HttpGetClient. ReadHandler.completed bytesRead: " + bytesRead);

            try {

                if (bytesRead > 0) {

                    // display data on screen
                    buffer.flip();
                    byte[] b = new byte[buffer.limit()];
                    buffer.get(b);
                    System.out.println(new String(b));

                    // read again
                    readCount = 0;
                    buffer.clear();
                    channel.read(buffer, 10, TimeUnit.SECONDS, buffer, this);

                } else if (bytesRead == 0) {

                    // try again if max isn't reached
                    readCount++;
                    if (readCount <= 3) {
                        channel.read(buffer, 10, TimeUnit.SECONDS, buffer, this);
                    } else {
                        System.out.println("HttpGetClient. ReadHandler.completed: Repeatedly no bytes read - we close the channel");
                        finish(channel);
                    }

                } else { // bytesRead < 0 -> EOF
                    finish(channel);
                }

            } catch(IllegalArgumentException | 
                    NotYetConnectedException | 
                    ReadPendingException | 
                    ShutdownChannelGroupException e) {
                e.printStackTrace(System.out);
                finish(channel);
            }

            System.out.println("HttpGetClient. end read()");

        }

        @Override 
        public void failed(Throwable t, ByteBuffer buffer) {
          if (t instanceof InterruptedByTimeoutException) {
            finish(channel);
          } else {
            System.out.println("HttpGetClient. ReadHandler.failed");
            t.printStackTrace(System.out);
            finish(channel);
          }
        }
    }


    void finish(SSLAsynchronousSocketChannel channel) {

        if (group != null) {
            try {
                group.shutdownNow();
            } catch(IOException e) {
                // there is nothing we can do here
                e.printStackTrace(System.out);
            }
        }
        channel.shutdown();
        group.shutdown();
    }

    void run() {

        SSLAsynchronousSocketChannel channel = null;

        try {
            group = new SSLAsynchronousChannelGroup();
            channel = SSLAsynchronousSocketChannel.open(group, useSSL);
            ConnectHandler c = new ConnectHandler();

            // Connect the channel and start the asynchronous processing chain
            channel.connect(new InetSocketAddress(host, port), channel, new ConnectHandler());
            Thread.sleep(5000);

        } catch(UnresolvedAddressException |
                UnsupportedAddressTypeException | 
                AlreadyConnectedException | 
                ConnectionPendingException | 
                SecurityException | 
                IOException | 
                InterruptedException e) {
            System.out.println("HttpGetClient. run() failed.");
            e.printStackTrace(System.out);
            finish(channel);
        }
    }

    private static String decomposeInput(String arg) {

        URI uri;
        try {
            uri = URI.create(arg);
        } catch(IllegalArgumentException e) {
            return e.getMessage();
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            return "Illegal scheme.";
        }

        host = uri.getHost();
        if (host == null) {
            return "host name is empty";
        }
        useSSL = scheme.equals("https"); 

        port = uri.getPort();
        if (port < 0) {
            port = scheme.equals("http")? 80 : 443;
        }

        String path = uri.getPath();
        String frag = uri.getFragment();
        String query = uri.getQuery();
        StringBuilder sb = new StringBuilder(arg.length());

        if (path == null || path.isEmpty()) {
            sb.append('/');
        } else {
            if (!path.startsWith("/")) {
                sb.append('/');
            }
            sb.append(path);
        }

        if (query != null) {
            sb.append('?');
            sb.append(query);
        }
        if (frag != null) {
            sb.append('#');
            sb.append(frag);
        }
        url = sb.toString();

        return null;

    }


    public static void main(String[] args) {

        if (args.length > 0) {

            String arg = args[0].trim();
            while(true) {

                if (arg.equalsIgnoreCase("help")   ||
                    arg.equalsIgnoreCase("-help")  ||
                    arg.equalsIgnoreCase("--help") ||
                    arg.equals("?")) {
                    System.out.println("Class HttpGetClient sends a Http Get request to the server. Input can be empty or a URL:");
                    System.out.println("- If the input is empty, the Get request will be sent to https://github.com.");
                    System.out.println("- The format of an URL is:\n");
                    System.out.println("     scheme://host[:port][/path][?query][#fragment]\n");
                    System.out.println("The parts in brackets [...] are optional and the scheme is either \"http\" or \"https\".");
                    return;
                }

                String msg = decomposeInput(arg);
                if (msg != null) {
                    System.out.println("Illegal URL format: " + msg);
                    System.out.println("Please, reenter the URL");
                    Scanner sc = new Scanner(System.in);
                    arg = sc.next();

                } else {
                    break;
                }
            }
        }

        new HttpGetClient().run();
    }
}

