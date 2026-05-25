import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';

import '../channels/screen_share_channel.dart';

/// Manages the full Flutter-side WebRTC screen-sharing pipeline.
///
/// Pipeline:
///  1. Connects to the Camera-Site signaling relay via a plain WebSocket
///     ([dart:io] [WebSocket]) at `/api/tpe/signal/{sessionId}`.
///  2. Calls [navigator.mediaDevices.getDisplayMedia] to acquire a
///     [MediaStream] containing the device screen (triggers the Android
///     MediaProjection system prompt internally through flutter_webrtc).
///  3. Adds the video track to an [RTCPeerConnection] and creates an SDP
///     offer which is relayed to the accountability partner's browser.
///  4. If remote control is enabled, creates an [RTCDataChannel] named
///     `remote-control` and listens for JSON tap/swipe/keyevent messages
///     from the partner.  Each received `tap` event is forwarded via
///     [ScreenShareChannel.injectTap] to the native [RemoteControlService]
///     (AccessibilityService) or `su -c` fallback.
///
/// Signaling envelope schema (matches Camera-Site relay and [StreamCoordinator]):
/// ```json
/// { "type": "join" }
/// { "type": "offer",         "sdp": "<sdp>" }
/// { "type": "answer",        "sdp": "<sdp>" }
/// { "type": "ice-candidate", "sdpMid": "...", "sdpMLineIndex": 0, "candidate": "..." }
/// ```
class ScreenShareService {
  // ------------------------------------------------------------------
  //  Configuration
  // ------------------------------------------------------------------

  static const _iceServers = {
    'iceServers': [
      {'urls': 'stun:stun.l.google.com:19302'},
      {'urls': 'stun:stun1.l.google.com:19302'},
    ],
    'sdpSemantics': 'unified-plan',
  };

  static const _offerConstraints = {
    'mandatory': {
      'OfferToReceiveVideo': false,
      'OfferToReceiveAudio': false,
    },
    'optional': [],
  };

  // ------------------------------------------------------------------
  //  State
  // ------------------------------------------------------------------

  RTCPeerConnection? _peerConnection;
  MediaStream? _localStream;
  RTCDataChannel? _remoteControlChannel;
  WebSocket? _ws;
  bool _remoteControlEnabled = false;

  /// `true` while the service is running (between [start] and [stop]).
  bool get isRunning => _peerConnection != null;

  /// Callback invoked whenever the streaming state changes.
  VoidCallback? onStateChanged;

  // ------------------------------------------------------------------
  //  Public API
  // ------------------------------------------------------------------

  /// Start screen sharing.
  ///
  /// [signalingUrl] must be the full WebSocket URL of the Camera-Site relay,
  /// e.g. `wss://example.com/api/tpe/signal/{sessionId}`.
  ///
  /// [remoteControlEnabled] enables the DataChannel that delivers remote-input
  /// events from the partner to this device.
  Future<void> start({
    required String signalingUrl,
    bool remoteControlEnabled = false,
  }) async {
    _remoteControlEnabled = remoteControlEnabled;

    // 1. Acquire screen capture stream via flutter_webrtc.
    //    On Android this triggers the MediaProjection system consent dialog.
    _localStream = await navigator.mediaDevices.getDisplayMedia({
      'video': {
        'mandatory': {
          'minWidth': '640',
          'minHeight': '480',
          'minFrameRate': '15',
        },
      },
      'audio': false,
    });

    // 2. Create the RTCPeerConnection.
    _peerConnection = await createPeerConnection(_iceServers);
    _peerConnection!.onIceCandidate = _onIceCandidate;
    _peerConnection!.onIceConnectionState = _onIceConnectionState;

    // 3. Add the screen-capture video track to the peer connection.
    for (final track in _localStream!.getTracks()) {
      await _peerConnection!.addTrack(track, _localStream!);
    }

    // 4. Optionally create the remote-control DataChannel.
    if (_remoteControlEnabled) {
      final dcInit = RTCDataChannelInit()
        ..ordered = true
        ..negotiated = false;
      _remoteControlChannel = await _peerConnection!
          .createDataChannel('remote-control', dcInit);
      _remoteControlChannel!.onMessage = _onDataChannelMessage;
    }

    // 5. Connect the signaling WebSocket.
    _ws = await WebSocket.connect(signalingUrl);
    _ws!.listen(
      _onSignalingMessage,
      onDone: _onWsDone,
      onError: _onWsError,
    );

    // 6. Announce arrival so the relay can route messages to this peer.
    _wsSend({'type': 'join'});

    // 7. Broadcaster always creates the initial offer.
    await _createAndSendOffer();

    onStateChanged?.call();
  }

