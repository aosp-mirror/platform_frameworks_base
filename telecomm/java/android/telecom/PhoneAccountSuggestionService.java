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
 * limitations under the License.
 */

package android.telecom;

import android.annotation.NonNull;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.internal.telecom.IPhoneAccountSuggestionCallback;
import com.android.internal.telecom.IPhoneAccountSuggestionService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for service that allows system apps to suggest phone accounts for outgoing calls.
 *
 * Phone account suggestions allow OEMs to intelligently select phone accounts based on knowledge
 * about the user's past behavior, carrier billing patterns, or other factors unknown to the AOSP
 * Telecom system.
 * OEMs who wish to provide a phone account suggestion service on their device should implement this
 * service in an app that resides in the /system/priv-app/ directory on their device. For security
 * reasons, the service's entry {@code AndroidManifest.xml} file must declare the
 * {@link android.Manifest.permission.BIND_PHONE_ACCOUNT_SUGGESTION_SERVICE} permission:
 * <pre>
 * {@code
 * <service android:name="your.package.YourServiceName"
 *          android:permission="android.permission.BIND_PHONE_ACCOUNT_SUGGESTION_SERVICE">
 *      <intent-filter>
 *          <action android:name="android.telecom.PhoneAccountSuggestionService"/>
 *      </intent-filter>
 * </service>
 * }
 * </pre>
 * Only one system app on each device may implement this service. If multiple system apps implement
 * this service, none of them will be queried for suggestions.
 * @hide
 */
@SystemApi
@TestApi
public class PhoneAccountSuggestionService extends Service {
    /**
     * The {@link Intent} that must be declared in the {@code intent-filter} element of the
     * service's manifest entry.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.telecom.PhoneAccountSuggestionService";

    private IPhoneAccountSuggestionService mInterface = new IPhoneAccountSuggestionService.Stub() {
        @Override
        public void onAccountSuggestionRequest(IPhoneAccountSuggestionCallback callback,
                String number) {
            mCallbackMap.put(number, callback);
            PhoneAccountSuggestionService.this.onAccountSuggestionRequest(number);
        }
    };

    private final Map<String, IPhoneAccountSuggestionCallback> mCallbackMap =
            new HashMap<>();

    @Override
    public IBinder onBind(Intent intent) {
        return mInterface.asBinder();
    }

    /**
     * The system calls this method during the outgoing call flow if it needs account suggestions.
     *
     * The implementer of this service must override this method to implement its account suggestion
     * logic. After preparing the suggestions, the implementation of the service must call
     * {@link #suggestPhoneAccounts(String, List)} to deliver the suggestions back to the system.
     *
     * Note that the system will suspend the outgoing call process after it calls this method until
     * this service calls {@link #suggestPhoneAccounts}.
     *
     * @param number The phone number to provide suggestions for.
     */
    public void onAccountSuggestionRequest(@NonNull String number) {}

    /**
     * The implementation of this service calls this method to deliver suggestions to the system.
     *
     * The implementation of this service must call this method after receiving a call to
     * {@link #onAccountSuggestionRequest(String)}. If no suggestions are available, pass an empty
     * list as the {@code suggestions} argument.
     *
     * @param number The phone number to provide suggestions for.
     * @param suggestions The list of suggestions.
     */
    public final void suggestPhoneAccounts(@NonNull String number,
            @NonNull List<PhoneAccountSuggestion> suggestions) {
        IPhoneAccountSuggestionCallback callback = mCallbackMap.remove(number);
        if (callback == null) {
            Log.w(this, "No suggestions requested for the number %s", Log.pii(number));
            return;
        }
        try {
            callback.suggestPhoneAccounts(number, suggestions);
        } catch (RemoteException e) {
            Log.w(this, "Remote exception calling suggestPhoneAccounts");
        }
    }
}
