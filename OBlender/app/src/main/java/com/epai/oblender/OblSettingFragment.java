package com.epai.oblender;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Environment;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

public class OblSettingFragment extends View {
    private final String TAG = "OBL_Grid";
    private static final String PREFS_CUSTOM = "obl_custom";
    private static final String KB_DIR_NAME = "keyboards";

    private enum Tab { KEYBOARD, NUMPAD }
    private Tab mCurrentTab = Tab.KEYBOARD;

    /* Colors */
    private int mBg = 0xFF1A1A2E;
    private int mBtnBg = 0xFF2D2D50;
    private int mBtnBgPress = 0xFF3D3D6C;
    private int mBtnBorder = 0xFF4A4A7A;
    private int mBtnTxt = 0xFFE8E8F0;
    private int mComboTxt = 0xFF9999BB;
    private int mToggleOnBg = 0xFF1B5E20;
    private int mToggleOnBorder = 0xFF4CAF50;
    private int mHeaderBg = 0xFF0F0F23;
    private int mCloseBg = 0xFF7A2A2A;
    private int mRadius = 12;
    private int mTabActiveBg = 0xFF3D3D6C;
    private int mTabInactiveBg = 0xFF1A1A2E;

    private Paint mPaint, mBorderPaint;
    private int mW, mH;

    /* Listener */
    private OBLSettingFragmentListener mListener;

    private Rect mSettingsRect;
    private int mGridTop;

    /* Keyboard tab state — toggle for Shift/Ctrl/Alt */
    private boolean mShiftActive = false;
    private boolean mCtrlActive = false;
    private boolean mAltActive = false;

    /* Grid position */
    private float mGridOffsetX = 0, mGridOffsetY = 0;
    private int mGridBaseX = 20, mGridBaseY = 180;
    private boolean mMoveGridMode = false;
    private boolean mHitButton = false;
    private boolean mMoveDragging = false;
    private float mDragStartX = 0, mDragStartY = 0;
    private static final float MOVE_FACTOR = 0.3f;

    /* Move toggle button */
    private RectF mMoveToggleRect;

    /* Key long-press hold state */
    private final Set<Integer> mHeldKeys = new HashSet<>();
    private Integer mTouchDownOrdinal = null;
    private int mTouchDownRow = -1, mTouchDownCol = -1;
    private final Handler mHandler = new Handler();
    private Runnable mLongPressRunnable = null;

    /* Tab bar rects for touch */
    private Rect mKeyboardTabRect, mNumPadTabRect;

