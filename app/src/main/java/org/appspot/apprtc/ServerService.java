package org.appspot.apprtc;

import android.app.AlertDialog;
import android.app.IntentService;


import java.io.File;
import java.io.FileInputStream;
import java.util.Random;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.webkit.URLUtil;

import org.appspot.apprtc.util.LiveViewInfo;
import org.appspot.apprtc.util.LooperExecutor;
import org.appspot.apprtc.util.RoomCheckerThread;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;
import org.webrtc.StatsReport;

import android.os.Handler;
import android.widget.EditText;
import android.widget.Toast;

public class ServerService extends IntentService
        implements AppRTCClient.SignalingEvents,
        PeerConnectionClient.PeerConnectionEvents {

    public static final String ServerStart = "org.appspot.apprtc.p2p.start";
    public static final String TAG = "HANK-ServerService";
    private String username = "";
    private String password = "";
    private boolean bCreateChannelSide = false;
    boolean cpuOveruseDetection = false;
    boolean displayHud = false;
    boolean bEnableChatRoom = false;
    String roomUrl = "";
    int notifyID = 1;
    private Toast logToast;

    private SharedPreferences sharedPref;

    private String keyprefCpuUsageDetection;
    private String keyprefDisplayHud;
    private String keyprefRoomServerUrl;
    private String keyprefRoom;

    // Hank Extension.
    private String keyperfEnableChatRoom;
    private String keyperfChannelCreateSide;
    private String keyUsername;
    private String keyPassword;
    //

    private PeerConnectionClient.PeerConnectionParameters peerConnectionParameters;
    private AppRTCClient.RoomConnectionParameters roomConnectionParameters;
    private PeerConnectionClient peerConnectionClient = null;
    private AppRTCClient appRtcClient;
    private AppRTCClient.SignalingParameters signalingParameters;

    Handler handler = new Handler();
    private long callStartedTimeMs = 0;
    private boolean isError;
    private boolean iceConnected;
    private static final int STAT_CALLBACK_PERIOD = 1000;
    LiveViewInfo liveViewInfo = new LiveViewInfo();
    public SurfaceView[] surfaceViews = new SurfaceView[4];
    SurfaceHolder[] surfaceHolders = new SurfaceHolder[4];



    private File f = null;

    public String readFileData(String fileName){
        String result="";
        try {
            FileInputStream fin = openFileInput(fileName);
            int lenght = fin.available();
            byte[] buffer = new byte[lenght];
            fin.read(buffer);
            result = new String(buffer, "UTF-8");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    void RunningNotification(int notifyId,String Title, String Content)
    {
        final int requestCode = notifyID;
        final Intent intent = new Intent(this, ServerService.class);
        final int flags = PendingIntent.FLAG_CANCEL_CURRENT;
        final PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(), requestCode, intent, flags);
        final NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        final Notification notification = new Notification.Builder(getApplicationContext()).setSmallIcon(R.drawable.ic_launcher).setContentTitle(Title).setContentText(Content).setContentIntent(pendingIntent).build();
        notificationManager.notify(notifyId, notification);
    }

    void RemoveNotification(int notifyId)
    {
        final NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(notifyID);
    }

    private void getPreferenceSetting() {
        // Get setting keys.
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        sharedPref = PreferenceManager.getDefaultSharedPreferences(this);

        keyprefCpuUsageDetection = getString(R.string.pref_cpu_usage_detection_key);
        keyprefDisplayHud = getString(R.string.pref_displayhud_key);
        keyprefRoomServerUrl = getString(R.string.pref_room_server_url_key);
        keyprefRoom = getString(R.string.pref_room_key);

        // Hank Extension
        keyperfEnableChatRoom = getString(R.string.pref_enable_chat_room_key_key);
        keyperfChannelCreateSide = getString(R.string.pref_enable_channel_create_side_key);
        keyUsername = getString(R.string.pref_username_key);
        keyPassword = getString(R.string.pref_password_key);

        username = sharedPref.getString(
                keyUsername,
                getString(R.string.pref_username_default));

        password = sharedPref.getString(
                keyPassword,
                getString(R.string.pref_password_default));

        bCreateChannelSide = sharedPref.getBoolean(
                keyperfChannelCreateSide,
                Boolean.valueOf(getString(R.string.pref_enable_channel_create_side_default)));

        roomUrl = sharedPref.getString(
                keyprefRoomServerUrl,
                getString(R.string.pref_room_server_url_default));

        // Test if CpuOveruseDetection should be disabled. By default is on.
        cpuOveruseDetection = sharedPref.getBoolean(
                keyprefCpuUsageDetection,
                Boolean.valueOf(
                        getString(R.string.pref_cpu_usage_detection_default)));

        // Check statistics display option.
        displayHud = sharedPref.getBoolean(keyprefDisplayHud,
                Boolean.valueOf(getString(R.string.pref_displayhud_default)));

        bEnableChatRoom = sharedPref.getBoolean(
                keyperfEnableChatRoom,
                Boolean.valueOf(getString(R.string.pref_enable_chat_room_key_default)));

        if(bCreateChannelSide){
            String roomId = Integer.toString((new Random()).nextInt(100000000));
        }

        Log.d(TAG,"username:"+username+",password:"+password+",CreateSide:"+bCreateChannelSide+"roomUrl:"+roomUrl
        +",cpuOveruseDetection:"+cpuOveruseDetection+",displayHud:"+displayHud+",bEnableChatRoom:"+bEnableChatRoom
        );

    }

    private boolean validateUrl(String url) {
        if (URLUtil.isHttpsUrl(url) || URLUtil.isHttpUrl(url)) {
            return true;
        }
        Log.d(TAG, "validateUrl " + url);
        return false;
    }

    public ServerService() {
        super(TAG);
        // TODO Auto-generated constructor stub
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Log.d(TAG, "onHandleIntent" + this);
        Log.d(TAG, "Intent Action : "+intent.getAction());
        if(intent.getAction().equals(ServerStart))
        {
            getPreferenceSetting();
            if(bCreateChannelSide) {
                Log.d(TAG,"It is create Channel side");
                Log.d(TAG,"Start Server");
                StartServer();
            }
        }
    }

    public static Intent getIntentServerStart()
    {
        Intent i = new Intent(ServerStart);
        return i;
    }

    public void StartServer() {
        RunningNotification(notifyID,"RTCServer","Start");

        peerConnectionParameters = new PeerConnectionClient.PeerConnectionParameters(cpuOveruseDetection,
                bEnableChatRoom,bCreateChannelSide);
        appRtcClient = new WebSocketRTCClient(this, new LooperExecutor());
        Uri roomUri = Uri.parse("https://apprtc.appspot.com/");
        String roomId = Integer.toString((new Random()).nextInt(100000000));
        roomConnectionParameters = new AppRTCClient.RoomConnectionParameters(
                roomUri.toString(), roomId, false);
        startCall();
        if(bCreateChannelSide) {
            Log.d("HANK","RoomCreate Thread start");
            Thread registerRoomThread = new RoomCheckerThread("RoomCreate",username,password,roomId);
            registerRoomThread.start();
        }
        createPeerConnectionFactory();
    }

    private void startCall() {
        if (appRtcClient == null) {
            Log.e(TAG, "AppRTC client is not allocated for a call.");
            return;
        }
        callStartedTimeMs = System.currentTimeMillis();

        // Start room connection.
        logAndToast(getString(R.string.connecting_to,
                roomConnectionParameters.roomUrl));
        appRtcClient.connectToRoom(roomConnectionParameters);

    }

    // Log |msg| and Toast about it.
    private void logAndToast(String msg) {
        Log.d(TAG, msg);

        if (logToast != null) {
            logToast.cancel();
        }
        logToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        logToast.show();
    }


    // -----Implementation of AppRTCClient.AppRTCSignalingEvents ---------------
    // All callbacks are invoked from websocket signaling looper thread and
    // are routed to UI thread.
    private void onConnectedToRoomInternal(final AppRTCClient.SignalingParameters params) {
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

    private void updateLiveViewInfo() {
        liveViewInfo.reset();
        liveViewInfo.add("rtsp://192.168.12.202:554/rtpvideo1.sdp", "RTSP202");
    }

    // Create peer connection factory when EGL context is ready.
    private void createPeerConnectionFactory() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (peerConnectionClient == null) {
                    final long delta = System.currentTimeMillis() - callStartedTimeMs;
                    Log.d(TAG, "Creating peer connection factory, delay=" + delta + "ms");
                    peerConnectionClient = PeerConnectionClient.getInstance();

                    updateLiveViewInfo();

                    peerConnectionClient.setLiveViewInfo(liveViewInfo);
                    peerConnectionClient.setUsernameAndPassword(username, password);
                    peerConnectionClient.createPeerConnectionFactory(ServerService.this, peerConnectionParameters, ServerService.this);

                }
                if (signalingParameters != null) {
                    Log.w(TAG, "EGL context is ready after room connection.");
                    onConnectedToRoomInternal(signalingParameters);
                }
            }
        });
    }

    @Override
    public void onConnectedToRoom(final AppRTCClient.SignalingParameters params) {
        handler.post((new Runnable() {
            @Override
            public void run() {
                onConnectedToRoomInternal(params);
            }
        }));
    }

    @Override
    public void onRemoteDescription(final SessionDescription sdp) {
        final long delta = System.currentTimeMillis() - callStartedTimeMs;
        handler.post((new Runnable() {
            @Override
            public void run() {
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
            }
        }));
    }

    @Override
    public void onRemoteIceCandidate(final IceCandidate candidate) {
        handler.post((new Runnable() {
            @Override
            public void run() {
                if (peerConnectionClient == null) {
                    Log.e(TAG,
                            "Received ICE candidate for non-initilized peer connection.");
                    return;
                }
                peerConnectionClient.addRemoteIceCandidate(candidate);
            }
        }));
    }

    @Override
    public void onChannelClose() {
        handler.post((new Runnable() {
            @Override
            public void run() {
                logAndToast("Remote end hung up; dropping PeerConnection");
                disconnect();


            }
        }));
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
        handler.post((new Runnable() {
            @Override
            public void run() {
                if (appRtcClient != null) {
                    logAndToast("Sending " + sdp.type + ", delay=" + delta + "ms");
                    if (signalingParameters.initiator) {
                        appRtcClient.sendOfferSdp(sdp);
                    } else {
                        appRtcClient.sendAnswerSdp(sdp);
                    }
                }
            }
        }));
    }

    @Override
    public void onIceCandidate(final IceCandidate candidate) {
        handler.post((new Runnable() {
            @Override
            public void run() {
                if (appRtcClient != null) {
                    appRtcClient.sendLocalIceCandidate(candidate);
                }
            }
        }));
    }

    // Should be called from UI thread
    private void callConnected() {
        final long delta = System.currentTimeMillis() - callStartedTimeMs;
        Log.i(TAG, "Call connected: delay=" + delta + "ms");

        // Update video view.
        //updateVideoView();
        if(!bCreateChannelSide) {
            Thread registerRoomThread = new RoomCheckerThread("RoomRemove",username,password,"");
            registerRoomThread.start();
        }

        // Enable statistics callback.
        peerConnectionClient.enableStatsEvents(true, STAT_CALLBACK_PERIOD);
    }

    @Override
    public void onIceConnected() {
        final long delta = System.currentTimeMillis() - callStartedTimeMs;
        handler.post((new Runnable() {
            @Override
            public void run() {
                logAndToast("ICE connected, delay=" + delta + "ms");
                iceConnected = true;
                callConnected();
            }
        }));
    }

    @Override
    public void onIceDisconnected() {
        handler.post((new Runnable() {
            @Override
            public void run() {
                logAndToast("ICE disconnected");
                iceConnected = false;
                disconnect();
            }
        }));
    }

    @Override
    public void onPeerConnectionClosed() {
    }

    @Override
    public void onPeerConnectionStatsReady(final StatsReport[] reports) {
        handler.post((new Runnable() {
            @Override
            public void run() {
                if (!isError && iceConnected) {
                    //hudFragment.updateEncoderStatistics(reports);
                }
            }
        }));
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
        final View v = View.inflate(this,R.layout.authentication_list,null);
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
                }).show();
    }

    private void reportError(final String description) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!isError) {
                    isError = true;
                    disconnectWithErrorMessage(description);
                }
            }
        });
    }

    // Disconnect from remote resources, dispose of local resources, and exit.
    private void disconnect() {
        if (appRtcClient != null) {
            appRtcClient.disconnectFromRoom();
            appRtcClient = null;
        }

        if (peerConnectionClient != null) {
            peerConnectionClient.stopAllThread();
            peerConnectionClient.close();
            peerConnectionClient = null;
        }

        signalingParameters = null;

        if (iceConnected && !isError) {
            //setResult(RESULT_OK);
        } else {
            //setResult(RESULT_CANCELED);
        }
        //finish();
        RemoveNotification(notifyID);
        /*
        Reconnect
         */
        iceConnected = false;
        isError = false;
        {
            getPreferenceSetting();
            if(bCreateChannelSide) {
                Log.d(TAG,"It is create Channel side");
                Log.d(TAG,"Start Server");
                StartServer();
            }
        }
    }

    private void disconnectWithErrorMessage(final String errorMessage) {
        if (true) {
            Log.e(TAG, "Critical error: " + errorMessage);
            disconnect();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(getText(R.string.channel_error_title))
                    .setMessage(errorMessage)
                    .setCancelable(false)
                    .setNeutralButton(R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                            disconnect();
                        }
                    }).create().show();
        }
    }
}