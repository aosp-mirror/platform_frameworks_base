/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityManager.AccessibilityServicesStateChangeListener;

import androidx.annotation.NonNull;

import com.android.systemui.dagger.SysUISingleton;

import javax.inject.Inject;

/**
 * For mocking because AccessibilityManager is final for some reason...
 */
@SysUISingleton
public class AccessibilityManagerWrapper implements
        CallbackController<AccessibilityServicesStateChangeListener> {

    private final AccessibilityManager mAccessibilityManager;

    @Inject
    public AccessibilityManagerWrapper(Context context) {
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
    }

    @Override
    public void addCallback(@NonNull AccessibilityServicesStateChangeListener listener) {
        mAccessibilityManager.addAccessibilityServicesStateChangeListener(listener);
    }

    @Override
    public void removeCallback(@NonNull AccessibilityServicesStateChangeListener listener) {
        mAccessibilityManager.removeAccessibilityServicesStateChangeListener(listener);
    }

    public void addAccessibilityStateChangeListener(
            AccessibilityManager.AccessibilityStateChangeListener listener) {
        mAccessibilityManager.addAccessibilityStateChangeListener(listener);
    }

    public void removeAccessibilityStateChangeListener(
            AccessibilityManager.AccessibilityStateChangeListener listener) {
        mAccessibilityManager.removeAccessibilityStateChangeListener(listener);
    }

    public boolean isEnabled() {
        return mAccessibilityManager.isEnabled();
    }

    public void sendAccessibilityEvent(AccessibilityEvent event) {
        mAccessibilityManager.sendAccessibilityEvent(event);
    }

    /** Returns a recommended ui timeout value in milliseconds. */
    public int getRecommendedTimeoutMillis(int originalTimeout, int uiContentFlags) {
        return mAccessibilityManager.getRecommendedTimeoutMillis(originalTimeout, uiContentFlags);
    }
}
