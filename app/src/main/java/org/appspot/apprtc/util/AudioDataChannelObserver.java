package org.appspot.apprtc.util;

import android.util.Log;

import org.appspot.apprtc.PeerConnectionClient;
import org.webrtc.DataChannel;

/**
 * Created by HankWu_Office on 2015/8/20.
 */
public class AudioDataChannelObserver implements DataChannel.Observer {

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
    @Override
    public void onMessage(final DataChannel.Buffer buffer) {

    }
}
