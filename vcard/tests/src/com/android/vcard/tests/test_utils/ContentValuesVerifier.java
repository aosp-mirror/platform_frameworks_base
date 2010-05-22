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
package com.android.vcard.tests.test_utils;

import android.test.AndroidTestCase;

import com.android.vcard.VCardConfig;
import com.android.vcard.VCardEntry;
import com.android.vcard.VCardEntryConstructor;
import com.android.vcard.VCardEntryHandler;
import com.android.vcard.VCardParser;
import com.android.vcard.VCardParser_V21;
import com.android.vcard.VCardParser_V30;
import com.android.vcard.exception.VCardException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class ContentValuesVerifier implements VCardEntryHandler {
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
            vCardParser = new VCardParser_V30();
        } else {
            vCardParser = new VCardParser_V21();
        }
        verify(is, vCardType, vCardParser);
    }

    public void verify(InputStream is, int vCardType, final VCardParser vCardParser)
            throws IOException, VCardException {
        VCardEntryConstructor builder = new VCardEntryConstructor(vCardType, null);
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
