/* SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright 2001-2002 NaN Holding BV. All rights reserved. */

/** \file
 * \ingroup creator
 */

#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <mutex>
#include <string>
#ifdef __ANDROID__
#  include <sys/system_properties.h>
#endif
#include "creator.h"

char strHomePath[256]={0};
char strConfigPath[256]={0};
static float g_dpi_scale = 1.0f;
static bool g_dpi_initialized = false;

#ifdef WIN32
#  include "utfconv.h"
#  include <windows.h>
#endif

#if defined(WITH_TBB_MALLOC) && defined(_MSC_VER) && defined(NDEBUG)
#  pragma comment(lib, "tbbmalloc_proxy.lib")
#  pragma comment(linker, "/include:__TBB_malloc_proxy")
#endif

#include "MEM_guardedalloc.h"

#include "CLG_log.h"

#include "DNA_genfile.h"

#include "BLI_string.h"
#include "BLI_system.h"
#include "BLI_listbase.h"
#include "BLI_math_vector.h"
#include "DNA_userdef_types.h"
#include "BLI_task.h"
#include "BLI_threads.h"
#include "BLI_utildefines.h"

/* Mostly initialization functions. */
#include "BKE_appdir.h"
#include "BKE_blender.h"
#include "BKE_brush.h"
#include "BKE_cachefile.h"
#include "BKE_callbacks.h"
#include "BKE_context.h"
#include "BKE_cpp_types.h"
#include "BKE_global.h"
#include "BKE_gpencil_modifier_legacy.h"
#include "BKE_idtype.h"
#include "BKE_main.h"
#include "BKE_material.h"
#include "BKE_modifier.h"
#include "BKE_node.h"
#include "BKE_paint.h"
#include "BKE_particle.h"
#include "BKE_shader_fx.h"
#include "BKE_sound.h"
#include "BKE_vfont.h"
#include "BKE_volume.h"
#include "DNA_brush_types.h"
#include "DNA_scene_types.h"
#include "DNA_screen_types.h"
#include "DNA_space_types.h"
#include "DNA_windowmanager_types.h"

#ifndef WITH_PYTHON_MODULE
#  include "BLI_args.h"
#endif

#include "DEG_depsgraph.h"

#include "IMB_imbuf.h" /* For #IMB_init. */

#include "RE_engine.h"
#include "RE_texture.h"

#include "ED_datafiles.h"

#include "WM_api.h"
#include "WM_toolsystem.h"

#include "RNA_define.h"

#include "BPY_extern_run.h"

#ifdef WITH_FREESTYLE
#  include "FRS_freestyle.h"
#endif

#include <signal.h>
#include <ghost/GHOST_C-api.h>

#ifdef __FreeBSD__
#  include <floatingpoint.h>
#endif

#ifdef WITH_BINRELOC
#  include "binreloc.h"
#endif

#ifdef WITH_LIBMV
#  include "libmv-capi.h"
#endif

#ifdef WITH_CYCLES_LOGGING
#  include "CCL_api.h"
#endif

#ifdef WITH_SDL_DYNLOAD
#  include "sdlew.h"
#endif

#ifdef WITH_USD
#  include "usd.h"
#endif

#include "creator_intern.h" /* Own include. */

/* -------------------------------------------------------------------- */
/** \name Local Defines
 * \{ */

/* When building as a Python module, don't use special argument handling
 * so the module loading logic can control the `argv` & `argc`. */
#if defined(WIN32) && !defined(WITH_PYTHON_MODULE)
#  define USE_WIN32_UNICODE_ARGS
#endif

/** \} */

/* -------------------------------------------------------------------- */
/** \name Local Application State
 * \{ */

/* written to by 'creator_args.c' */
struct ApplicationState app_state = {
    .signal =
        {
            .use_crash_handler = true,
            .use_abort_handler = true,
        },
    .exit_code_on_error =
        {
            .python = 0,
        },
};

/** \} */

/* -------------------------------------------------------------------- */
/** \name Application Level Callbacks
 *
 * Initialize callbacks for the modules that need them.
 * \{ */

static void callback_mem_error(const char *errorStr)
{
  fputs(errorStr, stderr);
  fflush(stderr);
}

static void main_callback_setup(void)
{
  /* Error output from the guarded allocation routines. */
  MEM_set_error_callback(callback_mem_error);
}

/* free data on early exit (if Python calls 'sys.exit()' while parsing args for eg). */
struct CreatorAtExitData {
#ifndef WITH_PYTHON_MODULE
  bArgs *ba;
#endif

#ifdef USE_WIN32_UNICODE_ARGS
  const char **argv;
  int argv_num;
#endif

#if defined(WITH_PYTHON_MODULE) && !defined(USE_WIN32_UNICODE_ARGS)
  void *_empty; /* Prevent empty struct error with MSVC. */
#endif
};

static void callback_main_atexit(void *user_data)
{
  struct CreatorAtExitData *app_init_data = (struct CreatorAtExitData *)user_data;

#ifndef WITH_PYTHON_MODULE
  if (app_init_data->ba) {
    BLI_args_destroy(app_init_data->ba);
    app_init_data->ba = NULL;
  }
#else
  UNUSED_VARS(app_init_data); /* May be unused. */
#endif

#ifdef USE_WIN32_UNICODE_ARGS
  if (app_init_data->argv) {
    while (app_init_data->argv_num) {
      free((void *)app_init_data->argv[--app_init_data->argv_num]);
    }
    free((void *)app_init_data->argv);
    app_init_data->argv = NULL;
  }
#else
  UNUSED_VARS(app_init_data); /* May be unused. */
#endif
}

static void callback_clg_fatal(void *fp)
{
  BLI_system_backtrace((FILE *)fp);
}

/** \} */

/* -------------------------------------------------------------------- */
/** \name Blender as a Stand-Alone Python Module (bpy)
 *
 * While not officially supported, this can be useful for Python developers.
 * See: https://wiki.blender.org/wiki/Building_Blender/Other/BlenderAsPyModule
 * \{ */

#ifdef WITH_PYTHON_MODULE

