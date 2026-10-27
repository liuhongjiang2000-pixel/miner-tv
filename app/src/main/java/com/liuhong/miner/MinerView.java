package com.liuhong.miner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 黄金矿工自绘视图：摆动钩子、确认键放钩、抓矿石回收计分。
 * 遥控确认/下/空格放钩，触屏点按同。关卡限时达标进入下一关。
 */
public class MinerView extends View {

    /** 抓到什么 / 过关 / 结束 的对外通知。 */
    public interface Callback {
        void onGrab(String speakText, boolean isBonus);
        void onLevelClear(int level, int levelScore);
        void onGameOver(int totalScore, int level);
    }

    private static final float SWING_RANGE = 78f;       // 摆动幅度（度）
    private static final float SWING_SPEED = 95f;       // 摆动速度（度/秒）
    private static final float HOOK_SPEED = 1000f;      // 放钩速度 px/s
    private static final float REEL_SPEED = 780f;       // 收绳基础速度 px/s
    private static final float LEVEL_TIME = 60f;        // 每关秒数
    private static final float PAUSE_AFTER_GRAB = 0.35f;

    // 矿石类型
    private static final int GOLD_BIG = 0;
    private static final int GOLD_MID = 1;
    private static final int GOLD_SMALL = 2;
    private static final int DIAMOND = 3;
    private static final int ROCK = 4;
    private static final int BAG = 5;

    private static class Item {
        int type;
        float x, y, r;
        int value;   // 分数
        int weight;  // 收绳减速倍率
    }

    private static final int STATE_SWING = 0;
    private static final int STATE_EXTEND = 1;
    private static final int STATE_REEL = 2;
    private static final int STATE_PAUSE = 3;
    private static final int STATE_LEVEL_CLEAR = 4;
    private static final int STATE_OVER = 5;

    private int state = STATE_SWING;
    private float stateTimer;

    private int level = 1;
    private int levelScore;
    private int totalScore;
    private float timeLeft = LEVEL_TIME;
    private int goal;

    private float swing;      // 当前角度（度，0=正下方）
    private int swingDir = 1;
    private float hookLen;    // 绳长
    private float baseLen;    // 静止绳长
    private Item grabbed;
    private int strengthCharges; // 大力剩余次数

    private final List<Item> items = new ArrayList<Item>();
    private final Random rng = new Random();

    private Callback callback;
    private boolean running;

    private int w, h;
    private float pivotX, pivotY, groundY;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastTick;

    private final Paint skyPaint = new Paint();
    private final Paint soilPaint = new Paint();
    private final Paint soilDotPaint = new Paint();
    private final Paint ropePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint metalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint goldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint goldEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint diamondPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bannerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint minerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint skinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MinerView(Context context) {
        this(context, null);
    }

