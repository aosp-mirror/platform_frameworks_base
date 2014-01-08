/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.telecomm;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;

import android.telecomm.ICallServiceProvider;
import android.telecomm.ICallServiceLookupResponse;
import android.util.Log;

/**
 * Base implementation of CallServiceProvider service which implements ICallServiceProvider.
 * Its primary function is to provide implementations of {@link ICallService} which itself
 * can be used to provide calls for the framework's telecomm component.
 *
 * TODO(santoscordon): Improve paragraph above once the final design is in place. Needs more
 * about how this can be used.
 * @hide
 */
public abstract class CallServiceProvider extends Service implements ICallServiceProvider {
    /** Used to identify log entries by this class. */
    private static final String TAG = CallServiceProvider.class.getSimpleName();

    /**
     * Default Handler used to consolidate binder method calls onto a single thread.
     */
    private final class CallServiceProviderMessageHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_GET_CALL_SERVICES:
                    try {
                        lookupCallServices((ICallServiceLookupResponse) msg.obj);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Remote exception on lookupCallServices().", e);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Default ICallServiceProvider implementation provided to CallsManager via {@link #onBind}.
     */
    private final class CallServiceProviderWrapper extends ICallServiceProvider.Stub {

        /** {@inheritDoc} */
        @Override
        public void lookupCallServices(ICallServiceLookupResponse callServiceLookupResponse) {
            mMessageHandler.obtainMessage(MSG_GET_CALL_SERVICES, callServiceLookupResponse)
                    .sendToTarget();
        }
    }

    // Only used internally by this class.
    // Binder method calls on this service can occur on multiple threads. These messages are used
    // in conjunction with {@link #mMessageHandler} to ensure that all callbacks are handled on a
    // single thread.  Keeping it on a single thread allows CallService implementations to avoid
    // needing multi-threaded code in their own callback routines.
    private static final int MSG_GET_CALL_SERVICES = 1;

    /**
     * Message handler for consolidating binder callbacks onto a single thread.
     * See {@link #CallServiceProviderMessageHandler}.
     */
    private final CallServiceProviderMessageHandler mMessageHandler;

    /**
     * Default binder implementation of {@link ICallServiceProvider} interface.
     */
    private final CallServiceProviderWrapper mBinder;

    /**
     * Protected constructor called only by subclasses creates the binder interface and
     * single-threaded message handler.
     */
    protected CallServiceProvider() {
        mMessageHandler = new CallServiceProviderMessageHandler();
        mBinder = new CallServiceProviderWrapper();
    }

    /** {@inheritDoc} */
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