/* Called in `bpy_interface.c` when building as a Python module. */
int main_python_enter(int argc, const char **argv);
void main_python_exit(void);

/* Rename the 'main' function, allowing Python initialization to call it. */
#  define main main_python_enter
static void *evil_C = NULL;

#  ifdef __APPLE__
/* Environment is not available in macOS shared libraries. */
#    include <crt_externs.h>
char **environ = NULL;
#  endif /* __APPLE__ */

#endif /* WITH_PYTHON_MODULE */

/** \} */

/* -------------------------------------------------------------------- */
/** \name GMP Allocator Workaround
 * \{ */

#if (defined(WITH_TBB_MALLOC) && defined(_MSC_VER) && defined(NDEBUG) && defined(WITH_GMP)) || \
    defined(DOXYGEN)
#  include "gmp.h"
#  include "tbb/scalable_allocator.h"

void *gmp_alloc(size_t size)
{
  return scalable_malloc(size);
}
void *gmp_realloc(void *ptr, size_t UNUSED(old_size), size_t new_size)
{
  return scalable_realloc(ptr, new_size);
}

void gmp_free(void *ptr, size_t UNUSED(size))
{
  scalable_free(ptr);
}
/**
 * Use TBB's scalable_allocator on Windows.
 * `TBBmalloc` correctly captures all allocations already,
 * however, GMP is built with MINGW since it doesn't build with MSVC,
 * which TBB has issues hooking into automatically.
 */
void gmp_blender_init_allocator()
{
  mp_set_memory_functions(gmp_alloc, gmp_realloc, gmp_free);
}
#endif

/** \} */

/* -------------------------------------------------------------------- */
/** \name Main Function
 * \{ */

/**
 * Blender's main function responsibilities are:
 * - setup subsystems.
 * - handle arguments.
 * - run #WM_main() event loop,
 *   or exit immediately when running in background-mode.
 */
static bool g_open_shortcut_grid = false;

/* Pending sculpt tool-set request arriving from the UI thread (Kotlin).
 * The JNI thread is NOT the Blender main thread, so we cannot invoke the
 * WM tool-set operator from there. Instead we stash the idname here and drain
 * it on the render thread inside #mainBlenderLoop, where a valid bContext (C)
 * is available. This drives the sculpt wheel overlay (all 42 tools) and does
 * NOT depend on key-event dispatch — which is what avoids the "first entry to
 * sculpt reverts to Draw" race that the keyboard-based arc used to trigger. */
static std::mutex g_tool_mutex;
static std::string g_pending_tool_id;
static bool g_pending_tool_set = false;

/* Pending brush size/strength requests from the wheel's mini menu (UI thread).
 * Mirrors the tool-set drain: stash under mutex, apply on the render thread
 * where bContext (C) and the active sculpt Brush are valid. The statics
 * g_obl_active_brush_* are refreshed every frame in the drain so the overlay
 * can read the CURRENT brush values (synchronously) without waiting. */
static std::mutex g_brush_mutex;
static bool g_brush_req_pending = false;
static int g_brush_req_type = 0; /* 1 = size (px), 2 = strength (0..1), 3 = color (rgb), 4 = extra param */
static int g_brush_req_ival = 0;
static int g_brush_req_param = 0; /* para req_type 4 (campo del brush) */
static float g_brush_req_fval = 0.0f;
static float g_brush_req_rgb[3] = {0.0f, 0.0f, 0.0f};
static int g_obl_active_brush_size = 50;
static float g_obl_active_brush_strength = 1.0f;
static float g_obl_active_brush_color[3] = {0.5f, 0.5f, 0.5f};
/* Params extra del brush (Fase 3). Sincronizados con Kotlin FIELD_*. */
static float g_obl_active_brush_autosmooth = 0.0f;
static float g_obl_active_brush_normal_weight = 0.0f;
static float g_obl_active_brush_crease_pinch = 0.0f;
static float g_obl_active_brush_rake = 0.0f;
static float g_obl_active_brush_height = 0.0f;
static float g_obl_active_brush_tip_roundness = 0.0f;
static float g_obl_active_brush_elastic_preserve = 0.0f;
static float g_obl_active_brush_plane_offset = 0.0f;
/* Params extra tipo enum / toggle (Fase 3). Sincronizados con Kotlin FIELD_*. */
static float g_obl_active_brush_blend = 0.0f;
static float g_obl_active_brush_elastic_deform_type = 0.0f;
static float g_obl_active_brush_pose_deform_type = 0.0f;
static float g_obl_active_brush_pose_origin_type = 0.0f;
static float g_obl_active_brush_cloth_deform_type = 0.0f;
static float g_obl_active_brush_boundary_deform_type = 0.0f;
static int g_obl_active_brush_use_persistent = 0;
static int g_obl_active_brush_use_smooth_stroke = 0;
static int g_obl_active_brush_use_pressure_area_radius = 0;
static int g_obl_active_brush_invert_to_scrape_fill = 0;
/* Batch 1 (Draw/Draw Sharp/Clay): hardness, direction, accumulate, front faces, plane trim. */
static float g_obl_active_brush_hardness = 0.0f;
static float g_obl_active_brush_normal_radius = 0.5f;
static float g_obl_active_brush_direction = 0.0f; /* 0=Add (BRUSH_DIR_IN off), 1=Subtract */
static int g_obl_active_brush_radius_unit = 0; /* 0=View, 1=Scene (BRUSH_LOCK_SIZE) */
static int g_obl_active_brush_accumulate = 0;
static int g_obl_active_brush_front_faces = 0;
static int g_obl_active_brush_plane_trim = 0;
/* Color Filter (builtin.color_filter): type/strength leidos del static de Blender
 * (sculpt_filter_color.cc OBL_color_filter_get) cada frame para el panel. */
static int g_obl_active_cf_type = 0;          /* 0 = FILL */
static float g_obl_active_cf_strength = 1.0f;

extern "C" void blenderSetActiveTool(const char *idname);
/* Definidos en blender .a (sculpt_filter_color.cc): ajustes del Color Filter tool. */
extern "C" void OBL_color_filter_set(int type, float strength);
extern "C" void OBL_color_filter_get(int *type, float *strength);

