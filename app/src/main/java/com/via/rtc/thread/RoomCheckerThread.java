package com.via.rtc.thread;

import android.util.Log;

import com.via.rtc.util.PostToPairingServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;

/**
 * Created by HankWu_Office on 2015/9/7.
 */
public class RoomCheckerThread extends Thread {
    final private String TAG = "VIA-RTC RoomChecker";
    final static String ROOM_CREATE = "RoomCreate";
    final static String ROOM_GET = "RoomGet";
    final static String ROOM_REMOVE = "RoomRemove";
    private String doFunction = "";
    // RoomCreate, RoomRemove...
    private String username = "";
    private String password = "";
    private String sendHttpRoomId   = ""; // if function is RoomCreate then sendHttpRoomId cannot be empty.

    public RoomCheckerThread(String function,String user,String pass,String roomId) {
        doFunction = function;
        username = user;
        password = pass;
        sendHttpRoomId = roomId;
    }
    //TODO: using POST Method
    public void run() {
        try {
            String ACCESS_METHOD = doFunction;
            String PREFIX_USERNAME   = "username";
            String PREFIX_PASSWORD   = "password";
            String PREFIX_ROOMID     = "roomid";

            String postParameters =
                    PREFIX_USERNAME +"=" + URLEncoder.encode(username, "UTF-8") +
                            "&"+PREFIX_PASSWORD+"=" + URLEncoder.encode(password, "UTF-8")+
                            "&"+PREFIX_ROOMID+"=" + URLEncoder.encode(sendHttpRoomId,"UTF-8");

            PostToPairingServer.excutePost(doFunction,postParameters);

        } catch (IOException e) {
            Log.e(TAG, "open connection fail "+ e);

        }
    }

}
