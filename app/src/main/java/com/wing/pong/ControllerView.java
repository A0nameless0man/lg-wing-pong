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
    // 镜像球场布局:触摸按此区域 1:1 映射到球拍行程
    private float fieldLeft, fieldTop, fieldW, fieldH;

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
        // 手指在镜像球场内的纵向位置 = 球拍位置(1:1),出界由引擎夹取
        float travel = Math.max(1f, fieldH);
        engine.onControllerDrag((event.getY() - fieldTop) / travel);
        // 点按(不带拖动的按下)触发发球/重开
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            engine.onControllerTap();
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        // 上下拖动引导箭头
        float arrowX = W / 2f;
        dim.setStrokeWidth(6);
        dim.setStyle(Paint.Style.STROKE);
        drawArrow(c, arrowX, H * 0.06f, true);
        drawArrow(c, arrowX, H * 0.94f, false);

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

        // 状态行
        String status;
        if (engine.attractMode) {
            status = "DEMO  -  TAP TO PLAY";
        } else if (engine.gameOver) {
            status = engine.scoreLeft > engine.scoreRight ? "YOU WIN - TAP TO REMATCH" : "AI WINS - TAP TO REMATCH";
        } else if (engine.serving) {
            status = "TAP TO SERVE";
        } else {
            status = "DRAG TO MOVE";
        }
        c.drawText(status, W / 2f, fy + fieldH + H * 0.05f, white);
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

    private void drawArrow(Canvas c, float cx, float cy, boolean up) {
        float s = W * 0.035f;
        Paint.Style prev = dim.getStyle();
        dim.setStyle(Paint.Style.FILL);
        android.graphics.Path p = new android.graphics.Path();
        if (up) {
            p.moveTo(cx, cy - s);
            p.lineTo(cx - s, cy + s * 0.6f);
            p.lineTo(cx + s, cy + s * 0.6f);
        } else {
            p.moveTo(cx, cy + s);
            p.lineTo(cx - s, cy - s * 0.6f);
            p.lineTo(cx + s, cy - s * 0.6f);
        }
        p.close();
        c.drawPath(p, dim);
        dim.setStyle(prev);
    }
}
