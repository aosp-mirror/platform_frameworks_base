/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.widget;

import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

/**
 * This class performs the glow effect used at the edges of scrollable widgets.
 * @hide
 */
public class EdgeGlow {
    private static final String TAG = "EdgeGlow";

    private static final boolean DEBUG = false;

    // Time it will take the effect to fully recede in ms
    private static final int RECEDE_TIME = 1000;

    // Time it will take before a pulled glow begins receding
    private static final int PULL_TIME = 250;

    // Time it will take for a pulled glow to decay to partial strength before release
    private static final int PULL_DECAY_TIME = 1000;

    private static final float HELD_EDGE_ALPHA = 0.7f;
    private static final float HELD_EDGE_SCALE_Y = 0.5f;
    private static final float HELD_GLOW_ALPHA = 0.5f;
    private static final float HELD_GLOW_SCALE_Y = 0.5f;

    private static final float PULL_GLOW_BEGIN = 0.5f;

    // Minimum velocity that will be absorbed
    private static final int MIN_VELOCITY = 750;
    
    private static final float EPSILON = 0.001f;

    private Drawable mEdge;
    private Drawable mGlow;
    private int mWidth;
    private int mHeight;

    private float mEdgeAlpha;
    private float mEdgeScaleY;
    private float mGlowAlpha;
    private float mGlowScaleY;

    private float mEdgeAlphaStart;
    private float mEdgeAlphaFinish;
    private float mEdgeScaleYStart;
    private float mEdgeScaleYFinish;
    private float mGlowAlphaStart;
    private float mGlowAlphaFinish;
    private float mGlowScaleYStart;
    private float mGlowScaleYFinish;

    private long mStartTime;
    private int mDuration;

    private Interpolator mInterpolator;

    private static final int STATE_IDLE = 0;
    private static final int STATE_PULL = 1;
    private static final int STATE_ABSORB = 2;
    private static final int STATE_RECEDE = 3;
    private static final int STATE_PULL_DECAY = 4;

    private int mState = STATE_IDLE;

    private float mPullDistance;

    public EdgeGlow(Drawable edge, Drawable glow) {
        mEdge = edge;
        mGlow = glow;

        mInterpolator = new DecelerateInterpolator();
    }

    public void setSize(int width, int height) {
        mWidth = width;
        mHeight = height;
    }

    public boolean isFinished() {
        return mState == STATE_IDLE;
    }

    /**
     * Call when the object is pulled by the user.
     * @param deltaDistance Change in distance since the last call
     */
    public void onPull(float deltaDistance) {
        final long now = AnimationUtils.currentAnimationTimeMillis();
        if (mState == STATE_PULL_DECAY && now - mStartTime < mDuration) {
            return;
        }
        if (mState != STATE_PULL) {
            mGlowScaleY = PULL_GLOW_BEGIN;
        }
        mState = STATE_PULL;

        mStartTime = now;
        mDuration = PULL_TIME;

        mPullDistance += deltaDistance;
        float distance = Math.abs(mPullDistance);

        mEdgeAlpha = mEdgeAlphaStart = Math.max(HELD_EDGE_ALPHA, Math.min(distance, 1.f));
        mEdgeScaleY = mEdgeScaleYStart = Math.max(HELD_EDGE_SCALE_Y, Math.min(distance, 2.f));

        mGlowAlpha = mGlowAlphaStart = Math.max(0.5f,
                Math.min(mGlowAlpha + Math.abs(deltaDistance), 1.f));

        float glowChange = Math.abs(deltaDistance);
        if (deltaDistance > 0 && mPullDistance < 0) {
            glowChange = -glowChange;
        }
        if (mPullDistance == 0) {
            mGlowScaleY = 0;
        }
        mGlowScaleY = mGlowScaleYStart = Math.max(0, mGlowScaleY + glowChange * 2);

        mEdgeAlphaFinish = mEdgeAlpha;
        mEdgeScaleYFinish = mEdgeScaleY;
        mGlowAlphaFinish = mGlowAlpha;
        mGlowScaleYFinish = mGlowScaleY;

        if (DEBUG) Log.d(TAG, "onPull(" + distance + ", " + deltaDistance + ")");
    }

    /**
     * Call when the object is released after being pulled.
     */
    public void onRelease() {
        mPullDistance = 0;

        if (mState != STATE_PULL && mState != STATE_PULL_DECAY) {
            return;
        }
        if (DEBUG) Log.d(TAG, "onRelease");

        mState = STATE_RECEDE;
        mEdgeAlphaStart = mEdgeAlpha;
        mEdgeScaleYStart = mEdgeScaleY;
        mGlowAlphaStart = mGlowAlpha;
        mGlowScaleYStart = mGlowScaleY;

        mEdgeAlphaFinish = 0.f;
        mEdgeScaleYFinish = 0.1f;
        mGlowAlphaFinish = 0.f;
        mGlowScaleYFinish = 0.1f;

        mStartTime = AnimationUtils.currentAnimationTimeMillis();
        mDuration = RECEDE_TIME;
    }

