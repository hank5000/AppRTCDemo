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

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import android.view.SurfaceView;


import org.appspot.apprtc.AppRTCClient.SignalingParameters;
import org.appspot.apprtc.util.AudioDataChannelObserver;
import org.appspot.apprtc.util.LiveViewInfo;
import org.appspot.apprtc.util.LooperExecutor;
import org.appspot.apprtc.util.SendingLiveViewAudioThread;
import org.appspot.apprtc.util.SendingLiveViewThread;
import org.appspot.apprtc.util.SendingThread;
import org.appspot.apprtc.util.VideoDataChannelObserver;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnection.IceConnectionState;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.StatsObserver;
import org.webrtc.StatsReport;

import org.appspot.apprtc.util.CommunicationChannelObserver;


import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Peer connection client implementation.
 *
 * <p>All public methods are routed to local looper thread.
 * All PeerConnectionEvents callbacks are invoked from the same looper thread.
 * This class is a singleton.
 */
public class PeerConnectionClient {

  private static final String TAG = "PCRTCClient";

  private static final String DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT = "DtlsSrtpKeyAgreement";
  private static final String VIDEO_CHANNEL_NAME = "VideoChannel";
  private static final String AUDIO_CHANNEL_NAME = "AudioChannel";
  private static final String COMMUNICATION_CHANNEL_NAME = "CommunicationChannel";

  private static final PeerConnectionClient instance = new PeerConnectionClient();
  private final PCObserver pcObserver = new PCObserver();
  private final SDPObserver sdpObserver = new SDPObserver();
  private final LooperExecutor executor;

  // use for data channel video
  private SurfaceView liveViewSurfaces[];

  public final int MAX_CHANNEL_NUMBER = 1;

  public DataChannel communicationChannel = null;
  private DataChannel[] videoDataChannels = new DataChannel[MAX_CHANNEL_NUMBER];
  private DataChannel[] audioDataChannels = new DataChannel[MAX_CHANNEL_NUMBER];
  public SendingThread[] sendingThreads = new SendingThread[MAX_CHANNEL_NUMBER*2];
  public SendingLiveViewThread[] sendingLiveViewThreads = new SendingLiveViewThread[MAX_CHANNEL_NUMBER];
  public SendingLiveViewAudioThread[] sendingLiveViewAudioThreads = new SendingLiveViewAudioThread[MAX_CHANNEL_NUMBER];
  public VideoDataChannelObserver[] videoDataChannelObservers = new VideoDataChannelObserver[MAX_CHANNEL_NUMBER];
  public AudioDataChannelObserver[] audioDataChannelObservers = new AudioDataChannelObserver[MAX_CHANNEL_NUMBER];


  public SendingLiveViewThread sendingLiveView = null;
  public SendingLiveViewAudioThread sendingLiveViewAudio = null;
  private CommunicationChannelObserver communicationChannelObserver = null;

  public String User = "default_user";
  public String Pass = "default_password";
  private boolean bPass = false;
  private boolean bAutoInputAuthentication = true;
  public boolean isAutoInputAuthentication() {
    return bAutoInputAuthentication;
  }
  private LiveViewInfo liveViewInfo = null;

  public boolean isPass() {
    return bPass;
  }

  public void setLiveViewInfo(LiveViewInfo lvi) {
    liveViewInfo = lvi;
  }
  public LiveViewInfo getLiveViewInfo() {
    return liveViewInfo;
  }

  public boolean checkAuthencation(String user, String pass) {
    if(user.equalsIgnoreCase(User) && pass.equalsIgnoreCase(Pass)) {
      bPass = true;
    }
    return bPass;
  }

  public void stopAllThread() {
    for(int i=0;i<MAX_CHANNEL_NUMBER*2;i++) {
      if(sendingThreads[i]!=null) {
        sendingThreads[i].stopThread();
        sendingThreads[i].interrupt();
        sendingThreads[i] = null;
      }
    }
    for(int i=0;i<MAX_CHANNEL_NUMBER;i++) {
      if(sendingLiveViewThreads[i]!=null) {
        sendingLiveViewThreads[i].stopThread();
        sendingLiveViewThreads[i].interrupt();
        sendingLiveViewThreads[i] = null;
      }

      if(sendingLiveViewAudioThreads[i]!=null) {
        sendingLiveViewAudioThreads[i].stopThread();
        sendingLiveViewAudioThreads[i].interrupt();
        sendingLiveViewAudioThreads[i] = null;
      }
    }


  }