void oblSetSculptToolRequest(const char *idname) {
    if (!idname || !idname[0]) return;
    std::lock_guard<std::mutex> lock(g_tool_mutex);
    g_pending_tool_id = idname;
    g_pending_tool_set = true;
}

#ifdef __ANDROID__
/* Android perf defaults must be applied AFTER WM_init(), because the device
 * keeps a userpref.blend under /sdcard that can still carry viewport_aa=8 and
 * smooth-wire ON from an older build, silently cancelling the new defaults.
 *
 * A/B switches (read once at startup; restart the app after changing):
 *   setprop debug.blender.perf_override 0   -> leave saved userpref untouched
 *   setprop debug.blender.viewport_aa 8     -> force 8x Workbench TAA/SMAA
 *   setprop debug.blender.smooth_wire 1     -> force overlay smooth-wire ON
 */
static int obl_android_prop_int(const char *name, int fallback)
{
  char value[PROP_VALUE_MAX] = {};
  if (__system_property_get(name, value) > 0 && value[0] != '\0') {
    return atoi(value);
  }
  return fallback;
}

static void obl_android_perf_override(void)
{
  if (!obl_android_prop_int("debug.blender.perf_override", 1)) {
    __android_log_print(ANDROID_LOG_INFO, "OBL.PERF",
                        "Override disabled, using saved userpref: aa=%d smooth_wire=%d",
                        (int)U.viewport_aa,
                        (U.gpu_flag & USER_GPU_FLAG_OVERLAY_SMOOTH_WIRE) != 0);
    return;
  }

  int aa = obl_android_prop_int("debug.blender.viewport_aa", 0);
  if (aa < 0) {
    aa = 0;
  }
  else if (aa > 32) {
    aa = 32;
  }
  U.viewport_aa = (char)aa;

  if (obl_android_prop_int("debug.blender.smooth_wire", 0)) {
    U.gpu_flag |= USER_GPU_FLAG_OVERLAY_SMOOTH_WIRE;
  }
  else {
    U.gpu_flag &= ~USER_GPU_FLAG_OVERLAY_SMOOTH_WIRE;
  }

  __android_log_print(ANDROID_LOG_INFO, "OBL.PERF",
                      "Applied Android perf override: aa=%d smooth_wire=%d gpu_flag=0x%x",
                      (int)U.viewport_aa,
                      (U.gpu_flag & USER_GPU_FLAG_OVERLAY_SMOOTH_WIRE) != 0,
                      (unsigned int)(unsigned char)U.gpu_flag);
}
#endif

