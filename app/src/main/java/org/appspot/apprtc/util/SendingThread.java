package org.appspot.apprtc.util;

import android.media.MediaExtractor;

import org.appspot.apprtc.PeerConnectionClient;

import java.nio.ByteBuffer;

import org.webrtc.DataChannel;

import android.media.MediaFormat;
import android.provider.MediaStore;
import android.util.Log;

/**
 * Created by HankWu_Office on 2015/8/25.
 */
public class SendingThread extends Thread {
    PeerConnectionClient pc = null;
    MediaExtractor mediaExtractor = null;
    ByteBuffer naluBuffer = ByteBuffer.allocate(1024 * 1024);
    DataChannel outChannel = null;
    int channelIndex = -1;
    boolean bAutoLoop = false;
    boolean bStart    = true;

    public SendingThread(PeerConnectionClient pcc, MediaExtractor me, int channel_index) {
        channelIndex = channel_index;
        pc = pcc;
        mediaExtractor = me;
        outChannel = pc.getVideoDataChannel(channel_index);
    }

    public void stopThread() {
        bStart = false;
    }

    @Override
    public void run() {

        int Count = mediaExtractor.getTrackCount();
        MediaFormat mf = null;
        String mime = null;
        for (int i = 0; i < Count; i++) {
            mf = mediaExtractor.getTrackFormat(i);
            mime = mf.getString(MediaFormat.KEY_MIME);
            if (mime.subSequence(0, 5).equals("video")) {
                mediaExtractor.selectTrack(i);
                break;
            }
        }

        pc.sendVideoInfo(channelIndex, "MIME", mf.getString(MediaFormat.KEY_MIME));
        pc.sendVideoInfo(channelIndex, "Width", "" + mf.getInteger(MediaFormat.KEY_WIDTH));
        pc.sendVideoInfo(channelIndex, "Height", "" + mf.getInteger(MediaFormat.KEY_HEIGHT));
        ByteBuffer sps_b = mf.getByteBuffer("csd-0");
        byte[] sps_ba = new byte[sps_b.remaining()];
        sps_b.get(sps_ba);
        pc.sendVideoInfo(channelIndex, "sps", pc.bytesToHex(sps_ba));

        mf.getByteBuffer("csd-1");
        ByteBuffer pps_b = mf.getByteBuffer("csd-1");
        byte[] pps_ba = new byte[pps_b.remaining()];
        pps_b.get(pps_ba);
        pc.sendVideoInfo(channelIndex, "pps", pc.bytesToHex(pps_ba));
        pc.sendVideoInfo(channelIndex, "Start");

        int NALUCount = 0;
        byte[] naluSizeByte = new byte[4];
        long startTime = 0;
        long endTime   = 0;
        while (!Thread.interrupted() && bStart) {
            int naluSize = mediaExtractor.readSampleData(naluBuffer, 0);
            //Log.d("HANK", "readSampleData : " + sampleSize);
            if (naluSize > 0) {
                NALUCount++;
                //Log.d("HANK", "Send NALU length : " + sampleSize);
                startTime = System.currentTimeMillis();

                // 1. Send NALU Size to connected device first.
                naluSizeByte[0] = (byte) (naluSize & 0x000000ff);
                naluSizeByte[1] = (byte) ((naluSize & 0x0000ff00) >> 8);
                naluSizeByte[2] = (byte) ((naluSize & 0x00ff0000) >> 16);
                naluSizeByte[3] = (byte) ((naluSize & 0xff000000) >> 24);
                outChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(naluSizeByte), true));

                // 2. Divide NALU data by 1000
                int sentSize = 0;
                for (;;) {
                    //Log.d("HANK","Send NALU("+NALUCount+")  : "+j+"/"+sampleSize);
                    int dividedSize = pc.DATA_CHANNEL_DIVIDE_SIZE_BYTE;
                    if ((naluSize - sentSize) < dividedSize) {
                        dividedSize = naluSize - sentSize;
                    }
                    //using bytebuffer.slice() , maybe decrease once memory copy
                    naluBuffer.position(sentSize);
                    naluBuffer.limit(dividedSize + sentSize);
                    if(Thread.interrupted())
                    {
                        break;
                    }
                    outChannel.send(new DataChannel.Buffer(naluBuffer.slice(), true));
                    naluBuffer.limit(naluBuffer.capacity());

                    sentSize = sentSize + dividedSize;
                    if (sentSize >= naluSize) {
                        break;
                    }
                }

                endTime = System.currentTimeMillis();
                if((endTime-startTime)<33 && (endTime-startTime)>=0) {
                    try {
                        // TODO: Using a good method to sync video
                        // assume it is 30fs currently.
                        Thread.sleep(33-(endTime-startTime));
                        //Log.d("HANK","Sleep : "+(33-(endTime-startTime))+"ms");
                    } catch (Exception e) {
                        Log.d("HANK", "Sleep Error");
                    }
                }

                naluBuffer.clear();
                mediaExtractor.advance();
            } else {
                Log.d("HANK", "No Frame lo.");

                if(bAutoLoop) {
                    Log.d("HANK", "Enable Auto Loop");
                    mediaExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                } else {
                    Log.d("HANK", "Auto Loop is disable");
                    pc.sendVideoInfo(channelIndex, "STOP");
                    break;
                }
            }
        }
        pc.sendVideoInfo(channelIndex, "STOP");
    }
}


