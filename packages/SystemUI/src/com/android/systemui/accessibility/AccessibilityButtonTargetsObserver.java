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

import android.content.Context;
import android.provider.Settings;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.android.systemui.dagger.SysUISingleton;

import javax.inject.Inject;

/**
 * Controller for tracking the current accessibility button list.
 *
 * @see Settings.Secure#ACCESSIBILITY_BUTTON_TARGETS
 */
@MainThread
@SysUISingleton
public class AccessibilityButtonTargetsObserver extends
        SecureSettingsContentObserver<AccessibilityButtonTargetsObserver.TargetsChangedListener> {

    /** Listener for accessibility button targets changes. */
    public interface TargetsChangedListener {

        /**
         * Called when accessibility button targets changes.
         *
         * @param targets Current content of {@link Settings.Secure#ACCESSIBILITY_BUTTON_TARGETS}
         */
        void onAccessibilityButtonTargetsChanged(String targets);
    }

    @Inject
    public AccessibilityButtonTargetsObserver(Context context) {
        super(context, Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS);
    }

    @Override
    void onValueChanged(TargetsChangedListener listener, String value) {
        listener.onAccessibilityButtonTargetsChanged(value);
    }

    /** Returns the current string from settings key
     *  {@link Settings.Secure#ACCESSIBILITY_BUTTON_TARGETS}. */
    @Nullable
    public String getCurrentAccessibilityButtonTargets() {
        return getSettingsValue();
    }
}
