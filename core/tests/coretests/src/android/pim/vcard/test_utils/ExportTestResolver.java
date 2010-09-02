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
package android.pim.vcard.test_utils;

import android.pim.vcard.VCardComposer;
import android.provider.ContactsContract.RawContacts;
import android.test.AndroidTestCase;
import android.test.mock.MockContentResolver;

public class ExportTestResolver extends MockContentResolver {
    private final ExportTestProvider mProvider;
    public ExportTestResolver(AndroidTestCase androidTestCase) {
        mProvider = new ExportTestProvider(androidTestCase);
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
