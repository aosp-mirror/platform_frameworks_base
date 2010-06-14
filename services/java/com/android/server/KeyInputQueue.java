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
import android.content.res.Resources;
import android.os.Environment;
import android.os.LatencyTimer;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.RawInputEvent;
import android.view.Surface;
import android.view.WindowManagerPolicy;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;

public abstract class KeyInputQueue {
    static final String TAG = "KeyInputQueue";

    static final boolean DEBUG = false;
    static final boolean DEBUG_VIRTUAL_KEYS = false;
    static final boolean DEBUG_POINTERS = false;
    
    /**
     * Turn on some hacks we have to improve the touch interaction with a
     * certain device whose screen currently is not all that good.
     */
    static boolean BAD_TOUCH_HACK = false;
    
    /**
     * Turn on some hacks to improve touch interaction with another device
     * where touch coordinate data can get corrupted.
     */
    static boolean JUMPY_TOUCH_HACK = false;
    
    private static final String EXCLUDED_DEVICES_PATH = "etc/excluded-input-devices.xml";

    final SparseArray<InputDevice> mDevices = new SparseArray<InputDevice>();
    final SparseArray<InputDevice> mIgnoredDevices = new SparseArray<InputDevice>();
    final ArrayList<VirtualKey> mVirtualKeys = new ArrayList<VirtualKey>();
    final HapticFeedbackCallback mHapticFeedbackCallback;
    
    int mGlobalMetaState = 0;
    boolean mHaveGlobalMetaState = false;
    
    final QueuedEvent mFirst;
    final QueuedEvent mLast;
    QueuedEvent mCache;
    int mCacheCount;

    Display mDisplay = null;
    int mDisplayWidth;
    int mDisplayHeight;
    
    int mOrientation = Surface.ROTATION_0;
    int[] mKeyRotationMap = null;
    
    VirtualKey mPressedVirtualKey = null;
    
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

    private static final boolean MEASURE_LATENCY = false;
    private LatencyTimer lt;

    public interface FilterCallback {
        int filterEvent(QueuedEvent ev);
    }
    
    public interface HapticFeedbackCallback {
        void virtualKeyFeedback(KeyEvent event);
    }
    
    static class QueuedEvent {
        InputDevice inputDevice;
        long whenNano;
        int flags; // From the raw event
        int classType; // One of the class constants in InputEvent
        Object event;
        boolean inQueue;

