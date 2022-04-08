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

package com.android.server.accessibility.utils;

import android.util.Log;
import android.view.MotionEvent;

import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;

/**
 * This class compares two motion events using a subset of their attributes: actionMasked, downTime,
 * eventTime, and location. If two events match they are considered to be effectively equal.
 */
public class MotionEventMatcher extends TypeSafeMatcher<MotionEvent> {
    private static final String LOG_TAG = "MotionEventMatcher";
    long mDownTime;
    long mEventTime;
    long mActionMasked;
    int mX;
    int mY;

    MotionEventMatcher(long downTime, long eventTime, int actionMasked, int x, int y) {
        mDownTime = downTime;
        mEventTime = eventTime;
        mActionMasked = actionMasked;
        mX = x;
        mY = y;
    }

    public MotionEventMatcher(MotionEvent event) {
        this(
                event.getDownTime(),
                event.getEventTime(),
                event.getActionMasked(),
                (int) event.getX(),
                (int) event.getY());
    }

    void offsetTimesBy(long timeOffset) {
        mDownTime += timeOffset;
        mEventTime += timeOffset;
    }

    @Override
    public boolean matchesSafely(MotionEvent event) {
        if ((event.getDownTime() == mDownTime)
                && (event.getEventTime() == mEventTime)
                && (event.getActionMasked() == mActionMasked)
                && ((int) event.getX() == mX)
                && ((int) event.getY() == mY)) {
            return true;
        }
        Log.e(LOG_TAG, "MotionEvent match failed");
        Log.e(LOG_TAG, "event.getDownTime() = " + event.getDownTime() + ", expected " + mDownTime);
        Log.e(
                LOG_TAG,
                "event.getEventTime() = " + event.getEventTime() + ", expected " + mEventTime);
        Log.e(
                LOG_TAG,
                "event.getActionMasked() = "
                        + event.getActionMasked()
                        + ", expected "
                        + mActionMasked);
        Log.e(LOG_TAG, "event.getX() = " + event.getX() + ", expected " + mX);
        Log.e(LOG_TAG, "event.getY() = " + event.getY() + ", expected " + mY);
        return false;
    }

    @Override
    public void describeTo(Description description) {
        description.appendText("Motion event matcher");
    }
}