void* mainBlenderInitial(int argc,
#ifdef USE_WIN32_UNICODE_ARGS
         const char **UNUSED(argv_c)
#else
         const char **argv
#endif
)
{
  bContext *C;

#ifndef WITH_PYTHON_MODULE
  bArgs *ba;
#endif

#ifdef USE_WIN32_UNICODE_ARGS
  char **argv;
  int argv_num;
#endif

  /* --- end declarations --- */

  /* Ensure we free data on early-exit. */
  struct CreatorAtExitData app_init_data = {NULL};
  BKE_blender_atexit_register(callback_main_atexit, &app_init_data);

  /* Un-buffered `stdout` makes `stdout` and `stderr` better synchronized, and helps
   * when stepping through code in a debugger (prints are immediately
   * visible). However disabling buffering causes lock contention on windows
   * see #76767 for details, since this is a debugging aid, we do not enable
   * the un-buffered behavior for release builds. */
#ifndef NDEBUG
  setvbuf(stdout, NULL, _IONBF, 0);
#endif

#ifdef WIN32
  /* We delay loading of OPENMP so we can set the policy here. */
#  if defined(_MSC_VER)
  _putenv_s("OMP_WAIT_POLICY", "PASSIVE");
#  endif

#  ifdef USE_WIN32_UNICODE_ARGS
  /* Win32 Unicode Arguments. */
  {
    /* NOTE: Can't use `guardedalloc` allocation here, as it's not yet initialized
     * (it depends on the arguments passed in, which is what we're getting here!) */
    wchar_t **argv_16 = CommandLineToArgvW(GetCommandLineW(), &argc);
    argv = malloc(argc * sizeof(char *));
    for (argv_num = 0; argv_num < argc; argv_num++) {
      argv[argv_num] = alloc_utf_8_from_16(argv_16[argv_num], 0);
    }
    LocalFree(argv_16);

    /* free on early-exit */
    app_init_data.argv = argv;
    app_init_data.argv_num = argv_num;
  }
#  endif /* USE_WIN32_UNICODE_ARGS */
#endif   /* WIN32 */

  /* NOTE: Special exception for guarded allocator type switch:
   *       we need to perform switch from lock-free to fully
   *       guarded allocator before any allocation happened.
   */
  {
    int i;
    for (i = 0; i < argc; i++) {
      if (STR_ELEM(argv[i], "-d", "--debug", "--debug-memory", "--debug-all")) {
        printf("Switching to fully guarded memory allocator.\n");
        MEM_use_guarded_allocator();
        break;
      }
      if (STREQ(argv[i], "--")) {
        break;
      }
    }
    MEM_init_memleak_detection();
  }

#ifdef BUILD_DATE
  {
    time_t temp_time = build_commit_timestamp;
    struct tm *tm = gmtime(&temp_time);
    if (LIKELY(tm)) {
      strftime(build_commit_date, sizeof(build_commit_date), "%Y-%m-%d", tm);
      strftime(build_commit_time, sizeof(build_commit_time), "%H:%M", tm);
    }
    else {
      const char *unknown = "date-unknown";
      STRNCPY(build_commit_date, unknown);
      STRNCPY(build_commit_time, unknown);
    }
  }
#endif

#ifdef WITH_SDL_DYNLOAD
  sdlewInit();
#endif

  /* Initialize logging. */
  CLG_init();
  CLG_fatal_fn_set(callback_clg_fatal);
  
   //  修改 设置打印日志
   char logPath[256]={0};
    strcat(logPath,strHomePath);
    strcat(logPath,"log.log");
  FILE*logFile=(FILE*)(fopen(logPath,"w"));
  CLG_output_set(logFile);
  CLG_backtrace_fn_set(callback_clg_fatal);

  C = CTX_create();

#ifdef WITH_PYTHON_MODULE
#  ifdef __APPLE__
  environ = *_NSGetEnviron();
#  endif

#  undef main
  evil_C = C;
#endif

#ifdef WITH_BINRELOC
  br_init(NULL);
#endif

#ifdef WITH_LIBMV
  libmv_initLogging(argv[0]);
#elif defined(WITH_CYCLES_LOGGING)
  CCL_init_logging(argv[0]);
#endif

#if defined(WITH_TBB_MALLOC) && defined(_MSC_VER) && defined(NDEBUG) && defined(WITH_GMP)
  gmp_blender_init_allocator();
#endif

  main_callback_setup();

#if defined(__APPLE__) && !defined(WITH_PYTHON_MODULE) && !defined(WITH_HEADLESS)
  /* Patch to ignore argument finder gives us (PID?) */
  if (argc == 2 && STRPREFIX(argv[1], "-psn_")) {
    extern int GHOST_HACK_getFirstFile(char buf[]);
    static char firstfilebuf[512];

    argc = 1;

    if (GHOST_HACK_getFirstFile(firstfilebuf)) {
      argc = 2;
      argv[1] = firstfilebuf;
    }
  }
#endif

#ifdef __FreeBSD__
  fpsetmask(0);
#endif

  /* Initialize path to executable. */
  BKE_appdir_program_path_init(argv[0]);

  BLI_threadapi_init();

  DNA_sdna_current_init();

  BKE_blender_globals_init(); /* blender.c */

  BKE_cpp_types_init();
  BKE_idtype_init();
  BKE_cachefiles_init();
  BKE_modifier_init();
  BKE_gpencil_modifier_init();
  BKE_shaderfx_init();
  BKE_volumes_init();
  DEG_register_node_types();

  BKE_brush_system_init();
  RE_texture_rng_init();

  BKE_callback_global_init();

  /* First test for background-mode (#Global.background) */
#ifndef WITH_PYTHON_MODULE
  ba = BLI_args_create(argc, (const char **)argv); /* skip binary path */

  /* Ensure we free on early exit. */
  app_init_data.ba = ba;

  main_args_setup(C, ba);

  /* Begin argument parsing, ignore leaks so arguments that call #exit
   * (such as '--version' & '--help') don't report leaks. */
  MEM_use_memleak_detection(false);

  /* Parse environment handling arguments. */
  BLI_args_parse(ba, ARG_PASS_ENVIRONMENT, NULL, NULL);

#else
  /* Using preferences or user startup makes no sense for #WITH_PYTHON_MODULE. */
  G.factory_startup = true;
#endif

  /* After parsing #ARG_PASS_ENVIRONMENT such as `--env-*`,
   * since they impact `BKE_appdir` behavior. */
  BKE_appdir_init();

  /* After parsing number of threads argument. */
  BLI_task_scheduler_init();

  /* Initialize sub-systems that use `BKE_appdir.h`. */
  IMB_init();

#ifdef WITH_USD
  USD_ensure_plugin_path_registered();
#endif

#ifndef WITH_PYTHON_MODULE
  /* First test for background-mode (#Global.background) */
  BLI_args_parse(ba, ARG_PASS_SETTINGS, NULL, NULL);

  main_signal_setup();
#endif

#ifdef WITH_FFMPEG
  /* Keep after #ARG_PASS_SETTINGS since debug flags are checked. */
  IMB_ffmpeg_init();
#endif

  /* After #ARG_PASS_SETTINGS arguments, this is so #WM_main_playanim skips #RNA_init. */
  RNA_init();

  RE_engines_init();
  BKE_node_system_init();
  BKE_particle_init_rng();
  /* End second initialization. */

#if defined(WITH_PYTHON_MODULE) || defined(WITH_HEADLESS)
  /* Python module mode ALWAYS runs in background-mode (for now). */
  G.background = true;
#else
  if (G.background) {
    main_signal_setup_background();
  }
#endif

  /* Background render uses this font too. */
  BKE_vfont_builtin_register(datatoc_bfont_pfb, datatoc_bfont_pfb_size);

  /* Initialize FFMPEG if built in, also needed for background-mode if videos are
   * rendered via FFMPEG. */
  BKE_sound_init_once();

  BKE_materials_init();

#ifndef WITH_PYTHON_MODULE
  if (G.background == 0) {
    BLI_args_parse(ba, ARG_PASS_SETTINGS_GUI, NULL, NULL);
  }
  BLI_args_parse(ba, ARG_PASS_SETTINGS_FORCE, NULL, NULL);
#endif

  WM_init(C, argc, (const char **)argv);

#ifdef __ANDROID__
  /* WM_init() loads userpref.blend: force the per-pixel perf defaults on top
   * of whatever was saved, so an old userpref cannot re-enable the AA passes. */
  obl_android_perf_override();
#endif

  /* Need to be after WM init so that userpref are loaded. */
  RE_engines_init_experimental();

#ifndef WITH_PYTHON
  printf(
      "\n* WARNING * - Blender compiled without Python!\n"
      "this is not intended for typical usage\n\n");
#endif

  CTX_py_init_set(C, true);
  WM_keyconfig_init(C);

#ifdef WITH_FREESTYLE
  /* Initialize Freestyle. */
  FRS_init();
  FRS_set_context(C);
#endif

  /* OK we are ready for it */
#ifndef WITH_PYTHON_MODULE
  /* Handles #ARG_PASS_FINAL. */
  main_args_setup_post(C, ba);
#endif

  /* Explicitly free data allocated for argument parsing:
   * - 'ba'
   * - 'argv' on WIN32.
   */
  callback_main_atexit(&app_init_data);
  BKE_blender_atexit_unregister(callback_main_atexit, &app_init_data);

  /* End argument parsing, allow memory leaks to be printed. */
  MEM_use_memleak_detection(true);

  /* Paranoid, avoid accidental re-use. */
#ifndef WITH_PYTHON_MODULE
  ba = NULL;
  (void)ba;
#endif

#ifdef USE_WIN32_UNICODE_ARGS
  argv = NULL;
  (void)argv;
#endif

#ifndef WITH_PYTHON_MODULE
  if (G.background) {
    /* Using window-manager API in background-mode is a bit odd, but works fine. */
    WM_exit(C, G.is_break ? EXIT_FAILURE : EXIT_SUCCESS);
  }
  else {
    /* Shows the splash as needed. */
    WM_init_splash_on_startup(C);

//    WM_main(C);
      Wm_loop_pre(C);
  }
  /* Neither #WM_exit, #WM_main return, this quiets CLANG's `unreachable-code-return` warning. */
  BLI_assert_unreachable();

#endif /* !WITH_PYTHON_MODULE */

  return C;

} /* End of `int main(...)` function. */

