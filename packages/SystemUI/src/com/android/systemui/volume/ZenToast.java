/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
import static android.provider.Settings.Global.ZEN_MODE_NO_INTERRUPTIONS;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;

public class ZenToast {
    private static final String ACTION_SHOW = ZenToast.class.getName() + ".SHOW";
    private static final String ACTION_HIDE = ZenToast.class.getName() + ".HIDE";
    private static final String EXTRA_ZEN = "zen";
    private static final String EXTRA_TEXT = "text";

    private static final int MSG_SHOW = 1;
    private static final int MSG_HIDE = 2;

    private final Context mContext;
    private final WindowManager mWindowManager;

    private View mZenToast;

    public ZenToast(Context context) {
        mContext = context;
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SHOW);
        filter.addAction(ACTION_HIDE);
        mContext.registerReceiverAsUser(mReceiver, UserHandle.ALL, filter, null, mHandler);
    }

    public void show(int zen) {
        mHandler.removeMessages(MSG_HIDE);
        mHandler.removeMessages(MSG_SHOW);
        mHandler.obtainMessage(MSG_SHOW, zen, 0).sendToTarget();
    }

    public void hide() {
        mHandler.removeMessages(MSG_HIDE);
        mHandler.removeMessages(MSG_SHOW);
        mHandler.obtainMessage(MSG_HIDE).sendToTarget();
    }

    private void handleShow(int zen, String overrideText) {
        handleHide();

        String text;
        final int iconRes;
        switch (zen) {
            case ZEN_MODE_NO_INTERRUPTIONS:
                text = mContext.getString(R.string.zen_no_interruptions);
                iconRes = R.drawable.ic_zen_none;
                break;
            case ZEN_MODE_IMPORTANT_INTERRUPTIONS:
                text = mContext.getString(R.string.zen_important_interruptions);
                iconRes = R.drawable.ic_zen_important;
                break;
            default:
                return;
        }
        if (overrideText != null) {
            text = overrideText;
        }
        final Resources res = mContext.getResources();
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.width = res.getDimensionPixelSize(R.dimen.zen_toast_width);
        params.format = PixelFormat.TRANSLUCENT;
        params.windowAnimations = R.style.ZenToastAnimations;
        params.type = WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL;
        params.setTitle(getClass().getSimpleName());
        params.flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        params.gravity = Gravity.CENTER;
        params.packageName = mContext.getPackageName();
        mZenToast = LayoutInflater.from(mContext).inflate(R.layout.zen_toast, null);
        final TextView message = (TextView) mZenToast.findViewById(android.R.id.message);
        message.setText(text);
        final ImageView icon = (ImageView) mZenToast.findViewById(android.R.id.icon);
        icon.setImageResource(iconRes);
        mZenToast.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override
            public void onViewDetachedFromWindow(View v) {
                // noop
            }

            @Override
            public void onViewAttachedToWindow(View v) {
                mZenToast.announceForAccessibility(message.getText());
            }
        });
        mWindowManager.addView(mZenToast, params);
        final int animDuration = res.getInteger(R.integer.zen_toast_animation_duration);
        final int visibleDuration = res.getInteger(R.integer.zen_toast_visible_duration);
        mHandler.sendEmptyMessageDelayed(MSG_HIDE, animDuration + visibleDuration);
    }

    private void handleHide() {
        if (mZenToast != null) {
            mWindowManager.removeView(mZenToast);
            mZenToast = null;
        }
    }

    private final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SHOW:
                    handleShow(msg.arg1, null);
                    break;
                case MSG_HIDE:
                    handleHide();
                    break;
            }
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_SHOW.equals(intent.getAction())) {
                final int zen = intent.getIntExtra(EXTRA_ZEN, ZEN_MODE_IMPORTANT_INTERRUPTIONS);
                final String text = intent.getStringExtra(EXTRA_TEXT);
                handleShow(zen, text);
            } else if (ACTION_HIDE.equals(intent.getAction())) {
                handleHide();
            }
        }
    };
}
