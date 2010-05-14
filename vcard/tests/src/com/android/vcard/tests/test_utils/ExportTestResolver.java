/*
 * Copyright (C) 2009 The Android Open Source Project
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
package com.android.vcard.tests.test_utils;

import android.provider.ContactsContract.RawContacts;
import android.test.mock.MockContentResolver;

import com.android.vcard.VCardComposer;

import junit.framework.TestCase;

/* package */ class ExportTestResolver extends MockContentResolver {
    private final ExportTestProvider mProvider;
    public ExportTestResolver(TestCase testCase) {
        mProvider = new ExportTestProvider(testCase);
        addProvider(VCardComposer.VCARD_TEST_AUTHORITY, mProvider);
        addProvider(RawContacts.CONTENT_URI.getAuthority(), mProvider);
    }

    public ContactEntry addInputContactEntry() {
        return mProvider.buildInputEntry();
    }

    public ExportTestProvider getProvider() {
        return mProvider;
    }
}
