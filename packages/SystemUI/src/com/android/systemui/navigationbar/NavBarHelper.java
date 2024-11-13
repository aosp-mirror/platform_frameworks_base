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

import static android.app.StatusBarManager.WINDOW_NAVIGATION_BAR;
import static android.app.StatusBarManager.WindowVisibleState;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR;
import static android.view.WindowInsetsController.APPEARANCE_LOW_PROFILE_BARS;
import static android.view.WindowInsetsController.APPEARANCE_OPAQUE_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_SEMI_TRANSPARENT_NAVIGATION_BARS;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;

import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.DEFAULT;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.GESTURE;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.SOFTWARE;
import static com.android.systemui.accessibility.SystemActions.SYSTEM_ACTION_ID_ACCESSIBILITY_BUTTON;
import static com.android.systemui.accessibility.SystemActions.SYSTEM_ACTION_ID_ACCESSIBILITY_BUTTON_CHOOSER;
import static com.android.systemui.shared.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT;
import static com.android.systemui.shared.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT_TRANSPARENT;
import static com.android.systemui.shared.statusbar.phone.BarTransitions.MODE_OPAQUE;
import static com.android.systemui.shared.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT;
import static com.android.systemui.shared.statusbar.phone.BarTransitions.MODE_TRANSPARENT;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.InputMethodService.ImeWindowVisibility;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.util.Log;
import android.view.IRotationWatcher;
import android.view.IWallpaperVisibilityListener;
import android.view.IWindowManager;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dumpable;
import com.android.systemui.accessibility.AccessibilityButtonModeObserver;
import com.android.systemui.accessibility.AccessibilityButtonTargetsObserver;
import com.android.systemui.accessibility.AccessibilityGestureTargetsObserver;
import com.android.systemui.accessibility.SystemActions;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shared.Flags;
import com.android.systemui.shared.rotation.RotationPolicyUtil;
import com.android.systemui.shared.statusbar.phone.BarTransitions.TransitionMode;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import dagger.Lazy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Inject;

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
        AccessibilityManager.AccessibilityServicesStateChangeListener,
        AccessibilityButtonModeObserver.ModeChangedListener,
        AccessibilityButtonTargetsObserver.TargetsChangedListener,
        AccessibilityGestureTargetsObserver.TargetsChangedListener,
        OverviewProxyService.OverviewProxyListener, NavigationModeController.ModeChangedListener,
        Dumpable, CommandQueue.Callbacks, ConfigurationController.ConfigurationListener {
    private static final String TAG = NavBarHelper.class.getSimpleName();

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Executor mMainExecutor;
    private final Handler mBgHandler;
    private final AccessibilityManager mAccessibilityManager;
    private final Lazy<AssistManager> mAssistManagerLazy;
    private final Lazy<Optional<CentralSurfaces>> mCentralSurfacesOptionalLazy;
    private final KeyguardStateController mKeyguardStateController;
    private final UserTracker mUserTracker;
    private final SystemActions mSystemActions;
    private final AccessibilityButtonModeObserver mAccessibilityButtonModeObserver;
    private final AccessibilityButtonTargetsObserver mAccessibilityButtonTargetsObserver;
    private final AccessibilityGestureTargetsObserver mAccessibilityGestureTargetsObserver;
    private final List<NavbarTaskbarStateUpdater> mStateListeners = new ArrayList<>();
    private final Context mContext;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final CommandQueue mCommandQueue;
    private final ContentResolver mContentResolver;
    private final EdgeBackGestureHandler mEdgeBackGestureHandler;
    private final IWindowManager mWm;
    private final int mDefaultDisplayId;
    private boolean mAssistantAvailable;
    private boolean mLongPressHomeEnabled;
    private boolean mAssistantTouchGestureEnabled;
    private int mNavBarMode;
    private long mA11yButtonState;
    private int mRotationWatcherRotation;
    private boolean mTogglingNavbarTaskbar;
    private boolean mWallpaperVisible;

    // Attributes used in NavBarHelper.CurrentSysuiState
    private int mWindowStateDisplayId;
    private @WindowVisibleState int mWindowState;

    // Listens for changes to the assistant
    private final ContentObserver mAssistContentObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateAssistantAvailability();
        }
    };

    // Listens for changes to the wallpaper visibility
    private final IWallpaperVisibilityListener mWallpaperVisibilityListener =
            new IWallpaperVisibilityListener.Stub() {
                @Override
                public void onWallpaperVisibilityChanged(boolean visible,
                        int displayId) throws RemoteException {
                    mHandler.post(() -> {
                        mWallpaperVisible = visible;
                        dispatchWallpaperVisibilityChanged(visible, displayId);
                    });
                }
            };

    // Listens for changes to display rotation
    private final IRotationWatcher mRotationWatcher = new IRotationWatcher.Stub() {
        @WorkerThread
        @Override
        public void onRotationChanged(final int rotation) {
            // We need this to be scheduled as early as possible to beat the redrawing of
            // window in response to the orientation change.
            @Nullable Boolean isRotationLocked = RotationPolicyUtil.isRotationLocked(mContext);
            mHandler.postAtFrontOfQueue(() -> {
                mRotationWatcherRotation = rotation;
                dispatchRotationChanged(rotation, isRotationLocked);
            });
        }
    };

    /**
     * @param context This is not display specific, then again neither is any of the code in
     *                this class. Once there's display specific code, we may want to create an
     *                instance of this class per navbar vs having it be a singleton.
     */
    @Inject
    public NavBarHelper(Context context, AccessibilityManager accessibilityManager,
            AccessibilityButtonModeObserver accessibilityButtonModeObserver,
            AccessibilityButtonTargetsObserver accessibilityButtonTargetsObserver,
            AccessibilityGestureTargetsObserver accessibilityGestureTargetsObserver,
            SystemActions systemActions,
            OverviewProxyService overviewProxyService,
            Lazy<AssistManager> assistManagerLazy,
            Lazy<Optional<CentralSurfaces>> centralSurfacesOptionalLazy,
            KeyguardStateController keyguardStateController,
            NavigationModeController navigationModeController,
            EdgeBackGestureHandler.Factory edgeBackGestureHandlerFactory,
            IWindowManager wm,
            UserTracker userTracker,
            DisplayTracker displayTracker,
            NotificationShadeWindowController notificationShadeWindowController,
            ConfigurationController configurationController,
            DumpManager dumpManager,
            CommandQueue commandQueue,
            @Main Executor mainExecutor,
            @Background Handler bgHandler) {
        // b/319489709: This component shouldn't be running for a non-primary user
        if (!Process.myUserHandle().equals(UserHandle.SYSTEM)) {
            Log.wtf(TAG, "Unexpected initialization for non-primary user", new Throwable());
        }
        mContext = context;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mCommandQueue = commandQueue;
        mContentResolver = mContext.getContentResolver();
        mAccessibilityManager = accessibilityManager;
        mAssistManagerLazy = assistManagerLazy;
        mCentralSurfacesOptionalLazy = centralSurfacesOptionalLazy;
        mKeyguardStateController = keyguardStateController;
        mUserTracker = userTracker;
        mSystemActions = systemActions;
        mAccessibilityButtonModeObserver = accessibilityButtonModeObserver;
        mAccessibilityButtonTargetsObserver = accessibilityButtonTargetsObserver;
        mAccessibilityGestureTargetsObserver = accessibilityGestureTargetsObserver;
        mWm = wm;
        mDefaultDisplayId = displayTracker.getDefaultDisplayId();
        mEdgeBackGestureHandler = edgeBackGestureHandlerFactory.create(context);
        mMainExecutor = mainExecutor;
        mBgHandler = bgHandler;

        mNavBarMode = navigationModeController.addListener(this);
        mCommandQueue.addCallback(this);
        configurationController.addCallback(this);
        overviewProxyService.addCallback(this);
        dumpManager.registerDumpable(this);
    }

    /**
     * Hints to the helper that bars are being replaced, which is a signal to potentially suppress
     * normal setup/cleanup when no bars are present.
     */
    public void setTogglingNavbarTaskbar(boolean togglingNavbarTaskbar) {
        mTogglingNavbarTaskbar = togglingNavbarTaskbar;
    }

    /**
     * Called when the first (non-replacing) bar is registered.
     */
    private void setupOnFirstBar() {
        // Setup accessibility listeners
        mAccessibilityManager.addAccessibilityServicesStateChangeListener(this);
        mAccessibilityButtonModeObserver.addListener(this);
        mAccessibilityButtonTargetsObserver.addListener(this);
        mAccessibilityGestureTargetsObserver.addListener(this);

        // Setup assistant listener
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ASSISTANT),
                false /* notifyForDescendants */, mAssistContentObserver, UserHandle.USER_ALL);
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ASSIST_LONG_PRESS_HOME_ENABLED),
                false, mAssistContentObserver, UserHandle.USER_ALL);
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Secure.SEARCH_ALL_ENTRYPOINTS_ENABLED),
                false, mAssistContentObserver, UserHandle.USER_ALL);
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ASSIST_TOUCH_GESTURE_ENABLED),
                false, mAssistContentObserver, UserHandle.USER_ALL);

        // Setup display rotation watcher
        try {
            mWm.watchRotation(mRotationWatcher, mDefaultDisplayId);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register rotation watcher", e);
        }

        // Setup wallpaper visibility listener
        try {
            mWallpaperVisible = mWm.registerWallpaperVisibilityListener(
                    mWallpaperVisibilityListener, mDefaultDisplayId);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register wallpaper visibility listener", e);
        }

        // Attach the back handler only when the first bar is registered
        mEdgeBackGestureHandler.onNavBarAttached();
    }

    /**
     * Called after the last (non-replacing) bar is unregistered.
     */
    private void cleanupAfterLastBar() {
        // Clean up accessibility listeners
        mAccessibilityManager.removeAccessibilityServicesStateChangeListener(this);
        mAccessibilityButtonModeObserver.removeListener(this);
        mAccessibilityButtonTargetsObserver.removeListener(this);
        mAccessibilityGestureTargetsObserver.removeListener(this);

        // Clean up assistant listeners
        mContentResolver.unregisterContentObserver(mAssistContentObserver);

        // Clean up display rotation watcher
        try {
            mWm.removeRotationWatcher(mRotationWatcher);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister rotation watcher", e);
        }

        // Clean up wallpaper visibility listener
        try {
            mWm.unregisterWallpaperVisibilityListener(mWallpaperVisibilityListener,
                    mDefaultDisplayId);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register wallpaper visibility listener", e);
        }

        // No more bars, detach the back handler for now
        mEdgeBackGestureHandler.onNavBarDetached();
    }

    /**
     * Registers a listener for future updates to the shared navbar/taskbar state.
     * @param listener Will immediately get callbacks based on current state
     */
    public void registerNavTaskStateUpdater(NavbarTaskbarStateUpdater listener) {
        mStateListeners.add(listener);
        if (!mTogglingNavbarTaskbar && mStateListeners.size() == 1) {
            setupOnFirstBar();

            // Update the state once the first bar is registered
            updateAssistantAvailability();
            updateA11yState();
            mCommandQueue.recomputeDisableFlags(mContext.getDisplayId(), false /* animate */);
        } else {
            listener.updateAccessibilityServicesState();
            listener.updateAssistantAvailable(mAssistantAvailable, mLongPressHomeEnabled);
        }
        listener.updateWallpaperVisibility(mWallpaperVisible, mDefaultDisplayId);

        mBgHandler.post(() -> {
            Boolean isRotationLocked = RotationPolicyUtil.isRotationLocked(mContext);
            mMainExecutor.execute(
                    () -> listener.updateRotationWatcherState(
                            mRotationWatcherRotation, isRotationLocked));
        });
    }

    /**
     * Removes a previously registered listener.
     */
    public void removeNavTaskStateUpdater(NavbarTaskbarStateUpdater listener) {
        mStateListeners.remove(listener);
        if (!mTogglingNavbarTaskbar && mStateListeners.isEmpty()) {
            cleanupAfterLastBar();
        }
    }

    private void dispatchA11yEventUpdate() {
        for (NavbarTaskbarStateUpdater listener : mStateListeners) {
            listener.updateAccessibilityServicesState();
        }
    }

    private void dispatchAssistantEventUpdate(boolean assistantAvailable,
            boolean longPressHomeEnabled) {
        for (NavbarTaskbarStateUpdater listener : mStateListeners) {
            listener.updateAssistantAvailable(assistantAvailable, longPressHomeEnabled);
        }
    }

    @Override
    public void onAccessibilityServicesStateChanged(AccessibilityManager manager) {
        updateA11yState();
    }

    @Override
    public void onAccessibilityButtonModeChanged(int mode) {
        updateA11yState();
    }

    @Override
    public void onAccessibilityButtonTargetsChanged(String targets) {
        updateA11yState();
    }

    @Override
    public void onAccessibilityGestureTargetsChanged(String targets) {
        updateA11yState();
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        mEdgeBackGestureHandler.onConfigurationChanged(newConfig);
    }

    private int getNumOfA11yShortcutTargetsForNavSystem() {
        final int buttonMode = mAccessibilityButtonModeObserver.getCurrentAccessibilityButtonMode();
        final int shortcutType;
        if (!android.provider.Flags.a11yStandaloneGestureEnabled()) {
            shortcutType = buttonMode
                    != ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU ? SOFTWARE : DEFAULT;
            // If accessibility button is floating menu mode, there are no clickable targets.
        } else {
            if (mNavBarMode == NAV_BAR_MODE_GESTURAL) {
                shortcutType = GESTURE;
            } else {
                shortcutType = buttonMode == ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR
                        ? SOFTWARE : DEFAULT;
            }
        }
        return mAccessibilityManager.getAccessibilityShortcutTargets(shortcutType).size();
    }

    /**
     * Updates the current accessibility button state. The accessibility button state is only
     * used for {@link Secure#ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR} and
     * {@link Secure#ACCESSIBILITY_BUTTON_MODE_GESTURE}, otherwise it is reset to 0.
     */
    @VisibleForTesting
    void updateA11yState() {
        final long prevState = mA11yButtonState;
        final boolean clickable;
        final boolean longClickable;
        int clickableServices = getNumOfA11yShortcutTargetsForNavSystem();
        clickable = clickableServices >= 1;
        // `longClickable` is used to determine whether to pop up the accessibility chooser
        // dialog or not, and it’s also only for multiple services.
        longClickable = clickableServices >= 2;
        mA11yButtonState = (clickable ? SYSUI_STATE_A11Y_BUTTON_CLICKABLE : 0)
                | (longClickable ? SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE : 0);

        // Update the system actions if the state has changed
        if (prevState != mA11yButtonState) {
            updateSystemAction(clickable, SYSTEM_ACTION_ID_ACCESSIBILITY_BUTTON);
            updateSystemAction(longClickable, SYSTEM_ACTION_ID_ACCESSIBILITY_BUTTON_CHOOSER);
        }

        dispatchA11yEventUpdate();
    }

    /**
     * Registers/unregisters the given system action id.
     */
    private void updateSystemAction(boolean register, int actionId) {
        if (register) {
            mSystemActions.register(actionId);
        } else {
            mSystemActions.unregister(actionId);
        }
    }

    /**
     * Gets the accessibility button state based on the {@link Secure#ACCESSIBILITY_BUTTON_MODE}.
     *
     * @return the accessibility button state:
     * 0 = disable state
     * 16 = {@link QuickStepContract#SYSUI_STATE_A11Y_BUTTON_CLICKABLE}
     * 48 = the combination of {@link QuickStepContract#SYSUI_STATE_A11Y_BUTTON_CLICKABLE} and
     * {@link QuickStepContract#SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE}
     */
    public long getA11yButtonState() {
        return mA11yButtonState;
    }

    @Override
    public void onConnectionChanged(boolean isConnected) {
        if (isConnected) {
            // We add the OPS callback during construction, so if the service is already connected
            // then we will try to get the AssistManager dependency which itself has an indirect
            // dependency on NavBarHelper leading to a cycle. For now, we can defer updating the
            // assistant availability.
            mMainExecutor.execute(this::updateAssistantAvailability);
        }
    }

    private void updateAssistantAvailability() {
        boolean assistantAvailableForUser = mAssistManagerLazy.get()
                .getAssistInfoForUser(mUserTracker.getUserId()) != null;

        boolean overrideLongPressHome = mAssistManagerLazy.get()
                .shouldOverrideAssist(AssistManager.INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS);
        boolean longPressDefault = mContext.getResources().getBoolean(overrideLongPressHome
                ? com.android.internal.R.bool.config_searchAllEntrypointsEnabledDefault
                : com.android.internal.R.bool.config_assistLongPressHomeEnabledDefault);
        mLongPressHomeEnabled = Settings.Secure.getIntForUser(mContentResolver,
                overrideLongPressHome ? Secure.SEARCH_ALL_ENTRYPOINTS_ENABLED
                        : Settings.Secure.ASSIST_LONG_PRESS_HOME_ENABLED, longPressDefault ? 1 : 0,
                mUserTracker.getUserId()) != 0;

        boolean gestureDefault = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_assistTouchGestureEnabledDefault);
        mAssistantTouchGestureEnabled = Settings.Secure.getIntForUser(mContentResolver,
                Settings.Secure.ASSIST_TOUCH_GESTURE_ENABLED, gestureDefault ? 1 : 0,
                mUserTracker.getUserId()) != 0;

        boolean supportsSwipeGesture = QuickStepContract.isGesturalMode(mNavBarMode)
                || (QuickStepContract.isLegacyMode(mNavBarMode) && Flags.threeButtonCornerSwipe());
        mAssistantAvailable = assistantAvailableForUser
                && mAssistantTouchGestureEnabled
                && supportsSwipeGesture;
        dispatchAssistantEventUpdate(mAssistantAvailable, mLongPressHomeEnabled);
    }

    public boolean getLongPressHomeEnabled() {
        return mLongPressHomeEnabled;
    }

    public EdgeBackGestureHandler getEdgeBackGestureHandler() {
        return mEdgeBackGestureHandler;
    }

    @Override
    public void startAssistant(Bundle bundle) {
        mAssistManagerLazy.get().startAssist(bundle);
    }

    @Override
    public void setAssistantOverridesRequested(int[] invocationTypes) {
        mAssistManagerLazy.get().setAssistantOverridesRequested(invocationTypes);
        updateAssistantAvailability();
    }

    @Override
    public void onNavigationModeChanged(int mode) {
        mNavBarMode = mode;
        updateAssistantAvailability();
    }

    /**
     * @return Whether the IME is shown on top of the screen given the {@code vis} flag of
     * {@link InputMethodService} and the keyguard states.
     */
    public boolean isImeShown(@ImeWindowVisibility int vis) {
        View shadeWindowView =  mNotificationShadeWindowController.getWindowRootView();
        boolean isKeyguardShowing = mKeyguardStateController.isShowing();
        boolean imeVisibleOnShade = shadeWindowView != null && shadeWindowView.isAttachedToWindow()
                && shadeWindowView.getRootWindowInsets().isVisible(WindowInsets.Type.ime());
        return imeVisibleOnShade
                || (!isKeyguardShowing && (vis & InputMethodService.IME_VISIBLE) != 0);
    }

    @Override
    public void setWindowState(int displayId, int window, int state) {
        CommandQueue.Callbacks.super.setWindowState(displayId, window, state);
        if (window != WINDOW_NAVIGATION_BAR) {
            return;
        }
        mWindowStateDisplayId = displayId;
        mWindowState = state;
    }

    private void dispatchWallpaperVisibilityChanged(boolean visible, int displayId) {
        for (NavbarTaskbarStateUpdater listener : mStateListeners) {
            listener.updateWallpaperVisibility(visible, displayId);
        }
    }

    private void dispatchRotationChanged(int rotation, @Nullable Boolean isRotationLocked) {
        for (NavbarTaskbarStateUpdater listener : mStateListeners) {
            listener.updateRotationWatcherState(rotation, isRotationLocked);
        }
    }

    public CurrentSysuiState getCurrentSysuiState() {
        return new CurrentSysuiState();
    }

    /**
     * Callbacks will get fired once immediately after registering via
     * {@link #registerNavTaskStateUpdater(NavbarTaskbarStateUpdater)}
     */
    public interface NavbarTaskbarStateUpdater {
        void updateAccessibilityServicesState();
        void updateAssistantAvailable(boolean available, boolean longPressHomeEnabled);
        default void updateWallpaperVisibility(boolean visible, int displayId) {}
        default void updateRotationWatcherState(int rotation, @Nullable Boolean isRotationLocked) {}
    }

    /** Data class to help Taskbar/Navbar initiate state correctly when switching between the two.*/
    public class CurrentSysuiState {
        public final int mWindowStateDisplayId;
        public final @WindowVisibleState int mWindowState;

        public CurrentSysuiState() {
            mWindowStateDisplayId = NavBarHelper.this.mWindowStateDisplayId;
            mWindowState = NavBarHelper.this.mWindowState;
        }
    }

    public static @TransitionMode int transitionMode(boolean isTransient, int appearance) {
        final int lightsOutOpaque = APPEARANCE_LOW_PROFILE_BARS | APPEARANCE_OPAQUE_NAVIGATION_BARS;
        if (isTransient) {
            return MODE_SEMI_TRANSPARENT;
        } else if ((appearance & lightsOutOpaque) == lightsOutOpaque) {
            return MODE_LIGHTS_OUT;
        } else if ((appearance & APPEARANCE_LOW_PROFILE_BARS) != 0) {
            return MODE_LIGHTS_OUT_TRANSPARENT;
        } else if ((appearance & APPEARANCE_OPAQUE_NAVIGATION_BARS) != 0) {
            return MODE_OPAQUE;
        } else if ((appearance & APPEARANCE_SEMI_TRANSPARENT_NAVIGATION_BARS) != 0) {
            return MODE_SEMI_TRANSPARENT;
        } else {
            return MODE_TRANSPARENT;
        }
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("NavbarTaskbarFriendster");
        pw.println("  longPressHomeEnabled=" + mLongPressHomeEnabled);
        pw.println("  mAssistantTouchGestureEnabled=" + mAssistantTouchGestureEnabled);
        pw.println("  mAssistantAvailable=" + mAssistantAvailable);
        pw.println("  mNavBarMode=" + mNavBarMode);
    }
}
