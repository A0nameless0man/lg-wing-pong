package com.wing.pong;

/**
 * 进程内共享游戏引擎。GameActivity(大屏) 与 ControllerActivity(小屏)
 * 运行在同一进程的不同 display 上，全部状态经此单例交换，零 IPC。
 *
 * 坐标系: x/y 归一化到 0..1（左上原点），速度为"每秒归一化单位"，
 * 与屏幕分辨率解耦，两个屏幕各自按自己的尺寸绘制同一状态。
 */
public final class PongEngine {

    private static final PongEngine I = new PongEngine();

    public static PongEngine get() {
        return I;
    }

    private PongEngine() {
        reset();
    }

    public static final int WIN_SCORE = 11;
    /** 静置多久后进入 AI vs AI 演示模式 */
    public static final long IDLE_TIMEOUT_MS = 10_000;

    // ---- 小屏(手柄)写入的输入 ----
    public volatile float controllerPaddleY = 0.5f;   // 归一化 0..1
    public volatile boolean controllerLive = false;   // 小屏 Activity 是否在前台

    // ---- 演示模式(玩家静置后接管玩家拍) ----
    public volatile boolean attractMode = false;
    private long lastPlayerInputMs = System.currentTimeMillis();
    private float attractServeTimer = 0f;
    private float attractResetTimer = 0f;

    // ---- 游戏状态（仅游戏线程写，UI 线程读） ----
    public float ballX, ballY;
    public float ballVX, ballVY;
    public float leftY, rightY;
    public int scoreLeft, scoreRight;
    public boolean serving;      // 等待发球
    public boolean gameOver;

    private static final float BALL_SPEED_START = 0.62f;
    private static final float BALL_SPEED_INC = 1.045f;   // 每次拍击加速
    private static final float BALL_SPEED_MAX = 1.5f;
    public static final float PADDLE_H = 0.22f;
    public static final float PADDLE_X = 0.05f;           // 拍面距边线的归一化距离
    private static final float AI_MAX_SPEED = 0.66f;      // AI 拍移速上限
    private static final float AI_DEADZONE = 0.035f;
    private static final float MAX_BOUNCE_ANGLE = 1.0f;   // 弧度，约 57°

    private static final float FIXED_DT = 1f / 120f;      // 固定物理步长

    /** 由渲染循环调用：把真实流逝时间切分成固定步长推进物理 */
    public void step(float dtSeconds) {
        float acc = Math.min(dtSeconds, 0.1f);   // 掉帧保护，单次最多 100ms
        while (acc > 0f) {
            float dt = Math.min(acc, FIXED_DT);
            stepFixed(dt);
            acc -= dt;
        }
    }

    private void stepFixed(float dt) {
        // 静置检测:超时进入演示模式,双 AI 对打
        attractMode = System.currentTimeMillis() - lastPlayerInputMs >= IDLE_TIMEOUT_MS;

        if (attractMode) {
            // 玩家拍交给 AI;发球与终局也自动流转
            leftY = aiTrack(leftY, ballVX < 0, dt);
            if (serving) {
                attractServeTimer += dt;
                if (attractServeTimer >= 1.2f) { serving = false; attractServeTimer = 0f; }
            }
            if (gameOver) {
                attractResetTimer += dt;
                if (attractResetTimer >= 2f) { reset(); attractResetTimer = 0f; return; }
            }
        } else {
            // 玩家拍:直接跟随手柄（两屏分离也无妨，状态在进程内即时可见）
            leftY = clamp01(controllerPaddleY);
        }

        // AI 拍:有限速度 + 死区，发球时回中
        rightY = aiTrack(rightY, !serving, dt);

        if (serving || gameOver) return;

        float nx = ballX + ballVX * dt;
        float ny = ballY + ballVY * dt;

        // 上下墙反弹
        if (ny < 0f) { ny = -ny; ballVY = -ballVY; }
        else if (ny > 1f) { ny = 2f - ny; ballVY = -ballVY; }

        float half = PADDLE_H / 2f;
        float leftFace = PADDLE_X;
        float rightFace = 1f - PADDLE_X;

        // 左拍(玩家)击球: 仅当球向左穿越拍面平面时判定
        if (ballVX < 0 && nx <= leftFace && ballX > leftFace) {
            if (ny > leftY - half && ny < leftY + half) {
                nx = leftFace + (leftFace - nx);
                bounceOffPaddle(true, ny, leftY);
            }
        }
        // 右拍(AI)击球
        if (ballVX > 0 && nx >= rightFace && ballX < rightFace) {
            if (ny > rightY - half && ny < rightY + half) {
                nx = rightFace - (nx - rightFace);
                bounceOffPaddle(false, ny, rightY);
            }
        }

        // 出界得分
        if (nx < 0f) { pointTo(false); return; }
        if (nx > 1f) { pointTo(true); return; }

        ballX = nx;
        ballY = ny;
    }

