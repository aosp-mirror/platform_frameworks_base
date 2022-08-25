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
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED;

import static com.android.server.display.DisplayDeviceInfo.FLAG_ALWAYS_UNLOCKED;
import static com.android.server.display.DisplayDeviceInfo.FLAG_OWN_DISPLAY_GROUP;
import static com.android.server.display.DisplayDeviceInfo.FLAG_TOUCH_FEEDBACK_DISABLED;
import static com.android.server.display.DisplayDeviceInfo.FLAG_TRUSTED;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplayConfig;
import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionCallback;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.Iterator;

/**
 * A display adapter that provides virtual displays on behalf of applications.
 * <p>
 * Display adapters are guarded by the {@link DisplayManagerService.SyncRoot} lock.
 * </p>
 */
@VisibleForTesting
public class VirtualDisplayAdapter extends DisplayAdapter {
    static final String TAG = "VirtualDisplayAdapter";
    static final boolean DEBUG = false;

    // Unique id prefix for virtual displays
    @VisibleForTesting
    static final String UNIQUE_ID_PREFIX = "virtual:";

    private final ArrayMap<IBinder, VirtualDisplayDevice> mVirtualDisplayDevices =
            new ArrayMap<IBinder, VirtualDisplayDevice>();
    private final Handler mHandler;
    private final SurfaceControlDisplayFactory mSurfaceControlDisplayFactory;

