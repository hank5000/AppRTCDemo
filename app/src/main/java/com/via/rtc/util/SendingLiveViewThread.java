package com.via.rtc.util;

import android.media.MediaExtractor;

import com.via.rtc.PeerConnectionClient;

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
    final static String TAG = "VIA-RTC SLVThread";

    PeerConnectionClient pc = null;
    DataChannel outChannel = null;
    int channelIndex = -1;
    boolean bAutoLoop = false;
    boolean bStart    = true;
    String ip_address = null;
    InputStream in = null;
    byte[] transferBuffer = new byte[1024*1024];
    ByteBuffer transByteBuffer = null;
    // TODO: Currently using hardcode. Feature:  it need to send from OV Extractor
    final String OV_SPS = "00000001674d0028a9500a00b742000007d00001d4c008";
    final String OV_PPS = "0000000168ee3c8000";

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
        // wait for Default setting
        try {
            this.sleep(1000);
        } catch (Exception e) {
            Log.d(TAG,"Sleep fail");
        }
        LocalSocket localSocket = new LocalSocket();
        try {
            localSocket.connect(new LocalSocketAddress(ip_address + "-video"));
            in = localSocket.getInputStream();

            if(localSocket.isConnected())
            {
                byte[] extra_data = new byte[512];
                int n = in.read(extra_data);
                String extra_msg = new String(extra_data,"UTF-8");
                String[] extra_split = extra_msg.split(":");

                String source_type=extra_split[0];
                String source_width="";
                String source_height="";
                String source_sps = "";
                String source_pps = "";

                if (source_type.equalsIgnoreCase("RTSP")) {
                    Log.d(TAG,"RTSP Source");
                    source_width = extra_split[1];
                    source_height= extra_split[2];
                    // find pps start code
                    int pps_pos  = extra_split[3].indexOf("00000001", 5);
                    source_sps   = extra_split[3].substring(0,pps_pos);
                    source_pps   = extra_split[3].substring(pps_pos, extra_split[3].length());

                } else if (source_type.equalsIgnoreCase("OV")) {
                    //TODO: Remove hardcode
                    Log.d(TAG,"OV Source");

                    source_width = "1280";
                    source_height= "720";
                    source_sps= OV_SPS;
                    source_pps= OV_PPS;
                }
                pc.sendVideoInfo(channelIndex, "MIME", "video/avc");
                pc.sendVideoInfo(channelIndex, "Width", source_width);
                pc.sendVideoInfo(channelIndex, "Height", source_height);
                pc.sendVideoInfo(channelIndex, "sps", source_sps);
                pc.sendVideoInfo(channelIndex, "pps", source_pps);
                pc.sendVideoInfo(channelIndex, "START");
            }
        } catch (IOException e) {
            e.printStackTrace();
            Log.d(TAG,"connect fail");
            pc.sendMessage("Something wrong with Live View, please try again");
            bStart = false;
        }

        // raw data ..
        int minus1Counter = 0;
        while (!Thread.interrupted() && bStart && localSocket.isConnected()) {
            int naluSize = 0;
            try {
                naluSize = in.read(transferBuffer);
                //Log.d(TAG,"read :"+naluSize);
                if(naluSize==-1) {
                    minus1Counter++;
                    Log.d(TAG,"minus1Counter :"+minus1Counter);

                    if(minus1Counter>30) {
                        bStart = false;
                        break;
                    }
                }
            } catch (Exception e) {
                Log.d(TAG,"something wrong at inputstream read : "+e);
                bStart = false;
            }

            if(naluSize>0) {
                minus1Counter = 0;
                transByteBuffer = ByteBuffer.wrap(transferBuffer);
                // 2. Divide NALU data by 1000
                int sentSize = 0;
                for (; ; ) {
                    //Log.d(TAG,"Send NALU("+NALUCount+")  : "+j+"/"+sampleSize);
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
                Log.d(TAG,"LocalSocket close fail");
            }
        }

    }
}


