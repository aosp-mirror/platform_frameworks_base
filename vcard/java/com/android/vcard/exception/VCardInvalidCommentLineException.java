/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.vcard.exception;

/**
 * Thrown when the vCard has some line starting with '#'. In the specification,
 * both vCard 2.1 and vCard 3.0 does not allow such line, but some actual exporter emit
 * such lines.
 */
public class VCardInvalidCommentLineException extends VCardInvalidLineException {
    public VCardInvalidCommentLineException() {
        super();
    }

    public VCardInvalidCommentLineException(final String message) {
        super(message);
    }
}
