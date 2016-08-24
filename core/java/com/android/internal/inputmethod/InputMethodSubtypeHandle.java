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

package com.android.internal.inputmethod;

import android.annotation.Nullable;
import android.text.TextUtils;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import java.util.Objects;

public class InputMethodSubtypeHandle {
    private final String mInputMethodId;
    private final int mSubtypeId;

    public InputMethodSubtypeHandle(InputMethodInfo info, @Nullable InputMethodSubtype subtype) {
        mInputMethodId = info.getId();
        if (subtype != null) {
            mSubtypeId = subtype.hashCode();
        } else {
            mSubtypeId = InputMethodUtils.NOT_A_SUBTYPE_ID;
        }
    }

    public InputMethodSubtypeHandle(String inputMethodId, int subtypeId) {
        mInputMethodId = inputMethodId;
        mSubtypeId = subtypeId;
    }

    public String getInputMethodId() {
        return mInputMethodId;
    }

    public int getSubtypeId() {
        return mSubtypeId;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || !(o instanceof InputMethodSubtypeHandle)) {
            return false;
        }
        InputMethodSubtypeHandle other = (InputMethodSubtypeHandle) o;
        return TextUtils.equals(mInputMethodId, other.getInputMethodId())
                && mSubtypeId == other.getSubtypeId();
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mInputMethodId) * 31 + mSubtypeId;
    }

    @Override
    public String toString() {
        return "InputMethodSubtypeHandle{mInputMethodId=" + mInputMethodId
            + ", mSubtypeId=" + mSubtypeId + "}";
    }
}
