package net.easycrab.util.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLSession;

public class NSSLSocketConnection
{
    private Logger logger = Logger.getLogger(NSSLSocketConnection.class.getName());
    
    private SSLEngine       sslEngine;
    private HandshakeStatus hsStatus;
    private Status          status;
    
    private ByteBuffer      localNetData;
    private ByteBuffer      localAppData;
    private ByteBuffer      peerNetData;
    private ByteBuffer      peerAppData;
    private ByteBuffer      dummy = ByteBuffer.allocate(0);

    private SocketChannel       channel;
    private Selector            readSelector;
    private Selector            writeSelector;

    private InetSocketAddress   hostAddr;

    public NSSLSocketConnection(InetSocketAddress address)
    {
        hostAddr = address;
    }

    public void connect(long timeout) throws Exception
    {
        initSSLEngine();
        initByteBuffer();
        
        channel = SocketChannel.open();
        channel.configureBlocking(false);
        
        Selector selector = Selector.open();
        channel.register(selector, SelectionKey.OP_CONNECT);
        
        long tsNow = System.currentTimeMillis();        
        channel.connect(hostAddr);
        sslEngine.beginHandshake();
        hsStatus = sslEngine.getHandshakeStatus();

        if (timeout > 0) {
            selector.select(timeout);
        }
        else {
            selector.select();
        }
        if (channel.finishConnect()) {
            selector.close();
        }
        else {
            selector.close();
            if (System.currentTimeMillis() - tsNow > timeout) {
                // time out
                throw new IOException("Connect to " + hostAddr.toString() + " timeout!");
            }
            else {
                throw new IOException("Fail to connect to " + hostAddr.toString() + " !");
            }
        }
        doHandshake();      
    }
    
    public void close() throws Exception
    {
        if (channel.isConnected()) {
            if (writeSelector != null) {
                writeSelector.close();
            }
            if (readSelector != null) {
                readSelector.close();
            }
            channel.close();
        }
    }
    

    
    private void initSSLEngine() throws Exception
    {
        // Create a trust manager that does not validate certificate chains
        javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[] {
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers()
                    {
                        return new java.security.cert.X509Certificate[] {};
                    }

                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType)
                            throws java.security.cert.CertificateException
                    {
                    }

                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType)
                            throws java.security.cert.CertificateException
                    {
                    }
                } };

        // Install the all-trusting trust manager
        SSLContext sslContext = SSLContext.getInstance("SSL");
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        sslEngine = sslContext.createSSLEngine();
        sslEngine.setUseClientMode(true);

    }
    
    private void initByteBuffer()
    {
        SSLSession session = sslEngine.getSession();
        int appBufMaxSize = session.getApplicationBufferSize();
        int packBufMaxSize = session.getPacketBufferSize();
        localAppData = ByteBuffer.allocate(appBufMaxSize);
        localNetData = ByteBuffer.allocate(packBufMaxSize);
        peerAppData = ByteBuffer.allocate(appBufMaxSize);
        peerNetData = ByteBuffer.allocate(packBufMaxSize);        
        peerNetData.clear();
   }

    private void handleSocketEvent(SelectionKey key) throws IOException
    {
         Selector selector = null;
        if (key.isConnectable()) {
            channel = (SocketChannel) key.channel();
            if (channel.isConnectionPending()) {
                channel.finishConnect();
            }
            doHandshake();
            channel.register(selector, SelectionKey.OP_READ);
        }
        if (key.isReadable()) {
            channel = (SocketChannel) key.channel();
            doHandshake();
            if (hsStatus == HandshakeStatus.FINISHED) {
                logger.info("Client handshake completes... ...");
                key.cancel();
                channel.close();
            }
        }
    }

    private void doHandshake() throws IOException
    {
        SSLEngineResult result;
        int count = 0;
        while (hsStatus != HandshakeStatus.FINISHED) {
            logger.info("handshake status: " + hsStatus);
            switch (hsStatus) {
            case NEED_TASK:
                Runnable runnable;
                while ((runnable = sslEngine.getDelegatedTask()) != null) {
                    runnable.run();
                }
                hsStatus = sslEngine.getHandshakeStatus();
                break;
            case NEED_UNWRAP:
                count = channel.read(peerNetData);
                if (count < 0) {
                    logger.info("no data is read for unwrap.");
                    break;
                } else {
                    logger.info("data read: " + count);
                }
                peerNetData.flip();
                peerAppData.clear();
                do {
                    result = sslEngine.unwrap(peerNetData, peerAppData);
                    logger.info("Unwrapping:\n" + result);
                    // During an handshake renegotiation we might need to
                    // perform
                    // several unwraps to consume the handshake data.
                } while (result.getStatus() == SSLEngineResult.Status.OK
                        && result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_UNWRAP
                        && result.bytesProduced() == 0);
                if (peerAppData.position() == 0 && result.getStatus() == SSLEngineResult.Status.OK
                        && peerNetData.hasRemaining()) {
                    result = sslEngine.unwrap(peerNetData, peerAppData);
                    logger.info("Unwrapping:\n" + result);
                }
                hsStatus = result.getHandshakeStatus();
                status = result.getStatus();
                assert status != status.BUFFER_OVERFLOW : "buffer not overflow." + status.toString();
                // Prepare the buffer to be written again.
                peerNetData.compact();
                // And the app buffer to be read.
                peerAppData.flip();
                break;
            case NEED_WRAP:
                localNetData.clear();
                result = sslEngine.wrap(dummy, localNetData);
                hsStatus = result.getHandshakeStatus();
                status = result.getStatus();
                while (status != Status.OK) {
                    logger.info("status: " + status);
                    switch (status) {
                    case BUFFER_OVERFLOW:
                        break;
                    case BUFFER_UNDERFLOW:
                        break;
                    }
                }
                localNetData.flip();
                count = channel.write(localNetData);
                if (count <= 0) {
                    logger.info("No data is written.");
                } else {
                    logger.info("Written data: " + count);
                }
                break;
            }
        }
    }
    
}
