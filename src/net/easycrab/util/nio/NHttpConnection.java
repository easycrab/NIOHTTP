package net.easycrab.util.nio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;

public class NHttpConnection 
{
    private final String                HTTP_PROTOCOL = "HTTP/1.1";
    private String                      url;
    private boolean                     isPost;
    private long                        timeout;
    
    private String                      host;
    private int                         port;
    private String                      path;
    
    private int                         statusCode;
    private String                      statusText;
    
    private HashMap<String, String>     requestHeaders;
    private HashMap<String, String>     responseHeaders;
    private NSocketConnection           connection;
    
    private boolean                     isConnected;
    private boolean                     hasReqHeaderSent;
    private boolean                     hasRespHeaderGot;
    
    public NHttpConnection(String targetUrl, boolean isMethodPost, long timeout)
    {
        url = targetUrl;
        isPost = isMethodPost;
        this.timeout = timeout;
        requestHeaders = new HashMap<String, String>();
        responseHeaders = new HashMap<String, String>();
        isConnected = false;
    }
    
    public void prepareRequestHeader(String headerName, String headerVal)
    {
        if (headerName != null && headerName.length() > 0 && headerVal != null) {
            requestHeaders.put(headerName, headerVal);
        }
    }
    
    public HashMap<String, String> getRequestHeaders()
    {
        return requestHeaders;
    }
    
    public HashMap<String, String> getResponseHeaders() throws Exception
    {
        ensureHeaderRead();

        return responseHeaders;
    }
    
    public int getResponseStatusCode() throws Exception
    {
        ensureHeaderRead();
        return statusCode;
    }
    
    public String getResponseStatusText() throws Exception
    {
        ensureHeaderRead();
        return statusText;
    }
    
    public int getContentLength() throws Exception
    {
        ensureHeaderRead();
        int len = -1;
        String lenStr = responseHeaders.get("Content-Length");
        if (lenStr != null && lenStr.length() > 0) {
            try {
                len = Integer.parseInt(lenStr);
            } catch (Exception e) {
                throw new IOException("Invalid header for Content-Length!");

            }
        }
        return len;
    }
    
    public void connect() throws Exception
    {
        parseRequestUrl();
        InetSocketAddress addr = new InetSocketAddress(host, port);
        connection = new NSocketConnection(addr);
        connection.connect(timeout);
        
        hasReqHeaderSent = false;
        hasRespHeaderGot = false;
        isConnected = true;
    }
    
    public void close() throws Exception
    {
        if (isConnected) {
            connection.close();
        }
    }
    
    public void resetTimeout(long newTimeout)
    {
        timeout = newTimeout;
    }
    
    public void sendData(byte[] data, int offset, int len) throws Exception
    {
        if (! isConnected) {
            throw new IOException("Connection is not established yet before sending data!");
        }
        
        if (! hasReqHeaderSent) {
            sendRequestHeader();
            hasReqHeaderSent = true;
        }
        
        connection.write(timeout, data, offset, len);        
    }
    
    public void readData(byte[] data, int offset, int len) throws Exception
    {
        ensureHeaderRead();
        
        connection.read(timeout, data, offset, len);        
    }
    
    private void ensureHeaderRead() throws Exception
    {
        if (! isConnected) {
            throw new IOException("Connection is not established yet before reading data!");
        }
        
        if (! hasReqHeaderSent) {
            sendRequestHeader();
            hasReqHeaderSent = true;
        }

        if (! hasRespHeaderGot) {
            readResponseHeader();
            hasRespHeaderGot = true;
        }
        
    }
    