#ifdef WITH_PYTHON_MODULE
void main_python_exit(void)
{
  WM_exit_ex((bContext *)evil_C, true, false);
  evil_C = NULL;
}
#endif

/** \} */

void mainBlenderInitial_reinit(void*pContext){
  blenderWMInitReinit();
  WM_check((bContext*)pContext,true);
}

/* Activa un tool de sculpt por idname (p.ej. "builtin_brush.Clay" o "builtin.box_mask").
 *
 * El hilo de render NO tiene contexto de ventana (CTX_wm_window(C) == NULL entre
 * eventos) -> CTX_wm_workspace(C) es NULL. WM_toolsystem_ref_set_by_id() necesita
 * el workspace para WM_toolsystem_ref_find() (NULL deref si es NULL) y el área
 * para WM_toolsystem_key_from_context() (calcula el mode del toolref). Además el
 * operador Python wm.tool_set_by_id usa context.workspace.tools. Fix: localizar
 * la ventana + área VIEW_3D (preferible en sculpt mode) y setear UN CONTEXTO
 * COMPLETO (window -> scene/workspace/screen + area + region WINDOW) mientras se
 * activa el tool. Se restaura todo al salir. */
static bToolRef *obl_activate_tool_by_id(bContext *C, const char *name)
{
  const Scene *scene = CTX_data_scene(C);
  ViewLayer *view_layer = CTX_data_view_layer(C);
  if (scene == NULL || view_layer == NULL) {
    return NULL;
  }

  Main *bmain = CTX_data_main(C);
  if (bmain == NULL) {
    return NULL;
  }

  wmWindow *prev_win = CTX_wm_window(C);
  ScrArea *prev_area = CTX_wm_area(C);
  ARegion *prev_region = CTX_wm_region(C);

  /* Localizar el área VIEW_3D (preferible en sculpt mode) Y la ventana que la
   * contiene, para poder setear un contexto completo. */
  wmWindow *win_best = NULL;
  ScrArea *best = NULL;
  bool found_sculpt = false;
  LISTBASE_FOREACH (wmWindowManager *, wm, &bmain->wm) {
    LISTBASE_FOREACH (wmWindow *, win, &wm->windows) {
      bScreen *screen = WM_window_get_active_screen(win);
      if (screen == NULL) {
        continue;
      }
      LISTBASE_FOREACH (ScrArea *, area, &screen->areabase) {
        if (area->spacetype != SPACE_VIEW3D) {
          continue;
        }
        if (best == NULL) {
          best = area;
          win_best = win;
        }
        if (WM_toolsystem_mode_from_spacetype(scene, view_layer, area, SPACE_VIEW3D) ==
            CTX_MODE_SCULPT) {
          best = area;
          win_best = win;
          found_sculpt = true;
          break;
        }
      }
      if (found_sculpt) {
        break;
      }
    }
    if (found_sculpt) {
      break;
    }
  }

  bToolRef *tref = NULL;
  if (best != NULL && win_best != NULL) {
    /* CTX_wm_window_set() setea C->data.scene, C->wm.workspace y C->wm.screen
     * (además de limpiar area/region), y borra los miembros del py_context para
     * que bpy.context los re-derive. Setear area + region DESPUÉS de la ventana. */
    CTX_wm_window_set(C, win_best);
    CTX_wm_area_set(C, best);
    if (CTX_wm_workspace(C) != NULL) {
      LISTBASE_FOREACH (ARegion *, region, &best->regionbase) {
        if (region->regiontype == RGN_TYPE_WINDOW) {
          CTX_wm_region_set(C, region);
          break;
        }
      }
      tref = WM_toolsystem_ref_set_by_id(C, name);
    }
    /* Restaurar el contexto para no alterar el main loop (window primero porque
     * setea/limpia area/region; luego area y region). */
    CTX_wm_window_set(C, prev_win);
    CTX_wm_area_set(C, prev_area);
    CTX_wm_region_set(C, prev_region);
  }
  return tref;
}

