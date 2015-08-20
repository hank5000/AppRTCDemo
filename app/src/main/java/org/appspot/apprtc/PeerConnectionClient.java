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
import org.appspot.apprtc.util.LooperExecutor;
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

import org.appspot.apprtc.util.RequestDataChannelObserver;


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
  private static final String REQUEST_CHANNEL_NAME = "RequestChannel";

  private static final PeerConnectionClient instance = new PeerConnectionClient();
  private final PCObserver pcObserver = new PCObserver();
  private final SDPObserver sdpObserver = new SDPObserver();
  private final LooperExecutor executor;

  // use for data channel video
  private SurfaceView liveViewSurfaces[];


  private final int MAX_CHANNEL_NUMBER = 4;

  public DataChannel requestChannel = null;
  private DataChannel[] videoDataChannels = new DataChannel[MAX_CHANNEL_NUMBER];
  private DataChannel[] audioDataChannels = new DataChannel[MAX_CHANNEL_NUMBER];

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

  public DataChannel getRequestChannel() {
    return requestChannel;
  }


  // video/file content divide to DATA_CAHNNEL_DIVIDE_SIZE size (Byte) and send.
  private final int DATA_CHANNEL_DIVIDE_SIZE_BYTE = 1024;
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
  private boolean isInitiator;
  private SessionDescription localSdp; // either offer or answer SDP

  public void setLiveViewSurface(SurfaceView[] Surfaces)
  {
    this.liveViewSurfaces = Surfaces;
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
  }

  private PeerConnectionClient() {
    executor = new LooperExecutor();
    // Looper thread is started once in private ctor and is used for all
    // peer connection API calls to ensure new peer connection factory is
    // created on the same thread as previously destroyed factory.
    executor.requestStart();
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

  /// DATA CHANNEL SENDER FUNCTION -START- ///
  // MESSAGE SENDER
  public void SendMessage(final DataChannel outChannel,String data) {
    data = "MSG:"+data;
    ByteBuffer buffer = ByteBuffer.wrap(data.getBytes());
    if(outChannel!=null) {
      Log.d("HANK","SendMessage");
      outChannel.send(new DataChannel.Buffer(buffer,false));
    }
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

  // VIDEO MESSAGE SENDER
  public void SendVideoInfo(DataChannel outChannel,String key, String value) {
    String data = "Video:"+key+":"+value;
    ByteBuffer buffer = ByteBuffer.wrap(data.getBytes());

    Log.d("HANK","Send Video Info "+data);
    outChannel.send(new DataChannel.Buffer(buffer, false));

  }

  public void SendVideoInfo(DataChannel outChannel,String key) {
    String data = "Video:"+key;
    ByteBuffer buffer = ByteBuffer.wrap(data.getBytes());
    Log.d("HANK","Send Video Info "+data);
    outChannel.send(new DataChannel.Buffer(buffer, false));
  }

  // VIDEO CONTENT SENDER, Local File only can support video part.
  public void SendVideo(final DataChannel outChannel) {
    try {
      final MediaExtractor mediaExtractor = new MediaExtractor();
      mediaExtractor.setDataSource("/mnt/sata/abc.mp4");
      int Count = mediaExtractor.getTrackCount();
      MediaFormat mf = null;
      String mime = null;
      for (int i = 0; i < Count; i++) {
        mf = mediaExtractor.getTrackFormat(i);
        mime = mf.getString(MediaFormat.KEY_MIME);
        if (mime.subSequence(0, 5).equals("video")) {
          mediaExtractor.selectTrack(i);
          break;
        }
      }

      SendVideoInfo(outChannel,"MIME", mf.getString(MediaFormat.KEY_MIME));
      SendVideoInfo(outChannel,"Width", "" + mf.getInteger(MediaFormat.KEY_WIDTH));
      SendVideoInfo(outChannel,"Height", "" + mf.getInteger(MediaFormat.KEY_HEIGHT));
      ByteBuffer sps_b = mf.getByteBuffer("csd-0");
      byte[] sps_ba = new byte[sps_b.remaining()];
      sps_b.get(sps_ba);
      SendVideoInfo(outChannel,"sps", bytesToHex(sps_ba));

      mf.getByteBuffer("csd-1");
      ByteBuffer pps_b = mf.getByteBuffer("csd-1");
      byte[] pps_ba = new byte[pps_b.remaining()];
      pps_b.get(pps_ba);
      SendVideoInfo(outChannel,"pps", bytesToHex(pps_ba));
      SendVideoInfo(outChannel,"Start");

      final ByteBuffer naluBuffer = ByteBuffer.allocate(1024 * 1024);

      Thread sendingThread = new Thread(new Runnable() {
        @Override
        public void run() {
          int NALUCount = 0;
          byte[] naluSizeByte = new byte[4];

          while (!Thread.interrupted()) {

            int naluSize = mediaExtractor.readSampleData(naluBuffer, 0);
            //Log.d("HANK", "readSampleData : " + sampleSize);

            if (naluSize > 0) {
              NALUCount++;
              //Log.d("HANK", "Send NALU length : " + sampleSize);

              if (outChannel != null) {
                // 1. Send NALU Size to connected device first.
                naluSizeByte[0] = (byte) (naluSize & 0x000000ff);
                naluSizeByte[1] = (byte) ((naluSize & 0x0000ff00) >> 8);
                naluSizeByte[2] = (byte) ((naluSize & 0x00ff0000) >> 16);
                naluSizeByte[3] = (byte) ((naluSize & 0xff000000) >> 24);
                outChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(naluSizeByte), true));

                // 2. Divide NALU data by 1000
                int sentSize = 0;
                for (;;) {
                  //Log.d("HANK","Send NALU("+NALUCount+")  : "+j+"/"+sampleSize);
                  int dividedSize = DATA_CHANNEL_DIVIDE_SIZE_BYTE;
                  if ((naluSize - sentSize) < dividedSize) {
                    dividedSize = naluSize - sentSize;
                  }
                  //using bytebuffer.slice() , maybe decrease once memory copy
                  naluBuffer.position(sentSize);
                  naluBuffer.limit(dividedSize+sentSize);

                  outChannel.send(new DataChannel.Buffer(naluBuffer.slice(), true));

                  naluBuffer.limit(naluBuffer.capacity());

                  sentSize = sentSize + dividedSize;
                  if (sentSize >= naluSize) {
                    break;
                  }
                }
                try {
                  // TODO: Using a good method to sync video
                  Thread.sleep(33);
                } catch (Exception e) {
                  Log.d("HANK", "Sleep Error");
                }
              }
              naluBuffer.clear();
              mediaExtractor.advance();
            } else {
              Log.d("HANK", "No Frame lo.");

              boolean bAutoLoop = true;
              if(bAutoLoop) {
                Log.d("HANK", "Enable Auto Loop");
                mediaExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
              } else {
                Log.d("HANK", "Auto Loop is disable");
                break;
              }

            }
          }
          SendVideoInfo(outChannel,"STOP");
        }
      });
      sendingThread.start();

    } catch (Exception e) {
      SendVideoInfo(outChannel,"STOP", "");
      Log.d("HANK","some error "+ e);
    }
  }

  // FILE MESSAGE SENDER
  public void SendFileInfo(final DataChannel outChannel,String data) {
    data = "File:"+data;
    ByteBuffer buffer = ByteBuffer.wrap(data.getBytes());
    if(outChannel!=null)
    {
      Log.d("HANK","SendData");
      outChannel.send(new DataChannel.Buffer(buffer,false));
    }
  }

  // FILE CONTENT SENDER
  public void SendFile(final DataChannel outChannel,final File f) {
    // Send file name first, the receive device will create the file.
    SendFileInfo(outChannel,f.getName());

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
      SendFileInfo(outChannel,"END");

    } catch (Exception e) {
      e.printStackTrace();

      //if send fail, send ERROR message to receive device
      SendFileInfo(outChannel,"ERROR");
      Log.e("HANK","Send Data error");
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
      requestChannel = peerConnection.createDataChannel(REQUEST_CHANNEL_NAME,dcInit);

      RequestDataChannelObserver requestDCObserver = new RequestDataChannelObserver(requestChannel,instance,executor,events);
      requestChannel.registerObserver(requestDCObserver);

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

  // empty DCObserver
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
        VideoDataChannelObserver videoDCObserver = new VideoDataChannelObserver(dc ,liveViewSurfaces[channelNumber],executor,events);
        dc.registerObserver(videoDCObserver);

      } else if(dc.label().startsWith(AUDIO_CHANNEL_NAME)) {
        Log.d("HANK", "onDataChannel : " + dc.label());

        int channelNumber = Integer.valueOf(dc.label().replaceAll("[^0-9]+", ""));

        audioDataChannels[channelNumber] = dc;
        AudioDataChannelObserver audioDCObserver = new AudioDataChannelObserver(dc,executor,events);
        dc.registerObserver(audioDCObserver);

      } else if(dc.label().startsWith(REQUEST_CHANNEL_NAME)) {
        Log.d("HANK","onDataChannel : "+ dc.label());

        requestChannel = dc;
        RequestDataChannelObserver requestObserver = new RequestDataChannelObserver(dc,instance,executor,events);
        requestChannel.registerObserver(requestObserver);

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
