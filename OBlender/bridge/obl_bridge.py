# SPDX-License-Identifier: GPL-2.0-or-later
"""obl_bridge - generic Kotlin/Compose <-> Blender command bridge.

The native side only forwards one string. ALL behaviour lives here so the UI
can be edited on the device without rebuilding the APK:

    /sdcard/Android/data/com.epai.oblender/files/obl/obl_bridge.py

Protocol v2 adds context-aware operators, RNA get/set, state snapshots, an
action catalogue and an event bus back to Kotlin.
"""

import bpy
import json
import os
from contextlib import nullcontext

_LOG_PREFIX = "[OBL.BRIDGE]"

_AREAS = {
    "VIEW_3D",
    "OUTLINER",
    "PROPERTIES",
    "NODE_EDITOR",
    "SEQUENCE_EDITOR",
    "IMAGE_EDITOR",
    "TEXT_EDITOR",
    "DOPESHEET_EDITOR",
    "GRAPH_EDITOR",
    "TIMELINE",
    "CLIP_EDITOR",
    "FILE_BROWSER",
    "PREFERENCES",
    "TOPBAR",
    "STATUSBAR",
}

_event_path_cache = None


def _event_path():
    global _event_path_cache
    if _event_path_cache is None:
        try:
            _event_path_cache = os.path.join(
                os.path.dirname(os.path.abspath(__file__)), "events.jsonl"
            )
        except Exception:
            _event_path_cache = os.path.join(bpy.app.tempdir, "obl_bridge_events.jsonl")
    return _event_path_cache


def emit(event_type, **data):
    """Append a JSON event to the bus. Kotlin polls this file."""
    record = {"type": event_type}
    record.update(data)
    try:
        line = json.dumps(record, separators=(",", ":")) + "\n"
        with open(_event_path(), "a", encoding="utf-8") as stream:
            stream.write(line)
    except Exception as exc:
        print("%s emit failed: %s" % (_LOG_PREFIX, exc))
    print("%s event: %s" % (_LOG_PREFIX, event_type))


def _log(message):
    print("%s %s" % (_LOG_PREFIX, message))
    emit("log", message=str(message))


def _state():
    """State survives bridge.reload because it lives in bpy's namespace."""
    ns = bpy.app.driver_namespace
    state = ns.get("obl_bridge_state")
    if state is None:
        state = {
            "hidden": False,
            "draws": {},
            "regions": [],
            "statusbars": [],
            "last_sig": None,
        }
        ns["obl_bridge_state"] = state
    return state


def _parse(command, prefix):
    """Parse '<payload>[|<json>][@AREA]' after a known prefix."""
    if not command.startswith(prefix):
        return None
    rest = command[len(prefix) :]
    area = None
    if "@" in rest:
        head, suffix = rest.rsplit("@", 1)
        if suffix in _AREAS:
            rest, area = head, suffix
    payload = None
    if "|" in rest:
        target, raw = rest.split("|", 1)
        try:
            payload = json.loads(raw)
        except Exception:
            payload = raw
    else:
        target = rest
    return target, payload, area


def _override(area_type):
    """Return a temp_override for the requested editor, or a null context."""
    if not area_type:
        return nullcontext()
    window = getattr(bpy.context, "window", None)
    if window is None or window.screen is None:
        return nullcontext()
    area = next((a for a in window.screen.areas if a.type == area_type), None)
    if area is None:
        return nullcontext()
    region = next((r for r in area.regions if r.type == "WINDOW"), None)
    if region is not None:
        return bpy.context.temp_override(window=window, area=area, region=region)
    return bpy.context.temp_override(window=window, area=area)


def _empty_draw(self, context):
    pass


def _active_tool_id():
    try:
        tool = bpy.context.workspace.tools.from_space_view3d_mode(
            bpy.context.mode, create=False
        )
        return tool.idname if tool else ""
    except Exception:
        return ""


def _snapshot():
    window = getattr(bpy.context, "window", None)
    view_layer = getattr(bpy.context, "view_layer", None)
    active = getattr(view_layer, "objects", None)
    active = getattr(active, "active", None) if active is not None else None
    area = getattr(bpy.context, "area", None)
    return {
        "workspace": getattr(getattr(window, "workspace", None), "name", ""),
        "screen": getattr(getattr(window, "screen", None), "name", ""),
        "area": getattr(area, "type", ""),
        "mode": getattr(bpy.context, "mode", ""),
        "tool": _active_tool_id(),
        "active_object": getattr(active, "name", ""),
        "selected": [obj.name for obj in getattr(bpy.context, "selected_objects", [])],
        "scene": getattr(getattr(bpy.context, "scene", None), "name", ""),
    }


def _workspace_tool():
    try:
        return bpy.context.workspace.tools.from_space_view3d_mode(
            bpy.context.mode, create=False
        )
    except Exception:
        return None


