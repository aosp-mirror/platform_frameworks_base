/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.legacy;

import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.collection.provider.VisualStabilityProvider;
import com.android.systemui.statusbar.notification.dagger.NotificationsModule;

/**
 * A manager that ensures that notifications are visually stable. It will suppress reorderings
 * and reorder at the right time when they are out of view.
 */
public class VisualStabilityManager {

    private final VisualStabilityProvider mVisualStabilityProvider;

    private boolean mPanelExpanded;
    private boolean mScreenOn;
    private boolean mPulsing;

    /**
     * Injected constructor. See {@link NotificationsModule}.
     */
    public VisualStabilityManager(
            VisualStabilityProvider visualStabilityProvider,
            StatusBarStateController statusBarStateController,
            WakefulnessLifecycle wakefulnessLifecycle) {

        mVisualStabilityProvider = visualStabilityProvider;

        if (statusBarStateController != null) {
            setPulsing(statusBarStateController.isPulsing());
            statusBarStateController.addCallback(new StatusBarStateController.StateListener() {
                @Override
                public void onPulsingChanged(boolean pulsing) {
                    setPulsing(pulsing);
                }

                @Override
                public void onExpandedChanged(boolean expanded) {
                    setPanelExpanded(expanded);
                }
            });
        }

        if (wakefulnessLifecycle != null) {
            wakefulnessLifecycle.addObserver(mWakefulnessObserver);
        }
    }

    /**
     * @param screenOn whether the screen is on
     */
    private void setScreenOn(boolean screenOn) {
        mScreenOn = screenOn;
        updateAllowedStates();
    }

    /**
     * Set the panel to be expanded.
     */
    private void setPanelExpanded(boolean expanded) {
        mPanelExpanded = expanded;
        updateAllowedStates();
    }

    /**
     * @param pulsing whether we are currently pulsing for ambient display.
     */
    private void setPulsing(boolean pulsing) {
        if (mPulsing == pulsing) {
            return;
        }
        mPulsing = pulsing;
        updateAllowedStates();
    }

    private void updateAllowedStates() {
        boolean reorderingAllowed = (!mScreenOn || !mPanelExpanded) && !mPulsing;
        mVisualStabilityProvider.setReorderingAllowed(reorderingAllowed);
    }

    final WakefulnessLifecycle.Observer mWakefulnessObserver = new WakefulnessLifecycle.Observer() {
        @Override
        public void onFinishedGoingToSleep() {
            setScreenOn(false);
        }

        @Override
        public void onStartedWakingUp() {
            setScreenOn(true);
        }
    };


    /**
     * See {@link Callback#onChangeAllowed()}
     */
    public interface Callback {

        /**
         * Called when changing is allowed again.
         */
        void onChangeAllowed();
    }
}
