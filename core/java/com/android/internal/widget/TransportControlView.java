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

package com.android.internal.widget;

import com.android.internal.R;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.LinearLayout;

/**
 * A special widget for displaying audio playback ("transport controls") in LockScreen.
 *
 */
public class TransportControlView extends LinearLayout implements LockScreenWidgetInterface,
        OnClickListener {
    private static final String TAG = "TransportControlView";
    static final int sViewIds[] = { R.id.control_prev, R.id.control_pauseplay, R.id.control_next };
    protected static final int AUDIO_FOCUS_CHANGED = 100;
    private LockScreenWidgetCallback mCallback;

    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what){
            case AUDIO_FOCUS_CHANGED:
                handleAudioFocusChange(msg.arg1);
            }
        }
    };

    AudioManager.OnAudioFocusChangeListener mAudioFocusChangeListener =
        new AudioManager.OnAudioFocusChangeListener() {
            public void onAudioFocusChange(final int focusChange) {
                mHandler.obtainMessage(AUDIO_FOCUS_CHANGED, focusChange, 0).sendToTarget();
            }
        };

    public TransportControlView(Context context) {
        this(context, null);
    }

    public TransportControlView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    protected void handleAudioFocusChange(int focusChange) {
        // TODO
    }

    public void setCallback(LockScreenWidgetCallback callback) {
        mCallback = callback;
    }

    @Override
    public void onFinishInflate() {
        for (int i = 0; i < sViewIds.length; i++) {
            View view = findViewById(sViewIds[i]);
            if (view != null) {
                view.setOnClickListener(this);
            }
        }
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.control_prev:
                // TODO
                break;

            case R.id.control_pauseplay:
                // TODO
                break;

            case R.id.control_next:
                // TODO
                break;
        }
        // Have any button click extend lockscreen's timeout.
        if (mCallback != null) {
            mCallback.userActivity(this);
        }
    }

}
