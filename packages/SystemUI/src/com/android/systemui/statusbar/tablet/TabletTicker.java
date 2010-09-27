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

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Message;
import android.util.Slog;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.statusbar.StatusBarNotification;

import com.android.systemui.R;

import java.util.Arrays;

public class TabletTicker extends Handler {
    private static final String TAG = "StatusBar.TabletTicker";

    private static final int MSG_ADVANCE = 1;

    private static final int ADVANCE_DELAY = 5000; // 5 seconds

    private Context mContext;
    private FrameLayout mParent;

    private StatusBarNotification mCurrentNotification;
    private View mCurrentView;

    private StatusBarNotification[] mQueue;
    private int mQueuePos;

    public TabletTicker(Context context, FrameLayout parent) {
        mContext = context;
        mParent = parent;

        // TODO: Make this a configuration value.
        // 3 is enough to let us see most cases, but not get so far behind that it's annoying.
        mQueue = new StatusBarNotification[3];
    }

    public void add(StatusBarNotification notification) {
        if (false) {
            Slog.d(TAG, "add mCurrentNotification=" + mCurrentNotification
                    + " mQueuePos=" + mQueuePos + " mQueue=" + Arrays.toString(mQueue));
        }
        mQueue[mQueuePos] = notification;

        // If nothing is running now, start the next one
        if (mCurrentNotification == null) {
            sendEmptyMessage(MSG_ADVANCE);
        }

        if (mQueuePos < mQueue.length - 1) {
            mQueuePos++;
        }
    }

    public void halt() {
        removeMessages(MSG_ADVANCE);
        if (mCurrentView != null) {
            final int N = mQueue.length;
            for (int i=0; i<N; i++) {
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
            mParent.removeView(mCurrentView);
            mCurrentView = null;
            mCurrentNotification = null;
        }

        // In with the new...
        final StatusBarNotification next = dequeue();
        if (next != null) {
            mCurrentNotification = next;
            mCurrentView = makeTickerView(next);
            mParent.addView(mCurrentView);
            sendEmptyMessageDelayed(MSG_ADVANCE, ADVANCE_DELAY);
        }
    }

    private StatusBarNotification dequeue() {
        StatusBarNotification notification = mQueue[0];
        if (false) {
            Slog.d(TAG, "dequeue mQueuePos=" + mQueuePos + " mQueue=" + Arrays.toString(mQueue));
        }
        final int N = mQueuePos;
        for (int i=0; i<N; i++) {
            mQueue[i] = mQueue[i+1];
        }
        mQueue[N] = null;
        if (mQueuePos > 0) {
            mQueuePos--;
        }
        return notification;
    }

    private View makeTickerView(StatusBarNotification notification) {
        final Notification n = notification.notification;

        LayoutInflater inflater = (LayoutInflater)mContext.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);

        int layoutId;
        ViewGroup group;
        if (n.tickerTitle != null || n.tickerSubtitle != null) {
            group = (ViewGroup)inflater.inflate(R.layout.ticker, mParent, false);
            if (n.tickerTitle != null) {
                final TextView title = (TextView)group.findViewById(R.id.title);
                title.setText(n.tickerTitle);
            }
            if (n.tickerSubtitle != null) {
                final TextView subtitle = (TextView)group.findViewById(R.id.subtitle);
                subtitle.setText(n.tickerSubtitle);
            }
        } else {
            group = (ViewGroup)inflater.inflate(R.layout.ticker_compat, mParent, false);
            TextView tv = (TextView)group.findViewById(R.id.text);
            tv.setText(n.tickerText);
        }

        // No more than 2 icons.
        if (n.tickerIcons != null) {
            int N = n.tickerIcons.length;
            if (N > 2) {
                N = 2;
            }
            for (int i=N-1; i>= 0; i--) {
                Bitmap b = n.tickerIcons[i];
                if (b != null) {
                    ImageView iv = (ImageView)inflater.inflate(R.layout.ticker_icon, group, false);
                    iv.setImageBitmap(b);
                    group.addView(iv, 0);
                }
            }
        }

        return group;
    }
}

