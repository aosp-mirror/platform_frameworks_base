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

package com.android.systemui.accessibility;

import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_GESTURE;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR;

import android.annotation.IntDef;
import android.annotation.MainThread;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.settings.SecureSettings;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Inject;

/**
 * Observes changes of the accessibility button mode
 * {@link Settings.Secure#ACCESSIBILITY_BUTTON_MODE} and notify its listeners.
 */
@MainThread
@SysUISingleton
public class AccessibilityButtonModeObserver extends
        SecureSettingsContentObserver<AccessibilityButtonModeObserver.ModeChangedListener> {

    private static final String TAG = "A11yButtonModeObserver";

    private static final int ACCESSIBILITY_BUTTON_MODE_DEFAULT =
            ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR,
            ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU,
            ACCESSIBILITY_BUTTON_MODE_GESTURE
    })
    public @interface AccessibilityButtonMode {}

    /** Listener for accessibility button mode changes. */
    public interface ModeChangedListener {

        /**
         * Called when accessibility button mode changes.
         *
         * @param mode Current accessibility button mode
         */
        void onAccessibilityButtonModeChanged(@AccessibilityButtonMode int mode);
    }

    @Inject
    public AccessibilityButtonModeObserver(
            Context context, UserTracker userTracker, SecureSettings secureSettings) {
        super(context, userTracker, secureSettings, Settings.Secure.ACCESSIBILITY_BUTTON_MODE);
    }

    @Override
    void onValueChanged(ModeChangedListener listener, String value) {
        final int mode = parseAccessibilityButtonMode(value);
        listener.onAccessibilityButtonModeChanged(mode);
    }

    /**
     * Gets the current accessibility button mode from the current user's settings.
     *
     * See {@link Settings.Secure#ACCESSIBILITY_BUTTON_MODE}.
     */
    public int getCurrentAccessibilityButtonMode() {
        final String value = getSettingsValue();

        return parseAccessibilityButtonMode(value);
    }

    private int parseAccessibilityButtonMode(String value) {
        int mode;

        try {
            mode = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid string for  " + e);
            mode = ACCESSIBILITY_BUTTON_MODE_DEFAULT;
        }

        return mode;
    }
}
