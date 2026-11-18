package com.epai.oblender;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.FragmentTransaction;
import android.app.NativeActivity;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Color;
import androidx.lifecycle.ProcessLifecycleOwner;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Xml;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.widget.Button;
import android.widget.ImageView;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.view.WindowManager.LayoutParams;
import android.Manifest;
import android.content.Intent;
import androidx.core.app.ActivityCompat;
import android.os.Environment;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.fragment.app.Fragment;

import com.epai.oblfiles.InstallOBLFiles;
import com.epai.oblender.WindowGLSurfaceView.WindowGLSurfaceViewListener;
import com.epai.oblender.GodotLib;
import com.epai.oblender.input.GodotEditText;
import com.epai.oblender.input.GodotInputHandler;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.content.ClipboardManager;
import android.content.ClipData;

import org.libsdl.app.SDLActivity;

public class OBLNativeActivity extends NativeActivity
        implements WindowGLSurfaceViewListener,GodotRenderView {
    private final String TAG="OBLNativeActivity";
    static {
        System.loadLibrary("blender");
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    Map<Integer, WindowGLSurfaceView> mWindowFragmentMap = new HashMap<>();

    private OblSettingFragment mOblSettingFragment = null;
    private boolean mBooleanLastOblSettingFragmentVisible=false;

    public String getClipboard(boolean selection){
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard.hasPrimaryClip()){
            ClipData clipData = clipboard.getPrimaryClip();
            ClipData.Item item = clipData.getItemAt(0); // 首个数据项
            String text = item.getText().toString();    // 提取文本
            return text;
        }else{
            return "";
        }
    }

    public void putClipboard(String stringText,boolean selection){
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("label", stringText);
        clipboard.setPrimaryClip(clip);
    }

    public void SetValue(int type,int value){
        if (mOblSettingFragment == null) {
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mOblSettingFragment.SetValue(type,value);
            }
        });
    }

    public int GetAsyncKeyState(int type) {
        if (type == 102) {
            return OverlayState.isSculptArcActive() ? 1 : 0;
        }
        if (type == 103) {
            return OverlayState.isSculptArcCollapsed() ? 1 : 0;
        }
        if (mOblSettingFragment == null) {
            return 0;
        }
        return mOblSettingFragment.GetAsyncKeyState(type);
    }

    public void showWindow(int left, int top, int width, int height, int shape_type,String stringInfo) {
        Log.i("OBLNativeActivity", "打开窗体 1" + " " + shape_type + " " + width);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.i("OBLNativeActivity", "打开窗体 2" + " " + shape_type + " " + width);
                if(shape_type==3000){
                    //  打开键盘
                    showKeyboardApp(stringInfo,left,top,width,height);
                }else if (shape_type==4000){
                    //  关闭键盘
                    hideKeyboardApp();
                }
                else if (shape_type == 1001) {
                    //  左上角窗口
                    if (mOblSettingFragment == null) {

                        mOblSettingFragment = new OblSettingFragment(OBLNativeActivity.this);
                        LayoutParams lp = new LayoutParams();

//                        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;//    允许点击窗口外面穿透


                        lp.flags = LayoutParams.FLAG_NOT_FOCUSABLE;
                        lp.flags|= LayoutParams.FLAG_FULLSCREEN;
                        lp.flags|= LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                        lp.flags|= LayoutParams.FLAG_LAYOUT_NO_LIMITS;
                        lp.flags|= LayoutParams.FLAG_LAYOUT_INSET_DECOR;
                        lp.flags|= LayoutParams.FLAG_NOT_TOUCH_MODAL;

                        lp.gravity = Gravity.RIGHT | Gravity.BOTTOM;
                        lp.width = 500;
                        lp.height = 500;
                        lp.x = 20;
                        lp.y = 180;
                        getWindowManager().addView(mOblSettingFragment, lp);

                        mOblSettingFragment.setOBLSettingFragmentListener(new OblSettingFragment.OBLSettingFragmentListener() {
                            @Override
                            public void enterKey(int[] keys) {
                                ArrayList<String> strings = new ArrayList<>();
                                for (int i = 0; i < keys.length; i++) {
                                    strings.add(String.valueOf(keys[i]));
                                }
                                String joined = String.join(",", strings);
                                Log.d("OBL.DIAG", "enterKey sending: " + joined);
                                oblSetValue(joined);
                            }

                            @Override
                            public void enterKeyOff(int[] keys) {
                                ArrayList<String> strings = new ArrayList<>();
                                for (int i = 0; i < keys.length; i++) {
                                    strings.add(String.valueOf(keys[i]));
                                }
                                String joined = String.join(",", strings);
                                Log.d("OBL.DIAG", "enterKeyOff sending: " + joined);
                                oblSetValueOff(joined);
                                /* Latched modifiers: emit the key up so Blender's keymap sees
                                 * the modifier release. */
                                for (int k : keys) {
                                    if (k == 0) GodotLib.key(KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0, false, false);
                                    else if (k == 1) GodotLib.key(KeyEvent.KEYCODE_CTRL_LEFT, 0, 0, false, false);
                                    else if (k == 2) GodotLib.key(KeyEvent.KEYCODE_ALT_LEFT, 0, 0, false, false);
                                }
                            }

                            @Override
                            public void enterKeyOn(int[] keys) {
                                ArrayList<String> strings = new ArrayList<>();
                                for (int i = 0; i < keys.length; i++) {
                                    strings.add(String.valueOf(keys[i]));
                                }
                                String joined = String.join(",", strings);
                                Log.d("OBL.DIAG", "enterKeyOn sending: " + joined);
                                oblSetValueOn(joined);
                                /* Latched modifiers: emit the key down so Blender's keymap sees
                                 * the modifier transition. Ordinals from OblSettingFragment:
                                 * 0=Shift, 1=Ctrl, 2=Alt. */
                                for (int k : keys) {
                                    if (k == 0) GodotLib.key(KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0, true, false);
                                    else if (k == 1) GodotLib.key(KeyEvent.KEYCODE_CTRL_LEFT, 0, 0, true, false);
                                    else if (k == 2) GodotLib.key(KeyEvent.KEYCODE_ALT_LEFT, 0, 0, true, false);
                                }
                            }

                            @Override
                            public void backspace() {
                                Log.d("OBL.DIAG", "backspace via GodotLib.key(KEYCODE_DEL)");
                                GodotLib.key(KeyEvent.KEYCODE_DEL, 0, 0, true, false);
                                GodotLib.key(KeyEvent.KEYCODE_DEL, 0, 0, false, false);
                            }

                            @Override
                            public void enter() {
                                Log.d("OBL.DIAG", "enter via GodotLib.key(KEYCODE_ENTER)");
                                GodotLib.key(KeyEvent.KEYCODE_ENTER, 0, 0, true, false);
                                GodotLib.key(KeyEvent.KEYCODE_ENTER, 0, 0, false, false);
                            }

                            @Override
                            public void closeFragment() {
                                mOblSettingFragment.setVisibility(View.INVISIBLE);
                            }
                        });
                        hideToolbar();
                    }
                    if (mOblSettingFragment.getVisibility() != View.VISIBLE) {
                        mOblSettingFragment.setVisibility(View.VISIBLE);
                        hideToolbar();
                    }
                } else {
                    if (width >= 0) {
                        if (mWindowFragmentMap.containsKey(shape_type)) {
                            mWindowFragmentMap.get(shape_type).setVisibility(View.VISIBLE);
                        } else {
                            Log.i("OBLNativeActivity", "打开窗体 3" + " " + shape_type + " " + width);
                            WindowGLSurfaceView windowFragment = new WindowGLSurfaceView(OBLNativeActivity.this.getBaseContext());
                            Log.i("OBLNativeActivity", "打开窗体 3 1" + " " + shape_type + " " + width);
                            windowFragment.setListener(OBLNativeActivity.this);
                            Log.i("OBLNativeActivity", "打开窗体 3 2" + " " + shape_type + " " + width + windowFragment);
                            mWindowFragmentMap.put(shape_type, windowFragment);
                            Log.i("OBLNativeActivity", "打开窗体 3 3" + " " + shape_type + " " + width);
                            LayoutParams lp = new LayoutParams();
                            Log.i("OBLNativeActivity", "打开窗体 3 4" + " " + shape_type + " " + width);
                            lp.type = LayoutParams.TYPE_APPLICATION_PANEL;
                            Log.i("OBLNativeActivity", "打开窗体 3 5" + " " + shape_type + " " + width);
                            lp.flags = LayoutParams.FLAG_NOT_FOCUSABLE |
                                    LayoutParams.FLAG_NOT_TOUCHABLE |
                                    LayoutParams.FLAG_NOT_TOUCH_MODAL |
                                    LayoutParams.FLAG_ALT_FOCUSABLE_IM;
                            //   重要修改 ，将窗体修改成这种 FLAG_ALT_FOCUSABLE_IM ，才能在子窗体中弹出键盘，并且键盘在子窗体之上
                            Log.i("OBLNativeActivity", "打开窗体 3 6" + " " + shape_type + " " + width);
                            getWindowManager().addView(windowFragment, lp);
                            Log.i("OBLNativeActivity", "打开窗体 4" + " " + shape_type + " " + width);
                        }
                    } else {
                        Log.i("OBLNativeActivity", "打开窗体 5" + " " + shape_type + " " + width);
//                    WindowWindow windowFragment = mWindowFragmentMap.get(shape_type);
//                    windowFragment.getDialog().show();
//                    mWindowFragmentMap.get(shape_type).setVisibility(View.VISIBLE);
                        Log.i("OBLNativeActivity", "打开窗体 6" + " " + shape_type + " " + width);
                        if (mWindowFragmentMap.containsKey(shape_type)) {
                            mWindowFragmentMap.get(shape_type).setVisibility(View.INVISIBLE);
                            Log.i("OBLNativeActivity", "打开窗体 7" + " " + shape_type + " " + width);
//                        int lastIndex=mWindowFragmentMap.size()-1;
                            Log.i("OBLNativeActivity", "打开窗体 8" + " " + shape_type + " " + width);
//                        if (mWindowFragmentMap.get(lastIndex).getVisibility()==View.VISIBLE)
                            {
                                Log.i("OBLNativeActivity", "打开窗体 9" + " " + shape_type + " " + width);
//                            WindowGLSurfaceView windowGLSurfaceView=mWindowFragmentMap.get(lastIndex);
//                            WindowManager.LayoutParams params=(WindowManager.LayoutParams)windowGLSurfaceView.getLayoutParams();
//                            params.type=LayoutParams.TYPE_APPLICATION;
//                            getWindowManager().updateViewLayout(windowGLSurfaceView,params);
//                            getWindowManager().removeView(windowGLSurfaceView);
//                            mWindowFragmentMap.remove(lastIndex);
//                            windowGLSurfaceView=null;
                                Log.i("OBLNativeActivity", "打开窗体 10" + " " + shape_type + " " + width);
                            }
                        }
                    }
                }
            }
        });
    }

    public void SetCursorPosition(long x,long y){
        Log.i(TAG,"SetCursorPosition "+x+" "+y);
    }

    @Override
    public void updateSurface(SurfaceHolder holder) {
        updateSurface(holder.getSurface());
    }

    @Override
    public void updateSurfaceDestroyed(SurfaceHolder holder) {
        updateSurfaceDestroyed(holder.getSurface());
    }

    // NativeActivity delivers the MAIN render surface (app->window, where Blender draws
    // the viewport) through SurfaceHolder.Callback. The precision lens PixelCopy needs
    // THIS surface, not the auxiliary WindowGLSurfaceView ones used for popups.
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        super.surfaceCreated(holder);
        OverlayState.renderSurface = holder.getSurface();
        Log.d("OBL", "surfaceCreated(main): renderSurface set " + holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        super.surfaceChanged(holder, format, width, height);
        OverlayState.renderSurface = holder.getSurface();
        Log.d("OBL", "surfaceChanged(main): renderSurface set " + holder.getSurface());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        super.surfaceDestroyed(holder);
        OverlayState.renderSurface = null;
        Log.d("OBL", "surfaceDestroyed(main): renderSurface cleared");
    }

    @Override
    public void hideWindow(WindowWindow windowWindow) {
        windowWindow.setVisibility(View.INVISIBLE);
    }

    public native String stringFromJNI();

    public native void initial(String stringPath,String stringPython);

    public native void updateSurface(Surface surface);

    public native void updateSurfaceDestroyed(Surface surface);

    /** Static reference for routeClickEvent to call instance native methods */
    private static OBLNativeActivity sActivity = null;

    public native void oblSetValue(String stringValue);

    public native void oblSetValueOn(String stringValue);

    public native void oblSetValueOff(String stringValue);

    public native int[] getCursorPosition();

    public native boolean getTouchDown();

    public native String getActiveToolId();

    public native String getActiveWorkspace();

    public native int getActiveMode();

    /** Sculpt wheel mini menu: current active brush size (px) / strength (0..1). */
    public native int getActiveBrushRadius();

    public native float getActiveBrushStrength();

    /** Sculpt wheel mini menu: current active brush color as linear rgb floats (0..1). */
    public native float[] getActiveBrushColor();

    /** Sculpt wheel mini menu: set the active brush size/strength (drained on render thread). */
    public native void setActiveBrushRadius(int px);

    public native void setActiveBrushStrength(float strength);

    /** Sculpt wheel mini menu: set the active brush color (linear rgb 0..1, drained on render thread). */
    public native void setActiveBrushColor(float r, float g, float b);

    /** Sculpt wheel mini menu (Fase 3): get/set an extra brush param (field id, valor 0..1 o rango propio). */
    public native float getActiveBrushExtra(int field);

    public native void setActiveBrushExtra(int field, float value);

    /** Sculpt wheel overlay: set the active sculpt tool directly (bypasses key dispatch). */
    public native void oblSetToolById(String idname);

    /** Generic UI bridge: one entry point for every Compose/UI action. */
    public native void oblDispatch(String command);

    /** Static accessor so Compose can send a command without holding the Activity. */
    public static void oblDispatchStatic(String command) {
        if (sActivity != null && command != null) sActivity.oblDispatch(command);
    }

    /** Static accessor for the overlay (Kotlin) to query the real GHOST cursor position. */
    public static int[] getCursorPositionStatic() {
        if (sActivity == null) return new int[] { -1, -1 };
        return sActivity.getCursorPosition();
    }

    /** Static accessor: true while a finger/stylus is pressed on the screen (precision lens). */
    public static boolean getTouchDownStatic() {
        if (sActivity == null) return false;
        return sActivity.getTouchDown();
    }

     /** Static accessor: idname of the active tool (e.g. "builtin_brush.Clay"), empty if none. */
     public static String getActiveToolIdStatic() {
         if (sActivity == null) return "";
         return sActivity.getActiveToolId();
     }

     /** Static accessor: set the active sculpt tool by idname (e.g. "builtin_brush.Clay"). */
     public static void oblSetToolByIdStatic(String idname) {
         if (sActivity != null) sActivity.oblSetToolById(idname);
     }

    /** Static accessor: name of the active workspace (e.g. "Sculpting", "2D Animation"). */
    public static String getActiveWorkspaceStatic() {
        if (sActivity == null) return "";
        return sActivity.getActiveWorkspace();
    }

    /** Static accessor: active object mode (eContextObjectMode, e.g. CTX_MODE_SCULPT = 9). */
    public static int getActiveModeStatic() {
        if (sActivity == null) return -1;
        return sActivity.getActiveMode();
    }

    /** Static accessor: active sculpt brush size (px). */
    public static int getActiveBrushRadiusStatic() {
        if (sActivity == null) return 50;
        return sActivity.getActiveBrushRadius();
    }

    /** Static accessor: active sculpt brush strength (0..1). */
    public static float getActiveBrushStrengthStatic() {
        if (sActivity == null) return 1.0f;
        return sActivity.getActiveBrushStrength();
    }

    /** Static accessor: set active sculpt brush size (px). */
    public static void setActiveBrushRadiusStatic(int px) {
        if (sActivity != null) sActivity.setActiveBrushRadius(px);
    }

    /** Static accessor: set active sculpt brush strength (0..1). */
    public static void setActiveBrushStrengthStatic(float strength) {
        if (sActivity != null) sActivity.setActiveBrushStrength(strength);
    }

    /** Static accessor: active sculpt brush color as linear rgb floats (0..1). */
    public static float[] getActiveBrushColorStatic() {
        if (sActivity == null) return new float[] { 0.5f, 0.5f, 0.5f };
        return sActivity.getActiveBrushColor();
    }

    /** Static accessor: set active sculpt brush color (linear rgb 0..1). */
    public static void setActiveBrushColorStatic(float r, float g, float b) {
        if (sActivity != null) sActivity.setActiveBrushColor(r, g, b);
    }

    /** Static accessor: lee un param extra del brush activo (Fase 3). */
    public static float getActiveBrushExtraStatic(int field) {
        if (sActivity == null) return 0.0f;
        return sActivity.getActiveBrushExtra(field);
    }

    /** Static accessor: escribe un param extra del brush activo (Fase 3). */
    public static void setActiveBrushExtraStatic(int field, float value) {
        if (sActivity != null) sActivity.setActiveBrushExtra(field, value);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        sActivity = this;
        setContentView(R.layout.activity_main);

        hideToolbar();

        initialEditText();
        initialKeyboardToggle();
        initialUndoRedoButtons();

        // Initialize cursor mode manager
        CursorModeManager.init(this);

        // Example of a call to a native method

        AssetManager assetManager=getAssets();
        String strHomePath = getExternalFilesDir("obl").getAbsolutePath()+ File.separator;
        String strConfigPath=getFilesDir().getAbsolutePath()+File.separator;
        Intent intent=getIntent();
        if (intent!=null){
            strHomePath=intent.getStringExtra("HomePath");
            strConfigPath=intent.getStringExtra("ConfigPath");
        }
        // Detect DPI and pass scale factor to native before Blender starts
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        int scaleFixed = Math.round(metrics.density * 100);
        Log.d("OBL.DPI", "density=" + metrics.density + " densityDpi=" + metrics.densityDpi + " scaleFixed=" + scaleFixed);
        oblSetValue("9998," + scaleFixed);

        initial(strHomePath,strConfigPath);

        //  监听键盘弹出和隐藏
        SoftKeyBoardListener.setListener(this, new SoftKeyBoardListener.OnSoftKeyBoardChangeListener() {
            @Override
            public void keyBoardShow(int height) {
                if (mOblSettingFragment!=null){
                    mBooleanLastOblSettingFragmentVisible=mOblSettingFragment.getVisibility()==View.VISIBLE;
                    mOblSettingFragment.setVisibility(View.INVISIBLE);
                }else{
                    mBooleanLastOblSettingFragmentVisible=false;
                }
                // Also hide runtime buttons when keyboard shows
                // (keep the sculpt overlay so the Nomad card/chip stays visible
                //  while editing a value with the keyboard open).
                OBLControllerOverlayKt.hideRuntimeButtonsKeepSculpt();
                ScreenUtils.fullScreen(getWindow());
            }

            @Override
            public void keyBoardHide(int height) {
                ScreenUtils.fullScreen(getWindow());
                if (mOblSettingFragment!=null){
                    if (mBooleanLastOblSettingFragmentVisible){
                        mOblSettingFragment.setVisibility(View.VISIBLE);
                    }
                }
            }
        });

        super.onCreate(savedInstanceState);

        // Sculpt arc overlay is independent of the control editor: show it on
        // app start so it is available even without a control_layout.json.
        OBLControllerOverlayKt.showSculptArcOverlay(OBLNativeActivity.this, ProcessLifecycleOwner.get());
    }

    /** Called from JNI (oblHideLoadingOverlay) after mainBlenderInitial completes. */
    public void hideLoadingOverlay() {
        runOnUiThread(() -> {
            View overlay = findViewById(R.id.loading_overlay);
            if (overlay != null) {
                overlay.setVisibility(View.GONE);
            }
        });
    }

    private void hideToolbar() {
        ScreenUtils.fullScreen(getWindow());
    }

    @Override
    protected void onResume() {
        //Hide toolbar
        hideToolbar();

        super.onResume();
        //  恢复运行时资源
    }

    @Override
    protected void onPause() {
        //  保存运行时资源
        super.onPause();
    }


    @Override
    protected void onStart() {
        super.onStart();
    }

    private GodotEditText mGodotEditText=null ;
    private GodotInputHandler inputHandler=null;

    private void initialEditText(){
        if (mGodotEditText==null){
            inputHandler = new GodotInputHandler(this);

            mGodotEditText=new GodotEditText(OBLNativeActivity.this);

            ViewGroup.LayoutParams layoutParams=new LayoutParams();
            layoutParams.height=200;
            layoutParams.width=500;
            mGodotEditText.setLayoutParams(layoutParams);
            mGodotEditText.setBackgroundColor(Color.TRANSPARENT);
            mGodotEditText.setTextColor(Color.valueOf(0.0f,0.0f,1.0f).toArgb());

            LayoutParams lp = new LayoutParams();
            lp.gravity = Gravity.LEFT | Gravity.BOTTOM;
            lp.width = 500;
            lp.height = 200;
            lp.x = 80;
            lp.y = 80;
            getWindowManager().addView(mGodotEditText,lp);

            mGodotEditText.setView(this);

            mGodotEditText.setVisibility(View.GONE);
        }
    }

    private void initialKeyboardToggle() {
        int btnSz = 72;
        int pad = 6;
        int gap = 4;

        /* ⌨ button — opens keyboard/numpad overlay */
        Button toggleBtn = new Button(OBLNativeActivity.this);
        toggleBtn.setText("⌨");
        toggleBtn.setTextSize(16);
        toggleBtn.setTextColor(Color.WHITE);
        toggleBtn.setAlpha(0.5f);
        toggleBtn.setBackgroundResource(android.R.color.transparent);

        LayoutParams lp = new LayoutParams();
        lp.gravity = Gravity.RIGHT | Gravity.BOTTOM;
        lp.width = btnSz;
        lp.height = btnSz;
        lp.x = pad;
        lp.y = 6 + btnSz + gap;
        lp.flags = LayoutParams.FLAG_NOT_FOCUSABLE | LayoutParams.FLAG_NOT_TOUCH_MODAL;

        toggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mOblSettingFragment == null) {
                    showWindow(0, 0, 0, 0, 1001, "");
                } else {
                    if (mOblSettingFragment.getVisibility() == View.VISIBLE) {
                        mOblSettingFragment.setVisibility(View.INVISIBLE);
                    } else {
                        mOblSettingFragment.setVisibility(View.VISIBLE);
                    }
                }
            }
        });

        getWindowManager().addView(toggleBtn, lp);

        /* Floating ball — opens/closes ControlEditor */
        ImageView ballBtn = new ImageView(OBLNativeActivity.this);
        ballBtn.setImageResource(R.drawable.ic_menu);
        ballBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        ballBtn.setPadding(14, 14, 14, 14);
        ballBtn.setBackgroundColor(Color.argb(100, 64, 64, 64));

        LayoutParams lp2 = new LayoutParams();
        lp2.gravity = Gravity.RIGHT | Gravity.BOTTOM;
        lp2.width = btnSz;
        lp2.height = btnSz;
        lp2.x = pad;
        lp2.y = 6;
        lp2.flags = LayoutParams.FLAG_NOT_FOCUSABLE | LayoutParams.FLAG_NOT_TOUCH_MODAL;

        ballBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean isEditMode = OBLControllerOverlayKt.getControlOverlayEditMode();
                if (isEditMode) {
                    // Exit editor → enter runtime
                    // hideEditor + showRuntimeButtons is handled by the exit lambda in Compose,
                    // but if we toggle from the ball button directly:
                    OBLControllerOverlayKt.setControlOverlayEditMode(false);
                    OBLControllerOverlayKt.hideRuntimeButtons(); // clean stale
                    OBLControllerOverlayKt.hideEditor(OBLNativeActivity.this);
                    OBLControllerOverlayKt.showRuntimeButtons(OBLNativeActivity.this, ProcessLifecycleOwner.get());
                } else {
                    // Exit runtime → enter editor
                    OBLControllerOverlayKt.hideRuntimeButtons();
                    OBLControllerOverlayKt.showEditor(OBLNativeActivity.this);
                }
            }
        });

        getWindowManager().addView(ballBtn, lp2);
    }

    private void initialUndoRedoButtons() {
        /* All functionality moved to the shortcut grid — no overlay buttons needed. */
    }

    /**
     * Called from OBLControllerOverlay.kt runtime button onClick.
     * Dispatches a single ClickEvent. Modifiers act as sticky toggles
     * (for standalone modifier buttons).
     */
    public static void routeClickEvent(Object clickEvent) {
        if (sActivity == null) return;
        try {
            java.lang.reflect.Method getType = clickEvent.getClass().getMethod("getType");
            java.lang.reflect.Method getKey = clickEvent.getClass().getMethod("getKey");
            Object type = getType.invoke(clickEvent);
            String key = (String) getKey.invoke(clickEvent);
            if (type == null || key == null) return;

            switch (type.toString()) {
                case "Key":
                    int mod = modifierOrdinal(key);
                    if (mod >= 0) {
                        // Sticky toggle for standalone modifier button
                        if (heldMods.contains(mod)) {
                            heldMods.remove(mod);
                            sActivity.oblSetValueOff(String.valueOf(mod));
                        } else {
                            heldMods.add(mod);
                            sActivity.oblSetValueOn(String.valueOf(mod));
                        }
                    } else {
                        int ord = glfwToOrdinal(key);
                        if (ord >= 0) sActivity.oblSetValue(String.valueOf(ord));
                    }
                    break;
                case "LauncherEvent":
                    sActivity.handleLauncherEvent(key);
                    break;
            }
        } catch (Exception ignored) {}
    }

    /**
     * Called from OBLControllerOverlay.kt for batch dispatch.
     * @param clickEvents list of ClickEvents from the button
     * @param pressed true = press modifiers / send keys; false = release modifiers only
     */
    /** Static helper for callbacks from Compose overlays (VirtualPointerOverlay). */
    public static void oblSetValueStatic(String value) {
        if (sActivity != null) sActivity.oblSetValue(value);
    }

    public static void oblSetValueOnStatic(String value) {
        if (sActivity != null) sActivity.oblSetValueOn(value);
    }

    public static void oblSetValueOffStatic(String value) {
        if (sActivity != null) sActivity.oblSetValueOff(value);
    }

    public static void routeClickEvents(List<?> clickEvents, boolean pressed) {
        if (sActivity == null || clickEvents.isEmpty()) return;
        try {
            for (Object clickEvent : clickEvents) {
                java.lang.reflect.Method getType = clickEvent.getClass().getMethod("getType");
                java.lang.reflect.Method getKey = clickEvent.getClass().getMethod("getKey");
                Object type = getType.invoke(clickEvent);
                String key = (String) getKey.invoke(clickEvent);
                if (type == null || key == null) continue;

                switch (type.toString()) {
                    case "Key": {
                        int mod = modifierOrdinal(key);
                        if (mod >= 0) {
                            if (pressed) sActivity.oblSetValueOn(String.valueOf(mod));
                            else sActivity.oblSetValueOff(String.valueOf(mod));
                        } else if (pressed) {
                            int ord = glfwToOrdinal(key);
                            if (ord >= 0) sActivity.oblSetValue(String.valueOf(ord));
                        }
                        break;
                    }
                    case "LauncherEvent":
                        // Launcher events are always press+release (one-shot even in toggle)
                        sActivity.handleLauncherEvent(key);
                        break;
                }
            }
        } catch (Exception ignored) {}
    }

    // ── Modifier toggle state (for sticky standalone buttons) ──

    private static final Set<Integer> heldMods = new HashSet<>();
    private static final int MOD_SHIFT = 0;
    private static final int MOD_CTRL  = 1;
    private static final int MOD_ALT   = 2;

    /** Return modifier ordinal (0,1,2) or -1 if not a modifier */
    private static int modifierOrdinal(String glfwKey) {
        switch (glfwKey) {
            case "GLFW_KEY_LEFT_SHIFT":
            case "GLFW_KEY_RIGHT_SHIFT":   return MOD_SHIFT;
            case "GLFW_KEY_LEFT_CONTROL":
            case "GLFW_KEY_RIGHT_CONTROL":  return MOD_CTRL;
            case "GLFW_KEY_LEFT_ALT":
            case "GLFW_KEY_RIGHT_ALT":      return MOD_ALT;
            default: return -1;
        }
    }

    /** Map GLFW key name → OBLButtonID ordinal. Returns -1 if unknown. */
    private static int glfwToOrdinal(String key) {
        switch (key) {
            // ── Letters ──
            case "GLFW_KEY_A": return 25; case "GLFW_KEY_B": return 52;
            case "GLFW_KEY_C": return 29; case "GLFW_KEY_D": return 48;
            case "GLFW_KEY_E": return 51; case "GLFW_KEY_F": return 53;
            case "GLFW_KEY_G": return 45; case "GLFW_KEY_H": return 46;
            case "GLFW_KEY_I": return 23; case "GLFW_KEY_J": return 49;
            case "GLFW_KEY_K": return 71; case "GLFW_KEY_L": return 72;
            case "GLFW_KEY_M": return 31; case "GLFW_KEY_N": return 30;
            case "GLFW_KEY_O": return 24; case "GLFW_KEY_P": return 73;
            case "GLFW_KEY_Q": return 20; case "GLFW_KEY_R": return 43;
            case "GLFW_KEY_S": return 44; case "GLFW_KEY_T": return 22;
            case "GLFW_KEY_U": return 74; case "GLFW_KEY_V": return 50;
            case "GLFW_KEY_W": return 21; case "GLFW_KEY_X": return 27;
            case "GLFW_KEY_Y": return 28; case "GLFW_KEY_Z": return 26;

            // ── Top-row numbers ──
            case "GLFW_KEY_0": return 66; case "GLFW_KEY_1": return 15;
            case "GLFW_KEY_2": return 16; case "GLFW_KEY_3": return 17;
            case "GLFW_KEY_4": return 18; case "GLFW_KEY_5": return 19;
            case "GLFW_KEY_6": return 67; case "GLFW_KEY_7": return 68;
            case "GLFW_KEY_8": return 69; case "GLFW_KEY_9": return 70;

            // ── Navigation & editing ──
            case "GLFW_KEY_ESCAPE":      return 7;
            case "GLFW_KEY_ENTER":
            case "GLFW_KEY_KP_ENTER":    return 13;
            case "GLFW_KEY_TAB":         return 41;
            case "GLFW_KEY_SPACE":       return 34;
            case "GLFW_KEY_BACKSPACE":   return 42;
            case "GLFW_KEY_DELETE":      return 42;  // forward delete → backspace ordinal
            case "GLFW_KEY_INSERT":      return 89;
            case "GLFW_KEY_HOME":        return 12;
            case "GLFW_KEY_END":         return 90;
            case "GLFW_KEY_PAGE_UP":     return 35;
            case "GLFW_KEY_PAGE_DOWN":   return 36;
            case "GLFW_KEY_UP":          return 37;
            case "GLFW_KEY_DOWN":        return 38;
            case "GLFW_KEY_LEFT":        return 39;
            case "GLFW_KEY_RIGHT":       return 40;

            // ── Function keys ──
            case "GLFW_KEY_F1":  return 75;  case "GLFW_KEY_F2":  return 8;
            case "GLFW_KEY_F3":  return 9;   case "GLFW_KEY_F4":  return 10;
            case "GLFW_KEY_F5":  return 76;  case "GLFW_KEY_F6":  return 77;
            case "GLFW_KEY_F7":  return 78;  case "GLFW_KEY_F8":  return 79;
            case "GLFW_KEY_F9":  return 80;  case "GLFW_KEY_F10": return 81;
            case "GLFW_KEY_F11": return 82;  case "GLFW_KEY_F12": return 11;

            // ── Numpad ──
            case "GLFW_KEY_KP_0":   return 54;  case "GLFW_KEY_KP_1": return 55;
            case "GLFW_KEY_KP_2":   return 56;  case "GLFW_KEY_KP_3": return 57;
            case "GLFW_KEY_KP_4":   return 58;  case "GLFW_KEY_KP_5": return 59;
            case "GLFW_KEY_KP_6":   return 90;
            case "GLFW_KEY_KP_7":   return 91;  case "GLFW_KEY_KP_8": return 92;
            case "GLFW_KEY_KP_9":   return 93;
            case "GLFW_KEY_KP_ADD":      return 60;
            case "GLFW_KEY_KP_SUBTRACT": return 61;
            case "GLFW_KEY_KP_MULTIPLY": return 62;
            case "GLFW_KEY_KP_DIVIDE":   return 63;
            case "GLFW_KEY_KP_DECIMAL":  return 64;
            case "GLFW_KEY_KP_EQUAL":    return 94;

            // ── Punctuation ──
            case "GLFW_KEY_APOSTROPHE":   return 88;
            case "GLFW_KEY_COMMA":        return 32;
            case "GLFW_KEY_MINUS":        return 83;
            case "GLFW_KEY_PERIOD":       return 33;
            case "GLFW_KEY_SLASH":        return 47;
            case "GLFW_KEY_SEMICOLON":    return 87;
            case "GLFW_KEY_EQUAL":        return 84;
            case "GLFW_KEY_LEFT_BRACKET":  return 85;
            case "GLFW_KEY_RIGHT_BRACKET": return 86;
            case "GLFW_KEY_BACKSLASH":     return 47;
            case "GLFW_KEY_GRAVE_ACCENT":  return 14;

            // ── Lock keys ──
            case "GLFW_KEY_CAPS_LOCK":    return 95;
            case "GLFW_KEY_SCROLL_LOCK":  return 96;
            case "GLFW_KEY_NUM_LOCK":     return 97;
            case "GLFW_KEY_PRINT_SCREEN": return 98;
            case "GLFW_KEY_PAUSE":        return 99;

            // ── Super/Meta/Menu → fallback to Alt ──
            case "GLFW_KEY_LEFT_SUPER":
            case "GLFW_KEY_RIGHT_SUPER":
            case "GLFW_KEY_MENU":         return MOD_ALT;

            default: return -1;
        }
    }

    /** Handle LauncherEvent type click events */
    private void handleLauncherEvent(String key) {
        // When virtual cursor is active, reposition cursor before mouse clicks
        if (OverlayState.virtualCursorActive) {
            switch (key) {
                case "GLFW_MOUSE_BUTTON_LEFT":
                case "GLFW_MOUSE_BUTTON_RIGHT":
                case "GLFW_MOUSE_BUTTON_MIDDLE":
                    oblSetValue("10010," + OverlayState.cursorX + "," + OverlayState.cursorY);
                    break;
            }
        }
        switch (key) {
            case "GLFW_MOUSE_BUTTON_LEFT":   oblSetValue("10000,"); break;
            case "GLFW_MOUSE_BUTTON_RIGHT":  oblSetValue("10001,"); break;
            case "GLFW_MOUSE_BUTTON_MIDDLE": oblSetValue("10006,"); break;
            case "launcher.event.scroll_up":   oblSetValue("10002,"); break;
            case "launcher.event.scroll_down": oblSetValue("10003,"); break;
        }
        Log.d("OBL", "launcher=" + key);
    }

    public void showKeyboardApp(String p_existing_text, int p_type, int p_max_input_length, int p_cursor_start, int p_cursor_end) {
        Log.i(TAG,"showKeyboardApp 1 "+p_existing_text+" "+p_type+" "+p_max_input_length+" "+p_cursor_start+" "+p_cursor_start);
        if (mGodotEditText != null) {
            mGodotEditText.showKeyboard(p_existing_text, GodotEditText.VirtualKeyboardType.values()[p_type], p_max_input_length, 0, p_existing_text.length());
        }
        Log.i(TAG,"showKeyboardApp 2");

        InputMethodManager inputMgr = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        inputMgr.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
    }

    public void hideKeyboardApp() {
        if (mGodotEditText != null)
            Log.i(TAG,"showKeyboardApp 3");
            mGodotEditText.hideKeyboard();
        Log.i(TAG,"showKeyboardApp 4");
    }

    @Override
    public View getView() {
        return getWindow().getDecorView();
    }

    @Override
    public void initInputDevices() {

    }

    @Override
    public void startRenderer() {

    }

    @Override
    public void onActivityPaused() {

    }

    @Override
    public void onActivityStopped() {

    }

    @Override
    public void onActivityResumed() {

    }

    @Override
    public void onActivityStarted() {

    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (inputHandler != null && inputHandler.onTouchEvent(event)) {
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public GodotInputHandler getInputHandler() {
        return inputHandler;
    }

//    @Override
//    public void configurePointerIcon(int pointerType, String imagePath, float hotSpotX, float hotSpotY) {
//
//    }
//
//    @Override
//    public void setPointerIcon(int pointerType) {
//
//    }
//
//    @Override
//    public boolean canCapturePointer() {
//        return false;
//    }
}
