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

package android.os.storage;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Callback class for receiving events related to shutdown.
 * 
 * @hide - For internal consumption only.
 */
public interface IMountShutdownObserver extends IInterface {
    /** Local-side IPC implementation stub class. */
    public static abstract class Stub extends Binder implements IMountShutdownObserver {
        private static final java.lang.String DESCRIPTOR = "IMountShutdownObserver";

        /** Construct the stub at attach it to the interface. */
        public Stub() {
            this.attachInterface(this, DESCRIPTOR);
        }

        /**
         * Cast an IBinder object into an IMountShutdownObserver interface,
         * generating a proxy if needed.
         */
        public static IMountShutdownObserver asInterface(IBinder obj) {
            if ((obj == null)) {
                return null;
            }
            IInterface iin = (IInterface) obj.queryLocalInterface(DESCRIPTOR);
            if (((iin != null) && (iin instanceof IMountShutdownObserver))) {
                return ((IMountShutdownObserver) iin);
            }
            return new IMountShutdownObserver.Stub.Proxy(obj);
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
                case TRANSACTION_onShutDownComplete: {
                    data.enforceInterface(DESCRIPTOR);
                    int statusCode;
                    statusCode = data.readInt();
                    this.onShutDownComplete(statusCode);
                    reply.writeNoException();
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements IMountShutdownObserver {
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

            /**
             * This method is called when the shutdown of MountService
             * completed.
             * 
             * @param statusCode indicates success or failure of the shutdown.
             */
            public void onShutDownComplete(int statusCode) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(statusCode);
                    mRemote.transact(Stub.TRANSACTION_onShutDownComplete, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }
        }

        static final int TRANSACTION_onShutDownComplete = (IBinder.FIRST_CALL_TRANSACTION + 0);
    }

    /**
     * This method is called when the shutdown of MountService completed.
     * 
     * @param statusCode indicates success or failure of the shutdown.
     */
    public void onShutDownComplete(int statusCode) throws RemoteException;
}
