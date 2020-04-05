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
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.view.InsetsController.ANIMATION_TYPE_HIDE;
import static android.view.InsetsController.ANIMATION_TYPE_SHOW;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.SyncRtSurfaceTransactionApplier.applyParams;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_TOUCH;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION;

import android.annotation.Nullable;
import android.app.StatusBarManager;
import android.util.IntArray;
import android.util.SparseArray;
import android.view.InsetsAnimationControlCallbacks;
import android.view.InsetsAnimationControlImpl;
import android.view.InsetsAnimationControlRunner;
import android.view.InsetsController;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.InsetsState.InternalInsetsType;
import android.view.SurfaceControl;
import android.view.SyncRtSurfaceTransactionApplier;
import android.view.ViewRootImpl;
import android.view.WindowInsetsAnimation;
import android.view.WindowInsetsAnimation.Bounds;
import android.view.WindowInsetsAnimationControlListener;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.DisplayThread;

/**
 * Policy that implements who gets control over the windows generating insets.
 */
class InsetsPolicy {

    private final InsetsStateController mStateController;
    private final DisplayContent mDisplayContent;
    private final DisplayPolicy mPolicy;
    private final IntArray mShowingTransientTypes = new IntArray();

    /** For resetting visibilities of insets sources. */
    private final InsetsControlTarget mDummyControlTarget = new InsetsControlTarget() { };

    private WindowState mFocusedWin;
    private BarWindow mStatusBar = new BarWindow(StatusBarManager.WINDOW_STATUS_BAR);
    private BarWindow mNavBar = new BarWindow(StatusBarManager.WINDOW_NAVIGATION_BAR);
    private boolean mAnimatingShown;
    private final float[] mTmpFloat9 = new float[9];

    InsetsPolicy(InsetsStateController stateController, DisplayContent displayContent) {
        mStateController = stateController;
        mDisplayContent = displayContent;
        mPolicy = displayContent.getDisplayPolicy();
    }

    /** Updates the target which can control system bars. */
    void updateBarControlTarget(@Nullable WindowState focusedWin) {
        if (mFocusedWin != focusedWin){
            abortTransient();
        }
        mFocusedWin = focusedWin;
        mStateController.onBarControlTargetChanged(getStatusControlTarget(focusedWin),
                getFakeStatusControlTarget(focusedWin),
                getNavControlTarget(focusedWin),
                getFakeNavControlTarget(focusedWin));
        if (ViewRootImpl.sNewInsetsMode != ViewRootImpl.NEW_INSETS_MODE_FULL) {
            return;
        }
        mStatusBar.setVisible(focusedWin == null
                || focusedWin != getStatusControlTarget(focusedWin)
                || focusedWin.getRequestedInsetsState().getSource(ITYPE_STATUS_BAR).isVisible());
        mNavBar.setVisible(focusedWin == null
                || focusedWin != getNavControlTarget(focusedWin)
                || focusedWin.getRequestedInsetsState().getSource(ITYPE_NAVIGATION_BAR)
                        .isVisible());
        updateHideNavInputEventReceiver();
    }

    private void updateHideNavInputEventReceiver() {
        mPolicy.updateHideNavInputEventReceiver(!isHidden(ITYPE_NAVIGATION_BAR),
                mFocusedWin != null
                        && mFocusedWin.mAttrs.insetsFlags.behavior != BEHAVIOR_SHOW_BARS_BY_TOUCH);
    }

    boolean isHidden(@InternalInsetsType int type) {
        final InsetsSourceProvider provider =  mStateController.peekSourceProvider(type);
        return provider != null && provider.hasWindow() && !provider.getSource().isVisible();
    }

