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

import android.animation.LayoutTransition;
import android.app.Notification;
import android.app.PendingIntent;
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
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarNotification;

import com.android.systemui.R;
import com.android.systemui.statusbar.StatusBarIconView;

public class TabletTicker
        extends Handler
        implements LayoutTransition.TransitionListener {

    private static final String TAG = "StatusBar.TabletTicker";

    private static final boolean CLICKABLE_TICKER = true;

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

    private final int mLargeIconHeight;

    private TabletStatusBar mBar;

    private LayoutTransition mLayoutTransition;
    private boolean mWindowShouldClose;

    public TabletTicker(TabletStatusBar bar) {
        mBar = bar;
        mContext = bar.getContext();
        final Resources res = mContext.getResources();
        mLargeIconHeight = res.getDimensionPixelSize(
                android.R.dimen.notification_large_icon_height);
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
            // Showing now
            if (advance) {
                removeMessages(MSG_ADVANCE);
                sendEmptyMessage(MSG_ADVANCE);
            }
        } else {
            // In the queue
            for (int i=0; i<QUEUE_LENGTH; i++) {
                if (mKeys[i] == key) {
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
            if (mWindow != null) {
                mWindow.removeView(mCurrentView);
            }
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
        mWindowShouldClose = (mCurrentView == null && mWindow != null);
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
        int windowFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (CLICKABLE_TICKER) {
            windowFlags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        } else {
            windowFlags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, mLargeIconHeight,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL, windowFlags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
//        lp.windowAnimations = com.android.internal.R.style.Animation_Toast;

        mLayoutTransition = new LayoutTransition();
        mLayoutTransition.addTransitionListener(this);
        view.setLayoutTransition(mLayoutTransition);
        lp.setTitle("NotificationTicker");
        view.setLayoutParams(lp);
        return view;
    }

    public void startTransition(LayoutTransition transition, ViewGroup container,
            View view, int transitionType) {}

    public void endTransition(LayoutTransition transition, ViewGroup container,
            View view, int transitionType) {
        if (mWindowShouldClose) {
            WindowManagerImpl.getDefault().removeView(mWindow);
            mWindow = null;
            mWindowShouldClose = false;
            mBar.doneTicking();
        }
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
            group = (ViewGroup)inflater.inflate(R.layout.system_bar_ticker_panel, null, false);
            ViewGroup content = (FrameLayout) group.findViewById(R.id.ticker_expanded);
            View expanded = null;
            Exception exception = null;
            try {
                expanded = n.tickerView.apply(mContext, content);
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
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 
                    ViewGroup.LayoutParams.MATCH_PARENT);
            content.addView(expanded, lp);
        } else if (n.tickerText != null) {
            group = (ViewGroup)inflater.inflate(R.layout.system_bar_ticker_compat, mWindow, false);
            final Drawable icon = StatusBarIconView.getIcon(mContext,
                    new StatusBarIcon(notification.pkg, n.icon, n.iconLevel, 0, n.tickerText));
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
            final ViewGroup.LayoutParams lp = largeIcon.getLayoutParams();
            final int statusBarHeight = mBar.getStatusBarHeight();
            if (n.largeIcon.getHeight() <= statusBarHeight) {
                // for smallish largeIcons, it looks a little odd to have them floating halfway up
                // the ticker, so we vertically center them in the status bar area instead
                lp.height = statusBarHeight;
            } else {
                lp.height = mLargeIconHeight;
            }
            largeIcon.setLayoutParams(lp);
        }

        if (CLICKABLE_TICKER) {
            PendingIntent contentIntent = notification.notification.contentIntent;
            if (contentIntent != null) {
                // create the usual notification clicker, but chain it together with a halt() call
                // to abort the ticker too
                final View.OnClickListener clicker = mBar.makeClicker(contentIntent,
                                            notification.pkg, notification.tag, notification.id);
                group.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        halt();
                        clicker.onClick(v);
                    }
                });
            } else {
                group.setOnClickListener(null);
            }
        }

        return group;
    }
}

