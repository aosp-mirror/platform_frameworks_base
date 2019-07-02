/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.volume;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources.NotFoundException;
import android.media.AudioManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;

import com.android.systemui.statusbar.phone.SystemUIDialog;

abstract public class SafetyWarningDialog extends SystemUIDialog
        implements DialogInterface.OnDismissListener, DialogInterface.OnClickListener {

    private static final String TAG = Util.logTag(SafetyWarningDialog.class);

    private static final int KEY_CONFIRM_ALLOWED_AFTER = 1000; // milliseconds

    private final Context mContext;
    private final AudioManager mAudioManager;

    private long mShowTime;
    private boolean mNewVolumeUp;
    private boolean mDisableOnVolumeUp;

    public SafetyWarningDialog(Context context, AudioManager audioManager) {
        super(context);
        mContext = context;
        mAudioManager = audioManager;
        try {
            mDisableOnVolumeUp = mContext.getResources().getBoolean(
                  com.android.internal.R.bool.config_safe_media_disable_on_volume_up);
        } catch (NotFoundException e) {
            mDisableOnVolumeUp = true;
        }
        getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
        setShowForAllUsers(true);
        setMessage(mContext.getString(com.android.internal.R.string.safe_media_volume_warning));
        setButton(DialogInterface.BUTTON_POSITIVE,
                mContext.getString(com.android.internal.R.string.yes), this);
        setButton(DialogInterface.BUTTON_NEGATIVE,
                mContext.getString(com.android.internal.R.string.no), (OnClickListener) null);
        setOnDismissListener(this);

        final IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        context.registerReceiver(mReceiver, filter);
    }

    abstract protected void cleanUp();

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mDisableOnVolumeUp && keyCode == KeyEvent.KEYCODE_VOLUME_UP
            && event.getRepeatCount() == 0) {
            mNewVolumeUp = true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && mNewVolumeUp
                && (System.currentTimeMillis() - mShowTime) > KEY_CONFIRM_ALLOWED_AFTER) {
            if (D.BUG) Log.d(TAG, "Confirmed warning via VOLUME_UP");
            mAudioManager.disableSafeMediaVolume();
            dismiss();
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        mAudioManager.disableSafeMediaVolume();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mShowTime = System.currentTimeMillis();
    }

    @Override
    public void onDismiss(DialogInterface unused) {
        mContext.unregisterReceiver(mReceiver);
        cleanUp();
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                if (D.BUG) Log.d(TAG, "Received ACTION_CLOSE_SYSTEM_DIALOGS");
                cancel();
                cleanUp();
            }
        }
    };
}
