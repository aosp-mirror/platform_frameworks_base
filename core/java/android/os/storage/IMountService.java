/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.os.storage;

import android.content.pm.IPackageMoveObserver;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * WARNING! Update IMountService.h and IMountService.cpp if you change this
 * file. In particular, the ordering of the methods below must match the
 * _TRANSACTION enum in IMountService.cpp
 *
 * @hide - Applications should use android.os.storage.StorageManager to access
 *       storage functions.
 */
public interface IMountService extends IInterface {
    /** Local-side IPC implementation stub class. */
    public static abstract class Stub extends Binder implements IMountService {
        private static class Proxy implements IMountService {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            public IBinder asBinder() {
                return mRemote;
            }

            public String getInterfaceDescriptor() {
                return DESCRIPTOR;
            }

            /**
             * Registers an IMountServiceListener for receiving async
             * notifications.
             */
            public void registerListener(IMountServiceListener listener) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeStrongBinder((listener != null ? listener.asBinder() : null));
                    mRemote.transact(Stub.TRANSACTION_registerListener, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            /**
             * Unregisters an IMountServiceListener
             */
            public void unregisterListener(IMountServiceListener listener) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeStrongBinder((listener != null ? listener.asBinder() : null));
                    mRemote.transact(Stub.TRANSACTION_unregisterListener, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            /**
             * Returns true if a USB mass storage host is connected
             */
            public boolean isUsbMassStorageConnected() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                boolean _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_isUsbMassStorageConnected, _data, _reply, 0);
                    _reply.readException();
                    _result = 0 != _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            /**
             * Enables / disables USB mass storage. The caller should check
             * actual status of enabling/disabling USB mass storage via
             * StorageEventListener.
             */
            public void setUsbMassStorageEnabled(boolean enable) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt((enable ? 1 : 0));
                    mRemote.transact(Stub.TRANSACTION_setUsbMassStorageEnabled, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            /**
             * Returns true if a USB mass storage host is enabled (media is
             * shared)
             */
            public boolean isUsbMassStorageEnabled() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                boolean _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_isUsbMassStorageEnabled, _data, _reply, 0);
                    _reply.readException();
                    _result = 0 != _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            /**
             * Mount external storage at given mount point. Returns an int
             * consistent with MountServiceResultCode
             */
            public int mountVolume(String mountPoint) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(mountPoint);
                    mRemote.transact(Stub.TRANSACTION_mountVolume, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            /**
             * Safely unmount external storage at given mount point. The unmount
             * is an asynchronous operation. Applications should register
             * StorageEventListener for storage related status changes.
             */
            public void unmountVolume(String mountPoint, boolean force, boolean removeEncryption)
                    throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(mountPoint);
                    _data.writeInt((force ? 1 : 0));
                    _data.writeInt((removeEncryption ? 1 : 0));
                    mRemote.transact(Stub.TRANSACTION_unmountVolume, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            /**
             * Format external storage given a mount point. Returns an int
             * consistent with MountServiceResultCode
             */
            public int formatVolume(String mountPoint) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(mountPoint);
                    mRemote.transact(Stub.TRANSACTION_formatVolume, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            /**
             * Returns an array of pids with open files on the specified path.
             */
            public int[] getStorageUsers(String path) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int[] _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(path);
                    mRemote.transact(Stub.TRANSACTION_getStorageUsers, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.createIntArray();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            /**
             * Gets the state of a volume via its mountpoint.
             */
            public String getVolumeState(String mountPoint) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                String _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(mountPoint);
                    mRemote.transact(Stub.TRANSACTION_getVolumeState, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readString();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            /*
             * Creates a secure container with the specified parameters. Returns
             * an int consistent with MountServiceResultCode
             */
            public int createSecureContainer(String id, int sizeMb, String fstype, String key,
                    int ownerUid, boolean external) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(id);
                    _data.writeInt(sizeMb);
                    _data.writeString(fstype);
                    _data.writeString(key);
                    _data.writeInt(ownerUid);
                    _data.writeInt(external ? 1 : 0);
                    mRemote.transact(Stub.TRANSACTION_createSecureContainer, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            /*
             * Destroy a secure container, and free up all resources associated
             * with it. NOTE: Ensure all references are released prior to
             * deleting. Returns an int consistent with MountServiceResultCode
             */
            public int destroySecureContainer(String id, boolean force) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(id);
                    _data.writeInt((force ? 1 : 0));
                    mRemote.transact(Stub.TRANSACTION_destroySecureContainer, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            /*
             * Finalize a container which has just been created and populated.
             * After finalization, the container is immutable. Returns an int
             * consistent with MountServiceResultCode
             */
            public int finalizeSecureContainer(String id) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(id);
                    mRemote.transact(Stub.TRANSACTION_finalizeSecureContainer, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            /*
             * Mount a secure container with the specified key and owner UID.
             * Returns an int consistent with MountServiceResultCode
             */
            public int mountSecureContainer(String id, String key, int ownerUid, boolean readOnly)
                    throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(id);
                    _data.writeString(key);
                    _data.writeInt(ownerUid);
                    _data.writeInt(readOnly ? 1 : 0);
                    mRemote.transact(Stub.TRANSACTION_mountSecureContainer, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            /*
             * Unount a secure container. Returns an int consistent with
             * MountServiceResultCode
             */
            public int unmountSecureContainer(String id, boolean force) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(id);
                    _data.writeInt((force ? 1 : 0));
                    mRemote.transact(Stub.TRANSACTION_unmountSecureContainer, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            /*
             * Returns true if the specified container is mounted
             */
            public boolean isSecureContainerMounted(String id) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                boolean _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(id);
                    mRemote.transact(Stub.TRANSACTION_isSecureContainerMounted, _data, _reply, 0);
                    _reply.readException();
                    _result = 0 != _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            /*
             * Rename an unmounted secure container. Returns an int consistent
             * with MountServiceResultCode
             */
            public int renameSecureContainer(String oldId, String newId) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(oldId);
                    _data.writeString(newId);
                    mRemote.transact(Stub.TRANSACTION_renameSecureContainer, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            /*
             * Returns the filesystem path of a mounted secure container.
             */
            public String getSecureContainerPath(String id) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                String _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(id);
                    mRemote.transact(Stub.TRANSACTION_getSecureContainerPath, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readString();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            /**
             * Gets an Array of currently known secure container IDs
             */
            public String[] getSecureContainerList() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                String[] _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_getSecureContainerList, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.createStringArray();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            /**
             * Shuts down the MountService and gracefully unmounts all external
             * media. Invokes call back once the shutdown is complete.
             */
            public void shutdown(IMountShutdownObserver observer)
                    throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeStrongBinder((observer != null ? observer.asBinder() : null));
                    mRemote.transact(Stub.TRANSACTION_shutdown, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            /**
             * Call into MountService by PackageManager to notify that its done
             * processing the media status update request.
             */
            public void finishMediaUpdate() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_finishMediaUpdate, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            /**
             * Mounts an Opaque Binary Blob (OBB) with the specified decryption
             * key and only allows the calling process's UID access to the
             * contents. MountService will call back to the supplied
             * IObbActionListener to inform it of the terminal state of the
             * call.
             */
            public void mountObb(String rawPath, String canonicalPath, String key,
                    IObbActionListener token, int nonce) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(rawPath);
                    _data.writeString(canonicalPath);
                    _data.writeString(key);
                    _data.writeStrongBinder((token != null ? token.asBinder() : null));
                    _data.writeInt(nonce);
                    mRemote.transact(Stub.TRANSACTION_mountObb, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            /**
             * Unmounts an Opaque Binary Blob (OBB). When the force flag is
             * specified, any program using it will be forcibly killed to
             * unmount the image. MountService will call back to the supplied
             * IObbActionListener to inform it of the terminal state of the
             * call.
             */
            public void unmountObb(
                    String rawPath, boolean force, IObbActionListener token, int nonce)
                    throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(rawPath);
                    _data.writeInt((force ? 1 : 0));
                    _data.writeStrongBinder((token != null ? token.asBinder() : null));
                    _data.writeInt(nonce);
                    mRemote.transact(Stub.TRANSACTION_unmountObb, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            /**
             * Checks whether the specified Opaque Binary Blob (OBB) is mounted
             * somewhere.
             */
            public boolean isObbMounted(String rawPath) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                boolean _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(rawPath);
                    mRemote.transact(Stub.TRANSACTION_isObbMounted, _data, _reply, 0);
                    _reply.readException();
                    _result = 0 != _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            /**
             * Gets the path to the mounted Opaque Binary Blob (OBB).
             */
            public String getMountedObbPath(String rawPath) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                String _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(rawPath);
                    mRemote.transact(Stub.TRANSACTION_getMountedObbPath, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readString();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            /**
             * Returns whether the external storage is emulated.
             */
            public boolean isExternalStorageEmulated() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                boolean _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_isExternalStorageEmulated, _data, _reply, 0);
                    _reply.readException();
                    _result = 0 != _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public int getEncryptionState() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_getEncryptionState, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public int decryptStorage(String password) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(password);
                    mRemote.transact(Stub.TRANSACTION_decryptStorage, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public int encryptStorage(int type, String password) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(type);
                    _data.writeString(password);
                    mRemote.transact(Stub.TRANSACTION_encryptStorage, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public int changeEncryptionPassword(int type, String password) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(type);
                    _data.writeString(password);
                    mRemote.transact(Stub.TRANSACTION_changeEncryptionPassword, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int verifyEncryptionPassword(String password) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(password);
                    mRemote.transact(Stub.TRANSACTION_verifyEncryptionPassword, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public int getPasswordType() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_getPasswordType, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public String getPassword() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                String _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_getPassword, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readString();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public void clearPassword() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_clearPassword, _data, _reply, IBinder.FLAG_ONEWAY);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            public void setField(String field, String data) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(field);
                    _data.writeString(data);
                    mRemote.transact(Stub.TRANSACTION_setField, _data, _reply, IBinder.FLAG_ONEWAY);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            public String getField(String field) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                String _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(field);
                    mRemote.transact(Stub.TRANSACTION_getField, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readString();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public StorageVolume[] getVolumeList(int uid, String packageName, int flags)
                    throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                StorageVolume[] _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(uid);
                    _data.writeString(packageName);
                    _data.writeInt(flags);
                    mRemote.transact(Stub.TRANSACTION_getVolumeList, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.createTypedArray(StorageVolume.CREATOR);
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            /*
             * Returns the filesystem path of a mounted secure container.
             */
            public String getSecureContainerFilesystemPath(String id) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                String _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(id);
                    mRemote.transact(Stub.TRANSACTION_getSecureContainerFilesystemPath, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readString();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            /**
             * Fix permissions in a container which has just been created and
             * populated. Returns an int consistent with MountServiceResultCode
             */
            public int fixPermissionsSecureContainer(String id, int gid, String filename)
                    throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(id);
                    _data.writeInt(gid);
                    _data.writeString(filename);
                    mRemote.transact(Stub.TRANSACTION_fixPermissionsSecureContainer, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int mkdirs(String callingPkg, String path) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(callingPkg);
                    _data.writeString(path);
                    mRemote.transact(Stub.TRANSACTION_mkdirs, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int resizeSecureContainer(String id, int sizeMb, String key)
                    throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(id);
                    _data.writeInt(sizeMb);
                    _data.writeString(key);
                    mRemote.transact(Stub.TRANSACTION_resizeSecureContainer, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public long lastMaintenance() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                long _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_lastMaintenance, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readLong();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public void runMaintenance() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_runMaintenance, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return;
            }

            @Override
            public void waitForAsecScan() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_waitForAsecScan, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return;
            }

            @Override
            public DiskInfo[] getDisks() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                DiskInfo[] _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_getDisks, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.createTypedArray(DiskInfo.CREATOR);
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public VolumeInfo[] getVolumes(int _flags) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                VolumeInfo[] _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(_flags);
                    mRemote.transact(Stub.TRANSACTION_getVolumes, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.createTypedArray(VolumeInfo.CREATOR);
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public VolumeRecord[] getVolumeRecords(int _flags) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                VolumeRecord[] _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(_flags);
                    mRemote.transact(Stub.TRANSACTION_getVolumeRecords, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.createTypedArray(VolumeRecord.CREATOR);
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public void mount(String volId) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(volId);
                    mRemote.transact(Stub.TRANSACTION_mount, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void unmount(String volId) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(volId);
                    mRemote.transact(Stub.TRANSACTION_unmount, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void format(String volId) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(volId);
                    mRemote.transact(Stub.TRANSACTION_format, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public long benchmark(String volId) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(volId);
                    mRemote.transact(Stub.TRANSACTION_benchmark, _data, _reply, 0);
                    _reply.readException();
                    return _reply.readLong();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void partitionPublic(String diskId) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(diskId);
                    mRemote.transact(Stub.TRANSACTION_partitionPublic, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void partitionPrivate(String diskId) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(diskId);
                    mRemote.transact(Stub.TRANSACTION_partitionPrivate, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void partitionMixed(String diskId, int ratio) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(diskId);
                    _data.writeInt(ratio);
                    mRemote.transact(Stub.TRANSACTION_partitionMixed, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void setVolumeNickname(String fsUuid, String nickname) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(fsUuid);
                    _data.writeString(nickname);
                    mRemote.transact(Stub.TRANSACTION_setVolumeNickname, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void setVolumeUserFlags(String fsUuid, int flags, int mask) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(fsUuid);
                    _data.writeInt(flags);
                    _data.writeInt(mask);
                    mRemote.transact(Stub.TRANSACTION_setVolumeUserFlags, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void forgetVolume(String fsUuid) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(fsUuid);
                    mRemote.transact(Stub.TRANSACTION_forgetVolume, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void forgetAllVolumes() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_forgetAllVolumes, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void setDebugFlags(int _flags, int _mask) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(_flags);
                    _data.writeInt(_mask);
                    mRemote.transact(Stub.TRANSACTION_setDebugFlags, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public String getPrimaryStorageUuid() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                String _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_getPrimaryStorageUuid, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readString();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public void setPrimaryStorageUuid(String volumeUuid, IPackageMoveObserver callback)
                    throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(volumeUuid);
                    _data.writeStrongBinder((callback != null ? callback.asBinder() : null));
                    mRemote.transact(Stub.TRANSACTION_setPrimaryStorageUuid, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }
        }

        private static final String DESCRIPTOR = "IMountService";

        static final int TRANSACTION_registerListener = IBinder.FIRST_CALL_TRANSACTION + 0;

        static final int TRANSACTION_unregisterListener = IBinder.FIRST_CALL_TRANSACTION + 1;

        static final int TRANSACTION_isUsbMassStorageConnected = IBinder.FIRST_CALL_TRANSACTION + 2;

        static final int TRANSACTION_setUsbMassStorageEnabled = IBinder.FIRST_CALL_TRANSACTION + 3;

        static final int TRANSACTION_isUsbMassStorageEnabled = IBinder.FIRST_CALL_TRANSACTION + 4;

        static final int TRANSACTION_mountVolume = IBinder.FIRST_CALL_TRANSACTION + 5;

        static final int TRANSACTION_unmountVolume = IBinder.FIRST_CALL_TRANSACTION + 6;

        static final int TRANSACTION_formatVolume = IBinder.FIRST_CALL_TRANSACTION + 7;

        static final int TRANSACTION_getStorageUsers = IBinder.FIRST_CALL_TRANSACTION + 8;

        static final int TRANSACTION_getVolumeState = IBinder.FIRST_CALL_TRANSACTION + 9;

        static final int TRANSACTION_createSecureContainer = IBinder.FIRST_CALL_TRANSACTION + 10;

        static final int TRANSACTION_finalizeSecureContainer = IBinder.FIRST_CALL_TRANSACTION + 11;

        static final int TRANSACTION_destroySecureContainer = IBinder.FIRST_CALL_TRANSACTION + 12;

        static final int TRANSACTION_mountSecureContainer = IBinder.FIRST_CALL_TRANSACTION + 13;

        static final int TRANSACTION_unmountSecureContainer = IBinder.FIRST_CALL_TRANSACTION + 14;

        static final int TRANSACTION_isSecureContainerMounted = IBinder.FIRST_CALL_TRANSACTION + 15;

        static final int TRANSACTION_renameSecureContainer = IBinder.FIRST_CALL_TRANSACTION + 16;

        static final int TRANSACTION_getSecureContainerPath = IBinder.FIRST_CALL_TRANSACTION + 17;

        static final int TRANSACTION_getSecureContainerList = IBinder.FIRST_CALL_TRANSACTION + 18;

        static final int TRANSACTION_shutdown = IBinder.FIRST_CALL_TRANSACTION + 19;

        static final int TRANSACTION_finishMediaUpdate = IBinder.FIRST_CALL_TRANSACTION + 20;

        static final int TRANSACTION_mountObb = IBinder.FIRST_CALL_TRANSACTION + 21;

        static final int TRANSACTION_unmountObb = IBinder.FIRST_CALL_TRANSACTION + 22;

        static final int TRANSACTION_isObbMounted = IBinder.FIRST_CALL_TRANSACTION + 23;

        static final int TRANSACTION_getMountedObbPath = IBinder.FIRST_CALL_TRANSACTION + 24;

        static final int TRANSACTION_isExternalStorageEmulated = IBinder.FIRST_CALL_TRANSACTION + 25;

        static final int TRANSACTION_decryptStorage = IBinder.FIRST_CALL_TRANSACTION + 26;

        static final int TRANSACTION_encryptStorage = IBinder.FIRST_CALL_TRANSACTION + 27;

        static final int TRANSACTION_changeEncryptionPassword = IBinder.FIRST_CALL_TRANSACTION + 28;

        static final int TRANSACTION_getVolumeList = IBinder.FIRST_CALL_TRANSACTION + 29;

        static final int TRANSACTION_getSecureContainerFilesystemPath = IBinder.FIRST_CALL_TRANSACTION + 30;

        static final int TRANSACTION_getEncryptionState = IBinder.FIRST_CALL_TRANSACTION + 31;

        static final int TRANSACTION_verifyEncryptionPassword = IBinder.FIRST_CALL_TRANSACTION + 32;

        static final int TRANSACTION_fixPermissionsSecureContainer = IBinder.FIRST_CALL_TRANSACTION + 33;

        static final int TRANSACTION_mkdirs = IBinder.FIRST_CALL_TRANSACTION + 34;

        static final int TRANSACTION_getPasswordType = IBinder.FIRST_CALL_TRANSACTION + 35;

        static final int TRANSACTION_getPassword = IBinder.FIRST_CALL_TRANSACTION + 36;

        static final int TRANSACTION_clearPassword = IBinder.FIRST_CALL_TRANSACTION + 37;

        static final int TRANSACTION_setField = IBinder.FIRST_CALL_TRANSACTION + 38;

        static final int TRANSACTION_getField = IBinder.FIRST_CALL_TRANSACTION + 39;

        static final int TRANSACTION_resizeSecureContainer = IBinder.FIRST_CALL_TRANSACTION + 40;

        static final int TRANSACTION_lastMaintenance = IBinder.FIRST_CALL_TRANSACTION + 41;

        static final int TRANSACTION_runMaintenance = IBinder.FIRST_CALL_TRANSACTION + 42;

        static final int TRANSACTION_waitForAsecScan = IBinder.FIRST_CALL_TRANSACTION + 43;

        static final int TRANSACTION_getDisks = IBinder.FIRST_CALL_TRANSACTION + 44;
        static final int TRANSACTION_getVolumes = IBinder.FIRST_CALL_TRANSACTION + 45;
        static final int TRANSACTION_getVolumeRecords = IBinder.FIRST_CALL_TRANSACTION + 46;

        static final int TRANSACTION_mount = IBinder.FIRST_CALL_TRANSACTION + 47;
        static final int TRANSACTION_unmount = IBinder.FIRST_CALL_TRANSACTION + 48;
        static final int TRANSACTION_format = IBinder.FIRST_CALL_TRANSACTION + 49;

        static final int TRANSACTION_partitionPublic = IBinder.FIRST_CALL_TRANSACTION + 50;
        static final int TRANSACTION_partitionPrivate = IBinder.FIRST_CALL_TRANSACTION + 51;
        static final int TRANSACTION_partitionMixed = IBinder.FIRST_CALL_TRANSACTION + 52;

        static final int TRANSACTION_setVolumeNickname = IBinder.FIRST_CALL_TRANSACTION + 53;
        static final int TRANSACTION_setVolumeUserFlags = IBinder.FIRST_CALL_TRANSACTION + 54;
        static final int TRANSACTION_forgetVolume = IBinder.FIRST_CALL_TRANSACTION + 55;
        static final int TRANSACTION_forgetAllVolumes = IBinder.FIRST_CALL_TRANSACTION + 56;

        static final int TRANSACTION_getPrimaryStorageUuid = IBinder.FIRST_CALL_TRANSACTION + 57;
        static final int TRANSACTION_setPrimaryStorageUuid = IBinder.FIRST_CALL_TRANSACTION + 58;

        static final int TRANSACTION_benchmark = IBinder.FIRST_CALL_TRANSACTION + 59;
        static final int TRANSACTION_setDebugFlags = IBinder.FIRST_CALL_TRANSACTION + 60;

        /**
         * Cast an IBinder object into an IMountService interface, generating a
         * proxy if needed.
         */
        public static IMountService asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin != null && iin instanceof IMountService) {
                return (IMountService) iin;
            }
            return new IMountService.Stub.Proxy(obj);
        }

        /** Construct the stub at attach it to the interface. */
        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply,
                int flags) throws RemoteException {
            switch (code) {
                case INTERFACE_TRANSACTION: {
                    reply.writeString(DESCRIPTOR);
                    return true;
                }
                case TRANSACTION_registerListener: {
                    data.enforceInterface(DESCRIPTOR);
                    IMountServiceListener listener;
                    listener = IMountServiceListener.Stub.asInterface(data.readStrongBinder());
                    registerListener(listener);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_unregisterListener: {
                    data.enforceInterface(DESCRIPTOR);
                    IMountServiceListener listener;
                    listener = IMountServiceListener.Stub.asInterface(data.readStrongBinder());
                    unregisterListener(listener);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_isUsbMassStorageConnected: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean result = isUsbMassStorageConnected();
                    reply.writeNoException();
                    reply.writeInt((result ? 1 : 0));
                    return true;
                }
                case TRANSACTION_setUsbMassStorageEnabled: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean enable;
                    enable = 0 != data.readInt();
                    setUsbMassStorageEnabled(enable);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_isUsbMassStorageEnabled: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean result = isUsbMassStorageEnabled();
                    reply.writeNoException();
                    reply.writeInt((result ? 1 : 0));
                    return true;
                }
                case TRANSACTION_mountVolume: {
                    data.enforceInterface(DESCRIPTOR);
                    String mountPoint;
                    mountPoint = data.readString();
                    int resultCode = mountVolume(mountPoint);
                    reply.writeNoException();
                    reply.writeInt(resultCode);
                    return true;
                }
                case TRANSACTION_unmountVolume: {
                    data.enforceInterface(DESCRIPTOR);
                    String mountPoint;
                    mountPoint = data.readString();
                    boolean force = 0 != data.readInt();
                    boolean removeEncrypt = 0 != data.readInt();
                    unmountVolume(mountPoint, force, removeEncrypt);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_formatVolume: {
                    data.enforceInterface(DESCRIPTOR);
                    String mountPoint;
                    mountPoint = data.readString();
                    int result = formatVolume(mountPoint);
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_getStorageUsers: {
                    data.enforceInterface(DESCRIPTOR);
                    String path;
                    path = data.readString();
                    int[] pids = getStorageUsers(path);
                    reply.writeNoException();
                    reply.writeIntArray(pids);
                    return true;
                }
                case TRANSACTION_getVolumeState: {
                    data.enforceInterface(DESCRIPTOR);
                    String mountPoint;
                    mountPoint = data.readString();
                    String state = getVolumeState(mountPoint);
                    reply.writeNoException();
                    reply.writeString(state);
                    return true;
                }
                case TRANSACTION_createSecureContainer: {
                    data.enforceInterface(DESCRIPTOR);
                    String id;
                    id = data.readString();
                    int sizeMb;
                    sizeMb = data.readInt();
                    String fstype;
                    fstype = data.readString();
                    String key;
                    key = data.readString();
                    int ownerUid;
                    ownerUid = data.readInt();
                    boolean external;
                    external = 0 != data.readInt();
                    int resultCode = createSecureContainer(id, sizeMb, fstype, key, ownerUid,
                            external);
                    reply.writeNoException();
                    reply.writeInt(resultCode);
                    return true;
                }
                case TRANSACTION_finalizeSecureContainer: {
                    data.enforceInterface(DESCRIPTOR);
                    String id;
                    id = data.readString();
                    int resultCode = finalizeSecureContainer(id);
                    reply.writeNoException();
                    reply.writeInt(resultCode);
                    return true;
                }
                case TRANSACTION_destroySecureContainer: {
                    data.enforceInterface(DESCRIPTOR);
                    String id;
                    id = data.readString();
                    boolean force;
                    force = 0 != data.readInt();
                    int resultCode = destroySecureContainer(id, force);
                    reply.writeNoException();
                    reply.writeInt(resultCode);
                    return true;
                }
                case TRANSACTION_mountSecureContainer: {
                    data.enforceInterface(DESCRIPTOR);
                    String id;
                    id = data.readString();
                    String key;
                    key = data.readString();
                    int ownerUid;
                    ownerUid = data.readInt();
                    boolean readOnly;
                    readOnly = data.readInt() != 0;
                    int resultCode = mountSecureContainer(id, key, ownerUid, readOnly);
                    reply.writeNoException();
                    reply.writeInt(resultCode);
                    return true;
                }
                case TRANSACTION_unmountSecureContainer: {
                    data.enforceInterface(DESCRIPTOR);
                    String id;
                    id = data.readString();
                    boolean force;
                    force = 0 != data.readInt();
                    int resultCode = unmountSecureContainer(id, force);
                    reply.writeNoException();
                    reply.writeInt(resultCode);
                    return true;
                }
                case TRANSACTION_isSecureContainerMounted: {
                    data.enforceInterface(DESCRIPTOR);
                    String id;
                    id = data.readString();
                    boolean status = isSecureContainerMounted(id);
                    reply.writeNoException();
                    reply.writeInt((status ? 1 : 0));
                    return true;
                }
                case TRANSACTION_renameSecureContainer: {
                    data.enforceInterface(DESCRIPTOR);
                    String oldId;
                    oldId = data.readString();
                    String newId;
                    newId = data.readString();
                    int resultCode = renameSecureContainer(oldId, newId);
                    reply.writeNoException();
                    reply.writeInt(resultCode);
                    return true;
                }
                case TRANSACTION_getSecureContainerPath: {
                    data.enforceInterface(DESCRIPTOR);
                    String id;
                    id = data.readString();
                    String path = getSecureContainerPath(id);
                    reply.writeNoException();
                    reply.writeString(path);
                    return true;
                }
                case TRANSACTION_getSecureContainerList: {
                    data.enforceInterface(DESCRIPTOR);
                    String[] ids = getSecureContainerList();
                    reply.writeNoException();
                    reply.writeStringArray(ids);
                    return true;
                }
                case TRANSACTION_shutdown: {
                    data.enforceInterface(DESCRIPTOR);
                    IMountShutdownObserver observer;
                    observer = IMountShutdownObserver.Stub.asInterface(data
                            .readStrongBinder());
                    shutdown(observer);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_finishMediaUpdate: {
                    data.enforceInterface(DESCRIPTOR);
                    finishMediaUpdate();
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_mountObb: {
                    data.enforceInterface(DESCRIPTOR);
                    final String rawPath = data.readString();
                    final String canonicalPath = data.readString();
                    final String key = data.readString();
                    IObbActionListener observer;
                    observer = IObbActionListener.Stub.asInterface(data.readStrongBinder());
                    int nonce;
                    nonce = data.readInt();
                    mountObb(rawPath, canonicalPath, key, observer, nonce);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_unmountObb: {
                    data.enforceInterface(DESCRIPTOR);
                    String filename;
                    filename = data.readString();
                    boolean force;
                    force = 0 != data.readInt();
                    IObbActionListener observer;
                    observer = IObbActionListener.Stub.asInterface(data.readStrongBinder());
                    int nonce;
                    nonce = data.readInt();
                    unmountObb(filename, force, observer, nonce);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_isObbMounted: {
                    data.enforceInterface(DESCRIPTOR);
                    String filename;
                    filename = data.readString();
                    boolean status = isObbMounted(filename);
                    reply.writeNoException();
                    reply.writeInt((status ? 1 : 0));
                    return true;
                }
                case TRANSACTION_getMountedObbPath: {
                    data.enforceInterface(DESCRIPTOR);
                    String filename;
                    filename = data.readString();
                    String mountedPath = getMountedObbPath(filename);
                    reply.writeNoException();
                    reply.writeString(mountedPath);
                    return true;
                }
                case TRANSACTION_isExternalStorageEmulated: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean emulated = isExternalStorageEmulated();
                    reply.writeNoException();
                    reply.writeInt(emulated ? 1 : 0);
                    return true;
                }
                case TRANSACTION_decryptStorage: {
                    data.enforceInterface(DESCRIPTOR);
                    String password = data.readString();
                    int result = decryptStorage(password);
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_encryptStorage: {
                    data.enforceInterface(DESCRIPTOR);
                    int type = data.readInt();
                    String password = data.readString();
                    int result = encryptStorage(type, password);
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_changeEncryptionPassword: {
                    data.enforceInterface(DESCRIPTOR);
                    int type = data.readInt();
                    String password = data.readString();
                    int result = changeEncryptionPassword(type, password);
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_getVolumeList: {
                    data.enforceInterface(DESCRIPTOR);
                    int uid = data.readInt();
                    String packageName = data.readString();
                    int _flags = data.readInt();
                    StorageVolume[] result = getVolumeList(uid, packageName, _flags);
                    reply.writeNoException();
                    reply.writeTypedArray(result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                    return true;
                }
                case TRANSACTION_getSecureContainerFilesystemPath: {
                    data.enforceInterface(DESCRIPTOR);
                    String id;
                    id = data.readString();
                    String path = getSecureContainerFilesystemPath(id);
                    reply.writeNoException();
                    reply.writeString(path);
                    return true;
                }
                case TRANSACTION_getEncryptionState: {
                    data.enforceInterface(DESCRIPTOR);
                    int result = getEncryptionState();
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_fixPermissionsSecureContainer: {
                    data.enforceInterface(DESCRIPTOR);
                    String id;
                    id = data.readString();
                    int gid;
                    gid = data.readInt();
                    String filename;
                    filename = data.readString();
                    int resultCode = fixPermissionsSecureContainer(id, gid, filename);
                    reply.writeNoException();
                    reply.writeInt(resultCode);
                    return true;
                }
                case TRANSACTION_mkdirs: {
                    data.enforceInterface(DESCRIPTOR);
                    String callingPkg = data.readString();
                    String path = data.readString();
                    int result = mkdirs(callingPkg, path);
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_getPasswordType: {
                    data.enforceInterface(DESCRIPTOR);
                    int result = getPasswordType();
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_getPassword: {
                    data.enforceInterface(DESCRIPTOR);
                    String result = getPassword();
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                case TRANSACTION_clearPassword: {
                    data.enforceInterface(DESCRIPTOR);
                    clearPassword();
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_setField: {
                    data.enforceInterface(DESCRIPTOR);
                    String field = data.readString();
                    String contents = data.readString();
                    setField(field, contents);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_getField: {
                    data.enforceInterface(DESCRIPTOR);
                    String field = data.readString();
                    String contents = getField(field);
                    reply.writeNoException();
                    reply.writeString(contents);
                    return true;
                }
                case TRANSACTION_resizeSecureContainer: {
                    data.enforceInterface(DESCRIPTOR);
                    String id;
                    id = data.readString();
                    int sizeMb;
                    sizeMb = data.readInt();
                    String key;
                    key = data.readString();
                    int resultCode = resizeSecureContainer(id, sizeMb, key);
                    reply.writeNoException();
                    reply.writeInt(resultCode);
                    return true;
                }
                case TRANSACTION_lastMaintenance: {
                    data.enforceInterface(DESCRIPTOR);
                    long lastMaintenance = lastMaintenance();
                    reply.writeNoException();
                    reply.writeLong(lastMaintenance);
                    return true;
                }
                case TRANSACTION_runMaintenance: {
                    data.enforceInterface(DESCRIPTOR);
                    runMaintenance();
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_waitForAsecScan: {
                    data.enforceInterface(DESCRIPTOR);
                    waitForAsecScan();
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_getDisks: {
                    data.enforceInterface(DESCRIPTOR);
                    DiskInfo[] disks = getDisks();
                    reply.writeNoException();
                    reply.writeTypedArray(disks, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                    return true;
                }
                case TRANSACTION_getVolumes: {
                    data.enforceInterface(DESCRIPTOR);
                    int _flags = data.readInt();
                    VolumeInfo[] volumes = getVolumes(_flags);
                    reply.writeNoException();
                    reply.writeTypedArray(volumes, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                    return true;
                }
                case TRANSACTION_getVolumeRecords: {
                    data.enforceInterface(DESCRIPTOR);
                    int _flags = data.readInt();
                    VolumeRecord[] volumes = getVolumeRecords(_flags);
                    reply.writeNoException();
                    reply.writeTypedArray(volumes, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                    return true;
                }
                case TRANSACTION_mount: {
                    data.enforceInterface(DESCRIPTOR);
                    String volId = data.readString();
                    mount(volId);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_unmount: {
                    data.enforceInterface(DESCRIPTOR);
                    String volId = data.readString();
                    unmount(volId);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_format: {
                    data.enforceInterface(DESCRIPTOR);
                    String volId = data.readString();
                    format(volId);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_benchmark: {
                    data.enforceInterface(DESCRIPTOR);
                    String volId = data.readString();
                    long res = benchmark(volId);
                    reply.writeNoException();
                    reply.writeLong(res);
                    return true;
                }
                case TRANSACTION_partitionPublic: {
                    data.enforceInterface(DESCRIPTOR);
                    String diskId = data.readString();
                    partitionPublic(diskId);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_partitionPrivate: {
                    data.enforceInterface(DESCRIPTOR);
                    String diskId = data.readString();
                    partitionPrivate(diskId);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_partitionMixed: {
                    data.enforceInterface(DESCRIPTOR);
                    String diskId = data.readString();
                    int ratio = data.readInt();
                    partitionMixed(diskId, ratio);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_setVolumeNickname: {
                    data.enforceInterface(DESCRIPTOR);
                    String volId = data.readString();
                    String nickname = data.readString();
                    setVolumeNickname(volId, nickname);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_setVolumeUserFlags: {
                    data.enforceInterface(DESCRIPTOR);
                    String volId = data.readString();
                    int _flags = data.readInt();
                    int _mask = data.readInt();
                    setVolumeUserFlags(volId, _flags, _mask);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_forgetVolume: {
                    data.enforceInterface(DESCRIPTOR);
                    String fsUuid = data.readString();
                    forgetVolume(fsUuid);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_forgetAllVolumes: {
                    data.enforceInterface(DESCRIPTOR);
                    forgetAllVolumes();
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_setDebugFlags: {
                    data.enforceInterface(DESCRIPTOR);
                    int _flags = data.readInt();
                    int _mask = data.readInt();
                    setDebugFlags(_flags, _mask);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_getPrimaryStorageUuid: {
                    data.enforceInterface(DESCRIPTOR);
                    String volumeUuid = getPrimaryStorageUuid();
                    reply.writeNoException();
                    reply.writeString(volumeUuid);
                    return true;
                }
                case TRANSACTION_setPrimaryStorageUuid: {
                    data.enforceInterface(DESCRIPTOR);
                    String volumeUuid = data.readString();
                    IPackageMoveObserver listener = IPackageMoveObserver.Stub.asInterface(
                            data.readStrongBinder());
                    setPrimaryStorageUuid(volumeUuid, listener);
                    reply.writeNoException();
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    /*
     * Creates a secure container with the specified parameters. Returns an int
     * consistent with MountServiceResultCode
     */
    public int createSecureContainer(String id, int sizeMb, String fstype, String key,
            int ownerUid, boolean external) throws RemoteException;

    /*
     * Destroy a secure container, and free up all resources associated with it.
     * NOTE: Ensure all references are released prior to deleting. Returns an
     * int consistent with MountServiceResultCode
     */
    public int destroySecureContainer(String id, boolean force) throws RemoteException;

    /*
     * Finalize a container which has just been created and populated. After
     * finalization, the container is immutable. Returns an int consistent with
     * MountServiceResultCode
     */
    public int finalizeSecureContainer(String id) throws RemoteException;

    /**
     * Call into MountService by PackageManager to notify that its done
     * processing the media status update request.
     */
    public void finishMediaUpdate() throws RemoteException;

    /**
     * Format external storage given a mount point. Returns an int consistent
     * with MountServiceResultCode
     */
    public int formatVolume(String mountPoint) throws RemoteException;

    /**
     * Gets the path to the mounted Opaque Binary Blob (OBB).
     */
    public String getMountedObbPath(String rawPath) throws RemoteException;

    /**
     * Gets an Array of currently known secure container IDs
     */
    public String[] getSecureContainerList() throws RemoteException;

    /*
     * Returns the filesystem path of a mounted secure container.
     */
    public String getSecureContainerPath(String id) throws RemoteException;

    /**
     * Returns an array of pids with open files on the specified path.
     */
    public int[] getStorageUsers(String path) throws RemoteException;

    /**
     * Gets the state of a volume via its mountpoint.
     */
    public String getVolumeState(String mountPoint) throws RemoteException;

    /**
     * Checks whether the specified Opaque Binary Blob (OBB) is mounted
     * somewhere.
     */
    public boolean isObbMounted(String rawPath) throws RemoteException;

    /*
     * Returns true if the specified container is mounted
     */
    public boolean isSecureContainerMounted(String id) throws RemoteException;

    /**
     * Returns true if a USB mass storage host is connected
     */
    public boolean isUsbMassStorageConnected() throws RemoteException;

    /**
     * Returns true if a USB mass storage host is enabled (media is shared)
     */
    public boolean isUsbMassStorageEnabled() throws RemoteException;

    /**
     * Mounts an Opaque Binary Blob (OBB) with the specified decryption key and
     * only allows the calling process's UID access to the contents.
     * MountService will call back to the supplied IObbActionListener to inform
     * it of the terminal state of the call.
     */
    public void mountObb(String rawPath, String canonicalPath, String key,
            IObbActionListener token, int nonce) throws RemoteException;

    /*
     * Mount a secure container with the specified key and owner UID. Returns an
     * int consistent with MountServiceResultCode
     */
    public int mountSecureContainer(String id, String key, int ownerUid, boolean readOnly)
            throws RemoteException;

    /**
     * Mount external storage at given mount point. Returns an int consistent
     * with MountServiceResultCode
     */
    public int mountVolume(String mountPoint) throws RemoteException;

    /**
     * Registers an IMountServiceListener for receiving async notifications.
     */
    public void registerListener(IMountServiceListener listener) throws RemoteException;

    /*
     * Rename an unmounted secure container. Returns an int consistent with
     * MountServiceResultCode
     */
    public int renameSecureContainer(String oldId, String newId) throws RemoteException;

    /**
     * Enables / disables USB mass storage. The caller should check actual
     * status of enabling/disabling USB mass storage via StorageEventListener.
     */
    public void setUsbMassStorageEnabled(boolean enable) throws RemoteException;

    /**
     * Shuts down the MountService and gracefully unmounts all external media.
     * Invokes call back once the shutdown is complete.
     */
    public void shutdown(IMountShutdownObserver observer) throws RemoteException;

    /**
     * Unmounts an Opaque Binary Blob (OBB). When the force flag is specified,
     * any program using it will be forcibly killed to unmount the image.
     * MountService will call back to the supplied IObbActionListener to inform
     * it of the terminal state of the call.
     */
    public void unmountObb(String rawPath, boolean force, IObbActionListener token, int nonce)
            throws RemoteException;

    /*
     * Unount a secure container. Returns an int consistent with
     * MountServiceResultCode
     */
    public int unmountSecureContainer(String id, boolean force) throws RemoteException;

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
    public void unmountVolume(String mountPoint, boolean force, boolean removeEncryption)
            throws RemoteException;

    /**
     * Unregisters an IMountServiceListener
     */
    public void unregisterListener(IMountServiceListener listener) throws RemoteException;

    /**
     * Returns whether or not the external storage is emulated.
     */
    public boolean isExternalStorageEmulated() throws RemoteException;

    /** The volume is not encrypted. */
    static final int ENCRYPTION_STATE_NONE = 1;
    /** The volume has been encrypted succesfully. */
    static final int ENCRYPTION_STATE_OK = 0;
    /** The volume is in a bad state.*/
    static final int ENCRYPTION_STATE_ERROR_UNKNOWN = -1;
    /** Encryption is incomplete */
    static final int ENCRYPTION_STATE_ERROR_INCOMPLETE = -2;
    /** Encryption is incomplete and irrecoverable */
    static final int ENCRYPTION_STATE_ERROR_INCONSISTENT = -3;
    /** Underlying data is corrupt */
    static final int ENCRYPTION_STATE_ERROR_CORRUPT = -4;

    /**
     * Determines the encryption state of the volume.
     * @return a numerical value. See {@code ENCRYPTION_STATE_*} for possible values.
     */
    public int getEncryptionState() throws RemoteException;

    /**
     * Decrypts any encrypted volumes.
     */
    public int decryptStorage(String password) throws RemoteException;

    /**
     * Encrypts storage.
     */
    public int encryptStorage(int type, String password) throws RemoteException;

    /**
     * Changes the encryption password.
     */
    public int changeEncryptionPassword(int type, String password)
        throws RemoteException;

    /**
     * Verify the encryption password against the stored volume.  This method
     * may only be called by the system process.
     */
    public int verifyEncryptionPassword(String password) throws RemoteException;

    /**
     * Returns list of all mountable volumes.
     */
    public StorageVolume[] getVolumeList(int uid, String packageName, int flags) throws RemoteException;

    /**
     * Gets the path on the filesystem for the ASEC container itself.
     *
     * @param cid ASEC container ID
     * @return path to filesystem or {@code null} if it's not found
     * @throws RemoteException
     */
    public String getSecureContainerFilesystemPath(String cid) throws RemoteException;

    /*
     * Fix permissions in a container which has just been created and populated.
     * Returns an int consistent with MountServiceResultCode
     */
    public int fixPermissionsSecureContainer(String id, int gid, String filename)
            throws RemoteException;

    /**
     * Ensure that all directories along given path exist, creating parent
     * directories as needed. Validates that given path is absolute and that it
     * contains no relative "." or ".." paths or symlinks. Also ensures that
     * path belongs to a volume managed by vold, and that path is either
     * external storage data or OBB directory belonging to calling app.
     */
    public int mkdirs(String callingPkg, String path) throws RemoteException;

    /**
     * Determines the type of the encryption password
     * @return PasswordType
     */
    public int getPasswordType() throws RemoteException;

    /**
     * Get password from vold
     * @return password or empty string
     */
    public String getPassword() throws RemoteException;

    /**
     * Securely clear password from vold
     */
    public void clearPassword() throws RemoteException;

    /**
     * Set a field in the crypto header.
     * @param field field to set
     * @param contents contents to set in field
     */
    public void setField(String field, String contents) throws RemoteException;

    /**
     * Gets a field from the crypto header.
     * @param field field to get
     * @return contents of field
     */
    public String getField(String field) throws RemoteException;

    public int resizeSecureContainer(String id, int sizeMb, String key) throws RemoteException;

    /**
     * Report the time of the last maintenance operation such as fstrim.
     * @return Timestamp of the last maintenance operation, in the
     *     System.currentTimeMillis() time base
     * @throws RemoteException
     */
    public long lastMaintenance() throws RemoteException;

    /**
     * Kick off an immediate maintenance operation
     * @throws RemoteException
     */
    public void runMaintenance() throws RemoteException;

    public void waitForAsecScan() throws RemoteException;

    public DiskInfo[] getDisks() throws RemoteException;
    public VolumeInfo[] getVolumes(int flags) throws RemoteException;
    public VolumeRecord[] getVolumeRecords(int flags) throws RemoteException;

    public void mount(String volId) throws RemoteException;
    public void unmount(String volId) throws RemoteException;
    public void format(String volId) throws RemoteException;
    public long benchmark(String volId) throws RemoteException;

    public void partitionPublic(String diskId) throws RemoteException;
    public void partitionPrivate(String diskId) throws RemoteException;
    public void partitionMixed(String diskId, int ratio) throws RemoteException;

    public void setVolumeNickname(String fsUuid, String nickname) throws RemoteException;
    public void setVolumeUserFlags(String fsUuid, int flags, int mask) throws RemoteException;
    public void forgetVolume(String fsUuid) throws RemoteException;
    public void forgetAllVolumes() throws RemoteException;
    public void setDebugFlags(int flags, int mask) throws RemoteException;

    public String getPrimaryStorageUuid() throws RemoteException;
    public void setPrimaryStorageUuid(String volumeUuid, IPackageMoveObserver callback)
            throws RemoteException;
}
