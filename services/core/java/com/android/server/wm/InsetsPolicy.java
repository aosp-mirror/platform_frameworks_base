/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.StatusBarManager.WINDOW_STATE_HIDDEN;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.view.InsetsSource.ID_IME;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.app.StatusBarManager;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.res.Resources;
import android.os.Handler;
import android.os.IBinder;
import android.util.SparseArray;
import android.view.InsetsController;
import android.view.InsetsFrameProvider;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.SyncRtSurfaceTransactionApplier;
import android.view.WindowInsets;
import android.view.WindowInsets.Type;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsAnimation;
import android.view.WindowInsetsAnimation.Bounds;
import android.view.WindowManager;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.statusbar.StatusBarManagerInternal;

import java.io.PrintWriter;
import java.util.List;

/**
 * Policy that implements who gets control over the windows generating insets.
 */
class InsetsPolicy {

    public static final int CONTROLLABLE_TYPES = WindowInsets.Type.statusBars()
            | WindowInsets.Type.navigationBars()
            | WindowInsets.Type.ime();

    private final InsetsStateController mStateController;
    private final DisplayContent mDisplayContent;
    private final DisplayPolicy mPolicy;

    /** Used to show system bars transiently. This won't affect the layout. */
    private final InsetsControlTarget mTransientControlTarget;

    /** Used to show system bars permanently. This will affect the layout. */
    private final InsetsControlTarget mPermanentControlTarget;

    /**
     * Used to override the visibility of {@link Type#statusBars()} when dispatching insets to
     * clients.
     */
    private InsetsControlTarget mFakeStatusControlTarget;

    /**
     * Used to override the visibility of {@link Type#navigationBars()} when dispatching insets to
     * clients.
     */
    private InsetsControlTarget mFakeNavControlTarget;

    private WindowState mFocusedWin;
    private final BarWindow mStatusBar = new BarWindow(StatusBarManager.WINDOW_STATUS_BAR);
    private final BarWindow mNavBar = new BarWindow(StatusBarManager.WINDOW_NAVIGATION_BAR);
    private @InsetsType int mShowingTransientTypes;
    private @InsetsType int mForcedShowingTypes;

    private final boolean mHideNavBarForKeyboard;

    InsetsPolicy(InsetsStateController stateController, DisplayContent displayContent) {
        mStateController = stateController;
        mDisplayContent = displayContent;
        mPolicy = displayContent.getDisplayPolicy();
        final Resources r = mPolicy.getContext().getResources();
        mHideNavBarForKeyboard = r.getBoolean(R.bool.config_hideNavBarForKeyboard);
        mTransientControlTarget = new ControlTarget(displayContent, "TransientControlTarget");
        mPermanentControlTarget = new ControlTarget(displayContent, "PermanentControlTarget");
    }

    /** Updates the target which can control system bars. */
    void updateBarControlTarget(@Nullable WindowState focusedWin) {
        if (mFocusedWin != focusedWin) {
            abortTransient();
        }
        mFocusedWin = focusedWin;
        final WindowState notificationShade = mPolicy.getNotificationShade();
        final WindowState topApp = mPolicy.getTopFullscreenOpaqueWindow();
        final InsetsControlTarget statusControlTarget =
                getStatusControlTarget(focusedWin, false /* fake */);
        mFakeStatusControlTarget = statusControlTarget == mTransientControlTarget
                ? getStatusControlTarget(focusedWin, true /* fake */)
                : statusControlTarget == notificationShade
                        ? getStatusControlTarget(topApp, true /* fake */)
                        : null;
        final InsetsControlTarget navControlTarget =
                getNavControlTarget(focusedWin, false /* fake */);
        mFakeNavControlTarget = navControlTarget == mTransientControlTarget
                ? getNavControlTarget(focusedWin, true /* fake */)
                : navControlTarget == notificationShade
                        ? getNavControlTarget(topApp, true /* fake */)
                        : null;
        mStateController.onBarControlTargetChanged(
                statusControlTarget, mFakeStatusControlTarget,
                navControlTarget, mFakeNavControlTarget);
        mStatusBar.updateVisibility(statusControlTarget, Type.statusBars());
        mNavBar.updateVisibility(navControlTarget, Type.navigationBars());
    }

