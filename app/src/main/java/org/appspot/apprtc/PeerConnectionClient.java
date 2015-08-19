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
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceView;
import android.net.LocalSocketAddress;

import org.appspot.apprtc.AppRTCClient.SignalingParameters;
import org.appspot.apprtc.util.LooperExecutor;
import org.appspot.apprtc.util.VideoDataChannelObserver;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
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

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Random;
import java.util.concurrent.Executor;

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

  private static final PeerConnectionClient instance = new PeerConnectionClient();
  private final PCObserver pcObserver = new PCObserver();
  private final SDPObserver sdpObserver = new SDPObserver();
  private final LooperExecutor executor;

  private final DCObserver dc1 = new DCObserver();

  public DataChannel outDataChannel   = null;
  public DataChannel inDataChannel = null;
  public DataChannel outDataChannel2 = null;
  public DataChannel inDataChannel2 = null;
  public DataChannel[] inDataChannels = new DataChannel[4];
  private int inChannelCounter = 0;

  public DataChannel[] outDataChannels = null;
  private int outChannelNumber = 4;


  public DCObserver  inDataChannelObserver = null;
  public DCObserver  inDataChannelObserver2= null;

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

  // enableVideo is set to true if video should be rendered and sent.

  // use for data channel video
  private SurfaceView liveViewSurface[];

  public void setLiveViewSurface(SurfaceView[] Surface)
  {
    this.liveViewSurface = Surface;
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
            new KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "true"));

    // Create SDP constraints.
    sdpMediaConstraints = new MediaConstraints();
    // if no OfferToReceiveAudio, it will assert
    sdpMediaConstraints.mandatory.add(new KeyValuePair(
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
  public void SendVideoInfo(DataChannel outChannel,String attribute, String value) {
    String data = "Video:"+attribute+":"+value;
    ByteBuffer buffer = ByteBuffer.wrap(data.getBytes());
    if(outChannel!=null)
    {
      Log.d("HANK","Send Video Info "+data);
      outChannel.send(new DataChannel.Buffer(buffer, false));
    }
  }

  // VIDEO CONTENT SENDER
  public void SendVideo(final DataChannel outChannel) {
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
      SendVideoInfo(outChannel,"Start", "");

      final ByteBuffer bb = ByteBuffer.allocate(1024 * 1024);

      Thread sendingThread = new Thread(new Runnable() {
        @Override
        public void run() {
          int NALUCount = 0;
          byte[] naluSizeByte = new byte[4];

          while (!Thread.interrupted()) {

            int sampleSize = me.readSampleData(bb, 0);
            //Log.d("HANK", "readSampleData : " + sampleSize);

            if (sampleSize > 0) {
              NALUCount++;
              //Log.d("HANK", "Send NALU length : " + sampleSize);
//              byte[] ba = new byte[sampleSize];
//              bb.get(ba);
              if (outChannel != null) {
                // 1. Send NALU Size to connected device first.
                naluSizeByte[0] = (byte) (sampleSize & 0x000000ff);
                naluSizeByte[1] = (byte) ((sampleSize & 0x0000ff00) >> 8);
                naluSizeByte[2] = (byte) ((sampleSize & 0x00ff0000) >> 16);
                naluSizeByte[3] = (byte) ((sampleSize & 0xff000000) >> 24);
                outChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(naluSizeByte), true));

                // 2. Divide NALU data by 1000
                int sentSize = 0;
                for (;;) {
                  //Log.d("HANK","Send NALU("+NALUCount+")  : "+j+"/"+sampleSize);
                  int dividedSize = 1000*16;
                  if ((sampleSize - sentSize) < dividedSize) {
                    dividedSize = sampleSize - sentSize;
                  }
                  //using slice method, maybe -1 memory copy
                  bb.position(sentSize);
                  bb.limit(dividedSize+sentSize);

                  outChannel.send(new DataChannel.Buffer(bb.slice(), true));

                  bb.limit(bb.capacity());

                  //outDataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(ba, j, Size), true));
                  sentSize = sentSize + dividedSize;
                  if (sentSize >= sampleSize) {
                    break;
                  }
                }
                try {
                  Thread.sleep(33);
                  //Log.d("HANK", "Sleep 33 ms lo.");
                } catch (Exception e) {
                  Log.d("HANK", "Sleep Error");
                }
              }
              bb.clear();
              me.advance();
            } else {
              Log.d("HANK", "No Frame lo.");
              boolean bAutoLoop = true;
              if(bAutoLoop) {
                Log.d("HANK", "Enable Auto Loop");
                me.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
              } else {
                Log.d("HANK", "Auto Loop is disable");
                break;
              }
            }
          }
          SendVideoInfo(outChannel,"STOP", "");
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
//      DataChannel.Init dcInit_tmp = new DataChannel.Init();
//      dcInit_tmp.id = 1;
//      outDataChannel = peerConnection.createDataChannel("1", dcInit_tmp);
//      outDataChannel.registerObserver(new DCObserver());
//
//      DataChannel.Init dcInit_tmp2 = new DataChannel.Init();
//      dcInit_tmp.id = 2;
//      outDataChannel2 = peerConnection.createDataChannel("2", dcInit_tmp2);
//      outDataChannel2.registerObserver(new DCObserver());
      outDataChannels = new DataChannel[outChannelNumber];
      for(int i=0;i<outChannelNumber;i++) {
        DataChannel.Init dcInit_tmp3 = new DataChannel.Init();
        dcInit_tmp3.id = i;
        outDataChannels[i] = peerConnection.createDataChannel("VideoChannel("+i+")", dcInit_tmp3);
        outDataChannels[i].registerObserver(new DCObserver());
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

  /// DATA CHANNEL RECEIVER FUNCTION -START- ///
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
    SurfaceView view;


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
          format.setInteger(MediaFormat.KEY_HEIGHT, mHeight);

          decoder = MediaCodec.createDecoderByType(mMime);

          byte[] sps = hexStringToByteArray(mSPS);
          byte[] pps = hexStringToByteArray(mPPS);
          ByteBuffer sps_bb = ByteBuffer.wrap(sps);
          ByteBuffer pps_bb = ByteBuffer.wrap(pps);
          format.setByteBuffer("csd-0", sps_bb);
          format.setByteBuffer("csd-1", pps_bb);

          decoder.configure(format, surface, null, 0);

        } catch (Exception e) {
          Log.d("HANK","This device cannot support codec :"+mMime);
        }


          if (decoder == null) {
            Log.e("DecodeActivity", "Can't find video info!");
            return;
          }

          decoder.start();

          ByteBuffer[] inputBuffers = decoder.getInputBuffers();
          ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
          MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

          int n = 0;
          int currentLength = 0;
          int frameCount = 0;
          int j = 0;
          while (!Thread.interrupted()) {
            int inIndex = decoder.dequeueInputBuffer(10000);
            if (inIndex > 0) {

              while (!Thread.interrupted()) {
                try {
                  n = is.read(vbuff_tmp);
                  bb_tmp.put(vbuff_tmp, 0, n);
                } catch (Exception e) {
                  Log.d("HANK","inputstream cannot read : "+e);
                  n = 0;
                }
                currentLength = bb_tmp.position();

                bb_tmp.flip();

                int length = (bb_tmp.get(0)<<0)&0x000000ff | (bb_tmp.get(1)<<8)&0x0000ff00 | (bb_tmp.get(2)<<16)&0x00ff0000| (bb_tmp.get(3)<<24)&0xff000000;
                if((length+4)<=(currentLength) && length >0 )
                {
                  Log.d("HANK", "Get NALU length : " + length );
                  bb_tmp.get();bb_tmp.get();bb_tmp.get();bb_tmp.get();
                  bb_tmp.get(dst, 0, length);
                  bb_tmp.compact();
                  ByteBuffer buffer = inputBuffers[inIndex];
                  buffer.clear();
                  buffer.put(dst, 0, length);

                  decoder.queueInputBuffer(inIndex, 0, length, j++, 0);
                  frameCount++;
                  Log.d("HANK","Receive Frame Count : "+frameCount);
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
      }
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
              vt = new VideoThread(view.getHolder().getSurface(),mime,width,height,sps,pps);
              vt.start();

              bLiveView = true;
              videomsg = videomsg + "START,"+width+"x"+height+"("+mime+")";

            } else if (cmd_split[1].equalsIgnoreCase("STOP")) {
              videomsg = videomsg + "STOP";
              bVideo = false;
              bLiveView = false;
              vt.interrupt();
              vt = null;
            }

          final String printMessage = videomsg;
          Log.d("HANK",printMessage);
          executor.execute(new Runnable() {
            @Override
            public void run() {
              events.onPeerMessage(printMessage);
            }
          });

          }
      }

    }
  }
  /// DATA CHANNEL RECEIVER FUNCTION -END- ///


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

      } else if(dc.label().equalsIgnoreCase("1")){

        Log.d("HANK", "onDataChannel - Data Coming by " + dc.label());
        inDataChannel = dc;
        String channelName = inDataChannel.label();
        Log.d("HANK", "channel Name : " + channelName);
        inDataChannelObserver = new DCObserver();
        inDataChannel.registerObserver(inDataChannelObserver);
        Log.d("HANK", "On data Channel : " + dc.label());

      } else if(dc.label().equalsIgnoreCase("2")) {

        Log.d("HANK", "onDataChannel - Data Coming by " + dc.label());
        inDataChannel2 = dc;
        String channelName = inDataChannel2.label();
        Log.d("HANK", "channel Name : " + channelName);
        inDataChannelObserver2 = new DCObserver();
        inDataChannel2.registerObserver(inDataChannelObserver2);
        Log.d("HANK", "On data Channel : " + dc.label());
      } else {

        inDataChannels[inChannelCounter] = dc;
        String channelName = inDataChannels[inChannelCounter].label();
        Log.d("HANK", "channel Name : " + channelName);
        Log.d("HANK", "On data Channel : " + dc.label());
        VideoDataChannelObserver VideoObserver = new VideoDataChannelObserver();
        VideoObserver.setEvents(events);
        VideoObserver.setExecutor(executor);
        VideoObserver.setSurface(liveViewSurface[inChannelCounter]);

        inDataChannels[inChannelCounter].registerObserver(VideoObserver);
        inChannelCounter++;
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
