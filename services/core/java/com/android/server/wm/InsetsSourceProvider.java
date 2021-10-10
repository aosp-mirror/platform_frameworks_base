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

import static android.view.InsetsState.ITYPE_CLIMATE_BAR;
import static android.view.InsetsState.ITYPE_EXTRA_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_IME;
import static com.android.server.wm.InsetsSourceProviderProto.CAPTURED_LEASH;
import static com.android.server.wm.InsetsSourceProviderProto.CLIENT_VISIBLE;
import static com.android.server.wm.InsetsSourceProviderProto.CONTROL;
import static com.android.server.wm.InsetsSourceProviderProto.CONTROLLABLE;
import static com.android.server.wm.InsetsSourceProviderProto.CONTROL_TARGET;
import static com.android.server.wm.InsetsSourceProviderProto.FAKE_CONTROL;
import static com.android.server.wm.InsetsSourceProviderProto.FAKE_CONTROL_TARGET;
import static com.android.server.wm.InsetsSourceProviderProto.FRAME;
import static com.android.server.wm.InsetsSourceProviderProto.IME_OVERRIDDEN_FRAME;
import static com.android.server.wm.InsetsSourceProviderProto.IS_LEASH_READY_FOR_DISPATCHING;
import static com.android.server.wm.InsetsSourceProviderProto.PENDING_CONTROL_TARGET;
import static com.android.server.wm.InsetsSourceProviderProto.SEAMLESS_ROTATING;
import static com.android.server.wm.InsetsSourceProviderProto.SERVER_VISIBLE;
import static com.android.server.wm.InsetsSourceProviderProto.SOURCE;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_INSETS_CONTROL;
import static com.android.server.wm.WindowManagerService.H.LAYOUT_AND_ASSIGN_WINDOW_LAYERS_IF_NEEDED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.proto.ProtoOutputStream;
import android.view.InsetsSource;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.function.TriConsumer;
import com.android.server.wm.SurfaceAnimator.AnimationType;
import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;

import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * Controller for a specific inset source on the server. It's called provider as it provides the
 * {@link InsetsSource} to the client that uses it in {@link android.view.InsetsSourceConsumer}.
 */
class InsetsSourceProvider {

    protected final DisplayContent mDisplayContent;
    protected final @NonNull InsetsSource mSource;
    protected WindowState mWin;

    private final Rect mTmpRect = new Rect();
    private final InsetsStateController mStateController;
    private final InsetsSourceControl mFakeControl;
    private @Nullable InsetsSourceControl mControl;
    private @Nullable InsetsControlTarget mControlTarget;
    private @Nullable InsetsControlTarget mPendingControlTarget;
    private @Nullable InsetsControlTarget mFakeControlTarget;

    private @Nullable ControlAdapter mAdapter;
    private TriConsumer<DisplayFrames, WindowState, Rect> mFrameProvider;
    private TriConsumer<DisplayFrames, WindowState, Rect> mImeFrameProvider;
    private final Rect mImeOverrideFrame = new Rect();
    private boolean mIsLeashReadyForDispatching;
    private final Rect mLastSourceFrame = new Rect();

    private final Consumer<Transaction> mSetLeashPositionConsumer = t -> {
        if (mControl != null) {
            final SurfaceControl leash = mControl.getLeash();
            if (leash != null) {
                final Point position = mControl.getSurfacePosition();
                t.setPosition(leash, position.x, position.y);
            }
        }
    };

    /** The visibility override from the current controlling window. */
    private boolean mClientVisible;

    /**
     * Whether the window is available and considered visible as in {@link WindowState#isVisible}.
     */
    private boolean mServerVisible;

    private boolean mSeamlessRotating;

    private final boolean mControllable;

