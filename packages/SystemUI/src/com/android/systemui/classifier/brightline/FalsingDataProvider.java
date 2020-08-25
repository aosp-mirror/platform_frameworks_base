/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.classifier.brightline;

import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;

import com.android.systemui.classifier.Classifier;
import com.android.systemui.statusbar.policy.BatteryController;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Acts as a cache and utility class for FalsingClassifiers.
 */
public class FalsingDataProvider {

    private static final long MOTION_EVENT_AGE_MS = 1000;
    private static final float THREE_HUNDRED_SIXTY_DEG = (float) (2 * Math.PI);

    private final int mWidthPixels;
    private final int mHeightPixels;
    private final BatteryController mBatteryController;
    private final float mXdpi;
    private final float mYdpi;

    private @Classifier.InteractionType int mInteractionType;
    private final TimeLimitedMotionEventBuffer mRecentMotionEvents =
            new TimeLimitedMotionEventBuffer(MOTION_EVENT_AGE_MS);

    private boolean mDirty = true;

    private float mAngle = 0;
    private MotionEvent mFirstActualMotionEvent;
    private MotionEvent mFirstRecentMotionEvent;
    private MotionEvent mLastMotionEvent;

    @Inject
    public FalsingDataProvider(DisplayMetrics displayMetrics, BatteryController batteryController) {
        mXdpi = displayMetrics.xdpi;
        mYdpi = displayMetrics.ydpi;
        mWidthPixels = displayMetrics.widthPixels;
        mHeightPixels = displayMetrics.heightPixels;
        mBatteryController = batteryController;

        FalsingClassifier.logInfo("xdpi, ydpi: " + getXdpi() + ", " + getYdpi());
        FalsingClassifier.logInfo("width, height: " + getWidthPixels() + ", " + getHeightPixels());
    }

    void onMotionEvent(MotionEvent motionEvent) {
        if (motionEvent.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mFirstActualMotionEvent = motionEvent;
        }

        List<MotionEvent> motionEvents = unpackMotionEvent(motionEvent);
        FalsingClassifier.logDebug("Unpacked into: " + motionEvents.size());
        if (BrightLineFalsingManager.DEBUG) {
            for (MotionEvent m : motionEvents) {
                FalsingClassifier.logDebug(
                        "x,y,t: " + m.getX() + "," + m.getY() + "," + m.getEventTime());
            }
        }

        if (motionEvent.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mRecentMotionEvents.clear();
        }
        mRecentMotionEvents.addAll(motionEvents);

        FalsingClassifier.logDebug("Size: " + mRecentMotionEvents.size());

        mDirty = true;
    }

    /** Returns screen width in pixels. */
    int getWidthPixels() {
        return mWidthPixels;
    }

    /** Returns screen height in pixels. */
    int getHeightPixels() {
        return mHeightPixels;
    }

    float getXdpi() {
        return mXdpi;
    }

    float getYdpi() {
        return mYdpi;
    }

    List<MotionEvent> getRecentMotionEvents() {
        return mRecentMotionEvents;
    }

    /**
     * interactionType is defined by {@link com.android.systemui.classifier.Classifier}.
     */
    final void setInteractionType(@Classifier.InteractionType int interactionType) {
        if (mInteractionType != interactionType) {
            mInteractionType = interactionType;
            mDirty = true;
        }
    }

    public boolean isDirty() {
        return mDirty;
    }

    final int getInteractionType() {
        return mInteractionType;
    }

    MotionEvent getFirstActualMotionEvent() {
        return mFirstActualMotionEvent;
    }

    MotionEvent getFirstRecentMotionEvent() {
        recalculateData();
        return mFirstRecentMotionEvent;
    }

    MotionEvent getLastMotionEvent() {
        recalculateData();
        return mLastMotionEvent;
    }

    /**
     * Returns the angle between the first and last point of the recent points.
     *
     * The angle will be in radians, always be between 0 and 2*PI, inclusive.
     */
    float getAngle() {
        recalculateData();
        return mAngle;
    }

