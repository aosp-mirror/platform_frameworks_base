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
import static android.view.InsetsController.AnimationType;
import static android.view.InsetsController.DEBUG;
import static android.view.InsetsState.getDefaultVisibility;
import static android.view.InsetsState.toPublicType;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.util.Log;
import android.view.InsetsState.InternalInsetsType;
import android.view.SurfaceControl.Transaction;
import android.view.WindowInsets.Type.InsetsType;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.function.Supplier;

/**
 * Controls the visibility and animations of a single window insets source.
 * @hide
 */
public class InsetsSourceConsumer {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {ShowResult.SHOW_IMMEDIATELY, ShowResult.IME_SHOW_DELAYED, ShowResult.IME_SHOW_FAILED})
    @interface ShowResult {
        /**
         * Window type is ready to be shown, will be shown immidiately.
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

    protected final InsetsController mController;
    protected boolean mRequestedVisible;
    protected final InsetsState mState;
    protected final @InternalInsetsType int mType;

    private static final String TAG = "InsetsSourceConsumer";
    private final Supplier<Transaction> mTransactionSupplier;
    private @Nullable InsetsSourceControl mSourceControl;
    private boolean mHasWindowFocus;
    private Rect mPendingFrame;
    private Rect mPendingVisibleFrame;

    /**
     * Indicates if we have the pending animation. When we have the control, we need to play the
     * animation if the requested visibility is different from the current state. But if we haven't
     * had a leash yet, we will set this flag, and play the animation once we get the leash.
     */
    private boolean mIsAnimationPending;

    public InsetsSourceConsumer(@InternalInsetsType int type, InsetsState state,
            Supplier<Transaction> transactionSupplier, InsetsController controller) {
        mType = type;
        mState = state;
        mTransactionSupplier = transactionSupplier;
        mController = controller;
        mRequestedVisible = getDefaultVisibility(type);
    }

    /**
     * Updates the control delivered from the server.

     * @param showTypes An integer array with a single entry that determines which types a show
     *                  animation should be run after setting the control.
     * @param hideTypes An integer array with a single entry that determines which types a hide
     *                  animation should be run after setting the control.
     */
    public void setControl(@Nullable InsetsSourceControl control,
            @InsetsType int[] showTypes, @InsetsType int[] hideTypes) {
        if (mSourceControl == control) {
            return;
        }
        SurfaceControl oldLeash = mSourceControl != null ? mSourceControl.getLeash() : null;

        final InsetsSourceControl lastControl = mSourceControl;
        mSourceControl = control;
        if (control != null) {
            if (DEBUG) Log.d(TAG, String.format("setControl -> %s on %s",
                    InsetsState.typeToString(control.getType()),
                    mController.getHost().getRootViewTitle()));
        }
        if (mSourceControl == null) {
            // We are loosing control
            mController.notifyControlRevoked(this);

            // Check if we need to restore server visibility.
            final InsetsSource source = mState.getSource(mType);
            final boolean serverVisibility =
                    mController.getLastDispatchedState().getSourceOrDefaultVisibility(mType);
            if (source.isVisible() != serverVisibility) {
                source.setVisible(serverVisibility);
                mController.notifyVisibilityChanged();
            }

            // For updateCompatSysUiVisibility
            applyLocalVisibilityOverride();
        } else {
            // We are gaining control, and need to run an animation since previous state
            // didn't match
            final boolean requestedVisible = isRequestedVisibleAwaitingControl();
            final boolean needAnimation = requestedVisible != mState.getSource(mType).isVisible();
            if (control.getLeash() != null && (needAnimation || mIsAnimationPending)) {
                if (DEBUG) Log.d(TAG, String.format("Gaining control in %s, requestedVisible: %b",
                        mController.getHost().getRootViewTitle(), requestedVisible));
                if (requestedVisible) {
                    showTypes[0] |= toPublicType(getType());
                } else {
                    hideTypes[0] |= toPublicType(getType());
                }
                mIsAnimationPending = false;
            } else {
                if (needAnimation) {
                    // We need animation but we haven't had a leash yet. Set this flag that when we
                    // get the leash we can play the deferred animation.
                    mIsAnimationPending = true;
                }
                // We are gaining control, but don't need to run an animation.
                // However make sure that the leash visibility is still up to date.
                if (applyLocalVisibilityOverride()) {
                    mController.notifyVisibilityChanged();
                }

                // If we have a new leash, make sure visibility is up-to-date, even though we
                // didn't want to run an animation above.
                SurfaceControl newLeash = mSourceControl.getLeash();
                if (oldLeash == null || newLeash == null || !oldLeash.isSameSurface(newLeash)) {
                    applyHiddenToControl();
                }
                if (!requestedVisible && !mIsAnimationPending) {
                    removeSurface();
                }
            }
        }
        if (lastControl != null) {
            lastControl.release(SurfaceControl::release);
        }
    }

    @VisibleForTesting
    public InsetsSourceControl getControl() {
        return mSourceControl;
    }

    /**
     * Determines if the consumer will be shown after control is available.
     * Note: for system bars this method is same as {@link #isRequestedVisible()}.
     *
     * @return {@code true} if consumer has a pending show.
     */
    protected boolean isRequestedVisibleAwaitingControl() {
        return isRequestedVisible();
    }

    int getType() {
        return mType;
    }

    @VisibleForTesting
    public void show(boolean fromIme) {
        if (DEBUG) Log.d(TAG, String.format("Call show() for type: %s fromIme: %b ",
                InsetsState.typeToString(mType), fromIme));
        setRequestedVisible(true);
    }

    @VisibleForTesting
    public void hide() {
        if (DEBUG) Log.d(TAG, String.format("Call hide for %s on %s",
                InsetsState.typeToString(mType), mController.getHost().getRootViewTitle()));
        setRequestedVisible(false);
    }

    void hide(boolean animationFinished, @AnimationType int animationType) {
        hide();
    }

    /**
     * Called when current window gains focus
     */
    public void onWindowFocusGained() {
        mHasWindowFocus = true;
    }

    /**
     * Called when current window loses focus.
     */
    public void onWindowFocusLost() {
        mHasWindowFocus = false;
    }

    boolean hasWindowFocus() {
        return mHasWindowFocus;
    }

    boolean applyLocalVisibilityOverride() {
        final InsetsSource source = mState.peekSource(mType);
        final boolean isVisible = source != null ? source.isVisible() : getDefaultVisibility(mType);
        final boolean hasControl = mSourceControl != null;

        // We still need to let the legacy app know the visibility change even if we don't have the
        // control. If we don't have the source, we don't change the requested visibility for making
        // the callback behavior compatible.
        mController.updateCompatSysUiVisibility(
                mType, (hasControl || source == null) ? mRequestedVisible : isVisible, hasControl);

        // If we don't have control, we are not able to change the visibility.
        if (!hasControl) {
            if (DEBUG) Log.d(TAG, "applyLocalVisibilityOverride: No control in "
                    + mController.getHost().getRootViewTitle()
                    + " requestedVisible " + mRequestedVisible);
            return false;
        }
        if (isVisible == mRequestedVisible) {
            return false;
        }
        if (DEBUG) Log.d(TAG, String.format("applyLocalVisibilityOverride: %s requestedVisible: %b",
                mController.getHost().getRootViewTitle(), mRequestedVisible));
        mState.getSource(mType).setVisible(mRequestedVisible);
        return true;
    }

    @VisibleForTesting
    public boolean isRequestedVisible() {
        return mRequestedVisible;
    }

    /**
     * Request to show current window type.
     *
     * @param fromController {@code true} if request is coming from controller.
     *                       (e.g. in IME case, controller is
     *                       {@link android.inputmethodservice.InputMethodService}).
     * @return @see {@link ShowResult}.
     */
    @VisibleForTesting
    public @ShowResult int requestShow(boolean fromController) {
        return ShowResult.SHOW_IMMEDIATELY;
    }

    /**
     * Reports that this source's perceptibility has changed
     *
     * @param perceptible true if the source is perceptible, false otherwise.
     * @see InsetsAnimationControlCallbacks#reportPerceptible
     */
    public void onPerceptible(boolean perceptible) {
    }

    /**
     * Notify listeners that window is now hidden.
     */
    void notifyHidden() {
        // no-op for types that always return ShowResult#SHOW_IMMEDIATELY.
    }

    /**
     * Remove surface on which this consumer type is drawn.
     */
    public void removeSurface() {
        // no-op for types that always return ShowResult#SHOW_IMMEDIATELY.
    }

    @VisibleForTesting(visibility = PACKAGE)
    public void updateSource(InsetsSource newSource, @AnimationType int animationType) {
        InsetsSource source = mState.peekSource(mType);
        if (source == null || animationType == ANIMATION_TYPE_NONE
                || source.getFrame().equals(newSource.getFrame())) {
            mPendingFrame = null;
            mPendingVisibleFrame = null;
            mState.addSource(newSource);
            return;
        }

        // Frame is changing while animating. Keep note of the new frame but keep existing frame
        // until animation is finished.
        newSource = new InsetsSource(newSource);
        mPendingFrame = new Rect(newSource.getFrame());
        mPendingVisibleFrame = newSource.getVisibleFrame() != null
                ? new Rect(newSource.getVisibleFrame())
                : null;
        newSource.setFrame(source.getFrame());
        newSource.setVisibleFrame(source.getVisibleFrame());
        mState.addSource(newSource);
        if (DEBUG) Log.d(TAG, "updateSource: " + newSource);
    }

    @VisibleForTesting(visibility = PACKAGE)
    public boolean notifyAnimationFinished() {
        if (mPendingFrame != null) {
            InsetsSource source = mState.getSource(mType);
            source.setFrame(mPendingFrame);
            source.setVisibleFrame(mPendingVisibleFrame);
            mPendingFrame = null;
            mPendingVisibleFrame = null;
            return true;
        }
        return false;
    }

    /**
     * Sets requested visibility from the client, regardless of whether we are able to control it at
     * the moment.
     */
    protected void setRequestedVisible(boolean requestedVisible) {
        if (mRequestedVisible != requestedVisible) {
            mRequestedVisible = requestedVisible;
            mIsAnimationPending = false;
            if (DEBUG) Log.d(TAG, "setRequestedVisible: " + requestedVisible);
        }
        if (applyLocalVisibilityOverride()) {
            mController.notifyVisibilityChanged();
        }
    }

    private void applyHiddenToControl() {
        if (mSourceControl == null || mSourceControl.getLeash() == null) {
            return;
        }

        final Transaction t = mTransactionSupplier.get();
        if (DEBUG) Log.d(TAG, "applyHiddenToControl: " + mRequestedVisible);
        if (mRequestedVisible) {
            t.show(mSourceControl.getLeash());
        } else {
            t.hide(mSourceControl.getLeash());
        }
        t.apply();
        onPerceptible(mRequestedVisible);
    }
}
