package org.appspot.apprtc.util;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceView;

import org.appspot.apprtc.PeerConnectionClient;
import org.webrtc.DataChannel;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Random;

/**
 * Created by HankWu_Office on 2015/8/20.
 */
public class DCObserverMix implements DataChannel.Observer {
    private LooperExecutor executor;
    private PeerConnectionClient.PeerConnectionEvents events;
    private SurfaceView surfaceView;

    boolean bNoReceived = true;

    public  DCObserverMix(SurfaceView surf, PeerConnectionClient.PeerConnectionEvents eve, LooperExecutor exe) {
        this.surfaceView = surf;
        this.events = eve;
        this.executor = exe;
        bNoReceived = false;
    }

    public DCObserverMix() {
        bNoReceived = true;
    }


    boolean bSendFile = false;
    FileOutputStream fout = null;
    DataOutputStream dout = null;
    File f = null;
    String  sFileName = "";
    boolean bLiveView = false;
    boolean bAudio    = false;
    boolean bVideo    = false;

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
    public InputStream is = null;
    VideoThread vt = null;

    public class VideoThread extends Thread {
        byte[] vbuff_tmp = new byte[1024*1024];
        ByteBuffer bb_tmp = ByteBuffer.allocate(1024*1024*10);
        byte[] dst  = new byte[1024*1024];
        private MediaCodec decoder;
        private Surface surface;

        String mMime;
        int    mWidth;
        int    mHeight;
        String mSPS;
        String mPPS;

        public byte[] hexStringToByteArray(String s) {
            int len = s.length();
            byte[] data = new byte[len / 2];
            for (int i = 0; i < len; i += 2) {
                data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                        + Character.digit(s.charAt(i+1), 16));
            }
            return data;
        }


        public VideoThread(Surface surf,String mime,int width,int height,String sps,String pps) {
            this.surface  = surf;
            this.mMime    = mime;
            this.mWidth   = width;
            this.mHeight  = height;
            this.mSPS     = sps;
            this.mPPS     = pps;
        }

        public void run() {

            try {
                MediaFormat format = new MediaFormat();
                format.setString(MediaFormat.KEY_MIME, mMime);
                format.setInteger(MediaFormat.KEY_WIDTH, mWidth);
                format.setInteger(MediaFormat.KEY_HEIGHT, mHeight);

                decoder = MediaCodec.createDecoderByType(mMime);

                byte[] sps = hexStringToByteArray(mSPS);
                byte[] pps = hexStringToByteArray(mPPS);
                ByteBuffer sps_bb = ByteBuffer.wrap(sps);
                ByteBuffer pps_bb = ByteBuffer.wrap(pps);
                format.setByteBuffer("csd-0", sps_bb);
                format.setByteBuffer("csd-1", pps_bb);

                decoder.configure(format, surface, null, 0);

            } catch (Exception e) {
                Log.d("HANK", "This device cannot support codec :" + mMime);
            }


            if (decoder == null) {
                Log.e("DecodeActivity", "Can't find video info!");
                return;
            }

            decoder.start();

            ByteBuffer[] inputBuffers = decoder.getInputBuffers();
            ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            int n = 0;
            int currentLength = 0;
            int frameCount = 0;
            int j = 0;
            while (!Thread.interrupted()) {
                int inIndex = decoder.dequeueInputBuffer(10000);
                if (inIndex > 0) {

                    while (!Thread.interrupted()) {
                        try {
                            n = is.read(vbuff_tmp);
                            bb_tmp.put(vbuff_tmp, 0, n);
                        } catch (Exception e) {
                            Log.d("HANK","inputstream cannot read : "+e);
                            n = 0;
                        }
                        currentLength = bb_tmp.position();

                        bb_tmp.flip();

                        int length = (bb_tmp.get(0)<<0)&0x000000ff | (bb_tmp.get(1)<<8)&0x0000ff00 | (bb_tmp.get(2)<<16)&0x00ff0000| (bb_tmp.get(3)<<24)&0xff000000;
                        if((length+4)<=(currentLength) && length >0 )
                        {
                            Log.d("HANK", "Get NALU length : " + length );
                            bb_tmp.get();bb_tmp.get();bb_tmp.get();bb_tmp.get();
                            bb_tmp.get(dst, 0, length);
                            bb_tmp.compact();
                            ByteBuffer buffer = inputBuffers[inIndex];
                            buffer.clear();
                            buffer.put(dst, 0, length);

                            decoder.queueInputBuffer(inIndex, 0, length, j++, 0);
                            frameCount++;
                            Log.d("HANK","Receive Frame Count : "+frameCount);
                            break;
                        }
                        else
                        {
                            bb_tmp.compact();
                            try {
                                sleep(10);
                            } catch (InterruptedException e) {
                                // TODO Auto-generated catch block
                                e.printStackTrace();
                            }
                        }
                    }
                }

                int outIndex = decoder.dequeueOutputBuffer(info, 10000);
                switch (outIndex) {
                    case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                        Log.d("DecodeActivity", "INFO_OUTPUT_BUFFERS_CHANGED");
                        outputBuffers = decoder.getOutputBuffers();
                        break;
                    case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                        Log.d("DecodeActivity", "New format " + decoder.getOutputFormat());
                        break;
                    case MediaCodec.INFO_TRY_AGAIN_LATER:
                        Log.d("DecodeActivity", "dequeueOutputBuffer timed out!");
                        break;
                    default:
                        ByteBuffer buffer = outputBuffers[outIndex];
                        //Log.v("DecodeActivity", "We can't use this buffer but render it due to the API limit, " + buffer);
                        try {
                            sleep(10);
                        } catch (InterruptedException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                        decoder.releaseOutputBuffer(outIndex, true);
                        break;
                }

                // All decoded frames have been rendered, we can stop playing now
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d("DecodeActivity", "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
                    break;
                }
            }

            decoder.stop();
            decoder.release();
        }
    }