  /// Tear down the WebRTC pipeline and disconnect the signaling socket.
  Future<void> stop() async {
    await _ws?.close();
    _ws = null;

    for (final track in _localStream?.getTracks() ?? []) {
      await track.stop();
    }
    await _localStream?.dispose();
    _localStream = null;

    await _remoteControlChannel?.close();
    _remoteControlChannel = null;

    await _peerConnection?.close();
    _peerConnection = null;

    _remoteControlEnabled = false;
    onStateChanged?.call();
  }

  // ------------------------------------------------------------------
  //  Signaling
  // ------------------------------------------------------------------

  void _wsSend(Map<String, dynamic> payload) {
    _ws?.add(jsonEncode(payload));
  }

  Future<void> _createAndSendOffer() async {
    final offer =
        await _peerConnection!.createOffer(_offerConstraints);
    await _peerConnection!.setLocalDescription(offer);
    _wsSend({'type': 'offer', 'sdp': offer.sdp});
  }

  void _onSignalingMessage(dynamic raw) {
    final Map<String, dynamic> msg;
    try {
      msg = jsonDecode(raw as String) as Map<String, dynamic>;
    } catch (_) {
      return;
    }

    switch (msg['type'] as String?) {
      case 'answer':
        final sdp =
            RTCSessionDescription(msg['sdp'] as String, 'answer');
        _peerConnection?.setRemoteDescription(sdp).catchError((Object e) {
          debugPrint('[ScreenShareService] setRemoteDescription error: $e');
        });
        break;

      case 'ice-candidate':
        final candidate = RTCIceCandidate(
          msg['candidate'] as String,
          msg['sdpMid'] as String?,
          msg['sdpMLineIndex'] as int?,
        );
        _peerConnection?.addCandidate(candidate).catchError((Object e) {
          debugPrint('[ScreenShareService] addCandidate error: $e');
        });
        break;

      default:
        break;
    }
  }

  void _onIceCandidate(RTCIceCandidate candidate) {
    _wsSend({
      'type': 'ice-candidate',
      'candidate': candidate.candidate,
      'sdpMid': candidate.sdpMid,
      'sdpMLineIndex': candidate.sdpMLineIndex,
    });
  }

  void _onIceConnectionState(RTCIceConnectionState state) {
    if (state == RTCIceConnectionState.RTCIceConnectionStateFailed ||
        state == RTCIceConnectionState.RTCIceConnectionStateDisconnected) {
      stop();
    }
  }

  // ------------------------------------------------------------------
  //  Remote-control DataChannel
  // ------------------------------------------------------------------

  void _onDataChannelMessage(RTCDataChannelMessage message) {
    final Map<String, dynamic> json;
    try {
      json = jsonDecode(message.text) as Map<String, dynamic>;
    } catch (e) {
      debugPrint('[ScreenShareService] DataChannel parse error: $e');
      return;
    }

    final type = json['type'] as String?;
    if (type == 'tap') {
      final x = (json['x'] as num?)?.toDouble();
      final y = (json['y'] as num?)?.toDouble();
      if (x != null && y != null) {
        // Forward normalised coordinates to the native RemoteControlService
        // (AccessibilityService) via MethodChannel.
        ScreenShareChannel.injectTap(x, y).catchError((Object e) {
          debugPrint('[ScreenShareService] injectTap error: $e');
        });
      }
    }
  }

  // ------------------------------------------------------------------
  //  WebSocket lifecycle
  // ------------------------------------------------------------------

  void _onWsDone() {
    stop();
  }

  void _onWsError(Object error) {
    debugPrint('[ScreenShareService] WebSocket error: $error');
    stop();
  }
}