    void showTransient(IntArray types) {
        boolean changed = false;
        for (int i = types.size() - 1; i >= 0; i--) {
            final int type = types.get(i);
            if (mShowingTransientTypes.indexOf(type) != -1) {
                continue;
            }
            if (!isHidden(type)) {
                continue;
            }
            mShowingTransientTypes.add(type);
            changed = true;
        }
        if (changed) {
            mPolicy.getStatusBarManagerInternal().showTransient(mDisplayContent.getDisplayId(),
                    mShowingTransientTypes.toArray());
            updateBarControlTarget(mFocusedWin);
            InsetsState state = new InsetsState(mStateController.getRawInsetsState());
            startAnimation(true /* show */, () -> {
                synchronized (mDisplayContent.mWmService.mGlobalLock) {
                    mStateController.notifyInsetsChanged();
                }
            }, state);
            mStateController.onInsetsModified(mDummyControlTarget, state);
        }
    }

    void hideTransient() {
        if (mShowingTransientTypes.size() == 0) {
            return;
        }
        InsetsState state = new InsetsState(mStateController.getRawInsetsState());
        startAnimation(false /* show */, () -> {
            synchronized (mDisplayContent.mWmService.mGlobalLock) {
                mShowingTransientTypes.clear();
                mStateController.notifyInsetsChanged();
                updateBarControlTarget(mFocusedWin);
            }
        }, state);
        mStateController.onInsetsModified(mDummyControlTarget, state);
    }

    boolean isTransient(@InternalInsetsType int type) {
        return mShowingTransientTypes.indexOf(type) != -1;
    }

    /**
     * @see InsetsStateController#getInsetsForDispatch
     */
    InsetsState getInsetsForDispatch(WindowState target) {
        InsetsState originalState = mStateController.getInsetsForDispatch(target);
        InsetsState state = originalState;
        for (int i = mShowingTransientTypes.size() - 1; i >= 0; i--) {
            state = new InsetsState(state);
            state.setSourceVisible(mShowingTransientTypes.get(i), false);
        }
        if (mFocusedWin != null && getStatusControlTarget(mFocusedWin) == mDummyControlTarget) {
            if (state == originalState) {
                state = new InsetsState(state);
            }
            state.setSourceVisible(ITYPE_STATUS_BAR, mFocusedWin.getRequestedInsetsState());
        }
        if (mFocusedWin != null && getNavControlTarget(mFocusedWin) == mDummyControlTarget) {
            if (state == originalState) {
                state = new InsetsState(state);
            }
            state.setSourceVisible(ITYPE_NAVIGATION_BAR, mFocusedWin.getRequestedInsetsState());
        }
        return state;
    }

    void onInsetsModified(WindowState windowState, InsetsState state) {
        mStateController.onInsetsModified(windowState, state);
        checkAbortTransient(windowState, state);
        if (ViewRootImpl.sNewInsetsMode != ViewRootImpl.NEW_INSETS_MODE_FULL) {
            return;
        }
        if (windowState == getStatusControlTarget(mFocusedWin)) {
            mStatusBar.setVisible(state.getSource(ITYPE_STATUS_BAR).isVisible());
        }
        if (windowState == getNavControlTarget(mFocusedWin)) {
            mNavBar.setVisible(state.getSource(ITYPE_NAVIGATION_BAR).isVisible());
        }
        updateHideNavInputEventReceiver();
    }

    /**
     * Called when a window modified the insets state. If the window set a insets source to visible
     * while it is shown transiently, we need to abort the transient state.
     *
     * @param windowState who changed the insets state.
     * @param state the modified insets state.
     */
    private void checkAbortTransient(WindowState windowState, InsetsState state) {
        if (mShowingTransientTypes.size() != 0) {
            IntArray abortTypes = new IntArray();
            for (int i = mShowingTransientTypes.size() - 1; i >= 0; i--) {
                final int type = mShowingTransientTypes.get(i);
                if (mStateController.isFakeTarget(type, windowState)
                        && state.getSource(type).isVisible()) {
                    mShowingTransientTypes.remove(i);
                    abortTypes.add(type);
                }
            }
            if (abortTypes.size() > 0) {
                mPolicy.getStatusBarManagerInternal().abortTransient(mDisplayContent.getDisplayId(),
                        abortTypes.toArray());
                updateBarControlTarget(mFocusedWin);
            }
        }
    }

