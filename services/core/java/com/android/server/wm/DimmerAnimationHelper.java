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

import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl;

import com.android.internal.protolog.common.ProtoLog;

import java.io.PrintWriter;

/**
 * Contains the information relative to the changes to apply to the dim layer
 */
public class DimmerAnimationHelper {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "DimmerAnimationHelper" : TAG_WM;
    private static final int DEFAULT_DIM_ANIM_DURATION_MS = 200;

    /**
     * Contains the requested changes
     */
    static class Change {
        private float mAlpha = -1f;
        private int mBlurRadius = -1;
        private WindowContainer mDimmingContainer = null;
        private int mRelativeLayer = -1;
        private static final float EPSILON = 0.0001f;

        Change() {}

        Change(Change other) {
            mAlpha = other.mAlpha;
            mBlurRadius = other.mBlurRadius;
            mDimmingContainer = other.mDimmingContainer;
            mRelativeLayer = other.mRelativeLayer;
        }

        // Same alpha and blur
        boolean hasSameVisualProperties(Change other) {
            return Math.abs(mAlpha - other.mAlpha) < EPSILON && mBlurRadius == other.mBlurRadius;
        }

        boolean hasSameDimmingContainer(Change other) {
            return mDimmingContainer != null && mDimmingContainer == other.mDimmingContainer;
        }

        void inheritPropertiesFromAnimation(AnimationSpec anim) {
            mAlpha = anim.mCurrentAlpha;
            mBlurRadius = anim.mCurrentBlur;
        }

        @Override
        public String toString() {
            return "Dim state: alpha=" + mAlpha + ", blur=" + mBlurRadius + ", container="
                    + mDimmingContainer + ", relativePosition=" + mRelativeLayer;
        }
    }

    private Change mCurrentProperties = new Change();
    private Change mRequestedProperties = new Change();
    private AnimationSpec mAlphaAnimationSpec;

    private final AnimationAdapterFactory mAnimationAdapterFactory;
    private AnimationAdapter mLocalAnimationAdapter;

    DimmerAnimationHelper(AnimationAdapterFactory animationFactory) {
        mAnimationAdapterFactory = animationFactory;
    }

