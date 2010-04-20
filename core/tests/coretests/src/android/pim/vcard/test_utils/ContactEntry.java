/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.pim.vcard.test_utils;

import android.content.ContentValues;
import android.provider.ContactsContract.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * The class representing one contact, which should contain multiple ContentValues like
 * StructuredName, Email, etc.
 * </p>
 */
public final class ContactEntry {
    private final List<ContentValues> mContentValuesList = new ArrayList<ContentValues>();

    public ContentValuesBuilder addContentValues(String mimeType) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(Data.MIMETYPE, mimeType);
        mContentValuesList.add(contentValues);
        return new ContentValuesBuilder(contentValues);
    }

    public List<ContentValues> getList() {
        return mContentValuesList;
    }
}