        void copyFrom(QueuedEvent that) {
            this.inputDevice = that.inputDevice;
            this.whenNano = that.whenNano;
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

    /**
     * A key that exists as a part of the touch-screen, outside of the normal
     * display area of the screen.
     */
    static class VirtualKey {
        int scancode;
        int centerx;
        int centery;
        int width;
        int height;
        
        int hitLeft;
        int hitTop;
        int hitRight;
        int hitBottom;
        
        InputDevice lastDevice;
        int lastKeycode;
        
        boolean checkHit(int x, int y) {
            return (x >= hitLeft && x <= hitRight
                    && y >= hitTop && y <= hitBottom);
        }
        
        void computeHitRect(InputDevice dev, int dw, int dh) {
            if (dev == lastDevice) {
                return;
            }
            
            if (DEBUG_VIRTUAL_KEYS) Slog.v(TAG, "computeHitRect for " + scancode
                    + ": dev=" + dev + " absX=" + dev.absX + " absY=" + dev.absY);
            
            lastDevice = dev;
            
            int minx = dev.absX.minValue;
            int maxx = dev.absX.maxValue;
            
            int halfw = width/2;
            int left = centerx - halfw;
            int right = centerx + halfw;
            hitLeft = minx + ((left*maxx-minx)/dw);
            hitRight = minx + ((right*maxx-minx)/dw);
            
            int miny = dev.absY.minValue;
            int maxy = dev.absY.maxValue;
            
            int halfh = height/2;
            int top = centery - halfh;
            int bottom = centery + halfh;
            hitTop = miny + ((top*maxy-miny)/dh);
            hitBottom = miny + ((bottom*maxy-miny)/dh);
        }
    }

    private void readVirtualKeys(String deviceName) {
        try {
            FileInputStream fis = new FileInputStream(
                    "/sys/board_properties/virtualkeys." + deviceName);
            InputStreamReader isr = new InputStreamReader(fis);
            BufferedReader br = new BufferedReader(isr, 2048);
            String str = br.readLine();
            if (str != null) {
                String[] it = str.split(":");
                if (DEBUG_VIRTUAL_KEYS) Slog.v(TAG, "***** VIRTUAL KEYS: " + it);
                final int N = it.length-6;
                for (int i=0; i<=N; i+=6) {
                    if (!"0x01".equals(it[i])) {
                        Slog.w(TAG, "Unknown virtual key type at elem #" + i
                                + ": " + it[i]);
                        continue;
                    }
                    try {
                        VirtualKey sb = new VirtualKey();
                        sb.scancode = Integer.parseInt(it[i+1]);
                        sb.centerx = Integer.parseInt(it[i+2]);
                        sb.centery = Integer.parseInt(it[i+3]);
                        sb.width = Integer.parseInt(it[i+4]);
                        sb.height = Integer.parseInt(it[i+5]);
                        if (DEBUG_VIRTUAL_KEYS) Slog.v(TAG, "Virtual key "
                                + sb.scancode + ": center=" + sb.centerx + ","
                                + sb.centery + " size=" + sb.width + "x"
                                + sb.height);
                        mVirtualKeys.add(sb);
                    } catch (NumberFormatException e) {
                        Slog.w(TAG, "Bad number at region " + i + " in: "
                                + str, e);
                    }
                }
            }
            br.close();
        } catch (FileNotFoundException e) {
            Slog.i(TAG, "No virtual keys found");
        } catch (IOException e) {
            Slog.w(TAG, "Error reading virtual keys", e);
        }
    }

    private void readExcludedDevices() {
        // Read partner-provided list of excluded input devices
        XmlPullParser parser = null;
        // Environment.getRootDirectory() is a fancy way of saying ANDROID_ROOT or "/system".
        File confFile = new File(Environment.getRootDirectory(), EXCLUDED_DEVICES_PATH);
        FileReader confreader = null;
        try {
            confreader = new FileReader(confFile);
            parser = Xml.newPullParser();
            parser.setInput(confreader);
            XmlUtils.beginDocument(parser, "devices");

            while (true) {
                XmlUtils.nextElement(parser);
                if (!"device".equals(parser.getName())) {
                    break;
                }
                String name = parser.getAttributeValue(null, "name");
                if (name != null) {
                    if (DEBUG) Slog.v(TAG, "addExcludedDevice " + name);
                    addExcludedDevice(name);
                }
            }
        } catch (FileNotFoundException e) {
            // It's ok if the file does not exist.
        } catch (Exception e) {
            Slog.e(TAG, "Exception while parsing '" + confFile.getAbsolutePath() + "'", e);
        } finally {
            try { if (confreader != null) confreader.close(); } catch (IOException e) { }
        }
    }

    KeyInputQueue(Context context, HapticFeedbackCallback  hapticFeedbackCallback) {
        if (MEASURE_LATENCY) {
            lt = new LatencyTimer(100, 1000);
        }

        Resources r = context.getResources();
        BAD_TOUCH_HACK = r.getBoolean(com.android.internal.R.bool.config_filterTouchEvents);
        
        JUMPY_TOUCH_HACK = r.getBoolean(com.android.internal.R.bool.config_filterJumpyTouchEvents);
        
        mHapticFeedbackCallback = hapticFeedbackCallback;
        
        if (! WindowManagerService.ENABLE_NATIVE_INPUT_DISPATCH) {
            readExcludedDevices();
        }
        
        PowerManager pm = (PowerManager)context.getSystemService(
                                                        Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                                        "KeyInputQueue");
        mWakeLock.setReferenceCounted(false);

        mFirst = new QueuedEvent();
        mLast = new QueuedEvent();
        mFirst.next = mLast;
        mLast.prev = mFirst;

        if (! WindowManagerService.ENABLE_NATIVE_INPUT_DISPATCH) {
            mThread.start();
        }
    }

    public void setDisplay(Display display) {
        mDisplay = display;
        
        // We assume at this point that the display dimensions reflect the
        // natural, unrotated display.  We will perform hit tests for soft
        // buttons based on that display.
        mDisplayWidth = display.getWidth();
        mDisplayHeight = display.getHeight();
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
                        //Slog.i("foo", "***** HAVE TOUCHSCREEN!");
                    }
                    if ((d.classes&RawInputEvent.CLASS_ALPHAKEY) != 0) {
                        config.keyboard
                                = Configuration.KEYBOARD_QWERTY;
                        //Slog.i("foo", "***** HAVE QWERTY!");
                    }
                    if ((d.classes&RawInputEvent.CLASS_TRACKBALL) != 0) {
                        config.navigation
                                = Configuration.NAVIGATION_TRACKBALL;
                        //Slog.i("foo", "***** HAVE TRACKBALL!");
                    } else if ((d.classes&RawInputEvent.CLASS_DPAD) != 0) {
                        config.navigation
                                = Configuration.NAVIGATION_DPAD;
                        //Slog.i("foo", "***** HAVE DPAD!");
                    }
                }
            }
        }
    }
    
    public int getScancodeState(int code) {
        synchronized (mFirst) {
            VirtualKey vk = mPressedVirtualKey;
            if (vk != null) {
                if (vk.scancode == code) {
                    return 2;
                }
            }
            return nativeGetScancodeState(code);
        }
    }
    
    public int getScancodeState(int deviceId, int code) {
        synchronized (mFirst) {
            VirtualKey vk = mPressedVirtualKey;
            if (vk != null) {
                if (vk.scancode == code) {
                    return 2;
                }
            }
            return nativeGetScancodeState(deviceId, code);
        }
    }
    
    public int getTrackballScancodeState(int code) {
        synchronized (mFirst) {
            final int N = mDevices.size();
            for (int i=0; i<N; i++) {
                InputDevice dev = mDevices.valueAt(i);
                if ((dev.classes&RawInputEvent.CLASS_TRACKBALL) != 0) {
                    int res = nativeGetScancodeState(dev.id, code);
                    if (res > 0) {
                        return res;
                    }
                }
            }
        }
        
        return 0;
    }
    
    public int getDPadScancodeState(int code) {
        synchronized (mFirst) {
            final int N = mDevices.size();
            for (int i=0; i<N; i++) {
                InputDevice dev = mDevices.valueAt(i);
                if ((dev.classes&RawInputEvent.CLASS_DPAD) != 0) {
                    int res = nativeGetScancodeState(dev.id, code);
                    if (res > 0) {
                        return res;
                    }
                }
            }
        }
        
        return 0;
    }
    
    public int getKeycodeState(int code) {
        synchronized (mFirst) {
            VirtualKey vk = mPressedVirtualKey;
            if (vk != null) {
                if (vk.lastKeycode == code) {
                    return 2;
                }
            }
            return nativeGetKeycodeState(code);
        }
    }
    
    public int getKeycodeState(int deviceId, int code) {
        synchronized (mFirst) {
            VirtualKey vk = mPressedVirtualKey;
            if (vk != null) {
                if (vk.lastKeycode == code) {
                    return 2;
                }
            }
            return nativeGetKeycodeState(deviceId, code);
        }
    }
    
    public int getTrackballKeycodeState(int code) {
        synchronized (mFirst) {
            final int N = mDevices.size();
            for (int i=0; i<N; i++) {
                InputDevice dev = mDevices.valueAt(i);
                if ((dev.classes&RawInputEvent.CLASS_TRACKBALL) != 0) {
                    int res = nativeGetKeycodeState(dev.id, code);
                    if (res > 0) {
                        return res;
                    }
                }
            }
        }
        
        return 0;
    }
    
    public int getDPadKeycodeState(int code) {
        synchronized (mFirst) {
            final int N = mDevices.size();
            for (int i=0; i<N; i++) {
                InputDevice dev = mDevices.valueAt(i);
                if ((dev.classes&RawInputEvent.CLASS_DPAD) != 0) {
                    int res = nativeGetKeycodeState(dev.id, code);
                    if (res > 0) {
                        return res;
                    }
                }
            }
        }
        
        return 0;
    }
    
    public static native String getDeviceName(int deviceId);
    public static native int getDeviceClasses(int deviceId);
    public static native void addExcludedDevice(String deviceName);
    public static native boolean getAbsoluteInfo(int deviceId, int axis,
            InputDevice.AbsoluteInfo outInfo);
    public static native int getSwitchState(int sw);
    public static native int getSwitchState(int deviceId, int sw);
    public static native int nativeGetScancodeState(int code);
    public static native int nativeGetScancodeState(int deviceId, int code);
    public static native int nativeGetKeycodeState(int code);
    public static native int nativeGetKeycodeState(int deviceId, int code);
    public static native int scancodeToKeycode(int deviceId, int scancode);
    public static native boolean hasKeys(int[] keycodes, boolean[] keyExists);
    
    public static KeyEvent newKeyEvent(InputDevice device, long downTime,
            long eventTime, boolean down, int keycode, int repeatCount,
            int scancode, int flags) {
        return new KeyEvent(
                downTime, eventTime,
                down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                keycode, repeatCount,
                device != null ? device.mMetaKeysState : 0,
                device != null ? device.id : -1, scancode,
                flags | KeyEvent.FLAG_FROM_SYSTEM);
    }
    
    Thread mThread = new Thread("InputDeviceReader") {
        public void run() {
            if (DEBUG) Slog.v(TAG, "InputDeviceReader.run()");
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
            
            RawInputEvent ev = new RawInputEvent();
            while (true) {
                try {
                    InputDevice di;

                    // block, doesn't release the monitor
                    readEvent(ev);

                    boolean send = false;
                    boolean configChanged = false;
                    
                    if (false) {
                        Slog.i(TAG, "Input event: dev=0x"
                                + Integer.toHexString(ev.deviceId)
                                + " type=0x" + Integer.toHexString(ev.type)
                                + " scancode=" + ev.scancode
                                + " keycode=" + ev.keycode
                                + " value=" + ev.value);
                    }
                    
                    if (ev.type == RawInputEvent.EV_DEVICE_ADDED) {
                        synchronized (mFirst) {
                            di = newInputDevice(ev.deviceId);
                            if (di.classes != 0) {
                                // If this device is some kind of input class,
                                // we care about it.
                                mDevices.put(ev.deviceId, di);
                                if ((di.classes & RawInputEvent.CLASS_TOUCHSCREEN) != 0) {
                                    readVirtualKeys(di.name);
                                }
                                // The configuration may have changed because
                                // of this device.
                                configChanged = true;
                            } else {
                                // We won't do anything with this device.
                                mIgnoredDevices.put(ev.deviceId, di);
                                Slog.i(TAG, "Ignoring non-input device: id=0x"
                                        + Integer.toHexString(di.id)
                                        + ", name=" + di.name);
                            }
                        }
                    } else if (ev.type == RawInputEvent.EV_DEVICE_REMOVED) {
                        synchronized (mFirst) {
                            if (false) {
                                Slog.i(TAG, "Device removed: id=0x"
                                        + Integer.toHexString(ev.deviceId));
                            }
                            di = mDevices.get(ev.deviceId);
                            if (di != null) {
                                mDevices.delete(ev.deviceId);
                                // The configuration may have changed because
                                // of this device.
                                configChanged = true;
                            } else if ((di=mIgnoredDevices.get(ev.deviceId)) != null) {
                                mIgnoredDevices.remove(ev.deviceId);
                            } else {
                                Slog.w(TAG, "Removing bad device id: "
                                        + Integer.toHexString(ev.deviceId));
                                continue;
                            }
                        }
                    } else {
                        di = getInputDevice(ev.deviceId);
                        if (di == null) {
                            // This may be some junk from an ignored device.
                            continue;
                        }
                        
                        // first crack at it
                        send = preprocessEvent(di, ev);

                        if (ev.type == RawInputEvent.EV_KEY) {
                            di.mMetaKeysState = makeMetaState(ev.keycode,
                                    ev.value != 0, di.mMetaKeysState);
                            mHaveGlobalMetaState = false;
                        }
                    }

                    if (configChanged) {
                        synchronized (mFirst) {
                            addLocked(di, System.nanoTime(), 0,
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
                        final long curTimeNano = System.nanoTime();
                        //Slog.i(TAG, "curTime=" + curTime + ", systemClock=" + SystemClock.uptimeMillis());
                        
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
                                di.mKeyDownTime = curTime;
                            } else {
                                down = false;
                            }
                            int keycode = rotateKeyCodeLocked(ev.keycode);
                            addLocked(di, curTimeNano, ev.flags,
                                    RawInputEvent.CLASS_KEYBOARD,
                                    newKeyEvent(di, di.mKeyDownTime, curTime, down,
                                            keycode, 0, scancode,
                                            ((ev.flags & WindowManagerPolicy.FLAG_WOKE_HERE) != 0)
                                             ? KeyEvent.FLAG_WOKE_HERE : 0));
                            
                        } else if (ev.type == RawInputEvent.EV_KEY) {
                            // Single touch protocol: touch going down or up.
                            if (ev.scancode == RawInputEvent.BTN_TOUCH &&
                                    (classes&(RawInputEvent.CLASS_TOUCHSCREEN
                                            |RawInputEvent.CLASS_TOUCHSCREEN_MT))
                                            == RawInputEvent.CLASS_TOUCHSCREEN) {
                                di.mAbs.changed = true;
                                di.mAbs.mDown[0] = ev.value != 0;
                            
                            // Trackball (mouse) protocol: press down or up.
                            } else if (ev.scancode == RawInputEvent.BTN_MOUSE &&
                                    (classes&RawInputEvent.CLASS_TRACKBALL) != 0) {
                                di.mRel.changed = true;
                                di.mRel.mNextNumPointers = ev.value != 0 ? 1 : 0;
                                send = true;
                            }
    
                        // Process position events from multitouch protocol.
                        } else if (ev.type == RawInputEvent.EV_ABS &&
                                (classes&RawInputEvent.CLASS_TOUCHSCREEN_MT) != 0) {
                            if (ev.scancode == RawInputEvent.ABS_MT_TOUCH_MAJOR) {
                                di.mAbs.changed = true;
                                di.mAbs.mNextData[di.mAbs.mAddingPointerOffset
                                        + MotionEvent.SAMPLE_PRESSURE] = ev.value;
                            } else if (ev.scancode == RawInputEvent.ABS_MT_POSITION_X) {
                                di.mAbs.changed = true;
                                di.mAbs.mNextData[di.mAbs.mAddingPointerOffset
                                    + MotionEvent.SAMPLE_X] = ev.value;
                                if (DEBUG_POINTERS) Slog.v(TAG, "MT @"
                                        + di.mAbs.mAddingPointerOffset
                                        + " X:" + ev.value);
                            } else if (ev.scancode == RawInputEvent.ABS_MT_POSITION_Y) {
                                di.mAbs.changed = true;
                                di.mAbs.mNextData[di.mAbs.mAddingPointerOffset
                                    + MotionEvent.SAMPLE_Y] = ev.value;
                                if (DEBUG_POINTERS) Slog.v(TAG, "MT @"
                                        + di.mAbs.mAddingPointerOffset
                                        + " Y:" + ev.value);
                            } else if (ev.scancode == RawInputEvent.ABS_MT_WIDTH_MAJOR) {
                                di.mAbs.changed = true;
                                di.mAbs.mNextData[di.mAbs.mAddingPointerOffset
                                    + MotionEvent.SAMPLE_SIZE] = ev.value;
                            }
                        
                        // Process position events from single touch protocol.
                        } else if (ev.type == RawInputEvent.EV_ABS &&
                                (classes&RawInputEvent.CLASS_TOUCHSCREEN) != 0) {
                            if (ev.scancode == RawInputEvent.ABS_X) {
                                di.mAbs.changed = true;
                                di.curTouchVals[MotionEvent.SAMPLE_X] = ev.value;
                            } else if (ev.scancode == RawInputEvent.ABS_Y) {
                                di.mAbs.changed = true;
                                di.curTouchVals[MotionEvent.SAMPLE_Y] = ev.value;
                            } else if (ev.scancode == RawInputEvent.ABS_PRESSURE) {
                                di.mAbs.changed = true;
                                di.curTouchVals[MotionEvent.SAMPLE_PRESSURE] = ev.value;
                                di.curTouchVals[MotionEvent.NUM_SAMPLE_DATA
                                                 + MotionEvent.SAMPLE_PRESSURE] = ev.value;
                            } else if (ev.scancode == RawInputEvent.ABS_TOOL_WIDTH) {
                                di.mAbs.changed = true;
                                di.curTouchVals[MotionEvent.SAMPLE_SIZE] = ev.value;
                                di.curTouchVals[MotionEvent.NUM_SAMPLE_DATA
                                                 + MotionEvent.SAMPLE_SIZE] = ev.value;
                            }
    
                        // Process movement events from trackball (mouse) protocol.
                        } else if (ev.type == RawInputEvent.EV_REL &&
                                (classes&RawInputEvent.CLASS_TRACKBALL) != 0) {
                            // Add this relative movement into our totals.
                            if (ev.scancode == RawInputEvent.REL_X) {
                                di.mRel.changed = true;
                                di.mRel.mNextData[MotionEvent.SAMPLE_X] += ev.value;
                            } else if (ev.scancode == RawInputEvent.REL_Y) {
                                di.mRel.changed = true;
                                di.mRel.mNextData[MotionEvent.SAMPLE_Y] += ev.value;
                            }
                        }
                        
                        // Handle multitouch protocol sync: tells us that the
                        // driver has returned all data for -one- of the pointers
                        // that is currently down.
                        if (ev.type == RawInputEvent.EV_SYN
                                && ev.scancode == RawInputEvent.SYN_MT_REPORT
                                && di.mAbs != null) {
                            di.mAbs.changed = true;
                            if (di.mAbs.mNextData[MotionEvent.SAMPLE_PRESSURE] > 0) {
                                // If the value is <= 0, the pointer is not
                                // down, so keep it in the count.
                                
                                if (di.mAbs.mNextData[di.mAbs.mAddingPointerOffset
                                                      + MotionEvent.SAMPLE_PRESSURE] != 0) {
                                    final int num = di.mAbs.mNextNumPointers+1;
                                    di.mAbs.mNextNumPointers = num;
                                    if (DEBUG_POINTERS) Slog.v(TAG,
                                            "MT_REPORT: now have " + num + " pointers");
                                    final int newOffset = (num <= InputDevice.MAX_POINTERS)
                                            ? (num * MotionEvent.NUM_SAMPLE_DATA)
                                            : (InputDevice.MAX_POINTERS *
                                                    MotionEvent.NUM_SAMPLE_DATA);
                                    di.mAbs.mAddingPointerOffset = newOffset;
                                    di.mAbs.mNextData[newOffset
                                            + MotionEvent.SAMPLE_PRESSURE] = 0;
                                } else {
                                    if (DEBUG_POINTERS) Slog.v(TAG, "MT_REPORT: no pointer");
                                }
                            }
                        
                        // Handle general event sync: all data for the current
                        // event update has been delivered.
                        } else if (send || (ev.type == RawInputEvent.EV_SYN
                                && ev.scancode == RawInputEvent.SYN_REPORT)) {
                            if (mDisplay != null) {
                                if (!mHaveGlobalMetaState) {
                                    computeGlobalMetaStateLocked();
                                }
                                
                                MotionEvent me;
                                
                                InputDevice.MotionState ms = di.mAbs;
                                if (ms.changed) {
                                    ms.everChanged = true;
                                    ms.changed = false;
                                    
                                    if ((classes&(RawInputEvent.CLASS_TOUCHSCREEN
                                            |RawInputEvent.CLASS_TOUCHSCREEN_MT))
                                            == RawInputEvent.CLASS_TOUCHSCREEN) {
                                        ms.mNextNumPointers = 0;
                                        if (ms.mDown[0]) {
                                            System.arraycopy(di.curTouchVals, 0,
                                                    ms.mNextData, 0,
                                                    MotionEvent.NUM_SAMPLE_DATA);
                                            ms.mNextNumPointers++;
                                        }
                                    }
                                    
                                    if (BAD_TOUCH_HACK) {
                                        ms.dropBadPoint(di);
                                    }
                                    if (JUMPY_TOUCH_HACK) {
                                        ms.dropJumpyPoint(di);
                                    }
                                    
                                    boolean doMotion = !monitorVirtualKey(di,
                                            ev, curTime, curTimeNano);
                                    
                                    if (doMotion && ms.mNextNumPointers > 0
                                            && (ms.mLastNumPointers == 0
                                                    || ms.mSkipLastPointers)) {
                                        doMotion = !generateVirtualKeyDown(di,
                                                ev, curTime, curTimeNano);
                                    }
                                    
                                    if (doMotion) {
                                        // XXX Need to be able to generate
                                        // multiple events here, for example
                                        // if two fingers change up/down state
                                        // at the same time.
                                        do {
                                            me = ms.generateAbsMotion(di, curTime,
                                                    curTimeNano, mDisplay,
                                                    mOrientation, mGlobalMetaState);
                                            if (DEBUG_POINTERS) Slog.v(TAG, "Absolute: x="
                                                    + di.mAbs.mNextData[MotionEvent.SAMPLE_X]
                                                    + " y="
                                                    + di.mAbs.mNextData[MotionEvent.SAMPLE_Y]
                                                    + " ev=" + me);
                                            if (me != null) {
                                                if (WindowManagerPolicy.WATCH_POINTER) {
                                                    Slog.i(TAG, "Enqueueing: " + me);
                                                }
                                                addLocked(di, curTimeNano, ev.flags,
                                                        RawInputEvent.CLASS_TOUCHSCREEN, me);
                                            }
                                        } while (ms.hasMore());
                                    } else {
                                        // We are consuming movement in the
                                        // virtual key area...  but still
                                        // propagate this to the previous
                                        // data for comparisons.
                                        int num = ms.mNextNumPointers;
                                        if (num > InputDevice.MAX_POINTERS) {
                                            num = InputDevice.MAX_POINTERS;
                                        }
                                        System.arraycopy(ms.mNextData, 0,
                                                ms.mLastData, 0,
                                                num * MotionEvent.NUM_SAMPLE_DATA);
                                        ms.mLastNumPointers = num;
                                        ms.mSkipLastPointers = true;
                                    }
                                    
                                    ms.finish();
                                }
                                
                                ms = di.mRel;
                                if (ms.changed) {
                                    ms.everChanged = true;
                                    ms.changed = false;
                                    
                                    me = ms.generateRelMotion(di, curTime,
                                            curTimeNano,
                                            mOrientation, mGlobalMetaState);
                                    if (false) Slog.v(TAG, "Relative: x="
                                            + di.mRel.mNextData[MotionEvent.SAMPLE_X]
                                            + " y="
                                            + di.mRel.mNextData[MotionEvent.SAMPLE_Y]
                                            + " ev=" + me);
                                    if (me != null) {
                                        addLocked(di, curTimeNano, ev.flags,
                                                RawInputEvent.CLASS_TRACKBALL, me);
                                    }
                                }
                            }
                        }
                    }
                
                } catch (RuntimeException exc) {
                    Slog.e(TAG, "InputReaderThread uncaught exception", exc);
                }
            }
        }
    };

    private boolean isInsideDisplay(InputDevice dev) {
        final InputDevice.AbsoluteInfo absx = dev.absX;
        final InputDevice.AbsoluteInfo absy = dev.absY;
        final InputDevice.MotionState absm = dev.mAbs;
        if (absx == null || absy == null || absm == null) {
            return true;
        }
        
        if (absm.mNextData[MotionEvent.SAMPLE_X] >= absx.minValue
                && absm.mNextData[MotionEvent.SAMPLE_X] <= absx.maxValue
                && absm.mNextData[MotionEvent.SAMPLE_Y] >= absy.minValue
                && absm.mNextData[MotionEvent.SAMPLE_Y] <= absy.maxValue) {
            if (DEBUG_VIRTUAL_KEYS) Slog.v(TAG, "Input ("
                    + absm.mNextData[MotionEvent.SAMPLE_X]
                    + "," + absm.mNextData[MotionEvent.SAMPLE_Y]
                    + ") inside of display");
            return true;
        }
        
        return false;
    }
    
    private VirtualKey findVirtualKey(InputDevice dev) {
        final int N = mVirtualKeys.size();
        if (N <= 0) {
            return null;
        }
        
        final InputDevice.MotionState absm = dev.mAbs;
        for (int i=0; i<N; i++) {
            VirtualKey sb = mVirtualKeys.get(i);
            sb.computeHitRect(dev, mDisplayWidth, mDisplayHeight);
            if (DEBUG_VIRTUAL_KEYS) Slog.v(TAG, "Hit test ("
                    + absm.mNextData[MotionEvent.SAMPLE_X] + ","
                    + absm.mNextData[MotionEvent.SAMPLE_Y] + ") in code "
                    + sb.scancode + " - (" + sb.hitLeft
                    + "," + sb.hitTop + ")-(" + sb.hitRight + ","
                    + sb.hitBottom + ")");
            if (sb.checkHit(absm.mNextData[MotionEvent.SAMPLE_X],
                    absm.mNextData[MotionEvent.SAMPLE_Y])) {
                if (DEBUG_VIRTUAL_KEYS) Slog.v(TAG, "Hit!");
                return sb;
            }
        }
        
        return null;
    }
    
    private boolean generateVirtualKeyDown(InputDevice di, RawInputEvent ev,
            long curTime, long curTimeNano) {
        if (isInsideDisplay(di)) {
            // Didn't consume event.
            return false;
        }
        
        
        VirtualKey vk = findVirtualKey(di);
        if (vk != null) {
            final InputDevice.MotionState ms = di.mAbs;
            mPressedVirtualKey = vk;
            vk.lastKeycode = scancodeToKeycode(di.id, vk.scancode);
            ms.mLastNumPointers = ms.mNextNumPointers;
            di.mKeyDownTime = curTime;
            if (DEBUG_VIRTUAL_KEYS) Slog.v(TAG,
                    "Generate key down for: " + vk.scancode
                    + " (keycode=" + vk.lastKeycode + ")");
            KeyEvent event = newKeyEvent(di, di.mKeyDownTime, curTime, true,
                    vk.lastKeycode, 0, vk.scancode,
                    KeyEvent.FLAG_VIRTUAL_HARD_KEY);
            mHapticFeedbackCallback.virtualKeyFeedback(event);
            addLocked(di, curTimeNano, ev.flags, RawInputEvent.CLASS_KEYBOARD,
                    event);
        }
        
        // We always consume the event, even if we didn't
        // generate a key event.  There are two reasons for
        // this: to avoid spurious touches when holding
        // the edges of the device near the touchscreen,
        // and to avoid reporting events if there are virtual
        // keys on the touchscreen outside of the display
        // area.
        // Note that for all of this we are only looking at the
        // first pointer, since what we are handling here is the
        // first pointer going down, and this is the coordinate
        // that will be used to dispatch the event.
        if (false) {
            final InputDevice.AbsoluteInfo absx = di.absX;
            final InputDevice.AbsoluteInfo absy = di.absY;
            final InputDevice.MotionState absm = di.mAbs;
            Slog.v(TAG, "Rejecting ("
                + absm.mNextData[MotionEvent.SAMPLE_X] + ","
                + absm.mNextData[MotionEvent.SAMPLE_Y] + "): outside of ("
                + absx.minValue + "," + absy.minValue
                + ")-(" + absx.maxValue + ","
                + absx.maxValue + ")");
        }
        return true;
    }
    
    private boolean monitorVirtualKey(InputDevice di, RawInputEvent ev,
            long curTime, long curTimeNano) {
        VirtualKey vk = mPressedVirtualKey;
        if (vk == null) {
            return false;
        }
        
        final InputDevice.MotionState ms = di.mAbs;
        if (ms.mNextNumPointers <= 0) {
            mPressedVirtualKey = null;
            ms.mLastNumPointers = 0;
            if (DEBUG_VIRTUAL_KEYS) Slog.v(TAG, "Generate key up for: " + vk.scancode);
            KeyEvent event = newKeyEvent(di, di.mKeyDownTime, curTime, false,
                    vk.lastKeycode, 0, vk.scancode,
                    KeyEvent.FLAG_VIRTUAL_HARD_KEY);
            mHapticFeedbackCallback.virtualKeyFeedback(event);
            addLocked(di, curTimeNano, ev.flags, RawInputEvent.CLASS_KEYBOARD,
                    event);
            return true;
            
        } else if (isInsideDisplay(di)) {
            // Whoops the pointer has moved into
            // the display area!  Cancel the
            // virtual key and start a pointer
            // motion.
            mPressedVirtualKey = null;
            if (DEBUG_VIRTUAL_KEYS) Slog.v(TAG, "Cancel key up for: " + vk.scancode);
            KeyEvent event = newKeyEvent(di, di.mKeyDownTime, curTime, false,
                    vk.lastKeycode, 0, vk.scancode,
                    KeyEvent.FLAG_CANCELED | KeyEvent.FLAG_VIRTUAL_HARD_KEY);
            mHapticFeedbackCallback.virtualKeyFeedback(event);
            addLocked(di, curTimeNano, ev.flags, RawInputEvent.CLASS_KEYBOARD,
                    event);
            ms.mLastNumPointers = 0;
            return false;
        }
        
        return true;
    }
    
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

    /**
     * Return true if the queue has an up event pending that corresponds
     * to the same key as the given key event.
     */
    boolean hasKeyUpEvent(KeyEvent origEvent) {
        synchronized (mFirst) {
            final int keyCode = origEvent.getKeyCode();
            QueuedEvent cur = mLast.prev;
            while (cur.prev != null) {
                if (cur.classType == RawInputEvent.CLASS_KEYBOARD) {
                    KeyEvent ke = (KeyEvent)cur.event;
                    if (ke.getAction() == KeyEvent.ACTION_UP
                            && ke.getKeyCode() == keyCode) {
                        return true;
                    }
                }
                cur = cur.prev;
            }
        }
        
        return false;
    }
    
    void recycleEvent(QueuedEvent ev) {
        synchronized (mFirst) {
            //Slog.i(TAG, "Recycle event: " + ev);
            if (ev.event == ev.inputDevice.mAbs.currentMove) {
                ev.inputDevice.mAbs.currentMove = null;
            }
            if (ev.event == ev.inputDevice.mRel.currentMove) {
                if (false) Slog.i(TAG, "Detach rel " + ev.event);
                ev.inputDevice.mRel.currentMove = null;
                ev.inputDevice.mRel.mNextData[MotionEvent.SAMPLE_X] = 0;
                ev.inputDevice.mRel.mNextData[MotionEvent.SAMPLE_Y] = 0;
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
    
    private QueuedEvent obtainLocked(InputDevice device, long whenNano,
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
        ev.whenNano = whenNano;
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

    private void addLocked(InputDevice device, long whenNano, int flags,
            int classType, Object event) {
        boolean poke = mFirst.next == mLast;

        QueuedEvent ev = obtainLocked(device, whenNano, flags, classType, event);
        QueuedEvent p = mLast.prev;
        while (p != mFirst && ev.whenNano < p.whenNano) {
            p = p.prev;
        }

        ev.next = p.next;
        ev.prev = p;
        p.next = ev;
        ev.next.prev = ev;
        ev.inQueue = true;

        if (poke) {
            long time;
            if (MEASURE_LATENCY) {
                time = System.nanoTime();
            }
            mFirst.notify();
            mWakeLock.acquire();
            if (MEASURE_LATENCY) {
                lt.sample("1 addLocked-queued event ", System.nanoTime() - time);
            }
        }
    }

    private InputDevice newInputDevice(int deviceId) {
        int classes = getDeviceClasses(deviceId);
        String name = getDeviceName(deviceId);
        InputDevice.AbsoluteInfo absX = null;
        InputDevice.AbsoluteInfo absY = null;
        InputDevice.AbsoluteInfo absPressure = null;
        InputDevice.AbsoluteInfo absSize = null;
        if (classes != 0) {
            Slog.i(TAG, "Device added: id=0x" + Integer.toHexString(deviceId)
                    + ", name=" + name
                    + ", classes=" + Integer.toHexString(classes));
            if ((classes&RawInputEvent.CLASS_TOUCHSCREEN_MT) != 0) {
                absX = loadAbsoluteInfo(deviceId,
                        RawInputEvent.ABS_MT_POSITION_X, "X");
                absY = loadAbsoluteInfo(deviceId,
                        RawInputEvent.ABS_MT_POSITION_Y, "Y");
                absPressure = loadAbsoluteInfo(deviceId,
                        RawInputEvent.ABS_MT_TOUCH_MAJOR, "Pressure");
                absSize = loadAbsoluteInfo(deviceId,
                        RawInputEvent.ABS_MT_WIDTH_MAJOR, "Size");
            } else if ((classes&RawInputEvent.CLASS_TOUCHSCREEN) != 0) {
                absX = loadAbsoluteInfo(deviceId,
                        RawInputEvent.ABS_X, "X");
                absY = loadAbsoluteInfo(deviceId,
                        RawInputEvent.ABS_Y, "Y");
                absPressure = loadAbsoluteInfo(deviceId,
                        RawInputEvent.ABS_PRESSURE, "Pressure");
                absSize = loadAbsoluteInfo(deviceId,
                        RawInputEvent.ABS_TOOL_WIDTH, "Size");
            }
        }
        
        return new InputDevice(deviceId, classes, name, absX, absY, absPressure, absSize);
    }
    
    private InputDevice.AbsoluteInfo loadAbsoluteInfo(int id, int channel,
            String name) {
        InputDevice.AbsoluteInfo info = new InputDevice.AbsoluteInfo();
        if (getAbsoluteInfo(id, channel, info)
                && info.minValue != info.maxValue) {
            Slog.i(TAG, "  " + name + ": min=" + info.minValue
                    + " max=" + info.maxValue
                    + " flat=" + info.flat
                    + " fuzz=" + info.fuzz);
            info.range = info.maxValue-info.minValue;
            return info;
        }
        Slog.i(TAG, "  " + name + ": unknown values");
        return null;
    }
    private static native boolean readEvent(RawInputEvent outEvent);
    
    void dump(PrintWriter pw, String prefix) {
        synchronized (mFirst) {
            for (int i=0; i<mDevices.size(); i++) {
                InputDevice dev = mDevices.valueAt(i);
                pw.print(prefix); pw.print("Device #");
                        pw.print(mDevices.keyAt(i)); pw.print(" ");
                        pw.print(dev.name); pw.print(" (classes=0x");
                        pw.print(Integer.toHexString(dev.classes));
                        pw.println("):");
                pw.print(prefix); pw.print("  mKeyDownTime=");
                        pw.print(dev.mKeyDownTime); pw.print(" mMetaKeysState=");
                        pw.println(dev.mMetaKeysState);
                if (dev.absX != null) {
                    pw.print(prefix); pw.print("  absX: "); dev.absX.dump(pw);
                            pw.println("");
                }
                if (dev.absY != null) {
                    pw.print(prefix); pw.print("  absY: "); dev.absY.dump(pw);
                            pw.println("");
                }
                if (dev.absPressure != null) {
                    pw.print(prefix); pw.print("  absPressure: ");
                            dev.absPressure.dump(pw); pw.println("");
                }
                if (dev.absSize != null) {
                    pw.print(prefix); pw.print("  absSize: ");
                            dev.absSize.dump(pw); pw.println("");
                }
                if (dev.mAbs.everChanged) {
                    pw.print(prefix); pw.println("  mAbs:");
                    dev.mAbs.dump(pw, prefix + "    ");
                }
                if (dev.mRel.everChanged) {
                    pw.print(prefix); pw.println("  mRel:");
                    dev.mRel.dump(pw, prefix + "    ");
                }
            }
            pw.println(" ");
            for (int i=0; i<mIgnoredDevices.size(); i++) {
                InputDevice dev = mIgnoredDevices.valueAt(i);
                pw.print(prefix); pw.print("Ignored Device #");
                        pw.print(mIgnoredDevices.keyAt(i)); pw.print(" ");
                        pw.print(dev.name); pw.print(" (classes=0x");
                        pw.print(Integer.toHexString(dev.classes));
                        pw.println(")");
            }
            pw.println(" ");
            for (int i=0; i<mVirtualKeys.size(); i++) {
                VirtualKey vk = mVirtualKeys.get(i);
                pw.print(prefix); pw.print("Virtual Key #");
                        pw.print(i); pw.println(":");
                pw.print(prefix); pw.print("  scancode="); pw.println(vk.scancode);
                pw.print(prefix); pw.print("  centerx="); pw.print(vk.centerx);
                        pw.print(" centery="); pw.print(vk.centery);
                        pw.print(" width="); pw.print(vk.width);
                        pw.print(" height="); pw.println(vk.height);
                pw.print(prefix); pw.print("  hitLeft="); pw.print(vk.hitLeft);
                        pw.print(" hitTop="); pw.print(vk.hitTop);
                        pw.print(" hitRight="); pw.print(vk.hitRight);
                        pw.print(" hitBottom="); pw.println(vk.hitBottom);
                if (vk.lastDevice != null) {
                    pw.print(prefix); pw.print("  lastDevice=#");
                            pw.println(vk.lastDevice.id);
                }
                if (vk.lastKeycode != 0) {
                    pw.print(prefix); pw.print("  lastKeycode=");
                            pw.println(vk.lastKeycode);
                }
            }
            pw.println(" ");
            pw.print(prefix); pw.print("  Default keyboard: ");
                    pw.println(SystemProperties.get("hw.keyboards.0.devname"));
            pw.print(prefix); pw.print("  mGlobalMetaState=");
                    pw.print(mGlobalMetaState); pw.print(" mHaveGlobalMetaState=");
                    pw.println(mHaveGlobalMetaState);
            pw.print(prefix); pw.print("  mDisplayWidth=");
                    pw.print(mDisplayWidth); pw.print(" mDisplayHeight=");
                    pw.println(mDisplayHeight);
            pw.print(prefix); pw.print("  mOrientation=");
                    pw.println(mOrientation);
            if (mPressedVirtualKey != null) {
                pw.print(prefix); pw.print("  mPressedVirtualKey.scancode=");
                        pw.println(mPressedVirtualKey.scancode);
            }
        }
    }
}
