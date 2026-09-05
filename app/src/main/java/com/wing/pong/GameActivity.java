package com.wing.pong;

import android.app.Activity;
import android.app.ActivityOptions;
import android.hardware.display.DisplayManager;
import android.content.Intent;
import android.os.Bundle;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();

        if (redirectToBiggestInternalIfNeeded()) return;

        PongEngine.get().reset();
        setContentView(new PongView(this));
        spawnControllerOnOtherInternalDisplay();
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
        if (getIntent().getBooleanExtra(EXTRA_REDIRECTED, false)) {
            return false;   // 已重定向过一次,不再循环
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
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            i.putExtra(EXTRA_REDIRECTED, true);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(biggest.getDisplayId());
            startActivity(i, opts.toBundle());
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
            return;
        }
        try {
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(target.getDisplayId());
            Intent i = new Intent(this, ControllerActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            startActivity(i, opts.toBundle());
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
