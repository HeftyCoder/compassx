import 'package:compassx/src/compassx_event.dart';
import 'package:compassx/src/compassx_platform.dart';
import 'package:flutter/foundation.dart';

/// The top-level class of compassx package.
///
/// See the [wiki](https://github.com/natsuk4ze/compassx/wiki) for details.
@immutable
final class CompassX {
  const CompassX._();

  /// [CompassXEvent] stream for using the magnetic compass sensor.
  ///
  /// Throw [CompassXException] when the compass sensor is not available.
  /// That includes the following cases.
  /// - Older or excessively cheap Android devices.
  /// - iOS simulators.
  static Stream<CompassXEvent> get magneticHeadingEvents => CompassXPlatform.magneticHeadingEvents;

  /// [CompassXEvent] stream for using the true heading compass sensor.
  ///
  /// Throw [CompassXException] when the compass sensor is not available.
  /// That includes the following cases.
  /// - Older or excessively cheap Android devices.
  /// - iOS simulators.
  static Stream<CompassXEvent> get trueHeadingEvents => CompassXPlatform.trueHeadingEvents;
}
