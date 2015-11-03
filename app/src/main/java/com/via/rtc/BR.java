package com.via.rtc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Created by HankWu_Office on 2015/10/1.
 */
public class BR extends BroadcastReceiver {
    public static final String START = "com.via.rtc.start";
    public static final String STOP  = "com.via.rtc.stop";



    ServerService ss = null;
    @Override
    public void onReceive(Context context, Intent intent) {
        if(intent.getAction().equals(START)) {
            ss = new ServerService();
            ss.StartServer();
        }
    }

}
