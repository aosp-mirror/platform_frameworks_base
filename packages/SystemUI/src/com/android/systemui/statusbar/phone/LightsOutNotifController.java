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

package com.android.systemui.statusbar.phone;

import static android.view.WindowInsetsController.APPEARANCE_LOW_PROFILE_BARS;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.Nullable;
import android.view.View;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowInsetsController.Behavior;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.view.AppearanceRegion;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import javax.inject.Inject;

/**
 * Apps can request a low profile mode {@link View.SYSTEM_UI_FLAG_LOW_PROFILE}
 * where status bar and navigation icons dim. In this mode, a notification dot appears
 * where the notification icons would appear if they would be shown outside of this mode.
 *
 * This controller shows and hides the notification dot in the status bar to indicate
 * whether there are notifications when the device is in {@link View.SYSTEM_UI_FLAG_LOW_PROFILE}.
 */
@SysUISingleton
public class LightsOutNotifController {
    private final CommandQueue mCommandQueue;
    private final NotificationEntryManager mEntryManager;
    private final WindowManager mWindowManager;

    /** @see android.view.WindowInsetsController#setSystemBarsAppearance(int) */
    @VisibleForTesting @Appearance int mAppearance;

    private int mDisplayId;
    private View mLightsOutNotifView;

    @Inject
    LightsOutNotifController(WindowManager windowManager,
            NotificationEntryManager entryManager,
            CommandQueue commandQueue) {
        mWindowManager = windowManager;
        mEntryManager = entryManager;
        mCommandQueue = commandQueue;
    }

    /**
     * Sets the notification dot view after it is created in the StatusBar.
     * This is the view this controller will show and hide depending on whether:
     * 1. there are active notifications
     * 2. an app has requested {@link View.SYSTEM_UI_FLAG_LOW_PROFILE}
     */
    void setLightsOutNotifView(View lightsOutNotifView) {
        destroy();
        mLightsOutNotifView = lightsOutNotifView;

        if (mLightsOutNotifView != null) {
            mLightsOutNotifView.setVisibility(View.GONE);
            mLightsOutNotifView.setAlpha(0f);
            init();
        }
    }

    private void destroy() {
        mEntryManager.removeNotificationEntryListener(mEntryListener);
        mCommandQueue.removeCallback(mCallback);
    }

    private void init() {
        mDisplayId = mWindowManager.getDefaultDisplay().getDisplayId();
        mEntryManager.addNotificationEntryListener(mEntryListener);
        mCommandQueue.addCallback(mCallback);

        updateLightsOutView();
    }

    private boolean hasActiveNotifications() {
        return mEntryManager.hasActiveNotifications();
    }

    @VisibleForTesting
    void updateLightsOutView() {
        if (mLightsOutNotifView == null) {
            return;
        }

        final boolean showDot = shouldShowDot();
        if (showDot != isShowingDot()) {
            if (showDot) {
                mLightsOutNotifView.setAlpha(0f);
                mLightsOutNotifView.setVisibility(View.VISIBLE);
            }

            mLightsOutNotifView.animate()
                    .alpha(showDot ? 1 : 0)
                    .setDuration(showDot ? 750 : 250)
                    .setInterpolator(new AccelerateInterpolator(2.0f))
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator a) {
                            mLightsOutNotifView.setAlpha(showDot ? 1 : 0);
                            mLightsOutNotifView.setVisibility(showDot ? View.VISIBLE : View.GONE);
                        }
                    })
                    .start();
        }
    }

    @VisibleForTesting
    boolean isShowingDot() {
        return mLightsOutNotifView.getVisibility() == View.VISIBLE
                && mLightsOutNotifView.getAlpha() == 1.0f;
    }

    @VisibleForTesting
    boolean shouldShowDot() {
        return hasActiveNotifications() && areLightsOut();
    }

    @VisibleForTesting
    boolean areLightsOut() {
        return 0 != (mAppearance & APPEARANCE_LOW_PROFILE_BARS);
    }

    private final CommandQueue.Callbacks mCallback = new CommandQueue.Callbacks() {
        @Override
        public void onSystemBarAttributesChanged(int displayId, @Appearance int appearance,
                AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme,
                @Behavior int behavior, boolean isFullscreen) {
            if (displayId != mDisplayId) {
                return;
            }
            mAppearance = appearance;
            updateLightsOutView();
        }
    };

    private final NotificationEntryListener mEntryListener = new NotificationEntryListener() {
        // Cares about notifications post-filtering
        @Override
        public void onNotificationAdded(NotificationEntry entry) {
            updateLightsOutView();
        }

        @Override
        public void onPostEntryUpdated(NotificationEntry entry) {
            updateLightsOutView();
        }

        @Override
        public void onEntryRemoved(@Nullable NotificationEntry entry,
                NotificationVisibility visibility, boolean removedByUser, int reason) {
            updateLightsOutView();
        }
    };
}
