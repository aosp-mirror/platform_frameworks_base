/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.annotation.SdkConstant;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecom.ICallScreeningAdapter;
import com.android.internal.telecom.ICallScreeningService;

/**
 * This service can be implemented by the default dialer (see
 * {@link TelecomManager#getDefaultDialerPackage()}) or a third party app to allow or disallow
 * incoming calls before they are shown to a user.  This service can also provide
 * {@link CallIdentification} information for calls.
 * <p>
 * Below is an example manifest registration for a {@code CallScreeningService}.
 * <pre>
 * {@code
 * <service android:name="your.package.YourCallScreeningServiceImplementation"
 *          android:permission="android.permission.BIND_SCREENING_SERVICE">
 *      <intent-filter>
 *          <action android:name="android.telecom.CallScreeningService"/>
 *      </intent-filter>
 * </service>
 * }
 * </pre>
 * <p>
 * A CallScreeningService performs two functions:
 * <ol>
 *     <li>Call blocking/screening - the service can choose which calls will ring on the user's
 *     device, and which will be silently sent to voicemail.</li>
 *     <li>Call identification - the service can optionally provide {@link CallIdentification}
 *     information about a {@link Call.Details call} which will be shown to the user in the
 *     Dialer app.</li>
 * </ol>
 * <p>
 * <h2>Becoming the {@link CallScreeningService}</h2>
 * Telecom will bind to a single app chosen by the user which implements the
 * {@link CallScreeningService} API when there are new incoming and outgoing calls.
 * <p>
 * The code snippet below illustrates how your app can request that it fills the call screening
 * role.
 * <pre>
 * {@code
 * private static final int REQUEST_ID = 1;
 *
 * public void requestRole() {
 *     RoleManager roleManager = (RoleManager) getSystemService(ROLE_SERVICE);
 *     Intent intent = roleManager.createRequestRoleIntent("android.app.role.CALL_SCREENING_APP");
 *     startActivityForResult(intent, REQUEST_ID);
 * }
 *
 * &#64;Override
 * public void onActivityResult(int requestCode, int resultCode, Intent data) {
 *     if (requestCode == REQUEST_ID) {
 *         if (resultCode == android.app.Activity.RESULT_OK) {
 *             // Your app is now the call screening app
 *         } else {
 *             // Your app is not the call screening app
 *         }
 *     }
 * }
 * </pre>
 */
