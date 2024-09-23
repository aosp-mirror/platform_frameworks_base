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
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Slog;
import android.view.InputDevice;
import android.view.WindowManager;

import com.android.server.input.InputManagerService;
import com.android.server.input.TouchpadHardwareProperties;
import com.android.server.input.TouchpadHardwareState;

import java.util.Objects;

public class TouchpadDebugViewController implements InputManager.InputDeviceListener {

    private static final String TAG = "TouchpadDebugView";

    private final Context mContext;
    private final Handler mHandler;

    @Nullable
    private TouchpadDebugView mTouchpadDebugView;

    private final InputManagerService mInputManagerService;
    private boolean mTouchpadVisualizerEnabled = false;

    public TouchpadDebugViewController(Context context, Looper looper,
                                       InputManagerService inputManagerService) {
        //TODO(b/369059937): Handle multi-display scenarios
        mContext = context;
        mHandler = new Handler(looper);
        mInputManagerService = inputManagerService;
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        final InputManager inputManager = Objects.requireNonNull(
                mContext.getSystemService(InputManager.class));
        InputDevice inputDevice = inputManager.getInputDevice(deviceId);

        if (Objects.requireNonNull(inputDevice).supportsSource(
                InputDevice.SOURCE_TOUCHPAD | InputDevice.SOURCE_MOUSE)
                && mTouchpadVisualizerEnabled) {
            showDebugView(deviceId);
        }
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        hideDebugView(deviceId);
        if (mTouchpadDebugView == null) {
            final InputManager inputManager = Objects.requireNonNull(
                    mContext.getSystemService(InputManager.class));
            for (int id : inputManager.getInputDeviceIds()) {
                onInputDeviceAdded(id);
            }
        }
    }

    /**
     * Switch to showing the touchpad with the given device ID
     */
    public void switchVisualisationToTouchpadId(int newDeviceId) {
        if (mTouchpadDebugView != null) hideDebugView(mTouchpadDebugView.getTouchpadId());
        showDebugView(newDeviceId);
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
    }

    /**
     * Notify the controller that the touchpad visualizer setting value has changed.
     * This must be called from the same looper thread as {@code mHandler}.
     */
    public void updateTouchpadVisualizerEnabled(boolean touchpadVisualizerEnabled) {
        if (mTouchpadVisualizerEnabled == touchpadVisualizerEnabled) {
            return;
        }
        mTouchpadVisualizerEnabled = touchpadVisualizerEnabled;
        final InputManager inputManager = Objects.requireNonNull(
                mContext.getSystemService(InputManager.class));
        if (touchpadVisualizerEnabled) {
            inputManager.registerInputDeviceListener(this, mHandler);
            for (int deviceId : inputManager.getInputDeviceIds()) {
                onInputDeviceAdded(deviceId);
            }
        } else {
            if (mTouchpadDebugView != null) {
                hideDebugView(mTouchpadDebugView.getTouchpadId());
            }
            inputManager.unregisterInputDeviceListener(this);
        }
    }

    private void showDebugView(int touchpadId) {
        if (mTouchpadDebugView != null) {
            return;
        }
        final WindowManager wm = Objects.requireNonNull(
                mContext.getSystemService(WindowManager.class));

        TouchpadHardwareProperties touchpadHardwareProperties =
                mInputManagerService.getTouchpadHardwareProperties(
                        touchpadId);

        mTouchpadDebugView = new TouchpadDebugView(mContext, touchpadId,
                touchpadHardwareProperties, this::switchVisualisationToTouchpadId);
        final WindowManager.LayoutParams mWindowLayoutParams =
                mTouchpadDebugView.getWindowLayoutParams();

        wm.addView(mTouchpadDebugView, mWindowLayoutParams);
        Slog.d(TAG, "Touchpad debug view created.");

        if (touchpadHardwareProperties != null) {
            Slog.d(TAG, touchpadHardwareProperties.toString());
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

    /**
     * Notifies about an update in the touchpad's hardware state.
     *
     * @param touchpadHardwareState the hardware state of a touchpad
     * @param deviceId              the deviceId of the touchpad that is sending the hardware state
     */
    public void updateTouchpadHardwareState(TouchpadHardwareState touchpadHardwareState,
                                            int deviceId) {
        if (mTouchpadDebugView != null) {
            mTouchpadDebugView.updateHardwareState(touchpadHardwareState, deviceId);
        }
    }

    /**
     * Notify the TouchpadDebugView of a new touchpad gesture.
     */
    public void updateTouchpadGestureInfo(int gestureType, int deviceId) {
        if (mTouchpadDebugView != null) {
            mTouchpadDebugView.updateGestureInfo(gestureType, deviceId);
        }
    }
}
