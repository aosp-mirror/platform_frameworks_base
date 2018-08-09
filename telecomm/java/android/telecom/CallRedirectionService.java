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
import com.android.internal.telecom.ICallRedirectionService;
import com.android.internal.telecom.ICallRedirectionAdapter;

/**
 * This service can be implemented to interact between Telecom and its implementor
 * for making outgoing call with optional redirection/cancellation purposes.
 *
 * <p>
 * Below is an example manifest registration for a {@code CallRedirectionService}.
 * <pre>
 * {@code
 * <service android:name="your.package.YourCallRedirectionServiceImplementation"
 *          android:permission="android.permission.BIND_REDIRECTION_SERVICE">
 *      <intent-filter>
 *          <action android:name="android.telecom.CallRedirectionService"/>
 *      </intent-filter>
 * </service>
 * }
 * </pre>
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
     * Telecom calls this method to inform the implemented {@link CallRedirectionService} of
     * a new outgoing call which is being placed.
     *
     * The implemented {@link CallRedirectionService} can call {@link #placeCallUnmodified()},
     * {@link #redirectCall(Uri, PhoneAccountHandle)}, and {@link #cancelCall()} only from here.
     *
     * @param handle the phone number dialed by the user
     * @param targetPhoneAccount the {@link PhoneAccountHandle} on which the call will be placed.
     */
    public abstract void onPlaceCall(Uri handle, PhoneAccountHandle targetPhoneAccount);

    /**
     * The implemented {@link CallRedirectionService} calls this method to response a request
     * received via {@link #onPlaceCall(Uri, PhoneAccountHandle)} to inform Telecom that no changes
     * are required to the outgoing call, and that the call should be placed as-is.
     *
     * This can only be called from implemented {@link #onPlaceCall(Uri, PhoneAccountHandle)}.
     *
     */
    public final void placeCallUnmodified() {
        try {
            mCallRedirectionAdapter.placeCallUnmodified();
        } catch (RemoteException e) {
        }
    }

    /**
     * The implemented {@link CallRedirectionService} calls this method to response a request
     * received via {@link #onPlaceCall(Uri, PhoneAccountHandle)} to inform Telecom that changes
     * are required to the phone number or/and {@link PhoneAccountHandle} for the outgoing call.
     *
     * This can only be called from implemented {@link #onPlaceCall(Uri, PhoneAccountHandle)}.
     *
     * @param handle the new phone number to dial
     * @param targetPhoneAccount the {@link PhoneAccountHandle} to use when placing the call.
     *                           If {@code null}, no change will be made to the
     *                           {@link PhoneAccountHandle} used to place the call.
     */
    public final void redirectCall(Uri handle, PhoneAccountHandle targetPhoneAccount) {
        try {
            mCallRedirectionAdapter.redirectCall(handle, targetPhoneAccount);
        } catch (RemoteException e) {
        }
    }

    /**
     * The implemented {@link CallRedirectionService} calls this method to response a request
     * received via {@link #onPlaceCall(Uri, PhoneAccountHandle)} to inform Telecom that an outgoing
     * call should be canceled entirely.
     *
     * This can only be called from implemented {@link #onPlaceCall(Uri, PhoneAccountHandle)}.
     *
     */
    public final void cancelCall() {
        try {
            mCallRedirectionAdapter.cancelCall();
        } catch (RemoteException e) {
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
                        onPlaceCall((Uri) args.arg2, (PhoneAccountHandle) args.arg3);
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
         * @param targetPhoneAccount the URI of the number the user dialed
         */
        @Override
        public void placeCall(ICallRedirectionAdapter adapter, Uri handle,
                              PhoneAccountHandle targetPhoneAccount) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = adapter;
            args.arg2 = handle;
            args.arg3 = targetPhoneAccount;
            mHandler.obtainMessage(MSG_PLACE_CALL, args).sendToTarget();
        }
    }

    @Override
    public final IBinder onBind(Intent intent) {
        return new CallRedirectionBinder();
    }

    @Override
    public final boolean onUnbind(Intent intent) {
        return false;
    }
}
