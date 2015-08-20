package org.appspot.apprtc.util;

import android.util.Log;

import org.appspot.apprtc.PeerConnectionClient;
import org.webrtc.DataChannel;

/**
 * Created by HankWu_Office on 2015/8/20.
 */
public class RequestDataChannelObserver implements DataChannel.Observer {

    // Giving it executor and events, such that can call function at PeerConnectionClient.
    private LooperExecutor executor;
    private PeerConnectionClient.PeerConnectionEvents events;
    private DataChannel dataChannel;

    // Let RequestDataChannelObserver can Remote peerConnectionClient.
    private PeerConnectionClient peerConnectionClient;

    public RequestDataChannelObserver(DataChannel dc,PeerConnectionClient pcc,LooperExecutor exe, PeerConnectionClient.PeerConnectionEvents eve) {
        this.dataChannel = dc;
        this.executor = exe;
        this.events   = eve;
        this.peerConnectionClient = pcc;
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
        executor.execute(new Runnable() {
            @Override
            public void run() {

            }
        });
    }
}
