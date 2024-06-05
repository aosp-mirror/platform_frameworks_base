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

import java.io.PrintWriter;

public class LegacyDimmer extends Dimmer {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "Dimmer" : TAG_WM;
    // This is in milliseconds.
    private static final int DEFAULT_DIM_ANIM_DURATION = 200;
    DimState mDimState;
    private WindowContainer mLastRequestedDimContainer;
    private final SurfaceAnimatorStarter mSurfaceAnimatorStarter;

    private class DimAnimatable implements SurfaceAnimator.Animatable {
        private SurfaceControl mDimLayer;

        private DimAnimatable(SurfaceControl dimLayer) {
            mDimLayer = dimLayer;
        }

        @Override
        public SurfaceControl.Transaction getSyncTransaction() {
            return mHost.getSyncTransaction();
        }

        @Override
        public SurfaceControl.Transaction getPendingTransaction() {
            return mHost.getPendingTransaction();
        }

        @Override
        public void commitPendingTransaction() {
            mHost.commitPendingTransaction();
        }

        @Override
        public void onAnimationLeashCreated(SurfaceControl.Transaction t, SurfaceControl leash) {
        }

        @Override
        public void onAnimationLeashLost(SurfaceControl.Transaction t) {
        }

        @Override
        public SurfaceControl.Builder makeAnimationLeash() {
            return mHost.makeAnimationLeash();
        }

        @Override
        public SurfaceControl getAnimationLeashParent() {
            return mHost.getSurfaceControl();
        }

        @Override
        public SurfaceControl getSurfaceControl() {
            return mDimLayer;
        }

        @Override
        public SurfaceControl getParentSurfaceControl() {
            return mHost.getSurfaceControl();
        }

        @Override
        public int getSurfaceWidth() {
            // This will determine the size of the leash created. This should be the size of the
            // host and not the dim layer since the dim layer may get bigger during animation. If
            // that occurs, the leash size cannot change so we need to ensure the leash is big
            // enough that the dim layer can grow.
            // This works because the mHost will be a Task which has the display bounds.
            return mHost.getSurfaceWidth();
        }

        @Override
        public int getSurfaceHeight() {
            // See getSurfaceWidth() above for explanation.
            return mHost.getSurfaceHeight();
        }

        void removeSurface() {
            if (mDimLayer != null && mDimLayer.isValid()) {
                getSyncTransaction().remove(mDimLayer);
            }
            mDimLayer = null;
        }
    }

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
        SurfaceAnimator mSurfaceAnimator;

