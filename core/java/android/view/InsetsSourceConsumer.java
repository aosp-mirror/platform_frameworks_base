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
 * limitations under the License
 */

package android.view;

import static android.view.InsetsController.ANIMATION_TYPE_NONE;
import static android.view.InsetsController.ANIMATION_TYPE_RESIZE;
import static android.view.InsetsController.AnimationType;
import static android.view.InsetsController.DEBUG;
import static android.view.InsetsSourceConsumerProto.ANIMATION_STATE;
import static android.view.InsetsSourceConsumerProto.HAS_WINDOW_FOCUS;
import static android.view.InsetsSourceConsumerProto.IS_REQUESTED_VISIBLE;
import static android.view.InsetsSourceConsumerProto.PENDING_FRAME;
import static android.view.InsetsSourceConsumerProto.PENDING_VISIBLE_FRAME;
import static android.view.InsetsSourceConsumerProto.SOURCE_CONTROL;
import static android.view.InsetsSourceConsumerProto.TYPE_NUMBER;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.WindowInsets.Type.InsetsType;
import android.view.inputmethod.Flags;
import android.view.inputmethod.ImeTracker;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.inputmethod.ImeTracing;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Controls the visibility and animations of a single window insets source.
 * @hide
 */
public class InsetsSourceConsumer {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            ShowResult.SHOW_IMMEDIATELY,
            ShowResult.IME_SHOW_DELAYED,
            ShowResult.IME_SHOW_FAILED
    })
    @interface ShowResult {
        /**
         * Window type is ready to be shown, will be shown immediately.
         */
        int SHOW_IMMEDIATELY = 0;
        /**
         * Result will be delayed. Window needs to be prepared or request is not from controller.
         * Request will be delegated to controller and may or may not be shown.
         */
        int IME_SHOW_DELAYED = 1;
        /**
         * Window will not be shown because one of the conditions couldn't be met.
         * (e.g. in IME's case, when no editor is focused.)
         */
        int IME_SHOW_FAILED = 2;
    }

    protected static final int ANIMATION_STATE_NONE = 0;
    protected static final int ANIMATION_STATE_SHOW = 1;
    protected static final int ANIMATION_STATE_HIDE = 2;

    protected int mAnimationState = ANIMATION_STATE_NONE;

    protected final InsetsController mController;
    protected final InsetsState mState;
    private int mId;
    @InsetsType
    private final int mType;

    private static final String TAG = "InsetsSourceConsumer";
    @Nullable
    private InsetsSourceControl mSourceControl;
    private boolean mHasWindowFocus;
    private InsetsAnimationControlRunner.SurfaceParamsApplier mSurfaceParamsApplier =
            InsetsAnimationControlRunner.SurfaceParamsApplier.DEFAULT;
    private final Matrix mTmpMatrix = new Matrix();

    /**
     * Whether the view has focus returned by {@link #onWindowFocusGained(boolean)}.
     */
    private boolean mHasViewFocusWhenWindowFocusGain;
    private Rect mPendingFrame;
    private Rect mPendingVisibleFrame;

    /**
     * @param id The ID of the consumed insets.
     * @param type The {@link InsetsType} of the consumed insets.
     * @param state The current {@link InsetsState} of the consumed insets.
     * @param controller The {@link InsetsController} to use for insets interaction.
     */
    public InsetsSourceConsumer(int id, @InsetsType int type, InsetsState state,
            InsetsController controller) {
        mId = id;
        mType = type;
        mState = state;
        mController = controller;
    }

    /**
     * Updates the control delivered from the server.
     *
     * @param showTypes An integer array with a single entry that determines which types a show
     *                  animation should be run after setting the control.
     * @param hideTypes An integer array with a single entry that determines which types a hide
     *                  animation should be run after setting the control.
     * @return Whether the control has changed from the server
     */
    public boolean setControl(@Nullable InsetsSourceControl control,
            @InsetsType int[] showTypes, @InsetsType int[] hideTypes, int[] cancelTypes) {
        if (Objects.equals(mSourceControl, control)) {
            if (mSourceControl != null && mSourceControl != control) {
                mSourceControl.release(SurfaceControl::release);
                mSourceControl = control;
            }
            return false;
        }

        final InsetsSourceControl lastControl = mSourceControl;
        mSourceControl = control;
        if (control != null) {
            if (DEBUG) Log.d(TAG, String.format("setControl -> %s on %s",
                    WindowInsets.Type.toString(control.getType()),
                    mController.getHost().getRootViewTitle()));
        }
        if (mSourceControl == null) {
            // We are loosing control
            mController.notifyControlRevoked(this);

            // Check if we need to restore server visibility.
            final InsetsSource localSource = mState.peekSource(mId);
            final InsetsSource serverSource = mController.getLastDispatchedState().peekSource(mId);
            final boolean localVisible = localSource != null && localSource.isVisible();
            final boolean serverVisible = serverSource != null && serverSource.isVisible();
            if (localSource != null) {
                localSource.setVisible(serverVisible);
            }
            if (localVisible != serverVisible) {
                mController.notifyVisibilityChanged();
            }

            // Reset the applier to the default one which has the most lightweight implementation.
            setSurfaceParamsApplier(InsetsAnimationControlRunner.SurfaceParamsApplier.DEFAULT);
        } else {
            if (lastControl != null && InsetsSource.getInsetSide(lastControl.getInsetsHint())
                    != InsetsSource.getInsetSide(control.getInsetsHint())) {
                // The source has been moved to a different side. The coordinates are stale.
                // Canceling existing animation if there is any.
                cancelTypes[0] |= mType;
            }
            final boolean requestedVisible = isRequestedVisibleAwaitingControl();
            final SurfaceControl oldLeash = lastControl != null ? lastControl.getLeash() : null;
            final SurfaceControl newLeash = control.getLeash();
            if (newLeash != null && (oldLeash == null || !newLeash.isSameSurface(oldLeash))
                    && requestedVisible != control.isInitiallyVisible()) {
                // We are gaining leash, and need to run an animation since previous state
                // didn't match.
                if (DEBUG) Log.d(TAG, String.format("Gaining leash in %s, requestedVisible: %b",
                        mController.getHost().getRootViewTitle(), requestedVisible));
                if (requestedVisible) {
                    showTypes[0] |= mType;
                } else {
                    hideTypes[0] |= mType;
                }
            } else {
                // We are gaining control, but don't need to run an animation.
                // However make sure that the leash visibility is still up to date.
                if (applyLocalVisibilityOverride()) {
                    mController.notifyVisibilityChanged();
                }

                // If there is no animation controlling the leash, make sure the visibility and the
                // position is up-to-date. Note: ANIMATION_TYPE_RESIZE doesn't control the leash.
                final int animType = mController.getAnimationType(mType);
                if (animType == ANIMATION_TYPE_NONE || animType == ANIMATION_TYPE_RESIZE) {
                    applyRequestedVisibilityAndPositionToControl();
                }

                // Remove the surface that owned by last control when it lost.
                if (!requestedVisible && lastControl == null) {
                    removeSurface();
                }
            }
        }
        if (lastControl != null) {
            lastControl.release(SurfaceControl::release);
        }
        return true;
    }

    @VisibleForTesting(visibility = PACKAGE)
    public InsetsSourceControl getControl() {
        return mSourceControl;
    }

    /**
     * Determines if the consumer will be shown after control is available.
     *
     * @return {@code true} if consumer has a pending show.
     */
    protected boolean isRequestedVisibleAwaitingControl() {
        return (mController.getRequestedVisibleTypes() & mType) != 0;
    }

    int getId() {
        return mId;
    }

    void setId(int id) {
        mId = id;
    }

    @InsetsType int getType() {
        return mType;
    }

    /**
     * Sets the SurfaceParamsApplier that the latest animation runner is using. The leash owned by
     * this class is always applied by the applier, so that the transaction order can always be
     * aligned with the calling sequence.
     */
    void setSurfaceParamsApplier(InsetsAnimationControlRunner.SurfaceParamsApplier applier) {
        mSurfaceParamsApplier = applier;
    }

    /**
     * Called right after the animation is started or finished.
     */
    @VisibleForTesting(visibility = PACKAGE)
    public boolean onAnimationStateChanged(boolean running) {
        boolean insetsChanged = false;
        if (!running && mPendingFrame != null) {
            final InsetsSource source = mState.peekSource(mId);
            if (source != null) {
                source.setFrame(mPendingFrame);
                source.setVisibleFrame(mPendingVisibleFrame);
                insetsChanged = true;
            }
            mPendingFrame = null;
            mPendingVisibleFrame = null;
        }

        final boolean showRequested = isShowRequested();
        final boolean cancelledForNewAnimation;
        if (Flags.refactorInsetsController()) {
            cancelledForNewAnimation =
                    (mController.getCancelledForNewAnimationTypes() & mType) != 0;
        } else {
            cancelledForNewAnimation = (!running && showRequested)
                    ? mAnimationState == ANIMATION_STATE_HIDE
                    : mAnimationState == ANIMATION_STATE_SHOW;
        }

        mAnimationState = running
                ? (showRequested ? ANIMATION_STATE_SHOW : ANIMATION_STATE_HIDE)
                : ANIMATION_STATE_NONE;

        // We apply the visibility override after the animation is started. We don't do this before
        // that because we need to know the initial insets state while creating the animation.
        // We also need to apply the override after the animation is finished because the requested
        // visibility can be set when finishing the user animation.
        // If the animation is cancelled because we are going to play a new animation with an
        // opposite direction, don't apply it now but after the new animation is started.
        if (!cancelledForNewAnimation) {
            insetsChanged |= applyLocalVisibilityOverride();
        }
        return insetsChanged;
    }

    protected boolean isShowRequested() {
        return (mController.getRequestedVisibleTypes() & getType()) != 0;
    }

    /**
     * Called when current window gains focus
     */
    public void onWindowFocusGained(boolean hasViewFocus) {
        mHasWindowFocus = true;
        mHasViewFocusWhenWindowFocusGain = hasViewFocus;
    }

    /**
     * Called when current window loses focus.
     */
    public void onWindowFocusLost() {
        mHasWindowFocus = false;
    }

    boolean hasViewFocusWhenWindowFocusGain() {
        return mHasViewFocusWhenWindowFocusGain;
    }

    @VisibleForTesting(visibility = PACKAGE)
    public boolean applyLocalVisibilityOverride() {
        if (Flags.refactorInsetsController()) {
            if (mType == WindowInsets.Type.ime()) {
                ImeTracing.getInstance().triggerClientDump(
                        "ImeInsetsSourceConsumer#applyLocalVisibilityOverride",
                        mController.getHost().getInputMethodManager(), null /* icProto */);
            }
        }
        final InsetsSource source = mState.peekSource(mId);
        if (source == null) {
            return false;
        }
        final boolean requestedVisible = (mController.getRequestedVisibleTypes() & mType) != 0;

        // If we don't have control or the leash (in case of the IME), we enforce the
        // visibility to be hidden, as otherwise we would let the app know too early.
        if (mSourceControl == null) {
            if (DEBUG) {
                Log.d(TAG, TextUtils.formatSimple(
                        "applyLocalVisibilityOverride: No control in %s for type %s, "
                                + "requestedVisible=%s",
                        mController.getHost().getRootViewTitle(),
                        WindowInsets.Type.toString(mType), requestedVisible));
            }
            return false;
        }
        if (Flags.refactorInsetsController()) {
            // TODO(b/323136120) add a flag to the control, to define whether a leash is
            //  needed and make it generic for all types
            if (mId == InsetsSource.ID_IME && mSourceControl.getLeash() == null) {
                if (DEBUG) {
                    Log.d(TAG, TextUtils.formatSimple(
                            "applyLocalVisibilityOverride: Set the source visibility to false, as"
                                    + " there is no leash yet for type %s in %s",
                            WindowInsets.Type.toString(mType),
                            mController.getHost().getRootViewTitle()));
                }
                boolean wasVisible = source.isVisible();
                source.setVisible(false);
                // only if it was visible before and is now hidden, we want to notify about the
                // changed state
                return wasVisible;
            }
        }
        if (source.isVisible() == requestedVisible) {
            return false;
        }
        if (DEBUG) Log.d(TAG, String.format("applyLocalVisibilityOverride: %s requestedVisible: %b",
                mController.getHost().getRootViewTitle(), requestedVisible));
        source.setVisible(requestedVisible);
        return true;
    }

    /**
     * Request to show current window type.
     *
     * @param fromController {@code true} if request is coming from controller.
     *                       (e.g. in IME case, controller is
     *                       {@link android.inputmethodservice.InputMethodService}).
     * @param statsToken the token tracking the current IME request or {@code null} otherwise.
     *
     * @implNote The {@code statsToken} is ignored here, and only handled in
     * {@link ImeInsetsSourceConsumer} for IME animations only.
     *
     * @return @see {@link ShowResult}.
     */
    @VisibleForTesting(visibility = PACKAGE)
    @ShowResult
    public int requestShow(boolean fromController, @Nullable ImeTracker.Token statsToken) {
        return ShowResult.SHOW_IMMEDIATELY;
    }

    void requestHide(boolean fromController, @Nullable ImeTracker.Token statsToken) {
        // no-op for types that always return ShowResult#SHOW_IMMEDIATELY.
    }

    /**
     * Reports that this source's perceptibility has changed
     *
     * @param perceptible true if the source is perceptible, false otherwise.
     * @see InsetsAnimationControlCallbacks#reportPerceptible
     */
    public void onPerceptible(boolean perceptible) {
        if (Flags.refactorInsetsController()) {
            if (mType == WindowInsets.Type.ime()) {
                final IBinder window = mController.getHost().getWindowToken();
                if (window != null) {
                    mController.getHost().getInputMethodManager().reportPerceptible(window,
                            perceptible);
                }
            }
        }
    }

    /**
     * Remove surface on which this consumer type is drawn.
     */
    public void removeSurface() {
        // no-op for types that always return ShowResult#SHOW_IMMEDIATELY.
        if (Flags.refactorInsetsController()) {
            if (mType == WindowInsets.Type.ime()) {
                final IBinder window = mController.getHost().getWindowToken();
                if (window != null) {
                    mController.getHost().getInputMethodManager().removeImeSurface(window);
                }
            }
        }
    }

    @VisibleForTesting(visibility = PACKAGE)
    public void updateSource(InsetsSource newSource, @AnimationType int animationType) {
        InsetsSource source = mState.peekSource(mId);
        if (source == null || animationType == ANIMATION_TYPE_NONE
                || source.getFrame().equals(newSource.getFrame())) {
            mPendingFrame = null;
            mPendingVisibleFrame = null;
            mState.addSource(newSource);
            return;
        }

        // Frame is changing while animating. Keep note of the new frame but keep existing frame
        // until animation is finished.
        mPendingFrame = new Rect(newSource.getFrame());
        mPendingVisibleFrame = newSource.getVisibleFrame() != null
                ? new Rect(newSource.getVisibleFrame())
                : null;
        newSource.setFrame(source.getFrame());
        newSource.setVisibleFrame(source.getVisibleFrame());
        mState.addSource(newSource);
        if (DEBUG) Log.d(TAG, "updateSource: " + newSource);
    }

    private void applyRequestedVisibilityAndPositionToControl() {
        if (mSourceControl == null) {
            return;
        }
        final SurfaceControl leash = mSourceControl.getLeash();
        if (leash == null) {
            return;
        }

        final boolean visible = (mController.getRequestedVisibleTypes() & mType) != 0;
        final Point surfacePosition = mSourceControl.getSurfacePosition();

        if (DEBUG) Log.d(TAG, "applyRequestedVisibilityAndPositionToControl: visible=" + visible
                + " position=" + surfacePosition);

        mTmpMatrix.setTranslate(surfacePosition.x, surfacePosition.y);
        mSurfaceParamsApplier.applySurfaceParams(
                new SyncRtSurfaceTransactionApplier.SurfaceParams.Builder(leash)
                        .withVisibility(visible)
                        .withAlpha(visible ? 1 : 0)
                        .withMatrix(mTmpMatrix)
                        .build());

        onPerceptible(visible);
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(HAS_WINDOW_FOCUS, mHasWindowFocus);
        proto.write(IS_REQUESTED_VISIBLE, isShowRequested());
        if (mSourceControl != null) {
            mSourceControl.dumpDebug(proto, SOURCE_CONTROL);
        }
        if (mPendingFrame != null) {
            mPendingFrame.dumpDebug(proto, PENDING_FRAME);
        }
        if (mPendingVisibleFrame != null) {
            mPendingVisibleFrame.dumpDebug(proto, PENDING_VISIBLE_FRAME);
        }
        proto.write(ANIMATION_STATE, mAnimationState);
        proto.write(TYPE_NUMBER, mType);
        proto.end(token);
    }
}
