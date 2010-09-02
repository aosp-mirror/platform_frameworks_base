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

import android.pim.vcard.VCardEntry;
import android.pim.vcard.VCardEntryHandler;
import android.test.AndroidTestCase;

import java.util.ArrayList;
import java.util.List;

public class ContentValuesVerifier implements VCardEntryHandler {
    private List<ContentValuesVerifierElem> mContentValuesVerifierElemList =
        new ArrayList<ContentValuesVerifierElem>();
    private int mIndex;

    public ContentValuesVerifierElem addElem(AndroidTestCase androidTestCase) {
        ContentValuesVerifierElem elem = new ContentValuesVerifierElem(androidTestCase);
        mContentValuesVerifierElemList.add(elem);
        return elem;
    }

    public void onStart() {
        for (ContentValuesVerifierElem elem : mContentValuesVerifierElemList) {
            elem.onParsingStart();
        }
    }

    public void onEntryCreated(VCardEntry entry) {
        AndroidTestCase.assertTrue(mIndex < mContentValuesVerifierElemList.size());
        mContentValuesVerifierElemList.get(mIndex).onEntryCreated(entry);
        mIndex++;
    }

    public void onEnd() {
        for (ContentValuesVerifierElem elem : mContentValuesVerifierElemList) {
            elem.onParsingEnd();
            elem.verifyResolver();
        }
    }
}
