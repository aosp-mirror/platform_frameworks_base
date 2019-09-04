/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.server.wm.AlphaAnimationSpecProto.DURATION_MS;
import static com.android.server.wm.AlphaAnimationSpecProto.FROM;
import static com.android.server.wm.AlphaAnimationSpecProto.TO;
import static com.android.server.wm.AnimationSpecProto.ALPHA;

import android.graphics.Rect;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

/**
 * Utility class for use by a WindowContainer implementation to add "DimLayer" support, that is
 * black layers of varying opacity at various Z-levels which create the effect of a Dim.
 */
class Dimmer {
    private static final String TAG = "WindowManager";
    // This is in milliseconds.
    private static final int DEFAULT_DIM_ANIM_DURATION = 200;

    private class DimAnimatable implements SurfaceAnimator.Animatable {
        private SurfaceControl mDimLayer;

        private DimAnimatable(SurfaceControl dimLayer) {
            mDimLayer = dimLayer;
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
                getPendingTransaction().remove(mDimLayer);
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
        boolean isVisible;
        SurfaceAnimator mSurfaceAnimator;

        /**
         * Determines whether the dim layer should animate before destroying.
         */
        boolean mAnimateExit = true;

        /**
         * Used for Dims not associated with a WindowContainer. See {@link Dimmer#dimAbove} for
         * details on Dim lifecycle.
         */
        boolean mDontReset;

        DimState(SurfaceControl dimLayer) {
            mDimLayer = dimLayer;
            mDimming = true;
            final DimAnimatable dimAnimatable = new DimAnimatable(dimLayer);
            mSurfaceAnimator = new SurfaceAnimator(dimAnimatable, () -> {
                if (!mDimming) {
                    dimAnimatable.removeSurface();
                }
            }, mHost.mWmService);
        }
    }

    /**
     * The {@link WindowContainer} that our Dim's are bounded to. We may be dimming on behalf of the
     * host, some controller of it, or one of the hosts children.
     */
    private WindowContainer mHost;
    private WindowContainer mLastRequestedDimContainer;
    @VisibleForTesting
    DimState mDimState;

    private final SurfaceAnimatorStarter mSurfaceAnimatorStarter;

    @VisibleForTesting
    interface SurfaceAnimatorStarter {
        void startAnimation(SurfaceAnimator surfaceAnimator, SurfaceControl.Transaction t,
                AnimationAdapter anim, boolean hidden);
    }

    Dimmer(WindowContainer host) {
        this(host, SurfaceAnimator::startAnimation);
    }

    Dimmer(WindowContainer host, SurfaceAnimatorStarter surfaceAnimatorStarter) {
        mHost = host;
        mSurfaceAnimatorStarter = surfaceAnimatorStarter;
    }

    private SurfaceControl makeDimLayer() {
        return mHost.makeChildSurface(null)
                .setParent(mHost.getSurfaceControl())
                .setColorLayer()
                .setName("Dim Layer for - " + mHost.getName())
                .build();
    }

    /**
     * Retrieve the DimState, creating one if it doesn't exist.
     */
    private DimState getDimState(WindowContainer container) {
        if (mDimState == null) {
            try {
                final SurfaceControl ctl = makeDimLayer();
                mDimState = new DimState(ctl);
                /**
                 * See documentation on {@link #dimAbove} to understand lifecycle management of
                 * Dim's via state resetting for Dim's with containers.
                 */
                if (container == null) {
                    mDimState.mDontReset = true;
                }
            } catch (Surface.OutOfResourcesException e) {
                Log.w(TAG, "OutOfResourcesException creating dim surface");
            }
        }

        mLastRequestedDimContainer = container;
        return mDimState;
    }

    private void dim(SurfaceControl.Transaction t, WindowContainer container, int relativeLayer,
            float alpha) {
        final DimState d = getDimState(container);

        if (d == null) {
            return;
        }

        if (container != null) {
            // The dim method is called from WindowState.prepareSurfaces(), which is always called
            // in the correct Z from lowest Z to highest. This ensures that the dim layer is always
            // relative to the highest Z layer with a dim.
            t.setRelativeLayer(d.mDimLayer, container.getSurfaceControl(), relativeLayer);
        } else {
            t.setLayer(d.mDimLayer, Integer.MAX_VALUE);
        }
        t.setAlpha(d.mDimLayer, alpha);

        d.mDimming = true;
    }

    /**
     * Finish a dim started by dimAbove in the case there was no call to dimAbove.
     *
     * @param t A Transaction in which to finish the dim.
     */
    void stopDim(SurfaceControl.Transaction t) {
        if (mDimState != null) {
            t.hide(mDimState.mDimLayer);
            mDimState.isVisible = false;
            mDimState.mDontReset = false;
        }
    }