int mainBlenderLoop(void*pContext) {
  bContext *C = (bContext *)pContext;
  if (!g_dpi_initialized && g_dpi_scale > 1.01f) {
    g_dpi_initialized = true;
    /* Don't override if user has a custom ui_scale saved in preferences */
    float diff_from_default = fabsf(U.ui_scale - 1.0f);
    float diff_from_dpi = fabsf(U.ui_scale - g_dpi_scale);
    if (diff_from_default > 0.1f && diff_from_dpi > 0.1f) {
      __android_log_print(ANDROID_LOG_INFO, "OBL.DPI",
        "Skipping DPI override: user has custom ui_scale=%.2f (dpi would set %.2f)",
        U.ui_scale, g_dpi_scale);
      return 0;
    }
    __android_log_print(ANDROID_LOG_INFO, "OBL.DPI", "Applying DPI scale: %.2f", g_dpi_scale);
    char python_cmd[256];
    SNPRINTF(python_cmd,
      "import bpy\n"
      "bpy.context.preferences.view.ui_scale = %.2f", g_dpi_scale);
    const char *imports[] = {"bpy", NULL};
    BPY_run_string_exec(C, imports, python_cmd);

    __android_log_print(ANDROID_LOG_INFO, "OBL.DPI",
        "DIAG: ui_scale=%.2f dpi=%d pixelsize=%.1f scale_factor=%.4f widget_unit=%d",
        U.ui_scale, U.dpi, U.pixelsize, U.scale_factor, U.widget_unit);
    {
      wmWindow *win = CTX_wm_window(C);
      if (win) {
        __android_log_print(ANDROID_LOG_INFO, "OBL.DPI",
            "DIAG: win_sizex=%d win_sizey=%d", win->sizex, win->sizey);
      }
    }
  }
  if (g_open_shortcut_grid) {
    g_open_shortcut_grid = false;
    const char *imports[] = {"bpy", NULL};
    BPY_run_string_exec(C, imports, "bpy.ops.obl.shortcut_grid('INVOKE_DEFAULT')");
  }
  /* Drain sculpt-tool-set requests queued from the overlay UI thread.
   * Done on the render thread where bContext (C) is valid, so the tool is
   * applied through the real toolsystem (no key-dispatch race) and the mobile
   * "active tool" static is updated for the overlay poll. */
  {
    std::string pending;
    {
      std::lock_guard<std::mutex> lock(g_tool_mutex);
      if (g_pending_tool_set) {
        g_pending_tool_set = false;
        pending = g_pending_tool_id;
      }
    }
    if (!pending.empty()) {
      __android_log_print(ANDROID_LOG_INFO, "OBL.WHEEL",
          "drain tool_set_by_id: %s", pending.c_str());
      bool ctx_ok = false;
      /* Con el área VIEW_3D en el contexto (ver obl_activate_tool_by_id),
       * el camino Python estándar funciona para TODOS los tools (brush y
       * non-brush: builtin.box_mask, builtin.mesh_filter, etc.). */
      if (obl_activate_tool_by_id(C, pending.c_str()) != NULL) {
        ctx_ok = true;
        __android_log_print(ANDROID_LOG_INFO, "OBL.WHEEL",
            "tool_set C-API OK: %s", pending.c_str());
      } else {
        /* Fallback: context-resolved Python operator (same proven path as
         * the shortcut-grid dispatch above). */
        char cmd[512];
        SNPRINTF(cmd,
            "import bpy\n"
            "try:\n"
            "  bpy.ops.wm.tool_set_by_id('INVOKE_DEFAULT', name=\"%s\")\n"
            "except Exception as e:\n"
            "  print('OBL.WHEEL tool_set_by_id FAILED:', e)\n",
            pending.c_str());
        const char *imports[] = {"bpy", NULL};
        BPY_run_string_exec(C, imports, cmd);
      }
      /* Solo actualizar el static si la activación realmente tuvo efecto en el
       * toolsystem (C-API o bpy OK). Si falló, NO mentirle al overlay: el poll
       * seguirá mostrando el brush real. La vía primaria (teclas del keymap /
       * paint.brush_select) actualiza este static desde el hook de Blender. */
      if (ctx_ok) {
        blenderSetActiveTool(pending.c_str());
      }
      __android_log_print(ANDROID_LOG_INFO, "OBL.WHEEL",
          "static updated -> %s | ctx_ok=%d", pending.c_str(), ctx_ok);
    }
  }
  /* Drain brush size/strength requests del mini menu del wheel + refrescar los
   * statics con el brush de sculpt activo (para que el overlay lea los valores
   * actuales al abrir el panel). C-API -> BKE_brush_* maneja el tamaño/alpha
   * unificado y refresca el cursor en el siguiente draw. */
  {
    Scene *scene = CTX_data_scene(C);
    if (scene != NULL && scene->toolsettings != NULL && scene->toolsettings->sculpt != NULL) {
      Brush *br = BKE_paint_brush(&scene->toolsettings->sculpt->paint);
      if (br != NULL) {
        int req_type = 0;
        int req_ival = 0;
        int req_param = 0;
        float req_fval = 0.0f;
        float req_rgb[3] = {0.0f, 0.0f, 0.0f};
        bool req_pending = false;
        {
          std::lock_guard<std::mutex> lock(g_brush_mutex);
          if (g_brush_req_pending) {
            req_pending = true;
            req_type = g_brush_req_type;
            req_ival = g_brush_req_ival;
            req_param = g_brush_req_param;
            req_fval = g_brush_req_fval;
            copy_v3_v3(req_rgb, g_brush_req_rgb);
            g_brush_req_pending = false;
          }
          g_obl_active_brush_size = BKE_brush_size_get(scene, br);
          g_obl_active_brush_strength = BKE_brush_alpha_get(scene, br);
          copy_v3_v3(g_obl_active_brush_color, BKE_brush_color_get(scene, br));
          g_obl_active_brush_autosmooth = br->autosmooth_factor;
          g_obl_active_brush_normal_weight = br->normal_weight;
          g_obl_active_brush_crease_pinch = br->crease_pinch_factor;
          g_obl_active_brush_rake = br->rake_factor;
          g_obl_active_brush_height = br->height;
          g_obl_active_brush_tip_roundness = br->tip_roundness;
          g_obl_active_brush_elastic_preserve = br->elastic_deform_volume_preservation;
          g_obl_active_brush_plane_offset = br->plane_offset;
          g_obl_active_brush_blend = (float)br->blend;
          g_obl_active_brush_elastic_deform_type = (float)br->elastic_deform_type;
          g_obl_active_brush_pose_deform_type = (float)br->pose_deform_type;
          g_obl_active_brush_pose_origin_type = (float)br->pose_origin_type;
          g_obl_active_brush_cloth_deform_type = (float)br->cloth_deform_type;
          g_obl_active_brush_boundary_deform_type = (float)br->boundary_deform_type;
          g_obl_active_brush_use_persistent = (br->flag & BRUSH_PERSISTENT) ? 1 : 0;
          g_obl_active_brush_use_smooth_stroke = (br->flag & BRUSH_SMOOTH_STROKE) ? 1 : 0;
          g_obl_active_brush_use_pressure_area_radius = (br->flag2 & BRUSH_AREA_RADIUS_PRESSURE) ? 1 : 0;
          g_obl_active_brush_invert_to_scrape_fill = (br->flag & BRUSH_INVERT_TO_SCRAPE_FILL) ? 1 : 0;
          g_obl_active_brush_hardness = br->hardness;
          g_obl_active_brush_normal_radius = br->normal_radius_factor;
          g_obl_active_brush_direction = (br->flag & BRUSH_DIR_IN) ? 1.0f : 0.0f;
          g_obl_active_brush_radius_unit = (br->flag & BRUSH_LOCK_SIZE) ? 1.0f : 0.0f;
          g_obl_active_brush_accumulate = (br->flag & BRUSH_ACCUMULATE) ? 1 : 0;
          g_obl_active_brush_front_faces = (br->flag & BRUSH_FRONTFACE) ? 1 : 0;
          g_obl_active_brush_plane_trim = (br->flag & BRUSH_PLANE_TRIM) ? 1 : 0;
          /* Color Filter: reflejar el static de Blender para el panel. */
          {
            int t = 0;
            float s = 1.0f;
            OBL_color_filter_get(&t, &s);
            g_obl_active_cf_type = t;
            g_obl_active_cf_strength = s;
          }
        }
        if (req_pending) {
          if (req_type == 1) {
            BKE_brush_size_set(scene, br, req_ival);
            g_obl_active_brush_size = BKE_brush_size_get(scene, br);
            __android_log_print(ANDROID_LOG_INFO, "OBL.WHEEL",
                "brush size set -> %d", req_ival);
          }
          else if (req_type == 2) {
            BKE_brush_alpha_set(scene, br, req_fval);
            g_obl_active_brush_strength = BKE_brush_alpha_get(scene, br);
            __android_log_print(ANDROID_LOG_INFO, "OBL.WHEEL",
                "brush strength set -> %.3f", req_fval);
          }
          else if (req_type == 3) {
            BKE_brush_color_set(scene, br, req_rgb);
            copy_v3_v3(g_obl_active_brush_color, BKE_brush_color_get(scene, br));
            __android_log_print(ANDROID_LOG_INFO, "OBL.WHEEL",
                "brush color set -> %.3f %.3f %.3f", req_rgb[0], req_rgb[1], req_rgb[2]);
          }
          else if (req_type == 4) {
            switch (req_param) {
              case 1: br->autosmooth_factor = CLAMPIS(req_fval, 0.0f, 1.0f); break;
              case 2: br->normal_weight = CLAMPIS(req_fval, 0.0f, 1.0f); break;
              case 3: br->crease_pinch_factor = CLAMPIS(req_fval, 0.0f, 1.0f); break;
              case 4: br->rake_factor = CLAMPIS(req_fval, 0.0f, 1.0f); break;
              case 5: br->height = CLAMPIS(req_fval, 0.0f, 1.0f); break;
              case 6: br->tip_roundness = CLAMPIS(req_fval, 0.0f, 1.0f); break;
              case 7: br->elastic_deform_volume_preservation = CLAMPIS(req_fval, 0.0f, 1.0f); break;
              case 8: br->plane_offset = CLAMPIS(req_fval, -0.5f, 0.5f); break;
              /* Color Filter: strength (float -10..10) y type (enum). No son campos
                 del Brush -> se persisten en el static de Blender. */
              case 9: OBL_color_filter_set(g_obl_active_cf_type, req_fval); break;
              case 10: br->hardness = CLAMPIS(req_fval, 0.0f, 1.0f); break;
              /* 11 = normal_radius_factor (float 0..2) */
              case 11: br->normal_radius_factor = CLAMPIS(req_fval, 0.0f, 2.0f); break;
              /* enums (field ids 101..106) */
              case 101: br->blend = (short)(int)req_fval; break;
              case 102: br->elastic_deform_type = (int)req_fval; break;
              case 103: br->pose_deform_type = (int)req_fval; break;
              case 104: br->pose_origin_type = (int)req_fval; break;
              case 105: br->cloth_deform_type = (int)req_fval; break;
              case 106: br->boundary_deform_type = (int)req_fval; break;
              case 107: OBL_color_filter_set((int)req_fval, g_obl_active_cf_strength); break;
              /* 108 = direction (bitflag BRUSH_DIR_IN): 0=Add, 1=Subtract */
              case 108:
                if (req_fval >= 0.5f) br->flag |= BRUSH_DIR_IN;
                else br->flag &= ~BRUSH_DIR_IN;
                break;
              /* 109 = radius unit (bitflag BRUSH_LOCK_SIZE): 0=View, 1=Scene */
              case 109:
                if (req_fval >= 0.5f) br->flag |= BRUSH_LOCK_SIZE;
                else br->flag &= ~BRUSH_LOCK_SIZE;
                break;
              /* bools (field ids 201..204) */
              case 201:
                if (req_fval >= 0.5f) br->flag |= BRUSH_PERSISTENT;
                else br->flag &= ~BRUSH_PERSISTENT;
                break;
              case 202:
                if (req_fval >= 0.5f) br->flag2 |= BRUSH_AREA_RADIUS_PRESSURE;
                else br->flag2 &= ~BRUSH_AREA_RADIUS_PRESSURE;
                break;
              case 203:
                if (req_fval >= 0.5f) br->flag |= BRUSH_INVERT_TO_SCRAPE_FILL;
                else br->flag &= ~BRUSH_INVERT_TO_SCRAPE_FILL;
                break;
              case 204:
                if (req_fval >= 0.5f) br->flag |= BRUSH_SMOOTH_STROKE;
                else br->flag &= ~BRUSH_SMOOTH_STROKE;
                break;
              /* 205 = accumulate, 206 = front faces, 207 = plane trim (batch 1) */
              case 205:
                if (req_fval >= 0.5f) br->flag |= BRUSH_ACCUMULATE;
                else br->flag &= ~BRUSH_ACCUMULATE;
                break;
              case 206:
                if (req_fval >= 0.5f) br->flag |= BRUSH_FRONTFACE;
                else br->flag &= ~BRUSH_FRONTFACE;
                break;
              case 207:
                if (req_fval >= 0.5f) br->flag |= BRUSH_PLANE_TRIM;
                else br->flag &= ~BRUSH_PLANE_TRIM;
                break;
            }
            __android_log_print(ANDROID_LOG_INFO, "OBL.WHEEL",
                "brush param[%d] set -> %.3f", req_param, req_fval);
          }
          DEG_id_tag_update(&br->id, ID_RECALC_TRANSFORM | ID_RECALC_GEOMETRY);
          WM_event_add_notifier(C, NC_BRUSH | NA_EDITED, br);
        }
      }
    }
  }
  Wm_loop(C);
    return 0;
}

