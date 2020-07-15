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

import static android.view.InsetsController.ANIMATION_TYPE_SHOW;
import static android.view.InsetsController.AnimationType;
import static android.view.InsetsController.DEBUG;
import static android.view.InsetsState.ISIDE_BOTTOM;
import static android.view.InsetsState.ISIDE_LEFT;
import static android.view.InsetsState.ISIDE_RIGHT;
import static android.view.InsetsState.ISIDE_TOP;
import static android.view.InsetsState.ITYPE_IME;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.annotation.Nullable;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.SparseSetArray;
import android.view.InsetsState.InternalInsetsSide;
import android.view.SyncRtSurfaceTransactionApplier.SurfaceParams;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsAnimation.Bounds;
import android.view.WindowManager.LayoutParams;
import android.view.animation.Interpolator;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;

/**
 * Implements {@link WindowInsetsAnimationController}
 * @hide
 */
@VisibleForTesting
public class InsetsAnimationControlImpl implements WindowInsetsAnimationController,
        InsetsAnimationControlRunner {

    private static final String TAG = "InsetsAnimationCtrlImpl";

    private final Rect mTmpFrame = new Rect();

    private final WindowInsetsAnimationControlListener mListener;
    private final SparseArray<InsetsSourceControl> mControls;
    private final SparseIntArray mTypeSideMap = new SparseIntArray();
    private final SparseSetArray<InsetsSourceControl> mSideSourceMap = new SparseSetArray<>();

    /** @see WindowInsetsAnimationController#getHiddenStateInsets */
    private final Insets mHiddenInsets;

    /** @see WindowInsetsAnimationController#getShownStateInsets */
    private final Insets mShownInsets;
    private final Matrix mTmpMatrix = new Matrix();
    private final InsetsState mInitialInsetsState;
    private final @AnimationType int mAnimationType;
    private final @InsetsType int mTypes;
    private final InsetsAnimationControlCallbacks mController;
    private final WindowInsetsAnimation mAnimation;
    /** @see WindowInsetsAnimationController#hasZeroInsetsIme */
    private final boolean mHasZeroInsetsIme;
    private Insets mCurrentInsets;
    private Insets mPendingInsets;
    private float mPendingFraction;
    private boolean mFinished;
    private boolean mCancelled;
    private boolean mShownOnFinish;
    private float mCurrentAlpha = 1.0f;
    private float mPendingAlpha = 1.0f;
    @VisibleForTesting(visibility = PACKAGE)
    public boolean mReadyDispatched;
    private Boolean mPerceptible;

    @VisibleForTesting
    public InsetsAnimationControlImpl(SparseArray<InsetsSourceControl> controls, Rect frame,
            InsetsState state, WindowInsetsAnimationControlListener listener,
            @InsetsType int types,
            InsetsAnimationControlCallbacks controller, long durationMs, Interpolator interpolator,
            @AnimationType int animationType) {
        mControls = controls;
        mListener = listener;
        mTypes = types;
        mController = controller;
        mInitialInsetsState = new InsetsState(state, true /* copySources */);
        mCurrentInsets = getInsetsFromState(mInitialInsetsState, frame, null /* typeSideMap */);
        mPendingInsets = mCurrentInsets;
        mHiddenInsets = calculateInsets(mInitialInsetsState, frame, controls, false /* shown */,
                null /* typeSideMap */);
        mShownInsets = calculateInsets(mInitialInsetsState, frame, controls, true /* shown */,
                mTypeSideMap);
        mHasZeroInsetsIme = mShownInsets.bottom == 0 && controlsInternalType(ITYPE_IME);
        if (mHasZeroInsetsIme) {
            // IME has shownInsets of ZERO, and can't map to a side by default.
            // Map zero insets IME to bottom, making it a special case of bottom insets.
            mTypeSideMap.put(ITYPE_IME, ISIDE_BOTTOM);
        }
        buildTypeSourcesMap(mTypeSideMap, mSideSourceMap, mControls);

        mAnimation = new WindowInsetsAnimation(mTypes, interpolator,
                durationMs);
        mAnimation.setAlpha(getCurrentAlpha());
        mAnimationType = animationType;
        mController.startAnimation(this, listener, types, mAnimation,
                new Bounds(mHiddenInsets, mShownInsets));
    }

    private boolean calculatePerceptible(Insets currentInsets, float currentAlpha) {
        return 100 * currentInsets.left >= 5 * (mShownInsets.left - mHiddenInsets.left)
                && 100 * currentInsets.top >= 5 * (mShownInsets.top - mHiddenInsets.top)
                && 100 * currentInsets.right >= 5 * (mShownInsets.right - mHiddenInsets.right)
                && 100 * currentInsets.bottom >= 5 * (mShownInsets.bottom - mHiddenInsets.bottom)
                && currentAlpha >= 0.5f;
    }

    @Override
    public boolean hasZeroInsetsIme() {
        return mHasZeroInsetsIme;
    }

    @Override
    public Insets getHiddenStateInsets() {
        return mHiddenInsets;
    }

    @Override
    public Insets getShownStateInsets() {
        return mShownInsets;
    }

    @Override
    public Insets getCurrentInsets() {
        return mCurrentInsets;
    }

    @Override
    public float getCurrentAlpha() {
        return mCurrentAlpha;
    }

    @Override
    @InsetsType public int getTypes() {
        return mTypes;
    }

    @Override
    public @AnimationType int getAnimationType() {
        return mAnimationType;
    }

    @Override
    public void setInsetsAndAlpha(Insets insets, float alpha, float fraction) {
        setInsetsAndAlpha(insets, alpha, fraction, false /* allowWhenFinished */);
    }

    private void setInsetsAndAlpha(Insets insets, float alpha, float fraction,
            boolean allowWhenFinished) {
        if (!allowWhenFinished && mFinished) {
            throw new IllegalStateException(
                    "Can't change insets on an animation that is finished.");
        }
        if (mCancelled) {
            throw new IllegalStateException(
                    "Can't change insets on an animation that is cancelled.");
        }
        mPendingFraction = sanitize(fraction);
        mPendingInsets = sanitize(insets);
        mPendingAlpha = sanitize(alpha);
        mController.scheduleApplyChangeInsets(this);
        boolean perceptible = calculatePerceptible(mPendingInsets, mPendingAlpha);
        if (mPerceptible == null || perceptible != mPerceptible) {
            mController.reportPerceptible(mTypes, perceptible);
            mPerceptible = perceptible;
        }
    }

    @VisibleForTesting
    /**
     * @return Whether the finish callback of this animation should be invoked.
     */
    public boolean applyChangeInsets(InsetsState state) {
        if (mCancelled) {
            if (DEBUG) Log.d(TAG, "applyChangeInsets canceled");
            return false;
        }
        final Insets offset = Insets.subtract(mShownInsets, mPendingInsets);
        ArrayList<SurfaceParams> params = new ArrayList<>();
        updateLeashesForSide(ISIDE_LEFT, offset.left, mShownInsets.left, mPendingInsets.left,
                params, state, mPendingAlpha);
        updateLeashesForSide(ISIDE_TOP, offset.top, mShownInsets.top, mPendingInsets.top, params,
                state, mPendingAlpha);
        updateLeashesForSide(ISIDE_RIGHT, offset.right, mShownInsets.right, mPendingInsets.right,
                params, state, mPendingAlpha);
        updateLeashesForSide(ISIDE_BOTTOM, offset.bottom, mShownInsets.bottom,
                mPendingInsets.bottom, params, state, mPendingAlpha);

        mController.applySurfaceParams(params.toArray(new SurfaceParams[params.size()]));
        mCurrentInsets = mPendingInsets;
        mAnimation.setFraction(mPendingFraction);
        mCurrentAlpha = mPendingAlpha;
        mAnimation.setAlpha(mPendingAlpha);
        if (mFinished) {
            if (DEBUG) Log.d(TAG, String.format(
                    "notifyFinished shown: %s, currentAlpha: %f, currentInsets: %s",
                    mShownOnFinish, mCurrentAlpha, mCurrentInsets));
            mController.notifyFinished(this, mShownOnFinish);
            releaseLeashes();
        }
        if (DEBUG) Log.d(TAG, "Animation finished abruptly.");
        return mFinished;
    }

    private void releaseLeashes() {
        for (int i = mControls.size() - 1; i >= 0; i--) {
            final InsetsSourceControl c = mControls.valueAt(i);
            if (c == null) continue;
            c.release(mController::releaseSurfaceControlFromRt);
        }
    }

    @Override
    public void finish(boolean shown) {
        if (mCancelled || mFinished) {
            if (DEBUG) Log.d(TAG, "Animation already canceled or finished, not notifying.");
            return;
        }
        mShownOnFinish = shown;
        mFinished = true;
        setInsetsAndAlpha(shown ? mShownInsets : mHiddenInsets, 1f /* alpha */, 1f /* fraction */,
                true /* allowWhenFinished */);

        if (DEBUG) Log.d(TAG, "notify control request finished for types: " + mTypes);
        mListener.onFinished(this);
    }

    @Override
    @VisibleForTesting
    public float getCurrentFraction() {
        return mAnimation.getFraction();
    }

    @Override
    public void cancel() {
        if (mFinished) {
            return;
        }
        mCancelled = true;
        mListener.onCancelled(mReadyDispatched ? this : null);
        if (DEBUG) Log.d(TAG, "notify Control request cancelled for types: " + mTypes);

        releaseLeashes();
    }

    @Override
    public boolean isFinished() {
        return mFinished;
    }

    @Override
    public boolean isCancelled() {
        return mCancelled;
    }

    @Override
    public WindowInsetsAnimation getAnimation() {
        return mAnimation;
    }

    WindowInsetsAnimationControlListener getListener() {
        return mListener;
    }

    SparseArray<InsetsSourceControl> getControls() {
        return mControls;
    }

    private Insets calculateInsets(InsetsState state, Rect frame,
            SparseArray<InsetsSourceControl> controls, boolean shown,
            @Nullable @InternalInsetsSide SparseIntArray typeSideMap) {
        for (int i = controls.size() - 1; i >= 0; i--) {
            // control may be null if it got revoked.
            if (controls.valueAt(i) == null) continue;
            state.getSource(controls.valueAt(i).getType()).setVisible(shown);
        }
        return getInsetsFromState(state, frame, typeSideMap);
    }

    private Insets getInsetsFromState(InsetsState state, Rect frame,
            @Nullable @InternalInsetsSide SparseIntArray typeSideMap) {
        return state.calculateInsets(frame, null /* ignoringVisibilityState */,
                false /* isScreenRound */,
                false /* alwaysConsumeSystemBars */, null /* displayCutout */,
                LayoutParams.SOFT_INPUT_ADJUST_RESIZE /* legacySoftInputMode*/,
                0 /* legacyWindowFlags */, 0 /* legacySystemUiFlags */, typeSideMap)
               .getInsets(mTypes);
    }

    private Insets sanitize(Insets insets) {
        if (insets == null) {
            insets = getCurrentInsets();
        }
        if (hasZeroInsetsIme()) {
            return insets;
        }
        return Insets.max(Insets.min(insets, mShownInsets), mHiddenInsets);
    }

    private static float sanitize(float alpha) {
        return alpha >= 1 ? 1 : (alpha <= 0 ? 0 : alpha);
    }

    private void updateLeashesForSide(@InternalInsetsSide int side, int offset, int maxInset,
            int inset, ArrayList<SurfaceParams> surfaceParams, InsetsState state, Float alpha) {
        ArraySet<InsetsSourceControl> items = mSideSourceMap.get(side);
        if (items == null) {
            return;
        }
        // TODO: Implement behavior when inset spans over multiple types
        for (int i = items.size() - 1; i >= 0; i--) {
            final InsetsSourceControl control = items.valueAt(i);
            final InsetsSource source = mInitialInsetsState.getSource(control.getType());
            final SurfaceControl leash = control.getLeash();

            mTmpMatrix.setTranslate(control.getSurfacePosition().x, control.getSurfacePosition().y);
            mTmpFrame.set(source.getFrame());
            addTranslationToMatrix(side, offset, mTmpMatrix, mTmpFrame);

            final boolean visible = mHasZeroInsetsIme && side == ISIDE_BOTTOM
                    ? (mAnimationType == ANIMATION_TYPE_SHOW ? true : !mFinished)
                    : inset != 0;

            state.getSource(source.getType()).setVisible(visible);
            state.getSource(source.getType()).setFrame(mTmpFrame);

            // If the system is controlling the insets source, the leash can be null.
            if (leash != null) {
                SurfaceParams params = new SurfaceParams.Builder(leash)
                        .withAlpha(alpha)
                        .withMatrix(mTmpMatrix)
                        .withVisibility(visible)
                        .build();
                surfaceParams.add(params);
            }
        }
    }

    private void addTranslationToMatrix(@InternalInsetsSide int side, int inset, Matrix m,
            Rect frame) {
        switch (side) {
            case ISIDE_LEFT:
                m.postTranslate(-inset, 0);
                frame.offset(-inset, 0);
                break;
            case ISIDE_TOP:
                m.postTranslate(0, -inset);
                frame.offset(0, -inset);
                break;
            case ISIDE_RIGHT:
                m.postTranslate(inset, 0);
                frame.offset(inset, 0);
                break;
            case ISIDE_BOTTOM:
                m.postTranslate(0, inset);
                frame.offset(0, inset);
                break;
        }
    }

    private static void buildTypeSourcesMap(SparseIntArray typeSideMap,
            SparseSetArray<InsetsSourceControl> sideSourcesMap,
            SparseArray<InsetsSourceControl> controls) {
        for (int i = typeSideMap.size() - 1; i >= 0; i--) {
            final int type = typeSideMap.keyAt(i);
            final int side = typeSideMap.valueAt(i);
            final InsetsSourceControl control = controls.get(type);
            if (control == null) {
                // If the types that we are controlling are less than the types that the system has,
                // there can be some null controllers.
                continue;
            }
            sideSourcesMap.add(side, control);
        }
    }
}
