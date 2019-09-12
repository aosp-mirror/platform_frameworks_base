/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.view.InsetsState.TYPE_IME;
import static android.view.InsetsState.TYPE_NAVIGATION_BAR;
import static android.view.InsetsState.TYPE_TOP_BAR;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_FULL;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_IME;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_NONE;
import static android.view.ViewRootImpl.sNewInsetsMode;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.proto.ProtoOutputStream;
import android.view.InsetsSource;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;

import com.android.internal.util.function.TriConsumer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;

import java.io.PrintWriter;

/**
 * Controller for a specific inset source on the server. It's called provider as it provides the
 * {@link InsetsSource} to the client that uses it in {@link InsetsSourceConsumer}.
 */
class InsetsSourceProvider {

    private final Rect mTmpRect = new Rect();
    private final @NonNull InsetsSource mSource;
    private final DisplayContent mDisplayContent;
    private final InsetsStateController mStateController;
    private @Nullable InsetsSourceControl mControl;
    private @Nullable InsetsControlTarget mControlTarget;
    private @Nullable ControlAdapter mAdapter;
    private WindowState mWin;
    private TriConsumer<DisplayFrames, WindowState, Rect> mFrameProvider;

    /** The visibility override from the current controlling window. */
    private boolean mClientVisible;

    /**
     * Whether the window is available and considered visible as in {@link WindowState#isVisible}.
     */
    private boolean mServerVisible;

    private final boolean mControllable;

    InsetsSourceProvider(InsetsSource source, InsetsStateController stateController,
            DisplayContent displayContent) {
        mClientVisible = InsetsState.getDefaultVisibility(source.getType());
        mSource = source;
        mDisplayContent = displayContent;
        mStateController = stateController;

        final int type = source.getType();
        if (type == TYPE_TOP_BAR || type == TYPE_NAVIGATION_BAR) {
            mControllable = sNewInsetsMode == NEW_INSETS_MODE_FULL;
        } else if (type == TYPE_IME) {
            mControllable = sNewInsetsMode >= NEW_INSETS_MODE_IME;
        } else {
            mControllable = false;
        }
    }

    InsetsSource getSource() {
        return mSource;
    }

    /**
     * @return Whether the current flag configuration allows to control this source.
     */
    boolean isControllable() {
        return mControllable;
    }

    /**
     * Updates the window that currently backs this source.
     *
     * @param win The window that links to this source.
     * @param frameProvider Based on display frame state and the window, calculates the resulting
     *                      frame that should be reported to clients.
     */
    void setWindow(@Nullable WindowState win,
            @Nullable TriConsumer<DisplayFrames, WindowState, Rect> frameProvider) {
        if (mWin != null) {
            mWin.setInsetProvider(null);
            // The window may be animating such that we can hand out the leash to the control
            // target. Revoke the leash by cancelling the animation to correct the state.
            // TODO: Ideally, we should wait for the animation to finish so previous window can
            // animate-out as new one animates-in.
            mWin.cancelAnimation();
        }
        mWin = win;
        mFrameProvider = frameProvider;
        if (win == null) {
            setServerVisible(false);
            mSource.setFrame(new Rect());
        } else {
            mWin.setInsetProvider(this);
            if (mControlTarget != null) {
                updateControlForTarget(mControlTarget, true /* force */);
            }
        }
    }

    /**
     * Called when a layout pass has occurred.
     */
    void onPostLayout() {
        if (mWin == null) {
            return;
        }

        mTmpRect.set(mWin.getFrameLw());
        if (mFrameProvider != null) {
            mFrameProvider.accept(mWin.getDisplayContent().mDisplayFrames, mWin, mTmpRect);
        } else {
            mTmpRect.inset(mWin.mGivenContentInsets);
        }
        mSource.setFrame(mTmpRect);
        if (mControl != null) {
            final Rect frame = mWin.getWindowFrames().mFrame;
            if (mControl.setSurfacePosition(frame.left, frame.top)) {
                mStateController.notifyControlChanged(mControlTarget);
            }
        }
        setServerVisible(mWin.wouldBeVisibleIfPolicyIgnored() && mWin.isVisibleByPolicy()
                && !mWin.mGivenInsetsPending);
    }

    void updateControlForTarget(@Nullable InsetsControlTarget target, boolean force) {
        if (mWin == null) {
            mControlTarget = target;
            return;
        }
        if (target == mControlTarget && !force) {
            return;
        }
        if (target == null) {
            // Cancelling the animation will invoke onAnimationCancelled, resetting all the fields.
            mWin.cancelAnimation();
            return;
        }
        mAdapter = new ControlAdapter();
        setClientVisible(InsetsState.getDefaultVisibility(mSource.getType()));
        mWin.startAnimation(mDisplayContent.getPendingTransaction(), mAdapter,
                !mClientVisible /* hidden */);
        mControlTarget = target;
        mControl = new InsetsSourceControl(mSource.getType(), mAdapter.mCapturedLeash,
                new Point(mWin.getWindowFrames().mFrame.left, mWin.getWindowFrames().mFrame.top));
    }

    boolean onInsetsModified(WindowState caller, InsetsSource modifiedSource) {
        if (mControlTarget != caller || modifiedSource.isVisible() == mClientVisible) {
            return false;
        }
        setClientVisible(modifiedSource.isVisible());
        return true;
    }

    private void setClientVisible(boolean clientVisible) {
        if (mClientVisible == clientVisible) {
            return;
        }
        mClientVisible = clientVisible;
        mDisplayContent.mWmService.mH.sendMessage(PooledLambda.obtainMessage(
                DisplayContent::layoutAndAssignWindowLayersIfNeeded, mDisplayContent));
        updateVisibility();
    }

    private void setServerVisible(boolean serverVisible) {
        mServerVisible = serverVisible;
        updateVisibility();
    }

    private void updateVisibility() {
        mSource.setVisible(mServerVisible && mClientVisible);
    }

    InsetsSourceControl getControl() {
        return mControl;
    }

    boolean isClientVisible() {
        return sNewInsetsMode == NEW_INSETS_MODE_NONE || mClientVisible;
    }

    private class ControlAdapter implements AnimationAdapter {

        private SurfaceControl mCapturedLeash;

        @Override
        public boolean getShowWallpaper() {
            return false;
        }

        @Override
        public void startAnimation(SurfaceControl animationLeash, Transaction t,
                OnAnimationFinishedCallback finishCallback) {
            mCapturedLeash = animationLeash;
            final Rect frame = mWin.getWindowFrames().mFrame;
            t.setPosition(mCapturedLeash, frame.left, frame.top);
        }

        @Override
        public void onAnimationCancelled(SurfaceControl animationLeash) {
            if (mAdapter == this) {
                mStateController.notifyControlRevoked(mControlTarget, InsetsSourceProvider.this);
                setClientVisible(InsetsState.getDefaultVisibility(mSource.getType()));
                mControl = null;
                mControlTarget = null;
                mAdapter = null;
            }
        }

        @Override
        public long getDurationHint() {
            return 0;
        }

        @Override
        public long getStatusBarTransitionsStartTime() {
            return 0;
        }

        @Override
        public void dump(PrintWriter pw, String prefix) {
        }

        @Override
        public void writeToProto(ProtoOutputStream proto) {
        }
    };
}