    boolean hasHiddenSources(@InsetsType int types) {
        final InsetsState state = mStateController.getRawInsetsState();
        for (int i = state.sourceSize() - 1; i >= 0; i--) {
            final InsetsSource source = state.sourceAt(i);
            if ((source.getType() & types) == 0) {
                continue;
            }
            if (!source.getFrame().isEmpty() && !source.isVisible()) {
                return true;
            }
        }
        return false;
    }

    void showTransient(@InsetsType int types, boolean isGestureOnSystemBar) {
        @InsetsType int showingTransientTypes = mShowingTransientTypes;
        final InsetsState rawState = mStateController.getRawInsetsState();
        for (int i = rawState.sourceSize() - 1; i >= 0; i--) {
            final InsetsSource source = rawState.sourceAt(i);
            if (source.isVisible()) {
                continue;
            }
            final @InsetsType int type = source.getType();
            if ((source.getType() & types) == 0) {
                continue;
            }
            showingTransientTypes |= type;
        }
        if (mShowingTransientTypes != showingTransientTypes) {
            mShowingTransientTypes = showingTransientTypes;
            StatusBarManagerInternal statusBarManagerInternal =
                    mPolicy.getStatusBarManagerInternal();
            if (statusBarManagerInternal != null) {
                statusBarManagerInternal.showTransient(mDisplayContent.getDisplayId(),
                        showingTransientTypes, isGestureOnSystemBar);
            }
            updateBarControlTarget(mFocusedWin);
            dispatchTransientSystemBarsVisibilityChanged(
                    mFocusedWin,
                    (showingTransientTypes & (Type.statusBars() | Type.navigationBars())) != 0,
                    isGestureOnSystemBar);
        }
    }

    @VisibleForTesting
    InsetsControlTarget getTransientControlTarget() {
        return  mTransientControlTarget;
    }

    @VisibleForTesting
    InsetsControlTarget getPermanentControlTarget() {
        return  mPermanentControlTarget;
    }

    void hideTransient() {
        if (mShowingTransientTypes == 0) {
            return;
        }

        dispatchTransientSystemBarsVisibilityChanged(
                mFocusedWin,
                /* areVisible= */ false,
                /* wereRevealedFromSwipeOnSystemBar= */ false);

        mShowingTransientTypes = 0;
        updateBarControlTarget(mFocusedWin);
    }

    boolean isTransient(@InsetsType int type) {
        return (mShowingTransientTypes & type) != 0;
    }

    /**
     * Adjusts the sources in {@code originalState} to account for things like transient bars, IME
     * & rounded corners.
     */
    InsetsState adjustInsetsForWindow(WindowState target, InsetsState originalState,
            boolean includesTransient) {
        InsetsState state;
        if (!includesTransient) {
            state = adjustVisibilityForFakeControllingSources(originalState);
        } else {
            state = originalState;
        }
        state = adjustVisibilityForIme(target, state, state == originalState);
        return adjustInsetsForRoundedCorners(target.mToken, state, state == originalState);
    }

    InsetsState adjustInsetsForWindow(WindowState target, InsetsState originalState) {
        return adjustInsetsForWindow(target, originalState, false);
    }

    /**
     * @see WindowState#getInsetsState()
     */
    void getInsetsForWindowMetrics(@Nullable WindowToken token,
            @NonNull InsetsState outInsetsState) {
        final InsetsState srcState = token != null && token.isFixedRotationTransforming()
                ? token.getFixedRotationTransformInsetsState()
                : mStateController.getRawInsetsState();
        outInsetsState.set(srcState, true /* copySources */);
        for (int i = outInsetsState.sourceSize() - 1; i >= 0; i--) {
            final InsetsSource source = outInsetsState.sourceAt(i);
            if (isTransient(source.getType())) {
                source.setVisible(false);
            }
        }
        adjustInsetsForRoundedCorners(token, outInsetsState, false /* copyState */);
        if (token != null && token.hasSizeCompatBounds()) {
            outInsetsState.scale(1f / token.getCompatScale());
        }
    }

