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
import static android.provider.Settings.Secure.ACCESSIBILITY_FLOATING_MENU_MIGRATION_TOOLTIP_PROMPT;
import static android.provider.Settings.Secure.ACCESSIBILITY_FLOATING_MENU_OPACITY;
import static android.provider.Settings.Secure.ACCESSIBILITY_FLOATING_MENU_SIZE;
import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_BUTTON;

import static com.android.internal.accessibility.dialog.AccessibilityTargetHelper.getTargets;
import static com.android.systemui.Prefs.Key.HAS_SEEN_ACCESSIBILITY_FLOATING_MENU_DOCK_TOOLTIP;
import static com.android.systemui.accessibility.floatingmenu.AccessibilityFloatingMenuView.ShapeType;
import static com.android.systemui.accessibility.floatingmenu.AccessibilityFloatingMenuView.SizeType;

import android.annotation.FloatRange;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Prefs;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.util.settings.SecureSettings;

import java.util.List;

/**
 * Contains logic for an accessibility floating menu view.
 */
public class AccessibilityFloatingMenu implements IAccessibilityFloatingMenu {
    private static final int DEFAULT_FADE_EFFECT_IS_ENABLED = 1;
    private static final int DEFAULT_MIGRATION_TOOLTIP_PROMPT_IS_DISABLED = 0;
    @FloatRange(from = 0.0, to = 1.0)
    private static final float DEFAULT_OPACITY_VALUE = 0.55f;
    @FloatRange(from = 0.0, to = 1.0)
    private static final float DEFAULT_POSITION_X_PERCENT = 1.0f;
    @FloatRange(from = 0.0, to = 1.0)
    private static final float DEFAULT_POSITION_Y_PERCENT = 0.9f;

