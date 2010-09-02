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
package android.pim.vcard.test_utils;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentValues;
import android.provider.ContactsContract.RawContacts;
import android.test.AndroidTestCase;
import android.test.mock.MockContentResolver;

import java.util.ArrayList;

public class ImportTestResolver extends MockContentResolver {
    private final ImportTestProvider mProvider;

    public ImportTestResolver(AndroidTestCase androidTestCase) {
        mProvider = new ImportTestProvider(androidTestCase);
    }

    @Override
    public ContentProviderResult[] applyBatch(String authority,
            ArrayList<ContentProviderOperation> operations) {
        equalsString(authority, RawContacts.CONTENT_URI.toString());
        return mProvider.applyBatch(operations);
    }

    public void addExpectedContentValues(ContentValues expectedContentValues) {
        mProvider.addExpectedContentValues(expectedContentValues);
    }

    public void verify() {
        mProvider.verify();
    }

    private static boolean equalsString(String a, String b) {
        if (a == null || a.length() == 0) {
            return b == null || b.length() == 0;
        } else {
            return a.equals(b);
        }
    }
}
