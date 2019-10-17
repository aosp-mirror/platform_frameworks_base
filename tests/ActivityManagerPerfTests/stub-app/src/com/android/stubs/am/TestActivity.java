/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.stubs.am;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import com.android.frameworks.perftests.am.util.Constants;

public class TestActivity extends Activity {
    private static final String TAG = "TestActivity";
    private static final boolean VERBOSE = InitService.VERBOSE;

    private Messenger mResultTo;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        if (VERBOSE) {
            Log.i(TAG, getPackageName() + " onCreate()");
        }
        setContentView(R.layout.activity_content);
        mResultTo = getIntent().getParcelableExtra(Constants.EXTRA_RECEIVER_CALLBACK);
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        mResultTo = intent.getParcelableExtra(Constants.EXTRA_RECEIVER_CALLBACK);
        if (intent.getBooleanExtra(Constants.EXTRA_REQ_FINISH_ACTIVITY, false)) {
            if (VERBOSE) {
                Log.i(TAG, getPackageName() + " finishing activity");
            }
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (VERBOSE) {
            Log.i(TAG, getPackageName() + " onResume()");
        }
        sendResult(Constants.RESULT_NO_ERROR);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (VERBOSE) {
            Log.i(TAG, getPackageName() + " onDestroy()");
        }
        sendResult(Constants.RESULT_NO_ERROR);
    }

    private void sendResult(int result) {
        Message msg = Message.obtain();
        msg.arg1 = getIntent().getIntExtra(Constants.EXTRA_ARG1, -1);
        msg.arg2 = result;
        try {
            mResultTo.send(msg);
        } catch (RemoteException e) {
            Log.e(TAG, "Error in sending result back", e);
        }
    }
}
