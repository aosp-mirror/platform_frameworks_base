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

package android.view;

import static android.view.InsetsState.TYPE_IME;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.TypeEvaluator;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.Property;
import android.util.SparseArray;
import android.view.InsetsSourceConsumer.ShowResult;
import android.view.InsetsState.InternalInsetType;
import android.view.SurfaceControl.Transaction;
import android.view.WindowInsets.Type;
import android.view.WindowInsets.Type.InsetType;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Implements {@link WindowInsetsController} on the client.
 * @hide
 */
public class InsetsController implements WindowInsetsController {

    private static final int ANIMATION_DURATION_SHOW_MS = 275;
    private static final int ANIMATION_DURATION_HIDE_MS = 340;
    private static final Interpolator INTERPOLATOR = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private static final int DIRECTION_NONE = 0;
    private static final int DIRECTION_SHOW = 1;
    private static final int DIRECTION_HIDE = 2;

    @IntDef ({DIRECTION_NONE, DIRECTION_SHOW, DIRECTION_HIDE})
    private @interface AnimationDirection{}

    /**
     * Translation animation evaluator.
     */
    private static TypeEvaluator<Insets> sEvaluator = (fraction, startValue, endValue) -> Insets.of(
            0,
            (int) (startValue.top + fraction * (endValue.top - startValue.top)),
            0,
            (int) (startValue.bottom + fraction * (endValue.bottom - startValue.bottom)));

    /**
     * Linear animation property
     */
    private static class InsetsProperty extends Property<WindowInsetsAnimationController, Insets> {
        InsetsProperty() {
            super(Insets.class, "Insets");
        }

        @Override
        public Insets get(WindowInsetsAnimationController object) {
            return object.getCurrentInsets();
        }
        @Override
        public void set(WindowInsetsAnimationController object, Insets value) {
            object.changeInsets(value);
        }
    }

    private final String TAG = "InsetsControllerImpl";

    private final InsetsState mState = new InsetsState();
    private final InsetsState mTmpState = new InsetsState();

    private final Rect mFrame = new Rect();
    private final SparseArray<InsetsSourceConsumer> mSourceConsumers = new SparseArray<>();
    private final ViewRootImpl mViewRoot;

    private final SparseArray<InsetsSourceControl> mTmpControlArray = new SparseArray<>();
    private final ArrayList<InsetsAnimationControlImpl> mAnimationControls = new ArrayList<>();
    private WindowInsets mLastInsets;

    private boolean mAnimCallbackScheduled;

    private final Runnable mAnimCallback;

    private final Rect mLastLegacyContentInsets = new Rect();
    private final Rect mLastLegacyStableInsets = new Rect();
    private ObjectAnimator mAnimator;
    private @AnimationDirection int mAnimationDirection;

    private int mPendingTypesToShow;

    private int mLastLegacySoftInputMode;

    public InsetsController(ViewRootImpl viewRoot) {
        mViewRoot = viewRoot;
        mAnimCallback = () -> {
            mAnimCallbackScheduled = false;
            if (mAnimationControls.isEmpty()) {
                return;
            }

            InsetsState state = new InsetsState(mState, true /* copySources */);
            for (int i = mAnimationControls.size() - 1; i >= 0; i--) {
                mAnimationControls.get(i).applyChangeInsets(state);
            }
            WindowInsets insets = state.calculateInsets(mFrame, mLastInsets.isRound(),
                    mLastInsets.shouldAlwaysConsumeNavBar(), mLastInsets.getDisplayCutout(),
                    mLastLegacyContentInsets, mLastLegacyStableInsets, mLastLegacySoftInputMode,
                    null /* typeSideMap */);
            mViewRoot.mView.dispatchWindowInsetsAnimationProgress(insets);
        };
    }

    void onFrameChanged(Rect frame) {
        if (mFrame.equals(frame)) {
            return;
        }
        mViewRoot.notifyInsetsChanged();
        mFrame.set(frame);
    }

    public InsetsState getState() {
        return mState;
    }

    boolean onStateChanged(InsetsState state) {
        if (mState.equals(state)) {
            return false;
        }
        mState.set(state);
        mTmpState.set(state, true /* copySources */);
        applyLocalVisibilityOverride();
        mViewRoot.notifyInsetsChanged();
        if (!mState.equals(mTmpState)) {
            sendStateToWindowManager();
        }
        return true;
    }

