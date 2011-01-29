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

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Callback class for receiving events from MountService about Opaque Binary
 * Blobs (OBBs).
 * 
 * @hide - Applications should use StorageManager to interact with OBBs.
 */
public interface IObbActionListener extends IInterface {
    /** Local-side IPC implementation stub class. */
    public static abstract class Stub extends Binder implements IObbActionListener {
        private static final String DESCRIPTOR = "IObbActionListener";

        /** Construct the stub at attach it to the interface. */
        public Stub() {
            this.attachInterface(this, DESCRIPTOR);
        }

        /**
         * Cast an IBinder object into an IObbActionListener interface,
         * generating a proxy if needed.
         */
        public static IObbActionListener asInterface(IBinder obj) {
            if ((obj == null)) {
                return null;
            }
            IInterface iin = (IInterface) obj.queryLocalInterface(DESCRIPTOR);
            if (((iin != null) && (iin instanceof IObbActionListener))) {
                return ((IObbActionListener) iin);
            }
            return new IObbActionListener.Stub.Proxy(obj);
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
                case TRANSACTION_onObbResult: {
                    data.enforceInterface(DESCRIPTOR);
                    String filename;
                    filename = data.readString();
                    int nonce;
                    nonce = data.readInt();
                    int status;
                    status = data.readInt();
                    this.onObbResult(filename, nonce, status);
                    reply.writeNoException();
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements IObbActionListener {
            private IBinder mRemote;

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
             * Return from an OBB action result.
             * 
             * @param filename the path to the OBB the operation was performed
             *            on
             * @param returnCode status of the operation
             */
            public void onObbResult(String filename, int nonce, int status)
                    throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(filename);
                    _data.writeInt(nonce);
                    _data.writeInt(status);
                    mRemote.transact(Stub.TRANSACTION_onObbResult, _data, _reply,
                            android.os.IBinder.FLAG_ONEWAY);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }
        }

        static final int TRANSACTION_onObbResult = (IBinder.FIRST_CALL_TRANSACTION + 0);
    }

    /**
     * Return from an OBB action result.
     * 
     * @param filename the path to the OBB the operation was performed on
     * @param nonce identifier that is meaningful to the receiver
     * @param status status code as defined in {@link OnObbStateChangeListener}
     */
    public void onObbResult(String filename, int nonce, int status) throws RemoteException;
}
