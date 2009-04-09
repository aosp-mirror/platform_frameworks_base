/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.accounts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.*;

/**
 * A helper class that calls back on the provided
 * AccountMonitorListener with the set of current accounts both when
 * it gets created and whenever the set changes. It does this by
 * binding to the AccountsService and registering to receive the
 * intent broadcast when the set of accounts is changed.  The
 * connection to the accounts service is only made when it needs to
 * fetch the current list of accounts (that is, when the
 * AccountMonitor is first created, and when the intent is received).
 */
public class AccountMonitor extends BroadcastReceiver {
    private static final String TAG = "AccountMonitor";

    private final Context mContext;
    private final AccountMonitorListener mListener;
    private boolean mClosed = false;

    private volatile Looper mServiceLooper;
    private volatile NotifierHandler mServiceHandler;

    /**
     * Initializes the AccountMonitor and initiates a bind to the
     * AccountsService to get the initial account list.  For 1.0,
     * the "list" is always a single account.
     *
     * @param context the context we are running in
     * @param listener the user to notify when the account set changes
     */
    public AccountMonitor(Context context, AccountMonitorListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener is null");
        }

        mContext = context;
        mListener = listener;

        // Register a broadcast receiver to monitor account changes
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.LOGIN_ACCOUNTS_CHANGED_ACTION);
        intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);  // To recover from disk-full.
        mContext.registerReceiver(this, intentFilter);

        HandlerThread thread = new HandlerThread("AccountMonitorHandlerThread");
        thread.start();
        mServiceLooper = thread.getLooper();
        mServiceHandler = new NotifierHandler(mServiceLooper);

        mServiceHandler.sendEmptyMessage(0);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        notifyListener();
    }

    private Future1Callback<Account[]> mGetAccountsCallback = new Future1Callback<Account[]>() {
        public void run(Future1<Account[]> future) {
            try {
                Account[] accounts = future.getResult();
                String[] accountNames = new String[accounts.length];
                for (int i = 0; i < accounts.length; i++) {
                    accountNames[i] = accounts[i].mName;
                }
                mListener.onAccountsUpdated(accountNames);
            } catch (OperationCanceledException e) {
                // the request was canceled
            }
        }
    };

    private synchronized void notifyListener() {
        AccountManager.get(mContext).getAccounts(mGetAccountsCallback, null /* handler */);
    }

    /**
     * Unregisters the account receiver.  Consecutive calls to this
     * method are harmless, but also do nothing.  Once this call is
     * made no more notifications will occur.
     */
    public synchronized void close() {
        if (!mClosed) {
            mContext.unregisterReceiver(this);
            mClosed = true;
        }
    }

    private final class NotifierHandler extends Handler {
        public NotifierHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            notifyListener();
        }
    }
}