    private void abortTransient() {
        mPolicy.getStatusBarManagerInternal().abortTransient(mDisplayContent.getDisplayId(),
                mShowingTransientTypes.toArray());
        mShowingTransientTypes.clear();
        updateBarControlTarget(mFocusedWin);
    }

    private @Nullable InsetsControlTarget getFakeStatusControlTarget(
            @Nullable WindowState focused) {
        return getStatusControlTarget(focused) == mDummyControlTarget ? focused : null;
    }

    private @Nullable InsetsControlTarget getFakeNavControlTarget(@Nullable WindowState focused) {
        return getNavControlTarget(focused) == mDummyControlTarget ? focused : null;
    }

    private @Nullable InsetsControlTarget getStatusControlTarget(@Nullable WindowState focusedWin) {
        if (mShowingTransientTypes.indexOf(ITYPE_STATUS_BAR) != -1) {
            return mDummyControlTarget;
        }
        if (focusedWin == mPolicy.getNotificationShade()) {
            // Notification shade has control anyways, no reason to force anything.
            return focusedWin;
        }
        if (forceShowsSystemBarsForWindowingMode()) {
            // Status bar is forcibly shown for the windowing mode which is a steady state.
            // We don't want the client to control the status bar, and we will dispatch the real
            // visibility of status bar to the client.
            return null;
        }
        if (forceShowsStatusBarTransiently()) {
            // Status bar is forcibly shown transiently, and its new visibility won't be
            // dispatched to the client so that we can keep the layout stable. We will dispatch the
            // fake control to the client, so that it can re-show the bar during this scenario.
            return mDummyControlTarget;
        }
        return focusedWin;
    }

