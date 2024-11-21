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
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Slog;
import android.view.View;
import android.view.ViewGroup.MarginLayoutParams;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

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

    static final String KEY_DID_ACKNOWLEDGE = "did_acknowledge";
    static final String KEY_TOKEN = "token";
    static final String KEY_ACTION = "action";

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
    String mAction;

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
                    Toast.makeText(mContext, R.string.toast_backup_started, Toast.LENGTH_LONG).show();
                }
                break;

                case MSG_BACKUP_PACKAGE: {
                    String name = (String) msg.obj;
                    mStatusView.setText(name);
                }
                break;

                case MSG_END_BACKUP: {
                    Toast.makeText(mContext, R.string.toast_backup_ended, Toast.LENGTH_LONG).show();
                    finish();
                }
                break;

                case MSG_START_RESTORE: {
                    Toast.makeText(mContext, R.string.toast_restore_started, Toast.LENGTH_LONG).show();
                }
                break;

                case MSG_RESTORE_PACKAGE: {
                    String name = (String) msg.obj;
                    mStatusView.setText(name);
                }
                break;

                case MSG_END_RESTORE: {
                    Toast.makeText(mContext, R.string.toast_restore_ended, Toast.LENGTH_SHORT).show();
                    finish();
                }
                break;

                case MSG_TIMEOUT: {
                    Toast.makeText(mContext, R.string.toast_timeout, Toast.LENGTH_LONG).show();
                }
                break;
            }
        }
    }
    
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        final Intent intent = getIntent();

        boolean tokenValid = setTokenOrFinish(intent, icicle);
        if (!tokenValid) { // already called finish()
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

        setViews(intent, icicle);
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        boolean tokenValid = setTokenOrFinish(intent, null);
        if (!tokenValid) { // already called finish()
            return;
        }

        setViews(intent, null);
    }

    private boolean setTokenOrFinish(Intent intent, Bundle icicle) {
        mToken = intent.getIntExtra(FullBackup.CONF_TOKEN_INTENT_EXTRA, -1);

        // for relaunch, we try to use the last token before exit
        if (icicle != null) {
            mToken = icicle.getInt(KEY_TOKEN, mToken);
        }

        if (mToken < 0) {
            Slog.e(TAG, "Backup/restore confirmation requested but no token passed!");
            finish();
            return false;
        }

        return true;
    }

    private void setViews(Intent intent, Bundle icicle) {
        mAction = intent.getAction();

        // for relaunch, we try to use the last action before exit
        if (icicle != null) {
            mAction = icicle.getString(KEY_ACTION, mAction);
        }

        final int layoutId;
        final int titleId;
        if (mAction.equals(FullBackup.FULL_BACKUP_INTENT_ACTION)) {
            layoutId = R.layout.confirm_backup;
            titleId = R.string.backup_confirm_title;
        } else if (mAction.equals(FullBackup.FULL_RESTORE_INTENT_ACTION)) {
            layoutId = R.layout.confirm_restore;
            titleId = R.string.restore_confirm_title;
        } else {
            Slog.w(TAG, "Backup/restore confirmation activity launched with invalid action!");
            finish();
            return;
        }

        setTitle(titleId);
        setContentView(layoutId);

        handleInsets();

        // Same resource IDs for each layout variant (backup / restore)
        mStatusView = findViewById(R.id.package_name);
        mAllowButton = findViewById(R.id.button_allow);
        mDenyButton = findViewById(R.id.button_deny);

        mCurPassword = findViewById(R.id.password);
        mEncPassword = findViewById(R.id.enc_password);
        TextView curPwDesc = findViewById(R.id.password_desc);

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
                finish();
            }
        });

        // if we're a relaunch we may need to adjust button enable state
        if (icicle != null) {
            mDidAcknowledge = icicle.getBoolean(KEY_DID_ACKNOWLEDGE, false);
            mAllowButton.setEnabled(!mDidAcknowledge);
            mDenyButton.setEnabled(!mDidAcknowledge);
        }

        // We vary the password prompt depending on whether one is predefined.
        if (!haveBackupPassword()) {
            curPwDesc.setVisibility(View.GONE);
            mCurPassword.setVisibility(View.GONE);
            if (layoutId == R.layout.confirm_backup) {
                TextView encPwDesc = findViewById(R.id.enc_password_desc);
                encPwDesc.setText(R.string.backup_enc_password_optional);
            }
        }
    }

    // Handle insets so that UI components are not covered by navigation and status bars
    private void handleInsets() {
        LinearLayout buttonBar = findViewById(R.id.button_bar);
        ViewCompat.setOnApplyWindowInsetsListener(buttonBar, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            MarginLayoutParams mlp = (MarginLayoutParams) v.getLayoutParams();
            mlp.leftMargin = insets.left;
            mlp.bottomMargin = insets.bottom;
            mlp.rightMargin = insets.right;
            v.setLayoutParams(mlp);
            return WindowInsetsCompat.CONSUMED;
        });

        ScrollView scrollView = findViewById(R.id.scroll_view);
        ViewCompat.setOnApplyWindowInsetsListener(scrollView, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            MarginLayoutParams mlp = (MarginLayoutParams) v.getLayoutParams();
            mlp.leftMargin = insets.left;
            mlp.topMargin = insets.top;
            mlp.rightMargin = insets.right;
            v.setLayoutParams(mlp);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void monitorEncryptionPassword() {
        mAllowButton.setEnabled(false);
        mEncPassword.addTextChangedListener(new TextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void afterTextChanged(Editable s) {
                mAllowButton.setEnabled(mEncPassword.getText().length() > 0);
            }
        });
    }

    // Preserve the restore observer callback binder across activity relaunch
    @Override
    public Object onRetainNonConfigurationInstance() {
        return mObserver;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(KEY_DID_ACKNOWLEDGE, mDidAcknowledge);
        outState.putInt(KEY_TOKEN, mToken);
        outState.putString(KEY_ACTION, mAction);
    }

    void sendAcknowledgement(int token, boolean allow, IFullBackupRestoreObserver observer) {
        if (!mDidAcknowledge) {
            mDidAcknowledge = true;

            try {
                CharSequence encPassword = mEncPassword.getText();
                mBackupManager.acknowledgeFullBackupOrRestore(mToken,
                        allow,
                        String.valueOf(mCurPassword.getText()),
                        String.valueOf(encPassword),
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
