/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.database.IContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ServiceManager;
import android.os.Bundle;
import android.util.Config;
import android.util.Log;

/**
 * {@hide}
 */
abstract class ContentServiceNative extends Binder implements IContentService
{
    public ContentServiceNative()
    {
        attachInterface(this, descriptor);
    }

    /**
     * Cast a Binder object into a content resolver interface, generating
     * a proxy if needed.
     */
    static public IContentService asInterface(IBinder obj)
    {
        if (obj == null) {
            return null;
        }
        IContentService in =
            (IContentService)obj.queryLocalInterface(descriptor);
        if (in != null) {
            return in;
        }

        return new ContentServiceProxy(obj);
    }

    /**
     * Retrieve the system's default/global content service.
     */
    static public IContentService getDefault()
    {
        if (gDefault != null) {
            return gDefault;
        }
        IBinder b = ServiceManager.getService("content");
        if (Config.LOGV) Log.v("ContentService", "default service binder = " + b);
        gDefault = asInterface(b);
        if (Config.LOGV) Log.v("ContentService", "default service = " + gDefault);
        return gDefault;
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
    {
        try {
        switch (code) {
            case 5038: {
                data.readString(); // ignore the interface token that service generated
                Uri uri = Uri.parse(data.readString());
                notifyChange(uri, null, false, false);
                return true;
            }
            
            case REGISTER_CONTENT_OBSERVER_TRANSACTION: {
                Uri uri = Uri.CREATOR.createFromParcel(data);
                boolean notifyForDescendents = data.readInt() != 0;
                IContentObserver observer = IContentObserver.Stub.asInterface(data.readStrongBinder());
                registerContentObserver(uri, notifyForDescendents, observer);
                return true;
            }

            case UNREGISTER_CHANGE_OBSERVER_TRANSACTION: {
                IContentObserver observer = IContentObserver.Stub.asInterface(data.readStrongBinder());
                unregisterContentObserver(observer);
                return true;
            }

            case NOTIFY_CHANGE_TRANSACTION: {
                Uri uri = Uri.CREATOR.createFromParcel(data);
                IContentObserver observer = IContentObserver.Stub.asInterface(data.readStrongBinder());
                boolean observerWantsSelfNotifications = data.readInt() != 0;
                boolean syncToNetwork = data.readInt() != 0;
                notifyChange(uri, observer, observerWantsSelfNotifications, syncToNetwork);
                return true;
            }

            case START_SYNC_TRANSACTION: {
                Uri url = null;
                int hasUrl = data.readInt();
                if (hasUrl != 0) {
                    url = Uri.CREATOR.createFromParcel(data);
                }
                startSync(url, data.readBundle());
                return true;
            }

            case CANCEL_SYNC_TRANSACTION: {
                Uri url = null;
                int hasUrl = data.readInt();
                if (hasUrl != 0) {
                    url = Uri.CREATOR.createFromParcel(data);
                }
                cancelSync(url);
                return true;
            }

            default:
                return super.onTransact(code, data, reply, flags);
        }
        } catch (Exception e) {
            Log.e("ContentServiceNative", "Caught exception in transact", e);
        }

        return false;
    }

    public IBinder asBinder()
    {
        return this;
    }

    private static IContentService gDefault;
}


final class ContentServiceProxy implements IContentService
{
    public ContentServiceProxy(IBinder remote)
    {
        mRemote = remote;
    }

    public IBinder asBinder()
    {
        return mRemote;
    }

    public void registerContentObserver(Uri uri, boolean notifyForDescendents,
            IContentObserver observer) throws RemoteException
    {
        Parcel data = Parcel.obtain();
        uri.writeToParcel(data, 0);
        data.writeInt(notifyForDescendents ? 1 : 0);
        data.writeStrongInterface(observer);
        mRemote.transact(REGISTER_CONTENT_OBSERVER_TRANSACTION, data, null, 0);
        data.recycle();
    }

    public void unregisterContentObserver(IContentObserver observer) throws RemoteException {
        Parcel data = Parcel.obtain();
        data.writeStrongInterface(observer);
        mRemote.transact(UNREGISTER_CHANGE_OBSERVER_TRANSACTION, data, null, 0);
        data.recycle();
    }

    public void notifyChange(Uri uri, IContentObserver observer,
            boolean observerWantsSelfNotifications, boolean syncToNetwork)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        uri.writeToParcel(data, 0);
        data.writeStrongInterface(observer);
        data.writeInt(observerWantsSelfNotifications ? 1 : 0);
        data.writeInt(syncToNetwork ? 1 : 0);
        mRemote.transact(NOTIFY_CHANGE_TRANSACTION, data, null, IBinder.FLAG_ONEWAY);
        data.recycle();
    }

    public void startSync(Uri url, Bundle extras) throws RemoteException {
        Parcel data = Parcel.obtain();
        if (url == null) {
            data.writeInt(0);
        } else {
            data.writeInt(1);
            url.writeToParcel(data, 0);
        }
        extras.writeToParcel(data, 0);
        mRemote.transact(START_SYNC_TRANSACTION, data, null, IBinder.FLAG_ONEWAY);
        data.recycle();
    }

    public void cancelSync(Uri url) throws RemoteException {
        Parcel data = Parcel.obtain();
        if (url == null) {
            data.writeInt(0);
        } else {
            data.writeInt(1);
            url.writeToParcel(data, 0);
        }
        mRemote.transact(CANCEL_SYNC_TRANSACTION, data, null /* reply */, IBinder.FLAG_ONEWAY);
        data.recycle();
    }

    private IBinder mRemote;
}

