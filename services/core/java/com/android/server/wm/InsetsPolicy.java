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
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.view.InsetsController.ANIMATION_TYPE_HIDE;
import static android.view.InsetsController.ANIMATION_TYPE_SHOW;
import static android.view.InsetsController.LAYOUT_INSETS_DURING_ANIMATION_HIDDEN;
import static android.view.InsetsController.LAYOUT_INSETS_DURING_ANIMATION_SHOWN;
import static android.view.InsetsState.ITYPE_CAPTION_BAR;
import static android.view.InsetsState.ITYPE_CLIMATE_BAR;
import static android.view.InsetsState.ITYPE_EXTRA_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_INVALID;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.SyncRtSurfaceTransactionApplier.applyParams;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.app.StatusBarManager;
import android.app.WindowConfiguration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.util.ArrayMap;
import android.util.IntArray;
import android.util.SparseArray;
import android.view.InsetsAnimationControlCallbacks;
import android.view.InsetsAnimationControlImpl;
import android.view.InsetsAnimationControlRunner;
import android.view.InsetsController;
import android.view.InsetsSource;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.InsetsState.InternalInsetsType;
import android.view.InternalInsetsAnimationController;
import android.view.SurfaceControl;
import android.view.SyncRtSurfaceTransactionApplier;
import android.view.WindowInsets.Type;
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
                final @InternalInsetsType int type = control.getType();
                if (mShowingTransientTypes.indexOf(type) != -1) {
                    // The visibilities of transient bars will be handled with animations.
                    continue;
                }
                final SurfaceControl leash = control.getLeash();
                if (leash != null) {
                    hasLeash = true;

                    // We use alpha to control the visibility here which aligns the logic at
                    // SurfaceAnimator.createAnimationLeash
                    mDisplayContent.getPendingTransaction().setAlpha(
                            leash, InsetsState.getDefaultVisibility(type) ? 1f : 0f);
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
    private final float[] mTmpFloat9 = new float[9];

    InsetsPolicy(InsetsStateController stateController, DisplayContent displayContent) {
        mStateController = stateController;
        mDisplayContent = displayContent;
        mPolicy = displayContent.getDisplayPolicy();
        mRemoteInsetsControllerControlsSystemBars = mPolicy.getContext().getResources().getBoolean(
                R.bool.config_remoteInsetsControllerControlsSystemBars);
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
        mStatusBar.updateVisibility(statusControlTarget, ITYPE_STATUS_BAR);
        mNavBar.updateVisibility(navControlTarget, ITYPE_NAVIGATION_BAR);
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
        return adjustInsetsForRoundedCorners(target, state, state == originalState);
    }

    InsetsState adjustInsetsForWindow(WindowState target, InsetsState originalState) {
        return adjustInsetsForWindow(target, originalState, false);
    }

    /**
     * @see WindowState#getInsetsState()
     */
    InsetsState getInsetsForWindowMetrics(@NonNull WindowManager.LayoutParams attrs) {
        final @InternalInsetsType int type = getInsetsTypeForLayoutParams(attrs);
        final WindowToken token = mDisplayContent.getWindowToken(attrs.token);
        if (token != null) {
            final InsetsState rotatedState = token.getFixedRotationTransformInsetsState();
            if (rotatedState != null) {
                return rotatedState;
            }
        }
        final boolean alwaysOnTop = token != null && token.isAlwaysOnTop();
        // Always use windowing mode fullscreen when get insets for window metrics to make sure it
        // contains all insets types.
        final InsetsState originalState = mDisplayContent.getInsetsPolicy()
                .enforceInsetsPolicyForTarget(type, WINDOWING_MODE_FULLSCREEN, alwaysOnTop,
                        mStateController.getRawInsetsState());
        return adjustVisibilityForTransientTypes(originalState);
    }

    /**
     * @param type the internal type of the insets.
     * @return {@code true} if the given type is controllable, {@code false} otherwise.
     */
    static boolean isInsetsTypeControllable(@InternalInsetsType int type) {
        switch (type) {
            case ITYPE_STATUS_BAR:
            case ITYPE_NAVIGATION_BAR:
            case ITYPE_IME:
            case ITYPE_CLIMATE_BAR:
            case ITYPE_EXTRA_NAVIGATION_BAR:
                return true;
            default:
                return false;
        }
    }

    private static @InternalInsetsType int getInsetsTypeForLayoutParams(
            WindowManager.LayoutParams attrs) {
        @WindowManager.LayoutParams.WindowType int type = attrs.type;
        switch (type) {
            case TYPE_STATUS_BAR:
                return ITYPE_STATUS_BAR;
            case TYPE_NAVIGATION_BAR:
                return ITYPE_NAVIGATION_BAR;
            case TYPE_INPUT_METHOD:
                return ITYPE_IME;
        }

        // If not one of the types above, check whether an internal inset mapping is specified.
        if (attrs.providesInsetsTypes != null) {
            for (@InternalInsetsType int insetsType : attrs.providesInsetsTypes) {
                switch (insetsType) {
                    case ITYPE_STATUS_BAR:
                    case ITYPE_NAVIGATION_BAR:
                    case ITYPE_CLIMATE_BAR:
                    case ITYPE_EXTRA_NAVIGATION_BAR:
                        return insetsType;
                }
            }
        }

        return ITYPE_INVALID;
    }


    /**
     * Modifies the given {@code state} according to the {@code type} (Inset type) provided by
     * the target.
     * When performing layout of the target or dispatching insets to the target, we need to exclude
     * sources which should not be visible to the target. e.g., the source which represents the
     * target window itself, and the IME source when the target is above IME. We also need to
     * exclude certain types of insets source for client within specific windowing modes.
     *
     * @param type the inset type provided by the target
     * @param windowingMode the windowing mode of the target
     * @param isAlwaysOnTop is the target always on top
     * @param state the input inset state containing all the sources
     * @return The state stripped of the necessary information.
     */
    InsetsState enforceInsetsPolicyForTarget(@InternalInsetsType int type,
            @WindowConfiguration.WindowingMode int windowingMode, boolean isAlwaysOnTop,
            InsetsState state) {
        boolean stateCopied = false;

        if (type != ITYPE_INVALID) {
            state = new InsetsState(state);
            stateCopied = true;
            state.removeSource(type);

            // Navigation bar doesn't get influenced by anything else
            if (type == ITYPE_NAVIGATION_BAR || type == ITYPE_EXTRA_NAVIGATION_BAR) {
                state.removeSource(ITYPE_IME);
                state.removeSource(ITYPE_STATUS_BAR);
                state.removeSource(ITYPE_CLIMATE_BAR);
                state.removeSource(ITYPE_CAPTION_BAR);
                state.removeSource(ITYPE_NAVIGATION_BAR);
                state.removeSource(ITYPE_EXTRA_NAVIGATION_BAR);
            }

            // Status bar doesn't get influenced by caption bar
            if (type == ITYPE_STATUS_BAR || type == ITYPE_CLIMATE_BAR) {
                state.removeSource(ITYPE_CAPTION_BAR);
            }

            // IME needs different frames for certain cases (e.g. navigation bar in gesture nav).
            if (type == ITYPE_IME) {
                ArrayMap<Integer, WindowContainerInsetsSourceProvider> providers = mStateController
                        .getSourceProviders();
                for (int i = providers.size() - 1; i >= 0; i--) {
                    WindowContainerInsetsSourceProvider otherProvider = providers.valueAt(i);
                    if (otherProvider.overridesImeFrame()) {
                        InsetsSource override =
                                new InsetsSource(
                                        state.getSource(otherProvider.getSource().getType()));
                        override.setFrame(otherProvider.getImeOverrideFrame());
                        state.addSource(override);
                    }
                }
            }
        }

        if (WindowConfiguration.isFloating(windowingMode)
                || (windowingMode == WINDOWING_MODE_MULTI_WINDOW && isAlwaysOnTop)) {
            if (!stateCopied) {
                state = new InsetsState(state);
                stateCopied = true;
            }
            state.removeSource(ITYPE_STATUS_BAR);
            state.removeSource(ITYPE_NAVIGATION_BAR);
            state.removeSource(ITYPE_EXTRA_NAVIGATION_BAR);
            if (windowingMode == WINDOWING_MODE_PINNED) {
                state.removeSource(ITYPE_IME);
            }
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
            // Navigation bar insets is always visible to IME.
            final InsetsSource originalNavSource = originalState.peekSource(ITYPE_NAVIGATION_BAR);
            if (originalNavSource != null && !originalNavSource.isVisible()) {
                final InsetsState state = copyState ? new InsetsState(originalState)
                        : originalState;
                final InsetsSource navSource = new InsetsSource(originalNavSource);
                navSource.setVisible(true);
                state.addSource(navSource);
                return state;
            }
        } else if (w.mActivityRecord != null && w.mActivityRecord.mImeInsetsFrozenUntilStartInput) {
            // During switching tasks with gestural navigation, if the IME is attached to
            // one app window on that time, even the next app window is behind the IME window,
            // conceptually the window should not receive the IME insets if the next window is
            // not eligible IME requester and ready to show IME on top of it.
            final boolean shouldImeAttachedToApp = mDisplayContent.shouldImeAttachedToApp();
            final InsetsSource originalImeSource = originalState.peekSource(ITYPE_IME);

            if (shouldImeAttachedToApp && originalImeSource != null) {
                final boolean imeVisibility =
                        w.mActivityRecord.mLastImeShown || w.getRequestedVisibility(ITYPE_IME);
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

    private InsetsState adjustInsetsForRoundedCorners(WindowState w, InsetsState originalState,
            boolean copyState) {
        final WindowState roundedCornerWindow = mPolicy.getRoundedCornerWindow();
        final Task task = w.getTask();
        if (task != null && !task.getWindowConfiguration().tasksAreFloating()
                && (roundedCornerWindow != null || task.inSplitScreen())) {
            // Instead of using display frame to calculating rounded corner, for the fake rounded
            // corners drawn by divider bar or task bar, we need to re-calculate rounded corners
            // based on task bounds and if the task bounds is intersected with task bar, we should
            // exclude the intersected part.
            final Rect roundedCornerFrame = new Rect(task.getBounds());
            if (roundedCornerWindow != null
                    && roundedCornerWindow.getControllableInsetProvider() != null) {
                final InsetsSource source =
                        roundedCornerWindow.getControllableInsetProvider().getSource();
                final Insets insets = source.calculateInsets(roundedCornerFrame, false);
                roundedCornerFrame.inset(insets);
            }
            final InsetsState state = copyState ? new InsetsState(originalState) : originalState;
            state.setRoundedCornerFrame(roundedCornerFrame);
            return state;
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
            final boolean imeRequestedVisible = caller.getRequestedVisibility(ITYPE_IME);
            for (int i = mShowingTransientTypes.size() - 1; i >= 0; i--) {
                final @InternalInsetsType int type = mShowingTransientTypes.get(i);
                if ((mStateController.isFakeTarget(type, caller)
                                && caller.getRequestedVisibility(type))
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
            mDisplayContent.mRemoteInsetsControlTarget.topFocusedWindowChanged(
                    focusedWin.mAttrs.packageName);
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
        if (imeWin != null && imeWin.isVisible()) {
            // Force showing navigation bar while IME is visible.
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
            mDisplayContent.mRemoteInsetsControlTarget.topFocusedWindowChanged(
                    focusedWin.mAttrs.packageName);
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

    private boolean isShowingTransientTypes(@Type.InsetsType int types) {
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
            final @InternalInsetsType int type = showingTransientTypes.get(i);
            WindowContainerInsetsSourceProvider provider = mStateController.getSourceProvider(type);
            InsetsSourceControl control = provider.getControl(mDummyControlTarget);
            if (control == null || control.getLeash() == null) {
                continue;
            }
            typesReady |= InsetsState.toPublicType(type);
            controls.put(control.getType(), new InsetsSourceControl(control));
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
                @InternalInsetsType int type) {
            setVisible(controlTarget == null || controlTarget.getRequestedVisibility(type));
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
                    false /* disable */, 0 /* floatingImeBottomInsets */);
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
                        null /* translator */);
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
