package com.via.rtc.DataChannelObserver;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import com.via.rtc.PeerConnectionClient;
import com.via.rtc.thread.AudioThread;
import com.via.rtc.util.GlobalSetting;
import com.via.rtc.util.LooperExecutor;
import com.via.rtc.thread.NormalAudioThread;

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

    final static String TAG = "VIA-RTC ADCObserver";

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
        Log.d(TAG,dataChannel.label()+":"+dataChannel.state().toString());
    }

    LocalServerSocket mLss = null;
    LocalSocket mReceiver = null;
    LocalSocket mSender   = null;
    int         mSocketId;
    final String LOCAL_ADDR = "DataChannelToAudioDecodeThread-";
    public OutputStream os = null;
    public WritableByteChannel writableByteChannel = null;
    public InputStream is = null;
    AudioThread at = null;
    NormalAudioThread nat = null;
    boolean bStart = false;
    SourceType sourceType = SourceType.UNKNOWN; // -1 : undefine ,0 : OV ,1 : RTSP
    String setting = null;

    public enum SourceType {
        UNKNOWN,
        OV,
        RTSP,
    }

    public void stopThread() {
        if(at!=null){at.setStop();at.interrupt();at=null;}
        if(nat!=null){nat.setStop();nat.interrupt();nat=null;}
    }



    private boolean isAudioMessage(String msg) {
        return msg.equalsIgnoreCase(AUDIO_PREFIX);
    }

    @Override
    public void onMessage(final DataChannel.Buffer inbuf) {

        ByteBuffer data = inbuf.data;
        if(inbuf.binary) {
            if(bStart) {
                try {
                    int size = data.remaining();

                    GlobalSetting.member.LOGD("received size : " + size);
                    if(writableByteChannel!=null) {
                        writableByteChannel.write(data);
                    } else {
                        Log.e(TAG,"writeableByteChannel is null");
                    }
                } catch (Exception e) {
                    Log.d(TAG,"os write fail:"+e);
                }
            } else {
                Log.e(TAG,"Received data in audio channel but bStart is disable");
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
                Log.d(TAG,audioMsg + command);

                if(msgKey.equalsIgnoreCase("SET")) {

                    if (cmd_split[2].equalsIgnoreCase("OV")) {
                        sourceType = SourceType.OV;
                        setting = "";
                    }else if (cmd_split[2].equalsIgnoreCase("RTSP")) {
                        sourceType = SourceType.RTSP;
                        // collect parameter after SET:
                        // ex: 1. AUDIO:SET:RTSP:AAC:1:8000:1588:.....
                        // ex: 2. AUDIO:SET:RTSP:mG711:1:....
                        // ex: 3. AUDIO:SET:RTSP:aG711:1:....
                        setting = command.substring(10,command.length());
                        Log.d(TAG,setting);
                    }
                }

                if(msgKey.equalsIgnoreCase("START")) {

                    if(sourceType==SourceType.UNKNOWN) {
                        Log.e(TAG,"Audio Something wrong!!");
                        return;
                    }

                    GlobalSetting.member.LOGD("Audio Start");
                    audioMsg = audioMsg + "START";

                    if(at!=null) {
                        at.setStop();
                        at.interrupt();
                        at = null;
                    }

                    if(nat!=null) {
                        nat.setStop();
                        nat.interrupt();
                        nat = null;
                    }

                    for(int jj=0;jj<10;jj++) {
                        try {
                            mSocketId = new Random().nextInt();
                            mLss = new LocalServerSocket(LOCAL_ADDR+mSocketId);
                            break;
                        } catch (IOException e) {
                            Log.e(TAG, "fail to create localserversocket :" + e);
                        }
                    }

                    //    DECODE FLOW
                    //
                    //    Intermediary:                             Localsocket       MediaCodec inputBuffer     MediaCodec outputBuffer
                    //        Flow    : Data Channel =======> Sender ========> Receiver ==================> Decoder =================> Display to surface/ Play by Audio Track
                    //       Thread   : |<---Data Channel thread--->|          |<--------- Decode Thread --------->|                 |<--------- Display/play Thread -------->|
                    //
                    mReceiver = new LocalSocket();
                    try {
                        mReceiver.connect( new LocalSocketAddress(LOCAL_ADDR+mSocketId));
                        mReceiver.setReceiveBufferSize(100000);
                        mReceiver.setSoTimeout(200);
                        mSender = mLss.accept();
                        mSender.setSendBufferSize(100000);
                    } catch (IOException e) {
                        Log.e(TAG, "fail to create mSender mReceiver :" + e);
                        e.printStackTrace();
                    }
                    try {
                        os = mSender.getOutputStream();
                        writableByteChannel = Channels.newChannel(os);
                        is = mReceiver.getInputStream();
                    } catch (IOException e) {
                        Log.e(TAG,"fail to get mSender mReceiver :"+e);
                        e.printStackTrace();
                    }
                    bStart = true;

                    if(sourceType == SourceType.OV) {
                        // using self adpcm_ima decoder to decode audio
                        at = new AudioThread(is);
                        at.start();
                        GlobalSetting.member.LOGD("Audio OV Start");

                    } else if(sourceType == SourceType.RTSP) {
                        // using mediaCodec to decode audio
                        nat = new NormalAudioThread(is,setting);
                        nat.start();
                        GlobalSetting.member.LOGD("Audio RTSP Start");
                    }
                }

                if(msgKey.equalsIgnoreCase("STOP")) {
                    if(bStart) {
                        bStart = false;
                        audioMsg = audioMsg + "STOP";
                        GlobalSetting.member.LOGD("Audio STOP");

                        if(at!=null) {
                            at.setStop();
                            at.interrupt();
                            try {
                                at.join();
                            } catch(InterruptedException e) {
                                e.printStackTrace();
                            }
                            at = null;
                        }

                        if(nat!=null) {
                            nat.setStop();
                            nat.interrupt();
                            try {
                                nat.join();
                            } catch(InterruptedException e) {
                                e.printStackTrace();
                            }
                            nat = null;
                        }

                        try {
                            os.close();
                            is.close();
                            writableByteChannel.close();
                            writableByteChannel = null;
                            os = null;
                            is = null;
                        } catch (Exception e) {
                            Log.e(TAG, "Close input/output stream error : " + e);
                        }
                    }
                }
            }
        }
    }
}