    /**
     * Modifies the given {@code state} according to insets provided by the target. When performing
     * layout of the target or dispatching insets to the target, we need to exclude sources which
     * should not be received by the target. e.g., the visible (non-gesture-wise) source provided by
     * the target window itself.
     *
     * We also need to exclude certain types of insets source for client within specific windowing
     * modes.
     *
     * @param attrs the LayoutParams of the target
     * @param windowingMode the windowing mode of the target
     * @param isAlwaysOnTop is the target always on top
     * @param state the input inset state containing all the sources
     * @return The state stripped of the necessary information.
     */
    InsetsState enforceInsetsPolicyForTarget(WindowManager.LayoutParams attrs,
            @WindowConfiguration.WindowingMode int windowingMode, boolean isAlwaysOnTop,
            InsetsState state) {
        final InsetsState originalState = state;

        // The caller should not receive the visible insets provided by itself.
        if (attrs.type == TYPE_INPUT_METHOD) {
            state = new InsetsState(state);
            state.removeSource(ID_IME);
        } else if (attrs.providedInsets != null) {
            for (InsetsFrameProvider provider : attrs.providedInsets) {
                if ((provider.getType() & WindowInsets.Type.systemBars()) == 0) {
                    continue;
                }
                if (state == originalState) {
                    state = new InsetsState(state);
                }
                state.removeSource(provider.getId());
            }
        }

        if (!attrs.isFullscreen() || attrs.getFitInsetsTypes() != 0) {
            if (state == originalState) {
                state = new InsetsState(originalState);
            }
            // Explicitly exclude floating windows from receiving caption insets. This is because we
            // hard code caption insets for windows due to a synchronization issue that leads to
            // flickering that bypasses insets frame calculation, which consequently needs us to
            // remove caption insets from floating windows.
            // TODO(b/254128050): Remove this workaround after we find a way to update window frames
            //  and caption insets frames simultaneously.
            for (int i = state.sourceSize() - 1; i >= 0; i--) {
                if (state.sourceAt(i).getType() == Type.captionBar()) {
                    state.removeSourceAt(i);
                }
            }
        }

        final SparseArray<InsetsSourceProvider> providers = mStateController.getSourceProviders();
        final int windowType = attrs.type;
        for (int i = providers.size() - 1; i >= 0; i--) {
            final InsetsSourceProvider otherProvider = providers.valueAt(i);
            if (otherProvider.overridesFrame(windowType)) {
                if (state == originalState) {
                    state = new InsetsState(state);
                }
                final InsetsSource override = new InsetsSource(otherProvider.getSource());
                override.setFrame(otherProvider.getOverriddenFrame(windowType));
                state.addSource(override);
            }
        }

        if (WindowConfiguration.isFloating(windowingMode)
                || (windowingMode == WINDOWING_MODE_MULTI_WINDOW && isAlwaysOnTop)) {
            // Keep frames, caption, and IME.
            int types = WindowInsets.Type.captionBar();
            if (windowingMode != WINDOWING_MODE_PINNED) {
                types |= WindowInsets.Type.ime();
            }
            final InsetsState newState = new InsetsState();
            newState.set(state, types);
            state = newState;
        }

        return state;
    }

    private InsetsState adjustVisibilityForFakeControllingSources(InsetsState originalState) {
        if (mFakeStatusControlTarget == null && mFakeNavControlTarget == null) {
            return originalState;
        }
        InsetsState state = originalState;
        for (int i = state.sourceSize() - 1; i >= 0; i--) {
            final InsetsSource source = state.sourceAt(i);
            state = adjustVisibilityForFakeControllingSource(state, Type.statusBars(), source,
                    mFakeStatusControlTarget);
            state = adjustVisibilityForFakeControllingSource(state, Type.navigationBars(), source,
                    mFakeNavControlTarget);
        }
        return state;
    }