    InsetsSourceProvider(InsetsSource source, InsetsStateController stateController,
            DisplayContent displayContent) {
        mClientVisible = InsetsState.getDefaultVisibility(source.getType());
        mSource = source;
        mDisplayContent = displayContent;
        mStateController = stateController;
        mFakeControl = new InsetsSourceControl(
                source.getType(), null /* leash */, new Point(), Insets.NONE);

        switch (source.getType()) {
            case ITYPE_STATUS_BAR:
            case ITYPE_NAVIGATION_BAR:
            case ITYPE_IME:
            case ITYPE_CLIMATE_BAR:
            case ITYPE_EXTRA_NAVIGATION_BAR:
                mControllable = true;
                break;
            default:
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
     * @param imeFrameProvider Based on display frame state and the window, calculates the resulting
     *                         frame that should be reported to IME.
     */
    void setWindow(@Nullable WindowState win,
            @Nullable TriConsumer<DisplayFrames, WindowState, Rect> frameProvider,
            @Nullable TriConsumer<DisplayFrames, WindowState, Rect> imeFrameProvider) {
        if (mWin != null) {
            if (mControllable) {
                mWin.setControllableInsetProvider(null);
            }
            // The window may be animating such that we can hand out the leash to the control
            // target. Revoke the leash by cancelling the animation to correct the state.
            // TODO: Ideally, we should wait for the animation to finish so previous window can
            // animate-out as new one animates-in.
            mWin.cancelAnimation();
            mWin.mProvidedInsetsSources.remove(mSource.getType());
        }
        ProtoLog.d(WM_DEBUG_IME, "InsetsSource setWin %s", win);
        mWin = win;
        mFrameProvider = frameProvider;
        mImeFrameProvider = imeFrameProvider;
        if (win == null) {
            setServerVisible(false);
            mSource.setFrame(new Rect());
            mSource.setVisibleFrame(null);
        } else {
            mWin.mProvidedInsetsSources.put(mSource.getType(), mSource);
            if (mControllable) {
                mWin.setControllableInsetProvider(this);
                if (mPendingControlTarget != null) {
                    updateControlForTarget(mPendingControlTarget, true /* force */);
                    mPendingControlTarget = null;
                }
            }
        }
    }

    /**
     * @return Whether there is a window which backs this source.
     */
    boolean hasWindow() {
        return mWin != null;
    }

    /**
     * The source frame can affect the layout of other windows, so this should be called once the
     * window gets laid out.
     */
    void updateSourceFrame() {
        if (mWin == null || mWin.mGivenInsetsPending) {
            // If the given insets are pending, they are not reliable for now. The source frame
            // should be updated after the new given insets are sent to window manager.
            return;
        }

        // Make sure we set the valid source frame only when server visible is true, because the
        // frame may not yet determined that server side doesn't think the window is ready to
        // visible. (i.e. No surface, pending insets that were given during layout, etc..)
        if (mServerVisible) {
            mTmpRect.set(mWin.getFrame());
            if (mFrameProvider != null) {
                mFrameProvider.accept(mWin.getDisplayContent().mDisplayFrames, mWin, mTmpRect);
            } else {
                mTmpRect.inset(mWin.mGivenContentInsets);
            }
        } else {
            mTmpRect.setEmpty();
        }
        mSource.setFrame(mTmpRect);

        if (mImeFrameProvider != null) {
            mImeOverrideFrame.set(mWin.getFrame());
            mImeFrameProvider.accept(mWin.getDisplayContent().mDisplayFrames, mWin,
                    mImeOverrideFrame);
        }

        if (mWin.mGivenVisibleInsets.left != 0 || mWin.mGivenVisibleInsets.top != 0
                || mWin.mGivenVisibleInsets.right != 0 || mWin.mGivenVisibleInsets.bottom != 0) {
            mTmpRect.set(mWin.getFrame());
            mTmpRect.inset(mWin.mGivenVisibleInsets);
            mSource.setVisibleFrame(mTmpRect);
        } else {
            mSource.setVisibleFrame(null);
        }
    }

    /** @return A new source computed by the specified window frame in the given display frames. */
    InsetsSource createSimulatedSource(DisplayFrames displayFrames, WindowFrames windowFrames) {
        // Don't copy visible frame because it might not be calculated in the provided display
        // frames and it is not significant for this usage.
        final InsetsSource source = new InsetsSource(mSource.getType());
        source.setVisible(mSource.isVisible());
        mTmpRect.set(windowFrames.mFrame);
        if (mFrameProvider != null) {
            mFrameProvider.accept(displayFrames, mWin, mTmpRect);
        }
        source.setFrame(mTmpRect);
        return source;
    }

    /**
     * Called when a layout pass has occurred.
     */
    void onPostLayout() {
        if (mWin == null) {
            return;
        }

        setServerVisible(mWin.wouldBeVisibleIfPolicyIgnored() && mWin.isVisibleByPolicy());
        updateSourceFrame();
        if (mControl != null) {
            boolean changed = false;
            final Point position = getWindowFrameSurfacePosition();
            if (mControl.setSurfacePosition(position.x, position.y) && mControlTarget != null) {
                changed = true;
                if (mWin.getWindowFrames().didFrameSizeChange() && mWin.mWinAnimator.getShown()
                        && mWin.okToDisplay()) {
                    mWin.applyWithNextDraw(mSetLeashPositionConsumer);
                } else {
                    mSetLeashPositionConsumer.accept(mWin.getPendingTransaction());
                }
            }
            if (mServerVisible && !mLastSourceFrame.equals(mSource.getFrame())) {
                final Insets insetsHint = mSource.calculateInsets(
                        mWin.getBounds(), true /* ignoreVisibility */);
                if (!insetsHint.equals(mControl.getInsetsHint())) {
                    changed = true;
                    mControl.setInsetsHint(insetsHint);
                }
                mLastSourceFrame.set(mSource.getFrame());
            }
            if (changed) {
                mStateController.notifyControlChanged(mControlTarget);
            }
        }
    }

    private Point getWindowFrameSurfacePosition() {
        final Rect frame = mWin.getFrame();
        final Point position = new Point();
        mWin.transformFrameToSurfacePosition(frame.left, frame.top, position);
        return position;
    }

    /**
     * @see InsetsStateController#onControlFakeTargetChanged(int, InsetsControlTarget)
     */
    void updateControlForFakeTarget(@Nullable InsetsControlTarget fakeTarget) {
        if (fakeTarget == mFakeControlTarget) {
            return;
        }
        mFakeControlTarget = fakeTarget;
    }

    void updateControlForTarget(@Nullable InsetsControlTarget target, boolean force) {
        if (mSeamlessRotating) {
            // We are un-rotating the window against the display rotation. We don't want the target
            // to control the window for now.
            return;
        }
        if (target != null && target.getWindow() != null) {
            // ime control target could be a different window.
            // Refer WindowState#getImeControlTarget().
            target = target.getWindow().getImeControlTarget();
        }

        if (mWin != null && mWin.getSurfaceControl() == null) {
            // if window doesn't have a surface, set it null and return.
            setWindow(null, null, null);
        }
        if (mWin == null) {
            mPendingControlTarget = target;
            return;
        }
        if (target == mControlTarget && !force) {
            return;
        }
        if (target == null) {
            // Cancelling the animation will invoke onAnimationCancelled, resetting all the fields.
            mWin.cancelAnimation();
            setClientVisible(InsetsState.getDefaultVisibility(mSource.getType()));
            return;
        }
        final Point surfacePosition = getWindowFrameSurfacePosition();
        mAdapter = new ControlAdapter(surfacePosition);
        if (getSource().getType() == ITYPE_IME) {
            setClientVisible(target.getRequestedVisibility(mSource.getType()));
        }
        final Transaction t = mDisplayContent.getPendingTransaction();
        mWin.startAnimation(t, mAdapter, !mClientVisible /* hidden */,
                ANIMATION_TYPE_INSETS_CONTROL);

        // The leash was just created. We cannot dispatch it until its surface transaction is
        // applied. Otherwise, the client's operation to the leash might be overwritten by us.
        mIsLeashReadyForDispatching = false;

        final SurfaceControl leash = mAdapter.mCapturedLeash;
        mControlTarget = target;
        updateVisibility();
        mControl = new InsetsSourceControl(mSource.getType(), leash, surfacePosition,
                mSource.calculateInsets(mWin.getBounds(), true /* ignoreVisibility */));
        ProtoLog.d(WM_DEBUG_IME,
                "InsetsSource Control %s for target %s", mControl, mControlTarget);
    }

    void startSeamlessRotation() {
      if (!mSeamlessRotating) {
          mSeamlessRotating = true;
          mWin.cancelAnimation();
      }
    }

    void finishSeamlessRotation() {
        mSeamlessRotating = false;
    }

    boolean updateClientVisibility(InsetsControlTarget caller) {
        final boolean requestedVisible = caller.getRequestedVisibility(mSource.getType());
        if (caller != mControlTarget || requestedVisible == mClientVisible) {
            return false;
        }
        setClientVisible(requestedVisible);
        return true;
    }

    void onSurfaceTransactionApplied() {
        mIsLeashReadyForDispatching = true;
    }

    void setClientVisible(boolean clientVisible) {
        if (mClientVisible == clientVisible) {
            return;
        }
        mClientVisible = clientVisible;
        mDisplayContent.mWmService.mH.obtainMessage(
                LAYOUT_AND_ASSIGN_WINDOW_LAYERS_IF_NEEDED, mDisplayContent).sendToTarget();
        updateVisibility();
    }

    @VisibleForTesting
    void setServerVisible(boolean serverVisible) {
        mServerVisible = serverVisible;
        updateVisibility();
    }

    protected void updateVisibility() {
        mSource.setVisible(mServerVisible && (isMirroredSource() || mClientVisible));
        ProtoLog.d(WM_DEBUG_IME,
                "InsetsSource updateVisibility serverVisible: %s clientVisible: %s",
                mServerVisible, mClientVisible);
    }

    private boolean isMirroredSource() {
        if (mWin == null) {
            return false;
        }
        final int[] provides = mWin.mAttrs.providesInsetsTypes;
        if (provides == null) {
            return false;
        }
        for (int i = 0; i < provides.length; i++) {
            if (provides[i] == ITYPE_IME) {
                return true;
            }
        }
        return false;
    }

    InsetsSourceControl getControl(InsetsControlTarget target) {
        if (target == mControlTarget) {
            if (!mIsLeashReadyForDispatching && mControl != null) {
                // The surface transaction of preparing leash is not applied yet. We don't send it
                // to the client in case that the client applies its transaction sooner than ours
                // that we could unexpectedly overwrite the surface state.
                return new InsetsSourceControl(mControl.getType(), null /* leash */,
                        mControl.getSurfacePosition(), mControl.getInsetsHint());
            }
            return mControl;
        }
        if (target == mFakeControlTarget) {
            return mFakeControl;
        }
        return null;
    }

    InsetsControlTarget getControlTarget() {
        return mControlTarget;
    }

    boolean isClientVisible() {
        return mClientVisible;
    }

    /**
     * @return Whether this provider uses a different frame to dispatch to the IME.
     */
    boolean overridesImeFrame() {
        return mImeFrameProvider != null;
    }

    /**
     * @return Rect to dispatch to the IME as frame. Only valid if {@link #overridesImeFrame()}
     *         returns {@code true}.
     */
    Rect getImeOverrideFrame() {
        return mImeOverrideFrame;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + getClass().getSimpleName());
        prefix = prefix + "  ";
        pw.print(prefix + "mSource="); mSource.dump("", pw);
        if (mControl != null) {
            pw.print(prefix + "mControl=");
            mControl.dump("", pw);
        }
        pw.print(prefix);
        pw.print("mIsLeashReadyForDispatching="); pw.print(mIsLeashReadyForDispatching);
        pw.print(" mImeOverrideFrame="); pw.print(mImeOverrideFrame.toShortString());
        pw.println();
        if (mWin != null) {
            pw.print(prefix + "mWin=");
            pw.println(mWin);
        }
        if (mAdapter != null) {
            pw.print(prefix + "mAdapter=");
            mAdapter.dump(pw, "");
        }
        if (mControlTarget != null) {
            pw.print(prefix + "mControlTarget=");
            pw.println(mControlTarget.getWindow());
        }
        if (mPendingControlTarget != null) {
            pw.print(prefix + "mPendingControlTarget=");
            pw.println(mPendingControlTarget.getWindow());
        }
        if (mFakeControlTarget != null) {
            pw.print(prefix + "mFakeControlTarget=");
            pw.println(mFakeControlTarget.getWindow());
        }
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId, @WindowTraceLogLevel int logLevel) {
        final long token = proto.start(fieldId);
        mSource.dumpDebug(proto, SOURCE);
        mTmpRect.dumpDebug(proto, FRAME);
        mFakeControl.dumpDebug(proto, FAKE_CONTROL);
        if (mControl != null) {
            mControl.dumpDebug(proto, CONTROL);
        }
        if (mControlTarget != null && mControlTarget.getWindow() != null) {
            mControlTarget.getWindow().dumpDebug(proto, CONTROL_TARGET, logLevel);
        }
        if (mPendingControlTarget != null && mPendingControlTarget.getWindow() != null) {
            mPendingControlTarget.getWindow().dumpDebug(proto, PENDING_CONTROL_TARGET, logLevel);
        }
        if (mFakeControlTarget != null && mFakeControlTarget.getWindow() != null) {
            mFakeControlTarget.getWindow().dumpDebug(proto, FAKE_CONTROL_TARGET, logLevel);
        }
        if (mAdapter != null && mAdapter.mCapturedLeash != null) {
            mAdapter.mCapturedLeash.dumpDebug(proto, CAPTURED_LEASH);
        }
        mImeOverrideFrame.dumpDebug(proto, IME_OVERRIDDEN_FRAME);
        proto.write(IS_LEASH_READY_FOR_DISPATCHING, mIsLeashReadyForDispatching);
        proto.write(CLIENT_VISIBLE, mClientVisible);
        proto.write(SERVER_VISIBLE, mServerVisible);
        proto.write(SEAMLESS_ROTATING, mSeamlessRotating);
        proto.write(CONTROLLABLE, mControllable);
        proto.end(token);
    }

    private class ControlAdapter implements AnimationAdapter {

        private final Point mSurfacePosition;
        private SurfaceControl mCapturedLeash;

        ControlAdapter(Point surfacePosition) {
            mSurfacePosition = surfacePosition;
        }

        @Override
        public boolean getShowWallpaper() {
            return false;
        }

        @Override
        public void startAnimation(SurfaceControl animationLeash, Transaction t,
                @AnimationType int type, OnAnimationFinishedCallback finishCallback) {
            // TODO(b/166736352): Check if we still need to control the IME visibility here.
            if (mSource.getType() == ITYPE_IME) {
                // TODO: use 0 alpha and remove t.hide() once b/138459974 is fixed.
                t.setAlpha(animationLeash, 1 /* alpha */);
                t.hide(animationLeash);
            }
            ProtoLog.i(WM_DEBUG_IME,
                    "ControlAdapter startAnimation mSource: %s controlTarget: %s", mSource,
                    mControlTarget);

            mCapturedLeash = animationLeash;
            t.setPosition(mCapturedLeash, mSurfacePosition.x, mSurfacePosition.y);
        }

        @Override
        public void onAnimationCancelled(SurfaceControl animationLeash) {
            if (mAdapter == this) {
                mStateController.notifyControlRevoked(mControlTarget, InsetsSourceProvider.this);
                mControl = null;
                mControlTarget = null;
                mAdapter = null;
                setClientVisible(InsetsState.getDefaultVisibility(mSource.getType()));
                ProtoLog.i(WM_DEBUG_IME,
                        "ControlAdapter onAnimationCancelled mSource: %s mControlTarget: %s",
                        mSource, mControlTarget);
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
            pw.print(prefix + "ControlAdapter mCapturedLeash=");
            pw.print(mCapturedLeash);
            pw.println();
        }

        @Override
        public void dumpDebug(ProtoOutputStream proto) {
        }
    }
}
