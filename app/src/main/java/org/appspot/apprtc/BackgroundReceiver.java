package org.appspot.apprtc;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
/**
 * Created by HankWu_Office on 2015/9/9.
 */
public class BackgroundReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // TODO Auto-generated method stub
        if(intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED))
        {
            Log.d("ServerReceiver","Recevie boot complete intent");
            context.startService(ServerService.getIntentServerStart());
            Log.d("ServerReceiver","start Server Service");
        }
    }


}