    private static InsetsState adjustVisibilityForFakeControllingSource(InsetsState originalState,
            @InsetsType int type, InsetsSource source, InsetsControlTarget target) {
        if (source.getType() != type || target == null) {
            return originalState;
        }
        final boolean isRequestedVisible = target.isRequestedVisible(type);
        if (source.isVisible() == isRequestedVisible) {
            return originalState;
        }
        // The source will be modified, create a non-deep copy to store the new one.
        final InsetsState state = new InsetsState(originalState);

        // Replace the source with a copy with the overridden visibility.
        final InsetsSource outSource = new InsetsSource(source);
        outSource.setVisible(isRequestedVisible);
        state.addSource(outSource);
        return state;
    }

    private InsetsState adjustVisibilityForIme(WindowState w, InsetsState originalState,
            boolean copyState) {
        if (w.mIsImWindow) {
            InsetsState state = originalState;
            // If navigation bar is not hidden by IME, IME should always receive visible
            // navigation bar insets.
            final boolean navVisible = !mHideNavBarForKeyboard;
            for (int i = originalState.sourceSize() - 1; i >= 0; i--) {
                final InsetsSource source = originalState.sourceAt(i);
                if (source.getType() != Type.navigationBars() || source.isVisible() == navVisible) {
                    continue;
                }
                if (state == originalState && copyState) {
                    state = new InsetsState(originalState);
                }
                final InsetsSource navSource = new InsetsSource(source);
                navSource.setVisible(navVisible);
                state.addSource(navSource);
            }
            return state;
        } else if (w.mActivityRecord != null && w.mActivityRecord.mImeInsetsFrozenUntilStartInput) {
            // During switching tasks with gestural navigation, before the next IME input target
            // starts the input, we should adjust and freeze the last IME visibility of the window
            // in case delivering obsoleted IME insets state during transitioning.
            final InsetsSource originalImeSource = originalState.peekSource(ID_IME);

            if (originalImeSource != null) {
                final boolean imeVisibility = w.isRequestedVisible(Type.ime());
                final InsetsState state = copyState
                        ? new InsetsState(originalState)
                        : originalState;
                final InsetsSource imeSource = new InsetsSource(originalImeSource);
                imeSource.setVisible(imeVisibility);
                state.addSource(imeSource);
                return state;
            }
        } else if (w.mImeInsetsConsumed) {
            // Set the IME source (if there is one) to be invisible if it has been consumed.
            final InsetsSource originalImeSource = originalState.peekSource(ID_IME);
            if (originalImeSource != null && originalImeSource.isVisible()) {
                final InsetsState state = copyState
                        ? new InsetsState(originalState)
                        : originalState;
                final InsetsSource imeSource = new InsetsSource(originalImeSource);
                imeSource.setVisible(false);
                state.addSource(imeSource);
                return state;
            }
        }
        return originalState;
    }

    private InsetsState adjustInsetsForRoundedCorners(WindowToken token, InsetsState originalState,
            boolean copyState) {
        if (token != null) {
            final ActivityRecord activityRecord = token.asActivityRecord();
            final Task task = activityRecord != null ? activityRecord.getTask() : null;
            if (task != null && !task.getWindowConfiguration().tasksAreFloating()) {
                // Use task bounds to calculating rounded corners if the task is not floating.
                final InsetsState state = copyState ? new InsetsState(originalState)
                        : originalState;
                state.setRoundedCornerFrame(token.isFixedRotationTransforming()
                        ? token.getFixedRotationTransformDisplayBounds()
                        : task.getBounds());
                return state;
            }
        }
        return originalState;
    }

    void onRequestedVisibleTypesChanged(InsetsTarget caller,
            @Nullable ImeTracker.Token statsToken) {
        mStateController.onRequestedVisibleTypesChanged(caller, statsToken);
        checkAbortTransient(caller);
        updateBarControlTarget(mFocusedWin);
    }

