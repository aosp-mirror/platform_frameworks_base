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

package android.os.strictmode;

import android.content.Context;

/**
 * Incorrect usage of {@link Context}, such as obtaining a visual service from non-visual
 * {@link Context} instance.
 * @see Context#getSystemService(String)
 * @see Context#getDisplayNoVerify()
 * @hide
 */
public final class IncorrectContextUseViolation extends Violation {

    /** @hide */
    public IncorrectContextUseViolation(String message, Throwable originStack) {
        super(message);
        initCause(originStack);
    }
}
