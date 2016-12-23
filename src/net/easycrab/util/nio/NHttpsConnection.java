package net.easycrab.util.nio;

import java.net.InetSocketAddress;

public class NHttpsConnection extends NHttpConnection
{
    
    public NHttpsConnection(String targetUrl, boolean isMethodPost, long timeout)
    {
        super(targetUrl, isMethodPost, timeout);
        acceptedProtocol = "https";
        defaultPortNum = 443;
    }

    public void connect() throws Exception
    {
        parseRequestUrl();
        InetSocketAddress addr = new InetSocketAddress(host, port);
        connection = new NSSLSocketConnection(addr);
        connection.connect(timeout);
        
        hasReqHeaderSent = false;
        hasRespHeaderGot = false;
        isConnected = true;
    }

}
