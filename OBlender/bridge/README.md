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
| `actions` | emite catálogo base para construir menús en Compose |
| `menu.tree` | emite la lista de **todos** los menús detectados |
| `menu:<id>` | emite los items de un menú/submenú con sus comandos |
| `menu.open:<id>` | fallback: abre el menú nativo |
| `mesh.subdivide[:<n>]` | subdivide en Edit Mode (`number_cuts`) |
| `subscribe` | emite `state` cuando cambia selección/modo/workspace/tool |
| `event:<nombre>:<json>` | emite un evento arbitrario |
| `file.new` / `file.open:<path>` | archivo |
| `workspace.set:<name>` | cambia workspace |
| `mode.set:<MODE>` | cambia modo (`EDIT`, `OBJECT`, `SCULPT`…) |
| `tool.set:<idname>` | tool con override de `VIEW_3D` |
| `tool.activate:<idname>` | activa tool y emite `tool_settings` |
| `tool.gizmo:<id>:<prop>[\|json]` | lee/escribe prop del gizmo del tool |
| `tool.opprop:<id>:<prop>[\|json]` | lee/escribe prop del operador del tool |
| `tool.exec:<op>[\|json]` | ejecuta el operador del tool |
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

## Caso concreto: Subdivide y menús/submenús

Los menús de Blender son clases Python que en `draw()` llaman a
`layout.operator("mesh.subdivide")` o `layout.menu("OTRO_MENU")`. El bridge
parsea esos archivos (`scripts/startup/bl_ui/*.py`) y te devuelve el árbol real:

```kotlin
b("menu.tree")                       // evento menu_tree: ids de todos los menús
b("menu:VIEW3D_MT_edit_mesh_edges")  // evento menu_items con sus items
```

Cada item trae:

```json
{"type":"operator","id":"mesh.subdivide","label":"Subdivide",
 "command":"op:bpy.ops.mesh.subdivide@VIEW_3D"}
```

Tu menú Compose searma con esos items. Al tocar Subdivide:

```kotlin
b("mode.set:EDIT")
b("mesh.subdivide:2")   // o b("op:bpy.ops.mesh.subdivide|{\"number_cuts\":2}@VIEW_3D")
```

No estás “llamando al menú”: estás llamando **al operador que el menú llama**.
Por eso funciona igual para cualquier menú/submenú, herramienta o panel.

## Caso concreto: tool Extrude

En las fuentes de 3.6/5.3 el tool es `builtin.extrude_region`, su operador es
`view3d.edit_mesh_extrude_move_normal` y su ajuste es `axis_type`
(`NORMAL` / `XYZ`) del gizmo `VIEW3D_GGT_xform_extrude`.

Con tu icono/menú de Kotlin:

```kotlin
b("mode.set:EDIT")
b("tool.activate:builtin.extrude_region")     // emite tool_settings
b("tool.gizmo:VIEW3D_GGT_xform_extrude:axis_type|\"XYZ\"")
b("tool.exec:view3d.edit_mesh_extrude_move_normal|{\"release_confirm\":true}")
```

`tool_settings` / `tool_setting` llegan por `events.jsonl`, así que Compose
pinta los mismos valores que vería en el header nativo. Si ocultás la UI con
`ui.hide`, tu panel Compose es la única representación visible, pero lee y
escribe **las mismas props** del tool de Blender.

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