    boolean isHorizontal() {
        recalculateData();
        if (mRecentMotionEvents.isEmpty()) {
            return false;
        }

        return Math.abs(mFirstRecentMotionEvent.getX() - mLastMotionEvent.getX()) > Math
                .abs(mFirstRecentMotionEvent.getY() - mLastMotionEvent.getY());
    }

    boolean isRight() {
        recalculateData();
        if (mRecentMotionEvents.isEmpty()) {
            return false;
        }

        return mLastMotionEvent.getX() > mFirstRecentMotionEvent.getX();
    }

    boolean isVertical() {
        return !isHorizontal();
    }

    boolean isUp() {
        recalculateData();
        if (mRecentMotionEvents.isEmpty()) {
            return false;
        }

        return mLastMotionEvent.getY() < mFirstRecentMotionEvent.getY();
    }

    /** Returns true if phone is being charged without a cable. */
    boolean isWirelessCharging() {
        return mBatteryController.isWirelessCharging();
    }

    private void recalculateData() {
        if (!mDirty) {
            return;
        }

        if (mRecentMotionEvents.isEmpty()) {
            mFirstRecentMotionEvent = null;
            mLastMotionEvent = null;
        } else {
            mFirstRecentMotionEvent = mRecentMotionEvents.get(0);
            mLastMotionEvent = mRecentMotionEvents.get(mRecentMotionEvents.size() - 1);
        }

        calculateAngleInternal();

        mDirty = false;
    }

    private void calculateAngleInternal() {
        if (mRecentMotionEvents.size() < 2) {
            mAngle = Float.MAX_VALUE;
        } else {
            float lastX = mLastMotionEvent.getX() - mFirstRecentMotionEvent.getX();
            float lastY = mLastMotionEvent.getY() - mFirstRecentMotionEvent.getY();

            mAngle = (float) Math.atan2(lastY, lastX);
            while (mAngle < 0) {
                mAngle += THREE_HUNDRED_SIXTY_DEG;
            }
            while (mAngle > THREE_HUNDRED_SIXTY_DEG) {
                mAngle -= THREE_HUNDRED_SIXTY_DEG;
            }
        }
    }

    private List<MotionEvent> unpackMotionEvent(MotionEvent motionEvent) {
        List<MotionEvent> motionEvents = new ArrayList<>();
        List<PointerProperties> pointerPropertiesList = new ArrayList<>();
        int pointerCount = motionEvent.getPointerCount();
        for (int i = 0; i < pointerCount; i++) {
            PointerProperties pointerProperties = new PointerProperties();
            motionEvent.getPointerProperties(i, pointerProperties);
            pointerPropertiesList.add(pointerProperties);
        }
        PointerProperties[] pointerPropertiesArray = new PointerProperties[pointerPropertiesList
                .size()];
        pointerPropertiesList.toArray(pointerPropertiesArray);

        int historySize = motionEvent.getHistorySize();
        for (int i = 0; i < historySize; i++) {
            List<PointerCoords> pointerCoordsList = new ArrayList<>();
            for (int j = 0; j < pointerCount; j++) {
                PointerCoords pointerCoords = new PointerCoords();
                motionEvent.getHistoricalPointerCoords(j, i, pointerCoords);
                pointerCoordsList.add(pointerCoords);
            }
            motionEvents.add(MotionEvent.obtain(
                    motionEvent.getDownTime(),
                    motionEvent.getHistoricalEventTime(i),
                    motionEvent.getAction(),
                    pointerCount,
                    pointerPropertiesArray,
                    pointerCoordsList.toArray(new PointerCoords[0]),
                    motionEvent.getMetaState(),
                    motionEvent.getButtonState(),
                    motionEvent.getXPrecision(),
                    motionEvent.getYPrecision(),
                    motionEvent.getDeviceId(),
                    motionEvent.getEdgeFlags(),
                    motionEvent.getSource(),
                    motionEvent.getFlags()
            ));
        }

        motionEvents.add(MotionEvent.obtainNoHistory(motionEvent));

        return motionEvents;
    }

    void onSessionEnd() {
        mFirstActualMotionEvent = null;

        for (MotionEvent ev : mRecentMotionEvents) {
            ev.recycle();
        }

        mRecentMotionEvents.clear();

        mDirty = true;
    }
}