    public OblSettingFragment(Context context) { super(context); init(); }
    public OblSettingFragment(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public OblSettingFragment(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }
    public OblSettingFragment(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) { super(context, attrs, defStyleAttr, defStyleRes); init(); }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelHoldTimer();
        releaseAllHeldKeys();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) {
            cancelHoldTimer();
            releaseAllHeldKeys();
        }
    }

    private void releaseAllHeldKeys() {
        if (mListener != null) {
            for (int ord : mHeldKeys) {
                mListener.enterKeyOff(new int[]{ord});
            }
        }
        mHeldKeys.clear();
        mTouchDownOrdinal = null;
    }

    private void init() {
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaint.setAntiAlias(true);
        mPaint.setDither(true);
        mPaint.setTextAlign(Paint.Align.CENTER);
        mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(1);
        loadCustomization();
        post(this::updateGridPosition);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        mW = getWidth(); mH = getHeight();
        if (mW <= 0 || mH <= 0) return;

        mPaint.setColor(mBg);
        canvas.drawRoundRect(0, 0, mW, mH, 16, 16, mPaint);

        int tabH = (int) (mH * 0.08f);
        int headerH = (int) (mH * 0.065f);

        int topBarH = tabH + headerH;
        mGridTop = topBarH;

        drawTabBar(canvas, tabH);
        drawHeader(canvas, tabH, headerH);

        switch (mCurrentTab) {
            case KEYBOARD:
                drawKeyboard(canvas, mGridTop, mH - mGridTop);
                break;
            case NUMPAD:
                drawNumPad(canvas, mGridTop, mH - mGridTop);
                break;
        }

        drawGridMoveControls(canvas, mGridTop, mH - mGridTop);
    }

    /* ─── Tab Bar ─── */

    private void drawTabBar(Canvas c, int h) {
        mPaint.setColor(mHeaderBg);
        c.drawRect(0, 0, mW, h, mPaint);

        String[] tabs = { "Keyboard", "#NumPad" };
        Tab[] values = { Tab.KEYBOARD, Tab.NUMPAD };
        float tw = mW / 2f;
        mPaint.setTextSize(h * 0.42f);
        Paint.FontMetrics fm = mPaint.getFontMetrics();
        float ty = h / 2f + (fm.bottom - fm.top) / 2f - fm.bottom;

        for (int i = 0; i < 2; i++) {
            float lx = i * tw, rx = (i + 1) * tw;
            boolean active = (mCurrentTab == values[i]);
            mPaint.setColor(active ? mTabActiveBg : mTabInactiveBg);
            c.drawRect(lx, 0, rx, h, mPaint);
            if (active) {
                mPaint.setColor(0xFF7C4DFF);
                c.drawRect(lx, h - 3, rx, h, mPaint);
            }
            mPaint.setColor(active ? mBtnTxt : mComboTxt);
            c.drawText(tabs[i], (lx + rx) / 2f, ty, mPaint);
        }

        mKeyboardTabRect = new Rect(0, 0, (int)(mW / 2f), h);
        mNumPadTabRect = new Rect((int)(mW / 2f), 0, mW, h);
    }

    /* ─── Header (gear + move arrows) ─── */

    private void drawHeader(Canvas c, int top, int h) {
        float gy = top + 2;
        mPaint.setColor(mHeaderBg);
        c.drawRect(0, gy, mW, gy + h, mPaint);

        mPaint.setColor(mBtnTxt);
        mPaint.setTextSize(h * 0.4f);
        Paint.FontMetrics fm = mPaint.getFontMetrics();
        float y = gy + h / 2f + (fm.bottom - fm.top) / 2f - fm.bottom;

        String title;
        switch (mCurrentTab) {
            case KEYBOARD: title = "QWERTY"; break;
            case NUMPAD: title = "Numpad"; break;
            default: title = "Keyboard"; break;
        }
        c.drawText(title, mW / 2f, y, mPaint);

        /* Gear icon (right side) */
        float gearSize = h * 0.6f;
        float gx = mW - gearSize - 16;
        float gtx = gx + gearSize / 2f;
        mPaint.setColor(0xFF9999BB);
        c.drawRoundRect(gx, gy, gx + gearSize + 16, gy + h, 8, 8, mPaint);
        mSettingsRect = new Rect((int)gx, (int)gy, (int)(gx + gearSize + 16), (int)(gy + h));
        mPaint.setColor(mBtnTxt);
        mPaint.setTextSize(12);
        c.drawText("\u2699", gtx, gy + gearSize * 0.75f, mPaint);
    }

    private void drawGridMoveControls(Canvas c, int gridTop, int gridH) {
        int bottom = gridTop + gridH;

        /* Move toggle button at the bottom of grid */
        float btnW = dpToPx(52);
        float btnH = dpToPx(20);
        float btnX = (mW - btnW) / 2f;
        float btnY = bottom - btnH - dpToPx(4);
        mPaint.setColor(mMoveGridMode ? mToggleOnBg : mBtnBg);
        c.drawRoundRect(btnX, btnY, btnX + btnW, btnY + btnH, 6, 6, mPaint);
        mBorderPaint.setColor(mBtnBorder);
        mBorderPaint.setStrokeWidth(1);
        c.drawRoundRect(btnX, btnY, btnX + btnW, btnY + btnH, 6, 6, mBorderPaint);
        mPaint.setColor(mBtnTxt);
        mPaint.setTextSize(btnH * 0.45f);
        Paint.FontMetrics fm = mPaint.getFontMetrics();
        float tx = btnX + btnW / 2f;
        float ty = btnY + btnH / 2f + (fm.bottom - fm.top) / 2f - fm.bottom;
        c.drawText("Move", tx, ty, mPaint);
        mMoveToggleRect = new RectF(btnX, btnY, btnX + btnW, btnY + btnH);
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    /* ════════════════════════════════════════
     * KEYBOARD TAB
     * ════════════════════════════════════════ */

    private static class KbKey { String label; int ordinal; KbKey(String l, int o) { label = l; ordinal = o; } }

    private static final KbKey[][] KB_ROWS = {
        { new KbKey("Q",20), new KbKey("W",21), new KbKey("E",51), new KbKey("R",43), new KbKey("T",22),
          new KbKey("Y",28), new KbKey("U",74), new KbKey("I",23), new KbKey("O",24), new KbKey("P",73) },
        { new KbKey("A",25), new KbKey("S",44), new KbKey("D",48), new KbKey("F",53), new KbKey("G",45),
          new KbKey("H",46), new KbKey("J",49), new KbKey("K",71), new KbKey("L",72) },
        { new KbKey("Z",26), new KbKey("X",27), new KbKey("C",29), new KbKey("V",50), new KbKey("B",52),
          new KbKey("N",30), new KbKey("M",31), new KbKey(",",32), new KbKey(".",33) },
        { new KbKey("Shift",0), new KbKey("Ctrl",1), new KbKey("Alt",2), new KbKey("Space",34),
          new KbKey("\u232B",42), new KbKey("Ent\u23CE",13) },
    };

    private RectF[][] mKbHitRects = null;

    private void drawKeyboard(Canvas c, int top, int gridH) {
        int rows = KB_ROWS.length;
        float pad = 4f;
        float rowH = Math.min(52f, (gridH - pad * (rows + 1)) / rows);

        if (mKbHitRects == null || mKbHitRects.length != rows) {
            mKbHitRects = new RectF[rows][];
        }

        for (int ri = 0; ri < rows; ri++) {
            KbKey[] row = KB_ROWS[ri];
            int cols = row.length;
            float cellW = (mW - pad) / cols - pad;
            float yy = top + ri * (rowH + pad) + pad;

            if (mKbHitRects[ri] == null || mKbHitRects[ri].length != row.length) {
                mKbHitRects[ri] = new RectF[row.length];
            }

            for (int ki = 0; ki < cols; ki++) {
                float xx = pad + ki * (cellW + pad);
                KbKey k = row[ki];
                RectF kr = new RectF(xx, yy, xx + cellW, yy + rowH);
                boolean active = (k.ordinal == 0 && mShiftActive) ||
                                 (k.ordinal == 1 && mCtrlActive) ||
                                 (k.ordinal == 2 && mAltActive) ||
                                 mHeldKeys.contains(k.ordinal);
                float sx = 1f, sy = 1f;
                if (active) { sx = 0.92f; sy = 0.92f; }
                float cx = kr.centerX(), cy = kr.centerY();
                float l = cx + (kr.left - cx) * sx;
                float t = cy + (kr.top - cy) * sy;
                float r2 = cx + (kr.right - cx) * sx;
                float b = cy + (kr.bottom - cy) * sy;

                mPaint.setColor(active ? mToggleOnBg : mBtnBg);
                c.drawRoundRect(l, t, r2, b, mRadius, mRadius, mPaint);
                mBorderPaint.setColor(active ? mToggleOnBorder : mBtnBorder);
                mBorderPaint.setStrokeWidth(1);
                c.drawRoundRect(l, t, r2, b, mRadius, mRadius, mBorderPaint);

                float sz = Math.min(20f, rowH * 0.32f);
                mPaint.setColor(active ? 0xFFA5D6A7 : mBtnTxt);
                mPaint.setTextSize(sz);
                Paint.FontMetrics fm = mPaint.getFontMetrics();
                float lx = (kr.left + kr.right) / 2f;
                float ly = (kr.top + kr.bottom) / 2f + (fm.bottom - fm.top) / 2f - fm.bottom;
                c.drawText(k.label, lx, ly, mPaint);

                mKbHitRects[ri][ki] = kr;
            }
        }
    }

    /* ════════════════════════════════════════
     * NUMPAD TAB
     * ════════════════════════════════════════ */

    private static final String[] NP_LABELS = {
        "7","8","9","/",
        "4","5","6","*",
        "1","2","3","-",
        "0",".","Ent","+"
    };
    private static final int[] NP_ORDS = {
        91,92,93,63, 58,59,90,62,
        55,56,57,61, 54,64,65,60
    };
    private RectF[] mNpHitRects = null;

    private void drawNumPad(Canvas c, int top, int gridH) {
        int cols = 4;
        int rows = 4;
        float cellW = (float) mW / cols;
        float cellH = Math.min(64f, (gridH - 10f) / rows);
        float startY = top + (gridH - rows * cellH) / 2f;

        if (mNpHitRects == null) mNpHitRects = new RectF[NP_LABELS.length];

        for (int i = 0; i < NP_LABELS.length; i++) {
            int r = i / cols, col = i % cols;
            RectF kr = new RectF(col * cellW + 5, startY + r * cellH + 5,
                                  (col + 1) * cellW - 5, startY + (r + 1) * cellH - 5);
            mNpHitRects[i] = kr;
            boolean isHeld = mHeldKeys.contains(NP_ORDS[i]);
            boolean isOp = NP_LABELS[i].equals("/") || NP_LABELS[i].equals("*") ||
                           NP_LABELS[i].equals("-") || NP_LABELS[i].equals("+");
            if (isHeld) {
                mPaint.setColor(mToggleOnBg);
            } else {
                mPaint.setColor(isOp ? 0xFF3D3D6C : mBtnBg);
            }
            c.drawRoundRect(kr, mRadius, mRadius, mPaint);
            mBorderPaint.setColor(isHeld ? mToggleOnBorder : mBtnBorder);
            mBorderPaint.setStrokeWidth(1);
            c.drawRoundRect(kr, mRadius, mRadius, mBorderPaint);

            mPaint.setColor(isHeld ? 0xFFA5D6A7 : (isOp ? 0xFFB388FF : mBtnTxt));
            float sz = Math.min(26f, cellH * 0.4f);
            mPaint.setTextSize(sz);
            Paint.FontMetrics fm = mPaint.getFontMetrics();
            float lx = (kr.left + kr.right) / 2f;
            float ly = (kr.top + kr.bottom) / 2f + (fm.bottom - fm.top) / 2f - fm.bottom;
            c.drawText(NP_LABELS[i], lx, ly, mPaint);
        }
    }

    /* ════════════════════════════════════════
     * TOUCH HANDLING
     * ════════════════════════════════════════ */

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX(), y = event.getY();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                mHitButton = false;
                mMoveDragging = false;
                mDragStartX = x; mDragStartY = y;
                cancelHoldTimer();

                /* Tab bar */
                if (mKeyboardTabRect != null && mKeyboardTabRect.contains((int)x, (int)y)) {
                    if (mCurrentTab != Tab.KEYBOARD) { mCurrentTab = Tab.KEYBOARD; invalidate(); }
                    return true;
                }
                if (mNumPadTabRect != null && mNumPadTabRect.contains((int)x, (int)y)) {
                    if (mCurrentTab != Tab.NUMPAD) { mCurrentTab = Tab.NUMPAD; invalidate(); }
                    return true;
                }

                /* Settings gear */
                if (mSettingsRect != null && mSettingsRect.contains((int)x, (int)y)) {
                    showSettings();
                    return true;
                }

                /* Move toggle button at the bottom */
                if (mMoveToggleRect != null && mMoveToggleRect.contains(x, y)) {
                    mHitButton = true;
                    mMoveGridMode = !mMoveGridMode;
                    saveCustomization();
                    invalidate();
                    return true;
                }

                switch (mCurrentTab) {
                    case KEYBOARD: onKeyboardTouch(x, y); break;
                    case NUMPAD: onNumPadTouch(x, y); break;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mMoveGridMode && !mHitButton) {
                    float dx = x - mDragStartX;
                    float dy = y - mDragStartY;
                    if (Math.abs(dx) > 4f || Math.abs(dy) > 4f) {
                        mMoveDragging = true;
                        mGridOffsetX += dx * MOVE_FACTOR;
                        mGridOffsetY += dy * MOVE_FACTOR;
                        mDragStartX = x; mDragStartY = y;
                        updateGridPosition();
                        invalidate();
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: {
                if (mMoveDragging) {
                    mMoveDragging = false;
                    invalidate(); performClick();
                    break;
                }
                if (mTouchDownOrdinal != null) {
                    cancelHoldTimer();
                    if (mListener != null) {
                        mListener.enterKey(new int[]{mTouchDownOrdinal});
                    }
                    mTouchDownOrdinal = null;
                    mTouchDownRow = -1;
                    mTouchDownCol = -1;
                }
                invalidate(); performClick();
                break;
            }
        }
        return true;
    }

    /* ─── Keyboard touch ─── */

    private void onKeyboardTouch(float x, float y) {
        if (mKbHitRects == null) return;
        for (int ri = 0; ri < mKbHitRects.length; ri++) {
            if (mKbHitRects[ri] == null) continue;
            for (int ki = 0; ki < mKbHitRects[ri].length; ki++) {
                RectF kr = mKbHitRects[ri][ki];
                if (kr != null && kr.contains(x, y)) {
                    KbKey k = KB_ROWS[ri][ki];
                    performHapticFeedback(0);
                    mHitButton = true;

                    if (k.ordinal == 0) {
                        mShiftActive = !mShiftActive;
                        if (mListener != null) {
                            if (mShiftActive) mListener.enterKeyOn(new int[]{0});
                            else mListener.enterKeyOff(new int[]{0});
                        }
                        invalidate();
                    } else if (k.ordinal == 1) {
                        mCtrlActive = !mCtrlActive;
                        if (mListener != null) {
                            if (mCtrlActive) mListener.enterKeyOn(new int[]{1});
                            else mListener.enterKeyOff(new int[]{1});
                        }
                        invalidate();
                    } else if (k.ordinal == 2) {
                        mAltActive = !mAltActive;
                        if (mListener != null) {
                            if (mAltActive) mListener.enterKeyOn(new int[]{2});
                            else mListener.enterKeyOff(new int[]{2});
                        }
                        invalidate();
                    } else if (k.ordinal == 42) {
                        if (mListener != null) mListener.backspace();
                    } else if (k.ordinal == 13) {
                        if (mListener != null) mListener.enter();
                    } else if (k.ordinal == 34) {
                        if (mListener != null) mListener.enterKey(new int[]{34});
                    } else if (mHeldKeys.contains(k.ordinal)) {
                        mHeldKeys.remove(k.ordinal);
                        if (mListener != null) mListener.enterKeyOff(new int[]{k.ordinal});
                        invalidate();
                    } else {
                        mTouchDownOrdinal = k.ordinal;
                        mTouchDownRow = ri;
                        mTouchDownCol = ki;
                        final int heldOrdinal = k.ordinal;
                        mLongPressRunnable = () -> {
                            mHeldKeys.add(heldOrdinal);
                            mTouchDownOrdinal = null;
                            if (mListener != null) mListener.enterKeyOn(new int[]{heldOrdinal});
                            invalidate();
                        };
                        mHandler.postDelayed(mLongPressRunnable, 1000);
                    }
                    return;
                }
            }
        }
    }

    /* ─── Numpad touch ─── */

    private void onNumPadTouch(float x, float y) {
        if (mNpHitRects == null) return;
        for (int i = 0; i < mNpHitRects.length; i++) {
            if (mNpHitRects[i] != null && mNpHitRects[i].contains(x, y)) {
                performHapticFeedback(0);
                mHitButton = true;
                int ord = NP_ORDS[i];
                if (mHeldKeys.contains(ord)) {
                    mHeldKeys.remove(ord);
                    if (mListener != null) mListener.enterKeyOff(new int[]{ord});
                    invalidate();
                } else {
                    mTouchDownOrdinal = ord;
                    mTouchDownRow = -1;
                    mTouchDownCol = -1;
                    final int heldOrdinal = ord;
                    mLongPressRunnable = () -> {
                        mHeldKeys.add(heldOrdinal);
                        mTouchDownOrdinal = null;
                        if (mListener != null) mListener.enterKeyOn(new int[]{heldOrdinal});
                        invalidate();
                    };
                    mHandler.postDelayed(mLongPressRunnable, 1000);
                }
                return;
            }
        }
    }

    /* ─── Cancel long-press timer ─── */

    private void cancelHoldTimer() {
        if (mLongPressRunnable != null) {
            mHandler.removeCallbacks(mLongPressRunnable);
            mLongPressRunnable = null;
        }
    }

    /* ─── Settings dialog ─── */

    private void showSettings() {
        Context ctx = getContext();
        if (ctx == null) return;
        new AlertDialog.Builder(ctx)
            .setCustomTitle(makeTitle(ctx, "Keyboard Settings"))
            .setItems(new String[]{
                "Export to file",
                "Import from file",
                "Customize appearance",
                "Save changes",
                "Cancel"
            }, (d, w) -> {
                if (w == 0) showExportDialog();
                else if (w == 1) showFilePicker();
                else if (w == 2) showCustomizeDialog();
                else if (w == 3) {
                    saveCustomization();
                    invalidate();
                    Toast.makeText(ctx, "Settings saved", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Discard", (d, w) -> {
                loadCustomization();
                invalidate();
                Toast.makeText(ctx, "Changes discarded", Toast.LENGTH_SHORT).show();
            })
            .create().show();
    }

    /* ─── Export ─── */

    private void showExportDialog() {
        Context ctx = getContext();
        if (ctx == null) return;
        final EditText input = new EditText(ctx);
        input.setText("my_keyboard");
        input.setHint("File name (without .json)");
        input.setTextColor(0xFFE8E8F0);
        input.setHintTextColor(0xFF666688);
        input.setBackgroundColor(0xFF2D2D50);
        input.setPadding(12, 8, 12, 8);
        new AlertDialog.Builder(ctx)
            .setCustomTitle(makeTitle(ctx, "Export to file"))
            .setView(input)
            .setPositiveButton("Export", (d, w) -> exportToFile(input.getText().toString().trim()))
            .setNegativeButton("Cancel", null)
            .create().show();
    }

    private void exportToFile(String name) {
        Context ctx = getContext();
        if (ctx == null) return;
        if (name.isEmpty()) name = "my_keyboard";
        if (!name.endsWith(".json")) name += ".json";
        try {
            JSONObject root = new JSONObject();
            root.put("format_version", 1);
            root.put("name", name.replace(".json", ""));
            JSONObject cust = new JSONObject();
            cust.put("bg", mBg); cust.put("btnBg", mBtnBg);
            cust.put("btnBgPress", mBtnBgPress); cust.put("btnBorder", mBtnBorder);
            cust.put("btnTxt", mBtnTxt); cust.put("comboTxt", mComboTxt);
            cust.put("toggleOnBg", mToggleOnBg); cust.put("toggleOnBorder", mToggleOnBorder);
            cust.put("headerBg", mHeaderBg); cust.put("closeBg", mCloseBg);
            cust.put("tabActiveBg", mTabActiveBg); cust.put("tabInactiveBg", mTabInactiveBg);
            cust.put("radius", mRadius);
            cust.put("gridOffsetX", mGridOffsetX); cust.put("gridOffsetY", mGridOffsetY);
            cust.put("moveGridMode", mMoveGridMode);
            root.put("customization", cust);
            File dir = new File(Environment.getExternalStorageDirectory(), "com.epai.oblender/" + KB_DIR_NAME);
            if (!dir.exists()) dir.mkdirs();
            FileWriter fw = new FileWriter(new File(dir, name));
            fw.write(root.toString(2));
            fw.close();
            Toast.makeText(ctx, "Exported to " + dir + "/" + name, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(ctx, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /* ─── Import ─── */

    private void showFilePicker() {
        Context ctx = getContext();
        if (ctx == null) return;
        File dir = new File(Environment.getExternalStorageDirectory(), "com.epai.oblender/" + KB_DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null || files.length == 0) {
            Toast.makeText(ctx, "No .json files in " + dir.getAbsolutePath(), Toast.LENGTH_LONG).show();
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        final String[] names = new String[files.length];
        final String[] paths = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            names[i] = files[i].getName();
            paths[i] = files[i].getAbsolutePath();
        }
        new AlertDialog.Builder(ctx)
            .setCustomTitle(makeTitle(ctx, "Select file to import"))
            .setItems(names, (d, w) -> importFromFile(paths[w]))
            .setNegativeButton("Cancel", null)
            .create().show();
    }

    private void importFromFile(String path) {
        Context ctx = getContext();
        if (ctx == null) return;
        try {
            BufferedReader br = new BufferedReader(new FileReader(path));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONObject root = new JSONObject(sb.toString());
            if (root.has("customization")) {
                JSONObject cust = root.getJSONObject("customization");
                mBg = cust.optInt("bg", mBg); mBtnBg = cust.optInt("btnBg", mBtnBg);
                mBtnBgPress = cust.optInt("btnBgPress", mBtnBgPress);
                mBtnBorder = cust.optInt("btnBorder", mBtnBorder);
                mBtnTxt = cust.optInt("btnTxt", mBtnTxt);
                mComboTxt = cust.optInt("comboTxt", mComboTxt);
                mToggleOnBg = cust.optInt("toggleOnBg", mToggleOnBg);
                mToggleOnBorder = cust.optInt("toggleOnBorder", mToggleOnBorder);
                mHeaderBg = cust.optInt("headerBg", mHeaderBg);
                mCloseBg = cust.optInt("closeBg", mCloseBg);
                mTabActiveBg = cust.optInt("tabActiveBg", mTabActiveBg);
                mTabInactiveBg = cust.optInt("tabInactiveBg", mTabInactiveBg);
                mRadius = cust.optInt("radius", mRadius);
                mGridOffsetX = (float) cust.optDouble("gridOffsetX", mGridOffsetX);
                mGridOffsetY = (float) cust.optDouble("gridOffsetY", mGridOffsetY);
                mMoveGridMode = cust.optBoolean("moveGridMode", mMoveGridMode);
                saveCustomization();
            }
            invalidate();
            Toast.makeText(ctx, "Imported " + path.substring(path.lastIndexOf('/') + 1), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(ctx, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /* ─── Customize dialog ─── */

    private void showCustomizeDialog() {
        Context ctx = getContext();
        if (ctx == null) return;
        final SharedPreferences sp = ctx.getSharedPreferences(PREFS_CUSTOM, Context.MODE_PRIVATE);
        new AlertDialog.Builder(ctx)
            .setCustomTitle(makeTitle(ctx, "Appearance"))
            .setItems(new String[]{
                "Reset to defaults",
                "Close"
            }, (d, w) -> {
                if (w == 0) {
                    mBg = 0xFF1A1A2E; mBtnBg = 0xFF2D2D50;
                    mBtnBgPress = 0xFF3D3D6C; mBtnBorder = 0xFF4A4A7A;
                    mBtnTxt = 0xFFE8E8F0; mComboTxt = 0xFF9999BB;
                    mToggleOnBg = 0xFF1B5E20; mToggleOnBorder = 0xFF4CAF50;
                    mHeaderBg = 0xFF0F0F23; mCloseBg = 0xFF7A2A2A;
                    mRadius = 12; mTabActiveBg = 0xFF3D3D6C;
                    mTabInactiveBg = 0xFF1A1A2E;
                    mGridOffsetX = 0; mGridOffsetY = 0;
                    mMoveGridMode = false;
                    saveCustomization();
                    invalidate();
                }
            })
            .create().show();
    }

    private TextView makeTitle(Context ctx, String s) {
        TextView tv = new TextView(ctx);
        tv.setText(s);
        tv.setTextColor(0xFFE8E8F0);
        tv.setTextSize(16);
        tv.setPadding(20, 16, 20, 8);
        return tv;
    }

    /* ─── Persistence ─── */

    private void loadCustomization() {
        try {
            SharedPreferences sp = getContext().getSharedPreferences(PREFS_CUSTOM, Context.MODE_PRIVATE);
            mBg = sp.getInt("bg", mBg); mBtnBg = sp.getInt("btnBg", mBtnBg);
            mBtnBgPress = sp.getInt("btnBgPress", mBtnBgPress);
            mBtnBorder = sp.getInt("btnBorder", mBtnBorder);
            mBtnTxt = sp.getInt("btnTxt", mBtnTxt); mComboTxt = sp.getInt("comboTxt", mComboTxt);
            mToggleOnBg = sp.getInt("toggleOnBg", mToggleOnBg);
            mToggleOnBorder = sp.getInt("toggleOnBorder", mToggleOnBorder);
            mHeaderBg = sp.getInt("headerBg", mHeaderBg);
            mCloseBg = sp.getInt("closeBg", mCloseBg);
            mTabActiveBg = sp.getInt("tabActiveBg", mTabActiveBg);
            mTabInactiveBg = sp.getInt("tabInactiveBg", mTabInactiveBg);
            mRadius = sp.getInt("radius", mRadius);
            mGridOffsetX = sp.getFloat("gridOffsetX", mGridOffsetX);
            mGridOffsetY = sp.getFloat("gridOffsetY", mGridOffsetY);
            mMoveGridMode = sp.getBoolean("moveGridMode", mMoveGridMode);
        } catch (Exception e) { Log.e(TAG, "loadCustomization", e); }
    }

    private void saveCustomization() {
        try {
            getContext().getSharedPreferences(PREFS_CUSTOM, Context.MODE_PRIVATE)
                .edit()
                .putInt("bg", mBg).putInt("btnBg", mBtnBg)
                .putInt("btnBgPress", mBtnBgPress).putInt("btnBorder", mBtnBorder)
                .putInt("btnTxt", mBtnTxt).putInt("comboTxt", mComboTxt)
                .putInt("toggleOnBg", mToggleOnBg).putInt("toggleOnBorder", mToggleOnBorder)
                .putInt("headerBg", mHeaderBg).putInt("closeBg", mCloseBg)
                .putInt("tabActiveBg", mTabActiveBg).putInt("tabInactiveBg", mTabInactiveBg)
                .putInt("radius", mRadius)
                .putFloat("gridOffsetX", mGridOffsetX).putFloat("gridOffsetY", mGridOffsetY)
                .putBoolean("moveGridMode", mMoveGridMode)
                .apply();
        } catch (Exception e) { Log.e(TAG, "saveCustomization", e); }
    }

    /* ─── Grid position ─── */

    void updateGridPosition() {
        if (getLayoutParams() instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) getLayoutParams();
            lp.x = (int)(mGridBaseX + mGridOffsetX);
            lp.y = (int)(mGridBaseY + mGridOffsetY);
            try {
                WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
                wm.updateViewLayout(this, lp);
            } catch (Exception e) { Log.e(TAG, "updateGridPosition", e); }
        }
    }

    void setGridPosition(float x, float y) {
        mGridOffsetX = x - mGridBaseX; mGridOffsetY = y - mGridBaseY;
        updateGridPosition();
    }

    void setOBLSettingFragmentListener(OBLSettingFragmentListener l) { mListener = l; }
    void SetValue(int type, int value) {}
    int GetAsyncKeyState(int type) {
        if (type == 100) return getVisibility() == VISIBLE ? 1 : 0;
        if (type == 101) return mMoveGridMode ? 1 : 0;
        return 0;
    }

    public interface OBLSettingFragmentListener {
        void enterKeyOn(int keys[]);
        void enterKeyOff(int keys[]);
        void enterKey(int keys[]);
        void backspace();
        void enter();
        void closeFragment();
    }
}
