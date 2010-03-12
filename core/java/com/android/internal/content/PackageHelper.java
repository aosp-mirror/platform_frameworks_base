/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.internal.content;

import android.os.storage.IMountService;

import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.storage.StorageResultCode;
import android.util.Log;

import java.io.File;

/**
 * Constants used internally between the PackageManager
 * and media container service transports.
 * Some utility methods to invoke MountService api.
 */
public class PackageHelper {
    public static final int RECOMMEND_INSTALL_INTERNAL = 1;
    public static final int RECOMMEND_INSTALL_EXTERNAL = 2;
    public static final int RECOMMEND_FAILED_INSUFFICIENT_STORAGE = -1;
    public static final int RECOMMEND_FAILED_INVALID_APK = -2;
    public static final int RECOMMEND_FAILED_INVALID_LOCATION = -3;
    public static final int RECOMMEND_FAILED_ALREADY_EXISTS = -4;
    public static final int RECOMMEND_MEDIA_UNAVAILABLE = -5;
    private static final boolean localLOGV = true;
    private static final String TAG = "PackageHelper";
    // App installation location settings values
    public static final int APP_INSTALL_AUTO = 0;
    public static final int APP_INSTALL_INTERNAL = 1;
    public static final int APP_INSTALL_EXTERNAL = 2;

    public static IMountService getMountService() {
        IBinder service = ServiceManager.getService("mount");
        if (service != null) {
            return IMountService.Stub.asInterface(service);
        } else {
            Log.e(TAG, "Can't get mount service");
        }
        return null;
    }

    public static String createSdDir(File tmpPackageFile, String cid,
            String sdEncKey, int uid) {
        // Create mount point via MountService
        IMountService mountService = getMountService();
        long len = tmpPackageFile.length();
        int mbLen = (int) (len >> 20);
        if ((len - (mbLen * 1024 * 1024)) > 0) {
            mbLen++;
        }
        // Add buffer size
        mbLen++;
        if (localLOGV) Log.i(TAG, "Size of container " + mbLen + " MB " + len + " bytes");

        try {
            int rc = mountService.createSecureContainer(
                    cid, mbLen, "fat", sdEncKey, uid);
            if (rc != StorageResultCode.OperationSucceeded) {
                Log.e(TAG, "Failed to create secure container " + cid);
                return null;
            }
            String cachePath = mountService.getSecureContainerPath(cid);
            if (localLOGV) Log.i(TAG, "Created secure container " + cid +
                    " at " + cachePath);
                return cachePath;
        } catch (RemoteException e) {
            Log.e(TAG, "MountService running?");
        }
        return null;
    }

   public static String mountSdDir(String cid, String key, int ownerUid) {
    try {
        int rc = getMountService().mountSecureContainer(cid, key, ownerUid);
        if (rc != StorageResultCode.OperationSucceeded) {
            Log.i(TAG, "Failed to mount container " + cid + " rc : " + rc);
            return null;
        }
        return getMountService().getSecureContainerPath(cid);
    } catch (RemoteException e) {
        Log.e(TAG, "MountService running?");
    }
    return null;
   }

   public static boolean unMountSdDir(String cid) {
    try {
        int rc = getMountService().unmountSecureContainer(cid, true);
        if (rc != StorageResultCode.OperationSucceeded) {
            Log.e(TAG, "Failed to unmount " + cid + " with rc " + rc);
            return false;
        }
        return true;
    } catch (RemoteException e) {
        Log.e(TAG, "MountService running?");
    }
        return false;
   }

   public static boolean renameSdDir(String oldId, String newId) {
       try {
           int rc = getMountService().renameSecureContainer(oldId, newId);
           if (rc != StorageResultCode.OperationSucceeded) {
               Log.e(TAG, "Failed to rename " + oldId + " to " +
                       newId + "with rc " + rc);
               return false;
           }
           return true;
       } catch (RemoteException e) {
           Log.i(TAG, "Failed ot rename  " + oldId + " to " + newId +
                   " with exception : " + e);
       }
       return false;
   }

   public static String getSdDir(String cid) {
       try {
            return getMountService().getSecureContainerPath(cid);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get container path for " + cid +
                " with exception " + e);
        }
        return null;
   }

    public static boolean finalizeSdDir(String cid) {
        try {
            int rc = getMountService().finalizeSecureContainer(cid);
            if (rc != StorageResultCode.OperationSucceeded) {
                Log.i(TAG, "Failed to finalize container " + cid);
                return false;
            }
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to finalize container " + cid +
                    " with exception " + e);
        }
        return false;
    }

    public static boolean destroySdDir(String cid) {
        try {
            if (localLOGV) Log.i(TAG, "Forcibly destroying container " + cid);
            int rc = getMountService().destroySecureContainer(cid, true);
            if (rc != StorageResultCode.OperationSucceeded) {
                Log.i(TAG, "Failed to destroy container " + cid);
                return false;
            }
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to destroy container " + cid +
                    " with exception " + e);
        }
        return false;
    }

    public static String[] getSecureContainerList() {
        try {
            return getMountService().getSecureContainerList();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get secure container list with exception" +
                    e);
        }
        return null;
    }

   public static boolean isContainerMounted(String cid) {
       try {
           return getMountService().isSecureContainerMounted(cid);
       } catch (RemoteException e) {
           Log.e(TAG, "Failed to find out if container " + cid + " mounted");
       }
       return false;
   }
}
