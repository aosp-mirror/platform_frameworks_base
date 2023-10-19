/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_DIMMER;
import static com.android.server.wm.AlphaAnimationSpecProto.DURATION_MS;
import static com.android.server.wm.AlphaAnimationSpecProto.FROM;
import static com.android.server.wm.AlphaAnimationSpecProto.TO;
import static com.android.server.wm.AnimationSpecProto.ALPHA;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_DIMMER;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.graphics.Rect;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;

import java.io.PrintWriter;

class SmoothDimmer extends Dimmer {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "Dimmer" : TAG_WM;
    private static final float EPSILON = 0.0001f;
    // This is in milliseconds.
    private static final int DEFAULT_DIM_ANIM_DURATION = 200;
    DimState mDimState;
    private WindowContainer mLastRequestedDimContainer;
    private final AnimationAdapterFactory mAnimationAdapterFactory;

    @VisibleForTesting
    class DimState {
        /**
         * The layer where property changes should be invoked on.
         */
        SurfaceControl mDimLayer;
        boolean mDimming;
        boolean mIsVisible;

        // TODO(b/64816140): Remove after confirming dimmer layer always matches its container.
        final Rect mDimBounds = new Rect();

        /**
         * Determines whether the dim layer should animate before destroying.
         */
        boolean mAnimateExit = true;

        /**
         * Used for Dims not associated with a WindowContainer.
         * See {@link Dimmer#adjustRelativeLayer(WindowContainer, int)} for details on Dim
         * lifecycle.
         */
        boolean mDontReset;

        Change mCurrentProperties;
        Change mRequestedProperties;
        private AnimationSpec mAlphaAnimationSpec;
        private AnimationAdapter mLocalAnimationAdapter;

        static class Change {
            private float mAlpha = -1f;
            private int mBlurRadius = -1;
            private WindowContainer mDimmingContainer = null;
            private int mRelativeLayer = -1;
            private boolean mSkipAnimation = false;

            Change() {}

            Change(Change other) {
                mAlpha = other.mAlpha;
                mBlurRadius = other.mBlurRadius;
                mDimmingContainer = other.mDimmingContainer;
                mRelativeLayer = other.mRelativeLayer;
            }

            @Override
            public String toString() {
                return "Dim state: alpha=" + mAlpha + ", blur=" + mBlurRadius + ", container="
                        + mDimmingContainer + ", relativePosition=" + mRelativeLayer
                        + ", skipAnimation=" + mSkipAnimation;
            }
        }

        DimState(SurfaceControl dimLayer) {
            mDimLayer = dimLayer;
            mDimming = true;
            mCurrentProperties = new Change();
            mRequestedProperties = new Change();
        }

        void setExitParameters(WindowContainer container) {
            setRequestedRelativeParent(container, -1 /* relativeLayer */);
            setRequestedAppearance(0f /* alpha */, 0 /* blur */);
        }

        // Sets a requested change without applying it immediately
        void setRequestedRelativeParent(WindowContainer relativeParent, int relativeLayer) {
            mRequestedProperties.mDimmingContainer = relativeParent;
            mRequestedProperties.mRelativeLayer = relativeLayer;
        }

        // Sets a requested change without applying it immediately
        void setRequestedAppearance(float alpha, int blurRadius) {
            mRequestedProperties.mAlpha = alpha;
            mRequestedProperties.mBlurRadius = blurRadius;
        }

        /**
         * Commit the last changes we received. Called after
         * {@link Change#setExitParameters(WindowContainer)},
         * {@link Change#setRequestedRelativeParent(WindowContainer, int)}, or
         * {@link Change#setRequestedAppearance(float, int)}
         */
        void applyChanges(SurfaceControl.Transaction t) {
            if (mRequestedProperties.mDimmingContainer == null) {
                Log.e(TAG, this + " does not have a dimming container. Have you forgotten to "
                        + "call adjustRelativeLayer?");
                return;
            }
            if (mRequestedProperties.mDimmingContainer.mSurfaceControl == null) {
                Log.w(TAG, "container " + mRequestedProperties.mDimmingContainer
                        + "does not have a surface");
                return;
            }
            if (!mDimState.mIsVisible) {
                mDimState.mIsVisible = true;
                t.show(mDimState.mDimLayer);
            }
            t.setRelativeLayer(mDimLayer,
                    mRequestedProperties.mDimmingContainer.getSurfaceControl(),
                    mRequestedProperties.mRelativeLayer);

            if (aspectChanged()) {
                if (isAnimating()) {
                    mLocalAnimationAdapter.onAnimationCancelled(mDimLayer);
                }
                if (mRequestedProperties.mSkipAnimation
                        || (!dimmingContainerChanged() && mDimming)) {
                    // If the dimming container has not changed, then it is running its own
                    // animation, thus we can directly set the values we get requested, unless it's
                    // the exiting animation
                    ProtoLog.d(WM_DEBUG_DIMMER,
                            "Dim %s skipping animation and directly setting alpha=%f, blur=%d",
                            mDimLayer, mRequestedProperties.mAlpha,
                            mRequestedProperties.mBlurRadius);
                    t.setAlpha(mDimLayer, mRequestedProperties.mAlpha);
                    t.setBackgroundBlurRadius(mDimLayer, mRequestedProperties.mBlurRadius);
                    mRequestedProperties.mSkipAnimation = false;
                } else {
                    startAnimation(t);
                }
            }
            mCurrentProperties = new Change(mRequestedProperties);
        }

