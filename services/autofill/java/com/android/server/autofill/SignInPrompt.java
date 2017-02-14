/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.autofill;

import android.content.Context;
import android.view.View;
import android.widget.Button;

/**
 * A view displaying the sign-in prompt for an auto-fill service.
 */
final class SignInPrompt extends Button {

    SignInPrompt(Context context, CharSequence serviceName, View.OnClickListener listener) {
        super(context);
        // TODO(b/33197203): use strings.xml
        final String text = "Sign in to " + serviceName + " to autofill";

        // TODO(b/33197203): polish UI / use better altenative than a button...

        setText(text);
        setOnClickListener(listener);
    }
}
