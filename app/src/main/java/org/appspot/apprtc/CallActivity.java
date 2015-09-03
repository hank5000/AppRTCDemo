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
import org.appspot.apprtc.util.LiveViewInfo;
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
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager.LayoutParams;
import android.widget.Adapter;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ListView;
import android.widget.NumberPicker;
import android.widget.Toast;

import org.appspot.apprtc.util.VideoDataChannelObserver;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;
import org.webrtc.StatsReport;
import org.webrtc.VideoRendererGui.ScalingType;
import android.widget.AdapterView;
import android.app.AlertDialog.Builder;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Activity for peer connection call setup, call waiting
 * and call view.
 */
public class CallActivity extends Activity
    implements AppRTCClient.SignalingEvents,
      PeerConnectionClient.PeerConnectionEvents,
      CallFragment.OnCallEvents {

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

    final String sendHttpRoomId = roomId;
    boolean loopback = intent.getBooleanExtra(EXTRA_LOOPBACK, false);

    bCreateSide = intent.getBooleanExtra(EXTRA_CREATE_SIDE,false);

    peerConnectionParameters = new PeerConnectionParameters(
        intent.getBooleanExtra(EXTRA_CPUOVERUSE_DETECTION, true),
            /*Hank Extension*/
        intent.getBooleanExtra(EXTRA_CHAT_ROOM,false),
        intent.getBooleanExtra(EXTRA_CREATE_SIDE,false));

    commandLineRun = intent.getBooleanExtra(EXTRA_CMDLINE, false);
    runTimeMs = intent.getIntExtra(EXTRA_RUNTIME, 0);

    // Create connection client and connection parameters.
    appRtcClient = new WebSocketRTCClient(this, new LooperExecutor(),this);
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

    Thread a = new Thread(new Runnable() {
      @Override
      public void run() {

          // TODO: Register room number to via server
          try {
            String method = "";
            if(bCreateSide) {
              method = "RoomCreate";
            } else {
              method = "RoomRemove";
            }
            String Username   = "Username";
            String Password   = "Password";
            String RoomId     = "RoomId";
            String urlString = "http://122.147.15.216/"+method+"?"+Username+"=HankWu&"+Password+"=123456&"+RoomId+"="+sendHttpRoomId;
            URL url = new URL(urlString);
            URLConnection conn = url.openConnection();
            Log.e("HANK", "connect success! "+urlString);
            InputStream is = conn.getInputStream();

          } catch (IOException e) {
            Log.e("HANK", "open connection fail "+ e);

          }
      }
    });
    a.start();

    createPeerConnectionFactory();

    int j = 0;
    // Create Live View
    surfaceViews[j] = (SurfaceView) findViewById(R.id.liveView_call);
    surfaceHolders[j] = surfaceViews[0].getHolder();
    surfaceHolders[j].addCallback(new SurfaceHolder.Callback() {
      @Override
      public void surfaceCreated(SurfaceHolder holder) {
        if(peerConnectionClient!=null) {
          if(peerConnectionClient.isCreateSide()) {
            me = new MediaPlayer();
            me.reset();
            me.setDisplay(holder);
            try {
              String OV_IP = "192.168.12.112";
              me.setDataSource("OV://"+OV_IP+":1000");
              me.prepare();
              me.start();
              liveViewInfo.add(OV_IP,"Camera01");
            } catch (IOException e) {
              e.printStackTrace();
            }
          }
        }
      }

      @Override
      public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
      }

      @Override
      public void surfaceDestroyed(SurfaceHolder holder) {
        if(me!=null) {
          me.release();
          me = null;
        }
      }
    });

    j=1;
    surfaceViews[j] = (SurfaceView) findViewById(R.id.liveView_call2);
    surfaceHolders[j] = surfaceViews[1].getHolder();
    surfaceHolders[j].addCallback(new SurfaceHolder.Callback() {
      @Override
      public void surfaceCreated(SurfaceHolder holder) {
        if(peerConnectionClient.isCreateSide()) {
          me2 = new MediaPlayer();
          me2.reset();
          me2.setDisplay(holder);
          try {
            String OV_IP = "192.168.12.114";
            me2.setDataSource("OV://"+OV_IP+":1000");
            me2.prepare();
            me2.start();
            liveViewInfo.add(OV_IP,"Camera02");
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
      }

      @Override
      public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
      }

      @Override
      public void surfaceDestroyed(SurfaceHolder holder) {
        if(me2!=null) {
          me2.release();
          me2 = null;
        }
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
        Log.d("HANK", "Select : " + liveViewInfo.getNickNameByIndex(position));
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
      final Dialog dialog = MyAlertDialog.show();

      gv.setOnItemLongClickListener(new GridView.OnItemLongClickListener() {
        public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
          Log.d("HANK", "Select : " + finalItem.get(position).getName());
          boolean bNeedStopMode = true;
          if(bNeedStopMode) {
            int channel_index = findNonOccupyChannel();
            if (channel_index == -1) {
              onPeerMessage("All Channels are occupied");
              dialog.dismiss();
            } else {
              peerConnectionClient.sendLiveViewRequest(channel_index, finalItem.get(position).getName());
              liveViewInfo.setNickNameOnChannel(finalItem.get(position).getName(), channel_index);
              dialog.dismiss();
            }
            return true; // if return true, then it will not trigger onItemClick.
            // if return false, then it will trigger onItemClick !
          } else {
            if(peerConnectionClient.MAX_CHANNEL_NUMBER==1) {
              if (isOccupied(0)) {
                peerConnectionClient.sendVideoRequest(0, "STOP");
              }

              for (;;) {
                if (peerConnectionClient.videoDataChannelObservers[0].isIdle()) {
                  // TODO: This class only can use in one view case.
                  // wait for videoDataChannelObserver 0 is idle.
                  peerConnectionClient.sendLiveViewRequest(0, finalItem.get(position).getName());
                  liveViewInfo.setNickNameOnChannel(finalItem.get(position).getName(), 0);
                  dialog.dismiss();
                  break;
                } else {


                }

              }
            }

            return true;
          }
        }
      });
    } else {
      onPeerMessage("No Live View lo.");
    }
  }

  public boolean isOccupied(int index) {
    return !peerConnectionClient.videoDataChannelObservers[index].isIdle();
  }

  public int findNonOccupyChannel() {
    for(int i=0;i<peerConnectionClient.MAX_CHANNEL_NUMBER;i++) {
      if (peerConnectionClient.videoDataChannelObservers[i].isIdle())
      {
        return i;
      }
    }

    return -1;
  }

  public void onQueryFile() {
    // TODO: send Query File Message.

    showLiveViewDialog();

  }

  public void onRequestVideo() {

    final View v = View.inflate(this,R.layout.request_video,null);
    //TODO: auto detect channel number
    CheckBox ch1 = (CheckBox) (v.findViewById(R.id.checkBox2));
    CheckBox ch2 = (CheckBox) (v.findViewById(R.id.checkBox3));
    CheckBox ch3 = (CheckBox) (v.findViewById(R.id.checkBox4));
    ch1.setVisibility(View.INVISIBLE);
    ch2.setVisibility(View.INVISIBLE);
    ch3.setVisibility(View.INVISIBLE);

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
          peerConnectionClient.setLiveViewInfo(liveViewInfo);
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
