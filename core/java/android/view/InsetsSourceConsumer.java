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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.view.InsetsState.InternalInsetType;
import android.view.SurfaceControl.Transaction;

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
    @IntDef(value = {ShowResult.SHOW_IMMEDIATELY, ShowResult.SHOW_DELAYED, ShowResult.SHOW_FAILED})
    @interface ShowResult {
        /**
         * Window type is ready to be shown, will be shown immidiately.
         */
        int SHOW_IMMEDIATELY = 0;
        /**
         * Result will be delayed. Window needs to be prepared or request is not from controller.
         * Request will be delegated to controller and may or may not be shown.
         */
        int SHOW_DELAYED = 1;
        /**
         * Window will not be shown because one of the conditions couldn't be met.
         * (e.g. in IME's case, when no editor is focused.)
         */
        int SHOW_FAILED = 2;
    }

    protected final InsetsController mController;
    protected boolean mVisible;
    private final Supplier<Transaction> mTransactionSupplier;
    private final @InternalInsetType int mType;
    private final InsetsState mState;
    private @Nullable InsetsSourceControl mSourceControl;
    private boolean mHasWindowFocus;

    public InsetsSourceConsumer(@InternalInsetType int type, InsetsState state,
            Supplier<Transaction> transactionSupplier, InsetsController controller) {
        mType = type;
        mState = state;
        mTransactionSupplier = transactionSupplier;
        mController = controller;
        mVisible = InsetsState.getDefaultVisibility(type);
    }

    public void setControl(@Nullable InsetsSourceControl control) {
        if (mSourceControl == control) {
            return;
        }
        mSourceControl = control;
        applyHiddenToControl();
        if (applyLocalVisibilityOverride()) {
            mController.notifyVisibilityChanged();
        }
        if (mSourceControl == null) {
            mController.notifyControlRevoked(this);
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
    public void show() {
        setVisible(true);
    }

    @VisibleForTesting
    public void hide() {
        setVisible(false);
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

        // If we don't have control, we are not able to change the visibility.
        if (mSourceControl == null) {
            return false;
        }
        if (mState.getSource(mType).isVisible() == mVisible) {
            return false;
        }
        mState.getSource(mType).setVisible(mVisible);
        return true;
    }

    @VisibleForTesting
    public boolean isVisible() {
        return mVisible;
    }

    /**
     * Request to show current window type.
     *
     * @param fromController {@code true} if request is coming from controller.
     *                       (e.g. in IME case, controller is
     *                       {@link android.inputmethodservice.InputMethodService}).
     * @return @see {@link ShowResult}.
     */
    @ShowResult int requestShow(boolean fromController) {
        return ShowResult.SHOW_IMMEDIATELY;
    }

    /**
     * Notify listeners that window is now hidden.
     */
    void notifyHidden() {
        // no-op for types that always return ShowResult#SHOW_IMMEDIATELY.
    }

    private void setVisible(boolean visible) {
        if (mVisible == visible) {
            return;
        }
        mVisible = visible;
        applyLocalVisibilityOverride();
        mController.notifyVisibilityChanged();
    }

    private void applyHiddenToControl() {
        if (mSourceControl == null || mSourceControl.getLeash() == null) {
            return;
        }

        final Transaction t = mTransactionSupplier.get();
        if (mVisible) {
            t.show(mSourceControl.getLeash());
        } else {
            t.hide(mSourceControl.getLeash());
        }
        t.apply();
    }
}
