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
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import com.android.internal.R;

/**
 * A basic, embed-able keyboard designed for password entry. Allows entry of all Latin-1 characters.
 *
 * It has two modes: alpha and numeric. In alpha mode, it allows all Latin-1 characters and enables
 * an additional keyboard with symbols.  In numeric mode, it shows a 12-key DTMF dialer-like
 * keypad with alpha characters hints.
 */
public class PasswordEntryKeyboard extends Keyboard {
    private static final int SHIFT_OFF = 0;
    private static final int SHIFT_ON = 1;
    private static final int SHIFT_LOCKED = 2;
    public static final int KEYCODE_SPACE = ' ';

    private Drawable mShiftIcon;
    private Drawable mShiftLockIcon;

    // These two arrays must be the same length
    private Drawable[] mOldShiftIcons = { null, null };
    private Key[] mShiftKeys = { null, null };

    private Key mEnterKey;
    private Key mF1Key;
    private Key mSpaceKey;
    private int mShiftState = SHIFT_OFF;

    static int sSpacebarVerticalCorrection;

    public PasswordEntryKeyboard(Context context, int xmlLayoutResId) {
        this(context, xmlLayoutResId, 0);
    }

    public PasswordEntryKeyboard(Context context, int xmlLayoutResId, int width, int height) {
        this(context, xmlLayoutResId, 0, width, height);
    }

    public PasswordEntryKeyboard(Context context, int xmlLayoutResId, int mode) {
        super(context, xmlLayoutResId, mode);
        init(context);
    }

    public PasswordEntryKeyboard(Context context, int xmlLayoutResId, int mode,
            int width, int height) {
        super(context, xmlLayoutResId, mode, width, height);
        init(context);
    }

    private void init(Context context) {
        final Resources res = context.getResources();
        mShiftIcon = context.getDrawable(R.drawable.sym_keyboard_shift);
        mShiftLockIcon = context.getDrawable(R.drawable.sym_keyboard_shift_locked);
        sSpacebarVerticalCorrection = res.getDimensionPixelOffset(
                R.dimen.password_keyboard_spacebar_vertical_correction);
    }

    public PasswordEntryKeyboard(Context context, int layoutTemplateResId,
            CharSequence characters, int columns, int horizontalPadding) {
        super(context, layoutTemplateResId, characters, columns, horizontalPadding);
    }

    @Override
    protected Key createKeyFromXml(Resources res, Row parent, int x, int y,
            XmlResourceParser parser) {
        LatinKey key = new LatinKey(res, parent, x, y, parser);
        final int code = key.codes[0];
        if (code >=0 && code != '\n' && (code < 32 || code > 127)) {
            // Log.w(TAG, "Key code for " + key.label + " is not latin-1");
            key.label = " ";
            key.setEnabled(false);
        }
        switch (key.codes[0]) {
            case 10:
                mEnterKey = key;
                break;
            case PasswordEntryKeyboardView.KEYCODE_F1:
                mF1Key = key;
                break;
            case 32:
                mSpaceKey = key;
                break;
        }
        return key;
    }

    /**
     * Allows enter key resources to be overridden
     * @param res resources to grab given items from
     * @param previewId preview drawable shown on enter key
     * @param iconId normal drawable shown on enter key
     * @param labelId string shown on enter key
     */
    void setEnterKeyResources(Resources res, int previewId, int iconId, int labelId) {
        if (mEnterKey != null) {
            // Reset some of the rarely used attributes.
            mEnterKey.popupCharacters = null;
            mEnterKey.popupResId = 0;
            mEnterKey.text = null;

            mEnterKey.iconPreview = res.getDrawable(previewId);
            mEnterKey.icon = res.getDrawable(iconId);
            mEnterKey.label = res.getText(labelId);

            // Set the initial size of the preview icon
            if (mEnterKey.iconPreview != null) {
                mEnterKey.iconPreview.setBounds(0, 0,
                        mEnterKey.iconPreview.getIntrinsicWidth(),
                        mEnterKey.iconPreview.getIntrinsicHeight());
            }
        }
    }