  public DataChannel getVideoDataChannel(int index) {
    if(index>MAX_CHANNEL_NUMBER)
    {
      return null;
    }
    return videoDataChannels[index];
  }

  public DataChannel getAudioDataChannel(int index) {
    if(index>MAX_CHANNEL_NUMBER)
    {
      return null;
    }
    return audioDataChannels[index];
  }

  public void sendLiveViewData(int channel, String nickname) {
    final String ip_address = liveViewInfo.getIpAddress(nickname);
    sendingLiveView = new SendingLiveViewThread(this,channel,ip_address);
    sendingLiveViewThreads[channel] = sendingLiveView;
    sendingLiveViewAudio = new SendingLiveViewAudioThread(this,channel,ip_address);
    sendingLiveViewAudioThreads[channel] = sendingLiveViewAudio;

    sendingLiveViewAudio.start();
    sendingLiveView.start();

  }

  public void stopChannelSending(int index) {
    if(sendingThreads[index]!=null) {
      sendingThreads[index].stopThread();
      sendingThreads[index].interrupt();
      sendingThreads[index] = null;
    }

    if(sendingLiveViewThreads[index]!=null) {
      sendingLiveViewThreads[index].stopThread();
      sendingLiveViewThreads[index].interrupt();
      sendingLiveViewThreads[index] = null;
    }

    if(sendingLiveViewAudioThreads[index]!=null) {
      sendingLiveViewAudioThreads[index].stopThread();
      sendingLiveViewAudioThreads[index].interrupt();
      sendingLiveViewAudioThreads[index] = null;
    }


  }

  public DataChannel getCommunicationChannel() {
    return communicationChannel;
  }


  // video/file content divide to DATA_CAHNNEL_DIVIDE_SIZE size (Byte) and send.
  public final int DATA_CHANNEL_DIVIDE_SIZE_BYTE = 1024;
  //

  private PeerConnectionFactory factory;
  private PeerConnection peerConnection;
  PeerConnectionFactory.Options options = null;

  private boolean isError;
  private Timer statsTimer;

  private SignalingParameters signalingParameters;
  private MediaConstraints pcConstraints;
  private MediaConstraints sdpMediaConstraints;
  private PeerConnectionParameters peerConnectionParameters;
  // Queued remote ICE candidates are consumed only after both local and
  // remote descriptions are set. Similarly local ICE candidates are sent to
  // remote peer after both local and remote description are set.
  private LinkedList<IceCandidate> queuedRemoteCandidates;
  private PeerConnectionEvents events;

  public PeerConnectionEvents getPeerConnectionEvents() {
    return events;
  }

  private boolean isInitiator;
  private SessionDescription localSdp; // either offer or answer SDP

  public void setLiveViewSurface(SurfaceView[] Surfaces)
  {
    this.liveViewSurfaces = Surfaces;
  }

  public boolean isCreateSide() {
    return peerConnectionParameters.bCreateSide;
  }
  /**
   * Peer connection parameters.
   */
  public static class PeerConnectionParameters {

    public final boolean cpuOveruseDetection;
    public final boolean bEnableChatRoom;
    public final boolean bCreateSide;

    public PeerConnectionParameters(boolean cpuOveruseDetection, boolean enableChatRoom, boolean createSide) {

      this.cpuOveruseDetection = cpuOveruseDetection;
      this.bEnableChatRoom = enableChatRoom;
      this.bCreateSide = createSide;
    }
  }

  /**
   * Peer connection events.
   */
  public static interface PeerConnectionEvents {
    /**
     * Callback fired once local SDP is created and set.
     */
    public void onLocalDescription(final SessionDescription sdp);

