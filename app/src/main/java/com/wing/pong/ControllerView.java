package com.wing.pong;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

/**
 * 小屏手柄。纵向握持:上下拖动 = 玩家拍上下移动,点按 = 发球/重开。
 * 中部镜像绘制球场,让手柄自己也能当记分牌看。
 */
public class ControllerView extends View implements Choreographer.FrameCallback {

    private final PongEngine engine = PongEngine.get();
    private final Choreographer choreographer = Choreographer.getInstance();

    private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int W, H;
    private boolean touching;   // 手指是否按在手柄上:按住时隐藏操作提示
    // 镜像球场布局:触摸按此区域 1:1 映射到球拍行程
    private float fieldLeft, fieldTop, fieldW, fieldH;

    // 甩动发球手势状态:手指不抬起,快速反向甩两下 = 发球/接管
    private float flickDistPx;
    private static final long FLICK_STROKE_MS = 320;   // 单次甩的最大极值间隔
    private static final long FLICK_PAIR_MS = 900;     // 两甩的最大间隔
    private boolean flickTracking;
    private float flickMin, flickMax;
    private long flickMinT, flickMaxT;
    private long firstFlickAt;
    private int firstFlickDir;

    public ControllerView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        white.setColor(Color.WHITE);
        dim.setColor(0xFF666666);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        W = w;
        H = h;
        fieldW = W * 0.86f;
        fieldH = fieldW * 0.5f;
        fieldLeft = (W - fieldW) / 2;
        fieldTop = H * 0.5f - fieldH / 2;
        flickDistPx = h * 0.12f;   // 甩动的最短行程
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        choreographer.postFrameCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        choreographer.removeFrameCallback(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void doFrame(long frameNanos) {
        // 镜像球场必须跟游戏状态持续刷新,不能只在触摸时重绘
        choreographer.postFrameCallback(this);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float travel = Math.max(1f, fieldH);
        engine.onControllerDrag((event.getY() - fieldTop) / travel);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                engine.onControllerTap();   // 点按(带抬手)仍是发球/重开
                flickTracking = false;
                break;
            case MotionEvent.ACTION_MOVE:
                detectFlick(event.getY(), event.getEventTime());
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                flickTracking = false;
                break;
        }
        touching = event.getActionMasked() != MotionEvent.ACTION_UP
                && event.getActionMasked() != MotionEvent.ACTION_CANCEL;
        return true;
    }

    /**
     * 不抬手指的发球手势:快速反向甩两下。
     * 用 min/max 包络检测:一次"甩"= 累计行程达 flickDistPx 且
     * 两个极值间隔 ≤250ms(慢速拖拽不会误触);两次甩间隔 ≤900ms 且
     * 方向相反 → 触发 onControllerTap(发球/接管/重开)。
     */
    private void detectFlick(float y, long t) {
        if (!flickTracking) {
            flickTracking = true;
            flickMin = flickMax = y;
            flickMinT = flickMaxT = t;
            firstFlickAt = 0;
            return;
        }
        if (y < flickMin) { flickMin = y; flickMinT = t; }
        if (y > flickMax) { flickMax = y; flickMaxT = t; }

        if (flickMax - flickMin >= flickDistPx) {
            int dir = flickMaxT >= flickMinT ? 1 : -1;   // 后到达的极值决定甩向
            boolean fastEnough = flickMaxT - flickMinT <= FLICK_STROKE_MS;
            if (fastEnough) {
                if (firstFlickAt != 0 && t - firstFlickAt <= FLICK_PAIR_MS && dir != firstFlickDir) {
                    firstFlickAt = 0;          // 消费掉这两下甩
                    flickTracking = false;
                    engine.onControllerTap();
                    return;
                }
                firstFlickAt = t;              // 记下第一甩
                firstFlickDir = dir;
            }
            // 无效或已消费:从当前位置重新累积包络
            flickMin = flickMax = y;
            flickMinT = flickMaxT = t;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        // 中部镜像球场(与 PongEngine 同一状态源,布局与触摸映射共用一套矩形)
        float fx = fieldLeft;
        float fy = fieldTop;
        float fw = fieldW;
        float fh = fieldH;
        drawField(c, fx, fy, fw, fh);

        // 大比分
        white.setTextSize(H * 0.07f);
        white.setTextAlign(Paint.Align.CENTER);
        c.drawText(engine.scoreLeft + "  :  " + engine.scoreRight, W / 2f, fy - H * 0.02f, white);

        // 状态行:按住手柄时隐藏,给打球留干净视野
        String status;
        if (engine.attractMode) {
            status = "DEMO  -  TAP TO PLAY";
        } else if (engine.gameOver) {
            status = engine.scoreLeft > engine.scoreRight ? "YOU WIN - TAP TO REMATCH" : "AI WINS - TAP TO REMATCH";
        } else if (engine.serving) {
            status = engine.controllerLive ? "FLICK ×2 OR TAP TO SERVE" : "TAP TO SERVE";
        } else {
            status = touching ? "" : "DRAG TO MOVE";
        }
        if (!status.isEmpty()) {
            c.drawText(status, W / 2f, fy + fieldH + H * 0.05f, white);
        }
    }

    private void drawField(Canvas c, float fx, float fy, float fw, float fh) {
        dim.setStyle(Paint.Style.FILL);
        // 中线虚线
        float dash = fh / 12f;
        for (float y = fy; y < fy + fh; y += dash * 2) {
            c.drawRect(fx + fw / 2 - 2, y, fx + fw / 2 + 2, y + dash, dim);
        }
        // 球拍
        float pw = fw * 0.014f;
        float ph = engine.PADDLE_H * fh;
        float lx = fx + engine.PADDLE_X * fw;
        float rx = fx + (1 - engine.PADDLE_X) * fw;
        c.drawRect(lx - pw, engine.leftY * fh + fy - ph / 2, lx, engine.leftY * fh + fy + ph / 2, white);
        c.drawRect(rx, engine.rightY * fh + fy - ph / 2, rx + pw, engine.rightY * fh + fy + ph / 2, white);
        // 球
        float bs = fw * 0.014f;
        c.drawRect(fx + engine.ballX * fw - bs, fy + engine.ballY * fh - bs,
                fx + engine.ballX * fw + bs, fy + engine.ballY * fh + bs, white);
        // 边框
        dim.setStyle(Paint.Style.STROKE);
        dim.setStrokeWidth(3);
        c.drawRect(fx, fy, fx + fw, fy + fh, dim);
    }
}
