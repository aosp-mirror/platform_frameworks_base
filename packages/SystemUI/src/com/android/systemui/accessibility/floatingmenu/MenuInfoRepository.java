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

import static android.provider.Settings.Secure.ACCESSIBILITY_FLOATING_MENU_FADE_ENABLED;
import static android.provider.Settings.Secure.ACCESSIBILITY_FLOATING_MENU_MIGRATION_TOOLTIP_PROMPT;
import static android.provider.Settings.Secure.ACCESSIBILITY_FLOATING_MENU_OPACITY;
import static android.provider.Settings.Secure.ACCESSIBILITY_FLOATING_MENU_SIZE;
import static android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;

import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.SOFTWARE;
import static com.android.internal.accessibility.dialog.AccessibilityTargetHelper.getTargets;
import static com.android.systemui.accessibility.floatingmenu.MenuFadeEffectInfoKt.DEFAULT_FADE_EFFECT_IS_ENABLED;
import static com.android.systemui.accessibility.floatingmenu.MenuFadeEffectInfoKt.DEFAULT_OPACITY_VALUE;
import static com.android.systemui.accessibility.floatingmenu.MenuViewAppearance.MenuSizeType.SMALL;

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.Nullable;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;

import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.bluetooth.HearingAidDeviceManager;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.Prefs;
import com.android.systemui.util.settings.SecureSettings;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Stores and observe the settings contents for the menu view.
 */
