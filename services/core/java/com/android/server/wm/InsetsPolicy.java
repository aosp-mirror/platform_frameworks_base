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
import static android.view.InsetsState.TYPE_NAVIGATION_BAR;
import static android.view.InsetsState.TYPE_TOP_BAR;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_STATUS_BAR_VISIBLE_TRANSPARENT;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION;

import android.annotation.Nullable;
import android.app.StatusBarManager;
import android.util.IntArray;
import android.view.InsetsState;
import android.view.InsetsState.InternalInsetType;
import android.view.ViewRootImpl;

/**
 * Policy that implements who gets control over the windows generating insets.
 */
class InsetsPolicy {

    private final InsetsStateController mStateController;
    private final DisplayContent mDisplayContent;
    private final DisplayPolicy mPolicy;
    private final TransientControlTarget mTransientControlTarget = new TransientControlTarget();
    private final IntArray mShowingTransientTypes = new IntArray();

    private WindowState mFocusedWin;
    private BarWindow mTopBar = new BarWindow(StatusBarManager.WINDOW_STATUS_BAR);
    private BarWindow mNavBar = new BarWindow(StatusBarManager.WINDOW_NAVIGATION_BAR);

    InsetsPolicy(InsetsStateController stateController, DisplayContent displayContent) {
        mStateController = stateController;
        mDisplayContent = displayContent;
        mPolicy = displayContent.getDisplayPolicy();
    }

    /** Updates the target which can control system bars. */
    void updateBarControlTarget(@Nullable WindowState focusedWin) {
        mFocusedWin = focusedWin;
        mStateController.onBarControlTargetChanged(getTopControlTarget(focusedWin),
                getFakeTopControlTarget(focusedWin),
                getNavControlTarget(focusedWin),
                getFakeNavControlTarget(focusedWin));
        mTopBar.setVisible(focusedWin == null
                || focusedWin != getTopControlTarget(focusedWin)
                || focusedWin.getClientInsetsState().getSource(TYPE_TOP_BAR).isVisible());
        mNavBar.setVisible(focusedWin == null
                || focusedWin != getNavControlTarget(focusedWin)
                || focusedWin.getClientInsetsState().getSource(TYPE_NAVIGATION_BAR).isVisible());
    }

    boolean isHidden(@InternalInsetType int type) {
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
            updateBarControlTarget(mFocusedWin);
            mPolicy.getStatusBarManagerInternal().showTransient(mDisplayContent.getDisplayId(),
                    mShowingTransientTypes.toArray());
            mStateController.notifyInsetsChanged();
            // TODO(b/118118435): Animation
        }
    }

    void hideTransient() {
        if (mShowingTransientTypes.size() == 0) {
            return;
        }

        // TODO(b/118118435): Animation
        mShowingTransientTypes.clear();
        updateBarControlTarget(mFocusedWin);
        mStateController.notifyInsetsChanged();
    }

    /**
     * @see InsetsStateController#getInsetsForDispatch
     */
    InsetsState getInsetsForDispatch(WindowState target) {
        InsetsState state = mStateController.getInsetsForDispatch(target);
        if (mShowingTransientTypes.size() == 0) {
            return state;
        }
        for (int i = mShowingTransientTypes.size() - 1; i >= 0; i--) {
            state.setSourceVisible(mShowingTransientTypes.get(i), false);
        }
        return state;
    }

    void onInsetsModified(WindowState windowState, InsetsState state) {
        mStateController.onInsetsModified(windowState, state);
        checkAbortTransient(windowState, state);
        if (ViewRootImpl.sNewInsetsMode != ViewRootImpl.NEW_INSETS_MODE_FULL) {
            return;
        }
        if (windowState == getTopControlTarget(mFocusedWin)) {
            mTopBar.setVisible(state.getSource(TYPE_TOP_BAR).isVisible());
        }
        if (windowState == getNavControlTarget(mFocusedWin)) {
            mNavBar.setVisible(state.getSource(TYPE_NAVIGATION_BAR).isVisible());
        }
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

    private @Nullable InsetsControlTarget getFakeTopControlTarget(@Nullable WindowState focused) {
        if (mShowingTransientTypes.indexOf(TYPE_TOP_BAR) != -1) {
            return focused;
        }
        return null;
    }

    private @Nullable InsetsControlTarget getFakeNavControlTarget(@Nullable WindowState focused) {
        if (mShowingTransientTypes.indexOf(TYPE_NAVIGATION_BAR) != -1) {
            return focused;
        }
        return null;
    }

    private @Nullable InsetsControlTarget getTopControlTarget(@Nullable WindowState focusedWin) {
        if (mShowingTransientTypes.indexOf(TYPE_TOP_BAR) != -1) {
            return mTransientControlTarget;
        }
        if (areSystemBarsForciblyVisible() || isStatusBarForciblyVisible()) {
            return null;
        }
        return focusedWin;
    }

    private @Nullable InsetsControlTarget getNavControlTarget(@Nullable WindowState focusedWin) {
        if (mShowingTransientTypes.indexOf(TYPE_NAVIGATION_BAR) != -1) {
            return mTransientControlTarget;
        }
        if (areSystemBarsForciblyVisible() || isNavBarForciblyVisible()) {
            return null;
        }
        return focusedWin;
    }

    private boolean isStatusBarForciblyVisible() {
        final WindowState statusBar = mPolicy.getStatusBar();
        if (statusBar == null) {
            return false;
        }
        final int privateFlags = statusBar.mAttrs.privateFlags;

        // TODO(b/118118435): Pretend to the app that it's still able to control it?
        if ((privateFlags & PRIVATE_FLAG_FORCE_STATUS_BAR_VISIBLE_TRANSPARENT) != 0) {
            return true;
        }
        if ((privateFlags & PRIVATE_FLAG_KEYGUARD) != 0) {
            return true;
        }
        return false;
    }

    private boolean isNavBarForciblyVisible() {
        final WindowState statusBar = mPolicy.getStatusBar();
        if (statusBar == null) {
            return false;
        }
        if ((statusBar.mAttrs.privateFlags & PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION) != 0) {
            return true;
        }
        return false;
    }

    private boolean areSystemBarsForciblyVisible() {
        final boolean isDockedStackVisible =
                mDisplayContent.isStackVisible(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        final boolean isFreeformStackVisible =
                mDisplayContent.isStackVisible(WINDOWING_MODE_FREEFORM);
        final boolean isResizing = mDisplayContent.getDockedDividerController().isResizing();

        // We need to force system bars when the docked stack is visible, when the freeform stack
        // is visible but also when we are resizing for the transitions when docked stack
        // visibility changes.
        return isDockedStackVisible || isFreeformStackVisible || isResizing;
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

    // TODO(b/118118435): Implement animations for it (with SurfaceAnimator)
    private class TransientControlTarget implements InsetsControlTarget {

        @Override
        public void notifyInsetsControlChanged() {
        }
    }
}
