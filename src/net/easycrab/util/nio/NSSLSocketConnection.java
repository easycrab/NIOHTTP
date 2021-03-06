package net.easycrab.util.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLSession;

public class NSSLSocketConnection implements NIOConnection
{
    
    private SSLEngine       sslEngine;
    private boolean         handshakeDone;
    
    private ByteBuffer      readNetBuffer;
    private ByteBuffer      readAppBuffer;
    private ByteBuffer      writeNetBuffer;
    private ByteBuffer      writeAppBuffer;
    private ByteBuffer      dummyBuffer = ByteBuffer.allocate(0);
        
    private SocketChannel       channel;
    private Selector            readSelector;
    private Selector            writeSelector;

    private InetSocketAddress   hostAddr;

    private boolean             onlyCheckBlockTime = true;
    
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
        System.out.println("Try to connect to host:" + hostAddr.toString());
        channel.connect(hostAddr);

        if (timeout > 0) {
            selector.select(timeout);
        }
        else {
            selector.select();
        }
        selector.close();
        if (! channel.finishConnect()) {
            if (System.currentTimeMillis() - tsNow > timeout) {
                // time out
                throw new IOException("Connect to " + hostAddr.toString() + " timeout!");
            }
            else {
                throw new IOException("Fail to connect to " + hostAddr.toString() + " !");
            }
        }
        
        
        System.out.println("----** Begin Handshake Now ... **----");
        handshakeDone = false;
        sslEngine.beginHandshake();
        processHandshake(timeout);
        System.out.println("----** Handshake Completed! **----");
    }
    
    public void close() throws Exception
    {
        if (channel.isConnected()) {
            if (writeSelector != null) {
                writeSelector.close();
                writeSelector = null;
            }
            if (readSelector != null) {
                readSelector.close();
                readSelector = null;
            }
            System.out.println("----** close SSL Socket Channel **----");
            channel.close();
        }
    }
    
    public void setTimeoutMode(boolean isOnlyCheckBlockTime)
    {
        onlyCheckBlockTime = isOnlyCheckBlockTime;
    }
    
    public void write(long timeout, byte[] data, int offset, int len) throws Exception
    {
        
        int remainLen = len;
        int offsetNow = offset;
        
        int capacity = writeAppBuffer.capacity();
        long tsStart = System.currentTimeMillis();  
        long remainTimeout = timeout;

        boolean remainDataInBuf = false;
        writeAppBuffer.clear();
        while (remainLen > 0 || remainDataInBuf) {
            if (timeout > 0) {
                long passTime = System.currentTimeMillis() - tsStart;
                if (passTime < timeout) {
                    remainTimeout = timeout - passTime;
                }
                else {
                    throw new IOException("Write data to outbound timeout!");
                }
            }
            
            if (! remainDataInBuf) {
                writeAppBuffer.clear();
                if (remainLen > capacity) {
                    writeAppBuffer.put(data, offsetNow, capacity);
                    offsetNow += capacity;
                    remainLen -= capacity;
                }
                else {
                    writeAppBuffer.put(data, offsetNow, remainLen);
                    remainLen = 0; 
                }
                writeAppBuffer.flip();
//                System.out.println("writeAppBuffer remaining :" + writeAppBuffer.remaining());
            }
            
            writeNetBuffer.clear();
//            SSLEngineResult result;
//            result = 
                    sslEngine.wrap(writeAppBuffer, writeNetBuffer);
//            System.out.println("---> Wrapping for Outbound - :" + result);

            if (writeNetBuffer.position() > 0) {
                if (onlyCheckBlockTime) {
                    flushNetBuffer(timeout);                    
                }
                else {
                    flushNetBuffer(remainTimeout);
                }
            }

            remainDataInBuf = writeAppBuffer.hasRemaining();
        }
              
    }
    
    private int readFromReaminAppBuffer(byte[] data, int offset, int len) throws Exception
    {
        int readLen = 0;
        int previousRemainLen = readAppBuffer.remaining();
        if (previousRemainLen > len) {
            readAppBuffer.get(data, offset, len);
            readLen = len;
        } else {
            readAppBuffer.get(data, offset, previousRemainLen);
            readLen = previousRemainLen;
//            System.out.println("------>>> SSLSocket Read from remain buffer, len:" + readLen);
        }
        return readLen;
    }
    
    public int read(long timeout, byte[] data, int offset, int len) throws Exception
    {
        int readLen;

        int remainLen = len;
        int offsetNow = offset;
        int totalReadLen = 0;

        if (readAppBuffer.hasRemaining()) {
            readLen = readFromReaminAppBuffer(data, offsetNow, remainLen);
            offsetNow += readLen;
            remainLen -= readLen;
            totalReadLen += readLen;
        }
        
        while (remainLen > 0 && readNetBuffer.hasRemaining()) {
            // try to consume the remain inbound net buffer
//            System.out.println("------>>> readNetBuffer has remaining!! len:" + readNetBuffer.remaining());
            readAppBuffer.clear();
            SSLEngineResult result;
            result = sslEngine.unwrap(readNetBuffer, readAppBuffer);
//            System.out.println("------>>> Unwrapping from remain read net buffer - :" + result
//                    + " readAppBuffer.position(): " + readAppBuffer.position());
            if (result.getStatus() != Status.OK && result.getStatus() != Status.BUFFER_UNDERFLOW) {
                throw new IOException("--> Unexpected error for unwrap read net buffer!");
            }
            
            readAppBuffer.flip();            
            if (readAppBuffer.hasRemaining()) {
                readLen = readFromReaminAppBuffer(data, offsetNow, remainLen);
                offsetNow += readLen;
                remainLen -= readLen;
                totalReadLen += readLen;
            }
            
        }

        long tsStart = System.currentTimeMillis();  
        long remainTimeout = timeout;

        while (remainLen > 0) {
            if (timeout > 0) {
                long passTime = System.currentTimeMillis() - tsStart;
                if (passTime < timeout) {
                    remainTimeout = timeout - passTime;
                }
                else {
                    throw new IOException("Read data from Inbound timeout!");
                }
            }
            if (onlyCheckBlockTime) {
                unwrapInboundData(timeout);
            }
            else {
                unwrapInboundData(remainTimeout);
            }
            
            readLen = readAppBuffer.remaining();
            if (readLen > remainLen) {
                readAppBuffer.get(data, offsetNow, remainLen);
                totalReadLen = remainLen;
                break;
            }
            else {
                readAppBuffer.get(data, offsetNow, readLen);
                offsetNow += readLen;
                remainLen -= readLen;
                totalReadLen += readLen;
            }
//            System.out.println("------>>> SSLSocket Read, total Now:" + totalReadLen);

        }
        return totalReadLen;
         
    }

    private void processHandshake(long timeout) throws Exception
    {        
        HandshakeStatus hsStatus;
        while (! handshakeDone) {
            hsStatus = sslEngine.getHandshakeStatus();
//            System.out.println("Current HandshakeStatus -- : " + hsStatus);
            if (hsStatus == HandshakeStatus.NEED_WRAP) {
                wrapHandshakeData(timeout);
            }
            else if (hsStatus == HandshakeStatus.NEED_UNWRAP) {
                unwrapHandshakeData(timeout);
            }
            else if (hsStatus == HandshakeStatus.NEED_TASK) {
                Runnable runnable;
                while ((runnable = sslEngine.getDelegatedTask()) != null) {
                    runnable.run();
                }
            }
            else { 
                // It's NOT_HANDSHAKING
                System.out.println("Unexpected HandshakeStatus -- : " + hsStatus);
                break;
            }
            
        }
    }
    
    private void wrapHandshakeData(long timeout) throws Exception
    {
        SSLEngineResult result;
        writeNetBuffer.clear();

        result = sslEngine.wrap(dummyBuffer, writeNetBuffer);
        Status          status = result.getStatus();
//        System.out.println("wrapHandshakeData() status: " + status);        
        if (status != Status.OK) {
            switch (status) {
            case BUFFER_OVERFLOW:
//                System.out.println("wrap to outbound (BUFFER_OVERFLOW)");
                throw new IOException("Handshake: wrap to outbound (BUFFER_OVERFLOW)");
            case BUFFER_UNDERFLOW:
//                System.out.println("wrap to outbound (BUFFER_UNDERFLOW)");
                throw new IOException("Handshake: wrap to outbound (BUFFER_UNDERFLOW)");
            default:
                // do nothing;
            }
        }
        
        flushNetBuffer(timeout); 

    }
    
    private void unwrapHandshakeData(long timeout) throws Exception
    {
        boolean unwrapDone = false;
        boolean previousUnderflow = false;
        SSLEngineResult result;

        while (! unwrapDone) {
            
            if (previousUnderflow || readNetBuffer.position() == 0) {
                waitForReadable(timeout);
                
                int readLen = channel.read(readNetBuffer);
//                System.out.println("Handshake: Inbound data read for upwrap. read Len:" + readLen);
                if (readLen < 0) {
                    throw new IOException("Handshake: No data is read for unwrap!");
                }
                
                if (previousUnderflow) {
                    previousUnderflow = false;
                }
                
            }
            readNetBuffer.flip();
            readAppBuffer.clear();

            do {
                result = sslEngine.unwrap(readNetBuffer, readAppBuffer);
//                System.out.println("Unwrapping - :" + result);
//                System.out.println("------==>1> readNetBuffer remaining count:" + readNetBuffer.remaining());
                // During an handshake re-negotiation we might need to
                // perform several unwraps to consume the handshake data.
            } while (result.getStatus() == SSLEngineResult.Status.OK
                    && result.getHandshakeStatus() == HandshakeStatus.NEED_UNWRAP
                    && result.bytesProduced() == 0);
            
            if (result.getHandshakeStatus() == HandshakeStatus.FINISHED) {
                handshakeDone = true;
            }
            
            if (readAppBuffer.position() == 0 && result.getStatus() == Status.OK
                    && readNetBuffer.hasRemaining()) {
                result = sslEngine.unwrap(readNetBuffer, readAppBuffer);
//                System.out.println("Unwrapping -* : " + result);
                if (result.getHandshakeStatus() == HandshakeStatus.FINISHED) {
                    handshakeDone = true;
                }
            }
//            System.out.println("------==>2> readNetBuffer remaining count:" + readNetBuffer.remaining());

            Status status = result.getStatus();
            if (status != Status.OK) {
                switch (status) {
                case BUFFER_OVERFLOW:
                    System.out.println("unwrap from inbound (BUFFER_OVERFLOW)");
//                    throw new IOException("Handshake: unwrap from inbound (BUFFER_OVERFLOW)");
                case BUFFER_UNDERFLOW:
                    System.out.println("unwrap from inbound (BUFFER_UNDERFLOW)");
                    readNetBuffer.compact();
                    previousUnderflow = true;
//                    throw new IOException("Handshake: unwrap from inbound (BUFFER_UNDERFLOW)");
                default:
                    // do nothing;
                }
            }
            else { 
                unwrapDone = true;
                if (! handshakeDone) {
                    readNetBuffer.compact();
                }

            }
       }

    }
    
    private void waitForReadable(long timeout) throws Exception
    {
        if (readSelector == null) {
            readSelector = Selector.open();
            channel.register(readSelector, SelectionKey.OP_READ);
        }
        
        long tsNow;  
        long remainTimeout = timeout;
        boolean needWait4Readable = true;
        while (needWait4Readable && readSelector != null && readSelector.isOpen()) {
            if (timeout > 0) {
                tsNow = System.currentTimeMillis();  
                readSelector.select(timeout);
                long passedTime = System.currentTimeMillis() - tsNow;
                if (passedTime > remainTimeout) {
                    throw new IOException("Wait for socket channel READable timeout!");
                }
                remainTimeout -= passedTime;
            }
            else {
                readSelector.select();
            }
            
            Set<SelectionKey> selectedKeys = readSelector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
            if (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                if (key.isReadable() ) {
                    needWait4Readable = false;
                }
            }
        }
    }

    private void waitForWritable(long timeout) throws Exception
    {
        if (writeSelector == null) {
            writeSelector = Selector.open();
            channel.register(writeSelector, SelectionKey.OP_WRITE);

        }
        
        long tsNow;  
        long remainTimeout = timeout;
        boolean needWait4Writable = true;
        while (needWait4Writable && writeSelector != null && writeSelector.isOpen()) {
            if (timeout > 0) {
                tsNow = System.currentTimeMillis();  
                writeSelector.select(timeout);
                long passedTime = System.currentTimeMillis() - tsNow;
                if (passedTime > remainTimeout) {
                    throw new IOException("Wait for socket channel WRITable timeout!");
                }
                remainTimeout -= passedTime;
            }
            else {
                writeSelector.select();
            }
            
            Set<SelectionKey> selectedKeys = writeSelector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
            if (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                if (key.isWritable() ) {
                    needWait4Writable = false;
                }
            }
        }
    }

    private void unwrapInboundData(long timeout) throws Exception
    {
        readNetBuffer.clear();
        readAppBuffer.clear();

        long tsStart = System.currentTimeMillis();  
        long remainTimeout = timeout;

        int readLen = 0;
        boolean needReadMore = true;
        while (needReadMore) {
            if (timeout > 0) {
                long passTime = System.currentTimeMillis() - tsStart;
                if (passTime < timeout) {
                    remainTimeout = timeout - passTime;
                }
                else {
                    throw new IOException("Read data from Inbound timeout!");
                }
            }
            if (onlyCheckBlockTime) {
                waitForReadable(timeout);
            }
            else {
                waitForReadable(remainTimeout);
            }

            readLen = channel.read(readNetBuffer);
            if (readLen < 0) {
//                System.out.println("no data is read for unwrap. count=" + readLen);
                throw new IOException("No data is read for unwrap!");
            }
//            System.out.println(" **======** data read: " + readLen);
            if (readLen > 0) {
                readNetBuffer.flip();
                
                SSLEngineResult result;
                result = sslEngine.unwrap(readNetBuffer, readAppBuffer);
//                System.out.println("Unwrapping from Inbound- :" + result 
//                        + " readAppBuffer.position(): " + readAppBuffer.position());
                if (result.getStatus() == Status.BUFFER_UNDERFLOW) {
                    needReadMore = true;
                    readNetBuffer.compact();
                }
                else {
                    needReadMore = false;
                }
                
            }
            
        }
        readAppBuffer.flip();
        readLen = readAppBuffer.remaining();
//        System.out.println("------==>>> Unwrapp result len : " + readLen);        
//        System.out.println("------==>>> readNetBuffer remaining count:" + readNetBuffer.remaining());
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
        readAppBuffer = ByteBuffer.allocate(appBufMaxSize);
        readNetBuffer = ByteBuffer.allocate(packBufMaxSize);
        writeAppBuffer = ByteBuffer.allocate(appBufMaxSize);
        writeNetBuffer = ByteBuffer.allocate(packBufMaxSize);   
   }
    
    private void flushNetBuffer(long timeout) throws Exception
    {        
        writeNetBuffer.flip();
        long tsNow;
        long remainTimeout = timeout;
//        int sendLen;
        while (writeNetBuffer.hasRemaining()) {
            tsNow = System.currentTimeMillis();
            if (onlyCheckBlockTime) {
                waitForWritable(timeout);
            }
            else {
                waitForWritable(remainTimeout);
            }
//            System.out.println("Before output - writeNetBuffer.remaining()=" + writeNetBuffer.remaining());
//            sendLen = 
                    channel.write(writeNetBuffer);
//            System.out.println("After output - : writeNetBuffer.remaining()=" + writeNetBuffer.remaining() 
//            + " sendLength=" + sendLen);
            if (writeNetBuffer.hasRemaining()) {
                if (timeout > 0) {
                    long passTime = System.currentTimeMillis() - tsNow;
                    if (passTime >= remainTimeout) {
                        throw new IOException("Flush Outbound Data timeout!");
                    }
                    remainTimeout -= passTime;
                }
            }
        }
              
    }
        
}
