/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.Context;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;

import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;

import com.android.keyguard.AlphaOptimizedImageButton;
import com.android.systemui.R;

/** Toggle button in Volume Dialog that allows extra state for when streams are opted-out */
public class CaptionsToggleImageButton extends AlphaOptimizedImageButton {

    private static final int[] OPTED_OUT_STATE = new int[] { R.attr.optedOut };

    private ConfirmedTapListener mConfirmedTapListener;
    private boolean mCaptionsEnabled = false;
    private boolean mOptedOut = false;

    private GestureDetector mGestureDetector;
    private GestureDetector.SimpleOnGestureListener mGestureListener =
            new GestureDetector.SimpleOnGestureListener() {
        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            return tryToSendTapConfirmedEvent();
        }
    };

    public CaptionsToggleImageButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.setContentDescription(
                getContext().getString(R.string.volume_odi_captions_content_description));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mGestureDetector != null) mGestureDetector.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    @Override
    public int[] onCreateDrawableState(int extraSpace) {
        int[] state = super.onCreateDrawableState(extraSpace + 1);
        if (mOptedOut) {
            mergeDrawableStates(state, OPTED_OUT_STATE);
        }
        return state;
    }

    Runnable setCaptionsEnabled(boolean areCaptionsEnabled) {
        this.mCaptionsEnabled = areCaptionsEnabled;

        ViewCompat.replaceAccessibilityAction(
                this,
                AccessibilityActionCompat.ACTION_CLICK,
                mCaptionsEnabled
                        ? getContext().getString(R.string.volume_odi_captions_hint_disable)
                        : getContext().getString(R.string.volume_odi_captions_hint_enable),
                (view, commandArguments) -> tryToSendTapConfirmedEvent());

        return this.setImageResourceAsync(mCaptionsEnabled
                ? R.drawable.ic_volume_odi_captions
                : R.drawable.ic_volume_odi_captions_disabled);
    }

    private boolean tryToSendTapConfirmedEvent() {
        if (mConfirmedTapListener != null) {
            mConfirmedTapListener.onConfirmedTap();
            return true;
        }
        return false;
    }

    boolean getCaptionsEnabled() {
        return this.mCaptionsEnabled;
    }

    /** Sets whether or not the current stream has opted out of captions */
    void setOptedOut(boolean isOptedOut) {
        this.mOptedOut = isOptedOut;
        refreshDrawableState();
    }

    boolean getOptedOut() {
        return this.mOptedOut;
    }

    void setOnConfirmedTapListener(ConfirmedTapListener listener, Handler handler) {
        mConfirmedTapListener = listener;

        if (mGestureDetector == null) {
            this.mGestureDetector = new GestureDetector(getContext(), mGestureListener, handler);
        }
    }

    /** Listener for confirmed taps rather than normal on click listener. */
    interface ConfirmedTapListener {
        void onConfirmedTap();
    }
}