    /**
     * Called when a control target modified the insets state. If the target set a insets source to
     * visible while it is shown transiently, we need to abort the transient state. While IME is
     * requested visible, we also need to abort the transient state of navigation bar if it is shown
     * transiently.
     *
     * @param caller who changed the insets state.
     */
    private void checkAbortTransient(InsetsTarget caller) {
        if (mShowingTransientTypes == 0) {
            return;
        }
        final boolean isImeVisible = mStateController.getImeSourceProvider().isClientVisible();
        final @InsetsType int fakeControllingTypes =
                mStateController.getFakeControllingTypes(caller);
        final @InsetsType int abortTypes =
                (fakeControllingTypes & caller.getRequestedVisibleTypes())
                | (isImeVisible ? Type.navigationBars() : 0);
        mShowingTransientTypes &= ~abortTypes;
        if (abortTypes != 0) {
            mDisplayContent.setLayoutNeeded();
            mDisplayContent.mWmService.requestTraversal();
            final StatusBarManagerInternal statusBarManager = mPolicy.getStatusBarManagerInternal();
            if (statusBarManager != null) {
                statusBarManager.abortTransient(mDisplayContent.getDisplayId(), abortTypes);
            }
        }
    }

    /**
     * If the caller is not {@link #updateBarControlTarget}, it should call
     * updateBarControlTarget(mFocusedWin) after this invocation.
     */
    private void abortTransient() {
        if (mShowingTransientTypes == 0) {
            return;
        }
        final StatusBarManagerInternal statusBarManager = mPolicy.getStatusBarManagerInternal();
        if (statusBarManager != null) {
            statusBarManager.abortTransient(mDisplayContent.getDisplayId(), mShowingTransientTypes);
        }
        mShowingTransientTypes = 0;
        mDisplayContent.setLayoutNeeded();
        mDisplayContent.mWmService.requestTraversal();

        dispatchTransientSystemBarsVisibilityChanged(
                mFocusedWin,
                /* areVisible= */ false,
                /* wereRevealedFromSwipeOnSystemBar= */ false);
    }

    private @Nullable InsetsControlTarget getStatusControlTarget(@Nullable WindowState focusedWin,
            boolean fake) {
        if (!fake && isTransient(Type.statusBars())) {
            return mTransientControlTarget;
        }
        final WindowState notificationShade = mPolicy.getNotificationShade();
        if (focusedWin == notificationShade) {
            // Notification shade has control anyways, no reason to force anything.
            return focusedWin;
        }
        if (areTypesForciblyShowing(Type.statusBars())) {
            // Status bar is forcibly shown. We don't want the client to control the status bar, and
            // we will dispatch the real visibility of status bar to the client.
            return mPermanentControlTarget;
        }
        if (mPolicy.areTypesForciblyShownTransiently(Type.statusBars()) && !fake) {
            // Status bar is forcibly shown transiently, and its new visibility won't be
            // dispatched to the client so that we can keep the layout stable. We will dispatch the
            // fake control to the client, so that it can re-show the bar during this scenario.
            return mTransientControlTarget;
        }
        if (!canBeTopFullscreenOpaqueWindow(focusedWin)
                && mPolicy.topAppHidesSystemBar(Type.statusBars())
                && (notificationShade == null || !notificationShade.canReceiveKeys())) {
            // Non-fullscreen focused window should not break the state that the top-fullscreen-app
            // window hides status bar, unless the notification shade can receive keys.
            if (remoteInsetsControllerControlsSystemBars(
                    mPolicy.getTopFullscreenOpaqueWindow())) {
                notifyRemoteInsetsController(mPolicy.getTopFullscreenOpaqueWindow());
                return mDisplayContent.mRemoteInsetsControlTarget;
            } else {
                return mPolicy.getTopFullscreenOpaqueWindow();
            }
        }
        if (remoteInsetsControllerControlsSystemBars(focusedWin)) {
            notifyRemoteInsetsController(focusedWin);
            return mDisplayContent.mRemoteInsetsControlTarget;
        }
        return focusedWin;
    }

