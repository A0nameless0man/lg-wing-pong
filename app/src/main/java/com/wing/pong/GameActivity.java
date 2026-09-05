package com.wing.pong;

import android.app.Activity;
import android.app.ActivityOptions;
import android.hardware.display.DisplayManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 大屏:游戏画面,横屏。启动后自动把 ControllerActivity 推到小屏。
 *
 * 角色分配规则:游戏主体永远落在面积最大的内建屏上(主屏)。
 * 若本实例发现自己跑在小屏上(例如用户从小屏抽屉点开),
 * 就把一个新 GameActivity 实例定向启动到大屏,自己 finish 退场。
 */
public class GameActivity extends Activity {

    private static final String TAG = "WingPong";
    private static final String EXTRA_REDIRECTED = "redirected";
    private static final long HEARTBEAT_MS = 2000;
    private static final long RESPAWN_MIN_INTERVAL_MS = 1500;
    // 重定向熔断:10 秒窗口内最多 3 次,防止 ROM 不服从 display 指定时陷入循环
    private static int redirectCount;
    private static long redirectWindowStart;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastControllerSpawnAt;
    private boolean heartbeatRunning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();

        if (redirectToBiggestInternalIfNeeded()) return;

        PongEngine.get().reset();
        setContentView(new PongView(this));
        spawnControllerOnOtherInternalDisplay();
        trackCoverState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PongEngine.get().gameUiLive = true;
        startHeartbeat();
        enforceTopology();
    }

    @Override
    protected void onPause() {
        PongEngine.get().gameUiLive = false;
        stopHeartbeat();
        super.onPause();
    }

    private void startHeartbeat() {
        if (heartbeatRunning) return;
        heartbeatRunning = true;
        mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_MS);
    }

    private void stopHeartbeat() {
        heartbeatRunning = false;
        mainHandler.removeCallbacks(heartbeatRunnable);
    }

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!heartbeatRunning) return;
            enforceTopology();
            mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_MS);
        }
    };

    /** 周期性拓扑自愈:副屏电源状态刷新 + 手柄实例健康检查(错屏纠正/亡佚补spawn) */
    private void enforceTopology() {
        reevaluateCoverState();
        Integer expected = PongEngine.get().expectedControllerDisplayId;
        if (expected == null) return;

        ControllerActivity ca = PongEngine.get().controllerActivity;
        boolean healthy = ca != null && !ca.isFinishing()
                && ca.getDisplay() != null && ca.getDisplay().getDisplayId() == expected;
        if (healthy) return;

        Log.i(TAG, "controller unhealthy (alive=" + (ca != null) + "), respawning on display " + expected);
        if (ca != null && !ca.isFinishing()) {
            ca.finish();
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastControllerSpawnAt >= RESPAWN_MIN_INTERVAL_MS) {
            lastControllerSpawnAt = now;
            spawnControllerOnOtherInternalDisplay();
        }
    }

    /** 若手柄实例健在且在正确屏幕上,把它带回前台而不是新开实例 */
    private boolean reviveExistingController(int targetDisplayId) {
        ControllerActivity ca = PongEngine.get().controllerActivity;
        if (ca == null || ca.isFinishing()) return false;
        if (ca.getDisplay() == null || ca.getDisplay().getDisplayId() != targetDisplayId) return false;
        try {
            // NEW_TASK(不带 MULTIPLE_TASK)+ singleTask 会复用既有任务带到前台,不产生新任务
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(targetDisplayId);
            startActivity(new Intent(this, ControllerActivity.class), opts.toBundle());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "revive failed: " + e);
            return false;
        }
    }

    /**
     * 大屏 only 开关:监听副屏电源状态。
     * 收起旋盖时 LG 会熄灭副屏面板(STATE 离开 ON),此时启用大屏触摸;
     * 重新展开副屏点亮后关闭大屏触摸,恢复双屏分工。
     */
    private void trackCoverState() {
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                reevaluateCoverState();
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                reevaluateCoverState();
            }

            @Override
            public void onDisplayChanged(int displayId) {
                reevaluateCoverState();
            }
        };
        dm.registerDisplayListener(listener, new Handler(Looper.getMainLooper()));
        reevaluateCoverState();
    }

    private void reevaluateCoverState() {
        int currentId = getDisplay() != null ? getDisplay().getDisplayId() : Display.DEFAULT_DISPLAY;
        Display sub = null;
        for (Display d : internalDisplays()) {
            if (d.getDisplayId() != currentId) {
                sub = d;
                break;
            }
        }
        // 无副屏(普通手机)或副屏面板熄灭(旋盖收起) → 大屏触摸接管;
        // 副屏点亮(展开) → 大屏只显示,操作归小屏手柄。
        boolean touchEnabled = sub == null || sub.getState() != Display.STATE_ON;
        if (PongEngine.get().bigScreenTouchEnabled != touchEnabled) {
            Log.i(TAG, "bigScreenTouchEnabled=" + touchEnabled);
            PongEngine.get().bigScreenTouchEnabled = touchEnabled;
        }
    }

    /** 枚举非私有的内建/覆盖屏(排除投屏与 Presentation 虚拟屏) */
    private List<Display> internalDisplays() {
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        List<Display> out = new ArrayList<>();
        for (Display d : dm.getDisplays()) {
            int flags = d.getFlags();
            if ((flags & Display.FLAG_PRIVATE) != 0 || (flags & Display.FLAG_PRESENTATION) != 0) {
                continue;
            }
            out.add(d);
        }
        return out;
    }

    /**
     * 若本实例不在最大内建屏上,则把游戏重定向到最大内建屏并 finish 自己。
     * @return true 表示已发起重定向,调用方应立即返回
     */
    private boolean redirectToBiggestInternalIfNeeded() {
        long now = SystemClock.elapsedRealtime();
        if (now - redirectWindowStart > 10_000) {
            redirectWindowStart = now;
            redirectCount = 0;
        }
        if (redirectCount >= 3) {
            Log.w(TAG, "redirect circuit breaker tripped, staying put");
            return false;
        }
        Display current = getDisplay();
        Display biggest = null;
        long bestArea = -1;
        for (Display d : internalDisplays()) {
            DisplayMetrics metrics = new DisplayMetrics();
            d.getRealMetrics(metrics);
            long area = (long) metrics.widthPixels * metrics.heightPixels;
            if (area > bestArea) {
                bestArea = area;
                biggest = d;
            }
        }
        if (biggest == null || current == null || biggest.getDisplayId() == current.getDisplayId()) {
            return false;   // 已在主屏,无需迁移
        }
        Log.i(TAG, "launched on display " + current.getDisplayId()
                + ", redirecting game to bigger display " + biggest.getDisplayId());
        try {
            Intent i = new Intent(this, GameActivity.class);
            // 必须带 MULTIPLE_TASK:否则 NEW_TASK 会复用自己所在的旧任务(仍在小屏),迁移失效。
            // 旧实例 finish 后任务自动回收,不会堆积。
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(biggest.getDisplayId());
            startActivity(i, opts.toBundle());
            redirectCount++;
            finish();
            return true;
        } catch (Exception e) {
            // 迁移失败则就地玩(小屏上横屏游戏体验差些,但功能完整)
            Log.w(TAG, "redirect failed: " + e);
            return false;
        }
    }

    /**
     * 把手柄推到另一块内建屏(Wing 小屏 = display 4)。
     * 用公开的 ActivityOptions.setLaunchDisplayId,与 LG 自家 launcher 同一机制。
     */
    private void spawnControllerOnOtherInternalDisplay() {
        int currentId = getDisplay() != null ? getDisplay().getDisplayId() : Display.DEFAULT_DISPLAY;
        Display target = null;
        for (Display d : internalDisplays()) {
            if (d.getDisplayId() == currentId) continue;
            target = d;
            break;
        }
        if (target == null) {
            Log.i(TAG, "no secondary display, controller merged on this screen");
            PongEngine.get().controllerExpected = false;
            return;
        }
        PongEngine.get().expectedControllerDisplayId = target.getDisplayId();
        PongEngine.get().controllerExpected = true;

        // 已有实例在正确屏幕(比如只是退到了桌面):带回前台即可,避免任务堆积
        if (reviveExistingController(target.getDisplayId())) {
            Log.i(TAG, "controller revived on display " + target.getDisplayId());
            return;
        }
        try {
            // NEW_TASK + manifest singleTask/独立 affinity/excludeFromRecents:
            // LG 视频播放器 DsdpControllerActivity 同款,复用单实例且不进多任务
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(target.getDisplayId());
            Intent i = new Intent(this, ControllerActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i, opts.toBundle());
            lastControllerSpawnAt = SystemClock.elapsedRealtime();
            Log.i(TAG, "controller launched on display " + target.getDisplayId());
        } catch (Exception e) {
            // 双屏启动失败(设备/系统限制)时游戏仍可在本屏触摸左右半区游玩
            Log.w(TAG, "controller spawn failed: " + e);
        }
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }
}
