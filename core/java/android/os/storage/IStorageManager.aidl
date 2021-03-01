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
import android.content.res.ObbInfo;
import android.os.IVoldTaskListener;
import android.os.ParcelFileDescriptor;
import android.os.storage.DiskInfo;
import android.os.storage.IStorageEventListener;
import android.os.storage.IStorageShutdownObserver;
import android.os.storage.IObbActionListener;
import android.os.storage.StorageVolume;
import android.os.storage.VolumeInfo;
import android.os.storage.VolumeRecord;
import com.android.internal.os.AppFuseMount;
import android.app.PendingIntent;


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
     * Shuts down the StorageManagerService and gracefully unmounts all external media.
     * Invokes call back once the shutdown is complete.
     */
    void shutdown(IStorageShutdownObserver observer) = 19;
    /**
     * Mounts an Opaque Binary Blob (OBB) with the specified decryption key and
     * only allows the calling process's UID access to the contents.
     * StorageManagerService will call back to the supplied IObbActionListener to inform
     * it of the terminal state of the call.
     */
    void mountObb(in String rawPath, in String canonicalPath, in String key,
            IObbActionListener token, int nonce, in ObbInfo obbInfo) = 21;
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
    /**
     * Ensure that all directories along given path exist, creating parent
     * directories as needed. Validates that given path is absolute and that it
     * contains no relative "." or ".." paths or symlinks. Also ensures that
     * path belongs to a volume managed by vold, and that path is either
     * external storage data or OBB directory belonging to calling app.
     */
    void mkdirs(in String callingPkg, in String path) = 34;
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
    void benchmark(in String volId, IVoldTaskListener listener) = 59;
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
    void fstrim(int flags, IVoldTaskListener listener) = 72;
    AppFuseMount mountProxyFileDescriptorBridge() = 73;
    ParcelFileDescriptor openProxyFileDescriptor(int mountPointId, int fileId, int mode) = 74;
    long getCacheQuotaBytes(String volumeUuid, int uid) = 75;
    long getCacheSizeBytes(String volumeUuid, int uid) = 76;
    long getAllocatableBytes(String volumeUuid, int flags, String callingPackage) = 77;
    void allocateBytes(String volumeUuid, long bytes, int flags, String callingPackage) = 78;
    void runIdleMaintenance() = 79;
    void abortIdleMaintenance() = 80;
    void commitChanges() = 83;
    boolean supportsCheckpoint() = 84;
    void startCheckpoint(int numTries) = 85;
    boolean needsCheckpoint() = 86;
    void abortChanges(in String message, boolean retry) = 87;
    void clearUserKeyAuth(int userId, int serialNumber, in byte[] token, in byte[] secret) = 88;
    void fixupAppDir(in String path) = 89;
    void disableAppDataIsolation(in String pkgName, int pid, int userId) = 90;
    PendingIntent getManageSpaceActivityIntent(in String packageName, int requestCode) = 91;
    void notifyAppIoBlocked(in String volumeUuid, int uid, int tid, int reason) = 92;
    void notifyAppIoResumed(in String volumeUuid, int uid, int tid, int reason) = 93;
}
