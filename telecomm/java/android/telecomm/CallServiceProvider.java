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

import com.android.internal.telecomm.ICallServiceLookupResponse;
import com.android.internal.telecomm.ICallServiceProvider;

/**
 * Base implementation of a call service provider which extends {@link Service}. This class
 * should be extended by an app that wants to supply phone calls to be handled and managed by
 * the device's in-call interface. All method-calls from the framework to the call service provider
 * are passed through to the main thread for before executing the overriden methods of
 * CallServiceProvider.
 *
 * TODO(santoscordon): Improve paragraph above once the final design is in place. Needs more
 * about how this can be used.
 */
public abstract class CallServiceProvider extends Service {

    /**
     * Default Handler used to consolidate binder method calls onto a single thread.
     */
    private final class CallServiceProviderMessageHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_LOOKUP_CALL_SERVICES:
                    CallServiceLookupResponse response =
                            new CallServiceLookupResponse((ICallServiceLookupResponse) msg.obj);
                    lookupCallServices(response);
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
            Message message = mMessageHandler.obtainMessage(
                    MSG_LOOKUP_CALL_SERVICES, callServiceLookupResponse);
            message.sendToTarget();
        }
    }

    // Only used internally by this class.
    // Binder method calls on this service can occur on multiple threads. These messages are used
    // in conjunction with {@link #mMessageHandler} to ensure that all callbacks are handled on a
    // single thread.  Keeping it on a single thread allows CallService implementations to avoid
    // needing multi-threaded code in their own callback routines.
    private static final int MSG_LOOKUP_CALL_SERVICES = 1;

    /**
     * Message handler for consolidating binder callbacks onto a single thread.
     * See {@link CallServiceProviderMessageHandler}.
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
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /**
     * Initiates the process to retrieve the list of {@link CallServiceDescriptor}s implemented by
     * this provider.
     *
     * @param response The response object through which the list of call services is sent.
     */
    public abstract void lookupCallServices(CallServiceLookupResponse response);
}
