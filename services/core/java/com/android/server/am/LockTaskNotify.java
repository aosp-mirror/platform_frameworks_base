/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.am;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Message;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ImageSpan;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.R;

/**
 *  Helper to manage showing/hiding a image to notify them that they are entering
 *  or exiting lock-to-app mode.
 */
public class LockTaskNotify {
    private static final String TAG = "LockTaskNotify";

    private static final int SHOW_LENGTH_MS = 1500;

    private final Context mContext;
    private final H mHandler;

    private ClingWindowView mClingWindow;
    private WindowManager mWindowManager;
    private boolean mIsStarting;

    public LockTaskNotify(Context context) {
        mContext = context;
        mHandler = new H();
        mWindowManager = (WindowManager)
                mContext.getSystemService(Context.WINDOW_SERVICE);
    }

    public void showToast(boolean isLocked) {
        mHandler.obtainMessage(H.SHOW_TOAST, isLocked ? 1 : 0, 0 /* Not used */).sendToTarget();
    }

    public void handleShowToast(boolean isLocked) {
        final Resources r = Resources.getSystem();
        String text = mContext.getString(isLocked
                ? R.string.lock_to_app_toast_locked : R.string.lock_to_app_toast);
        Toast toast = Toast.makeText(mContext, text, Toast.LENGTH_LONG);
        TextView tv = (TextView) toast.getView().findViewById(R.id.message);

        if (isLocked) {
            tv.setText(text);
        } else {
            final SpannableString formattedText =
                    new SpannableString(text.replace('$', ' '));
            final ImageSpan imageSpan = new ImageSpan(mContext,
                    BitmapFactory.decodeResource(r, R.drawable.ic_recent),
                    DynamicDrawableSpan.ALIGN_BOTTOM);
            final int index = text.indexOf('$');
            if (index >= 0) {
                formattedText.setSpan(imageSpan, index, index + 1,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            // Make icon fit.
            final float width = imageSpan.getDrawable().getIntrinsicWidth();
            final float height = imageSpan.getDrawable().getIntrinsicHeight();
            final int lineHeight = tv.getLineHeight();
            imageSpan.getDrawable().setBounds(0, 0, (int) (lineHeight * width / height),
                    lineHeight);

            tv.setText(formattedText);
        }


        toast.show();
    }

    public void show(boolean starting) {
        mIsStarting = starting;
        mHandler.obtainMessage(H.SHOW).sendToTarget();
    }

    public WindowManager.LayoutParams getClingWindowLayoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_TOAST,
                0
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                ,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        lp.setTitle("LockTaskNotify");
        lp.windowAnimations = com.android.internal.R.style.Animation_RecentApplications;
        lp.gravity = Gravity.FILL;
        return lp;
    }

    public FrameLayout.LayoutParams getImageLayoutParams() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
    }

    private void handleShow() {
        mClingWindow = new ClingWindowView(mContext);

        // we will be hiding the nav bar, so layout as if it's already hidden
        mClingWindow.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        // show the confirmation
        WindowManager.LayoutParams lp = getClingWindowLayoutParams();
        mWindowManager.addView(mClingWindow, lp);
    }

    private void handleHide() {
        if (mClingWindow != null) {
            mWindowManager.removeView(mClingWindow);
            mClingWindow = null;
        }
    }


    private class ClingWindowView extends FrameLayout {
        private View mView;

        private Runnable mUpdateLayoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (mView != null && mView.getParent() != null) {
                    mView.setLayoutParams(getImageLayoutParams());
                }
            }
        };

        private BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                    post(mUpdateLayoutRunnable);
                }
            }
        };

        public ClingWindowView(Context context) {
            super(context);
            setClickable(true);
        }

        @Override
        public void onAttachedToWindow() {
            super.onAttachedToWindow();

            DisplayMetrics metrics = new DisplayMetrics();
            mWindowManager.getDefaultDisplay().getMetrics(metrics);

            int id = R.layout.lock_to_app_exit;
            if (mIsStarting) {
                id = R.layout.lock_to_app_enter;
            }
            mView = View.inflate(getContext(), id, null);

            addView(mView, getImageLayoutParams());

            mContext.registerReceiver(mReceiver,
                    new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED));
            mHandler.sendMessageDelayed(mHandler.obtainMessage(H.HIDE), SHOW_LENGTH_MS);
        }

        @Override
        public void onDetachedFromWindow() {
            mContext.unregisterReceiver(mReceiver);
        }

        @Override
        public boolean onTouchEvent(MotionEvent motion) {
            Slog.v(TAG, "ClingWindowView.onTouchEvent");
            return true;
        }
    }

    private final class H extends Handler {
        private static final int SHOW = 1;
        private static final int HIDE = 2;
        private static final int SHOW_TOAST = 3;

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case SHOW:
                    handleShow();
                    break;
                case HIDE:
                    handleHide();
                    break;
                case SHOW_TOAST:
                    handleShowToast(msg.arg1 != 0);
                    break;
            }
        }
    }
}
