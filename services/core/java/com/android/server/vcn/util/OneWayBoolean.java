/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.vcn.util;

/**
 * OneWayBoolean is an abstraction for a boolean that MUST only ever be flipped from false to true
 *
 * <p>This class allows the providing of a guarantee that a flag will never be flipped back after
 * being set.
 *
 * @hide
 */
public class OneWayBoolean {
    private boolean mValue = false;

    /** Get boolean value. */
    public boolean getValue() {
        return mValue;
    }

    /** Sets the value to true. */
    public void setTrue() {
        mValue = true;
    }
}
