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
    //When the last touchpad event occurred
    private long mLastTouchPadStartTimeMs = 0;

    public void updateTrackballDirection(ViewRootImpl viewroot, MotionEvent event){
        //Store what time the touchpad event occurred
        final long time = event.getEventTime();
        switch (event.getAction()) {
            case MotionEvent.ACTION_HOVER_ENTER:
                mLastTouchPadStartTimeMs = time;
                break;
            case MotionEvent.ACTION_HOVER_MOVE:
                //Find the difference in position between the two most recent touchpad events
                float deltaX = event.getX()-mLastTouchpadXPosition;
                float deltaY = event.getY()-mLastTouchpadYPosition;
                //TODO: Get simulated trackball configuration parameters
                //Create a trackball event from recorded touchpad event data
                MotionEvent trackballEvent = MotionEvent.obtain(mLastTouchPadStartTimeMs, time,
                        MotionEvent.ACTION_MOVE, deltaX/50, deltaY/50, 0, 0, event.getMetaState(),
                        10f, 10f, event.getDeviceId(), 0);
                trackballEvent.setSource(InputDevice.SOURCE_CLASS_TRACKBALL);
                //Add the new event to event queue
                viewroot.enqueueInputEvent(trackballEvent);
                break;
            case MotionEvent.ACTION_HOVER_EXIT:
                break;
        }
        //Store touch event position
        mLastTouchpadXPosition = event.getX();
        mLastTouchpadYPosition = event.getY();
    }
}
