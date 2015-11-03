package com.via.rtc.util;

/**
 * Created by HankWu_Office on 2015/8/28.
 */

import android.util.Log;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

public class AudioOut {
    /**
     * Java side of the audio output module for Android.
     * Uses an AudioTrack to play decoded audio buffers.
     *
     * TODO Use MODE_STATIC instead of MODE_STREAM with a MemoryFile (ashmem)
     */

    private AudioTrack mAudioTrack;
    private static final String TAG = "VIA-RTC AudioOut";

    public void init() {
        int sampleRateInHz = 11025;
        int channels = 1;
        int samples = 11025;
        Log.d(TAG, sampleRateInHz + ", " + channels + ", " + samples + "=>" + channels*samples);

        mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                sampleRateInHz,
                AudioFormat.CHANNEL_CONFIGURATION_MONO, //STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                channels * samples * 2,
                AudioTrack.MODE_STREAM);
    }

    public void init(int sampleRateInHz, int channels, int samples) {
        Log.d(TAG, sampleRateInHz + ", " + channels + ", " + samples + "=>" + channels*samples);
        mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                sampleRateInHz,
                AudioFormat.CHANNEL_CONFIGURATION_MONO, //STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                channels * samples * 2,
                AudioTrack.MODE_STREAM);
    }

    public void release() {
        Log.d(TAG, "Stopping audio playback");
        // mAudioTrack.stop();
        mAudioTrack.release();
        mAudioTrack = null;
    }

    public void playBuffer(byte[] audioData, int bufferSize, int nbSamples) {
//        Log.d(TAG, "Buffer size: " + bufferSize + " nb samples: " + nbSamples);

        if (mAudioTrack.write(audioData, 0, bufferSize) != bufferSize)
        {
            Log.w(TAG, "Could not write all the samples to the audio device");
        }
        mAudioTrack.play();
    }
}
