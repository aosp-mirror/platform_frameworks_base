/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.ServiceManager;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.ImageView;
import android.widget.RemoteViews.RemoteView;

import com.android.systemui.R;

public class KeyButtonView extends ImageView {
    IWindowManager mWindowManager;
    long mDownTime;
    boolean mSending;
    int mCode;
    int mRepeat;

    public KeyButtonView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyButtonView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.KeyButtonView,
                defStyle, 0);

        mCode = a.getInteger(R.styleable.KeyButtonView_keyCode, 0);
        if (mCode == 0) {
            Slog.w(StatusBarService.TAG, "KeyButtonView without key code id=0x"
                    + Integer.toHexString(getId()));
        }
        
        a.recycle();

        mWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));
    }

    public boolean onTouchEvent(MotionEvent ev) {
        final int action = ev.getAction();
        int x, y;

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mDownTime = SystemClock.uptimeMillis();
                mRepeat = 0;
                mSending = true;
                sendEvent(KeyEvent.ACTION_DOWN, mDownTime);
                break;
            case MotionEvent.ACTION_MOVE:
                if (mSending) {
                    x = (int)ev.getX();
                    y = (int)ev.getY();
                    if (x < 0 || x >= getWidth() || y < 0 || y >= getHeight()) {
                        mSending = false;
                        sendEvent(KeyEvent.ACTION_UP);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mSending) {
                    sendEvent(KeyEvent.ACTION_UP);
                    mSending = false;
                }
                break;
        }

        return true;
    }

    void sendEvent(int action) {
        sendEvent(action, SystemClock.uptimeMillis());
    }

    void sendEvent(int action, long when) {
        final KeyEvent ev = new KeyEvent(mDownTime, mDownTime, action, mCode, mRepeat);
        try {
            Slog.d(StatusBarService.TAG, "injecting event " + ev);
            mWindowManager.injectKeyEvent(ev, false);
        } catch (RemoteException ex) {
            // System process is dead
        }
    }
}


