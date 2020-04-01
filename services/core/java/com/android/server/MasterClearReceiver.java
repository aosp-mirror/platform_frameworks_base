/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server;

import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.RecoverySystem;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.telephony.euicc.EuiccManager;
import android.util.Log;
import android.util.Slog;
import android.view.WindowManager;

import com.android.internal.R;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MasterClearReceiver extends BroadcastReceiver {
    private static final String TAG = "MasterClear";
    private boolean mWipeExternalStorage;
    private boolean mWipeEsims;

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent.getAction().equals(Intent.ACTION_REMOTE_INTENT)) {
            if (!"google.com".equals(intent.getStringExtra("from"))) {
                Slog.w(TAG, "Ignoring master clear request -- not from trusted server.");
                return;
            }
        }
        if (Intent.ACTION_MASTER_CLEAR.equals(intent.getAction())) {
            Slog.w(TAG, "The request uses the deprecated Intent#ACTION_MASTER_CLEAR, "
                    + "Intent#ACTION_FACTORY_RESET should be used instead.");
        }
        if (intent.hasExtra(Intent.EXTRA_FORCE_MASTER_CLEAR)) {
            Slog.w(TAG, "The request uses the deprecated Intent#EXTRA_FORCE_MASTER_CLEAR, "
                    + "Intent#EXTRA_FORCE_FACTORY_RESET should be used instead.");
        }

        final boolean shutdown = intent.getBooleanExtra("shutdown", false);
        final String reason = intent.getStringExtra(Intent.EXTRA_REASON);
        mWipeExternalStorage = intent.getBooleanExtra(Intent.EXTRA_WIPE_EXTERNAL_STORAGE, false);
        mWipeEsims = intent.getBooleanExtra(Intent.EXTRA_WIPE_ESIMS, false);
        final boolean forceWipe = intent.getBooleanExtra(Intent.EXTRA_FORCE_MASTER_CLEAR, false)
                || intent.getBooleanExtra(Intent.EXTRA_FORCE_FACTORY_RESET, false);

        Slog.w(TAG, "!!! FACTORY RESET !!!");
        // The reboot call is blocking, so we need to do it on another thread.
        Thread thr = new Thread("Reboot") {
            @Override
            public void run() {
                try {
                    RecoverySystem
                            .rebootWipeUserData(context, shutdown, reason, forceWipe, mWipeEsims);
                    Log.wtf(TAG, "Still running after master clear?!");
                } catch (IOException e) {
                    Slog.e(TAG, "Can't perform master clear/factory reset", e);
                } catch (SecurityException e) {
                    Slog.e(TAG, "Can't perform master clear/factory reset", e);
                }
            }
        };

        if (mWipeExternalStorage) {
            // thr will be started at the end of this task.
            new WipeDataTask(context, thr).execute();
        } else {
            thr.start();
        }
    }

    private class WipeDataTask extends AsyncTask<Void, Void, Void> {
        private final Thread mChainedTask;
        private final Context mContext;
        private final ProgressDialog mProgressDialog;

        public WipeDataTask(Context context, Thread chainedTask) {
            mContext = context;
            mChainedTask = chainedTask;
            mProgressDialog = new ProgressDialog(context);
        }

        @Override
        protected void onPreExecute() {
            mProgressDialog.setIndeterminate(true);
            mProgressDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            mProgressDialog.setMessage(mContext.getText(R.string.progress_erasing));
            mProgressDialog.show();
        }

        @Override
        protected Void doInBackground(Void... params) {
            Slog.w(TAG, "Wiping adoptable disks");
            if (mWipeExternalStorage) {
                StorageManager sm = (StorageManager) mContext.getSystemService(
                        Context.STORAGE_SERVICE);
                sm.wipeAdoptableDisks();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            mProgressDialog.dismiss();
            mChainedTask.start();
        }

    }
}
