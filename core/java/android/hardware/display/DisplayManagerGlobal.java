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

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.ActivityThread;
import android.app.PropertyInvalidatedCache;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.graphics.ColorSpace;
import android.graphics.Point;
import android.hardware.OverlayProperties;
import android.hardware.display.DisplayManager.DisplayListener;
import android.hardware.graphics.common.DisplayDecorationSupport;
import android.media.projection.IMediaProjection;
import android.media.projection.MediaProjection;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Trace;
import android.sysprop.DisplayProperties;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayAdjustments;
import android.view.DisplayInfo;
import android.view.Surface;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manager communication with the display manager service on behalf of
 * an application process.  You're probably looking for {@link DisplayManager}.
 *
 * @hide
 */
public final class DisplayManagerGlobal {
    private static final String TAG = "DisplayManager";

    private static final String EXTRA_LOGGING_PACKAGE_NAME =
            DisplayProperties.debug_vri_package().orElse(null);
    private static String sCurrentPackageName = ActivityThread.currentPackageName();
    private static boolean sExtraDisplayListenerLogging = initExtraLogging();

    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.DisplayManager DEBUG && adb reboot'
    private static final boolean DEBUG = DisplayManager.DEBUG || sExtraDisplayListenerLogging;

    // True if display info and display ids should be cached.
    //
    // FIXME: The cache is currently disabled because it's unclear whether we have the
    // necessary guarantees that the caches will always be flushed before clients
    // attempt to observe their new state.  For example, depending on the order
    // in which the binder transactions take place, we might have a problem where
    // an application could start processing a configuration change due to a display
    // orientation change before the display info cache has actually been invalidated.
    private static final boolean USE_CACHE = false;