        private void startAnimation(SurfaceControl.Transaction t) {
            mAlphaAnimationSpec = getRequestedAnimationSpec(mRequestedProperties.mAlpha,
                    mRequestedProperties.mBlurRadius);
            mLocalAnimationAdapter = mAnimationAdapterFactory.get(mAlphaAnimationSpec,
                    mHost.mWmService.mSurfaceAnimationRunner);

            mLocalAnimationAdapter.startAnimation(mDimLayer, t,
                    ANIMATION_TYPE_DIMMER, (type, animator) -> {
                        t.setAlpha(mDimLayer, mRequestedProperties.mAlpha);
                        t.setBackgroundBlurRadius(mDimLayer, mRequestedProperties.mBlurRadius);
                        if (mRequestedProperties.mAlpha == 0f && !mDimming) {
                            ProtoLog.d(WM_DEBUG_DIMMER,
                                    "Removing dim surface %s on transaction %s", mDimLayer, t);
                            t.remove(mDimLayer);
                        }
                        mLocalAnimationAdapter = null;
                        mAlphaAnimationSpec = null;
                    });
        }

        private boolean isAnimating() {
            return mAlphaAnimationSpec != null;
        }

        private boolean aspectChanged() {
            return Math.abs(mRequestedProperties.mAlpha - mCurrentProperties.mAlpha) > EPSILON
                    || mRequestedProperties.mBlurRadius != mCurrentProperties.mBlurRadius;
        }

        private boolean dimmingContainerChanged() {
            return mRequestedProperties.mDimmingContainer != mCurrentProperties.mDimmingContainer;
        }

        private AnimationSpec getRequestedAnimationSpec(float targetAlpha, int targetBlur) {
            final float startAlpha;
            final int startBlur;
            if (mAlphaAnimationSpec != null) {
                startAlpha = mAlphaAnimationSpec.mCurrentAlpha;
                startBlur = mAlphaAnimationSpec.mCurrentBlur;
            } else {
                startAlpha = Math.max(mCurrentProperties.mAlpha, 0f);
                startBlur = Math.max(mCurrentProperties.mBlurRadius, 0);
            }
            long duration = (long) (getDimDuration(mRequestedProperties.mDimmingContainer)
                    * Math.abs(targetAlpha - startAlpha));

            ProtoLog.v(WM_DEBUG_DIMMER, "Starting animation on dim layer %s, requested by %s, "
                            + "alpha: %f -> %f, blur: %d -> %d",
                    mDimLayer, mRequestedProperties.mDimmingContainer, startAlpha, targetAlpha,
                    startBlur, targetBlur);
            return new AnimationSpec(
                    new AnimationExtremes<>(startAlpha, targetAlpha),
                    new AnimationExtremes<>(startBlur, targetBlur),
                    duration
            );
        }
    }

    protected SmoothDimmer(WindowContainer host) {
        this(host, new AnimationAdapterFactory());
    }

    @VisibleForTesting
    SmoothDimmer(WindowContainer host, AnimationAdapterFactory animationFactory) {
        super(host);
        mAnimationAdapterFactory = animationFactory;
    }

    private DimState obtainDimState(WindowContainer container) {
        if (mDimState == null) {
            try {
                final SurfaceControl ctl = makeDimLayer();
                mDimState = new DimState(ctl);
            } catch (Surface.OutOfResourcesException e) {
                Log.w(TAG, "OutOfResourcesException creating dim surface");
            }
        }

        mLastRequestedDimContainer = container;
        return mDimState;
    }

    private SurfaceControl makeDimLayer() {
        return mHost.makeChildSurface(null)
                .setParent(mHost.getSurfaceControl())
                .setColorLayer()
                .setName("Dim Layer for - " + mHost.getName())
                .setCallsite("Dimmer.makeDimLayer")
                .build();
    }

    @Override
    SurfaceControl getDimLayer() {
        return mDimState != null ? mDimState.mDimLayer : null;
    }

    @Override
    void resetDimStates() {
        if (mDimState == null) {
            return;
        }
        if (!mDimState.mDontReset) {
            mDimState.mDimming = false;
        }
    }

    @Override
    Rect getDimBounds() {
        return mDimState != null ? mDimState.mDimBounds : null;
    }

