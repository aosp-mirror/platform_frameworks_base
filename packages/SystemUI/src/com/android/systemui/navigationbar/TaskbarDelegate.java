/*
 * Copyright 2021 The Android Open Source Project
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

import static android.app.ActivityManager.LOCK_TASK_MODE_PINNED;
import static android.app.StatusBarManager.NAVIGATION_HINT_BACK_ALT;
import static android.app.StatusBarManager.NAVIGATION_HINT_IME_SWITCHER_SHOWN;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import static com.android.systemui.navigationbar.NavBarHelper.transitionMode;
import static com.android.systemui.shared.statusbar.phone.BarTransitions.TransitionMode;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BACK_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_HOME_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_SWITCHER_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NAV_BAR_HIDDEN;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_OVERVIEW_DISABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SCREEN_PINNING;

import android.app.StatusBarManager;
import android.app.StatusBarManager.WindowVisibleState;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.InputMethodService.BackDispositionMode;
import android.inputmethodservice.InputMethodService.ImeWindowVisibility;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowInsetsController.Behavior;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.internal.statusbar.LetterboxDetails;
import com.android.internal.view.AppearanceRegion;
import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.recents.utilities.Utilities;
import com.android.systemui.shared.statusbar.phone.BarTransitions;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.statusbar.AutoHideUiElement;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.LightBarTransitionsController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.wm.shell.back.BackAnimation;
import com.android.wm.shell.pip.Pip;

import java.io.PrintWriter;
import java.util.Optional;
import java.util.function.Consumer;

import javax.inject.Inject;

/** */
@SysUISingleton
public class TaskbarDelegate implements CommandQueue.Callbacks,
        OverviewProxyService.OverviewProxyListener, NavigationModeController.ModeChangedListener,
        Dumpable {
    private static final String TAG = TaskbarDelegate.class.getSimpleName();

    private EdgeBackGestureHandler mEdgeBackGestureHandler;
    private final LightBarTransitionsController.Factory mLightBarTransitionsControllerFactory;
    private boolean mInitialized;
    private CommandQueue mCommandQueue;
    private OverviewProxyService mOverviewProxyService;
    private NavBarHelper mNavBarHelper;
    private NavigationModeController mNavigationModeController;
    private SysUiState mSysUiState;
    private AutoHideController mAutoHideController;
    private LightBarController mLightBarController;
    private LightBarTransitionsController mLightBarTransitionsController;
    private TaskStackChangeListeners mTaskStackChangeListeners;
    private Optional<Pip> mPipOptional;
    private int mDisplayId;
    private int mNavigationIconHints;
    private final NavBarHelper.NavbarTaskbarStateUpdater mNavbarTaskbarStateUpdater =
            new NavBarHelper.NavbarTaskbarStateUpdater() {
                @Override
                public void updateAccessibilityServicesState() {
                    updateSysuiFlags();
                }

                @Override
                public void updateAssistantAvailable(boolean available,
                        boolean longPressHomeEnabled) {
                    updateAssistantAvailability(available, longPressHomeEnabled);
                }

                @Override
                public void updateWallpaperVisibility(boolean visible, int displayId) {
                    updateWallpaperVisible(displayId, visible);
                }
            };
    private int mDisabledFlags;
    private @WindowVisibleState int mTaskBarWindowState = WINDOW_STATE_SHOWING;

    private @TransitionMode int mTransitionMode;
    private @Appearance int mAppearance;
    private @Behavior int mBehavior;
    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private Context mWindowContext;
    private ScreenPinningNotify mScreenPinningNotify;
    private final TaskStackChangeListener mTaskStackListener = new TaskStackChangeListener() {
        @Override
        public void onLockTaskModeChanged(int mode) {
            mSysUiState.setFlag(SYSUI_STATE_SCREEN_PINNING, mode == LOCK_TASK_MODE_PINNED)
                    .commitUpdate(mDisplayId);
        }
    };

    private int mNavigationMode = -1;
    private final Consumer<Rect> mPipListener;

    /**
     * Tracks the system calls for when taskbar should transiently show or hide so we can return
     * this value in {@link AutoHideUiElement#isVisible()} below.
     *
     * This also gets set by {@link #onTaskbarAutohideSuspend(boolean)} to force show the transient
     * taskbar if launcher has requested to suspend auto-hide behavior.
     */
    private boolean mTaskbarTransientShowing;
    private final AutoHideUiElement mAutoHideUiElement = new AutoHideUiElement() {
        @Override
        public void synchronizeState() {
            checkNavBarModes();
        }

        @Override
        public boolean isVisible() {
            return mTaskbarTransientShowing;
        }

        @Override
        public void hide() {
            clearTransient();
        }
    };

    private BackAnimation mBackAnimation;

    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final StatusBarStateController mStatusBarStateController;
    @Inject
    public TaskbarDelegate(Context context,
            LightBarTransitionsController.Factory lightBarTransitionsControllerFactory,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            StatusBarStateController statusBarStateController) {
        mLightBarTransitionsControllerFactory = lightBarTransitionsControllerFactory;

        mContext = context;
        mDisplayManager = mContext.getSystemService(DisplayManager.class);
        mPipListener = (bounds) -> {
            mEdgeBackGestureHandler.setPipStashExclusionBounds(bounds);
        };
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mStatusBarKeyguardViewManager.setTaskbarDelegate(this);
        mStatusBarStateController = statusBarStateController;
    }

    public void setDependencies(CommandQueue commandQueue,
            OverviewProxyService overviewProxyService,
            NavBarHelper navBarHelper,
            NavigationModeController navigationModeController,
            SysUiState sysUiState, DumpManager dumpManager,
            AutoHideController autoHideController,
            LightBarController lightBarController,
            Optional<Pip> pipOptional,
            BackAnimation backAnimation,
            TaskStackChangeListeners taskStackChangeListeners) {
        // TODO: adding this in the ctor results in a dagger dependency cycle :(
        mCommandQueue = commandQueue;
        mOverviewProxyService = overviewProxyService;
        mNavBarHelper = navBarHelper;
        mNavigationModeController = navigationModeController;
        mSysUiState = sysUiState;
        dumpManager.registerDumpable(this);
        mAutoHideController = autoHideController;
        mLightBarController = lightBarController;
        mPipOptional = pipOptional;
        mBackAnimation = backAnimation;
        mLightBarTransitionsController = createLightBarTransitionsController();
        mTaskStackChangeListeners = taskStackChangeListeners;
        mEdgeBackGestureHandler = navBarHelper.getEdgeBackGestureHandler();
    }

    // Separated into a method to keep setDependencies() clean/readable.
    private LightBarTransitionsController createLightBarTransitionsController() {

        LightBarTransitionsController controller =  mLightBarTransitionsControllerFactory.create(
                new LightBarTransitionsController.DarkIntensityApplier() {
                    @Override
                    public void applyDarkIntensity(float darkIntensity) {
                        mOverviewProxyService.onNavButtonsDarkIntensityChanged(darkIntensity);
                    }

                    @Override
                    public int getTintAnimationDuration() {
                        return LightBarTransitionsController.DEFAULT_TINT_ANIMATION_DURATION;
                    }
                });

        return controller;
    }

    public void init(int displayId) {
        Trace.beginSection("TaskbarDelegate#init");
        try {
            if (mInitialized) {
                return;
            }
            mDisplayId = displayId;
            parseCurrentSysuiState();
            mCommandQueue.addCallback(this);
            mOverviewProxyService.addCallback(this);
            onNavigationModeChanged(mNavigationModeController.addListener(this));
            mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);
            // Initialize component callback
            Display display = mDisplayManager.getDisplay(displayId);
            mWindowContext = mContext.createWindowContext(display, TYPE_APPLICATION, null);
            mScreenPinningNotify = new ScreenPinningNotify(mWindowContext);
            // Set initial state for any listeners
            updateSysuiFlags();
            mAutoHideController.setNavigationBar(mAutoHideUiElement);
            mLightBarController.setNavigationBar(mLightBarTransitionsController);
            mPipOptional.ifPresent(this::addPipExclusionBoundsChangeListener);
            mEdgeBackGestureHandler.setBackAnimation(mBackAnimation);
            mTaskStackChangeListeners.registerTaskStackListener(mTaskStackListener);
            mInitialized = true;
        } finally {
            Trace.endSection();
        }
    }

    public void destroy() {
        if (!mInitialized) {
            return;
        }
        mCommandQueue.removeCallback(this);
        mOverviewProxyService.removeCallback(this);
        mNavigationModeController.removeListener(this);
        mNavBarHelper.removeNavTaskStateUpdater(mNavbarTaskbarStateUpdater);
        mScreenPinningNotify = null;
        mWindowContext = null;
        mAutoHideController.setNavigationBar(null);
        mLightBarTransitionsController.destroy();
        mLightBarController.setNavigationBar(null);
        mPipOptional.ifPresent(this::removePipExclusionBoundsChangeListener);
        mTaskStackChangeListeners.unregisterTaskStackListener(mTaskStackListener);
        mInitialized = false;
    }

    void addPipExclusionBoundsChangeListener(Pip pip) {
        pip.addPipExclusionBoundsChangeListener(mPipListener);
    }

    void removePipExclusionBoundsChangeListener(Pip pip) {
        pip.removePipExclusionBoundsChangeListener(mPipListener);
    }

    /**
     * Returns {@code true} if this taskBar is {@link #init(int)}.
     * Returns {@code false} if this taskbar has not yet been {@link #init(int)}
     * or has been {@link #destroy()}.
     */
    public boolean isInitialized() {
        return mInitialized;
    }

    private void parseCurrentSysuiState() {
        NavBarHelper.CurrentSysuiState state = mNavBarHelper.getCurrentSysuiState();
        if (state.mWindowStateDisplayId == mDisplayId) {
            mTaskBarWindowState = state.mWindowState;
        }
    }

    private void updateSysuiFlags() {
        long a11yFlags = mNavBarHelper.getA11yButtonState();
        boolean clickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_CLICKABLE) != 0;
        boolean longClickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE) != 0;

        mSysUiState.setFlag(SYSUI_STATE_A11Y_BUTTON_CLICKABLE, clickable)
                .setFlag(SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE, longClickable)
                .setFlag(SYSUI_STATE_IME_SHOWING,
                        (mNavigationIconHints & NAVIGATION_HINT_BACK_ALT) != 0)
                .setFlag(SYSUI_STATE_IME_SWITCHER_SHOWING,
                        (mNavigationIconHints & NAVIGATION_HINT_IME_SWITCHER_SHOWN) != 0)
                .setFlag(SYSUI_STATE_OVERVIEW_DISABLED,
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0)
                .setFlag(SYSUI_STATE_HOME_DISABLED,
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0)
                .setFlag(SYSUI_STATE_BACK_DISABLED,
                        (mDisabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0)
                .setFlag(SYSUI_STATE_NAV_BAR_HIDDEN, !isWindowVisible())
                .setFlag(SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY,
                        allowSystemGestureIgnoringBarVisibility())
                .commitUpdate(mDisplayId);
    }

    boolean isOverviewEnabled() {
        return (mSysUiState.getFlags() & View.STATUS_BAR_DISABLE_RECENT) == 0;
    }

    void onTransitionModeUpdated(int barMode, boolean checkBarModes) {
        if (mOverviewProxyService.getProxy() == null) {
            return;
        }

        try {
            mOverviewProxyService.getProxy().onTransitionModeUpdated(barMode, checkBarModes);
        } catch (RemoteException e) {
            Log.e(TAG, "onTransitionModeUpdated() failed, barMode: " + barMode, e);
        }
    }

    void checkNavBarModes() {
        if (mOverviewProxyService.getProxy() == null) {
            return;
        }

        try {
            mOverviewProxyService.getProxy().checkNavBarModes();
        } catch (RemoteException e) {
            Log.e(TAG, "checkNavBarModes() failed", e);
        }
    }

    void finishBarAnimations() {
        if (mOverviewProxyService.getProxy() == null) {
            return;
        }

        try {
            mOverviewProxyService.getProxy().finishBarAnimations();
        } catch (RemoteException e) {
            Log.e(TAG, "finishBarAnimations() failed", e);
        }
    }

    void touchAutoDim() {
        if (mOverviewProxyService.getProxy() == null) {
            return;
        }

        try {
            int state = mStatusBarStateController.getState();
            boolean shouldReset =
                    state != StatusBarState.KEYGUARD && state != StatusBarState.SHADE_LOCKED;
            mOverviewProxyService.getProxy().touchAutoDim(shouldReset);
        } catch (RemoteException e) {
            Log.e(TAG, "touchAutoDim() failed", e);
        }
    }

    void transitionTo(@BarTransitions.TransitionMode int barMode, boolean animate) {
        if (mOverviewProxyService.getProxy() == null) {
            return;
        }

        try {
            mOverviewProxyService.getProxy().transitionTo(barMode, animate);
        } catch (RemoteException e) {
            Log.e(TAG, "transitionTo() failed, barMode: " + barMode, e);
        }
    }
    private void updateAssistantAvailability(boolean assistantAvailable,
            boolean longPressHomeEnabled) {
        if (mOverviewProxyService.getProxy() == null) {
            return;
        }

        try {
            mOverviewProxyService.getProxy().onAssistantAvailable(assistantAvailable,
                    longPressHomeEnabled);
        } catch (RemoteException e) {
            Log.e(TAG, "onAssistantAvailable() failed, available: " + assistantAvailable, e);
        }
    }

    private void updateWallpaperVisible(int displayId, boolean visible) {
        if (mOverviewProxyService.getProxy() == null) {
            return;
        }

        try {
            mOverviewProxyService.getProxy().updateWallpaperVisibility(displayId, visible);
        } catch (RemoteException e) {
            Log.e(TAG, "updateWallpaperVisibility() failed, visible: " + visible, e);
        }
    }

    private void appTransitionPending(boolean pending) {
        if (mOverviewProxyService.getProxy() == null) {
            return;
        }

        try {
            mOverviewProxyService.getProxy().appTransitionPending(pending);
        } catch (RemoteException e) {
            Log.e(TAG, "appTransitionPending() failed, pending: " + pending, e);
        }
    }

    @Override
    public void setImeWindowStatus(int displayId, @ImeWindowVisibility int vis,
            @BackDispositionMode int backDisposition, boolean showImeSwitcher) {
        boolean imeShown = mNavBarHelper.isImeShown(vis);
        if (!imeShown) {
            // Count imperceptible changes as visible so we transition taskbar out quickly.
            imeShown = (vis & InputMethodService.IME_VISIBLE_IMPERCEPTIBLE) != 0;
        }
        showImeSwitcher = imeShown && showImeSwitcher;
        int hints = Utilities.calculateBackDispositionHints(mNavigationIconHints, backDisposition,
                imeShown, showImeSwitcher);
        if (hints != mNavigationIconHints) {
            mNavigationIconHints = hints;
            updateSysuiFlags();
        }
    }

    @Override
    public void setWindowState(int displayId, int window, int state) {
        if (displayId == mDisplayId
                && window == StatusBarManager.WINDOW_NAVIGATION_BAR
                && mTaskBarWindowState != state) {
            mTaskBarWindowState = state;
            updateSysuiFlags();
        }
    }

    @Override
    public void onRotationProposal(int rotation, boolean isValid) {
        mOverviewProxyService.onRotationProposal(rotation, isValid);
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        mDisabledFlags = state1;
        updateSysuiFlags();
        mOverviewProxyService.disable(displayId, state1, state2, animate);
    }

    @Override
    public void onSystemBarAttributesChanged(int displayId, int appearance,
            AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme, int behavior,
            @InsetsType int requestedVisibleTypes, String packageName,
            LetterboxDetails[] letterboxDetails) {
        mOverviewProxyService.onSystemBarAttributesChanged(displayId, behavior);
        boolean nbModeChanged = false;
        if (mAppearance != appearance) {
            mAppearance = appearance;
            nbModeChanged = updateTransitionMode(
                    transitionMode(mTaskbarTransientShowing, appearance));
        }
        if (displayId == mDisplayId) {
            mLightBarController.onNavigationBarAppearanceChanged(appearance, nbModeChanged,
                    mTransitionMode, navbarColorManagedByIme);
        }
        if (mBehavior != behavior) {
            mBehavior = behavior;
            updateSysuiFlags();
        }
    }

    @Override
    public void showTransient(int displayId, @InsetsType int types, boolean isGestureOnSystemBar) {
        if (displayId != mDisplayId) {
            return;
        }
        if ((types & WindowInsets.Type.navigationBars()) == 0) {
            return;
        }
        if (!mTaskbarTransientShowing) {
            mTaskbarTransientShowing = true;
            onTransientStateChanged();
        }
    }

    @Override
    public void abortTransient(int displayId, @InsetsType int types) {
        if (displayId != mDisplayId) {
            return;
        }
        if ((types & WindowInsets.Type.navigationBars()) == 0) {
            return;
        }
        clearTransient();
    }

    @Override
    public void onTaskbarAutohideSuspend(boolean suspend) {
        if (suspend) {
            mAutoHideController.suspendAutoHide();
        } else {
            mAutoHideController.resumeSuspendedAutoHide();
        }
    }

    @Override
    public void toggleTaskbar() {
        if (mOverviewProxyService.getProxy() == null) {
            return;
        }

        try {
            mOverviewProxyService.getProxy().onTaskbarToggled();
        } catch (RemoteException e) {
            Log.e(TAG, "onTaskbarToggled() failed", e);
        }
    }

    @Override
    public void appTransitionPending(int displayId, boolean forced) {
        appTransitionPending(true);
    }

    @Override
    public void appTransitionStarting(int displayId, long startTime, long duration,
            boolean forced) {
        appTransitionPending(false);
    }

    @Override
    public void appTransitionCancelled(int displayId) {
        appTransitionPending(false);
    }

    @Override
    public void appTransitionFinished(int displayId) {
        appTransitionPending(false);
    }

    private void clearTransient() {
        if (mTaskbarTransientShowing) {
            mTaskbarTransientShowing = false;
            onTransientStateChanged();
        }
    }

    private void onTransientStateChanged() {
        mEdgeBackGestureHandler.onNavBarTransientStateChanged(mTaskbarTransientShowing);

        final int transitionMode = transitionMode(mTaskbarTransientShowing, mAppearance);
        if (updateTransitionMode(transitionMode)) {
            mLightBarController.onNavigationBarModeChanged(transitionMode);
        }
    }

    private boolean updateTransitionMode(int barMode) {
        if (mTransitionMode != barMode) {
            mTransitionMode = barMode;
            onTransitionModeUpdated(barMode, true);
            if (mAutoHideController != null) {
                mAutoHideController.touchAutoHide();
            }

            return true;
        }
        return false;
    }

    @Override
    public void onNavigationModeChanged(int mode) {
        mNavigationMode = mode;
        mEdgeBackGestureHandler.onNavigationModeChanged(mode);
    }

    private boolean isWindowVisible() {
        return mTaskBarWindowState == WINDOW_STATE_SHOWING;
    }

    private boolean allowSystemGestureIgnoringBarVisibility() {
        return mBehavior != BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
    }

    @Override
    public void setNavigationBarLumaSamplingEnabled(int displayId, boolean enable) {
        mOverviewProxyService.onNavigationBarLumaSamplingEnabled(displayId, enable);
    }

    @Override
    public void showPinningEnterExitToast(boolean entering) {
        updateSysuiFlags();
        if (mScreenPinningNotify == null) {
            return;
        }
        if (entering) {
            mScreenPinningNotify.showPinningStartToast();
        } else {
            mScreenPinningNotify.showPinningExitToast();
        }
    }

    @Override
    public void showPinningEscapeToast() {
        updateSysuiFlags();
        if (mScreenPinningNotify == null) {
            return;
        }
        mScreenPinningNotify.showEscapeToast(QuickStepContract.isGesturalMode(mNavigationMode),
                !QuickStepContract.isGesturalMode(mNavigationMode));
    }

    @VisibleForTesting
    int getNavigationMode() {
        return mNavigationMode;
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("TaskbarDelegate (displayId=" + mDisplayId + "):");
        pw.println("  mNavigationIconHints=" + mNavigationIconHints);
        pw.println("  mNavigationMode=" + mNavigationMode);
        pw.println("  mDisabledFlags=" + mDisabledFlags);
        pw.println("  mTaskBarWindowState=" + mTaskBarWindowState);
        pw.println("  mBehavior=" + mBehavior);
        pw.println("  mTaskbarTransientShowing=" + mTaskbarTransientShowing);
        mEdgeBackGestureHandler.dump(pw);
    }
}