    private static boolean canBeTopFullscreenOpaqueWindow(@Nullable WindowState win) {
        // The condition doesn't use WindowState#canAffectSystemUiFlags because the window may
        // haven't drawn or committed the visibility.
        final boolean nonAttachedAppWindow = win != null
                && win.mAttrs.type >= WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW
                && win.mAttrs.type <= WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
        return nonAttachedAppWindow && win.mAttrs.isFullscreen() && !win.isFullyTransparent()
                && !win.inMultiWindowMode();
    }

    private @Nullable InsetsControlTarget getNavControlTarget(@Nullable WindowState focusedWin,
            boolean fake) {
        final WindowState imeWin = mDisplayContent.mInputMethodWindow;
        if (imeWin != null && imeWin.isVisible() && !mHideNavBarForKeyboard) {
            // Force showing navigation bar while IME is visible and if navigation bar is not
            // configured to be hidden by the IME.
            return mPermanentControlTarget;
        }
        if (!fake && isTransient(Type.navigationBars())) {
            return mTransientControlTarget;
        }
        if (focusedWin == mPolicy.getNotificationShade()) {
            // Notification shade has control anyways, no reason to force anything.
            return focusedWin;
        }
        if (focusedWin != null) {
            final InsetsSourceProvider provider = focusedWin.getControllableInsetProvider();
            if (provider != null && provider.getSource().getType() == Type.navigationBars()) {
                // Navigation bar has control if it is focused.
                return focusedWin;
            }
        }
        if (areTypesForciblyShowing(Type.navigationBars())) {
            // Navigation bar is forcibly shown. We don't want the client to control the navigation
            // bar, and we will dispatch the real visibility of navigation bar to the client.
            return mPermanentControlTarget;
        }
        if (mPolicy.areTypesForciblyShownTransiently(Type.navigationBars()) && !fake) {
            // Navigation bar is forcibly shown transiently, and its new visibility won't be
            // dispatched to the client so that we can keep the layout stable. We will dispatch the
            // fake control to the client, so that it can re-show the bar during this scenario.
            return mTransientControlTarget;
        }
        final WindowState notificationShade = mPolicy.getNotificationShade();
        if (!canBeTopFullscreenOpaqueWindow(focusedWin)
                && mPolicy.topAppHidesSystemBar(Type.navigationBars())
                && (notificationShade == null || !notificationShade.canReceiveKeys())) {
            // Non-fullscreen focused window should not break the state that the top-fullscreen-app
            // window hides navigation bar, unless the notification shade can receive keys.
            if (remoteInsetsControllerControlsSystemBars(
                    mPolicy.getTopFullscreenOpaqueWindow())) {
                notifyRemoteInsetsController(mPolicy.getTopFullscreenOpaqueWindow());
                return mDisplayContent.mRemoteInsetsControlTarget;
            } else {
                return mPolicy.getTopFullscreenOpaqueWindow();
            }
        }
        if (remoteInsetsControllerControlsSystemBars(focusedWin)) {
            notifyRemoteInsetsController(focusedWin);
            return mDisplayContent.mRemoteInsetsControlTarget;
        }
        return focusedWin;
    }

    private void notifyRemoteInsetsController(@Nullable WindowState win) {
        if (win == null) {
            return;
        }
        ComponentName component = win.mActivityRecord != null
                ? win.mActivityRecord.mActivityComponent : null;
        mDisplayContent.mRemoteInsetsControlTarget.topFocusedWindowChanged(
                component, win.getRequestedVisibleTypes());
    }

    boolean areTypesForciblyShowing(@InsetsType int types) {
        return (mForcedShowingTypes & types) == types;
    }

