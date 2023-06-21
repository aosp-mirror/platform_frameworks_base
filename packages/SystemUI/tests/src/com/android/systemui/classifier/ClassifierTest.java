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

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dock.DockManagerFake;
import com.android.systemui.statusbar.policy.BatteryController;

import org.junit.After;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

public class ClassifierTest extends SysuiTestCase {

    private FalsingDataProvider mDataProvider;
    private final List<MotionEvent> mMotionEvents = new ArrayList<>();
    private float mOffsetX = 0;
    private float mOffsetY = 0;
    @Mock
    private BatteryController mBatteryController;
    private FoldStateListener mFoldStateListener = new FoldStateListener(mContext);
    private final DockManagerFake mDockManager = new DockManagerFake();

    public void setup() {
        MockitoAnnotations.initMocks(this);
        DisplayMetrics displayMetrics = new DisplayMetrics();
        displayMetrics.xdpi = 100;
        displayMetrics.ydpi = 100;
        displayMetrics.widthPixels = 1000;
        displayMetrics.heightPixels = 1000;
        mDataProvider = new FalsingDataProvider(
                displayMetrics, mBatteryController, mFoldStateListener, mDockManager, false);
    }

    @After
    public void tearDown() {
        resetDataProvider();
    }

    protected FalsingDataProvider getDataProvider() {
        return mDataProvider;
    }

    protected void setOffsetX(float offsetX) {
        mOffsetX = offsetX;
    }

    protected void setOffsetY(float offsetY) {
        mOffsetY = offsetY;
    }

    protected void resetDataProvider() {
        for (MotionEvent motionEvent : mMotionEvents) {
            motionEvent.recycle();
        }

        mMotionEvents.clear();

        mDataProvider.onSessionEnd();
    }

    protected MotionEvent appendDownEvent(float x, float y) {
        return appendMotionEvent(MotionEvent.ACTION_DOWN, x, y);
    }

    protected MotionEvent appendDownEvent(float x, float y, long eventTime) {
        return appendMotionEvent(MotionEvent.ACTION_DOWN, x, y, eventTime);
    }

    protected MotionEvent appendMoveEvent(float x, float y) {
        return appendMotionEvent(MotionEvent.ACTION_MOVE, x, y);
    }

    protected MotionEvent appendMoveEvent(float x, float y, long eventTime) {
        return appendMotionEvent(MotionEvent.ACTION_MOVE, x, y, eventTime);
    }


    protected MotionEvent appendUpEvent(float x, float y) {
        return appendMotionEvent(MotionEvent.ACTION_UP, x, y);
    }

    protected MotionEvent appendUpEvent(float x, float y, long eventTime) {
        return appendMotionEvent(MotionEvent.ACTION_UP, x, y, eventTime);
    }

    private MotionEvent appendMotionEvent(int actionType, float x, float y) {

        long eventTime = mMotionEvents.isEmpty() ? 1 : mMotionEvents.get(
                mMotionEvents.size() - 1).getEventTime() + 1;
        return appendMotionEvent(actionType, x, y, eventTime);
    }

    private MotionEvent appendMotionEvent(int actionType, float x, float y, long eventTime) {
        x += mOffsetX;
        y += mOffsetY;

        MotionEvent motionEvent = MotionEvent.obtain(1, eventTime, actionType, x, y,
                0);
        mMotionEvents.add(motionEvent);

        mDataProvider.onMotionEvent(motionEvent);

        return motionEvent;
    }
}