        DimState(SurfaceControl dimLayer) {
            mDimLayer = dimLayer;
            mDimming = true;
            final DimAnimatable dimAnimatable = new DimAnimatable(dimLayer);
            mSurfaceAnimator = new SurfaceAnimator(dimAnimatable, (type, anim) -> {
                if (!mDimming) {
                    dimAnimatable.removeSurface();
                }
            }, mHost.mWmService);
        }
    }

    @VisibleForTesting
    interface SurfaceAnimatorStarter {
        void startAnimation(SurfaceAnimator surfaceAnimator, SurfaceControl.Transaction t,
                AnimationAdapter anim, boolean hidden, @SurfaceAnimator.AnimationType int type);
    }

    protected LegacyDimmer(WindowContainer host) {
        this(host, SurfaceAnimator::startAnimation);
    }

    LegacyDimmer(WindowContainer host, SurfaceAnimatorStarter surfaceAnimatorStarter) {
        super(host);
        mSurfaceAnimatorStarter = surfaceAnimatorStarter;
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
        if (d == null) {
            return;
        }

        // The dim method is called from WindowState.prepareSurfaces(), which is always called
        // in the correct Z from lowest Z to highest. This ensures that the dim layer is always
        // relative to the highest Z layer with a dim.
        SurfaceControl.Transaction t = mHost.getPendingTransaction();
        t.setAlpha(d.mDimLayer, alpha);
        t.setBackgroundBlurRadius(d.mDimLayer, blurRadius);
        d.mDimming = true;
    }

    @Override
    protected void adjustRelativeLayer(WindowContainer container, int relativeLayer) {
        final DimState d = mDimState;
        if (d != null) {
            SurfaceControl.Transaction t = mHost.getPendingTransaction();
            t.setRelativeLayer(d.mDimLayer, container.getSurfaceControl(), relativeLayer);
        }
    }

    @Override
    boolean updateDims(SurfaceControl.Transaction t) {
        if (mDimState == null) {
            return false;
        }

        if (!mDimState.mDimming) {
            if (!mDimState.mAnimateExit) {
                if (mDimState.mDimLayer.isValid()) {
                    t.remove(mDimState.mDimLayer);
                }
            } else {
                startDimExit(mLastRequestedDimContainer,
                        mDimState.mSurfaceAnimator, t);
            }
            mDimState = null;
            return false;
        } else {
            final Rect bounds = mDimState.mDimBounds;
            // TODO: Once we use geometry from hierarchy this falls away.
            t.setPosition(mDimState.mDimLayer, bounds.left, bounds.top);
            t.setWindowCrop(mDimState.mDimLayer, bounds.width(), bounds.height());
            if (!mDimState.mIsVisible) {
                mDimState.mIsVisible = true;
                t.show(mDimState.mDimLayer);
                // Skip enter animation while starting window is on top of its activity
                final WindowState ws = mLastRequestedDimContainer.asWindowState();
                if (ws == null || ws.mActivityRecord == null
                        || ws.mActivityRecord.mStartingData == null) {
                    startDimEnter(mLastRequestedDimContainer,
                            mDimState.mSurfaceAnimator, t);
                }
            }
            return true;
        }
    }

    private long getDimDuration(WindowContainer container) {
        // Use the same duration as the animation on the WindowContainer
        AnimationAdapter animationAdapter = container.mSurfaceAnimator.getAnimation();
        final float durationScale = container.mWmService.getTransitionAnimationScaleLocked();
        return animationAdapter == null ? (long) (DEFAULT_DIM_ANIM_DURATION * durationScale)
                : animationAdapter.getDurationHint();
    }

    private void startDimEnter(WindowContainer container, SurfaceAnimator animator,
            SurfaceControl.Transaction t) {
        startAnim(container, animator, t, 0 /* startAlpha */, 1 /* endAlpha */);
    }

    private void startDimExit(WindowContainer container, SurfaceAnimator animator,
            SurfaceControl.Transaction t) {
        startAnim(container, animator, t, 1 /* startAlpha */, 0 /* endAlpha */);
    }

    private void startAnim(WindowContainer container, SurfaceAnimator animator,
            SurfaceControl.Transaction t, float startAlpha, float endAlpha) {
        mSurfaceAnimatorStarter.startAnimation(animator, t, new LocalAnimationAdapter(
                        new AlphaAnimationSpec(startAlpha, endAlpha, getDimDuration(container)),
                        mHost.mWmService.mSurfaceAnimationRunner), false /* hidden */,
                ANIMATION_TYPE_DIMMER);
    }

    private static class AlphaAnimationSpec implements LocalAnimationAdapter.AnimationSpec {
        private final long mDuration;
        private final float mFromAlpha;
        private final float mToAlpha;

        AlphaAnimationSpec(float fromAlpha, float toAlpha, long duration) {
            mFromAlpha = fromAlpha;
            mToAlpha = toAlpha;
            mDuration = duration;
        }

        @Override
        public long getDuration() {
            return mDuration;
        }

        @Override
        public void apply(SurfaceControl.Transaction t, SurfaceControl sc, long currentPlayTime) {
            final float fraction = getFraction(currentPlayTime);
            final float alpha = fraction * (mToAlpha - mFromAlpha) + mFromAlpha;
            t.setAlpha(sc, alpha);
        }

        @Override
        public void dump(PrintWriter pw, String prefix) {
            pw.print(prefix); pw.print("from="); pw.print(mFromAlpha);
            pw.print(" to="); pw.print(mToAlpha);
            pw.print(" duration="); pw.println(mDuration);
        }

        @Override
        public void dumpDebugInner(ProtoOutputStream proto) {
            final long token = proto.start(ALPHA);
            proto.write(FROM, mFromAlpha);
            proto.write(TO, mToAlpha);
            proto.write(DURATION_MS, mDuration);
            proto.end(token);
        }
    }
}
