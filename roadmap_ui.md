# Roadmap UI — Blender 3.6.22 (solo este port)

Este documento describe el puente genérico Kotlin/Compose ↔ Blender que ya
está implementado en **este repo de APK** y el plan para reemplazar la UI
nativa de Blender 3.6.22 por una UI Compose, sin compilar Blender por cada
botón.

## 1. Objetivo

- Tener una UI propia en **Kotlin/Compose** (horizontal, tipo hamburger:
  `File / Edit / Render / Window / Help`, workspaces y `+`).
- Cada botón, menú, submenú y tool de esa UI debe llamar **exactamente a la
  misma acción** que llama la UI de Blender hoy.
- La UI nativa de Blender se oculta, pero los addons y operadores siguen vivos.
- Iterar la UI en **segundos** editando Python desde `/sdcard`, no recompilando
  `.a` ni `.so` por cada cambio.

## 2. Estado actual

Commits locales en este repo (`main`):

| Commit | Contenido |
|---|---|
| `565bdbb` | Puente v1: `oblDispatch` JNI + cola en `creator.cc` + `obl_bridge.py` |
| `8f2fd82` | Protocolo v2: overrides por área, RNA `set/get`, `state`, `actions`, `subscribe`, bus de eventos |
| `80d887a` | Comandos de tool: `mode.set`, `tool.activate`, `tool.gizmo`, `tool.opprop`, `tool.exec` |
| `71fe7f6` | Catálogo automático de menús/submenús leyendo `bl_ui/*.py` |

El repo de Blender (`blender-clone`, rama `gestos-5.3-port-viejo`) **no tiene
cambios nuevos** para este trabajo: su último commit `6f63e80e` (FXAA de un
pass + `renderdiv 1.25`) ya está pusheado.

## 3. Arquitectura del puente

```text
Compose / Kotlin
   │  oblDispatch("op:bpy.ops.mesh.subdivide|{\"number_cuts\":2}@VIEW_3D")
   ▼
JNI  Java_com_epai_oblender_OBLNativeActivity_oblDispatch()
   │  jstring2string() → oblQueueDispatch()
   ▼
creator.cc  mutex + std::deque<std::string>
   │  drenado dentro del loop de Blender, con bContext válido
   ▼
BPY_run_string_exec(C, {"bpy"}, "import obl_bridge; obl_bridge.handle(cmd)")
   │
   ▼
bpy.ops.*  /  RNA  /  propiedades del tool
   │
   ▼
events.jsonl  →  Kotlin pollea y actualiza su UI
```

Puntos clave:

- **Un solo JNI**, no uno por botón.
- El hilo de UI **solo encola**; nunca toca `bpy.ops`.
- El `drain` ocurre en el hilo de render de Blender, igual que el patrón ya
  probado de `oblSetSculptToolRequest()`.
- El módulo Python se lee desde `/sdcard`, así que se edita sin rebuild.

### ¿Por qué no hace falta rebuild de los `.a`?

`creator.cc` ya usaba `BPY_run_string_exec` en este mismo port (shortcut grid y
fallback del tool set).Todo el código nuevo vive en la app:

- `app/src/main/cpp/creator/creator.{h,cc}`
- `app/src/main/cpp/android_native_app_glue.cc`
- `app/src/main/java/com/epai/oblender/OBLNativeActivity.java`
- `OBlender/bridge/obl_bridge.py`

Por tanto se mantiene `LATEST_BLENDER_RUN="44383142625"` en
`.github/workflows/release.yml`. Solo se necesita un **build de APK**.

## 4. Archivos en el dispositivo

```text
/sdcard/Android/data/com.epai.oblender/files/obl/obl_bridge.py
/sdcard/Android/data/com.epai.oblender/files/obl/events.jsonl
```

Para actualizar el módulo sin rebuild:

```bash
adb push OBlender/bridge/obl_bridge.py \
  /sdcard/Android/data/com.epai.oblender/files/obl/obl_bridge.py
```

