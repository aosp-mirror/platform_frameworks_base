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

package com.android.systemui.accessibility.floatingmenu;

import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_BUTTON;

import static com.android.internal.accessibility.dialog.AccessibilityTargetHelper.getTargets;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Contains logic for an accessibility floating menu view.
 */
public class AccessibilityFloatingMenu implements IAccessibilityFloatingMenu {
    private final Context mContext;
    private final AccessibilityFloatingMenuView mMenuView;

    private final ContentObserver mContentObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    mMenuView.onTargetsChanged(getTargets(mContext, ACCESSIBILITY_BUTTON));
                }
            };

    public AccessibilityFloatingMenu(Context context) {
        mContext = context;
        mMenuView = new AccessibilityFloatingMenuView(context);
    }

    @VisibleForTesting
    AccessibilityFloatingMenu(Context context, AccessibilityFloatingMenuView menuView) {
        mContext = context;
        mMenuView = menuView;
    }

    @Override
    public boolean isShowing() {
        return mMenuView.isShowing();
    }

    @Override
    public void show() {
        if (isShowing()) {
            return;
        }

        mMenuView.onTargetsChanged(getTargets(mContext, ACCESSIBILITY_BUTTON));
        mMenuView.show();

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS),
                /* notifyForDescendants */ false, mContentObserver,
                UserHandle.USER_CURRENT);
    }

    @Override
    public void hide() {
        if (!isShowing()) {
            return;
        }

        mMenuView.hide();

        mContext.getContentResolver().unregisterContentObserver(mContentObserver);
    }
}
