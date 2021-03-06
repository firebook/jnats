/*******************************************************************************
 * Copyright (c) 2015-2016 Apcera Inc. All rights reserved. This program and the accompanying
 * materials are made available under the terms of the MIT License (MIT) which accompanies this
 * distribution, and is available at http://opensource.org/licenses/MIT
 *******************************************************************************/

package io.nats.client;

import static io.nats.client.ConnectionImpl.DEFAULT_BUF_SIZE;
import static io.nats.client.ConnectionImpl.PING_PROTO;
import static io.nats.client.ConnectionImpl.PONG_PROTO;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

class TcpConnectionMock extends TcpConnection implements Runnable, AutoCloseable {
    final Logger logger = LoggerFactory.getLogger(TcpConnectionMock.class);

    static final Charset encoding = Charset.forName("UTF-8");

    ExecutorService executor = null;

    volatile boolean shutdown = false;

    static final String defaultInfo =
            "INFO {\"server_id\":\"a1c9cf0c66c3ea102c600200d441ad8e\",\"version\":\"0.7.2\",\"go\":"
                    + "\"go1.4.2\",\"host\":\"0.0.0.0\",\"port\":4222,\"auth_required\":false,"
                    + "\"ssl_required\":false,\"tls_required\":false,\"tls_verify\":false,"
                    + "\"max_payload\":1048576}";

    static final String defaultAsyncInfo =
            "INFO {\"server_id\":\"a1c9cf0c66c3ea102c600200d441ad8e\",\"version\":\"0.7.2\",\"go\":"
                    + "\"go1.4.2\",\"host\":\"0.0.0.0\",\"port\":4222,\"auth_required\":false,"
                    + "\"ssl_required\":false,\"tls_required\":false,\"tls_verify\":false,"
                    + "\"connect_urls\":[\"localhost:5222\"]," + "\"max_payload\":1048576}";

    ReentrantLock mu = new ReentrantLock();
    // Socket client = null;
    char[] buffer = new char[ConnectionImpl.DEFAULT_BUF_SIZE];

    // for the client
    // private InputStream readBufferedStream;
    // private OutputStream writeBufferedStream;

    private InputStream in;
    private OutputStream out;

    private InetSocketAddress addr = null;
    // private int timeout = 0;

    PipedInputStream readStream = null;
    PipedOutputStream writeStream = null;

    BufferedReader br = null;
    OutputStream bw = null;

    String control = null;

    Map<String, Integer> subs = new ConcurrentHashMap<String, Integer>();
    Map<String, ArrayList<Object>> groups = new ConcurrentHashMap<String, ArrayList<Object>>();

    boolean badWriter = false;
    boolean badReader = false;

    private BufferedReader isr = null;
    private BufferedInputStream bis = null;
    private BufferedOutputStream bos = null;

    ServerInfo serverInfo = ServerInfo.createFromWire(defaultInfo);

    ClientConnectInfo connectInfo;

    private boolean sendNullPong;

    private boolean sendGenericError;

    private boolean sendAuthorizationError;

    private boolean sendTlsError;

    private boolean closeStream;

    private boolean noInfo;

    private boolean tlsRequired;

    private boolean openFailure;

    private boolean noPongs;

    private boolean throwTimeoutException;

    private boolean verboseNoOK;

