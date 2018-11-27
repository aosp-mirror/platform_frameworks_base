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
package android.service.sms;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.database.CursorWindow;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallback;
import android.os.RemoteException;

/**
 * A service to support sms messages read for financial apps.
 *
 * {@hide}
 */
@SystemApi
public abstract class FinancialSmsService extends Service {

    private static final String TAG = "FinancialSmsService";

    /**
     * The {@link Intent} action that must be declared as handled by a service
     * in its manifest for the system to recognize it as a quota providing
     * service.
     */
    public static final String ACTION_FINANCIAL_SERVICE_INTENT =
            "android.service.sms.action.FINANCIAL_SERVICE_INTENT";

    /** {@hide} **/
    public static final String EXTRA_SMS_MSGS = "sms_messages";

    private FinancialSmsServiceWrapper mWrapper;

    private void getSmsMessages(RemoteCallback callback, Bundle params) {
        final Bundle data = new Bundle();
        CursorWindow smsMessages = onGetSmsMessages(params);
        if (smsMessages != null) {
            data.putParcelable(EXTRA_SMS_MSGS, smsMessages);
        }
        callback.sendResult(data);
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper(), null, true);

    /** @hide */
    public FinancialSmsService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mWrapper = new FinancialSmsServiceWrapper();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mWrapper;
    }

    /**
     * Get sms messages for financial apps.
     *
     * @param params parameters passed in by the calling app.
     * @return the {@code CursorWindow} with all sms messages for the app to read.
     *
     * {@hide}
     */
    @Nullable
    @SystemApi
    public abstract CursorWindow onGetSmsMessages(@NonNull Bundle params);

    private final class FinancialSmsServiceWrapper extends IFinancialSmsService.Stub {
        @Override
        public void getSmsMessages(RemoteCallback callback, Bundle params) throws RemoteException {
            mHandler.sendMessage(obtainMessage(
                    FinancialSmsService::getSmsMessages,
                    FinancialSmsService.this,
                    callback, params));
        }
    }

}
