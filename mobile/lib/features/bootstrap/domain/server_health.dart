import 'package:freezed_annotation/freezed_annotation.dart';

part 'server_health.freezed.dart';
part 'server_health.g.dart';

@freezed
abstract class ServerHealth with _$ServerHealth {
  const factory ServerHealth({
    required String status,
    required String application,
    required String profile,
    required String timezone,
    required DateTime serverTime,
  }) = _ServerHealth;

  factory ServerHealth.fromJson(Map<String, dynamic> json) =>
      _$ServerHealthFromJson(json);
}