def _gizmo_props(idname):
    tool = _workspace_tool()
    return tool.gizmo_group_properties(idname) if tool is not None else None


def _operator_props(idname):
    tool = _workspace_tool()
    return tool.operator_properties(idname) if tool is not None else None


def _tool_settings(tool_id):
    info = {"tool": tool_id}
    if tool_id == "builtin.extrude_region":
        info["operator"] = "view3d.edit_mesh_extrude_move_normal"
        props = _gizmo_props("VIEW3D_GGT_xform_extrude")
        info["axis_type"] = getattr(props, "axis_type", "NORMAL")
    return info


def ui_hide():
    """Hide Blender's own UI, leaving the 3D canvas for the Compose overlay."""
    state = _state()
    if not state["hidden"]:
        for name in ("TOPBAR_HT_upper_bar", "TOPBAR_HT_lower_bar", "STATUSBAR_HT_header"):
            cls = getattr(bpy.types, name, None)
            if cls is not None and hasattr(cls, "draw"):
                state["draws"][name] = cls.draw
                cls.draw = _empty_draw
        state["hidden"] = True

    state["regions"] = []
    state["statusbars"] = []
    region_props = ("show_region_header", "show_region_toolbar", "show_region_ui")
    for screen in bpy.data.screens:
        state["statusbars"].append((screen.name, getattr(screen, "show_statusbar", False)))
        screen.show_statusbar = False
        for area_index, area in enumerate(screen.areas):
            space = area.spaces.active
            for prop in region_props:
                if hasattr(space, prop):
                    state["regions"].append(
                        (screen.name, area_index, prop, getattr(space, prop))
                    )
                    setattr(space, prop, False)
    _log("Blender UI hidden")


def ui_restore():
    state = _state()
    for name, draw in state["draws"].items():
        cls = getattr(bpy.types, name, None)
        if cls is not None:
            cls.draw = draw
    state["draws"] = {}

    for screen_name, value in state["statusbars"]:
        screen = bpy.data.screens.get(screen_name)
        if screen is not None:
            screen.show_statusbar = value

    for screen_name, area_index, prop, value in state["regions"]:
        screen = bpy.data.screens.get(screen_name)
        if screen is None or area_index >= len(screen.areas):
            continue
        space = screen.areas[area_index].spaces.active
        if hasattr(space, prop):
            setattr(space, prop, value)

    state["regions"] = []
    state["statusbars"] = []
    state["hidden"] = False
    _log("Blender UI restored")


def _depsgraph_handler(scene, depsgraph=None):
    snapshot = _snapshot()
    signature = (
        snapshot["active_object"],
        tuple(snapshot["selected"]),
        snapshot["mode"],
        snapshot["workspace"],
        snapshot["tool"],
    )
    state = _state()
    if state.get("last_sig") != signature:
        state["last_sig"] = signature
        emit("state", **snapshot)


def subscribe():
    """Emit a state event whenever the active object/mode/workspace/tool changes."""
    state = _state()
    old = state.get("depsgraph_handler")
    if old is not None:
        try:
            bpy.app.handlers.depsgraph_update_post.remove(old)
        except Exception:
            pass
    bpy.app.handlers.depsgraph_update_post.append(_depsgraph_handler)
    state["depsgraph_handler"] = _depsgraph_handler
    _log("subscribed to state changes")


def action_catalog():
    actions = [
        {"id": "file.new", "label": "File / New", "command": "file.new"},
        {"id": "ui.hide", "label": "Hide Blender UI", "command": "ui.hide"},
        {"id": "ui.restore", "label": "Restore Blender UI", "command": "ui.restore"},
        {
            "id": "outliner.select",
            "label": "Outliner / Activate",
            "command": "op:bpy.ops.outliner.item_activate|{\"extend\":false}@OUTLINER",
        },
        {
            "id": "props.material",
            "label": "Properties / Material",
            "command": "op:bpy.ops.wm.context_set_enum|{\"data_path\":\"space_data.context\",\"value\":\"MATERIAL\"}@PROPERTIES",
        },
    ]
    for workspace in bpy.data.workspaces:
        actions.append(
            {
                "id": "ws." + workspace.name,
                "label": workspace.name,
                "command": "workspace.set:" + workspace.name,
            }
        )
    for tool_id in ("builtin_brush.Draw", "builtin_brush.Clay", "builtin_brush.Creativity"):
        actions.append(
            {
                "id": "tool." + tool_id,
                "label": tool_id.split(".")[-1],
                "command": "tool.set:" + tool_id,
            }
        )
    return actions


