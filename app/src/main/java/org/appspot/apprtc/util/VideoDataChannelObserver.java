package org.appspot.apprtc.util;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import android.view.SurfaceView;

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
 * Created by HankWu_Office on 2015/8/19.
 */
public class VideoDataChannelObserver implements DataChannel.Observer {
    public final static String VIDEO_PREFIX = "VIDEO";

    private LooperExecutor executor;
    private PeerConnectionClient.PeerConnectionEvents events;
    private SurfaceView surfaceView;
    private DataChannel dataChannel;
    private PeerConnectionClient pcc;
    private int channelNumber = 0;

    public  VideoDataChannelObserver(DataChannel dc, SurfaceView surf, LooperExecutor exe, PeerConnectionClient pc) {
        this.dataChannel = dc;
        this.surfaceView = surf;
        this.pcc = pc;
        this.events = pc.getPeerConnectionEvents();
        this.executor = exe;
        this.channelNumber = Integer.valueOf(dc.label().replaceAll("[^0-9]+",""));

    }

    boolean bLiveView = false;
    boolean bStart    = false;

    public boolean isIdle() {
        return !bStart;
    }

    int     width    = -1;
    int     height   = -1;
    String  mime     = null;
    String  sps      = null;
    String  pps      = null;

    LocalServerSocket mLss = null;
    LocalSocket mReceiver = null;
    LocalSocket mSender   = null;
    int         mSocketId;
    final String LOCAL_ADDR = "com.via.hank-";
    public OutputStream os = null;
    public WritableByteChannel writableByteChannel;
    public InputStream is = null;
    VideoThread vt = null;

    private boolean isVideoMessage(String msg) {
        return msg.equalsIgnoreCase(VIDEO_PREFIX);
    }

    private enum VideoMessageType {
        WIDTH,
        HEIGHT,
        MIME,
        SPS,
        PPS,
        START,
        STOP
    }

    @Override
    public void onBufferedAmountChange(long bufferedByte) {
        // if coming here, maybe the internet is not stable.
        // libjingle will Buffer Sended Data by itself (if internet lag),
        // and show the Number of Buffered Byte (bufferedByte) at here

        // TODO: Maybe set a threshold to control data sending, ex bufferedByte>high_threshold, stop sending, bufferedByte<low_threshold, continue sending.
        //Log.d("HANK","DataChannel.Observer onBufferedAmountChange : "+var1);
    }

    @Override
    public void onStateChange() {
        // TODO: Here will show this data channel state, OPEN CLOSING CLOSE..., if CLOSING then STOP all thread (decode thread...)
        Log.d("HANK", dataChannel.label()+":"+dataChannel.state().toString());
        if(dataChannel.state().toString().equalsIgnoreCase("CLOSING")) {
            if(vt!=null) {
                vt.setStop();
                vt.interrupt();
                vt=null;

                bStart = false;

                os = null;
                writableByteChannel = null;
                is = null;
            }
        }
    }

    @Override
    public void onMessage(final DataChannel.Buffer inbuf) {

        //Log.d("HANK", "DataChannel.Observer onMessage Buffer Received");
        ByteBuffer data = inbuf.data;

        if(inbuf.binary)
        {
            if(bStart) {
                try {
                    writableByteChannel.write(data);
                } catch (Exception e) {
                    Log.d("HANK", "os write fail:"+e);
                }
            } else {
                Log.e("HANK","bStart is not enable, but received binary type buffer ?");
            }
        }
        else
        {
            int size = data.remaining();
            byte[] bytes = new byte[size];
            data.get(bytes);

            String command = new String(bytes);
            String[] cmd_split = command.split(":");
            String msgType = cmd_split[0];
            String msgKey = cmd_split[1];
            String msgValue   = null;
            boolean bShowToast = false;
            if(cmd_split.length==3) {
                msgValue = cmd_split[2];
            }

            if(isVideoMessage(msgType)) {
                String videoMsg = "Get Video ";

                VideoMessageType messageType = VideoMessageType.valueOf(msgKey.toUpperCase());
                switch(messageType)
                {
                    case WIDTH:
                        width = Integer.valueOf(msgValue);
                        break;
                    case HEIGHT:
                        height = Integer.valueOf(msgValue);
                        break;
                    case SPS:
                        sps = msgValue;
                        break;
                    case PPS:
                        pps = msgValue;
                        break;
                    case MIME:
                        mime = msgValue;
                        break;
                    case START:
                        if(vt!=null) {
                            vt.setStop();
                            vt.interrupt();
                            vt = null;
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

                        // Video Thread need input SurfaceView->surface, mime, width, height, sps, pps , inputstream is.
                        executor.execute(new Runnable() {
                            @Override
                            public void run() {
                                events.onPeerMessage("create video thread!");

                            }
                        });
                        vt = new VideoThread(surfaceView.getHolder().getSurface(),mime,width,height,sps,pps,is);
                        vt.start();

                        bLiveView = true;
                        videoMsg = videoMsg + "START,"+width+"x"+height+"("+mime+")";
                        bShowToast = true;
                        bStart = true;

                        break;
                    case STOP:
                        if(bStart) {
                            videoMsg = videoMsg + "STOP";
                            bLiveView = false;
                            bStart = false;
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
                            vt.setStop();
                            vt.interrupt();

                            vt = null;
                            bShowToast = true;

                            pcc.getLiveViewInfo().removeOnChannel(channelNumber);

                        } else {
                            Log.d("HANK","It is already stop lo.");
                        }
                        break;
                }

                if(bShowToast) {
                    final String printMessage = videoMsg;
                    Log.d("HANK", printMessage);
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            events.onPeerMessage(printMessage);
                        }
                    });
                }

            }
        }

    }
}