Después, desde Kotlin:

```kotlin
OBLNativeActivity.oblDispatchStatic("bridge.reload")
```

`strHomePath` viene de Java `getExternalFilesDir("obl")` y se pasa a
`initial(...)`, por eso el módulo se importa desde esa ruta.

## 5. Protocolo resumido

| Comando | Acción |
|---|---|
| `ping` / `bridge.reload` | prueba y recarga del módulo |
| `state` | snapshot: workspace, area, mode, tool, objeto activo, selección |
| `actions` | catálogo base de acciones |
| `menu.tree` | ids de todos los menús detectados |
| `menu:<id>` | items de un menú/submenú con sus comandos |
| `menu.open:<id>` | fallback al menú nativo |
| `subscribe` | emite `state` al cambiar selección/modo/workspace/tool |
| `event:<nombre>:<json>` | evento arbitrario hacia Kotlin |
| `mode.set:<MODE>` | `EDIT`, `OBJECT`, `SCULPT`… |
| `tool.activate:<id>` | activa tool y emite `tool_settings` |
| `tool.gizmo:<id>:<prop>|<json>` | ajuste del gizmo de la tool |
| `tool.opprop:<op>:<prop>|<json>` | prop del operador de la tool |
| `tool.exec:<op>|<json>` | ejecuta el operador de la tool |
| `op:<id>|<json>@AREA` | cualquier operador con contexto de editor |
| `set:<path>|<json>[@AREA]` / `get:<path>` | propiedades RNA |
| `file.new` / `file.open:<path>` | archivo |
| `workspace.set:<name>` | workspace |
| `mesh.subdivide[:<n>]` | subdividir en Edit Mode |
| `ui.hide` / `ui.restore` | ocultar/restaurar UI nativa |
| `python:<code>` | canal de desarrollo |

`AREA` acepta `VIEW_3D`, `OUTLINER`, `PROPERTIES`, `NODE_EDITOR`,
`SEQUENCE_EDITOR`, `IMAGE_EDITOR`, `TEXT_EDITOR`, etc. El bridge aplica
`bpy.context.temp_override(window, area, region)` sobre esa editor.

## 6. Ejemplos que ya son válidos

### Subdivide (menú → operador)

Los menús son clases Python que en `draw()` llaman a
`layout.operator("mesh.subdivide")`. El bridge las parsea:

```kotlin
val b = OBLNativeActivity::oblDispatchStatic

b("menu.tree")
b("menu:VIEW3D_MT_edit_mesh_edges")
```

Item devuelto:

```json
{"type":"operator","id":"mesh.subdivide","label":"Subdivide",
 "command":"op:bpy.ops.mesh.subdivide@VIEW_3D"}
```

Al tocarlo en Compose:

```kotlin
b("mode.set:EDIT")
b("mesh.subdivide:2")
// o: b("op:bpy.ops.mesh.subdivide|{\"number_cuts\":2}@VIEW_3D")
```

### Extrude (active tool)

En las fuentes de 3.6:

```text
tool:      builtin.extrude_region
operador:  view3d.edit_mesh_extrude_move_normal
ajuste:    VIEW3D_GGT_xform_extrude.axis_type = NORMAL | XYZ
```

```kotlin
b("mode.set:EDIT")
b("tool.activate:builtin.extrude_region")
b("tool.gizmo:VIEW3D_GGT_xform_extrude:axis_type|\"XYZ\"")
b("tool.exec:view3d.edit_mesh_extrude_move_normal|{\"release_confirm\":true}")
```

### Outliner y Properties

```kotlin
b("op:bpy.ops.outliner.item_activate|{\"extend\":false}@OUTLINER")
b("op:bpy.ops.wm.context_set_enum|{\"data_path\":\"space_data.context\",\"value\":\"MATERIAL\"}@PROPERTIES")
```

### Ocultar la UI de Blender

```kotlin
b("ui.hide")
```