void initialLib(void *pVoid) {
  setNativeWindow(pVoid);
}

void oblSetValue(int values[],int num){
  if (num >= 2 && values[0] == 9998) {
    g_dpi_scale = values[1] / 100.0f;
    __android_log_print(ANDROID_LOG_INFO, "OBL.DPI", "DPI scale set to %.2f", g_dpi_scale);
    return;
  }
  if (num >= 1 && values[0] == 9999) {
    g_open_shortcut_grid = true;
    return;
  }
  blenderSetValue(values,num);
}

void oblSetValueOn(int values[],int num){
  blenderSetValueOn(values,num);
}

void oblSetValueOff(int values[],int num){
  blenderSetValueOff(values,num);
}

void oblGetCursorPosition(int *x, int *y){
  blenderGetCursorPosition(x, y);
}

bool oblIsTouchDown(void){
  return blenderIsTouchDown();
}

const char *oblGetActiveToolId(void){
  return blenderGetActiveToolId();
}

const char *oblGetActiveWorkspace(void){
  return blenderGetActiveWorkspace();
}

int oblGetActiveMode(void){
  return blenderGetActiveMode();
}

/* Mini menu del sculpt wheel: valores actuales del brush de sculpt activo. */
int oblGetActiveBrushRadius(void){
  std::lock_guard<std::mutex> lock(g_brush_mutex);
  return g_obl_active_brush_size;
}

