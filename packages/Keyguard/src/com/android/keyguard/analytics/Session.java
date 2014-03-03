/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.keyguard.analytics;

import android.os.Build;
import android.util.Slog;
import android.view.MotionEvent;

import java.util.ArrayList;

import static com.android.keyguard.analytics.KeyguardAnalyticsProtos.Session.SensorEvent;
import static com.android.keyguard.analytics.KeyguardAnalyticsProtos.Session.TouchEvent;

/**
 * Records data about one keyguard session.
 *
 * The recorded data contains start and end of the session, whether it unlocked the device
 * successfully, sensor data and touch data.
 *
 * If the keyguard is secure, the recorded touch data will correlate or contain the user pattern or
 * PIN. If this is not desired, the touch coordinates can be redacted before serialization.
 */
public class Session {

    private static final String TAG = "KeyguardAnalytics";
    private static final boolean DEBUG = false;

    /**
     * The user has failed to unlock the device in this session.
     */
    public static final int RESULT_FAILURE = KeyguardAnalyticsProtos.Session.FAILURE;
    /**
     * The user has succeeded in unlocking the device in this session.
     */
    public static final int RESULT_SUCCESS = KeyguardAnalyticsProtos.Session.SUCCESS;

    /**
     * It is unknown how the session with the keyguard ended.
     */
    public static final int RESULT_UNKNOWN = KeyguardAnalyticsProtos.Session.UNKNOWN;

    /**
     * This session took place on an insecure keyguard.
     */
    public static final int TYPE_KEYGUARD_INSECURE
            = KeyguardAnalyticsProtos.Session.KEYGUARD_INSECURE;

    /**
     * This session took place on an secure keyguard.
     */
    public static final int TYPE_KEYGUARD_SECURE
            = KeyguardAnalyticsProtos.Session.KEYGUARD_SECURE;

    /**
     * This session took place during a fake wake up of the device.
     */
    public static final int TYPE_RANDOM_WAKEUP = KeyguardAnalyticsProtos.Session.RANDOM_WAKEUP;


    private final PointerTracker mPointerTracker = new PointerTracker();

    private final long mStartTimestampMillis;
    private final long mStartSystemTimeNanos;
    private final int mType;

    private boolean mRedactTouchEvents;
    private ArrayList<TouchEvent> mMotionEvents = new ArrayList<TouchEvent>(200);
    private ArrayList<SensorEvent> mSensorEvents = new ArrayList<SensorEvent>(600);
    private int mTouchAreaHeight;
    private int mTouchAreaWidth;

    private long mEndTimestampMillis;
    private int mResult;
    private boolean mEnded;

    public Session(long startTimestampMillis, long startSystemTimeNanos, int type) {
        mStartTimestampMillis = startTimestampMillis;
        mStartSystemTimeNanos = startSystemTimeNanos;
        mType = type;
    }

    public void end(long endTimestampMillis, int result) {
        mEnded = true;
        mEndTimestampMillis = endTimestampMillis;
        mResult = result;
    }

    public void addMotionEvent(MotionEvent motionEvent) {
        if (mEnded) {
            return;
        }
        mPointerTracker.addMotionEvent(motionEvent);
        mMotionEvents.add(protoFromMotionEvent(motionEvent));
    }

