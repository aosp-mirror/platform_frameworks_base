/* //device/java/android/android/os/IUsb.aidl
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

package android.os.storage;

import android.os.storage.IMountServiceListener;
import android.os.storage.IMountShutdownObserver;

/** WARNING! Update IMountService.h and IMountService.cpp if you change this file.
 * In particular, the ordering of the methods below must match the 
 * _TRANSACTION enum in IMountService.cpp
 * @hide - Applications should use android.os.storage.StorageManager to access
 * storage functions.
 */
interface IMountService
{
    /**
     * Registers an IMountServiceListener for receiving async
     * notifications.
     */
    void registerListener(IMountServiceListener listener);

    /**
     * Unregisters an IMountServiceListener
     */
    void unregisterListener(IMountServiceListener listener);

    /**
     * Returns true if a USB mass storage host is connected
     */
    boolean isUsbMassStorageConnected();

    /**
     * Enables / disables USB mass storage.
     * The caller should check actual status of enabling/disabling
     * USB mass storage via StorageEventListener.
     */
    void setUsbMassStorageEnabled(boolean enable);

    /**
     * Returns true if a USB mass storage host is enabled (media is shared)
     */
    boolean isUsbMassStorageEnabled();

    /**
     * Mount external storage at given mount point.
     * Returns an int consistent with MountServiceResultCode
     */
    int mountVolume(String mountPoint);

    /**
     * Safely unmount external storage at given mount point.
     * The unmount is an asynchronous operation. Applications
     * should register StorageEventListener for storage related
     * status changes.
     * 
     */
    void unmountVolume(String mountPoint, boolean force);

    /**
     * Format external storage given a mount point.
     * Returns an int consistent with MountServiceResultCode
     */
    int formatVolume(String mountPoint);

    /**
     * Returns an array of pids with open files on
     * the specified path.
     */
    int[] getStorageUsers(String path);

    /**
     * Gets the state of a volume via its mountpoint.
     */
    String getVolumeState(String mountPoint);

    /*
     * Creates a secure container with the specified parameters.
     * Returns an int consistent with MountServiceResultCode
     */
    int createSecureContainer(String id, int sizeMb, String fstype, String key, int ownerUid);

    /*
     * Finalize a container which has just been created and populated.
     * After finalization, the container is immutable.
     * Returns an int consistent with MountServiceResultCode
     */
    int finalizeSecureContainer(String id);

    /*
     * Destroy a secure container, and free up all resources associated with it.
     * NOTE: Ensure all references are released prior to deleting.
     * Returns an int consistent with MountServiceResultCode
     */
    int destroySecureContainer(String id, boolean force);

    /*
     * Mount a secure container with the specified key and owner UID.
     * Returns an int consistent with MountServiceResultCode
     */
    int mountSecureContainer(String id, String key, int ownerUid);

    /*
     * Unount a secure container.
     * Returns an int consistent with MountServiceResultCode
     */
    int unmountSecureContainer(String id, boolean force);

    /*
     * Returns true if the specified container is mounted
     */
    boolean isSecureContainerMounted(String id);

    /*
     * Rename an unmounted secure container.
     * Returns an int consistent with MountServiceResultCode
     */
    int renameSecureContainer(String oldId, String newId);

    /*
     * Returns the filesystem path of a mounted secure container.
     */
    String getSecureContainerPath(String id);

    /**
     * Gets an Array of currently known secure container IDs
     */
    String[] getSecureContainerList();

    /**
     * Shuts down the MountService and gracefully unmounts all external media.
     * Invokes call back once the shutdown is complete.
     */
    void shutdown(IMountShutdownObserver observer);

    /**
     * Call into MountService by PackageManager to notify that its done
     * processing the media status update request.
     */
    void finishMediaUpdate();
}
