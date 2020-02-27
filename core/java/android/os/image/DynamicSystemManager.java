/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.os.image;

import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.content.Context;
import android.gsi.AvbPublicKey;
import android.gsi.GsiProgress;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

/**
 * The DynamicSystemManager offers a mechanism to use a new system image temporarily. After the
 * installation, the device can reboot into this image with a new created /data. This image will
 * last until the next reboot and then the device will go back to the original image. However the
 * installed image and the new created /data are not deleted but disabled. Thus the application can
 * either re-enable the installed image by calling {@link #toggle} or use the {@link #remove} to
 * delete it completely. In other words, there are three device states: no installation, installed
 * and running. The procedure to install a DynamicSystem starts with a {@link #startInstallation},
 * followed by a series of {@link #write} and ends with a {@link commit}. Once the installation is
 * complete, the device state changes from no installation to the installed state and a followed
 * reboot will change its state to running. Note one instance of DynamicSystem can exist on a given
 * device thus the {@link #startInstallation} will fail if the device is currently running a
 * DynamicSystem.
 *
 * @hide
 */
@SystemService(Context.DYNAMIC_SYSTEM_SERVICE)
public class DynamicSystemManager {
    private static final String TAG = "DynamicSystemManager";

    private final IDynamicSystemService mService;

    /** {@hide} */
    public DynamicSystemManager(IDynamicSystemService service) {
        mService = service;
    }

    /** The DynamicSystemManager.Session represents a started session for the installation. */
    public class Session {
        private Session() {}

        /**
         * Set the file descriptor that points to a ashmem which will be used
         * to fetch data during the submitFromAshmem.
         *
         * @param ashmem fd that points to a ashmem
         * @param size size of the ashmem file
         */
        @RequiresPermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
        public boolean setAshmem(ParcelFileDescriptor ashmem, long size) {
            try {
                return mService.setAshmem(ashmem, size);
            } catch (RemoteException e) {
                throw new RuntimeException(e.toString());
            }
        }

        /**
         * Submit bytes to the DSU partition from the ashmem previously set with
         * setAshmem.
         *
         * @param size Number of bytes
         * @return true on success, false otherwise.
         */
        @RequiresPermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
        public boolean submitFromAshmem(int size) {
            try {
                return mService.submitFromAshmem(size);
            } catch (RemoteException e) {
                throw new RuntimeException(e.toString());
            }
        }

        /**
         * Retrieve AVB public key from installing partition.
         *
         * @param dst           Output the AVB public key.
         * @return              true on success, false if partition doesn't have a
         *                      valid VBMeta block to retrieve the AVB key from.
         */
        @RequiresPermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
        public boolean getAvbPublicKey(AvbPublicKey dst) {
            try {
                return mService.getAvbPublicKey(dst);
            } catch (RemoteException e) {
                throw new RuntimeException(e.toString());
            }
        }

        /**
         * Finish write and make device to boot into the it after reboot.
         *
         * @return {@code true} if the call succeeds. {@code false} if there is any native runtime
         *     error.
         */
        @RequiresPermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
        public boolean commit() {
            try {
                return mService.setEnable(true, true);
            } catch (RemoteException e) {
                throw new RuntimeException(e.toString());
            }
        }
    }
    /**
     * Start DynamicSystem installation.
     *
     * @return true if the call succeeds
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public boolean startInstallation(String dsuSlot) {
        try {
            return mService.startInstallation(dsuSlot);
        } catch (RemoteException e) {
            throw new RuntimeException(e.toString());
        }
    }
    /**
     * Start DynamicSystem installation. This call may take an unbounded amount of time. The caller
     * may use another thread to call the getStartProgress() to get the progress.
     *
     * @param name The DSU partition name
     * @param size Size of the DSU image in bytes
     * @param readOnly True if the partition is read only, e.g. system.
     * @return {@code true} if the call succeeds. {@code false} either the device does not contain
     *     enough space or a DynamicSystem is currently in use where the {@link #isInUse} would be
     *     true.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public Session createPartition(String name, long size, boolean readOnly) {
        try {
            if (mService.createPartition(name, size, readOnly)) {
                return new Session();
            } else {
                return null;
            }
        } catch (RemoteException e) {
            throw new RuntimeException(e.toString());
        }
    }
    /**
     * Finish a previously started installation. Installations without a cooresponding
     * finishInstallation() will be cleaned up during device boot.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public boolean finishInstallation() {
        try {
            return mService.finishInstallation();
        } catch (RemoteException e) {
            throw new RuntimeException(e.toString());
        }
    }
    /**
     * Query the progress of the current installation operation. This can be called while the
     * installation is in progress.
     *
     * @return GsiProgress GsiProgress { int status; long bytes_processed; long total_bytes; } The
     *     status field can be IGsiService.STATUS_NO_OPERATION, IGsiService.STATUS_WORKING or
     *     IGsiService.STATUS_COMPLETE.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public GsiProgress getInstallationProgress() {
        try {
            return mService.getInstallationProgress();
        } catch (RemoteException e) {
            throw new RuntimeException(e.toString());
        }
    }

    /**
     * Abort the installation process. Note this method must be called in a thread other than the
     * one calling the startInstallation method as the startInstallation method will not return
     * until it is finished.
     *
     * @return {@code true} if the call succeeds. {@code false} if there is no installation
     *     currently.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public boolean abort() {
        try {
            return mService.abort();
        } catch (RemoteException e) {
            throw new RuntimeException(e.toString());
        }
    }

    /** @return {@code true} if the device is running a dynamic system */
    @RequiresPermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public boolean isInUse() {
        try {
            return mService.isInUse();
        } catch (RemoteException e) {
            throw new RuntimeException(e.toString());
        }
    }

    /** @return {@code true} if the device has a dynamic system installed */
    @RequiresPermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public boolean isInstalled() {
        try {
            return mService.isInstalled();
        } catch (RemoteException e) {
            throw new RuntimeException(e.toString());
        }
    }

    /** @return {@code true} if the device has a dynamic system enabled */
    @RequiresPermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public boolean isEnabled() {
        try {
            return mService.isEnabled();
        } catch (RemoteException e) {
            throw new RuntimeException(e.toString());
        }
    }

    /**
     * Remove DynamicSystem installation if present
     *
     * @return {@code true} if the call succeeds. {@code false} if there is no installed image.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public boolean remove() {
        try {
            return mService.remove();
        } catch (RemoteException e) {
            throw new RuntimeException(e.toString());
        }
    }

    /**
     * Enable or disable DynamicSystem.
     * @return {@code true} if the call succeeds. {@code false} if there is no installed image.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public boolean setEnable(boolean enable, boolean oneShot) {
        try {
            return mService.setEnable(enable, oneShot);
        } catch (RemoteException e) {
            throw new RuntimeException(e.toString());
        }
    }
}