    public void addSensorEvent(android.hardware.SensorEvent eventOrig, long systemTimeNanos) {
        if (mEnded) {
            return;
        }
        SensorEvent event = protoFromSensorEvent(eventOrig, systemTimeNanos);
        mSensorEvents.add(event);
        if (DEBUG) {
            Slog.v(TAG, String.format("addSensorEvent(name=%s, values[0]=%f",
                    event.getType(), event.values[0]));
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Session{");
        sb.append("mType=").append(mType);
        sb.append(", mStartTimestampMillis=").append(mStartTimestampMillis);
        sb.append(", mStartSystemTimeNanos=").append(mStartSystemTimeNanos);
        sb.append(", mEndTimestampMillis=").append(mEndTimestampMillis);
        sb.append(", mResult=").append(mResult);
        sb.append(", mRedactTouchEvents=").append(mRedactTouchEvents);
        sb.append(", mTouchAreaHeight=").append(mTouchAreaHeight);
        sb.append(", mTouchAreaWidth=").append(mTouchAreaWidth);
        sb.append(", mMotionEvents=[size=").append(mMotionEvents.size()).append("]");
        sb.append(", mSensorEvents=[size=").append(mSensorEvents.size()).append("]");
        sb.append('}');
        return sb.toString();
    }

    public KeyguardAnalyticsProtos.Session toProto() {
        KeyguardAnalyticsProtos.Session proto = new KeyguardAnalyticsProtos.Session();
        proto.setStartTimestampMillis(mStartTimestampMillis);
        proto.setDurationMillis(mEndTimestampMillis - mStartTimestampMillis);
        proto.setBuild(Build.FINGERPRINT);
        proto.setResult(mResult);
        proto.sensorEvents = mSensorEvents.toArray(proto.sensorEvents);
        proto.touchEvents = mMotionEvents.toArray(proto.touchEvents);
        proto.setTouchAreaWidth(mTouchAreaWidth);
        proto.setTouchAreaHeight(mTouchAreaHeight);
        proto.setType(mType);
        if (mRedactTouchEvents) {
            redactTouchEvents(proto.touchEvents);
        }
        return proto;
    }

    private void redactTouchEvents(TouchEvent[] touchEvents) {
        for (int i = 0; i < touchEvents.length; i++) {
            TouchEvent t = touchEvents[i];
            for (int j = 0; j < t.pointers.length; j++) {
                TouchEvent.Pointer p = t.pointers[j];
                p.clearX();
                p.clearY();
            }
            t.setRedacted(true);
        }
    }

    private SensorEvent protoFromSensorEvent(android.hardware.SensorEvent ev, long sysTimeNanos) {
        SensorEvent proto = new SensorEvent();
        proto.setType(ev.sensor.getType());
        proto.setTimeOffsetNanos(sysTimeNanos - mStartSystemTimeNanos);
        proto.setTimestamp(ev.timestamp);
        proto.values = ev.values.clone();
        return proto;
    }

    private TouchEvent protoFromMotionEvent(MotionEvent ev) {
        int count = ev.getPointerCount();
        TouchEvent proto = new TouchEvent();
        proto.setTimeOffsetNanos(ev.getEventTimeNano() - mStartSystemTimeNanos);
        proto.setAction(ev.getActionMasked());
        proto.setActionIndex(ev.getActionIndex());
        proto.pointers = new TouchEvent.Pointer[count];
        for (int i = 0; i < count; i++) {
            TouchEvent.Pointer p = new TouchEvent.Pointer();
            p.setX(ev.getX(i));
            p.setY(ev.getY(i));
            p.setSize(ev.getSize(i));
            p.setPressure(ev.getPressure(i));
            p.setId(ev.getPointerId(i));
            proto.pointers[i] = p;
            if ((ev.getActionMasked() == MotionEvent.ACTION_POINTER_UP && ev.getActionIndex() == i)
                    || ev.getActionMasked() == MotionEvent.ACTION_UP) {
                p.boundingBox = mPointerTracker.getPointerBoundingBox(p.getId());
                p.setLength(mPointerTracker.getPointerLength(p.getId()));
            }
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
            proto.boundingBox = mPointerTracker.getBoundingBox();
        }
        return proto;
    }

    /**
     * Discards the x / y coordinates of the touch events on serialization. Retained are the
     * size of the individual and overall bounding boxes and the length of each pointer's gesture.
     */
    public void setRedactTouchEvents() {
        mRedactTouchEvents = true;
    }

    public void setTouchArea(int width, int height) {
        mTouchAreaWidth = width;
        mTouchAreaHeight = height;
    }

    public long getStartTimestampMillis() {
        return mStartTimestampMillis;
    }
}
