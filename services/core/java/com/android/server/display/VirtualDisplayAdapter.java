/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.display;

import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_FOCUS;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED;

import static com.android.server.display.DisplayDeviceInfo.FLAG_ALWAYS_UNLOCKED;
import static com.android.server.display.DisplayDeviceInfo.FLAG_DEVICE_DISPLAY_GROUP;
import static com.android.server.display.DisplayDeviceInfo.FLAG_OWN_DISPLAY_GROUP;
import static com.android.server.display.DisplayDeviceInfo.FLAG_STEAL_TOP_FOCUS_DISABLED;
import static com.android.server.display.DisplayDeviceInfo.FLAG_TOUCH_FEEDBACK_DISABLED;
import static com.android.server.display.DisplayDeviceInfo.FLAG_TRUSTED;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Point;
import android.hardware.display.IBrightnessListener;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplayConfig;
import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionCallback;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayShape;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.brightness.BrightnessUtils;
import com.android.server.display.feature.DisplayManagerFlags;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A display adapter that provides virtual displays on behalf of applications.
 * <p>
 * Display adapters are guarded by the {@link DisplayManagerService.SyncRoot} lock.
 * </p>
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class VirtualDisplayAdapter extends DisplayAdapter {
    static final String TAG = "VirtualDisplayAdapter";

    // Unique id prefix for virtual displays
    @VisibleForTesting
    static final String UNIQUE_ID_PREFIX = "virtual:";

    // Unique id suffix for virtual displays
    private static final AtomicInteger sNextUniqueIndex = new AtomicInteger(0);

    private final ArrayMap<IBinder, VirtualDisplayDevice> mVirtualDisplayDevices = new ArrayMap<>();

    private final int mMaxDevices;
    private final int mMaxDevicesPerPackage;
    private final SparseIntArray mNoOfDevicesPerPackage = new SparseIntArray();

    private final Handler mHandler;
    private final SurfaceControlDisplayFactory mSurfaceControlDisplayFactory;

    // Called with SyncRoot lock held.
    public VirtualDisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener, DisplayManagerFlags featureFlags) {
        this(syncRoot, context, handler, listener, new SurfaceControlDisplayFactory() {
            @Override
            public IBinder createDisplay(String name, boolean secure, String uniqueId,
                                         float requestedRefreshRate) {
                return DisplayControl.createVirtualDisplay(name, secure, uniqueId,
                                                           requestedRefreshRate);
            }

            @Override
            public void destroyDisplay(IBinder displayToken) {
                DisplayControl.destroyVirtualDisplay(displayToken);
            }
        }, featureFlags);
    }

    @VisibleForTesting
    VirtualDisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener,
            SurfaceControlDisplayFactory surfaceControlDisplayFactory,
            DisplayManagerFlags featureFlags) {
        super(syncRoot, context, handler, listener, TAG, featureFlags);
        mHandler = handler;
        mSurfaceControlDisplayFactory = surfaceControlDisplayFactory;

        mMaxDevices = context.getResources().getInteger(R.integer.config_virtualDisplayLimit);
        if (mMaxDevices < 1) {
            throw new IllegalArgumentException("The limit of virtual displays must be >= 1");
        }
        mMaxDevicesPerPackage =
                context.getResources().getInteger(R.integer.config_virtualDisplayLimitPerPackage);
        if (mMaxDevicesPerPackage < 1) {
            throw new IllegalArgumentException(
                    "The limit of virtual displays per package must be >= 1");
        }
    }

    /**
     * Create a virtual display
     * @param callback The callback
     * @param projection The media projection
     * @param ownerUid The UID of the package creating a display
     * @param ownerPackageName The name of the package creating a display
     * @param uniqueId The unique ID of the display device
     * @param surface The surface
     * @param flags The flags
     * @param virtualDisplayConfig The config
     * @return The display device created
     */
    public DisplayDevice createVirtualDisplayLocked(IVirtualDisplayCallback callback,
            IMediaProjection projection, int ownerUid, String ownerPackageName, String uniqueId,
            Surface surface, int flags, VirtualDisplayConfig virtualDisplayConfig) {
        IBinder appToken = callback.asBinder();
        if (mVirtualDisplayDevices.containsKey(appToken)) {
            Slog.wtfStack(TAG,
                    "Can't create virtual display, display with same appToken already exists");
            return null;
        }

        if (getFeatureFlags().isVirtualDisplayLimitEnabled()
                && mVirtualDisplayDevices.size() >= mMaxDevices) {
            Slog.w(TAG, "Rejecting request to create private virtual display because "
                    + mMaxDevices + " devices already exist.");
            return null;
        }

        int noOfDevices = mNoOfDevicesPerPackage.get(ownerUid, /* valueIfKeyNotFound= */ 0);
        if (getFeatureFlags().isVirtualDisplayLimitEnabled()
                && noOfDevices >= mMaxDevicesPerPackage) {
            Slog.w(TAG, "Rejecting request to create private virtual display because "
                    + mMaxDevicesPerPackage + " devices already exist for package "
                    + ownerPackageName + ".");
            return null;
        }

        String name = virtualDisplayConfig.getName();
        boolean secure = (flags & VIRTUAL_DISPLAY_FLAG_SECURE) != 0;

        IBinder displayToken = mSurfaceControlDisplayFactory.createDisplay(name, secure, uniqueId,
                virtualDisplayConfig.getRequestedRefreshRate());
        MediaProjectionCallback mediaProjectionCallback =  null;
        if (projection != null) {
            mediaProjectionCallback = new MediaProjectionCallback(appToken);
        }

        Callback callbackDelegate = new Callback(
                callback, virtualDisplayConfig.getBrightnessListener(), mHandler);
        VirtualDisplayDevice device = new VirtualDisplayDevice(displayToken, appToken,
                ownerUid, ownerPackageName, surface, flags, callbackDelegate,
                projection, mediaProjectionCallback, uniqueId, virtualDisplayConfig);

        mVirtualDisplayDevices.put(appToken, device);
        if (getFeatureFlags().isVirtualDisplayLimitEnabled()) {
            mNoOfDevicesPerPackage.put(ownerUid, noOfDevices + 1);
        }

        try {
            if (projection != null) {
                projection.registerCallback(mediaProjectionCallback);
                Slog.d(TAG, "Virtual Display: registered media projection callback for new "
                        + "VirtualDisplayDevice");
            }
            appToken.linkToDeath(device, 0);
        } catch (RemoteException ex) {
            Slog.e(TAG, "Virtual Display: error while setting up VirtualDisplayDevice", ex);
            removeVirtualDisplayDeviceLocked(appToken, ownerUid);
            device.destroyLocked(false);
            return null;
        }

        // Return the display device without actually sending the event indicating
        // that it was added.  The caller will handle it.
        return device;
    }

    public void resizeVirtualDisplayLocked(IBinder appToken,
            int width, int height, int densityDpi) {
        VirtualDisplayDevice device = mVirtualDisplayDevices.get(appToken);
        if (device != null) {
            Slog.v(TAG, "Resize VirtualDisplay " + device.mName + " to " + width
                    + " " + height);
            device.resizeLocked(width, height, densityDpi);
        }
    }

    @VisibleForTesting
    Surface getVirtualDisplaySurfaceLocked(IBinder appToken) {
        VirtualDisplayDevice device = mVirtualDisplayDevices.get(appToken);
        if (device != null) {
            return device.getSurfaceLocked();
        }
        return null;
    }

    public void setVirtualDisplaySurfaceLocked(IBinder appToken, Surface surface) {
        VirtualDisplayDevice device = mVirtualDisplayDevices.get(appToken);
        if (device != null) {
            Slog.v(TAG, "Update surface for VirtualDisplay " + device.mName);
            device.setSurfaceLocked(surface);
        }
    }

    void setDisplayIdToMirror(IBinder appToken, int displayId) {
        VirtualDisplayDevice device = mVirtualDisplayDevices.get(appToken);
        if (device != null) {
            device.setDisplayIdToMirror(displayId);
        }
    }

    /**
     * Release a virtual display that was previously created
     * @param appToken The token to identify the display
     * @param ownerUid The UID of the package, used to keep track of and limit the number of
     *                 displays created per package
     * @return The display device that has been removed
     */
    public DisplayDevice releaseVirtualDisplayLocked(IBinder appToken, int ownerUid) {
        VirtualDisplayDevice device = removeVirtualDisplayDeviceLocked(appToken, ownerUid);
        if (device != null) {
            Slog.v(TAG, "Release VirtualDisplay " + device.mName);
            device.destroyLocked(true);
            appToken.unlinkToDeath(device, 0);
        }

        // Return the display device that was removed without actually sending the
        // event indicating that it was removed.  The caller will handle it.
        return device;
    }

    DisplayDevice getDisplayDevice(IBinder appToken) {
        return mVirtualDisplayDevices.get(appToken);
    }

    /**
     * Generates a virtual display's unique identifier.
     *
     * <p>It is always prefixed with "virtual:package-name". If the provided config explicitly
     * specifies a unique ID, then it's simply appended. Otherwise, the UID, display name and a
     * unique index are appended.</p>
     *
     * <p>The unique index is incremented for every virtual display unique ID generation and serves
     * for differentiating between displays with the same name created by the same owner.</p>
     */
    static String generateDisplayUniqueId(String packageName, int uid,
            VirtualDisplayConfig config) {
        return UNIQUE_ID_PREFIX + packageName + ((config.getUniqueId() != null)
                ? (":" + config.getUniqueId())
                : ("," + uid + "," + config.getName() + "," + sNextUniqueIndex.getAndIncrement()));
    }

    private void handleMediaProjectionStoppedLocked(IBinder appToken) {
        VirtualDisplayDevice device = mVirtualDisplayDevices.get(appToken);
        if (device != null) {
            Slog.i(TAG, "Virtual display device released because media projection stopped: "
                    + device.mName);
            device.stopLocked();
        }
    }

    private VirtualDisplayDevice removeVirtualDisplayDeviceLocked(IBinder appToken, int ownerUid) {
        int noOfDevices = mNoOfDevicesPerPackage.get(ownerUid, /* valueIfKeyNotFound= */ 0);
        if (getFeatureFlags().isVirtualDisplayLimitEnabled()) {
            if (noOfDevices <= 1) {
                mNoOfDevicesPerPackage.delete(ownerUid);
            } else {
                mNoOfDevicesPerPackage.put(ownerUid, noOfDevices - 1);
            }
        }
        return mVirtualDisplayDevices.remove(appToken);
    }

    private final class VirtualDisplayDevice extends DisplayDevice implements DeathRecipient {
        private static final int PENDING_SURFACE_CHANGE = 0x01;
        private static final int PENDING_RESIZE = 0x02;

        private static final float REFRESH_RATE = 60.0f;

        private final IBinder mAppToken;
        private final int mOwnerUid;
        final String mOwnerPackageName;
        final String mName;
        private final int mFlags;
        private final Callback mCallback;
        @Nullable private final IMediaProjection mProjection;
        @Nullable private final IMediaProjectionCallback mMediaProjectionCallback;

        private int mWidth;
        private int mHeight;
        private int mDensityDpi;
        private final float mRequestedRefreshRate;
        private Surface mSurface;
        private DisplayDeviceInfo mInfo;
        private int mDisplayState;
        private boolean mStopped;
        private int mPendingChanges;
        private Display.Mode mMode;
        private int mDisplayIdToMirror;
        private boolean mIsWindowManagerMirroring;
        private final DisplayCutout mDisplayCutout;
        private final float mDefaultBrightness;
        private final float mDimBrightness;
        private float mCurrentBrightness;
        private final IBrightnessListener mBrightnessListener;

        public VirtualDisplayDevice(IBinder displayToken, IBinder appToken,
                int ownerUid, String ownerPackageName, Surface surface, int flags,
                Callback callback, IMediaProjection projection,
                IMediaProjectionCallback mediaProjectionCallback, String uniqueId,
                VirtualDisplayConfig virtualDisplayConfig) {
            super(VirtualDisplayAdapter.this, displayToken, uniqueId, getContext());
            mAppToken = appToken;
            mOwnerUid = ownerUid;
            mOwnerPackageName = ownerPackageName;
            mName = virtualDisplayConfig.getName();
            mWidth = virtualDisplayConfig.getWidth();
            mHeight = virtualDisplayConfig.getHeight();
            mDensityDpi = virtualDisplayConfig.getDensityDpi();
            mRequestedRefreshRate = virtualDisplayConfig.getRequestedRefreshRate();
            mDisplayCutout = virtualDisplayConfig.getDisplayCutout();
            mDefaultBrightness = virtualDisplayConfig.getDefaultBrightness();
            mDimBrightness = virtualDisplayConfig.getDimBrightness();
            mCurrentBrightness = PowerManager.BRIGHTNESS_INVALID;
            mBrightnessListener = virtualDisplayConfig.getBrightnessListener();
            mMode = createMode(mWidth, mHeight, getRefreshRate());
            mSurface = surface;
            mFlags = flags;
            mCallback = callback;
            mProjection = projection;
            mMediaProjectionCallback = mediaProjectionCallback;
            mDisplayState = Display.STATE_ON;
            mPendingChanges |= PENDING_SURFACE_CHANGE;
            mDisplayIdToMirror = virtualDisplayConfig.getDisplayIdToMirror();
            mIsWindowManagerMirroring = virtualDisplayConfig.isWindowManagerMirroringEnabled();
        }

        @Override
        public void binderDied() {
            synchronized (getSyncRoot()) {
                removeVirtualDisplayDeviceLocked(mAppToken, mOwnerUid);
                Slog.i(TAG, "Virtual display device released because application token died: "
                    + mOwnerPackageName);
                destroyLocked(false);
                if (mProjection != null && mMediaProjectionCallback != null) {
                    try {
                        mProjection.unregisterCallback(mMediaProjectionCallback);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to unregister callback in binderDied", e);
                    }
                }
                sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_REMOVED);
            }
        }

        public void destroyLocked(boolean binderAlive) {
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            mSurfaceControlDisplayFactory.destroyDisplay(getDisplayTokenLocked());
            if (mProjection != null && mMediaProjectionCallback != null) {
                try {
                    mProjection.unregisterCallback(mMediaProjectionCallback);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to unregister callback in destroy", e);
                }
            }
            if (binderAlive) {
                mCallback.dispatchDisplayStopped();
            }
        }

        @Override
        public int getDisplayIdToMirrorLocked() {
            return mDisplayIdToMirror;
        }

        void setDisplayIdToMirror(int displayIdToMirror) {
            if (mDisplayIdToMirror != displayIdToMirror) {
                mDisplayIdToMirror = displayIdToMirror;
                mInfo = null;
                sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
                sendTraversalRequestLocked();
            }
        }

        @Override
        public boolean isWindowManagerMirroringLocked() {
            return mIsWindowManagerMirroring;
        }

        @Override
        public void setWindowManagerMirroringLocked(boolean mirroring) {
            if (mIsWindowManagerMirroring != mirroring) {
                mIsWindowManagerMirroring = mirroring;
                sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
                sendTraversalRequestLocked();
            }
        }

        @Override
        public Point getDisplaySurfaceDefaultSizeLocked() {
            if (mSurface == null) {
                return null;
            }
            final Point surfaceSize = mSurface.getDefaultSize();
            return isRotatedLocked() ? new Point(surfaceSize.y, surfaceSize.x) : surfaceSize;
        }

        @VisibleForTesting
        Surface getSurfaceLocked() {
            return mSurface;
        }

        @Override
        public boolean hasStableUniqueId() {
            return false;
        }

        @Override
        public Runnable requestDisplayStateLocked(int state, float brightnessState,
                float sdrBrightnessState, DisplayOffloadSessionImpl displayOffloadSession) {
            if (state != mDisplayState) {
                mDisplayState = state;
                mInfo = null;
                sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
                if (state == Display.STATE_OFF) {
                    mCallback.dispatchDisplayPaused();
                } else {
                    mCallback.dispatchDisplayResumed();
                }
            }
            if (android.companion.virtualdevice.flags.Flags.deviceAwareDisplayPower()
                    && mBrightnessListener != null
                    && BrightnessUtils.isValidBrightnessValue(brightnessState)
                    && brightnessState != mCurrentBrightness) {
                mCurrentBrightness = brightnessState;
                mCallback.dispatchRequestedBrightnessChanged(mCurrentBrightness);
            }
            return null;
        }

        @Override
        public void performTraversalLocked(SurfaceControl.Transaction t) {
            if ((mPendingChanges & PENDING_RESIZE) != 0) {
                t.setDisplaySize(getDisplayTokenLocked(), mWidth, mHeight);
            }
            if ((mPendingChanges & PENDING_SURFACE_CHANGE) != 0) {
                setSurfaceLocked(t, mSurface);
            }
            mPendingChanges = 0;
        }

        public void setSurfaceLocked(Surface surface) {
            if (!mStopped && mSurface != surface) {
                if (mDisplayState == Display.STATE_ON
                        && ((mSurface == null) != (surface == null))) {
                    mInfo = null;
                    sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
                }
                sendTraversalRequestLocked();
                mSurface = surface;
                mPendingChanges |= PENDING_SURFACE_CHANGE;
            }
        }

        public void resizeLocked(int width, int height, int densityDpi) {
            if (mWidth != width || mHeight != height || mDensityDpi != densityDpi) {
                sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
                sendTraversalRequestLocked();
                mWidth = width;
                mHeight = height;
                mMode = createMode(width, height, getRefreshRate());
                mDensityDpi = densityDpi;
                mInfo = null;
                mPendingChanges |= PENDING_RESIZE;
            }
        }

        public void stopLocked() {
            Slog.d(TAG, "Virtual Display: stopping device " + mName);
            setSurfaceLocked(null);
            mStopped = true;
        }

        @Override
        public void dumpLocked(PrintWriter pw) {
            super.dumpLocked(pw);
            pw.println("mFlags=" + mFlags);
            pw.println("mDisplayState=" + Display.stateToString(mDisplayState));
            pw.println("mStopped=" + mStopped);
            pw.println("mDisplayIdToMirror=" + mDisplayIdToMirror);
            pw.println("mWindowManagerMirroring=" + mIsWindowManagerMirroring);
            pw.println("mRequestedRefreshRate=" + mRequestedRefreshRate);
        }

        @Override
        public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
            if (mInfo == null) {
                mInfo = new DisplayDeviceInfo();
                mInfo.name = mName;
                mInfo.uniqueId = getUniqueId();
                mInfo.width = mWidth;
                mInfo.height = mHeight;
                mInfo.modeId = mMode.getModeId();
                mInfo.renderFrameRate = mMode.getRefreshRate();
                mInfo.defaultModeId = mMode.getModeId();
                mInfo.supportedModes = new Display.Mode[] { mMode };
                mInfo.densityDpi = mDensityDpi;
                mInfo.xDpi = mDensityDpi;
                mInfo.yDpi = mDensityDpi;
                mInfo.presentationDeadlineNanos = 1000000000L / (int) getRefreshRate(); // 1 frame
                mInfo.flags = 0;
                if ((mFlags & VIRTUAL_DISPLAY_FLAG_PUBLIC) == 0) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_PRIVATE
                            | DisplayDeviceInfo.FLAG_NEVER_BLANK;
                }
                if ((mFlags & VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) != 0) {
                    mInfo.flags &= ~DisplayDeviceInfo.FLAG_NEVER_BLANK;
                } else {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY;

                    if ((mFlags & VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP) != 0) {
                        mInfo.flags |= FLAG_OWN_DISPLAY_GROUP;
                    }
                }
                if ((mFlags & VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP) != 0) {
                    mInfo.flags |= FLAG_DEVICE_DISPLAY_GROUP;
                }

                if ((mFlags & VIRTUAL_DISPLAY_FLAG_SECURE) != 0) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_SECURE;
                }
                if ((mFlags & VIRTUAL_DISPLAY_FLAG_PRESENTATION) != 0) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_PRESENTATION;

                    if ((mFlags & VIRTUAL_DISPLAY_FLAG_PUBLIC) != 0) {
                        // For demonstration purposes, allow rotation of the external display.
                        // In the future we might allow the user to configure this directly.
                        if ("portrait".equals(SystemProperties.get(
                                "persist.demo.remoterotation"))) {
                            mInfo.rotation = Surface.ROTATION_270;
                        }
                    }
                }
                if ((mFlags & VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD) != 0) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD;
                }
                if ((mFlags & VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT) != 0) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT;
                }
                if ((mFlags & VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL) != 0) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_DESTROY_CONTENT_ON_REMOVAL;
                }
                if ((mFlags & VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS) != 0) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS;
                }
                if ((mFlags & VIRTUAL_DISPLAY_FLAG_TRUSTED) != 0) {
                    mInfo.flags |= FLAG_TRUSTED;
                }
                if ((mFlags & VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED) != 0) {
                    if ((mInfo.flags & DisplayDeviceInfo.FLAG_OWN_DISPLAY_GROUP) != 0
                            || (mFlags & VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP) != 0) {
                        mInfo.flags |= FLAG_ALWAYS_UNLOCKED;
                    } else {
                        Slog.w(
                                TAG,
                                "Ignoring VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED as it requires"
                                    + " VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP or"
                                    + " VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP.");
                    }
                }
                if ((mFlags & VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED) != 0) {
                    mInfo.flags |= FLAG_TOUCH_FEEDBACK_DISABLED;
                }
                if ((mFlags & VIRTUAL_DISPLAY_FLAG_OWN_FOCUS) != 0) {
                    if ((mFlags & VIRTUAL_DISPLAY_FLAG_TRUSTED) != 0) {
                        mInfo.flags |= DisplayDeviceInfo.FLAG_OWN_FOCUS;
                    } else {
                        Slog.w(TAG, "Ignoring VIRTUAL_DISPLAY_FLAG_OWN_FOCUS as it requires "
                                + "VIRTUAL_DISPLAY_FLAG_TRUSTED.");
                    }
                }
                if ((mFlags & VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED) != 0) {
                    if ((mFlags & VIRTUAL_DISPLAY_FLAG_TRUSTED) != 0
                            && (mFlags & VIRTUAL_DISPLAY_FLAG_OWN_FOCUS) != 0) {
                        mInfo.flags |= FLAG_STEAL_TOP_FOCUS_DISABLED;
                    } else {
                        Slog.w(TAG,
                                "Ignoring VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED as it "
                                        + "requires VIRTUAL_DISPLAY_FLAG_OWN_FOCUS which requires "
                                        + "VIRTUAL_DISPLAY_FLAG_TRUSTED.");
                    }
                }

                mInfo.type = Display.TYPE_VIRTUAL;
                mInfo.touch = ((mFlags & VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH) == 0) ?
                        DisplayDeviceInfo.TOUCH_NONE : DisplayDeviceInfo.TOUCH_VIRTUAL;

                if (mSurface == null) {
                    mInfo.state = Display.STATE_OFF;
                } else {
                    mInfo.state = mDisplayState;
                }

                mInfo.brightnessMinimum = PowerManager.BRIGHTNESS_MIN;
                mInfo.brightnessMaximum = PowerManager.BRIGHTNESS_MAX;
                mInfo.brightnessDefault = mDefaultBrightness;
                mInfo.brightnessDim = mDimBrightness;

                mInfo.ownerUid = mOwnerUid;
                mInfo.ownerPackageName = mOwnerPackageName;

                mInfo.displayShape =
                        DisplayShape.createDefaultDisplayShape(mInfo.width, mInfo.height, false);
                mInfo.displayCutout = mDisplayCutout;
            }
            return mInfo;
        }

        private float getRefreshRate() {
            return (mRequestedRefreshRate != 0.0f) ? mRequestedRefreshRate : REFRESH_RATE;
        }
    }

    private static class Callback extends Handler {
        private static final int MSG_ON_DISPLAY_PAUSED = 0;
        private static final int MSG_ON_DISPLAY_RESUMED = 1;
        private static final int MSG_ON_DISPLAY_STOPPED = 2;
        private static final int MSG_ON_REQUESTED_BRIGHTNESS_CHANGED = 3;

        private final IVirtualDisplayCallback mCallback;
        private final IBrightnessListener mBrightnessListener;

        Callback(IVirtualDisplayCallback callback, IBrightnessListener brightnessListener,
                Handler handler) {
            super(handler.getLooper());
            mCallback = callback;
            mBrightnessListener = brightnessListener;
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                switch (msg.what) {
                    case MSG_ON_DISPLAY_PAUSED:
                        mCallback.onPaused();
                        break;
                    case MSG_ON_DISPLAY_RESUMED:
                        mCallback.onResumed();
                        break;
                    case MSG_ON_DISPLAY_STOPPED:
                        mCallback.onStopped();
                        break;
                    case MSG_ON_REQUESTED_BRIGHTNESS_CHANGED:
                        if (mBrightnessListener != null) {
                            mBrightnessListener.onBrightnessChanged((Float) msg.obj);
                        }
                        break;
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify listener of virtual display event.", e);
            }
        }

        public void dispatchDisplayPaused() {
            sendEmptyMessage(MSG_ON_DISPLAY_PAUSED);
        }

        public void dispatchDisplayResumed() {
            sendEmptyMessage(MSG_ON_DISPLAY_RESUMED);
        }

        public void dispatchRequestedBrightnessChanged(float brightness) {
            Message msg = obtainMessage(MSG_ON_REQUESTED_BRIGHTNESS_CHANGED, brightness);
            sendMessage(msg);
        }

        public void dispatchDisplayStopped() {
            sendEmptyMessage(MSG_ON_DISPLAY_STOPPED);
        }
    }

    private final class MediaProjectionCallback extends IMediaProjectionCallback.Stub {
        private IBinder mAppToken;
        public MediaProjectionCallback(IBinder appToken) {
            mAppToken = appToken;
        }

        @Override
        public void onStop() {
            synchronized (getSyncRoot()) {
                handleMediaProjectionStoppedLocked(mAppToken);
            }
        }

        @Override
        public void onCapturedContentResize(int width, int height) {
            // Do nothing when we tell the client that the content is resized - it is up to them
            // to decide to update the VirtualDisplay and Surface.
            // We could only update the VirtualDisplay size, anyway (which the client wouldn't
            // expect), and there will still be letterboxing on the output content since the
            // Surface and VirtualDisplay would then have different aspect ratios.
        }

        @Override
        public void onCapturedContentVisibilityChanged(boolean isVisible) {
            // Do nothing when we tell the client that the content has a visibility change - it is
            // up to them to decide to pause recording, and update their own UI, depending on their
            // use case.
        }
    }

    @VisibleForTesting
    public interface SurfaceControlDisplayFactory {
        /**
         * Create a virtual display in SurfaceFlinger.
         *
         * @param name The name of the display.
         * @param secure Whether this display is secure.
         * @param uniqueId The unique ID for the display.
         * @param requestedRefreshRate
         *     The refresh rate, frames per second, to request on the virtual display.
         *     It should be a divisor of refresh rate of the leader physical display
         *     that drives VSYNC, e.g. 30/60fps on 120fps display. If an arbitrary refresh
         *     rate is specified, SurfaceFlinger rounds up or down to match a divisor of
         *     the refresh rate of the leader physical display.
         * @return The token reference for the display in SurfaceFlinger.
         */
        IBinder createDisplay(String name, boolean secure, String uniqueId,
                              float requestedRefreshRate);

        /**
         * Destroy a display in SurfaceFlinger.
         *
         * @param displayToken The display token for the display to be destroyed.
         */
        void destroyDisplay(IBinder displayToken);
    }
}
