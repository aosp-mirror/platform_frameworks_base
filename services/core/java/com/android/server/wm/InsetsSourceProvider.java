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

import static android.view.InsetsState.ITYPE_IME;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WINDOW_INSETS;
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
abstract class InsetsSourceProvider {

    protected final DisplayContent mDisplayContent;
    protected final @NonNull InsetsSource mSource;
    protected WindowContainer mWindowContainer;

    private final Rect mTmpRect = new Rect();
    private final InsetsStateController mStateController;
    private final InsetsSourceControl mFakeControl;
    private @Nullable InsetsSourceControl mControl;
    private @Nullable InsetsControlTarget mControlTarget;
    private @Nullable InsetsControlTarget mPendingControlTarget;
    private @Nullable InsetsControlTarget mFakeControlTarget;

    private @Nullable ControlAdapter mAdapter;
    private TriConsumer<DisplayFrames, WindowContainer, Rect> mFrameProvider;
    private TriConsumer<DisplayFrames, WindowContainer, Rect> mImeFrameProvider;
    private final Rect mImeOverrideFrame = new Rect();
    private boolean mIsLeashReadyForDispatching;
    private final Rect mSourceFrame = new Rect();
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
     * Whether the window container is available and considered visible as in
     * {@link WindowContainer#isVisible}.
     */
    private boolean mServerVisible;

    private boolean mSeamlessRotating;

    private final boolean mControllable;

    /**
     * Whether to forced the dimensions of the source window container to the inset frame and crop
     * out any overflow.
     * Used to crop the taskbar inset source when a task animation is occurring to hide the taskbar
     * rounded corners overlays.
     *
     * TODO: Remove when we enable shell transitions (b/202383002)
     */
    private boolean mCropToProvidingInsets = false;

    InsetsSourceProvider(InsetsSource source, InsetsStateController stateController,
            DisplayContent displayContent) {
        mClientVisible = InsetsState.getDefaultVisibility(source.getType());
        mSource = source;
        mDisplayContent = displayContent;
        mStateController = stateController;
        mFakeControl = new InsetsSourceControl(
                source.getType(), null /* leash */, new Point(), Insets.NONE);
        mControllable = InsetsPolicy.isInsetsTypeControllable(source.getType());
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
     * Updates the window container that currently backs this source.
     *
     * @param windowContainer The window container that links to this source.
     * @param frameProvider Based on display frame state and the window, calculates the resulting
     *                      frame that should be reported to clients.
     * @param imeFrameProvider Based on display frame state and the window, calculates the resulting
     *                         frame that should be reported to IME.
     */
    void setWindowContainer(@Nullable WindowContainer windowContainer,
            @Nullable TriConsumer<DisplayFrames, WindowContainer, Rect> frameProvider,
            @Nullable TriConsumer<DisplayFrames, WindowContainer, Rect> imeFrameProvider) {
        if (mWindowContainer != null) {
            if (mControllable) {
                mWindowContainer.setControllableInsetProvider(null);
            }
            // The window container may be animating such that we can hand out the leash to the
            // control target. Revoke the leash by cancelling the animation to correct the state.
            // TODO: Ideally, we should wait for the animation to finish so previous window can
            // animate-out as new one animates-in.
            mWindowContainer.cancelAnimation();
            mWindowContainer.getProvidedInsetsSources().remove(mSource.getType());
            mSeamlessRotating = false;
        }
        ProtoLog.d(WM_DEBUG_WINDOW_INSETS, "InsetsSource setWin %s for type %s",
                windowContainer, InsetsState.typeToString(mSource.getType()));
        mWindowContainer = windowContainer;
        mFrameProvider = frameProvider;
        mImeFrameProvider = imeFrameProvider;
        if (windowContainer == null) {
            setServerVisible(false);
            mSource.setVisibleFrame(null);
            mSourceFrame.setEmpty();
        } else {
            mWindowContainer.getProvidedInsetsSources().put(mSource.getType(), mSource);
            if (mControllable) {
                mWindowContainer.setControllableInsetProvider(this);
                if (mPendingControlTarget != null) {
                    updateControlForTarget(mPendingControlTarget, true /* force */);
                    mPendingControlTarget = null;
                }
            }
        }
    }

    /**
     * @return Whether there is a window container which backs this source.
     */
    boolean hasWindowContainer() {
        return mWindowContainer != null;
    }

    /**
     * The source frame can affect the layout of other windows, so this should be called once the
     * window container gets laid out.
     */
    void updateSourceFrame(Rect frame) {
        if (mWindowContainer == null) {
            return;
        }
        WindowState win = mWindowContainer.asWindowState();

        if (win == null) {
            // For all the non window WindowContainers.
            if (mServerVisible) {
                mTmpRect.set(mWindowContainer.getBounds());
                if (mFrameProvider != null) {
                    mFrameProvider.accept(mWindowContainer.getDisplayContent().mDisplayFrames,
                            mWindowContainer, mTmpRect);
                }
            } else {
                mTmpRect.setEmpty();
            }
            mSource.setFrame(mTmpRect);
            mSource.setVisibleFrame(null);
            return;
        }

        mSourceFrame.set(frame);
        if (mFrameProvider != null) {
            mFrameProvider.accept(mWindowContainer.getDisplayContent().mDisplayFrames,
                    mWindowContainer, mSourceFrame);
        } else {
            mSourceFrame.inset(win.mGivenContentInsets);
        }
        updateSourceFrameForServerVisibility();

        if (mImeFrameProvider != null) {
            mImeOverrideFrame.set(frame);
            mImeFrameProvider.accept(mWindowContainer.getDisplayContent().mDisplayFrames,
                    mWindowContainer, mImeOverrideFrame);
        }

        if (win.mGivenVisibleInsets.left != 0 || win.mGivenVisibleInsets.top != 0
                || win.mGivenVisibleInsets.right != 0
                || win.mGivenVisibleInsets.bottom != 0) {
            mTmpRect.set(frame);
            mTmpRect.inset(win.mGivenVisibleInsets);
            mSource.setVisibleFrame(mTmpRect);
        } else {
            mSource.setVisibleFrame(null);
        }
    }

    private void updateSourceFrameForServerVisibility() {
        // Make sure we set the valid source frame only when server visible is true, because the
        // frame may not yet determined that server side doesn't think the window is ready to
        // visible. (i.e. No surface, pending insets that were given during layout, etc..)
        if (mServerVisible) {
            mSource.setFrame(mSourceFrame);
        } else {
            mSource.setFrame(0, 0, 0, 0);
        }
    }

    /** @return A new source computed by the specified window frame in the given display frames. */
    InsetsSource createSimulatedSource(DisplayFrames displayFrames, Rect frame) {
        // Don't copy visible frame because it might not be calculated in the provided display
        // frames and it is not significant for this usage.
        final InsetsSource source = new InsetsSource(mSource.getType());
        source.setVisible(mSource.isVisible());
        mTmpRect.set(frame);
        if (mFrameProvider != null) {
            mFrameProvider.accept(displayFrames, mWindowContainer, mTmpRect);
        }
        source.setFrame(mTmpRect);
        return source;
    }

    /**
     * Called when a layout pass has occurred.
     */
    void onPostLayout() {
        if (mWindowContainer == null) {
            return;
        }
        WindowState windowState = mWindowContainer.asWindowState();
        boolean isServerVisible = windowState != null
                ? windowState.wouldBeVisibleIfPolicyIgnored() && windowState.isVisibleByPolicy()
                : mWindowContainer.isVisibleRequested();
        setServerVisible(isServerVisible);
        if (mControl != null) {
            boolean changed = false;
            final Point position = getWindowFrameSurfacePosition();
            if (mControl.setSurfacePosition(position.x, position.y) && mControlTarget != null) {
                changed = true;
                if (windowState != null && windowState.getWindowFrames().didFrameSizeChange()
                        && windowState.mWinAnimator.getShown() && mWindowContainer.okToDisplay()) {
                    windowState.applyWithNextDraw(mSetLeashPositionConsumer);
                } else {
                    mSetLeashPositionConsumer.accept(mWindowContainer.getSyncTransaction());
                }
            }
            if (mServerVisible && !mLastSourceFrame.equals(mSource.getFrame())) {
                final Insets insetsHint = mSource.calculateInsets(
                        mWindowContainer.getBounds(), true /* ignoreVisibility */);
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
        WindowState win = mWindowContainer.asWindowState();
        if (mControl != null) {
            final AsyncRotationController controller =
                    win.mDisplayContent.getAsyncRotationController();
            if (controller != null && controller.shouldFreezeInsetsPosition(win)) {
                // Use previous position because the fade-out animation runs in old rotation.
                return mControl.getSurfacePosition();
            }
        }
        final Rect frame = mWindowContainer.asWindowState() != null
                ? mWindowContainer.asWindowState().getFrame() : mWindowContainer.getBounds();
        final Point position = new Point();
        mWindowContainer.transformFrameToSurfacePosition(frame.left, frame.top, position);
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

    /**
     * Ensures that the inset source window container is cropped so that anything that doesn't fit
     * within the inset frame is cropped out until removeCropToProvidingInsetsBounds is called.
     *
     * The inset source surface will get cropped to the be of the size of the insets it's providing.
     *
     * For example, for the taskbar window which serves as the ITYPE_EXTRA_NAVIGATION_BAR inset
     * source, the window is larger than the insets because of the rounded corners overlay, but
     * during task animations we want to make sure that the overlay is cropped out of the window so
     * that they don't hide the window animations.
     *
     * @param t The transaction to use to apply immediate overflow cropping operations.
     *
     * NOTE: The relies on the inset source window to have a leash (usually this would be a leash
     * for the ANIMATION_TYPE_INSETS_CONTROL animation if the inset is controlled by the client)
     *
     * TODO: Remove when we migrate over to shell transitions (b/202383002)
     */
    void setCropToProvidingInsetsBounds(Transaction t) {
        mCropToProvidingInsets = true;

        if (mWindowContainer != null && mWindowContainer.mSurfaceAnimator.hasLeash()) {
            // apply to existing leash
            t.setWindowCrop(mWindowContainer.mSurfaceAnimator.mLeash,
                    getProvidingInsetsBoundsCropRect());
        }
    }

    /**
     * Removes any overflow cropping and future cropping to the inset source window's leash that may
     * have been set with a call to setCropToProvidingInsetsBounds().
     * @param t The transaction to use to apply immediate removal of overflow cropping.
     *
     * TODO: Remove when we migrate over to shell transitions (b/202383002)
     */
    void removeCropToProvidingInsetsBounds(Transaction t) {
        mCropToProvidingInsets = false;

        // apply to existing leash
        if (mWindowContainer != null && mWindowContainer.mSurfaceAnimator.hasLeash()) {
            t.setWindowCrop(mWindowContainer.mSurfaceAnimator.mLeash, null);
        }
    }

    private Rect getProvidingInsetsBoundsCropRect() {
        Rect sourceWindowFrame = mWindowContainer.asWindowState() != null
                ? mWindowContainer.asWindowState().getFrame()
                : mWindowContainer.getBounds();
        Rect insetFrame = getSource().getFrame();

        // The rectangle in buffer space we want to crop to
        return new Rect(
                insetFrame.left - sourceWindowFrame.left,
                insetFrame.top - sourceWindowFrame.top,
                insetFrame.right - sourceWindowFrame.left,
                insetFrame.bottom - sourceWindowFrame.top
        );
    }

    void updateControlForTarget(@Nullable InsetsControlTarget target, boolean force) {
        if (mSeamlessRotating) {
            // We are un-rotating the window against the display rotation. We don't want the target
            // to control the window for now.
            return;
        }

        if (mWindowContainer != null && mWindowContainer.getSurfaceControl() == null) {
            // if window doesn't have a surface, set it null and return.
            setWindowContainer(null, null, null);
        }
        if (mWindowContainer == null) {
            mPendingControlTarget = target;
            return;
        }
        if (target == mControlTarget && !force) {
            return;
        }
        if (target == null) {
            // Cancelling the animation will invoke onAnimationCancelled, resetting all the fields.
            mWindowContainer.cancelAnimation();
            setClientVisible(InsetsState.getDefaultVisibility(mSource.getType()));
            return;
        }
        final Point surfacePosition = getWindowFrameSurfacePosition();
        mAdapter = new ControlAdapter(surfacePosition);
        if (getSource().getType() == ITYPE_IME) {
            setClientVisible(target.getRequestedVisibility(mSource.getType()));
        }
        final Transaction t = mDisplayContent.getSyncTransaction();
        mWindowContainer.startAnimation(t, mAdapter, !mClientVisible /* hidden */,
                ANIMATION_TYPE_INSETS_CONTROL);

        // The leash was just created. We cannot dispatch it until its surface transaction is
        // applied. Otherwise, the client's operation to the leash might be overwritten by us.
        mIsLeashReadyForDispatching = false;

        final SurfaceControl leash = mAdapter.mCapturedLeash;
        mControlTarget = target;
        updateVisibility();
        mControl = new InsetsSourceControl(mSource.getType(), leash, surfacePosition,
                mSource.calculateInsets(mWindowContainer.getBounds(), true /* ignoreVisibility */));
        ProtoLog.d(WM_DEBUG_WINDOW_INSETS,
                "InsetsSource Control %s for target %s", mControl, mControlTarget);
    }

    void startSeamlessRotation() {
        if (!mSeamlessRotating) {
            mSeamlessRotating = true;
            mWindowContainer.cancelAnimation();
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
        if (!mDisplayContent.mLayoutAndAssignWindowLayersScheduled) {
            mDisplayContent.mLayoutAndAssignWindowLayersScheduled = true;
            mDisplayContent.mWmService.mH.obtainMessage(
                    LAYOUT_AND_ASSIGN_WINDOW_LAYERS_IF_NEEDED, mDisplayContent).sendToTarget();
        }
        updateVisibility();
    }

    @VisibleForTesting
    void setServerVisible(boolean serverVisible) {
        mServerVisible = serverVisible;
        updateSourceFrameForServerVisibility();
        updateVisibility();
    }

    protected void updateVisibility() {
        mSource.setVisible(mServerVisible && (isMirroredSource() || mClientVisible));
        ProtoLog.d(WM_DEBUG_WINDOW_INSETS,
                "InsetsSource updateVisibility for %s, serverVisible: %s clientVisible: %s",
                InsetsState.typeToString(mSource.getType()),
                mServerVisible, mClientVisible);
    }

    private boolean isMirroredSource() {
        if (mWindowContainer == null) {
            return false;
        }
        if (mWindowContainer.asWindowState() == null) {
            return false;
        }
        final int[] provides = ((WindowState) mWindowContainer).mAttrs.providesInsetsTypes;
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
        if (mWindowContainer != null) {
            pw.print(prefix + "mWindowContainer=");
            pw.println(mWindowContainer);
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
                @AnimationType int type, @NonNull OnAnimationFinishedCallback finishCallback) {
            // TODO(b/166736352): Check if we still need to control the IME visibility here.
            if (mSource.getType() == ITYPE_IME) {
                // TODO: use 0 alpha and remove t.hide() once b/138459974 is fixed.
                t.setAlpha(animationLeash, 1 /* alpha */);
                t.hide(animationLeash);
            }
            ProtoLog.i(WM_DEBUG_WINDOW_INSETS,
                    "ControlAdapter startAnimation mSource: %s controlTarget: %s", mSource,
                    mControlTarget);

            mCapturedLeash = animationLeash;
            t.setPosition(mCapturedLeash, mSurfacePosition.x, mSurfacePosition.y);

            if (mCropToProvidingInsets) {
                // Apply crop to hide overflow
                t.setWindowCrop(mCapturedLeash, getProvidingInsetsBoundsCropRect());
            }
        }

        @Override
        public void onAnimationCancelled(SurfaceControl animationLeash) {
            if (mAdapter == this) {
                mStateController.notifyControlRevoked(mControlTarget, InsetsSourceProvider.this);
                mControl = null;
                mControlTarget = null;
                mAdapter = null;
                setClientVisible(InsetsState.getDefaultVisibility(mSource.getType()));
                ProtoLog.i(WM_DEBUG_WINDOW_INSETS,
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
