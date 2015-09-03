package org.appspot.apprtc.util;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import org.appspot.apprtc.PeerConnectionClient;
import org.webrtc.DataChannel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.Random;

/**
 * Created by HankWu_Office on 2015/8/20.
 */
public class AudioDataChannelObserver implements DataChannel.Observer {
    public final static String AUDIO_PREFIX = "AUDIO";
    // Giving it executor and events, such that can call function at PeerConnectionClient.
    private LooperExecutor executor;
    private PeerConnectionClient.PeerConnectionEvents events;
    private DataChannel dataChannel;

    public AudioDataChannelObserver(DataChannel dc, LooperExecutor exe, PeerConnectionClient.PeerConnectionEvents eve) {
        this.dataChannel = dc;
        this.executor = exe;
        this.events   = eve;
    }

    @Override
    public void onBufferedAmountChange(long var1) {

    }
    @Override
    public void onStateChange() {
        Log.d("HANK",dataChannel.label()+":"+dataChannel.state().toString());
    }


    LocalServerSocket mLss = null;
    LocalSocket mReceiver = null;
    LocalSocket mSender   = null;
    int         mSocketId;
    final String LOCAL_ADDR = "com.via.hank-";
    public OutputStream os = null;
    public WritableByteChannel writableByteChannel = null;
    public InputStream is = null;
    AudioThread at = null;
    boolean bStart = false;

    private boolean isAudioMessage(String msg) {
        return msg.equalsIgnoreCase(AUDIO_PREFIX);
    }


    @Override
    public void onMessage(final DataChannel.Buffer inbuf) {

        ByteBuffer data = inbuf.data;
        if(inbuf.binary) {
            if(bStart) {
                try {
                    if(writableByteChannel!=null) {
                        writableByteChannel.write(data);
                    } else {
                        Log.e("HANK","writeableByteChannel is null");
                    }
                } catch (Exception e) {
                    Log.d("HANK","os write fail:"+e);
                }
            }
        } else {
            int size = data.remaining();
            byte[] bytes = new byte[size];
            data.get(bytes);

            String command = new String(bytes);
            String[] cmd_split = command.split(":");
            String msgType = cmd_split[0];
            String msgKey = cmd_split[1];

            if(isAudioMessage(msgType)) {
                String audioMsg = "Get Audio: ";

                if(msgKey.equalsIgnoreCase("START")) {
                    audioMsg = audioMsg + "START";

                    if(at!=null) {
                        at.setStop();
                        at.interrupt();
                        at = null;
                    }
                    for(int jj=0;jj<10;jj++) {
                        try {
                            mSocketId = new Random().nextInt();
                            mLss = new LocalServerSocket(LOCAL_ADDR+mSocketId);
                            break;
                        } catch (IOException e) {
                            Log.e("HANK", "fail to create localserversocket :" + e);
                        }
                    }

                    mReceiver = new LocalSocket();
                    try {
                        mReceiver.connect( new LocalSocketAddress(LOCAL_ADDR+mSocketId));
                        mReceiver.setReceiveBufferSize(100000);
                        mReceiver.setSoTimeout(3000);
                        mSender = mLss.accept();
                        mSender.setSendBufferSize(100000);
                    } catch (IOException e) {
                        Log.e("HANK", "fail to create mSender mReceiver :" + e);
                        e.printStackTrace();
                    }
                    try {
                        os = mSender.getOutputStream();
                        writableByteChannel = Channels.newChannel(os);
                        is = mReceiver.getInputStream();
                    } catch (IOException e) {
                        Log.e("HANK","fail to get mSender mReceiver :"+e);
                        e.printStackTrace();
                    }
                    bStart = true;

                    at = new AudioThread(is);
                    at.start();
                }

                if(msgKey.equalsIgnoreCase("STOP")) {
                    if(bStart) {
                        audioMsg = audioMsg + "STOP";

                        try {
                            os.close();
                            is.close();
                            writableByteChannel.close();
                            writableByteChannel = null;
                            os = null;
                            is = null;
                        } catch (Exception e) {
                            Log.e("HANK", "Close input/output stream error : " + e);
                        }
                        at.setStop();
                        at.interrupt();
                        at = null;
                        bStart = false;
                    }
                }



            }





        }


    }
}