    /**
     * @see InsetsState#calculateInsets
     */
    @VisibleForTesting
    public WindowInsets calculateInsets(boolean isScreenRound,
            boolean alwaysConsumeNavBar, DisplayCutout cutout, Rect legacyContentInsets,
            Rect legacyStableInsets, int legacySoftInputMode) {
        mLastLegacyContentInsets.set(legacyContentInsets);
        mLastLegacyStableInsets.set(legacyStableInsets);
        mLastLegacySoftInputMode = legacySoftInputMode;
        mLastInsets = mState.calculateInsets(mFrame, isScreenRound, alwaysConsumeNavBar, cutout,
                legacyContentInsets, legacyStableInsets, legacySoftInputMode,
                null /* typeSideMap */);
        return mLastInsets;
    }

    /**
     * Called when the server has dispatched us a new set of inset controls.
     */
    public void onControlsChanged(InsetsSourceControl[] activeControls) {
        if (activeControls != null) {
            for (InsetsSourceControl activeControl : activeControls) {
                if (activeControl != null) {
                    // TODO(b/122982984): Figure out why it can be null.
                    mTmpControlArray.put(activeControl.getType(), activeControl);
                }
            }
        }

        // Ensure to update all existing source consumers
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            final InsetsSourceControl control = mTmpControlArray.get(consumer.getType());

            // control may be null, but we still need to update the control to null if it got
            // revoked.
            consumer.setControl(control);
        }

