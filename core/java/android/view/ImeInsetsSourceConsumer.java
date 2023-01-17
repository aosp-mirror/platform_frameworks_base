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
 * limitations under the License
 */

package android.view;

import static android.os.Trace.TRACE_TAG_VIEW;
import static android.view.ImeInsetsSourceConsumerProto.HAS_PENDING_REQUEST;
import static android.view.ImeInsetsSourceConsumerProto.INSETS_SOURCE_CONSUMER;
import static android.view.ImeInsetsSourceConsumerProto.IS_REQUESTED_VISIBLE_AWAITING_CONTROL;

import android.annotation.Nullable;
import android.os.IBinder;
import android.os.Process;
import android.os.Trace;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl.Transaction;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.inputmethod.ImeTracing;
import com.android.internal.inputmethod.SoftInputShowHideReason;

import java.util.function.Supplier;

/**
 * Controls the visibility and animations of IME window insets source.
 * @hide
 */
public final class ImeInsetsSourceConsumer extends InsetsSourceConsumer {

    /**
     * Tracks whether are requested to show during the hide animation or requested to hide during
     * the show animation. If this is true, we should not remove the surface.
     */
    private boolean mHasPendingRequest;

    /**
     * Tracks whether we have an outstanding request from the IME to show, but weren't able to
     * execute it because we didn't have control yet.
     */
    private boolean mIsRequestedVisibleAwaitingControl;

    public ImeInsetsSourceConsumer(
            int id, InsetsState state, Supplier<Transaction> transactionSupplier,
            InsetsController controller) {
        super(id, WindowInsets.Type.ime(), state, transactionSupplier, controller);
    }

    @Override
    public boolean onAnimationStateChanged(boolean running) {
        if (!running) {
            ImeTracing.getInstance().triggerClientDump(
                    "ImeInsetsSourceConsumer#onAnimationFinished",
                    mController.getHost().getInputMethodManager(), null /* icProto */);
        }
        final boolean insetsChanged = super.onAnimationStateChanged(running);
        if (!isShowRequested()) {
            mIsRequestedVisibleAwaitingControl = false;
            if (!running && !mHasPendingRequest) {
                notifyHidden(null /* statsToken */);
                removeSurface();
            }
        }
        // This method is called
        // (1) after the animation starts.
        // (2) after the animation ends (including the case of cancel).
        // (3) if the IME is not controllable (running == false in this case).
        // We should reset mHasPendingRequest in all cases.
        mHasPendingRequest = false;
        return insetsChanged;
    }

    @Override
    public void onWindowFocusGained(boolean hasViewFocus) {
        super.onWindowFocusGained(hasViewFocus);
        getImm().registerImeConsumer(this);
        if ((mController.getRequestedVisibleTypes() & getType()) != 0 && getControl() == null) {
            mIsRequestedVisibleAwaitingControl = true;
        }
    }

    @Override
    public void onWindowFocusLost() {
        super.onWindowFocusLost();
        getImm().unregisterImeConsumer(this);
        mIsRequestedVisibleAwaitingControl = false;
    }

    @Override
    public boolean applyLocalVisibilityOverride() {
        ImeTracing.getInstance().triggerClientDump(
                "ImeInsetsSourceConsumer#applyLocalVisibilityOverride",
                mController.getHost().getInputMethodManager(), null /* icProto */);
        return super.applyLocalVisibilityOverride();
    }

    /**
     * Request {@link InputMethodManager} to show the IME.
     * @return @see {@link android.view.InsetsSourceConsumer.ShowResult}.
     */
    @Override
    @ShowResult
    public int requestShow(boolean fromIme, @Nullable ImeTracker.Token statsToken) {
        if (fromIme) {
            ImeTracing.getInstance().triggerClientDump(
                    "ImeInsetsSourceConsumer#requestShow",
                    mController.getHost().getInputMethodManager(), null /* icProto */);
        }
        onShowRequested();

        // TODO: ResultReceiver for IME.
        // TODO: Set mShowOnNextImeRender to automatically show IME and guard it with a flag.
        ImeTracker.forLogging().onProgress(statsToken,
                ImeTracker.PHASE_CLIENT_INSETS_CONSUMER_REQUEST_SHOW);

        if (getControl() == null) {
            // If control is null, schedule to show IME when control is available.
            mIsRequestedVisibleAwaitingControl = true;
        }
        // If we had a request before to show from IME (tracked with mImeRequestedShow), reaching
        // this code here means that we now got control, so we can start the animation immediately.
        // If client window is trying to control IME and IME is already visible, it is immediate.
        if (fromIme || (mState.getSource(getId()).isVisible() && getControl() != null)) {
            return ShowResult.SHOW_IMMEDIATELY;
        }

        return getImm().requestImeShow(mController.getHost().getWindowToken(), statsToken)
                ? ShowResult.IME_SHOW_DELAYED : ShowResult.IME_SHOW_FAILED;
    }