    /**
     * Allows shiftlock to be turned on.  See {@link #setShiftLocked(boolean)}
     *
     */
    void enableShiftLock() {
        int i = 0;
        for (int index : getShiftKeyIndices()) {
            if (index >= 0 && i < mShiftKeys.length) {
                mShiftKeys[i] = getKeys().get(index);
                if (mShiftKeys[i] instanceof LatinKey) {
                    ((LatinKey)mShiftKeys[i]).enableShiftLock();
                }
                mOldShiftIcons[i] = mShiftKeys[i].icon;
                i++;
            }
        }
    }

    /**
     * Turn on shift lock. This turns on the LED for this key, if it has one.
     * It should be followed by a call to {@link KeyboardView#invalidateKey(int)}
     * or {@link KeyboardView#invalidateAllKeys()}
     *
     * @param shiftLocked
     */
    void setShiftLocked(boolean shiftLocked) {
        for (Key shiftKey : mShiftKeys) {
            if (shiftKey != null) {
                shiftKey.on = shiftLocked;
                shiftKey.icon = mShiftLockIcon;
            }
        }
        mShiftState = shiftLocked ? SHIFT_LOCKED : SHIFT_ON;
    }

    /**
     * Turn on shift mode. Sets shift mode and turns on icon for shift key.
     * It should be followed by a call to {@link KeyboardView#invalidateKey(int)}
     * or {@link KeyboardView#invalidateAllKeys()}
     *
     * @param shiftLocked
     */
    @Override
    public boolean setShifted(boolean shiftState) {
        boolean shiftChanged = false;
        if (shiftState == false) {
            shiftChanged = mShiftState != SHIFT_OFF;
            mShiftState = SHIFT_OFF;
        } else if (mShiftState == SHIFT_OFF) {
            shiftChanged = mShiftState == SHIFT_OFF;
            mShiftState = SHIFT_ON;
        }
        for (int i = 0; i < mShiftKeys.length; i++) {
            if (mShiftKeys[i] != null) {
                if (shiftState == false) {
                    mShiftKeys[i].on = false;
                    mShiftKeys[i].icon = mOldShiftIcons[i];
                } else if (mShiftState == SHIFT_OFF) {
                    mShiftKeys[i].on = false;
                    mShiftKeys[i].icon = mShiftIcon;
                }
            } else {
                // return super.setShifted(shiftState);
            }
        }
        return shiftChanged;
    }

    /**
     * Whether or not keyboard is shifted.
     * @return true if keyboard state is shifted.
     */
    @Override
    public boolean isShifted() {
        if (mShiftKeys[0] != null) {
            return mShiftState != SHIFT_OFF;
        } else {
            return super.isShifted();
        }
    }

    static class LatinKey extends Keyboard.Key {
        private boolean mShiftLockEnabled;
        private boolean mEnabled = true;

        public LatinKey(Resources res, Keyboard.Row parent, int x, int y,
                XmlResourceParser parser) {
            super(res, parent, x, y, parser);
            if (popupCharacters != null && popupCharacters.length() == 0) {
                // If there is a keyboard with no keys specified in popupCharacters
                popupResId = 0;
            }
        }

        void setEnabled(boolean enabled) {
            mEnabled = enabled;
        }

        void enableShiftLock() {
            mShiftLockEnabled = true;
        }

        @Override
        public void onReleased(boolean inside) {
            if (!mShiftLockEnabled) {
                super.onReleased(inside);
            } else {
                pressed = !pressed;
            }
        }

        /**
         * Overriding this method so that we can reduce the target area for certain keys.
         */
        @Override
        public boolean isInside(int x, int y) {
            if (!mEnabled) {
                return false;
            }
            final int code = codes[0];
            if (code == KEYCODE_SHIFT || code == KEYCODE_DELETE) {
                y -= height / 10;
                if (code == KEYCODE_SHIFT) x += width / 6;
                if (code == KEYCODE_DELETE) x -= width / 6;
            } else if (code == KEYCODE_SPACE) {
                y += PasswordEntryKeyboard.sSpacebarVerticalCorrection;
            }
            return super.isInside(x, y);
        }
    }
}
