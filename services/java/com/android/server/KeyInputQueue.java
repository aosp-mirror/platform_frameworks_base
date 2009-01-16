/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server;

import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.os.PowerManager;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.RawInputEvent;
import android.view.Surface;
import android.view.WindowManagerPolicy;

public abstract class KeyInputQueue {
    static final String TAG = "KeyInputQueue";

    SparseArray<InputDevice> mDevices = new SparseArray<InputDevice>();
    
    int mGlobalMetaState = 0;
    boolean mHaveGlobalMetaState = false;
    
    final QueuedEvent mFirst;
    final QueuedEvent mLast;
    QueuedEvent mCache;
    int mCacheCount;

    Display mDisplay = null;
    
    int mOrientation = Surface.ROTATION_0;
    int[] mKeyRotationMap = null;
    
    PowerManager.WakeLock mWakeLock;

    static final int[] KEY_90_MAP = new int[] {
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN,
    };
    
    static final int[] KEY_180_MAP = new int[] {
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    };
    
    static final int[] KEY_270_MAP = new int[] {
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
    };
    
    public static final int FILTER_REMOVE = 0;
    public static final int FILTER_KEEP = 1;
    public static final int FILTER_ABORT = -1;
    
    public interface FilterCallback {
        int filterEvent(QueuedEvent ev);
    }
    
    static class QueuedEvent {
        InputDevice inputDevice;
        long when;
        int flags; // From the raw event
        int classType; // One of the class constants in InputEvent
        Object event;
        boolean inQueue;

        void copyFrom(QueuedEvent that) {
            this.inputDevice = that.inputDevice;
            this.when = that.when;
            this.flags = that.flags;
            this.classType = that.classType;
            this.event = that.event;
        }

        @Override
        public String toString() {
            return "QueuedEvent{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + event + "}";
        }
        
