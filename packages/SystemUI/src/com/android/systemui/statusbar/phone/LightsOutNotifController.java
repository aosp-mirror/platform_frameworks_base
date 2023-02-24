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

import static com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentModule.LIGHTS_OUT_NOTIF_VIEW;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.view.InsetsVisibilities;
import android.view.View;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowInsetsController.Behavior;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;

import androidx.lifecycle.Observer;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.LetterboxDetails;
import com.android.internal.view.AppearanceRegion;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.notification.collection.NotifLiveDataStore;
import com.android.systemui.statusbar.phone.fragment.dagger.StatusBarFragmentScope;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Apps can request a low profile mode {@link View#SYSTEM_UI_FLAG_LOW_PROFILE}
 * where status bar and navigation icons dim. In this mode, a notification dot appears
 * where the notification icons would appear if they would be shown outside of this mode.
 *
 * This controller shows and hides the notification dot in the status bar to indicate
 * whether there are notifications when the device is in {@link View#SYSTEM_UI_FLAG_LOW_PROFILE}.
 */
@StatusBarFragmentScope
public class LightsOutNotifController extends ViewController<View> {
    private final CommandQueue mCommandQueue;
    private final NotifLiveDataStore mNotifDataStore;
    private final WindowManager mWindowManager;
    private final Observer<Boolean> mObserver = hasNotifs -> updateLightsOutView();

    /** @see android.view.WindowInsetsController#setSystemBarsAppearance(int, int) */
    @VisibleForTesting @Appearance int mAppearance;

    private int mDisplayId;

    @Inject
    LightsOutNotifController(
            @Named(LIGHTS_OUT_NOTIF_VIEW) View lightsOutNotifView,
            WindowManager windowManager,
            NotifLiveDataStore notifDataStore,
            CommandQueue commandQueue) {
        super(lightsOutNotifView);
        mWindowManager = windowManager;
        mNotifDataStore = notifDataStore;
        mCommandQueue = commandQueue;

    }

    @Override
    protected void onViewDetached() {
        mNotifDataStore.getHasActiveNotifs().removeObserver(mObserver);
        mCommandQueue.removeCallback(mCallback);
    }

    @Override
    protected void onViewAttached() {
        mView.setVisibility(View.GONE);
        mView.setAlpha(0f);

        mDisplayId = mWindowManager.getDefaultDisplay().getDisplayId();
        mNotifDataStore.getHasActiveNotifs().addSyncObserver(mObserver);
        mCommandQueue.addCallback(mCallback);

        updateLightsOutView();
    }

    private boolean hasActiveNotifications() {
        return mNotifDataStore.getHasActiveNotifs().getValue();
    }

    @VisibleForTesting
    void updateLightsOutView() {
        final boolean showDot = shouldShowDot();
        if (showDot != isShowingDot()) {
            if (showDot) {
                mView.setAlpha(0f);
                mView.setVisibility(View.VISIBLE);
            }

            mView.animate()
                    .alpha(showDot ? 1 : 0)
                    .setDuration(showDot ? 750 : 250)
                    .setInterpolator(new AccelerateInterpolator(2.0f))
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator a) {
                            mView.setAlpha(showDot ? 1 : 0);
                            mView.setVisibility(showDot ? View.VISIBLE : View.GONE);
                            // Unset the listener, otherwise this may persist for
                            // another view property animation
                            mView.animate().setListener(null);
                        }
                    })
                    .start();
        }
    }

    @VisibleForTesting
    boolean isShowingDot() {
        return mView.getVisibility() == View.VISIBLE
                && mView.getAlpha() == 1.0f;
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
                @Behavior int behavior, InsetsVisibilities requestedVisibilities,
                String packageName, LetterboxDetails[] letterboxDetails) {
            if (displayId != mDisplayId) {
                return;
            }
            mAppearance = appearance;
            updateLightsOutView();
        }
    };
}
