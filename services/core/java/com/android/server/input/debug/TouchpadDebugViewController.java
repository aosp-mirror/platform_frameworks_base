/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.input.debug;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Slog;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.WindowManager;

import com.android.server.input.InputManagerService;
import com.android.server.input.TouchpadHardwareProperties;

import java.util.Objects;

public class TouchpadDebugViewController {

    private static final String TAG = "TouchpadDebugViewController";

    private final Context mContext;
    private final Handler mHandler;
    @Nullable
    private TouchpadDebugView mTouchpadDebugView;
    private final InputManagerService mInputManagerService;

    public TouchpadDebugViewController(Context context, Looper looper,
                                       InputManagerService inputManagerService) {
        final DisplayManager displayManager = Objects.requireNonNull(
                context.getSystemService(DisplayManager.class));
        final Display defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        mContext = context.createDisplayContext(defaultDisplay);
        mHandler = new Handler(looper);
        mInputManagerService = inputManagerService;
    }

    public void systemRunning() {
        final InputManager inputManager = Objects.requireNonNull(
                mContext.getSystemService(InputManager.class));
        inputManager.registerInputDeviceListener(mInputDeviceListener, mHandler);
        for (int deviceId : inputManager.getInputDeviceIds()) {
            mInputDeviceListener.onInputDeviceAdded(deviceId);
        }
    }

    private final InputManager.InputDeviceListener mInputDeviceListener =
            new InputManager.InputDeviceListener() {
                @Override
                public void onInputDeviceAdded(int deviceId) {
                    final InputManager inputManager = Objects.requireNonNull(
                            mContext.getSystemService(InputManager.class));
                    InputDevice inputDevice = inputManager.getInputDevice(deviceId);

                    if (Objects.requireNonNull(inputDevice).supportsSource(
                            InputDevice.SOURCE_TOUCHPAD | InputDevice.SOURCE_MOUSE)) {
                        showDebugView(deviceId);
                    }
                }

                @Override
                public void onInputDeviceRemoved(int deviceId) {
                    hideDebugView(deviceId);
                }

                @Override
                public void onInputDeviceChanged(int deviceId) {
                }
            };

    private void showDebugView(int touchpadId) {
        if (mTouchpadDebugView != null) {
            return;
        }
        final WindowManager wm = Objects.requireNonNull(
                mContext.getSystemService(WindowManager.class));

        mTouchpadDebugView = new TouchpadDebugView(mContext, touchpadId);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.setTitle("TouchpadDebugView - display " + mContext.getDisplayId());
        lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;

        lp.x = 40;
        lp.y = 100;
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.TOP | Gravity.LEFT;

        wm.addView(mTouchpadDebugView, lp);
        Slog.d(TAG, "Touchpad debug view created.");

        TouchpadHardwareProperties mTouchpadHardwareProperties =
                mInputManagerService.getTouchpadHardwareProperties(
                        touchpadId);
        // TODO(b/360137366): Use the hardware properties to initialise layout parameters.
        if (mTouchpadHardwareProperties != null) {
            Slog.d(TAG, mTouchpadHardwareProperties.toString());
        } else {
            Slog.w(TAG, "Failed to retrieve touchpad hardware properties for "
                    + "device ID: " + touchpadId);
        }
    }

    private void hideDebugView(int touchpadId) {
        if (mTouchpadDebugView == null || mTouchpadDebugView.getTouchpadId() != touchpadId) {
            return;
        }
        final WindowManager wm = Objects.requireNonNull(
                mContext.getSystemService(WindowManager.class));
        wm.removeView(mTouchpadDebugView);
        mTouchpadDebugView = null;
        Slog.d(TAG, "Touchpad debug view removed.");
    }
}
