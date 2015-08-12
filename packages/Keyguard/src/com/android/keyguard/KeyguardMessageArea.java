/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.keyguard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/***
 * Manages a number of views inside of the given layout. See below for a list of widgets.
 */
class KeyguardMessageArea extends TextView implements SecurityMessageDisplay {
    /** Handler token posted with accessibility announcement runnables. */
    private static final Object ANNOUNCE_TOKEN = new Object();

    /**
     * Delay before speaking an accessibility announcement. Used to prevent
     * lift-to-type from interrupting itself.
     */
    private static final long ANNOUNCEMENT_DELAY = 250;
    private static final int DEFAULT_COLOR = -1;

    private static final int SECURITY_MESSAGE_DURATION = 5000;

    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final Handler mHandler;
    private final int mDefaultColor;

    // Timeout before we reset the message to show charging/owner info
    long mTimeout = SECURITY_MESSAGE_DURATION;
    CharSequence mMessage;
    private int mNextMessageColor = DEFAULT_COLOR;

    private final Runnable mClearMessageRunnable = new Runnable() {
        @Override
        public void run() {
            mMessage = null;
            update();
        }
    };

    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {
        public void onFinishedGoingToSleep(int why) {
            setSelected(false);
        };
        public void onStartedWakingUp() {
            setSelected(true);
        };
    };

    public KeyguardMessageArea(Context context) {
        this(context, null);
    }

    public KeyguardMessageArea(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayerType(LAYER_TYPE_HARDWARE, null); // work around nested unclipped SaveLayer bug

        mUpdateMonitor = KeyguardUpdateMonitor.getInstance(getContext());
        mUpdateMonitor.registerCallback(mInfoCallback);
        mHandler = new Handler(Looper.myLooper());

        mDefaultColor = getCurrentTextColor();
        update();
    }

    @Override
    public void setNextMessageColor(int color) {
        mNextMessageColor = color;
    }

    @Override
    public void setMessage(CharSequence msg, boolean important) {
        if (!TextUtils.isEmpty(msg) && important) {
            securityMessageChanged(msg);
        } else {
            clearMessage();
        }
    }

    @Override
    public void setMessage(int resId, boolean important) {
        if (resId != 0 && important) {
            CharSequence message = getContext().getResources().getText(resId);
            securityMessageChanged(message);
        } else {
            clearMessage();
        }
    }

    @Override
    public void setMessage(int resId, boolean important, Object... formatArgs) {
        if (resId != 0 && important) {
            String message = getContext().getString(resId, formatArgs);
            securityMessageChanged(message);
        } else {
            clearMessage();
        }
    }

    @Override
    public void setTimeout(int timeoutMs) {
        mTimeout = timeoutMs;
    }

    public static SecurityMessageDisplay findSecurityMessageDisplay(View v) {
        KeyguardMessageArea messageArea = (KeyguardMessageArea) v.findViewById(
                R.id.keyguard_message_area);
        if (messageArea == null) {
            throw new RuntimeException("Can't find keyguard_message_area in " + v.getClass());
        }
        return messageArea;
    }

    @Override
    protected void onFinishInflate() {
        boolean shouldMarquee = KeyguardUpdateMonitor.getInstance(mContext).isDeviceInteractive();
        setSelected(shouldMarquee); // This is required to ensure marquee works
    }

    private void securityMessageChanged(CharSequence message) {
        mMessage = message;
        update();
        mHandler.removeCallbacks(mClearMessageRunnable);
        if (mTimeout > 0) {
            mHandler.postDelayed(mClearMessageRunnable, mTimeout);
        }
        mHandler.removeCallbacksAndMessages(ANNOUNCE_TOKEN);
        mHandler.postAtTime(new AnnounceRunnable(this, getText()), ANNOUNCE_TOKEN,
                (SystemClock.uptimeMillis() + ANNOUNCEMENT_DELAY));
    }

    private void clearMessage() {
        mHandler.removeCallbacks(mClearMessageRunnable);
        mHandler.post(mClearMessageRunnable);
    }

    private void update() {
        CharSequence status = mMessage;
        setVisibility(TextUtils.isEmpty(status) ? INVISIBLE : VISIBLE);
        setText(status);
        int color = mDefaultColor;
        if (mNextMessageColor != DEFAULT_COLOR) {
            color = mNextMessageColor;
            mNextMessageColor = DEFAULT_COLOR;
        }
        setTextColor(color);
    }


    /**
     * Runnable used to delay accessibility announcements.
     */
    private static class AnnounceRunnable implements Runnable {
        private final WeakReference<View> mHost;
        private final CharSequence mTextToAnnounce;

        AnnounceRunnable(View host, CharSequence textToAnnounce) {
            mHost = new WeakReference<View>(host);
            mTextToAnnounce = textToAnnounce;
        }

        @Override
        public void run() {
            final View host = mHost.get();
            if (host != null) {
                host.announceForAccessibility(mTextToAnnounce);
            }
        }
    }
}
