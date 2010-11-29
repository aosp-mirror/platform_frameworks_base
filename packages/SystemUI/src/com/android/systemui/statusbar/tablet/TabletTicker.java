/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.tablet;

import java.util.Arrays;

import android.app.Notification;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Slog;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarNotification;

import com.android.systemui.R;
import com.android.systemui.statusbar.StatusBarIconView;

public class TabletTicker extends Handler {
    private static final String TAG = "StatusBar.TabletTicker";

    // 3 is enough to let us see most cases, but not get so far behind that it's too annoying.
    private static final int QUEUE_LENGTH = 3;

    private static final int MSG_ADVANCE = 1;

    private static final int ADVANCE_DELAY = 5000; // 5 seconds

    private Context mContext;

    private ViewGroup mWindow;
    private IBinder mCurrentKey;
    private StatusBarNotification mCurrentNotification;
    private View mCurrentView;

    private IBinder[] mKeys = new IBinder[QUEUE_LENGTH];
    private StatusBarNotification[] mQueue = new StatusBarNotification[QUEUE_LENGTH];
    private int mQueuePos;

    public TabletTicker(Context context) {
        mContext = context;
    }

    public void add(IBinder key, StatusBarNotification notification) {
        if (false) {
            Slog.d(TAG, "add 1 mCurrentNotification=" + mCurrentNotification
                    + " mQueuePos=" + mQueuePos + " mQueue=" + Arrays.toString(mQueue));
        }

        // If it's already in here, remove whatever's in there and put the new one at the end.
        remove(key, false);

        mKeys[mQueuePos] = key;
        mQueue[mQueuePos] = notification;

        // If nothing is running now, start the next one.
        if (mQueuePos == 0 && mCurrentNotification == null) {
            sendEmptyMessage(MSG_ADVANCE);
        }

        if (mQueuePos < QUEUE_LENGTH - 1) {
            mQueuePos++;
        }
    }

    public void remove(IBinder key) {
        remove(key, true);
    }

    public void remove(IBinder key, boolean advance) {
        if (mCurrentKey == key) {
            Slog.d(TAG, "removed current");
            // Showing now
            if (advance) {
                removeMessages(MSG_ADVANCE);
                sendEmptyMessage(MSG_ADVANCE);
            }
        } else {
            // In the queue
            for (int i=0; i<QUEUE_LENGTH; i++) {
                if (mKeys[i] == key) {
                    Slog.d(TAG, "removed from queue: " + i);
                    for (; i<QUEUE_LENGTH-1; i++) {
                        mKeys[i] = mKeys[i+1];
                        mQueue[i] = mQueue[i+1];
                    }
                    mKeys[QUEUE_LENGTH-1] = null;
                    mQueue[QUEUE_LENGTH-1] = null;
                    if (mQueuePos > 0) {
                        mQueuePos--;
                    }
                    break;
                }
            }
        }
    }

    public void halt() {
        removeMessages(MSG_ADVANCE);
        if (mCurrentView != null || mQueuePos != 0) {
            for (int i=0; i<QUEUE_LENGTH; i++) {
                mKeys[i] = null;
                mQueue[i] = null;
            }
            mQueuePos = 0;
            sendEmptyMessage(MSG_ADVANCE);
        }
    }

    public void handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_ADVANCE:
                advance();
                break;
        }
    }

    private void advance() {
        // Out with the old...
        if (mCurrentView != null) {
            mWindow.removeView(mCurrentView);
            mCurrentView = null;
            mCurrentKey = null;
            mCurrentNotification = null;
        }

        // In with the new...
        dequeue();
        while (mCurrentNotification != null) {
            mCurrentView = makeTickerView(mCurrentNotification);
            if (mCurrentView != null) {
                if (mWindow == null) {
                    mWindow = makeWindow();
                    WindowManagerImpl.getDefault().addView(mWindow, mWindow.getLayoutParams());
                }
                mWindow.addView(mCurrentView);
                sendEmptyMessageDelayed(MSG_ADVANCE, ADVANCE_DELAY);
                break;
            }
            dequeue();
        }

        // if there's nothing left, close the window
        // TODO: Do this when the animation is done instead
        if (mCurrentView == null && mWindow != null) {
            WindowManagerImpl.getDefault().removeView(mWindow);
            mWindow = null;
        }
    }

    private void dequeue() {
        mCurrentKey = mKeys[0];
        mCurrentNotification = mQueue[0];
        if (false) {
            Slog.d(TAG, "dequeue mQueuePos=" + mQueuePos + " mQueue=" + Arrays.toString(mQueue));
        }
        final int N = mQueuePos;
        for (int i=0; i<N; i++) {
            mKeys[i] = mKeys[i+1];
            mQueue[i] = mQueue[i+1];
        }
        mKeys[N] = null;
        mQueue[N] = null;
        if (mQueuePos > 0) {
            mQueuePos--;
        }
    }

    private ViewGroup makeWindow() {
        final Resources res = mContext.getResources();
        final FrameLayout view = new FrameLayout(mContext);
        final int width = res.getDimensionPixelSize(R.dimen.notification_ticker_width);
        final int height = res.getDimensionPixelSize(R.dimen.notification_large_icon_height);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        lp.setTitle("NotificationTicker");
        view.setLayoutParams(lp);
        return view;
    }

    private View makeTickerView(StatusBarNotification notification) {
        final Notification n = notification.notification;

        LayoutInflater inflater = (LayoutInflater)mContext.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);

        ViewGroup group;
        int layoutId;
        int iconId;
        if (n.largeIcon != null) {
            iconId = R.id.right_icon;
        } else {
            iconId = R.id.left_icon;
        }
        if (n.tickerView != null) {
            group = (ViewGroup)inflater.inflate(R.layout.status_bar_ticker_panel, null, false);
            View expanded = null;
            Exception exception = null;
            try {
                expanded = n.tickerView.apply(mContext, group);
            }
            catch (RuntimeException e) {
                exception = e;
            }
            if (expanded == null) {
                final String ident = notification.pkg
                        + "/0x" + Integer.toHexString(notification.id);
                Slog.e(TAG, "couldn't inflate view for notification " + ident, exception);
                return null;
            }
            final int statusBarHeight = mContext.getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.status_bar_height);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, statusBarHeight, 1.0f);
            lp.gravity = Gravity.BOTTOM;
            group.addView(expanded, lp);
        } else if (n.tickerText != null) {
            group = (ViewGroup)inflater.inflate(R.layout.status_bar_ticker_compat, mWindow, false);
            final Drawable icon = StatusBarIconView.getIcon(mContext,
                    new StatusBarIcon(notification.pkg, n.icon, n.iconLevel, 0));
            ImageView iv = (ImageView)group.findViewById(iconId);
            iv.setImageDrawable(icon);
            iv.setVisibility(View.VISIBLE);
            TextView tv = (TextView)group.findViewById(R.id.text);
            tv.setText(n.tickerText);
        } else {
            throw new RuntimeException("tickerView==null && tickerText==null");
        }
        ImageView largeIcon = (ImageView)group.findViewById(R.id.large_icon);
        if (n.largeIcon != null) {
            largeIcon.setImageBitmap(n.largeIcon);
            largeIcon.setVisibility(View.VISIBLE);
        }
        return group;
    }
}

