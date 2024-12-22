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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.view.EventLogTags.IMF_IME_ANIM_CANCEL;
import static android.view.EventLogTags.IMF_IME_ANIM_FINISH;
import static android.view.EventLogTags.IMF_IME_ANIM_START;
import static android.view.InsetsAnimationControlImplProto.CURRENT_ALPHA;
import static android.view.InsetsAnimationControlImplProto.IS_CANCELLED;
import static android.view.InsetsAnimationControlImplProto.IS_FINISHED;
import static android.view.InsetsAnimationControlImplProto.PENDING_ALPHA;
import static android.view.InsetsAnimationControlImplProto.PENDING_FRACTION;
import static android.view.InsetsAnimationControlImplProto.PENDING_INSETS;
import static android.view.InsetsAnimationControlImplProto.SHOWN_ON_FINISH;
import static android.view.InsetsAnimationControlImplProto.TMP_MATRIX;
import static android.view.InsetsController.ANIMATION_TYPE_SHOW;
import static android.view.InsetsController.AnimationType;
import static android.view.InsetsController.DEBUG;
import static android.view.InsetsController.LAYOUT_INSETS_DURING_ANIMATION_SHOWN;
import static android.view.InsetsController.LayoutInsetsDuringAnimation;
import static android.view.InsetsSource.ID_IME;
import static android.view.InsetsSource.SIDE_BOTTOM;
import static android.view.InsetsSource.SIDE_LEFT;
import static android.view.InsetsSource.SIDE_NONE;
import static android.view.InsetsSource.SIDE_RIGHT;
import static android.view.InsetsSource.SIDE_TOP;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.inputmethod.ImeTracker.DEBUG_IME_VISIBILITY;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.annotation.Nullable;
import android.content.res.CompatibilityInfo;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.SparseSetArray;
import android.util.proto.ProtoOutputStream;
import android.view.InsetsSource.InternalInsetsSide;
import android.view.SyncRtSurfaceTransactionApplier.SurfaceParams;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsAnimation.Bounds;
import android.view.animation.Interpolator;
import android.view.inputmethod.ImeTracker;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Implements {@link WindowInsetsAnimationController}
 * @hide
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class InsetsAnimationControlImpl implements InternalInsetsAnimationController,
        InsetsAnimationControlRunner {

    private static final String TAG = "InsetsAnimationCtrlImpl";

    private final Rect mTmpFrame = new Rect();

    private final WindowInsetsAnimationControlListener mListener;
    private final SparseArray<InsetsSourceControl> mControls;
    private final SparseSetArray<InsetsSourceControl> mSideControlsMap = new SparseSetArray<>();

    /** @see WindowInsetsAnimationController#getHiddenStateInsets */
    private final Insets mHiddenInsets;

    /** @see WindowInsetsAnimationController#getShownStateInsets */
    private final Insets mShownInsets;
    private final Matrix mTmpMatrix = new Matrix();
    private final InsetsState mInitialInsetsState;
    private final @AnimationType int mAnimationType;
    private @LayoutInsetsDuringAnimation int mLayoutInsetsDuringAnimation;
    private final @InsetsType int mTypes;
    private @InsetsType int mControllingTypes;
    private final InsetsAnimationControlCallbacks mController;
    private final SurfaceParamsApplier mSurfaceParamsApplier;
    private final WindowInsetsAnimation mAnimation;
    private final long mDurationMs;
    private final Interpolator mInterpolator;
    /** @see WindowInsetsAnimationController#hasZeroInsetsIme */
    private final boolean mHasZeroInsetsIme;
    private final CompatibilityInfo.Translator mTranslator;
    @Nullable
    private final ImeTracker.Token mStatsToken;
    private Insets mCurrentInsets;
    private Insets mPendingInsets;
    private float mPendingFraction;
    private boolean mFinished;
    private boolean mCancelled;
    private boolean mShownOnFinish;
    private float mCurrentAlpha = 1.0f;
    private float mPendingAlpha = 1.0f;
    @VisibleForTesting(visibility = PACKAGE)
    private boolean mReadyDispatched;
    private Boolean mPerceptible;

    @VisibleForTesting
    public InsetsAnimationControlImpl(SparseArray<InsetsSourceControl> controls,
            @Nullable Rect frame, InsetsState state, WindowInsetsAnimationControlListener listener,
            @InsetsType int types, InsetsAnimationControlCallbacks controller,
            SurfaceParamsApplier surfaceParamsApplier,
            InsetsAnimationSpec insetsAnimationSpec, @AnimationType int animationType,
            @LayoutInsetsDuringAnimation int layoutInsetsDuringAnimation,
            CompatibilityInfo.Translator translator, @Nullable ImeTracker.Token statsToken) {
        mControls = controls;
        mListener = listener;
        mTypes = types;
        mControllingTypes = types;
        mController = controller;
        mSurfaceParamsApplier = surfaceParamsApplier;
        mInitialInsetsState = new InsetsState(state, true /* copySources */);
        if (frame != null) {
            final SparseIntArray idSideMap = new SparseIntArray();
            mCurrentInsets = getInsetsFromState(mInitialInsetsState, frame, null /* idSideMap */);
            mHiddenInsets = calculateInsets(mInitialInsetsState, frame, controls, false /* shown */,
                    null /* idSideMap */);
            mShownInsets = calculateInsets(mInitialInsetsState, frame, controls, true /* shown */,
                    idSideMap);
            mHasZeroInsetsIme = mShownInsets.bottom == 0 && controlsType(WindowInsets.Type.ime());
            if (mHasZeroInsetsIme) {
                // IME has shownInsets of ZERO, and can't map to a side by default.
                // Map zero insets IME to bottom, making it a special case of bottom insets.
                idSideMap.put(ID_IME, SIDE_BOTTOM);
            }
            buildSideControlsMap(idSideMap, mSideControlsMap, controls);
        } else {
            // Passing a null frame indicates the caller wants to play the insets animation anyway,
            // no matter the source provides insets to the frame or not.
            mCurrentInsets = calculateInsets(mInitialInsetsState, controls, true /* shown */);
            mHiddenInsets = calculateInsets(null, controls, false /* shown */);
            mShownInsets = calculateInsets(null, controls, true /* shown */);
            mHasZeroInsetsIme = mShownInsets.bottom == 0 && controlsType(WindowInsets.Type.ime());
            buildSideControlsMap(mSideControlsMap, controls);
        }
        mPendingInsets = mCurrentInsets;

        mDurationMs = insetsAnimationSpec.getDurationMs(mHasZeroInsetsIme);
        mInterpolator = insetsAnimationSpec.getInsetsInterpolator(mHasZeroInsetsIme);

        mAnimation = new WindowInsetsAnimation(mTypes, mInterpolator, mDurationMs);
        mAnimation.setAlpha(getCurrentAlpha());
        mAnimationType = animationType;
        mLayoutInsetsDuringAnimation = layoutInsetsDuringAnimation;
        mTranslator = translator;
        mStatsToken = statsToken;
        if (DEBUG_IME_VISIBILITY && (types & ime()) != 0) {
            EventLog.writeEvent(IMF_IME_ANIM_START,
                    mStatsToken != null ? mStatsToken.getTag() : ImeTracker.TOKEN_NONE,
                    mAnimationType, mCurrentAlpha, "Current:" + mCurrentInsets,
                    "Shown:" + mShownInsets, "Hidden:" + mHiddenInsets);
        }
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
    public long getDurationMs() {
        return mDurationMs;
    }

    @Override
    public Interpolator getInsetsInterpolator() {
        return mInterpolator;
    }

    @Override
    public void setReadyDispatched(boolean dispatched) {
        mReadyDispatched = dispatched;
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
    public int getControllingTypes() {
        return mControllingTypes;
    }

    @Override
    public void notifyControlRevoked(@InsetsType int types) {
        mControllingTypes &= ~types;
    }

    @Override
    public void updateSurfacePosition(SparseArray<InsetsSourceControl> controls) {
        for (int i = controls.size() - 1; i >= 0; i--) {
            final InsetsSourceControl control = controls.valueAt(i);
            final InsetsSourceControl c = mControls.get(control.getId());
            if (c == null) {
                continue;
            }
            final Point position = control.getSurfacePosition();
            c.setSurfacePosition(position.x, position.y);
        }
    }

    @Override
    public @AnimationType int getAnimationType() {
        return mAnimationType;
    }

    @Override
    public SurfaceParamsApplier getSurfaceParamsApplier() {
        return mSurfaceParamsApplier;
    }

    @Override
    @Nullable
    public ImeTracker.Token getStatsToken() {
        return mStatsToken;
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

    /**
     * @return Whether the finish callback of this animation should be invoked.
     */
    @VisibleForTesting
    public boolean applyChangeInsets(@Nullable InsetsState outState) {
        if (mCancelled) {
            if (DEBUG) Log.d(TAG, "applyChangeInsets canceled");
            return false;
        }
        final Insets offset = Insets.subtract(mShownInsets, mPendingInsets);
        final ArrayList<SurfaceParams> params = new ArrayList<>();
        updateLeashesForSide(SIDE_LEFT, offset.left, params, outState, mPendingAlpha);
        updateLeashesForSide(SIDE_TOP, offset.top, params, outState, mPendingAlpha);
        updateLeashesForSide(SIDE_RIGHT, offset.right, params, outState, mPendingAlpha);
        updateLeashesForSide(SIDE_BOTTOM, offset.bottom, params, outState, mPendingAlpha);

        mSurfaceParamsApplier.applySurfaceParams(params.toArray(new SurfaceParams[params.size()]));
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
            if (DEBUG) Log.d(TAG, "Animation finished abruptly.");
        }
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
        final Insets insets = shown ? mShownInsets : mHiddenInsets;
        setInsetsAndAlpha(insets, mPendingAlpha, 1f /* fraction */, true /* allowWhenFinished */);

        if (DEBUG) Log.d(TAG, "notify control request finished for types: " + mTypes);
        mListener.onFinished(this);
        if (DEBUG_IME_VISIBILITY && (mTypes & ime()) != 0) {
            EventLog.writeEvent(IMF_IME_ANIM_FINISH,
                    mStatsToken != null ? mStatsToken.getTag() : ImeTracker.TOKEN_NONE,
                    mAnimationType, mCurrentAlpha, shown ? 1 : 0, Objects.toString(insets));
        }
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
        mPendingInsets = mLayoutInsetsDuringAnimation == LAYOUT_INSETS_DURING_ANIMATION_SHOWN
                ? mShownInsets : mHiddenInsets;
        mPendingAlpha = 1f;
        applyChangeInsets(null);
        mCancelled = true;
        mListener.onCancelled(mReadyDispatched ? this : null);
        if (DEBUG) Log.d(TAG, "notify Control request cancelled for types: " + mTypes);
        if (DEBUG_IME_VISIBILITY && (mTypes & ime()) != 0) {
            EventLog.writeEvent(IMF_IME_ANIM_CANCEL,
                    mStatsToken != null ? mStatsToken.getTag() : ImeTracker.TOKEN_NONE,
                    mAnimationType, Objects.toString(mPendingInsets));
        }
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

    @Override
    public void updateLayoutInsetsDuringAnimation(
            @LayoutInsetsDuringAnimation int layoutInsetsDuringAnimation) {
        mLayoutInsetsDuringAnimation = layoutInsetsDuringAnimation;
    }

    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(IS_CANCELLED, mCancelled);
        proto.write(IS_FINISHED, mFinished);
        proto.write(TMP_MATRIX, Objects.toString(mTmpMatrix));
        proto.write(PENDING_INSETS, Objects.toString(mPendingInsets));
        proto.write(PENDING_FRACTION, mPendingFraction);
        proto.write(SHOWN_ON_FINISH, mShownOnFinish);
        proto.write(CURRENT_ALPHA, mCurrentAlpha);
        proto.write(PENDING_ALPHA, mPendingAlpha);
        proto.end(token);
    }

    SparseArray<InsetsSourceControl> getControls() {
        return mControls;
    }

    private Insets getInsetsFromState(InsetsState state, Rect frame,
            @Nullable @InternalInsetsSide SparseIntArray idSideMap) {
        return state.calculateInsets(frame, null /* ignoringVisibilityState */,
                false /* isScreenRound */, SOFT_INPUT_ADJUST_RESIZE /* legacySoftInputMode */,
                0 /* legacyWindowFlags */, 0 /* legacySystemUiFlags */, TYPE_APPLICATION,
                ACTIVITY_TYPE_UNDEFINED, idSideMap).getInsets(mTypes);
    }

    /** Computes the insets relative to the given frame. */
    private Insets calculateInsets(InsetsState state, Rect frame,
            SparseArray<InsetsSourceControl> controls, boolean shown,
            @Nullable @InternalInsetsSide SparseIntArray idSideMap) {
        for (int i = controls.size() - 1; i >= 0; i--) {
            final InsetsSourceControl control  = controls.valueAt(i);
            if (control == null) {
                // control may be null if it got revoked.
                continue;
            }
            state.setSourceVisible(control.getId(), shown);
        }
        return getInsetsFromState(state, frame, idSideMap);
    }

    /** Computes the insets from the insets hints of controls. */
    private Insets calculateInsets(InsetsState state, SparseArray<InsetsSourceControl> controls,
            boolean shownOrCurrent) {
        Insets insets = Insets.NONE;
        if (!shownOrCurrent) {
            return insets;
        }
        for (int i = controls.size() - 1; i >= 0; i--) {
            final InsetsSourceControl control  = controls.valueAt(i);
            if (control == null) {
                // control may be null if it got revoked.
                continue;
            }
            if (state == null
                    || state.isSourceOrDefaultVisible(control.getId(), control.getType())) {
                insets = Insets.max(insets, control.getInsetsHint());
            }
        }
        return insets;
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

    private void updateLeashesForSide(@InternalInsetsSide int side, int offset,
            ArrayList<SurfaceParams> surfaceParams, @Nullable InsetsState outState, float alpha) {
        final ArraySet<InsetsSourceControl> controls = mSideControlsMap.get(side);
        if (controls == null) {
            return;
        }
        // TODO: Implement behavior when inset spans over multiple types
        for (int i = controls.size() - 1; i >= 0; i--) {
            final InsetsSourceControl control = controls.valueAt(i);
            final InsetsSource source = mInitialInsetsState.peekSource(control.getId());
            final SurfaceControl leash = control.getLeash();

            mTmpMatrix.setTranslate(control.getSurfacePosition().x, control.getSurfacePosition().y);
            if (source != null) {
                mTmpFrame.set(source.getFrame());
            }
            addTranslationToMatrix(side, offset, mTmpMatrix, mTmpFrame);

            // The first frame of ANIMATION_TYPE_SHOW should be invisible since it is animated from
            // the hidden state.
            final boolean visible = mPendingFraction == 0
                    ? mAnimationType != ANIMATION_TYPE_SHOW
                    : !mFinished || mShownOnFinish;

            if (outState != null && source != null) {
                outState.addSource(new InsetsSource(source)
                        .setVisible(visible)
                        .setFrame(mTmpFrame));
            }

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

    private void addTranslationToMatrix(@InternalInsetsSide int side, int offset, Matrix m,
            Rect frame) {
        final float surfaceOffset = mTranslator != null
                ? mTranslator.translateLengthInAppWindowToScreen(offset) : offset;
        switch (side) {
            case SIDE_LEFT:
                m.postTranslate(-surfaceOffset, 0);
                frame.offset(-offset, 0);
                break;
            case SIDE_TOP:
                m.postTranslate(0, -surfaceOffset);
                frame.offset(0, -offset);
                break;
            case SIDE_RIGHT:
                m.postTranslate(surfaceOffset, 0);
                frame.offset(offset, 0);
                break;
            case SIDE_BOTTOM:
                m.postTranslate(0, surfaceOffset);
                frame.offset(0, offset);
                break;
        }
    }

    private static void buildSideControlsMap(SparseIntArray idSideMap,
            SparseSetArray<InsetsSourceControl> sideControlsMap,
            SparseArray<InsetsSourceControl> controls) {
        for (int i = idSideMap.size() - 1; i >= 0; i--) {
            final int type = idSideMap.keyAt(i);
            final int side = idSideMap.valueAt(i);
            final InsetsSourceControl control = controls.get(type);
            if (control == null) {
                // If the types that we are controlling are less than the types that the system has,
                // there can be some null controllers.
                continue;
            }
            sideControlsMap.add(side, control);
        }
    }

    private static void buildSideControlsMap(
            SparseSetArray<InsetsSourceControl> sideControlsMap,
            SparseArray<InsetsSourceControl> controls) {
        for (int i = controls.size() - 1; i >= 0; i--) {
            final InsetsSourceControl control  = controls.valueAt(i);
            if (control == null) {
                // control may be null if it got revoked.
                continue;
            }
            @InternalInsetsSide int side = InsetsSource.getInsetSide(control.getInsetsHint());
            if (side == SIDE_NONE && control.getType() == WindowInsets.Type.ime()) {
                // IME might not provide insets when it is fullscreen or floating.
                side = SIDE_BOTTOM;
            }
            sideControlsMap.add(side, control);
        }
    }
}
