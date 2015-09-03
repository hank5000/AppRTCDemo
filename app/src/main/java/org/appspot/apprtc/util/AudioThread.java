package org.appspot.apprtc.util;

/**
 * Created by HankWu_Office on 2015/8/28.
 */
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;

public class AudioThread extends Thread {
    AudioOut aout = new AudioOut();

    byte[] abuff_tmp  = new byte[1024*1024];
    byte[] abuff_dec  = new byte[256];
    InputStream is = null;

    private boolean bStart = true;

    public void setStop() {
        bStart = false;
    }

    static {
        System.loadLibrary("adec");
    }

    public AudioThread (InputStream input) {
        this.is = input;
    }

    native void adpcmDecoderInit(int index);
    native byte[] adpcmDecode(byte[] inData,int offset ,int length);

    public void run() {
        aout.init();
        adpcmDecoderInit(1);

        int n = 0;

        while(!Thread.interrupted() && bStart)
        {
            try {
                n = is.read(abuff_tmp);
            } catch (IOException e) {
                Log.d("HANK","audio inputstream read fail : "+e);
            }
            Log.d("HANK","come in length : "+n);
            if(n%256!=0)
            {
                Log.d("HANK","Something wrong");
            }
            int NumberOfAudio = n / 256;

            for(int j=0;j<NumberOfAudio;j++)
            {
                System.arraycopy(abuff_tmp, 256*j, abuff_dec, 0, 256);
                byte[] audioData = adpcmDecode(abuff_dec,0,256);

                aout.playBuffer(audioData, 505*2, 505);
            }
        }
        aout.release();
        try {
            is.close();
        } catch (Exception e) {
            Log.d("HANK","audio inputstream close fail");
        }
    }
}