Esto solo oculta regiones y reemplaza el `draw` de la topbar; **no desregistra
operadores, addons ni keymaps**. Para volver: `ui.restore`.

No usar `area.type = 'EMPTY'`: el propio RNA dice *“should never be set”*
(issue #87187).

## 7. Cómo se hará la UI Compose

### Fase 0 — validar el puente en la APK

1. Build de APK con GitHub Actions (`LATEST_BLENDER_RUN` sin cambios).
2. Instalar y verificar en logcat:

```text
OBL.BRIDGE JNI queued: ...
OBL.BRIDGE dispatch: ...
[OBL.BRIDGE] ...
```

3. Enviar `ping`, `state`, `menu.tree` y confirmar eventos en `events.jsonl`.

### Fase 1 — prototipo en Python addon

Antes de Compose, validar en el addon las acciones:

- hamburger con `File / Edit / Render / Window / Help`,
- lista de workspaces + `+`,
- `ui.hide` / `ui.restore`,
- ejecutar Subdivide/Extrude desde el menú Python.

Cada acción que funcione se deja como comando en `obl_bridge.py`.

### Fase 2 — menú Compose desde `menu.tree`

- Kotlin pide `menu.tree` y luego `menu:<id>`.
- Se arma un `LazyColumn`/`DropdownMenu` con los items devueltos.
- Cada item guarda su `command` y lo envía con `oblDispatchStatic`.
- Submenús: el item `type=menu` vuelve a pedir `menu:<id>`.

Limitación conocida: el parseo es estático, no evalúa `if` dentro de `draw()`.
Algunos items pueden no estar disponibles según modo/selección; se filtrará
después con `poll()` si hace falta.

### Fase 3 — paneles de tool y Properties

- `tool_settings` / `tool_setting` alimentan los sliders/toggles de Compose.
- Esos controles escriben con `tool.gizmo:...`, `tool.opprop:...`, `set:...`.
- Así la UI propia usa **las mismas props** que el Tool Settings nativo.

### Fase 4 — sincronización

- Corrutina que pollea `events.jsonl` (o `FileObserver`).
- Eventos: `state`, `actions`, `menu_items`, `tool_settings`, `op_result`.
- La UI se actualiza cuando cambia selección/modo/workspace/tool (`subscribe`).

### Fase 5 — input y canvas

- UI Compose encima del `Surface` de Blender.
- `ui.hide` deja visible solo el canvas `VIEW_3D`.
- Los toques de Compose se consumen en la overlay; el canvas 3D sigue para
  navegar, esculpir y pinceladas.

### Fase 6 — rendimiento

Mantener lo ya decidido:

- `renderdiv 1.25` por defecto (`1528×720 → 1222×576`).
- FXAA real de un pass activo (`debug.blender.obl_fxaa=1`).
- `viewport_aa=0`, `force_redraw=0`.
- No usar `renderdiv 2`, no desactivar EEVEE Next, no forzar Workbench global.
- No usar `GPU_finish()` / `glFinish()` en draw engines (rompe “New 2D Animation”).
- No extirpar VSE ni Grease Pencil.
- Solo horizontal; prioridad RAM/FPS.

## 8. Build y push

- **APK**: `.github/workflows/release.yml` con
  `LATEST_BLENDER_RUN="44383142625"`.
- **`.a`**: no hace falta regenerar para el puente.
- **Repo de Blender**: sin cambios nuevos; no se pushea nada de ahí.
- **Repo de APK**: se pushea `main` con los 4 commits del puente más este
  `roadmap_ui.md`.

## 9. Pendiente

- Ejecutar el build de APK y probar el puente en el dispositivo.
- Confirmar que `menu.tree` devuelve los menús esperados en 3.6.22.
- Diseñar el hamburger Compose consumiendo `menu_items`.
- Si se quiere push real de eventos en vez de polling, añadir callback JNI
  (costaría otro build de APK; no es necesario para empezar).
