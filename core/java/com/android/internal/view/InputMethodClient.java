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

package com.android.internal.view;

import android.annotation.IntDef;

import java.lang.annotation.Retention;

import static java.lang.annotation.RetentionPolicy.SOURCE;

public final class InputMethodClient {
    public static final int UNBIND_REASON_UNSPECIFIED = 0;
    public static final int UNBIND_REASON_SWITCH_CLIENT = 1;
    public static final int UNBIND_REASON_SWITCH_IME = 2;
    public static final int UNBIND_REASON_DISCONNECT_IME = 3;
    public static final int UNBIND_REASON_NO_IME = 4;
    public static final int UNBIND_REASON_SWITCH_IME_FAILED = 5;
    public static final int UNBIND_REASON_SWITCH_USER = 6;

    @Retention(SOURCE)
    @IntDef({UNBIND_REASON_UNSPECIFIED, UNBIND_REASON_SWITCH_CLIENT, UNBIND_REASON_SWITCH_IME,
            UNBIND_REASON_DISCONNECT_IME, UNBIND_REASON_NO_IME, UNBIND_REASON_SWITCH_IME_FAILED,
            UNBIND_REASON_SWITCH_USER})
    public @interface UnbindReason {}
}
