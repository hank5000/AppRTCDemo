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

import android.app.Activity;
import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import com.via.rtc.util.GlobalSetting;

/**
 * Fragment for call control.
 */
public class CallFragment extends Fragment {
  private View controlView;
  private TextView contactView;
  private ImageButton disconnectButton;

  private ImageButton messageButton;
  private ImageButton fileButton;
  private ImageButton reqVideoButton;

  private OnCallEvents callEvents;

  /*
    if it is for Sales mode, turn off the fileButton Message button , and contact View
   */
  private boolean bForSales = GlobalSetting.member.isForSalesMode();


  /**
   * Call control interface for container activity.
   */
  public interface OnCallEvents {
    public void onCallHangUp();
    public void onMessageSend();
    public void onQueryLiveView();
    public void onRequestVideo();
    public void onAuthenticationReceived();
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container,
      Bundle savedInstanceState) {
    controlView =
        inflater.inflate(R.layout.fragment_call_left, container, false);

    // Create UI controls.
    contactView =
        (TextView) controlView.findViewById(R.id.contact_name_call);
    disconnectButton =
        (ImageButton) controlView.findViewById(R.id.button_call_disconnect);
    messageButton =
        (ImageButton) controlView.findViewById(R.id.button_call_message);
    fileButton =
        (ImageButton) controlView.findViewById(R.id.button_call_liveview);
    reqVideoButton =
            (ImageButton) controlView.findViewById(R.id.button_call_video);


    // Add buttons click events.
    disconnectButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        callEvents.onCallHangUp();
      }
    });

    messageButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        callEvents.onMessageSend();
      }
    });

    fileButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        callEvents.onQueryLiveView();
      }
    });

    reqVideoButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        callEvents.onRequestVideo();
      }
    });


    messageButton.setVisibility(View.INVISIBLE);
    reqVideoButton.setVisibility(View.INVISIBLE);
    contactView.setVisibility(View.INVISIBLE);

    return controlView;
  }

  @Override
  public void onStart() {
    super.onStart();

    Bundle args = getArguments();
    if (args != null) {
      String contactName = args.getString(CallActivity.EXTRA_ROOMID);
      contactView.setText(contactName);
    }
//    if (!videoCallEnabled) {
//      cameraSwitchButton.setVisibility(View.INVISIBLE);
//    }
  }

  @Override
  public void onAttach(Activity activity) {
    super.onAttach(activity);
    callEvents = (OnCallEvents) activity;
  }

}
