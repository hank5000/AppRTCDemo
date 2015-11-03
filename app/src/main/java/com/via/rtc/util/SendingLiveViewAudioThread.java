package com.via.rtc.util;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import com.via.rtc.PeerConnectionClient;
import org.webrtc.DataChannel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class SendingLiveViewAudioThread extends Thread {
    final static String TAG = "VIA-RTC SLVAudioThread";

    DataChannel outChannel = null;
    InputStream instream = null;
    byte[] audioBuffer = new byte[1024*1024];
    ByteBuffer audioByteBuffer = null;
    PeerConnectionClient pc = null;
    int channelIndex = -1;
    String ipAddress = "";
    boolean bStart = true;


    public void stopThread() {
        bStart = false;
    }

    public SendingLiveViewAudioThread(PeerConnectionClient pcc, int channel_index, String ip) {
        this.channelIndex = channel_index;
        this.pc = pcc;
        this.outChannel = pc.getAudioDataChannel(channel_index);
        this.ipAddress = ip;
    }

    public void run() {
        LocalSocket localSocket = new LocalSocket();
        try {
            localSocket.connect(new LocalSocketAddress(ipAddress + "-audio"));
            instream = localSocket.getInputStream();

            byte[] first_data = new byte[512];
            int n = instream.read(first_data);
            String first_msg = new String(first_data,"UTF-8");
            Log.d(TAG,"get first_data : "+first_msg);
            pc.sendAudioInfo(channelIndex, "SET:"+first_msg);


            pc.sendAudioInfo(channelIndex, "START");

        } catch (IOException e) {
            Log.d(TAG, "connect fail");
            pc.sendMessage(ipAddress+" has no audio");
            bStart = false;
        }

        int n = 0;
        while(!Thread.interrupted() && bStart) {
            n = 0;
            try {
                n = instream.read(audioBuffer);
                audioByteBuffer = ByteBuffer.wrap(audioBuffer);

                audioByteBuffer.limit(n);
                outChannel.send(new DataChannel.Buffer(audioByteBuffer.slice(), true));
                audioByteBuffer.limit(audioByteBuffer.capacity());

            } catch (IOException e) {
                Log.d(TAG,"audio inpustream read fail "+e);
            }
        }

        try {
            instream.close();
            pc.sendAudioInfo(channelIndex, "STOP");
        } catch (Exception e) {
            Log.d(TAG,"audio inpustream close fail");
        }

    }

}