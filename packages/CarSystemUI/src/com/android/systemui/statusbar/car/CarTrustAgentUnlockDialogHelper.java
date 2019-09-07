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

package com.android.systemui.statusbar.car;

import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.R;

/**
 * A helper class displays an unlock dialog and receives broadcast about detecting trusted device
 * & unlocking state to show the appropriate message on the dialog.
 */
class CarTrustAgentUnlockDialogHelper extends BroadcastReceiver{
    private static final String TAG = CarTrustAgentUnlockDialogHelper.class.getSimpleName();

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final UserManager mUserManager;
    private final WindowManager.LayoutParams mParams;
    /**
     * Not using Dialog because context passed from {@link FullscreenUserSwitcher} is not an
     * activity.
     */
    private final View mUnlockDialogLayout;
    private final TextView mUnlockingText;
    private final Button mButton;
    private final IntentFilter mFilter;
    private int mUid;
    private boolean mIsDialogShowing;
    private OnHideListener mOnHideListener;

    CarTrustAgentUnlockDialogHelper(Context context) {
        mContext = context;
        mUserManager = mContext.getSystemService(UserManager.class);
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mParams = createLayoutParams();
        mFilter = getIntentFilter();

        mParams.packageName = mContext.getPackageName();
        mParams.setTitle(mContext.getString(R.string.unlock_dialog_title));

        mUnlockDialogLayout = LayoutInflater.from(mContext).inflate(
            R.layout.trust_agent_unlock_dialog, null);
        mUnlockDialogLayout.setLayoutParams(mParams);

        View dialogParent = mUnlockDialogLayout.findViewById(R.id.unlock_dialog_parent);
        dialogParent.setOnTouchListener((v, event)-> {
            hideUnlockDialog(/* dismissUserSwitcher= */ false);
            return true;
        });
        View unlockDialog = mUnlockDialogLayout.findViewById(R.id.unlock_dialog);
        unlockDialog.setOnTouchListener((v, event) -> {
            // If the person taps inside the unlock dialog, the touch event will be intercepted here
            // and the dialog will not exit
            return true;
        });
        mUnlockingText = mUnlockDialogLayout.findViewById(R.id.unlocking_text);
        mButton = mUnlockDialogLayout.findViewById(R.id.enter_pin_button);
        mButton.setOnClickListener(v -> {
            hideUnlockDialog(/* dismissUserSwitcher= */true);
            // TODO(b/138250105) Stop unlock advertising
        });

        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null
                && bluetoothAdapter.getLeState() == BluetoothAdapter.STATE_BLE_ON) {
            mUnlockingText.setText(R.string.unlock_dialog_message_start);
        }
    }

    /**
     * This filter is listening on:
     * {@link BluetoothAdapter#ACTION_BLE_STATE_CHANGED} for starting unlock advertising;
     * {@link Intent#ACTION_USER_UNLOCKED} for IHU unlocked
     */
    private IntentFilter getIntentFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_BLE_STATE_CHANGED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        return filter;
    }

    /**
     * Show dialog for the given user
     */
    void showUnlockDialog(int uid, OnHideListener listener) {
        showUnlockDialogAfterDelay(uid, 0, listener);
    }

    /**
     * Show dialog for the given user after the certain time of delay has elapsed
     *
     * @param uid the user to unlock
     * @param listener listener that listens to dialog hide
     */
    void showUnlockDialogAfterDelay(int uid, OnHideListener listener) {
        long delayMillis = mContext.getResources().getInteger(R.integer.unlock_dialog_delay_ms);
        showUnlockDialogAfterDelay(uid, delayMillis, listener);
    }

    /**
     * Show dialog for the given user after the supplied delay has elapsed
     */
    private void showUnlockDialogAfterDelay(int uid, long delayMillis, OnHideListener listener) {
        setUid(uid);
        mOnHideListener = listener;
        if (!mIsDialogShowing) {
            logd("Receiver registered");
            mContext.registerReceiverAsUser(this, UserHandle.ALL, mFilter,
                    /* broadcastPermission= */ null,
                    /* scheduler= */ null);
            new Handler().postDelayed(() -> {
                if (!mUserManager.isUserUnlocked(uid)) {
                    logd("Showed unlock dialog for user: " + uid + " after " + delayMillis
                            + " delay.");
                    mWindowManager.addView(mUnlockDialogLayout, mParams);
                }
            }, delayMillis);
        }
        mIsDialogShowing = true;
    }

    private void setUid(int uid) {
        mUid = uid;
        TextView userName = mUnlockDialogLayout.findViewById(R.id.user_name);
        userName.setText(mUserManager.getUserInfo(mUid).name);
        ImageView avatar = mUnlockDialogLayout.findViewById(R.id.avatar);
        avatar.setImageBitmap(mUserManager.getUserIcon(mUid));
        setButtonText();
    }

    private void hideUnlockDialog(boolean dismissUserSwitcher) {
        if (!mIsDialogShowing) {
            return;
        }
        mWindowManager.removeView(mUnlockDialogLayout);
        logd("Receiver unregistered");
        mContext.unregisterReceiver(this);
        if (mOnHideListener != null) {
            mOnHideListener.onHide(dismissUserSwitcher);
        }
        mIsDialogShowing = false;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        switch (action) {
            case BluetoothAdapter.ACTION_BLE_STATE_CHANGED:
                logd("Received ACTION_BLE_STATE_CHANGED");
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                if (state == BluetoothAdapter.STATE_BLE_ON) {
                    logd("Received BLE_ON");
                    mUnlockingText.setText(R.string.unlock_dialog_message_start);
                }
                break;
            case Intent.ACTION_USER_UNLOCKED:
                int uid = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (uid == mUid) {
                    logd("IHU unlocked");
                    hideUnlockDialog(/* notifyOnHideListener= */false);
                } else {
                    Log.e(TAG, "Received ACTION_USER_UNLOCKED for unexpected uid: " + uid);
                }
                break;
            default:
                Log.e(TAG, "Encountered unexpected action when attempting to set "
                        + "unlock state message: " + action);
        }
    }

    // Set button text based on screen lock type
    private void setButtonText() {
        LockPatternUtils lockPatternUtils = new LockPatternUtils(mContext);
        int passwordQuality = lockPatternUtils.getActivePasswordQuality(mUid);
        switch (passwordQuality) {
            // PIN
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC:
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX:
                mButton.setText(R.string.unlock_dialog_button_text_pin);
                break;
            // Pattern
            case DevicePolicyManager.PASSWORD_QUALITY_SOMETHING:
                mButton.setText(R.string.unlock_dialog_button_text_pattern);
                break;
            // Password
            case DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC:
            case DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC:
            case DevicePolicyManager.PASSWORD_QUALITY_COMPLEX:
                mButton.setText(R.string.unlock_dialog_button_text_password);
                break;
            default:
                Log.e(TAG, "Encountered unexpected screen lock type when attempting to set "
                        + "button text:" + passwordQuality);
        }
    }

    private WindowManager.LayoutParams createLayoutParams() {
        return new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                        | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
                PixelFormat.TRANSLUCENT
        );
    }

    private void logd(String message) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, message);
        }
    }

    /**
     * Listener used to notify when the dialog is hidden
     */
    interface OnHideListener {
        void onHide(boolean dismissUserSwitcher);
    }
}
