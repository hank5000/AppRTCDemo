/*
 * libjingle
 * Copyright 2014 Google Inc.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
 * EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.appspot.apprtc;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.opengl.EGLContext;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceView;
import android.widget.Toast;
import android.net.LocalSocketAddress;

import org.appspot.apprtc.AppRTCClient.SignalingParameters;
import org.appspot.apprtc.util.LooperExecutor;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaCodecVideoEncoder;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaConstraints.KeyValuePair;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnection.IceConnectionState;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.StatsObserver;
import org.webrtc.StatsReport;
import org.webrtc.VideoCapturerAndroid;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Random;

/**
 * Peer connection client implementation.
 *
 * <p>All public methods are routed to local looper thread.
 * All PeerConnectionEvents callbacks are invoked from the same looper thread.
 * This class is a singleton.
 */
public class PeerConnectionClient {

  public static final String VIDEO_TRACK_ID = "ARDAMSv0";
  public static final String AUDIO_TRACK_ID = "ARDAMSa0";
  private static final String TAG = "PCRTCClient";
  private static final String FIELD_TRIAL_VP9 = "WebRTC-SupportVP9/Enabled/";
  private static final String VIDEO_CODEC_VP8 = "VP8";
  private static final String VIDEO_CODEC_VP9 = "VP9";
  private static final String VIDEO_CODEC_H264 = "H264";
  private static final String AUDIO_CODEC_OPUS = "opus";
  private static final String AUDIO_CODEC_ISAC = "ISAC";
  private static final String VIDEO_CODEC_PARAM_START_BITRATE =
      "x-google-start-bitrate";
  private static final String AUDIO_CODEC_PARAM_BITRATE = "maxaveragebitrate";
  private static final String AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation";
  private static final String AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT= "googAutoGainControl";
  private static final String AUDIO_HIGH_PASS_FILTER_CONSTRAINT  = "googHighpassFilter";
  private static final String AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression";
  private static final String MAX_VIDEO_WIDTH_CONSTRAINT = "maxWidth";
  private static final String MIN_VIDEO_WIDTH_CONSTRAINT = "minWidth";
  private static final String MAX_VIDEO_HEIGHT_CONSTRAINT = "maxHeight";
  private static final String MIN_VIDEO_HEIGHT_CONSTRAINT = "minHeight";
  private static final String MAX_VIDEO_FPS_CONSTRAINT = "maxFrameRate";
  private static final String MIN_VIDEO_FPS_CONSTRAINT = "minFrameRate";
  private static final String DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT = "DtlsSrtpKeyAgreement";
  private static final String RTP_DATA_CHANNELS_CONSTRAINT = "RtpDataChannels";


  private static final int HD_VIDEO_WIDTH = 1280;
  private static final int HD_VIDEO_HEIGHT = 720;
  private static final int MAX_VIDEO_WIDTH = 1280;
  private static final int MAX_VIDEO_HEIGHT = 1280;
  private static final int MAX_VIDEO_FPS = 30;

  private static final PeerConnectionClient instance = new PeerConnectionClient();
  private final PCObserver pcObserver = new PCObserver();
  private final SDPObserver sdpObserver = new SDPObserver();
  private final LooperExecutor executor;

  private final DCObserver dc1 = new DCObserver();

  public DataChannel outDataChannel   = null;
  public DataChannel inDataChannel = null;
  private DataChannel.Init dcInit = new DataChannel.Init();
  public DCObserver  inDataChannelObserver = null;


  private PeerConnectionFactory factory;
  private PeerConnection peerConnection;
  PeerConnectionFactory.Options options = null;
  private VideoSource videoSource;

  private boolean videoCallEnabled;
  private boolean bEnableAudio = false;

  private boolean preferIsac;
  private boolean preferH264;
  private boolean videoSourceStopped;
  private boolean isError;
  private Timer statsTimer;
  private VideoRenderer.Callbacks localRender;
  private VideoRenderer.Callbacks remoteRender;
  private SignalingParameters signalingParameters;
  private MediaConstraints pcConstraints;
  private MediaConstraints videoConstraints;
  private MediaConstraints audioConstraints;
  private MediaConstraints sdpMediaConstraints;
  private PeerConnectionParameters peerConnectionParameters;
  // Queued remote ICE candidates are consumed only after both local and
  // remote descriptions are set. Similarly local ICE candidates are sent to
  // remote peer after both local and remote description are set.
  private LinkedList<IceCandidate> queuedRemoteCandidates;
  private PeerConnectionEvents events;
  private boolean isInitiator;
  private SessionDescription localSdp; // either offer or answer SDP
  private MediaStream mediaStream;
  private int numberOfCameras;
  private VideoCapturerAndroid videoCapturer;
  // enableVideo is set to true if video should be rendered and sent.
  private boolean renderVideo;
  private VideoTrack localVideoTrack;
  private VideoTrack remoteVideoTrack;

  private SurfaceView liveViewSurface;

  public void setLiveViewSurface(SurfaceView Surface)
  {
    Log.d("HANK","GGGG");
    this.liveViewSurface = Surface;

  }

  /**
   * Peer connection parameters.
   */
  public static class PeerConnectionParameters {
    public final boolean videoCallEnabled;
    public final boolean loopback;
    public final int videoWidth;
    public final int videoHeight;
    public final int videoFps;
    public final int videoStartBitrate;
    public final String videoCodec;
    public final boolean videoCodecHwAcceleration;
    public final int audioStartBitrate;
    public final String audioCodec;
    public final boolean noAudioProcessing;
    public final boolean cpuOveruseDetection;

