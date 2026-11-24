import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ieum_mobile/core/voice/mic_permission.dart';
import 'package:ieum_mobile/core/voice/on_device_stt_service.dart';
import 'package:ieum_mobile/core/voice/on_device_tts_service.dart';
import 'package:ieum_mobile/core/voice/stt_service.dart';
import 'package:ieum_mobile/core/voice/tts_service.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'voice_providers.g.dart';

@riverpod
MicPermissionService micPermissionService(Ref ref) => const OnDeviceMicPermissionService();

@riverpod
SttService sttService(Ref ref) => OnDeviceSttService(permission: ref.watch(micPermissionServiceProvider));

@riverpod
TtsService ttsService(Ref ref) => OnDeviceTtsService();
