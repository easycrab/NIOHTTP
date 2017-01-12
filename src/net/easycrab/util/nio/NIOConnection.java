package net.easycrab.util.nio;

public interface NIOConnection
{
    public void connect(long timeout) throws Exception;
    
    public void close() throws Exception;
    
    public void write(long timeout, byte[] data, int offset, int len) throws Exception;
    
    public int read(long timeout, byte[] data, int offset, int len) throws Exception;
    
    public void setTimeoutMode(boolean isOnlyCheckBlockTime);
    
}