    /**
     * Callback fired once local Ice candidate is generated.
     */
    public void onIceCandidate(final IceCandidate candidate);

    /**
     * Callback fired once connection is established (IceConnectionState is
     * CONNECTED).
     */
    public void onIceConnected();

    /**
     * Callback fired once connection is closed (IceConnectionState is
     * DISCONNECTED).
     */
    public void onIceDisconnected();

    /**
     * Callback fired once peer connection is closed.
     */
    public void onPeerConnectionClosed();

    /**
     * Callback fired once peer connection statistics is ready.
     */
    public void onPeerConnectionStatsReady(final StatsReport[] reports);

    /**
     * Callback fired once peer connection error happened.
     */
    public void onPeerConnectionError(final String description);

    public void onPeerMessage(final String description);

    public void onShowReceivedMessage(final String description);

    public void onAuthenticationReceived();
  }

  private PeerConnectionClient() {
    executor = new LooperExecutor();
    // Looper thread is started once in private ctor and is used for all
    // peer connection API calls to ensure new peer connection factory is
    // created on the same thread as previously destroyed factory.
    executor.requestStart();

    for(int i=0;i<MAX_CHANNEL_NUMBER;i++) {
      sendingThreads[i] = null;
      sendingThreads[i+MAX_CHANNEL_NUMBER] = null;
      sendingLiveViewThreads[i] = null;
      sendingLiveViewAudioThreads[i] = null;
    }
  }

  public static PeerConnectionClient getInstance() {
    return instance;
  }

  public void createPeerConnectionFactory(
      final Context context,
      final PeerConnectionParameters peerConnectionParameters,
      final PeerConnectionEvents events) {
    this.peerConnectionParameters = peerConnectionParameters;
    this.events = events;
    // Reset variables to initial states.
    factory = null;
    peerConnection = null;

    isError = false;
    queuedRemoteCandidates = null;
    localSdp = null; // either offer or answer SDP
    statsTimer = new Timer();

    executor.execute(new Runnable() {
      @Override
      public void run() {
        createPeerConnectionFactoryInternal(context);
      }
    });
  }

  public void createPeerConnection(final SignalingParameters signalingParameters) {
    if (peerConnectionParameters == null) {
      Log.e(TAG, "Creating peer connection without initializing factory.");
      return;
    }

    this.signalingParameters = signalingParameters;
    executor.execute(new Runnable() {
      @Override
      public void run() {
        createMediaConstraintsInternal();
        createPeerConnectionInternal();
      }
    });
  }

