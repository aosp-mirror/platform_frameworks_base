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

import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.UserHandle;
import android.text.TextUtils;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IUserInitializationCompleteCallback;

import androidx.annotation.MainThread;

import com.android.app.viewcapture.ViewCaptureAwareWindowManager;
import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.accessibility.AccessibilityButtonModeObserver;
import com.android.systemui.accessibility.AccessibilityButtonModeObserver.AccessibilityButtonMode;
import com.android.systemui.accessibility.AccessibilityButtonTargetsObserver;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.util.settings.SecureSettings;

import javax.inject.Inject;

/** A controller to handle the lifecycle of accessibility floating menu. */
@MainThread
@SysUISingleton
public class AccessibilityFloatingMenuController implements
        AccessibilityButtonModeObserver.ModeChangedListener,
        AccessibilityButtonTargetsObserver.TargetsChangedListener {

    private final AccessibilityButtonModeObserver mAccessibilityButtonModeObserver;
    private final AccessibilityButtonTargetsObserver mAccessibilityButtonTargetsObserver;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;

    private Context mContext;
    private final WindowManager mWindowManager;
    private final ViewCaptureAwareWindowManager mViewCaptureAwareWindowManager;
    private final DisplayManager mDisplayManager;
    private final AccessibilityManager mAccessibilityManager;

    private final SecureSettings mSecureSettings;
    private final DisplayTracker mDisplayTracker;
    private final NavigationModeController mNavigationModeController;
    @VisibleForTesting
    IAccessibilityFloatingMenu mFloatingMenu;
    private int mBtnMode;
    private String mBtnTargets;
    private boolean mIsKeyguardVisible;
    private boolean mIsUserInInitialization;
    @VisibleForTesting
    Handler mHandler;

    @VisibleForTesting
    final KeyguardUpdateMonitorCallback mKeyguardCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onUserUnlocked() {
            handleFloatingMenuVisibility(mIsKeyguardVisible, mBtnMode, mBtnTargets);
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean visible) {
            mIsKeyguardVisible = visible;
            handleFloatingMenuVisibility(mIsKeyguardVisible, mBtnMode, mBtnTargets);
        }

        @Override
        public void onUserSwitching(int userId) {
            destroyFloatingMenu();
            mIsUserInInitialization = true;
        }
    };

    @VisibleForTesting
    final UserInitializationCompleteCallback mUserInitializationCompleteCallback =
            new UserInitializationCompleteCallback();

    @Inject
    public AccessibilityFloatingMenuController(Context context,
            WindowManager windowManager,
            ViewCaptureAwareWindowManager viewCaptureAwareWindowManager,
            DisplayManager displayManager,
            AccessibilityManager accessibilityManager,
            AccessibilityButtonTargetsObserver accessibilityButtonTargetsObserver,
            AccessibilityButtonModeObserver accessibilityButtonModeObserver,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            SecureSettings secureSettings,
            DisplayTracker displayTracker,
            NavigationModeController navigationModeController,
            Handler handler) {
        mContext = context;
        mWindowManager = windowManager;
        mViewCaptureAwareWindowManager = viewCaptureAwareWindowManager;
        mDisplayManager = displayManager;
        mAccessibilityManager = accessibilityManager;
        mAccessibilityButtonTargetsObserver = accessibilityButtonTargetsObserver;
        mAccessibilityButtonModeObserver = accessibilityButtonModeObserver;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mSecureSettings = secureSettings;
        mDisplayTracker = displayTracker;
        mNavigationModeController = navigationModeController;
        mHandler = handler;

        mIsKeyguardVisible = false;
    }

    /**
     * Handles visibility of the accessibility floating menu when accessibility button mode changes.
     *
     * @param mode Current accessibility button mode.
     */
    @Override
    public void onAccessibilityButtonModeChanged(@AccessibilityButtonMode int mode) {
        mBtnMode = mode;
        handleFloatingMenuVisibility(mIsKeyguardVisible, mBtnMode, mBtnTargets);
    }

    /**
     * Handles visibility of the accessibility floating menu when accessibility button targets
     * changes.
     * List should come from {@link android.provider.Settings.Secure#ACCESSIBILITY_BUTTON_TARGETS}.
     * @param targets Current accessibility button list.
     */
    @Override
    public void onAccessibilityButtonTargetsChanged(String targets) {
        mBtnTargets = targets;
        handleFloatingMenuVisibility(mIsKeyguardVisible, mBtnMode, mBtnTargets);
    }

    /** Initializes the AccessibilityFloatingMenuController configurations. */
    public void init() {
        mBtnMode = mAccessibilityButtonModeObserver.getCurrentAccessibilityButtonMode();
        mBtnTargets = mAccessibilityButtonTargetsObserver.getCurrentAccessibilityButtonTargets();
        registerContentObservers();
    }

    private void registerContentObservers() {
        mAccessibilityButtonModeObserver.addListener(this);
        mAccessibilityButtonTargetsObserver.addListener(this);
        mKeyguardUpdateMonitor.registerCallback(mKeyguardCallback);
        mAccessibilityManager.registerUserInitializationCompleteCallback(
                mUserInitializationCompleteCallback);
    }

    /**
     * Handles the accessibility floating menu visibility with the given values.
     *
     * @param keyguardVisible the keyguard visibility status. Not show the
     *                        {@link MenuView} when keyguard appears.
     * @param mode accessibility button mode {@link AccessibilityButtonMode}
     * @param targets accessibility button list; it should comes from
     *                {@link android.provider.Settings.Secure#ACCESSIBILITY_BUTTON_TARGETS}.
     */
    private void handleFloatingMenuVisibility(boolean keyguardVisible,
            @AccessibilityButtonMode int mode, String targets) {
        if (keyguardVisible || mIsUserInInitialization) {
            destroyFloatingMenu();
            return;
        }

        if (shouldShowFloatingMenu(mode, targets)) {
            showFloatingMenu();
        } else {
            destroyFloatingMenu();
        }
    }

    private boolean shouldShowFloatingMenu(@AccessibilityButtonMode int mode, String targets) {
        return mode == ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU && !TextUtils.isEmpty(targets);
    }

    private void showFloatingMenu() {
        if (mFloatingMenu == null) {
            final Display defaultDisplay = mDisplayManager.getDisplay(
                    mDisplayTracker.getDefaultDisplayId());
            final Context windowContext = mContext.createWindowContext(defaultDisplay,
                    TYPE_NAVIGATION_BAR_PANEL, /* options= */ null);
            mFloatingMenu = new MenuViewLayerController(windowContext, mWindowManager,
                    mViewCaptureAwareWindowManager, mAccessibilityManager, mSecureSettings,
                    mNavigationModeController);
        }

        mFloatingMenu.show();
    }

    private void destroyFloatingMenu() {
        if (mFloatingMenu == null) {
            return;
        }

        mFloatingMenu.hide();
        mFloatingMenu = null;
    }

    class UserInitializationCompleteCallback
            extends IUserInitializationCompleteCallback.Stub {
        @Override
        public void onUserInitializationComplete(int userId) {
            mIsUserInInitialization = false;
            mContext = mContext.createContextAsUser(UserHandle.of(userId), /* flags= */ 0);
            mBtnMode = mAccessibilityButtonModeObserver.getCurrentAccessibilityButtonMode();
            mBtnTargets =
                    mAccessibilityButtonTargetsObserver.getCurrentAccessibilityButtonTargets();
            mHandler.post(
                    () -> handleFloatingMenuVisibility(mIsKeyguardVisible, mBtnMode, mBtnTargets));
        }
    }
}
