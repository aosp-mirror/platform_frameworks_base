/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.policy;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.Handler;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.view.DisplayInfo;
import android.view.IDisplayFoldListener;

import com.android.server.DisplayThread;
import com.android.server.LocalServices;
import com.android.server.wm.WindowManagerInternal;

/**
 * Controls the behavior of foldable devices whose screen can literally bend and fold.
 */
class DisplayFoldController {

    private static final String TAG = "DisplayFoldController";
    private final WindowManagerInternal mWindowManagerInternal;
    private final DisplayManagerInternal mDisplayManagerInternal;
    private final int mDisplayId;
    private final Handler mHandler;

    /** The display area while device is folded. */
    private final Rect mFoldedArea;
    /** The display area to override the original folded area. */
    private Rect mOverrideFoldedArea = new Rect();

    private final DisplayInfo mNonOverrideDisplayInfo = new DisplayInfo();
    private final RemoteCallbackList<IDisplayFoldListener> mListeners = new RemoteCallbackList<>();
    private Boolean mFolded;

    DisplayFoldController(WindowManagerInternal windowManagerInternal,
            DisplayManagerInternal displayManagerInternal, int displayId, Rect foldedArea,
            Handler handler) {
        mWindowManagerInternal = windowManagerInternal;
        mDisplayManagerInternal = displayManagerInternal;
        mDisplayId = displayId;
        mFoldedArea = new Rect(foldedArea);
        mHandler = handler;
    }

    void requestDeviceFolded(boolean folded) {
        mHandler.post(() -> setDeviceFolded(folded));
    }

    void setDeviceFolded(boolean folded) {
        if (mFolded != null && mFolded == folded) {
            return;
        }
        if (folded) {
            Rect foldedArea;
            if (!mOverrideFoldedArea.isEmpty()) {
                foldedArea = mOverrideFoldedArea;
            } else if (!mFoldedArea.isEmpty()) {
                foldedArea = mFoldedArea;
            } else {
                return;
            }

            mDisplayManagerInternal.getNonOverrideDisplayInfo(mDisplayId, mNonOverrideDisplayInfo);
            final int dx = (mNonOverrideDisplayInfo.logicalWidth - foldedArea.width()) / 2
                    - foldedArea.left;
            final int dy = (mNonOverrideDisplayInfo.logicalHeight - foldedArea.height()) / 2
                    - foldedArea.top;

            // Bypass scaling otherwise LogicalDisplay will scale contents by default.
            mDisplayManagerInternal.setDisplayScalingDisabled(mDisplayId, true);
            mWindowManagerInternal.setForcedDisplaySize(mDisplayId,
                    foldedArea.width(), foldedArea.height());
            mDisplayManagerInternal.setDisplayOffsets(mDisplayId, -dx, -dy);
        } else {
            mDisplayManagerInternal.setDisplayScalingDisabled(mDisplayId, false);
            mWindowManagerInternal.clearForcedDisplaySize(mDisplayId);
            mDisplayManagerInternal.setDisplayOffsets(mDisplayId, 0, 0);
        }
        mFolded = folded;

        final int n = mListeners.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                mListeners.getBroadcastItem(i).onDisplayFoldChanged(mDisplayId, folded);
            } catch (RemoteException e) {
                // Listener died.
            }
        }
        mListeners.finishBroadcast();
    }

    void registerDisplayFoldListener(IDisplayFoldListener listener) {
        mListeners.register(listener);
        if (mFolded == null) {
            return;
        }
        mHandler.post(() -> {
            try {
                listener.onDisplayFoldChanged(mDisplayId, mFolded);
            } catch (RemoteException e) {
                // Listener died.
            }
        });
    }

    void unregisterDisplayFoldListener(IDisplayFoldListener listener) {
        mListeners.unregister(listener);
    }

    void setOverrideFoldedArea(Rect area) {
        mOverrideFoldedArea.set(area);
    }

    Rect getFoldedArea() {
        if (!mOverrideFoldedArea.isEmpty()) {
            return mOverrideFoldedArea;
        } else {
            return mFoldedArea;
        }
    }

    /**
     * Only used for the case that persist.debug.force_foldable is set.
     * This is using proximity sensor to simulate the fold state switch.
     */
    static DisplayFoldController createWithProxSensor(Context context, int displayId) {
        final SensorManager sensorManager = context.getSystemService(SensorManager.class);
        final Sensor proxSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (proxSensor == null) {
            return null;
        }

        final DisplayFoldController result = create(context, displayId);
        sensorManager.registerListener(new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                result.requestDeviceFolded(event.values[0] < 1f);
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // Ignore.
            }
        }, proxSensor, SensorManager.SENSOR_DELAY_NORMAL);

        return result;
    }

    static DisplayFoldController create(Context context, int displayId) {
        final DisplayManagerInternal displayService =
                LocalServices.getService(DisplayManagerInternal.class);
        final String configFoldedArea = context.getResources().getString(
                com.android.internal.R.string.config_foldedArea);
        final Rect foldedArea;
        if (configFoldedArea == null || configFoldedArea.isEmpty()) {
            foldedArea = new Rect();
        } else {
            foldedArea = Rect.unflattenFromString(configFoldedArea);
        }

        return new DisplayFoldController(LocalServices.getService(WindowManagerInternal.class),
                displayService, displayId, foldedArea, DisplayThread.getHandler());
    }
}
