package com.wing.pong;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Choreographer;
import android.view.View;

/**
 * 大屏游戏画面。Choreographer 驱动:每帧推进固定步长物理再绘制,
 * 状态永远读自 PongEngine 单例——小屏的手部动作直接反映到这里。
 */
public class PongView extends View implements Choreographer.FrameCallback {

    private final PongEngine engine = PongEngine.get();
    private final Choreographer choreographer = Choreographer.getInstance();

    private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long lastFrameNanos = 0;

    // 与像素密度解耦的绘制尺寸
    private float ballSizePx;
    private float paddleWPx;
    private float strokePx;
    private int W, H;

    public PongView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        float density = getResources().getDisplayMetrics().density;
        ballSizePx = 10 * density;
        paddleWPx = 9 * density;
        strokePx = Math.max(2f, 2 * density);
        white.setColor(Color.WHITE);
        white.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastFrameNanos = 0;
        choreographer.postFrameCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        choreographer.removeFrameCallback(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void doFrame(long frameNanos) {
        choreographer.postFrameCallback(this);
        if (lastFrameNanos != 0) {
            float dt = (frameNanos - lastFrameNanos) / 1_000_000_000f;
            engine.step(dt);
            invalidate();
        }
        lastFrameNanos = frameNanos;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        W = w;
        H = h;
    }

    @Override
    protected void onDraw(Canvas c) {
        // 中线:经典虚线
        float dashH = H / 22f;
        float gap = dashH * 0.8f;
        float x = W / 2f - strokePx / 2f;
        for (float y = gap / 2; y + dashH <= H; y += dashH + gap) {
            c.drawRect(x, y, x + strokePx, y + dashH, white);
        }

        // 球拍
        float paddleH = engine.PADDLE_H * H;
        float leftFace = engine.PADDLE_X * W - paddleWPx;
        float rightFace = (1f - engine.PADDLE_X) * W;
        c.drawRect(leftFace, engine.leftY * H - paddleH / 2, leftFace + paddleWPx,
                engine.leftY * H + paddleH / 2, white);
        c.drawRect(rightFace, engine.rightY * H - paddleH / 2, rightFace + paddleWPx,
                engine.rightY * H + paddleH / 2, white);

        // 球
        float bs = ballSizePx;
        c.drawRect(engine.ballX * W - bs / 2, engine.ballY * H - bs / 2,
                engine.ballX * W + bs / 2, engine.ballY * H + bs / 2, white);

        // 比分:经典七段数码管,左右各一个
        drawScore(c, engine.scoreLeft, W * 0.25f);
        drawScore(c, engine.scoreRight, W * 0.75f);

        // 状态提示
        if (engine.attractMode) {
            centerText(c, "DEMO  AI VS AI   -   TAP CONTROLLER TO PLAY");
        } else if (!engine.controllerLive) {
            centerText(c, "WAITING FOR CONTROLLER ON SECOND DISPLAY...");
        } else if (engine.gameOver) {
            centerText(c, (engine.scoreLeft > engine.scoreRight ? "YOU WIN" : "AI WINS")
                    + "   TAP CONTROLLER TO REMATCH");
        } else if (engine.serving) {
            centerText(c, "TAP CONTROLLER TO SERVE");
        }
    }

    private void centerText(Canvas c, String text) {
        white.setTextSize(H * 0.055f);
        white.setTextAlign(Paint.Align.CENTER);
        float y = H * 0.14f;
        // 半透明底避免与比分重叠看不清
        int prevAlpha = white.getAlpha();
        white.setAlpha(160);
        c.drawText(text, W / 2f, y, white);
        white.setAlpha(prevAlpha);
    }

    private void drawScore(Canvas c, int score, float centerX) {
        float digitH = H * 0.16f;
        float digitW = digitH * 0.55f;
        drawDigit(c, score, centerX - digitW / 2, H * 0.06f, digitW, digitH);
    }

    /** 七段数码管数字绘制,复刻 1972 年的味道(粗笔画块状) */
    private void drawDigit(Canvas c, int digit, float left, float top, float w, float h) {
        float t = h * 0.14f;   // 经典 Pong 的粗笔画,按数字高度比例而非屏幕密度
        float half = t / 2f;
        float midY = top + h / 2;
        float bot = top + h;
        boolean[] seg = SEGMENTS[digit & 0xF];
        // a(top) b(top-right) c(bottom-right) d(bottom) e(bottom-left) f(top-left) g(middle)
        if (seg[0]) c.drawRect(left + t, top, left + w - t, top + t, white);                          // a
        if (seg[1]) c.drawRect(left + w - t, top + t, left + w, midY - half, white);                  // b
        if (seg[2]) c.drawRect(left + w - t, midY + half, left + w, bot - t, white);                  // c
        if (seg[3]) c.drawRect(left + t, bot - t, left + w - t, bot, white);                          // d
        if (seg[4]) c.drawRect(left, midY + half, left + t, bot - t, white);                          // e
        if (seg[5]) c.drawRect(left, top + t, left + t, midY - half, white);                          // f
        if (seg[6]) c.drawRect(left + t, midY - half, left + w - t, midY + half, white);              // g
    }

    private static final boolean[][] SEGMENTS = {
            // a      b      c      d      e      f      g
            new boolean[]{true,  true,  true,  true,  true,  true,  false}, // 0
            new boolean[]{false, true,  true,  false, false, false, false}, // 1
            new boolean[]{true,  true,  false, true,  true,  false, true},  // 2
            new boolean[]{true,  true,  true,  true,  false, false, true},  // 3
            new boolean[]{false, true,  true,  false, false, true,  true},  // 4
            new boolean[]{true,  false, true,  true,  false, true,  true},  // 5
            new boolean[]{true,  false, true,  true,  true,  true,  true},  // 6
            new boolean[]{true,  true,  true,  false, false, false, false}, // 7
            new boolean[]{true,  true,  true,  true,  true,  true,  true},  // 8
            new boolean[]{true,  true,  true,  true,  false, true,  true},  // 9
    };
}