    void setExitParameters() {
        setRequestedRelativeParent(mRequestedProperties.mDimmingContainer, -1 /* relativeLayer */);
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
     * {@link Change#setExitParameters()},
     * {@link Change#setRequestedRelativeParent(WindowContainer, int)}, or
     * {@link Change#setRequestedAppearance(float, int)}
     */
    void applyChanges(SurfaceControl.Transaction t, SmoothDimmer.DimState dim) {
        if (mRequestedProperties.mDimmingContainer == null) {
            Log.e(TAG, this + " does not have a dimming container. Have you forgotten to "
                    + "call adjustRelativeLayer?");
            return;
        }
        if (mRequestedProperties.mDimmingContainer.mSurfaceControl == null) {
            Log.w(TAG, "container " + mRequestedProperties.mDimmingContainer
                    + "does not have a surface");
            dim.remove(t);
            return;
        }

        dim.ensureVisible(t);
        relativeReparent(dim.mDimSurface,
                mRequestedProperties.mDimmingContainer.getSurfaceControl(),
                mRequestedProperties.mRelativeLayer, t);

        if (!mCurrentProperties.hasSameVisualProperties(mRequestedProperties)) {
            stopCurrentAnimation(dim.mDimSurface);

            if (dim.mSkipAnimation
                    // If the container doesn't change but requests a dim change, then it is
                    // directly providing us the animated values
                    || (mRequestedProperties.hasSameDimmingContainer(mCurrentProperties)
                    && dim.isDimming())) {
                ProtoLog.d(WM_DEBUG_DIMMER,
                        "%s skipping animation and directly setting alpha=%f, blur=%d",
                        dim, mRequestedProperties.mAlpha,
                        mRequestedProperties.mBlurRadius);
                setAlphaBlur(dim.mDimSurface, mRequestedProperties.mAlpha,
                        mRequestedProperties.mBlurRadius, t);
                dim.mSkipAnimation = false;
            } else {
                startAnimation(t, dim);
            }

        } else if (!dim.isDimming()) {
            // We are not dimming, so we tried the exit animation but the alpha is already 0,
            // therefore, let's just remove this surface
            dim.remove(t);
        }
        mCurrentProperties = new Change(mRequestedProperties);
    }

    private void startAnimation(
            SurfaceControl.Transaction t, SmoothDimmer.DimState dim) {
        ProtoLog.v(WM_DEBUG_DIMMER, "Starting animation on %s", dim);
        mAlphaAnimationSpec = getRequestedAnimationSpec();
        mLocalAnimationAdapter = mAnimationAdapterFactory.get(mAlphaAnimationSpec,
                dim.mHostContainer.mWmService.mSurfaceAnimationRunner);

        float targetAlpha = mRequestedProperties.mAlpha;
        int targetBlur = mRequestedProperties.mBlurRadius;

        mLocalAnimationAdapter.startAnimation(dim.mDimSurface, t,
                ANIMATION_TYPE_DIMMER, /* finishCallback */ (type, animator) -> {
                    synchronized (dim.mHostContainer.mWmService.mGlobalLock) {
                        setAlphaBlur(dim.mDimSurface, targetAlpha, targetBlur, t);
                        if (targetAlpha == 0f && !dim.isDimming()) {
                            dim.remove(t);
                        }
                        mLocalAnimationAdapter = null;
                        mAlphaAnimationSpec = null;
                    }
                });
    }

    private boolean isAnimating() {
        return mAlphaAnimationSpec != null;
    }

    void stopCurrentAnimation(SurfaceControl surface) {
        if (mLocalAnimationAdapter != null && isAnimating()) {
            // Save the current animation progress and cancel the animation
            mCurrentProperties.inheritPropertiesFromAnimation(mAlphaAnimationSpec);
            mLocalAnimationAdapter.onAnimationCancelled(surface);
            mLocalAnimationAdapter = null;
            mAlphaAnimationSpec = null;
        }
    }

    private AnimationSpec getRequestedAnimationSpec() {
        final float startAlpha = Math.max(mCurrentProperties.mAlpha, 0f);
        final int startBlur = Math.max(mCurrentProperties.mBlurRadius, 0);
        long duration = (long) (getDimDuration(mRequestedProperties.mDimmingContainer)
                * Math.abs(mRequestedProperties.mAlpha - startAlpha));

        final AnimationSpec spec =  new AnimationSpec(
                new AnimationSpec.AnimationExtremes<>(startAlpha, mRequestedProperties.mAlpha),
                new AnimationSpec.AnimationExtremes<>(startBlur, mRequestedProperties.mBlurRadius),
                duration
        );
        ProtoLog.v(WM_DEBUG_DIMMER, "Dim animation requested: %s", spec);
        return spec;
    }

    /**
     * Change the relative parent of this dim layer
     */
    void relativeReparent(SurfaceControl dimLayer, SurfaceControl relativeParent,
                          int relativePosition, SurfaceControl.Transaction t) {
        try {
            t.setRelativeLayer(dimLayer, relativeParent, relativePosition);
        } catch (NullPointerException e) {
            Log.w(TAG, "Tried to change parent of dim " + dimLayer + " after remove", e);
        }
    }

    void setAlphaBlur(SurfaceControl sc, float alpha, int blur, SurfaceControl.Transaction t) {
        try {
            t.setAlpha(sc, alpha);
            t.setBackgroundBlurRadius(sc, blur);
        } catch (NullPointerException e) {
            Log.w(TAG , "Tried to change look of dim " + sc + " after remove",  e);
        }
    }

    private long getDimDuration(WindowContainer container) {
        // Use the same duration as the animation on the WindowContainer
        AnimationAdapter animationAdapter = container.mSurfaceAnimator.getAnimation();
        final float durationScale = container.mWmService.getTransitionAnimationScaleLocked();
        return animationAdapter == null ? (long) (DEFAULT_DIM_ANIM_DURATION_MS * durationScale)
                : animationAdapter.getDurationHint();
    }

    /**
     * Collects the animation specifics
     */
    static class AnimationSpec implements LocalAnimationAdapter.AnimationSpec {
        private static final String TAG = TAG_WITH_CLASS_NAME ? "DimmerAnimationSpec" : TAG_WM;

        static class AnimationExtremes<T> {
            final T mStartValue;
            final T mFinishValue;

            AnimationExtremes(T fromValue, T toValue) {
                mStartValue = fromValue;
                mFinishValue = toValue;
            }

            @Override
            public String toString() {
                return "[" + mStartValue + "->" + mFinishValue + "]";
            }
        }

        private final long mDuration;
        private final AnimationSpec.AnimationExtremes<Float> mAlpha;
        private final AnimationSpec.AnimationExtremes<Integer> mBlur;

        float mCurrentAlpha = 0;
        int mCurrentBlur = 0;
        boolean mStarted = false;

        AnimationSpec(AnimationSpec.AnimationExtremes<Float> alpha,
                      AnimationSpec.AnimationExtremes<Integer> blur, long duration) {
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
            if (!mStarted) {
                // The first frame would end up in the sync transaction, and since this could be
                // applied after the animation transaction, we avoid putting visible changes here.
                // The initial state of the animation matches the current state of the dim anyway.
                mStarted = true;
                return;
            }
            final float fraction = getFraction(currentPlayTime);
            mCurrentAlpha =
                    fraction * (mAlpha.mFinishValue - mAlpha.mStartValue) + mAlpha.mStartValue;
            mCurrentBlur =
                    (int) fraction * (mBlur.mFinishValue - mBlur.mStartValue) + mBlur.mStartValue;
            if (sc.isValid()) {
                t.setAlpha(sc, mCurrentAlpha);
                t.setBackgroundBlurRadius(sc, mCurrentBlur);
            } else {
                Log.w(TAG, "Dimmer#AnimationSpec tried to access " + sc + " after release");
            }
        }

        @Override
        public String toString() {
            return "Animation spec: alpha=" + mAlpha + ", blur=" + mBlur;
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
