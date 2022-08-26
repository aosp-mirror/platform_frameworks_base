/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.media.projection;

import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.IDisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.Surface;

/**
 * This is a helper for MediaProjection when requests are made from outside an application. This
 * should only be used by processes running as shell as a way to capture recordings without being
 * an application. The requests will fail if coming from any process that's not Shell.
 * @hide
 */
@SystemApi
public class MediaProjectionGlobal {
    private static final Object sLock = new Object();
    private static MediaProjectionGlobal sInstance;

    /**
     * @return The instance of {@link MediaProjectionGlobal}
     */
    @NonNull
    public static MediaProjectionGlobal getInstance() {
        synchronized (sLock) {
            if (sInstance == null) {
                final IBinder displayBinder = ServiceManager.getService(Context.DISPLAY_SERVICE);
                final IBinder packageBinder = ServiceManager.getService("package");
                if (displayBinder != null && packageBinder != null) {
                    sInstance = new MediaProjectionGlobal(
                                    IDisplayManager.Stub.asInterface(displayBinder),
                                    IPackageManager.Stub.asInterface(packageBinder));
                }
            }
            return sInstance;
        }
    }

    private final IDisplayManager mDm;
    private final IPackageManager mPackageManager;

    private MediaProjectionGlobal(IDisplayManager dm, IPackageManager packageManager) {
        mDm = dm;
        mPackageManager = packageManager;
    }

    /**
     * Creates a VirtualDisplay that will mirror the content of displayIdToMirror
     * @param name The name for the virtual display
     * @param width The initial width for the virtual display
     * @param height The initial height for the virtual display
     * @param displayIdToMirror The displayId that will be mirrored into the virtual display.
     * @return VirtualDisplay that can be used to update properties.
     */
    @Nullable
    public VirtualDisplay createVirtualDisplay(@NonNull String name, int width, int height,
            int displayIdToMirror, @Nullable Surface surface) {

        // Density doesn't matter since this virtual display is only used for mirroring.
        VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(name, width,
                height, 1 /* densityDpi */)
                .setFlags(VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR)
                .setDisplayIdToMirror(displayIdToMirror);
        if (surface != null) {
            builder.setSurface(surface);
        }
        VirtualDisplayConfig virtualDisplayConfig = builder.build();

        String[] packages;
        try {
            packages = mPackageManager.getPackagesForUid(Process.myUid());
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }

        // Just use the first one since it just needs to match the package when looking it up by
        // calling UID in system server.
        // The call may come from a rooted device, in that case the requesting uid will be root so
        // it will not have any package name
        String packageName = packages == null ? null : packages[0];
        DisplayManagerGlobal.VirtualDisplayCallback
                callbackWrapper = new DisplayManagerGlobal.VirtualDisplayCallback(null, null);
        int displayId;
        try {
            displayId = mDm.createVirtualDisplay(virtualDisplayConfig, callbackWrapper, null,
                    packageName);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
        return DisplayManagerGlobal.getInstance().createVirtualDisplayWrapper(virtualDisplayConfig,
                null, callbackWrapper, displayId);
    }
}
