/*
 * Copyright (C) 2012 The Android Open Source Project
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

/**
 * This class creates trackball events from touchpad events.
 * @see ViewRootImpl
 */
class SimulatedTrackball {

    //The position of the previous touchpad event
    private float mLastTouchpadXPosition;
    private float mLastTouchpadYPosition;
    //Where the touchpad was initially pressed
    private float mTouchpadEnterXPosition;
    private float mTouchpadEnterYPosition;
    //When the last touchpad event occurred
    private long mLastTouchPadStartTimeMs = 0;

    //Change in position allowed during tap events
    private float mTouchSlop;
    private float mTouchSlopSquared;
    //Has the TouchSlop constraint been invalidated
    private boolean mAlwaysInTapRegion = true;

    //Maximum difference in milliseconds between the down and up of a touch event
    //for it to be considered a tap
    //TODO:Read this value from a config file
    private static final int MAX_TAP_TIME = 250;

    public SimulatedTrackball(){
        mTouchSlop = ViewConfiguration.getTouchSlop();
        mTouchSlopSquared = mTouchSlop * mTouchSlop;
    }

    public void updateTrackballDirection(ViewRootImpl viewroot, MotionEvent event){
        //Store what time the touchpad event occurred
        final long time = event.getEventTime();
        MotionEvent trackballEvent;
        switch (event.getAction()) {
            case MotionEvent.ACTION_HOVER_ENTER:
                mLastTouchPadStartTimeMs = time;
                mAlwaysInTapRegion = true;
                mTouchpadEnterXPosition = event.getX();
                mTouchpadEnterYPosition = event.getY();
                break;
            case MotionEvent.ACTION_HOVER_MOVE:
                //Find the difference in position between the two most recent touchpad events
                float deltaX = event.getX() - mLastTouchpadXPosition;
                float deltaY = event.getY() - mLastTouchpadYPosition;

                //TODO: Get simulated trackball configuration parameters
                //Create a trackball event from recorded touchpad event data
                trackballEvent = MotionEvent.obtain(mLastTouchPadStartTimeMs, time,
                        MotionEvent.ACTION_MOVE, deltaX / 50,
                        deltaY / 50, 0, 0, event.getMetaState(),
                        10f, 10f, event.getDeviceId(), 0);
                trackballEvent.setSource(InputDevice.SOURCE_CLASS_TRACKBALL);
                //Add the new event to event queue
                viewroot.enqueueInputEvent(trackballEvent);

                deltaX = event.getX() - mTouchpadEnterXPosition;
                deltaY = event.getY() - mTouchpadEnterYPosition;
                if (mTouchSlopSquared < deltaX * deltaX + deltaY * deltaY){
                    mAlwaysInTapRegion = false;
                }
                break;
            case MotionEvent.ACTION_HOVER_EXIT:
                if (time-mLastTouchPadStartTimeMs<MAX_TAP_TIME && mAlwaysInTapRegion){
                    //Trackball Down
                    trackballEvent = MotionEvent.obtain(mLastTouchPadStartTimeMs, time,
                        MotionEvent.ACTION_DOWN, 0, 0, 0, 0, event.getMetaState(),
                        10f, 10f, event.getDeviceId(), 0);
                    trackballEvent.setSource(InputDevice.SOURCE_CLASS_TRACKBALL);
                    //Add the new event to event queue
                    viewroot.enqueueInputEvent(trackballEvent);

                    //Trackball Release
                    trackballEvent = MotionEvent.obtain(mLastTouchPadStartTimeMs, time,
                        MotionEvent.ACTION_UP, 0, 0, 0, 0, event.getMetaState(),
                        10f, 10f, event.getDeviceId(), 0);
                    trackballEvent.setSource(InputDevice.SOURCE_CLASS_TRACKBALL);
                    //Add the new event to event queue
                    viewroot.enqueueInputEvent(trackballEvent);
                }
                break;
        }
        //Store touch event position
        mLastTouchpadXPosition = event.getX();
        mLastTouchpadYPosition = event.getY();
    }
}
