# SPDX-License-Identifier: GPL-2.0-or-later
"""obl_bridge - generic Kotlin/Compose <-> Blender command bridge.

This file is the executable spec for the UI. The native side only forwards a
single string; ALL behaviour lives here so it can be edited on the device at:

    /sdcard/Android/data/com.epai.oblender/files/obl/obl_bridge.py

No APK rebuild is needed to change a button: edit the file, then send the
command again. Use the command ``bridge.reload`` to pick up edits immediately.
"""

import bpy

_LOG_PREFIX = "[OBL.BRIDGE]"


def _log(message):
    print("%s %s" % (_LOG_PREFIX, message))


def _state():
    """State survives ``bridge.reload`` because it lives in bpy's namespace."""
    ns = bpy.app.driver_namespace
    state = ns.get("obl_bridge_state")
    if state is None:
        state = {"hidden": False, "draws": {}, "regions": [], "statusbars": []}
        ns["obl_bridge_state"] = state
    return state


def _view3d_context():
    """Return a VIEW_3D area override, or the current context if there is none."""
    window = getattr(bpy.context, "window", None)
    if window and window.screen:
        for area in window.screen.areas:
            if area.type == "VIEW_3D":
                return bpy.context.temp_override(area=area)
    return None


def _run_tool_set(idname):
    override = _view3d_context()
    if override is not None:
        with override:
            return bpy.ops.wm.tool_set_by_id(name=idname)
    return bpy.ops.wm.tool_set_by_id(name=idname)


def _empty_draw(self, context):
    pass


def ui_hide():
    """Hide Blender's own UI, leaving the 3D canvas for the Compose overlay.

    Regions are only hidden; no operator, addon or keymap is unregistered.
    """
    state = _state()
    if not state["hidden"]:
        classes = ("TOPBAR_HT_upper_bar", "TOPBAR_HT_lower_bar", "STATUSBAR_HT_header")
        for name in classes:
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
        _log("pong")
        return

    if command == "ui.hide":
        ui_hide()
        return
    if command == "ui.restore":
        ui_restore()
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

    if command.startswith("tool.set:"):
        idname = command.split(":", 1)[1]
        _run_tool_set(idname)
        _log("tool -> %s" % idname)
        return

    if command.startswith("op:") or command.startswith("python:"):
        code = command.split(":", 1)[1]
        exec(code, {"bpy": bpy})
        _log("exec ok")
        return

    _log("unknown command: %s" % command)
