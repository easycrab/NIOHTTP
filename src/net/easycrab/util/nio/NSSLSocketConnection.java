package net.easycrab.util.nio;

import java.io.ByteArrayOutputStream;
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
    
    private ByteBuffer      readNetBuffer;
    private ByteBuffer      readAppBuffer;
    private ByteBuffer      writeNetBuffer;
    private ByteBuffer      writeAppBuffer;
    
    private ByteArrayOutputStream dataBuffer;
    
    private SocketChannel       channel;
    private Selector            readSelector;
    private Selector            writeSelector;

    private InetSocketAddress   hostAddr;

    Object myLock = new Object();
    
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
        sslEngine.beginHandshake();

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
        
        writeSelector = Selector.open();
        channel.register(writeSelector, SelectionKey.OP_WRITE);

        readSelector = Selector.open();
        channel.register(readSelector, SelectionKey.OP_READ);

        new Thread( new Runnable() {
            public void run()
            {
                try {
                    processHandshakeStatus();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }
    
    public void close() throws Exception
    {
        if (channel.isConnected()) {
            synchronized (myLock) {
                myLock.notifyAll();
            }
            
            if (writeSelector != null) {
                writeSelector.close();
            }
            if (readSelector != null) {
                readSelector.close();
            }
            channel.close();
        }
    }
    
    public void write(long timeout, byte[] data, int offset, int len) throws Exception
    {
        
        int remainLen = len;
        int offsetNow = offset;
        long tsNow;        

        writeAppBuffer.clear();
        writeAppBuffer.flip();
        
        int capacity = writeAppBuffer.capacity();
        while (remainLen > 0 || writeAppBuffer.hasRemaining()) {
            tsNow = System.currentTimeMillis();  
            if (timeout > 0) {
                writeSelector.select(timeout);
                if (System.currentTimeMillis() - tsNow > timeout) {
                    // time out
                    throw new IOException("Wait for connection writable timeout!");
                }
            }
            else {
                writeSelector.select();
            }
            
            Set<SelectionKey> selectedKeys = writeSelector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
            if (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                if (key.isWritable() ) {
                    if (! writeAppBuffer.hasRemaining()) {
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
                        System.out.println("1 - writeAppBuffer remaining - :" + writeAppBuffer.remaining());
                        writeAppBuffer.flip();
                        System.out.println("2 - writeAppBuffer remaining :" + writeAppBuffer.remaining());
                        synchronized (myLock) {
                            myLock.notifyAll();
                        }
                    }
                    
                 }
            }
            
        }
              
    }
    
    public int read(long timeout, byte[] data, int offset, int len) throws Exception
    {
        int totalReadLen = readDataFromBuffer(data, offset, len);
        int remainLen = len - totalReadLen;
        int offsetNow = offset + totalReadLen;
        
        if (remainLen == 0) {
            return totalReadLen;
        }
                
        long tsNow;  
        long remainTimeout = timeout;

        int readLen;
        while (remainLen > 0) {
            tsNow = System.currentTimeMillis();  
            if (timeout > 0) {
                readSelector.select(remainTimeout);
                long passedTime = System.currentTimeMillis() - tsNow;
                if (passedTime > remainTimeout) {
                    throw new IOException("Read data timeout!");
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
                    synchronized (myLock) {
                        myLock.notifyAll();
                    }
                    
                    readLen = readDataFromBuffer(data, offsetNow, remainLen);
                    System.out.println("Read len this time -- : " + readLen);
                    if (readLen > 0) {
                        remainLen -= readLen;
                        offsetNow += readLen;
                        totalReadLen += readLen;
                    }

                }
            }
            
        }
        return totalReadLen;
         
    }

    /*
    public int read2(long timeout, byte[] data, int offset, int len) throws Exception
    {
        if (readSelector == null) {
            readSelector = Selector.open();
            channel.register(readSelector, SelectionKey.OP_READ);
        }
        
        int remainLen = len;
        int offsetNow = offset;
        long tsNow;        
        int totalReadLen = 0;

        if (readAppBuffer.hasRemaining()) {
            int previousRemainLen = readAppBuffer.remaining();
            if (previousRemainLen > remainLen) {
                readAppBuffer.get(data, offsetNow, remainLen);
                totalReadLen = remainLen;
                return totalReadLen;
            }
            else {
                readAppBuffer.get(data, offsetNow, previousRemainLen);
                offsetNow += previousRemainLen;
                remainLen -= previousRemainLen;
                totalReadLen += previousRemainLen;
            }

        }

        int readLen;
        SSLEngineResult result;
        while (remainLen > 0) {
            tsNow = System.currentTimeMillis();  
            if (timeout > 0) {
                readSelector.select(timeout);
                if (System.currentTimeMillis() - tsNow > timeout) {
                    // time out
                    throw new IOException("Wait for connection readable timeout!");
                }
            }
            else {
                readSelector.select();
            }
            
            Set<SelectionKey> selectedKeys = readSelector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
            if (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                if (key.isReadable() ) {
                    hsStatus = sslEngine.getHandshakeStatus();
                    if (hsStatus == HandshakeStatus.NEED_UNWRAP) {
                        readLen = channel.read(readNetBuffer);
                        if (readLen < 0) {
                            System.out.println("no data is read for unwrap. count=" + readLen);
                            throw new IOException("No data is read for unwrap!");
                        }
                        System.out.println("data read: " + readLen);
                        readNetBuffer.flip();
                        readAppBuffer.clear();
                        do {
                            result = sslEngine.unwrap(readNetBuffer, readAppBuffer);
                            System.out.println("Unwrapping - :" + result);
                            // During an handshake re-negotiation we might need to
                            // perform several unwraps to consume the handshake data.
                        } while (result.getStatus() == SSLEngineResult.Status.OK
                                && result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_UNWRAP
                                && result.bytesConsumed() == 0);
                        if (readAppBuffer.position() == 0 && result.getStatus() == SSLEngineResult.Status.OK
                                && readNetBuffer.hasRemaining()) {
                            result = sslEngine.unwrap(readNetBuffer, readAppBuffer);
                            System.out.println("Unwrapping -- : " + result);
                        }

                        status = result.getStatus();
                        assert status != Status.BUFFER_OVERFLOW : "buffer not overflow." + status.toString();
                        // Prepare the buffer to be written again.
                        readNetBuffer.clear();
                        // And the app buffer to be read.
                        readAppBuffer.flip();

                        readLen = readAppBuffer.remaining();
                        
                        if (readLen > 0) {
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
                        }
                        
                    }

                }
            }
            
        }
        return totalReadLen;
         
    }
    */
    
    private void processHandshakeStatus() throws Exception
    {        
        synchronized (myLock) {
            myLock.wait();
            HandshakeStatus hsStatus = sslEngine.getHandshakeStatus();
            while (hsStatus != HandshakeStatus.FINISHED ) {
                System.out.println("Current HandshakeStatus -- : " + hsStatus);
                if (hsStatus == HandshakeStatus.NEED_WRAP) {
                    if (! wrapData()) {
                        myLock.wait();
                    }
                }
                else if (hsStatus == HandshakeStatus.NEED_UNWRAP) {
                    if (! unwrapData()) {
                        myLock.wait();
                    }
                }
                else if (hsStatus == HandshakeStatus.NEED_TASK) {
                    Runnable runnable;
                    while ((runnable = sslEngine.getDelegatedTask()) != null) {
                        runnable.run();
                    }
                }
                else {
                    System.out.println("Unsupported HandshakeStatus -- : " + hsStatus);
                    break;
                }
                
                hsStatus = sslEngine.getHandshakeStatus();
            }
        }
    }
    
    private boolean wrapData() throws Exception
    {
        boolean hasDataToWrap = writeAppBuffer.hasRemaining();
        if (hasDataToWrap) {
            writeNetBuffer.clear();
            SSLEngineResult result;
            do {
                result = sslEngine.wrap(writeAppBuffer, writeNetBuffer);
                System.out.println("Wrapping - :" + result);
                // During an handshake re-negotiation we might need to
                // perform several wraps to consume the handshake data.
                if (result.getStatus() == Status.BUFFER_OVERFLOW) {
                    throw new IOException("Net buffer overflow!!");
                }
                System.out.println("3-   remaining:" + writeAppBuffer.remaining());
            } while (result.getStatus() == Status.OK
                    && result.getHandshakeStatus() == HandshakeStatus.NEED_WRAP
                    && result.bytesProduced() == 0);
            System.out.println("4 - writeAppBuffer remaining :" + writeAppBuffer.remaining());
            if (writeNetBuffer.position() == 0 && result.getStatus() == SSLEngineResult.Status.OK
                    && writeAppBuffer.hasRemaining()) {
                result = sslEngine.wrap(writeAppBuffer, writeNetBuffer);
                System.out.println("Wrapping -- : " + result);
            }
            System.out.println("5 - writeAppBuffer remaining :" + writeAppBuffer.remaining());
            writeAppBuffer.clear();
            writeAppBuffer.flip();
            
            if (result.getStatus() == Status.BUFFER_OVERFLOW) {
                throw new IOException("Output buffer overflow!!");
            }
            flushNetBuffer(0); // TODO: timeout value
        }
        
        return hasDataToWrap;

    }
    
    private boolean unwrapData() throws Exception
    {
        readNetBuffer.clear();
        int readLen = channel.read(readNetBuffer);
        if (readLen < 0) {
            System.out.println("no data is read for upwrap. count=" + readLen);
            throw new IOException("No data is read for unwrap!");
        }
        System.out.println("data read: " + readLen);
        boolean hasDataToUnwrap = readLen > 0;
        if (hasDataToUnwrap) {
            readNetBuffer.flip();
            readAppBuffer.clear();
            
            SSLEngineResult result;
            do {
                result = sslEngine.unwrap(readNetBuffer, readAppBuffer);
                System.out.println("Unwrapping - :" + result);
                // During an handshake re-negotiation we might need to
                // perform several unwraps to consume the handshake data.
            } while (result.getStatus() == SSLEngineResult.Status.OK
                    && result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_UNWRAP
                    && result.bytesConsumed() == 0);
            if (readAppBuffer.position() == 0 && result.getStatus() == SSLEngineResult.Status.OK
                    && readNetBuffer.hasRemaining()) {
                result = sslEngine.unwrap(readNetBuffer, readAppBuffer);
                System.out.println("Unwrapping -- : " + result);
            }
            readAppBuffer.flip();
            readLen = readAppBuffer.remaining();
            System.out.println("Unwrapp result len : " + readLen);
            byte[] tmp = new byte[readLen];
            readAppBuffer.get(tmp);
            dataBuffer.write(tmp);
            
            readNetBuffer.clear();
        }
        
        return hasDataToUnwrap;
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

        writeAppBuffer.flip();
        readNetBuffer.flip();
        
        dataBuffer = new ByteArrayOutputStream();
   }
    
    private void flushNetBuffer(long timeout) throws Exception
    {        
        long tsNow;        

        writeNetBuffer.flip();
        while (writeNetBuffer.hasRemaining()) {
            tsNow = System.currentTimeMillis();  
            if (timeout > 0) {
                writeSelector.select(timeout);
                if (System.currentTimeMillis() - tsNow > timeout) {
                    // time out
                    throw new IOException("Wait for connection writable timeout!");
                }
            }
            else {
                writeSelector.select();
            }
            
            Set<SelectionKey> selectedKeys = writeSelector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
            if (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                if (key.isWritable() ) {
                    System.out.println("Before output - writeNetBuffer.remaining()=" + writeNetBuffer.remaining());
                    channel.write(writeNetBuffer);
                    System.out.println("After output - : writeNetBuffer.remaining()=" + writeNetBuffer.remaining());

                }
            }
            
        }
              
    }
    
    private int readDataFromBuffer(byte[] data, int offset, int len)
    {
        int totalReadLen = 0;

        if (dataBuffer.size() > 0) {
            byte[] buf = dataBuffer.toByteArray();
            int availableLen = buf.length;
            if (availableLen > len) {
                System.arraycopy(buf, 0, data, offset, len);
                dataBuffer.reset();
                dataBuffer.write(buf, len, buf.length - len);
                totalReadLen = len;
            }
            else {
                System.arraycopy(buf, 0, data, offset, availableLen);
                dataBuffer.reset();
                totalReadLen = availableLen;
            }
        }
        
        return totalReadLen;
    }
    
}
