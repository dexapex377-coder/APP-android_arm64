# Puente UI v2 (3.6.22)

Un solo JNI (`oblDispatch`) para toda la UI Compose. El hilo de la UI solo
encola la cadena; `creator.cc` la drena en el hilo de Blender con `bContext`
válido y llama a `obl_bridge.handle(cmd)`.

## Archivo editable

```
/sdcard/Android/data/com.epai.oblender/files/obl/obl_bridge.py
/sdcard/Android/data/com.epai.oblender/files/obl/events.jsonl
```

Cambiar el `.py` no requiere rebuild. Enviar `bridge.reload` para recargarlo.

## Comandos (Kotlin -> Blender)

| Comando | Acción |
|---|---|
| `ping` | emite `pong` |
| `bridge.reload` | recarga el módulo |
| `state` | emite snapshot: workspace, area, mode, tool, objeto activo, selección |
| `actions` | emite catálogo para construir menús en Compose |
| `subscribe` | emite `state` cuando cambia selección/modo/workspace/tool |
| `event:<nombre>:<json>` | emite un evento arbitrario |
| `file.new` / `file.open:<path>` | archivo |
| `workspace.set:<name>` | cambia workspace |
| `tool.set:<idname>` | tool con override de `VIEW_3D` |
| `op:<idname>[\|json]@AREA` | operador con kwargs y contexto de editor |
| `set:<path>\|<json>[@AREA]` | escribe una propiedad RNA |
| `get:<path>` | lee una propiedad RNA y emite `get_result` |
| `ui.hide` / `ui.restore` | oculta/restaura la UI nativa |
| `python:<code>` | canal de desarrollo, Python arbitrario |

`AREA` puede ser `VIEW_3D`, `OUTLINER`, `PROPERTIES`, `NODE_EDITOR`,
`SEQUENCE_EDITOR`, `IMAGE_EDITOR`, `TEXT_EDITOR`, etc. El puente busca esa
editor en la screen activa y aplica `temp_override(window, area, region)`.
Así `bpy.ops.outliner.*`, `bpy.ops.node.*` o los settings de Tool funcionan
igual que si el usuario tocara esa región.

Ejemplos:

```kotlin
val b = OBLNativeActivity::oblDispatchStatic

b("workspace.set:Shading")
b("op:bpy.ops.outliner.item_activate|{\"extend\":false}@OUTLINER")
b("set:bpy.context.scene.render.resolution_x|1920")
b("get:bpy.context.object.name")
b("subscribe")
b("ui.hide")
```

## Eventos (Blender -> Kotlin)

`emit()` escribe una línea JSON por evento en `events.jsonl`. Kotlin puede
pollear el archivo (o usar `FileObserver`) y quedarse syncronizado con Blender:

```kotlin
data class BridgeEvent(val type: String, val data: Map<String, Any?>)
```

Eventos emitidos: `pong`, `log`, `state`, `actions`, `op_result`, `set_result`,
`get_result` y cualquier `event:<nombre>` propio.

## Cobertura

Cualquier botón de File/Edit/Render/Window/Help se reduce a una de estas
cuatro cosas: operador con contexto, propiedad RNA, cambio de workspace/tool
o una acción Python propia. No hace falta una JNI por botón.
