/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.security;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * This must be kept manually in sync with system/security/keystore until AIDL
 * can generate both Java and C++ bindings.
 *
 * @hide
 */
public interface IKeystoreService extends IInterface {
    public static abstract class Stub extends Binder implements IKeystoreService {
        private static class Proxy implements IKeystoreService {
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

            public int test() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_test, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public byte[] get(String name) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                byte[] _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(name);
                    mRemote.transact(Stub.TRANSACTION_get, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.createByteArray();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public int insert(String name, byte[] item, int uid, int flags) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(name);
                    _data.writeByteArray(item);
                    _data.writeInt(uid);
                    _data.writeInt(flags);
                    mRemote.transact(Stub.TRANSACTION_insert, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public int del(String name, int uid) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(name);
                    _data.writeInt(uid);
                    mRemote.transact(Stub.TRANSACTION_del, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public int exist(String name, int uid) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(name);
                    _data.writeInt(uid);
                    mRemote.transact(Stub.TRANSACTION_exist, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public String[] saw(String name, int uid) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                String[] _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(name);
                    _data.writeInt(uid);
                    mRemote.transact(Stub.TRANSACTION_saw, _data, _reply, 0);
                    _reply.readException();
                    int size = _reply.readInt();
                    _result = new String[size];
                    for (int i = 0; i < size; i++) {
                        _result[i] = _reply.readString();
                    }
                    int _ret = _reply.readInt();
                    if (_ret != 1) {
                        return null;
                    }
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int reset() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_reset, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public int password(String password) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(password);
                    mRemote.transact(Stub.TRANSACTION_password, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public int lock() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_lock, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public int unlock(String password) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(password);
                    mRemote.transact(Stub.TRANSACTION_unlock, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int zero() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_zero, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public int generate(String name, int uid, int keyType, int keySize, int flags,
                    byte[][] args) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(name);
                    _data.writeInt(uid);
                    _data.writeInt(keyType);
                    _data.writeInt(keySize);
                    _data.writeInt(flags);
                    if (args == null) {
                        _data.writeInt(0);
                    } else {
                        _data.writeInt(args.length);
                        for (int i = 0; i < args.length; i++) {
                            _data.writeByteArray(args[i]);
                        }
                    }
                    mRemote.transact(Stub.TRANSACTION_generate, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public int import_key(String name, byte[] data, int uid, int flags)
                    throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(name);
                    _data.writeByteArray(data);
                    _data.writeInt(uid);
                    _data.writeInt(flags);
                    mRemote.transact(Stub.TRANSACTION_import, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public byte[] sign(String name, byte[] data) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                byte[] _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(name);
                    _data.writeByteArray(data);
                    mRemote.transact(Stub.TRANSACTION_sign, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.createByteArray();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public int verify(String name, byte[] data, byte[] signature) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(name);
                    _data.writeByteArray(data);
                    _data.writeByteArray(signature);
                    mRemote.transact(Stub.TRANSACTION_verify, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public byte[] get_pubkey(String name) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                byte[] _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(name);
                    mRemote.transact(Stub.TRANSACTION_get_pubkey, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.createByteArray();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public int del_key(String name, int uid) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(name);
                    _data.writeInt(uid);
                    mRemote.transact(Stub.TRANSACTION_del_key, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public int grant(String name, int granteeUid) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(name);
                    _data.writeInt(granteeUid);
                    mRemote.transact(Stub.TRANSACTION_grant, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public int ungrant(String name, int granteeUid) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(name);
                    _data.writeInt(granteeUid);
                    mRemote.transact(Stub.TRANSACTION_ungrant, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public long getmtime(String name) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                long _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(name);
                    mRemote.transact(Stub.TRANSACTION_getmtime, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readLong();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int duplicate(String srcKey, int srcUid, String destKey, int destUid)
                    throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(srcKey);
                    _data.writeInt(srcUid);
                    _data.writeString(destKey);
                    _data.writeInt(destUid);
                    mRemote.transact(Stub.TRANSACTION_duplicate, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int is_hardware_backed(String keyType) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(keyType);
                    mRemote.transact(Stub.TRANSACTION_is_hardware_backed, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            @Override
            public int clear_uid(long uid) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeLong(uid);
                    mRemote.transact(Stub.TRANSACTION_clear_uid, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public int reset_uid(int uid) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(uid);
                    mRemote.transact(Stub.TRANSACTION_reset_uid, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public int sync_uid(int srcUid, int dstUid) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(srcUid);
                    _data.writeInt(dstUid);
                    mRemote.transact(Stub.TRANSACTION_sync_uid, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public int password_uid(String password, int uid) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                int _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(password);
                    _data.writeInt(uid);
                    mRemote.transact(Stub.TRANSACTION_password_uid, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readInt();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }
        }

        private static final String DESCRIPTOR = "android.security.keystore";

        static final int TRANSACTION_test = IBinder.FIRST_CALL_TRANSACTION + 0;
        static final int TRANSACTION_get = IBinder.FIRST_CALL_TRANSACTION + 1;
        static final int TRANSACTION_insert = IBinder.FIRST_CALL_TRANSACTION + 2;
        static final int TRANSACTION_del = IBinder.FIRST_CALL_TRANSACTION + 3;
        static final int TRANSACTION_exist = IBinder.FIRST_CALL_TRANSACTION + 4;
        static final int TRANSACTION_saw = IBinder.FIRST_CALL_TRANSACTION + 5;
        static final int TRANSACTION_reset = IBinder.FIRST_CALL_TRANSACTION + 6;
        static final int TRANSACTION_password = IBinder.FIRST_CALL_TRANSACTION + 7;
        static final int TRANSACTION_lock = IBinder.FIRST_CALL_TRANSACTION + 8;
        static final int TRANSACTION_unlock = IBinder.FIRST_CALL_TRANSACTION + 9;
        static final int TRANSACTION_zero = IBinder.FIRST_CALL_TRANSACTION + 10;
        static final int TRANSACTION_generate = IBinder.FIRST_CALL_TRANSACTION + 11;
        static final int TRANSACTION_import = IBinder.FIRST_CALL_TRANSACTION + 12;
        static final int TRANSACTION_sign = IBinder.FIRST_CALL_TRANSACTION + 13;
        static final int TRANSACTION_verify = IBinder.FIRST_CALL_TRANSACTION + 14;
        static final int TRANSACTION_get_pubkey = IBinder.FIRST_CALL_TRANSACTION + 15;
        static final int TRANSACTION_del_key = IBinder.FIRST_CALL_TRANSACTION + 16;
        static final int TRANSACTION_grant = IBinder.FIRST_CALL_TRANSACTION + 17;
        static final int TRANSACTION_ungrant = IBinder.FIRST_CALL_TRANSACTION + 18;
        static final int TRANSACTION_getmtime = IBinder.FIRST_CALL_TRANSACTION + 19;
        static final int TRANSACTION_duplicate = IBinder.FIRST_CALL_TRANSACTION + 20;
        static final int TRANSACTION_is_hardware_backed = IBinder.FIRST_CALL_TRANSACTION + 21;
        static final int TRANSACTION_clear_uid = IBinder.FIRST_CALL_TRANSACTION + 22;
        static final int TRANSACTION_reset_uid = IBinder.FIRST_CALL_TRANSACTION + 23;
        static final int TRANSACTION_sync_uid = IBinder.FIRST_CALL_TRANSACTION + 24;
        static final int TRANSACTION_password_uid = IBinder.FIRST_CALL_TRANSACTION + 25;

        /**
         * Cast an IBinder object into an IKeystoreService interface, generating
         * a proxy if needed.
         */
        public static IKeystoreService asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin != null && iin instanceof IKeystoreService) {
                return (IKeystoreService) iin;
            }
            return new IKeystoreService.Stub.Proxy(obj);
        }

        /** Construct the stub at attach it to the interface. */
        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            switch (code) {
                case INTERFACE_TRANSACTION: {
                    reply.writeString(DESCRIPTOR);
                    return true;
                }
                case TRANSACTION_test: {
                    data.enforceInterface(DESCRIPTOR);
                    int resultCode = test();
                    reply.writeNoException();
                    reply.writeInt(resultCode);
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    public int test() throws RemoteException;

    public byte[] get(String name) throws RemoteException;

    public int insert(String name, byte[] item, int uid, int flags) throws RemoteException;

    public int del(String name, int uid) throws RemoteException;

    public int exist(String name, int uid) throws RemoteException;

    public String[] saw(String name, int uid) throws RemoteException;

    public int reset() throws RemoteException;

    public int password(String password) throws RemoteException;

    public int lock() throws RemoteException;

    public int unlock(String password) throws RemoteException;

    public int zero() throws RemoteException;

    public int generate(String name, int uid, int keyType, int keySize, int flags, byte[][] args)
            throws RemoteException;

    public int import_key(String name, byte[] data, int uid, int flags) throws RemoteException;

    public byte[] sign(String name, byte[] data) throws RemoteException;

    public int verify(String name, byte[] data, byte[] signature) throws RemoteException;

    public byte[] get_pubkey(String name) throws RemoteException;

    public int del_key(String name, int uid) throws RemoteException;

    public int grant(String name, int granteeUid) throws RemoteException;

    public int ungrant(String name, int granteeUid) throws RemoteException;

    public long getmtime(String name) throws RemoteException;

    public int duplicate(String srcKey, int srcUid, String destKey, int destUid)
            throws RemoteException;

    public int is_hardware_backed(String string) throws RemoteException;

    public int clear_uid(long uid) throws RemoteException;

    public int reset_uid(int uid) throws RemoteException;

    public int sync_uid(int sourceUid, int targetUid) throws RemoteException;

    public int password_uid(String password, int uid) throws RemoteException;
}
