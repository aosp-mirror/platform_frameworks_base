/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server;

import android.content.pm.IPackageMoveObserver;
import android.os.IBinder;
import android.os.IProgressListener;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.storage.DiskInfo;
import android.os.storage.IObbActionListener;
import android.os.storage.IStorageEventListener;
import android.os.storage.IStorageManager;
import android.os.storage.IStorageShutdownObserver;
import android.os.storage.StorageVolume;
import android.os.storage.VolumeInfo;
import android.os.storage.VolumeRecord;
import android.util.ArrayMap;
import android.util.Pair;

import com.android.internal.os.AppFuseMount;

import junit.framework.AssertionFailedError;

import java.util.ArrayList;
import java.util.Arrays;

public class MockStorageManager implements IStorageManager {

    private ArrayMap<Integer, ArrayList<Pair<byte[], byte[]>>> mAuth = new ArrayMap<>();
    private boolean mIgnoreBadUnlock;

    @Override
    public void addUserKeyAuth(int userId, int serialNumber, byte[] token, byte[] secret)
            throws RemoteException {
        getUserAuth(userId).add(new Pair<>(token, secret));
    }

    @Override
    public void fixateNewestUserKeyAuth(int userId) throws RemoteException {
        ArrayList<Pair<byte[], byte[]>> auths = mAuth.get(userId);
        Pair<byte[], byte[]> latest = auths.get(auths.size() - 1);
        auths.clear();
        auths.add(latest);
    }

    private ArrayList<Pair<byte[], byte[]>> getUserAuth(int userId) {
        if (!mAuth.containsKey(userId)) {
            ArrayList<Pair<byte[], byte[]>> auths = new ArrayList<Pair<byte[], byte[]>>();
            auths.add(new Pair(null, null));
            mAuth.put(userId,  auths);
        }
        return mAuth.get(userId);
    }

    public byte[] getUserUnlockToken(int userId) {
        ArrayList<Pair<byte[], byte[]>> auths = getUserAuth(userId);
        if (auths.size() != 1) {
            throw new AssertionFailedError("More than one secret exists");
        }
        return auths.get(0).second;
    }

    public void unlockUser(int userId, byte[] secret, IProgressListener listener)
            throws RemoteException {
        listener.onStarted(userId, null);
        listener.onFinished(userId, null);
        ArrayList<Pair<byte[], byte[]>> auths = getUserAuth(userId);
        if (secret != null) {
            if (auths.size() > 1) {
                throw new AssertionFailedError("More than one secret exists");
            }
            Pair<byte[], byte[]> auth = auths.get(0);
            if ((!mIgnoreBadUnlock) && auth.second != null && !Arrays.equals(secret, auth.second)) {
                throw new AssertionFailedError("Invalid secret to unlock user");
            }
        } else {
            if (auths != null && auths.size() > 0) {
                throw new AssertionFailedError("Cannot unlock encrypted user with empty token");
            }
        }
    }

    public void setIgnoreBadUnlock(boolean ignore) {
        mIgnoreBadUnlock = ignore;
    }

    @Override
    public IBinder asBinder() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void registerListener(IStorageEventListener listener) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unregisterListener(IStorageEventListener listener) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isUsbMassStorageConnected() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setUsbMassStorageEnabled(boolean enable) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isUsbMassStorageEnabled() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int mountVolume(String mountPoint) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unmountVolume(String mountPoint, boolean force, boolean removeEncryption)
            throws RemoteException {
        throw new UnsupportedOperationException();

    }

    @Override
    public int formatVolume(String mountPoint) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int[] getStorageUsers(String path) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getVolumeState(String mountPoint) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int createSecureContainer(String id, int sizeMb, String fstype, String key, int ownerUid,
            boolean external) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int finalizeSecureContainer(String id) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int destroySecureContainer(String id, boolean force) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int mountSecureContainer(String id, String key, int ownerUid, boolean readOnly)
            throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int unmountSecureContainer(String id, boolean force) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isSecureContainerMounted(String id) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int renameSecureContainer(String oldId, String newId) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getSecureContainerPath(String id) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] getSecureContainerList() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void shutdown(IStorageShutdownObserver observer) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void finishMediaUpdate() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void mountObb(String rawPath, String canonicalPath, String key, IObbActionListener token,
            int nonce) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unmountObb(String rawPath, boolean force, IObbActionListener token, int nonce)
            throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isObbMounted(String rawPath) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getMountedObbPath(String rawPath) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isExternalStorageEmulated() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int decryptStorage(String password) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int encryptStorage(int type, String password) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int changeEncryptionPassword(int type, String password) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public StorageVolume[] getVolumeList(int uid, String packageName, int flags)
            throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getSecureContainerFilesystemPath(String cid) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getEncryptionState() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int verifyEncryptionPassword(String password) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int fixPermissionsSecureContainer(String id, int gid, String filename)
            throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int mkdirs(String callingPkg, String path) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getPasswordType() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getPassword() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clearPassword() throws RemoteException {
        throw new UnsupportedOperationException();

    }

    @Override
    public void setField(String field, String contents) throws RemoteException {
        throw new UnsupportedOperationException();

    }

    @Override
    public String getField(String field) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int resizeSecureContainer(String id, int sizeMb, String key) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public long lastMaintenance() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void runMaintenance() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void waitForAsecScan() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public DiskInfo[] getDisks() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public VolumeInfo[] getVolumes(int flags) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public VolumeRecord[] getVolumeRecords(int flags) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void mount(String volId) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unmount(String volId) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void format(String volId) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void partitionPublic(String diskId) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void partitionPrivate(String diskId) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void partitionMixed(String diskId, int ratio) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setVolumeNickname(String fsUuid, String nickname) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setVolumeUserFlags(String fsUuid, int flags, int mask) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void forgetVolume(String fsUuid) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void forgetAllVolumes() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getPrimaryStorageUuid() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setPrimaryStorageUuid(String volumeUuid, IPackageMoveObserver callback)
            throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public long benchmark(String volId) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setDebugFlags(int flags, int mask) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void createUserKey(int userId, int serialNumber, boolean ephemeral)
            throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void destroyUserKey(int userId) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unlockUserKey(int userId, int serialNumber, byte[] token, byte[] secret)
            throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void lockUserKey(int userId) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isUserKeyUnlocked(int userId) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void prepareUserStorage(String volumeUuid, int userId, int serialNumber, int flags)
            throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void destroyUserStorage(String volumeUuid, int userId, int flags)
            throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isConvertibleToFBE() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void fstrim(int flags) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public AppFuseMount mountProxyFileDescriptorBridge() throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public ParcelFileDescriptor openProxyFileDescriptor(int mountPointId, int fileId, int mode)
            throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getCacheQuotaBytes(String volumeUuid, int uid) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getCacheSizeBytes(String volumeUuid, int uid) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getAllocatableBytes(String path, int flags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void allocateBytes(String path, long bytes, int flags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void secdiscard(String path) throws RemoteException {
        throw new UnsupportedOperationException();
    }

}