    private final Context mContext;
    private final SecureSettings mSecureSettings;
    private final AccessibilityFloatingMenuView mMenuView;
    private final MigrationTooltipView mMigrationTooltipView;
    private final DockTooltipView mDockTooltipView;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final ContentObserver mContentObserver =
            new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    mMenuView.onTargetsChanged(getTargets(mContext, ACCESSIBILITY_BUTTON));
                }
            };

    private final ContentObserver mSizeContentObserver =
            new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    mMenuView.setSizeType(getSizeType());
                }
            };

    private final ContentObserver mFadeOutContentObserver =
            new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    mMenuView.updateOpacityWith(isFadeEffectEnabled(),
                            getOpacityValue());
                }
            };

    private final ContentObserver mEnabledA11yServicesContentObserver =
            new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    mMenuView.onEnabledFeaturesChanged();
                }
            };

    public AccessibilityFloatingMenu(Context context, SecureSettings secureSettings) {
        mContext = context;
        mSecureSettings = secureSettings;
        mMenuView = new AccessibilityFloatingMenuView(context, getPosition(context));
        mMigrationTooltipView = new MigrationTooltipView(mContext, mMenuView);
        mDockTooltipView = new DockTooltipView(mContext, mMenuView);
    }

    @VisibleForTesting
    AccessibilityFloatingMenu(Context context, SecureSettings secureSettings,
            AccessibilityFloatingMenuView menuView) {
        mContext = context;
        mSecureSettings = secureSettings;
        mMenuView = menuView;
        mMigrationTooltipView = new MigrationTooltipView(mContext, mMenuView);
        mDockTooltipView = new DockTooltipView(mContext, mMenuView);
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
        final List<AccessibilityTarget> targetList = getTargets(mContext, ACCESSIBILITY_BUTTON);
        if (targetList.isEmpty()) {
            return;
        }

        mMenuView.show();
        mMenuView.onTargetsChanged(targetList);
        mMenuView.updateOpacityWith(isFadeEffectEnabled(),
                getOpacityValue());
        mMenuView.setSizeType(getSizeType());
        mMenuView.setShapeType(getShapeType());
        mMenuView.setOnDragEndListener(this::onDragEnd);

        showMigrationTooltipIfNecessary();

        registerContentObservers();
    }

    @Override
    public void hide() {
        if (!isShowing()) {
            return;
        }

        mMenuView.hide();
        mMenuView.setOnDragEndListener(null);
        mMigrationTooltipView.hide();
        mDockTooltipView.hide();

        unregisterContentObservers();
    }

    @NonNull
    private Position getPosition(Context context) {
        final String absolutePositionString = Prefs.getString(context,
                Prefs.Key.ACCESSIBILITY_FLOATING_MENU_POSITION, /* defaultValue= */ null);

        if (TextUtils.isEmpty(absolutePositionString)) {
            return new Position(DEFAULT_POSITION_X_PERCENT, DEFAULT_POSITION_Y_PERCENT);
        } else {
            return Position.fromString(absolutePositionString);
        }
    }

    // Migration tooltip was the android S feature. It's just used on the Android version from R
    // to S. In addition, it only shows once.
    private void showMigrationTooltipIfNecessary() {
        if (isMigrationTooltipPromptEnabled()) {
            mMigrationTooltipView.show();

            mSecureSettings.putInt(
                    ACCESSIBILITY_FLOATING_MENU_MIGRATION_TOOLTIP_PROMPT, /* disabled */ 0);
        }
    }

    private boolean isMigrationTooltipPromptEnabled() {
        return mSecureSettings.getInt(
                ACCESSIBILITY_FLOATING_MENU_MIGRATION_TOOLTIP_PROMPT,
                DEFAULT_MIGRATION_TOOLTIP_PROMPT_IS_DISABLED) == /* enabled */ 1;
    }

    private void onDragEnd(Position position) {
        SysUiStatsLog.write(SysUiStatsLog.ACCESSIBILITY_FLOATING_MENU_UI_CHANGED,
                position.getPercentageX(), position.getPercentageY(),
                mContext.getResources().getConfiguration().orientation);
        savePosition(mContext, position);
        showDockTooltipIfNecessary(mContext);
    }

    private void savePosition(Context context, Position position) {
        Prefs.putString(context, Prefs.Key.ACCESSIBILITY_FLOATING_MENU_POSITION,
                position.toString());
    }

    /**
     * Shows tooltip when user drags accessibility floating menu for the first time.
     */
    private void showDockTooltipIfNecessary(Context context) {
        if (!Prefs.get(context).getBoolean(
                HAS_SEEN_ACCESSIBILITY_FLOATING_MENU_DOCK_TOOLTIP, false)) {
            // if the menu is an oval, the user has already dragged it out, so show the tooltip.
            if (mMenuView.isOvalShape()) {
                mDockTooltipView.show();
            }

            Prefs.putBoolean(context, HAS_SEEN_ACCESSIBILITY_FLOATING_MENU_DOCK_TOOLTIP, true);
        }
    }

    private boolean isFadeEffectEnabled() {
        return mSecureSettings.getInt(
                ACCESSIBILITY_FLOATING_MENU_FADE_ENABLED,
                DEFAULT_FADE_EFFECT_IS_ENABLED) == /* enabled */ 1;
    }

    private float getOpacityValue() {
        return mSecureSettings.getFloat(
                ACCESSIBILITY_FLOATING_MENU_OPACITY,
                DEFAULT_OPACITY_VALUE);
    }

    private int getSizeType() {
        return mSecureSettings.getInt(
                ACCESSIBILITY_FLOATING_MENU_SIZE, SizeType.SMALL);
    }

    private int getShapeType() {
        return mSecureSettings.getInt(
                ACCESSIBILITY_FLOATING_MENU_ICON_TYPE,
                ShapeType.OVAL);
    }

    private void registerContentObservers() {
        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS,
                /* notifyForDescendants */ false, mContentObserver,
                UserHandle.USER_CURRENT);
        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS,
                /* notifyForDescendants */ false, mContentObserver,
                UserHandle.USER_CURRENT);
        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.ACCESSIBILITY_FLOATING_MENU_SIZE,
                /* notifyForDescendants */ false, mSizeContentObserver,
                UserHandle.USER_CURRENT);
        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.ACCESSIBILITY_FLOATING_MENU_FADE_ENABLED,
                /* notifyForDescendants */ false, mFadeOutContentObserver,
                UserHandle.USER_CURRENT);
        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.ACCESSIBILITY_FLOATING_MENU_OPACITY,
                /* notifyForDescendants */ false, mFadeOutContentObserver,
                UserHandle.USER_CURRENT);
        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                /* notifyForDescendants */ false,
                mEnabledA11yServicesContentObserver, UserHandle.USER_CURRENT);
    }

    private void unregisterContentObservers() {
        mSecureSettings.unregisterContentObserver(mContentObserver);
        mSecureSettings.unregisterContentObserver(mSizeContentObserver);
        mSecureSettings.unregisterContentObserver(mFadeOutContentObserver);
        mSecureSettings.unregisterContentObserver(
                mEnabledA11yServicesContentObserver);
    }
}
