package org.appspot.apprtc.util;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

/**
 * Created by HankWu_Office on 2015/9/7.
 */
public class RoomCheckerThread extends Thread {

    String doFunction = "";
    // RoomCreate, RoomRemove...
    String username = "";
    String password = "";
    String sendHttpRoomId   = ""; // if function is RoomCreate then sendHttpRoomId cannot be empty.

    public RoomCheckerThread(String function,String user,String pass,String roomId) {
        doFunction = function;
        username = user;
        password = pass;
        sendHttpRoomId = roomId;
    }
    public void run() {
        try {
            String ACCESS_METHOD = doFunction;
            String PREFIX_USERNAME   = "Username";
            String PREFIX_PASSWORD   = "Password";
            String PREFIX_ROOMID     = "RoomId";
            String urlString = "http://122.147.15.216/"+ACCESS_METHOD+"?"+PREFIX_USERNAME+"="+username+"&"+PREFIX_PASSWORD+"="+password+"&"+PREFIX_ROOMID+"="+sendHttpRoomId;
            URL url = new URL(urlString);
            URLConnection conn = url.openConnection();
            Log.e("HANK", "connect success! " + urlString);
            InputStream is = conn.getInputStream();

        } catch (IOException e) {
            Log.e("HANK", "open connection fail "+ e);

        }
    }

}
