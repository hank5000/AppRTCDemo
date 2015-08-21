/*
 *  Copyright 2014 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */


package org.appspot.apprtc;

import org.appspot.apprtc.AppRTCClient.RoomConnectionParameters;
import org.appspot.apprtc.AppRTCClient.SignalingParameters;
import org.appspot.apprtc.PeerConnectionClient.PeerConnectionParameters;
import org.appspot.apprtc.util.LooperExecutor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentTransaction;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager.LayoutParams;
import android.widget.EditText;
import android.widget.Toast;

import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;
import org.webrtc.StatsReport;
import org.webrtc.VideoRendererGui.ScalingType;

import java.io.File;

/**
 * Activity for peer connection call setup, call waiting
 * and call view.
 */
public class CallActivity extends Activity
    implements AppRTCClient.SignalingEvents,
      PeerConnectionClient.PeerConnectionEvents,
      CallFragment.OnCallEvents, SurfaceHolder.Callback {

  public static final String EXTRA_ROOMID =
      "org.appspot.apprtc.ROOMID";
  public static final String EXTRA_LOOPBACK =
      "org.appspot.apprtc.LOOPBACK";
  public static final String EXTRA_VIDEO_CALL =
      "org.appspot.apprtc.VIDEO_CALL";
  public static final String EXTRA_VIDEO_WIDTH =
      "org.appspot.apprtc.VIDEO_WIDTH";
  public static final String EXTRA_VIDEO_HEIGHT =
      "org.appspot.apprtc.VIDEO_HEIGHT";
  public static final String EXTRA_VIDEO_FPS =
      "org.appspot.apprtc.VIDEO_FPS";
  public static final String EXTRA_VIDEO_BITRATE =
      "org.appspot.apprtc.VIDEO_BITRATE";
  public static final String EXTRA_VIDEOCODEC =
      "org.appspot.apprtc.VIDEOCODEC";
  public static final String EXTRA_HWCODEC_ENABLED =
      "org.appspot.apprtc.HWCODEC";
  public static final String EXTRA_AUDIO_BITRATE =
      "org.appspot.apprtc.AUDIO_BITRATE";
  public static final String EXTRA_AUDIOCODEC =
      "org.appspot.apprtc.AUDIOCODEC";
  public static final String EXTRA_NOAUDIOPROCESSING_ENABLED =
      "org.appspot.apprtc.NOAUDIOPROCESSING";
  public static final String EXTRA_CPUOVERUSE_DETECTION =
      "org.appspot.apprtc.CPUOVERUSE_DETECTION";
  public static final String EXTRA_DISPLAY_HUD =
      "org.appspot.apprtc.DISPLAY_HUD";
  //Hank Extension
  public static final String EXTRA_CHAT_ROOM = "test.via.hank.CHAT_ROOM";
  public static final String EXTRA_CREATE_SIDE = "test.via.hank.CREATE_SIDE";


  public static final String EXTRA_CMDLINE =
      "org.appspot.apprtc.CMDLINE";
  public static final String EXTRA_RUNTIME =
      "org.appspot.apprtc.RUNTIME";
  private static final String TAG = "CallRTCClient";

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
  private AppRTCAudioManager audioManager = null;

  private Toast logToast;
  private boolean commandLineRun;
  private int runTimeMs;
  private boolean activityRunning;
  private RoomConnectionParameters roomConnectionParameters;
  private PeerConnectionParameters peerConnectionParameters;
  private boolean iceConnected;
  private boolean isError;
  private boolean callControlFragmentVisible = true;
  private long callStartedTimeMs = 0;

  // Controls
  CallFragment callFragment;
  HudFragment hudFragment;

  // Live View
  public SurfaceView[] surfaceViews = new SurfaceView[4];
  SurfaceHolder[] surfaceHolders = new SurfaceHolder[4];


  @Override
  public void surfaceCreated(SurfaceHolder holder) {
    createPeerConnectionFactory();

  }

  @Override
  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

  }

  @Override
  public void surfaceDestroyed(SurfaceHolder holder) {

  }
  ////////////////////////////////
  ///////////////////////////////

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Thread.setDefaultUncaughtExceptionHandler(
        new UnhandledExceptionHandler(this));

    // Set window styles for fullscreen-window size. Needs to be done before
    // adding content.
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    getWindow().addFlags(
            LayoutParams.FLAG_FULLSCREEN
                    | LayoutParams.FLAG_KEEP_SCREEN_ON
                    | LayoutParams.FLAG_DISMISS_KEYGUARD
                    | LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | LayoutParams.FLAG_TURN_SCREEN_ON);
    getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    setContentView(R.layout.activity_call);

    iceConnected = false;
    signalingParameters = null;

    // Create UI controls.
    callFragment = new CallFragment();
    hudFragment = new HudFragment();

    // Check for mandatory permissions.
    for (String permission : MANDATORY_PERMISSIONS) {
      if (checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
        logAndToast("Permission " + permission + " is not granted");
        setResult(RESULT_CANCELED);
        finish();
        return;
      }
    }

    // Get Intent parameters.
      final Intent intent = getIntent();
      Uri roomUri = intent.getData();
      if (roomUri == null) {
        logAndToast(getString(R.string.missing_url));
      Log.e(TAG, "Didn't get any URL in intent!");
      setResult(RESULT_CANCELED);
      finish();
      return;
    }
    String roomId = intent.getStringExtra(EXTRA_ROOMID);
    if (roomId == null || roomId.length() == 0) {
      logAndToast(getString(R.string.missing_url));
      Log.e(TAG, "Incorrect room ID in intent!");
      setResult(RESULT_CANCELED);
      finish();
      return;
    }
    boolean loopback = intent.getBooleanExtra(EXTRA_LOOPBACK, false);

    peerConnectionParameters = new PeerConnectionParameters(
        intent.getBooleanExtra(EXTRA_CPUOVERUSE_DETECTION, true),
            /*Hank Extension*/
        intent.getBooleanExtra(EXTRA_CHAT_ROOM,false),
        intent.getBooleanExtra(EXTRA_CREATE_SIDE,false));

    commandLineRun = intent.getBooleanExtra(EXTRA_CMDLINE, false);
    runTimeMs = intent.getIntExtra(EXTRA_RUNTIME, 0);

    // Create connection client and connection parameters.
    appRtcClient = new WebSocketRTCClient(this, new LooperExecutor());
    roomConnectionParameters = new RoomConnectionParameters(
        roomUri.toString(), roomId, loopback);

    // Send intent arguments to fragments.
    callFragment.setArguments(intent.getExtras());
    hudFragment.setArguments(intent.getExtras());
    // Activate call and HUD fragments and start the call.
    FragmentTransaction ft = getFragmentManager().beginTransaction();
    ft.add(R.id.call_fragment_container, callFragment);
    ft.add(R.id.hud_fragment_container, hudFragment);
    ft.commit();
    startCall();

    int j = 0;
    // Create Live View
    surfaceViews[j] = (SurfaceView) findViewById(R.id.liveView_call);
    surfaceHolders[j] = surfaceViews[0].getHolder();
    surfaceHolders[j].addCallback(this);

    j=1;
    surfaceViews[j] = (SurfaceView) findViewById(R.id.liveView_call2);
    surfaceHolders[j] = surfaceViews[1].getHolder();
    surfaceHolders[j].addCallback(new SurfaceHolder.Callback() {
      @Override
      public void surfaceCreated(SurfaceHolder holder) {
      }

      @Override
      public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
      }

      @Override
      public void surfaceDestroyed(SurfaceHolder holder) {

      }
    });

    j=2;
    surfaceViews[j] = (SurfaceView) findViewById(R.id.liveView_call3);
    surfaceHolders[j] = surfaceViews[1].getHolder();
    surfaceHolders[j].addCallback(new SurfaceHolder.Callback() {
      @Override
      public void surfaceCreated(SurfaceHolder holder) {
      }

      @Override
      public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
      }

      @Override
      public void surfaceDestroyed(SurfaceHolder holder) {

      }
    });

    j=3;
    surfaceViews[j] = (SurfaceView) findViewById(R.id.liveView_call4);
    surfaceHolders[j] = surfaceViews[1].getHolder();
    surfaceHolders[j].addCallback(new SurfaceHolder.Callback() {
      @Override
      public void surfaceCreated(SurfaceHolder holder) {
      }

      @Override
      public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
      }

      @Override
      public void surfaceDestroyed(SurfaceHolder holder) {

      }
    });
  }

  // Activity interfaces
  @Override
  public void onPause() {
    super.onPause();
    activityRunning = false;

  }

  @Override
  public void onResume() {
    super.onResume();
    activityRunning = true;

  }

  @Override
  protected void onDestroy() {
    disconnect();
    super.onDestroy();
    if (logToast != null) {
      logToast.cancel();
    }
    activityRunning = false;
  }

  // CallFragment.OnCallEvents interface implementation.
  @Override
  public void onCallHangUp() {
    disconnect();
  }

  @Override
  public void onCameraSwitch() {

  }

  @Override
  public void onVideoScalingSwitch(ScalingType scalingType) {
    //this.scalingType = scalingType;
    //updateVideoView();
  }

  int a = 0;
  public void onLiveView() {
    logAndToast("Live View lo");
    peerConnectionClient.sendVideo(a,"/mnt/sata/abc.mp4");
    a++;
  }

  // TODO: implement interface, that it is used by fragment_call
  public void onMessageSend() {

    final View v = View.inflate(this,R.layout.message,null);
    new AlertDialog.Builder(this)
            .setTitle("Send Message")
            .setView(v)
            .setPositiveButton("Send", new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int which) {
                EditText message_et= (EditText) (v.findViewById(R.id.message_content));
                String message = message_et.getText().toString()+"";
                peerConnectionClient.sendMessage(message);
              }
            })
            .setNegativeButton("Cancel" , new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int which) {
                logAndToast("Cancel!");
              }
            }).show();

  }

  public void onQueryFile() {

  }

  public void onRequestVideo() {

    final View v = View.inflate(this,R.layout.request_video,null);
    new AlertDialog.Builder(this)
            .setTitle("Send Video Request")
            .setView(v)
            .setPositiveButton("Send", new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int which) {
                EditText filePath_et = (EditText) (v.findViewById(R.id.file_path));
                EditText channel_et= (EditText) (v.findViewById(R.id.on_channel));

                String filePath = filePath_et.getText().toString();
                int channelNumber = Integer.valueOf(channel_et.getText().toString());

                if(channelNumber>4 || channelNumber <0) {
                  logAndToast("input the wrong channel, channel need lower than four");
                }
                else {
                  peerConnectionClient.sendVideoRequest(channelNumber, filePath);
                }
              }
            })
            .setNegativeButton("Cancel" , new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int which) {
              logAndToast("Cancel!");
              }
            }).show();
  }

  public void onMessageTransfer() {
    Log.d("HANK", "onMessageTransfer");
    logAndToast("Send Message HELLO");
    peerConnectionClient.sendMessage("HELLO");
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

  // Helper functions.
  private void toggleCallControlFragmentVisibility() {
    if (!iceConnected || !callFragment.isAdded()) {
      return;
    }
    // Show/hide call control fragment
    callControlFragmentVisible = !callControlFragmentVisible;
    FragmentTransaction ft = getFragmentManager().beginTransaction();
    if (callControlFragmentVisible) {
      ft.show(callFragment);
      ft.show(hudFragment);
    } else {
      ft.hide(callFragment);
      ft.hide(hudFragment);
    }
    ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
    ft.commit();
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

//    // Create and audio manager that will take care of audio routing,
//    // audio modes, audio device enumeration etc.
//    audioManager = AppRTCAudioManager.create(this, new Runnable() {
//        // This method will be called each time the audio state (number and
//        // type of devices) has been changed.
//        @Override
//        public void run() {
//          onAudioManagerChangedState();
//        }
//      }
//    );
//    // Store existing audio settings and change audio mode to
//    // MODE_IN_COMMUNICATION for best possible VoIP performance.
//    Log.d(TAG, "Initializing the audio manager...");
//    audioManager.init();
  }

  // Should be called from UI thread
  private void callConnected() {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    Log.i(TAG, "Call connected: delay=" + delta + "ms");

    // Update video view.
    //updateVideoView();
    // Enable statistics callback.
    peerConnectionClient.enableStatsEvents(true, STAT_CALLBACK_PERIOD);
  }

  private void onAudioManagerChangedState() {
    // TODO(henrika): disable video if AppRTCAudioManager.AudioDevice.EARPIECE
    // is active.
  }

  // Create peer connection factory when EGL context is ready.
  private void createPeerConnectionFactory() {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (peerConnectionClient == null) {
          final long delta = System.currentTimeMillis() - callStartedTimeMs;
          Log.d(TAG, "Creating peer connection factory, delay=" + delta + "ms");
          peerConnectionClient = PeerConnectionClient.getInstance();
          peerConnectionClient.setLiveViewSurface(surfaceViews);
          peerConnectionClient.createPeerConnectionFactory(CallActivity.this, peerConnectionParameters, CallActivity.this);
        }
        if (signalingParameters != null) {
          Log.w(TAG, "EGL context is ready after room connection.");
          onConnectedToRoomInternal(signalingParameters);
        }
      }
    });
  }

  // Disconnect from remote resources, dispose of local resources, and exit.
  private void disconnect() {
    activityRunning = false;
    if (appRtcClient != null) {
      appRtcClient.disconnectFromRoom();
      appRtcClient = null;
    }
    if (peerConnectionClient != null) {
      peerConnectionClient.close();
      peerConnectionClient = null;
    }

    if (iceConnected && !isError) {
      setResult(RESULT_OK);
    } else {
      setResult(RESULT_CANCELED);
    }
    finish();
  }

  private void disconnectWithErrorMessage(final String errorMessage) {
    if (commandLineRun || !activityRunning) {
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

  // Log |msg| and Toast about it.
  private void logAndToast(String msg) {
    Log.d(TAG, msg);
    if (logToast != null) {
      logToast.cancel();
    }
    logToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
    logToast.show();
  }

  private void reportError(final String description) {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (!isError) {
          isError = true;
          disconnectWithErrorMessage(description);
        }
      }
    });
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
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        onConnectedToRoomInternal(params);
      }
    });
  }

  @Override
  public void onRemoteDescription(final SessionDescription sdp) {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    runOnUiThread(new Runnable() {
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
    });
  }

  @Override
  public void onRemoteIceCandidate(final IceCandidate candidate) {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (peerConnectionClient == null) {
          Log.e(TAG,
                  "Received ICE candidate for non-initilized peer connection.");
          return;
        }
        peerConnectionClient.addRemoteIceCandidate(candidate);
      }
    });
  }

  @Override
  public void onChannelClose() {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        logAndToast("Remote end hung up; dropping PeerConnection");
        disconnect();
      }
    });
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
    runOnUiThread(new Runnable() {
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
    });
  }

  @Override
  public void onIceCandidate(final IceCandidate candidate) {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (appRtcClient != null) {
          appRtcClient.sendLocalIceCandidate(candidate);
        }
      }
    });
  }

  @Override
  public void onIceConnected() {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        logAndToast("ICE connected, delay=" + delta + "ms");
        iceConnected = true;
        callConnected();
      }
    });
  }

  @Override
  public void onIceDisconnected() {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        logAndToast("ICE disconnected");
        iceConnected = false;
        disconnect();
      }
    });
  }

  @Override
  public void onPeerConnectionClosed() {
  }

  @Override
  public void onPeerConnectionStatsReady(final StatsReport[] reports) {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (!isError && iceConnected) {
          hudFragment.updateEncoderStatistics(reports);
        }
      }
    });
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

}
