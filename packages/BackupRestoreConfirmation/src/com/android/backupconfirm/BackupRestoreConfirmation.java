/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.backupconfirm;

import android.app.Activity;
import android.app.backup.FullBackup;
import android.app.backup.IBackupManager;
import android.app.backup.IFullBackupRestoreObserver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Confirm with the user that a requested full backup/restore operation is legitimate.
 * Any attempt to perform a full backup/restore will launch this UI and wait for a
 * designated timeout interval (nominally 30 seconds) for the user to confirm.  If the
 * user fails to respond within the timeout period, or explicitly refuses the operation
 * within the UI presented here, no data will be transferred off the device.
 *
 * Note that the fully scoped name of this class is baked into the backup manager service.
 *
 * @hide
 */
public class BackupRestoreConfirmation extends Activity {
    static final String TAG = "BackupRestoreConfirmation";
    static final boolean DEBUG = true;

    static final String DID_ACKNOWLEDGE = "did_acknowledge";

    static final int MSG_START_BACKUP = 1;
    static final int MSG_BACKUP_PACKAGE = 2;
    static final int MSG_END_BACKUP = 3;
    static final int MSG_START_RESTORE = 11;
    static final int MSG_RESTORE_PACKAGE = 12;
    static final int MSG_END_RESTORE = 13;
    static final int MSG_TIMEOUT = 100;

    Handler mHandler;
    IBackupManager mBackupManager;
    FullObserver mObserver;
    int mToken;
    boolean mDidAcknowledge;

    TextView mStatusView;
    TextView mCurPassword;
    TextView mEncPassword;
    Button mAllowButton;
    Button mDenyButton;

