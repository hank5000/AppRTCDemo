package org.appspot.apprtc.util;

import android.media.MediaExtractor;

import org.appspot.apprtc.PeerConnectionClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import org.webrtc.DataChannel;

import android.util.Log;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

/**
 * Created by HankWu_Office on 2015/8/25.
 */
public class SendingLiveViewThread extends Thread {
    PeerConnectionClient pc = null;
    DataChannel outChannel = null;
    int channelIndex = -1;
    boolean bAutoLoop = false;
    boolean bStart    = true;
    String ip_address = null;
    InputStream in = null;
    byte[] transferBuffer = new byte[1024*1024];
    ByteBuffer transByteBuffer = null;

    public SendingLiveViewThread(PeerConnectionClient pcc, int channel_index, String ip) {
        channelIndex = channel_index;
        pc = pcc;
        outChannel = pc.getVideoDataChannel(channel_index);
        ip_address = ip;
    }

    public void stopThread() {
        bStart = false;
    }

    @Override
    public void run() {
        // OV Camera default setting
        try {
            this.sleep(1000);
        } catch (Exception e) {
            Log.d("HANK","Sleep fail");
        }
        LocalSocket localSocket = new LocalSocket();

        try {
            localSocket.connect(new LocalSocketAddress(ip_address + "-video"));
            in = localSocket.getInputStream();


            pc.sendVideoInfo(channelIndex, "MIME", "video/avc");
            pc.sendVideoInfo(channelIndex, "Width", "1280");
            pc.sendVideoInfo(channelIndex, "Height", "720");
            pc.sendVideoInfo(channelIndex, "sps", "00000001674d0028a9500a00b742000007d00001d4c008");
            pc.sendVideoInfo(channelIndex, "pps", "0000000168ee3c8000");
            pc.sendVideoInfo(channelIndex, "START");


        } catch (IOException e) {
            e.printStackTrace();
            Log.d("HANK","connect fail");
            pc.sendMessage("Something wrong with Live View, please try again");
            bStart = false;
        }

        int minus1Counter = 0;
        while (!Thread.interrupted() && bStart && localSocket.isConnected()) {
            int naluSize = 0;
            try {
                naluSize = in.read(transferBuffer);
                Log.d("HANK","read :"+naluSize);
                if(naluSize==-1) {
                    minus1Counter++;
                    Log.d("HANK","minus1Counter :"+minus1Counter);

                    if(minus1Counter>30) {
                        bStart = false;
                        break;
                    }
                }
            } catch (Exception e) {
                Log.d("HANK","something wrong at inputstream read : "+e);
                bStart = false;
            }

            if(naluSize>0) {
                minus1Counter = 0;
                transByteBuffer = ByteBuffer.wrap(transferBuffer);
                // 2. Divide NALU data by 1000
                int sentSize = 0;
                for (; ; ) {
                    //Log.d("HANK","Send NALU("+NALUCount+")  : "+j+"/"+sampleSize);
                    int dividedSize = pc.DATA_CHANNEL_DIVIDE_SIZE_BYTE;
                    if ((naluSize - sentSize) < dividedSize) {
                        dividedSize = naluSize - sentSize;
                    }
                    //using bytebuffer.slice() , maybe decrease once memory copy
                    transByteBuffer.position(sentSize);
                    transByteBuffer.limit(dividedSize + sentSize);
                    if (Thread.interrupted()) {
                        break;
                    }
                    outChannel.send(new DataChannel.Buffer(transByteBuffer.slice(), true));
                    transByteBuffer.limit(transByteBuffer.capacity());

                    sentSize = sentSize + dividedSize;
                    if (sentSize >= naluSize) {
                        break;
                    }
                }
            }
        }
        while(true) {
            try {
                localSocket.close();
                pc.sendVideoInfo(channelIndex, "STOP");
                break;
            } catch (Exception e) {
                Log.d("HANK","LocalSocket close fail");
            }
        }

    }
}


