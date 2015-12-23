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

package com.android.documentsui.dirlist;

import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.test.mock.MockContentProvider;

import com.android.documentsui.model.DocumentInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * A very simple test double for ContentProvider. Useful in this package only.
 */
class TestContentProvider extends MockContentProvider {
    List<Uri> mDeleted = new ArrayList<>();

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        // Intercept and log delete method calls.
        if (DocumentsContract.METHOD_DELETE_DOCUMENT.equals(method)) {
            final Uri documentUri = extras.getParcelable(DocumentsContract.EXTRA_URI);
            mDeleted.add(documentUri);
            return new Bundle();
        } else {
            return super.call(method, arg, extras);
        }
    }

    public void assertWasDeleted(DocumentInfo doc) {
        ModelTest.assertTrue(mDeleted.contains(doc.derivedUri));
    }
}