import 'package:ieum_mobile/features/voice/domain/voice_ports.dart';

class SystemRecordingClock implements RecordingClock {
  const SystemRecordingClock();

  @override
  DateTime now() => DateTime.now();

  @override
  Stream<void> ticks(Duration interval) => Stream<void>.periodic(interval);
}