class MenuInfoRepository {
    private static final String TAG = "MenuInfoRepository";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG) || Build.IS_DEBUGGABLE;

    @FloatRange(from = 0.0, to = 1.0)
    private static final float DEFAULT_MENU_POSITION_X_PERCENT = 1.0f;

    @FloatRange(from = 0.0, to = 1.0)
    private static final float DEFAULT_MENU_POSITION_X_PERCENT_RTL = 0.0f;

    @FloatRange(from = 0.0, to = 1.0)
    private static final float DEFAULT_MENU_POSITION_Y_PERCENT = 0.77f;
    private static final boolean DEFAULT_MOVE_TO_TUCKED_VALUE = false;
    private static final boolean DEFAULT_HAS_SEEN_DOCK_TOOLTIP_VALUE = false;
    private static final int DEFAULT_MIGRATION_TOOLTIP_VALUE_PROMPT = MigrationPrompt.DISABLED;

    private final Context mContext;
    // Pref always get the userId from the context to store SharedPreferences for the correct user
    private final Context mCurrentUserContext;
    private final Configuration mConfiguration;
    private final AccessibilityManager mAccessibilityManager;
    private final AccessibilityManager.AccessibilityServicesStateChangeListener
            mA11yServicesStateChangeListener = manager -> onTargetFeaturesChanged();
    private final HearingAidDeviceManager mHearingAidDeviceManager;
    private final HearingAidDeviceManager.ConnectionStatusListener
            mHearingDeviceStatusListener = this::onDevicesConnectionStatusChanged;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final OnContentsChanged mSettingsContentsCallback;
    private final SecureSettings mSecureSettings;
    private Position mPercentagePosition;

    @IntDef({
            MigrationPrompt.DISABLED,
            MigrationPrompt.ENABLED,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface MigrationPrompt {
        int DISABLED = 0;
        int ENABLED = 1;
    }

    @VisibleForTesting
    final ContentObserver mMenuTargetFeaturesContentObserver =
            new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    onTargetFeaturesChanged();
                }
            };

    @VisibleForTesting
    final ContentObserver mMenuSizeContentObserver =
            new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    mSettingsContentsCallback.onSizeTypeChanged(
                            getMenuSizeTypeFromSettings());
                }
            };

    @VisibleForTesting
    final ContentObserver mMenuFadeOutContentObserver =
            new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    mSettingsContentsCallback.onFadeEffectInfoChanged(getMenuFadeEffectInfo());
                }
            };

    @VisibleForTesting
    final ComponentCallbacks mComponentCallbacks = new ComponentCallbacks() {
        @Override
        public void onConfigurationChanged(@NonNull Configuration newConfig) {
            final int diff = newConfig.diff(mConfiguration);

            if (DEBUG) {
                Log.d(TAG, "onConfigurationChanged = " + Configuration.configurationDiffToString(
                        diff));
            }

            if ((diff & ActivityInfo.CONFIG_LOCALE) != 0) {
                onTargetFeaturesChanged();
            }

            mConfiguration.setTo(newConfig);
        }

        @Override
        public void onLowMemory() {
            // Do nothing.
        }
    };

    MenuInfoRepository(Context context, AccessibilityManager accessibilityManager,
            OnContentsChanged settingsContentsChanged, SecureSettings secureSettings,
            @Nullable HearingAidDeviceManager hearingAidDeviceManager) {
        mContext = context;
        final int currentUserId = secureSettings.getRealUserHandle(UserHandle.USER_CURRENT);
        mCurrentUserContext = context.createContextAsUser(
                UserHandle.of(currentUserId), /* flags= */ 0);
        mAccessibilityManager = accessibilityManager;
        mConfiguration = new Configuration(context.getResources().getConfiguration());
        mSettingsContentsCallback = settingsContentsChanged;
        mSecureSettings = secureSettings;
        mHearingAidDeviceManager = hearingAidDeviceManager;

        mPercentagePosition = getStartPosition();
    }

    void loadMenuMoveToTucked(OnInfoReady<Boolean> callback) {
        callback.onReady(
                Prefs.getBoolean(
                        mCurrentUserContext, Prefs.Key.HAS_ACCESSIBILITY_FLOATING_MENU_TUCKED,
                        DEFAULT_MOVE_TO_TUCKED_VALUE));
    }

    void loadDockTooltipVisibility(OnInfoReady<Boolean> callback) {
        callback.onReady(Prefs.getBoolean(mCurrentUserContext,
                Prefs.Key.HAS_SEEN_ACCESSIBILITY_FLOATING_MENU_DOCK_TOOLTIP,
                DEFAULT_HAS_SEEN_DOCK_TOOLTIP_VALUE));
    }

    void loadMigrationTooltipVisibility(OnInfoReady<Boolean> callback) {
        callback.onReady(mSecureSettings.getIntForUser(
                ACCESSIBILITY_FLOATING_MENU_MIGRATION_TOOLTIP_PROMPT,
                DEFAULT_MIGRATION_TOOLTIP_VALUE_PROMPT, UserHandle.USER_CURRENT)
                == MigrationPrompt.ENABLED);
    }

    void loadMenuPosition(OnInfoReady<Position> callback) {
        callback.onReady(mPercentagePosition);
    }

    void loadMenuTargetFeatures(OnInfoReady<List<AccessibilityTarget>> callback) {
        callback.onReady(getTargets(mContext, SOFTWARE));
    }

    void loadHearingDeviceStatus(OnInfoReady<Integer> callback) {
        if (mHearingAidDeviceManager != null) {
            callback.onReady(mHearingAidDeviceManager.getDevicesConnectionStatus());
        } else {
            callback.onReady(HearingAidDeviceManager.ConnectionStatus.NO_DEVICE_BONDED);
        }
    }

    void loadMenuSizeType(OnInfoReady<Integer> callback) {
        callback.onReady(getMenuSizeTypeFromSettings());
    }

    void loadMenuFadeEffectInfo(OnInfoReady<MenuFadeEffectInfo> callback) {
        callback.onReady(getMenuFadeEffectInfo());
    }

    private MenuFadeEffectInfo getMenuFadeEffectInfo() {
        return new MenuFadeEffectInfo(isMenuFadeEffectEnabledFromSettings(),
                getMenuOpacityFromSettings());
    }

    void updateMoveToTucked(boolean isMoveToTucked) {
        Prefs.putBoolean(mCurrentUserContext, Prefs.Key.HAS_ACCESSIBILITY_FLOATING_MENU_TUCKED,
                isMoveToTucked);
    }

    void updateMenuSavingPosition(Position percentagePosition) {
        mPercentagePosition = percentagePosition;
        Prefs.putString(mCurrentUserContext, Prefs.Key.ACCESSIBILITY_FLOATING_MENU_POSITION,
                percentagePosition.toString());
    }

    void updateDockTooltipVisibility(boolean hasSeen) {
        Prefs.putBoolean(mCurrentUserContext,
                Prefs.Key.HAS_SEEN_ACCESSIBILITY_FLOATING_MENU_DOCK_TOOLTIP, hasSeen);
    }

    void updateMigrationTooltipVisibility(boolean visible) {
        mSecureSettings.putIntForUser(
                ACCESSIBILITY_FLOATING_MENU_MIGRATION_TOOLTIP_PROMPT,
                visible ? MigrationPrompt.ENABLED : MigrationPrompt.DISABLED,
                UserHandle.USER_CURRENT);
    }

    private void onTargetFeaturesChanged() {
        List<AccessibilityTarget> targets = getTargets(mContext, SOFTWARE);
        mSettingsContentsCallback.onTargetFeaturesChanged(targets);
    }

    private Position getStartPosition() {
        final String absolutePositionString = Prefs.getString(mCurrentUserContext,
                Prefs.Key.ACCESSIBILITY_FLOATING_MENU_POSITION, /* defaultValue= */ null);

        final float defaultPositionXPercent =
                mConfiguration.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                        ? DEFAULT_MENU_POSITION_X_PERCENT_RTL
                        : DEFAULT_MENU_POSITION_X_PERCENT;
        return TextUtils.isEmpty(absolutePositionString)
                ? new Position(defaultPositionXPercent, DEFAULT_MENU_POSITION_Y_PERCENT)
                : Position.fromString(absolutePositionString);
    }

    void registerObserversAndCallbacks() {
        mSecureSettings.registerContentObserverForUserSync(
                mSecureSettings.getUriFor(Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS),
                /* notifyForDescendants */ false, mMenuTargetFeaturesContentObserver,
                UserHandle.USER_CURRENT);
        if (com.android.systemui.Flags.floatingMenuNotifyTargetsChangedOnStrictDiff()) {
            mSecureSettings.registerContentObserverForUserSync(
                    mSecureSettings.getUriFor(ENABLED_ACCESSIBILITY_SERVICES),
                    /* notifyForDescendants */ false,
                    mMenuTargetFeaturesContentObserver,
                    UserHandle.USER_CURRENT);
        }
        mSecureSettings.registerContentObserverForUserSync(
                mSecureSettings.getUriFor(Settings.Secure.ACCESSIBILITY_FLOATING_MENU_SIZE),
                /* notifyForDescendants */ false, mMenuSizeContentObserver,
                UserHandle.USER_CURRENT);
        mSecureSettings.registerContentObserverForUserSync(
                mSecureSettings.getUriFor(ACCESSIBILITY_FLOATING_MENU_FADE_ENABLED),
                /* notifyForDescendants */ false, mMenuFadeOutContentObserver,
                UserHandle.USER_CURRENT);
        mSecureSettings.registerContentObserverForUserSync(
                mSecureSettings.getUriFor(ACCESSIBILITY_FLOATING_MENU_OPACITY),
                /* notifyForDescendants */ false, mMenuFadeOutContentObserver,
                UserHandle.USER_CURRENT);
        mContext.registerComponentCallbacks(mComponentCallbacks);

        if (com.android.systemui.Flags.floatingMenuNotifyTargetsChangedOnStrictDiff()) {
            mAccessibilityManager.addAccessibilityServicesStateChangeListener(
                    mA11yServicesStateChangeListener);
        }

        if (com.android.settingslib.flags.Flags.hearingDeviceSetConnectionStatusReport()) {
            registerConnectionStatusListener();
        }
    }

    private void registerConnectionStatusListener() {
        if (mHearingAidDeviceManager != null) {
            mHearingAidDeviceManager.registerConnectionStatusListener(
                    mHearingDeviceStatusListener, ThreadUtils.getBackgroundExecutor());
        }
    }

    private void unregisterConnectionStatusListener() {
        if (mHearingAidDeviceManager != null) {
            mHearingAidDeviceManager.unregisterConnectionStatusListener(
                    mHearingDeviceStatusListener);
        }
    }

    void unregisterObserversAndCallbacks() {
        mContext.getContentResolver().unregisterContentObserver(mMenuTargetFeaturesContentObserver);
        mContext.getContentResolver().unregisterContentObserver(mMenuSizeContentObserver);
        mContext.getContentResolver().unregisterContentObserver(mMenuFadeOutContentObserver);
        mContext.unregisterComponentCallbacks(mComponentCallbacks);

        if (com.android.systemui.Flags.floatingMenuNotifyTargetsChangedOnStrictDiff()) {
            mAccessibilityManager.removeAccessibilityServicesStateChangeListener(
                    mA11yServicesStateChangeListener);
        }

        unregisterConnectionStatusListener();
    }

    interface OnContentsChanged {
        void onTargetFeaturesChanged(List<AccessibilityTarget> newTargetFeatures);

        void onSizeTypeChanged(int newSizeType);

        void onFadeEffectInfoChanged(MenuFadeEffectInfo fadeEffectInfo);

        void onDevicesConnectionStatusChanged(@HearingAidDeviceManager.ConnectionStatus int status);
    }

    interface OnInfoReady<T> {
        void onReady(T info);
    }

    private int getMenuSizeTypeFromSettings() {
        return mSecureSettings.getIntForUser(
                ACCESSIBILITY_FLOATING_MENU_SIZE, SMALL, UserHandle.USER_CURRENT);
    }

    private boolean isMenuFadeEffectEnabledFromSettings() {
        return mSecureSettings.getIntForUser(
                ACCESSIBILITY_FLOATING_MENU_FADE_ENABLED,
                DEFAULT_FADE_EFFECT_IS_ENABLED, UserHandle.USER_CURRENT) == /* enabled */ 1;
    }

    private float getMenuOpacityFromSettings() {
        return mSecureSettings.getFloatForUser(
                ACCESSIBILITY_FLOATING_MENU_OPACITY, DEFAULT_OPACITY_VALUE,
                UserHandle.USER_CURRENT);
    }

    private void onDevicesConnectionStatusChanged(
            @HearingAidDeviceManager.ConnectionStatus int status) {
        mSettingsContentsCallback.onDevicesConnectionStatusChanged(status);
    }
}
