package org.appspot.apprtc.util;

import android.util.Log;

import org.appspot.apprtc.PeerConnectionClient;
import org.webrtc.DataChannel;

import java.nio.ByteBuffer;

/**
 * Created by HankWu_Office on 2015/8/20.
 */
public class CommunicationChannelObserver implements DataChannel.Observer {
    public final static String REQUEST_VIDEO_PREFIX = "REQUEST_VIDEO";
    public final static String REQUEST_VIDEO_ON_CHANNEL_PREFIX = "ON_CHANNEL";
    public final static String REQUEST_VIDEO_FILE_PATH_PREFIX  = "FILE_PATH";

    public final static String getRequestVideoMessage(int onChannel, String filePath) {
        String resultMessage = REQUEST_VIDEO_PREFIX + ":" +
                    REQUEST_VIDEO_ON_CHANNEL_PREFIX + ":" + onChannel + ":" +
                    REQUEST_VIDEO_FILE_PATH_PREFIX  + ":" + filePath;
        return resultMessage;
    }

    public final static String MESSAGE_PREFIX = "MESSAGE";

    // Giving it executor and events, such that can call function at PeerConnectionClient.
    private LooperExecutor executor;
    private PeerConnectionClient.PeerConnectionEvents events;
    private DataChannel dataChannel;

    // Let RequestDataChannelObserver can Remote peerConnectionClient.
    private PeerConnectionClient peerConnectionClient;

    public CommunicationChannelObserver(DataChannel dc,PeerConnectionClient pcc,LooperExecutor exe, PeerConnectionClient.PeerConnectionEvents eve) {
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
        ByteBuffer data = buffer.data;
        int size = data.remaining();
        byte[] bytes = new byte[size];
        data.get(bytes);

        if(!buffer.binary) {
            String command = new String(bytes);
            if(command.startsWith(REQUEST_VIDEO_PREFIX))
            {
                // REQUEST_VIDEO_PREFIX + ":" +REQUEST_VIDEO_ON_CHANNEL_PREFIX + ":" + onChannel + ":" +REQUEST_VIDEO_FILE_PATH_PREFIX  + ":" + filePath;
                //      [0]                             [1]                              [2]                     [3]                              [4]
                String[] commands = command.split(":");
                if(commands[0].equalsIgnoreCase(REQUEST_VIDEO_PREFIX)
                && commands[1].equalsIgnoreCase(REQUEST_VIDEO_ON_CHANNEL_PREFIX)
                && commands[3].equalsIgnoreCase(REQUEST_VIDEO_FILE_PATH_PREFIX))
                {
                    final int onChannel = Integer.valueOf(commands[2]);
                    final String filePath = commands[4];
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            peerConnectionClient.sendVideo(onChannel,filePath);
                        }
                    });
                }
            }

            if(command.startsWith(MESSAGE_PREFIX)) {
                final String printMessage = command;
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        events.onShowReceivedMessage(printMessage);
                    }
                });
            }

        }
    }
}
