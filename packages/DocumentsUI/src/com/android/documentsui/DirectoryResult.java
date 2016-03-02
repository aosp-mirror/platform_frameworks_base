/*
 * Copyright (C) 2013 The Android Open Source Project
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

import static com.android.documentsui.State.MODE_UNKNOWN;
import static com.android.documentsui.State.SORT_ORDER_UNKNOWN;

import android.content.ContentProviderClient;
import android.database.Cursor;

import com.android.documentsui.model.DocumentInfo;

import libcore.io.IoUtils;

public class DirectoryResult implements AutoCloseable {
    ContentProviderClient client;
    public Cursor cursor;
    public Exception exception;
    public DocumentInfo doc;

    public int sortOrder = SORT_ORDER_UNKNOWN;

    @Override
    public void close() {
        IoUtils.closeQuietly(cursor);
        ContentProviderClient.releaseQuietly(client);
        cursor = null;
        client = null;
    }
}
