/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.frameworks.coretests.bdr_helper_app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;

/**
 * Receiver used to hand off a binder owned by this process to
 * {@link android.os.BinderDeathRecipientTest}.
 */
public class TestCommsReceiver extends BroadcastReceiver {
    private static final String TAG = TestCommsReceiver.class.getSimpleName();
    private  static final String PACKAGE_NAME = "com.android.frameworks.coretests.bdr_helper_app";

    public static final String ACTION_GET_BINDER = PACKAGE_NAME + ".action.GET_BINDER";
    public static final String EXTRA_KEY_BINDER = PACKAGE_NAME + ".EXTRA_BINDER";

    @Override
    public void onReceive(Context context, Intent intent) {
        switch (intent.getAction()) {
            case ACTION_GET_BINDER:
                final Bundle resultExtras = new Bundle();
                resultExtras.putBinder(EXTRA_KEY_BINDER, new Binder());
                setResult(Activity.RESULT_OK, null, resultExtras);
                break;
            default:
                Log.e(TAG, "Unknown action " + intent.getAction());
                break;
        }
    }
}
