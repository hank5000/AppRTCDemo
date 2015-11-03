package com.via.rtc;


import com.via.rtc.AppRTCClient.RoomConnectionParameters;
import com.via.rtc.AppRTCClient.SignalingParameters;
import com.via.rtc.PeerConnectionClient.PeerConnectionParameters;
import com.via.rtc.util.LooperExecutor;
import com.via.rtc.util.LiveViewInfo;

import android.content.Intent;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import android.widget.Toast;

import com.via.rtc.thread.RoomCheckerThread;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;
import org.webrtc.StatsReport;

import java.io.File;
import android.app.IntentService;

/**
 * Created by HankWu_Office on 2015/9/9.
 */
public class CallNoActivity extends IntentService
        implements AppRTCClient.SignalingEvents,
        PeerConnectionClient.PeerConnectionEvents,
        CallFragment.OnCallEvents
         {

    public static final String EXTRA_ROOMID =
            "com.via.rtc.ROOMID";
    public static final String EXTRA_CPUOVERUSE_DETECTION =
            "com.via.rtc.CPUOVERUSE_DETECTION";
    public static final String EXTRA_DISPLAY_HUD =
            "com.via.rtc.DISPLAY_HUD";

    public static final String EXTRA_USERNAME = "com.via.rtc.USERNAME";
    public static final String EXTRA_PASSWORD = "com.via.rtc.PASSWORD";

    public static final String EXTRA_CHAT_ROOM = "com.via.rtc.CHAT_ROOM";
    public static final String EXTRA_CREATE_SIDE = "com.via.rtc.CREATE_SIDE";

    public static final String EXTRA_CMDLINE =
            "com.via.rtc.CMDLINE";
    public static final String EXTRA_RUNTIME =
            "com.via.rtc.RUNTIME";
    private static final String TAG = "VIA-RTC CallRTCClient";

    // List of mandatory application permissions.
    private static final String[] MANDATORY_PERMISSIONS = {
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.RECORD_AUDIO",
            "android.permission.INTERNET"
    };


    // Peer connection statistics callback period in ms.
    private static final int STAT_CALLBACK_PERIOD = 1000;

    private PeerConnectionClient peerConnectionClient = null;
    private AppRTCClient appRtcClient;
    private SignalingParameters signalingParameters;

    private Toast logToast;
    private boolean commandLineRun;
    private boolean activityRunning;
    private boolean bCreateSide;
    private RoomConnectionParameters roomConnectionParameters;
    private PeerConnectionParameters peerConnectionParameters;
    private boolean iceConnected;
    private boolean isError;
    private boolean callControlFragmentVisible = true;
    private long callStartedTimeMs = 0;
    MediaPlayer me = null;
    MediaPlayer me2= null;
    // Controls
    CallFragment callFragment;
    HudFragment hudFragment;

    // Live View
    public SurfaceView[] surfaceViews = new SurfaceView[4];
    SurfaceHolder[] surfaceHolders = new SurfaceHolder[4];
    LiveViewInfo liveViewInfo = new LiveViewInfo();

    String username = "";
    String password = "";

    ////////////////////////////////
    ///////////////////////////////
    public static final String ServerStart = "com.via.WebRtspServer.start";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Log.d(TAG,"onHandleIntent"+this);
        if(intent.getAction().equals(ServerStart))
        {
            //StartServer();
        }
    }

    public CallNoActivity() {
        super(TAG);
        // TODO Auto-generated constructor stub

        iceConnected = false;
        signalingParameters = null;

        // Create UI controls.
        callFragment = new CallFragment();
        hudFragment = new HudFragment();


        // Get Intent parameters.
        final Intent intent = new Intent();//getIntent();
        Uri roomUri = intent.getData();
//        if (roomUri == null) {
//            logAndToast(getString(R.string.missing_url));
//            Log.e(TAG, "Didn't get any URL in intent!");
//            setResult(RESULT_CANCELED);
//            finish();
//            return;
//        }
//
        String roomId = intent.getStringExtra(EXTRA_ROOMID);
//        if (roomId == null || roomId.length() == 0) {
//            logAndToast(getString(R.string.missing_url));
//            Log.e(TAG, "Incorrect room ID in intent!");
//            setResult(RESULT_CANCELED);
//            finish();
//            return;
//        }

        username = intent.getStringExtra(EXTRA_USERNAME);
        password = intent.getStringExtra(EXTRA_PASSWORD);

        bCreateSide = intent.getBooleanExtra(EXTRA_CREATE_SIDE,false);

        peerConnectionParameters = new PeerConnectionParameters(
                intent.getBooleanExtra(EXTRA_CPUOVERUSE_DETECTION, true),
        /*Hank Extension*/
                intent.getBooleanExtra(EXTRA_CHAT_ROOM,false),
                intent.getBooleanExtra(EXTRA_CREATE_SIDE,false));

        commandLineRun = intent.getBooleanExtra(EXTRA_CMDLINE, false);


        // Create connection client and connection parameters.
        appRtcClient = new WebSocketRTCClient(this, new LooperExecutor());
        roomConnectionParameters = new RoomConnectionParameters(
                roomUri.toString(), roomId, false);

        startCall();

        if(bCreateSide) {
            Log.d("HANK","RoomCreate Thread start");
            Thread registerRoomThread = new RoomCheckerThread("RoomCreate",username,password,roomId);
            registerRoomThread.start();
        }

        createPeerConnectionFactory();
    }


    // CallFragment.OnCallEvents interface implementation.
    @Override
    public void onCallHangUp() {
        disconnect();
    }


    // TODO: implement interface, that it is used by fragment_call
    public void onMessageSend() {

    }

    public void onFileTransfer()
    {
        Log.d("HANK", "onFileTransfer");
        final String filePath = "/mnt/sata/OV_ACM.mkv";
        logAndToast("Send File : " + filePath);
        Thread fileSenderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                File f = new File(filePath);
                peerConnectionClient.sendFile(f);
            }
        });
        fileSenderThread.start();
    }

    private void startCall() {
        if (appRtcClient == null) {
            Log.e(TAG, "AppRTC client is not allocated for a call.");
            return;
        }
        callStartedTimeMs = System.currentTimeMillis();
        appRtcClient.connectToRoom(roomConnectionParameters);
    }

    // Should be called from UI thread
    private void callConnected() {
        final long delta = System.currentTimeMillis() - callStartedTimeMs;
        Log.i(TAG, "Call connected: delay=" + delta + "ms");

        if(!bCreateSide) {
            Thread registerRoomThread = new RoomCheckerThread("RoomRemove",username,password,"");
            registerRoomThread.start();
        }
        peerConnectionClient.enableStatsEvents(true, STAT_CALLBACK_PERIOD);
    }

    // Create peer connection factory when EGL context is ready.
    private void createPeerConnectionFactory() {

                if (peerConnectionClient == null) {
                    final long delta = System.currentTimeMillis() - callStartedTimeMs;
                    Log.d(TAG, "Creating peer connection factory, delay=" + delta + "ms");
                    peerConnectionClient = PeerConnectionClient.getInstance();
                    peerConnectionClient.setLiveViewSurface(surfaceViews);
                    peerConnectionClient.setLiveViewInfo(liveViewInfo);
                    peerConnectionClient.setUsernameAndPassword(username,password);
                    peerConnectionClient.createPeerConnectionFactory(CallNoActivity.this, peerConnectionParameters, this);
                }
                if (signalingParameters != null) {
                    Log.w(TAG, "EGL context is ready after room connection.");
                    onConnectedToRoomInternal(signalingParameters);
                }
    }

    // Disconnect from remote resources, dispose of local resources, and exit.
    private void disconnect() {
        activityRunning = false;
        if (appRtcClient != null) {
            appRtcClient.disconnectFromRoom();
            appRtcClient = null;
        }

        if (peerConnectionClient != null) {
            peerConnectionClient.stopAllThread();
            peerConnectionClient.close();
            peerConnectionClient = null;
        }

        if (iceConnected && !isError) {
        } else {
        }
    }

    private void disconnectWithErrorMessage(final String errorMessage) {
        if (commandLineRun || !activityRunning) {
            Log.e(TAG, "Critical error: " + errorMessage);
            disconnect();
        } else {
//            new AlertDialog.Builder(this)
//                    .setTitle(getText(R.string.channel_error_title))
//                    .setMessage(errorMessage)
//                    .setCancelable(false)
//                    .setNeutralButton(R.string.ok, new DialogInterface.OnClickListener() {
//                        @Override
//                        public void onClick(DialogInterface dialog, int id) {
//                            dialog.cancel();
//                            disconnect();
//                        }
//                    }).create().show();
        }
        Intent i = new Intent();
    }
