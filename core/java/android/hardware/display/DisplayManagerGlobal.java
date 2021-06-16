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


import static android.hardware.display.DisplayManager.EventsMask;
import static android.view.Display.HdrCapabilities.HdrType;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PropertyInvalidatedCache;
import android.compat.annotation.UnsupportedAppUsage;
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
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayAdjustments;
import android.view.DisplayInfo;
import android.view.Surface;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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

    @IntDef(prefix = {"SWITCHING_TYPE_"}, value = {
            EVENT_DISPLAY_ADDED,
            EVENT_DISPLAY_CHANGED,
            EVENT_DISPLAY_REMOVED,
            EVENT_DISPLAY_BRIGHTNESS_CHANGED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DisplayEvent {}

    public static final int EVENT_DISPLAY_ADDED = 1;
    public static final int EVENT_DISPLAY_CHANGED = 2;
    public static final int EVENT_DISPLAY_REMOVED = 3;
    public static final int EVENT_DISPLAY_BRIGHTNESS_CHANGED = 4;

    @UnsupportedAppUsage
    private static DisplayManagerGlobal sInstance;

    // Guarded by mLock
    private boolean mDispatchNativeCallbacks = false;
    private float mNativeCallbackReportedRefreshRate;
    private final Object mLock = new Object();

    @UnsupportedAppUsage
    private final IDisplayManager mDm;

    private DisplayManagerCallback mCallback;
    private @EventsMask long mRegisteredEventsMask = 0;
    private final ArrayList<DisplayListenerDelegate> mDisplayListeners = new ArrayList<>();

    private final SparseArray<DisplayInfo> mDisplayInfoCache = new SparseArray<>();
    private final ColorSpace mWideColorSpace;
    private int[] mDisplayIdCache;

    private int mWifiDisplayScanNestCount;

    @VisibleForTesting
    public DisplayManagerGlobal(IDisplayManager dm) {
        mDm = dm;
        try {
            mWideColorSpace =
                    ColorSpace.get(
                            ColorSpace.Named.values()[mDm.getPreferredWideGamutColorSpaceId()]);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    private PropertyInvalidatedCache<Integer, DisplayInfo> mDisplayCache =
            new PropertyInvalidatedCache<Integer, DisplayInfo>(
                8, // size of display cache
                CACHE_KEY_DISPLAY_INFO_PROPERTY) {
                @Override
                protected DisplayInfo recompute(Integer id) {
                    try {
                        return mDm.getDisplayInfo(id);
                    } catch (RemoteException ex) {
                        throw ex.rethrowFromSystemServer();
                    }
                }
            };

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
        synchronized (mLock) {
            return getDisplayInfoLocked(displayId);
        }
    }

    /**
     * Gets information about a particular logical display
     * See {@link getDisplayInfo}, but assumes that {@link mLock} is held
     */
    private @Nullable DisplayInfo getDisplayInfoLocked(int displayId) {
        DisplayInfo info = null;
        if (mDisplayCache != null) {
            info = mDisplayCache.query(displayId);
        } else {
            try {
                info = mDm.getDisplayInfo(displayId);
            } catch (RemoteException ex) {
                ex.rethrowFromSystemServer();
            }
        }
        if (info == null) {
            return null;
        }

        registerCallbackIfNeededLocked();

        if (DEBUG) {
            Log.d(TAG, "getDisplayInfo: displayId=" + displayId + ", info=" + info);
        }
        return info;
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
     * Check if specified UID's content is present on display and should be granted access to it.
     *
     * @param uid UID to be checked.
     * @param displayId id of the display where presence of the content is checked.
     * @return {@code true} if UID is present on display, {@code false} otherwise.
     */
    public boolean isUidPresentOnDisplay(int uid, int displayId) {
        try {
            return mDm.isUidPresentOnDisplay(uid, displayId);
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
            @Nullable Handler handler, @EventsMask long eventsMask) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        if (eventsMask == 0) {
            throw new IllegalArgumentException("The set of events to listen to must not be empty.");
        }

        synchronized (mLock) {
            int index = findDisplayListenerLocked(listener);
            if (index < 0) {
                Looper looper = getLooperForHandler(handler);
                mDisplayListeners.add(new DisplayListenerDelegate(listener, looper, eventsMask));
                registerCallbackIfNeededLocked();
            } else {
                mDisplayListeners.get(index).setEventsMask(eventsMask);
            }
            updateCallbackIfNeededLocked();
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
                updateCallbackIfNeededLocked();
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

    @EventsMask
    private int calculateEventsMaskLocked() {
        int mask = 0;
        final int numListeners = mDisplayListeners.size();
        for (int i = 0; i < numListeners; i++) {
            mask |= mDisplayListeners.get(i).mEventsMask;
        }
        return mask;
    }

    private void registerCallbackIfNeededLocked() {
        if (mCallback == null) {
            mCallback = new DisplayManagerCallback();
            updateCallbackIfNeededLocked();
        }
    }

    private void updateCallbackIfNeededLocked() {
        int mask = calculateEventsMaskLocked();
        if (mask != mRegisteredEventsMask) {
            try {
                mDm.registerCallbackWithEventMask(mCallback, mask);
                mRegisteredEventsMask = mask;
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }
    }

    private void handleDisplayEvent(int displayId, @DisplayEvent int event) {
        synchronized (mLock) {
            if (USE_CACHE) {
                mDisplayInfoCache.remove(displayId);

                if (event == EVENT_DISPLAY_ADDED || event == EVENT_DISPLAY_REMOVED) {
                    mDisplayIdCache = null;
                }
            }

            final int numListeners = mDisplayListeners.size();
            DisplayInfo info = getDisplayInfo(displayId);
            for (int i = 0; i < numListeners; i++) {
                mDisplayListeners.get(i).sendDisplayEvent(displayId, event, info);
            }
            if (event == EVENT_DISPLAY_CHANGED && mDispatchNativeCallbacks) {
                // Choreographer only supports a single display, so only dispatch refresh rate
                // changes for the default display.
                if (displayId == Display.DEFAULT_DISPLAY) {
                    // We can likely save a binder hop if we attach the refresh rate onto the
                    // listener.
                    DisplayInfo display = getDisplayInfoLocked(displayId);
                    if (display != null
                            && mNativeCallbackReportedRefreshRate != display.getRefreshRate()) {
                        mNativeCallbackReportedRefreshRate = display.getRefreshRate();
                        // Signal native callbacks if we ever set a refresh rate.
                        nSignalNativeCallbacks(mNativeCallbackReportedRefreshRate);
                    }
                }
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

    /**
     * Sets the HDR types that have been disabled by user.
     * @param userDisabledHdrTypes the HDR types to disable. The HDR types are any of
     */
    public void setUserDisabledHdrTypes(@HdrType int[] userDisabledHdrTypes) {
        try {
            mDm.setUserDisabledHdrTypes(userDisabledHdrTypes);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Sets whether or not the user disabled HDR types are returned from
     * {@link Display#getHdrCapabilities}.
     *
     * @param areUserDisabledHdrTypesAllowed If true, the user-disabled
     * types are ignored and returned, if the display supports them. If
     * false, the user-disabled types are taken into consideration and
     * are never returned, even if the display supports them.
     */
    public void setAreUserDisabledHdrTypesAllowed(boolean areUserDisabledHdrTypesAllowed) {
        try {
            mDm.setAreUserDisabledHdrTypesAllowed(areUserDisabledHdrTypesAllowed);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether or not the user-disabled HDR types are returned from
     * {@link Display#getHdrCapabilities}.
     */
    public boolean areUserDisabledHdrTypesAllowed() {
        try {
            return mDm.areUserDisabledHdrTypesAllowed();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the HDR formats disabled by the user.
     *
     */
    public int[] getUserDisabledHdrTypes() {
        try {
            return mDm.getUserDisabledHdrTypes();
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

    public VirtualDisplay createVirtualDisplay(@NonNull Context context, MediaProjection projection,
            @NonNull VirtualDisplayConfig virtualDisplayConfig, VirtualDisplay.Callback callback,
            Handler handler) {
        VirtualDisplayCallback callbackWrapper = new VirtualDisplayCallback(callback, handler);
        IMediaProjection projectionToken = projection != null ? projection.getProjection() : null;
        int displayId;
        try {
            displayId = mDm.createVirtualDisplay(virtualDisplayConfig, callbackWrapper,
                    projectionToken, context.getPackageName());
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
        if (displayId < 0) {
            Log.e(TAG, "Could not create virtual display: " + virtualDisplayConfig.getName());
            return null;
        }
        Display display = getRealDisplay(displayId);
        if (display == null) {
            Log.wtf(TAG, "Could not obtain display info for newly created "
                    + "virtual display: " + virtualDisplayConfig.getName());
            try {
                mDm.releaseVirtualDisplay(callbackWrapper);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
            return null;
        }
        return new VirtualDisplay(this, display, callbackWrapper,
                virtualDisplayConfig.getSurface());
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
     * Retrieves Brightness Info for the specified display.
     */
    public BrightnessInfo getBrightnessInfo(int displayId) {
        try {
            return mDm.getBrightnessInfo(displayId);
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
     * Sets the brightness configuration for a given display.
     *
     * @hide
     */
    public void setBrightnessConfigurationForDisplay(BrightnessConfiguration c,
            String uniqueDisplayId, int userId, String packageName) {
        try {
            mDm.setBrightnessConfigurationForDisplay(c, uniqueDisplayId, userId, packageName);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the brightness configuration for a given display or null if one hasn't been set.
     *
     * @hide
     */
    public BrightnessConfiguration getBrightnessConfigurationForDisplay(String uniqueDisplayId,
            int userId) {
        try {
            return mDm.getBrightnessConfigurationForDisplay(uniqueDisplayId, userId);
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
     * Gets the last requested minimal post processing setting for the display with displayId.
     *
     * @hide
     */
    public boolean isMinimalPostProcessingRequested(int displayId) {
        try {
            return mDm.isMinimalPostProcessingRequested(displayId);
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
     * @param brightness The brightness value from 0.0f to 1.0f.
     *
     * @hide Requires signature permission.
     */
    public void setTemporaryBrightness(int displayId, float brightness) {
        try {
            mDm.setTemporaryBrightness(displayId, brightness);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }


    /**
     * Sets the brightness of the display.
     *
     * @param brightness The brightness value from 0.0f to 1.0f.
     *
     * @hide
     */
    public void setBrightness(int displayId, float brightness) {
        try {
            mDm.setBrightness(displayId, brightness);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the brightness of the display.
     *
     * @param displayId The display from which to get the brightness
     *
     * @hide
     */
    public float getBrightness(int displayId) {
        try {
            return mDm.getBrightness(displayId);
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

    /**
     * When enabled the app requested display resolution and refresh rate is always selected
     * in DisplayModeDirector regardless of user settings and policies for low brightness, low
     * battery etc.
     */
    public void setShouldAlwaysRespectAppRequestedMode(boolean enabled) {
        try {
            mDm.setShouldAlwaysRespectAppRequestedMode(enabled);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether DisplayModeDirector is running in a mode which always selects the app
     * requested display mode and ignores user settings and policies for low brightness, low
     * battery etc.
     */
    public boolean shouldAlwaysRespectAppRequestedMode() {
        try {
            return mDm.shouldAlwaysRespectAppRequestedMode();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the refresh rate switching type.
     *
     * @hide
     */
    public void setRefreshRateSwitchingType(@DisplayManager.SwitchingType int newValue) {
        try {
            mDm.setRefreshRateSwitchingType(newValue);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the refresh rate switching type.
     *
     * @hide
     */
    @DisplayManager.SwitchingType
    public int getRefreshRateSwitchingType() {
        try {
            return mDm.getRefreshRateSwitchingType();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    private final class DisplayManagerCallback extends IDisplayManagerCallback.Stub {
        @Override
        public void onDisplayEvent(int displayId, @DisplayEvent int event) {
            if (DEBUG) {
                Log.d(TAG, "onDisplayEvent: displayId=" + displayId + ", event=" + event);
            }
            handleDisplayEvent(displayId, event);
        }
    }

    private static final class DisplayListenerDelegate extends Handler {
        public final DisplayListener mListener;
        public long mEventsMask;

        private final DisplayInfo mDisplayInfo = new DisplayInfo();

        DisplayListenerDelegate(DisplayListener listener, @NonNull Looper looper,
                @EventsMask long eventsMask) {
            super(looper, null, true /*async*/);
            mListener = listener;
            mEventsMask = eventsMask;
        }

        public void sendDisplayEvent(int displayId, @DisplayEvent int event, DisplayInfo info) {
            Message msg = obtainMessage(event, displayId, 0, info);
            sendMessage(msg);
        }

        public void clearEvents() {
            removeCallbacksAndMessages(null);
        }

        public synchronized void setEventsMask(@EventsMask long newEventsMask) {
            mEventsMask = newEventsMask;
        }

        @Override
        public synchronized void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_DISPLAY_ADDED:
                    if ((mEventsMask & DisplayManager.EVENT_FLAG_DISPLAY_ADDED) != 0) {
                        mListener.onDisplayAdded(msg.arg1);
                    }
                    break;
                case EVENT_DISPLAY_CHANGED:
                    if ((mEventsMask & DisplayManager.EVENT_FLAG_DISPLAY_CHANGED) != 0) {
                        DisplayInfo newInfo = (DisplayInfo) msg.obj;
                        if (newInfo != null && !newInfo.equals(mDisplayInfo)) {
                            mDisplayInfo.copyFrom(newInfo);
                            mListener.onDisplayChanged(msg.arg1);
                        }
                    }
                    break;
                case EVENT_DISPLAY_BRIGHTNESS_CHANGED:
                    if ((mEventsMask & DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS) != 0) {
                        mListener.onDisplayChanged(msg.arg1);
                    }
                    break;
                case EVENT_DISPLAY_REMOVED:
                    if ((mEventsMask & DisplayManager.EVENT_FLAG_DISPLAY_REMOVED) != 0) {
                        mListener.onDisplayRemoved(msg.arg1);
                    }
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

    /**
     * Name of the property containing a unique token which changes every time we update the
     * system's display configuration.
     */
    public static final String CACHE_KEY_DISPLAY_INFO_PROPERTY =
            "cache_key.display_info";

    /**
     * Invalidates the contents of the display info cache for all applications. Can only
     * be called by system_server.
     */
    public static void invalidateLocalDisplayInfoCaches() {
        PropertyInvalidatedCache.invalidateCache(CACHE_KEY_DISPLAY_INFO_PROPERTY);
    }

    /**
     * Disables the binder call cache.
     */
    public void disableLocalDisplayInfoCaches() {
        mDisplayCache = null;
    }

    private static native void nSignalNativeCallbacks(float refreshRate);

    // Called from AChoreographer via JNI.
    // Registers AChoreographer so that refresh rate callbacks can be dispatched from DMS.
    private void registerNativeChoreographerForRefreshRateCallbacks() {
        synchronized (mLock) {
            registerCallbackIfNeededLocked();
            mDispatchNativeCallbacks = true;
            DisplayInfo display = getDisplayInfoLocked(Display.DEFAULT_DISPLAY);
            if (display != null) {
                // We need to tell AChoreographer instances the current refresh rate so that apps
                // can get it for free once a callback first registers.
                mNativeCallbackReportedRefreshRate = display.getRefreshRate();
                nSignalNativeCallbacks(mNativeCallbackReportedRefreshRate);
            }
        }
    }

    // Called from AChoreographer via JNI.
    // Unregisters AChoreographer from receiving refresh rate callbacks.
    private void unregisterNativeChoreographerForRefreshRateCallbacks() {
        synchronized (mLock) {
            mDispatchNativeCallbacks = false;
        }
    }
}
