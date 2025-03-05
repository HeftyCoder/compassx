import 'dart:io';

import 'package:compassx/compassx.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:math' as math;

import 'package:permission_handler/permission_handler.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setPreferredOrientations([
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
  ]);
  runApp(const App());
}

class App extends StatelessWidget {
  const App({super.key});

  @override
  Widget build(BuildContext context) {
    final double size = 100;
    return MaterialApp(
      home: Scaffold(
        body: Center(
          child: DefaultTextStyle(
              style: Theme.of(context).textTheme.titleMedium!,
              child: Column(
                mainAxisAlignment: MainAxisAlignment.spaceAround,
                children: [
                  CompassWidget(
                    stream: CompassX.magneticHeadingEvents,
                    size: size,
                  ),
                  CompassWidget(
                    stream: CompassX.trueHeadingEvents,
                    size: size,
                  ),
                  FilledButton(
                    onPressed: () async {
                      if (!Platform.isAndroid) return;
                      await Permission.location.request();
                    },
                    child: const Text('Request permission'),
                  )
                ],
              )),
        ),
      ),
    );
  }
} //

class CompassWidget extends StatelessWidget {
  final Stream<CompassXEvent> stream;
  final double size;
  const CompassWidget({super.key, required this.stream, this.size = 20});

  @override
  Widget build(BuildContext context) {
    return StreamBuilder(
      stream: stream,
      builder: (context, snapshot) {
        if (snapshot.hasError) return Text(snapshot.error.toString());
        if (!snapshot.hasData) return const CircularProgressIndicator();
        final compass = snapshot.data!;
        return Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('Heading: ${compass.heading}'),
            Text('Accuracy: ${compass.accuracy}'),
            Text('Should calibrate: ${compass.shouldCalibrate}'),
            Transform.rotate(
              angle: compass.heading * math.pi / 180,
              child: Icon(
                Icons.arrow_upward_rounded,
                size: size,
              ),
            ),
          ],
        );
      },
    );
  }
}
