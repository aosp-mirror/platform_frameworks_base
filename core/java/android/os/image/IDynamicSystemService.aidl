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

import android.gsi.AvbPublicKey;
import android.gsi.GsiProgress;

/** {@hide} */
interface IDynamicSystemService
{
    /**
     * Start DynamicSystem installation.
     * @param dsuSlot Name used to identify this installation
     * @return true if the call succeeds
     */
    @EnforcePermission("MANAGE_DYNAMIC_SYSTEM")
    boolean startInstallation(@utf8InCpp String dsuSlot);

    /**
     * Create a DSU partition. This call may take 60~90 seconds. The caller
     * may use another thread to call the getStartProgress() to get the progress.
     * @param name The DSU partition name
     * @param size Size of the DSU image in bytes
     * @param readOnly True if this partition is readOnly
     * @return IGsiService.INSTALL_* status code
     */
    @EnforcePermission("MANAGE_DYNAMIC_SYSTEM")
    int createPartition(@utf8InCpp String name, long size, boolean readOnly);

    /**
     * Complete the current partition installation.
     *
     * @return true if the partition installation completes without error.
     */
    @EnforcePermission("MANAGE_DYNAMIC_SYSTEM")
    boolean closePartition();

    /**
     * Finish a previously started installation. Installations without
     * a cooresponding finishInstallation() will be cleaned up during device boot.
     */
    @EnforcePermission("MANAGE_DYNAMIC_SYSTEM")
    boolean finishInstallation();

    /**
     * Query the progress of the current installation operation. This can be called while
     * the installation is in progress.
     *
     * @return GsiProgress
     */
    @EnforcePermission("MANAGE_DYNAMIC_SYSTEM")
    GsiProgress getInstallationProgress();

    /**
     * Abort the installation process. Note this method must be called in a thread other
     * than the one calling the startInstallation method as the startInstallation
     * method will not return until it is finished.
     *
     * @return true if the call succeeds
     */
    @EnforcePermission("MANAGE_DYNAMIC_SYSTEM")
    boolean abort();

    /**
     * @return true if the device is running an DynamicAnroid image
     */
    @RequiresNoPermission
    boolean isInUse();

    /**
     * @return true if the device has an DynamicSystem image installed
     */
    @RequiresNoPermission
    boolean isInstalled();

    /**
     * @return true if the device has an DynamicSystem image enabled
     */
    @EnforcePermission("MANAGE_DYNAMIC_SYSTEM")
    boolean isEnabled();

    /**
     * Remove DynamicSystem installation if present
     *
     * @return true if the call succeeds
     */
    @EnforcePermission("MANAGE_DYNAMIC_SYSTEM")
    boolean remove();

    /**
     * Enable or disable DynamicSystem.
     *
     * @param oneShot       If true, the GSI will boot once and then disable itself.
     *
     * @return true if the call succeeds
     */
    @EnforcePermission("MANAGE_DYNAMIC_SYSTEM")
    boolean setEnable(boolean enable, boolean oneShot);

    /**
     * Set the file descriptor that points to a ashmem which will be used
     * to fetch data during the submitFromAshmem.
     *
     * @param fd            fd that points to a ashmem
     * @param size          size of the ashmem file
     */
    @EnforcePermission("MANAGE_DYNAMIC_SYSTEM")
    boolean setAshmem(in ParcelFileDescriptor fd, long size);

    /**
     * Submit bytes to the DSU partition from the ashmem previously set with
     * setAshmem.
     *
     * @param bytes         number of bytes that can be read from stream.
     * @return              true on success, false otherwise.
     */
    @EnforcePermission("MANAGE_DYNAMIC_SYSTEM")
    boolean submitFromAshmem(long bytes);

    /**
     * Retrieve AVB public key from installing partition.
     *
     * @param dst           Output the AVB public key.
     * @return              true on success, false if partition doesn't have a
     *                      valid VBMeta block to retrieve the AVB key from.
     */
    @EnforcePermission("MANAGE_DYNAMIC_SYSTEM")
    boolean getAvbPublicKey(out AvbPublicKey dst);

    /**
     * Returns the suggested scratch partition size for overlayFS.
     */
    @EnforcePermission("MANAGE_DYNAMIC_SYSTEM")
    long suggestScratchSize();
}
