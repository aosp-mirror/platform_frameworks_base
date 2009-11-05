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

package com.android.unit_tests.vcard;

import android.content.ContentValues;
import android.pim.vcard.VCardConfig;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;

import com.android.unit_tests.vcard.PropertyNodesVerifierElem.TypeSet;

import java.util.Arrays;

public class VCardJapanizationTests extends VCardTestsBase {
    private void testNameUtf8Common(int vcardType) {
        ExportTestResolver resolver = new ExportTestResolver();
        resolver.buildContactEntry().buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "\u3075\u308B\u3069")
                .put(StructuredName.GIVEN_NAME, "\u3091\u308A\u304B")
                .put(StructuredName.MIDDLE_NAME, "B")
                .put(StructuredName.PREFIX, "Dr.")
                .put(StructuredName.SUFFIX, "Ph.D");

        VCardVerifier verifier = new VCardVerifier(resolver, vcardType);
        ContentValues contentValues =
            (VCardConfig.isV30(vcardType) ? null : mContentValuesForQPAndUtf8);
        verifier.addPropertyNodesVerifierElem()
                .addNodeWithoutOrder("FN", "Dr. \u3075\u308B\u3069 B \u3091\u308A\u304B Ph.D",
                        contentValues)
                .addNodeWithoutOrder("N", "\u3075\u308B\u3069;\u3091\u308A\u304B;B;Dr.;Ph.D",
                        Arrays.asList(
                                "\u3075\u308B\u3069", "\u3091\u308A\u304B", "B", "Dr.", "Ph.D"),
                                null, contentValues, null, null);
        verifier.verify();
    }

    public void testNameUtf8V21() {
        testNameUtf8Common(VCardConfig.VCARD_TYPE_V21_JAPANESE_UTF8);
    }

    public void testNameUtf8V30() {
        testNameUtf8Common(VCardConfig.VCARD_TYPE_V30_JAPANESE_UTF8);
    }

    public void testNameShiftJis() {
        ExportTestResolver resolver = new ExportTestResolver();
        resolver.buildContactEntry().buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "\u3075\u308B\u3069")
                .put(StructuredName.GIVEN_NAME, "\u3091\u308A\u304B")
                .put(StructuredName.MIDDLE_NAME, "B")
                .put(StructuredName.PREFIX, "Dr.")
                .put(StructuredName.SUFFIX, "Ph.D");

        VCardVerifier verifier = new VCardVerifier(resolver,
                VCardConfig.VCARD_TYPE_V30_JAPANESE_SJIS);
        verifier.addPropertyNodesVerifierElem()
                .addNodeWithoutOrder("FN", "Dr. \u3075\u308B\u3069 B \u3091\u308A\u304B Ph.D",
                        mContentValuesForSJis)
                .addNodeWithoutOrder("N", "\u3075\u308B\u3069;\u3091\u308A\u304B;B;Dr.;Ph.D",
                        Arrays.asList(
                                "\u3075\u308B\u3069", "\u3091\u308A\u304B", "B", "Dr.", "Ph.D"),
                                null, mContentValuesForSJis, null, null);
        verifier.verify();
    }

    /**
     * DoCoMo phones require all name elements should be in "family name" field.
     */
    public void testNameDoCoMo() {
        ExportTestResolver resolver = new ExportTestResolver();
        resolver.buildContactEntry().buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "\u3075\u308B\u3069")
                .put(StructuredName.GIVEN_NAME, "\u3091\u308A\u304B")
                .put(StructuredName.MIDDLE_NAME, "B")
                .put(StructuredName.PREFIX, "Dr.")
                .put(StructuredName.SUFFIX, "Ph.D");

        VCardVerifier verifier = new VCardVerifier(resolver,
                VCardConfig.VCARD_TYPE_DOCOMO);
        final String fullName = "Dr. \u3075\u308B\u3069 B \u3091\u308A\u304B Ph.D";
        verifier.addPropertyNodesVerifierElem()
                .addNodeWithoutOrder("N", fullName + ";;;;",
                        Arrays.asList(fullName, "", "", "", ""),
                        null, mContentValuesForSJis, null, null)
                .addNodeWithoutOrder("FN", fullName, mContentValuesForSJis)
                .addNodeWithoutOrder("SOUND", ";;;;", new TypeSet("X-IRMC-N"))
                .addNodeWithoutOrder("TEL", "", new TypeSet("HOME"))
                .addNodeWithoutOrder("EMAIL", "", new TypeSet("HOME"))
                .addNodeWithoutOrder("ADR", "", new TypeSet("HOME"))
                .addNodeWithoutOrder("X-CLASS", "PUBLIC")
                .addNodeWithoutOrder("X-REDUCTION", "")
                .addNodeWithoutOrder("X-NO", "")
                .addNodeWithoutOrder("X-DCM-HMN-MODE", "");
        verifier.verify();
    }
}