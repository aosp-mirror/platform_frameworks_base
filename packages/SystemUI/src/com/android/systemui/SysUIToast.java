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
package com.android.systemui;

import android.annotation.StringRes;
import android.content.Context;
import android.view.WindowManager;
import android.widget.Toast;
import static android.widget.Toast.Duration;

public class SysUIToast {

    public static Toast makeText(Context context, @StringRes int resId, @Duration int duration) {
        return makeText(context, context.getString(resId), duration);
    }

    public static Toast makeText(Context context, CharSequence text, @Duration int duration) {
        Toast toast = Toast.makeText(context, text, duration);
        toast.getWindowParams().privateFlags |=
                WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        return toast;
    }

}
