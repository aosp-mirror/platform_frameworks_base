/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.core;

import android.util.Log;

import java.io.*;
import java.lang.Thread;
import java.net.*;
import java.util.*;

/**
 * TestWebServer is a simulated controllable test server that
 * can respond to requests from HTTP clients.
 *
 * The server can be controlled to change how it reacts to any
 * requests, and can be told to simulate various events (such as
 * network failure) that would happen in a real environment.
 */
class TestWebServer implements HttpConstants {

    /* static class data/methods */

    /* The ANDROID_LOG_TAG */
    private final static String LOGTAG = "httpsv";

    /* Where worker threads stand idle */
    Vector threads = new Vector();

    /* List of all active worker threads */
    Vector activeThreads = new Vector();

    /* timeout on client connections */
    int timeout = 0;

    /* max # worker threads */
    int workers = 5;

    /* Default port for this server to listen on */
    final static int DEFAULT_PORT = 8080;

    /* Default socket timeout value */
    final static int DEFAULT_TIMEOUT = 5000;

    /* Version string (configurable) */
    protected String HTTP_VERSION_STRING = "HTTP/1.1";

    /* Indicator for whether this server is configured as a HTTP/1.1
     * or HTTP/1.0 server
     */
    private boolean http11 = true;

    /* The thread handling new requests from clients */
    private AcceptThread acceptT;

    /* timeout on client connections */
    int mTimeout;

    /* Server port */
    int mPort;

    /* Switch on/off logging */
    boolean mLog = false;

    /* If set, this will keep connections alive after a request has been
     * processed.
     */
    boolean keepAlive = true;

    /* If set, this will cause response data to be sent in 'chunked' format */
    boolean chunked = false;

    /* If set, this will indicate a new redirection host */
    String redirectHost = null;

    /* If set, this indicates the reason for redirection */
    int redirectCode = -1;

    /* Set the number of connections the server will accept before shutdown */
    int acceptLimit = 100;

    /* Count of number of accepted connections */
    int acceptedConnections = 0;

    public TestWebServer() {
    }

    /**
     * Initialize a new server with default port and timeout.
     * @param log Set true if you want trace output
     */
    public void initServer(boolean log) throws Exception {
        initServer(DEFAULT_PORT, DEFAULT_TIMEOUT, log);
    }

    /**
     * Initialize a new server with default timeout.
     * @param port Sets the server to listen on this port
     * @param log Set true if you want trace output
     */
    public void initServer(int port, boolean log) throws Exception {
        initServer(port, DEFAULT_TIMEOUT, log);
    }

    /**
     * Initialize a new server with default port and timeout.
     * @param port Sets the server to listen on this port
     * @param timeout Indicates the period of time to wait until a socket is
     *                closed
     * @param log Set true if you want trace output
     */
    public void initServer(int port, int timeout, boolean log) throws Exception {
        mPort = port;
        mTimeout = timeout;
        mLog = log;
        keepAlive = true;

        if (acceptT == null) {
            acceptT = new AcceptThread();
            acceptT.init();
            acceptT.start();
        }
    }

    /**
     * Print to the log file (if logging enabled)
     * @param s String to send to the log
     */
    protected void log(String s) {
        if (mLog) {
            Log.d(LOGTAG, s);
        }
    }

    /**
     * Set the server to be an HTTP/1.0 or HTTP/1.1 server.
     * This should be called prior to any requests being sent
     * to the server.
     * @param set True for the server to be HTTP/1.1, false for HTTP/1.0
     */
    public void setHttpVersion11(boolean set) {
        http11 = set;
        if (set) {
            HTTP_VERSION_STRING = "HTTP/1.1";
        } else {
            HTTP_VERSION_STRING = "HTTP/1.0";
        }
    }

    /**
     * Call this to determine whether server connection should remain open
     * @param value Set true to keep connections open after a request
     *              completes
     */
    public void setKeepAlive(boolean value) {
        keepAlive = value;
    }

    /**
     * Call this to indicate whether chunked data should be used
     * @param value Set true to make server respond with chunk encoded
     *              content data.
     */
    public void setChunked(boolean value) {
        chunked = value;
    }

    /**
     * Call this to specify the maximum number of sockets to accept
     * @param limit The number of sockets to accept
     */
    public void setAcceptLimit(int limit) {
        acceptLimit = limit;
    }

