/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.keyguard;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;

/**
 * A Pin based Keyguard input view
 */
public abstract class KeyguardPinBasedInputView extends KeyguardAbsKeyInputView
        implements View.OnKeyListener {

    private final android.database.ContentObserver mSpeakPasswordObserver
            = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            // Ensure that it's not called too early
            if (mButton0 != null) {
                mButton0.updateContentDescription();
                mButton1.updateContentDescription();
                mButton2.updateContentDescription();
                mButton3.updateContentDescription();
                mButton4.updateContentDescription();
                mButton5.updateContentDescription();
                mButton6.updateContentDescription();
                mButton7.updateContentDescription();
                mButton8.updateContentDescription();
                mButton9.updateContentDescription();
            }
        }
    };
    protected PasswordTextView mPasswordEntry;
    private View mOkButton;
    private View mDeleteButton;
    private NumPadKey mButton0;
    private NumPadKey mButton1;
    private NumPadKey mButton2;
    private NumPadKey mButton3;
    private NumPadKey mButton4;
    private NumPadKey mButton5;
    private NumPadKey mButton6;
    private NumPadKey mButton7;
    private NumPadKey mButton8;
    private NumPadKey mButton9;

    public KeyguardPinBasedInputView(Context context) {
        this(context, null);
    }

    public KeyguardPinBasedInputView(Context context, AttributeSet attrs) {
        super(context, attrs);
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_SPEAK_PASSWORD), true,
                mSpeakPasswordObserver, UserHandle.USER_ALL);
    }

    @Override
    public void reset() {
        mPasswordEntry.requestFocus();
        super.reset();
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        // send focus to the password field
        return mPasswordEntry.requestFocus(direction, previouslyFocusedRect);
    }

    protected void resetState() {
        mPasswordEntry.setEnabled(true);
    }

    @Override
    protected void setPasswordEntryEnabled(boolean enabled) {
        mPasswordEntry.setEnabled(enabled);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (KeyEvent.isConfirmKey(keyCode)) {
            performClick(mOkButton);
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DEL) {
            performClick(mDeleteButton);
            return true;
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            int number = keyCode - KeyEvent.KEYCODE_0 ;
            performNumberClick(number);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void performClick(View view) {
        view.performClick();
    }

    private void performNumberClick(int number) {
        switch (number) {
            case 0:
                performClick(mButton0);
                break;
            case 1:
                performClick(mButton1);
                break;
            case 2:
                performClick(mButton2);
                break;
            case 3:
                performClick(mButton3);
                break;
            case 4:
                performClick(mButton4);
                break;
            case 5:
                performClick(mButton5);
                break;
            case 6:
                performClick(mButton6);
                break;
            case 7:
                performClick(mButton7);
                break;
            case 8:
                performClick(mButton8);
                break;
            case 9:
                performClick(mButton9);
                break;
        }
    }

    @Override
    protected void resetPasswordText(boolean animate) {
        mPasswordEntry.reset(animate);
    }

    @Override
    protected String getPasswordText() {
        return mPasswordEntry.getText();
    }

    @Override
    protected void onFinishInflate() {
        mPasswordEntry = (PasswordTextView) findViewById(getPasswordTextViewId());
        mPasswordEntry.setOnKeyListener(this);

        // Set selected property on so the view can send accessibility events.
        mPasswordEntry.setSelected(true);

        // Poke the wakelock any time the text is selected or modified
        mPasswordEntry.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mCallback.userActivity();
            }
        });

        mOkButton = findViewById(R.id.key_enter);
        if (mOkButton != null) {
            mOkButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    doHapticKeyClick();
                    if (mPasswordEntry.isEnabled()) {
                        verifyPasswordAndUnlock();
                    }
                }
            });
            mOkButton.setOnHoverListener(new LiftToActivateListener(getContext()));
        }

        mDeleteButton = findViewById(R.id.delete_button);
        mDeleteButton.setVisibility(View.VISIBLE);
        mDeleteButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                // check for time-based lockouts
                if (mPasswordEntry.isEnabled()) {
                    mPasswordEntry.deleteLastChar();
                }
                doHapticKeyClick();
            }
        });
        mDeleteButton.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                // check for time-based lockouts
                if (mPasswordEntry.isEnabled()) {
                    resetPasswordText(true /* animate */);
                }
                doHapticKeyClick();
                return true;
            }
        });

        mButton0 = (NumPadKey) findViewById(R.id.key0);
        mButton1 = (NumPadKey) findViewById(R.id.key1);
        mButton2 = (NumPadKey) findViewById(R.id.key2);
        mButton3 = (NumPadKey) findViewById(R.id.key3);
        mButton4 = (NumPadKey) findViewById(R.id.key4);
        mButton5 = (NumPadKey) findViewById(R.id.key5);
        mButton6 = (NumPadKey) findViewById(R.id.key6);
        mButton7 = (NumPadKey) findViewById(R.id.key7);
        mButton8 = (NumPadKey) findViewById(R.id.key8);
        mButton9 = (NumPadKey) findViewById(R.id.key9);

        mPasswordEntry.requestFocus();
        super.onFinishInflate();
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            onKeyDown(keyCode, event);
            return true;
        }
        return false;
    }
}