    // Handler for dealing with observer callbacks on the main thread
    class ObserverHandler extends Handler {
        Context mContext;
        ObserverHandler(Context context) {
            mContext = context;
            mDidAcknowledge = false;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START_BACKUP: {
                    Toast.makeText(mContext, "!!! Backup starting !!!", Toast.LENGTH_LONG).show();
                }
                break;

                case MSG_BACKUP_PACKAGE: {
                    String name = (String) msg.obj;
                    mStatusView.setText(name);
                }
                break;

                case MSG_END_BACKUP: {
                    Toast.makeText(mContext, "!!! Backup ended !!!", Toast.LENGTH_SHORT).show();
                    finish();
                }
                break;

                case MSG_START_RESTORE: {
                    Toast.makeText(mContext, "!!! Restore starting !!!", Toast.LENGTH_LONG).show();
                }
                break;

                case MSG_RESTORE_PACKAGE: {
                    String name = (String) msg.obj;
                    mStatusView.setText(name);
                }
                break;

                case MSG_END_RESTORE: {
                    Toast.makeText(mContext, "!!! Restore ended !!!", Toast.LENGTH_SHORT).show();
                    finish();
                }
                break;

                case MSG_TIMEOUT: {
                    Toast.makeText(mContext, "!!! TIMED OUT !!!", Toast.LENGTH_LONG).show();
                }
                break;
            }
        }
    }
    
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        final Intent intent = getIntent();
        final String action = intent.getAction();

        final int layoutId;
        if (action.equals(FullBackup.FULL_BACKUP_INTENT_ACTION)) {
            layoutId = R.layout.confirm_backup;
        } else if (action.equals(FullBackup.FULL_RESTORE_INTENT_ACTION)) {
            layoutId = R.layout.confirm_restore;
        } else {
            Slog.w(TAG, "Backup/restore confirmation activity launched with invalid action!");
            finish();
            return;
        }

        mToken = intent.getIntExtra(FullBackup.CONF_TOKEN_INTENT_EXTRA, -1);
        if (mToken < 0) {
            Slog.e(TAG, "Backup/restore confirmation requested but no token passed!");
            finish();
            return;
        }

        mBackupManager = IBackupManager.Stub.asInterface(ServiceManager.getService(Context.BACKUP_SERVICE));

        mHandler = new ObserverHandler(getApplicationContext());
        final Object oldObserver = getLastNonConfigurationInstance();
        if (oldObserver == null) {
            mObserver = new FullObserver(mHandler);
        } else {
            mObserver = (FullObserver) oldObserver;
            mObserver.setHandler(mHandler);
        }

        setContentView(layoutId);

        // Same resource IDs for each layout variant (backup / restore)
        mStatusView = (TextView) findViewById(R.id.package_name);
        mAllowButton = (Button) findViewById(R.id.button_allow);
        mDenyButton = (Button) findViewById(R.id.button_deny);

        mCurPassword = (TextView) findViewById(R.id.password);
        mEncPassword = (TextView) findViewById(R.id.enc_password);
        TextView curPwDesc = (TextView) findViewById(R.id.password_desc);

        // We vary the password prompt depending on whether one is predefined
        if (!haveBackupPassword()) {
            curPwDesc.setVisibility(View.GONE);
            mCurPassword.setVisibility(View.GONE);
            if (layoutId == R.layout.confirm_backup) {
                TextView encPwDesc = (TextView) findViewById(R.id.enc_password_desc);
                encPwDesc.setText(R.string.backup_enc_password_optional);
            }
        }

        mAllowButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendAcknowledgement(mToken, true, mObserver);
                mAllowButton.setEnabled(false);
                mDenyButton.setEnabled(false);
            }
        });

        mDenyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendAcknowledgement(mToken, false, mObserver);
                mAllowButton.setEnabled(false);
                mDenyButton.setEnabled(false);
            }
        });

        // if we're a relaunch we may need to adjust button enable state
        if (icicle != null) {
            mDidAcknowledge = icicle.getBoolean(DID_ACKNOWLEDGE, false);
            mAllowButton.setEnabled(!mDidAcknowledge);
            mDenyButton.setEnabled(!mDidAcknowledge);
        }
    }

    // Preserve the restore observer callback binder across activity relaunch
    @Override
    public Object onRetainNonConfigurationInstance() {
        return mObserver;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(DID_ACKNOWLEDGE, mDidAcknowledge);
    }

    void sendAcknowledgement(int token, boolean allow, IFullBackupRestoreObserver observer) {
        if (!mDidAcknowledge) {
            mDidAcknowledge = true;

            try {
                mBackupManager.acknowledgeFullBackupOrRestore(mToken,
                        allow,
                        String.valueOf(mCurPassword.getText()),
                        String.valueOf(mEncPassword.getText()),
                        mObserver);
            } catch (RemoteException e) {
                // TODO: bail gracefully if we can't contact the backup manager
            }
        }
    }

    boolean haveBackupPassword() {
        try {
            return mBackupManager.hasBackupPassword();
        } catch (RemoteException e) {
            return true;        // in the failure case, assume we need one
        }
    }

    /**
     * The observer binder for showing backup/restore progress.  This binder just bounces
     * the notifications onto the main thread.
     */
    class FullObserver extends IFullBackupRestoreObserver.Stub {
        private Handler mHandler;

        public FullObserver(Handler h) {
            mHandler = h;
        }

        public void setHandler(Handler h) {
            mHandler = h;
        }

        //
        // IFullBackupRestoreObserver implementation
        //
        @Override
        public void onStartBackup() throws RemoteException {
            mHandler.sendEmptyMessage(MSG_START_BACKUP);
        }

        @Override
        public void onBackupPackage(String name) throws RemoteException {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_BACKUP_PACKAGE, name));
        }

        @Override
        public void onEndBackup() throws RemoteException {
            mHandler.sendEmptyMessage(MSG_END_BACKUP);
        }

        @Override
        public void onStartRestore() throws RemoteException {
            mHandler.sendEmptyMessage(MSG_START_RESTORE);
        }

        @Override
        public void onRestorePackage(String name) throws RemoteException {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_RESTORE_PACKAGE, name));
        }

        @Override
        public void onEndRestore() throws RemoteException {
            mHandler.sendEmptyMessage(MSG_END_RESTORE);
        }        

        @Override
        public void onTimeout() throws RemoteException {
            mHandler.sendEmptyMessage(MSG_TIMEOUT);
        }
    }
}
