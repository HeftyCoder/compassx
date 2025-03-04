import 'package:compassx/src/compassx_event.dart';
import 'package:compassx/src/compassx_exception.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// The platform interface for receiving data from native side.
@immutable
final class CompassXPlatform {
  const CompassXPlatform._();

  /// The [EventChannel] used for receiving magnetic heading events.
  static const _mangeticHeadingChannel = EventChannel("studio.midoridesign/compassx/magnetic_heading");
  /// The [EventChannel] used for receiving true heading events.
  static const _trueHeadingChannel = EventChannel("studio.midoridesign/compassx/true_heading");

  static final _magneticHeadingStream = _mangeticHeadingChannel.receiveBroadcastStream();
  static final _trueHeadingStream = _trueHeadingChannel.receiveBroadcastStream(); 

  /// CompassXEvent stream for [CompassX].
  static Stream<CompassXEvent> get magneticHeadingEvents =>
      _magneticHeadingStream.map(CompassXEvent.fromMap).handleError(
            (e, st) => throw CompassXException.fromPlatformException(
              platformException: e,
              stackTrace: st,
            ),
            test: (e) => e is PlatformException,
          );
  
  static Stream<CompassXEvent> get trueHeadingEvents =>
      _trueHeadingStream.map(CompassXEvent.fromMap).handleError(
            (e, st) => throw CompassXException.fromPlatformException(
              platformException: e,
              stackTrace: st,
            ),
            test: (e) => e is PlatformException,
          );
        
}
