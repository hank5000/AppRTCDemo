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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.TextureView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.EditorInfo;
import android.webkit.URLUtil;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Random;

/**
 * Handles the initial setup where the user selects which room to join.
 */
public class ConnectActivity extends Activity {
  private static final String TAG = "ConnectActivity";
  private static final int CONNECTION_REQUEST = 1;
  private static boolean commandLineRun = false;
  private ConnectActivity connectActivity = this;
  private ImageButton refreshButton;

  private static final boolean bForSaleMode = true;

  private ImageButton connectButton;
  private EditText roomEditText;
  private TextView roomEdittextDescription;
  private TextView normalModeDescription;

  private ImageButton online_offline;
  private boolean bOnline = false;

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


  private boolean bNoCreateSide = false;
  private boolean bCreateChannelSide = false;
  private boolean bAutoConnect = false;
  private String username = "";
  private String password = "";
  private String DS2RoomID = "";

  public void hideNormalModeInfo() {
    connectButton.setVisibility(View.INVISIBLE);
    normalModeDescription.setVisibility(View.INVISIBLE);
    roomEdittextDescription.setVisibility(View.INVISIBLE);
    roomEditText.setVisibility(View.INVISIBLE);
  }


  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

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

    setContentView(R.layout.activity_connect);


    refreshButton = (ImageButton) findViewById(R.id.refresh_button);
    refreshButton.setOnClickListener(refreshRoom);
    connectButton = (ImageButton) findViewById(R.id.connect_button);
    connectButton.setOnClickListener(connectListener);

    normalModeDescription = (TextView) findViewById(R.id.normal_mode_description);
    roomEdittextDescription = (TextView) findViewById(R.id.room_edittext_description);
    roomEditText = (EditText) findViewById(R.id.room_edittext);



    online_offline = (ImageButton) findViewById(R.id.online_offline_button);
    online_offline.setOnClickListener(onlineListener);

    if(bForSaleMode) {
      hideNormalModeInfo();
    }

    bCreateChannelSide = sharedPref.getBoolean(
            keyperfChannelCreateSide,
            Boolean.valueOf(getString(R.string.pref_enable_channel_create_side_default)));

    if(bCreateChannelSide) {
      if(bAutoConnect) {
        String roomId = Integer.toString((new Random()).nextInt(100000000));
        roomEditText.setText(roomId);
        connectToRoom(0);
      }

    } else {

      GetRoomIdThread firstUpdate = new GetRoomIdThread(connectActivity,true);
      firstUpdate.start();

    }

//    // If an implicit VIEW intent is launching the app, go directly to that URL.
//    final Intent intent = getIntent();
//    if ("android.intent.action.VIEW".equals(intent.getAction())
//        && !commandLineRun) {
//      commandLineRun = true;
//      boolean loopback = intent.getBooleanExtra(
//          CallActivity.EXTRA_LOOPBACK, false);
//      int runTimeMs = intent.getIntExtra(
//          CallActivity.EXTRA_RUNTIME, 0);
//      String room = sharedPref.getString(keyprefRoom, "");
//      roomEditText.setText(room);
//      connectToRoom(runTimeMs);
//      return;
//    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.connect_menu, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    // Handle presses on the action bar items.
    if (item.getItemId() == R.id.action_settings) {
      Intent intent = new Intent(this, SettingsActivity.class);
      startActivity(intent);
      return true;
    } else {
      return super.onOptionsItemSelected(item);
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    String room = roomEditText.getText().toString();
    //String roomListJson = new JSONArray(roomList).toString();
    SharedPreferences.Editor editor = sharedPref.edit();
    editor.putString(keyprefRoom, room);
    //editor.putString(keyprefRoomList, roomListJson);
    editor.commit();
  }