    /**
     * Place a Dim above the entire host container. The caller is responsible for calling stopDim to
     * remove this effect. If the Dim can be assosciated with a particular child of the host
     * consider using the other variant of dimAbove which ties the Dim lifetime to the child
     * lifetime more explicitly.
     *
     * @param t     A transaction in which to apply the Dim.
     * @param alpha The alpha at which to Dim.
     */
    void dimAbove(SurfaceControl.Transaction t, float alpha) {
        dim(t, null, 1, alpha);
    }

    /**
     * Place a dim above the given container, which should be a child of the host container.
     * for each call to {@link WindowContainer#prepareSurfaces} the Dim state will be reset
     * and the child should call dimAbove again to request the Dim to continue.
     *
     * @param t         A transaction in which to apply the Dim.
     * @param container The container which to dim above. Should be a child of our host.
     * @param alpha     The alpha at which to Dim.
     */
    void dimAbove(SurfaceControl.Transaction t, WindowContainer container, float alpha) {
        dim(t, container, 1, alpha);
    }

    /**
     * Like {@link #dimAbove} but places the dim below the given container.
     *
     * @param t         A transaction in which to apply the Dim.
     * @param container The container which to dim below. Should be a child of our host.
     * @param alpha     The alpha at which to Dim.
     */

    void dimBelow(SurfaceControl.Transaction t, WindowContainer container, float alpha) {
        dim(t, container, -1, alpha);
    }

    /**
     * Mark all dims as pending completion on the next call to {@link #updateDims}
     *
     * This is intended for us by the host container, to be called at the beginning of
     * {@link WindowContainer#prepareSurfaces}. After calling this, the container should
     * chain {@link WindowContainer#prepareSurfaces} down to it's children to give them
     * a chance to request dims to continue.
     */
    void resetDimStates() {
        if (mDimState != null && !mDimState.mDontReset) {
            mDimState.mDimming = false;
        }
    }

    void dontAnimateExit() {
        if (mDimState != null) {
            mDimState.mAnimateExit = false;
        }
    }

    /**
     * Call after invoking {@link WindowContainer#prepareSurfaces} on children as
     * described in {@link #resetDimStates}.
     *
     * @param t      A transaction in which to update the dims.
     * @param bounds The bounds at which to dim.
     * @return true if any Dims were updated.
     */
    boolean updateDims(SurfaceControl.Transaction t, Rect bounds) {
        if (mDimState == null) {
            return false;
        }

        if (!mDimState.mDimming) {
            if (!mDimState.mAnimateExit) {
                if (mDimState.mDimLayer.isValid()) {
                    t.remove(mDimState.mDimLayer);
                }
            } else {
                startDimExit(mLastRequestedDimContainer, mDimState.mSurfaceAnimator, t);
            }
            mDimState = null;
            return false;
        } else {
            // TODO: Once we use geometry from hierarchy this falls away.
            t.setPosition(mDimState.mDimLayer, bounds.left, bounds.top);
            t.setWindowCrop(mDimState.mDimLayer, bounds.width(), bounds.height());
            if (!mDimState.isVisible) {
                mDimState.isVisible = true;
                t.show(mDimState.mDimLayer);
                startDimEnter(mLastRequestedDimContainer, mDimState.mSurfaceAnimator, t);
            }
            return true;
        }
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
                mHost.mWmService.mSurfaceAnimationRunner), false /* hidden */);
    }

    private long getDimDuration(WindowContainer container) {
        // If there's no container, then there isn't an animation occurring while dimming. Set the
        // duration to 0 so it immediately dims to the set alpha.
        if (container == null) {
            return 0;
        }

        // Otherwise use the same duration as the animation on the WindowContainer
        AnimationAdapter animationAdapter = container.mSurfaceAnimator.getAnimation();
        return animationAdapter == null ? DEFAULT_DIM_ANIM_DURATION
                : animationAdapter.getDurationHint();
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
            float alpha = ((float) currentPlayTime / getDuration()) * (mToAlpha - mFromAlpha)
                    + mFromAlpha;
            t.setAlpha(sc, alpha);
        }

        @Override
        public void dump(PrintWriter pw, String prefix) {
            pw.print(prefix); pw.print("from="); pw.print(mFromAlpha);
            pw.print(" to="); pw.print(mToAlpha);
            pw.print(" duration="); pw.println(mDuration);
        }

        @Override
        public void writeToProtoInner(ProtoOutputStream proto) {
            final long token = proto.start(ALPHA);
            proto.write(FROM, mFromAlpha);
            proto.write(TO, mToAlpha);
            proto.write(DURATION_MS, mDuration);
            proto.end(token);
        }
    }
}
