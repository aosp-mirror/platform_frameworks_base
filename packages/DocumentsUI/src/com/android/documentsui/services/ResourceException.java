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

package com.android.documentsui.services;

import android.net.Uri;

public class ResourceException extends Exception {
    public ResourceException(String message, Exception e) {
        super(message, e);
    }

    public ResourceException(String message, Uri uri1, Exception e) {
        super(String.format(message, uri1.toString()), e);
    }

    public ResourceException(String message, Uri uri1, Uri uri2, Exception e) {
        super(String.format(message, uri1.toString(), uri2.toString()), e);
    }

    public ResourceException(String message) {
        super(message);
    }

    public ResourceException(String message, Uri uri1) {
        super(String.format(message, uri1.toString()));
    }

    public ResourceException(String message, Uri uri1, Uri uri2) {
        super(message.format(uri1.toString(), uri2.toString()));
    }
}
