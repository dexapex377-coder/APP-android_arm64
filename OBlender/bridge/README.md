# Puente UI (3.6.22)

Un solo punto de entrada JNI para toda la UI Compose:

```kotlin
OBLNativeActivity.oblDispatchStatic("workspace.set:Sculpting")
```

El hilo de la UI **solo encola** la cadena. El `drain` vive en
`app/src/main/cpp/creator/creator.cc`, dentro del loop de Blender, con un
`bContext` válido, y ejecuta `obl_bridge.handle(cmd)`.

## Archivo editable en el dispositivo

```
/sdcard/Android/data/com.epai.oblender/files/obl/obl_bridge.py
```

Se puede editar con `adb push`; no hace falta volver a compilar la APK. Después de
editar, enviar `bridge.reload` (o reiniciar la app).

## Protocolo

| Comando | Acción |
|---|---|
| `ping` | comprueba que el puente responde |
| `bridge.reload` | recarga el módulo Python desde `/sdcard` |
| `file.new` | `bpy.ops.wm.read_homefile(app_template="")` |
| `file.open:<path>` | abre un `.blend` |
| `workspace.set:<name>` | cambia `window.workspace` |
| `tool.set:<idname>` | `bpy.ops.wm.tool_set_by_id` con override de `VIEW_3D` |
| `ui.hide` | oculta topbar, statusbar, headers, toolbar y sidebar |
| `ui.restore` | restaura lo oculto por `ui.hide` |
| `op:<python>` | ejecuta Python arbitrario (canal de desarrollo) |

Ejemplos:

```kotlin
OBLNativeActivity.oblDispatchStatic("file.new")
OBLNativeActivity.oblDispatchStatic("workspace.set:Shading")
OBLNativeActivity.oblDispatchStatic("tool.set:builtin_brush.Clay")
OBLNativeActivity.oblDispatchStatic("ui.hide")
```

## Instalación del módulo

```bash
adb push obl_bridge.py /sdcard/Android/data/com.epai.oblender/files/obl/obl_bridge.py
```

Verificar en logcat:

```
OBL.BRIDGE JNI queued: ...
OBL.BRIDGE dispatch: ...
[OBL.BRIDGE] ...
```

## Flujo recomendado

1. Probar la acción desde el addon/Python y dejarla como comando en `obl_bridge.py`.
2. El botón Compose solo envía esa cadena.
3. Cuando la UI de Blender se oculta con `ui.hide`, los addons siguen vivos:
   solo se ocultan regiones y se sustituye el `draw` de la topbar; no se
   desregistran operadores ni keymaps.