    public final boolean bEnableChatRoom;
    public final boolean bCreateSide;

    public PeerConnectionParameters(
        boolean videoCallEnabled, boolean loopback,
        int videoWidth, int videoHeight, int videoFps, int videoStartBitrate,
        String videoCodec, boolean videoCodecHwAcceleration,
        int audioStartBitrate, String audioCodec,
        boolean noAudioProcessing, boolean cpuOveruseDetection, boolean enableChatRoom, boolean createSide) {
      this.videoCallEnabled = videoCallEnabled;
      this.loopback = loopback;
      this.videoWidth = videoWidth;
      this.videoHeight = videoHeight;
      this.videoFps = videoFps;
      this.videoStartBitrate = videoStartBitrate;
      this.videoCodec = videoCodec;
      this.videoCodecHwAcceleration = videoCodecHwAcceleration;
      this.audioStartBitrate = audioStartBitrate;
      this.audioCodec = audioCodec;
      this.noAudioProcessing = noAudioProcessing;
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

    public void onVideoStart();
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

  public void setPeerConnectionFactoryOptions(PeerConnectionFactory.Options options) {
    this.options = options;
  }

  public void createPeerConnectionFactory(
      final Context context,
      final EGLContext renderEGLContext,
      final PeerConnectionParameters peerConnectionParameters,
      final PeerConnectionEvents events) {
    this.peerConnectionParameters = peerConnectionParameters;
    this.events = events;
    videoCallEnabled = peerConnectionParameters.videoCallEnabled;
    // Reset variables to initial states.
    factory = null;
    peerConnection = null;
    preferIsac = false;
    preferH264 = false;
    videoSourceStopped = false;
    isError = false;
    queuedRemoteCandidates = null;
    localSdp = null; // either offer or answer SDP
    mediaStream = null;
    videoCapturer = null;
    renderVideo = true;
    localVideoTrack = null;
    remoteVideoTrack = null;
    statsTimer = new Timer();

    executor.execute(new Runnable() {
      @Override
      public void run() {
        createPeerConnectionFactoryInternal(context, renderEGLContext);
      }
    });
  }

  public void createPeerConnection(
      final VideoRenderer.Callbacks localRender,
      final VideoRenderer.Callbacks remoteRender,
      final SignalingParameters signalingParameters) {
    if (peerConnectionParameters == null) {
      Log.e(TAG, "Creating peer connection without initializing factory.");
      return;
    }
    this.localRender = localRender;
    this.remoteRender = remoteRender;
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

  public boolean isVideoCallEnabled() {
    return videoCallEnabled;
  }

  private void createPeerConnectionFactoryInternal(
      Context context, EGLContext renderEGLContext) {
    Log.d(TAG, "Create peer connection factory with EGLContext "
            + renderEGLContext + ". Use video: "
            + peerConnectionParameters.videoCallEnabled);
    isError = false;
    // Check if VP9 is used by default.
    if (videoCallEnabled && peerConnectionParameters.videoCodec != null
        && peerConnectionParameters.videoCodec.equals(VIDEO_CODEC_VP9)) {
      PeerConnectionFactory.initializeFieldTrials(FIELD_TRIAL_VP9);
    } else {
      PeerConnectionFactory.initializeFieldTrials(null);
    }
    // Check if H.264 is used by default.
    preferH264 = false;
    if (videoCallEnabled && peerConnectionParameters.videoCodec != null
        && peerConnectionParameters.videoCodec.equals(VIDEO_CODEC_H264)) {
      preferH264 = true;
    }
    // Check if ISAC is used by default.
    preferIsac = false;
    if (peerConnectionParameters.audioCodec != null
        && peerConnectionParameters.audioCodec.equals(AUDIO_CODEC_ISAC)) {
      preferIsac = true;
    }
    if (!PeerConnectionFactory.initializeAndroidGlobals(
        context, true, true,
        peerConnectionParameters.videoCodecHwAcceleration, renderEGLContext)) {
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
    // Enable DTLS for normal calls and disable for loopback calls.
    if (peerConnectionParameters.loopback) {
      pcConstraints.optional.add(
              new KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "false"));
    } else {
      pcConstraints.optional.add(
              new KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "true"));
    }
    // Check if there is a camera on device and disable video call if not.
    numberOfCameras = VideoCapturerAndroid.getDeviceCount();
    if (numberOfCameras == 0) {
      Log.w(TAG, "No camera on device. Switch to audio only call.");
      videoCallEnabled = false;
    }
    // Create video constraints if video call is enabled.
    if (videoCallEnabled) {
      videoConstraints = new MediaConstraints();
      int videoWidth = peerConnectionParameters.videoWidth;
      int videoHeight = peerConnectionParameters.videoHeight;

      // If VP8 HW video encoder is supported and video resolution is not
      // specified force it to HD.
      if ((videoWidth == 0 || videoHeight == 0)
          && peerConnectionParameters.videoCodecHwAcceleration
          && MediaCodecVideoEncoder.isVp8HwSupported()) {
        videoWidth = HD_VIDEO_WIDTH;
        videoHeight = HD_VIDEO_HEIGHT;
      }

      // Add video resolution constraints.
      if (videoWidth > 0 && videoHeight > 0) {
        videoWidth = Math.min(videoWidth, MAX_VIDEO_WIDTH);
        videoHeight = Math.min(videoHeight, MAX_VIDEO_HEIGHT);
        videoConstraints.mandatory.add(new KeyValuePair(
            MIN_VIDEO_WIDTH_CONSTRAINT, Integer.toString(videoWidth)));
        videoConstraints.mandatory.add(new KeyValuePair(
            MAX_VIDEO_WIDTH_CONSTRAINT, Integer.toString(videoWidth)));
        videoConstraints.mandatory.add(new KeyValuePair(
            MIN_VIDEO_HEIGHT_CONSTRAINT, Integer.toString(videoHeight)));
        videoConstraints.mandatory.add(new KeyValuePair(
            MAX_VIDEO_HEIGHT_CONSTRAINT, Integer.toString(videoHeight)));
      }

      // Add fps constraints.
      int videoFps = peerConnectionParameters.videoFps;
      if (videoFps > 0) {
        videoFps = Math.min(videoFps, MAX_VIDEO_FPS);
        videoConstraints.mandatory.add(new KeyValuePair(
            MIN_VIDEO_FPS_CONSTRAINT, Integer.toString(videoFps)));
        videoConstraints.mandatory.add(new KeyValuePair(
            MAX_VIDEO_FPS_CONSTRAINT, Integer.toString(videoFps)));
      }
    }

    if(bEnableAudio) {
      // Create audio constraints.
      audioConstraints = new MediaConstraints();
      // added for audio performance measurements
      if (peerConnectionParameters.noAudioProcessing) {
        Log.d(TAG, "Disabling audio processing");
        audioConstraints.mandatory.add(new KeyValuePair(
                AUDIO_ECHO_CANCELLATION_CONSTRAINT, "false"));
        audioConstraints.mandatory.add(new KeyValuePair(
                AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT, "false"));
        audioConstraints.mandatory.add(new KeyValuePair(
                AUDIO_HIGH_PASS_FILTER_CONSTRAINT, "false"));
        audioConstraints.mandatory.add(new KeyValuePair(
                AUDIO_NOISE_SUPPRESSION_CONSTRAINT, "false"));
      }
    }
    // Create SDP constraints.
    sdpMediaConstraints = new MediaConstraints();
    if(true) {
      sdpMediaConstraints.mandatory.add(new KeyValuePair(
              "OfferToReceiveAudio", "true"));
    } else {
      sdpMediaConstraints.mandatory.add(new KeyValuePair(
              "OfferToReceiveAudio", "false"));
    }
    if (videoCallEnabled || peerConnectionParameters.loopback) {
      sdpMediaConstraints.mandatory.add(new KeyValuePair(
          "OfferToReceiveVideo", "true"));
    } else {
      sdpMediaConstraints.mandatory.add(new KeyValuePair(
              "OfferToReceiveVideo", "false"));
    }
  }

  public void SendMessage(String data) {
    data = "MSG:"+data;
    ByteBuffer buffer = ByteBuffer.wrap(data.getBytes());
    if(outDataChannel!=null) {
      Log.d("HANK","SendMessage");
      outDataChannel.send(new DataChannel.Buffer(buffer,false));
    }
  }

  public void SendVideoInfo(String attribute, String value) {
    String data = "Video:"+attribute+":"+value;
    ByteBuffer buffer = ByteBuffer.wrap(data.getBytes());
    if(outDataChannel!=null)
    {
      Log.d("HANK","Send Video Info "+data);
      outDataChannel.send(new DataChannel.Buffer(buffer,false));
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

  public void SendVideo() {
    try {

      final MediaExtractor me = new MediaExtractor();
      me.setDataSource("/mnt/sata/abc.mp4");
      int Count = me.getTrackCount();
      MediaFormat mf = null;
      String mime = null;
      for (int i = 0; i < Count; i++) {
        mf = me.getTrackFormat(i);
        mime = mf.getString(MediaFormat.KEY_MIME);
        if (mime.subSequence(0, 5).equals("video")) {
          me.selectTrack(i);
          break;
        }
      }

      SendVideoInfo("MIME", mf.getString(MediaFormat.KEY_MIME));
      SendVideoInfo("Width", "" + mf.getInteger(MediaFormat.KEY_WIDTH));
      SendVideoInfo("Height", "" + mf.getInteger(MediaFormat.KEY_HEIGHT));
      ByteBuffer sps_b = mf.getByteBuffer("csd-0");
      byte[] sps_ba = new byte[sps_b.remaining()];
      sps_b.get(sps_ba);
      SendVideoInfo("sps", bytesToHex(sps_ba));

      mf.getByteBuffer("csd-1");
      ByteBuffer pps_b = mf.getByteBuffer("csd-1");
      byte[] pps_ba = new byte[pps_b.remaining()];
      pps_b.get(pps_ba);
      SendVideoInfo("pps", bytesToHex(pps_ba));
      SendVideoInfo("Start", "");

      final ByteBuffer bb = ByteBuffer.allocate(1024 * 1024);

      Thread a = new Thread(new Runnable() {
        @Override
        public void run() {
          int NALUCount = 0;
          while (true) {

            int sampleSize = me.readSampleData(bb, 0);
            Log.d("HANK", "readSampleData : " + sampleSize);

            if (sampleSize > 0) {
              NALUCount++;
              Log.d("HANK", "Send NALU length : " + sampleSize);
              byte[] ba = new byte[sampleSize];
              bb.get(ba);
              if (outDataChannel != null) {

                byte[] b_size = new byte[4];
                b_size[0] = (byte) (sampleSize & 0x000000ff);
                b_size[1] = (byte) ((sampleSize & 0x0000ff00) >> 8);
                b_size[2] = (byte) ((sampleSize & 0x00ff0000) >> 16);
                b_size[3] = (byte) ((sampleSize & 0xff000000) >> 24);

                outDataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(b_size), true));

                int j = 0;
                for (; ; ) {
                  //Log.d("HANK","Send NALU("+NALUCount+")  : "+j+"/"+sampleSize);

                  int Size = 1000;
                  if ((sampleSize - j) < Size) {
                    Size = sampleSize - j;
                  }
                  outDataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(ba, j, Size), true));
                  j = j + Size;
                  if (j >= sampleSize) {
                    break;
                  }
                }
                try {
                  Thread.sleep(10);
                } catch (Exception e) {
                  Log.d("HANK", "Sleep Error");
                }
              }
              bb.clear();
              me.advance();
            } else {
              Log.d("HANK", "No Frame lo.");
              break;
            }
          }
          SendVideoInfo("STOP", "");
        }
      });
      a.start();


    } catch (Exception e) {
      SendVideoInfo("STOP","");
    }
  }