    @Override
    public void onBufferedAmountChange(long var1) {
        Log.d("HANK","DataChannel.Observer onBufferedAmountChange : "+var1);
    }
    @Override
    public void onStateChange() {

    }
    @Override
    public void onMessage(final DataChannel.Buffer buffer) {
        //Log.d("HANK", "DataChannel.Observer onMessage Buffer Received");
        ByteBuffer data = buffer.data;
        int size = data.remaining();
        byte[] bytes = new byte[size];
        data.get(bytes);
        if(buffer.binary)
        {
            if(bSendFile)
            {
                try {
                    dout.write(bytes);
                } catch (Exception e) {
                    final String printCommand = "file : "+sFileName+" write fail";
                    Log.d("HANK",printCommand);
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            events.onPeerMessage(printCommand);
                        }
                    });
                }
            }
            else if(bVideo) {
                try {
                    os.write(bytes);
                } catch (Exception e) {
                    Log.d("HANK","os write fail");
                }
            }
        }
        else
        {
            String command = new String(bytes);
            String[] cmd_split = command.split(":");
            if(cmd_split[0].equalsIgnoreCase("File"))
            {
                if(cmd_split[1].equalsIgnoreCase("END")) {
                    bSendFile = false;
                    String rel = "OK";
                    try {
                        dout.close();
                        fout.close();
                    } catch (Exception e) {
                        Log.d("HANK","fout close fail");
                        rel = "NO OK";
                    }
                    dout = null;
                    fout = null;

                    final String printCommand = "file : "+sFileName+" Complete,"+"Close File: "+rel;
                    Log.d("HANK",printCommand);
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            events.onPeerMessage(printCommand);
                        }
                    });
                }
                else {
                    sFileName = cmd_split[1];
                    bSendFile = true;
                    String rel = "OK";

                    // TODO: Need to Refine to external stroage, or user select.
                    String PadFoneStoragePath = "/storage/emulated/0/Download/";

                    f = new File(PadFoneStoragePath+sFileName);
                    try {
                        fout = new FileOutputStream(f);
                        dout = new DataOutputStream(fout);
                    } catch (Exception e) {
                        rel = "NO OK";
                        bSendFile = false;
                        fout = null;
                        dout = null;
                        f = null;
                    }

                    final String printCommand = "Start to receive file : "+sFileName+"Create File: "+rel;
                    Log.d("HANK",printCommand);
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            events.onPeerMessage(printCommand);
                        }
                    });
                }
            } else if(cmd_split[0].equalsIgnoreCase("MSG")) {
                String msgContent = cmd_split[1];
                final String printMssage = "Receive Message : "+ msgContent;
                Log.d("HANK",printMssage);
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        events.onPeerMessage(printMssage);
                    }
                });
            } else if(cmd_split[0].equalsIgnoreCase("VIDEO")) {
                String videomsg = "Get Video ";
                if(cmd_split[1].equalsIgnoreCase("Width")) {
                    width = Integer.valueOf(cmd_split[2]);
                    videomsg = videomsg + "Width : "+width;
                } else if (cmd_split[1].equalsIgnoreCase("Height")) {
                    height = Integer.valueOf(cmd_split[2]);
                    videomsg = videomsg + "Height : "+height;
                } else if (cmd_split[1].equalsIgnoreCase("MIME")) {
                    mime   = cmd_split[2];
                    videomsg = videomsg + "MIME : "+mime;
                } else if (cmd_split[1].equalsIgnoreCase("sps")) {
                    sps = cmd_split[2];
                    videomsg = videomsg + "sps : "+sps;
                } else if (cmd_split[1].equalsIgnoreCase("pps")) {
                    pps = cmd_split[2];
                    videomsg = videomsg + "pps : "+pps;
                } else if (cmd_split[1].equalsIgnoreCase("START")) {

                    bVideo = true;

                    for(int jj=0;jj<10;jj++) {
                        try {
                            mSocketId = new Random().nextInt();
                            mLss = new LocalServerSocket(LOCAL_ADDR+mSocketId);
                        } catch (IOException e) {
                            Log.d("HANK","fail to create localserversocket");
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
                        // TODO Auto-generated catch block
                        Log.d("HANK","fail to create mSender mReceiver");
                        e.printStackTrace();
                    }
                    try {
                        os = mSender.getOutputStream();
                        is = mReceiver.getInputStream();
                    } catch (IOException e) {
                        // TODO Auto-generated catch block
                        Log.d("HANK","fail to get mSender mReceiver");
                        e.printStackTrace();
                    }
                    vt = new VideoThread(surfaceView.getHolder().getSurface(),mime,width,height,sps,pps);
                    vt.start();

                    bLiveView = true;
                    videomsg = videomsg + "START,"+width+"x"+height+"("+mime+")";

                } else if (cmd_split[1].equalsIgnoreCase("STOP")) {
                    videomsg = videomsg + "STOP";
                    bVideo = false;
                    bLiveView = false;
                    vt.interrupt();
                    vt = null;
                }

                final String printMessage = videomsg;
                Log.d("HANK",printMessage);
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