  public void close() {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        closeInternal();
      }
    });
  }

  private void createPeerConnectionFactoryInternal(
      Context context) {

    isError = false;
    PeerConnectionFactory.initializeFieldTrials(null);

    if (!PeerConnectionFactory.initializeAndroidGlobals(context, true, true, false, null)) {
      events.onPeerConnectionError("Failed to initializeAndroidGlobals");
    }

    factory = new PeerConnectionFactory();
    if (options != null) {
      Log.d(TAG, "Factory networkIgnoreMask option: " + options.networkIgnoreMask);
      factory.setOptions(options);
    }
    Log.d(TAG, "Peer connection factory created.");
  }

  private void createMediaConstraintsInternal() {
    // Create peer connection constraints.
    pcConstraints = new MediaConstraints();
    pcConstraints.optional.add(
            new MediaConstraints.KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "true"));

    // Create SDP constraints.
    sdpMediaConstraints = new MediaConstraints();
    // if no OfferToReceiveAudio, it will assert
    sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
            "OfferToReceiveAudio", "true"));

  }

  final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
  public static String bytesToHex(byte[] bytes) {
    char[] hexChars = new char[bytes.length * 2];
    for ( int j = 0; j < bytes.length; j++ ) {
      int v = bytes[j] & 0xFF;
      hexChars[j * 2] = hexArray[v >>> 4];
      hexChars[j * 2 + 1] = hexArray[v & 0x0F];
    }
    return new String(hexChars);
  }


  /// DATA CHANNEL SENDER FUNCTION -START- ///

  // MESSAGE/REQUEST METHOD
  // base on communicationChannel.
  // MESSAGE SENDER
  public void sendMessage(String data) {
    final DataChannel outChannel = communicationChannel;

    data = CommunicationChannelObserver.MESSAGE_PREFIX+":"+data;
    ByteBuffer buffer = ByteBuffer.wrap(data.getBytes());
    if(outChannel!=null) {
      Log.d("HANK","SendMessage");
      outChannel.send(new DataChannel.Buffer(buffer,false));
    }
  }

  public void sendLiveViewInfo() {
    final DataChannel outChannel = communicationChannel;

    String data = CommunicationChannelObserver.FEEDBACK_LIVEVIEW_INFO;
    for(int i=0;i<8;i++) {
      if(!liveViewInfo.getNickNameByIndex(i).equalsIgnoreCase("")) {
        data = data +":"+liveViewInfo.getNickNameByIndex(i);
      }
    }
    ByteBuffer buffer = ByteBuffer.wrap(data.getBytes());
    if(outChannel!=null) {
      Log.d("HANK","SendMessage");
      outChannel.send(new DataChannel.Buffer(buffer,false));
    }
  }

  public void sendRequestLiveViewInfo() {
    final DataChannel outChannel = communicationChannel;

    String data = CommunicationChannelObserver.REQUEST_LIVEVIEW_INFO;
    ByteBuffer buffer = ByteBuffer.wrap(data.getBytes());
    if(outChannel!=null) {
      Log.d("HANK","SendMessage");
      outChannel.send(new DataChannel.Buffer(buffer,false));
    }
  }

  public void sendRequestAuthentication() {
    String data = CommunicationChannelObserver.REQUEST_AUTHENTICATION;
    final DataChannel outChannel = communicationChannel;
    ByteBuffer buffer = ByteBuffer.wrap(data.getBytes());
    Log.d("HANK","sendRequestAuthentication");
    outChannel.send(new DataChannel.Buffer(buffer,false));

  }

  public void sendAuthencationInformation(String user,String pass) {
    String data = CommunicationChannelObserver.FEEDBACK_AUTHENTICATION + ":" + CommunicationChannelObserver.REQUEST_USERNAME + ":" + user + ":" +CommunicationChannelObserver.REQUEST_PASSWORD + ":" +pass;
    final DataChannel outChannel = communicationChannel;
    ByteBuffer buffer = ByteBuffer.wrap(data.getBytes());
    Log.d("HANK","send authencation information");
    outChannel.send(new DataChannel.Buffer(buffer, false));
  }

  public void sendVideoRequest(int onchannel,String videoPath) {
    final DataChannel outChannel = communicationChannel;

    String data = CommunicationChannelObserver.getRequestVideoMessage(onchannel,videoPath);
    ByteBuffer buffer = ByteBuffer.wrap(data.getBytes());
    if(outChannel!=null) {
      Log.d("HANK","SendMessage");
      outChannel.send(new DataChannel.Buffer(buffer,false));
    }
  }

  public void sendLiveViewRequest(int onchannel,String nickName) {
    final DataChannel outChannel = communicationChannel;

    String data = CommunicationChannelObserver.getRequestLiveViewMessage(onchannel, nickName);
    ByteBuffer buffer = ByteBuffer.wrap(data.getBytes());
    if(outChannel!=null) {
      Log.d("HANK","SendMessage");
      outChannel.send(new DataChannel.Buffer(buffer,false));
    }
  }


  // FILE METHOD
  // TODO: Add File Channel, using communicationChannel currently
  // FILE MESSAGE SENDER
  public void sendFileInfo(String data) {
    final DataChannel outChannel = communicationChannel;

    data = "File:"+data;
    ByteBuffer buffer = ByteBuffer.wrap(data.getBytes());
    if(outChannel!=null)
    {
      Log.d("HANK","SendData");
      outChannel.send(new DataChannel.Buffer(buffer,false));
    }
  }
  // FILE CONTENT SENDER
  public void sendFile(final File f) {
    final DataChannel outChannel = communicationChannel;

    // Send file name first, the receive device will create the file.
    sendFileInfo(f.getName());

    int dividedSize = DATA_CHANNEL_DIVIDE_SIZE_BYTE;
    long fileSize = f.length();

    try {
      FileInputStream fileInputStream = new FileInputStream(f);
      int sentSize=0;

      for(;;) {
        int nextSendSize;
        if((fileSize-sentSize)>=dividedSize) {
          nextSendSize = dividedSize;
        }
        else if((fileSize-sentSize) < dividedSize && (fileSize-sentSize)>0) {
          nextSendSize = (int)(fileSize-sentSize);
        }
        else {
          // file Send Over
          break;
        }

        byte[] sendByteFile = new byte[nextSendSize];

        fileInputStream.read(sendByteFile);
        ByteBuffer byteBuffer = ByteBuffer.wrap(sendByteFile);
        if (outChannel != null) {
          outChannel.send(new DataChannel.Buffer(byteBuffer, true));
          Log.d("HANK", "Send File : " + ((float) sentSize * 100 / (float) fileSize) + "%");
        }
        sentSize += nextSendSize;
      }
      fileInputStream.close();

      //Send END message to receive device.
      sendFileInfo("END");

    } catch (Exception e) {
      e.printStackTrace();

      //if send fail, send ERROR message to receive device
      sendFileInfo("ERROR");
      Log.e("HANK","Send Data error");
    }
  }

  // VIDEO METHOD
  // need input channelIndex to select correct videoChannel.
  // VIDEO MESSAGE SENDER
  public void sendVideoInfo(final int channelIndex,String key, String value) {
    final DataChannel outChannel = videoDataChannels[channelIndex];

    String data = VideoDataChannelObserver.VIDEO_PREFIX+":"+key+":"+value;
    ByteBuffer buffer = ByteBuffer.wrap(data.getBytes());

    Log.d("HANK","Send Video Info "+data);
    outChannel.send(new DataChannel.Buffer(buffer, false));

  }
  public void sendVideoInfo(final int channelIndex,String key) {
    DataChannel outChannel = videoDataChannels[channelIndex];
    String data = VideoDataChannelObserver.VIDEO_PREFIX+":"+key;
    ByteBuffer buffer = ByteBuffer.wrap(data.getBytes());
    Log.d("HANK", "Send Video Info " + data);
    outChannel.send(new DataChannel.Buffer(buffer, false));
  }

  public void sendAudioInfo(final int channelIndex,String key) {
    DataChannel outChannel = audioDataChannels[channelIndex];
    String data = AudioDataChannelObserver.AUDIO_PREFIX+":"+key;
    ByteBuffer buffer = ByteBuffer.wrap(data.getBytes());
    Log.d("HANK", "Send Audio Info " + data);
    outChannel.send(new DataChannel.Buffer(buffer, false));
  }

  // VIDEO CONTENT SENDER, Local File only can support video part.
  public void sendVideo(final int channelIndex, String videoPath) {
    // thread index is same as channel index
    int threadIndex = channelIndex;

    final DataChannel outChannel = videoDataChannels[channelIndex];
    try {
      MediaExtractor mediaExtractor = new MediaExtractor();
      mediaExtractor.setDataSource(videoPath);

      sendingThreads[threadIndex] = new SendingThread(this,mediaExtractor,threadIndex);
      sendingThreads[threadIndex].start();

    } catch (Exception e) {
      sendVideoInfo(channelIndex, "STOP");
      sendMessage(e+"");
      Log.d("HANK", "some error " + e);
    }
  }


  /// DATA CHANNEL SEND FUNCTION -OVER- ///

  private void createPeerConnectionInternal() {
    if (factory == null || isError) {
      Log.e(TAG, "Peerconnection factory is not created");
      return;
    }
    Log.d(TAG, "Create peer connection");
    Log.d(TAG, "PCConstraints: " + pcConstraints.toString());

    queuedRemoteCandidates = new LinkedList<IceCandidate>();

    PeerConnection.RTCConfiguration rtcConfig =
        new PeerConnection.RTCConfiguration(signalingParameters.iceServers);
    // TCP candidates are only useful when connecting to a server that supports
    // ICE-TCP.
    rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
    rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
    rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;

    peerConnection = factory.createPeerConnection(
            rtcConfig, pcConstraints, pcObserver);
    isInitiator = false;

    boolean bEnableDataChannel = peerConnectionParameters.bEnableChatRoom && peerConnectionParameters.bCreateSide;

    if(bEnableDataChannel) {
      int channelCount = 0;

      // Use for send Request
      DataChannel.Init dcInit = new DataChannel.Init();
      dcInit.id = channelCount++;
      communicationChannel = peerConnection.createDataChannel(COMMUNICATION_CHANNEL_NAME,dcInit);

      communicationChannelObserver = new CommunicationChannelObserver(communicationChannel,instance,executor,events);
      communicationChannel.registerObserver(communicationChannelObserver);

      // use for send video
      for(int i=0;i<MAX_CHANNEL_NUMBER;i++) {
        dcInit = new DataChannel.Init();
        dcInit.id = channelCount++;
        videoDataChannels[i] = peerConnection.createDataChannel(VIDEO_CHANNEL_NAME+"("+i+")", dcInit);
        videoDataChannels[i].registerObserver(new DCObserver());
      }

      // use for send audio
      for(int i=0;i<MAX_CHANNEL_NUMBER;i++) {
        dcInit = new DataChannel.Init();
        dcInit.id = channelCount++;
        audioDataChannels[i] = peerConnection.createDataChannel(AUDIO_CHANNEL_NAME+"("+i+")", dcInit);
        audioDataChannels[i].registerObserver(new DCObserver());
      }
    }

    // Set default WebRTC tracing and INFO libjingle logging.
    // NOTE: this _must_ happen while |factory| is alive!
    Logging.enableTracing(
        "logcat:",
        EnumSet.of(Logging.TraceLevel.TRACE_DEFAULT),
        Logging.Severity.LS_INFO);

    Log.d(TAG, "Peer connection created.");
  }

  private void closeInternal() {
    Log.d(TAG, "Closing peer connection.");
    statsTimer.cancel();
    if (peerConnection != null) {
      stopAllThread();

      peerConnection.dispose();
      peerConnection = null;
    }

    Log.d(TAG, "Closing peer connection factory.");
    if (factory != null) {
      factory.dispose();
      factory = null;
    }
    options = null;
    Log.d(TAG, "Closing peer connection done.");
    events.onPeerConnectionClosed();
  }

  private void getStats() {
    if (peerConnection == null || isError) {
      return;
    }
    boolean success = peerConnection.getStats(new StatsObserver() {
      @Override
      public void onComplete(final StatsReport[] reports) {
        events.onPeerConnectionStatsReady(reports);
      }
    }, null);
    if (!success) {
      Log.e(TAG, "getStats() returns false!");
    }
  }

  public void enableStatsEvents(boolean enable, int periodMs) {
    if (enable) {
      try {
        statsTimer.schedule(new TimerTask() {
          @Override
          public void run() {
            executor.execute(new Runnable() {
              @Override
              public void run() {
                getStats();
              }
            });
          }
        }, 0, periodMs);
      } catch (Exception e) {
        Log.e(TAG, "Can not schedule statistics timer", e);
      }
    } else {
      statsTimer.cancel();
    }
  }

  public void createOffer() {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        if (peerConnection != null && !isError) {
          Log.d(TAG, "PC Create OFFER");
          isInitiator = true;
          peerConnection.createOffer(sdpObserver, sdpMediaConstraints);
        }
      }
    });
  }

  public void createAnswer() {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        if (peerConnection != null && !isError) {
          Log.d(TAG, "PC create ANSWER");
          isInitiator = false;
          peerConnection.createAnswer(sdpObserver, sdpMediaConstraints);
        }
      }
    });
  }

  public void addRemoteIceCandidate(final IceCandidate candidate) {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        if (peerConnection != null && !isError) {
          if (queuedRemoteCandidates != null) {
            queuedRemoteCandidates.add(candidate);
          } else {
            peerConnection.addIceCandidate(candidate);
          }
        }
      }
    });
  }

  public void setRemoteDescription(final SessionDescription sdp) {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        if (peerConnection == null || isError) {
          return;
        }
        String sdpDescription = sdp.description;
        Log.d(TAG, "Set remote SDP.");
        SessionDescription sdpRemote = new SessionDescription(
            sdp.type, sdpDescription);
        peerConnection.setRemoteDescription(sdpObserver, sdpRemote);
      }
    });
  }

  private void reportError(final String errorMessage) {
    Log.e(TAG, "Peerconnection error: " + errorMessage);
    executor.execute(new Runnable() {
      @Override
      public void run() {
        if (!isError) {
          events.onPeerConnectionError(errorMessage);
          isError = true;
        }
      }
    });
  }

  private void drainCandidates() {
    if (queuedRemoteCandidates != null) {
      Log.d(TAG, "Add " + queuedRemoteCandidates.size() + " remote candidates");
      for (IceCandidate candidate : queuedRemoteCandidates) {
        peerConnection.addIceCandidate(candidate);
      }
      queuedRemoteCandidates = null;
    }
  }

  // empty DCObserver, using to non-recevied DataChannel
  public class DCObserver implements DataChannel.Observer {

    @Override
    public void onBufferedAmountChange(long var1) {
      Log.d("HANK","DataChannel.Observer onBufferedAmountChange : "+var1);
    }
    @Override
    public void onStateChange() {

    }
    @Override
    public void onMessage(final DataChannel.Buffer buffer) {

    }
  }


  // Implementation detail: observe ICE & stream changes and react accordingly.
  private class PCObserver implements PeerConnection.Observer {
    @Override
    public void onIceCandidate(final IceCandidate candidate){
      executor.execute(new Runnable() {
        @Override
        public void run() {
          events.onIceCandidate(candidate);
        }
      });
    }

    @Override
    public void onSignalingChange(
        PeerConnection.SignalingState newState) {
      Log.d(TAG, "SignalingState: " + newState);
    }

    @Override
    public void onIceConnectionChange(
        final PeerConnection.IceConnectionState newState) {
      executor.execute(new Runnable() {
        @Override
        public void run() {
          Log.d(TAG, "IceConnectionState: " + newState);
          if (newState == IceConnectionState.CONNECTED) {
            events.onIceConnected();
          } else if (newState == IceConnectionState.DISCONNECTED) {
            events.onIceDisconnected();
          } else if (newState == IceConnectionState.FAILED) {
            reportError("ICE connection failed.");
          }
        }
      });
    }

    @Override
    public void onIceGatheringChange(
      PeerConnection.IceGatheringState newState) {
      Log.d(TAG, "IceGatheringState: " + newState);
    }

    @Override
    public void onIceConnectionReceivingChange(boolean receiving) {
      Log.d(TAG, "IceConnectionReceiving changed to " + receiving);
    }

    @Override
    public void onAddStream(final MediaStream stream){
//      executor.execute(new Runnable() {
//        @Override
//        public void run() {
//          if (peerConnection == null || isError) {
//            return;
//          }
//          if (stream.audioTracks.size() > 1 || stream.videoTracks.size() > 1) {
//            reportError("Weird-looking stream: " + stream);
//            return;
//          }
//          if (stream.videoTracks.size() == 1) {
//            remoteVideoTrack = stream.videoTracks.get(0);
//            remoteVideoTrack.setEnabled(renderVideo);
//          }
//        }
//      });
    }

    @Override
    public void onRemoveStream(final MediaStream stream){
//      executor.execute(new Runnable() {
//        @Override
//        public void run() {
//          if (peerConnection == null || isError) {
//            return;
//          }
//          remoteVideoTrack = null;
//          stream.videoTracks.get(0).dispose();
//        }
//      });
    }


    @Override
    public void onDataChannel(final DataChannel dc) {
      // Hank Extension
      if(!peerConnectionParameters.bEnableChatRoom) {

        reportError("AppRTC doesn't use data channels, but got: " + dc.label()
                + " anyway! (If you wanna use data channel, plz enable chat room)");

      } else if(dc.label().startsWith(VIDEO_CHANNEL_NAME)) {
        Log.d("HANK", "onDataChannel : " + dc.label());

        int channelNumber = Integer.valueOf(dc.label().replaceAll("[^0-9]+",""));

        videoDataChannels[channelNumber] = dc;
        VideoDataChannelObserver videoDCObserver = new VideoDataChannelObserver(dc ,liveViewSurfaces[channelNumber],executor,getInstance());
        videoDataChannelObservers[channelNumber] = videoDCObserver;
        dc.registerObserver(videoDCObserver);

      } else if(dc.label().startsWith(AUDIO_CHANNEL_NAME)) {
        Log.d("HANK", "onDataChannel : " + dc.label());

        int channelNumber = Integer.valueOf(dc.label().replaceAll("[^0-9]+", ""));

        audioDataChannels[channelNumber] = dc;
        AudioDataChannelObserver audioDCObserver = new AudioDataChannelObserver(dc,executor,events);
        audioDataChannelObservers[channelNumber] = audioDCObserver;
        dc.registerObserver(audioDCObserver);

      } else if(dc.label().startsWith(COMMUNICATION_CHANNEL_NAME)) {
        Log.d("HANK","onDataChannel : "+ dc.label());

        communicationChannel = dc;
        communicationChannelObserver = new CommunicationChannelObserver(dc,instance,executor,events);
        communicationChannel.registerObserver(communicationChannelObserver);
      }
    }

    @Override
    public void onRenegotiationNeeded() {
      // No need to do anything; AppRTC follows a pre-agreed-upon
      // signaling/negotiation protocol.
    }
  }

  // Implementation detail: handle offer creation/signaling and answer setting,
  // as well as adding remote ICE candidates once the answer SDP is set.
  private class SDPObserver implements SdpObserver {
    @Override
    public void onCreateSuccess(final SessionDescription origSdp) {
      if (localSdp != null) {
        reportError("Multiple SDP create.");
        return;
      }
      String sdpDescription = origSdp.description;

      final SessionDescription sdp = new SessionDescription(
          origSdp.type, sdpDescription);
      localSdp = sdp;
      executor.execute(new Runnable() {
        @Override
        public void run() {
          if (peerConnection != null && !isError) {
            Log.d(TAG, "Set local SDP from " + sdp.type);
            peerConnection.setLocalDescription(sdpObserver, sdp);
          }
        }
      });
    }

    @Override
    public void onSetSuccess() {
      executor.execute(new Runnable() {
        @Override
        public void run() {
          if (peerConnection == null || isError) {
            return;
          }
          if (isInitiator) {
            // For offering peer connection we first create offer and set
            // local SDP, then after receiving answer set remote SDP.
            if (peerConnection.getRemoteDescription() == null) {
              // We've just set our local SDP so time to send it.
              Log.d(TAG, "Local SDP set succesfully");
              events.onLocalDescription(localSdp);
            } else {
              // We've just set remote description, so drain remote
              // and send local ICE candidates.
              Log.d(TAG, "Remote SDP set succesfully");
              drainCandidates();
            }
          } else {
            // For answering peer connection we set remote SDP and then
            // create answer and set local SDP.
            if (peerConnection.getLocalDescription() != null) {
              // We've just set our local SDP so time to send it, drain
              // remote and send local ICE candidates.
              Log.d(TAG, "Local SDP set succesfully");
              events.onLocalDescription(localSdp);
              drainCandidates();
            } else {
              // We've just set remote SDP - do nothing for now -
              // answer will be created soon.
              Log.d(TAG, "Remote SDP set succesfully");
            }
          }
        }
      });
    }

    @Override
    public void onCreateFailure(final String error) {
      reportError("createSDP error: " + error);
    }

    @Override
    public void onSetFailure(final String error) {
      reportError("setSDP error: " + error);
    }
  }
}
