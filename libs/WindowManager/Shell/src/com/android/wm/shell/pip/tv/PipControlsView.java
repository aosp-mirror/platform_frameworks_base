/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.pip.tv;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.widget.LinearLayout;

import com.android.wm.shell.R;


/**
 * A view containing PIP controls including fullscreen, close, and media controls.
 */
public class PipControlsView extends LinearLayout {

    public PipControlsView(Context context) {
        this(context, null);
    }

    public PipControlsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PipControlsView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public PipControlsView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        LayoutInflater layoutInflater = (LayoutInflater) getContext().getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        layoutInflater.inflate(R.layout.tv_pip_controls, this);
        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    }

    PipControlButtonView getFullscreenButton() {
        return findViewById(R.id.full_button);
    }

    PipControlButtonView getCloseButton() {
        return findViewById(R.id.close_button);
    }
}
