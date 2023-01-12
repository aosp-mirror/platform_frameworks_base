/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.classifier;

import android.hardware.devicestate.DeviceStateManager.FoldStateListener;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dock.DockManager;
import com.android.systemui.statusbar.policy.BatteryController;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Acts as a cache and utility class for FalsingClassifiers.
 */
@SysUISingleton
public class FalsingDataProvider {

    private static final long MOTION_EVENT_AGE_MS = 1000;
    private static final float THREE_HUNDRED_SIXTY_DEG = (float) (2 * Math.PI);

    private final int mWidthPixels;
    private final int mHeightPixels;
    private BatteryController mBatteryController;
    private final FoldStateListener mFoldStateListener;
    private final DockManager mDockManager;
    private final float mXdpi;
    private final float mYdpi;
    private final List<SessionListener> mSessionListeners = new ArrayList<>();
    private final List<MotionEventListener> mMotionEventListeners = new ArrayList<>();
    private final List<GestureFinalizedListener> mGestureFinalizedListeners = new ArrayList<>();

    private TimeLimitedMotionEventBuffer mRecentMotionEvents =
            new TimeLimitedMotionEventBuffer(MOTION_EVENT_AGE_MS);
    private List<MotionEvent> mPriorMotionEvents = new ArrayList<>();

    private boolean mDirty = true;

    private float mAngle = 0;
    private MotionEvent mFirstRecentMotionEvent;
    private MotionEvent mLastMotionEvent;
    private boolean mJustUnlockedWithFace;
    private boolean mA11YAction;

    @Inject
    public FalsingDataProvider(
            DisplayMetrics displayMetrics,
            BatteryController batteryController,
            FoldStateListener foldStateListener,
            DockManager dockManager) {
        mXdpi = displayMetrics.xdpi;
        mYdpi = displayMetrics.ydpi;
        mWidthPixels = displayMetrics.widthPixels;
        mHeightPixels = displayMetrics.heightPixels;
        mBatteryController = batteryController;
        mFoldStateListener = foldStateListener;
        mDockManager = dockManager;

        FalsingClassifier.logInfo("xdpi, ydpi: " + getXdpi() + ", " + getYdpi());
        FalsingClassifier.logInfo("width, height: " + getWidthPixels() + ", " + getHeightPixels());
    }

    void onMotionEvent(MotionEvent motionEvent) {
        List<MotionEvent> motionEvents = unpackMotionEvent(motionEvent);
        FalsingClassifier.logVerbose("Unpacked into: " + motionEvents.size());
        if (BrightLineFalsingManager.DEBUG) {
            for (MotionEvent m : motionEvents) {
                FalsingClassifier.logVerbose(
                        "x,y,t: " + m.getX() + "," + m.getY() + "," + m.getEventTime());
            }
        }

        if (motionEvent.getActionMasked() == MotionEvent.ACTION_DOWN) {
            // Ensure prior gesture was completed. May be a no-op.
            completePriorGesture();
        }
        mRecentMotionEvents.addAll(motionEvents);

        FalsingClassifier.logVerbose("Size: " + mRecentMotionEvents.size());

        mMotionEventListeners.forEach(listener -> listener.onMotionEvent(motionEvent));

        // We explicitly do not "finalize" a gesture on UP or CANCEL events.
        // We wait for the next gesture to start before marking the prior gesture as complete.  This
        // has multiple benefits. First, it makes it trivial to track the "current" or "recent"
        // gesture, as it will always be found in mRecentMotionEvents. Second, and most importantly,
        // it ensures that the current gesture doesn't get added to this HistoryTracker before it
        // is analyzed.

        mDirty = true;
    }

