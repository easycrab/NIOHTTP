package net.easycrab.util.nio;

import java.io.IOException;
import java.net.InetSocketAddress;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;


public class NSocketConnection implements NIOConnection
{
    private final int           BUFFER_CAPACITY = 256;
    
    private SocketChannel       channel;
    private ByteBuffer          readBuf;
    private ByteBuffer          writeBuf;
    private Selector            readSelector;
    private Selector            writeSelector;
    
    private InetSocketAddress   hostAddr;
    
    public NSocketConnection(InetSocketAddress address)
    {
        hostAddr = address;
    }
    
    public void connect(long timeout) throws Exception
    {
        channel = SocketChannel.open();
        channel.configureBlocking(false);
        
        Selector selector = Selector.open();
        channel.register(selector, SelectionKey.OP_CONNECT);
        
        long tsNow = System.currentTimeMillis();        
        channel.connect(hostAddr);
        if (timeout > 0) {
            selector.select(timeout);
        }
        else {
            selector.select();
        }
        if (channel.finishConnect()) {
            selector.close();
            readBuf = ByteBuffer.allocate(BUFFER_CAPACITY);
            writeBuf = ByteBuffer.allocate(BUFFER_CAPACITY);
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
    
    public void write(long timeout, byte[] data, int offset, int len) throws Exception
    {
        if (writeSelector == null) {
            writeSelector = Selector.open();
            channel.register(writeSelector, SelectionKey.OP_WRITE);
        }
        
        int remainLen = len;
        int offsetNow = offset;
        long tsNow;  
        long remainTimeout = timeout;

        writeBuf.clear();
        writeBuf.flip();
        while (remainLen > 0 || writeBuf.hasRemaining()) {
            tsNow = System.currentTimeMillis();  
            if (timeout > 0) {
                writeSelector.select(remainTimeout);
                long passTime = System.currentTimeMillis() - tsNow;
                if (passTime >= remainTimeout) {
                    // time out
                    throw new IOException("Wait for connection writable timeout!");
                }
                remainTimeout -= passTime;
            }
            else {
                writeSelector.select();
            }
            
            Set<SelectionKey> selectedKeys = writeSelector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
            if (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                if (key.isWritable() ) {
                    if (! writeBuf.hasRemaining()) {
                        writeBuf.clear();
                        if (remainLen > BUFFER_CAPACITY) {
                            writeBuf.put(data, offsetNow, BUFFER_CAPACITY);
                            offsetNow += BUFFER_CAPACITY;
                            remainLen -= BUFFER_CAPACITY;
                        }
                        else {
                            writeBuf.put(data, offsetNow, remainLen);
                            remainLen = 0; 
                        }
                        writeBuf.flip();
                    }
                    
                    channel.write(writeBuf);
                }
            }
            
        }
              
    }
    
    public int read(long timeout, byte[] data, int offset, int len) throws Exception
    {
        if (readSelector == null) {
            readSelector = Selector.open();
            channel.register(readSelector, SelectionKey.OP_READ);
        }
        
        int remainLen = len;
        int offsetNow = offset;
        long tsNow;        
        long remainTimeout = timeout;
        int totalReadLen = 0;

        if (readBuf.hasRemaining()) {
            int previousRemainLen = readBuf.remaining();
            if (previousRemainLen > remainLen) {
                readBuf.get(data, offsetNow, remainLen);
                totalReadLen = remainLen;
                return totalReadLen;
            }
            else {
                readBuf.get(data, offsetNow, previousRemainLen);
                offsetNow += previousRemainLen;
                remainLen -= previousRemainLen;
                totalReadLen += previousRemainLen;
            }

        }

        while (remainLen > 0) {
            tsNow = System.currentTimeMillis();  
            if (timeout > 0) {
                readSelector.select(remainTimeout);
                long passTime = System.currentTimeMillis() - tsNow;
                if (passTime >= remainTimeout) {
                    // time out
                    throw new IOException("Wait for connection readable timeout!");
                }
                remainTimeout -= passTime;
            }
            else {
                readSelector.select();
            }
            
            Set<SelectionKey> selectedKeys = readSelector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
            if (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                if (key.isReadable() ) {
                    readBuf.clear();
                    int readLen = channel.read(readBuf);
                    
                    if (readLen == -1) {
                        // the read channel is not available now?
                        throw new IOException("Cannot read more from the connection!");
                    }
                    else if (readLen > 0) {
                        readBuf.flip();
                        if (readLen >= remainLen) {
                            readBuf.get(data, offsetNow, remainLen);
                            totalReadLen += remainLen;
                            break;
                        }
                        else {
                            readBuf.get(data, offsetNow, readLen);
                            offsetNow += readLen;
                            remainLen -= readLen;
                            totalReadLen += readLen;
                        }
                    }
                }
            }
            
        }
        return totalReadLen;
         
    }
        
}