    void updateSystemBars(WindowState win, boolean inSplitScreenMode, boolean inFreeformMode) {
        mForcedShowingTypes = (inSplitScreenMode || inFreeformMode)
                ? (Type.statusBars() | Type.navigationBars())
                : forceShowingNavigationBars(win)
                        ? Type.navigationBars()
                        : 0;

        // The client app won't be able to control these types of system bars. Here makes the client
        // forcibly consume these types to prevent the app content from getting obscured.
        mStateController.setForcedConsumingTypes(
                mForcedShowingTypes | (remoteInsetsControllerControlsSystemBars(win)
                        ? (Type.statusBars() | Type.navigationBars())
                        : 0));

        updateBarControlTarget(win);
    }

    private boolean forceShowingNavigationBars(WindowState win) {
        // When "force show navigation bar" is enabled, it means both force visible is true, and
        // we are in 3-button navigation. In this mode, the navigation bar is forcibly shown
        // when activity type is ACTIVITY_TYPE_STANDARD which means Launcher or Recent could
        // still control the navigation bar in this mode.
        return mPolicy.isForceShowNavigationBarEnabled() && win != null
                && win.getActivityType() == ACTIVITY_TYPE_STANDARD;
    }

    /**
     * Determines whether the remote insets controller should take control of system bars for all
     * windows.
     */
    boolean remoteInsetsControllerControlsSystemBars(@Nullable WindowState focusedWin) {
        if (focusedWin == null) {
            return false;
        }

        if (!mPolicy.isRemoteInsetsControllerControllingSystemBars()) {
            return false;
        }
        if (mDisplayContent == null || mDisplayContent.mRemoteInsetsControlTarget == null) {
            // No remote insets control target to take control of insets.
            return false;
        }
        // If necessary, auto can control application windows when
        // config_remoteInsetsControllerControlsSystemBars is set to true. This is useful in cases
        // where we want to dictate system bar inset state for applications.
        return focusedWin.getAttrs().type >= WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW
                && focusedWin.getAttrs().type <= WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
    }

