import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/voice/stt_service.dart';
import 'package:ieum_mobile/core/voice/tts_service.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'voice_providers.g.dart';

@riverpod
SttService sttService(Ref ref) => const NoopSttService();

@riverpod
TtsService ttsService(Ref ref) => const NoopTtsService();
