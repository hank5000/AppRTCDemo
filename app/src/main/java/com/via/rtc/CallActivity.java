/*
 *  Copyright 2014 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */


package com.via.rtc;

import com.via.rtc.AppRTCClient.RoomConnectionParameters;
import com.via.rtc.AppRTCClient.SignalingParameters;
import com.via.rtc.PeerConnectionClient.PeerConnectionParameters;
import com.via.rtc.util.LooperExecutor;
import com.via.rtc.util.LiveViewInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.FragmentTransaction;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridView;

import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.via.rtc.thread.RoomCheckerThread;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;
import org.webrtc.StatsReport;
import android.widget.AdapterView;
import android.app.AlertDialog.Builder;

import java.io.File;

import java.util.ArrayList;
import java.util.List;

/**
 * Activity for peer connection call setup, call waiting
 * and call view.
 */
public class CallActivity extends Activity
    implements AppRTCClient.SignalingEvents,
      PeerConnectionClient.PeerConnectionEvents,
      CallFragment.OnCallEvents {

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
  public SurfaceView[] surfaceViews = new SurfaceView[PeerConnectionClient.getMaxChannelNumber()];
  SurfaceHolder[] surfaceHolders = new SurfaceHolder[PeerConnectionClient.getMaxChannelNumber()];
  LiveViewInfo liveViewInfo = new LiveViewInfo();

  String username = "";
  String password = "";

  boolean bEnableButton = false;

  Handler handler = new Handler();



  void processingButtonUsing() {
    bEnableButton = false;

    final ImageButton power = (ImageButton) findViewById(R.id.button_call_disconnect);
    final ImageButton live  = (ImageButton) findViewById(R.id.button_call_liveview);
//    power.setVisibility(View.INVISIBLE);
//    live.setVisibility(View.INVISIBLE);

    Runnable mTask = new Runnable() {
      @Override
      public void run() {
        bEnableButton = true;
//        runOnUiThread(new Runnable() {
//          @Override
//          public void run() {
//            power.setVisibility(View.VISIBLE);
//            live.setVisibility(View.VISIBLE);
//          }
//        });
      }
    };

    handler.postDelayed(mTask,1000*5);

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

    // Send intent arguments to fragments.
    callFragment.setArguments(intent.getExtras());
    hudFragment.setArguments(intent.getExtras());
    // Activate call and HUD fragments and start the call.
    FragmentTransaction ft = getFragmentManager().beginTransaction();
    ft.add(R.id.call_fragment_container, callFragment);
    ft.add(R.id.hud_fragment_container, hudFragment);
    ft.commit();
    startCall();

    if(bCreateSide) {
      Log.d(TAG,"RoomCreate Thread start");
      Thread registerRoomThread = new RoomCheckerThread("RoomCreate",username,password,roomId);
      registerRoomThread.start();
    }

    createPeerConnectionFactory();
//
////
//// TODO: REMOVE the following code, bcuz Mixplayer will do that.

    // Create Live View
    /*
    if change MaxChannelNumber, must be modify activity_call too.
     */
    for(int j = 0;j<PeerConnectionClient.getMaxChannelNumber();j++) {
      int id = getResources().getIdentifier("liveView" + j, "id", getPackageName());
      surfaceViews[j] = (SurfaceView) findViewById(id);
      surfaceHolders[j] = surfaceViews[j].getHolder();
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
    processingButtonUsing();
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
    if (bEnableButton){
      disconnect();
    }
  }


  // TODO: implement interface, that it is used by fragment_call
  public void onMessageSend() {

    final View v = View.inflate(this, R.layout.message, null);
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

  private void showSelectChannelDialog(final String nick_name,final Dialog preDialog) {
    View v = View.inflate(this, R.layout.request_live_view1, null);
    GridView gv = (GridView) v.findViewById(R.id.camera_gridview);
    List<Item> item = new ArrayList<Item>();

    for (int i = 0; i < 4; i++) {
      Item n = new Item("Channel("+(i+1)+")");
      item.add(n);
    }

    ItemArrayAdapter itemArrayAdapter = new ItemArrayAdapter(this, R.layout.view_channel, item);
    gv.setNumColumns(2);
    gv.setAdapter(itemArrayAdapter);

    final Builder MyAlertDialog = new AlertDialog.Builder(this);
    MyAlertDialog.setTitle("Live View List")
            .setView(v);
    final Dialog dialog = MyAlertDialog.show();

    gv.setOnItemLongClickListener(new GridView.OnItemLongClickListener() {
      public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        Log.d(TAG, "Select : " + liveViewInfo.getNickNameByIndex(position));
        liveViewInfo.remove(nick_name);
        peerConnectionClient.sendLiveViewRequest(position, nick_name);
        dialog.dismiss();
        preDialog.dismiss();
        return true; // if return true, then it will not trigger onItemClick.
        // if return false, then it will trigger onItemClick !
      }
    });

  }

  private void showLiveViewDialog() {

    if(liveViewInfo.getNumberOfLiveView()>0) {

      final View v = View.inflate(this, R.layout.request_live_view1, null);
      GridView gv = (GridView) v.findViewById(R.id.camera_gridview);
      List<Item> item = new ArrayList<Item>();
      String[] itemStringArray = new String[liveViewInfo.getNumberOfLiveView()];
      for (int i = 0; i < liveViewInfo.getNumberOfLiveView(); i++) {
        if(!liveViewInfo.getNickNameByIndex(i).equalsIgnoreCase("")){
          if(liveViewInfo.getOnChannel(liveViewInfo.getNickNameByIndex(i))<0) {
            Item n = new Item(liveViewInfo.getNickNameByIndex(i));
            item.add(n);
          }
        }
      }
      final List<Item> finalItem = item;


      ItemArrayAdapter itemArrayAdapter = new ItemArrayAdapter(this, R.layout.view_camera, item);
      gv.setNumColumns(liveViewInfo.getNumberOfLiveView());
      gv.setAdapter(itemArrayAdapter);

      final Builder MyAlertDialog = new AlertDialog.Builder(this);

      MyAlertDialog.setTitle("Live View List")
              .setView(v);

      final AlertDialog dialog1 = MyAlertDialog.create();

      //final Dialog dialog = MyAlertDialog.show();

      DisplayMetrics displaymetrics = new DisplayMetrics();
      getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);
      int height = displaymetrics.heightPixels;
      int width = displaymetrics.widthPixels;



      dialog1.show();
      dialog1.getWindow().setLayout(width-500,height-200);




      gv.setOnItemLongClickListener(new GridView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
              Log.d(TAG, "Select : " + finalItem.get(position).getName());
          boolean bNeedStopMode = false;
          if(bNeedStopMode) {
            int channel_index = findNonOccupyChannel();
            if (channel_index == -1) {
              onPeerMessage("All Channels are occupied");
              dialog1.dismiss();
              processingButtonUsing();
            } else {
              peerConnectionClient.sendLiveViewRequest(channel_index, finalItem.get(position).getName());
              liveViewInfo.setNickNameOnChannel(finalItem.get(position).getName(), channel_index);
              dialog1.dismiss();
              processingButtonUsing();
            }
            return true; // if return true, then it will not trigger onItemClick.
            // if return false, then it will trigger onItemClick !
          } else {
            if(PeerConnectionClient.getMaxChannelNumber()==1) {
              if (isOccupied(0)) {
                peerConnectionClient.sendVideoRequest(0, "STOP");
              }

              for (;;) {
                if (peerConnectionClient.getVideoDataChannelObserver(0).isIdle()) {
                  // TODO: This class only can use in one view case.
                  // wait for videoDataChannelObserver 0 is idle.
                  peerConnectionClient.sendLiveViewRequest(0, finalItem.get(position).getName());
                  TextView tv = (TextView) findViewById(R.id.contact_name_call);
                  tv.setText(finalItem.get(position).getName());
                  tv.setVisibility(View.VISIBLE);
                  liveViewInfo.setNickNameOnChannel(finalItem.get(position).getName(), 0);
                  dialog1.dismiss();
                  break;
                } else {


                }

              }


            }
            processingButtonUsing();
            return true;
          }
        }
      });
    } else {
      onPeerMessage("No Live View lo.");
    }


  }

  public boolean isOccupied(int index) {
    return !peerConnectionClient.getVideoDataChannelObserver(index).isIdle();
  }

  public int findNonOccupyChannel() {
    for(int i=0;i<PeerConnectionClient.getMaxChannelNumber();i++) {
      if (peerConnectionClient.getVideoDataChannelObserver(i).isIdle())
      {
        return i;
      }
    }

    return -1;
  }

  public void onQueryLiveView() {
    // TODO: send Query File Message.
    if(bEnableButton) {
      showLiveViewDialog();
    }
  }

  public void onRequestVideo() {

    final View v = View.inflate(this,R.layout.request_video,null);
    //TODO: auto detect channel number
    CheckBox[] checkBoxes = new CheckBox[4];

    checkBoxes[0] = (CheckBox) (v.findViewById(R.id.checkBox));
    checkBoxes[1] = (CheckBox) (v.findViewById(R.id.checkBox2));
    checkBoxes[2] = (CheckBox) (v.findViewById(R.id.checkBox3));
    checkBoxes[3] = (CheckBox) (v.findViewById(R.id.checkBox4));

    for(int i=0;i<4;i++) {
      if(i<PeerConnectionClient.getMaxChannelNumber()) {
        checkBoxes[i].setVisibility(View.VISIBLE);
      } else {
        checkBoxes[i].setVisibility(View.INVISIBLE);
      }
    }

    new AlertDialog.Builder(this)
            .setTitle("Send Video Request")
            .setView(v)
            .setPositiveButton("Send", new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int which) {
                CheckBox ch0 = (CheckBox) (v.findViewById(R.id.checkBox));
                CheckBox ch1 = (CheckBox) (v.findViewById(R.id.checkBox2));
                CheckBox ch2 = (CheckBox) (v.findViewById(R.id.checkBox3));
                CheckBox ch3 = (CheckBox) (v.findViewById(R.id.checkBox4));

                boolean[] checkedArray = new boolean[4];

                checkedArray[0] = ch0.isChecked();
                checkedArray[1] = ch1.isChecked();
                checkedArray[2] = ch2.isChecked();
                checkedArray[3] = ch3.isChecked();

                EditText filePath_et = (EditText) (v.findViewById(R.id.file_path));

                String filePath = filePath_et.getText().toString();

                for (int channelIndex = 0; channelIndex < 4; channelIndex++) {
                  if (checkedArray[channelIndex]) {
                    peerConnectionClient.sendVideoRequest(channelIndex, filePath);
                  }
                }
              }
            })
            .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int which) {
                logAndToast("Cancel!");
              }
            }).show();
  }

  public void onMessageTransfer() {
    Log.d(TAG, "onMessageTransfer");
    logAndToast("Send Message HELLO");
    peerConnectionClient.sendMessage("HELLO");
  }

  public void onFileTransfer()
  {
    Log.d(TAG, "onFileTransfer");
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

  }

  // Should be called from UI thread
  private void callConnected() {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    Log.i(TAG, "Call connected: delay=" + delta + "ms");

    // Update video view.
    //updateVideoView();
    if(!bCreateSide) {
      Thread registerRoomThread = new RoomCheckerThread("RoomRemove", username, password,"");
      registerRoomThread.start();
    }

    // Enable statistics callback.
    peerConnectionClient.enableStatsEvents(true, STAT_CALLBACK_PERIOD);
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
          peerConnectionClient.setLiveViewInfo(liveViewInfo);
          peerConnectionClient.setUsernameAndPassword(username, password);
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

    if(me!=null) {
      me.release();
      me = null;
    }

    if(me2!=null) {
      me2.release();
      me2 = null;
    }

    if (peerConnectionClient != null) {
      peerConnectionClient.stopAllThread();
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

}
