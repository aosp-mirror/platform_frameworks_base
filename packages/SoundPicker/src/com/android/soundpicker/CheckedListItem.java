/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.soundpicker;

import android.content.Context;
import android.widget.Checkable;
import android.widget.CheckedTextView;
import android.widget.RelativeLayout;
import android.util.AttributeSet;

/**
 * The {@link CheckedListItem} is a layout item that represents a ringtone, and is used in
 * {@link RingtonePickerActivity}. It contains the ringtone's name, and a work badge to right of the
 * name if the ringtone belongs to a work profile.
 */
public class CheckedListItem extends RelativeLayout implements Checkable {

    public CheckedListItem(Context context) {
        super(context);
    }

    public CheckedListItem(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CheckedListItem(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public CheckedListItem(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void setChecked(boolean checked) {
        getCheckedTextView().setChecked(checked);
    }

    @Override
    public boolean isChecked() {
        return getCheckedTextView().isChecked();
    }

    @Override
    public void toggle() {
        getCheckedTextView().toggle();
    }

    private CheckedTextView getCheckedTextView() {
        return (CheckedTextView) findViewById(R.id.checked_text_view);
    }

}
