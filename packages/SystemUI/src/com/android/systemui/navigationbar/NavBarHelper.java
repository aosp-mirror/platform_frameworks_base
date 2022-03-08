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

package com.android.systemui.navigationbar;

import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;

import com.android.systemui.Dumpable;
import com.android.systemui.accessibility.AccessibilityButtonModeObserver;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.inject.Inject;

import dagger.Lazy;

/**
 * Extracts shared elements between navbar and taskbar delegate to de-dupe logic and help them
 * experience the joys of friendship.
 * The events are then passed through
 *
 * Currently consolidates
 * * A11y
 * * Assistant
 */
@SysUISingleton
public final class NavBarHelper implements
        AccessibilityButtonModeObserver.ModeChangedListener,
        OverviewProxyService.OverviewProxyListener, NavigationModeController.ModeChangedListener,
        Dumpable {
    private final AccessibilityManager mAccessibilityManager;
    private final Lazy<AssistManager> mAssistManagerLazy;
    private final Lazy<Optional<StatusBar>> mStatusBarOptionalLazy;
    private final UserTracker mUserTracker;
    private final AccessibilityButtonModeObserver mAccessibilityButtonModeObserver;
    private final List<NavbarTaskbarStateUpdater> mA11yEventListeners = new ArrayList<>();
    private final Context mContext;
    private ContentResolver mContentResolver;
    private boolean mAssistantAvailable;
    private boolean mLongPressHomeEnabled;
    private boolean mAssistantTouchGestureEnabled;
    private int mNavBarMode;

    private final ContentObserver mAssistContentObserver = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateAssitantAvailability();
        }
    };

    /**
     * @param context This is not display specific, then again neither is any of the code in
     *                this class. Once there's display specific code, we may want to create an
     *                instance of this class per navbar vs having it be a singleton.
     */
    @Inject
    public NavBarHelper(Context context, AccessibilityManager accessibilityManager,
            AccessibilityManagerWrapper accessibilityManagerWrapper,
            AccessibilityButtonModeObserver accessibilityButtonModeObserver,
            OverviewProxyService overviewProxyService,
            Lazy<AssistManager> assistManagerLazy,
            Lazy<Optional<StatusBar>> statusBarOptionalLazy,
            NavigationModeController navigationModeController,
            UserTracker userTracker,
            DumpManager dumpManager) {
        mContext = context;
        mContentResolver = mContext.getContentResolver();
        mAccessibilityManager = accessibilityManager;
        mAssistManagerLazy = assistManagerLazy;
        mStatusBarOptionalLazy = statusBarOptionalLazy;
        mUserTracker = userTracker;
        accessibilityManagerWrapper.addCallback(
                accessibilityManager1 -> NavBarHelper.this.dispatchA11yEventUpdate());
        mAccessibilityButtonModeObserver = accessibilityButtonModeObserver;

        mAccessibilityButtonModeObserver.addListener(this);
        mNavBarMode = navigationModeController.addListener(this);
        overviewProxyService.addCallback(this);
        dumpManager.registerDumpable(this);
    }

    public void init() {
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ASSISTANT),
                false /* notifyForDescendants */, mAssistContentObserver, UserHandle.USER_ALL);
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ASSIST_LONG_PRESS_HOME_ENABLED),
                false, mAssistContentObserver, UserHandle.USER_ALL);
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ASSIST_TOUCH_GESTURE_ENABLED),
                false, mAssistContentObserver, UserHandle.USER_ALL);
        updateAssitantAvailability();
    }

    public void destroy() {
        mContentResolver.unregisterContentObserver(mAssistContentObserver);
    }

    /**
     * @param listener Will immediately get callbacks based on current state
     */
    public void registerNavTaskStateUpdater(NavbarTaskbarStateUpdater listener) {
        mA11yEventListeners.add(listener);
        listener.updateAccessibilityServicesState();
        listener.updateAssistantAvailable(mAssistantAvailable);
    }

    public void removeNavTaskStateUpdater(NavbarTaskbarStateUpdater listener) {
        mA11yEventListeners.remove(listener);
    }

    private void dispatchA11yEventUpdate() {
        for (NavbarTaskbarStateUpdater listener : mA11yEventListeners) {
            listener.updateAccessibilityServicesState();
        }
    }

    private void dispatchAssistantEventUpdate(boolean assistantAvailable) {
        for (NavbarTaskbarStateUpdater listener : mA11yEventListeners) {
            listener.updateAssistantAvailable(assistantAvailable);
        }
    }

    @Override
    public void onAccessibilityButtonModeChanged(int mode) {
        dispatchA11yEventUpdate();
    }

    /**
     * See {@link QuickStepContract#SYSUI_STATE_A11Y_BUTTON_CLICKABLE} and
     * {@link QuickStepContract#SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE}
     *
     * @return the a11y button clickable and long_clickable states, or 0 if there is no
     *         a11y button in the navbar
     */
    public int getA11yButtonState() {
        // AccessibilityManagerService resolves services for the current user since the local
        // AccessibilityManager is created from a Context with the INTERACT_ACROSS_USERS permission
        final List<String> a11yButtonTargets =
                mAccessibilityManager.getAccessibilityShortcutTargets(
                        AccessibilityManager.ACCESSIBILITY_BUTTON);
        final int requestingServices = a11yButtonTargets.size();

        // If accessibility button is floating menu mode, click and long click state should be
        // disabled.
        if (mAccessibilityButtonModeObserver.getCurrentAccessibilityButtonMode()
                == ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU) {
            return 0;
        }

        return (requestingServices >= 1 ? SYSUI_STATE_A11Y_BUTTON_CLICKABLE : 0)
                | (requestingServices >= 2 ? SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE : 0);
    }

    @Override
    public void onConnectionChanged(boolean isConnected) {
        if (isConnected) {
            updateAssitantAvailability();
        }
    }

    private void updateAssitantAvailability() {
        boolean assistantAvailableForUser = mAssistManagerLazy.get()
                .getAssistInfoForUser(UserHandle.USER_CURRENT) != null;
        boolean longPressDefault = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_assistLongPressHomeEnabledDefault);
        mLongPressHomeEnabled = Settings.Secure.getIntForUser(mContentResolver,
                Settings.Secure.ASSIST_LONG_PRESS_HOME_ENABLED, longPressDefault ? 1 : 0,
                mUserTracker.getUserId()) != 0;
        boolean gestureDefault = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_assistTouchGestureEnabledDefault);
        mAssistantTouchGestureEnabled = Settings.Secure.getIntForUser(mContentResolver,
                Settings.Secure.ASSIST_TOUCH_GESTURE_ENABLED, gestureDefault ? 1 : 0,
                mUserTracker.getUserId()) != 0;

        mAssistantAvailable = assistantAvailableForUser
                && mAssistantTouchGestureEnabled
                && QuickStepContract.isGesturalMode(mNavBarMode);
        dispatchAssistantEventUpdate(mAssistantAvailable);
    }

    public boolean getLongPressHomeEnabled() {
        return mLongPressHomeEnabled;
    }

    @Override
    public void startAssistant(Bundle bundle) {
        mAssistManagerLazy.get().startAssist(bundle);
    }

    @Override
    public void onNavigationModeChanged(int mode) {
        mNavBarMode = mode;
        updateAssitantAvailability();
    }

    /**
     * @return Whether the IME is shown on top of the screen given the {@code vis} flag of
     * {@link InputMethodService} and the keyguard states.
     */
    public boolean isImeShown(int vis) {
        View shadeWindowView = mStatusBarOptionalLazy.get().get().getNotificationShadeWindowView();
        boolean isKeyguardShowing = mStatusBarOptionalLazy.get().get().isKeyguardShowing();
        boolean imeVisibleOnShade = shadeWindowView != null && shadeWindowView.isAttachedToWindow()
                && shadeWindowView.getRootWindowInsets().isVisible(WindowInsets.Type.ime());
        return imeVisibleOnShade
                || (!isKeyguardShowing && (vis & InputMethodService.IME_VISIBLE) != 0);
    }

    /**
     * Callbacks will get fired once immediately after registering via
     * {@link #registerNavTaskStateUpdater(NavbarTaskbarStateUpdater)}
     */
    public interface NavbarTaskbarStateUpdater {
        void updateAccessibilityServicesState();
        void updateAssistantAvailable(boolean available);
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("NavbarTaskbarFriendster");
        pw.println("  longPressHomeEnabled=" + mLongPressHomeEnabled);
        pw.println("  mAssistantTouchGestureEnabled=" + mAssistantTouchGestureEnabled);
        pw.println("  mAssistantAvailable=" + mAssistantAvailable);
        pw.println("  mNavBarMode=" + mNavBarMode);
    }
}