    /*
     * (non-Javadoc)
     * 
     * @see io.nats.client.TcpConnection#open(java.lang.String, int, int)
     */
    @Override
    void open(String host, int port, int timeoutMillis) throws IOException {
        mu.lock();
        try {
            client = mock(Socket.class);
            when(client.getInetAddress()).thenReturn(InetAddress.getByName(host));
            when(client.getPort()).thenReturn(port);
            when(client.isConnected()).thenReturn(false);
            when(client.isClosed()).thenReturn(false);

            this.addr = new InetSocketAddress(host, port);
            logger.trace("opening TcpConnectionMock for {}:{}", addr.getHostName(), addr.getPort());

            if (openFailure) {
                throw new IOException("Mock: Connection refused");
            }

            writeStream = new PipedOutputStream();
            in = new PipedInputStream(writeStream, DEFAULT_BUF_SIZE);

            readStream = new PipedInputStream(DEFAULT_BUF_SIZE);
            out = new PipedOutputStream(readStream);
            isr = null;

            bw = new BufferedOutputStream(out, DEFAULT_BUF_SIZE);

            if (!shutdown) {
                if (executor != null) {
                    executor.shutdownNow();
                    executor = null;
                }
                executor = Executors.newCachedThreadPool(new NatsThreadFactory("mockserver"));
                executor.execute(this);
                logger.trace("Thread started");
            }
            when(client.isConnected()).thenReturn(true);
            when(client.isClosed()).thenReturn(false);
            logger.trace("TcpConnectionMock is open and initialized");
        } finally {
            mu.unlock();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see io.nats.client.TcpConnection#setConnectTimeout(int)
     */
    @Override
    protected void setConnectTimeout(int value) {
        super.setConnectTimeout(value);
    }

    /*
     * (non-Javadoc)
     * 
     * @see io.nats.client.TcpConnection#isSetup()
     */
    @Override
    boolean isSetup() {
        return client.isConnected();
    }

    /*
     * (non-Javadoc)
     * 
     * @see io.nats.client.TcpConnection#teardown()
     */
    @Override
    void teardown() {
        logger.trace("in teardown()");

        super.teardown();
        // if (client != null)
        // when(client.isClosed()).thenReturn(true);
    }

    void setBufferedInputStreamReader(BufferedReader isr) {
        this.isr = isr;
    }

    /*
     * (non-Javadoc)
     * 
     * @see io.nats.client.TcpConnection#getInputStreamReader()
     */
    @Override
    BufferedReader getBufferedReader() {
        if (badReader) {
            isr = mock(BufferedReader.class);
            try {
                doThrow(new IOException("Stuff")).when(isr).readLine();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        } else if (isr == null) {
            if (readStream == null) {
                logger.trace("NULL readstream");
            } else {
                isr = new BufferedReader(new InputStreamReader(bis));
            }
        }
        return isr;
    }

    /*
     * (non-Javadoc)
     * 
     * @see io.nats.client.TcpConnection#getReadBufferedStream(int)
     */
    @Override
    BufferedInputStream getInputStream(int size) {
        bis = new BufferedInputStream(readStream, size);
        return bis;
    }

    /*
     * (non-Javadoc)
     * 
     * @see io.nats.client.TcpConnection#getWriteBufferedStream(int)
     */
    @Override
    BufferedOutputStream getOutputStream(int size) {
        // return new BufferedOutputStream(writeStream, size);
        if (badWriter) {
            bos = mock(BufferedOutputStream.class);
            try {
                doThrow(new IOException("Mock write I/O error")).when(bos).write(any(byte[].class));
                doThrow(new IOException("Mock write I/O error")).when(bos).flush();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        } else if (bos == null) {
            // bos = writeStream;
            bos = new BufferedOutputStream(writeStream);
        }
        return bos;
    }

    /*
     * (non-Javadoc)
     * 
     * @see io.nats.client.TcpConnection#isConnected()
     */
    @Override
    boolean isConnected() {
        return client != null && client.isConnected();
    }

    /*
     * (non-Javadoc)
     * 
     * @see io.nats.client.TcpConnection#isDataAvailable()
     */
    @Override
    boolean isDataAvailable() {
        boolean rv = false;
        try {
            rv = super.isDataAvailable();
        } catch (IOException e) {
            // ignore
        }
        return rv;
    }

    void shutdown() {
        this.shutdown = true;
    }

    @Override
    public void run() {
        InputStreamReader is = new InputStreamReader(in);
        BufferedReader br = new BufferedReader(is);

        logger.trace("started");

        try {
            if (!noInfo) {
                if (tlsRequired) {
                    String str =
                            defaultInfo.replace("\"tls_required\":false", "\"tls_required\":true");
                    serverInfo = ServerInfo.createFromWire(str);
                }
                bw.write(String.format("%s\r\n", serverInfo.toString()).getBytes());
                bw.flush();
                logger.trace("=> {}", serverInfo.toString().trim());
            } else {
                String fakeOpStr = "FOO BAR\r\n";
                byte[] fakeOp = fakeOpStr.getBytes();
                bw.write(fakeOp);
                bw.flush();
                logger.trace("=> {}", fakeOpStr.trim());
            }

            while (!shutdown) {
                control = br.readLine();
                if (control == null) {
                    break;
                }

                logger.trace("<= {}", control);

                if (control.equalsIgnoreCase(PING_PROTO.trim())) {
                    byte[] response = null;
                    String logMsg = null;
                    if (noPongs) {
                        // do nothing
                    } else if (sendNullPong) {
                        response = "\r\n".getBytes();
                        logMsg = ("=> NULL PONG");
                    } else if (sendGenericError) {
                        logger.trace("Sending generic error");
                        sendErr("generic error message");
                    } else if (sendAuthorizationError) {
                        sendErr("Authorization Violation");
                    } else if (sendTlsError) {
                        // TODO Does gnatsd even send any error that starts with "tls:"?
                        response = "tls: Secure Connection Failed\r\n".getBytes();
                        logMsg = "=> tls: Secure Connection Failed";
                    } else if (closeStream) {
                        out.close();
                        logMsg = "=> Close stream.";
                    } else {
                        response = PONG_PROTO.getBytes();
                        logMsg = "=> PONG";
                    }

                    if (response != null) {
                        bw.write(response);
                        bw.flush();
                    }
                    if (logMsg != null) {
                        logger.trace(logMsg);
                    }
                } else if (control.equalsIgnoreCase(PONG_PROTO.trim())) {
                    /* NOOP */
                } else if (control.toUpperCase().startsWith("CONNECT")) {
                    logger.trace("Processing CONNECT");
                    this.connectInfo = ClientConnectInfo.createFromWire(control);
                    sendOk();
                } else if (control.startsWith("UNSUB")) {
                    processUnsub(control);
                } else if (control.startsWith("SUB")) {
                    processSubscription(control);
                } else if (control.startsWith("PUB")) {
                    String subj = null;
                    String reply = null;
                    Integer numBytes = 0;
                    byte[] payload = null;

                    String[] tokens = control.split("\\s+");

                    subj = tokens[1];
                    switch (tokens.length) {
                        case 3:
                            numBytes = Integer.parseInt(tokens[2]);
                            break;
                        case 4:
                            reply = tokens[2];
                            numBytes = Integer.parseInt(tokens[3]);
                            break;
                        default:
                            throw new IllegalArgumentException(
                                    "Wrong number of PUB arguments: " + tokens.length);
                    }

                    if (numBytes > 0) {
                        payload = br.readLine().getBytes(encoding);
                        if (payload.length > numBytes) {
                            throw new IllegalArgumentException("actual payload size ("
                                    + payload.length + "), expected: " + numBytes);
                        }
                    }

                    deliverMessage(subj, -1, reply, payload);
                } else {
                    sendErr("Unknown Protocol Operation");
                    // break;
                }
            }
            // shutdown=true;
            // bw.close();
            // br.close();
        } catch (IOException e) {
            /* NOOP */
        } finally {
            this.teardown();
        }
    }

    private void sendOk() throws IOException {
        if (this.connectInfo.isVerbose()) {
            if (!this.isVerboseNoOK()) {
                bw.write("+OK\r\n".getBytes());
                bw.flush();
                logger.trace("=> +OK");
            } else {
                bw.write("+WRONGPROTO\r\n".getBytes());
                bw.flush();
                logger.trace("=> +WRONGPROTO");

            }
        }
    }

    private void sendErr(String err) throws IOException {
        String str = String.format("-ERR '%s'\r\n", err);
        bw.write(str.getBytes());
        bw.flush();
        logger.trace("=> " + str.trim());
    }

    void deliverMessage(String subj, int sid, String reply, byte[] payload) {
        String out = null;

        if (sid < 0) {
            if (subs.containsKey(subj)) {
                sid = subs.get(subj);
            }
        }

        if (reply != null) {
            out = String.format("MSG %s %d %s %d\r\n", subj, sid, reply,
                    payload != null ? payload.length : 0);
        } else {
            out = String.format("MSG %s %d %d\r\n", subj, sid, payload.length);
        }
        logger.trace(out);
        try {
            bw.write(out.getBytes());
            if (payload != null) {
                bw.write(payload, 0, payload.length);
            }
            bw.write(ConnectionImpl._CRLF_.getBytes());
            bw.flush();
            logger.trace(String.format("=> %s\r\n",
                    out + (payload != null ? new String(payload) : "null")));
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private void processUnsub(String control) {
        String[] tokens = control.split("\\s+");
        int sid = Integer.parseInt(tokens[1]);
        int max = 0;
        if (tokens.length == 3) {
            max = Integer.parseInt(tokens[2]);
        }

        for (String s : subs.keySet()) {
            if (subs.get(s) == sid) {
                subs.remove(s);
            }
        }
    }

    private void processSubscription(String control) {
        // String buf = control.replaceFirst("SUB\\s+", "");
        String[] tokens = control.split("\\s+");

        String subj = null;
        String qgroup = null;
        int sid = -1;

        subj = tokens[1];

        switch (tokens.length) {
            case 3:
                sid = Integer.parseInt(tokens[2]);
                break;
            case 4:
                qgroup = tokens[2];
                sid = Integer.parseInt(tokens[3]);
                break;
            default:
                throw new IllegalArgumentException(
                        "Wrong number of SUB arguments: " + tokens.length);
        }
        subs.put(subj, sid);
    }

    void sendPing() throws IOException {
        byte[] pingProtoBytes = PING_PROTO.getBytes();
        int pingProtoBytesLen = pingProtoBytes.length;

        bw.write(pingProtoBytes, 0, pingProtoBytesLen);
        logger.trace("=> {}", new String(pingProtoBytes).trim());
        bw.flush();
    }

    void setServerInfoString(String info) {
        this.serverInfo = ServerInfo.createFromWire(info);
    }

    @Override
    public void close() {
        logger.trace("in close()");
        if (executor != null) {
            executor.shutdownNow();
        }
        this.shutdown();
        this.teardown();
    }

    void setBadWriter(boolean bad) {
        this.badWriter = bad;
    }

    void setBadReader(boolean bad) {
        this.badReader = bad;
    }

    void setSendNullPong(boolean badpong) {
        this.sendNullPong = badpong;
    }

    void setSendGenericError(boolean senderr) {
        this.sendGenericError = senderr;
    }

    void setSendAuthorizationError(boolean senderr) {
        this.sendAuthorizationError = senderr;
    }

    void setSendTlsErr(boolean senderr) {
        this.sendTlsError = senderr;
    }

    void setCloseStream(boolean senderr) {
        this.closeStream = senderr;
    }

    void setNoInfo(boolean noInfo) {
        this.noInfo = noInfo;
    }

    void setTlsRequired(boolean tlsRequired) {
        this.tlsRequired = tlsRequired;
    }

    void setOpenFailure(boolean openFailure) {
        this.openFailure = openFailure;
    }

    void setNoPongs(boolean noPongs) {
        this.noPongs = noPongs;
    }

    void bounce() {
        // TODO Auto-generated method stub
        try {
            logger.trace("bouncing");
            if (in != null) {
                in.close();
                in = null;
            }
            if (writeStream != null) {
                writeStream.close();
                writeStream = null;
            }

            if (out != null) {
                out.close();
                out = null;
            }

            if (readStream != null) {
                readStream.close();
                readStream = null;
            }

            if (br != null) {
                br.close();
                br = null;
            }

            if (bw != null) {
                bw.close();
                bw = null;
            }

            if (isr != null) {
                isr.close();
                isr = null;
            }

            if (bis != null) {
                bis.close();
                bis = null;
            }

            if (bos != null) {
                bos.close();
                bos = null;
            }

            shutdown();

            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            shutdown = false;
            // close();
            UnitTestUtilities.sleep(100);
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    void setThrowTimeoutException(boolean b) {
        this.throwTimeoutException = b;

    }

    String getBuffer() {
        return control;
    }

    boolean isVerboseNoOK() {
        return verboseNoOK;
    }

    void setVerboseNoOK(boolean verboseNoOK) {
        this.verboseNoOK = verboseNoOK;
    }

    protected void makeTLS(SSLContext context) throws IOException {
        this.sslContext = context;
        // setSocketFactory(sslContext.getSocketFactory());
        setSocketFactory(mock(SSLSocketFactory.class));
        // SSLSocketFactory sslSf = (SSLSocketFactory) factory;
        SSLSocketFactory sslSf = (SSLSocketFactory) factory;
        // SSLSocket sslSocket = (SSLSocket) sslSf.createSocket(client,
        // client.getInetAddress().getHostAddress(), client.getPort(), true);

        SSLSocket sslSocket = mock(SSLSocket.class);

        if (isTlsDebug()) {
            sslSocket.addHandshakeCompletedListener(new HandshakeListener());
        }

        // this.setSocket(sslSocket);
        sslSocket.startHandshake();
        // this.bisr = null;
        // this.readStream = new PipedInputStream(DEFAULT_BUF_SIZE);
        // sslSocket.getInputStream();
        // bis = null;
        // this.writeStream = sslSocket.getOutputStream();
        // bos = null;


        readStream = new PipedInputStream(DEFAULT_BUF_SIZE);
        out = new PipedOutputStream(readStream);
        isr = null;

        writeStream = new PipedOutputStream();
        in = new PipedInputStream(writeStream, DEFAULT_BUF_SIZE);


        bw = new BufferedOutputStream(out, DEFAULT_BUF_SIZE);

    }
}