    public MinerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);

        skyPaint.setColor(0xFF26303B);
        soilPaint.setColor(0xFF4E342E);
        soilDotPaint.setColor(0xFF3E2723);
        ropePaint.setColor(0xFFD7CCC8);
        ropePaint.setStrokeWidth(3f);
        metalPaint.setColor(0xFF90A4AE);
        metalPaint.setStyle(Paint.Style.STROKE);
        metalPaint.setStrokeWidth(6f);
        metalPaint.setStrokeCap(Paint.Cap.ROUND);
        goldPaint.setColor(0xFFFFC107);
        goldEdgePaint.setColor(0xFFB8860B);
        goldEdgePaint.setStyle(Paint.Style.STROKE);
        goldEdgePaint.setStrokeWidth(4f);
        shinePaint.setColor(0xFFFFF8E1);
        diamondPaint.setColor(0xFF4DD0E1);
        rockPaint.setColor(0xFF78909C);
        bagPaint.setColor(0xFF8D6E63);
        hudPaint.setColor(0xFFFFF8E1);
        hudBarPaint.setColor(0xFF66BB6A);
        bannerPaint.setColor(0xFFFFF176);
        bannerPaint.setTextAlign(Paint.Align.CENTER);
        starPaint.setColor(0x66FFFFFF);
        minerPaint.setColor(0xFF5D4037);
        skinPaint.setColor(0xFFFFCC80);
    }

    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    public void startGame() {
        level = 1;
        totalScore = 0;
        startLevel();
        running = true;
        requestFocus();
        lastTick = SystemClock.elapsedRealtime();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    public void pauseGame() {
        running = false;
        handler.removeCallbacks(tick);
    }

    public void resumeGame() {
        if (state == STATE_OVER) return;
        running = true;
        lastTick = SystemClock.elapsedRealtime();
        handler.removeCallbacks(tick);
        handler.post(tick);
        requestFocus();
    }

    private void startLevel() {
        goal = 350 + (level - 1) * 250;
        levelScore = 0;
        timeLeft = LEVEL_TIME;
        strengthCharges = 0;
        state = STATE_SWING;
        grabbed = null;
        genItems();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            float dt = Math.min(0.05f, (now - lastTick) / 1000f);
            lastTick = now;
            update(dt);
            invalidate();
            handler.postDelayed(this, 33);
        }
    };

    @Override
    protected void onSizeChanged(int width, int height, int oldw, int oldh) {
        super.onSizeChanged(width, height, oldw, oldh);
        w = width;
        h = height;
        pivotX = w / 2f;
        pivotY = h * 0.13f;
        groundY = h * 0.24f;
        baseLen = h * 0.06f;
        hookLen = baseLen;
        hudPaint.setTextSize(h * 0.038f);
        bannerPaint.setTextSize(h * 0.09f);
        if (!items.isEmpty()) genItems();
    }

    private float scale() {
        return Math.min(w, h);
    }

    private void genItems() {
        items.clear();
        int count = Math.min(16, 5 + level);
        float unit = scale();
        int tries = 0;
        while (items.size() < count && tries < 500) {
            tries++;
            Item it = new Item();
            float roll = rng.nextFloat();
            if (roll < 0.16f) { it.type = GOLD_BIG; it.r = unit * 0.075f; it.value = 500; it.weight = 3; }
            else if (roll < 0.38f) { it.type = GOLD_MID; it.r = unit * 0.055f; it.value = 250; it.weight = 2; }
            else if (roll < 0.62f) { it.type = GOLD_SMALL; it.r = unit * 0.04f; it.value = 100; it.weight = 1; }
            else if (roll < 0.70f) { it.type = DIAMOND; it.r = unit * 0.028f; it.value = 600; it.weight = 1; }
            else if (roll < 0.88f) { it.type = ROCK; it.r = unit * 0.045f; it.value = 20; it.weight = 3; }
            else { it.type = BAG; it.r = unit * 0.034f; it.value = 0; it.weight = 1; }

            it.x = unit * 0.06f + rng.nextFloat() * (w - unit * 0.12f);
            it.y = groundY + unit * 0.10f + rng.nextFloat() * (h - groundY - unit * 0.20f);
            boolean ok = true;
            for (Item o : items) {
                float dx = o.x - it.x, dy = o.y - it.y;
                if (dx * dx + dy * dy < (o.r + it.r + unit * 0.02f) * (o.r + it.r + unit * 0.02f)) {
                    ok = false;
                    break;
                }
            }
            if (ok) items.add(it);
        }
    }

    private void update(float dt) {
        switch (state) {
            case STATE_SWING:
                timeLeft -= dt;
                swing += SWING_SPEED * swingDir * dt;
                if (swing > SWING_RANGE) { swing = SWING_RANGE; swingDir = -1; }
                if (swing < -SWING_RANGE) { swing = -SWING_RANGE; swingDir = 1; }
                checkTime();
                break;

            case STATE_EXTEND: {
                timeLeft -= dt;
                hookLen += HOOK_SPEED * dt;
                float[] tip = hookTip();
                Item hit = null;
                for (Item it : items) {
                    float dx = it.x - tip[0], dy = it.y - tip[1];
                    if (dx * dx + dy * dy <= it.r * it.r) { hit = it; break; }
                }
                if (hit != null) {
                    grabbed = hit;
                    items.remove(hit);
                    state = STATE_REEL;
                } else if (tip[0] < 8 || tip[0] > w - 8 || tip[1] > h - 8) {
                    state = STATE_REEL;
                }
                checkTime();
                break;
            }

            case STATE_REEL: {
                timeLeft -= dt;
                float speed = REEL_SPEED / (grabbed != null ? grabbed.weight : 1);
                if (grabbed != null && strengthCharges > 0) {
                    speed *= 2f;
                }
                hookLen -= speed * dt;
                if (hookLen <= baseLen) {
                    hookLen = baseLen;
                    applyGrab();
                }
                checkTime();
                break;
            }

            case STATE_PAUSE:
                timeLeft -= dt;
                stateTimer -= dt;
                if (stateTimer <= 0) {
                    state = STATE_SWING;
                }
                checkTime();
                break;

            case STATE_LEVEL_CLEAR:
                stateTimer -= dt;
                if (stateTimer <= 0) {
                    level++;
                    startLevel();
                }
                break;

            case STATE_OVER:
                stateTimer -= dt;
                if (stateTimer <= 0 && callback != null) {
                    Callback cb = callback;
                    callback = null;
                    cb.onGameOver(totalScore, level);
                }
                break;
        }
    }

    private void checkTime() {
        if (state == STATE_OVER || state == STATE_LEVEL_CLEAR) return;
        if (timeLeft > 0) return;
        timeLeft = 0;
        if (levelScore >= goal) {
            state = STATE_LEVEL_CLEAR;
            stateTimer = 1.6f;
            if (callback != null) callback.onLevelClear(level, levelScore);
        } else {
            state = STATE_OVER;
            stateTimer = 1.6f;
        }
    }

    private void applyGrab() {
        if (grabbed == null) {
            state = STATE_PAUSE;
            stateTimer = PAUSE_AFTER_GRAB * 0.6f;
            return;
        }
        Item it = grabbed;
        grabbed = null;
        String speakText = null;
        boolean bonus = false;
        switch (it.type) {
            case GOLD_BIG: levelScore += 500; totalScore += 500; speakText = "大金块，五百分"; break;
            case GOLD_MID: levelScore += 250; totalScore += 250; speakText = "金块，两百五十分"; break;
            case GOLD_SMALL: levelScore += 100; totalScore += 100; speakText = "小金块，一百分"; break;
            case DIAMOND: levelScore += 600; totalScore += 600; speakText = "钻石，六百分"; break;
            case ROCK: levelScore += 20; totalScore += 20; speakText = "破石头"; break;
            case BAG: {
                bonus = true;
                float roll = rng.nextFloat();
                if (roll < 0.45f) {
                    int v = 100 + rng.nextInt(8) * 100;
                    levelScore += v;
                    totalScore += v;
                    speakText = "神秘袋子，开出 " + v + " 分";
                } else if (roll < 0.75f) {
                    timeLeft += 15f;
                    speakText = "神秘袋子，加时十五秒";
                } else {
                    strengthCharges = 3;
                    speakText = "神秘袋子，大力出奇迹";
                }
                break;
            }
        }
        if (it.type != BAG && strengthCharges > 0) strengthCharges--;
        if (callback != null && speakText != null) callback.onGrab(speakText, bonus);
        state = STATE_PAUSE;
        stateTimer = PAUSE_AFTER_GRAB;
    }

    private float[] hookTip() {
        double rad = Math.toRadians(swing);
        return new float[]{
                pivotX + (float) Math.sin(rad) * hookLen,
                pivotY + (float) Math.cos(rad) * hookLen
        };
    }

    private void launch() {
        if (state == STATE_SWING) {
            state = STATE_EXTEND;
        }
    }

    // ---------- 输入 ----------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                launch();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            launch();
            return true;
        }
        return true;
    }

    // ---------- 绘制 ----------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // 天空
        canvas.drawRect(0, 0, w, groundY, skyPaint);
        // 星星（伪随机固定）
        starPaint.setStrokeWidth(2f);
        for (int i = 0; i < 24; i++) {
            float sx = ((i * 733) % 1000) / 1000f * w;
            float sy = ((i * 397) % 1000) / 1000f * groundY * 0.85f;
            canvas.drawPoint(sx, sy, starPaint);
        }
        // 地层
        canvas.drawRect(0, groundY, w, h, soilPaint);
        for (int i = 0; i < 30; i++) {
            float dx = ((i * 571) % 1000) / 1000f * w;
            float dy = groundY + ((i * 313) % 1000) / 1000f * (h - groundY);
            canvas.drawCircle(dx, dy, 3f + (i % 3) * 2f, soilDotPaint);
        }
        // 矿石
        for (Item it : items) {
            drawItem(canvas, it, it.x, it.y);
        }
        // 矿工
        drawMiner(canvas);
        // 绳与钩
        float[] tip = hookTip();
        canvas.drawLine(pivotX, pivotY, tip[0], tip[1], ropePaint);
        drawClaw(canvas, tip[0], tip[1]);
        if (grabbed != null) {
            drawItem(canvas, grabbed, tip[0], tip[1] + grabbed.r * 0.6f);
        }
        // HUD
        drawHud(canvas);
        // 横幅
        if (state == STATE_LEVEL_CLEAR) {
            drawBanner(canvas, "过关！");
        } else if (state == STATE_OVER) {
            drawBanner(canvas, "时间到！");
        }
    }

    private void drawMiner(Canvas canvas) {
        float u = scale();
        // 身体
        canvas.drawRect(pivotX - u * 0.045f, pivotY - u * 0.11f,
                pivotX + u * 0.045f, pivotY, minerPaint);
        // 头
        canvas.drawCircle(pivotX, pivotY - u * 0.145f, u * 0.035f, skinPaint);
        // 安全帽
        canvas.drawCircle(pivotX, pivotY - u * 0.158f, u * 0.038f, goldPaint);
        // 帽檐
        canvas.drawRect(pivotX - u * 0.055f, pivotY - u * 0.155f,
                pivotX + u * 0.055f, pivotY - u * 0.145f, goldPaint);
        // 滑轮
        canvas.drawCircle(pivotX, pivotY, u * 0.012f, metalPaint);
    }

    private void drawClaw(Canvas canvas, float x, float y) {
        Path p = new Path();
        p.moveTo(x, y - 14f);
        p.lineTo(x, y);
        canvas.drawPath(p, ropePaint);
        // 两爪
        canvas.drawLine(x, y, x - 16f, y + 18f, metalPaint);
        canvas.drawLine(x, y, x + 16f, y + 18f, metalPaint);
        canvas.drawLine(x - 16f, y + 18f, x - 8f, y + 26f, metalPaint);
        canvas.drawLine(x + 16f, y + 18f, x + 8f, y + 26f, metalPaint);
    }

    private void drawItem(Canvas canvas, Item it, float x, float y) {
        switch (it.type) {
            case GOLD_BIG:
            case GOLD_MID:
            case GOLD_SMALL:
                canvas.drawCircle(x, y, it.r, goldPaint);
                canvas.drawCircle(x, y, it.r, goldEdgePaint);
                canvas.drawCircle(x - it.r * 0.3f, y - it.r * 0.3f, it.r * 0.22f, shinePaint);
                break;
            case DIAMOND:
                Path d = new Path();
                d.moveTo(x, y - it.r * 1.3f);
                d.lineTo(x + it.r, y);
                d.lineTo(x, y + it.r * 1.3f);
                d.lineTo(x - it.r, y);
                d.close();
                canvas.drawPath(d, diamondPaint);
                break;
            case ROCK:
                canvas.drawCircle(x, y, it.r, rockPaint);
                canvas.drawCircle(x - it.r * 0.25f, y - it.r * 0.2f, it.r * 0.18f, soilDotPaint);
                break;
            case BAG:
                canvas.drawRoundRect(x - it.r, y - it.r, x + it.r, y + it.r, 8f, 8f, bagPaint);
                hudPaint.setTextAlign(Paint.Align.CENTER);
                float oldSize = hudPaint.getTextSize();
                hudPaint.setTextSize(it.r * 1.1f);
                hudPaint.setColor(0xFFFFF8E1);
                canvas.drawText("?", x, y + it.r * 0.38f, hudPaint);
                hudPaint.setTextSize(oldSize);
                hudPaint.setColor(0xFFFFF8E1);
                break;
        }
    }

    private void drawHud(Canvas canvas) {
        hudPaint.setTextAlign(Paint.Align.LEFT);
        float ts = hudPaint.getTextSize();
        canvas.drawText("第 " + level + " 关", w * 0.03f, ts * 1.4f, hudPaint);
        canvas.drawText("本关 " + levelScore + " / 目标 " + goal, w * 0.20f, ts * 1.4f, hudPaint);
        canvas.drawText("总分 " + totalScore, w * 0.55f, ts * 1.4f, hudPaint);
        if (strengthCharges > 0) {
            canvas.drawText("大力×" + strengthCharges, w * 0.78f, ts * 1.4f, hudPaint);
        }
        // 时间条
        float barW = w * 0.94f;
        float barX = w * 0.03f;
        float barY = ts * 1.9f;
        float frac = Math.max(0f, timeLeft / LEVEL_TIME);
        hudBarPaint.setColor(frac > 0.35f ? 0xFF66BB6A : 0xFFEF5350);
        canvas.drawRect(barX, barY, barX + barW * frac, barY + ts * 0.5f, hudBarPaint);
        hudPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText((int) Math.ceil(timeLeft) + " 秒", w * 0.97f, ts * 1.4f, hudPaint);
        hudPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawBanner(Canvas canvas, String text) {
        canvas.drawRect(0, h * 0.38f, w, h * 0.56f, 0x88000000 == 0 ? bannerBgPaint : bannerBgPaint);
        canvas.drawText(text, w / 2f, h * 0.50f, bannerPaint);
    }

    private final Paint bannerBgPaint = new Paint();
    {
        bannerBgPaint.setColor(0x88000000);
    }
}
