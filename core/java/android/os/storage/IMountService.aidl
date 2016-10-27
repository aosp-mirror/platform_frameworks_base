/**
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os.storage;

import android.content.pm.IPackageMoveObserver;
import android.os.ParcelFileDescriptor;
import android.os.storage.DiskInfo;
import android.os.storage.IMountServiceListener;
import android.os.storage.IMountShutdownObserver;
import android.os.storage.IObbActionListener;
import android.os.storage.StorageVolume;
import android.os.storage.VolumeInfo;
import android.os.storage.VolumeRecord;

/**
 * WARNING! Update IMountService.h and IMountService.cpp if you change this
 * file. In particular, the transaction ids below must match the
 * _TRANSACTION enum in IMountService.cpp
 *
 * @hide - Applications should use android.os.storage.StorageManager to access
 *       storage functions.
 */
interface IMountService {
    /**
     * Registers an IMountServiceListener for receiving async notifications.
     */
    void registerListener(IMountServiceListener listener) = 1;
    /**
     * Unregisters an IMountServiceListener
     */
    void unregisterListener(IMountServiceListener listener) = 2;
    /**
     * Returns true if a USB mass storage host is connected
     */
    boolean isUsbMassStorageConnected() = 3;
    /**
     * Enables / disables USB mass storage. The caller should check actual
     * status of enabling/disabling USB mass storage via StorageEventListener.
     */
    void setUsbMassStorageEnabled(boolean enable) = 4;
    /**
     * Returns true if a USB mass storage host is enabled (media is shared)
     */
    boolean isUsbMassStorageEnabled() = 5;
    /**
     * Mount external storage at given mount point. Returns an int consistent
     * with MountServiceResultCode
     */
    int mountVolume(in String mountPoint) = 6;
    /**
     * Safely unmount external storage at given mount point. The unmount is an
     * asynchronous operation. Applications should register StorageEventListener
     * for storage related status changes.
     * @param mountPoint the mount point
     * @param force whether or not to forcefully unmount it (e.g. even if programs are using this
     *     data currently)
     * @param removeEncryption whether or not encryption mapping should be removed from the volume.
     *     This value implies {@code force}.
     */
    void unmountVolume(in String mountPoint, boolean force, boolean removeEncryption) = 7;
    /**
     * Format external storage given a mount point. Returns an int consistent
     * with MountServiceResultCode
     */
    int formatVolume(in String mountPoint) = 8;
    /**
     * Returns an array of pids with open files on the specified path.
     */
    int[] getStorageUsers(in String path) = 9;
    /**
     * Gets the state of a volume via its mountpoint.
     */
    String getVolumeState(in String mountPoint) = 10;
    /*
     * Creates a secure container with the specified parameters. Returns an int
     * consistent with MountServiceResultCode
     */
    int createSecureContainer(in String id, int sizeMb, in String fstype, in String key,
            int ownerUid, boolean external) = 11;
    /*
     * Finalize a container which has just been created and populated. After
     * finalization, the container is immutable. Returns an int consistent with
     * MountServiceResultCode
     */
    int finalizeSecureContainer(in String id) = 12;
    /*
     * Destroy a secure container, and free up all resources associated with it.
     * NOTE: Ensure all references are released prior to deleting. Returns an
     * int consistent with MountServiceResultCode
     */
    int destroySecureContainer(in String id, boolean force) = 13;
    /*
     * Mount a secure container with the specified key and owner UID. Returns an
     * int consistent with MountServiceResultCode
     */
    int mountSecureContainer(in String id, in String key, int ownerUid, boolean readOnly) = 14;
    /*
     * Unount a secure container. Returns an int consistent with
     * MountServiceResultCode
     */
    int unmountSecureContainer(in String id, boolean force) = 15;
    /*
     * Returns true if the specified container is mounted
     */
    boolean isSecureContainerMounted(in String id) = 16;
    /*
     * Rename an unmounted secure container. Returns an int consistent with
     * MountServiceResultCode
     */
    int renameSecureContainer(in String oldId, in String newId) = 17;
    /*
     * Returns the filesystem path of a mounted secure container.
     */
    String getSecureContainerPath(in String id) = 18;
    /**
     * Gets an Array of currently known secure container IDs
     */
    String[] getSecureContainerList() = 19;
    /**
     * Shuts down the MountService and gracefully unmounts all external media.
     * Invokes call back once the shutdown is complete.
     */
    void shutdown(IMountShutdownObserver observer) = 20;
    /**
     * Call into MountService by PackageManager to notify that its done
     * processing the media status update request.
     */
    void finishMediaUpdate() = 21;
    /**
     * Mounts an Opaque Binary Blob (OBB) with the specified decryption key and
     * only allows the calling process's UID access to the contents.
     * MountService will call back to the supplied IObbActionListener to inform
     * it of the terminal state of the call.
     */
    void mountObb(in String rawPath, in String canonicalPath, in String key,
            IObbActionListener token, int nonce) = 22;
    /**
     * Unmounts an Opaque Binary Blob (OBB). When the force flag is specified,
     * any program using it will be forcibly killed to unmount the image.
     * MountService will call back to the supplied IObbActionListener to inform
     * it of the terminal state of the call.
     */
    void unmountObb(in String rawPath, boolean force, IObbActionListener token, int nonce) = 23;
    /**
     * Checks whether the specified Opaque Binary Blob (OBB) is mounted
     * somewhere.
     */
    boolean isObbMounted(in String rawPath) = 24;
    /**
     * Gets the path to the mounted Opaque Binary Blob (OBB).
     */
    String getMountedObbPath(in String rawPath) = 25;
    /**
     * Returns whether or not the external storage is emulated.
     */
    boolean isExternalStorageEmulated() = 26;
    /**
     * Decrypts any encrypted volumes.
     */
    int decryptStorage(in String password) = 27;
    /**
     * Encrypts storage.
     */
    int encryptStorage(int type, in String password) = 28;
    /**
     * Changes the encryption password.
     */
    int changeEncryptionPassword(int type, in String password) = 29;
    /**
     * Returns list of all mountable volumes.
     */
    StorageVolume[] getVolumeList(int uid, in String packageName, int flags) = 30;
    /**
     * Gets the path on the filesystem for the ASEC container itself.
     *
     * @param cid ASEC container ID
     * @return path to filesystem or {@code null} if it's not found
     * @throws RemoteException
     */
    String getSecureContainerFilesystemPath(in String cid) = 31;
    /**
     * Determines the encryption state of the volume.
     * @return a numerical value. See {@code ENCRYPTION_STATE_*} for possible
     * values.
     * Note that this has been replaced in most cases by the APIs in
     * StorageManager (see isEncryptable and below)
     * This is still useful to get the error state when encryption has failed
     * and CryptKeeper needs to throw up a screen advising the user what to do
     */
    int getEncryptionState() = 32;
    /**
     * Verify the encryption password against the stored volume.  This method
     * may only be called by the system process.
     */
    int verifyEncryptionPassword(in String password) = 33;
    /*
     * Fix permissions in a container which has just been created and populated.
     * Returns an int consistent with MountServiceResultCode
     */
    int fixPermissionsSecureContainer(in String id, int gid, in String filename) = 34;
    /**
     * Ensure that all directories along given path exist, creating parent
     * directories as needed. Validates that given path is absolute and that it
     * contains no relative "." or ".." paths or symlinks. Also ensures that
     * path belongs to a volume managed by vold, and that path is either
     * external storage data or OBB directory belonging to calling app.
     */
    int mkdirs(in String callingPkg, in String path) = 35;
    /**
     * Determines the type of the encryption password
     * @return PasswordType
     */
    int getPasswordType() = 36;
    /**
     * Get password from vold
     * @return password or empty string
     */
    String getPassword() = 37;
    /**
     * Securely clear password from vold
     */
    oneway void clearPassword() = 38;
    /**
     * Set a field in the crypto header.
     * @param field field to set
     * @param contents contents to set in field
     */
    oneway void setField(in String field, in String contents) = 39;
    /**
     * Gets a field from the crypto header.
     * @param field field to get
     * @return contents of field
     */
    String getField(in String field) = 40;
    int resizeSecureContainer(in String id, int sizeMb, in String key) = 41;
    /**
     * Report the time of the last maintenance operation such as fstrim.
     * @return Timestamp of the last maintenance operation, in the
     *     System.currentTimeMillis() time base
     * @throws RemoteException
     */
    long lastMaintenance() = 42;
    /**
     * Kick off an immediate maintenance operation
     * @throws RemoteException
     */
    void runMaintenance() = 43;
    void waitForAsecScan() = 44;
    DiskInfo[] getDisks() = 45;
    VolumeInfo[] getVolumes(int flags) = 46;
    VolumeRecord[] getVolumeRecords(int flags) = 47;
    void mount(in String volId) = 48;
    void unmount(in String volId) = 49;
    void format(in String volId) = 50;
    void partitionPublic(in String diskId) = 51;
    void partitionPrivate(in String diskId) = 52;
    void partitionMixed(in String diskId, int ratio) = 53;
    void setVolumeNickname(in String fsUuid, in String nickname) = 54;
    void setVolumeUserFlags(in String fsUuid, int flags, int mask) = 55;
    void forgetVolume(in String fsUuid) = 56;
    void forgetAllVolumes() = 57;
    String getPrimaryStorageUuid() = 58;
    void setPrimaryStorageUuid(in String volumeUuid, IPackageMoveObserver callback) = 59;
    long benchmark(in String volId) = 60;
    void setDebugFlags(int flags, int mask) = 61;
    void createUserKey(int userId, int serialNumber, boolean ephemeral) = 62;
    void destroyUserKey(int userId) = 63;
    void unlockUserKey(int userId, int serialNumber, in byte[] token, in byte[] secret) = 64;
    void lockUserKey(int userId) = 65;
    boolean isUserKeyUnlocked(int userId) = 66;
    void prepareUserStorage(in String volumeUuid, int userId, int serialNumber, int flags) = 67;
    void destroyUserStorage(in String volumeUuid, int userId, int flags) = 68;
    boolean isConvertibleToFBE() = 69;
    ParcelFileDescriptor mountAppFuse(in String name) = 70;
    void addUserKeyAuth(int userId, int serialNumber, in byte[] token, in byte[] secret) = 71;
    void fixateNewestUserKeyAuth(int userId) = 72;
}