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

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;

/**
 * A display adapter that provides virtual displays on behalf of applications.
 * <p>
 * Display adapters are guarded by the {@link DisplayManagerService.SyncRoot} lock.
 * </p>
 */
final class VirtualDisplayAdapter extends DisplayAdapter {
    static final String TAG = "VirtualDisplayAdapter";
    static final boolean DEBUG = false;

    private final ArrayMap<IBinder, VirtualDisplayDevice> mVirtualDisplayDevices =
            new ArrayMap<IBinder, VirtualDisplayDevice>();

    // Called with SyncRoot lock held.
    public VirtualDisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener) {
        super(syncRoot, context, handler, listener, TAG);
    }

    public DisplayDevice createVirtualDisplayLocked(IBinder appToken,
            int ownerUid, String ownerPackageName,
            String name, int width, int height, int densityDpi, Surface surface, int flags) {
        boolean secure = (flags & DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE) != 0;
        IBinder displayToken = SurfaceControl.createDisplay(name, secure);
        VirtualDisplayDevice device = new VirtualDisplayDevice(displayToken, appToken,
                ownerUid, ownerPackageName, name, width, height, densityDpi, surface, flags);

        try {
            appToken.linkToDeath(device, 0);
        } catch (RemoteException ex) {
            device.destroyLocked();
            return null;
        }

        mVirtualDisplayDevices.put(appToken, device);

        // Return the display device without actually sending the event indicating
        // that it was added.  The caller will handle it.
        return device;
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
            device.destroyLocked();
            appToken.unlinkToDeath(device, 0);
        }

        // Return the display device that was removed without actually sending the
        // event indicating that it was removed.  The caller will handle it.
        return device;
    }

    private void handleBinderDiedLocked(IBinder appToken) {
        VirtualDisplayDevice device = mVirtualDisplayDevices.remove(appToken);
        if (device != null) {
            Slog.i(TAG, "Virtual display device released because application token died: "
                    + device.mOwnerPackageName);
            device.destroyLocked();
            sendDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_REMOVED);
        }
    }

    private final class VirtualDisplayDevice extends DisplayDevice
            implements DeathRecipient {
        private final IBinder mAppToken;
        private final int mOwnerUid;
        final String mOwnerPackageName;
        private final String mName;
        private final int mWidth;
        private final int mHeight;
        private final int mDensityDpi;
        private final int mFlags;

        private Surface mSurface;
        private DisplayDeviceInfo mInfo;

        public VirtualDisplayDevice(IBinder displayToken,
                IBinder appToken, int ownerUid, String ownerPackageName,
                String name, int width, int height, int densityDpi, Surface surface, int flags) {
            super(VirtualDisplayAdapter.this, displayToken);
            mAppToken = appToken;
            mOwnerUid = ownerUid;
            mOwnerPackageName = ownerPackageName;
            mName = name;
            mWidth = width;
            mHeight = height;
            mDensityDpi = densityDpi;
            mSurface = surface;
            mFlags = flags;
        }

        @Override
        public void binderDied() {
            synchronized (getSyncRoot()) {
                if (mSurface != null) {
                    handleBinderDiedLocked(mAppToken);
                }
            }
        }

        public void destroyLocked() {
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            SurfaceControl.destroyDisplay(getDisplayTokenLocked());
        }

        @Override
        public void performTraversalInTransactionLocked() {
            setSurfaceInTransactionLocked(mSurface);
        }

        public void setSurfaceLocked(Surface surface) {
            if (mSurface != surface) {
                if ((mSurface != null) != (surface != null)) {
                    sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
                }
                sendTraversalRequestLocked();
                mSurface = surface;
                mInfo = null;
            }
        }

        @Override
        public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
            if (mInfo == null) {
                mInfo = new DisplayDeviceInfo();
                mInfo.name = mName;
                mInfo.width = mWidth;
                mInfo.height = mHeight;
                mInfo.refreshRate = 60;
                mInfo.densityDpi = mDensityDpi;
                mInfo.xDpi = mDensityDpi;
                mInfo.yDpi = mDensityDpi;
                mInfo.flags = 0;
                if ((mFlags & DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC) == 0) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_PRIVATE
                            | DisplayDeviceInfo.FLAG_NEVER_BLANK
                            | DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY;
                } else if ((mFlags & DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY) != 0) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY;
                }
                if ((mFlags & DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE) != 0) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_SECURE;
                }
                if ((mFlags & DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION) != 0) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_PRESENTATION;
                }
                mInfo.type = Display.TYPE_VIRTUAL;
                mInfo.touch = DisplayDeviceInfo.TOUCH_NONE;
                mInfo.state = mSurface != null ? Display.STATE_ON : Display.STATE_OFF;
                mInfo.ownerUid = mOwnerUid;
                mInfo.ownerPackageName = mOwnerPackageName;
            }
            return mInfo;
        }
    }
}
