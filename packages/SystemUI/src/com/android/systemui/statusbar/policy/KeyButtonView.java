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

package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.ServiceManager;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.accessibility.AccessibilityEvent;
import android.view.HapticFeedbackConstants;
import android.view.IWindowManager;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.ViewConfiguration;
import android.widget.ImageView;
import android.widget.RemoteViews.RemoteView;

import com.android.systemui.R;

public class KeyButtonView extends ImageView {
    private static final String TAG = "StatusBar.KeyButtonView";

    IWindowManager mWindowManager;
    long mDownTime;
    boolean mSending;
    int mCode;
    int mRepeat;
    int mTouchSlop;

    Runnable mCheckLongPress = new Runnable() {
        public void run() {
            if (isPressed()) {
                mRepeat++;
                sendEvent(KeyEvent.ACTION_DOWN,
                        KeyEvent.FLAG_FROM_SYSTEM
                        | KeyEvent.FLAG_VIRTUAL_HARD_KEY
                        | KeyEvent.FLAG_LONG_PRESS);

                sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
                //playSoundEffect(SoundEffectConstants.CLICK);
            }
        }
    };

    public KeyButtonView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyButtonView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.KeyButtonView,
                defStyle, 0);

        mCode = a.getInteger(R.styleable.KeyButtonView_keyCode, 0);
        if (mCode == 0) {
            Slog.w(TAG, "KeyButtonView without key code id=0x" + Integer.toHexString(getId()));
        }
        
        a.recycle();

        mWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));

        setClickable(true);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public boolean onTouchEvent(MotionEvent ev) {
        final int action = ev.getAction();
        int x, y;

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                //Slog.d("KeyButtonView", "press");
                mDownTime = SystemClock.uptimeMillis();
                mRepeat = 0;
                mSending = true;
                sendEvent(KeyEvent.ACTION_DOWN,
                        KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY, mDownTime);
                setPressed(true);
                removeCallbacks(mCheckLongPress);
                postDelayed(mCheckLongPress, ViewConfiguration.getLongPressTimeout());
                break;
            case MotionEvent.ACTION_MOVE:
                if (mSending) {
                    x = (int)ev.getX();
                    y = (int)ev.getY();
                    setPressed(x >= -mTouchSlop
                            && x < getWidth() + mTouchSlop
                            && y >= -mTouchSlop
                            && y < getHeight() + mTouchSlop);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                if (mSending) {
                    mSending = false;
                    sendEvent(KeyEvent.ACTION_UP,
                            KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY
                                | KeyEvent.FLAG_CANCELED);
                    removeCallbacks(mCheckLongPress);
                }
                break;
            case MotionEvent.ACTION_UP:
                final boolean doIt = isPressed();
                setPressed(false);
                if (mSending) {
                    mSending = false;
                    removeCallbacks(mCheckLongPress);
                    if (doIt) {
                        sendEvent(KeyEvent.ACTION_UP,
                                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY);

                        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
                        playSoundEffect(SoundEffectConstants.CLICK);
                    }
                }
                break;
        }

        return true;
    }

    void sendEvent(int action, int flags) {
        sendEvent(action, flags, SystemClock.uptimeMillis());
    }

    void sendEvent(int action, int flags, long when) {
        final KeyEvent ev = new KeyEvent(mDownTime, when, action, mCode, mRepeat,
                0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD);
        try {
            //Slog.d(TAG, "injecting event " + ev);
            mWindowManager.injectInputEventNoWait(ev);
        } catch (RemoteException ex) {
            // System process is dead
        }
    }
}


