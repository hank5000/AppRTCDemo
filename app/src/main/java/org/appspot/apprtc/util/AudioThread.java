package org.appspot.apprtc.util;

/**
 * Created by HankWu_Office on 2015/8/28.
 */
import android.util.Log;

public class AudioThread extends Thread {
    AudioOut aout = new AudioOut();

    byte[] abuff_tmp  = new byte[1024*1024];
    byte[] abuff_dec  = new byte[256];

    static {
        System.loadLibrary("adec");
    }
    native void adpcmDecoderInit(int index);
    native short[] adpcmDecode(byte[] inData,int offset ,int length);

    public void run() {
        aout.init();
        adpcmDecoderInit(1);


        int n = 0;

        while(!Thread.interrupted())
        {

            //Log.d("HANK","come in length : "+n+",now buffer position:"+cur_position);
            if(n%256!=0)
            {
                Log.d("HANK","Something wrong");
            }
            int NumberOfAudio = n / 256;

            for(int j=0;j<NumberOfAudio;j++)
            {
                System.arraycopy(abuff_tmp, 256*j, abuff_dec, 0, 256);
                short[] audioData =adpcmDecode(abuff_dec,0,256);
                aout.playBuffer(audioData, 505*2, 505);
            }
        }

        aout.release();


    }
}