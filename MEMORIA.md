# MEMORIA — Heracles

Notas cortas para no volver a descubrir lo mismo. Una línea por cosa, con fecha.

## Cómo está montado (para orientarme rápido)

- 2026-11-23 · `app/` es la app Flutter (Dart); `backend/` (Dart) y `core/` acompañan. No hay `flutter`/`dart` instalados en este Linux, así que **no se puede correr `flutter test` ni `dart analyze` aquí**: los cambios de UI se revisan a ojo leyendo el archivo.
- 2026-11-23 · Filas de acción del chat, tres piezas que deben ir a la par:
  · `app/lib/widgets/stream_step.dart` → `StreamStepView` (工作步骤 / 思考)
  · `app/lib/widgets/tool_call_card.dart` → `ToolCallCard` (工具卡 + 双层命令卡)
  · `app/lib/widgets/bw_topbar.dart` → `PlainToolLine` (纯文字工具行)
  Las tres comparten geometría: icono 15 + hueco 8, texto 13.5 w600, caja `kMinTapTarget`.
- 2026-11-23 · Tiempo en cada acción: campo `atMs` (epoch ms) en `HToolCall` y `HStepStream`; se pinta con `hClockLabel(atMs)` (formato `11.23 12:10`). `null` ⇒ no se pinta (nunca se rellena con «ahora»).
- 2026-11-23 · Al leer historial, `chat_screen.dart` restaura `atMs` desde el `ts` de cada fila (`step` y `tool`) que escriben `buildStepRow` / `buildToolRow` en `local_agent.dart`.
- 2026-11-23 · Regresión del tiempo de las acciones: `app/test/action_clock_test.dart`.