/*

 */
    private void logAndToast(String msg) {
        Log.d(TAG, msg);
//        if (logToast != null) {
//            logToast.cancel();
//        }
//        logToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
//        logToast.show();
    }

    private void reportError(final String description) {
        //runOnUiThread(new Runnable() {
          //  @Override
           // public void run() {
                if (!isError) {
                    isError = true;
                    disconnectWithErrorMessage(description);
                }
            //}
        //});
    }

    // -----Implementation of AppRTCClient.AppRTCSignalingEvents ---------------
    // All callbacks are invoked from websocket signaling looper thread and
    // are routed to UI thread.
    private void onConnectedToRoomInternal(final SignalingParameters params) {
        final long delta = System.currentTimeMillis() - callStartedTimeMs;

        signalingParameters = params;
        if (peerConnectionClient == null) {
            Log.w(TAG, "Room is connected, but EGL context is not ready yet.");
            return;
        }
        logAndToast("Creating peer connection, delay=" + delta + "ms");
        peerConnectionClient.createPeerConnection(signalingParameters);

        if (signalingParameters.initiator) {
            logAndToast("Creating OFFER...");
            // Create offer. Offer SDP will be sent to answering client in
            // PeerConnectionEvents.onLocalDescription event.
            peerConnectionClient.createOffer();
        } else {
            if (params.offerSdp != null) {
                peerConnectionClient.setRemoteDescription(params.offerSdp);
                logAndToast("Creating ANSWER...");
                // Create answer. Answer SDP will be sent to offering client in
                // PeerConnectionEvents.onLocalDescription event.
                peerConnectionClient.createAnswer();
            }
            if (params.iceCandidates != null) {
                // Add remote ICE candidates from room.
                for (IceCandidate iceCandidate : params.iceCandidates) {
                    peerConnectionClient.addRemoteIceCandidate(iceCandidate);
                }
            }
        }
    }

    @Override
    public void onConnectedToRoom(final SignalingParameters params) {
        //runOnUiThread(new Runnable() {
          //  @Override
            //public void run() {
                onConnectedToRoomInternal(params);
            //}
        //});
    }

    @Override
    public void onRemoteDescription(final SessionDescription sdp) {
        final long delta = System.currentTimeMillis() - callStartedTimeMs;
        //runOnUiThread(new Runnable() {
          //  @Override
           // public void run() {
                if (peerConnectionClient == null) {
                    Log.e(TAG, "Received remote SDP for non-initilized peer connection.");
                    return;
                }
                logAndToast("Received remote " + sdp.type + ", delay=" + delta + "ms");
                peerConnectionClient.setRemoteDescription(sdp);
                if (!signalingParameters.initiator) {
                    logAndToast("Creating ANSWER...");
                    // Create answer. Answer SDP will be sent to offering client in
                    // PeerConnectionEvents.onLocalDescription event.
                    peerConnectionClient.createAnswer();
                }
           // }
        //});
    }

    @Override
    public void onRemoteIceCandidate(final IceCandidate candidate) {
        //runOnUiThread(new Runnable() {
          //  @Override
            //public void run() {
                if (peerConnectionClient == null) {
                    Log.e(TAG,
                            "Received ICE candidate for non-initilized peer connection.");
                    return;
                }
                peerConnectionClient.addRemoteIceCandidate(candidate);
            //}
        //});
    }

    @Override
    public void onChannelClose() {
        //runOnUiThread(new Runnable() {
           // @Override
           // public void run() {
                logAndToast("Remote end hung up; dropping PeerConnection");
                disconnect();
        //    }
        //});
    }

    @Override
    public void onChannelError(final String description) {
        reportError(description);
    }

    // -----Implementation of PeerConnectionClient.PeerConnectionEvents.---------
    // Send local peer connection SDP and ICE candidates to remote party.
    // All callbacks are invoked from peer connection client looper thread and
    // are routed to UI thread.
    @Override
    public void onLocalDescription(final SessionDescription sdp) {
        final long delta = System.currentTimeMillis() - callStartedTimeMs;
        //runOnUiThread(new Runnable() {
           // @Override
          //  public void run() {
                if (appRtcClient != null) {
                    logAndToast("Sending " + sdp.type + ", delay=" + delta + "ms");
                    if (signalingParameters.initiator) {
                        appRtcClient.sendOfferSdp(sdp);
                    } else {
                        appRtcClient.sendAnswerSdp(sdp);
                    }
                }
            //}
        //});
    }

    @Override
    public void onIceCandidate(final IceCandidate candidate) {
        //runOnUiThread(new Runnable() {
          //  @Override
            //public void run() {
                if (appRtcClient != null) {
                    appRtcClient.sendLocalIceCandidate(candidate);
                }
            //}
        //});
    }

    @Override
    public void onIceConnected() {
        final long delta = System.currentTimeMillis() - callStartedTimeMs;
        //runOnUiThread(new Runnable() {
            //@Override
            //public void run() {
                logAndToast("ICE connected, delay=" + delta + "ms");
                iceConnected = true;
                callConnected();
            //}
        //});
    }

    @Override
    public void onIceDisconnected() {
        //runOnUiThread(new Runnable() {
           // @Override
            //public void run() {
                logAndToast("ICE disconnected");
                iceConnected = false;
                disconnect();
            //}
       // });
    }

    @Override
    public void onPeerConnectionClosed() {
    }

    @Override
    public void onPeerConnectionStatsReady(final StatsReport[] reports) {
        //runOnUiThread(new Runnable() {
          //  @Override
            //public void run() {
                if (!isError && iceConnected) {
                    hudFragment.updateEncoderStatistics(reports);
                }
            //}
        //});
    }

    @Override
    public void onPeerConnectionError(final String description) {
        reportError(description);
    }

    @Override
    public void onPeerMessage(final String description) {
        logAndToast("Recevie : " + description);
    }

    @Override
    public void onShowReceivedMessage(final String description) {
        logAndToast(description);
    }

    @Override
    public void onAuthenticationReceived() {
        /*final View v = View.inflate(this,R.layout.authentication_list,null);
        new AlertDialog.Builder(this)
                .setTitle("Authentication Information")
                .setView(v)
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        EditText username_et= (EditText) (v.findViewById(R.id.username));
                        EditText password_et= (EditText) (v.findViewById(R.id.password));

                        String username = username_et.getText().toString()+"";
                        String password = password_et.getText().toString()+"";
                        peerConnectionClient.sendAuthencationInformation(username, password);
                    }
                })
                .setNegativeButton("Cancel" , new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        logAndToast("Cancel!");
                    }
                }).show();*/
    }

    @Override
    public void onRequestVideo() {

    }
    public void onQueryLiveView() {

    }
    protected void onDestory()
    {
        Log.d(TAG,"onDestory lo");
        //StopServer();
    }



}
