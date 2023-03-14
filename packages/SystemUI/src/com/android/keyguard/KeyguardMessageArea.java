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
import android.content.res.TypedArray;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.internal.policy.SystemBarUtils;
import com.android.systemui.R;

import java.lang.ref.WeakReference;

/***
 * Manages a number of views inside of the given layout. See below for a list of widgets.
 */
public abstract class KeyguardMessageArea extends TextView implements SecurityMessageDisplay {
    /** Handler token posted with accessibility announcement runnables. */
    private static final Object ANNOUNCE_TOKEN = new Object();

    /**
     * Delay before speaking an accessibility announcement. Used to prevent
     * lift-to-type from interrupting itself.
     */
    private static final long ANNOUNCEMENT_DELAY = 250;

    private final Handler mHandler;

    private CharSequence mMessage;
    private boolean mIsVisible;
    /**
     * Container that wraps the KeyguardMessageArea - may be null if current view hierarchy doesn't
     * contain {@link R.id.keyguard_message_area_container}.
     */
    @Nullable
    private ViewGroup mContainer;
    private int mTopMargin;
    protected boolean mAnimate;

    public KeyguardMessageArea(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayerType(LAYER_TYPE_HARDWARE, null); // work around nested unclipped SaveLayer bug

        mHandler = new Handler(Looper.myLooper());
        onThemeChanged();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mContainer = getRootView().findViewById(R.id.keyguard_message_area_container);
    }

    void onConfigChanged() {
        if (mContainer == null) {
            return;
        }
        final int newTopMargin = SystemBarUtils.getStatusBarHeight(getContext());
        if (mTopMargin == newTopMargin) {
            return;
        }
        mTopMargin = newTopMargin;
        ViewGroup.MarginLayoutParams lp =
                (ViewGroup.MarginLayoutParams) mContainer.getLayoutParams();
        lp.topMargin = mTopMargin;
        mContainer.setLayoutParams(lp);
    }

    protected void onThemeChanged() {
        update();
    }

    protected void reloadColor() {
        update();
    }

    void onDensityOrFontScaleChanged() {
        TypedArray array = mContext.obtainStyledAttributes(R.style.Keyguard_TextView, new int[] {
                android.R.attr.textSize
        });
        setTextSize(TypedValue.COMPLEX_UNIT_PX, array.getDimensionPixelSize(0, 0));
        array.recycle();
    }

    @Override
    public void setMessage(CharSequence msg, boolean animate) {
        if (!TextUtils.isEmpty(msg)) {
            securityMessageChanged(msg);
        } else {
            clearMessage();
        }
    }

    @Override
    public void formatMessage(int resId, Object... formatArgs) {
        CharSequence message = null;
        if (resId != 0) {
            message = getContext().getString(resId, formatArgs);
        }
        setMessage(message, true);
    }

    private void securityMessageChanged(CharSequence message) {
        mMessage = message;
        update();
        mHandler.removeCallbacksAndMessages(ANNOUNCE_TOKEN);
        mHandler.postAtTime(new AnnounceRunnable(this, getText()), ANNOUNCE_TOKEN,
                (SystemClock.uptimeMillis() + ANNOUNCEMENT_DELAY));
    }

    private void clearMessage() {
        mMessage = null;
        update();
    }

    void update() {
        CharSequence status = mMessage;
        setVisibility(TextUtils.isEmpty(status) || (!mIsVisible) ? INVISIBLE : VISIBLE);
        setText(status);
        updateTextColor();
    }

    /**
     * Set whether the bouncer is fully showing
     */
    public void setIsVisible(boolean isVisible) {
        if (mIsVisible != isVisible) {
            mIsVisible = isVisible;
            update();
        }
    }

    /** Set the text color */
    protected abstract void updateTextColor();

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
