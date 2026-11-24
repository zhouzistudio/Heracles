import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:heracles/models/message.dart';
import 'package:heracles/theme/app_theme.dart';
import 'package:heracles/theme/cupertino_kit.dart';
import 'package:heracles/widgets/bw_topbar.dart';
import 'package:heracles/widgets/stream_step.dart';
import 'package:heracles/widgets/tool_call_card.dart';

/// **「每次操作在对话后面加上当前的时间」回归测试**（2026-11-23）。
///
/// ## 用户要的
///
/// 每一行动作（工作步骤 / 工具调用）**行尾**带上它发生的时刻，格式
/// `11.23 12:10`（月.日 时:分）。一串动作竖着排下来，时间对齐成一列，
/// 回看时能排出先后。
///
/// ## 这组断言守什么
///
/// 1. `hClockLabel` 的格式（含一位数补零）与"没有时间"⇒ 空串
/// 2. 工具卡（`ToolCallCard`）与纯文字工具行（`PlainToolLine`）行尾都画时间
/// 3. 工作步骤行（`StreamStepView`）行尾也画
/// 4. **老记录（`atMs == null`）不画时间** —— 不拿"现在"去顶替（那是伪造）
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  /// 2026-11-23 12:10（用户给的样例句式）。
  final stamp = DateTime(2026, 11, 23, 12, 10).millisecondsSinceEpoch;
  const stampText = '11.23 12:10';

  /// 「像时间」的兜底判据：任意 `M.dd HH:mm`。
  ///
  /// 用正则找"有没有画时间"，而不是只找上面那一串 —— 这样一旦有人
  /// 在缺 `atMs` 时偷偷用 `DateTime.now()` 补一个，这条也会红。
  final anyClock = RegExp(r'\d{1,2}\.\d{2} \d{2}:\d{2}');

  Widget wrap(Widget child) => MaterialApp(
        theme: buildHeraclesTheme(),
        builder: (context, inner) => HueScope(
          hue: Hue.darkHue,
          child: inner ?? const SizedBox.shrink(),
        ),
        home: Scaffold(
          body: SingleChildScrollView(child: child),
        ),
      );

  // ============================================================
  // 1. 格式化：纯函数
  // ============================================================

  test('`hClockLabel`：月.日 时:分，一位数补零', () {
    expect(hClockLabel(stamp), stampText);
    // 1 月 5 日 09:07 ⇒ `1.05 09:07`（月不补零、日补零，与用户样例一致）
    expect(
      hClockLabel(DateTime(2026, 1, 5, 9, 7).millisecondsSinceEpoch),
      '1.05 09:07',
    );
  });

  test('`hClockLabel`：null / 0 / 负数 ⇒ 空串（调用方据此不画）', () {
    expect(hClockLabel(null), '');
    expect(hClockLabel(0), '');
    expect(hClockLabel(-1), '');
    expect(hClockLabel(-1700000000000), '');
  });

  // ============================================================
  // 2. 工具卡 / 纯文字工具行：行尾时间
  // ============================================================

  testWidgets('★★ 工具卡行尾画出时间', (tester) async {
    await tester.pumpWidget(wrap(ToolCallCard(
      call: HToolCall(
        id: 't1',
        tool: 'read_file',
        args: const {'path': 'lib/main.dart'},
        result: 'final x = 1;\n',
        status: ToolStatus.success,
        atMs: stamp,
      ),
    )));
    await tester.pump();

    expect(find.text(stampText), findsOneWidget,
        reason: '用户要求：每个动作后面带当时的时间');
  });

  testWidgets('★★ 老记录（atMs == null）的工具卡**不**画时间（不编"现在"）',
      (tester) async {
    await tester.pumpWidget(wrap(const ToolCallCard(
      call: HToolCall(
        id: 't2',
        tool: 'read_file',
        args: {'path': 'lib/main.dart'},
        result: 'final x = 1;\n',
        status: ToolStatus.success,
      ),
    )));
    await tester.pump();

    final texts = tester
        .widgetList<Text>(find.byType(Text))
        .map((t) => t.data ?? '')
        .where(anyClock.hasMatch);
    expect(texts, isEmpty,
        reason: '没有 `ts` 就不画 —— 补一个"现在"比没有更糟（伪造时间）');
  });

  testWidgets('★ 命令卡（双层）标题行也有时间', (tester) async {
    await tester.pumpWidget(wrap(ToolCallCard(
      call: HToolCall(
        id: 'c1',
        tool: 'run_shell_command',
        args: const {'command': 'ls -la'},
        result: 'total 0\n',
        status: ToolStatus.success,
        surface: 'sandbox',
        exitCode: 0,
        command: 'ls -la',
        atMs: stamp,
      ),
    )));
    await tester.pump();

    expect(find.text(stampText), findsOneWidget);
  });

  testWidgets('★ 纯文字工具行：传入 clock 就画，空串不画', (tester) async {
    await tester.pumpWidget(wrap(const PlainToolLine(
      icon: AppIcons.file,
      summary: '读取了 lib/main.dart',
      clock: stampText,
    )));
    await tester.pump();
    expect(find.text(stampText), findsOneWidget);

    await tester.pumpWidget(wrap(const PlainToolLine(
      icon: AppIcons.file,
      summary: '读取了 lib/main.dart',
    )));
    await tester.pump();
    expect(find.text(stampText), findsNothing);
  });

  // ============================================================
  // 3. 工作步骤行
  // ============================================================

  testWidgets('★★ 步骤行行尾也画时间', (tester) async {
    await tester.pumpWidget(wrap(StreamStepView(
      step: HStepStream(
        id: 's1',
        stepId: 'turn-1',
        title: '',
        text: '读完了三个文件',
        finished: true,
        status: 'ok',
        atMs: stamp,
      ),
    )));
    await tester.pump();

    expect(find.text(stampText), findsOneWidget);
  });

  testWidgets('★ 没有 atMs 的步骤行不画时间', (tester) async {
    await tester.pumpWidget(wrap(const StreamStepView(
      step: HStepStream(
        id: 's2',
        stepId: 'turn-1',
        title: '',
        text: '读完了三个文件',
        finished: true,
        status: 'ok',
      ),
    )));
    await tester.pump();

    final texts = tester
        .widgetList<Text>(find.byType(Text))
        .map((t) => t.data ?? '')
        .where(anyClock.hasMatch);
    expect(texts, isEmpty);
  });
}