    private @Nullable InsetsControlTarget getNavControlTarget(@Nullable WindowState focusedWin) {
        if (mShowingTransientTypes.indexOf(ITYPE_NAVIGATION_BAR) != -1) {
            return mDummyControlTarget;
        }
        if (focusedWin == mPolicy.getNotificationShade()) {
            // Notification shade has control anyways, no reason to force anything.
            return focusedWin;
        }
        if (forceShowsSystemBarsForWindowingMode()) {
            // Navigation bar is forcibly shown for the windowing mode which is a steady state.
            // We don't want the client to control the navigation bar, and we will dispatch the real
            // visibility of navigation bar to the client.
            return null;
        }
        if (forceShowsNavigationBarTransiently()) {
            // Navigation bar is forcibly shown transiently, and its new visibility won't be
            // dispatched to the client so that we can keep the layout stable. We will dispatch the
            // fake control to the client, so that it can re-show the bar during this scenario.
            return mDummyControlTarget;
        }
        return focusedWin;
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

    private boolean forceShowsSystemBarsForWindowingMode() {
        final boolean isDockedStackVisible = mDisplayContent.getDefaultTaskDisplayArea()
                .isStackVisible(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        final boolean isFreeformStackVisible = mDisplayContent.getDefaultTaskDisplayArea()
                .isStackVisible(WINDOWING_MODE_FREEFORM);
        final boolean isResizing = mDisplayContent.getDockedDividerController().isResizing();

        // We need to force system bars when the docked stack is visible, when the freeform stack
        // is visible but also when we are resizing for the transitions when docked stack
        // visibility changes.
        return isDockedStackVisible || isFreeformStackVisible || isResizing;
    }

    @VisibleForTesting
    void startAnimation(boolean show, Runnable callback, InsetsState state) {
        int typesReady = 0;
        final SparseArray<InsetsSourceControl> controls = new SparseArray<>();
        final IntArray showingTransientTypes = mShowingTransientTypes;
        for (int i = showingTransientTypes.size() - 1; i >= 0; i--) {
            int type = showingTransientTypes.get(i);
            InsetsSourceProvider provider = mStateController.getSourceProvider(type);
            InsetsSourceControl control = provider.getControl(mDummyControlTarget);
            if (control == null || control.getLeash() == null) {
                continue;
            }
            typesReady |= InsetsState.toPublicType(type);
            controls.put(control.getType(), new InsetsSourceControl(control));
            state.setSourceVisible(type, show);
        }
        controlAnimationUnchecked(typesReady, controls, show, callback);
    }

    private void controlAnimationUnchecked(int typesReady,
            SparseArray<InsetsSourceControl> controls, boolean show, Runnable callback) {
        InsetsPolicyAnimationControlListener listener =
                new InsetsPolicyAnimationControlListener(show, callback, typesReady);
        listener.mControlCallbacks.controlAnimationUnchecked(typesReady, controls, show);
    }

    private class BarWindow {

        private final int mId;
        private  @StatusBarManager.WindowVisibleState int mState =
                StatusBarManager.WINDOW_STATE_SHOWING;

        BarWindow(int id) {
            mId = id;
        }

        private void setVisible(boolean visible) {
            final int state = visible ? WINDOW_STATE_SHOWING : WINDOW_STATE_HIDDEN;
            if (mState != state) {
                mState = state;
                mPolicy.getStatusBarManagerInternal().setWindowState(
                        mDisplayContent.getDisplayId(), mId, state);
            }
        }
    }

    private class InsetsPolicyAnimationControlListener extends
            InsetsController.InternalAnimationControlListener {
        Runnable mFinishCallback;
        InsetsPolicyAnimationControlCallbacks mControlCallbacks;

        InsetsPolicyAnimationControlListener(boolean show, Runnable finishCallback, int types) {
            super(show, false /* hasCallbacks */, types);
            mFinishCallback = finishCallback;
            mControlCallbacks = new InsetsPolicyAnimationControlCallbacks(this);
        }

        @Override
        protected void onAnimationFinish() {
            super.onAnimationFinish();
            DisplayThread.getHandler().post(mFinishCallback);
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

                mAnimationControl = new InsetsAnimationControlImpl(controls,
                        mFocusedWin.getDisplayContent().getBounds(), getState(),
                        mListener, typesReady, this, mListener.getDurationMs(),
                        InsetsController.SYSTEM_BARS_INTERPOLATOR,
                        show ? ANIMATION_TYPE_SHOW : ANIMATION_TYPE_HIDE);
                SurfaceAnimationThread.getHandler().post(
                        () -> mListener.onReady(mAnimationControl, typesReady));
            }

            /** Called on SurfaceAnimationThread without global WM lock held. */
            @Override
            public void scheduleApplyChangeInsets(InsetsAnimationControlRunner runner) {
                InsetsState state = getState();
                if (mAnimationControl.applyChangeInsets(state)) {
                    mAnimationControl.finish(mAnimatingShown);
                }
            }

            @Override
            public void notifyFinished(InsetsAnimationControlRunner runner, boolean shown) {
                // Nothing's needed here. Finish steps is handled in the listener
                // onAnimationFinished callback.
            }

            /**
             * This method will return a state with fullscreen frame override. No need to make copy
             * after getting state from this method.
             * @return The client insets state with full display frame override.
             */
            private InsetsState getState() {
                // To animate the transient animation correctly, we need to let the state hold
                // the full display frame.
                InsetsState overrideState = new InsetsState(mFocusedWin.getRequestedInsetsState(),
                        true);
                overrideState.setDisplayFrame(mFocusedWin.getDisplayContent().getBounds());
                return overrideState;
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
            public void startAnimation(InsetsAnimationControlImpl controller,
                    WindowInsetsAnimationControlListener listener, int types,
                    WindowInsetsAnimation animation,
                    Bounds bounds) {
            }
        }
    }
}
