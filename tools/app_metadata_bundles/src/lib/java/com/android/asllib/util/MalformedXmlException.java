/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.asllib.util;

public class MalformedXmlException extends Exception {
    /** Constructs an {@code MalformedXmlException} with no detail message. */
    public MalformedXmlException() {
        super();
    }

    /**
     * Constructs an {@code MalformedXmlException} with the specified detail message.
     *
     * @param s the detail message.
     */
    public MalformedXmlException(String s) {
        super(s);
    }
}
