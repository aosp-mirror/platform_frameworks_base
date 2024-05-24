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

package android.view;

import android.os.SystemClock;

import com.android.graphics.hwui.flags.Flags;

import java.util.function.Consumer;

/** @hide */
class HdrRenderState implements Consumer<Display> {
    // Targeting an animation from 1x to 5x over 400ms means we need to increase by 0.01/ms
    private static final float TRANSITION_PER_MS = 0.01f;

    private static final boolean FLAG_ANIMATE_ENABLED = Flags.animateHdrTransitions();

    private final ViewRootImpl mViewRoot;

    private boolean mIsHdrEnabled = false;
    private boolean mIsListenerRegistered = false;
    private boolean mUpdateHdrSdrRatioInfo = false;
    private float mDesiredHdrSdrRatio = 1f;
    private float mTargetDesiredHdrSdrRatio = 1f;
    private float mTargetHdrSdrRatio = 1f;
    private float mRenderHdrSdrRatio = 1f;
    private float mPreviousRenderRatio = 1f;
    private long mLastUpdateMillis = -1;

    HdrRenderState(ViewRootImpl viewRoot) {
        mViewRoot = viewRoot;
    }

    @Override
    public void accept(Display display) {
        forceUpdateHdrSdrRatio();
        mViewRoot.invalidate();
    }

    boolean isHdrEnabled() {
        return mIsHdrEnabled;
    }

    void stopListening() {
        if (mIsListenerRegistered) {
            mViewRoot.mDisplay.unregisterHdrSdrRatioChangedListener(this);
            mIsListenerRegistered = false;
        }
    }

    void startListening() {
        if (isHdrEnabled() && !mIsListenerRegistered && mViewRoot.mDisplay != null) {
            mViewRoot.mDisplay.registerHdrSdrRatioChangedListener(mViewRoot.mExecutor, this);
        }
    }

    /** @return true if something changed, else false */
    boolean updateForFrame(long frameTimeMillis) {
        boolean hasUpdate = mUpdateHdrSdrRatioInfo;
        mUpdateHdrSdrRatioInfo = false;
        mRenderHdrSdrRatio = mTargetHdrSdrRatio;
        long timeDelta = Math.max(Math.min(32, frameTimeMillis - mLastUpdateMillis), 8);
        final float maxStep = timeDelta * TRANSITION_PER_MS;
        mLastUpdateMillis = frameTimeMillis;
        if (hasUpdate && FLAG_ANIMATE_ENABLED) {
            if (isHdrEnabled()) {
                float delta = mTargetHdrSdrRatio - mPreviousRenderRatio;
                if (delta > maxStep) {
                    mRenderHdrSdrRatio = mPreviousRenderRatio + maxStep;
                    mUpdateHdrSdrRatioInfo = true;
                    mViewRoot.invalidate();
                }
                mPreviousRenderRatio = mRenderHdrSdrRatio;

                if (mTargetDesiredHdrSdrRatio < mDesiredHdrSdrRatio) {
                    mDesiredHdrSdrRatio = Math.max(mTargetDesiredHdrSdrRatio,
                            mDesiredHdrSdrRatio - maxStep);
                    if (mDesiredHdrSdrRatio != mTargetDesiredHdrSdrRatio) {
                        mUpdateHdrSdrRatioInfo = true;
                        mViewRoot.invalidate();
                    }
                }

            } else {
                mPreviousRenderRatio = mTargetHdrSdrRatio;
                mDesiredHdrSdrRatio = mTargetDesiredHdrSdrRatio;
            }
        }
        return hasUpdate;
    }

    float getDesiredHdrSdrRatio() {
        return mDesiredHdrSdrRatio;
    }

    float getRenderHdrSdrRatio() {
        return mRenderHdrSdrRatio;
    }

    void forceUpdateHdrSdrRatio() {
        if (isHdrEnabled()) {
            mTargetHdrSdrRatio = Math.min(mDesiredHdrSdrRatio,
                    mViewRoot.mDisplay.getHdrSdrRatio());
        } else {
            mTargetHdrSdrRatio = 1.0f;
        }
        mUpdateHdrSdrRatioInfo = true;
    }

    void setDesiredHdrSdrRatio(boolean isHdrEnabled, float desiredRatio) {
        mIsHdrEnabled = isHdrEnabled;
        mLastUpdateMillis = SystemClock.uptimeMillis();
        if (desiredRatio != mTargetDesiredHdrSdrRatio) {
            mTargetDesiredHdrSdrRatio = desiredRatio;
            if (mTargetDesiredHdrSdrRatio > mDesiredHdrSdrRatio || !FLAG_ANIMATE_ENABLED) {
                mDesiredHdrSdrRatio = mTargetDesiredHdrSdrRatio;
            }
            forceUpdateHdrSdrRatio();
            mViewRoot.invalidate();

            if (isHdrEnabled()) {
                startListening();
            } else {
                stopListening();
            }
        }
    }
}
