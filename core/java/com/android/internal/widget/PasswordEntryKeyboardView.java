/*
 * Copyright (C) 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.internal.widget;

import android.content.Context;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.widget.PopupWindow;
import com.android.internal.R;

public class PasswordEntryKeyboardView extends KeyboardView {

    public static final int KEYCODE_OPTIONS = -100;
    static final int KEYCODE_SHIFT_LONGPRESS = -101;
    static final int KEYCODE_VOICE = -102;
    static final int KEYCODE_F1 = -103;
    static final int KEYCODE_NEXT_LANGUAGE = -104;

    private boolean mExtensionVisible;
    private PasswordEntryKeyboardView mExtension;
    private PopupWindow mExtensionPopup;
    private boolean mFirstEvent;

    public PasswordEntryKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PasswordEntryKeyboardView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public boolean onTouchEvent(MotionEvent me) {
        if (((PasswordEntryKeyboard) getKeyboard()).getExtension() == 0) {
            return super.onTouchEvent(me);
        }
        if (me.getY() < 0) {
            if (mExtensionVisible) {
                int action = me.getAction();
                if (mFirstEvent) action = MotionEvent.ACTION_DOWN;
                mFirstEvent = false;
                MotionEvent translated = MotionEvent.obtain(me.getEventTime(), me.getEventTime(),
                        action,
                        me.getX(), me.getY() + mExtension.getHeight(), me.getMetaState());
                boolean result = mExtension.onTouchEvent(translated);
                translated.recycle();
                if (me.getAction() == MotionEvent.ACTION_UP
                        || me.getAction() == MotionEvent.ACTION_CANCEL) {
                    closeExtension();
                }
                return result;
            } else {
                if (openExtension()) {
                    MotionEvent cancel = MotionEvent.obtain(me.getDownTime(), me.getEventTime(),
                            MotionEvent.ACTION_CANCEL, me.getX() - 100, me.getY() - 100, 0);
                    super.onTouchEvent(cancel);
                    cancel.recycle();
                    if (mExtension.getHeight() > 0) {
                        MotionEvent translated = MotionEvent.obtain(me.getEventTime(),
                                me.getEventTime(),
                                MotionEvent.ACTION_DOWN,
                                me.getX(), me.getY() + mExtension.getHeight(),
                                me.getMetaState());
                        mExtension.onTouchEvent(translated);
                        translated.recycle();
                    } else {
                        mFirstEvent = true;
                    }
                }
                return true;
            }
        } else if (mExtensionVisible) {
            closeExtension();
            // Send a down event into the main keyboard first
            MotionEvent down = MotionEvent.obtain(me.getEventTime(), me.getEventTime(),
                    MotionEvent.ACTION_DOWN, me.getX(), me.getY(), me.getMetaState());
            super.onTouchEvent(down);
            down.recycle();
            // Send the actual event
            return super.onTouchEvent(me);
        } else {
            return super.onTouchEvent(me);
        }
    }

    private boolean openExtension() {
        if (((PasswordEntryKeyboard) getKeyboard()).getExtension() == 0) return false;
        makePopupWindow();
        mExtensionVisible = true;
        return true;
    }

    private void makePopupWindow() {
        if (mExtensionPopup == null) {
            int[] windowLocation = new int[2];
            mExtensionPopup = new PopupWindow(getContext());
            mExtensionPopup.setBackgroundDrawable(null);
            LayoutInflater li = (LayoutInflater) getContext().getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);
            mExtension = (PasswordEntryKeyboardView) li.inflate(
                    R.layout.password_keyboard_input, null);
            mExtension.setOnKeyboardActionListener(getOnKeyboardActionListener());
            mExtension.setPopupParent(this);
            mExtension.setPopupOffset(0, -windowLocation[1]);
            Keyboard keyboard;
            mExtension.setKeyboard(keyboard = new PasswordEntryKeyboard(getContext(),
                    ((PasswordEntryKeyboard) getKeyboard()).getExtension()));
            mExtensionPopup.setContentView(mExtension);
            mExtensionPopup.setWidth(getWidth());
            mExtensionPopup.setHeight(keyboard.getHeight());
            getLocationInWindow(windowLocation);
            // TODO: Fix the "- 30".
            mExtension.setPopupOffset(0, -windowLocation[1] - 30);
            mExtensionPopup.showAtLocation(this, 0, 0, -keyboard.getHeight()
                    + windowLocation[1]);
        } else {
            mExtension.setVisibility(VISIBLE);
        }
    }

    @Override
    public void closing() {
        super.closing();
        if (mExtensionPopup != null && mExtensionPopup.isShowing()) {
            mExtensionPopup.dismiss();
            mExtensionPopup = null;
        }
    }

    private void closeExtension() {
        mExtension.setVisibility(INVISIBLE);
        mExtension.closing();
        mExtensionVisible = false;
    }
}