    void requestHide(boolean fromIme, @Nullable ImeTracker.Token statsToken) {
        if (!fromIme) {
            // The insets might be controlled by a remote target. Let the server know we are
            // requested to hide.
            notifyHidden(statsToken);
        }
        if (mAnimationState == ANIMATION_STATE_SHOW) {
            mHasPendingRequest = true;
        }
    }

    /**
     * Notify {@link com.android.server.inputmethod.InputMethodManagerService} that
     * IME insets are hidden.
     *
     * @param statsToken the token tracking the current IME hide request or {@code null} otherwise.
     */
    private void notifyHidden(@Nullable ImeTracker.Token statsToken) {
        // Create a new stats token to track the hide request when:
        //  - we do not already have one, or
        //  - we do already have one, but we have control and use the passed in token
        //      for the insets animation already.
        if (statsToken == null || getControl() != null) {
            statsToken = ImeTracker.forLogging().onRequestHide(null /* component */,
                    Process.myUid(),
                    ImeTracker.ORIGIN_CLIENT_HIDE_SOFT_INPUT,
                    SoftInputShowHideReason.HIDE_SOFT_INPUT_BY_INSETS_API);
        }

        ImeTracker.forLogging().onProgress(statsToken,
                ImeTracker.PHASE_CLIENT_INSETS_CONSUMER_NOTIFY_HIDDEN);

        getImm().notifyImeHidden(mController.getHost().getWindowToken(), statsToken);
        mIsRequestedVisibleAwaitingControl = false;
        Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.hideRequestFromApi", 0);
    }

    @Override
    public void removeSurface() {
        final IBinder window = mController.getHost().getWindowToken();
        if (window != null) {
            getImm().removeImeSurface(window);
        }
    }

    @Override
    public boolean setControl(@Nullable InsetsSourceControl control, int[] showTypes,
            int[] hideTypes) {
        ImeTracing.getInstance().triggerClientDump("ImeInsetsSourceConsumer#setControl",
                mController.getHost().getInputMethodManager(), null /* icProto */);
        if (!super.setControl(control, showTypes, hideTypes)) {
            return false;
        }
        if (control == null && !mIsRequestedVisibleAwaitingControl) {
            mController.setRequestedVisibleTypes(0 /* visibleTypes */, getType());
            removeSurface();
        }
        if (control != null) {
            mIsRequestedVisibleAwaitingControl = false;
        }
        return true;
    }

    @Override
    protected boolean isRequestedVisibleAwaitingControl() {
        return super.isRequestedVisibleAwaitingControl() || mIsRequestedVisibleAwaitingControl;
    }

    @Override
    public void onPerceptible(boolean perceptible) {
        super.onPerceptible(perceptible);
        final IBinder window = mController.getHost().getWindowToken();
        if (window != null) {
            getImm().reportPerceptible(window, perceptible);
        }
    }

    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        super.dumpDebug(proto, INSETS_SOURCE_CONSUMER);
        proto.write(IS_REQUESTED_VISIBLE_AWAITING_CONTROL, mIsRequestedVisibleAwaitingControl);
        proto.write(HAS_PENDING_REQUEST, mHasPendingRequest);
        proto.end(token);
    }

    /**
     * Called when {@link #requestShow(boolean, ImeTracker.Token)} or
     * {@link InputMethodManager#showSoftInput(View, int)} is called.
     */
    public void onShowRequested() {
        if (mAnimationState == ANIMATION_STATE_HIDE) {
            mHasPendingRequest = true;
        }
    }

    private InputMethodManager getImm() {
        return mController.getHost().getInputMethodManager();
    }
}
