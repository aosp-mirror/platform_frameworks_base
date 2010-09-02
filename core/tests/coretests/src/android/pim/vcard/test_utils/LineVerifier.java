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

import android.content.Context;
import android.pim.vcard.VCardComposer;
import android.test.AndroidTestCase;

import junit.framework.TestCase;

import java.util.ArrayList;

public class LineVerifier implements VCardComposer.OneEntryHandler {
    private final AndroidTestCase mAndroidTestCase;
    private final ArrayList<LineVerifierElem> mLineVerifierElemList;
    private int mVCardType;
    private int index;

    public LineVerifier(AndroidTestCase androidTestCase, int vcardType) {
        mAndroidTestCase = androidTestCase;
        mLineVerifierElemList = new ArrayList<LineVerifierElem>();
        mVCardType = vcardType;
    }

    public LineVerifierElem addLineVerifierElem() {
        LineVerifierElem lineVerifier = new LineVerifierElem(mAndroidTestCase, mVCardType);
        mLineVerifierElemList.add(lineVerifier);
        return lineVerifier;
    }

    public void verify(String vcard) {
        if (index >= mLineVerifierElemList.size()) {
            TestCase.fail("Insufficient number of LineVerifier (" + index + ")");
        }

        final LineVerifierElem lineVerifier = mLineVerifierElemList.get(index);
        lineVerifier.verify(vcard);

        index++;
    }

    public boolean onEntryCreated(String vcard) {
        verify(vcard);
        return true;
    }

    public boolean onInit(Context context) {
        return true;
    }

    public void onTerminate() {
    }
}
