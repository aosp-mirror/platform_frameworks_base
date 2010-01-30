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
package android.pim.vcard;

import android.pim.vcard.VCardConfig;
import android.pim.vcard.VCardEntry;
import android.pim.vcard.VCardEntryConstructor;
import android.pim.vcard.VCardEntryHandler;
import android.pim.vcard.VCardParser;
import android.pim.vcard.VCardParser_V21;
import android.pim.vcard.VCardParser_V30;
import android.pim.vcard.exception.VCardException;
import android.test.AndroidTestCase;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/* package */ class ContentValuesVerifier implements VCardEntryHandler {
    private AndroidTestCase mTestCase;
    private List<ContentValuesVerifierElem> mContentValuesVerifierElemList =
        new ArrayList<ContentValuesVerifierElem>();
    private int mIndex;

    public ContentValuesVerifierElem addElem(AndroidTestCase androidTestCase) {
        mTestCase = androidTestCase;
        ContentValuesVerifierElem importVerifier = new ContentValuesVerifierElem(androidTestCase);
        mContentValuesVerifierElemList.add(importVerifier);
        return importVerifier;
    }

    public void verify(int resId, int vCardType) throws IOException, VCardException {
        verify(mTestCase.getContext().getResources().openRawResource(resId), vCardType);
    }

    public void verify(int resId, int vCardType, final VCardParser vCardParser)
            throws IOException, VCardException {
        verify(mTestCase.getContext().getResources().openRawResource(resId),
                vCardType, vCardParser);
    }

    public void verify(InputStream is, int vCardType) throws IOException, VCardException {
        final VCardParser vCardParser;
        if (VCardConfig.isV30(vCardType)) {
            vCardParser = new VCardParser_V30(true);  // use StrictParsing
        } else {
            vCardParser = new VCardParser_V21();
        }
        verify(is, vCardType, vCardParser);
    }

    public void verify(InputStream is, int vCardType, final VCardParser vCardParser)
            throws IOException, VCardException {
        VCardEntryConstructor builder =
            new VCardEntryConstructor(null, null, false, vCardType, null);
        builder.addEntryHandler(this);
        try {
            vCardParser.parse(is, builder);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                }
            }
        }
    }

    public void onStart() {
        for (ContentValuesVerifierElem elem : mContentValuesVerifierElemList) {
            elem.onParsingStart();
        }
    }

    public void onEntryCreated(VCardEntry entry) {
        mTestCase.assertTrue(mIndex < mContentValuesVerifierElemList.size());
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