    // Called with SyncRoot lock held.
    public VirtualDisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener) {
        this(syncRoot, context, handler, listener,
                (String name, boolean secure) -> SurfaceControl.createDisplay(name, secure));
    }

    @VisibleForTesting
    VirtualDisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener,
            SurfaceControlDisplayFactory surfaceControlDisplayFactory) {
        super(syncRoot, context, handler, listener, TAG);
        mHandler = handler;
        mSurfaceControlDisplayFactory = surfaceControlDisplayFactory;
    }

    public DisplayDevice createVirtualDisplayLocked(IVirtualDisplayCallback callback,
            IMediaProjection projection, int ownerUid, String ownerPackageName, Surface surface,
            int flags, VirtualDisplayConfig virtualDisplayConfig) {
        String name = virtualDisplayConfig.getName();
        boolean secure = (flags & VIRTUAL_DISPLAY_FLAG_SECURE) != 0;
        IBinder appToken = callback.asBinder();
        IBinder displayToken = mSurfaceControlDisplayFactory.createDisplay(name, secure);
        final String baseUniqueId =
                UNIQUE_ID_PREFIX + ownerPackageName + "," + ownerUid + "," + name + ",";
        final int uniqueIndex = getNextUniqueIndex(baseUniqueId);
        String uniqueId = virtualDisplayConfig.getUniqueId();
        if (uniqueId == null) {
            uniqueId = baseUniqueId + uniqueIndex;
        } else {
            uniqueId = UNIQUE_ID_PREFIX + ownerPackageName + ":" + uniqueId;
        }
        VirtualDisplayDevice device = new VirtualDisplayDevice(displayToken, appToken,
                ownerUid, ownerPackageName, surface, flags, new Callback(callback, mHandler),
                uniqueId, uniqueIndex, virtualDisplayConfig);

        mVirtualDisplayDevices.put(appToken, device);

        try {
            if (projection != null) {
                projection.registerCallback(new MediaProjectionCallback(appToken));
            }
            appToken.linkToDeath(device, 0);
        } catch (RemoteException ex) {
            mVirtualDisplayDevices.remove(appToken);
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
            device.setSurfaceLocked(surface);
        }
    }

    public DisplayDevice releaseVirtualDisplayLocked(IBinder appToken) {
        VirtualDisplayDevice device = mVirtualDisplayDevices.remove(appToken);
        if (device != null) {
            device.destroyLocked(true);
            appToken.unlinkToDeath(device, 0);
        }

        // Return the display device that was removed without actually sending the
        // event indicating that it was removed.  The caller will handle it.
        return device;
    }

    void setVirtualDisplayStateLocked(IBinder appToken, boolean isOn) {
        VirtualDisplayDevice device = mVirtualDisplayDevices.get(appToken);
        if (device != null) {
            device.setDisplayState(isOn);
        }
    }

    /**
     * Returns the next unique index for the uniqueIdPrefix
     */
    private int getNextUniqueIndex(String uniqueIdPrefix) {
        if (mVirtualDisplayDevices.isEmpty()) {
            return 0;
        }

        int nextUniqueIndex = 0;
        Iterator<VirtualDisplayDevice> it = mVirtualDisplayDevices.values().iterator();
        while (it.hasNext()) {
            VirtualDisplayDevice device = it.next();
            if (device.getUniqueId().startsWith(uniqueIdPrefix)
                    && device.mUniqueIndex >= nextUniqueIndex) {
                // Increment the next unique index to be greater than ones we have already ran
                // across for displays that have the same unique Id prefix.
                nextUniqueIndex = device.mUniqueIndex + 1;
            }
        }

        return nextUniqueIndex;
    }

    private void handleBinderDiedLocked(IBinder appToken) {
        mVirtualDisplayDevices.remove(appToken);
    }

    private void handleMediaProjectionStoppedLocked(IBinder appToken) {
        VirtualDisplayDevice device = mVirtualDisplayDevices.get(appToken);
        if (device != null) {
            Slog.i(TAG, "Virtual display device released because media projection stopped: "
                    + device.mName);
            device.stopLocked();
        }
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

        private int mWidth;
        private int mHeight;
        private int mDensityDpi;
        private Surface mSurface;
        private DisplayDeviceInfo mInfo;
        private int mDisplayState;
        private boolean mStopped;
        private int mPendingChanges;
        private int mUniqueIndex;
        private Display.Mode mMode;
        private boolean mIsDisplayOn;
        private int mDisplayIdToMirror;
        private boolean mIsWindowManagerMirroring;

        public VirtualDisplayDevice(IBinder displayToken, IBinder appToken,
                int ownerUid, String ownerPackageName, Surface surface, int flags,
                Callback callback, String uniqueId, int uniqueIndex,
                VirtualDisplayConfig virtualDisplayConfig) {
            super(VirtualDisplayAdapter.this, displayToken, uniqueId, getContext());
            mAppToken = appToken;
            mOwnerUid = ownerUid;
            mOwnerPackageName = ownerPackageName;
            mName = virtualDisplayConfig.getName();
            mWidth = virtualDisplayConfig.getWidth();
            mHeight = virtualDisplayConfig.getHeight();
            mMode = createMode(mWidth, mHeight, REFRESH_RATE);
            mDensityDpi = virtualDisplayConfig.getDensityDpi();
            mSurface = surface;
            mFlags = flags;
            mCallback = callback;
            mDisplayState = Display.STATE_UNKNOWN;
            mPendingChanges |= PENDING_SURFACE_CHANGE;
            mUniqueIndex = uniqueIndex;
            mIsDisplayOn = surface != null;
            mDisplayIdToMirror = virtualDisplayConfig.getDisplayIdToMirror();
            mIsWindowManagerMirroring = virtualDisplayConfig.isWindowManagerMirroring();
        }

        @Override
        public void binderDied() {
            synchronized (getSyncRoot()) {
                handleBinderDiedLocked(mAppToken);
                Slog.i(TAG, "Virtual display device released because application token died: "
                    + mOwnerPackageName);
                destroyLocked(false);
                sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_REMOVED);
            }
        }

        public void destroyLocked(boolean binderAlive) {
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            SurfaceControl.destroyDisplay(getDisplayTokenLocked());
            if (binderAlive) {
                mCallback.dispatchDisplayStopped();
            }
        }

        @Override
        public int getDisplayIdToMirrorLocked() {
            return mDisplayIdToMirror;
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
            return mSurface.getDefaultSize();
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
                float sdrBrightnessState) {
            if (state != mDisplayState) {
                mDisplayState = state;
                if (state == Display.STATE_OFF) {
                    mCallback.dispatchDisplayPaused();
                } else {
                    mCallback.dispatchDisplayResumed();
                }
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
                if ((mSurface != null) != (surface != null)) {
                    sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
                }
                sendTraversalRequestLocked();
                mSurface = surface;
                mInfo = null;
                mPendingChanges |= PENDING_SURFACE_CHANGE;
            }
        }

        public void resizeLocked(int width, int height, int densityDpi) {
            if (mWidth != width || mHeight != height || mDensityDpi != densityDpi) {
                sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
                sendTraversalRequestLocked();
                mWidth = width;
                mHeight = height;
                mMode = createMode(width, height, REFRESH_RATE);
                mDensityDpi = densityDpi;
                mInfo = null;
                mPendingChanges |= PENDING_RESIZE;
            }
        }

        void setDisplayState(boolean isOn) {
            if (mIsDisplayOn != isOn) {
                mIsDisplayOn = isOn;
                mInfo = null;
                sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
            }
        }

        public void stopLocked() {
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
                mInfo.defaultModeId = mMode.getModeId();
                mInfo.supportedModes = new Display.Mode[] { mMode };
                mInfo.densityDpi = mDensityDpi;
                mInfo.xDpi = mDensityDpi;
                mInfo.yDpi = mDensityDpi;
                mInfo.presentationDeadlineNanos = 1000000000L / (int) REFRESH_RATE; // 1 frame
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
                if ((mFlags & VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED) != 0
                        && (mInfo.flags & DisplayDeviceInfo.FLAG_OWN_DISPLAY_GROUP) != 0) {
                    mInfo.flags |= FLAG_ALWAYS_UNLOCKED;
                }
                if ((mFlags & VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED) != 0) {
                    mInfo.flags |= FLAG_TOUCH_FEEDBACK_DISABLED;
                }

                mInfo.type = Display.TYPE_VIRTUAL;
                mInfo.touch = ((mFlags & VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH) == 0) ?
                        DisplayDeviceInfo.TOUCH_NONE : DisplayDeviceInfo.TOUCH_VIRTUAL;

                mInfo.state = mIsDisplayOn ? Display.STATE_ON : Display.STATE_OFF;

                mInfo.ownerUid = mOwnerUid;
                mInfo.ownerPackageName = mOwnerPackageName;
            }
            return mInfo;
        }
    }

    private static class Callback extends Handler {
        private static final int MSG_ON_DISPLAY_PAUSED = 0;
        private static final int MSG_ON_DISPLAY_RESUMED = 1;
        private static final int MSG_ON_DISPLAY_STOPPED = 2;

        private final IVirtualDisplayCallback mCallback;

        public Callback(IVirtualDisplayCallback callback, Handler handler) {
            super(handler.getLooper());
            mCallback = callback;
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
    }

    @VisibleForTesting
    public interface SurfaceControlDisplayFactory {
        public IBinder createDisplay(String name, boolean secure);
    }
}