        // not copied
        QueuedEvent prev;
        QueuedEvent next;
    }

    KeyInputQueue(Context context) {
        PowerManager pm = (PowerManager)context.getSystemService(
                                                        Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                                        "KeyInputQueue");
        mWakeLock.setReferenceCounted(false);

        mFirst = new QueuedEvent();
        mLast = new QueuedEvent();
        mFirst.next = mLast;
        mLast.prev = mFirst;

        mThread.start();
    }

    public void setDisplay(Display display) {
        mDisplay = display;
    }
    
    public void getInputConfiguration(Configuration config) {
        synchronized (mFirst) {
            config.touchscreen = Configuration.TOUCHSCREEN_NOTOUCH;
            config.keyboard = Configuration.KEYBOARD_NOKEYS;
            config.navigation = Configuration.NAVIGATION_NONAV;
            
            final int N = mDevices.size();
            for (int i=0; i<N; i++) {
                InputDevice d = mDevices.valueAt(i);
                if (d != null) {
                    if ((d.classes&RawInputEvent.CLASS_TOUCHSCREEN) != 0) {
                        config.touchscreen
                                = Configuration.TOUCHSCREEN_FINGER;
                        //Log.i("foo", "***** HAVE TOUCHSCREEN!");
                    }
                    if ((d.classes&RawInputEvent.CLASS_ALPHAKEY) != 0) {
                        config.keyboard
                                = Configuration.KEYBOARD_QWERTY;
                        //Log.i("foo", "***** HAVE QWERTY!");
                    }
                    if ((d.classes&RawInputEvent.CLASS_TRACKBALL) != 0) {
                        config.navigation
                                = Configuration.NAVIGATION_TRACKBALL;
                        //Log.i("foo", "***** HAVE TRACKBALL!");
                    }
                }
            }
        }
    }
    
    public static native String getDeviceName(int deviceId);
    public static native int getDeviceClasses(int deviceId);
    public static native boolean getAbsoluteInfo(int deviceId, int axis,
            InputDevice.AbsoluteInfo outInfo);
    public static native int getSwitchState(int sw);
    public static native int getSwitchState(int deviceId, int sw);
    public static native int getScancodeState(int sw);
    public static native int getScancodeState(int deviceId, int sw);
    public static native int getKeycodeState(int sw);
    public static native int getKeycodeState(int deviceId, int sw);
    
    public static KeyEvent newKeyEvent(InputDevice device, long downTime,
            long eventTime, boolean down, int keycode, int repeatCount,
            int scancode, int flags) {
        return new KeyEvent(
                downTime, eventTime,
                down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                keycode, repeatCount,
                device != null ? device.mMetaKeysState : 0,
                device != null ? device.id : -1, scancode,
                flags);
    }
    
    Thread mThread = new Thread("InputDeviceReader") {
        public void run() {
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
            
            try {
                RawInputEvent ev = new RawInputEvent();
                while (true) {
                    InputDevice di;

                    // block, doesn't release the monitor
                    readEvent(ev);

                    boolean send = false;
                    boolean configChanged = false;
                    
                    if (false) {
                        Log.i(TAG, "Input event: dev=0x"
                                + Integer.toHexString(ev.deviceId)
                                + " type=0x" + Integer.toHexString(ev.type)
                                + " scancode=" + ev.scancode
                                + " keycode=" + ev.keycode
                                + " value=" + ev.value);
                    }
                    
                    if (ev.type == RawInputEvent.EV_DEVICE_ADDED) {
                        synchronized (mFirst) {
                            di = newInputDevice(ev.deviceId);
                            mDevices.put(ev.deviceId, di);
                            configChanged = true;
                        }
                    } else if (ev.type == RawInputEvent.EV_DEVICE_REMOVED) {
                        synchronized (mFirst) {
                            Log.i(TAG, "Device removed: id=0x"
                                    + Integer.toHexString(ev.deviceId));
                            di = mDevices.get(ev.deviceId);
                            if (di != null) {
                                mDevices.delete(ev.deviceId);
                                configChanged = true;
                            } else {
                                Log.w(TAG, "Bad device id: " + ev.deviceId);
                            }
                        }
                    } else {
                        di = getInputDevice(ev.deviceId);
                        
                        // first crack at it
                        send = preprocessEvent(di, ev);

                        if (ev.type == RawInputEvent.EV_KEY) {
                            di.mMetaKeysState = makeMetaState(ev.keycode,
                                    ev.value != 0, di.mMetaKeysState);
                            mHaveGlobalMetaState = false;
                        }
                    }

                    if (di == null) {
                        continue;
                    }
                    
                    if (configChanged) {
                        synchronized (mFirst) {
                            addLocked(di, SystemClock.uptimeMillis(), 0,
                                    RawInputEvent.CLASS_CONFIGURATION_CHANGED,
                                    null);
                        }
                    }
                    
                    if (!send) {
                        continue;
                    }
                    
                    synchronized (mFirst) {
                        // NOTE: The event timebase absolutely must be the same
                        // timebase as SystemClock.uptimeMillis().
                        //curTime = gotOne ? ev.when : SystemClock.uptimeMillis();
                        final long curTime = SystemClock.uptimeMillis();
                        //Log.i(TAG, "curTime=" + curTime + ", systemClock=" + SystemClock.uptimeMillis());
                        
                        final int classes = di.classes;
                        final int type = ev.type;
                        final int scancode = ev.scancode;
                        send = false;
                        
                        // Is it a key event?
                        if (type == RawInputEvent.EV_KEY &&
                                (classes&RawInputEvent.CLASS_KEYBOARD) != 0 &&
                                (scancode < RawInputEvent.BTN_FIRST ||
                                        scancode > RawInputEvent.BTN_LAST)) {
                            boolean down;
                            if (ev.value != 0) {
                                down = true;
                                di.mDownTime = curTime;
                            } else {
                                down = false;
                            }
                            int keycode = rotateKeyCodeLocked(ev.keycode);
                            addLocked(di, curTime, ev.flags,
                                    RawInputEvent.CLASS_KEYBOARD,
                                    newKeyEvent(di, di.mDownTime, curTime, down,
                                            keycode, 0, scancode,
                                            ((ev.flags & WindowManagerPolicy.FLAG_WOKE_HERE) != 0)
                                             ? KeyEvent.FLAG_WOKE_HERE : 0));
                        } else if (ev.type == RawInputEvent.EV_KEY) {
                            if (ev.scancode == RawInputEvent.BTN_TOUCH &&
                                    (classes&RawInputEvent.CLASS_TOUCHSCREEN) != 0) {
                                di.mAbs.changed = true;
                                di.mAbs.down = ev.value != 0;
                            }
                            if (ev.scancode == RawInputEvent.BTN_MOUSE &&
                                    (classes&RawInputEvent.CLASS_TRACKBALL) != 0) {
                                di.mRel.changed = true;
                                di.mRel.down = ev.value != 0;
                                send = true;
                            }
    
                        } else if (ev.type == RawInputEvent.EV_ABS &&
                                (classes&RawInputEvent.CLASS_TOUCHSCREEN) != 0) {
                            if (ev.scancode == RawInputEvent.ABS_X) {
                                di.mAbs.changed = true;
                                di.mAbs.x = ev.value;
                            } else if (ev.scancode == RawInputEvent.ABS_Y) {
                                di.mAbs.changed = true;
                                di.mAbs.y = ev.value;
                            } else if (ev.scancode == RawInputEvent.ABS_PRESSURE) {
                                di.mAbs.changed = true;
                                di.mAbs.pressure = ev.value;
                            } else if (ev.scancode == RawInputEvent.ABS_TOOL_WIDTH) {
                                di.mAbs.changed = true;
                                di.mAbs.size = ev.value;
                            }
    
                        } else if (ev.type == RawInputEvent.EV_REL &&
                                (classes&RawInputEvent.CLASS_TRACKBALL) != 0) {
                            // Add this relative movement into our totals.
                            if (ev.scancode == RawInputEvent.REL_X) {
                                di.mRel.changed = true;
                                di.mRel.x += ev.value;
                            } else if (ev.scancode == RawInputEvent.REL_Y) {
                                di.mRel.changed = true;
                                di.mRel.y += ev.value;
                            }
                        }
                        
                        if (send || ev.type == RawInputEvent.EV_SYN) {
                            if (mDisplay != null) {
                                if (!mHaveGlobalMetaState) {
                                    computeGlobalMetaStateLocked();
                                }
                                
                                MotionEvent me;
                                me = di.mAbs.generateMotion(di, curTime, true,
                                        mDisplay, mOrientation, mGlobalMetaState);
                                if (false) Log.v(TAG, "Absolute: x=" + di.mAbs.x
                                        + " y=" + di.mAbs.y + " ev=" + me);
                                if (me != null) {
                                    if (WindowManagerPolicy.WATCH_POINTER) {
                                        Log.i(TAG, "Enqueueing: " + me);
                                    }
                                    addLocked(di, curTime, ev.flags,
                                            RawInputEvent.CLASS_TOUCHSCREEN, me);
                                }
                                me = di.mRel.generateMotion(di, curTime, false,
                                        mDisplay, mOrientation, mGlobalMetaState);
                                if (false) Log.v(TAG, "Relative: x=" + di.mRel.x
                                        + " y=" + di.mRel.y + " ev=" + me);
                                if (me != null) {
                                    addLocked(di, curTime, ev.flags,
                                            RawInputEvent.CLASS_TRACKBALL, me);
                                }
                            }
                        }
                    }
                }
            }
            catch (RuntimeException exc) {
                Log.e(TAG, "InputReaderThread uncaught exception", exc);
            }
        }
    };

    /**
     * Returns a new meta state for the given keys and old state.
     */
    private static final int makeMetaState(int keycode, boolean down, int old) {
        int mask;
        switch (keycode) {
        case KeyEvent.KEYCODE_ALT_LEFT:
            mask = KeyEvent.META_ALT_LEFT_ON;
            break;
        case KeyEvent.KEYCODE_ALT_RIGHT:
            mask = KeyEvent.META_ALT_RIGHT_ON;
            break;
        case KeyEvent.KEYCODE_SHIFT_LEFT:
            mask = KeyEvent.META_SHIFT_LEFT_ON;
            break;
        case KeyEvent.KEYCODE_SHIFT_RIGHT:
            mask = KeyEvent.META_SHIFT_RIGHT_ON;
            break;
        case KeyEvent.KEYCODE_SYM:
            mask = KeyEvent.META_SYM_ON;
            break;
        default:
            return old;
        }
        int result = ~(KeyEvent.META_ALT_ON | KeyEvent.META_SHIFT_ON)
                    & (down ? (old | mask) : (old & ~mask));
        if (0 != (result & (KeyEvent.META_ALT_LEFT_ON | KeyEvent.META_ALT_RIGHT_ON))) {
            result |= KeyEvent.META_ALT_ON;
        }
        if (0 != (result & (KeyEvent.META_SHIFT_LEFT_ON | KeyEvent.META_SHIFT_RIGHT_ON))) {
            result |= KeyEvent.META_SHIFT_ON;
        }
        return result;
    }

    private void computeGlobalMetaStateLocked() {
        int i = mDevices.size();
        mGlobalMetaState = 0;
        while ((--i) >= 0) {
            mGlobalMetaState |= mDevices.valueAt(i).mMetaKeysState;
        }
        mHaveGlobalMetaState = true;
    }
    
    /*
     * Return true if you want the event to get passed on to the 
     * rest of the system, and false if you've handled it and want
     * it dropped.
     */
    abstract boolean preprocessEvent(InputDevice device, RawInputEvent event);

    InputDevice getInputDevice(int deviceId) {
        synchronized (mFirst) {
            return getInputDeviceLocked(deviceId);
        }
    }
    
    private InputDevice getInputDeviceLocked(int deviceId) {
        return mDevices.get(deviceId);
    }

    public void setOrientation(int orientation) {
        synchronized(mFirst) {
            mOrientation = orientation;
            switch (orientation) {
                case Surface.ROTATION_90:
                    mKeyRotationMap = KEY_90_MAP;
                    break;
                case Surface.ROTATION_180:
                    mKeyRotationMap = KEY_180_MAP;
                    break;
                case Surface.ROTATION_270:
                    mKeyRotationMap = KEY_270_MAP;
                    break;
                default:
                    mKeyRotationMap = null;
                    break;
            }
        }
    }
    
    public int rotateKeyCode(int keyCode) {
        synchronized(mFirst) {
            return rotateKeyCodeLocked(keyCode);
        }
    }
    
    private int rotateKeyCodeLocked(int keyCode) {
        int[] map = mKeyRotationMap;
        if (map != null) {
            final int N = map.length;
            for (int i=0; i<N; i+=2) {
                if (map[i] == keyCode) {
                    return map[i+1];
                }
            }
        }
        return keyCode;
    }
    
    boolean hasEvents() {
        synchronized (mFirst) {
            return mFirst.next != mLast;
        }
    }
    
    /*
     * returns true if we returned an event, and false if we timed out
     */
    QueuedEvent getEvent(long timeoutMS) {
        long begin = SystemClock.uptimeMillis();
        final long end = begin+timeoutMS;
        long now = begin;
        synchronized (mFirst) {
            while (mFirst.next == mLast && end > now) {
                try {
                    mWakeLock.release();
                    mFirst.wait(end-now);
                }
                catch (InterruptedException e) {
                }
                now = SystemClock.uptimeMillis();
                if (begin > now) {
                    begin = now;
                }
            }
            if (mFirst.next == mLast) {
                return null;
            }
            QueuedEvent p = mFirst.next;
            mFirst.next = p.next;
            mFirst.next.prev = mFirst;
            p.inQueue = false;
            return p;
        }
    }

    void recycleEvent(QueuedEvent ev) {
        synchronized (mFirst) {
            //Log.i(TAG, "Recycle event: " + ev);
            if (ev.event == ev.inputDevice.mAbs.currentMove) {
                ev.inputDevice.mAbs.currentMove = null;
            }
            if (ev.event == ev.inputDevice.mRel.currentMove) {
                if (false) Log.i(TAG, "Detach rel " + ev.event);
                ev.inputDevice.mRel.currentMove = null;
                ev.inputDevice.mRel.x = 0;
                ev.inputDevice.mRel.y = 0;
            }
            recycleLocked(ev);
        }
    }
    
    void filterQueue(FilterCallback cb) {
        synchronized (mFirst) {
            QueuedEvent cur = mLast.prev;
            while (cur.prev != null) {
                switch (cb.filterEvent(cur)) {
                    case FILTER_REMOVE:
                        cur.prev.next = cur.next;
                        cur.next.prev = cur.prev;
                        break;
                    case FILTER_ABORT:
                        return;
                }
                cur = cur.prev;
            }
        }
    }
    
    private QueuedEvent obtainLocked(InputDevice device, long when,
            int flags, int classType, Object event) {
        QueuedEvent ev;
        if (mCacheCount == 0) {
            ev = new QueuedEvent();
        } else {
            ev = mCache;
            ev.inQueue = false;
            mCache = ev.next;
            mCacheCount--;
        }
        ev.inputDevice = device;
        ev.when = when;
        ev.flags = flags;
        ev.classType = classType;
        ev.event = event;
        return ev;
    }

    private void recycleLocked(QueuedEvent ev) {
        if (ev.inQueue) {
            throw new RuntimeException("Event already in queue!");
        }
        if (mCacheCount < 10) {
            mCacheCount++;
            ev.next = mCache;
            mCache = ev;
            ev.inQueue = true;
        }
    }

    private void addLocked(InputDevice device, long when, int flags,
            int classType, Object event) {
        boolean poke = mFirst.next == mLast;

        QueuedEvent ev = obtainLocked(device, when, flags, classType, event);
        QueuedEvent p = mLast.prev;
        while (p != mFirst && ev.when < p.when) {
            p = p.prev;
        }

        ev.next = p.next;
        ev.prev = p;
        p.next = ev;
        ev.next.prev = ev;
        ev.inQueue = true;

        if (poke) {
            mFirst.notify();
            mWakeLock.acquire();
        }
    }

    private InputDevice newInputDevice(int deviceId) {
        int classes = getDeviceClasses(deviceId);
        String name = getDeviceName(deviceId);
        Log.i(TAG, "Device added: id=0x" + Integer.toHexString(deviceId)
                + ", name=" + name
                + ", classes=" + Integer.toHexString(classes));
        InputDevice.AbsoluteInfo absX;
        InputDevice.AbsoluteInfo absY;
        InputDevice.AbsoluteInfo absPressure;
        InputDevice.AbsoluteInfo absSize;
        if ((classes&RawInputEvent.CLASS_TOUCHSCREEN) != 0) {
            absX = loadAbsoluteInfo(deviceId, RawInputEvent.ABS_X, "X");
            absY = loadAbsoluteInfo(deviceId, RawInputEvent.ABS_Y, "Y");
            absPressure = loadAbsoluteInfo(deviceId, RawInputEvent.ABS_PRESSURE, "Pressure");
            absSize = loadAbsoluteInfo(deviceId, RawInputEvent.ABS_TOOL_WIDTH, "Size");
        } else {
            absX = null;
            absY = null;
            absPressure = null;
            absSize = null;
        }
        
        return new InputDevice(deviceId, classes, name, absX, absY, absPressure, absSize);
    }
    
    private InputDevice.AbsoluteInfo loadAbsoluteInfo(int id, int channel,
            String name) {
        InputDevice.AbsoluteInfo info = new InputDevice.AbsoluteInfo();
        if (getAbsoluteInfo(id, channel, info)
                && info.minValue != info.maxValue) {
            Log.i(TAG, "  " + name + ": min=" + info.minValue
                    + " max=" + info.maxValue
                    + " flat=" + info.flat
                    + " fuzz=" + info.fuzz);
            info.range = info.maxValue-info.minValue;
            return info;
        }
        Log.i(TAG, "  " + name + ": unknown values");
        return null;
    }
    private static native boolean readEvent(RawInputEvent outEvent);
}
