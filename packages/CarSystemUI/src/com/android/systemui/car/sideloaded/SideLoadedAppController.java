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

package com.android.systemui.car.sideloaded;

import android.app.IActivityTaskManager;
import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import com.android.systemui.SystemUI;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Controller responsible for detecting unsafe apps.
 */
@Singleton
public class SideLoadedAppController extends SystemUI {
    private static final String TAG = SideLoadedAppController.class.getSimpleName();

    private IActivityTaskManager mActivityTaskManager;
    private SideLoadedAppListener mSideLoadedAppListener;
    private SideLoadedAppDetector mSideLoadedAppDetector;
    private SideLoadedAppStateController mSideLoadedAppStateController;

    @Inject
    public SideLoadedAppController(Context context,
            IActivityTaskManager activityTaskManager,
            SideLoadedAppDetector sideLoadedAppDetector,
            SideLoadedAppListener sideLoadedAppListener,
            SideLoadedAppStateController sideLoadedAppStateController) {
        super(context);

        mSideLoadedAppDetector = sideLoadedAppDetector;
        mActivityTaskManager = activityTaskManager;
        mSideLoadedAppListener = sideLoadedAppListener;
        mSideLoadedAppStateController = sideLoadedAppStateController;
    }

    @Override
    public void start() {
    }

    @Override
    protected void onBootCompleted() {
        Log.i(TAG, "OnBootCompleted");

        try {
            mActivityTaskManager.registerTaskStackListener(mSideLoadedAppListener);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not register car side loaded app listener.", e);
        }

        if (mSideLoadedAppDetector.hasUnsafeInstalledApps()) {
            mSideLoadedAppStateController.onUnsafeInstalledAppsDetected();
        }
    }
}