    @IntDef(prefix = {"EVENT_DISPLAY_"}, flag = true, value = {
            EVENT_DISPLAY_ADDED,
            EVENT_DISPLAY_CHANGED,
            EVENT_DISPLAY_REMOVED,
            EVENT_DISPLAY_BRIGHTNESS_CHANGED,
            EVENT_DISPLAY_HDR_SDR_RATIO_CHANGED,
            EVENT_DISPLAY_CONNECTED,
            EVENT_DISPLAY_DISCONNECTED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DisplayEvent {}

    public static final int EVENT_DISPLAY_ADDED = 1;
    public static final int EVENT_DISPLAY_CHANGED = 2;
    public static final int EVENT_DISPLAY_REMOVED = 3;
    public static final int EVENT_DISPLAY_BRIGHTNESS_CHANGED = 4;
    public static final int EVENT_DISPLAY_HDR_SDR_RATIO_CHANGED = 5;
    public static final int EVENT_DISPLAY_CONNECTED = 6;
    public static final int EVENT_DISPLAY_DISCONNECTED = 7;

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
    private final CopyOnWriteArrayList<DisplayListenerDelegate> mDisplayListeners =
            new CopyOnWriteArrayList<>();

    private final SparseArray<DisplayInfo> mDisplayInfoCache = new SparseArray<>();
    private final ColorSpace mWideColorSpace;
    private final OverlayProperties mOverlayProperties;
    private int[] mDisplayIdCache;

    private int mWifiDisplayScanNestCount;

    private final Binder mToken = new Binder();

    @VisibleForTesting
    public DisplayManagerGlobal(IDisplayManager dm) {
        mDm = dm;
        initExtraLogging();

        try {
            mWideColorSpace =
                    ColorSpace.get(
                            ColorSpace.Named.values()[mDm.getPreferredWideGamutColorSpaceId()]);
            mOverlayProperties = mDm.getOverlaySupport();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    private PropertyInvalidatedCache<Integer, DisplayInfo> mDisplayCache =
            new PropertyInvalidatedCache<Integer, DisplayInfo>(
                8, // size of display cache
                CACHE_KEY_DISPLAY_INFO_PROPERTY) {
                @Override
                public DisplayInfo recompute(Integer id) {
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
        return getDisplayIds(/* includeDisabled= */ false);
    }

    /**
     * Gets all currently valid logical display ids.
     *
     * @param includeDisabled True if the returned list of displays includes disabled displays.
     * @return An array containing all display ids.
     */
    public int[] getDisplayIds(boolean includeDisabled) {
        try {
            synchronized (mLock) {
                if (USE_CACHE) {
                    if (mDisplayIdCache != null) {
                        return mDisplayIdCache;
                    }
                }

                int[] displayIds = mDm.getDisplayIds(includeDisabled);
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
     * @param packageName of the calling package.
     */
    public void registerDisplayListener(@NonNull DisplayListener listener,
            @Nullable Handler handler, @EventsMask long eventsMask, String packageName) {
        Looper looper = getLooperForHandler(handler);
        Handler springBoard = new Handler(looper);
        registerDisplayListener(listener, new HandlerExecutor(springBoard), eventsMask,
                packageName);
    }

    /**
     * Register a listener for display-related changes.
     *
     * @param listener The listener that will be called when display changes occur.
     * @param executor Executor for the thread that will be receiving the callbacks. Cannot be null.
     * @param eventsMask Mask of events to be listened to.
     * @param packageName of the calling package.
     */
    public void registerDisplayListener(@NonNull DisplayListener listener,
            @NonNull Executor executor, @EventsMask long eventsMask, String packageName) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        if (eventsMask == 0) {
            throw new IllegalArgumentException("The set of events to listen to must not be empty.");
        }

        if (extraLogging()) {
            Slog.i(TAG, "Registering Display Listener: "
                    + Long.toBinaryString(eventsMask) + ", packageName: " + packageName);
        }

        synchronized (mLock) {
            int index = findDisplayListenerLocked(listener);
            if (index < 0) {
                mDisplayListeners.add(new DisplayListenerDelegate(listener, executor, eventsMask,
                        packageName));
                registerCallbackIfNeededLocked();
            } else {
                mDisplayListeners.get(index).setEventsMask(eventsMask);
            }
            updateCallbackIfNeededLocked();
            maybeLogAllDisplayListeners();
        }
    }

    public void unregisterDisplayListener(DisplayListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        if (extraLogging()) {
            Slog.i(TAG, "Unregistering Display Listener: " + listener);
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
        maybeLogAllDisplayListeners();
    }

    private void maybeLogAllDisplayListeners() {
        if (!extraLogging()) {
            return;
        }

        Slog.i(TAG, "Currently Registered Display Listeners:");
        for (int i = 0; i < mDisplayListeners.size(); i++) {
            Slog.i(TAG, i + ": " + mDisplayListeners.get(i));
        }
    }

    /**
     * Called when there is a display-related window configuration change. Reroutes the event from
     * WindowManager to make sure the {@link Display} fields are up-to-date in the last callback.
     * @param displayId the logical display that was changed.
     */
    public void handleDisplayChangeFromWindowManager(int displayId) {
        // There can be racing condition between DMS and WMS callbacks, so force triggering the
        // listener to make sure the client can get the onDisplayChanged callback even if
        // DisplayInfo is not changed (Display read from both DisplayInfo and WindowConfiguration).
        handleDisplayEvent(displayId, EVENT_DISPLAY_CHANGED, true /* forceUpdate */);
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
        if (mDispatchNativeCallbacks) {
            mask |= DisplayManager.EVENT_FLAG_DISPLAY_ADDED
                    | DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
                    | DisplayManager.EVENT_FLAG_DISPLAY_REMOVED;
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
        if (DEBUG) {
            Log.d(TAG, "Mask for listener: " + mask);
        }
        if (mask != mRegisteredEventsMask) {
            try {
                mDm.registerCallbackWithEventMask(mCallback, mask);
                mRegisteredEventsMask = mask;
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }
    }

    private void handleDisplayEvent(int displayId, @DisplayEvent int event, boolean forceUpdate) {
        final DisplayInfo info;
        synchronized (mLock) {
            if (USE_CACHE) {
                mDisplayInfoCache.remove(displayId);

                if (event == EVENT_DISPLAY_ADDED || event == EVENT_DISPLAY_REMOVED) {
                    mDisplayIdCache = null;
                }
            }

            info = getDisplayInfoLocked(displayId);
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
        // Accepting an Executor means the listener may be synchronously invoked, so we must
        // not be holding mLock when we do so
        for (DisplayListenerDelegate listener : mDisplayListeners) {
            listener.sendDisplayEvent(displayId, event, info, forceUpdate);
        }
    }

    /**
     * Enable a connected display that is currently disabled.
     * @hide
     */
    @RequiresPermission("android.permission.MANAGE_DISPLAYS")
    public void enableConnectedDisplay(int displayId) {
        try {
            mDm.enableConnectedDisplay(displayId);
        } catch (RemoteException ex) {
            Log.e(TAG, "Error trying to enable external display", ex);
        }
    }


    /**
     * Disable a connected display that is currently enabled.
     * @hide
     */
    @RequiresPermission("android.permission.MANAGE_DISPLAYS")
    public void disableConnectedDisplay(int displayId) {
        try {
            mDm.disableConnectedDisplay(displayId);
        } catch (RemoteException ex) {
            Log.e(TAG, "Error trying to enable external display", ex);
        }
    }

    /**
     * Request to power a display OFF or reset it to a power state it supposed to have.
     * @param displayId the id of the display
     * @param state one of {@link android.view.Display#STATE_UNKNOWN} (to reset the state to
     *  the one the display should have had now), {@link android.view.Display#STATE_OFF}.
     * @return true if successful, false otherwise
     * @hide
     */
    @RequiresPermission("android.permission.MANAGE_DISPLAYS")
    public boolean requestDisplayPower(int displayId, int state) {
        try {
            return mDm.requestDisplayPower(displayId, state);
        } catch (RemoteException ex) {
            Log.e(TAG, "Error trying to request display power:"
                    + " state=" + state, ex);
            return false;
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

    /**
     * Overrides HDR modes for a display device.
     *
     */
    @RequiresPermission(Manifest.permission.ACCESS_SURFACE_FLINGER)
    public void overrideHdrTypes(int displayId, int[] modes) {
        try {
            mDm.overrideHdrTypes(displayId, modes);
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
            @Nullable Executor executor) {
        VirtualDisplayCallback callbackWrapper = new VirtualDisplayCallback(callback, executor);
        IMediaProjection projectionToken = projection != null ? projection.getProjection() : null;
        int displayId;
        try {
            displayId = mDm.createVirtualDisplay(virtualDisplayConfig, callbackWrapper,
                    projectionToken, context.getPackageName());
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
        return createVirtualDisplayWrapper(virtualDisplayConfig, callbackWrapper,
                displayId);
    }

    /**
     * Create a VirtualDisplay wrapper object for a newly created virtual display ; to be called
     * once the display has been created in system_server.
     */
    @Nullable
    public VirtualDisplay createVirtualDisplayWrapper(VirtualDisplayConfig virtualDisplayConfig,
            IVirtualDisplayCallback callbackWrapper, int displayId) {
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
     * Gets the overlay properties for all displays.
     *
     * @hide
     */
    public OverlayProperties getOverlaySupport() {
        return mOverlayProperties;
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
     * Report whether/how the display supports DISPLAY_DECORATION.
     *
     * @param displayId The display whose support is being queried.
     *
     * @hide
     */
    public DisplayDecorationSupport getDisplayDecorationSupport(int displayId) {
        try {
            return mDm.getDisplayDecorationSupport(displayId);
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
     * Sets the default display mode, according to the refresh rate and the resolution chosen by the
     * user.
     */
    public void setUserPreferredDisplayMode(int displayId, Display.Mode mode) {
        try {
            mDm.setUserPreferredDisplayMode(displayId, mode);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the user preferred display mode.
     */
    public Display.Mode getUserPreferredDisplayMode(int displayId) {
        try {
            return mDm.getUserPreferredDisplayMode(displayId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the system preferred display mode.
     */
    public Display.Mode getSystemPreferredDisplayMode(int displayId) {
        try {
            return mDm.getSystemPreferredDisplayMode(displayId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the {@link HdrConversionMode} for the device.
     */
    public void setHdrConversionMode(@NonNull HdrConversionMode hdrConversionMode) {
        try {
            mDm.setHdrConversionMode(hdrConversionMode);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the {@link HdrConversionMode} of the device, which is set by the user.
     * The HDR conversion mode chosen by user is returned irrespective of whether HDR conversion
     * is disabled by an app.
     */
    public HdrConversionMode getHdrConversionModeSetting() {
        try {
            return mDm.getHdrConversionModeSetting();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the {@link HdrConversionMode} of the device.
     */
    public HdrConversionMode getHdrConversionMode() {
        try {
            return mDm.getHdrConversionMode();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the HDR output types supported by the device.
     */
    public @HdrType int[] getSupportedHdrOutputTypes() {
        try {
            return mDm.getSupportedHdrOutputTypes();
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

    /**
     * Sets allowed display mode ids
     *
     * @hide
     */
    @RequiresPermission("android.permission.RESTRICT_DISPLAY_MODES")
    public void requestDisplayModes(int displayId, @Nullable int[] modeIds) {
        try {
            mDm.requestDisplayModes(mToken, displayId, modeIds);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    private final class DisplayManagerCallback extends IDisplayManagerCallback.Stub {
        @Override
        public void onDisplayEvent(int displayId, @DisplayEvent int event) {
            if (DEBUG) {
                Log.d(TAG, "onDisplayEvent: displayId=" + displayId + ", event=" + eventToString(
                        event));
            }
            handleDisplayEvent(displayId, event, false /* forceUpdate */);
        }
    }

    private static final class DisplayListenerDelegate {
        public final DisplayListener mListener;
        public volatile long mEventsMask;

        private final DisplayInfo mDisplayInfo = new DisplayInfo();
        private final Executor mExecutor;
        private AtomicLong mGenerationId = new AtomicLong(1);
        private final String mPackageName;

        DisplayListenerDelegate(DisplayListener listener, @NonNull Executor executor,
                @EventsMask long eventsMask, String packageName) {
            mExecutor = executor;
            mListener = listener;
            mEventsMask = eventsMask;
            mPackageName = packageName;
        }

        void sendDisplayEvent(int displayId, @DisplayEvent int event, @Nullable DisplayInfo info,
                boolean forceUpdate) {
            if (extraLogging()) {
                Slog.i(TAG, "Sending Display Event: " + eventToString(event));
            }
            long generationId = mGenerationId.get();
            mExecutor.execute(() -> {
                // If the generation id's don't match we were canceled
                if (generationId == mGenerationId.get()) {
                    handleDisplayEventInner(displayId, event, info, forceUpdate);
                }
            });
        }

        void clearEvents() {
            mGenerationId.incrementAndGet();
        }

        void setEventsMask(@EventsMask long newEventsMask) {
            mEventsMask = newEventsMask;
        }

        private void handleDisplayEventInner(int displayId, @DisplayEvent int event,
                @Nullable DisplayInfo info, boolean forceUpdate) {
            if (extraLogging()) {
                Slog.i(TAG, "DLD(" + eventToString(event)
                        + ", display=" + displayId
                        + ", mEventsMask=" + Long.toBinaryString(mEventsMask)
                        + ", mPackageName=" + mPackageName
                        + ", displayInfo=" + info
                        + ", listener=" + mListener.getClass() + ")");
            }
            if (DEBUG) {
                Trace.beginSection(
                        TextUtils.trimToSize(
                                "DLD(" + eventToString(event)
                                + ", display=" + displayId
                                + ", listener=" + mListener.getClass() + ")", 127));
            }
            switch (event) {
                case EVENT_DISPLAY_ADDED:
                    if ((mEventsMask & DisplayManager.EVENT_FLAG_DISPLAY_ADDED) != 0) {
                        mListener.onDisplayAdded(displayId);
                    }
                    break;
                case EVENT_DISPLAY_CHANGED:
                    if ((mEventsMask & DisplayManager.EVENT_FLAG_DISPLAY_CHANGED) != 0) {
                        if (info != null && (forceUpdate || !info.equals(mDisplayInfo))) {
                            if (extraLogging()) {
                                Slog.i(TAG, "Sending onDisplayChanged: Display Changed. Info: "
                                        + info);
                            }
                            mDisplayInfo.copyFrom(info);
                            mListener.onDisplayChanged(displayId);
                        }
                    }
                    break;
                case EVENT_DISPLAY_BRIGHTNESS_CHANGED:
                    if ((mEventsMask & DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS) != 0) {
                        mListener.onDisplayChanged(displayId);
                    }
                    break;
                case EVENT_DISPLAY_REMOVED:
                    if ((mEventsMask & DisplayManager.EVENT_FLAG_DISPLAY_REMOVED) != 0) {
                        mListener.onDisplayRemoved(displayId);
                    }
                    break;
                case EVENT_DISPLAY_HDR_SDR_RATIO_CHANGED:
                    if ((mEventsMask & DisplayManager.EVENT_FLAG_HDR_SDR_RATIO_CHANGED) != 0) {
                        mListener.onDisplayChanged(displayId);
                    }
                    break;
                case EVENT_DISPLAY_CONNECTED:
                    if ((mEventsMask & DisplayManager.EVENT_FLAG_DISPLAY_CONNECTION_CHANGED) != 0) {
                        mListener.onDisplayConnected(displayId);
                    }
                    break;
                case EVENT_DISPLAY_DISCONNECTED:
                    if ((mEventsMask & DisplayManager.EVENT_FLAG_DISPLAY_CONNECTION_CHANGED) != 0) {
                        mListener.onDisplayDisconnected(displayId);
                    }
                    break;
            }
            if (DEBUG) {
                Trace.endSection();
            }
        }

        @Override
        public String toString() {
            return "mask: {" + mEventsMask + "}, for " + mListener.getClass();
        }
    }

    /**
     * Assists in dispatching VirtualDisplay lifecycle event callbacks on a given Executor.
     */
    public static final class VirtualDisplayCallback extends IVirtualDisplayCallback.Stub {
        @Nullable private final VirtualDisplay.Callback mCallback;
        @Nullable private final Executor mExecutor;

        /**
         * Creates a virtual display callback.
         *
         * @param callback The callback to call for virtual display events, or {@code null} if the
         * caller does not wish to receive callback events.
         * @param executor The executor to call the {@code callback} on. Must not be {@code null} if
         * the callback is not {@code null}.
         */
        public VirtualDisplayCallback(VirtualDisplay.Callback callback, Executor executor) {
            mCallback = callback;
            mExecutor = mCallback != null ? Objects.requireNonNull(executor) : null;
        }

        // These methods are called from the binder thread, but the AIDL is oneway, so it should be
        // safe to call the callback on arbitrary executors directly without risking blocking
        // the system.

        @Override // Binder call
        public void onPaused() {
            if (mCallback != null) {
                mExecutor.execute(mCallback::onPaused);
            }
        }

        @Override // Binder call
        public void onResumed() {
            if (mCallback != null) {
                mExecutor.execute(mCallback::onResumed);
            }
        }

        @Override // Binder call
        public void onStopped() {
            if (mCallback != null) {
                mExecutor.execute(mCallback::onStopped);
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

    /**
     * Called from AChoreographer via JNI.
     * Registers AChoreographer so that refresh rate callbacks can be dispatched from DMS.
     * Public for unit testing to be able to call this method.
     */
    @VisibleForTesting
    public void registerNativeChoreographerForRefreshRateCallbacks() {
        synchronized (mLock) {
            mDispatchNativeCallbacks = true;
            registerCallbackIfNeededLocked();
            updateCallbackIfNeededLocked();
            DisplayInfo display = getDisplayInfoLocked(Display.DEFAULT_DISPLAY);
            if (display != null) {
                // We need to tell AChoreographer instances the current refresh rate so that apps
                // can get it for free once a callback first registers.
                mNativeCallbackReportedRefreshRate = display.getRefreshRate();
                nSignalNativeCallbacks(mNativeCallbackReportedRefreshRate);
            }
        }
    }

    /**
     * Called from AChoreographer via JNI.
     * Unregisters AChoreographer from receiving refresh rate callbacks.
     * Public for unit testing to be able to call this method.
     */
    @VisibleForTesting
    public void unregisterNativeChoreographerForRefreshRateCallbacks() {
        synchronized (mLock) {
            mDispatchNativeCallbacks = false;
            updateCallbackIfNeededLocked();
        }
    }

    private static String eventToString(@DisplayEvent int event) {
        switch (event) {
            case EVENT_DISPLAY_ADDED:
                return "ADDED";
            case EVENT_DISPLAY_CHANGED:
                return "CHANGED";
            case EVENT_DISPLAY_REMOVED:
                return "REMOVED";
            case EVENT_DISPLAY_BRIGHTNESS_CHANGED:
                return "BRIGHTNESS_CHANGED";
            case EVENT_DISPLAY_HDR_SDR_RATIO_CHANGED:
                return "HDR_SDR_RATIO_CHANGED";
            case EVENT_DISPLAY_CONNECTED:
                return "EVENT_DISPLAY_CONNECTED";
            case EVENT_DISPLAY_DISCONNECTED:
                return "EVENT_DISPLAY_DISCONNECTED";
        }
        return "UNKNOWN";
    }


    private static boolean initExtraLogging() {
        if (sCurrentPackageName == null) {
            sCurrentPackageName = ActivityThread.currentPackageName();
            sExtraDisplayListenerLogging = !TextUtils.isEmpty(EXTRA_LOGGING_PACKAGE_NAME)
                    && EXTRA_LOGGING_PACKAGE_NAME.equals(sCurrentPackageName);
        }
        return sExtraDisplayListenerLogging;
    }

    private static boolean extraLogging() {
        return sExtraDisplayListenerLogging;
    }
}
