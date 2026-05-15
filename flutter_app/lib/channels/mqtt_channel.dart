import 'package:flutter/services.dart';

class MqttChannel {
  MqttChannel._();

  static const _events = EventChannel('com.tpeapp/mqtt_events');

  static Stream<Map<String, String>> get events =>
      _events.receiveBroadcastStream().map((event) =>
          Map<String, String>.from(event as Map));
}