  public void SendFileInfo(String data) {
    data = "File:"+data;
    ByteBuffer buffer = ByteBuffer.wrap(data.getBytes());
    if(outDataChannel!=null)
    {
      Log.d("HANK","SendData");
      outDataChannel.send(new DataChannel.Buffer(buffer,false));
    }
  }
  public void SendFile(final File f) {

    // Send file name first, the receive device will create the file.
    SendFileInfo(f.getName());

    int dividedSize = 1000;
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
        if (outDataChannel != null) {
          outDataChannel.send(new DataChannel.Buffer(byteBuffer, true));
          Log.d("HANK", "Send File : " + ((float) sentSize * 100 / (float) fileSize) + "%");
        }
        sentSize += nextSendSize;
      }
      fileInputStream.close();

      //Send END message to receive device.
      SendFileInfo("END");

    } catch (Exception e) {
      e.printStackTrace();

      //if send fail, send ERROR message to receive device
      SendFileInfo("ERROR");
      Log.e("HANK","Send Data error");
    }
  }

  private void createPeerConnectionInternal() {
    if (factory == null || isError) {
      Log.e(TAG, "Peerconnection factory is not created");
      return;
    }
    Log.d(TAG, "Create peer connection");
    Log.d(TAG, "PCConstraints: " + pcConstraints.toString());
    if (videoConstraints != null) {
      Log.d(TAG, "VideoConstraints: " + videoConstraints.toString());
    }
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
      DataChannel.Init dcInit_tmp = new DataChannel.Init();
      dcInit_tmp.id = 1;
      outDataChannel = peerConnection.createDataChannel("1", dcInit_tmp);
      outDataChannel.registerObserver(new DCObserver());
    }



    // Set default WebRTC tracing and INFO libjingle logging.
    // NOTE: this _must_ happen while |factory| is alive!
    Logging.enableTracing(
        "logcat:",
        EnumSet.of(Logging.TraceLevel.TRACE_DEFAULT),
        Logging.Severity.LS_INFO);

    mediaStream = factory.createLocalMediaStream("ARDAMS");
    if (videoCallEnabled) {
      String cameraDeviceName = VideoCapturerAndroid.getDeviceName(0);
      String frontCameraDeviceName =
          VideoCapturerAndroid.getNameOfFrontFacingDevice();
      if (numberOfCameras > 1 && frontCameraDeviceName != null) {
        cameraDeviceName = frontCameraDeviceName;
      }
      Log.d(TAG, "Opening camera: " + cameraDeviceName);
      videoCapturer = VideoCapturerAndroid.create(cameraDeviceName, null);
      if (videoCapturer == null) {
        reportError("Failed to open camera");
        return;
      }
      mediaStream.addTrack(createVideoTrack(videoCapturer));

    }
    if(bEnableAudio)
    {
      mediaStream.addTrack(factory.createAudioTrack(
              AUDIO_TRACK_ID,
              factory.createAudioSource(audioConstraints)));
      peerConnection.addStream(mediaStream);
    }
    Log.d(TAG, "Peer connection created.");
  }

  private void closeInternal() {
    Log.d(TAG, "Closing peer connection.");
    statsTimer.cancel();
    if (peerConnection != null) {
      peerConnection.dispose();
      peerConnection = null;
    }
    Log.d(TAG, "Closing video source.");
    if (videoSource != null) {
      videoSource.dispose();
      videoSource = null;
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

  public boolean isHDVideo() {
    if (!videoCallEnabled) {
      return false;
    }
    int minWidth = 0;
    int minHeight = 0;
    for (KeyValuePair keyValuePair : videoConstraints.mandatory) {
      if (keyValuePair.getKey().equals("minWidth")) {
        try {
          minWidth = Integer.parseInt(keyValuePair.getValue());
        } catch (NumberFormatException e) {
          Log.e(TAG, "Can not parse video width from video constraints");
        }
      } else if (keyValuePair.getKey().equals("minHeight")) {
        try {
          minHeight = Integer.parseInt(keyValuePair.getValue());
        } catch (NumberFormatException e) {
          Log.e(TAG, "Can not parse video height from video constraints");
        }
      }
    }
    if (minWidth * minHeight >= 1280 * 720) {
      return true;
    } else {
      return false;
    }
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

  public void setVideoEnabled(final boolean enable) {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        renderVideo = enable;
        if (localVideoTrack != null) {
          localVideoTrack.setEnabled(renderVideo);
        }
        if (remoteVideoTrack != null) {
          remoteVideoTrack.setEnabled(renderVideo);
        }
      }
    });
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
        if (preferIsac) {
          sdpDescription = preferCodec(sdpDescription, AUDIO_CODEC_ISAC, true);
        }
        if (videoCallEnabled && preferH264) {
          sdpDescription = preferCodec(sdpDescription, VIDEO_CODEC_H264, false);
        }
        if (videoCallEnabled && peerConnectionParameters.videoStartBitrate > 0) {
          sdpDescription = setStartBitrate(VIDEO_CODEC_VP8, true,
              sdpDescription, peerConnectionParameters.videoStartBitrate);
          sdpDescription = setStartBitrate(VIDEO_CODEC_VP9, true,
              sdpDescription, peerConnectionParameters.videoStartBitrate);
          sdpDescription = setStartBitrate(VIDEO_CODEC_H264, true,
              sdpDescription, peerConnectionParameters.videoStartBitrate);
        }
        if (peerConnectionParameters.audioStartBitrate > 0) {
          sdpDescription = setStartBitrate(AUDIO_CODEC_OPUS, false,
              sdpDescription, peerConnectionParameters.audioStartBitrate);
        }
        Log.d(TAG, "Set remote SDP.");
        SessionDescription sdpRemote = new SessionDescription(
            sdp.type, sdpDescription);
        peerConnection.setRemoteDescription(sdpObserver, sdpRemote);
      }
    });
  }

  public void stopVideoSource() {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        if (videoSource != null && !videoSourceStopped) {
          Log.d(TAG, "Stop video source.");
          videoSource.stop();
          videoSourceStopped = true;
        }
      }
    });
  }

  public void startVideoSource() {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        if (videoSource != null && videoSourceStopped) {
          Log.d(TAG, "Restart video source.");
          videoSource.restart();
          videoSourceStopped = false;
        }
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

  private VideoTrack createVideoTrack(VideoCapturerAndroid capturer) {
    videoSource = factory.createVideoSource(capturer, videoConstraints);



    localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
    localVideoTrack.setEnabled(renderVideo);
    localVideoTrack.addRenderer(new VideoRenderer(localRender));
    return localVideoTrack;
  }

  private static String setStartBitrate(String codec, boolean isVideoCodec,
      String sdpDescription, int bitrateKbps) {
    String[] lines = sdpDescription.split("\r\n");
    int rtpmapLineIndex = -1;
    boolean sdpFormatUpdated = false;
    String codecRtpMap = null;
    // Search for codec rtpmap in format
    // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
    String regex = "^a=rtpmap:(\\d+) " + codec + "(/\\d+)+[\r]?$";
    Pattern codecPattern = Pattern.compile(regex);
    for (int i = 0; i < lines.length; i++) {
      Matcher codecMatcher = codecPattern.matcher(lines[i]);
      if (codecMatcher.matches()) {
        codecRtpMap = codecMatcher.group(1);
        rtpmapLineIndex = i;
        break;
      }
    }
    if (codecRtpMap == null) {
      Log.w(TAG, "No rtpmap for " + codec + " codec");
      return sdpDescription;
    }
    Log.d(TAG, "Found " +  codec + " rtpmap " + codecRtpMap
        + " at " + lines[rtpmapLineIndex]);

    // Check if a=fmtp string already exist in remote SDP for this codec and
    // update it with new bitrate parameter.
    regex = "^a=fmtp:" + codecRtpMap + " \\w+=\\d+.*[\r]?$";
    codecPattern = Pattern.compile(regex);
    for (int i = 0; i < lines.length; i++) {
      Matcher codecMatcher = codecPattern.matcher(lines[i]);
      if (codecMatcher.matches()) {
        Log.d(TAG, "Found " +  codec + " " + lines[i]);
        if (isVideoCodec) {
          lines[i] += "; " + VIDEO_CODEC_PARAM_START_BITRATE
              + "=" + bitrateKbps;
        } else {
          lines[i] += "; " + AUDIO_CODEC_PARAM_BITRATE
              + "=" + (bitrateKbps * 1000);
        }
        Log.d(TAG, "Update remote SDP line: " + lines[i]);
        sdpFormatUpdated = true;
        break;
      }
    }

    StringBuilder newSdpDescription = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      newSdpDescription.append(lines[i]).append("\r\n");
      // Append new a=fmtp line if no such line exist for a codec.
      if (!sdpFormatUpdated && i == rtpmapLineIndex) {
        String bitrateSet;
        if (isVideoCodec) {
          bitrateSet = "a=fmtp:" + codecRtpMap + " "
              + VIDEO_CODEC_PARAM_START_BITRATE + "=" + bitrateKbps;
        } else {
          bitrateSet = "a=fmtp:" + codecRtpMap + " "
              + AUDIO_CODEC_PARAM_BITRATE + "=" + (bitrateKbps * 1000);
        }
        Log.d(TAG, "Add remote SDP line: " + bitrateSet);
        newSdpDescription.append(bitrateSet).append("\r\n");
      }

    }
    return newSdpDescription.toString();
  }

  private static String preferCodec(
      String sdpDescription, String codec, boolean isAudio) {
    String[] lines = sdpDescription.split("\r\n");
    int mLineIndex = -1;
    String codecRtpMap = null;
    // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
    String regex = "^a=rtpmap:(\\d+) " + codec + "(/\\d+)+[\r]?$";
    Pattern codecPattern = Pattern.compile(regex);
    String mediaDescription = "m=video ";
    if (isAudio) {
      mediaDescription = "m=audio ";
    }
    for (int i = 0; (i < lines.length)
        && (mLineIndex == -1 || codecRtpMap == null); i++) {
      if (lines[i].startsWith(mediaDescription)) {
        mLineIndex = i;
        continue;
      }
      Matcher codecMatcher = codecPattern.matcher(lines[i]);
      if (codecMatcher.matches()) {
        codecRtpMap = codecMatcher.group(1);
        continue;
      }
    }
    if (mLineIndex == -1) {
      Log.w(TAG, "No " + mediaDescription + " line, so can't prefer " + codec);
      return sdpDescription;
    }
    if (codecRtpMap == null) {
      Log.w(TAG, "No rtpmap for " + codec);
      return sdpDescription;
    }
    Log.d(TAG, "Found " +  codec + " rtpmap " + codecRtpMap + ", prefer at "
        + lines[mLineIndex]);
    String[] origMLineParts = lines[mLineIndex].split(" ");
    if (origMLineParts.length > 3) {
      StringBuilder newMLine = new StringBuilder();
      int origPartIndex = 0;
      // Format is: m=<media> <port> <proto> <fmt> ...
      newMLine.append(origMLineParts[origPartIndex++]).append(" ");
      newMLine.append(origMLineParts[origPartIndex++]).append(" ");
      newMLine.append(origMLineParts[origPartIndex++]).append(" ");
      newMLine.append(codecRtpMap);
      for (; origPartIndex < origMLineParts.length; origPartIndex++) {
        if (!origMLineParts[origPartIndex].equals(codecRtpMap)) {
          newMLine.append(" ").append(origMLineParts[origPartIndex]);
        }
      }
      lines[mLineIndex] = newMLine.toString();
      Log.d(TAG, "Change media description: " + lines[mLineIndex]);
    } else {
      Log.e(TAG, "Wrong SDP media description format: " + lines[mLineIndex]);
    }
    StringBuilder newSdpDescription = new StringBuilder();
    for (String line : lines) {
      newSdpDescription.append(line).append("\r\n");
    }
    return newSdpDescription.toString();
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

  private void switchCameraInternal() {
    if (!videoCallEnabled || numberOfCameras < 2 || isError || videoCapturer == null) {
      Log.e(TAG, "Failed to switch camera. Video: " + videoCallEnabled + ". Error : "
          + isError + ". Number of cameras: " + numberOfCameras);
      return;  // No video is sent or only one camera is available or error happened.
    }
    Log.d(TAG, "Switch camera");
    videoCapturer.switchCamera(null);
  }

  public void switchCamera() {
    executor.execute(new Runnable() {
      @Override
      public void run() {
        switchCameraInternal();
      }
    });
  }

  // Hank Extension
  public class DCObserver implements DataChannel.Observer {
    boolean bSendFile = false;
    FileOutputStream fout = null;
    DataOutputStream dout = null;
    File f = null;
    String  sFileName = "";
    boolean bLiveView = false;
    boolean bAudio    = false;

    boolean bVideo    = false;

    int     width    = -1;
    int     height   = -1;
    String  mime     = null;
    String  sps      = null;
    String  pps      = null;


    LocalServerSocket mLss = null;
    LocalSocket mReceiver = null;
    LocalSocket mSender   = null;
    int         mSocketId;
    final String LOCAL_ADDR = "com.via.hank-";
    public OutputStream os = null;
    public InputStream  is = null;
    VideoThread vt = null;


    public class VideoThread extends Thread {
      byte[] vbuff_tmp = new byte[1024*1024];
      ByteBuffer bb_tmp = ByteBuffer.allocate(1024*1024*10);
      byte[] dst  = new byte[1024*1024];
      private MediaCodec decoder;
      private Surface surface;

      String mMime;
      int    mWidth;
      int    mHeight;
      String mSPS;
      String mPPS;

      public byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
          data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                  + Character.digit(s.charAt(i+1), 16));
        }
        return data;
      }


      public VideoThread(Surface surf,String mime,int width,int height,String sps,String pps) {
        this.surface  = surf;
        this.mMime    = mime;
        this.mWidth   = width;
        this.mHeight  = height;
        this.mSPS     = sps;
        this.mPPS     = pps;
      }

      public void run() {

        try {
          MediaFormat format = new MediaFormat();
          format.setString(MediaFormat.KEY_MIME, mMime);
          format.setInteger(MediaFormat.KEY_WIDTH, mWidth);
          format.setInteger(MediaFormat.KEY_HEIGHT,mHeight);

          decoder = MediaCodec.createDecoderByType(mMime);

          byte[] sps= hexStringToByteArray(mSPS);
          byte[] pps= hexStringToByteArray(mPPS);
          ByteBuffer sps_bb = ByteBuffer.wrap(sps);
          ByteBuffer pps_bb = ByteBuffer.wrap(pps);
          format.setByteBuffer("csd-0", sps_bb);
          format.setByteBuffer("csd-1", pps_bb);

          decoder.configure(format, surface, null, 0);

          if (decoder == null) {
            Log.e("DecodeActivity", "Can't find video info!");
            return;
          }

          decoder.start();

          ByteBuffer[] inputBuffers = decoder.getInputBuffers();
          ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
          MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();


          int n = 0;
          int remaining = 0;

          int j = 0;
          while (!Thread.interrupted()) {
            int inIndex = decoder.dequeueInputBuffer(10000);
            if (inIndex > 0) {

              while (true) {
                //n = peerConnectionClient.inDataChannelObserver.is.read(vbuff_tmp);
                n = is.read(vbuff_tmp);
                bb_tmp.put(vbuff_tmp,0,n);

                remaining = bb_tmp.position();

                bb_tmp.flip();

                int length = (bb_tmp.get(0)<<0)&0x000000ff | (bb_tmp.get(1)<<8)&0x0000ff00 | (bb_tmp.get(2)<<16)&0x00ff0000| (bb_tmp.get(3)<<24)&0xff000000;
                if(length<remaining && length >0 )
                {
                  Log.d("HANK","Get NALU length : "+length);
                  bb_tmp.get();bb_tmp.get();bb_tmp.get();bb_tmp.get();

                  bb_tmp.get(dst,0,length);
                  bb_tmp.compact();

                  ByteBuffer buffer = inputBuffers[inIndex];
                  buffer.clear();
                  buffer.put(dst,0,length);
                  decoder.queueInputBuffer(inIndex, 0, length, j++, 0);
                  break;
                }
                else
                {
                  bb_tmp.compact();
                  try {
                    sleep(10);
                  } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                  }
                }
              }
            }

            int outIndex = decoder.dequeueOutputBuffer(info, 10000);
            switch (outIndex) {
              case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                Log.d("DecodeActivity", "INFO_OUTPUT_BUFFERS_CHANGED");
                outputBuffers = decoder.getOutputBuffers();
                break;
              case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                Log.d("DecodeActivity", "New format " + decoder.getOutputFormat());
                break;
              case MediaCodec.INFO_TRY_AGAIN_LATER:
                Log.d("DecodeActivity", "dequeueOutputBuffer timed out!");
                break;
              default:
                ByteBuffer buffer = outputBuffers[outIndex];
                //Log.v("DecodeActivity", "We can't use this buffer but render it due to the API limit, " + buffer);
                try {
                  sleep(10);
                } catch (InterruptedException e) {
                  // TODO Auto-generated catch block
                  e.printStackTrace();
                }

                decoder.releaseOutputBuffer(outIndex, true);
                break;
            }

            // All decoded frames have been rendered, we can stop playing now
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
              Log.d("DecodeActivity", "OutputBuffer BUFFER_FLAG_END_OF_STREAM");
              break;
            }
          }
          decoder.stop();
          decoder.release();
        } catch (Exception e) {
          Log.d("HANK","Something wrong in Video Thread");
        }
