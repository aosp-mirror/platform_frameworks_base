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

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WINDOW_INSETS;
import static com.android.server.wm.InsetsSourceProviderProto.CAPTURED_LEASH;
import static com.android.server.wm.InsetsSourceProviderProto.CLIENT_VISIBLE;
import static com.android.server.wm.InsetsSourceProviderProto.CONTROL;
import static com.android.server.wm.InsetsSourceProviderProto.CONTROLLABLE;
import static com.android.server.wm.InsetsSourceProviderProto.CONTROL_TARGET;
import static com.android.server.wm.InsetsSourceProviderProto.FAKE_CONTROL;
import static com.android.server.wm.InsetsSourceProviderProto.FAKE_CONTROL_TARGET;
import static com.android.server.wm.InsetsSourceProviderProto.FRAME;
import static com.android.server.wm.InsetsSourceProviderProto.IS_LEASH_READY_FOR_DISPATCHING;
import static com.android.server.wm.InsetsSourceProviderProto.PENDING_CONTROL_TARGET;
import static com.android.server.wm.InsetsSourceProviderProto.SEAMLESS_ROTATING;
import static com.android.server.wm.InsetsSourceProviderProto.SERVER_VISIBLE;
import static com.android.server.wm.InsetsSourceProviderProto.SOURCE;
import static com.android.server.wm.InsetsSourceProviderProto.SOURCE_WINDOW_STATE;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_INSETS_CONTROL;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.InsetsSource;
import android.view.InsetsSource.Flags;
import android.view.InsetsSourceControl;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.WindowInsets;
import android.view.inputmethod.ImeTracker;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLog;
import com.android.internal.util.function.TriFunction;
import com.android.server.wm.SurfaceAnimator.AnimationType;
import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;

import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * Controller for a specific inset source on the server. It's called provider as it provides the
 * {@link InsetsSource} to the client that uses it in {@link android.view.InsetsSourceConsumer}.
 */
class InsetsSourceProvider {

    private static final Rect EMPTY_RECT = new Rect();

    protected final @NonNull InsetsSource mSource;
    protected final @NonNull DisplayContent mDisplayContent;
    protected final @NonNull InsetsStateController mStateController;
    protected @Nullable WindowContainer mWindowContainer;
    protected @Nullable InsetsSourceControl mControl;
    protected @Nullable InsetsControlTarget mControlTarget;
    protected boolean mIsLeashReadyForDispatching;

    private final Rect mTmpRect = new Rect();
    private final InsetsSourceControl mFakeControl;
    private final Point mPosition = new Point();
    private final Consumer<Transaction> mSetControlPositionConsumer;
    private @Nullable InsetsControlTarget mPendingControlTarget;
    private @Nullable InsetsControlTarget mFakeControlTarget;

    private @Nullable ControlAdapter mAdapter;
    private TriFunction<DisplayFrames, WindowContainer, Rect, Integer> mFrameProvider;
    private SparseArray<TriFunction<DisplayFrames, WindowContainer, Rect, Integer>>
            mOverrideFrameProviders;
    private final SparseArray<Rect> mOverrideFrames = new SparseArray<Rect>();
    private final Rect mSourceFrame = new Rect();
    private final Rect mLastSourceFrame = new Rect();
    private @NonNull Insets mInsetsHint = Insets.NONE;
    private boolean mInsetsHintStale = true;
    private @Flags int mFlagsFromFrameProvider;
    private @Flags int mFlagsFromServer;
    private boolean mHasPendingPosition;

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

