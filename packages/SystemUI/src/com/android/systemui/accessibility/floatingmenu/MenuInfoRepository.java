/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.accessibility.floatingmenu;

import static android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_BUTTON;

import static com.android.internal.accessibility.dialog.AccessibilityTargetHelper.getTargets;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.internal.accessibility.dialog.AccessibilityTarget;

import java.util.List;

/**
 * Stores and observe the settings contents for the menu view.
 */
class MenuInfoRepository {
    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final OnSettingsContentsChanged mSettingsContentsCallback;

    private final ContentObserver mMenuTargetFeaturesContentObserver =
            new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    mSettingsContentsCallback.onTargetFeaturesChanged(
                            getTargets(mContext, ACCESSIBILITY_BUTTON));
                }
            };

    MenuInfoRepository(Context context, OnSettingsContentsChanged settingsContentsChanged) {
        mContext = context;
        mSettingsContentsCallback = settingsContentsChanged;
    }

    void loadMenuTargetFeatures(OnInfoReady<List<AccessibilityTarget>> callback) {
        callback.onReady(getTargets(mContext, ACCESSIBILITY_BUTTON));
    }

    void registerContentObservers() {
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS),
                /* notifyForDescendants */ false, mMenuTargetFeaturesContentObserver,
                UserHandle.USER_CURRENT);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(ENABLED_ACCESSIBILITY_SERVICES),
                /* notifyForDescendants */ false,
                mMenuTargetFeaturesContentObserver, UserHandle.USER_CURRENT);
    }

    void unregisterContentObservers() {
        mContext.getContentResolver().unregisterContentObserver(mMenuTargetFeaturesContentObserver);
    }

    interface OnSettingsContentsChanged {
        void onTargetFeaturesChanged(List<AccessibilityTarget> newTargetFeatures);
    }

    interface OnInfoReady<T> {
        void onReady(T info);
    }
}