  @Override
  public void onResume() {
    super.onResume();
    String room = sharedPref.getString(keyprefRoom, "");
    roomEditText.setText(room);
//    String roomListJson = sharedPref.getString(keyprefRoomList, null);
//    if (roomListJson != null) {
//      try {
//        JSONArray jsonArray = new JSONArray(roomListJson);
//        for (int i = 0; i < jsonArray.length(); i++) {
//          roomList.add(jsonArray.get(i).toString());
//        }
//      } catch (JSONException e) {
//        Log.e(TAG, "Failed to load room list: " + e.toString());
//      }
//    }

    bCreateChannelSide = sharedPref.getBoolean(
            keyperfChannelCreateSide,
            Boolean.valueOf(getString(R.string.pref_enable_channel_create_side_default)));

    if(bCreateChannelSide) {
      if(bAutoConnect) {
        String roomId = Integer.toString((new Random()).nextInt(100000000));
        roomEditText.setText(roomId);
        connectToRoom(0);
      }
    }
  }

  @Override
  protected void onActivityResult(
      int requestCode, int resultCode, Intent data) {
    if (requestCode == CONNECTION_REQUEST && commandLineRun) {
      Log.d(TAG, "Return: " + resultCode);
      setResult(resultCode);
      commandLineRun = false;
      finish();
    }
  }

  public void changeOnOfflineButton(boolean isOnline) {
    bOnline = isOnline;
    if(isOnline) {
      online_offline.setBackgroundResource(0);
      online_offline.setImageDrawable(getResources().getDrawable(R.drawable.online_shadow));
      online_offline.setAlpha((float) 1);

      online_offline.setScaleType(ImageView.ScaleType.FIT_XY);
    } else {
      online_offline.setBackgroundResource(0);
      online_offline.setImageDrawable(getResources().getDrawable(R.drawable.offline_shadow));
      online_offline.setAlpha((float)0.1);
      online_offline.setScaleType(ImageView.ScaleType.FIT_XY);

    }
  }

  public class GetRoomIdThread extends Thread {
    ConnectActivity act = null;
    boolean bUpdate = false;
    String registerRoomServerUrl = "http://122.147.15.216/";

    public GetRoomIdThread(ConnectActivity connect_activity,boolean only_update) {
        this.act = connect_activity;
        this.bUpdate = only_update;
      }

      public void run() {
        try {
          bNoCreateSide = false;
          final String ACCESS_METHOD = "RoomGet";
          final String PREFIX_USERNAME   = "Username";
          final String PREFIX_PASSWORD   = "Password";
          String urlString = registerRoomServerUrl + ACCESS_METHOD + "?" + PREFIX_USERNAME + "="+username+"&" +PREFIX_PASSWORD+ "="+password;

          URL url = new URL(urlString);
          URLConnection urlConnection = url.openConnection();
          urlConnection.setRequestProperty("Connection", "close");
          HttpURLConnection httpConn = (HttpURLConnection)urlConnection;
          InputStream is;

          if (httpConn.getResponseCode() >= 400) {
            is = httpConn.getErrorStream();
          } else {
            is = httpConn.getInputStream();
          }

          BufferedReader reader = new BufferedReader(new InputStreamReader(is));
          String line = "";
          if((line = reader.readLine()) != null) {
            Log.d("HANK", "RoomId:" + line);
            final String RoomId = line;
            DS2RoomID = RoomId;
            if(RoomId.equalsIgnoreCase("null")) {
              bNoCreateSide = true;
            }
            final boolean bConnect = !bUpdate;
            final boolean bNoSource= bNoCreateSide;
            runOnUiThread(new Runnable() {
              @Override
              public void run() {
                if(bNoSource) {
                  Log.e("HANK", "DS2 is not online!!!");
                  act.changeOnOfflineButton(false);
//                  GetRoomIdThread againThread = new GetRoomIdThread(connectActivity,true);
//                  againThread.start();
                } else {
                  act.changeOnOfflineButton(true);
                  if(bConnect) {
                    EditText roomId_ed = (EditText) findViewById(R.id.room_edittext);
                    roomId_ed.setText(DS2RoomID, TextView.BufferType.EDITABLE);
                    act.connectToRoom(0);
                  }
                }
              }
            });
          }

        } catch (IOException e) {
          Log.e("HANK", "open connection fail"+e);
          runOnUiThread(new Runnable() {
            @Override
            public void run() {
              act.changeOnOfflineButton(false);
            }
          });
        }
      }
  }