//
      }
    }


    public byte[] hexStringToByteArray(String s) {
      int len = s.length();
      byte[] data = new byte[len / 2];
      for (int i = 0; i < len; i += 2) {
        data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                + Character.digit(s.charAt(i+1), 16));
      }
      return data;
    }

    @Override
    public void onBufferedAmountChange(long var1) {
      Log.d("HANK","DataChannel.Observer onBufferedAmountChange : "+var1);
    }
    @Override
    public void onStateChange() {
      if(outDataChannel!=null) {
        Log.d("HANK", "outdataChannel.Observer onStateChange" + outDataChannel.state().name());
      }
      if(inDataChannel!=null) {
        Log.d("HANK", "indataChannel.Observer onStateChange" + inDataChannel.state().name());
      }
    }
    @Override
    public void onMessage(final DataChannel.Buffer buffer) {
      //Log.d("HANK", "DataChannel.Observer onMessage Buffer Received");
      ByteBuffer data = buffer.data;
      int size = data.remaining();
      byte[] bytes = new byte[size];
      data.get(bytes);
      if(buffer.binary)
      {
        if(bSendFile)
        {
          try {
            dout.write(bytes);
          } catch (Exception e) {
            final String printCommand = "file : "+sFileName+" write fail";
            Log.d("HANK",printCommand);
            executor.execute(new Runnable() {
              @Override
              public void run() {
                events.onPeerMessage(printCommand);
              }
            });
          }
        }
        else if(bVideo) {
          try {
            os.write(bytes);
          } catch (Exception e) {
            Log.d("HANK","os write fail");
          }
        }
      }
      else
      {
        String command = new String(bytes);
        String[] cmd_split = command.split(":");
        if(cmd_split[0].equalsIgnoreCase("File"))
        {
          if(cmd_split[1].equalsIgnoreCase("END")) {
            bSendFile = false;
            String rel = "OK";
            try {
              dout.close();
              fout.close();
            } catch (Exception e) {
              Log.d("HANK","fout close fail");
              rel = "NO OK";
            }
            dout = null;
            fout = null;

            final String printCommand = "file : "+sFileName+" Complete,"+"Close File: "+rel;
            Log.d("HANK",printCommand);
            executor.execute(new Runnable() {
              @Override
              public void run() {
                events.onPeerMessage(printCommand);
              }
            });
          }
          else {
            sFileName = cmd_split[1];
            bSendFile = true;
            String rel = "OK";

            //TODO : Need to Refine to external stroage, or user select.
            String PadFoneStoragePath = "/storage/emulated/0/Download/";

            f = new File(PadFoneStoragePath+sFileName);
            try {
              fout = new FileOutputStream(f);
              dout = new DataOutputStream(fout);
            } catch (Exception e) {
              rel = "NO OK";
              bSendFile = false;
              fout = null;
              dout = null;
              f = null;
            }

            final String printCommand = "Start to receive file : "+sFileName+"Create File: "+rel;
            Log.d("HANK",printCommand);
            executor.execute(new Runnable() {
              @Override
              public void run() {
                events.onPeerMessage(printCommand);
              }
            });
          }
        } else if(cmd_split[0].equalsIgnoreCase("MSG")) {
          String msgContent = cmd_split[1];
          final String printMssage = "Receive Message : "+ msgContent;
          Log.d("HANK",printMssage);
          executor.execute(new Runnable() {
            @Override
            public void run() {
              events.onPeerMessage(printMssage);
            }
          });
        } else if(cmd_split[0].equalsIgnoreCase("VIDEO")) {
            String videomsg = "Get Video ";
            if(cmd_split[1].equalsIgnoreCase("Width")) {
              width = Integer.valueOf(cmd_split[2]);
              videomsg = videomsg + "Width : "+width;
            } else if (cmd_split[1].equalsIgnoreCase("Height")) {
              height = Integer.valueOf(cmd_split[2]);
              videomsg = videomsg + "Height : "+height;
            } else if (cmd_split[1].equalsIgnoreCase("MIME")) {
              mime   = cmd_split[2];
              videomsg = videomsg + "MIME : "+mime;
            } else if (cmd_split[1].equalsIgnoreCase("sps")) {
              sps = cmd_split[2];
              videomsg = videomsg + "sps : "+sps;
            } else if (cmd_split[1].equalsIgnoreCase("pps")) {
              pps = cmd_split[2];
              videomsg = videomsg + "pps : "+pps;
            } else if (cmd_split[1].equalsIgnoreCase("START")) {

              bVideo = true;

              for(int jj=0;jj<10;jj++) {
                try {
                  mSocketId = new Random().nextInt();
                  mLss = new LocalServerSocket(LOCAL_ADDR+mSocketId);
                } catch (IOException e) {
                  Log.d("HANK","fail to create localserversocket");
                }
              }

              mReceiver = new LocalSocket();
              try {
                mReceiver.connect( new LocalSocketAddress(LOCAL_ADDR+mSocketId));
                mReceiver.setReceiveBufferSize(100000);
                mReceiver.setSoTimeout(3000);
                mSender = mLss.accept();
                mSender.setSendBufferSize(100000);
              } catch (IOException e) {
                // TODO Auto-generated catch block
                Log.d("HANK","fail to create mSender mReceiver");
                e.printStackTrace();
              }
              try {
                os = mSender.getOutputStream();
                is = mReceiver.getInputStream();
              } catch (IOException e) {
                // TODO Auto-generated catch block
                Log.d("HANK","fail to get mSender mReceiver");
                e.printStackTrace();
              }
              vt = new VideoThread(liveViewSurface.getHolder().getSurface(),mime,width,height,sps,pps);
              vt.start();

              bLiveView = true;
              videomsg = videomsg + "START,"+width+"x"+height+"("+mime+")";
              //executor.execute(new Runnable() {
//                @Override
//                public void run() {
//                  events.onVideoStart();
//                }
//              });
            } else if (cmd_split[1].equalsIgnoreCase("STOP")) {
              videomsg = videomsg + "STOP";
              bVideo = false;
              bLiveView = false;

            }

          final String printMssage = videomsg;
          Log.d("HANK",printMssage);
          executor.execute(new Runnable() {
            @Override
            public void run() {
              events.onPeerMessage(printMssage);
            }
          });



          }
      }

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
        final IceConnectionState newState) {
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
      executor.execute(new Runnable() {
        @Override
        public void run() {
          if (peerConnection == null || isError) {
            return;
          }
          if (stream.audioTracks.size() > 1 || stream.videoTracks.size() > 1) {
            reportError("Weird-looking stream: " + stream);
            return;
          }
          if (stream.videoTracks.size() == 1) {
            remoteVideoTrack = stream.videoTracks.get(0);
            remoteVideoTrack.setEnabled(renderVideo);
            remoteVideoTrack.addRenderer(new VideoRenderer(remoteRender));
          }
        }
      });
    }

    @Override
    public void onRemoveStream(final MediaStream stream){
      executor.execute(new Runnable() {
        @Override
        public void run() {
          if (peerConnection == null || isError) {
            return;
          }
          remoteVideoTrack = null;
          stream.videoTracks.get(0).dispose();
        }
      });
    }


    @Override
    public void onDataChannel(final DataChannel dc) {
      // Hank Extension
      if(!peerConnectionParameters.bEnableChatRoom) {

        reportError("AppRTC doesn't use data channels, but got: " + dc.label()
                + " anyway! (If you wanna use data channel, plz enable chat room)");

      } else {
        Log.d("HANK", "onDataChannel - Data Coming by " + dc.label());
        inDataChannel = dc;
        String channelName = inDataChannel.label();
        Log.d("HANK", "channel Name : " + channelName);
        inDataChannelObserver = new DCObserver();
        inDataChannel.registerObserver(inDataChannelObserver);
        Log.d("HANK", "On data Channel : " + dc.label());
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
      if (preferIsac) {
        sdpDescription = preferCodec(sdpDescription, AUDIO_CODEC_ISAC, true);
      }
      if (videoCallEnabled && preferH264) {
        sdpDescription = preferCodec(sdpDescription, VIDEO_CODEC_H264, false);
      }
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