    InsetsSourceProvider(@NonNull InsetsSource source,
            @NonNull InsetsStateController stateController,
            @NonNull DisplayContent displayContent) {
        mClientVisible = (WindowInsets.Type.defaultVisible() & source.getType()) != 0;
        mSource = source;
        mDisplayContent = displayContent;
        mStateController = stateController;
        mFakeControl = new InsetsSourceControl(
                source.getId(), source.getType(), null /* leash */, false /* initialVisible */,
                new Point(), Insets.NONE);
        mControllable = (InsetsPolicy.CONTROLLABLE_TYPES & source.getType()) != 0;
        mSetControlPositionConsumer = t -> {
            if (mControl == null || mControlTarget == null) {
                return;
            }
            boolean changed = mControl.setSurfacePosition(mPosition.x, mPosition.y);
            final SurfaceControl leash = mControl.getLeash();
            if (changed && leash != null) {
                t.setPosition(leash, mPosition.x, mPosition.y);
            }
            if (mHasPendingPosition) {
                mHasPendingPosition = false;
                if (mPendingControlTarget != mControlTarget) {
                    mStateController.notifyControlTargetChanged(mPendingControlTarget, this);
                }
            }
            changed |= updateInsetsHint();
            if (changed) {
                mStateController.notifyControlChanged(mControlTarget, this);
            }
        };
    }

