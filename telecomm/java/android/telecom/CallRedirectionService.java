/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package android.telecom;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecom.ICallRedirectionAdapter;
import com.android.internal.telecom.ICallRedirectionService;

/**
 * This service can be implemented to interact between Telecom and its implementor
 * for making outgoing call with optional redirection/cancellation purposes.
 *
 * <p>
 * Below is an example manifest registration for a {@code CallRedirectionService}.
 * {@code
 * <service android:name="your.package.YourCallRedirectionServiceImplementation"
 *          android:permission="android.permission.BIND_CALL_REDIRECTION_SERVICE">
 *      <intent-filter>
 *          <action android:name="android.telecom.CallRedirectionService"/>
 *      </intent-filter>
 * </service>
 * }
 */
public abstract class CallRedirectionService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.telecom.CallRedirectionService";

    /**
     * An adapter to inform Telecom the response from the implementor of the Call
     * Redirection service
     */
    private ICallRedirectionAdapter mCallRedirectionAdapter;

    /**
     * Telecom calls this method once upon binding to a {@link CallRedirectionService} to inform
     * it of a new outgoing call which is being placed. Telecom does not request to redirect
     * emergency calls and does not request to redirect calls with gateway information.
     *
     * <p>Telecom will cancel the call if Telecom does not receive a response in 5 seconds from
     * the implemented {@link CallRedirectionService} set by users.
     *
     * <p>The implemented {@link CallRedirectionService} can call {@link #placeCallUnmodified()},
     * {@link #redirectCall(Uri, PhoneAccountHandle, boolean)}, and {@link #cancelCall()} only
     * from here. Calls to these methods are assumed by the Telecom framework to be the response
     * for the phone call for which {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)} was
     * invoked by Telecom. The Telecom framework will only invoke
     * {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)} once each time it binds to a
     * {@link CallRedirectionService}.
     *
     * @param handle the phone number dialed by the user, represented in E.164 format if possible
     * @param initialPhoneAccount the {@link PhoneAccountHandle} on which the call will be placed.
     * @param allowInteractiveResponse a boolean to tell if the implemented
     *                                 {@link CallRedirectionService} should allow interactive
     *                                 responses with users. Will be {@code false} if, for example
     *                                 the device is in car mode and the user would not be able to
     *                                 interact with their device.
     */
    public abstract void onPlaceCall(@NonNull Uri handle,
                                     @NonNull PhoneAccountHandle initialPhoneAccount,
                                     boolean allowInteractiveResponse);

    /**
     * The implemented {@link CallRedirectionService} calls this method to response a request
     * received via {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)} to inform Telecom that
     * no changes are required to the outgoing call, and that the call should be placed as-is.
     *
     * <p>This can only be called from implemented
     * {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)}. The response corresponds to the
     * latest request via {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)}.
     *
     */
    public final void placeCallUnmodified() {
        try {
            if (mCallRedirectionAdapter == null) {
                throw new IllegalStateException("Can only be called from onPlaceCall.");
            }
            mCallRedirectionAdapter.placeCallUnmodified();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * The implemented {@link CallRedirectionService} calls this method to response a request
     * received via {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)} to inform Telecom that
     * changes are required to the phone number or/and {@link PhoneAccountHandle} for the outgoing
     * call. Telecom will cancel the call if the implemented {@link CallRedirectionService}
     * replies Telecom a handle for an emergency number.
     *
     * <p>This can only be called from implemented
     * {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)}. The response corresponds to the
     * latest request via {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)}.
     *
     * @param gatewayUri the gateway uri for call redirection.
     * @param targetPhoneAccount the {@link PhoneAccountHandle} to use when placing the call.
     * @param confirmFirst Telecom will ask users to confirm the redirection via a yes/no dialog
     *                     if the confirmFirst is true, and if the redirection request of this
     *                     response was sent with a true flag of allowInteractiveResponse via
     *                     {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)}
     */
    public final void redirectCall(@NonNull Uri gatewayUri,
                                   @NonNull PhoneAccountHandle targetPhoneAccount,
                                   boolean confirmFirst) {
        try {
            if (mCallRedirectionAdapter == null) {
                throw new IllegalStateException("Can only be called from onPlaceCall.");
            }
            mCallRedirectionAdapter.redirectCall(gatewayUri, targetPhoneAccount, confirmFirst);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * The implemented {@link CallRedirectionService} calls this method to response a request
     * received via {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)} to inform Telecom that
     * an outgoing call should be canceled entirely.
     *
     * <p>This can only be called from implemented
     * {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)}. The response corresponds to the
     * latest request via {@link #onPlaceCall(Uri, PhoneAccountHandle, boolean)}.
     *
     */
    public final void cancelCall() {
        try {
            if (mCallRedirectionAdapter == null) {
                throw new IllegalStateException("Can only be called from onPlaceCall.");
            }
            mCallRedirectionAdapter.cancelCall();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * A handler message to process the attempt to place call with redirection service from Telecom
     */
    private static final int MSG_PLACE_CALL = 1;

    /**
     * A handler to process the attempt to place call with redirection service from Telecom
     */
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PLACE_CALL:
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        mCallRedirectionAdapter = (ICallRedirectionAdapter) args.arg1;
                        onPlaceCall((Uri) args.arg2, (PhoneAccountHandle) args.arg3,
                                (boolean) args.arg4);
                    } finally {
                        args.recycle();
                    }
                    break;
            }
        }
    };

    private final class CallRedirectionBinder extends ICallRedirectionService.Stub {

        /**
         * Telecom calls this method to inform the CallRedirectionService of a new outgoing call
         * which is about to be placed.
         * @param handle the phone number dialed by the user
         * @param initialPhoneAccount the URI of the number the user dialed
         * @param allowInteractiveResponse a boolean to tell if the implemented
         *                                 {@link CallRedirectionService} should allow interactive
         *                                 responses with users.
         */
        @Override
        public void placeCall(@NonNull ICallRedirectionAdapter adapter, @NonNull Uri handle,
                              @NonNull PhoneAccountHandle initialPhoneAccount,
                              boolean allowInteractiveResponse) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = adapter;
            args.arg2 = handle;
            args.arg3 = initialPhoneAccount;
            args.arg4 = allowInteractiveResponse;
            mHandler.obtainMessage(MSG_PLACE_CALL, args).sendToTarget();
        }
    }

    @Override
    public final @Nullable IBinder onBind(@NonNull Intent intent) {
        return new CallRedirectionBinder();
    }

    @Override
    public final boolean onUnbind(@NonNull Intent intent) {
        return false;
    }
}
