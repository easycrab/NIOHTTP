package net.easycrab.util.nio;

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
//        NHttpConnection conn = new NHttpConnection("http://101.231.126.26/share/tmp/bazi.apk", false, 5000);
        NHttpsConnection conn = new NHttpsConnection("vip.163.com", false, 5000);
        
        try {
            System.out.println(getTimestamp() + " Ready to connect to server now...");
            conn.connect();
//            System.out.println(getTimestamp() + " Connect Done! Sleep 1 seconds ...");
//            Thread.sleep(1000);
//            System.out.println(getTimestamp() + " Wake up! Try to close the connection ...");
            System.out.println(getTimestamp() + " Try to get the statusCode ...");
            int code = conn.getResponseStatusCode();
            String statusText = conn.getResponseStatusText();
            System.out.println(getTimestamp() + " StatusCode = " + code + " StatusText:" + statusText);
            
            int contentLen = conn.getContentLength();
            System.out.println(getTimestamp() + " ContentLength = " + contentLen);
            
            if (contentLen > 0) {
                int targetGetLen = (contentLen > 1024) ? 1024 : contentLen;
                byte[] body = new byte[targetGetLen];
                System.out.println(getTimestamp() + " Try to receive connection body ...");
                conn.readData(body, 0, targetGetLen);
                
                String content = new String(body, "utf-8");
                System.out.println(getTimestamp() + " Done!");
                System.out.println(content);

            }

            System.out.println(getTimestamp() + " Try to close the connection ...");
            conn.close();
            System.out.println(getTimestamp() + " Connection closed!");
        } catch (Exception e) {
            e.printStackTrace();
        }
        
    }

}