public abstract class CallScreeningService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.telecom.CallScreeningService";

    private static final int MSG_SCREEN_CALL = 1;

    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SCREEN_CALL:
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        mCallScreeningAdapter = (ICallScreeningAdapter) args.arg1;
                        onScreenCall(
                                Call.Details.createFromParcelableCall((ParcelableCall) args.arg2));
                    } finally {
                        args.recycle();
                    }
                    break;
            }
        }
    };

    private final class CallScreeningBinder extends ICallScreeningService.Stub {
        @Override
        public void screenCall(ICallScreeningAdapter adapter, ParcelableCall call) {
            Log.v(this, "screenCall");
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = adapter;
            args.arg2 = call;
            mHandler.obtainMessage(MSG_SCREEN_CALL, args).sendToTarget();
        }
    }

    private ICallScreeningAdapter mCallScreeningAdapter;

    /*
     * Information about how to respond to an incoming call.
     */
    public static class CallResponse {
        private final boolean mShouldDisallowCall;
        private final boolean mShouldRejectCall;
        private final boolean mShouldSkipCallLog;
        private final boolean mShouldSkipNotification;

        private CallResponse(
                boolean shouldDisallowCall,
                boolean shouldRejectCall,
                boolean shouldSkipCallLog,
                boolean shouldSkipNotification) {
            if (!shouldDisallowCall
                    && (shouldRejectCall || shouldSkipCallLog || shouldSkipNotification)) {
                throw new IllegalStateException("Invalid response state for allowed call.");
            }

            mShouldDisallowCall = shouldDisallowCall;
            mShouldRejectCall = shouldRejectCall;
            mShouldSkipCallLog = shouldSkipCallLog;
            mShouldSkipNotification = shouldSkipNotification;
        }

        /*
         * @return Whether the incoming call should be blocked.
         */
        public boolean getDisallowCall() {
            return mShouldDisallowCall;
        }

        /*
         * @return Whether the incoming call should be disconnected as if the user had manually
         * rejected it.
         */
        public boolean getRejectCall() {
            return mShouldRejectCall;
        }

        /*
         * @return Whether the incoming call should not be displayed in the call log.
         */
        public boolean getSkipCallLog() {
            return mShouldSkipCallLog;
        }

        /*
         * @return Whether a missed call notification should not be shown for the incoming call.
         */
        public boolean getSkipNotification() {
            return mShouldSkipNotification;
        }

        public static class Builder {
            private boolean mShouldDisallowCall;
            private boolean mShouldRejectCall;
            private boolean mShouldSkipCallLog;
            private boolean mShouldSkipNotification;

            /**
             * Sets whether the incoming call should be blocked.
             */
            public Builder setDisallowCall(boolean shouldDisallowCall) {
                mShouldDisallowCall = shouldDisallowCall;
                return this;
            }

            /**
             * Sets whether the incoming call should be disconnected as if the user had manually
             * rejected it. This property should only be set to true if the call is disallowed.
             */
            public Builder setRejectCall(boolean shouldRejectCall) {
                mShouldRejectCall = shouldRejectCall;
                return this;
            }

            /**
             * Sets whether the incoming call should not be displayed in the call log. This property
             * should only be set to true if the call is disallowed.
             * <p>
             * Note: Calls will still be logged with type
             * {@link android.provider.CallLog.Calls#BLOCKED_TYPE}, regardless of how this property
             * is set.
             */
            public Builder setSkipCallLog(boolean shouldSkipCallLog) {
                mShouldSkipCallLog = shouldSkipCallLog;
                return this;
            }

            /**
             * Sets whether a missed call notification should not be shown for the incoming call.
             * This property should only be set to true if the call is disallowed.
             */
            public Builder setSkipNotification(boolean shouldSkipNotification) {
                mShouldSkipNotification = shouldSkipNotification;
                return this;
            }

            public CallResponse build() {
                return new CallResponse(
                        mShouldDisallowCall,
                        mShouldRejectCall,
                        mShouldSkipCallLog,
                        mShouldSkipNotification);
            }
       }
    }

    public CallScreeningService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.v(this, "onBind");
        return new CallScreeningBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.v(this, "onUnbind");
        return false;
    }

    /**
     * Called when a new incoming or outgoing call is added which is not in the user's contact list.
     * <p>
     * A {@link CallScreeningService} must indicate whether an incoming call is allowed or not by
     * calling
     * {@link CallScreeningService#respondToCall(Call.Details, CallScreeningService.CallResponse)}.
     * Your app can tell if a call is an incoming call by checking to see if
     * {@link Call.Details#getCallDirection()} is {@link Call.Details#DIRECTION_INCOMING}.
     * <p>
     * For incoming or outgoing calls, the {@link CallScreeningService} can call
     * {@link #provideCallIdentification(Call.Details, CallIdentification)} in order to provide
     * {@link CallIdentification} for the call.
     * <p>
     * Note: The {@link Call.Details} instance provided to a call screening service will only have
     * the following properties set.  The rest of the {@link Call.Details} properties will be set to
     * their default value or {@code null}.
     * <ul>
     *     <li>{@link Call.Details#getCallDirection()}</li>
     *     <li>{@link Call.Details#getConnectTimeMillis()}</li>
     *     <li>{@link Call.Details#getCreationTimeMillis()}</li>
     *     <li>{@link Call.Details#getHandle()}</li>
     *     <li>{@link Call.Details#getHandlePresentation()}</li>
     * </ul>
     * <p>
     * Only calls where the {@link Call.Details#getHandle() handle} {@link Uri#getScheme() scheme}
     * is {@link PhoneAccount#SCHEME_TEL} are passed for call
     * screening.  Further, only calls which are not in the user's contacts are passed for
     * screening.  For outgoing calls, no post-dial digits are passed.
     *
     * @param callDetails Information about a new call, see {@link Call.Details}.
     */
    public abstract void onScreenCall(@NonNull Call.Details callDetails);

    /**
     * Responds to the given incoming call, either allowing it or disallowing it.
     * <p>
     * The {@link CallScreeningService} calls this method to inform the system whether the call
     * should be silently blocked or not.
     * <p>
     * Calls to this method are ignored unless the {@link Call.Details#getCallDirection()} is
     * {@link Call.Details#DIRECTION_INCOMING}.
     *
     * @param callDetails The call to allow.
     *                    <p>
     *                    Must be the same {@link Call.Details call} which was provided to the
     *                    {@link CallScreeningService} via {@link #onScreenCall(Call.Details)}.
     * @param response The {@link CallScreeningService.CallResponse} which contains information
     * about how to respond to a call.
     */
    public final void respondToCall(@NonNull Call.Details callDetails,
            @NonNull CallResponse response) {
        try {
            if (response.getDisallowCall()) {
                mCallScreeningAdapter.disallowCall(
                        callDetails.getTelecomCallId(),
                        response.getRejectCall(),
                        !response.getSkipCallLog(),
                        !response.getSkipNotification(),
                        new ComponentName(getPackageName(), getClass().getName()));
            } else {
                mCallScreeningAdapter.allowCall(callDetails.getTelecomCallId());
            }
        } catch (RemoteException e) {
        }
    }

    /**
     * Provide {@link CallIdentification} information about a {@link Call.Details call}.
     * <p>
     * The {@link CallScreeningService} calls this method to provide information it has identified
     * about a {@link Call.Details call}.  This information will potentially be shown to the user
     * in the {@link InCallService dialer} app.  It will be logged to the
     * {@link android.provider.CallLog}.
     * <p>
     * A {@link CallScreeningService} should only call this method for calls for which it is able to
     * provide some {@link CallIdentification} for.  {@link CallIdentification} instances with no
     * fields set will be ignored by the system.
     *
     * @param callDetails The call to provide information for.
     *                    <p>
     *                    Must be the same {@link Call.Details call} which was provided to the
     *                    {@link CallScreeningService} via {@link #onScreenCall(Call.Details)}.
     * @param identification An instance of {@link CallIdentification} with information about the
     *                       {@link Call.Details call}.
     */
    public final void provideCallIdentification(@NonNull Call.Details callDetails,
            @NonNull CallIdentification identification) {
        try {
            mCallScreeningAdapter.provideCallIdentification(callDetails.getTelecomCallId(),
                    identification);
        } catch (RemoteException e) {
        }
    }
}