    /**
     * Call this to indicate redirection port requirement.
     * When this value is set, the server will respond to a request with
     * a redirect code with the Location response header set to the value
     * specified.
     * @param redirect The location to be redirected to
     * @param redirectCode The code to send when redirecting
     */
    public void setRedirect(String redirect, int code) {
        redirectHost = redirect;
        redirectCode = code;
        log("Server will redirect output to "+redirect+" code "+code);
    }

    /**
     * Cause the thread accepting connections on the server socket to close
     */
    public void close() {
        /* Stop the Accept thread */
        if (acceptT != null) {
            log("Closing AcceptThread"+acceptT);
            acceptT.close();
            acceptT = null;
        }
    }
    /**
     * The AcceptThread is responsible for initiating worker threads
     * to handle incoming requests from clients.
     */
    class AcceptThread extends Thread {

        ServerSocket ss = null;
        boolean running = false;

        public void init() {
            // Networking code doesn't support ServerSocket(port) yet
            InetSocketAddress ia = new InetSocketAddress(mPort);
            while (true) {
                try {
                    ss = new ServerSocket();
                    // Socket timeout functionality is not available yet
                    //ss.setSoTimeout(5000);
                    ss.setReuseAddress(true);
                    ss.bind(ia);
                    break;
                } catch (IOException e) {
                    log("IOException in AcceptThread.init()");                    
                    e.printStackTrace();
                    // wait and retry
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e1) {
                        // TODO Auto-generated catch block
                        e1.printStackTrace();
                    }
                }
            }
        }

        /**
         * Main thread responding to new connections
         */
        public synchronized void run() {
            running = true;
            try {
                while (running) {
                    // Log.d(LOGTAG, "TestWebServer run() calling accept()");
                    Socket s = ss.accept();
                    acceptedConnections++;
                    if (acceptedConnections >= acceptLimit) {
                        running = false;
                    }

                    Worker w = null;
                    synchronized (threads) {
                        if (threads.isEmpty()) {
                            Worker ws = new Worker();
                            ws.setSocket(s);
                            activeThreads.addElement(ws);
                            (new Thread(ws, "additional worker")).start();
                        } else {
                            w = (Worker) threads.elementAt(0);
                            threads.removeElementAt(0);
                            w.setSocket(s);
                        }
                    }
                }
            } catch (SocketException e) {
                log("SocketException in AcceptThread: probably closed during accept");
                running = false;
            } catch (IOException e) {
                log("IOException in AcceptThread");
                e.printStackTrace();
                running = false;
            }
            log("AcceptThread terminated" + this);
        }