float oblGetActiveBrushStrength(void){
  std::lock_guard<std::mutex> lock(g_brush_mutex);
  return g_obl_active_brush_strength;
}

void oblGetActiveBrushColor(float rgb[3]){
  std::lock_guard<std::mutex> lock(g_brush_mutex);
  copy_v3_v3(rgb, g_obl_active_brush_color);
}

void oblSetActiveBrushRadius(int px){
  std::lock_guard<std::mutex> lock(g_brush_mutex);
  g_brush_req_type = 1;
  g_brush_req_ival = px;
  g_brush_req_pending = true;
}

void oblSetActiveBrushStrength(float v){
  std::lock_guard<std::mutex> lock(g_brush_mutex);
  g_brush_req_type = 2;
  g_brush_req_fval = v;
  g_brush_req_pending = true;
}

void oblSetActiveBrushColor(float r, float g, float b){
  std::lock_guard<std::mutex> lock(g_brush_mutex);
  g_brush_req_type = 3;
  g_brush_req_rgb[0] = r;
  g_brush_req_rgb[1] = g;
  g_brush_req_rgb[2] = b;
  g_brush_req_pending = true;
}

/* Fase 3: params extra del brush (field ids sincronizados con Kotlin FIELD_*).
 * floats id 1..8, enums id 101..106, toggles/bools id 201..204. */
float oblGetActiveBrushExtra(int field){
  std::lock_guard<std::mutex> lock(g_brush_mutex);
  switch (field) {
    case 1: return g_obl_active_brush_autosmooth;
    case 2: return g_obl_active_brush_normal_weight;
    case 3: return g_obl_active_brush_crease_pinch;
    case 4: return g_obl_active_brush_rake;
    case 5: return g_obl_active_brush_height;
    case 6: return g_obl_active_brush_tip_roundness;
    case 7: return g_obl_active_brush_elastic_preserve;
    case 8: return g_obl_active_brush_plane_offset;
    case 9: return g_obl_active_cf_strength;   /* Color Filter strength */
    case 10: return g_obl_active_brush_hardness;
    case 11: return g_obl_active_brush_normal_radius;
    case 101: return g_obl_active_brush_blend;
    case 102: return g_obl_active_brush_elastic_deform_type;
    case 103: return g_obl_active_brush_pose_deform_type;
    case 104: return g_obl_active_brush_pose_origin_type;
    case 105: return g_obl_active_brush_cloth_deform_type;
    case 106: return g_obl_active_brush_boundary_deform_type;
    case 107: return (float)g_obl_active_cf_type;   /* Color Filter type */
    case 108: return g_obl_active_brush_direction;   /* 0=Add, 1=Subtract */
    case 109: return g_obl_active_brush_radius_unit;  /* 0=View, 1=Scene */
    case 201: return (float)g_obl_active_brush_use_persistent;
    case 202: return (float)g_obl_active_brush_use_pressure_area_radius;
    case 203: return (float)g_obl_active_brush_invert_to_scrape_fill;
    case 204: return (float)g_obl_active_brush_use_smooth_stroke;
    case 205: return (float)g_obl_active_brush_accumulate;
    case 206: return (float)g_obl_active_brush_front_faces;
    case 207: return (float)g_obl_active_brush_plane_trim;
  }
  return 0.0f;
}

/* Fase 3: parametro extra del brush (req_type 4). field ids: floats 1..8,
 * enums 101..106, toggles/bools 201..204 (ids sincronizados con Kotlin FIELD_*). */
void oblSetActiveBrushExtra(int field, float v){
  std::lock_guard<std::mutex> lock(g_brush_mutex);
  g_brush_req_type = 4;
  g_brush_req_param = field;
  g_brush_req_fval = v;
  g_brush_req_pending = true;
}

void inputKey(int p_physical_keycode,
                     int p_unicode, int p_key_label, int p_pressed,
              int p_echo){
  blenderInputKey(p_physical_keycode,p_unicode,p_key_label,p_pressed,p_echo);
}
