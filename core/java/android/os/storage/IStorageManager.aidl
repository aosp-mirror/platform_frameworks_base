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
import android.os.storage.IStorageEventListener;
import android.os.storage.IStorageShutdownObserver;
import android.os.storage.IObbActionListener;
import android.os.storage.StorageVolume;
import android.os.storage.VolumeInfo;
import android.os.storage.VolumeRecord;
import com.android.internal.os.AppFuseMount;

/**
 * WARNING! Update IMountService.h and IMountService.cpp if you change this
 * file. In particular, the transaction ids below must match the
 * _TRANSACTION enum in IMountService.cpp
 *
 * @hide - Applications should use android.os.storage.StorageManager to access
 *       storage functions.
 */
interface IStorageManager {
    /**
     * Registers an IStorageEventListener for receiving async notifications.
     */
    void registerListener(IStorageEventListener listener) = 0;
    /**
     * Unregisters an IStorageEventListener
     */
    void unregisterListener(IStorageEventListener listener) = 1;
    /**
     * Returns true if a USB mass storage host is connected
     */
    boolean isUsbMassStorageConnected() = 2;
    /**
     * Enables / disables USB mass storage. The caller should check actual
     * status of enabling/disabling USB mass storage via StorageEventListener.
     */
    void setUsbMassStorageEnabled(boolean enable) = 3;
    /**
     * Returns true if a USB mass storage host is enabled (media is shared)
     */
    boolean isUsbMassStorageEnabled() = 4;
    /**
     * Mount external storage at given mount point. Returns an int consistent
     * with StorageResultCode
     */
    int mountVolume(in String mountPoint) = 5;
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
    void unmountVolume(in String mountPoint, boolean force, boolean removeEncryption) = 6;
    /**
     * Format external storage given a mount point. Returns an int consistent
     * with StorageResultCode
     */
    int formatVolume(in String mountPoint) = 7;
    /**
     * Returns an array of pids with open files on the specified path.
     */
    int[] getStorageUsers(in String path) = 8;
    /**
     * Gets the state of a volume via its mountpoint.
     */
    String getVolumeState(in String mountPoint) = 9;
    /*
     * Creates a secure container with the specified parameters. Returns an int
     * consistent with StorageResultCode
     */
    int createSecureContainer(in String id, int sizeMb, in String fstype, in String key,
            int ownerUid, boolean external) = 10;
    /*
     * Finalize a container which has just been created and populated. After
     * finalization, the container is immutable. Returns an int consistent with
     * StorageResultCode
     */
    int finalizeSecureContainer(in String id) = 11;
    /*
     * Destroy a secure container, and free up all resources associated with it.
     * NOTE: Ensure all references are released prior to deleting. Returns an
     * int consistent with StorageResultCode
     */
    int destroySecureContainer(in String id, boolean force) = 12;
    /*
     * Mount a secure container with the specified key and owner UID. Returns an
     * int consistent with StorageResultCode
     */
    int mountSecureContainer(in String id, in String key, int ownerUid, boolean readOnly) = 13;
    /*
     * Unount a secure container. Returns an int consistent with
     * StorageResultCode
     */
    int unmountSecureContainer(in String id, boolean force) = 14;
    /*
     * Returns true if the specified container is mounted
     */
    boolean isSecureContainerMounted(in String id) = 15;
    /*
     * Rename an unmounted secure container. Returns an int consistent with
     * StorageResultCode
     */
    int renameSecureContainer(in String oldId, in String newId) = 16;
    /*
     * Returns the filesystem path of a mounted secure container.
     */
    String getSecureContainerPath(in String id) = 17;
    /**
     * Gets an Array of currently known secure container IDs
     */
    String[] getSecureContainerList() = 18;
    /**
     * Shuts down the StorageManagerService and gracefully unmounts all external media.
     * Invokes call back once the shutdown is complete.
     */
    void shutdown(IStorageShutdownObserver observer) = 19;
    /**
     * Call into StorageManagerService by PackageManager to notify that its done
     * processing the media status update request.
     */
    void finishMediaUpdate() = 20;
    /**
     * Mounts an Opaque Binary Blob (OBB) with the specified decryption key and
     * only allows the calling process's UID access to the contents.
     * StorageManagerService will call back to the supplied IObbActionListener to inform
     * it of the terminal state of the call.
     */
    void mountObb(in String rawPath, in String canonicalPath, in String key,
            IObbActionListener token, int nonce) = 21;
    /**
     * Unmounts an Opaque Binary Blob (OBB). When the force flag is specified,
     * any program using it will be forcibly killed to unmount the image.
     * StorageManagerService will call back to the supplied IObbActionListener to inform
     * it of the terminal state of the call.
     */
    void unmountObb(in String rawPath, boolean force, IObbActionListener token, int nonce) = 22;
    /**
     * Checks whether the specified Opaque Binary Blob (OBB) is mounted
     * somewhere.
     */
    boolean isObbMounted(in String rawPath) = 23;
    /**
     * Gets the path to the mounted Opaque Binary Blob (OBB).
     */
    String getMountedObbPath(in String rawPath) = 24;
    /**
     * Returns whether or not the external storage is emulated.
     */
    boolean isExternalStorageEmulated() = 25;
    /**
     * Decrypts any encrypted volumes.
     */
    int decryptStorage(in String password) = 26;
    /**
     * Encrypts storage.
     */
    int encryptStorage(int type, in String password) = 27;
    /**
     * Changes the encryption password.
     */
    int changeEncryptionPassword(int type, in String password) = 28;
    /**
     * Returns list of all mountable volumes.
     */
    StorageVolume[] getVolumeList(int uid, in String packageName, int flags) = 29;
    /**
     * Gets the path on the filesystem for the ASEC container itself.
     *
     * @param cid ASEC container ID
     * @return path to filesystem or {@code null} if it's not found
     * @throws RemoteException
     */
    String getSecureContainerFilesystemPath(in String cid) = 30;
    /**
     * Determines the encryption state of the volume.
     * @return a numerical value. See {@code ENCRYPTION_STATE_*} for possible
     * values.
     * Note that this has been replaced in most cases by the APIs in
     * StorageManager (see isEncryptable and below)
     * This is still useful to get the error state when encryption has failed
     * and CryptKeeper needs to throw up a screen advising the user what to do
     */
    int getEncryptionState() = 31;
    /**
     * Verify the encryption password against the stored volume.  This method
     * may only be called by the system process.
     */
    int verifyEncryptionPassword(in String password) = 32;
    /*
     * Fix permissions in a container which has just been created and populated.
     * Returns an int consistent with StorageResultCode
     */
    int fixPermissionsSecureContainer(in String id, int gid, in String filename) = 33;
    /**
     * Ensure that all directories along given path exist, creating parent
     * directories as needed. Validates that given path is absolute and that it
     * contains no relative "." or ".." paths or symlinks. Also ensures that
     * path belongs to a volume managed by vold, and that path is either
     * external storage data or OBB directory belonging to calling app.
     */
    int mkdirs(in String callingPkg, in String path) = 34;
    /**
     * Determines the type of the encryption password
     * @return PasswordType
     */
    int getPasswordType() = 35;
    /**
     * Get password from vold
     * @return password or empty string
     */
    String getPassword() = 36;
    /**
     * Securely clear password from vold
     */
    oneway void clearPassword() = 37;
    /**
     * Set a field in the crypto header.
     * @param field field to set
     * @param contents contents to set in field
     */
    oneway void setField(in String field, in String contents) = 38;
    /**
     * Gets a field from the crypto header.
     * @param field field to get
     * @return contents of field
     */
    String getField(in String field) = 39;
    int resizeSecureContainer(in String id, int sizeMb, in String key) = 40;
    /**
     * Report the time of the last maintenance operation such as fstrim.
     * @return Timestamp of the last maintenance operation, in the
     *     System.currentTimeMillis() time base
     * @throws RemoteException
     */
    long lastMaintenance() = 41;
    /**
     * Kick off an immediate maintenance operation
     * @throws RemoteException
     */
    void runMaintenance() = 42;
    void waitForAsecScan() = 43;
    DiskInfo[] getDisks() = 44;
    VolumeInfo[] getVolumes(int flags) = 45;
    VolumeRecord[] getVolumeRecords(int flags) = 46;
    void mount(in String volId) = 47;
    void unmount(in String volId) = 48;
    void format(in String volId) = 49;
    void partitionPublic(in String diskId) = 50;
    void partitionPrivate(in String diskId) = 51;
    void partitionMixed(in String diskId, int ratio) = 52;
    void setVolumeNickname(in String fsUuid, in String nickname) = 53;
    void setVolumeUserFlags(in String fsUuid, int flags, int mask) = 54;
    void forgetVolume(in String fsUuid) = 55;
    void forgetAllVolumes() = 56;
    String getPrimaryStorageUuid() = 57;
    void setPrimaryStorageUuid(in String volumeUuid, IPackageMoveObserver callback) = 58;
    long benchmark(in String volId) = 59;
    void setDebugFlags(int flags, int mask) = 60;
    void createUserKey(int userId, int serialNumber, boolean ephemeral) = 61;
    void destroyUserKey(int userId) = 62;
    void unlockUserKey(int userId, int serialNumber, in byte[] token, in byte[] secret) = 63;
    void lockUserKey(int userId) = 64;
    boolean isUserKeyUnlocked(int userId) = 65;
    void prepareUserStorage(in String volumeUuid, int userId, int serialNumber, int flags) = 66;
    void destroyUserStorage(in String volumeUuid, int userId, int flags) = 67;
    boolean isConvertibleToFBE() = 68;
    void addUserKeyAuth(int userId, int serialNumber, in byte[] token, in byte[] secret) = 70;
    void fixateNewestUserKeyAuth(int userId) = 71;
    void fstrim(int flags) = 72;
    AppFuseMount mountProxyFileDescriptorBridge() = 73;
    ParcelFileDescriptor openProxyFileDescriptor(int mountPointId, int fileId, int mode) = 74;
    long getCacheQuotaBytes(String volumeUuid, int uid) = 75;
    long getCacheSizeBytes(String volumeUuid, int uid) = 76;
    long getAllocatableBytes(String volumeUuid, int flags, String callingPackage) = 77;
    void allocateBytes(String volumeUuid, long bytes, int flags, String callingPackage) = 78;
    void secdiscard(in String path) = 79;
}
