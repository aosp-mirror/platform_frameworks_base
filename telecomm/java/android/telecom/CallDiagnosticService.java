/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.telecom;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.Annotation;
import android.telephony.ims.ImsReasonInfo;
import android.util.ArrayMap;

import com.android.internal.telecom.ICallDiagnosticService;
import com.android.internal.telecom.ICallDiagnosticServiceAdapter;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The platform supports a single OEM provided {@link CallDiagnosticService}, as defined by the
 * {@code call_diagnostic_service_package_name} key in the
 * {@code packages/services/Telecomm/res/values/config.xml} file.  An OEM can use this API to help
 * provide more actionable information about calling issues the user encounters during and after
 * a call.
 *
 * <h1>Manifest Declaration</h1>
 * The following is an example of how to declare the service entry in the
 * {@link CallDiagnosticService} manifest file:
 * <pre>
 * {@code
 * <service android:name="your.package.YourCallDiagnosticServiceImplementation"
 *          android:permission="android.permission.BIND_CALL_DIAGNOSTIC_SERVICE">
 *      <intent-filter>
 *          <action android:name="android.telecom.CallDiagnosticService"/>
 *      </intent-filter>
 * </service>
 * }
 * </pre>
 * <p>
 * <h2>Threading Model</h2>
 * By default, all incoming IPC from Telecom in this service and in the {@link DiagnosticCall}
 * instances will take place on the main thread.  You can override {@link #getExecutor()} in your
 * implementation to provide your own {@link Executor}.
 * @hide
 */
@SystemApi
public abstract class CallDiagnosticService extends Service {

    /**
     * Binder stub implementation which handles incoming requests from Telecom.
     */
    private final class CallDiagnosticServiceBinder extends ICallDiagnosticService.Stub {

        @Override
        public void setAdapter(ICallDiagnosticServiceAdapter adapter) throws RemoteException {
            handleSetAdapter(adapter);
        }

        @Override
        public void initializeDiagnosticCall(ParcelableCall call) throws RemoteException {
            handleCallAdded(call);
        }

        @Override
        public void updateCall(ParcelableCall call) throws RemoteException {
            handleCallUpdated(call);
        }

        @Override
        public void removeDiagnosticCall(String callId) throws RemoteException {
            handleCallRemoved(callId);
        }

        @Override
        public void updateCallAudioState(CallAudioState callAudioState) throws RemoteException {
            getExecutor().execute(() -> onCallAudioStateChanged(callAudioState));
        }

        @Override
        public void receiveDeviceToDeviceMessage(String callId, int message, int value) {
            handleReceivedD2DMessage(callId, message, value);
        }

        @Override
        public void receiveBluetoothCallQualityReport(BluetoothCallQualityReport qualityReport)
                throws RemoteException {
            handleBluetoothCallQualityReport(qualityReport);
        }

        @Override
        public void notifyCallDisconnected(@NonNull String callId,
                @NonNull DisconnectCause disconnectCause) throws RemoteException {
            handleCallDisconnected(callId, disconnectCause);
        }
    }

    /**
     * Listens to events raised by a {@link DiagnosticCall}.
     */
    private android.telecom.DiagnosticCall.Listener mDiagnosticCallListener =
            new android.telecom.DiagnosticCall.Listener() {

                @Override
                public void onSendDeviceToDeviceMessage(DiagnosticCall diagnosticCall,
                        @DiagnosticCall.MessageType int message, int value) {
                    handleSendDeviceToDeviceMessage(diagnosticCall, message, value);
                }

                @Override
                public void onDisplayDiagnosticMessage(DiagnosticCall diagnosticCall, int messageId,
                        CharSequence message) {
                    handleDisplayDiagnosticMessage(diagnosticCall, messageId, message);
                }

                @Override
                public void onClearDiagnosticMessage(DiagnosticCall diagnosticCall, int messageId) {
                    handleClearDiagnosticMessage(diagnosticCall, messageId);
                }
            };

    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.telecom.CallDiagnosticService";

    /**
     * Map which tracks the Telecom calls received from the Telecom stack.
     */
    private final Map<String, Call.Details> mCallByTelecomCallId = new ArrayMap<>();
    private final Map<String, DiagnosticCall> mDiagnosticCallByTelecomCallId = new ArrayMap<>();
    private final Object mLock = new Object();
    private ICallDiagnosticServiceAdapter mAdapter;

    /**
     * Handles binding to the {@link CallDiagnosticService}.
     *
     * @param intent The Intent that was used to bind to this service,
     * as given to {@link android.content.Context#bindService
     * Context.bindService}.  Note that any extras that were included with
     * the Intent at that point will <em>not</em> be seen here.
     * @return
     */
    @Nullable
    @Override
    public IBinder onBind(@NonNull Intent intent) {
        Log.i(this, "onBind!");
        return new CallDiagnosticServiceBinder();
    }

    /**
     * Returns the {@link Executor} to use for incoming IPS from Telecom into your service
     * implementation.
     * <p>
     * Override this method in your {@link CallDiagnosticService} implementation to provide the
     * executor you want to use for incoming IPC.
     *
     * @return the {@link Executor} to use for incoming IPC from Telecom to
     * {@link CallDiagnosticService} and {@link DiagnosticCall}.
     */
    @SuppressLint("OnNameExpected")
    @NonNull public Executor getExecutor() {
        return new HandlerExecutor(Handler.createAsync(getMainLooper()));
    }

    /**
     * Telecom calls this method on the {@link CallDiagnosticService} with details about a new call
     * which was added to Telecom.
     * <p>
     * The {@link CallDiagnosticService} returns an implementation of {@link DiagnosticCall} to be
     * used for the lifespan of this call.
     * <p>
     * Calls to this method will use the {@link CallDiagnosticService}'s {@link Executor}; see
     * {@link CallDiagnosticService#getExecutor()} for more information.
     *
     * @param call The details of the new call.
     * @return An instance of {@link DiagnosticCall} which the {@link CallDiagnosticService}
     * provides to be used for the lifespan of the call.
     * @throws IllegalArgumentException if a {@code null} {@link DiagnosticCall} is returned.
     */
    public abstract @NonNull DiagnosticCall onInitializeDiagnosticCall(@NonNull
            android.telecom.Call.Details call);

    /**
     * Telecom calls this method when a previous created {@link DiagnosticCall} is no longer needed.
     * This happens when Telecom is no longer tracking the call in question.
     * <p>
     * Calls to this method will use the {@link CallDiagnosticService}'s {@link Executor}; see
     * {@link CallDiagnosticService#getExecutor()} for more information.
     *
     * @param call The diagnostic call which is no longer tracked by Telecom.
     */
    public abstract void onRemoveDiagnosticCall(@NonNull DiagnosticCall call);

    /**
     * Telecom calls this method when the audio routing or available audio route information
     * changes.
     * <p>
     * Audio state is common to all calls.
     * <p>
     * Calls to this method will use the {@link CallDiagnosticService}'s {@link Executor}; see
     * {@link CallDiagnosticService#getExecutor()} for more information.
     *
     * @param audioState The new audio state.
     */
    public abstract void onCallAudioStateChanged(
            @NonNull CallAudioState audioState);

    /**
     * Telecom calls this method when a {@link BluetoothCallQualityReport} is received from the
     * bluetooth stack.
     * <p>
     * Calls to this method will use the {@link CallDiagnosticService}'s {@link Executor}; see
     * {@link CallDiagnosticService#getExecutor()} for more information.
     *
     * @param qualityReport the {@link BluetoothCallQualityReport}.
     */
    public abstract void onBluetoothCallQualityReportReceived(
            @NonNull BluetoothCallQualityReport qualityReport);

    /**
     * Handles a request from Telecom to set the adapater used to communicate back to Telecom.
     * @param adapter
     */
    private void handleSetAdapter(@NonNull ICallDiagnosticServiceAdapter adapter) {
        mAdapter = adapter;
    }

    /**
     * Handles a request from Telecom to add a new call.
     * @param parcelableCall
     */
    private void handleCallAdded(@NonNull ParcelableCall parcelableCall) {
        String telecomCallId = parcelableCall.getId();
        Log.i(this, "handleCallAdded: callId=%s - added", telecomCallId);
        Call.Details newCallDetails = Call.Details.createFromParcelableCall(parcelableCall);
        synchronized (mLock) {
            mCallByTelecomCallId.put(telecomCallId, newCallDetails);
        }

        getExecutor().execute(() -> {
            DiagnosticCall diagnosticCall = onInitializeDiagnosticCall(newCallDetails);
            if (diagnosticCall == null) {
                throw new IllegalArgumentException(
                        "A valid DiagnosticCall instance was not provided.");
            }
            synchronized (mLock) {
                diagnosticCall.setListener(mDiagnosticCallListener);
                diagnosticCall.setCallId(telecomCallId);
                mDiagnosticCallByTelecomCallId.put(telecomCallId, diagnosticCall);
            }
        });
    }

    /**
     * Handles an update to {@link Call.Details} notified by Telecom.
     * Caches the call details and notifies the {@link DiagnosticCall} of the change via
     * {@link DiagnosticCall#onCallDetailsChanged(Call.Details)}.
     * @param parcelableCall the new parceled call details from Telecom.
     */
    private void handleCallUpdated(@NonNull ParcelableCall parcelableCall) {
        String telecomCallId = parcelableCall.getId();
        Log.i(this, "handleCallUpdated: callId=%s - updated", telecomCallId);
        Call.Details newCallDetails = Call.Details.createFromParcelableCall(parcelableCall);
        DiagnosticCall diagnosticCall;
        synchronized (mLock) {
            diagnosticCall = mDiagnosticCallByTelecomCallId.get(telecomCallId);
            mCallByTelecomCallId.put(telecomCallId, newCallDetails);
        }
        getExecutor().execute(() -> diagnosticCall.handleCallUpdated(newCallDetails));
    }

    /**
     * Handles a request from Telecom to remove an existing call.
     * @param telecomCallId
     */
    private void handleCallRemoved(@NonNull String telecomCallId) {
        Log.i(this, "handleCallRemoved: callId=%s - removed", telecomCallId);

        if (mCallByTelecomCallId.containsKey(telecomCallId)) {
            mCallByTelecomCallId.remove(telecomCallId);
        }

        DiagnosticCall diagnosticCall;
        synchronized (mLock) {
            if (mDiagnosticCallByTelecomCallId.containsKey(telecomCallId)) {
                diagnosticCall = mDiagnosticCallByTelecomCallId.remove(telecomCallId);
            } else {
                diagnosticCall = null;
            }
        }

        // Inform the service of the removed call.
        if (diagnosticCall != null) {
            getExecutor().execute(() -> onRemoveDiagnosticCall(diagnosticCall));
        }
    }

    /**
     * Handles an incoming device to device message received from Telecom.  Notifies the
     * {@link DiagnosticCall} via {@link DiagnosticCall#onReceiveDeviceToDeviceMessage(int, int)}.
     * @param callId
     * @param message
     * @param value
     */
    private void handleReceivedD2DMessage(@NonNull String callId, int message, int value) {
        Log.i(this, "handleReceivedD2DMessage: callId=%s, msg=%d/%d", callId, message, value);
        DiagnosticCall diagnosticCall;
        synchronized (mLock) {
            diagnosticCall = mDiagnosticCallByTelecomCallId.get(callId);
        }
        if (diagnosticCall != null) {
            getExecutor().execute(
                    () -> diagnosticCall.onReceiveDeviceToDeviceMessage(message, value));
        }
    }

    /**
     * Handles a request from the Telecom framework to get a disconnect message from the
     * {@link CallDiagnosticService}.
     * @param callId The ID of the call.
     * @param disconnectCause The telecom disconnect cause.
     */
    private void handleCallDisconnected(@NonNull String callId,
            @NonNull DisconnectCause disconnectCause) {
        Log.i(this, "handleCallDisconnected: call=%s; cause=%s", callId, disconnectCause);
        DiagnosticCall diagnosticCall = mDiagnosticCallByTelecomCallId.get(callId);
        CharSequence message;
        if (disconnectCause.getImsReasonInfo() != null) {
            message = diagnosticCall.onCallDisconnected(disconnectCause.getImsReasonInfo());
        } else {
            message = diagnosticCall.onCallDisconnected(
                    disconnectCause.getTelephonyDisconnectCause(),
                    disconnectCause.getTelephonyPreciseDisconnectCause());
        }
        try {
            mAdapter.overrideDisconnectMessage(callId, message);
        } catch (RemoteException e) {
            Log.w(this, "handleCallDisconnected: call=%s; cause=%s; %s",
                    callId, disconnectCause, e);
        }
    }

    /**
     * Handles an incoming bluetooth call quality report from Telecom.  Notifies via
     * {@link CallDiagnosticService#onBluetoothCallQualityReportReceived(
     * BluetoothCallQualityReport)}.
     * @param qualityReport The bluetooth call quality remote.
     */
    private void handleBluetoothCallQualityReport(@NonNull BluetoothCallQualityReport
            qualityReport) {
        Log.i(this, "handleBluetoothCallQualityReport; report=%s", qualityReport);
        getExecutor().execute(() -> onBluetoothCallQualityReportReceived(qualityReport));
    }

    /**
     * Handles a request from a {@link DiagnosticCall} to send a device to device message (received
     * via {@link DiagnosticCall#sendDeviceToDeviceMessage(int, int)}.
     * @param diagnosticCall
     * @param message
     * @param value
     */
    private void handleSendDeviceToDeviceMessage(@NonNull DiagnosticCall diagnosticCall,
            int message, int value) {
        String callId = diagnosticCall.getCallId();
        try {
            mAdapter.sendDeviceToDeviceMessage(callId, message, value);
            Log.i(this, "handleSendDeviceToDeviceMessage: call=%s; msg=%d/%d", callId, message,
                    value);
        } catch (RemoteException e) {
            Log.w(this, "handleSendDeviceToDeviceMessage: call=%s; msg=%d/%d failed %s",
                    callId, message, value, e);
        }
    }

    /**
     * Handles a request from a {@link DiagnosticCall} to display an in-call diagnostic message.
     * Originates from {@link DiagnosticCall#displayDiagnosticMessage(int, CharSequence)}.
     * @param diagnosticCall
     * @param messageId
     * @param message
     */
    private void handleDisplayDiagnosticMessage(DiagnosticCall diagnosticCall, int messageId,
            CharSequence message) {
        String callId = diagnosticCall.getCallId();
        try {
            mAdapter.displayDiagnosticMessage(callId, messageId, message);
            Log.i(this, "handleDisplayDiagnosticMessage: call=%s; msg=%d/%s", callId, messageId,
                    message);
        } catch (RemoteException e) {
            Log.w(this, "handleDisplayDiagnosticMessage: call=%s; msg=%d/%s failed %s",
                    callId, messageId, message, e);
        }
    }

    /**
     * Handles a request from a {@link DiagnosticCall} to clear a previously shown diagnostic
     * message.
     * Originates from {@link DiagnosticCall#clearDiagnosticMessage(int)}.
     * @param diagnosticCall
     * @param messageId
     */
    private void handleClearDiagnosticMessage(DiagnosticCall diagnosticCall, int messageId) {
        String callId = diagnosticCall.getCallId();
        try {
            mAdapter.clearDiagnosticMessage(callId, messageId);
            Log.i(this, "handleClearDiagnosticMessage: call=%s; msg=%d", callId, messageId);
        } catch (RemoteException e) {
            Log.w(this, "handleClearDiagnosticMessage: call=%s; msg=%d failed %s",
                    callId, messageId, e);
        }
    }
}
