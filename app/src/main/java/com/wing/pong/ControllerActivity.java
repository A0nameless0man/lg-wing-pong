package com.wing.pong;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

/**
 * 小屏手柄 Activity。由 GameActivity 通过 setLaunchDisplayId 推到 display 4。
 * 生命周期即"手柄在线"信号:大屏据此显示 WAITING FOR CONTROLLER。
 */
public class ControllerActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 双保险:manifest 已声明 portrait,代码再锁一次——
        // 部分厂商 ROM 对副屏 activity 的 manifest 朝向不生效,手机转动时小屏内容会跟着转
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        setContentView(new ControllerView(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        PongEngine.get().controllerLive = true;
    }

    @Override
    protected void onPause() {
        PongEngine.get().controllerLive = false;
        super.onPause();
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
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }
}
