/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import com.android.systemui.R;

import android.annotation.NonNull;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Host for the remote input.
 */
public class RemoteInputView extends FrameLayout implements View.OnClickListener {

    private static final String TAG = "RemoteInput";

    private RemoteEditText mEditText;
    private ProgressBar mProgressBar;
    private PendingIntent mPendingIntent;
    private RemoteInput mRemoteInput;
    private Notification.Action mAction;

    public RemoteInputView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mProgressBar = (ProgressBar) findViewById(R.id.remote_input_progress);

        mEditText = (RemoteEditText) getChildAt(0);
        mEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {

                // Check if this was the result of hitting the enter key
                final boolean isSoftImeEvent = event == null
                        && (actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_NEXT
                        || actionId == EditorInfo.IME_ACTION_SEND);
                final boolean isKeyboardEnterKey = event != null
                        && KeyEvent.isConfirmKey(event.getKeyCode())
                        && event.getAction() == KeyEvent.ACTION_DOWN;

                if (isSoftImeEvent || isKeyboardEnterKey) {
                    sendRemoteInput();
                    return true;
                }
                return false;
            }
        });
        mEditText.setOnClickListener(this);
        mEditText.setInnerFocusable(false);
    }

    private void sendRemoteInput() {
        Bundle results = new Bundle();
        results.putString(mRemoteInput.getResultKey(), mEditText.getText().toString());
        Intent fillInIntent = new Intent();
        RemoteInput.addResultsToIntent(mAction.getRemoteInputs(), fillInIntent,
                results);

        mEditText.setEnabled(false);
        mProgressBar.setVisibility(VISIBLE);

        try {
            mPendingIntent.send(mContext, 0, fillInIntent);
        } catch (PendingIntent.CanceledException e) {
            Log.i(TAG, "Unable to send remote input result", e);
        }
    }

    public static RemoteInputView inflate(Context context, ViewGroup root,
            Notification.Action action, RemoteInput remoteInput) {
        RemoteInputView v = (RemoteInputView)
                LayoutInflater.from(context).inflate(R.layout.remote_input, root, false);

        v.mEditText.setHint(action.title);
        v.mPendingIntent = action.actionIntent;
        v.mRemoteInput = remoteInput;
        v.mAction = action;

        return v;
    }

    @Override
    public void onClick(View v) {
        if (v == mEditText) {
            if (!mEditText.isFocusable()) {
                mEditText.setInnerFocusable(true);
                InputMethodManager imm = InputMethodManager.getInstance();
                if (imm != null) {
                    imm.viewClicked(mEditText);
                    imm.showSoftInput(mEditText, 0);
                }
            }
        }
    }

    /**
     * An EditText that changes appearance based on whether it's focusable and becomes
     * un-focusable whenever the user navigates away from it or it becomes invisible.
     */
    public static class RemoteEditText extends EditText {

        private final Drawable mBackground;

        public RemoteEditText(Context context, AttributeSet attrs) {
            super(context, attrs);
            mBackground = getBackground();
        }

        private void defocusIfNeeded() {
            if (isFocusable() && isEnabled()) {
                setInnerFocusable(false);
            }
        }

        @Override
        protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);

            if (!isShown()) {
                defocusIfNeeded();
            }
        }

        @Override
        protected void onFocusLost() {
            super.onFocusLost();
            defocusIfNeeded();
        }

        @Override
        public boolean onKeyPreIme(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                defocusIfNeeded();
            }
            return super.onKeyPreIme(keyCode, event);
        }


        void setInnerFocusable(boolean focusable) {
            setFocusableInTouchMode(focusable);
            setFocusable(focusable);
            setCursorVisible(focusable);

            if (focusable) {
                requestFocus();
                setBackground(mBackground);
            } else {
                setBackground(null);
            }

        }
    }
}
