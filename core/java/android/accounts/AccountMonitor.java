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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.database.SQLException;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

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
public class AccountMonitor extends BroadcastReceiver implements ServiceConnection {
    private final Context mContext;
    private final AccountMonitorListener mListener;
    private boolean mClosed = false;
    private int pending = 0;

    // This thread runs in the background and runs the code to update accounts
    // in the listener.
    private class AccountUpdater extends Thread {
        private IBinder mService;

        public AccountUpdater(IBinder service) {
            mService = service;
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            IAccountsService accountsService = IAccountsService.Stub.asInterface(mService);
            String[] accounts = null;
            do {
                try {
                    accounts = accountsService.getAccounts();
                } catch (RemoteException e) {
                    // if the service was killed then the system will restart it and when it does we
                    // will get another onServiceConnected, at which point we will do a notify.
                    Log.w("AccountMonitor", "Remote exception when getting accounts", e);
                    return;
                }

                synchronized (AccountMonitor.this) {
                    --pending;
                    if (pending == 0) {
                        break;
                    }
                }
            } while (true);

            mContext.unbindService(AccountMonitor.this);

            try {
                mListener.onAccountsUpdated(accounts);
            } catch (SQLException e) {
                // Better luck next time.  If the problem was disk-full,
                // the STORAGE_OK intent will re-trigger the update.
                Log.e("AccountMonitor", "Can't update accounts", e);
            }
        }
    }

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
        intentFilter.addAction(AccountsServiceConstants.LOGIN_ACCOUNTS_CHANGED_ACTION);
        intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);  // To recover from disk-full.
        mContext.registerReceiver(this, intentFilter);

        // Send the listener the initial state now.
        notifyListener();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        notifyListener();
    }

    public void onServiceConnected(ComponentName className, IBinder service) {
        // Create a background thread to update the accounts.
        new AccountUpdater(service).start();
    }

    public void onServiceDisconnected(ComponentName className) {
    }

    private synchronized void notifyListener() {
        if (pending == 0) {
            // initiate the bind
            if (!mContext.bindService(AccountsServiceConstants.SERVICE_INTENT,
                                      this, Context.BIND_AUTO_CREATE)) {
                // This is normal if GLS isn't part of this build.
                Log.w("AccountMonitor",
                      "Couldn't connect to "  +
                      AccountsServiceConstants.SERVICE_INTENT +
                      " (Missing service?)");
            }
        } else {
            // already bound.  bindService will not trigger another
            // call to onServiceConnected, so instead we make sure
            // that the existing background thread will call
            // getAccounts() after this function returns, by
            // incrementing pending.
            //
            // Yes, this else clause contains only a comment.
        }
        ++pending;
    }

    /**
     * calls close()
     * @throws Throwable
     */
    @Override
    protected void finalize() throws Throwable {
        close();
        super.finalize();
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
}
