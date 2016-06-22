/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.analytics;

import android.os.Build;
import android.util.Log;
import android.view.MotionEvent;

import java.util.ArrayList;

import static com.android.systemui.statusbar.phone.nano.TouchAnalyticsProto.Session;
import static com.android.systemui.statusbar.phone.nano.TouchAnalyticsProto.Session.PhoneEvent;
import static com.android.systemui.statusbar.phone.nano.TouchAnalyticsProto.Session.SensorEvent;
import static com.android.systemui.statusbar.phone.nano.TouchAnalyticsProto.Session.TouchEvent;

/**
 * Collects touch, sensor and phone events and converts the data to
 * TouchAnalyticsProto.Session.
 */
public class SensorLoggerSession {
    private static final String TAG = "SensorLoggerSession";

    private final long mStartTimestampMillis;
    private final long mStartSystemTimeNanos;

    private long mEndTimestampMillis;
    private int mType;

    private ArrayList<TouchEvent> mMotionEvents = new ArrayList<>();
    private ArrayList<SensorEvent> mSensorEvents = new ArrayList<>();
    private ArrayList<PhoneEvent> mPhoneEvents = new ArrayList<>();
    private int mTouchAreaHeight;
    private int mTouchAreaWidth;
    private int mResult = Session.UNKNOWN;

    public SensorLoggerSession(long startTimestampMillis, long startSystemTimeNanos) {
        mStartTimestampMillis = startTimestampMillis;
        mStartSystemTimeNanos = startSystemTimeNanos;
        mType = Session.REAL;
    }

    public void setType(int type) {
        mType = type;
    }

    public void end(long endTimestampMillis, int result) {
        mResult = result;
        mEndTimestampMillis = endTimestampMillis;

        if (DataCollector.DEBUG) {
            Log.d(TAG, "Ending session result=" + result + " it lasted for " +
                    (float) (mEndTimestampMillis - mStartTimestampMillis) / 1000f + "s");
        }
    }

    public void addMotionEvent(MotionEvent motionEvent) {
        TouchEvent event = motionEventToProto(motionEvent);
        mMotionEvents.add(event);
    }

    public void addSensorEvent(android.hardware.SensorEvent eventOrig, long systemTimeNanos) {
        SensorEvent event = sensorEventToProto(eventOrig, systemTimeNanos);
        mSensorEvents.add(event);
    }

    public void addPhoneEvent(int eventType, long systemTimeNanos) {
        PhoneEvent event = phoneEventToProto(eventType, systemTimeNanos);
        mPhoneEvents.add(event);
    }


    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Session{");
        sb.append("mStartTimestampMillis=").append(mStartTimestampMillis);
        sb.append(", mStartSystemTimeNanos=").append(mStartSystemTimeNanos);
        sb.append(", mEndTimestampMillis=").append(mEndTimestampMillis);
        sb.append(", mResult=").append(mResult);
        sb.append(", mTouchAreaHeight=").append(mTouchAreaHeight);
        sb.append(", mTouchAreaWidth=").append(mTouchAreaWidth);
        sb.append(", mMotionEvents=[size=").append(mMotionEvents.size()).append("]");
        sb.append(", mSensorEvents=[size=").append(mSensorEvents.size()).append("]");
        sb.append(", mPhoneEvents=[size=").append(mPhoneEvents.size()).append("]");
        sb.append('}');
        return sb.toString();
    }

    public Session toProto() {
        Session proto = new Session();
        proto.setStartTimestampMillis(mStartTimestampMillis);
        proto.setDurationMillis(mEndTimestampMillis - mStartTimestampMillis);
        proto.setBuild(Build.FINGERPRINT);
        proto.setResult(mResult);
        proto.setType(mType);
        proto.sensorEvents = mSensorEvents.toArray(proto.sensorEvents);
        proto.touchEvents = mMotionEvents.toArray(proto.touchEvents);
        proto.phoneEvents = mPhoneEvents.toArray(proto.phoneEvents);
        proto.setTouchAreaWidth(mTouchAreaWidth);
        proto.setTouchAreaHeight(mTouchAreaHeight);
        return proto;
    }

    private PhoneEvent phoneEventToProto(int eventType, long sysTimeNanos) {
        PhoneEvent proto = new PhoneEvent();
        proto.setType(eventType);
        proto.setTimeOffsetNanos(sysTimeNanos - mStartSystemTimeNanos);
        return proto;
    }

    private SensorEvent sensorEventToProto(android.hardware.SensorEvent ev, long sysTimeNanos) {
        SensorEvent proto = new SensorEvent();
        proto.setType(ev.sensor.getType());
        proto.setTimeOffsetNanos(sysTimeNanos - mStartSystemTimeNanos);
        proto.setTimestamp(ev.timestamp);
        proto.values = ev.values.clone();
        return proto;
    }

    private TouchEvent motionEventToProto(MotionEvent ev) {
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
        }
        return proto;
    }

    public void setTouchArea(int width, int height) {
        mTouchAreaWidth = width;
        mTouchAreaHeight = height;
    }

    public int getResult() {
        return mResult;
    }

    public long getStartTimestampMillis() {
        return mStartTimestampMillis;
    }
}
