/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;

import android.annotation.UnsupportedAppUsage;

/**
 * {@hide}
 */
public class EncodeException extends Exception {

    private int mError = ERROR_UNENCODABLE;

    public static final int ERROR_UNENCODABLE = 0;
    public static final int ERROR_EXCEED_SIZE = 1;

    public EncodeException() {
        super();
    }

    @UnsupportedAppUsage
    public EncodeException(String s) {
        super(s);
    }

    public EncodeException(String s, int error) {
        super(s);
        mError = error;
    }

    @UnsupportedAppUsage
    public EncodeException(char c) {
        super("Unencodable char: '" + c + "'");
    }

    public int getError() {
        return mError;
    }
}

