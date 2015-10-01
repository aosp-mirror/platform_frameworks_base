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
 * limitations under the License.
 */

package com.android.documentsui;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.app.Activity;
import android.content.Context;
import android.support.design.widget.Snackbar;
import android.view.View;

/** @hide */
public final class Shared {
    public static final boolean DEBUG = true;
    public static final String TAG = "Documents";
    public static final String EXTRA_STACK = "com.android.documentsui.STACK";

    /**
     * Generates a formatted quantity string.
     */
    public static final String getQuantityString(Context context, int resourceId, int quantity) {
        return context.getResources().getQuantityString(resourceId, quantity, quantity);
    }

    public static final Snackbar makeSnackbar(Activity activity, int messageId, int duration) {
        return makeSnackbar(activity, activity.getResources().getText(messageId), duration);
    }

    public static final Snackbar makeSnackbar(Activity activity, CharSequence message, int duration)
    {
        final View view = checkNotNull(activity.findViewById(R.id.coordinator_layout));
        return Snackbar.make(view, message, duration);
    }
}