    private void dispatchTransientSystemBarsVisibilityChanged(
            @Nullable WindowState focusedWindow,
            boolean areVisible,
            boolean wereRevealedFromSwipeOnSystemBar) {
        if (focusedWindow == null) {
            return;
        }

        Task task = focusedWindow.getTask();
        if (task == null) {
            return;
        }

        int taskId = task.mTaskId;
        boolean isValidTaskId = taskId != ActivityTaskManager.INVALID_TASK_ID;
        if (!isValidTaskId) {
            return;
        }

        mDisplayContent.mWmService.mTaskSystemBarsListenerController
                .dispatchTransientSystemBarVisibilityChanged(
                        taskId,
                        areVisible,
                        wereRevealedFromSwipeOnSystemBar);
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "InsetsPolicy");
        prefix = prefix + "  ";
        pw.println(prefix + "status: " + StatusBarManager.windowStateToString(mStatusBar.mState));
        pw.println(prefix + "nav: " + StatusBarManager.windowStateToString(mNavBar.mState));
        if (mShowingTransientTypes != 0) {
            pw.println(prefix + "mShowingTransientTypes="
                    + WindowInsets.Type.toString(mShowingTransientTypes));
        }
        if (mForcedShowingTypes != 0) {
            pw.println(prefix + "mForcedShowingTypes="
                    + WindowInsets.Type.toString(mForcedShowingTypes));
        }
    }

    private class BarWindow {

        private final int mId;
        private  @StatusBarManager.WindowVisibleState int mState =
                StatusBarManager.WINDOW_STATE_SHOWING;

        BarWindow(int id) {
            mId = id;
        }

        private void updateVisibility(@Nullable InsetsControlTarget controlTarget,
                @InsetsType int type) {
            setVisible(controlTarget == null || controlTarget.isRequestedVisible(type));
        }

        private void setVisible(boolean visible) {
            final int state = visible ? WINDOW_STATE_SHOWING : WINDOW_STATE_HIDDEN;
            if (mState != state) {
                mState = state;
                StatusBarManagerInternal statusBarManagerInternal =
                        mPolicy.getStatusBarManagerInternal();
                if (statusBarManagerInternal != null) {
                    statusBarManagerInternal.setWindowState(
                            mDisplayContent.getDisplayId(), mId, state);
                }
            }
        }
    }

    private static class ControlTarget implements InsetsControlTarget, Runnable {

        private final Handler mHandler;
        private final Object mGlobalLock;
        private final InsetsState mState = new InsetsState();
        private final InsetsStateController mStateController;
        private final InsetsController mInsetsController;
        private final String mName;

        ControlTarget(DisplayContent displayContent, String name) {
            mHandler = displayContent.mWmService.mH;
            mGlobalLock = displayContent.mWmService.mGlobalLock;
            mStateController = displayContent.getInsetsStateController();
            mInsetsController = new InsetsController(new Host(mHandler, name));
            mName = name;
        }

        @Override
        public void notifyInsetsControlChanged(int displayId) {
            mHandler.post(this);
        }

        @Override
        public void run() {
            synchronized (mGlobalLock) {
                mState.set(mStateController.getRawInsetsState(), true /* copySources */);
                mInsetsController.onStateChanged(mState);
                mInsetsController.onControlsChanged(mStateController.getControlsForDispatch(this));
            }
        }

        @Override
        public String toString() {
            return mName;
        }
    }

    private static class Host implements InsetsController.Host {

        private final float[] mTmpFloat9 = new float[9];
        private final Handler mHandler;
        private final String mName;

        private boolean mInsetsAnimationRunning;

        Host(Handler handler, String name) {
            mHandler = handler;
            mName = name;
        }

        @Override
        public Handler getHandler() {
            return mHandler;
        }

        @Override
        public void notifyInsetsChanged() { }

        @Override
        public void dispatchWindowInsetsAnimationPrepare(
                @NonNull WindowInsetsAnimation animation) { }

        @Override
        public Bounds dispatchWindowInsetsAnimationStart(
                @NonNull WindowInsetsAnimation animation,
                @NonNull Bounds bounds) {
            return bounds;
        }

        @Override
        public WindowInsets dispatchWindowInsetsAnimationProgress(
                @NonNull WindowInsets insets,
                @NonNull List<WindowInsetsAnimation> runningAnimations) {
            return insets;
        }

        @Override
        public void dispatchWindowInsetsAnimationEnd(
                @NonNull WindowInsetsAnimation animation) { }

        @Override
        public void applySurfaceParams(SyncRtSurfaceTransactionApplier.SurfaceParams... p) {
            final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            for (int i = p.length - 1; i >= 0; i--) {
                SyncRtSurfaceTransactionApplier.applyParams(t, p[i], mTmpFloat9);
            }
            t.apply();
            t.close();
        }

        @Override
        public void updateRequestedVisibleTypes(int types, @Nullable ImeTracker.Token statsToken) {
        }

        @Override
        public boolean hasAnimationCallbacks() {
            return false;
        }

        @Override
        public void setSystemBarsAppearance(int appearance, int mask) { }

        @Override
        public int getSystemBarsAppearance() {
            return 0;
        }

        @Override
        public void setSystemBarsBehavior(int behavior) { }

        @Override
        public int getSystemBarsBehavior() {
            return BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
        }

        @Override
        public void releaseSurfaceControlFromRt(SurfaceControl surfaceControl) {
            surfaceControl.release();
        }

        @Override
        public void addOnPreDrawRunnable(Runnable r) { }

        @Override
        public void postInsetsAnimationCallback(Runnable r) { }

        @Override
        public InputMethodManager getInputMethodManager() {
            return null;
        }

        @Nullable
        @Override
        public String getRootViewTitle() {
            return mName;
        }

        @Override
        public int dipToPx(int dips) {
            return 0;
        }

        @Nullable
        @Override
        public IBinder getWindowToken() {
            return null;
        }

        @Override
        public void notifyAnimationRunningStateChanged(boolean running) {
            mInsetsAnimationRunning = running;
        }
    }
}
