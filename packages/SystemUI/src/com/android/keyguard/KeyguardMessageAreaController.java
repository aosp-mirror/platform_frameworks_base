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

package com.android.keyguard;

import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.VisibleForTesting;

import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.util.ViewController;

import java.lang.ref.WeakReference;

import javax.inject.Inject;

/**
 * Controller for a {@link KeyguardMessageAreaController}.
 * @param <T> A subclass of KeyguardMessageArea.
 */
public class KeyguardMessageAreaController<T extends KeyguardMessageArea>
        extends ViewController<T> {
    /**
     * Delay before speaking an accessibility announcement. Used to prevent
     * lift-to-type from interrupting itself.
     */
    private static final long ANNOUNCEMENT_DELAY = 250;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final ConfigurationController mConfigurationController;
    private final AnnounceRunnable mAnnounceRunnable;

    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {
        public void onFinishedGoingToSleep(int why) {
            mView.setSelected(false);
        }

        public void onStartedWakingUp() {
            mView.setSelected(true);
        }
    };

    private ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onConfigChanged(Configuration newConfig) {
            mView.onConfigChanged();
        }

        @Override
        public void onThemeChanged() {
            mView.onThemeChanged();
        }

        @Override
        public void onDensityOrFontScaleChanged() {
            mView.onDensityOrFontScaleChanged();
        }
    };

    protected KeyguardMessageAreaController(T view,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            ConfigurationController configurationController) {
        super(view);

        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mConfigurationController = configurationController;
        mAnnounceRunnable = new AnnounceRunnable(mView);
    }

    @Override
    protected void onViewAttached() {
        mConfigurationController.addCallback(mConfigurationListener);
        mKeyguardUpdateMonitor.registerCallback(mInfoCallback);
        mView.setSelected(mKeyguardUpdateMonitor.isDeviceInteractive());
        mView.onThemeChanged();
    }

    @Override
    protected void onViewDetached() {
        mConfigurationController.removeCallback(mConfigurationListener);
        mKeyguardUpdateMonitor.removeCallback(mInfoCallback);
    }

    /**
     * Indicate that view is visible and can display messages.
     */
    public void setIsVisible(boolean isVisible) {
        mView.setIsVisible(isVisible);
    }

    public void setMessage(CharSequence s) {
        setMessage(s, true);
    }

    /**
     * Sets a message to the underlying text view.
     */
    public void setMessage(CharSequence s, boolean animate) {
        mView.setMessage(s, animate);
        CharSequence msg = mView.getText();
        if (!TextUtils.isEmpty(msg)) {
            mView.removeCallbacks(mAnnounceRunnable);
            mAnnounceRunnable.setTextToAnnounce(msg);
            mView.postDelayed(mAnnounceRunnable, ANNOUNCEMENT_DELAY);
        }
    }

    public void setMessage(int resId) {
        String message = resId != 0 ? mView.getResources().getString(resId) : null;
        setMessage(message);
    }

    public void setNextMessageColor(ColorStateList colorState) {
        mView.setNextMessageColor(colorState);
    }

    /** Returns the message of the underlying TextView. */
    public CharSequence getMessage() {
        return mView.getText();
    }

    /** Factory for creating {@link com.android.keyguard.KeyguardMessageAreaController}. */
    public static class Factory {
        private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
        private final ConfigurationController mConfigurationController;

        @Inject
        public Factory(KeyguardUpdateMonitor keyguardUpdateMonitor,
                ConfigurationController configurationController) {
            mKeyguardUpdateMonitor = keyguardUpdateMonitor;
            mConfigurationController = configurationController;
        }

        /** Build a new {@link KeyguardMessageAreaController}. */
        public KeyguardMessageAreaController create(KeyguardMessageArea view) {
            return new KeyguardMessageAreaController(
                    view, mKeyguardUpdateMonitor, mConfigurationController);
        }
    }

    /**
     * Runnable used to delay accessibility announcements.
     */
    @VisibleForTesting
    public static class AnnounceRunnable implements Runnable {
        private final WeakReference<View> mHost;
        private CharSequence mTextToAnnounce;

        AnnounceRunnable(View host) {
            mHost = new WeakReference<>(host);
        }

        /** Sets the text to announce. */
        public void setTextToAnnounce(CharSequence textToAnnounce) {
            mTextToAnnounce = textToAnnounce;
        }

        @Override
        public void run() {
            final View host = mHost.get();
            if (host != null) {
                host.announceForAccessibility(mTextToAnnounce);
            }
        }
    }
}
