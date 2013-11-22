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

package android.hardware.display;

import android.content.Context;
import android.hardware.display.DisplayManager.DisplayListener;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.DisplayAdjustments;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;

import java.util.ArrayList;

/**
 * Manager communication with the display manager service on behalf of
 * an application process.  You're probably looking for {@link DisplayManager}.
 *
 * @hide
 */
public final class DisplayManagerGlobal {
    private static final String TAG = "DisplayManager";
    private static final boolean DEBUG = false;

    // True if display info and display ids should be cached.
    //
    // FIXME: The cache is currently disabled because it's unclear whether we have the
    // necessary guarantees that the caches will always be flushed before clients
    // attempt to observe their new state.  For example, depending on the order
    // in which the binder transactions take place, we might have a problem where
    // an application could start processing a configuration change due to a display
    // orientation change before the display info cache has actually been invalidated.
    private static final boolean USE_CACHE = false;

    public static final int EVENT_DISPLAY_ADDED = 1;
    public static final int EVENT_DISPLAY_CHANGED = 2;
    public static final int EVENT_DISPLAY_REMOVED = 3;

    private static DisplayManagerGlobal sInstance;

    private final Object mLock = new Object();

    private final IDisplayManager mDm;

    private DisplayManagerCallback mCallback;
    private final ArrayList<DisplayListenerDelegate> mDisplayListeners =
            new ArrayList<DisplayListenerDelegate>();

    private final SparseArray<DisplayInfo> mDisplayInfoCache = new SparseArray<DisplayInfo>();
    private int[] mDisplayIdCache;

    private int mWifiDisplayScanNestCount;

    private DisplayManagerGlobal(IDisplayManager dm) {
        mDm = dm;
    }

    /**
     * Gets an instance of the display manager global singleton.
     *
     * @return The display manager instance, may be null early in system startup
     * before the display manager has been fully initialized.
     */
    public static DisplayManagerGlobal getInstance() {
        synchronized (DisplayManagerGlobal.class) {
            if (sInstance == null) {
                IBinder b = ServiceManager.getService(Context.DISPLAY_SERVICE);
                if (b != null) {
                    sInstance = new DisplayManagerGlobal(IDisplayManager.Stub.asInterface(b));
                }
            }
            return sInstance;
        }
    }

    /**
     * Get information about a particular logical display.
     *
     * @param displayId The logical display id.
     * @return Information about the specified display, or null if it does not exist.
     * This object belongs to an internal cache and should be treated as if it were immutable.
     */
    public DisplayInfo getDisplayInfo(int displayId) {
        try {
            synchronized (mLock) {
                DisplayInfo info;
                if (USE_CACHE) {
                    info = mDisplayInfoCache.get(displayId);
                    if (info != null) {
                        return info;
                    }
                }

                info = mDm.getDisplayInfo(displayId);
                if (info == null) {
                    return null;
                }

                if (USE_CACHE) {
                    mDisplayInfoCache.put(displayId, info);
                }
                registerCallbackIfNeededLocked();

                if (DEBUG) {
                    Log.d(TAG, "getDisplayInfo: displayId=" + displayId + ", info=" + info);
                }
                return info;
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "Could not get display information from display manager.", ex);
            return null;
        }
    }

    /**
     * Gets all currently valid logical display ids.
     *
     * @return An array containing all display ids.
     */
    public int[] getDisplayIds() {
        try {
            synchronized (mLock) {
                if (USE_CACHE) {
                    if (mDisplayIdCache != null) {
                        return mDisplayIdCache;
                    }
                }

                int[] displayIds = mDm.getDisplayIds();
                if (USE_CACHE) {
                    mDisplayIdCache = displayIds;
                }
                registerCallbackIfNeededLocked();
                return displayIds;
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "Could not get display ids from display manager.", ex);
            return new int[] { Display.DEFAULT_DISPLAY };
        }
    }

    /**
     * Gets information about a logical display.
     *
     * The display metrics may be adjusted to provide compatibility
     * for legacy applications or limited screen areas.
     *
     * @param displayId The logical display id.
     * @param daj The compatibility info and activityToken.
     * @return The display object, or null if there is no display with the given id.
     */
    public Display getCompatibleDisplay(int displayId, DisplayAdjustments daj) {
        DisplayInfo displayInfo = getDisplayInfo(displayId);
        if (displayInfo == null) {
            return null;
        }
        return new Display(this, displayId, displayInfo, daj);
    }

