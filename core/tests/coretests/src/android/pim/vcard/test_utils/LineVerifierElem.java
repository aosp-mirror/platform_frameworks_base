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

import android.pim.vcard.VCardConfig;
import android.test.AndroidTestCase;
import android.text.TextUtils;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;

public class LineVerifierElem {
    private final List<String> mExpectedLineList = new ArrayList<String>();
    private final int mVCardType;

    public LineVerifierElem(AndroidTestCase androidTestCase, int vcardType) {
        mVCardType = vcardType;
    }

    public LineVerifierElem addExpected(final String line) {
        if (!TextUtils.isEmpty(line)) {
            mExpectedLineList.add(line);
        }
        return this;
    }

    public void verify(final String vcard) {
        final String[] lineArray = vcard.split("\\r?\\n");
        final int length = lineArray.length;
        boolean beginExists = false;
        boolean endExists = false;
        boolean versionExists = false;

        for (int i = 0; i < length; i++) {
            final String line = lineArray[i];
            if (TextUtils.isEmpty(line)) {
                continue;
            }

            if ("BEGIN:VCARD".equalsIgnoreCase(line)) {
                if (beginExists) {
                    TestCase.fail("Multiple \"BEGIN:VCARD\" line found");
                } else {
                    beginExists = true;
                    continue;
                }
            } else if ("END:VCARD".equalsIgnoreCase(line)) {
                if (endExists) {
                    TestCase.fail("Multiple \"END:VCARD\" line found");
                } else {
                    endExists = true;
                    continue;
                }
            } else if ((VCardConfig.isVersion21(mVCardType) ? "VERSION:2.1" :
                (VCardConfig.isVersion30(mVCardType) ? "VERSION:3.0" :
                    "VERSION:4.0")).equalsIgnoreCase(line)) {
                if (versionExists) {
                    TestCase.fail("Multiple VERSION line + found");
                } else {
                    versionExists = true;
                    continue;
                }
            }

            if (!beginExists) {
                TestCase.fail("Property other than BEGIN came before BEGIN property: " + line);
            } else if (endExists) {
                TestCase.fail("Property other than END came after END property: " + line);
            }

            final int index = mExpectedLineList.indexOf(line);
            if (index >= 0) {
                mExpectedLineList.remove(index);
            } else {
                TestCase.fail("Unexpected line: " + line);
            }
        }

        if (!mExpectedLineList.isEmpty()) {
            StringBuffer buffer = new StringBuffer();
            for (String expectedLine : mExpectedLineList) {
                buffer.append(expectedLine);
                buffer.append("\n");
            }

            TestCase.fail("Expected line(s) not found:" + buffer.toString());
        }
    }
}