    @Override
    void dontAnimateExit() {
        if (mDimState != null) {
            mDimState.mAnimateExit = false;
        }
    }

    @Override
    protected void adjustAppearance(WindowContainer container, float alpha, int blurRadius) {
        final DimState d = obtainDimState(container);
        mDimState.setRequestedAppearance(alpha, blurRadius);
        d.mDimming = true;
    }

    @Override
    protected void adjustRelativeLayer(WindowContainer container, int relativeLayer) {
        if (mDimState != null) {
            mDimState.setRequestedRelativeParent(container, relativeLayer);
        }
    }

    boolean updateDims(SurfaceControl.Transaction t) {
        if (mDimState == null) {
            return false;
        }

        if (!mDimState.mDimming) {
            // No one is dimming anymore, fade out dim and remove
            if (!mDimState.mAnimateExit) {
                if (mDimState.mDimLayer.isValid()) {
                    t.remove(mDimState.mDimLayer);
                }
            } else {
                mDimState.setExitParameters(
                        mDimState.mRequestedProperties.mDimmingContainer);
                mDimState.applyChanges(t);
            }
            mDimState = null;
            return false;
        }
        final Rect bounds = mDimState.mDimBounds;
        // TODO: Once we use geometry from hierarchy this falls away.
        t.setPosition(mDimState.mDimLayer, bounds.left, bounds.top);
        t.setWindowCrop(mDimState.mDimLayer, bounds.width(), bounds.height());
        // Skip enter animation while starting window is on top of its activity
        final WindowState ws = mLastRequestedDimContainer.asWindowState();
        if (!mDimState.mIsVisible && ws != null && ws.mActivityRecord != null
                && ws.mActivityRecord.mStartingData != null) {
            mDimState.mRequestedProperties.mSkipAnimation = true;
        }
        mDimState.applyChanges(t);
        return true;
    }

    private long getDimDuration(WindowContainer container) {
        // Use the same duration as the animation on the WindowContainer
        AnimationAdapter animationAdapter = container.mSurfaceAnimator.getAnimation();
        final float durationScale = container.mWmService.getTransitionAnimationScaleLocked();
        return animationAdapter == null ? (long) (DEFAULT_DIM_ANIM_DURATION * durationScale)
                : animationAdapter.getDurationHint();
    }

    private static class AnimationExtremes<T> {
        final T mStartValue;
        final T mFinishValue;

        AnimationExtremes(T fromValue, T toValue) {
            mStartValue = fromValue;
            mFinishValue = toValue;
        }
    }

    private static class AnimationSpec implements LocalAnimationAdapter.AnimationSpec {
        private final long mDuration;
        private final AnimationExtremes<Float> mAlpha;
        private final AnimationExtremes<Integer> mBlur;

        float mCurrentAlpha = 0;
        int mCurrentBlur = 0;

        AnimationSpec(AnimationExtremes<Float> alpha,
                AnimationExtremes<Integer> blur, long duration) {
            mAlpha = alpha;
            mBlur = blur;
            mDuration = duration;
        }

        @Override
        public long getDuration() {
            return mDuration;
        }

        @Override
        public void apply(SurfaceControl.Transaction t, SurfaceControl sc, long currentPlayTime) {
            final float fraction = getFraction(currentPlayTime);
            mCurrentAlpha =
                    fraction * (mAlpha.mFinishValue - mAlpha.mStartValue) + mAlpha.mStartValue;
            mCurrentBlur =
                    (int) fraction * (mBlur.mFinishValue - mBlur.mStartValue) + mBlur.mStartValue;
            t.setAlpha(sc, mCurrentAlpha);
            t.setBackgroundBlurRadius(sc, mCurrentBlur);
        }

        @Override
        public void dump(PrintWriter pw, String prefix) {
            pw.print(prefix); pw.print("from_alpha="); pw.print(mAlpha.mStartValue);
            pw.print(" to_alpha="); pw.print(mAlpha.mFinishValue);
            pw.print(prefix); pw.print("from_blur="); pw.print(mBlur.mStartValue);
            pw.print(" to_blur="); pw.print(mBlur.mFinishValue);
            pw.print(" duration="); pw.println(mDuration);
        }

        @Override
        public void dumpDebugInner(ProtoOutputStream proto) {
            final long token = proto.start(ALPHA);
            proto.write(FROM, mAlpha.mStartValue);
            proto.write(TO, mAlpha.mFinishValue);
            proto.write(DURATION_MS, mDuration);
            proto.end(token);
        }
    }

    static class AnimationAdapterFactory {

        public AnimationAdapter get(LocalAnimationAdapter.AnimationSpec alphaAnimationSpec,
                SurfaceAnimationRunner runner) {
            return new LocalAnimationAdapter(alphaAnimationSpec, runner);
        }
    }
}