    /**
     * Gets information about a logical display without applying any compatibility metrics.
     *
     * @param displayId The logical display id.
     * @return The display object, or null if there is no display with the given id.
     */
    public Display getRealDisplay(int displayId) {
        return getCompatibleDisplay(displayId, DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
    }

    /**
     * Gets information about a logical display without applying any compatibility metrics.
     *
     * @param displayId The logical display id.
     * @param IBinder the activity token for this display.
     * @return The display object, or null if there is no display with the given id.
     */
    public Display getRealDisplay(int displayId, IBinder token) {
        return getCompatibleDisplay(displayId, new DisplayAdjustments(token));
    }

    public void registerDisplayListener(DisplayListener listener, Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        synchronized (mLock) {
            int index = findDisplayListenerLocked(listener);
            if (index < 0) {
                mDisplayListeners.add(new DisplayListenerDelegate(listener, handler));
                registerCallbackIfNeededLocked();
            }
        }
    }

    public void unregisterDisplayListener(DisplayListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        synchronized (mLock) {
            int index = findDisplayListenerLocked(listener);
            if (index >= 0) {
                DisplayListenerDelegate d = mDisplayListeners.get(index);
                d.clearEvents();
                mDisplayListeners.remove(index);
            }
        }
    }

    private int findDisplayListenerLocked(DisplayListener listener) {
        final int numListeners = mDisplayListeners.size();
        for (int i = 0; i < numListeners; i++) {
            if (mDisplayListeners.get(i).mListener == listener) {
                return i;
            }
        }
        return -1;
    }

    private void registerCallbackIfNeededLocked() {
        if (mCallback == null) {
            mCallback = new DisplayManagerCallback();
            try {
                mDm.registerCallback(mCallback);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to register callback with display manager service.", ex);
                mCallback = null;
            }
        }
    }

    private void handleDisplayEvent(int displayId, int event) {
        synchronized (mLock) {
            if (USE_CACHE) {
                mDisplayInfoCache.remove(displayId);

                if (event == EVENT_DISPLAY_ADDED || event == EVENT_DISPLAY_REMOVED) {
                    mDisplayIdCache = null;
                }
            }

            final int numListeners = mDisplayListeners.size();
            for (int i = 0; i < numListeners; i++) {
                mDisplayListeners.get(i).sendDisplayEvent(displayId, event);
            }
        }
    }

    public void startWifiDisplayScan() {
        synchronized (mLock) {
            if (mWifiDisplayScanNestCount++ == 0) {
                registerCallbackIfNeededLocked();
                try {
                    mDm.startWifiDisplayScan();
                } catch (RemoteException ex) {
                    Log.e(TAG, "Failed to scan for Wifi displays.", ex);
                }
            }
        }
    }

    public void stopWifiDisplayScan() {
        synchronized (mLock) {
            if (--mWifiDisplayScanNestCount == 0) {
                try {
                    mDm.stopWifiDisplayScan();
                } catch (RemoteException ex) {
                    Log.e(TAG, "Failed to scan for Wifi displays.", ex);
                }
            } else if (mWifiDisplayScanNestCount < 0) {
                Log.wtf(TAG, "Wifi display scan nest count became negative: "
                        + mWifiDisplayScanNestCount);
                mWifiDisplayScanNestCount = 0;
            }
        }
    }

    public void connectWifiDisplay(String deviceAddress) {
        if (deviceAddress == null) {
            throw new IllegalArgumentException("deviceAddress must not be null");
        }

        try {
            mDm.connectWifiDisplay(deviceAddress);
        } catch (RemoteException ex) {
            Log.e(TAG, "Failed to connect to Wifi display " + deviceAddress + ".", ex);
        }
    }

    public void pauseWifiDisplay() {
        try {
            mDm.pauseWifiDisplay();
        } catch (RemoteException ex) {
            Log.e(TAG, "Failed to pause Wifi display.", ex);
        }
    }

    public void resumeWifiDisplay() {
        try {
            mDm.resumeWifiDisplay();
        } catch (RemoteException ex) {
            Log.e(TAG, "Failed to resume Wifi display.", ex);
        }
    }

    public void disconnectWifiDisplay() {
        try {
            mDm.disconnectWifiDisplay();
        } catch (RemoteException ex) {
            Log.e(TAG, "Failed to disconnect from Wifi display.", ex);
        }
    }

    public void renameWifiDisplay(String deviceAddress, String alias) {
        if (deviceAddress == null) {
            throw new IllegalArgumentException("deviceAddress must not be null");
        }

        try {
            mDm.renameWifiDisplay(deviceAddress, alias);
        } catch (RemoteException ex) {
            Log.e(TAG, "Failed to rename Wifi display " + deviceAddress
                    + " with alias " + alias + ".", ex);
        }
    }

    public void forgetWifiDisplay(String deviceAddress) {
        if (deviceAddress == null) {
            throw new IllegalArgumentException("deviceAddress must not be null");
        }

        try {
            mDm.forgetWifiDisplay(deviceAddress);
        } catch (RemoteException ex) {
            Log.e(TAG, "Failed to forget Wifi display.", ex);
        }
    }

    public WifiDisplayStatus getWifiDisplayStatus() {
        try {
            return mDm.getWifiDisplayStatus();
        } catch (RemoteException ex) {
            Log.e(TAG, "Failed to get Wifi display status.", ex);
            return new WifiDisplayStatus();
        }
    }

    public VirtualDisplay createVirtualDisplay(Context context, String name,
            int width, int height, int densityDpi, Surface surface, int flags) {
        if (TextUtils.isEmpty(name)) {
            throw new IllegalArgumentException("name must be non-null and non-empty");
        }
        if (width <= 0 || height <= 0 || densityDpi <= 0) {
            throw new IllegalArgumentException("width, height, and densityDpi must be "
                    + "greater than 0");
        }
        if (surface == null) {
            throw new IllegalArgumentException("surface must not be null");
        }

        Binder token = new Binder();
        int displayId;
        try {
            displayId = mDm.createVirtualDisplay(token, context.getPackageName(),
                    name, width, height, densityDpi, surface, flags);
        } catch (RemoteException ex) {
            Log.e(TAG, "Could not create virtual display: " + name, ex);
            return null;
        }
        if (displayId < 0) {
            Log.e(TAG, "Could not create virtual display: " + name);
            return null;
        }
        Display display = getRealDisplay(displayId);
        if (display == null) {
            Log.wtf(TAG, "Could not obtain display info for newly created "
                    + "virtual display: " + name);
            try {
                mDm.releaseVirtualDisplay(token);
            } catch (RemoteException ex) {
            }
            return null;
        }
        return new VirtualDisplay(this, display, token);
    }

    public void releaseVirtualDisplay(IBinder token) {
        try {
            mDm.releaseVirtualDisplay(token);
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed to release virtual display.", ex);
        }
    }

    private final class DisplayManagerCallback extends IDisplayManagerCallback.Stub {
        @Override
        public void onDisplayEvent(int displayId, int event) {
            if (DEBUG) {
                Log.d(TAG, "onDisplayEvent: displayId=" + displayId + ", event=" + event);
            }
            handleDisplayEvent(displayId, event);
        }
    }

    private static final class DisplayListenerDelegate extends Handler {
        public final DisplayListener mListener;

        public DisplayListenerDelegate(DisplayListener listener, Handler handler) {
            super(handler != null ? handler.getLooper() : Looper.myLooper(), null, true /*async*/);
            mListener = listener;
        }

        public void sendDisplayEvent(int displayId, int event) {
            Message msg = obtainMessage(event, displayId, 0);
            sendMessage(msg);
        }

        public void clearEvents() {
            removeCallbacksAndMessages(null);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_DISPLAY_ADDED:
                    mListener.onDisplayAdded(msg.arg1);
                    break;
                case EVENT_DISPLAY_CHANGED:
                    mListener.onDisplayChanged(msg.arg1);
                    break;
                case EVENT_DISPLAY_REMOVED:
                    mListener.onDisplayRemoved(msg.arg1);
                    break;
            }
        }
    }
}
