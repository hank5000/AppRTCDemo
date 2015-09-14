package org.appspot.apprtc.util;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import org.appspot.apprtc.PeerConnectionClient;
import org.webrtc.DataChannel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class SendingLiveViewAudioThread extends Thread {
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
            pc.sendAudioInfo(channelIndex, "START");

        } catch (IOException e) {
            Log.d("HANK", "connect fail");
            pc.sendMessage("Something wrong with Live View, please try again");
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
                Log.d("HANK","audio inpustream read fail "+e);
            }
            //Log.d("HANK","audio inpustream read size "+n);

        }

        try {
            instream.close();
            pc.sendAudioInfo(channelIndex, "STOP");
        } catch (Exception e) {
            Log.d("HANK","audio inpustream close fail");
        }

    }

}