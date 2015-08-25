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
    public final static String REQUEST_VIDEO_FILE_PATH_PREFIX = "FILE_PATH";

    public final static String REQUEST_AUTHENTICATION = "REQUEST_AUTHENTICATION";
    public final static String FEEDBACK_AUTHENTICATION = "FEEDBACK_AUTHENTICATION";
    public final static String REQUEST_USERNAME = "USERNAME";
    public final static String REQUEST_PASSWORD = "PASSWORD";


    public final static String getRequestVideoMessage(int onChannel, String filePath) {
        String resultMessage = REQUEST_VIDEO_PREFIX + ":" +
                REQUEST_VIDEO_ON_CHANNEL_PREFIX + ":" + onChannel + ":" +
                REQUEST_VIDEO_FILE_PATH_PREFIX + ":" + filePath;
        return resultMessage;
    }

    public final static String MESSAGE_PREFIX = "MESSAGE";

    // Giving it executor and events, such that can call function at PeerConnectionClient.
    private LooperExecutor executor;
    private PeerConnectionClient.PeerConnectionEvents events;
    private DataChannel dataChannel;

    // Let RequestDataChannelObserver can Remote peerConnectionClient.
    private PeerConnectionClient peerConnectionClient;

    public CommunicationChannelObserver(DataChannel dc, PeerConnectionClient pcc, LooperExecutor exe, PeerConnectionClient.PeerConnectionEvents eve) {
        this.dataChannel = dc;
        this.executor = exe;
        this.events = eve;
        this.peerConnectionClient = pcc;
    }

    @Override
    public void onBufferedAmountChange(long var1) {

    }

    @Override
    public void onStateChange() {
        Log.d("HANK", dataChannel.label() + ":" + dataChannel.state().toString());
        // Request user and password
        if (peerConnectionClient.isCreateSide() && dataChannel.state().toString().equalsIgnoreCase("OPEN")) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    peerConnectionClient.requestAuthen();
                }
            });
        }
    }

    @Override
    public void onMessage(final DataChannel.Buffer buffer) {
        ByteBuffer data = buffer.data;
        int size = data.remaining();
        byte[] bytes = new byte[size];
        data.get(bytes);

        if (!buffer.binary) {
            String command = new String(bytes);

            // the device which bPass and bCreateSide are true can receive VIDEO REQUEST
            if (peerConnectionClient.isPass() && peerConnectionClient.isCreateSide()) {


                if (command.startsWith(REQUEST_VIDEO_PREFIX)) {
                    // REQUEST_VIDEO_PREFIX + ":" +REQUEST_VIDEO_ON_CHANNEL_PREFIX + ":" + onChannel + ":" +REQUEST_VIDEO_FILE_PATH_PREFIX  + ":" + filePath;
                    //      [0]                             [1]                              [2]                     [3]                              [4]
                    String[] commands = command.split(":");
                    if (commands[0].equalsIgnoreCase(REQUEST_VIDEO_PREFIX)
                            && commands[1].equalsIgnoreCase(REQUEST_VIDEO_ON_CHANNEL_PREFIX)
                            && commands[3].equalsIgnoreCase(REQUEST_VIDEO_FILE_PATH_PREFIX)) {
                        final int onChannel = Integer.valueOf(commands[2]);
                        final String filePath = commands[4];
                        if (filePath.equalsIgnoreCase("STOP")) {
                            executor.execute(new Runnable() {
                                @Override
                                public void run() {
                                    events.onPeerMessage("Send to channel : " + onChannel + "STOP, Remotely");
                                    peerConnectionClient.stopChannelSending(onChannel);
                                }
                            });
                        } else {
                            executor.execute(new Runnable() {
                                @Override
                                public void run() {
                                    events.onPeerMessage("Send to channel : " + onChannel + ", file : " + filePath + ",Remotely");
                                    peerConnectionClient.sendVideo(onChannel, filePath);
                                }
                            });
                        }
                    }
                }

            }

            /// MESSAGE PART START ///
            if (command.startsWith(MESSAGE_PREFIX)) {
                final String printMessage = command;
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        events.onShowReceivedMessage(printMessage);
                    }
                });
            }
            /// MESSAGE PART END ///


            /// AUTHENCATION PART START ///
            if (command.startsWith(REQUEST_AUTHENTICATION)) {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        events.onShowReceivedMessage("Received :" + REQUEST_AUTHENTICATION);
                    }
                });
                if(peerConnectionClient.isAutoInputAuthentication()) {
                    peerConnectionClient.sendAuthencationInformation(peerConnectionClient.User, peerConnectionClient.Pass);
                } else {
                    // TODO dialog
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            events.onAuthenticationReceived();
                        }
                    });
                }
            }
            if (command.startsWith(FEEDBACK_AUTHENTICATION)) {
                String[] commands = command.split(":");
                if (commands[0].equalsIgnoreCase(FEEDBACK_AUTHENTICATION)
                        && commands[1].equalsIgnoreCase(REQUEST_USERNAME)
                        && commands[3].equalsIgnoreCase(REQUEST_PASSWORD)) {
                    String username = commands[2];
                    String password = commands[4];
                    final String showMessage = command;
                    if(peerConnectionClient.checkAuthencation(username, password)) {
                        executor.execute(new Runnable() {
                            @Override
                            public void run() {
                                events.onShowReceivedMessage("Received :"+showMessage);
                            }
                        });
                        peerConnectionClient.sendMessage("Authentication Pass, you can send request lo.");
                    } else {
                        peerConnectionClient.sendMessage("Authentication fail, please input correct username and password");
                    }
                }
            }
            /// AUTHENCATION PART END ///

        }
    }
}