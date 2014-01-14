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

import android.telecomm.ICallService;
import android.telecomm.ICallServiceAdapter;
import android.util.Log;
import android.util.Pair;

/**
 * Base implementation of CallService which can be used to provide calls for the system
 * in-call UI. CallService is a one-way service from the framework's CallsManager to any app
 * that would like to provide calls managed by the default system in-call user interface.
 * When the service is bound by the framework, CallsManager will call setCallServiceAdapter
 * which will provide CallService with an instance of {@link CallServiceAdapter} to be used
 * for communicating back to CallsManager. Subsequently, more specific methods of the service
 * will be called to perform various call actions including making an outgoing call and
 * disconnected existing calls.
 * TODO(santoscordon): Needs more about AndroidManifest.xml service registrations before
 * we can unhide this API.
 *
 * Most public methods of this function are backed by a one-way AIDL interface which precludes
 * synchronous responses. As a result, most responses are handled by (or have TODOs to handle)
 * response objects instead of return values.
 * TODO(santoscordon): Improve paragraph above once the final design is in place.
 * @hide
 */
public abstract class CallService extends Service {
    private static final String TAG = CallService.class.getSimpleName();

    /**
     * Default Handler used to consolidate binder method calls onto a single thread.
     */
    private final class CallServiceMessageHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SET_CALL_SERVICE_ADAPTER:
                    setCallServiceAdapter((ICallServiceAdapter) msg.obj);
                    break;
                case MSG_IS_COMPATIBLE_WITH:
                    // See {@link CallServiceWrapper#isCompatibleWith} for dataObject definition.
                    try {
                        // TODO(santoscordon): Switch to using a custom class here instead.  When we
                        // switch to using Call objects instead of handles directly, this may not even be
                        // necessary.
                        Pair<String, String> dataObject = (Pair<String, String>) msg.obj;
                        isCompatibleWith(dataObject.first, dataObject.second);
                    } catch (ClassCastException e) {
                        Log.e(TAG, "Unexpected object type for MSG_IS_COMPATIBLE_WITH.", e);
                    }
                    break;
                case MSG_CALL:
                    // See {@link CallServiceWrapper#call} for dataObject definition.
                    try {
                        Pair<String, String> dataObject = (Pair<String, String>) msg.obj;
                        call(dataObject.first, dataObject.second);
                    } catch (ClassCastException e) {
                        Log.e(TAG, "Unexpected object type for MSG_CALL.", e);
                    }
                    break;
                case MSG_DISCONNECT:
                    disconnect((String) msg.obj);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Default ICallService implementation provided to CallsManager via {@link #onBind}.
     */
    private final class CallServiceWrapper extends ICallService.Stub {
        @Override
        public void setCallServiceAdapter(ICallServiceAdapter callServiceAdapter) {
            mMessageHandler.obtainMessage(MSG_SET_CALL_SERVICE_ADAPTER, callServiceAdapter)
                    .sendToTarget();
        }

        @Override
        public void isCompatibleWith(String handle, String callId) {
            Pair<String, String> dataObject = new Pair<String, String>(handle, callId);
            mMessageHandler.obtainMessage(MSG_IS_COMPATIBLE_WITH, dataObject).sendToTarget();
        }

        @Override
        public void call(String handle, String callId) {
            Pair<String, String> dataObject = new Pair<String, String>(handle, callId);
            mMessageHandler.obtainMessage(MSG_CALL, dataObject).sendToTarget();
        }

        @Override
        public void disconnect(String callId) {
            mMessageHandler.obtainMessage(MSG_DISCONNECT, callId).sendToTarget();
        }
    }

    // Only used internally by this class.
    // Binder method calls on this service can occur on multiple threads. These messages are used
    // in conjunction with {@link #mMessageHandler} to ensure that all callbacks are handled on a
    // single thread.  Keeping it on a single thread allows CallService implementations to avoid
    // needing multi-threaded code in their own callback routines.
    private static final int
            MSG_SET_CALL_SERVICE_ADAPTER = 1,
            MSG_IS_COMPATIBLE_WITH = 2,
            MSG_CALL = 3,
            MSG_DISCONNECT = 4;

    /**
     * Message handler for consolidating binder callbacks onto a single thread.
     * See {@link #CallServiceMessageHandler}.
     */
    private final CallServiceMessageHandler mMessageHandler;

    /**
     * Default binder implementation of {@link ICallService} interface.
     */
    private final CallServiceWrapper mBinder;

    /**
     * Protected constructor called only by subclasses creates the binder interface and
     * single-threaded message handler.
     */
    protected CallService() {
        mMessageHandler = new CallServiceMessageHandler();
        mBinder = new CallServiceWrapper();
    }

    /** {@inheritDoc} */
    public IBinder onBind(Intent intent) {
        return getBinder();
    }

    /**
     * Returns binder object which can be used across IPC methods.
     */
    public IBinder getBinder() {
        return mBinder;
    }

    /**
     * Sets an implementation of ICallServiceAdapter for adding new calls and communicating state
     * changes of existing calls.
     * TODO(santoscordon): Should we not reference ICallServiceAdapter directly from here? Should we
     * wrap that in a wrapper like we do for CallService/ICallService?
     *
     * @param callServiceAdapter Adapter object for communicating call to CallsManager
     */
    public abstract void setCallServiceAdapter(ICallServiceAdapter callServiceAdapter);

    /**
     * Determines if the CallService can make calls to the handle. Response is sent via
     * ICallServiceAdapter. When responding, the correct call ID must be specified along with
     * the handle.
     *
     * @param handle The destination handle to test against.
     * @param callId The call identifier associated with this compatibility request.
     */
    public abstract void isCompatibleWith(String handle, String callId);

    /**
     * Attempts to call the relevant party using the specified handle, be it a phone number,
     * SIP address, or some other kind of user ID.  Note that the set of handle types is
     * dynamically extensible since call providers should be able to implement arbitrary
     * handle-calling systems.  See {@link #isCompatibleWith}. It is expected that the
     * call service respond via {@link ICallServiceAdapter#newOutgoingCall} if it can successfully
     * make the call.
     *
     * @param handle The destination handle to call.
     * @param callId Unique identifier for the call.
     */
    public abstract void call(String handle, String callId);

    /**
     * Disconnects the specified call.
     *
     * @param callId The ID of the call to disconnect.
     */
    public abstract void disconnect(String callId);
}
