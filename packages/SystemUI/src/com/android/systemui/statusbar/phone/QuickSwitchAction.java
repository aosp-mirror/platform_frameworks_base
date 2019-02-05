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
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.Log;
import android.view.MotionEvent;

import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.recents.utilities.Utilities;

/**
 * QuickSwitch action to send to launcher
 */
public class QuickSwitchAction extends NavigationGestureAction {
    private static final String TAG = "QuickSwitchAction";

    protected final Rect mDragOverRect = new Rect();

    public QuickSwitchAction(@NonNull NavigationBarView navigationBar,
            @NonNull OverviewProxyService service) {
        super(navigationBar, service);
    }

    @Override
    public void setBarState(boolean changed, int navBarPos, boolean dragHorPositive,
            boolean dragVerPositive) {
        super.setBarState(changed, navBarPos, dragHorPositive, dragVerPositive);
        if (changed && isActive()) {
            // End quickscrub if the state changes mid-transition
            endQuickGesture(false /* animate */);
        }
    }

    @Override
    public boolean isEnabled() {
        return mNavigationBarView.isQuickScrubEnabled();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        mDragOverRect.set(top, left, right, bottom);
    }

    @Override
    public boolean disableProxyEvents() {
        return true;
    }

    @Override
    protected void onGestureStart(MotionEvent event) {
        startQuickGesture(event);
    }

    @Override
    public void onGestureMove(int x, int y) {
        int dragLength, offset;
        if (isNavBarVertical()) {
            dragLength = mDragOverRect.height();
            offset = y - mDragOverRect.top;
        } else {
            offset = x - mDragOverRect.left;
            dragLength = mDragOverRect.width();
        }
        if (!mDragHorizontalPositive || !mDragVerticalPositive) {
            offset -= dragLength;
        }
        float scrubFraction = Utilities.clamp(Math.abs(offset) * 1f / dragLength, 0, 1);
        try {
            mProxySender.getProxy().onQuickScrubProgress(scrubFraction);
            if (DEBUG_OVERVIEW_PROXY) {
                Log.d(TAG_OPS, "Quick Switch Progress:" + scrubFraction);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send progress of quick switch.", e);
        }
    }

    @Override
    protected void onGestureEnd() {
        endQuickGesture(true /* animate */);
    }

    protected void startQuickGesture(MotionEvent event) {
        // Disable slippery for quick scrub to not cancel outside the nav bar
        mNavigationBarView.updateSlippery();

        try {
            mProxySender.getProxy().onQuickScrubStart();
            if (DEBUG_OVERVIEW_PROXY) {
                Log.d(TAG_OPS, "Quick Scrub Start");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send start of quick scrub.", e);
        }
        mProxySender.notifyQuickScrubStarted();
    }

    protected void endQuickGesture(boolean animate) {
        try {
            mProxySender.getProxy().onQuickScrubEnd();
            if (DEBUG_OVERVIEW_PROXY) {
                Log.d(TAG_OPS, "Quick Scrub End");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send end of quick scrub.", e);
        }
    }
}
