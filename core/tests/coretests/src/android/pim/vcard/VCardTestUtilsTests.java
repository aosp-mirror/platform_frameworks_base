/*
 * Copyright (C) 2010 The Android Open Source Project
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

import com.android.frameworks.coretests.R;

import android.pim.vcard.test_utils.VCardVerifier;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.test.AndroidTestCase;

import junit.framework.AssertionFailedError;
import junit.framework.TestCase;

import java.util.Arrays;

/**
 * Tests confirming utilities for vCard tests work fine.
 *
 * Now that the foundation classes for vCard test cases became too complicated to
 * rely on without testing itself.
 */
public class VCardTestUtilsTests extends AndroidTestCase {
    public void testShouldFailAtPropertyNodeVerification() {
        boolean failureDetected = false;
        try {
            final VCardVerifier verifier = new VCardVerifier(this);
            verifier.initForImportTest(VCardConfig.VCARD_TYPE_V21_GENERIC, R.raw.v21_backslash);
            verifier.addPropertyNodesVerifierElem()
                    .addExpectedNodeWithOrder("N", ";A;B\\;C\\;;D;:E;\\\\;--",  // wrong
                            Arrays.asList("", "A;B\\", "C\\;", "D", ":E", "\\\\", ""))
                    .addExpectedNodeWithOrder("FN", "A;B\\C\\;D:E\\\\");
            verifier.verify();
        } catch (AssertionFailedError e) {
            failureDetected = true;
        }
        if (!failureDetected) {
            TestCase.fail("Test case that should fail actually succeeded.");
        }
    }

    public void testShouldFailAtContentValueVerification() {
        boolean failureDetected = false;
        try {
            final VCardVerifier verifier = new VCardVerifier(this);
            verifier.initForImportTest(VCardConfig.VCARD_TYPE_V21_GENERIC, R.raw.v21_backslash);
            verifier.addContentValuesVerifierElem()
                    .addExpected(StructuredName.CONTENT_ITEM_TYPE)
                            .put(StructuredName.GIVEN_NAME, "A;B\\")
                            .put(StructuredName.MIDDLE_NAME, "C\\;")
                            .put(StructuredName.PREFIX, "D")
                            .put(StructuredName.SUFFIX, ":E");
            // DISPLAY_NAME is missing.
            verifier.verify();
        } catch (AssertionFailedError e) {
            failureDetected = true;
        }
        if (!failureDetected) {
            TestCase.fail("Test case that should fail actually succeeded.");
        }
    }

    public void testShouldFailAtLineVerification() {
        boolean failureDetected = false;
        try {
            final VCardVerifier verifier = new VCardVerifier(this);
            verifier.initForExportTest(VCardConfig.VCARD_TYPE_V30_GENERIC);
            verifier.addInputEntry().addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                    .put(StructuredName.FAMILY_NAME, "\\")
                    .put(StructuredName.GIVEN_NAME, ";")
                    .put(StructuredName.MIDDLE_NAME, ",")
                    .put(StructuredName.PREFIX, "\n")
                    .put(StructuredName.DISPLAY_NAME, "[<{Unescaped:Asciis}>]");
            verifier.addLineVerifierElem()
                    .addExpected("TEL:1")  // wrong
                    .addExpected("FN:[<{Unescaped:Asciis}>]");
            verifier.verify();
        } catch (AssertionFailedError e) {
            failureDetected = true;
        }
        if (!failureDetected) {
            TestCase.fail("Test case that should fail actually succeeded.");
        }
    }

}
