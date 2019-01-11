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

import static com.android.systemui.recents.OverviewProxyService.DEBUG_OVERVIEW_PROXY;
import static com.android.systemui.recents.OverviewProxyService.TAG_OPS;

import android.annotation.NonNull;
import android.os.RemoteException;
import android.util.Log;
import android.view.MotionEvent;

import com.android.systemui.recents.OverviewProxyService;

/**
 * QuickStep action to send to launcher to start overview
 */
public class QuickStepAction extends NavigationGestureAction {
    private static final String TAG = "QuickStepAction";

    public QuickStepAction(@NonNull NavigationBarView navigationBarView,
            @NonNull OverviewProxyService service) {
        super(navigationBarView, service);
    }

    @Override
    public boolean canRunWhenNotificationsShowing() {
        return false;
    }

    @Override
    public boolean isEnabled() {
        return mNavigationBarView.isQuickStepSwipeUpEnabled();
    }

    protected boolean requiresStableTaskList() {
        return true;
    }

    @Override
    public void onGestureStart(MotionEvent event) {
        try {
            mProxySender.getProxy().onQuickStep(event);
            if (DEBUG_OVERVIEW_PROXY) {
                Log.d(TAG_OPS, "Quick Step Start");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send quick step started.", e);
        }
        mProxySender.notifyQuickStepStarted();
    }
}
