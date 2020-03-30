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
import static android.view.InsetsState.toPublicType;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.graphics.Rect;
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

    private final Supplier<Transaction> mTransactionSupplier;
    private @Nullable InsetsSourceControl mSourceControl;
    private boolean mHasWindowFocus;
    private Rect mPendingFrame;
    private Rect mPendingVisibleFrame;

    public InsetsSourceConsumer(@InternalInsetsType int type, InsetsState state,
            Supplier<Transaction> transactionSupplier, InsetsController controller) {
        mType = type;
        mState = state;
        mTransactionSupplier = transactionSupplier;
        mController = controller;
        mRequestedVisible = InsetsState.getDefaultVisibility(type);
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
        final InsetsSourceControl lastControl = mSourceControl;
        mSourceControl = control;

        // We are loosing control
        if (mSourceControl == null) {
            mController.notifyControlRevoked(this);

            // Restore server visibility.
            mState.getSource(getType()).setVisible(
                    mController.getLastDispatchedState().getSource(getType()).isVisible());
            applyLocalVisibilityOverride();
        } else {
            // We are gaining control, and need to run an animation since previous state
            // didn't match
            if (mRequestedVisible != mState.getSource(mType).isVisible()) {
                if (mRequestedVisible) {
                    showTypes[0] |= toPublicType(getType());
                } else {
                    hideTypes[0] |= toPublicType(getType());
                }
            } else {
                // We are gaining control, but don't need to run an animation.
                // However make sure that the leash visibility is still up to date.
                if (applyLocalVisibilityOverride()) {
                    mController.notifyVisibilityChanged();
                    applyHiddenToControl();
                }
            }
        }
        if (lastControl != null) {
            lastControl.release(mController::releaseSurfaceControlFromRt);
        }
    }

    @VisibleForTesting
    public InsetsSourceControl getControl() {
        return mSourceControl;
    }

    int getType() {
        return mType;
    }

    @VisibleForTesting
    public void show(boolean fromIme) {
        setRequestedVisible(true);
    }

    @VisibleForTesting
    public void hide() {
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
        InsetsSource source = mState.peekSource(mType);
        final boolean isVisible = source != null && source.isVisible();
        final boolean hasControl = mSourceControl != null;

        // We still need to let the legacy app know the visibility change even if we don't have the
        // control.
        mController.updateCompatSysUiVisibility(
                mType, hasControl ? mRequestedVisible : isVisible, hasControl);

        // If we don't have control, we are not able to change the visibility.
        if (!hasControl) {
            return false;
        }
        if (isVisible == mRequestedVisible) {
            return false;
        }
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

    void updateSource(InsetsSource newSource) {
        InsetsSource source = mState.peekSource(mType);
        if (source == null || mController.getAnimationType(mType) == ANIMATION_TYPE_NONE
                || source.getFrame().equals(newSource.getFrame())) {
            mState.addSource(newSource);
            return;
        }

        // Frame is changing while animating. Keep note of the new frame but keep existing frame
        // until animaition is finished.
        newSource = new InsetsSource(newSource);
        mPendingFrame = new Rect(newSource.getFrame());
        mPendingVisibleFrame = newSource.getVisibleFrame() != null
                ? new Rect(newSource.getVisibleFrame())
                : null;
        newSource.setFrame(source.getFrame());
        newSource.setVisibleFrame(source.getVisibleFrame());
        mState.addSource(newSource);
    }

    boolean notifyAnimationFinished() {
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
    private void setRequestedVisible(boolean requestedVisible) {
        mRequestedVisible = requestedVisible;
        if (applyLocalVisibilityOverride()) {
            mController.notifyVisibilityChanged();
        }
    }

    private void applyHiddenToControl() {
        if (mSourceControl == null || mSourceControl.getLeash() == null) {
            return;
        }

        final Transaction t = mTransactionSupplier.get();
        if (mRequestedVisible) {
            t.show(mSourceControl.getLeash());
        } else {
            t.hide(mSourceControl.getLeash());
        }
        t.apply();
    }
}
