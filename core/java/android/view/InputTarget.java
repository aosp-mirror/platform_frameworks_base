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

package android.view;

/**
 * An input target specifies how an input event is to be dispatched to a particular window
 * including the window's input channel, control flags, a timeout, and an X / Y offset to
 * be added to input event coordinates to compensate for the absolute position of the
 * window area.
 * 
 * These parameters are used by the native input dispatching code.
 * @hide
 */
public class InputTarget {
    public InputChannel mInputChannel;
    public int mFlags;
    public long mTimeoutNanos;
    public float mXOffset;
    public float mYOffset;
    
    /**
     * This flag indicates that subsequent event delivery should be held until the
     * current event is delivered to this target or a timeout occurs.
     */
    public static int FLAG_SYNC = 0x01;
    
    /**
     * This flag indicates that a MotionEvent with ACTION_DOWN falls outside of the area of
     * this target and so should instead be delivered as an ACTION_OUTSIDE to this target.
     */
    public static int FLAG_OUTSIDE = 0x02;
    
    /*
     * This flag indicates that a KeyEvent or MotionEvent is being canceled.
     * In the case of a key event, it should be delivered with KeyEvent.FLAG_CANCELED set.
     * In the case of a motion event, it should be delivered as MotionEvent.ACTION_CANCEL.
     */
    public static int FLAG_CANCEL = 0x04;
    
    public void recycle() {
        mInputChannel = null;
    }
}
