/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.display.mode;

import android.hardware.Sensor;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.SurfaceControl;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.server.sensors.SensorManagerInternal;

import java.io.PrintWriter;

class ProximitySensorObserver implements
        SensorManagerInternal.ProximityActiveListener,
        DisplayManager.DisplayListener {
    private final String mProximitySensorName = null;
    private final String mProximitySensorType = Sensor.STRING_TYPE_PROXIMITY;

    private final VotesStorage mVotesStorage;
    private final DisplayModeDirector.Injector mInjector;
    @GuardedBy("mSensorObserverLock")
    private final SparseBooleanArray mDozeStateByDisplay = new SparseBooleanArray();
    private final Object mSensorObserverLock = new Object();
    private DisplayManagerInternal mDisplayManagerInternal;
    @GuardedBy("mSensorObserverLock")
    private boolean mIsProxActive = false;

    ProximitySensorObserver(VotesStorage votesStorage, DisplayModeDirector.Injector injector) {
        mVotesStorage = votesStorage;
        mInjector = injector;
    }

    @Override
    public void onProximityActive(boolean isActive) {
        synchronized (mSensorObserverLock) {
            if (mIsProxActive != isActive) {
                mIsProxActive = isActive;
                recalculateVotesLocked();
            }
        }
    }

    void observe() {
        mDisplayManagerInternal = mInjector.getDisplayManagerInternal();

        final SensorManagerInternal sensorManager = mInjector.getSensorManagerInternal();
        sensorManager.addProximityActiveListener(BackgroundThread.getExecutor(), this);

        synchronized (mSensorObserverLock) {
            for (Display d : mInjector.getDisplays()) {
                mDozeStateByDisplay.put(d.getDisplayId(), mInjector.isDozeState(d));
            }
        }
        mInjector.registerDisplayListener(this, BackgroundThread.getHandler(),
                DisplayManager.EVENT_FLAG_DISPLAY_ADDED
                        | DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
                        | DisplayManager.EVENT_FLAG_DISPLAY_REMOVED);
    }

    @GuardedBy("mSensorObserverLock")
    private void recalculateVotesLocked() {
        final Display[] displays = mInjector.getDisplays();
        for (Display d : displays) {
            int displayId = d.getDisplayId();
            Vote vote = null;
            if (mIsProxActive && !mDozeStateByDisplay.get(displayId)) {
                final SurfaceControl.RefreshRateRange rate =
                        mDisplayManagerInternal.getRefreshRateForDisplayAndSensor(
                                displayId, mProximitySensorName, mProximitySensorType);
                if (rate != null) {
                    vote = Vote.forPhysicalRefreshRates(rate.min, rate.max);
                }
            }
            mVotesStorage.updateVote(displayId, Vote.PRIORITY_PROXIMITY, vote);
        }
    }

    void dump(PrintWriter pw) {
        pw.println("  SensorObserver");
        synchronized (mSensorObserverLock) {
            pw.println("    mIsProxActive=" + mIsProxActive);
            pw.println("    mDozeStateByDisplay:");
            for (int i = 0; i < mDozeStateByDisplay.size(); i++) {
                final int id = mDozeStateByDisplay.keyAt(i);
                final boolean dozed = mDozeStateByDisplay.valueAt(i);
                pw.println("      " + id + " -> " + dozed);
            }
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
        boolean isDozeState = mInjector.isDozeState(mInjector.getDisplay(displayId));
        synchronized (mSensorObserverLock) {
            mDozeStateByDisplay.put(displayId, isDozeState);
            recalculateVotesLocked();
        }
    }

    @Override
    public void onDisplayChanged(int displayId) {
        synchronized (mSensorObserverLock) {
            boolean wasDozeState = mDozeStateByDisplay.get(displayId);
            mDozeStateByDisplay.put(displayId,
                    mInjector.isDozeState(mInjector.getDisplay(displayId)));
            if (wasDozeState != mDozeStateByDisplay.get(displayId)) {
                recalculateVotesLocked();
            }
        }
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        synchronized (mSensorObserverLock) {
            mDozeStateByDisplay.delete(displayId);
            recalculateVotesLocked();
        }
    }
}
