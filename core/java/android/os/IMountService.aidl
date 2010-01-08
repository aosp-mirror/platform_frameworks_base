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

package android.os;

/** WARNING! Update IMountService.h and IMountService.cpp if you change this file.
 * In particular, the ordering of the methods below must match the 
 * _TRANSACTION enum in IMountService.cpp
 * @hide
 */
interface IMountService
{
    /**
     * Is mass storage support enabled?
     */
    boolean getMassStorageEnabled();

    /**
     * Enable or disable mass storage support.
     */
    void setMassStorageEnabled(boolean enabled);

    /**
     * Is mass storage connected?
     */
    boolean getMassStorageConnected();
    
    /**
     * Mount external storage at given mount point.
     */
    void mountMedia(String mountPoint);

    /**
     * Safely unmount external storage at given mount point.
     */
    void unmountMedia(String mountPoint);

    /**
     * Format external storage given a mount point.
     */
    void formatMedia(String mountPoint);

    /**
     * Returns true if media notification sounds are enabled.
     */
    boolean getPlayNotificationSounds();

    /**
     * Sets whether or not media notification sounds are played.
     */
    void setPlayNotificationSounds(boolean value);

    /**
     * Gets the state of an volume via it's mountpoint.
     */
    String getVolumeState(String mountPoint);

    /*
     * Creates a secure cache with the specified parameters.
     * On success, the filesystem cache-path is returned.
     */
    String createSecureCache(String id, int sizeMb, String fstype, String key, int ownerUid);

    /*
     * Finalize a cache which has just been created and populated.
     * After finalization, the cache is immutable.
     */
    void finalizeSecureCache(String id);

    /*
     * Destroy a secure cache, and free up all resources associated with it.
     * NOTE: Ensure all references are released prior to deleting.
     */
    void destroySecureCache(String id);

    /*
     * Mount a secure cache with the specified key and owner UID.
     * On success, the filesystem cache-path is returned.
     */
    String mountSecureCache(String id, String key, int ownerUid);

    /*
     * Returns the filesystem path of a mounted secure cache.
     */
    String getSecureCachePath(String id);

    /**
     * Gets an Array of currently known secure cache IDs
     */
    String[] getSecureCacheList();

    /**
     * Shuts down the MountService and gracefully unmounts
     * all external media.
     */
    void shutdown();
}
