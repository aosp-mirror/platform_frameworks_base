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
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.graphics.Rect;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;

class SmoothDimmer extends Dimmer {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "Dimmer" : TAG_WM;
    DimState mDimState;
    final DimmerAnimationHelper.AnimationAdapterFactory mAnimationAdapterFactory;

    /**
     * Controls the dim behaviour
     */
    @VisibleForTesting
    class DimState {
        /** Related objects */
        SurfaceControl mDimSurface;
        final WindowContainer mHostContainer;
        // The last container to request to dim
        private WindowContainer mLastRequestedDimContainer;
        /** Animation */
        private final DimmerAnimationHelper mAnimationHelper;
        boolean mSkipAnimation = false;
        // Determines whether the dim layer should animate before destroying.
        boolean mAnimateExit = true;
        /** Surface visibility and bounds */
        private boolean mIsVisible = false;
        // TODO(b/64816140): Remove after confirming dimmer layer always matches its container.
        final Rect mDimBounds = new Rect();

        DimState() {
            mHostContainer = mHost;
            mAnimationHelper = new DimmerAnimationHelper(mAnimationAdapterFactory);
            try {
                mDimSurface = makeDimLayer();
            } catch (Surface.OutOfResourcesException e) {
                Log.w(TAG, "OutOfResourcesException creating dim surface");
            }
        }

        void ensureVisible(SurfaceControl.Transaction t) {
            if (!mIsVisible) {
                t.show(mDimSurface);
                t.setAlpha(mDimSurface, 0f);
                mIsVisible = true;
            }
        }

        void adjustSurfaceLayout(SurfaceControl.Transaction t) {
            // TODO: Once we use geometry from hierarchy this falls away.
            t.setPosition(mDimSurface, mDimBounds.left, mDimBounds.top);
            t.setWindowCrop(mDimSurface, mDimBounds.width(), mDimBounds.height());
        }

        /**
         * Set the parameters to prepare the dim to change its appearance
         */
        void prepareLookChange(float alpha, int blurRadius) {
            mAnimationHelper.setRequestedAppearance(alpha, blurRadius);
        }

        /**
         * Prepare the dim for the exit animation
         */
        void exit(SurfaceControl.Transaction t) {
            if (!mAnimateExit) {
                remove(t);
            } else {
                mAnimationHelper.setExitParameters();
                setReady(t);
            }
        }

        void remove(SurfaceControl.Transaction t) {
            mAnimationHelper.stopCurrentAnimation(mDimSurface);
            if (mDimSurface.isValid()) {
                t.remove(mDimSurface);
                ProtoLog.d(WM_DEBUG_DIMMER,
                        "Removing dim surface %s on transaction %s", this, t);
            } else {
                Log.w(TAG, "Tried to remove " + mDimSurface + " multiple times\n");
            }
        }

        @Override
        public String toString() {
            return "SmoothDimmer#DimState with host=" + mHostContainer + ", surface=" + mDimSurface;
        }

        /**
         * Set the parameters to prepare the dim to be relative parented to the dimming container
         */
        void prepareReparent(WindowContainer relativeParent, int relativeLayer) {
            mAnimationHelper.setRequestedRelativeParent(relativeParent, relativeLayer);
        }

        /**
         * Call when all the changes have been requested to have them applied
         * @param t The transaction in which to apply the changes
         */
        void setReady(SurfaceControl.Transaction t) {
            mAnimationHelper.applyChanges(t, this);
        }

        /**
         * Whether anyone is currently requesting the dim
         */
        boolean isDimming() {
            return mLastRequestedDimContainer != null;
        }

        private SurfaceControl makeDimLayer() {
            return mHost.makeChildSurface(null)
                    .setParent(mHost.getSurfaceControl())
                    .setColorLayer()
                    .setName("Dim Layer for - " + mHost.getName())
                    .setCallsite("DimLayer.makeDimLayer")
                    .build();
        }
    }

    protected SmoothDimmer(WindowContainer host) {
        this(host, new DimmerAnimationHelper.AnimationAdapterFactory());
    }

    @VisibleForTesting
    SmoothDimmer(WindowContainer host,
                 DimmerAnimationHelper.AnimationAdapterFactory animationFactory) {
        super(host);
        mAnimationAdapterFactory = animationFactory;
    }

    @Override
    void resetDimStates() {
        if (mDimState != null) {
            mDimState.mLastRequestedDimContainer = null;
        }
    }

    @Override
    protected void adjustAppearance(WindowContainer container, float alpha, int blurRadius) {
        final DimState d = obtainDimState(container);
        d.prepareLookChange(alpha, blurRadius);
    }

    @Override
    protected void adjustRelativeLayer(WindowContainer container, int relativeLayer) {
        if (mDimState != null) {
            mDimState.prepareReparent(container, relativeLayer);
        }
    }

    @Override
    boolean updateDims(SurfaceControl.Transaction t) {
        if (mDimState == null) {
            return false;
        }
        if (!mDimState.isDimming()) {
            // No one is dimming, fade out and remove the dim
            mDimState.exit(t);
            mDimState = null;
            return false;
        } else {
            // Someone is dimming, show the requested changes
            mDimState.adjustSurfaceLayout(t);
            final WindowState ws = mDimState.mLastRequestedDimContainer.asWindowState();
            if (!mDimState.mIsVisible && ws != null && ws.mActivityRecord != null
                    && ws.mActivityRecord.mStartingData != null) {
                // Skip enter animation while starting window is on top of its activity
                mDimState.mSkipAnimation = true;
            }
            mDimState.setReady(t);
            return true;
        }
    }

    private DimState obtainDimState(WindowContainer container) {
        if (mDimState == null) {
            mDimState = new DimState();
        }
        mDimState.mLastRequestedDimContainer = container;
        return mDimState;
    }

    @Override
    @VisibleForTesting
    SurfaceControl getDimLayer() {
        return mDimState != null ? mDimState.mDimSurface : null;
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
}
