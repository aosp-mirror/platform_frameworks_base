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

import static android.view.InsetsState.ISIDE_BOTTOM;
import static android.view.InsetsState.ISIDE_FLOATING;
import static android.view.InsetsState.ISIDE_LEFT;
import static android.view.InsetsState.ISIDE_RIGHT;
import static android.view.InsetsState.ISIDE_TOP;

import android.annotation.Nullable;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.SparseSetArray;
import android.view.InsetsState.InternalInsetsSide;
import android.view.SyncRtSurfaceTransactionApplier.SurfaceParams;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsAnimationCallback.AnimationBounds;
import android.view.WindowInsetsAnimationCallback.InsetsAnimation;
import android.view.WindowManager.LayoutParams;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;

/**
 * Implements {@link WindowInsetsAnimationController}
 * @hide
 */
@VisibleForTesting
public class InsetsAnimationControlImpl implements WindowInsetsAnimationController  {

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
    private final @InsetsType int mTypes;
    private final InsetsAnimationControlCallbacks mController;
    private final WindowInsetsAnimationCallback.InsetsAnimation mAnimation;
    private final Rect mFrame;
    private final boolean mFade;
    private Insets mCurrentInsets;
    private Insets mPendingInsets;
    private float mPendingFraction;
    private boolean mFinished;
    private boolean mCancelled;
    private boolean mShownOnFinish;
    private float mCurrentAlpha;
    private float mPendingAlpha;

    @VisibleForTesting
    public InsetsAnimationControlImpl(SparseArray<InsetsSourceControl> controls, Rect frame,
            InsetsState state, WindowInsetsAnimationControlListener listener,
            @InsetsType int types,
            InsetsAnimationControlCallbacks controller, long durationMs, boolean fade) {
        mControls = controls;
        mListener = listener;
        mTypes = types;
        mFade = fade;
        mController = controller;
        mInitialInsetsState = new InsetsState(state, true /* copySources */);
        mCurrentInsets = getInsetsFromState(mInitialInsetsState, frame, null /* typeSideMap */);
        mHiddenInsets = calculateInsets(mInitialInsetsState, frame, controls, false /* shown */,
                null /* typeSideMap */);
        mShownInsets = calculateInsets(mInitialInsetsState, frame, controls, true /* shown */,
                mTypeSideMap);
        mFrame = new Rect(frame);
        buildTypeSourcesMap(mTypeSideMap, mSideSourceMap, mControls);

        // TODO: Check for controllability first and wait for IME if needed.
        listener.onReady(this, types);

        mAnimation = new WindowInsetsAnimationCallback.InsetsAnimation(mTypes,
                InsetsController.INTERPOLATOR, durationMs);
        mAnimation.setAlpha(getCurrentAlpha());
        mController.dispatchAnimationStarted(mAnimation,
                new AnimationBounds(mHiddenInsets, mShownInsets));
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
    public void setInsetsAndAlpha(Insets insets, float alpha, float fraction) {
        if (mFinished) {
            throw new IllegalStateException(
                    "Can't change insets on an animation that is finished.");
        }
        if (mCancelled) {
            throw new IllegalStateException(
                    "Can't change insets on an animation that is cancelled.");
        }
        mPendingFraction = sanitize(fraction);
        mPendingInsets = sanitize(insets);
        mPendingAlpha = 1 - sanitize(alpha);
        mController.scheduleApplyChangeInsets();
    }

    @VisibleForTesting
    /**
     * @return Whether the finish callback of this animation should be invoked.
     */
    public boolean applyChangeInsets(InsetsState state) {
        if (mCancelled) {
            return false;
        }
        final Insets offset = Insets.subtract(mShownInsets, mPendingInsets);
        final Float alphaOffset = 1 - mPendingAlpha;
        ArrayList<SurfaceParams> params = new ArrayList<>();
        updateLeashesForSide(ISIDE_LEFT, offset.left, mShownInsets.left, mPendingInsets.left,
                params, state, alphaOffset);
        updateLeashesForSide(ISIDE_TOP, offset.top, mShownInsets.top, mPendingInsets.top, params,
                state, alphaOffset);
        updateLeashesForSide(ISIDE_RIGHT, offset.right, mShownInsets.right, mPendingInsets.right,
                params, state, alphaOffset);
        updateLeashesForSide(ISIDE_BOTTOM, offset.bottom, mShownInsets.bottom,
                mPendingInsets.bottom, params, state, alphaOffset);
        updateLeashesForSide(ISIDE_FLOATING, 0 /* offset */, 0 /* inset */, 0 /* maxInset */,
                params, state, alphaOffset);

        mController.applySurfaceParams(params.toArray(new SurfaceParams[params.size()]));
        mCurrentInsets = mPendingInsets;
        mAnimation.setFraction(mPendingFraction);
        mCurrentAlpha = 1 - alphaOffset;
        if (mFinished) {
            mController.notifyFinished(this, mShownOnFinish);
        }
        return mFinished;
    }

    @Override
    public void finish(boolean shown) {
        if (mCancelled) {
            return;
        }
        InsetsState state = new InsetsState(mController.getState());
        for (int i = mControls.size() - 1; i >= 0; i--) {
            InsetsSourceControl control = mControls.valueAt(i);
            state.getSource(control.getType()).setVisible(shown);
        }
        Insets insets = getInsetsFromState(state, mFrame, null /* typeSideMap */);
        setInsetsAndAlpha(insets, 1f /* alpha */, shown ? 1f : 0f /* fraction */);
        mFinished = true;
        mShownOnFinish = shown;
    }

    @Override
    @VisibleForTesting
    public float getCurrentFraction() {
        return mAnimation.getFraction();
    }

    public void onCancelled() {
        if (mFinished) {
            return;
        }
        mCancelled = true;
        mListener.onCancelled();
    }

    InsetsAnimation getAnimation() {
        return mAnimation;
    }

    WindowInsetsAnimationControlListener getListener() {
        return mListener;
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
        return state.calculateInsets(frame, false /* isScreenRound */,
                false /* alwaysConsumeSystemBars */, null /* displayCutout */,
                null /* legacyContentInsets */, null /* legacyStableInsets */,
                LayoutParams.SOFT_INPUT_ADJUST_RESIZE /* legacySoftInputMode*/, typeSideMap)
               .getInsets(mTypes);
    }

    private Insets sanitize(Insets insets) {
        if (insets == null) {
            insets = getCurrentInsets();
        }
        return Insets.max(Insets.min(insets, mShownInsets), mHiddenInsets);
    }

    private static float sanitize(float alpha) {
        return alpha >= 1 ? 1 : (alpha <= 0 ? 0 : alpha);
    }

    private void updateLeashesForSide(@InternalInsetsSide int side, int offset, int inset,
            int maxInset, ArrayList<SurfaceParams> surfaceParams, InsetsState state, Float alpha) {
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

            state.getSource(source.getType()).setFrame(mTmpFrame);

            // If the system is controlling the insets source, the leash can be null.
            if (leash != null) {
                // TODO: use a better interpolation for fade.
                alpha = mFade ? ((float) maxInset / inset * 0.3f + 0.7f) : alpha;
                surfaceParams.add(new SurfaceParams(leash, alpha, mTmpMatrix,
                        null /* windowCrop */, 0 /* layer */, 0f /* cornerRadius*/,
                        side == ISIDE_FLOATING ? state.getSource(source.getType()).isVisible()
                                : inset != 0 /* visible */));
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
