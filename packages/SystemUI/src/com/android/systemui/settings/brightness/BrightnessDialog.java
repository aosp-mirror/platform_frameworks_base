/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.settings.brightness;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.settings.UserTracker;

import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/** A dialog that provides controls for adjusting the screen brightness. */
public class BrightnessDialog extends Activity {

    private BrightnessController mBrightnessController;
    private final BrightnessSliderController.Factory mToggleSliderFactory;
    private final UserTracker mUserTracker;
    private final Executor mMainExecutor;
    private final Handler mBackgroundHandler;

    @Inject
    public BrightnessDialog(
            UserTracker userTracker,
            BrightnessSliderController.Factory factory,
            @Main Executor mainExecutor,
            @Background Handler bgHandler) {
        mUserTracker = userTracker;
        mToggleSliderFactory = factory;
        mMainExecutor = mainExecutor;
        mBackgroundHandler = bgHandler;
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Window window = getWindow();

        window.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.requestFeature(Window.FEATURE_NO_TITLE);

        // Calling this creates the decor View, so setLayout takes proper effect
        // (see Dialog#onWindowAttributesChanged)
        window.getDecorView();
        window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);

        setContentView(R.layout.brightness_mirror_container);
        FrameLayout frame = findViewById(R.id.brightness_mirror_container);
        // The brightness mirror container is INVISIBLE by default.
        frame.setVisibility(View.VISIBLE);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) frame.getLayoutParams();
        int horizontalMargin =
                getResources().getDimensionPixelSize(R.dimen.notification_side_paddings);
        lp.leftMargin = horizontalMargin;
        lp.rightMargin = horizontalMargin;
        frame.setLayoutParams(lp);
        Rect bounds = new Rect();
        frame.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    // Exclude this view (and its horizontal margins) from triggering gestures.
                    // This prevents back gesture from being triggered by dragging close to the
                    // edge of the slider (0% or 100%).
                    bounds.set(-horizontalMargin, 0, right - left + horizontalMargin, bottom - top);
                    v.setSystemGestureExclusionRects(List.of(bounds));
                });

        BrightnessSliderController controller = mToggleSliderFactory.create(this, frame);
        controller.init();
        frame.addView(controller.getRootView(), MATCH_PARENT, WRAP_CONTENT);

        mBrightnessController = new BrightnessController(
                this, controller, mUserTracker, mMainExecutor, mBackgroundHandler);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mBrightnessController.registerCallbacks();
        MetricsLogger.visible(this, MetricsEvent.BRIGHTNESS_DIALOG);
    }

    @Override
    protected void onPause() {
        super.onPause();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onStop() {
        super.onStop();
        MetricsLogger.hidden(this, MetricsEvent.BRIGHTNESS_DIALOG);
        mBrightnessController.unregisterCallbacks();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            finish();
        }

        return super.onKeyDown(keyCode, event);
    }
}
