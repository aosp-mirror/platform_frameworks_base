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
 * incoming calls before they are shown to a user. A {@link CallScreeningService} can also see
 * outgoing calls for the purpose of providing caller ID services for those calls.
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
 *     <li>Call identification - services which provide call identification functionality can
 *     display a user-interface of their choosing which contains identifying information for a call.
 *     </li>
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
 *     Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING);
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
        private final boolean mShouldSilenceCall;
        private final boolean mShouldSkipCallLog;
        private final boolean mShouldSkipNotification;

        private CallResponse(
                boolean shouldDisallowCall,
                boolean shouldRejectCall,
                boolean shouldSilenceCall,
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
            mShouldSilenceCall = shouldSilenceCall;
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
         * @return Whether the ringtone should be silenced for the incoming call.
         */
        public boolean getSilenceCall() {
            return mShouldSilenceCall;
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
            private boolean mShouldSilenceCall;
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
             * Sets whether ringing should be silenced for the incoming call.  When set
             * to {@code true}, the Telecom framework will not play a ringtone for the call.
             * The call will, however, still be sent to the default dialer app if it is not blocked.
             * A {@link CallScreeningService} can use this to ensure a potential nuisance call is
             * still surfaced to the user, but in a less intrusive manner.
             *
             * Setting this to true only makes sense when the call has not been disallowed
             * using {@link #setDisallowCall(boolean)}.
             */
            public @NonNull Builder setSilenceCall(boolean shouldSilenceCall) {
                mShouldSilenceCall = shouldSilenceCall;
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
                        mShouldSilenceCall,
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
     * Responds to the given incoming call, either allowing it, silencing it or disallowing it.
     * <p>
     * The {@link CallScreeningService} calls this method to inform the system whether the call
     * should be silently blocked or not. In the event that it should not be blocked, it may
     * also be requested to ring silently.
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
            } else if (response.getSilenceCall()) {
                mCallScreeningAdapter.silenceCall(callDetails.getTelecomCallId());
            } else {
                mCallScreeningAdapter.allowCall(callDetails.getTelecomCallId());
            }
        } catch (RemoteException e) {
        }
    }
}