        // Ensure to create source consumers if not available yet.
        for (int i = mTmpControlArray.size() - 1; i >= 0; i--) {
            final InsetsSourceControl control = mTmpControlArray.valueAt(i);
            getSourceConsumer(control.getType()).setControl(control);
        }
        mTmpControlArray.clear();
    }

    @Override
    public void show(@InsetType int types) {
        show(types, false /* fromIme */);
    }

    private void show(@InsetType int types, boolean fromIme) {
        // TODO: Support a ResultReceiver for IME.
        // TODO(b/123718661): Make show() work for multi-session IME.
        int typesReady = 0;
        final ArraySet<Integer> internalTypes = InsetsState.toInternalType(types);
        for (int i = internalTypes.size() - 1; i >= 0; i--) {
            InsetsSourceConsumer consumer = getSourceConsumer(internalTypes.valueAt(i));
            if (mAnimationDirection == DIRECTION_HIDE) {
                // Only one animator (with multiple InsetType) can run at a time.
                // previous one should be cancelled for simplicity.
                cancelExistingAnimation();
            } else if (consumer.isVisible()
                    && (mAnimationDirection == DIRECTION_NONE
                    || mAnimationDirection == DIRECTION_HIDE)) {
                // no-op: already shown or animating in (because window visibility is
                // applied before starting animation).
                // TODO: When we have more than one types: handle specific case when
                // show animation is going on, but the current type is not becoming visible.
                continue;
            }
            typesReady |= InsetsState.toPublicType(consumer.getType());
        }
        applyAnimation(typesReady, true /* show */, fromIme);
    }

    @Override
    public void hide(@InsetType int types) {
        int typesReady = 0;
        final ArraySet<Integer> internalTypes = InsetsState.toInternalType(types);
        for (int i = internalTypes.size() - 1; i >= 0; i--) {
            InsetsSourceConsumer consumer = getSourceConsumer(internalTypes.valueAt(i));
            if (mAnimationDirection == DIRECTION_SHOW) {
                cancelExistingAnimation();
            } else if (!consumer.isVisible()
                    && (mAnimationDirection == DIRECTION_NONE
                    || mAnimationDirection == DIRECTION_HIDE)) {
                // no-op: already hidden or animating out.
                continue;
            }
            typesReady |= InsetsState.toPublicType(consumer.getType());
        }
        applyAnimation(typesReady, false /* show */, false /* fromIme */);
    }

    @Override
    public void controlWindowInsetsAnimation(@InsetType int types,
            WindowInsetsAnimationControlListener listener) {
        controlWindowInsetsAnimation(types, listener, false /* fromIme */);
    }

    private void controlWindowInsetsAnimation(@InsetType int types,
            WindowInsetsAnimationControlListener listener, boolean fromIme) {
        // If the frame of our window doesn't span the entire display, the control API makes very
        // little sense, as we don't deal with negative insets. So just cancel immediately.
        if (!mState.getDisplayFrame().equals(mFrame)) {
            listener.onCancelled();
            return;
        }
        controlAnimationUnchecked(types, listener, mFrame, fromIme);
    }

    private void controlAnimationUnchecked(@InsetType int types,
            WindowInsetsAnimationControlListener listener, Rect frame, boolean fromIme) {
        if (types == 0) {
            // nothing to animate.
            return;
        }
        // TODO: Check whether we already have a controller.
        final ArraySet<Integer> internalTypes = mState.toInternalType(types);
        final SparseArray<InsetsSourceConsumer> consumers = new SparseArray<>();

        Pair<Integer, Boolean> typesReadyPair = collectConsumers(fromIme, internalTypes, consumers);
        int typesReady = typesReadyPair.first;
        boolean isReady = typesReadyPair.second;
        if (!isReady) {
            // IME isn't ready, all requested types would be shown once IME is ready.
            mPendingTypesToShow = typesReady;
            // TODO: listener for pending types.
            return;
        }

        // pending types from previous request.
        typesReady = collectPendingConsumers(typesReady, consumers);

        if (typesReady == 0) {
            listener.onCancelled();
            return;
        }

        final InsetsAnimationControlImpl controller = new InsetsAnimationControlImpl(consumers,
                frame, mState, listener, typesReady,
                () -> new SyncRtSurfaceTransactionApplier(mViewRoot.mView), this);
        mAnimationControls.add(controller);
    }

    /**
     * @return Pair of (types ready to animate, is ready to animate).
     */
    private Pair<Integer, Boolean> collectConsumers(boolean fromIme,
            ArraySet<Integer> internalTypes, SparseArray<InsetsSourceConsumer> consumers) {
        int typesReady = 0;
        boolean isReady = true;
        for (int i = internalTypes.size() - 1; i >= 0; i--) {
            InsetsSourceConsumer consumer = getSourceConsumer(internalTypes.valueAt(i));
            if (consumer.getControl() != null) {
                if (!consumer.isVisible()) {
                    // Show request
                    switch(consumer.requestShow(fromIme)) {
                        case ShowResult.SHOW_IMMEDIATELY:
                            typesReady |= InsetsState.toPublicType(TYPE_IME);
                            break;
                        case ShowResult.SHOW_DELAYED:
                            isReady = false;
                            break;
                        case ShowResult.SHOW_FAILED:
                            // IME cannot be shown (since it didn't have focus), proceed
                            // with animation of other types.
                            if (mPendingTypesToShow != 0) {
                                // remove IME from pending because view no longer has focus.
                                mPendingTypesToShow &= ~InsetsState.toPublicType(TYPE_IME);
                            }
                            break;
                    }
                } else {
                    // Hide request
                    // TODO: Move notifyHidden() to beginning of the hide animation
                    // (when visibility actually changes using hideDirectly()).
                    consumer.notifyHidden();
                    typesReady |= InsetsState.toPublicType(consumer.getType());
                }
                consumers.put(consumer.getType(), consumer);
            } else {
                // TODO: Let calling app know it's not possible, or wait
                // TODO: Remove it from types
            }
        }
        return new Pair<>(typesReady, isReady);
    }

    private int collectPendingConsumers(@InsetType int typesReady,
            SparseArray<InsetsSourceConsumer> consumers) {
        if (mPendingTypesToShow != 0) {
            typesReady |= mPendingTypesToShow;
            final ArraySet<Integer> internalTypes = mState.toInternalType(mPendingTypesToShow);
            for (int i = internalTypes.size() - 1; i >= 0; i--) {
                InsetsSourceConsumer consumer = getSourceConsumer(internalTypes.valueAt(i));
                consumers.put(consumer.getType(), consumer);
            }
            mPendingTypesToShow = 0;
        }
        return typesReady;
    }

    private void applyLocalVisibilityOverride() {
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer controller = mSourceConsumers.valueAt(i);
            controller.applyLocalVisibilityOverride();
        }
    }

    @VisibleForTesting
    public @NonNull InsetsSourceConsumer getSourceConsumer(@InternalInsetType int type) {
        InsetsSourceConsumer controller = mSourceConsumers.get(type);
        if (controller != null) {
            return controller;
        }
        controller = createConsumerOfType(type);
        mSourceConsumers.put(type, controller);
        return controller;
    }

    @VisibleForTesting
    public void notifyVisibilityChanged() {
        mViewRoot.notifyInsetsChanged();
        sendStateToWindowManager();
    }

    /**
     * Called when current window gains focus.
     */
    public void onWindowFocusGained() {
        getSourceConsumer(TYPE_IME).onWindowFocusGained();
    }

    /**
     * Called when current window loses focus.
     */
    public void onWindowFocusLost() {
        getSourceConsumer(TYPE_IME).onWindowFocusLost();
    }

    ViewRootImpl getViewRoot() {
        return mViewRoot;
    }

    /**
     * Used by {@link ImeInsetsSourceConsumer} when IME decides to be shown/hidden.
     * @hide
     */
    @VisibleForTesting
    public void applyImeVisibility(boolean setVisible) {
        if (setVisible) {
            show(Type.IME, true /* fromIme */);
        } else {
            hide(Type.IME);
        }
    }

    private InsetsSourceConsumer createConsumerOfType(int type) {
        if (type == TYPE_IME) {
            return new ImeInsetsSourceConsumer(mState, Transaction::new, this);
        } else {
            return new InsetsSourceConsumer(type, mState, Transaction::new, this);
        }
    }

    /**
     * Sends the local visibility state back to window manager.
     */
    private void sendStateToWindowManager() {
        InsetsState tmpState = new InsetsState();
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            if (consumer.getControl() != null) {
                tmpState.addSource(mState.getSource(consumer.getType()));
            }
        }

        // TODO: Put this on a dispatcher thread.
        try {
            mViewRoot.mWindowSession.insetsModified(mViewRoot.mWindow, tmpState);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to call insetsModified", e);
        }
    }

    private void applyAnimation(@InsetType final int types, boolean show, boolean fromIme) {
        if (types == 0) {
            // nothing to animate.
            return;
        }

        WindowInsetsAnimationControlListener listener = new WindowInsetsAnimationControlListener() {
            @Override
            public void onReady(WindowInsetsAnimationController controller, int types) {
                if (show) {
                    showDirectly(types);
                } else {
                    hideDirectly(types);
                }
                mAnimator = ObjectAnimator.ofObject(
                        controller,
                        new InsetsProperty(),
                        sEvaluator,
                        show ? controller.getHiddenStateInsets() : controller.getShownStateInsets(),
                        show ? controller.getShownStateInsets() : controller.getHiddenStateInsets()
                );
                mAnimator.setDuration(show
                        ? ANIMATION_DURATION_SHOW_MS
                        : ANIMATION_DURATION_HIDE_MS);
                mAnimator.setInterpolator(INTERPOLATOR);
                mAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationCancel(Animator animation) {
                        onAnimationFinish();
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        onAnimationFinish();
                    }
                });
                mAnimator.start();
            }

            @Override
            public void onCancelled() {}

            private void onAnimationFinish() {
                mAnimationDirection = DIRECTION_NONE;
            }
        };
        // TODO: Instead of clearing this here, properly wire up
        // InsetsAnimationControlImpl.finish() to remove this from mAnimationControls.
        mAnimationControls.clear();

        // Show/hide animations always need to be relative to the display frame, in order that shown
        // and hidden state insets are correct.
        controlAnimationUnchecked(types, listener, mState.getDisplayFrame(), fromIme);
    }

    private void hideDirectly(@InsetType int types) {
        final ArraySet<Integer> internalTypes = InsetsState.toInternalType(types);
        for (int i = internalTypes.size() - 1; i >= 0; i--) {
            getSourceConsumer(internalTypes.valueAt(i)).hide();
        }
    }

    private void showDirectly(@InsetType int types) {
        final ArraySet<Integer> internalTypes = InsetsState.toInternalType(types);
        for (int i = internalTypes.size() - 1; i >= 0; i--) {
            getSourceConsumer(internalTypes.valueAt(i)).show();
        }
    }

    /**
     * Cancel on-going animation to show/hide {@link InsetType}.
     */
    @VisibleForTesting
    public void cancelExistingAnimation() {
        mAnimationDirection = DIRECTION_NONE;
        if (mAnimator != null) {
            mAnimator.cancel();
        }
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix); pw.println("InsetsController:");
        mState.dump(prefix + "  ", pw);
    }

    @VisibleForTesting
    public void dispatchAnimationStarted(WindowInsetsAnimationListener.InsetsAnimation animation) {
        mViewRoot.mView.dispatchWindowInsetsAnimationStarted(animation);
    }

    @VisibleForTesting
    public void dispatchAnimationFinished(WindowInsetsAnimationListener.InsetsAnimation animation) {
        mViewRoot.mView.dispatchWindowInsetsAnimationFinished(animation);
    }

    @VisibleForTesting
    public void scheduleApplyChangeInsets() {
        if (!mAnimCallbackScheduled) {
            mViewRoot.mChoreographer.postCallback(Choreographer.CALLBACK_INSETS_ANIMATION,
                    mAnimCallback, null /* token*/);
            mAnimCallbackScheduled = true;
        }
    }
}