    /**
     * Call when the effect absorbs an impact at the given velocity.
     * @param velocity Velocity at impact in pixels per second.
     */
    public void onAbsorb(int velocity) {
        mState = STATE_ABSORB;
        if (DEBUG) Log.d(TAG, "onAbsorb uncooked velocity: " + velocity);
        velocity = Math.max(MIN_VELOCITY, Math.abs(velocity));

        mStartTime = AnimationUtils.currentAnimationTimeMillis();
        mDuration = (int) (velocity * 0.03f);

        mEdgeAlphaStart = 0.5f;
        mEdgeScaleYStart = 0.2f;
        mGlowAlphaStart = 0.5f;
        mGlowScaleYStart = 0.f;

        mEdgeAlphaFinish = Math.max(0, Math.min(velocity * 0.01f, 1));
        mEdgeScaleYFinish = 1.f;
        mGlowAlphaFinish = 1.f;
        mGlowScaleYFinish = Math.min(velocity * 0.001f, 1);

        if (DEBUG) Log.d(TAG, "onAbsorb(" + velocity + "): duration " + mDuration);
    }

    /**
     * Draw into the provided canvas.
     * Assumes that the canvas has been rotated accordingly and the size has been set.
     * The effect will be drawn the full width of X=0 to X=width, emitting from Y=0 and extending
     * to some factor < 1.f of height.
     *
     * @param canvas Canvas to draw into
     * @return true if drawing should continue beyond this frame to continue the animation
     */
    public boolean draw(Canvas canvas) {
        update();

        final int edgeHeight = mEdge.getIntrinsicHeight();
        final int glowHeight = mGlow.getIntrinsicHeight();

        mGlow.setAlpha((int) (Math.max(0, Math.min(mGlowAlpha, 1)) * 255));
        mGlow.setBounds(0, 0, mWidth, (int) (glowHeight * mGlowScaleY * 0.5f));
        mGlow.draw(canvas);

        mEdge.setAlpha((int) (Math.max(0, Math.min(mEdgeAlpha, 1)) * 255));
        mEdge.setBounds(0,
                0,
                mWidth,
                (int) (edgeHeight * mEdgeScaleY));
        mEdge.draw(canvas);
        if (DEBUG) Log.d(TAG, "draw() glow(" + mGlowAlpha + ", " + mGlowScaleY + ") edge(" + mEdgeAlpha +
                ", " + mEdgeScaleY + ")");

        return mState != STATE_IDLE;
    }

    private void update() {
        final long time = AnimationUtils.currentAnimationTimeMillis();
        final float t = Math.min((float) (time - mStartTime) / mDuration, 1.f);

        final float interp = mInterpolator.getInterpolation(t);

        mEdgeAlpha = mEdgeAlphaStart + (mEdgeAlphaFinish - mEdgeAlphaStart) * interp;
        mEdgeScaleY = mEdgeScaleYStart + (mEdgeScaleYFinish - mEdgeScaleYStart) * interp;
        mGlowAlpha = mGlowAlphaStart + (mGlowAlphaFinish - mGlowAlphaStart) * interp;
        mGlowScaleY = mGlowScaleYStart + (mGlowScaleYFinish - mGlowScaleYStart) * interp;

        if (t >= 1.f - EPSILON) {
            switch (mState) {
                case STATE_ABSORB:
                    mState = STATE_RECEDE;
                    mStartTime = AnimationUtils.currentAnimationTimeMillis();
                    mDuration = RECEDE_TIME;

                    mEdgeAlphaStart = mEdgeAlpha;
                    mEdgeScaleYStart = mEdgeScaleY;
                    mGlowAlphaStart = mGlowAlpha;
                    mGlowScaleYStart = mGlowScaleY;

                    mEdgeAlphaFinish = 0.f;
                    mEdgeScaleYFinish = 0.1f;
                    mGlowAlphaFinish = 0.f;
                    mGlowScaleYFinish = mGlowScaleY;
                    if (DEBUG) Log.d(TAG, "STATE_ABSORB => STATE_RECEDE");
                    break;
                case STATE_PULL:
                    mState = STATE_PULL_DECAY;
                    mStartTime = AnimationUtils.currentAnimationTimeMillis();
                    mDuration = PULL_DECAY_TIME;

                    mEdgeAlphaStart = mEdgeAlpha;
                    mEdgeScaleYStart = mEdgeScaleY;
                    mGlowAlphaStart = mGlowAlpha;
                    mGlowScaleYStart = mGlowScaleY;

                    mEdgeAlphaFinish = Math.min(mEdgeAlphaStart, HELD_EDGE_ALPHA);
                    mEdgeScaleYFinish = Math.min(mEdgeScaleYStart, HELD_EDGE_SCALE_Y);
                    mGlowAlphaFinish = Math.min(mGlowAlphaStart, HELD_GLOW_ALPHA);
                    mGlowScaleYFinish = Math.min(mGlowScaleY, HELD_GLOW_SCALE_Y);
                    if (DEBUG) Log.d(TAG, "STATE_PULL => STATE_PULL_DECAY");
                    break;
                case STATE_PULL_DECAY:
                    // Do nothing; wait for release
                    break;
                case STATE_RECEDE:
                    mState = STATE_IDLE;
                    if (DEBUG) Log.d(TAG, "STATE_RECEDE => STATE_IDLE");
                    break;
            }
        }
    }
}
