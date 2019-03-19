/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.annotation.NonNull;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.android.systemui.recents.OverviewProxyService;

/**
 * A back action when triggered will execute a back command
 */
public class NavigationBackAction extends NavigationGestureAction {

    private static final String BACK_AFTER_END_PROP =
            "quickstepcontroller_homegoesbackwhenend";
    private static final long BACK_BUTTON_FADE_OUT_ALPHA = 60;
    private static final long BACK_GESTURE_POLL_TIMEOUT = 1000;

    private final Handler mHandler = new Handler();

    private final Runnable mExecuteBackRunnable = new Runnable() {
        @Override
        public void run() {
            if (isEnabled() && canPerformAction()) {
                performBack();
                mHandler.postDelayed(this, BACK_GESTURE_POLL_TIMEOUT);
            }
        }
    };

    public NavigationBackAction(@NonNull NavigationBarView navigationBarView,
            @NonNull OverviewProxyService service) {
        super(navigationBarView, service);
    }

    @Override
    public boolean allowHitTargetToMoveOverDrag() {
        return true;
    }

    @Override
    public boolean canPerformAction() {
        return mProxySender.getBackButtonAlpha() > 0;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    protected void onGestureStart(MotionEvent event) {
        if (!QuickStepController.shouldhideBackButton(getContext())) {
            mNavigationBarView.getBackButton().setAlpha(0 /* alpha */, true /* animate */,
                    BACK_BUTTON_FADE_OUT_ALPHA);
        }
        mHandler.removeCallbacks(mExecuteBackRunnable);
        if (!shouldExecuteBackOnUp()) {
            performBack();
            mHandler.postDelayed(mExecuteBackRunnable, BACK_GESTURE_POLL_TIMEOUT);
        }
    }

    @Override
    protected void onGestureEnd() {
        mHandler.removeCallbacks(mExecuteBackRunnable);
        if (!QuickStepController.shouldhideBackButton(getContext())) {
            mNavigationBarView.getBackButton().setAlpha(
                    mProxySender.getBackButtonAlpha(), true /* animate */);
        }
        if (shouldExecuteBackOnUp()) {
            performBack();
        }
    }

    @Override
    public boolean disableProxyEvents() {
        return true;
    }

    private void performBack() {
        sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK);
        sendEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK);
    }

    private boolean shouldExecuteBackOnUp() {
        return getGlobalBoolean(BACK_AFTER_END_PROP);
    }

    private void sendEvent(int action, int code) {
        long when = SystemClock.uptimeMillis();
        final KeyEvent ev = new KeyEvent(when, when, action, code, 0 /* repeat */,
                0 /* metaState */, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /* scancode */,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);
        InputManager.getInstance().injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }
}
