/*
 * Copyright 2019 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

package android.bluetooth;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.bluetooth.annotations.RequiresBluetoothConnectPermission;
import android.content.ComponentName;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.Log;
import android.annotation.SuppressLint;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * This class provides the APIs to control the Call Control profile.
 *
 * <p>
 * This class provides Bluetooth Telephone Bearer Service functionality,
 * allowing applications to expose a GATT Service based interface to control the
 * state of the calls by remote devices such as LE audio devices.
 *
 * <p>
 * BluetoothLeCallControl is a proxy object for controlling the Bluetooth Telephone Bearer
 * Service via IPC. Use {@link BluetoothAdapter#getProfileProxy} to get the
 * BluetoothLeCallControl proxy object.
 *
 * @hide
 */
public final class BluetoothLeCallControl implements BluetoothProfile {
    private static final String TAG = "BluetoothLeCallControl";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    /** @hide */
    @IntDef(prefix = "RESULT_", value = {
            RESULT_SUCCESS,
            RESULT_ERROR_UNKNOWN_CALL_ID,
            RESULT_ERROR_INVALID_URI,
            RESULT_ERROR_APPLICATION
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Result {
    }

    /**
     * Opcode write was successful.
     *
     * @hide
     */
    public static final int RESULT_SUCCESS = 0;

    /**
     * Unknown call Id has been used in the operation.
     *
     * @hide
     */
    public static final int RESULT_ERROR_UNKNOWN_CALL_ID = 1;

    /**
     * The URI provided in {@link Callback#onPlaceCallRequest} is invalid.
     *
     * @hide
     */
    public static final int RESULT_ERROR_INVALID_URI = 2;

    /**
     * Application internal error.
     *
     * @hide
     */
    public static final int RESULT_ERROR_APPLICATION = 3;

    /** @hide */
    @IntDef(prefix = "TERMINATION_REASON_", value = {
            TERMINATION_REASON_INVALID_URI,
            TERMINATION_REASON_FAIL,
            TERMINATION_REASON_REMOTE_HANGUP,
            TERMINATION_REASON_SERVER_HANGUP,
            TERMINATION_REASON_LINE_BUSY,
            TERMINATION_REASON_NETWORK_CONGESTION,
            TERMINATION_REASON_CLIENT_HANGUP,
            TERMINATION_REASON_NO_SERVICE,
            TERMINATION_REASON_NO_ANSWER
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TerminationReason {
    }

    /**
     * Remote Caller ID value used to place a call was formed improperly.
     *
     * @hide
     */
    public static final int TERMINATION_REASON_INVALID_URI = 0x00;

    /**
     * Call fail.
     *
     * @hide
     */
    public static final int TERMINATION_REASON_FAIL = 0x01;

    /**
     * Remote party ended call.
     *
     * @hide
     */
    public static final int TERMINATION_REASON_REMOTE_HANGUP = 0x02;

    /**
     * Call ended from the server.
     *
     * @hide
     */
    public static final int TERMINATION_REASON_SERVER_HANGUP = 0x03;

    /**
     * Line busy.
     *
     * @hide
     */
    public static final int TERMINATION_REASON_LINE_BUSY = 0x04;

    /**
     * Network congestion.
     *
     * @hide
     */
    public static final int TERMINATION_REASON_NETWORK_CONGESTION = 0x05;

    /**
     * Client terminated.
     *
     * @hide
     */
    public static final int TERMINATION_REASON_CLIENT_HANGUP = 0x06;

    /**
     * No service.
     *
     * @hide
     */
    public static final int TERMINATION_REASON_NO_SERVICE = 0x07;

    /**
     * No answer.
     *
     * @hide
     */
    public static final int TERMINATION_REASON_NO_ANSWER = 0x08;

    /*
     * Flag indicating support for hold/unhold call feature.
     *
     * @hide
     */
    public static final int CAPABILITY_HOLD_CALL = 0x00000001;

    /**
     * Flag indicating support for joining calls feature.
     *
     * @hide
     */
    public static final int CAPABILITY_JOIN_CALLS = 0x00000002;

    private static final int MESSAGE_TBS_SERVICE_CONNECTED = 102;
    private static final int MESSAGE_TBS_SERVICE_DISCONNECTED = 103;

    private static final int REG_TIMEOUT = 10000;

    /**
     * The template class is used to call callback functions on events from the TBS
     * server. Callback functions are wrapped in this class and registered to the
     * Android system during app registration.
     *
     * @hide
     */
    public abstract static class Callback {

        private static final String TAG = "BluetoothLeCallControl.Callback";

        /**
         * Called when a remote client requested to accept the call.
         *
         * <p>
         * An application must call {@link BluetoothLeCallControl#requestResult} to complete the
         * request.
         *
         * @param requestId The Id of the request
         * @param callId    The call Id requested to be accepted
         * @hide
         */
        public abstract void onAcceptCall(int requestId, @NonNull UUID callId);

        /**
         * A remote client has requested to terminate the call.
         *
         * <p>
         * An application must call {@link BluetoothLeCallControl#requestResult} to complete the
         * request.
         *
         * @param requestId The Id of the request
         * @param callId    The call Id requested to terminate
         * @hide
         */
        public abstract void onTerminateCall(int requestId, @NonNull UUID callId);

        /**
         * A remote client has requested to hold the call.
         *
         * <p>
         * An application must call {@link BluetoothLeCallControl#requestResult} to complete the
         * request.
         *
         * @param requestId The Id of the request
         * @param callId    The call Id requested to be put on hold
         * @hide
         */
        public void onHoldCall(int requestId, @NonNull UUID callId) {
            Log.e(TAG, "onHoldCall: unimplemented, however CAPABILITY_HOLD_CALL is set!");
        }

        /**
         * A remote client has requested to unhold the call.
         *
         * <p>
         * An application must call {@link BluetoothLeCallControl#requestResult} to complete the
         * request.
         *
         * @param requestId The Id of the request
         * @param callId    The call Id requested to unhold
         * @hide
         */
        public void onUnholdCall(int requestId, @NonNull UUID callId) {
            Log.e(TAG, "onUnholdCall: unimplemented, however CAPABILITY_HOLD_CALL is set!");
        }

        /**
         * A remote client has requested to place a call.
         *
         * <p>
         * An application must call {@link BluetoothLeCallControl#requestResult} to complete the
         * request.
         *
         * @param requestId The Id of the request
         * @param callId    The Id to be assigned for the new call
         * @param uri       The caller URI requested
         * @hide
         */
        public abstract void onPlaceCall(int requestId, @NonNull UUID callId, @NonNull String uri);

        /**
         * A remote client has requested to join the calls.
         *
         * <p>
         * An application must call {@link BluetoothLeCallControl#requestResult} to complete the
         * request.
         *
         * @param requestId The Id of the request
         * @param callIds   The call Id list requested to join
         * @hide
         */
        public void onJoinCalls(int requestId, @NonNull List<UUID> callIds) {
            Log.e(TAG, "onJoinCalls: unimplemented, however CAPABILITY_JOIN_CALLS is set!");
        }
    }

    private class CallbackWrapper extends IBluetoothLeCallControlCallback.Stub {

        private final Executor mExecutor;
        private final Callback mCallback;

        CallbackWrapper(Executor executor, Callback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onBearerRegistered(int ccid) {
            if (mCallback != null) {
                mCcid = ccid;
            } else {
                // registration timeout
                Log.e(TAG, "onBearerRegistered: mCallback is null");
            }
        }

        @Override
        public void onAcceptCall(int requestId, ParcelUuid uuid) {
            final long identityToken = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onAcceptCall(requestId, uuid.getUuid()));
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
        }

        @Override
        public void onTerminateCall(int requestId, ParcelUuid uuid) {
            final long identityToken = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onTerminateCall(requestId, uuid.getUuid()));
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
        }

        @Override
        public void onHoldCall(int requestId, ParcelUuid uuid) {
            final long identityToken = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onHoldCall(requestId, uuid.getUuid()));
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
        }

        @Override
        public void onUnholdCall(int requestId, ParcelUuid uuid) {
            final long identityToken = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onUnholdCall(requestId, uuid.getUuid()));
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
        }

        @Override
        public void onPlaceCall(int requestId, ParcelUuid uuid, String uri) {
            final long identityToken = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onPlaceCall(requestId, uuid.getUuid(), uri));
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
        }

        @Override
        public void onJoinCalls(int requestId, List<ParcelUuid> parcelUuids) {
            List<UUID> uuids = new ArrayList<>();
            for (ParcelUuid parcelUuid : parcelUuids) {
                uuids.add(parcelUuid.getUuid());
            }

            final long identityToken = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onJoinCalls(requestId, uuids));
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
        }
    };

    private Context mContext;
    private ServiceListener mServiceListener;
    private volatile IBluetoothLeCallControl mService;
    private BluetoothAdapter mAdapter;
    private int mCcid = 0;
    private String mToken;
    private Callback mCallback = null;

    private final IBluetoothStateChangeCallback mBluetoothStateChangeCallback =
        new IBluetoothStateChangeCallback.Stub() {
        public void onBluetoothStateChange(boolean up) {
            if (DBG)
                Log.d(TAG, "onBluetoothStateChange: up=" + up);
            if (!up) {
                doUnbind();
            } else {
                doBind();
            }
        }
    };

    /**
     * Create a BluetoothLeCallControl proxy object for interacting with the local Bluetooth
     * telephone bearer service.
     */
    /* package */ BluetoothLeCallControl(Context context, ServiceListener listener) {
        mContext = context;
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mServiceListener = listener;

        IBluetoothManager mgr = mAdapter.getBluetoothManager();
        if (mgr != null) {
            try {
                mgr.registerStateChangeCallback(mBluetoothStateChangeCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "", e);
            }
        }

        doBind();
    }

    private boolean doBind() {
        synchronized (mConnection) {
            if (mService == null) {
                if (VDBG)
                    Log.d(TAG, "Binding service...");
                try {
                    return mAdapter.getBluetoothManager().
                            bindBluetoothProfileService(BluetoothProfile.LE_CALL_CONTROL,
                            mConnection);
                } catch (RemoteException e) {
                    Log.e(TAG, "Unable to bind TelephoneBearerService", e);
                }
            }
        }
        return false;
    }

    private void doUnbind() {
        synchronized (mConnection) {
            if (mService != null) {
                if (VDBG)
                    Log.d(TAG, "Unbinding service...");
                try {
                    mAdapter.getBluetoothManager().
                        unbindBluetoothProfileService(BluetoothProfile.LE_CALL_CONTROL,
                        mConnection);
                } catch (RemoteException e) {
                    Log.e(TAG, "Unable to unbind TelephoneBearerService", e);
                } finally {
                    mService = null;
                }
            }
        }
    }

    /* package */ void close() {
        if (VDBG)
            log("close()");
        unregisterBearer();

        IBluetoothManager mgr = mAdapter.getBluetoothManager();
        if (mgr != null) {
            try {
                mgr.unregisterStateChangeCallback(mBluetoothStateChangeCallback);
            } catch (RemoteException re) {
                Log.e(TAG, "", re);
            }
        }
        mServiceListener = null;
        doUnbind();
    }

    private IBluetoothLeCallControl getService() {
        return mService;
    }

    /**
     * Not supported
     *
     * @throws UnsupportedOperationException
     */
    @Override
    public int getConnectionState(@Nullable BluetoothDevice device) {
        throw new UnsupportedOperationException("not supported");
    }

    /**
     * Not supported
     *
     * @throws UnsupportedOperationException
     */
    @Override
    public @NonNull List<BluetoothDevice> getConnectedDevices() {
        throw new UnsupportedOperationException("not supported");
    }

    /**
     * Not supported
     *
     * @throws UnsupportedOperationException
     */
    @Override
    public @NonNull List<BluetoothDevice> getDevicesMatchingConnectionStates(
        @NonNull int[] states) {
        throw new UnsupportedOperationException("not supported");
    }

    /**
     * Register Telephone Bearer exposing the interface that allows remote devices
     * to track and control the call states.
     *
     * <p>
     * This is an asynchronous call. The callback is used to notify success or
     * failure if the function returns true.
     *
     * <p>
     * Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * <!-- The UCI is a String identifier of the telephone bearer as defined at
     * https://www.bluetooth.com/specifications/assigned-numbers/uniform-caller-identifiers
     * (login required). -->
     *
     * <!-- The examples of common URI schemes can be found in
     * https://iana.org/assignments/uri-schemes/uri-schemes.xhtml -->
     *
     * <!-- The Technology is an integer value. The possible values are defined at
     * https://www.bluetooth.com/specifications/assigned-numbers (login required).
     * -->
     *
     * @param uci          Bearer Unique Client Identifier
     * @param uriSchemes   URI Schemes supported list
     * @param capabilities bearer capabilities
     * @param provider     Network provider name
     * @param technology   Network technology
     * @param executor     {@link Executor} object on which callback will be
     *                     executed. The Executor object is required.
     * @param callback     {@link Callback} object to which callback messages will
     *                     be sent. The Callback object is required.
     * @return true on success, false otherwise
     * @hide
     */
    @SuppressLint("ExecutorRegistration")
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public boolean registerBearer(@Nullable String uci,
                    @NonNull List<String> uriSchemes, int capabilities,
                    @NonNull String provider, int technology,
                    @NonNull Executor executor, @NonNull Callback callback) {
        if (DBG) {
            Log.d(TAG, "registerBearer");
        }
        if (callback == null) {
            throw new IllegalArgumentException("null parameter: " + callback);
        }
        if (mCcid != 0) {
            return false;
        }

        mToken = uci;

        final IBluetoothLeCallControl service = getService();
        if (service != null) {
            if (mCallback != null) {
                Log.e(TAG, "Bearer can be opened only once");
                return false;
            }

            mCallback = callback;
            try {
                CallbackWrapper callbackWrapper = new CallbackWrapper(executor, callback);
                service.registerBearer(mToken, callbackWrapper, uci, uriSchemes, capabilities,
                                        provider, technology);
            } catch (RemoteException e) {
                Log.e(TAG, "", e);
                mCallback = null;
                return false;
            }

            if (mCcid == 0) {
                mCallback = null;
                return false;
            }

            return true;
        }

        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
        }

        return false;
    }

    /**
     * Unregister Telephone Bearer Service and destroy all the associated data.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public void unregisterBearer() {
        if (DBG) {
            Log.d(TAG, "unregisterBearer");
        }
        if (mCcid == 0) {
            return;
        }

        int ccid = mCcid;
        mCcid = 0;
        mCallback = null;

        final IBluetoothLeCallControl service = getService();
        if (service != null) {
            try {
                service.unregisterBearer(mToken);
            } catch (RemoteException e) {
                Log.e(TAG, "", e);
            }
        }
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
        }
    }

    /**
     * Get the Content Control ID (CCID) value.
     *
     * @return ccid Content Control ID value
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public int getContentControlId() {
        return mCcid;
    }

    /**
     * Notify about the newly added call.
     *
     * <p>
     * This shall be called as early as possible after the call has been added.
     *
     * <p>
     * Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param call Newly added call
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public void onCallAdded(@NonNull BluetoothLeCall call) {
        if (DBG) {
            Log.d(TAG, "onCallAdded: call=" + call);
        }
        if (mCcid == 0) {
            return;
        }

        final IBluetoothLeCallControl service = getService();
        if (service != null) {
            try {
                service.callAdded(mCcid, call);
            } catch (RemoteException e) {
                Log.e(TAG, "", e);
            }
        }
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
        }
    }

    /**
     * Notify about the removed call.
     *
     * <p>
     * This shall be called as early as possible after the call has been removed.
     *
     * <p>
     * Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param callId The Id of a call that has been removed
     * @param reason Call termination reason
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public void onCallRemoved(@NonNull UUID callId, @TerminationReason int reason) {
        if (DBG) {
            Log.d(TAG, "callRemoved: callId=" + callId);
        }
        if (mCcid == 0) {
            return;
        }

        final IBluetoothLeCallControl service = getService();
        if (service != null) {
            try {
                service.callRemoved(mCcid, new ParcelUuid(callId), reason);
            } catch (RemoteException e) {
                Log.e(TAG, "", e);
            }
        }
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
        }
    }

    /**
     * Notify the call state change
     *
     * <p>
     * This shall be called as early as possible after the state of the call has
     * changed.
     *
     * <p>
     * Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param callId The call Id that state has been changed
     * @param state  Call state
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public void onCallStateChanged(@NonNull UUID callId, @BluetoothLeCall.State int state) {
        if (DBG) {
            Log.d(TAG, "callStateChanged: callId=" + callId + " state=" + state);
        }
        if (mCcid == 0) {
            return;
        }

        final IBluetoothLeCallControl service = getService();
        if (service != null) {
            try {
                service.callStateChanged(mCcid, new ParcelUuid(callId), state);
            } catch (RemoteException e) {
                Log.e(TAG, "", e);
            }
        }
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
        }
    }

    /**
     * Provide the current calls list
     *
     * <p>
     * This function must be invoked after registration if application has any
     * calls.
     *
     * @param calls current calls list
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
     public void currentCallsList(@NonNull List<BluetoothLeCall> calls) {
        final IBluetoothLeCallControl service = getService();
        if (service != null) {
            try {
                service.currentCallsList(mCcid, calls);
            } catch (RemoteException e) {
                Log.e(TAG, "", e);
            }
        }
    }

    /**
     * Provide the network current status
     *
     * <p>
     * This function must be invoked on change of network state.
     *
     * <p>
     * Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * <!-- The Technology is an integer value. The possible values are defined at
     * https://www.bluetooth.com/specifications/assigned-numbers (login required).
     * -->
     *
     * @param provider   Network provider name
     * @param technology Network technology
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public void networkStateChanged(@NonNull String provider, int technology) {
        if (DBG) {
            Log.d(TAG, "networkStateChanged: provider=" + provider + ", technology=" + technology);
        }
        if (mCcid == 0) {
            return;
        }

        final IBluetoothLeCallControl service = getService();
        if (service != null) {
            try {
                service.networkStateChanged(mCcid, provider, technology);
            } catch (RemoteException e) {
                Log.e(TAG, "", e);
            }
        }
        if (service == null) {
            Log.w(TAG, "Proxy not attached to service");
        }
    }

    /**
     * Send a response to a call control request to a remote device.
     *
     * <p>
     * This function must be invoked in when a request is received by one of these
     * callback methods:
     *
     * <ul>
     * <li>{@link Callback#onAcceptCall}
     * <li>{@link Callback#onTerminateCall}
     * <li>{@link Callback#onHoldCall}
     * <li>{@link Callback#onUnholdCall}
     * <li>{@link Callback#onPlaceCall}
     * <li>{@link Callback#onJoinCalls}
     * </ul>
     *
     * @param requestId The ID of the request that was received with the callback
     * @param result    The result of the request to be sent to the remote devices
     */
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public void requestResult(int requestId, @Result int result) {
        if (DBG) {
            Log.d(TAG, "requestResult: requestId=" + requestId + " result=" + result);
        }
        if (mCcid == 0) {
            return;
        }

        final IBluetoothLeCallControl service = getService();
        if (service != null) {
            try {
                service.requestResult(mCcid, requestId, result);
            } catch (RemoteException e) {
                Log.e(TAG, "", e);
            }
        }
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    private static boolean isValidDevice(@Nullable BluetoothDevice device) {
        return device != null && BluetoothAdapter.checkBluetoothAddress(device.getAddress());
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }

    private final IBluetoothProfileServiceConnection mConnection =
                                    new IBluetoothProfileServiceConnection.Stub() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            if (DBG) {
                Log.d(TAG, "Proxy object connected");
            }
            mService = IBluetoothLeCallControl.Stub.asInterface(Binder.allowBlocking(service));
            mHandler.sendMessage(mHandler.obtainMessage(MESSAGE_TBS_SERVICE_CONNECTED));
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            if (DBG) {
                Log.d(TAG, "Proxy object disconnected");
            }
            doUnbind();
            mHandler.sendMessage(mHandler.obtainMessage(MESSAGE_TBS_SERVICE_DISCONNECTED));
        }
    };

    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_TBS_SERVICE_CONNECTED: {
                if (mServiceListener != null) {
                    mServiceListener.onServiceConnected(BluetoothProfile.LE_CALL_CONTROL,
                        BluetoothLeCallControl.this);
                }
                break;
            }
            case MESSAGE_TBS_SERVICE_DISCONNECTED: {
                if (mServiceListener != null) {
                    mServiceListener.onServiceDisconnected(BluetoothProfile.LE_CALL_CONTROL);
                }
                break;
            }
            }
        }
    };
}
