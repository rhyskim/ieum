import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

/// 하단 4탭(홈·목록·캘린더·설정) 껍데기. 각 탭은 자기 내비게이션 스택을 유지한다.
class MainShell extends StatelessWidget {
  const MainShell({required this.navigationShell, super.key});

  final StatefulNavigationShell navigationShell;

  static const List<({IconData icon, String label})> tabs = <({IconData icon, String label})>[
    (icon: Icons.home_outlined, label: '홈'),
    (icon: Icons.view_list_outlined, label: '목록'),
    (icon: Icons.calendar_month_outlined, label: '캘린더'),
    (icon: Icons.settings_outlined, label: '설정'),
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: navigationShell,
      bottomNavigationBar: NavigationBar(
        height: 72,
        backgroundColor: Colors.transparent,
        selectedIndex: navigationShell.currentIndex,
        labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
        onDestinationSelected: (int i) =>
            navigationShell.goBranch(i, initialLocation: i == navigationShell.currentIndex),
        destinations: <Widget>[
          for (final t in tabs) NavigationDestination(icon: Icon(t.icon), label: t.label),
        ],
      ),
    );
  }
}
