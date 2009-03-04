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
import android.os.RemoteException;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Bundle;

/**
 * {@hide}
 */
public interface IContentService extends IInterface
{
    public void registerContentObserver(Uri uri, boolean notifyForDescendentsn,
            IContentObserver observer) throws RemoteException;
    public void unregisterContentObserver(IContentObserver observer) throws RemoteException;

    public void notifyChange(Uri uri, IContentObserver observer,
            boolean observerWantsSelfNotifications, boolean syncToNetwork)
            throws RemoteException;

    public void startSync(Uri url, Bundle extras) throws RemoteException;
    public void cancelSync(Uri uri) throws RemoteException;

    static final String SERVICE_NAME = "content";

    /* IPC constants */
    static final String descriptor = "android.content.IContentService";

    static final int REGISTER_CONTENT_OBSERVER_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int UNREGISTER_CHANGE_OBSERVER_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 2;
    static final int NOTIFY_CHANGE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 3;
    static final int START_SYNC_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 4;
    static final int CANCEL_SYNC_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 5;
}