    /** 击球瞬间:按击中拍面的偏移量决定反弹角，并加速 */
    private void bounceOffPaddle(boolean playerPaddle, float hitY, float paddleY) {
        float speed = (float) Math.hypot(ballVX, ballVY) * BALL_SPEED_INC;
        speed = Math.min(speed, BALL_SPEED_MAX);
        float half = PADDLE_H / 2f;
        float offset = clamp(hitY - paddleY, -half, half) / half;   // -1..1
        float angle = offset * MAX_BOUNCE_ANGLE;
        float dir = playerPaddle ? 1f : -1f;
        ballVX = dir * speed * (float) Math.cos(angle);
        ballVY = speed * (float) Math.sin(angle);
    }

    private void pointTo(boolean playerScored) {
        if (playerScored) scoreLeft++; else scoreRight++;
        if (scoreLeft >= WIN_SCORE || scoreRight >= WIN_SCORE) {
            gameOver = true;
        } else {
            serving = true;
        }
        // 失分方向发球：球先朝得分者反方向飞
        float dir = playerScored ? -1f : 1f;
        positionForServe(dir);
    }

    private void positionForServe(float dir) {
        ballX = 0.5f;
        ballY = 0.5f;
        float angle = (float) (Math.random() * 0.7 - 0.35);
        ballVX = dir * BALL_SPEED_START * (float) Math.cos(angle);
        ballVY = BALL_SPEED_START * (float) Math.sin(angle);
    }

    /** 通用 AI 拍驱动:球飞来时追球，否则回中，限速+死区 */
    private float aiTrack(float currentY, boolean ballIncoming, float dt) {
        float target = ballIncoming ? ballY : 0.5f;
        float dy = target - currentY;
        if (Math.abs(dy) > AI_DEADZONE) {
            float move = Math.signum(dy) * Math.min(Math.abs(dy), AI_MAX_SPEED * dt);
            return clamp01(currentY + move);
        }
        return currentY;
    }

    // ---- 手柄操作 ----

    /** 小屏手指位置驱动玩家拍 */
    public void onControllerDrag(float normalizedY) {
        markPlayerInput();
        controllerPaddleY = clamp01(normalizedY);
    }

    /** 小屏点按:接管演示 / 发球 / 整场重开 */
    public void onControllerTap() {
        markPlayerInput();
        if (attractMode) {
            attractMode = false;
            reset();
            return;
        }
        if (gameOver) {
            reset();
        } else if (serving) {
            serving = false;
        }
    }

    private void markPlayerInput() {
        lastPlayerInputMs = System.currentTimeMillis();
        attractMode = false;
    }

    public void reset() {
        scoreLeft = 0;
        scoreRight = 0;
        gameOver = false;
        serving = true;
        leftY = rightY = 0.5f;
        controllerPaddleY = 0.5f;
        positionForServe(Math.random() < 0.5 ? 1f : -1f);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