    private boolean updateInsetsHint() {
        final Insets insetsHint = getInsetsHint();
        if (!mControl.getInsetsHint().equals(insetsHint)) {
            mControl.setInsetsHint(insetsHint);
            return true;
        }
        return false;
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
     *                      This will only be used when the window container providing the insets is
     *                      not a WindowState.
     * @param overrideFrameProviders Based on display frame state and the window, calculates the
     *                               resulting frame that should be reported to given window type.
     */
    void setWindowContainer(@Nullable WindowContainer windowContainer,
            @Nullable TriFunction<DisplayFrames, WindowContainer, Rect, Integer> frameProvider,
            @Nullable SparseArray<TriFunction<DisplayFrames, WindowContainer, Rect, Integer>>
                    overrideFrameProviders) {
        if (mWindowContainer != null) {
            if (mControllable) {
                mWindowContainer.setControllableInsetProvider(null);
            }
            // The window container may be animating such that we can hand out the leash to the
            // control target. Revoke the leash by cancelling the animation to correct the state.
            // TODO: Ideally, we should wait for the animation to finish so previous window can
            // animate-out as new one animates-in.
            mWindowContainer.cancelAnimation();
            mWindowContainer.getInsetsSourceProviders().remove(mSource.getId());
            mSeamlessRotating = false;
            mHasPendingPosition = false;
        }
        ProtoLog.d(WM_DEBUG_WINDOW_INSETS, "InsetsSource setWin %s for type %s",
                windowContainer, WindowInsets.Type.toString(mSource.getType()));
        mWindowContainer = windowContainer;
        // TODO: remove the frame provider for non-WindowState container.
        mFrameProvider = frameProvider;
        mOverrideFrames.clear();
        mOverrideFrameProviders = overrideFrameProviders;
        if (windowContainer == null) {
            setServerVisible(false);
            mSource.setVisibleFrame(null);
            mSource.setFlags(0, 0xffffffff);
            mSourceFrame.setEmpty();
        } else {
            mWindowContainer.getInsetsSourceProviders().put(mSource.getId(), this);
            if (mControllable) {
                mWindowContainer.setControllableInsetProvider(this);
                if (mPendingControlTarget != mControlTarget) {
                    mStateController.notifyControlTargetChanged(mPendingControlTarget, this);
                }
            }
        }
    }

    boolean setFlags(@Flags int flags, @Flags int mask) {
        mFlagsFromServer = (mFlagsFromServer & ~mask) | (flags & mask);
        final @Flags int mergedFlags = mFlagsFromFrameProvider | mFlagsFromServer;
        if (mSource.getFlags() != mergedFlags) {
            mSource.setFlags(mergedFlags);
            return true;
        }
        return false;
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
                    mFrameProvider.apply(mWindowContainer.getDisplayContent().mDisplayFrames,
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
            mFlagsFromFrameProvider = mFrameProvider.apply(
                    mWindowContainer.getDisplayContent().mDisplayFrames,
                    mWindowContainer,
                    mSourceFrame);
            mSource.setFlags(mFlagsFromFrameProvider | mFlagsFromServer);
        }
        updateSourceFrameForServerVisibility();
        if (!mLastSourceFrame.equals(mSourceFrame)) {
            mLastSourceFrame.set(mSourceFrame);
            mInsetsHintStale = true;
        }

        if (mOverrideFrameProviders != null) {
            // Not necessary to clear the mOverrideFrames here. It will be cleared every time the
            // override frame provider updates.
            for (int i = mOverrideFrameProviders.size() - 1; i >= 0; i--) {
                final int windowType = mOverrideFrameProviders.keyAt(i);
                final Rect overrideFrame;
                if (mOverrideFrames.contains(windowType)) {
                    overrideFrame = mOverrideFrames.get(windowType);
                    overrideFrame.set(frame);
                } else {
                    overrideFrame = new Rect(frame);
                }
                final TriFunction<DisplayFrames, WindowContainer, Rect, Integer> provider =
                        mOverrideFrameProviders.get(windowType);
                if (provider != null) {
                    mOverrideFrameProviders.get(windowType).apply(
                            mWindowContainer.getDisplayContent().mDisplayFrames, mWindowContainer,
                            overrideFrame);
                }
                mOverrideFrames.put(windowType, overrideFrame);
            }
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
        // frame may not yet be determined that server side doesn't think the window is ready to
        // visible. (i.e. No surface, pending insets that were given during layout, etc..)
        final Rect frame = mServerVisible ? mSourceFrame : EMPTY_RECT;
        if (mSource.getFrame().equals(frame)) {
            return;
        }
        mSource.setFrame(frame);
        if (mWindowContainer != null) {
            mSource.updateSideHint(mWindowContainer.getBounds());
        }
    }

    void onWindowContainerBoundsChanged() {
        mInsetsHintStale = true;
    }

    @VisibleForTesting
    Insets getInsetsHint() {
        if (!mServerVisible) {
            return mInsetsHint;
        }
        final WindowState win = mWindowContainer.asWindowState();
        if (win != null && win.mGivenInsetsPending) {
            return mInsetsHint;
        }
        if (mInsetsHintStale) {
            final Rect bounds = mWindowContainer.getBounds();
            mInsetsHint = mSource.calculateInsets(bounds, true /* ignoreVisibility */);
            mInsetsHintStale = false;
        }
        return mInsetsHint;
    }

    /** @return A new source computed by the specified window frame in the given display frames. */
    InsetsSource createSimulatedSource(DisplayFrames displayFrames, Rect frame) {
        final InsetsSource source = new InsetsSource(mSource);
        mTmpRect.set(frame);
        if (mFrameProvider != null) {
            mFrameProvider.apply(displayFrames, mWindowContainer, mTmpRect);
        }
        source.setFrame(mTmpRect);

        // Don't copy visible frame because it might not be calculated in the provided display
        // frames and it is not significant for this usage.
        source.setVisibleFrame(null);

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

        if (android.view.inputmethod.Flags.refactorInsetsController()) {
            if (mControl != null && mControl.getType() == WindowInsets.Type.ime() && !mServerVisible
                    && isServerVisible && windowState != null) {
                // in case the IME becomes visible, we need to check if it is already drawn and
                // does not have given insets pending. If it's not yet drawn, we do not set
                // server visibility
                isServerVisible = windowState.isDrawn() && !windowState.mGivenInsetsPending;
            }
        }
        final boolean serverVisibleChanged = mServerVisible != isServerVisible;
        setServerVisible(isServerVisible);
        final boolean positionChanged = updateInsetsControlPosition(windowState);
        if (mControl != null && !positionChanged
                // The insets hint would be updated if the position is changed. Here updates it for
                // the possible change of the bounds or the server visibility.
                && (updateInsetsHint()
                        || serverVisibleChanged
                                && android.view.inputmethod.Flags.refactorInsetsController())) {
            // Only call notifyControlChanged here when the position is not changed. Otherwise, it
            // is called or is scheduled to be called during updateInsetsControlPosition.
            mStateController.notifyControlChanged(mControlTarget, this);
        }
    }

    /**
     * @return {#code true} if the surface position of the control is changed.
     */
    boolean updateInsetsControlPosition(WindowState windowState) {
        if (mControl == null) {
            return false;
        }
        final Point position = getWindowFrameSurfacePosition();
        if (!mPosition.equals(position)) {
            mPosition.set(position);
            if (windowState != null && windowState.getWindowFrames().didFrameSizeChange()
                    && windowState.mWinAnimator.getShown() && mWindowContainer.okToDisplay()) {
                windowState.applyWithNextDraw(mSetControlPositionConsumer);
            } else {
                Transaction t = mWindowContainer.getSyncTransaction();
                if (windowState != null) {
                    // Make the buffer, token transformation, and leash position to be updated
                    // together when the window is drawn for new rotation. Otherwise the window
                    // may be outside the screen by the inconsistent orientations.
                    final AsyncRotationController rotationController =
                            mDisplayContent.getAsyncRotationController();
                    if (rotationController != null) {
                        final Transaction drawT =
                                rotationController.getDrawTransaction(windowState.mToken);
                        if (drawT != null) {
                            t = drawT;
                        }
                    }
                }
                mSetControlPositionConsumer.accept(t);
            }
            return true;
        }
        return false;
    }

    private Point getWindowFrameSurfacePosition() {
        final WindowState win = mWindowContainer.asWindowState();
        if (win != null && mControl != null) {
            final AsyncRotationController controller = mDisplayContent.getAsyncRotationController();
            if (controller != null && controller.shouldFreezeInsetsPosition(win)) {
                // Use previous position because the window still shows with old rotation.
                return mControl.getSurfacePosition();
            }
        }
        final Rect frame = win != null ? win.getFrame() : mWindowContainer.getBounds();
        final Point position = new Point();
        mWindowContainer.transformFrameToSurfacePosition(frame.left, frame.top, position);
        return position;
    }

    /**
     * @see InsetsStateController#onControlTargetChanged
     */
    void updateFakeControlTarget(@Nullable InsetsControlTarget fakeTarget) {
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

    void updateControlForTarget(@Nullable InsetsControlTarget target, boolean force,
            @Nullable ImeTracker.Token statsToken) {
        if (mSeamlessRotating) {
            // We are un-rotating the window against the display rotation. We don't want the target
            // to control the window for now.
            return;
        }
        mPendingControlTarget = target;

        if (mWindowContainer != null && mWindowContainer.getSurfaceControl() == null) {
            // if window doesn't have a surface, set it null and return.
            setWindowContainer(null, null, null);
        }
        if (mWindowContainer == null) {
            return;
        }
        if (target == mControlTarget && !force) {
            return;
        }
        if (mHasPendingPosition) {
            // Don't create a new leash while having a pending position. Otherwise, the position
            // will be changed earlier than expected, which can cause flicker.
            return;
        }
        if (target == null) {
            // Cancelling the animation will invoke onAnimationCancelled, resetting all the fields.
            mWindowContainer.cancelAnimation();
            setClientVisible((WindowInsets.Type.defaultVisible() & mSource.getType()) != 0);
            return;
        }
        boolean initiallyVisible = mClientVisible;
        final Point surfacePosition = getWindowFrameSurfacePosition();
        mPosition.set(surfacePosition);
        mAdapter = new ControlAdapter(surfacePosition);
        if (mSource.getType() == WindowInsets.Type.ime()) {
            if (android.view.inputmethod.Flags.refactorInsetsController()) {
                if (mClientVisible && mServerVisible) {
                    WindowContainer imeParentWindow = mDisplayContent.getImeParentWindow();
                    // If the IME is attached to an app window, only consider it initially visible
                    // if the parent is visible and wasn't part of a transition.
                    initiallyVisible =
                            imeParentWindow != null && !imeParentWindow.inTransitionSelfOrParent()
                                    && imeParentWindow.isVisible()
                                    && imeParentWindow.isVisibleRequested();
                } else {
                    initiallyVisible = false;
                }
            }
            setClientVisible(target.isRequestedVisible(WindowInsets.Type.ime()));
        }
        final Transaction t = mWindowContainer.getSyncTransaction();
        mWindowContainer.startAnimation(t, mAdapter, !initiallyVisible /* hidden */,
                ANIMATION_TYPE_INSETS_CONTROL);

        // The leash was just created. We cannot dispatch it until its surface transaction is
        // applied. Otherwise, the client's operation to the leash might be overwritten by us.
        mIsLeashReadyForDispatching = false;

        final SurfaceControl leash = mAdapter.mCapturedLeash;
        mControlTarget = target;
        updateVisibility();
        if (mSource.getType() == WindowInsets.Type.ime()) {
            if (!android.view.inputmethod.Flags.refactorInsetsController()) {
                // The IME cannot be initially visible, see ControlAdapter#startAnimation below.
                // Also, the ImeInsetsSourceConsumer clears the client visibility upon losing
                // control,  but this won't have reached here yet by the time the new control is
                // created.
                // Note: The DisplayImeController needs the correct previous client's visibility,
                // so we only override the initiallyVisible here.
                initiallyVisible = false;
            }
        }
        mControl = new InsetsSourceControl(mSource.getId(), mSource.getType(), leash,
                initiallyVisible, surfacePosition, getInsetsHint());
        mStateController.notifySurfaceTransactionReady(this, getSurfaceTransactionId(leash), true);

        ProtoLog.d(WM_DEBUG_WINDOW_INSETS,
                "InsetsSource Control %s for target %s", mControl, mControlTarget);
    }

    private long getSurfaceTransactionId(SurfaceControl leash) {
        // Here returns mNativeObject (long) as the ID instead of the leash itself so that
        // InsetsStateController won't keep referencing the leash unexpectedly.
        return leash != null ? leash.mNativeObject : 0;
    }

    /**
     * This is called when the surface transaction of the leash initialization has been committed.
     *
     * @param id Indicates which transaction is committed so that stale callbacks can be dropped.
     */
    void onSurfaceTransactionCommitted(long id) {
        if (mIsLeashReadyForDispatching) {
            return;
        }
        if (mControl == null) {
            return;
        }
        if (id != getSurfaceTransactionId(mControl.getLeash())) {
            return;
        }
        mIsLeashReadyForDispatching = true;
        mStateController.notifySurfaceTransactionReady(this, 0, false);
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

    boolean updateClientVisibility(InsetsTarget caller,
            @Nullable ImeTracker.Token statsToken) {
        final boolean requestedVisible = caller.isRequestedVisible(mSource.getType());
        if (caller != mControlTarget || requestedVisible == mClientVisible) {
            return false;
        }
        setClientVisible(requestedVisible);
        return true;
    }

    void setClientVisible(boolean clientVisible) {
        if (mClientVisible == clientVisible) {
            return;
        }
        mClientVisible = clientVisible;
        updateVisibility();
        // The visibility change needs a traversal to apply.
        mDisplayContent.setLayoutNeeded();
        mDisplayContent.mWmService.mWindowPlacerLocked.requestTraversal();
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PROTECTED)
    void setServerVisible(boolean serverVisible) {
        mServerVisible = serverVisible;
        updateSourceFrameForServerVisibility();
        updateVisibility();
    }

    protected void updateVisibility() {
        mSource.setVisible(mServerVisible && mClientVisible);
        ProtoLog.d(WM_DEBUG_WINDOW_INSETS,
                "InsetsSource updateVisibility for %s, serverVisible: %s clientVisible: %s",
                WindowInsets.Type.toString(mSource.getType()),
                mServerVisible, mClientVisible);
    }

    protected boolean isLeashReadyForDispatching(InsetsControlTarget target) {
        // If the target is not the control target, we are ready for dispatching a null-leash to it.
        return target != mControlTarget || mIsLeashReadyForDispatching;
    }

    /**
     * Gets the source control for the given control target. If this is the provider's control
     * target, but the leash is not ready for dispatching, a new source control object with the
     * leash set to {@code null} is returned.
     *
     * @param target the control target to get the source control for.
     */
    @Nullable
    InsetsSourceControl getControl(InsetsControlTarget target) {
        if (target == mControlTarget) {
            if (!isLeashReadyForDispatching(target) && mControl != null) {
                // The surface transaction of preparing leash is not applied yet. We don't send it
                // to the client in case that the client applies its transaction sooner than ours
                // that we could unexpectedly overwrite the surface state.
                return new InsetsSourceControl(mControl.getId(), mControl.getType(),
                        null /* leash */, mControl.isInitiallyVisible(),
                        mControl.getSurfacePosition(), mControl.getInsetsHint());
            }
            return mControl;
        }
        if (target == mFakeControlTarget) {
            return mFakeControl;
        }
        return null;
    }

    /**
     * Gets the leash of the source control for the given control target. If this is not the
     * provider's control target, or the leash is not ready for dispatching, this will
     * return {@code null}.
     *
     * @param target the control target to get the source control leash for.
     */
    @Nullable
    protected SurfaceControl getLeash(@NonNull InsetsControlTarget target) {
        return target == mControlTarget && mIsLeashReadyForDispatching && mControl != null
                ? mControl.getLeash() : null;
    }

    @Nullable
    InsetsControlTarget getControlTarget() {
        return mControlTarget;
    }

    @Nullable
    InsetsControlTarget getFakeControlTarget() {
        return mFakeControlTarget;
    }

    boolean isClientVisible() {
        return mClientVisible;
    }

    boolean overridesFrame(int windowType) {
        return mOverrideFrames.contains(windowType);
    }

    Rect getOverriddenFrame(int windowType) {
        return mOverrideFrames.get(windowType);
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + getClass().getSimpleName());
        prefix = prefix + "  ";
        pw.print(prefix + "mSource="); mSource.dump("", pw);
        pw.print(prefix + "mSourceFrame=");
        pw.println(mSourceFrame);
        if (mOverrideFrames.size() > 0) {
            pw.print(prefix + "mOverrideFrames=");
            pw.println(mOverrideFrames);
        }
        if (mControl != null) {
            pw.print(prefix + "mControl=");
            mControl.dump("", pw);
        }
        if (mControllable) {
            pw.print(prefix + "mInsetsHint=");
            pw.print(mInsetsHint);
            if (mInsetsHintStale) {
                pw.print(" stale");
            }
            pw.println();
        }
        pw.print(prefix);
        pw.print("mIsLeashReadyForDispatching="); pw.print(mIsLeashReadyForDispatching);
        pw.print(" mHasPendingPosition="); pw.print(mHasPendingPosition);
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
            pw.println(mControlTarget);
        }
        if (mPendingControlTarget != mControlTarget) {
            pw.print(prefix + "mPendingControlTarget=");
            pw.println(mPendingControlTarget);
        }
        if (mFakeControlTarget != null) {
            pw.print(prefix + "mFakeControlTarget=");
            pw.println(mFakeControlTarget);
        }
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId, @WindowTracingLogLevel int logLevel) {
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
        if (mPendingControlTarget != null && mPendingControlTarget != mControlTarget
                && mPendingControlTarget.getWindow() != null) {
            mPendingControlTarget.getWindow().dumpDebug(proto, PENDING_CONTROL_TARGET, logLevel);
        }
        if (mFakeControlTarget != null && mFakeControlTarget.getWindow() != null) {
            mFakeControlTarget.getWindow().dumpDebug(proto, FAKE_CONTROL_TARGET, logLevel);
        }
        if (mAdapter != null && mAdapter.mCapturedLeash != null) {
            mAdapter.mCapturedLeash.dumpDebug(proto, CAPTURED_LEASH);
        }
        proto.write(IS_LEASH_READY_FOR_DISPATCHING, mIsLeashReadyForDispatching);
        proto.write(CLIENT_VISIBLE, mClientVisible);
        proto.write(SERVER_VISIBLE, mServerVisible);
        proto.write(SEAMLESS_ROTATING, mSeamlessRotating);
        proto.write(CONTROLLABLE, mControllable);
        if (mWindowContainer != null && mWindowContainer.asWindowState() != null) {
            mWindowContainer.asWindowState().dumpDebug(proto, SOURCE_WINDOW_STATE, logLevel);
        }
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
            if (mSource.getType() == WindowInsets.Type.ime()) {
                if (!android.view.inputmethod.Flags.refactorInsetsController()) {
                    // TODO: use 0 alpha and remove t.hide() once b/138459974 is fixed.
                    t.setAlpha(animationLeash, 1 /* alpha */);
                    t.hide(animationLeash);
                }
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
                mStateController.notifySurfaceTransactionReady(InsetsSourceProvider.this, 0, false);
                mControl = null;
                mControlTarget = null;
                mAdapter = null;
                setClientVisible((WindowInsets.Type.defaultVisible() & mSource.getType()) != 0);
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
