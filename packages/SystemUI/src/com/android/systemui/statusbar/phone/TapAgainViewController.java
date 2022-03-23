/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.android.systemui.classifier.FalsingModule.DOUBLE_TAP_TIMEOUT_MS;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.concurrency.DelayableExecutor;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Controller for {@link TapAgainView}.
 */
@StatusBarComponent.StatusBarScope
public class TapAgainViewController extends ViewController<TapAgainView> {
    private final DelayableExecutor mDelayableExecutor;
    private final ConfigurationController mConfigurationController;
    private final long mDoubleTapTimeMs;

    private Runnable mHideCanceler;

    @VisibleForTesting
    final ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onOverlayChanged() {
            mView.updateColor();
        }

        @Override
        public void onUiModeChanged() {
            mView.updateColor();
        }

        @Override
        public void onThemeChanged() {
            mView.updateColor();
        }
    };

    @Inject
    protected TapAgainViewController(TapAgainView view,
            @Main DelayableExecutor delayableExecutor,
            ConfigurationController configurationController,
            @Named(DOUBLE_TAP_TIMEOUT_MS) long doubleTapTimeMs) {
        super(view);
        mDelayableExecutor = delayableExecutor;
        mConfigurationController = configurationController;
        mDoubleTapTimeMs = doubleTapTimeMs;
    }

    @Override
    protected void onViewAttached() {
        mConfigurationController.addCallback(mConfigurationListener);
    }

    @Override
    protected void onViewDetached() {
        mConfigurationController.removeCallback(mConfigurationListener);
    }

    /** Shows the associated view, possibly animating it. */
    public void show() {
        if (mHideCanceler != null) {
            mHideCanceler.run();
        }
        mView.animateIn();
        mHideCanceler = mDelayableExecutor.executeDelayed(this::hide, mDoubleTapTimeMs);
    }

    /** Hides the associated view, possibly animating it. */
    public void hide() {
        mHideCanceler = null;
        mView.animateOut();
    }
}