        // Close this socket
        public void close() {
            try {
                running = false;
                /* Stop server socket from processing further. Currently
                   this does not cause the SocketException from ss.accept
                   therefore the acceptLimit functionality has been added
                   to circumvent this limitation */
                ss.close();

                // Stop worker threads from continuing
                for (Enumeration e = activeThreads.elements(); e.hasMoreElements();) {
                    Worker w = (Worker)e.nextElement();
                    w.close();
                }
                activeThreads.clear();

            } catch (IOException e) {
                /* We are shutting down the server, so we expect
                 * things to die. Don't propagate.
                 */
                log("IOException caught by server socket close");
            }
        }
    }

    // Size of buffer for reading from the connection
    final static int BUF_SIZE = 2048;

    /* End of line byte sequence */
    static final byte[] EOL = {(byte)'\r', (byte)'\n' };

    /**
     * The worker thread handles all interactions with a current open
     * connection. If pipelining is turned on, this will allow this
     * thread to continuously operate on numerous requests before the
     * connection is closed.
     */
    class Worker implements HttpConstants, Runnable {

        /* buffer to use to hold request data */
        byte[] buf;

        /* Socket to client we're handling */
        private Socket s;

        /* Reference to current request method ID */
        private int requestMethod;

        /* Reference to current requests test file/data */
        private String testID;

        /* Reference to test number from testID */
        private int testNum;

        /* Reference to whether new request has been initiated yet */
        private boolean readStarted;

        /* Indicates whether current request has any data content */
        private boolean hasContent = false;

        boolean running = false;

        /* Request headers are stored here */
        private Hashtable<String, String> headers = new Hashtable<String, String>();

        /* Create a new worker thread */
        Worker() {
            buf = new byte[BUF_SIZE];
            s = null;
        }

        /**
         * Called by the AcceptThread to unblock this Worker to process
         * a request.
         * @param s The socket on which the connection has been made
         */
        synchronized void setSocket(Socket s) {
            this.s = s;
            notify();
        }

        /**
         * Called by the accept thread when it's closing. Potentially unblocks
         * the worker thread to terminate properly
         */
        synchronized void close() {
            running = false;
            notify();
        }

        /**
         * Main worker thread. This will wait until a request has
         * been identified by the accept thread upon which it will
         * service the thread.
         */
        public synchronized void run() {
            running = true;
            while(running) {
                if (s == null) {
                    /* nothing to do */
                    try {
                        log(this+" Moving to wait state");
                        wait();
                    } catch (InterruptedException e) {
                        /* should not happen */
                        continue;
                    }
                    if (!running) break;
                }
                try {
                    handleClient();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                /* go back in wait queue if there's fewer
                 * than numHandler connections.
                 */
                s = null;
                Vector pool = threads;
                synchronized (pool) {
                    if (pool.size() >= workers) {
                        /* too many threads, exit this one */
                        activeThreads.remove(this);
                        return;
                    } else {
                        pool.addElement(this);
                    }
                }
            }
            log(this+" terminated");
        }

        /**
         * Zero out the buffer from last time
         */
        private void clearBuffer() {
            for (int i = 0; i < BUF_SIZE; i++) {
                buf[i] = 0;
            }
        }

        /**
         * Utility method to read a line of data from the input stream
         * @param is Inputstream to read
         * @return number of bytes read
         */
        private int readOneLine(InputStream is) {

            int read = 0;

            clearBuffer();
            try {
                log("Reading one line: started ="+readStarted+" avail="+is.available());
                while ((!readStarted) || (is.available() > 0)) {
                    int data = is.read();
                    // We shouldn't get EOF but we need tdo check
                    if (data == -1) {
                        log("EOF returned");
                        return -1;
                    }

                    buf[read] = (byte)data;

                    System.out.print((char)data);

                    readStarted = true;
                    if (buf[read++]==(byte)'\n') {
                        System.out.println();
                        return read;
                    }
                }
            } catch (IOException e) {
                log("IOException from readOneLine");
                e.printStackTrace();
            }
            return read;
        }

        /**
         * Read a chunk of data
         * @param is Stream from which to read data
         * @param length Amount of data to read
         * @return number of bytes read
         */
        private int readData(InputStream is, int length) {
            int read = 0;
            int count;
            // At the moment we're only expecting small data amounts
            byte[] buf = new byte[length];

            try {
                while (is.available() > 0) {
                    count = is.read(buf, read, length-read);
                    read += count;
                }
            } catch (IOException e) {
                log("IOException from readData");
                e.printStackTrace();
            }
            return read;
        }

        /**
         * Read the status line from the input stream extracting method
         * information.
         * @param is Inputstream to read
         * @return number of bytes read
         */
        private int parseStatusLine(InputStream is) {
            int index;
            int nread = 0;

            log("Parse status line");
            // Check for status line first
            nread = readOneLine(is);
            // Bomb out if stream closes prematurely
            if (nread == -1) {
                requestMethod = UNKNOWN_METHOD;
                return -1;
            }

            if (buf[0] == (byte)'G' &&
                buf[1] == (byte)'E' &&
                buf[2] == (byte)'T' &&
                buf[3] == (byte)' ') {
                requestMethod = GET_METHOD;
                log("GET request");
                index = 4;
            } else if (buf[0] == (byte)'H' &&
                       buf[1] == (byte)'E' &&
                       buf[2] == (byte)'A' &&
                       buf[3] == (byte)'D' &&
                       buf[4] == (byte)' ') {
                requestMethod = HEAD_METHOD;
                log("HEAD request");
                index = 5;
            } else if (buf[0] == (byte)'P' &&
                       buf[1] == (byte)'O' &&
                       buf[2] == (byte)'S' &&
                       buf[3] == (byte)'T' &&
                       buf[4] == (byte)' ') {
                requestMethod = POST_METHOD;
                log("POST request");
                index = 5;
            } else {
                // Unhandled request
                requestMethod = UNKNOWN_METHOD;
                return -1;
            }

            // A valid method we understand
            if (requestMethod > UNKNOWN_METHOD) {
                // Read file name
                int i = index;
                while (buf[i] != (byte)' ') {
                    // There should be HTTP/1.x at the end
                    if ((buf[i] == (byte)'\n') || (buf[i] == (byte)'\r')) {
                        requestMethod = UNKNOWN_METHOD;
                        return -1;
                    }
                    i++;
                }

                testID = new String(buf, 0, index, i-index);
                if (testID.startsWith("/")) {
                    testID = testID.substring(1);
                }

                return nread;
            }
            return -1;
        }

        /**
         * Read a header from the input stream
         * @param is Inputstream to read
         * @return number of bytes read
         */
        private int parseHeader(InputStream is) {
            int index = 0;
            int nread = 0;
            log("Parse a header");
            // Check for status line first
            nread = readOneLine(is);
            // Bomb out if stream closes prematurely
            if (nread == -1) {
                requestMethod = UNKNOWN_METHOD;
                return -1;
            }
            // Read header entry 'Header: data'
            int i = index;
            while (buf[i] != (byte)':') {
                // There should be an entry after the header

                if ((buf[i] == (byte)'\n') || (buf[i] == (byte)'\r')) {
                    return UNKNOWN_METHOD;
                }
                i++;
            }

            String headerName = new String(buf, 0, i);
            i++; // Over ':'
            while (buf[i] == ' ') {
                i++;
            }
            String headerValue = new String(buf, i, nread-1);

            headers.put(headerName, headerValue);
            return nread;
        }

        /**
         * Read all headers from the input stream
         * @param is Inputstream to read
         * @return number of bytes read
         */
        private int readHeaders(InputStream is) {
            int nread = 0;
            log("Read headers");
            // Headers should be terminated by empty CRLF line
            while (true) {
                int headerLen = 0;
                headerLen = parseHeader(is);
                if (headerLen == -1)
                    return -1;
                nread += headerLen;
                if (headerLen <= 2) {
                    return nread;
                }
            }
        }

        /**
         * Read content data from the input stream
         * @param is Inputstream to read
         * @return number of bytes read
         */
        private int readContent(InputStream is) {
            int nread = 0;
            log("Read content");
            String lengthString = headers.get(requestHeaders[REQ_CONTENT_LENGTH]);
            int length = new Integer(lengthString).intValue();

            // Read content
            length = readData(is, length);
            return length;
        }

        /**
         * The main loop, reading requests.
         */
        void handleClient() throws IOException {
            InputStream is = new BufferedInputStream(s.getInputStream());
            PrintStream ps = new PrintStream(s.getOutputStream());
            int nread = 0;

            /* we will only block in read for this many milliseconds
             * before we fail with java.io.InterruptedIOException,
             * at which point we will abandon the connection.
             */
            s.setSoTimeout(mTimeout);
            s.setTcpNoDelay(true);

            do {
                nread = parseStatusLine(is);
                if (requestMethod != UNKNOWN_METHOD) {

                    // If status line found, read any headers
                    nread = readHeaders(is);

                    // Then read content (if any)
                    // TODO handle chunked encoding from the client
                    if (headers.get(requestHeaders[REQ_CONTENT_LENGTH]) != null) {
                        nread = readContent(is);
                    }
                } else {
                    if (nread > 0) {
                        /* we don't support this method */
                        ps.print(HTTP_VERSION_STRING + " " + HTTP_BAD_METHOD +
                                 " unsupported method type: ");
                        ps.write(buf, 0, 5);
                        ps.write(EOL);
                        ps.flush();
                    } else {
                    }
                    if (!keepAlive || nread <= 0) {
                        headers.clear();
                        readStarted = false;

                        log("SOCKET CLOSED");
                        s.close();
                        return;
                    }
                }

                // Reset test number prior to outputing data
                testNum = -1;

                // Write out the data
                printStatus(ps);
                printHeaders(ps);

                // Write line between headers and body
                psWriteEOL(ps);

                // Write the body
                if (redirectCode == -1) {
                    switch (requestMethod) {
                        case GET_METHOD:
                            if ((testNum < 0) || (testNum > TestWebData.tests.length - 1)) {
                                send404(ps);
                            } else {
                                sendFile(ps);
                            }
                            break;
                        case HEAD_METHOD:
                            // Nothing to do
                            break;
                        case POST_METHOD:
                            // Post method write body data
                            if ((testNum > 0) || (testNum < TestWebData.tests.length - 1)) {
                                sendFile(ps);
                            }

                            break;
                        default:
                            break;
                    }
                } else { // Redirecting
                    switch (redirectCode) {
                        case 301:
                            // Seems 301 needs a body by neon (although spec
                            // says SHOULD).
                            psPrint(ps, TestWebData.testServerResponse[TestWebData.REDIRECT_301]);
                            break;
                        case 302:
                            //
                            psPrint(ps, TestWebData.testServerResponse[TestWebData.REDIRECT_302]);
                            break;
                        case 303:
                            psPrint(ps, TestWebData.testServerResponse[TestWebData.REDIRECT_303]);
                            break;
                        case 307:
                            psPrint(ps, TestWebData.testServerResponse[TestWebData.REDIRECT_307]);
                            break;
                        default:
                            break;
                    }
                }

                ps.flush();

                // Reset for next request
                readStarted = false;
                headers.clear();

            } while (keepAlive);

            log("SOCKET CLOSED");
            s.close();
        }

        // Print string to log and output stream
        void psPrint(PrintStream ps, String s) throws IOException {
            log(s);
            ps.print(s);
        }

        // Print bytes to log and output stream
        void psWrite(PrintStream ps, byte[] bytes, int len) throws IOException {
            log(new String(bytes));
            ps.write(bytes, 0, len);
        }

        // Print CRLF to log and output stream
        void psWriteEOL(PrintStream ps) throws IOException {
            log("CRLF");
            ps.write(EOL);
        }


        // Print status to log and output stream
        void printStatus(PrintStream ps) throws IOException {
            // Handle redirects first.
            if (redirectCode != -1) {
                log("REDIRECTING TO "+redirectHost+" status "+redirectCode);
                psPrint(ps, HTTP_VERSION_STRING + " " + redirectCode +" Moved permanently");
                psWriteEOL(ps);
                psPrint(ps, "Location: " + redirectHost);
                psWriteEOL(ps);
                return;
            }


            if (testID.startsWith("test")) {
                testNum = Integer.valueOf(testID.substring(4))-1;
            }

            if ((testNum < 0) || (testNum > TestWebData.tests.length - 1)) {
                psPrint(ps, HTTP_VERSION_STRING + " " + HTTP_NOT_FOUND + " not found");
                psWriteEOL(ps);
            }  else {
                psPrint(ps, HTTP_VERSION_STRING + " " + HTTP_OK+" OK");
                psWriteEOL(ps);
            }

            log("Status sent");
        }
        /**
         * Create the server response and output to the stream
         * @param ps The PrintStream to output response headers and data to
         */
        void printHeaders(PrintStream ps) throws IOException {
            psPrint(ps,"Server: TestWebServer"+mPort);
            psWriteEOL(ps);
            psPrint(ps, "Date: " + (new Date()));
            psWriteEOL(ps);
            psPrint(ps, "Connection: " + ((keepAlive) ? "Keep-Alive" : "Close"));
            psWriteEOL(ps);

            // Yuk, if we're not redirecting, we add the file details
            if (redirectCode == -1) {

                if (!TestWebData.testParams[testNum].testDir) {
                    if (chunked) {
                        psPrint(ps, "Transfer-Encoding: chunked");
                    } else {
                        psPrint(ps, "Content-length: "+TestWebData.testParams[testNum].testLength);
                    }
                    psWriteEOL(ps);

                    psPrint(ps,"Last Modified: " + (new
                                                    Date(TestWebData.testParams[testNum].testLastModified)));
                    psWriteEOL(ps);

                    psPrint(ps, "Content-type: " + TestWebData.testParams[testNum].testType);
                    psWriteEOL(ps);
                } else {
                    psPrint(ps, "Content-type: text/html");
                    psWriteEOL(ps);
                }
            } else {
                // Content-length of 301, 302, 303, 307 are the same.
                psPrint(ps, "Content-length: "+(TestWebData.testServerResponse[TestWebData.REDIRECT_301]).length());
                psWriteEOL(ps);
                psWriteEOL(ps);
            }
            log("Headers sent");

        }

        /**
         * Sends the 404 not found message
         * @param ps The PrintStream to write to
         */
        void send404(PrintStream ps) throws IOException {
            ps.println("Not Found\n\n"+
                       "The requested resource was not found.\n");
        }

        /**
         * Sends the data associated with the headers
         * @param ps The PrintStream to write to
         */
        void sendFile(PrintStream ps) throws IOException {
            // For now just make a chunk with the whole of the test data
            // It might be worth making this multiple chunks for large
            // test data to test multiple chunks.
            int dataSize = TestWebData.tests[testNum].length;
            if (chunked) {
                psPrint(ps, Integer.toHexString(dataSize));
                psWriteEOL(ps);
                psWrite(ps, TestWebData.tests[testNum], dataSize);
                psWriteEOL(ps);
                psPrint(ps, "0");
                psWriteEOL(ps);
                psWriteEOL(ps);
            } else {
                psWrite(ps, TestWebData.tests[testNum], dataSize);
            }
        }
    }
}
