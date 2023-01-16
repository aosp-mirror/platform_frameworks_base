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
import static android.view.InsetsController.ANIMATION_TYPE_HIDE;
import static android.view.InsetsController.ANIMATION_TYPE_SHOW;
import static android.view.InsetsController.LAYOUT_INSETS_DURING_ANIMATION_HIDDEN;
import static android.view.InsetsController.LAYOUT_INSETS_DURING_ANIMATION_SHOWN;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.SyncRtSurfaceTransactionApplier.applyParams;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.app.StatusBarManager;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.res.Resources;
import android.util.ArrayMap;
import android.util.IntArray;
import android.util.SparseArray;
import android.view.InsetsAnimationControlCallbacks;
import android.view.InsetsAnimationControlImpl;
import android.view.InsetsAnimationControlRunner;
import android.view.InsetsController;
import android.view.InsetsFrameProvider;
import android.view.InsetsSource;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.InsetsState.InternalInsetsType;
import android.view.InternalInsetsAnimationController;
import android.view.SurfaceControl;
import android.view.SyncRtSurfaceTransactionApplier;
import android.view.WindowInsets;
import android.view.WindowInsets.Type;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsAnimation;
import android.view.WindowInsetsAnimation.Bounds;
import android.view.WindowInsetsAnimationControlListener;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.DisplayThread;
import com.android.server.statusbar.StatusBarManagerInternal;

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
    private final IntArray mShowingTransientTypes = new IntArray();

    /** For resetting visibilities of insets sources. */
    private final InsetsControlTarget mDummyControlTarget = new InsetsControlTarget() {

        @Override
        public void notifyInsetsControlChanged() {
            boolean hasLeash = false;
            final InsetsSourceControl[] controls =
                    mStateController.getControlsForDispatch(this);
            if (controls == null) {
                return;
            }
            for (InsetsSourceControl control : controls) {
                if (mShowingTransientTypes.indexOf(control.getId()) != -1) {
                    // The visibilities of transient bars will be handled with animations.
                    continue;
                }
                final SurfaceControl leash = control.getLeash();
                if (leash != null) {
                    hasLeash = true;

                    // We use alpha to control the visibility here which aligns the logic at
                    // SurfaceAnimator.createAnimationLeash
                    final boolean visible =
                            (control.getType() & WindowInsets.Type.defaultVisible()) != 0;
                    mDisplayContent.getPendingTransaction().setAlpha(leash, visible ? 1f : 0f);
                }
            }
            if (hasLeash) {
                mDisplayContent.scheduleAnimation();
            }
        }
    };

    private WindowState mFocusedWin;
    private BarWindow mStatusBar = new BarWindow(StatusBarManager.WINDOW_STATUS_BAR);
    private BarWindow mNavBar = new BarWindow(StatusBarManager.WINDOW_NAVIGATION_BAR);
    private boolean mAnimatingShown;
    /**
     * Let remote insets controller control system bars regardless of other settings.
     */
    private boolean mRemoteInsetsControllerControlsSystemBars;
    private final boolean mHideNavBarForKeyboard;
    private final float[] mTmpFloat9 = new float[9];

    InsetsPolicy(InsetsStateController stateController, DisplayContent displayContent) {
        mStateController = stateController;
        mDisplayContent = displayContent;
        mPolicy = displayContent.getDisplayPolicy();
        final Resources r = mPolicy.getContext().getResources();
        mRemoteInsetsControllerControlsSystemBars = r.getBoolean(
                R.bool.config_remoteInsetsControllerControlsSystemBars);
        mHideNavBarForKeyboard = r.getBoolean(R.bool.config_hideNavBarForKeyboard);
    }

    boolean getRemoteInsetsControllerControlsSystemBars() {
        return mRemoteInsetsControllerControlsSystemBars;
    }

    /**
     * Used only for testing.
     */
    @VisibleForTesting
    void setRemoteInsetsControllerControlsSystemBars(boolean controlsSystemBars) {
        mRemoteInsetsControllerControlsSystemBars = controlsSystemBars;
    }

    /** Updates the target which can control system bars. */
    void updateBarControlTarget(@Nullable WindowState focusedWin) {
        if (mFocusedWin != focusedWin) {
            abortTransient();
        }
        mFocusedWin = focusedWin;
        final InsetsControlTarget statusControlTarget =
                getStatusControlTarget(focusedWin, false /* fake */);
        final InsetsControlTarget navControlTarget =
                getNavControlTarget(focusedWin, false /* fake */);
        final WindowState notificationShade = mPolicy.getNotificationShade();
        final WindowState topApp = mPolicy.getTopFullscreenOpaqueWindow();
        mStateController.onBarControlTargetChanged(
                statusControlTarget,
                statusControlTarget == mDummyControlTarget
                        ? getStatusControlTarget(focusedWin, true /* fake */)
                        : statusControlTarget == notificationShade
                                ? getStatusControlTarget(topApp, true /* fake */)
                                : null,
                navControlTarget,
                navControlTarget == mDummyControlTarget
                        ? getNavControlTarget(focusedWin, true /* fake */)
                        : navControlTarget == notificationShade
                                ? getNavControlTarget(topApp, true /* fake */)
                                : null);
        mStatusBar.updateVisibility(statusControlTarget, Type.statusBars());
        mNavBar.updateVisibility(navControlTarget, Type.navigationBars());
    }

    boolean isHidden(@InternalInsetsType int type) {
        final WindowContainerInsetsSourceProvider provider = mStateController
                .peekSourceProvider(type);
        return provider != null && provider.hasWindowContainer()
                && !provider.getSource().isVisible();
    }

    void showTransient(@InternalInsetsType int[] types, boolean isGestureOnSystemBar) {
        boolean changed = false;
        for (int i = types.length - 1; i >= 0; i--) {
            final @InternalInsetsType int type = types[i];
            if (!isHidden(type)) {
                continue;
            }
            if (mShowingTransientTypes.indexOf(type) != -1) {
                continue;
            }
            mShowingTransientTypes.add(type);
            changed = true;
        }
        if (changed) {
            StatusBarManagerInternal statusBarManagerInternal =
                    mPolicy.getStatusBarManagerInternal();
            if (statusBarManagerInternal != null) {
                statusBarManagerInternal.showTransient(mDisplayContent.getDisplayId(),
                        mShowingTransientTypes.toArray(), isGestureOnSystemBar);
            }
            updateBarControlTarget(mFocusedWin);
            dispatchTransientSystemBarsVisibilityChanged(
                    mFocusedWin,
                    isTransient(ITYPE_STATUS_BAR) || isTransient(ITYPE_NAVIGATION_BAR),
                    isGestureOnSystemBar);

            // The leashes can be created while updating bar control target. The surface transaction
            // of the new leashes might not be applied yet. The callback posted here ensures we can
            // get the valid leashes because the surface transaction will be applied in the next
            // animation frame which will be triggered if a new leash is created.
            mDisplayContent.mWmService.mAnimator.getChoreographer().postFrameCallback(time -> {
                synchronized (mDisplayContent.mWmService.mGlobalLock) {
                    startAnimation(true /* show */, null /* callback */);
                }
            });
        }
    }

    void hideTransient() {
        if (mShowingTransientTypes.size() == 0) {
            return;
        }

        dispatchTransientSystemBarsVisibilityChanged(
                mFocusedWin,
                /* areVisible= */ false,
                /* wereRevealedFromSwipeOnSystemBar= */ false);

        startAnimation(false /* show */, () -> {
            synchronized (mDisplayContent.mWmService.mGlobalLock) {
                for (int i = mShowingTransientTypes.size() - 1; i >= 0; i--) {
                    // We are about to clear mShowingTransientTypes, we don't want the transient bar
                    // can cause insets on the client. Restore the client visibility.
                    final @InternalInsetsType int type = mShowingTransientTypes.get(i);
                    mStateController.getSourceProvider(type).setClientVisible(false);
                }
                mShowingTransientTypes.clear();
                updateBarControlTarget(mFocusedWin);
            }
        });
    }

    boolean isTransient(@InternalInsetsType int type) {
        return mShowingTransientTypes.indexOf(type) != -1;
    }

    /**
     * Adjusts the sources in {@code originalState} to account for things like transient bars, IME
     * & rounded corners.
     */
    InsetsState adjustInsetsForWindow(WindowState target, InsetsState originalState,
            boolean includesTransient) {
        InsetsState state;
        if (!includesTransient) {
            state = adjustVisibilityForTransientTypes(originalState);
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
        for (int i = mShowingTransientTypes.size() - 1; i >= 0; i--) {
            final InsetsSource source = outInsetsState.peekSource(mShowingTransientTypes.get(i));
            if (source != null) {
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
            state.removeSource(ITYPE_IME);
        } else if (attrs.providedInsets != null) {
            for (InsetsFrameProvider provider : attrs.providedInsets) {
                // TODO(b/234093736): Let InsetsFrameProvider return the public type and the ID.
                final int sourceId = provider.type;
                final @InsetsType int type = InsetsState.toPublicType(sourceId);
                if ((type & WindowInsets.Type.systemBars()) == 0) {
                    continue;
                }
                if (state == originalState) {
                    state = new InsetsState(state);
                }
                state.removeSource(sourceId);
            }
        }

        final ArrayMap<Integer, WindowContainerInsetsSourceProvider> providers = mStateController
                .getSourceProviders();
        final int windowType = attrs.type;
        for (int i = providers.size() - 1; i >= 0; i--) {
            final WindowContainerInsetsSourceProvider otherProvider = providers.valueAt(i);
            if (otherProvider.overridesFrame(windowType)) {
                if (state == originalState) {
                    state = new InsetsState(state);
                }
                final InsetsSource override =
                        new InsetsSource(state.getSource(otherProvider.getSource().getId()));
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

    private InsetsState adjustVisibilityForTransientTypes(InsetsState originalState) {
        InsetsState state = originalState;
        for (int i = mShowingTransientTypes.size() - 1; i >= 0; i--) {
            final @InternalInsetsType int type = mShowingTransientTypes.get(i);
            final InsetsSource originalSource = state.peekSource(type);
            if (originalSource != null && originalSource.isVisible()) {
                if (state == originalState) {
                    // The source will be modified, create a non-deep copy to store the new one.
                    state = new InsetsState(originalState);
                }
                // Replace the source with a copy in invisible state.
                final InsetsSource source = new InsetsSource(originalSource);
                source.setVisible(false);
                state.addSource(source);
            }
        }
        return state;
    }

    private InsetsState adjustVisibilityForIme(WindowState w, InsetsState originalState,
            boolean copyState) {
        if (w.mIsImWindow) {
            // If navigation bar is not hidden by IME, IME should always receive visible
            // navigation bar insets.
            final boolean navVisible = !mHideNavBarForKeyboard;
            final InsetsSource originalNavSource = originalState.peekSource(ITYPE_NAVIGATION_BAR);
            if (originalNavSource != null && originalNavSource.isVisible() != navVisible) {
                final InsetsState state = copyState ? new InsetsState(originalState)
                        : originalState;
                final InsetsSource navSource = new InsetsSource(originalNavSource);
                navSource.setVisible(navVisible);
                state.addSource(navSource);
                return state;
            }
        } else if (w.mActivityRecord != null && w.mActivityRecord.mImeInsetsFrozenUntilStartInput) {
            // During switching tasks with gestural navigation, before the next IME input target
            // starts the input, we should adjust and freeze the last IME visibility of the window
            // in case delivering obsoleted IME insets state during transitioning.
            final InsetsSource originalImeSource = originalState.peekSource(ITYPE_IME);

            if (originalImeSource != null) {
                final boolean imeVisibility = w.isRequestedVisible(Type.ime());
                final InsetsState state = copyState ? new InsetsState(originalState)
                        : originalState;
                final InsetsSource imeSource = new InsetsSource(originalImeSource);
                imeSource.setVisible(imeVisibility);
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
                state.setRoundedCornerFrame(task.getBounds());
                return state;
            }
        }
        return originalState;
    }

    void onInsetsModified(InsetsControlTarget caller) {
        mStateController.onInsetsModified(caller);
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
    private void checkAbortTransient(InsetsControlTarget caller) {
        if (mShowingTransientTypes.size() != 0) {
            final IntArray abortTypes = new IntArray();
            final boolean imeRequestedVisible = caller.isRequestedVisible(Type.ime());
            for (int i = mShowingTransientTypes.size() - 1; i >= 0; i--) {
                final @InternalInsetsType int type = mShowingTransientTypes.get(i);
                if ((mStateController.isFakeTarget(type, caller)
                                && caller.isRequestedVisible(InsetsState.toPublicType(type)))
                        || (type == ITYPE_NAVIGATION_BAR && imeRequestedVisible)) {
                    mShowingTransientTypes.remove(i);
                    abortTypes.add(type);
                }
            }
            StatusBarManagerInternal statusBarManagerInternal =
                    mPolicy.getStatusBarManagerInternal();
            if (abortTypes.size() > 0 && statusBarManagerInternal != null) {
                statusBarManagerInternal.abortTransient(
                        mDisplayContent.getDisplayId(), abortTypes.toArray());
            }
        }
    }

    /**
     * If the caller is not {@link #updateBarControlTarget}, it should call
     * updateBarControlTarget(mFocusedWin) after this invocation.
     */
    private void abortTransient() {
        StatusBarManagerInternal statusBarManagerInternal = mPolicy.getStatusBarManagerInternal();
        if (statusBarManagerInternal != null) {
            statusBarManagerInternal.abortTransient(
                    mDisplayContent.getDisplayId(), mShowingTransientTypes.toArray());
        }
        mShowingTransientTypes.clear();

        dispatchTransientSystemBarsVisibilityChanged(
                mFocusedWin,
                /* areVisible= */ false,
                /* wereRevealedFromSwipeOnSystemBar= */ false);
    }

    private @Nullable InsetsControlTarget getStatusControlTarget(@Nullable WindowState focusedWin,
            boolean fake) {
        if (!fake && isShowingTransientTypes(Type.statusBars())) {
            return mDummyControlTarget;
        }
        final WindowState notificationShade = mPolicy.getNotificationShade();
        if (focusedWin == notificationShade) {
            // Notification shade has control anyways, no reason to force anything.
            return focusedWin;
        }
        if (remoteInsetsControllerControlsSystemBars(focusedWin)) {
            ComponentName component = focusedWin.mActivityRecord != null
                    ? focusedWin.mActivityRecord.mActivityComponent : null;
            mDisplayContent.mRemoteInsetsControlTarget.topFocusedWindowChanged(
                    component, focusedWin.getRequestedVisibleTypes());
            return mDisplayContent.mRemoteInsetsControlTarget;
        }
        if (mPolicy.areSystemBarsForcedShownLw()) {
            // Status bar is forcibly shown. We don't want the client to control the status bar, and
            // we will dispatch the real visibility of status bar to the client.
            return null;
        }
        if (forceShowsStatusBarTransiently() && !fake) {
            // Status bar is forcibly shown transiently, and its new visibility won't be
            // dispatched to the client so that we can keep the layout stable. We will dispatch the
            // fake control to the client, so that it can re-show the bar during this scenario.
            return mDummyControlTarget;
        }
        if (!canBeTopFullscreenOpaqueWindow(focusedWin) && mPolicy.topAppHidesStatusBar()
                && (notificationShade == null || !notificationShade.canReceiveKeys())) {
            // Non-fullscreen focused window should not break the state that the top-fullscreen-app
            // window hides status bar, unless the notification shade can receive keys.
            return mPolicy.getTopFullscreenOpaqueWindow();
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
            return null;
        }
        if (!fake && isShowingTransientTypes(Type.navigationBars())) {
            return mDummyControlTarget;
        }
        if (focusedWin == mPolicy.getNotificationShade()) {
            // Notification shade has control anyways, no reason to force anything.
            return focusedWin;
        }
        if (mPolicy.isForceShowNavigationBarEnabled() && focusedWin != null
                && focusedWin.getActivityType() == ACTIVITY_TYPE_STANDARD) {
            // When "force show navigation bar" is enabled, it means both force visible is true, and
            // we are in 3-button navigation. In this mode, the navigation bar is forcibly shown
            // when activity type is ACTIVITY_TYPE_STANDARD which means Launcher or Recent could
            // still control the navigation bar in this mode.
            return null;
        }
        if (remoteInsetsControllerControlsSystemBars(focusedWin)) {
            ComponentName component = focusedWin.mActivityRecord != null
                    ? focusedWin.mActivityRecord.mActivityComponent : null;
            mDisplayContent.mRemoteInsetsControlTarget.topFocusedWindowChanged(
                    component, focusedWin.getRequestedVisibleTypes());
            return mDisplayContent.mRemoteInsetsControlTarget;
        }
        if (mPolicy.areSystemBarsForcedShownLw()) {
            // Navigation bar is forcibly shown. We don't want the client to control the navigation
            // bar, and we will dispatch the real visibility of navigation bar to the client.
            return null;
        }
        if (forceShowsNavigationBarTransiently() && !fake) {
            // Navigation bar is forcibly shown transiently, and its new visibility won't be
            // dispatched to the client so that we can keep the layout stable. We will dispatch the
            // fake control to the client, so that it can re-show the bar during this scenario.
            return mDummyControlTarget;
        }
        return focusedWin;
    }

    private boolean isShowingTransientTypes(@InsetsType int types) {
        final IntArray showingTransientTypes = mShowingTransientTypes;
        for (int i = showingTransientTypes.size() - 1; i >= 0; i--) {
            if ((InsetsState.toPublicType(showingTransientTypes.get(i)) & types) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Determines whether the remote insets controller should take control of system bars for all
     * windows.
     */
    boolean remoteInsetsControllerControlsSystemBars(@Nullable WindowState focusedWin) {
        if (focusedWin == null) {
            return false;
        }
        if (!mRemoteInsetsControllerControlsSystemBars) {
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

    private boolean forceShowsStatusBarTransiently() {
        final WindowState win = mPolicy.getStatusBar();
        return win != null && (win.mAttrs.privateFlags & PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR) != 0;
    }

    private boolean forceShowsNavigationBarTransiently() {
        final WindowState win = mPolicy.getNotificationShade();
        return win != null
                && (win.mAttrs.privateFlags & PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION) != 0;
    }

    @VisibleForTesting
    void startAnimation(boolean show, Runnable callback) {
        int typesReady = 0;
        final SparseArray<InsetsSourceControl> controls = new SparseArray<>();
        final IntArray showingTransientTypes = mShowingTransientTypes;
        for (int i = showingTransientTypes.size() - 1; i >= 0; i--) {
            final int sourceId = showingTransientTypes.get(i);
            final WindowContainerInsetsSourceProvider provider =
                    mStateController.getSourceProvider(sourceId);
            final InsetsSourceControl control = provider.getControl(mDummyControlTarget);
            if (control == null || control.getLeash() == null) {
                continue;
            }
            typesReady |= control.getType();
            controls.put(sourceId, new InsetsSourceControl(control));
        }
        controlAnimationUnchecked(typesReady, controls, show, callback);
    }

    private void controlAnimationUnchecked(int typesReady,
            SparseArray<InsetsSourceControl> controls, boolean show, Runnable callback) {
        InsetsPolicyAnimationControlListener listener =
                new InsetsPolicyAnimationControlListener(show, callback, typesReady);
        listener.mControlCallbacks.controlAnimationUnchecked(typesReady, controls, show);
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

    private class InsetsPolicyAnimationControlListener extends
            InsetsController.InternalAnimationControlListener {
        Runnable mFinishCallback;
        InsetsPolicyAnimationControlCallbacks mControlCallbacks;

        InsetsPolicyAnimationControlListener(boolean show, Runnable finishCallback, int types) {
            super(show, false /* hasCallbacks */, types, BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
                    false /* disable */, 0 /* floatingImeBottomInsets */,
                    null /* loggingListener */, null /* jankContext */);
            mFinishCallback = finishCallback;
            mControlCallbacks = new InsetsPolicyAnimationControlCallbacks(this);
        }

        @Override
        protected void onAnimationFinish() {
            super.onAnimationFinish();
            if (mFinishCallback != null) {
                DisplayThread.getHandler().post(mFinishCallback);
            }
        }

        private class InsetsPolicyAnimationControlCallbacks implements
                InsetsAnimationControlCallbacks {
            private InsetsAnimationControlImpl mAnimationControl = null;
            private InsetsPolicyAnimationControlListener mListener;

            InsetsPolicyAnimationControlCallbacks(InsetsPolicyAnimationControlListener listener) {
                mListener = listener;
            }

            private void controlAnimationUnchecked(int typesReady,
                    SparseArray<InsetsSourceControl> controls, boolean show) {
                if (typesReady == 0) {
                    // nothing to animate.
                    return;
                }
                mAnimatingShown = show;

                final InsetsState state = mFocusedWin.getInsetsState();

                // We are about to playing the default animation. Passing a null frame indicates
                // the controlled types should be animated regardless of the frame.
                mAnimationControl = new InsetsAnimationControlImpl(controls,
                        null /* frame */, state, mListener, typesReady, this,
                        mListener.getDurationMs(), getInsetsInterpolator(),
                        show ? ANIMATION_TYPE_SHOW : ANIMATION_TYPE_HIDE, show
                                ? LAYOUT_INSETS_DURING_ANIMATION_SHOWN
                                : LAYOUT_INSETS_DURING_ANIMATION_HIDDEN,
                        null /* translator */, null /* statsToken */);
                SurfaceAnimationThread.getHandler().post(
                        () -> mListener.onReady(mAnimationControl, typesReady));
            }

            /** Called on SurfaceAnimationThread without global WM lock held. */
            @Override
            public void scheduleApplyChangeInsets(InsetsAnimationControlRunner runner) {
                if (mAnimationControl.applyChangeInsets(null /* outState */)) {
                    mAnimationControl.finish(mAnimatingShown);
                }
            }

            @Override
            public void notifyFinished(InsetsAnimationControlRunner runner, boolean shown) {
                // Nothing's needed here. Finish steps is handled in the listener
                // onAnimationFinished callback.
            }

            /** Called on SurfaceAnimationThread without global WM lock held. */
            @Override
            public void applySurfaceParams(
                    final SyncRtSurfaceTransactionApplier.SurfaceParams... params) {
                SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                for (int i = params.length - 1; i >= 0; i--) {
                    SyncRtSurfaceTransactionApplier.SurfaceParams surfaceParams = params[i];
                    applyParams(t, surfaceParams, mTmpFloat9);
                }
                t.apply();
                t.close();
            }

            // Since we don't push applySurfaceParams to a Handler-queue we don't need
            // to push release in this case.
            @Override
            public void releaseSurfaceControlFromRt(SurfaceControl sc) {
                sc.release();
            }

            @Override
            public <T extends InsetsAnimationControlRunner & InternalInsetsAnimationController>
            void startAnimation(T runner, WindowInsetsAnimationControlListener listener, int types,
                    WindowInsetsAnimation animation,
                    Bounds bounds) {
            }

            @Override
            public void reportPerceptible(int types, boolean perceptible) {
                // No-op for now - only client windows report perceptibility for now, with policy
                // controllers assumed to always be perceptible.
            }
        }
    }
}
