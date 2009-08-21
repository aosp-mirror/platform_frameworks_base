/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.content;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.Parcelable;
import android.util.Log;

/**
 * ICPC interface methods for an iterator over Entity objects.
 * @hide
 */
public interface IEntityIterator extends IInterface {
    /** Local-side IPC implementation stub class. */
    public static abstract class Stub extends Binder implements IEntityIterator {
        private static final String TAG = "IEntityIterator";
        private static final java.lang.String DESCRIPTOR = "android.content.IEntityIterator";

        /** Construct the stub at attach it to the interface. */
        public Stub() {
            this.attachInterface(this, DESCRIPTOR);
        }
        /**
         * Cast an IBinder object into an IEntityIterator interface,
         * generating a proxy if needed.
         */
        public static IEntityIterator asInterface(IBinder obj) {
            if ((obj==null)) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (((iin!=null)&&(iin instanceof IEntityIterator))) {
                return ((IEntityIterator)iin);
            }
            return new IEntityIterator.Stub.Proxy(obj);
        }

        public IBinder asBinder() {
            return this;
        }

        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            switch (code) {
                case INTERFACE_TRANSACTION:
                {
                    reply.writeString(DESCRIPTOR);
                    return true;
                }

                case TRANSACTION_hasNext:
                {
                    data.enforceInterface(DESCRIPTOR);
                    boolean _result;
                    try {
                        _result = this.hasNext();
                    } catch (Exception e) {
                        Log.e(TAG, "caught exception in hasNext()", e);
                        reply.writeException(e);
                        return true;
                    }
                    reply.writeNoException();
                    reply.writeInt(((_result)?(1):(0)));
                    return true;
                }

                case TRANSACTION_next:
                {
                    data.enforceInterface(DESCRIPTOR);
                    Entity entity;
                    try {
                        entity = this.next();
                    } catch (RemoteException e) {
                        Log.e(TAG, "caught exception in next()", e);
                        reply.writeException(e);
                        return true;
                    }
                    reply.writeNoException();
                    entity.writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                    return true;
                }

                case TRANSACTION_reset:
                {
                    data.enforceInterface(DESCRIPTOR);
                    try {
                        this.reset();
                    } catch (RemoteException e) {
                        Log.e(TAG, "caught exception in next()", e);
                        reply.writeException(e);
                        return true;
                    }
                    reply.writeNoException();
                    return true;
                }

                case TRANSACTION_close:
                {
                    data.enforceInterface(DESCRIPTOR);
                    try {
                        this.close();
                    } catch (RemoteException e) {
                        Log.e(TAG, "caught exception in close()", e);
                        reply.writeException(e);
                        return true;
                    }
                    reply.writeNoException();
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements IEntityIterator {
            private IBinder mRemote;
            Proxy(IBinder remote) {
                mRemote = remote;
            }
            public IBinder asBinder() {
                return mRemote;
            }
            public java.lang.String getInterfaceDescriptor() {
                return DESCRIPTOR;
            }
            public boolean hasNext() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                boolean _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_hasNext, _data, _reply, 0);
                    _reply.readException();
                    _result = (0!=_reply.readInt());
                }
                finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }

            public Entity next() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_next, _data, _reply, 0);
                    _reply.readException();
                    return Entity.CREATOR.createFromParcel(_reply);
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            public void reset() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_reset, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            public void close() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_close, _data, _reply, 0);
                    _reply.readException();
                }
                finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }
        }
        static final int TRANSACTION_hasNext = (IBinder.FIRST_CALL_TRANSACTION + 0);
        static final int TRANSACTION_next = (IBinder.FIRST_CALL_TRANSACTION + 1);
        static final int TRANSACTION_close = (IBinder.FIRST_CALL_TRANSACTION + 2);
        static final int TRANSACTION_reset = (IBinder.FIRST_CALL_TRANSACTION + 3);
    }
    public boolean hasNext() throws RemoteException;
    public Entity next() throws RemoteException;
    public void reset() throws RemoteException;
    public void close() throws RemoteException;
}
