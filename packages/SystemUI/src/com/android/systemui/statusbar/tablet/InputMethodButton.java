/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.tablet;

import android.content.Context;
import android.util.Slog;
import android.view.View;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.view.inputmethod.InputMethodManager;

import com.android.server.InputMethodManagerService;

public class InputMethodButton extends ImageView {

    // other services we wish to talk to
    InputMethodManager mImm;

    public InputMethodButton(Context context, AttributeSet attrs) {
        super(context, attrs);

        // IME hookup
        mImm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        
        // TODO: read the current icon & visibility state directly from the service

        // TODO: register for notifications about changes to visibility & subtype from service

        setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mImm.showInputMethodSubtypePicker();
            }
        });
    }
}

