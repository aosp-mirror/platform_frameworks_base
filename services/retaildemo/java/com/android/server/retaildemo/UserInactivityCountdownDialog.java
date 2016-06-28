/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.retaildemo;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.os.CountDownTimer;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.internal.R;

public class UserInactivityCountdownDialog extends AlertDialog {

    private OnCountDownExpiredListener mOnCountDownExpiredListener;
    private CountDownTimer mCountDownTimer;
    private long mCountDownDuration;
    private long mRefreshInterval;

    UserInactivityCountdownDialog(Context context, long duration, long refreshInterval) {
        super(context);
        mCountDownDuration = duration;
        mRefreshInterval = refreshInterval;

        getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        getWindow().setAttributes(attrs);

        setTitle(R.string.demo_user_inactivity_timeout_title);
        setMessage(getContext().getString(R.string.demo_user_inactivity_timeout_countdown,
                duration));
    }

    public void setOnCountDownExpiredListener(
            OnCountDownExpiredListener onCountDownExpiredListener) {
        mOnCountDownExpiredListener = onCountDownExpiredListener;
    }

    public void setPositiveButtonClickListener(OnClickListener onClickListener) {
        setButton(Dialog.BUTTON_POSITIVE,
                getContext().getString(R.string.demo_user_inactivity_timeout_right_button),
                onClickListener);
    }

    public void setNegativeButtonClickListener(OnClickListener onClickListener) {
        setButton(Dialog.BUTTON_NEGATIVE,
                getContext().getString(R.string.demo_user_inactivity_timeout_left_button),
                onClickListener);
    }

    @Override
    public void show() {
        super.show();
        final TextView messageView = (TextView) findViewById(R.id.message);
        messageView.post(new Runnable() {
            @Override
            public void run() {
                mCountDownTimer = new CountDownTimer(mCountDownDuration, mRefreshInterval) {

                    @Override
                    public void onTick(long millisUntilFinished) {
                        String msg = getContext().getString(
                                R.string.demo_user_inactivity_timeout_countdown,
                                millisUntilFinished / 1000);
                        messageView.setText(msg);
                    }

                    @Override
                    public void onFinish() {
                        dismiss();
                        if (mOnCountDownExpiredListener != null)
                            mOnCountDownExpiredListener.onCountDownExpired();
                    }
                }.start();
            }
        });
    }

    @Override
    public void onStop() {
        if (mCountDownTimer != null) {
            mCountDownTimer.cancel();
        }
    }

    interface OnCountDownExpiredListener {
        void onCountDownExpired();
    }
}
