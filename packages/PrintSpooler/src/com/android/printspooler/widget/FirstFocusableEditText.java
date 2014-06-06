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
 * limitations under the License.
 */

package com.android.printspooler.widget;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.EditText;

/**
 * An instance of this class class is intended to be the first focusable
 * in a layout to which the system automatically gives focus. It performs
 * some voodoo to avoid the first tap on it to start an edit mode, rather
 * to bring up the IME, i.e. to get the behavior as if the view was not
 * focused.
 */
public final class FirstFocusableEditText extends EditText {
    private boolean mClickedBeforeFocus;
    private CharSequence mError;

    public FirstFocusableEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (isFocused() && !mClickedBeforeFocus) {
            clearFocus();
            requestFocus();
        }
        mClickedBeforeFocus = true;
        return true;
    }

    @Override
    public CharSequence getError() {
        return mError;
    }

    @Override
    public void setError(CharSequence error, Drawable icon) {
        setCompoundDrawables(null, null, icon, null);
        mError = error;
    }

    protected void onFocusChanged(boolean gainFocus, int direction,
            Rect previouslyFocusedRect) {
        if (!gainFocus) {
            mClickedBeforeFocus = false;
        }
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
    }
}