    private void parseRequestUrl() throws Exception
    {
        if (url == null || url.length() == 0) {
            throw new IOException("Request URL shall not be empty!");
        }
        String urlNow = url;
        int place = url.indexOf("://");
        if (place > 0) {
            String protocol = url.substring(0, place);
            if (! "http".equalsIgnoreCase(protocol)) {
                throw new IOException("Unsupport Protocol for request URL [" + url + "]");

            }
            urlNow = url.substring(place + 3);
        }
        place = urlNow.indexOf(':'); // try to find first colon
        int place2 = urlNow.indexOf('/'); // try to find first slash
        if (place < 0) {
            // use default port number 80
            port = 80;
        }
        else if (place == 0) {
            throw new IOException("Invalid request URL. [" + url + "]");
        }
        
        String portStr = null;
        if (place2 < 0) {
            // no more slash found
            path = "/";
            if (place > 0) {
                host = urlNow.substring(0, place);
                portStr = urlNow.substring(place + 1);
            }
            else {
                host = urlNow;
            }
        }
        else if (place2 < place) {
            throw new IOException("Invalid request URL.. [" + url + "]");
        }
        else {
            path = urlNow.substring(place2);
            if (place > 0) {
                host = urlNow.substring(0, place);
                portStr = urlNow.substring(place + 1, place2);
            }
            else {
                host = urlNow.substring(0, place2);
            }
        }
        
        if (portStr != null) {
            System.out.println("== Debug port str:" + portStr);
            try {
                port = Integer.parseInt(portStr);
            } catch (Exception e) {
                throw new IOException("Invalid request URL... [" + url + "]");
            }
        }

    }
    
    private void sendRequestHeader() throws Exception
    {
        StringBuilder sb = new StringBuilder();
        // Append Request Header
        if (isPost) {
            sb.append("POST ");
        }
        else {
            sb.append("GET ");
        }
        sb.append(path);
        sb.append(' ');
        sb.append(HTTP_PROTOCOL);
        sb.append('\r');
        sb.append('\n');
        sb.append("HOST: ");
        sb.append(host);
        sb.append('\r');
        sb.append('\n');
        
        // Append Other Header
        Iterator<Entry<String, String>> headers = requestHeaders.entrySet().iterator();
        while (headers.hasNext()) {
            Entry<String, String> entry = headers.next();
            sb.append( entry.getKey() );
            sb.append(':');
            sb.append(' ');
            sb.append( entry.getValue() );
            sb.append('\r');
            sb.append('\n');
        }
        
        sb.append('\r');
        sb.append('\n');
        
        byte[] data = sb.toString().getBytes();
        connection.write(timeout, data, 0, data.length);
        
    }
    
    private void readResponseHeader() throws Exception
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[32];
        
        boolean isEndOfHeader = false;
        byte bNow;
        
        boolean isPreviousCr = false;
        boolean isCrLfFound  = false;
        while (! isEndOfHeader) {
            connection.read(timeout, buffer, 0, 1); // read one byte every time
            bNow = buffer[0];
            baos.write(bNow);
            
            if (isPreviousCr) {
                if (bNow == '\n') {
                    if (isCrLfFound) {
                        isEndOfHeader = true;
                    }
                    else {
                        isPreviousCr = false;
                        isCrLfFound = true;
                    }
                }
                else {
                    isPreviousCr = false;
                    isCrLfFound = false;
                }
            }
            else {
                if (bNow == '\r') {
                    isPreviousCr = true;
                }
                else {
                    isCrLfFound = false;
                }
            }
           
        }
        
        String respHeader = baos.toString("utf-8");
        String[] headers = respHeader.split("\r\n");
        if (headers == null || headers.length == 0) {
            throw new IOException("Error, no response header received!");
        }
        System.out.println("Got Response Header:");
        System.out.println(respHeader);
        
        int place1;
        int place2;
        // parse status line
        String statusLine = headers[0].trim();
        boolean parseStatusLineDone = false;
        place1 = statusLine.indexOf(' '); // place between protocol and status code
        if (place1 > 0) {
            place2 = statusLine.indexOf(' ', place1 + 1);
            if (place2 > place1) {
                statusText = statusLine.substring(place2 + 1);
                String tmp = statusLine.substring(place1 + 1, place2);
                try {
                    statusCode = Integer.parseInt(tmp);
                    parseStatusLineDone = true;
                } catch (Exception e) {
                    // invalid status code
                }
            }
        }
        if (! parseStatusLineDone) {
            throw new IOException("Error, Invalid Status Line in Response Header [" + statusLine + "]");

        }
        // parse other headers
        responseHeaders.clear();
        String line;
        String key;
        String value;
        for (int i = 1; i < headers.length; i++) {
            line = headers[i].trim();
            place1 = line.indexOf(':');
            if (place1 > 0) {
                key = line.substring(0, place1).trim();
                value = line.substring(place1 + 1).trim();
                responseHeaders.put(key, value);
            }
            else {
                throw new IOException("Error, Invalid Line in Response Header [" + line + "]");
            }
        }
    }
    
    
}