    void onMotionEventComplete() {
        if (mRecentMotionEvents.isEmpty()) {
            return;
        }
        int action = mRecentMotionEvents.get(mRecentMotionEvents.size() - 1).getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            completePriorGesture();
        }
    }

    private void completePriorGesture() {
        if (!mRecentMotionEvents.isEmpty()) {
            mGestureFinalizedListeners.forEach(listener -> listener.onGestureFinalized(
                    mRecentMotionEvents.get(mRecentMotionEvents.size() - 1).getEventTime()));

            mPriorMotionEvents = mRecentMotionEvents;
            mRecentMotionEvents = new TimeLimitedMotionEventBuffer(MOTION_EVENT_AGE_MS);
        }
        mA11YAction = false;
    }

    /** Returns screen width in pixels. */
    public int getWidthPixels() {
        return mWidthPixels;
    }

    /** Returns screen height in pixels. */
    public int getHeightPixels() {
        return mHeightPixels;
    }

    public float getXdpi() {
        return mXdpi;
    }

    public float getYdpi() {
        return mYdpi;
    }

    public List<MotionEvent> getRecentMotionEvents() {
        return mRecentMotionEvents;
    }

    public List<MotionEvent> getPriorMotionEvents() {
        return mPriorMotionEvents;
    }

    /**
     * Get the first recorded {@link MotionEvent} of the most recent gesture.
     *
     * Note that MotionEvents are not kept forever. As a gesture gets longer in duration, older
     * MotionEvents may expire and be ejected.
     */
    public MotionEvent getFirstRecentMotionEvent() {
        recalculateData();
        return mFirstRecentMotionEvent;
    }

    /** Get the last recorded {@link MotionEvent}. */
    public MotionEvent getLastMotionEvent() {
        recalculateData();
        return mLastMotionEvent;
    }

    /**
     * Returns the angle between the first and last point of the recent points.
     *
     * The angle will be in radians, always be between 0 and 2*PI, inclusive.
     */
    public float getAngle() {
        recalculateData();
        return mAngle;
    }

    /** Returns if the most recent gesture is more horizontal than vertical. */
    public boolean isHorizontal() {
        recalculateData();
        if (mRecentMotionEvents.isEmpty()) {
            return false;
        }

        return Math.abs(mFirstRecentMotionEvent.getX() - mLastMotionEvent.getX()) > Math
                .abs(mFirstRecentMotionEvent.getY() - mLastMotionEvent.getY());
    }

    /**
     * Is the most recent gesture more right than left.
     *
     * This does not mean the gesture is mostly horizontal. Simply that it ended at least one pixel
     * to the right of where it started. See also {@link #isHorizontal()}.
     */
    public boolean isRight() {
        recalculateData();
        if (mRecentMotionEvents.isEmpty()) {
            return false;
        }

        return mLastMotionEvent.getX() > mFirstRecentMotionEvent.getX();
    }

    /** Returns if the most recent gesture is more vertical than horizontal. */
    public boolean isVertical() {
        return !isHorizontal();
    }

    /**
     * Is the most recent gesture more up than down.
     *
     * This does not mean the gesture is mostly vertical. Simply that it ended at least one pixel
     * higher than it started. See also {@link #isVertical()}.
     */
    public boolean isUp() {
        recalculateData();
        if (mRecentMotionEvents.isEmpty()) {
            return false;
        }

        return mLastMotionEvent.getY() < mFirstRecentMotionEvent.getY();
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

    /** Register a {@link SessionListener}. */
    public void addSessionListener(SessionListener listener) {
        mSessionListeners.add(listener);
    }

    /** Unregister a {@link SessionListener}. */
    public void removeSessionListener(SessionListener listener) {
        mSessionListeners.remove(listener);
    }

    /** Register a {@link MotionEventListener}. */
    public void addMotionEventListener(MotionEventListener listener) {
        mMotionEventListeners.add(listener);
    }

    /** Unegister a {@link MotionEventListener}. */
    public void removeMotionEventListener(MotionEventListener listener) {
        mMotionEventListeners.remove(listener);
    }

    /** Register a {@link GestureFinalizedListener}. */
    public void addGestureCompleteListener(GestureFinalizedListener listener) {
        mGestureFinalizedListeners.add(listener);
    }

    /** Unregister a {@link GestureFinalizedListener}. */
    public void removeGestureCompleteListener(GestureFinalizedListener listener) {
        mGestureFinalizedListeners.remove(listener);
    }

    /** Return whether last gesture was an A11y action. */
    public boolean isA11yAction() {
        return mA11YAction;
    }

    /** Set whether last gesture was an A11y action. */
    public void onA11yAction() {
        completePriorGesture();
        this.mA11YAction = true;
    }

    void onSessionStarted() {
        mSessionListeners.forEach(SessionListener::onSessionStarted);
    }

    void onSessionEnd() {
        for (MotionEvent ev : mRecentMotionEvents) {
            ev.recycle();
        }

        mRecentMotionEvents.clear();

        mDirty = true;

        mSessionListeners.forEach(SessionListener::onSessionEnded);
    }

    public boolean isJustUnlockedWithFace() {
        return mJustUnlockedWithFace;
    }

    public void setJustUnlockedWithFace(boolean justUnlockedWithFace) {
        mJustUnlockedWithFace = justUnlockedWithFace;
    }

    /** Returns true if phone is sitting in a dock or is wirelessly charging. */
    public boolean isDocked() {
        return mBatteryController.isWirelessCharging() || mDockManager.isDocked();
    }

    public boolean isFolded() {
        return Boolean.TRUE.equals(mFoldStateListener.getFolded());
    }

    /** Implement to be alerted abotu the beginning and ending of falsing tracking. */
    public interface SessionListener {
        /** Called when the lock screen is shown and falsing-tracking begins. */
        void onSessionStarted();

        /** Called when the lock screen exits and falsing-tracking ends. */
        void onSessionEnded();
    }

    /** Callback for receiving {@link android.view.MotionEvent}s as they are reported. */
    public interface MotionEventListener {
        /** */
        void onMotionEvent(MotionEvent ev);
    }

    /** Callback to be alerted when the current gesture ends. */
    public interface GestureFinalizedListener {
        /**
         * Called just before a new gesture starts.
         *
         * Any pending work on a prior gesture can be considered cemented in place.
         */
        void onGestureFinalized(long completionTimeMs);
    }
}
