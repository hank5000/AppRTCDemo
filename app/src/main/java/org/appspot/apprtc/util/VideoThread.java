package org.appspot.apprtc.util;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Created by HankWu_Office on 2015/8/20.
 */
public class VideoThread extends Thread {

    byte[] inputStreamTmp = new byte[1024*1024];
    ByteBuffer rawDataCollectBuffer = ByteBuffer.allocate(1024*1024*10);
    byte[] dst  = new byte[1024*1024];
    private MediaCodec decoder;
    private Surface surface;
    private InputStream is;
    private boolean bStart = true;

    public void setStop() {
        bStart = false;
    }

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

    public VideoThread(Surface surf,String mime,int width,int height,String sps,String pps,InputStream inputStream) {
        this.surface  = surf;
        this.mMime    = mime;
        this.mWidth   = width;
        this.mHeight  = height;
        this.mSPS     = sps;
        this.mPPS     = pps;
        this.is       = inputStream;
    }

    public void run() {

        /// Create Decoder -START- ///
        try {
            MediaFormat format = new MediaFormat();
            format.setString(MediaFormat.KEY_MIME, mMime);
            format.setInteger(MediaFormat.KEY_WIDTH, mWidth);
            format.setInteger(MediaFormat.KEY_HEIGHT, mHeight);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(hexStringToByteArray(mSPS)));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(hexStringToByteArray(mPPS)));
            decoder = MediaCodec.createDecoderByType(mMime);
            if(decoder == null) {
                Log.d("HANK", "This device cannot support codec :" + mMime);
            }
            decoder.configure(format, surface, null, 0);
        } catch (Exception e) {
            Log.d("HANK","Create Decoder Fail, because "+e);
            //Log.d("HANK", "This device cannot support codec :" + mMime);
        }
        if (decoder == null) {
            Log.e("DecodeActivity", "Can't find video info!");
            return;
        }
        decoder.start();
        /// Create Decoder -END- ///
        ByteBuffer[] inputBuffers = decoder.getInputBuffers();
        ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        /// Decode -START- ///
        int readSize = 0;
        int collectLength;
        int frameCount = 0;
        int j = 0;
        while (!Thread.interrupted()) {
            int inIndex = decoder.dequeueInputBuffer(10000);
            if (inIndex > 0) {

                while (!Thread.interrupted()) {
                    try {
                        readSize = is.read(inputStreamTmp);
                        if(readSize>0) {
                            rawDataCollectBuffer.put(inputStreamTmp, 0, readSize);
                        }
                    } catch (Exception e) {
                        Log.d("HANK","inputstream cannot read : "+e);

                        if(!bStart) {
                            break;
                        }
                    }
                    collectLength = rawDataCollectBuffer.position();

                    rawDataCollectBuffer.flip();

                    int nextNALULength = (rawDataCollectBuffer.get(0)<<0)&0x000000ff | (rawDataCollectBuffer.get(1)<<8)&0x0000ff00 |
                            (rawDataCollectBuffer.get(2)<<16)&0x00ff0000| (rawDataCollectBuffer.get(3)<<24)&0xff000000;

                    if( (nextNALULength + 4/*nalu length number use 4 byte*/)<= collectLength
                            && (collectLength >0) && (nextNALULength >0)) {

                        // Log.d("HANK", "Get NALU length : " + nextNALULength );

                        // remove NALU length number : 4 byte
                        rawDataCollectBuffer.get();rawDataCollectBuffer.get();rawDataCollectBuffer.get();rawDataCollectBuffer.get();

                        // get full NALU raw data : nextNALULength byte
                        rawDataCollectBuffer.get(dst, 0, nextNALULength);
                        rawDataCollectBuffer.compact();

                        // put NALU raw data into inputBuffers[index]
                        ByteBuffer buffer = inputBuffers[inIndex];
                        buffer.clear();
                        buffer.put(dst, 0, nextNALULength);

                        // decode a NALU
                        decoder.queueInputBuffer(inIndex, 0, nextNALULength, j++, 0);

//                            // just a frame count
//                            frameCount++;
//                            Log.d("HANK","Receive Frame Count : "+frameCount);
                        break;
                    }
                    else
                    {
                        rawDataCollectBuffer.compact();
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
                    //Log.d("DecodeActivity", "dequeueOutputBuffer timed out!");
                    break;
                default:
                    // ByteBuffer buffer = outputBuffers[outIndex];
                    // Log.v("DecodeActivity", "We can't use this buffer but render it due to the API limit, " + buffer);
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
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || (!bStart)) {
                Log.d("DecodeActivity", "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
                break;
            }
        }

        decoder.stop();
        decoder.release();
    }
    /// Decode -END- ///
}