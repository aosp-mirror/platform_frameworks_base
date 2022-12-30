/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.navigationbar.gestural;

import static android.view.MotionEvent.AXIS_GESTURE_X_OFFSET;
import static android.view.MotionEvent.AXIS_GESTURE_Y_OFFSET;

import static com.android.systemui.navigationbar.gestural.Utilities.isTrackpadMotionEvent;

import android.graphics.PointF;
import android.view.MotionEvent;

import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.plugins.MotionEventsHandlerBase;

/** Handles both trackpad and touch events and report displacements in both axis's. */
public class MotionEventsHandler implements MotionEventsHandlerBase {

    private final boolean mIsTrackpadGestureBackEnabled;
    private final int mScale;

    private final PointF mDownPos = new PointF();
    private final PointF mLastPos = new PointF();
    private float mCurrentTrackpadOffsetX = 0;
    private float mCurrentTrackpadOffsetY = 0;

    public MotionEventsHandler(FeatureFlags featureFlags, int scale) {
        mIsTrackpadGestureBackEnabled = featureFlags.isEnabled(Flags.TRACKPAD_GESTURE_BACK);
        mScale = scale;
    }

    @Override
    public void onMotionEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onActionDown(ev);
                break;
            case MotionEvent.ACTION_MOVE:
                onActionMove(ev);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                onActionUp(ev);
                break;
            default:
                break;
        }
    }

    private void onActionDown(MotionEvent ev) {
        reset();
        if (!isTrackpadMotionEvent(mIsTrackpadGestureBackEnabled, ev)) {
            mDownPos.set(ev.getX(), ev.getY());
            mLastPos.set(mDownPos);
        }
    }

    private void onActionMove(MotionEvent ev) {
        updateMovements(ev);
    }

    private void onActionUp(MotionEvent ev) {
        updateMovements(ev);
    }

    private void updateMovements(MotionEvent ev) {
        if (isTrackpadMotionEvent(mIsTrackpadGestureBackEnabled, ev)) {
            mCurrentTrackpadOffsetX += ev.getAxisValue(AXIS_GESTURE_X_OFFSET) * mScale;
            mCurrentTrackpadOffsetY += ev.getAxisValue(AXIS_GESTURE_Y_OFFSET) * mScale;
        } else {
            mLastPos.set(ev.getX(), ev.getY());
        }
    }

    private void reset() {
        mDownPos.set(0, 0);
        mLastPos.set(0, 0);
        mCurrentTrackpadOffsetX = 0;
        mCurrentTrackpadOffsetY = 0;
    }

    @Override
    public float getDisplacementX(MotionEvent ev) {
        return isTrackpadMotionEvent(mIsTrackpadGestureBackEnabled, ev) ? mCurrentTrackpadOffsetX
                : mLastPos.x - mDownPos.x;
    }

    @Override
    public float getDisplacementY(MotionEvent ev) {
        return isTrackpadMotionEvent(mIsTrackpadGestureBackEnabled, ev) ? mCurrentTrackpadOffsetY
                : mLastPos.y - mDownPos.y;
    }

    @Override
    public String dump() {
        return "mDownPos: " + mDownPos + ", mLastPos: " + mLastPos + ", mCurrentTrackpadOffsetX: "
                + mCurrentTrackpadOffsetX + ", mCurrentTrackpadOffsetY: " + mCurrentTrackpadOffsetY;
    }
}
