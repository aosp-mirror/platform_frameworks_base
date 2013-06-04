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

    public DisplayDevice createPrivateVirtualDisplayLocked(IBinder appToken,
            int ownerUid, String ownerPackageName,
            String name, int width, int height, int densityDpi, Surface surface) {
        IBinder displayToken = SurfaceControl.createDisplay(name, false /*secure*/);
        VirtualDisplayDevice device = new VirtualDisplayDevice(displayToken, appToken,
                ownerUid, ownerPackageName, name, width, height, densityDpi, surface);

        try {
            appToken.linkToDeath(device, 0);
        } catch (RemoteException ex) {
            device.releaseLocked();
            return null;
        }

        mVirtualDisplayDevices.put(appToken, device);

        // Return the display device without actually sending the event indicating
        // that it was added.  The caller will handle it.
        return device;
    }

    public DisplayDevice releaseVirtualDisplayLocked(IBinder appToken) {
        VirtualDisplayDevice device = mVirtualDisplayDevices.remove(appToken);
        if (device != null) {
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

        private boolean mReleased;
        private Surface mSurface;
        private DisplayDeviceInfo mInfo;

        public VirtualDisplayDevice(IBinder displayToken,
                IBinder appToken, int ownerUid, String ownerPackageName,
                String name, int width, int height, int densityDpi, Surface surface) {
            super(VirtualDisplayAdapter.this, displayToken);
            mAppToken = appToken;
            mOwnerUid = ownerUid;
            mOwnerPackageName = ownerPackageName;
            mName = name;
            mWidth = width;
            mHeight = height;
            mDensityDpi = densityDpi;
            mSurface = surface;
        }

        @Override
        public void binderDied() {
            synchronized (getSyncRoot()) {
                if (!mReleased) {
                    handleBinderDiedLocked(mAppToken);
                }
            }
        }

        public void releaseLocked() {
            mReleased = true;
            sendTraversalRequestLocked();
        }

        @Override
        public void performTraversalInTransactionLocked() {
            if (mReleased && mSurface != null) {
                mSurface.destroy();
                mSurface = null;
            }
            setSurfaceInTransactionLocked(mSurface);
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
                mInfo.flags = DisplayDeviceInfo.FLAG_PRIVATE | DisplayDeviceInfo.FLAG_NEVER_BLANK;
                mInfo.type = Display.TYPE_VIRTUAL;
                mInfo.touch = DisplayDeviceInfo.TOUCH_NONE;
                mInfo.ownerUid = mOwnerUid;
                mInfo.ownerPackageName = mOwnerPackageName;
            }
            return mInfo;
        }
    }
}