  private final OnClickListener connectListener = new OnClickListener() {
    @Override
    public void onClick(View view) {

      commandLineRun = false;
      updateAuthenticationInformation();
//      GetRoomIdThread connect = new GetRoomIdThread(connectActivity,false);
//      connect.start();
      connectToRoom(0);
    }
  };

  private final OnClickListener onlineListener = new OnClickListener() {
    @Override
    public void onClick(View view) {
      if(bOnline) {
        commandLineRun = false;
        updateAuthenticationInformation();
        GetRoomIdThread connect = new GetRoomIdThread(connectActivity, false);
        connect.start();
      }
    }
  };




  public void connectToRoom(int runTimeMs) {

    String roomId = roomEditText.getText().toString();


    String roomUrl = sharedPref.getString(
            keyprefRoomServerUrl,
            getString(R.string.pref_room_server_url_default));

    // Test if CpuOveruseDetection should be disabled. By default is on.
    boolean cpuOveruseDetection = sharedPref.getBoolean(
        keyprefCpuUsageDetection,
        Boolean.valueOf(
            getString(R.string.pref_cpu_usage_detection_default)));

    // Check statistics display option.
    boolean displayHud = sharedPref.getBoolean(keyprefDisplayHud,
        Boolean.valueOf(getString(R.string.pref_displayhud_default)));

    boolean bEnableChatRoom = sharedPref.getBoolean(
            keyperfEnableChatRoom,
            Boolean.valueOf(getString(R.string.pref_enable_chat_room_key_default)));

    boolean bCreateChannelSide = sharedPref.getBoolean(
            keyperfChannelCreateSide,
            Boolean.valueOf(getString(R.string.pref_enable_channel_create_side_default)));

    // Start AppRTCDemo activity.
    Log.d(TAG, "Connecting to room " + roomId + " at URL " + roomUrl);
    if (validateUrl(roomUrl)) {
      Uri uri = Uri.parse(roomUrl);
      Intent intent = new Intent(this, CallActivity.class);
      intent.setData(uri);
      intent.putExtra(CallActivity.EXTRA_ROOMID, roomId);
      intent.putExtra(CallActivity.EXTRA_CPUOVERUSE_DETECTION,
          cpuOveruseDetection);
      intent.putExtra(CallActivity.EXTRA_DISPLAY_HUD, displayHud);
      // Hank Extension
      intent.putExtra(CallActivity.EXTRA_CHAT_ROOM, bEnableChatRoom);
      intent.putExtra(CallActivity.EXTRA_CREATE_SIDE, bCreateChannelSide);
      intent.putExtra(CallActivity.EXTRA_USERNAME,username);
      intent.putExtra(CallActivity.EXTRA_PASSWORD,password);
      //
      intent.putExtra(CallActivity.EXTRA_CMDLINE, commandLineRun);
      intent.putExtra(CallActivity.EXTRA_RUNTIME, runTimeMs);


      startActivityForResult(intent, CONNECTION_REQUEST);
    }
  }

  private boolean validateUrl(String url) {
    if (URLUtil.isHttpsUrl(url) || URLUtil.isHttpUrl(url)) {
      return true;
    }

    new AlertDialog.Builder(this)
        .setTitle(getText(R.string.invalid_url_title))
        .setMessage(getString(R.string.invalid_url_text, url))
        .setCancelable(false)
        .setNeutralButton(R.string.ok, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
              dialog.cancel();
            }
          }).create().show();
    return false;
  }

//  private final OnClickListener addRoomListener = new OnClickListener() {
//    @Override
//    public void onClick(View view) {
//      String newRoom = roomEditText.getText().toString();
//      if (newRoom.length() > 0 && !roomList.contains(newRoom)) {
//        adapter.add(newRoom);
//        adapter.notifyDataSetChanged();
//      }
//    }
//  };

  private void updateAuthenticationInformation() {
    username = sharedPref.getString(
            keyUsername,
            getString(R.string.pref_username_default));

    password = sharedPref.getString(
            keyPassword,
            getString(R.string.pref_password_default));
  }

  private final OnClickListener refreshRoom = new OnClickListener() {
    @Override
    public void onClick(View view) {

      updateAuthenticationInformation();
      GetRoomIdThread updateThread = new GetRoomIdThread(connectActivity,true);
      updateThread.start();

    }
  };


}
