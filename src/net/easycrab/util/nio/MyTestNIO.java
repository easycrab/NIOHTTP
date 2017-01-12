package net.easycrab.util.nio;

import java.io.ByteArrayOutputStream;
import java.util.Calendar;

public class MyTestNIO
{
    private static String getTimestamp()
    {
        Calendar cal = Calendar.getInstance();
        StringBuilder sb = new StringBuilder();
        int val;
        sb.append(cal.get(Calendar.YEAR));
        
        sb.append('/');
        val = cal.get(Calendar.MONTH) + 1;
        if (val < 10) {
            sb.append('0');
        }
        sb.append(val);
        
        sb.append('/');
        val = cal.get(Calendar.DAY_OF_MONTH);
        if (val < 10) {
            sb.append('0');
        }
        sb.append(val);
        
        sb.append(' ');
        
        val = cal.get(Calendar.HOUR_OF_DAY);
        if (val < 10) {
            sb.append('0');
        }
        sb.append(val);
        
        sb.append(':');
        val = cal.get(Calendar.MINUTE);
        if (val < 10) {
            sb.append('0');
        }
        sb.append(val);
        
        sb.append(':');
        val = cal.get(Calendar.SECOND);
        if (val < 10) {
            sb.append('0');
        }
        sb.append(val);

        return sb.toString();
    }
    
    public static void main(String[] args) {
        NHttpsConnection conn = new NHttpsConnection("https://www.ssllabs.com", false, 5000);
        
        try {
            System.out.println(getTimestamp() + " Ready to connect to server now...");
            conn.connect();
            System.out.println(getTimestamp() + " Server was connected!");

            System.out.println(getTimestamp() + " Reset Timeout Mode as NotOnlyCheckBlockTime!");
            conn.setTimeoutMode(false);
            
//            System.out.println(getTimestamp() + " Connect Done! Sleep 1 seconds ...");
//            Thread.sleep(1000);
//            System.out.println(getTimestamp() + " Wake up! Try to close the connection ...");
//          /*
            System.out.println(getTimestamp() + " Try to get the statusCode ...");
            int code = conn.getResponseStatusCode();
            String statusText = conn.getResponseStatusText();
            System.out.println(getTimestamp() + " StatusCode = " + code + " StatusText:" + statusText);
            
            int contentLen = conn.getContentLength();
            System.out.println(getTimestamp() + " ContentLength = " + contentLen);
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int targetGetLen;
            String content;
            if (conn.isChunked()) {
                conn.readAllChunk(baos);
                System.out.println(getTimestamp() + " Total Chunk Size = " + baos.size());
                
                if (conn.isGzipContent()) {
                    System.out.println(getTimestamp() + " response body is GZIP, need decompress first");
                    byte[] gzipData = baos.toByteArray();
                    baos.reset();
                    conn.decompressGzipData(gzipData, baos);
                }
                
                System.out.println(getTimestamp() + " Response Body Data Length: " + baos.size());
                
                targetGetLen = (baos.size() > 1024) ? 1024 : baos.size();
                content = new String(baos.toByteArray(), 0, targetGetLen, "utf-8");
                System.out.println(content);

            
            }
            else if (contentLen > 0) {
                byte[] body = new byte[contentLen];
                System.out.println(getTimestamp() + " Try to receive connection body ...");
                conn.readData(body, 0, contentLen);
                if (conn.isGzipContent()) {
                    System.out.println(getTimestamp() + " response body is GZIP, need decompress first");
                    conn.decompressGzipData(body, baos);

                    targetGetLen = (baos.size() > 1024) ? 1024 : baos.size();
                    content = new String(baos.toByteArray(), 0, targetGetLen, "utf-8");
                    System.out.println(content);
                }
                else {
                    targetGetLen = (contentLen > 1024) ? 1024 : contentLen;
                    content = new String(body, 0, targetGetLen, "utf-8");
                    System.out.println(content);
                }

                
                System.out.println(getTimestamp() + " Done!");

            }
//          */
            System.out.println(getTimestamp() + " Try to close the connection ...");
            conn.close();
            System.out.println(getTimestamp() + " Connection closed!");
        } catch (Exception e) {
            e.printStackTrace();
        }
        
    }

}
