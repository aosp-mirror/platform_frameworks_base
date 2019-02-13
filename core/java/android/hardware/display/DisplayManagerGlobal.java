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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.graphics.ColorSpace;
import android.graphics.Point;
import android.hardware.display.DisplayManager.DisplayListener;
import android.media.projection.IMediaProjection;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayAdjustments;
import android.view.DisplayInfo;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    @UnsupportedAppUsage
    private static DisplayManagerGlobal sInstance;

    private final Object mLock = new Object();

    @UnsupportedAppUsage
    private final IDisplayManager mDm;

    private DisplayManagerCallback mCallback;
    private final ArrayList<DisplayListenerDelegate> mDisplayListeners =
            new ArrayList<DisplayListenerDelegate>();

    private final SparseArray<DisplayInfo> mDisplayInfoCache = new SparseArray<DisplayInfo>();
    private final ColorSpace mWideColorSpace;
    private int[] mDisplayIdCache;

    private int mWifiDisplayScanNestCount;

    private DisplayManagerGlobal(IDisplayManager dm) {
        mDm = dm;
        try {
            mWideColorSpace =
                    ColorSpace.get(
                            ColorSpace.Named.values()[mDm.getPreferredWideGamutColorSpaceId()]);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets an instance of the display manager global singleton.
     *
     * @return The display manager instance, may be null early in system startup
     * before the display manager has been fully initialized.
     */
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets all currently valid logical display ids.
     *
     * @return An array containing all display ids.
     */
    @UnsupportedAppUsage
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
            throw ex.rethrowFromSystemServer();
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
     * Gets information about a logical display.
     *
     * The display metrics may be adjusted to provide compatibility
     * for legacy applications or limited screen areas.
     *
     * @param displayId The logical display id.
     * @param resources Resources providing compatibility info.
     * @return The display object, or null if there is no display with the given id.
     */
    public Display getCompatibleDisplay(int displayId, Resources resources) {
        DisplayInfo displayInfo = getDisplayInfo(displayId);
        if (displayInfo == null) {
            return null;
        }
        return new Display(this, displayId, displayInfo, resources);
    }

    /**
     * Gets information about a logical display without applying any compatibility metrics.
     *
     * @param displayId The logical display id.
     * @return The display object, or null if there is no display with the given id.
     */
    @UnsupportedAppUsage
    public Display getRealDisplay(int displayId) {
        return getCompatibleDisplay(displayId, DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
    }

    /**
     * Register a listener for display-related changes.
     *
     * @param listener The listener that will be called when display changes occur.
     * @param handler Handler for the thread that will be receiving the callbacks. May be null.
     * If null, listener will use the handler for the current thread, and if still null,
     * the handler for the main thread.
     * If that is still null, a runtime exception will be thrown.
     */
    public void registerDisplayListener(@NonNull DisplayListener listener,
            @Nullable Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        synchronized (mLock) {
            int index = findDisplayListenerLocked(listener);
            if (index < 0) {
                Looper looper = getLooperForHandler(handler);
                mDisplayListeners.add(new DisplayListenerDelegate(listener, looper));
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

    private static Looper getLooperForHandler(@Nullable Handler handler) {
        Looper looper = handler != null ? handler.getLooper() : Looper.myLooper();
        if (looper == null) {
            looper = Looper.getMainLooper();
        }
        if (looper == null) {
            throw new RuntimeException("Could not get Looper for the UI thread.");
        }
        return looper;
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
                throw ex.rethrowFromSystemServer();
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
                    throw ex.rethrowFromSystemServer();
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
                    throw ex.rethrowFromSystemServer();
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
            throw ex.rethrowFromSystemServer();
        }
    }

    public void pauseWifiDisplay() {
        try {
            mDm.pauseWifiDisplay();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    public void resumeWifiDisplay() {
        try {
            mDm.resumeWifiDisplay();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    @UnsupportedAppUsage
    public void disconnectWifiDisplay() {
        try {
            mDm.disconnectWifiDisplay();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    public void renameWifiDisplay(String deviceAddress, String alias) {
        if (deviceAddress == null) {
            throw new IllegalArgumentException("deviceAddress must not be null");
        }

        try {
            mDm.renameWifiDisplay(deviceAddress, alias);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    public void forgetWifiDisplay(String deviceAddress) {
        if (deviceAddress == null) {
            throw new IllegalArgumentException("deviceAddress must not be null");
        }

        try {
            mDm.forgetWifiDisplay(deviceAddress);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    @UnsupportedAppUsage
    public WifiDisplayStatus getWifiDisplayStatus() {
        try {
            return mDm.getWifiDisplayStatus();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    public void requestColorMode(int displayId, int colorMode) {
        try {
            mDm.requestColorMode(displayId, colorMode);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    public VirtualDisplay createVirtualDisplay(Context context, MediaProjection projection,
            String name, int width, int height, int densityDpi, Surface surface, int flags,
            VirtualDisplay.Callback callback, Handler handler, String uniqueId) {
        if (TextUtils.isEmpty(name)) {
            throw new IllegalArgumentException("name must be non-null and non-empty");
        }
        if (width <= 0 || height <= 0 || densityDpi <= 0) {
            throw new IllegalArgumentException("width, height, and densityDpi must be "
                    + "greater than 0");
        }

        VirtualDisplayCallback callbackWrapper = new VirtualDisplayCallback(callback, handler);
        IMediaProjection projectionToken = projection != null ? projection.getProjection() : null;
        int displayId;
        try {
            displayId = mDm.createVirtualDisplay(callbackWrapper, projectionToken,
                    context.getPackageName(), name, width, height, densityDpi, surface, flags,
                    uniqueId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
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
                mDm.releaseVirtualDisplay(callbackWrapper);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
            return null;
        }
        return new VirtualDisplay(this, display, callbackWrapper, surface);
    }

    public void setVirtualDisplaySurface(IVirtualDisplayCallback token, Surface surface) {
        try {
            mDm.setVirtualDisplaySurface(token, surface);
            setVirtualDisplayState(token, surface != null);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    public void resizeVirtualDisplay(IVirtualDisplayCallback token,
            int width, int height, int densityDpi) {
        try {
            mDm.resizeVirtualDisplay(token, width, height, densityDpi);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    public void releaseVirtualDisplay(IVirtualDisplayCallback token) {
        try {
            mDm.releaseVirtualDisplay(token);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    void setVirtualDisplayState(IVirtualDisplayCallback token, boolean isOn) {
        try {
            mDm.setVirtualDisplayState(token, isOn);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the stable device display size, in pixels.
     */
    public Point getStableDisplaySize() {
        try {
            return mDm.getStableDisplaySize();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves brightness change events.
     */
    public List<BrightnessChangeEvent> getBrightnessEvents(String callingPackage) {
        try {
            ParceledListSlice<BrightnessChangeEvent> events =
                    mDm.getBrightnessEvents(callingPackage);
            if (events == null) {
                return Collections.emptyList();
            }
            return events.getList();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the preferred wide gamut color space for all displays.
     * The wide gamut color space is returned from composition pipeline
     * based on hardware capability.
     *
     * @hide
     */
    public ColorSpace getPreferredWideGamutColorSpace() {
        return mWideColorSpace;
    }

    /**
     * Sets the global brightness configuration for a given user.
     *
     * @hide
     */
    public void setBrightnessConfigurationForUser(BrightnessConfiguration c, int userId,
            String packageName) {
        try {
            mDm.setBrightnessConfigurationForUser(c, userId, packageName);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the global brightness configuration for a given user or null if one hasn't been set.
     *
     * @hide
     */
    public BrightnessConfiguration getBrightnessConfigurationForUser(int userId) {
        try {
            return mDm.getBrightnessConfigurationForUser(userId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the default brightness configuration or null if one hasn't been configured.
     *
     * @hide
     */
    public BrightnessConfiguration getDefaultBrightnessConfiguration() {
        try {
            return mDm.getDefaultBrightnessConfiguration();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Temporarily sets the brightness of the display.
     * <p>
     * Requires the {@link android.Manifest.permission#CONTROL_DISPLAY_BRIGHTNESS} permission.
     * </p>
     *
     * @param brightness The brightness value from 0 to 255.
     *
     * @hide Requires signature permission.
     */
    public void setTemporaryBrightness(int brightness) {
        try {
            mDm.setTemporaryBrightness(brightness);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Temporarily sets the auto brightness adjustment factor.
     * <p>
     * Requires the {@link android.Manifest.permission#CONTROL_DISPLAY_BRIGHTNESS} permission.
     * </p>
     *
     * @param adjustment The adjustment factor from -1.0 to 1.0.
     *
     * @hide Requires signature permission.
     */
    public void setTemporaryAutoBrightnessAdjustment(float adjustment) {
        try {
            mDm.setTemporaryAutoBrightnessAdjustment(adjustment);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the minimum brightness curve, which guarantess that any brightness curve that dips
     * below it is rejected by the system.
     * This prevent auto-brightness from setting the screen so dark as to prevent the user from
     * resetting or disabling it, and maps lux to the absolute minimum nits that are still readable
     * in that ambient brightness.
     *
     * @return The minimum brightness curve (as lux values and their corresponding nits values).
     */
    public Pair<float[], float[]> getMinimumBrightnessCurve() {
        try {
            Curve curve = mDm.getMinimumBrightnessCurve();
            return Pair.create(curve.getX(), curve.getY());
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves ambient brightness stats.
     */
    public List<AmbientBrightnessDayStats> getAmbientBrightnessStats() {
        try {
            ParceledListSlice<AmbientBrightnessDayStats> stats = mDm.getAmbientBrightnessStats();
            if (stats == null) {
                return Collections.emptyList();
            }
            return stats.getList();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
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

        DisplayListenerDelegate(DisplayListener listener, @NonNull Looper looper) {
            super(looper, null, true /*async*/);
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

    private final static class VirtualDisplayCallback extends IVirtualDisplayCallback.Stub {
        private VirtualDisplayCallbackDelegate mDelegate;

        public VirtualDisplayCallback(VirtualDisplay.Callback callback, Handler handler) {
            if (callback != null) {
                mDelegate = new VirtualDisplayCallbackDelegate(callback, handler);
            }
        }

        @Override // Binder call
        public void onPaused() {
            if (mDelegate != null) {
                mDelegate.sendEmptyMessage(VirtualDisplayCallbackDelegate.MSG_DISPLAY_PAUSED);
            }
        }

        @Override // Binder call
        public void onResumed() {
            if (mDelegate != null) {
                mDelegate.sendEmptyMessage(VirtualDisplayCallbackDelegate.MSG_DISPLAY_RESUMED);
            }
        }

        @Override // Binder call
        public void onStopped() {
            if (mDelegate != null) {
                mDelegate.sendEmptyMessage(VirtualDisplayCallbackDelegate.MSG_DISPLAY_STOPPED);
            }
        }
    }

    private final static class VirtualDisplayCallbackDelegate extends Handler {
        public static final int MSG_DISPLAY_PAUSED = 0;
        public static final int MSG_DISPLAY_RESUMED = 1;
        public static final int MSG_DISPLAY_STOPPED = 2;

        private final VirtualDisplay.Callback mCallback;

        public VirtualDisplayCallbackDelegate(VirtualDisplay.Callback callback,
                Handler handler) {
            super(handler != null ? handler.getLooper() : Looper.myLooper(), null, true /*async*/);
            mCallback = callback;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DISPLAY_PAUSED:
                    mCallback.onPaused();
                    break;
                case MSG_DISPLAY_RESUMED:
                    mCallback.onResumed();
                    break;
                case MSG_DISPLAY_STOPPED:
                    mCallback.onStopped();
                    break;
            }
        }
    }
}
