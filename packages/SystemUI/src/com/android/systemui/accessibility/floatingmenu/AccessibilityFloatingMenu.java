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

import static android.provider.Settings.Secure.ACCESSIBILITY_FLOATING_MENU_FADE_ENABLED;
import static android.provider.Settings.Secure.ACCESSIBILITY_FLOATING_MENU_ICON_TYPE;
import static android.provider.Settings.Secure.ACCESSIBILITY_FLOATING_MENU_OPACITY;
import static android.provider.Settings.Secure.ACCESSIBILITY_FLOATING_MENU_SIZE;
import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_BUTTON;

import static com.android.internal.accessibility.dialog.AccessibilityTargetHelper.getTargets;
import static com.android.systemui.accessibility.floatingmenu.AccessibilityFloatingMenuView.ShapeType;
import static com.android.systemui.accessibility.floatingmenu.AccessibilityFloatingMenuView.SizeType;

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
    private static final int DEFAULT_FADE_EFFECT_ENABLED = 1;
    private static final float DEFAULT_OPACITY_VALUE = 0.55f;
    private final Context mContext;
    private final AccessibilityFloatingMenuView mMenuView;

    private final ContentObserver mContentObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    mMenuView.onTargetsChanged(getTargets(mContext, ACCESSIBILITY_BUTTON));
                }
            };

    private final ContentObserver mSizeContentObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    mMenuView.setSizeType(getSizeType(mContext));
                }
            };

    private final ContentObserver mShapeContentObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    mMenuView.setShapeType(getShapeType(mContext));
                }
            };

    private final ContentObserver mFadeOutContentObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    mMenuView.updateOpacityWith(isFadeEffectEnabled(mContext),
                            getOpacityValue(mContext));
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

        mMenuView.show();
        mMenuView.onTargetsChanged(getTargets(mContext, ACCESSIBILITY_BUTTON));
        mMenuView.updateOpacityWith(isFadeEffectEnabled(mContext),
                getOpacityValue(mContext));
        mMenuView.setSizeType(getSizeType(mContext));
        mMenuView.setShapeType(getShapeType(mContext));

        registerContentObservers();
    }

    @Override
    public void hide() {
        if (!isShowing()) {
            return;
        }

        mMenuView.hide();

        unregisterContentObservers();
    }

    private static boolean isFadeEffectEnabled(Context context) {
        return Settings.Secure.getInt(
                context.getContentResolver(), ACCESSIBILITY_FLOATING_MENU_FADE_ENABLED,
                DEFAULT_FADE_EFFECT_ENABLED) == /* enable */ 1;
    }

    private static float getOpacityValue(Context context) {
        return Settings.Secure.getFloat(
                context.getContentResolver(), ACCESSIBILITY_FLOATING_MENU_OPACITY,
                DEFAULT_OPACITY_VALUE);
    }

    private static int getSizeType(Context context) {
        return Settings.Secure.getInt(
                context.getContentResolver(), ACCESSIBILITY_FLOATING_MENU_SIZE, SizeType.SMALL);
    }

    private static int getShapeType(Context context) {
        return Settings.Secure.getInt(
                context.getContentResolver(), ACCESSIBILITY_FLOATING_MENU_ICON_TYPE,
                ShapeType.CIRCLE);
    }

    private void registerContentObservers() {
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS),
                /* notifyForDescendants */ false, mContentObserver,
                UserHandle.USER_CURRENT);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_FLOATING_MENU_SIZE),
                /* notifyForDescendants */ false, mSizeContentObserver,
                UserHandle.USER_CURRENT);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_FLOATING_MENU_ICON_TYPE),
                /* notifyForDescendants */ false, mShapeContentObserver,
                UserHandle.USER_CURRENT);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_FLOATING_MENU_FADE_ENABLED),
                /* notifyForDescendants */ false, mFadeOutContentObserver,
                UserHandle.USER_CURRENT);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_FLOATING_MENU_OPACITY),
                /* notifyForDescendants */ false, mFadeOutContentObserver,
                UserHandle.USER_CURRENT);
    }

    private void unregisterContentObservers() {
        mContext.getContentResolver().unregisterContentObserver(mContentObserver);
        mContext.getContentResolver().unregisterContentObserver(mSizeContentObserver);
        mContext.getContentResolver().unregisterContentObserver(mShapeContentObserver);
        mContext.getContentResolver().unregisterContentObserver(mFadeOutContentObserver);
    }
}
