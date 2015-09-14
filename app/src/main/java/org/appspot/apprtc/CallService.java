package org.appspot.apprtc;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Created by HankWu_Office on 2015/9/9.
 */
public class CallService extends Service {

    public CallService() {
        super();
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}
