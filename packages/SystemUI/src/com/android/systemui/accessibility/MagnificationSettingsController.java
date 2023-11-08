/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.accessibility;

import static com.android.systemui.accessibility.WindowMagnificationSettings.MagnificationSize;

import android.annotation.NonNull;
import android.annotation.UiContext;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import android.util.Range;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.util.settings.SecureSettings;

/**
 * A class to control {@link WindowMagnificationSettings} and receive settings panel callbacks by
 * {@link WindowMagnificationSettingsCallback}.
 * The settings panel callbacks will be delegated through
 * {@link MagnificationSettingsController.Callback} to {@link Magnification}.
 */

public class MagnificationSettingsController implements ComponentCallbacks {

    // It should be consistent with the value defined in WindowMagnificationGestureHandler.
    private static final Range<Float> A11Y_ACTION_SCALE_RANGE = new Range<>(1.0f, 8.0f);

    private final Context mContext;

    private final int mDisplayId;

    @NonNull
    private final Callback mSettingsControllerCallback;

    // Window Magnification Setting view
    private WindowMagnificationSettings mWindowMagnificationSettings;

    private final Configuration mConfiguration;

    MagnificationSettingsController(
            @UiContext Context context,
            SfVsyncFrameCallbackProvider sfVsyncFrameProvider,
            @NonNull Callback settingsControllerCallback,
            SecureSettings secureSettings) {
        this(context, sfVsyncFrameProvider, settingsControllerCallback,  secureSettings, null);
    }

    @VisibleForTesting
    MagnificationSettingsController(
            @UiContext Context context,
            SfVsyncFrameCallbackProvider sfVsyncFrameProvider,
            @NonNull Callback settingsControllerCallback,
            SecureSettings secureSettings,
            WindowMagnificationSettings windowMagnificationSettings) {
        mContext = context.createWindowContext(
                context.getDisplay(),
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                null);
        mContext.setTheme(com.android.systemui.res.R.style.Theme_SystemUI);
        mDisplayId = mContext.getDisplayId();
        mConfiguration = new Configuration(mContext.getResources().getConfiguration());
        mSettingsControllerCallback = settingsControllerCallback;
        if (windowMagnificationSettings != null) {
            mWindowMagnificationSettings = windowMagnificationSettings;
        } else {
            mWindowMagnificationSettings = new WindowMagnificationSettings(mContext,
                    mWindowMagnificationSettingsCallback,
                    sfVsyncFrameProvider, secureSettings);
        }
    }

    /**
     * Toggles the visibility of magnification settings panel {@link WindowMagnificationSettings}.
     * We show the panel if it is not visible. Otherwise, hide the panel.
     */
    void toggleSettingsPanelVisibility() {
        if (!mWindowMagnificationSettings.isSettingPanelShowing()) {
            onConfigurationChanged(mContext.getResources().getConfiguration());
            mContext.registerComponentCallbacks(this);
        }
        mWindowMagnificationSettings.toggleSettingsPanelVisibility();
    }

    void closeMagnificationSettings() {
        mContext.unregisterComponentCallbacks(this);
        mWindowMagnificationSettings.hideSettingPanel();
    }

    boolean isMagnificationSettingsShowing() {
        return mWindowMagnificationSettings.isSettingPanelShowing();
    }

    void setMagnificationScale(float scale) {
        mWindowMagnificationSettings.setMagnificationScale(scale);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        final int configDiff = newConfig.diff(mConfiguration);
        mConfiguration.setTo(newConfig);
        onConfigurationChanged(configDiff);
    }

    @VisibleForTesting
    void onConfigurationChanged(int configDiff) {
        mWindowMagnificationSettings.onConfigurationChanged(configDiff);
    }

    @Override
    public void onLowMemory() {

    }

    interface Callback {

        /**
         * Called when change magnification size.
         *
         * @param displayId The logical display id.
         * @param index Magnification size index.
         *     0 : MagnificationSize.NONE,
         *     1 : MagnificationSize.SMALL,
         *     2 : MagnificationSize.MEDIUM,
         *     3 : MagnificationSize.LARGE,
         *     4 : MagnificationSize.FULLSCREEN
         */
        void onSetMagnifierSize(int displayId, @MagnificationSize int index);

        /**
         * Called when set allow diagonal scrolling.
         *
         * @param displayId The logical display id.
         * @param enable Allow diagonal scrolling enable value.
         */
        void onSetDiagonalScrolling(int displayId, boolean enable);

        /**
         * Called when change magnification size on free mode.
         *
         * @param displayId The logical display id.
         * @param enable Free mode enable value.
         */
        void onEditMagnifierSizeMode(int displayId, boolean enable);

        /**
         * Called when set magnification scale.
         *
         * @param displayId The logical display id.
         * @param scale Magnification scale value.
         * @param updatePersistence whether the new scale should be persisted.
         */
        void onMagnifierScale(int displayId, float scale, boolean updatePersistence);

        /**
         * Called when magnification mode changed.
         *
         * @param displayId The logical display id.
         * @param newMode Magnification mode
         *      1 : ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN,
         *      2 : ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW
         */
        void onModeSwitch(int displayId, int newMode);

        /**
         * Called when the visibility of the magnification settings panel changed.
         *
         * @param displayId The logical display id.
         * @param shown The visibility of the magnification settings panel.
         */
        void onSettingsPanelVisibilityChanged(int displayId, boolean shown);
    }

    @VisibleForTesting
    final WindowMagnificationSettingsCallback mWindowMagnificationSettingsCallback =
            new WindowMagnificationSettingsCallback() {
                @Override
        public void onSetDiagonalScrolling(boolean enable) {
            mSettingsControllerCallback.onSetDiagonalScrolling(mDisplayId, enable);
        }

        @Override
        public void onModeSwitch(int newMode) {
            mSettingsControllerCallback.onModeSwitch(mDisplayId, newMode);
        }

        @Override
        public void onSettingsPanelVisibilityChanged(boolean shown) {
            mSettingsControllerCallback.onSettingsPanelVisibilityChanged(mDisplayId, shown);
        }

        @Override
        public void onSetMagnifierSize(@MagnificationSize int index) {
            mSettingsControllerCallback.onSetMagnifierSize(mDisplayId, index);
        }

        @Override
        public void onEditMagnifierSizeMode(boolean enable) {
            mSettingsControllerCallback.onEditMagnifierSizeMode(mDisplayId, enable);
        }

        @Override
        public void onMagnifierScale(float scale, boolean updatePersistence) {
            mSettingsControllerCallback.onMagnifierScale(mDisplayId,
                    A11Y_ACTION_SCALE_RANGE.clamp(scale), updatePersistence);
        }
    };
}