def handle(command):
    """Execute one command string received from Kotlin/Compose."""
    if not command:
        return

    if command == "bridge.reload":
        import importlib
        import sys

        module = sys.modules.get(__name__)
        if module is not None:
            importlib.reload(module)
            _log("module reloaded")
        return

    if command == "ping":
        emit("pong")
        return

    if command == "ui.hide":
        ui_hide()
        return
    if command == "ui.restore":
        ui_restore()
        return
    if command == "subscribe":
        subscribe()
        return
    if command == "state":
        emit("state", **_snapshot())
        return
    if command == "actions":
        emit("actions", actions=action_catalog())
        return

    if command.startswith("event:"):
        rest = command[len("event:") :]
        if ":" in rest:
            name, raw = rest.split(":", 1)
            try:
                data = json.loads(raw)
            except Exception:
                data = {"value": raw}
            emit(name, **(data if isinstance(data, dict) else {"value": data}))
        else:
            emit(rest)
        return

    if command == "file.new":
        bpy.ops.wm.read_homefile(app_template="")
        _log("new file (General)")
        return

    if command.startswith("file.open:"):
        path = command.split(":", 1)[1]
        bpy.ops.wm.open_mainfile(filepath=path)
        _log("opened %s" % path)
        return

    if command.startswith("workspace.set:"):
        name = command.split(":", 1)[1]
        workspace = bpy.data.workspaces.get(name)
        if workspace is None:
            workspace = next((w for w in bpy.data.workspaces if w.name.startswith(name)), None)
        if workspace is None:
            _log("workspace not found: %s" % name)
            return
        bpy.context.window.workspace = workspace
        _log("workspace -> %s" % workspace.name)
        return

    if command.startswith("mode.set:"):
        mode = command.split(":", 1)[1]
        with _override("VIEW_3D"):
            bpy.ops.object.mode_set(mode=mode)
        emit("mode", mode=mode)
        return

    if command.startswith("tool.set:"):
        idname = command.split(":", 1)[1]
        with _override("VIEW_3D"):
            bpy.ops.wm.tool_set_by_id(name=idname)
        emit("tool", **_tool_settings(idname))
        return

    if command.startswith("tool.activate:"):
        idname = command.split(":", 1)[1]
        with _override("VIEW_3D"):
            bpy.ops.wm.tool_set_by_id(name=idname)
        emit("tool_settings", **_tool_settings(idname))
        return

    if command.startswith("tool.gizmo:"):
        parsed = _parse(command, "tool.gizmo:")
        rest, value, _area = parsed
        gizmo_id, prop = rest.split(":", 1)
        props = _gizmo_props(gizmo_id)
        if props is None:
            _log("gizmo props unavailable: %s" % gizmo_id)
            return
        if value is not None:
            setattr(props, prop, value)
        emit("tool_setting", group="gizmo", id=gizmo_id, prop=prop, value=getattr(props, prop))
        return

    if command.startswith("tool.opprop:"):
        parsed = _parse(command, "tool.opprop:")
        rest, value, _area = parsed
        op_id, prop = rest.split(":", 1)
        props = _operator_props(op_id)
        if props is None:
            _log("operator props unavailable: %s" % op_id)
            return
        if value is not None:
            setattr(props, prop, value)
        emit("tool_setting", group="operator", id=op_id, prop=prop, value=getattr(props, prop))
        return

    if command.startswith("tool.exec:"):
        parsed = _parse(command, "tool.exec:")
        target, kwargs, area = parsed
        operator = bpy
        for part in target.split("."):
            operator = getattr(operator, part)
        arguments = kwargs if isinstance(kwargs, dict) else {}
        with _override(area or "VIEW_3D"):
            result = operator(**arguments)
        emit("op_result", id=target, result=sorted(str(v) for v in (result or ())))
        return

    if command.startswith("op:"):
        parsed = _parse(command, "op:")
        target, kwargs, area = parsed
        operator = bpy
        for part in target.split("."):
            operator = getattr(operator, part)
        arguments = kwargs if isinstance(kwargs, dict) else {}
        with _override(area):
            result = operator(**arguments)
        emit("op_result", id=target, result=sorted(str(v) for v in (result or ())), area=area or "")
        return

    if command.startswith("set:"):
        parsed = _parse(command, "set:")
        target, value, area = parsed
        safe_globals = {"bpy": bpy, "__builtins__": {}}
        with _override(area):
            exec(target + " = _obl_value", safe_globals, {"_obl_value": value})
        emit("set_result", path=target, value=value)
        return

    if command.startswith("get:"):
        target = command[len("get:") :]
        safe_globals = {"bpy": bpy, "__builtins__": {}}
        value = eval(target, safe_globals, {})
        emit("get_result", path=target, value=value)
        return

    if command.startswith("python:"):
        code = command[len("python:") :]
        exec(code, {"bpy": bpy})
        _log("python exec ok")
        return

    _log("unknown command: %s" % command)
