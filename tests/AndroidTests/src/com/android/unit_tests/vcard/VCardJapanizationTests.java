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
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;

import com.android.unit_tests.vcard.PropertyNodesVerifierElem.TypeSet;
import com.android.unit_tests.vcard.VCardTestsBase.ContactEntry;
import com.android.unit_tests.vcard.VCardTestsBase.VCardVerifier;

import java.util.Arrays;

public class VCardJapanizationTests extends VCardTestsBase {
    private void testNameUtf8Common(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "\u3075\u308B\u3069")
                .put(StructuredName.GIVEN_NAME, "\u3091\u308A\u304B")
                .put(StructuredName.MIDDLE_NAME, "B")
                .put(StructuredName.PREFIX, "Dr.")
                .put(StructuredName.SUFFIX, "Ph.D");
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
        VCardVerifier verifier = new VCardVerifier(VCardConfig.VCARD_TYPE_V30_JAPANESE_SJIS);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "\u3075\u308B\u3069")
                .put(StructuredName.GIVEN_NAME, "\u3091\u308A\u304B")
                .put(StructuredName.MIDDLE_NAME, "B")
                .put(StructuredName.PREFIX, "Dr.")
                .put(StructuredName.SUFFIX, "Ph.D");

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
        VCardVerifier verifier = new VCardVerifier(VCardConfig.VCARD_TYPE_DOCOMO);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "\u3075\u308B\u3069")
                .put(StructuredName.GIVEN_NAME, "\u3091\u308A\u304B")
                .put(StructuredName.MIDDLE_NAME, "B")
                .put(StructuredName.PREFIX, "Dr.")
                .put(StructuredName.SUFFIX, "Ph.D");

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

    private void testPhoneticNameCommon(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.PHONETIC_FAMILY_NAME, "\u3084\u307E\u3060")
                .put(StructuredName.PHONETIC_MIDDLE_NAME, "\u30DF\u30C9\u30EB\u30CD\u30FC\u30E0")
                .put(StructuredName.PHONETIC_GIVEN_NAME, "\u305F\u308D\u3046");

        final ContentValues contentValues =
            (VCardConfig.usesShiftJis(vcardType) ?
                    (VCardConfig.isV30(vcardType) ? mContentValuesForSJis :
                            mContentValuesForQPAndSJis) :
                    (VCardConfig.isV30(vcardType) ? null : mContentValuesForQPAndUtf8));
        PropertyNodesVerifierElem elem = verifier.addPropertyNodesVerifierElemWithEmptyName();
        elem.addNodeWithoutOrder("X-PHONETIC-LAST-NAME", "\u3084\u307E\u3060",
                        contentValues)
                .addNodeWithoutOrder("X-PHONETIC-MIDDLE-NAME",
                        "\u30DF\u30C9\u30EB\u30CD\u30FC\u30E0",
                        contentValues)
                .addNodeWithoutOrder("X-PHONETIC-FIRST-NAME", "\u305F\u308D\u3046",
                        contentValues);
        if (VCardConfig.isV30(vcardType)) {
            elem.addNodeWithoutOrder("SORT-STRING",
                    "\u3084\u307E\u3060 \u30DF\u30C9\u30EB\u30CD\u30FC\u30E0 \u305F\u308D\u3046",
                    contentValues);
        }
        ContentValuesBuilder builder = verifier.addImportVerifier()
                .addExpected(StructuredName.CONTENT_ITEM_TYPE);
        builder.put(StructuredName.PHONETIC_FAMILY_NAME, "\u3084\u307E\u3060")
                .put(StructuredName.PHONETIC_MIDDLE_NAME, "\u30DF\u30C9\u30EB\u30CD\u30FC\u30E0")
                .put(StructuredName.PHONETIC_GIVEN_NAME, "\u305F\u308D\u3046")
                .put(StructuredName.DISPLAY_NAME,
                        "\u3084\u307E\u3060 \u30DF\u30C9\u30EB\u30CD\u30FC\u30E0 " +
                        "\u305F\u308D\u3046");
        verifier.verify();
    }

    public void testPhoneticNameForJapaneseV21Utf8() {
        testPhoneticNameCommon(VCardConfig.VCARD_TYPE_V21_JAPANESE_UTF8);
    }

    public void testPhoneticNameForJapaneseV21Sjis() {
        testPhoneticNameCommon(VCardConfig.VCARD_TYPE_V21_JAPANESE_SJIS);
    }

    public void testPhoneticNameForJapaneseV30Utf8() {
        testPhoneticNameCommon(VCardConfig.VCARD_TYPE_V30_JAPANESE_UTF8);
    }

    public void testPhoneticNameForJapaneseV30SJis() {
        testPhoneticNameCommon(VCardConfig.VCARD_TYPE_V30_JAPANESE_SJIS);
    }

    public void testPhoneticNameForMobileV21_1() {
        VCardVerifier verifier = new VCardVerifier(VCardConfig.VCARD_TYPE_V21_JAPANESE_MOBILE);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.PHONETIC_FAMILY_NAME, "\u3084\u307E\u3060")
                .put(StructuredName.PHONETIC_MIDDLE_NAME, "\u30DF\u30C9\u30EB\u30CD\u30FC\u30E0")
                .put(StructuredName.PHONETIC_GIVEN_NAME, "\u305F\u308D\u3046");

        verifier.addPropertyNodesVerifierElem()
                .addNodeWithoutOrder("SOUND",
                        "\uFF94\uFF8F\uFF80\uFF9E \uFF90\uFF84\uFF9E\uFF99\uFF88\uFF70\uFF91 " +
                        "\uFF80\uFF9B\uFF73;;;;",
                        mContentValuesForSJis, new TypeSet("X-IRMC-N"));
        ContentValuesBuilder builder = verifier.addImportVerifier()
                .addExpected(StructuredName.CONTENT_ITEM_TYPE);
        builder.put(StructuredName.PHONETIC_FAMILY_NAME, "\uFF94\uFF8F\uFF80\uFF9E")
                .put(StructuredName.PHONETIC_MIDDLE_NAME,
                        "\uFF90\uFF84\uFF9E\uFF99\uFF88\uFF70\uFF91")
                .put(StructuredName.PHONETIC_GIVEN_NAME, "\uFF80\uFF9B\uFF73")
                .put(StructuredName.DISPLAY_NAME,
                        "\uFF94\uFF8F\uFF80\uFF9E \uFF90\uFF84\uFF9E\uFF99\uFF88\uFF70\uFF91 " +
                        "\uFF80\uFF9B\uFF73");
        verifier.verify();
    }

    public void testPhoneticNameForMobileV21_2() {
        VCardVerifier verifier = new VCardVerifier(VCardConfig.VCARD_TYPE_V21_JAPANESE_MOBILE);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.PHONETIC_FAMILY_NAME, "\u3084\u307E\u3060")
                .put(StructuredName.PHONETIC_GIVEN_NAME, "\u305F\u308D\u3046");

        verifier.addPropertyNodesVerifierElem()
                .addNodeWithoutOrder("SOUND", "\uFF94\uFF8F\uFF80\uFF9E \uFF80\uFF9B\uFF73;;;;",
                                mContentValuesForSJis, new TypeSet("X-IRMC-N"));
        ContentValuesBuilder builder = verifier.addImportVerifier()
                .addExpected(StructuredName.CONTENT_ITEM_TYPE);
        builder.put(StructuredName.PHONETIC_FAMILY_NAME, "\uFF94\uFF8F\uFF80\uFF9E")
                .put(StructuredName.PHONETIC_GIVEN_NAME, "\uFF80\uFF9B\uFF73")
                .put(StructuredName.DISPLAY_NAME, "\uFF94\uFF8F\uFF80\uFF9E \uFF80\uFF9B\uFF73");
        verifier.verify();
    }

    private void testPostalAddressWithJapaneseCommon(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.POBOX, "\u79C1\u66F8\u7BB107")
                .put(StructuredPostal.NEIGHBORHOOD,
                "\u30A2\u30D1\u30FC\u30C8\u0020\u0033\u0034\u53F7\u5BA4")
                .put(StructuredPostal.STREET, "\u96DB\u898B\u6CA2\u6751")
                .put(StructuredPostal.CITY, "\u9E7F\u9AA8\u5E02")
                .put(StructuredPostal.REGION, "\u00D7\u00D7\u770C")
                .put(StructuredPostal.POSTCODE, "494-1313")
                .put(StructuredPostal.COUNTRY, "\u65E5\u672C")
                .put(StructuredPostal.FORMATTED_ADDRESS,
                        "\u3053\u3093\u306A\u3068\u3053\u308D\u3092\u898B"
                        + "\u308B\u306A\u3093\u3066\u6687\u4EBA\u3067\u3059\u304B\uFF1F")
                .put(StructuredPostal.TYPE, StructuredPostal.TYPE_CUSTOM)
                .put(StructuredPostal.LABEL, "\u304A\u3082\u3061\u304B\u3048\u308A");

        ContentValues contentValues = (VCardConfig.usesShiftJis(vcardType) ?
                (VCardConfig.isV30(vcardType) ? mContentValuesForSJis :
                    mContentValuesForQPAndSJis) :
                (VCardConfig.isV30(vcardType) ? mContentValuesForUtf8 :
                    mContentValuesForQPAndUtf8));

        PropertyNodesVerifierElem elem = verifier.addPropertyNodesVerifierElemWithEmptyName();
        // LABEL must be ignored in vCard 2.1. As for vCard 3.0, the current behavior is
        // same as that in vCard 3.0, which can be changed in the future.
        elem.addNodeWithoutOrder("ADR", Arrays.asList("\u79C1\u66F8\u7BB107",
                "\u30A2\u30D1\u30FC\u30C8\u0020\u0033\u0034\u53F7\u5BA4",
                "\u96DB\u898B\u6CA2\u6751", "\u9E7F\u9AA8\u5E02", "\u00D7\u00D7\u770C",
                "494-1313", "\u65E5\u672C"),
                contentValues);
        // NEIGHBORHOOD is "not" used. Instead, "Extended address" is appended into the
        // other field with a space.
        verifier.addImportVerifier().addExpected(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.POBOX, "\u79C1\u66F8\u7BB107")
                .put(StructuredPostal.STREET, "\u96DB\u898B\u6CA2\u6751 "
                        + "\u30A2\u30D1\u30FC\u30C8\u0020\u0033\u0034\u53F7\u5BA4")
                        .put(StructuredPostal.CITY, "\u9E7F\u9AA8\u5E02")
                .put(StructuredPostal.REGION, "\u00D7\u00D7\u770C")
                .put(StructuredPostal.POSTCODE, "494-1313")
                .put(StructuredPostal.COUNTRY, "\u65E5\u672C")
                .put(StructuredPostal.FORMATTED_ADDRESS,
                        "\u65E5\u672C 494-1313 \u00D7\u00D7\u770C \u9E7F\u9AA8\u5E02 " +
                        "\u96DB\u898B\u6CA2\u6751 "
                        + "\u30A2\u30D1\u30FC\u30C8\u0020\u0033\u0034\u53F7\u5BA4 " +
                        "\u79C1\u66F8\u7BB107")
                .put(StructuredPostal.TYPE, StructuredPostal.TYPE_HOME);
        verifier.verify();
    }

    public void testPostalAddresswithJapaneseV21() {
        testPostalAddressWithJapaneseCommon(VCardConfig.VCARD_TYPE_V21_JAPANESE_SJIS);
    }

    /**
     * Verifies that only one address field is emitted toward DoCoMo phones.
     * Prefered type must (should?) be: HOME > WORK > OTHER > CUSTOM
     */
    public void testPostalAdrressForDoCoMo_1() {
        VCardVerifier verifier = new VCardVerifier(VCardConfig.VCARD_TYPE_DOCOMO);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.TYPE, StructuredPostal.TYPE_WORK)
                .put(StructuredPostal.POBOX, "1");
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.TYPE, StructuredPostal.TYPE_OTHER)
                .put(StructuredPostal.POBOX, "2");
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.TYPE, StructuredPostal.TYPE_HOME)
                .put(StructuredPostal.POBOX, "3");
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.TYPE, StructuredPostal.TYPE_CUSTOM)
                .put(StructuredPostal.LABEL, "custom")
                .put(StructuredPostal.POBOX, "4");

        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithoutOrder("TEL", "", new TypeSet("HOME"))
                .addNodeWithoutOrder("EMAIL", "", new TypeSet("HOME"))
                .addNodeWithoutOrder("X-CLASS", "PUBLIC")
                .addNodeWithoutOrder("X-REDUCTION", "")
                .addNodeWithoutOrder("X-NO", "")
                .addNodeWithoutOrder("X-DCM-HMN-MODE", "")
                .addNodeWithoutOrder("ADR",
                        Arrays.asList("3", "", "", "", "", "", ""), new TypeSet("HOME"));
        verifier.verify();
    }

    public void testPostalAdrressForDoCoMo_2() {
        VCardVerifier verifier = new VCardVerifier(VCardConfig.VCARD_TYPE_DOCOMO);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.TYPE, StructuredPostal.TYPE_OTHER)
                .put(StructuredPostal.POBOX, "1");
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.TYPE, StructuredPostal.TYPE_WORK)
                .put(StructuredPostal.POBOX, "2");
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.TYPE, StructuredPostal.TYPE_CUSTOM)
                .put(StructuredPostal.LABEL, "custom")
                .put(StructuredPostal.POBOX, "3");

        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithoutOrder("TEL", "", new TypeSet("HOME"))
                .addNodeWithoutOrder("EMAIL", "", new TypeSet("HOME"))
                .addNodeWithoutOrder("X-CLASS", "PUBLIC")
                .addNodeWithoutOrder("X-REDUCTION", "")
                .addNodeWithoutOrder("X-NO", "")
                .addNodeWithoutOrder("X-DCM-HMN-MODE", "")
                .addNodeWithoutOrder("ADR",
                        Arrays.asList("2", "", "", "", "", "", ""), new TypeSet("WORK"));
        verifier.verify();
    }

    public void testPostalAdrressForDoCoMo_3() {
        VCardVerifier verifier = new VCardVerifier(VCardConfig.VCARD_TYPE_DOCOMO);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.TYPE, StructuredPostal.TYPE_CUSTOM)
                .put(StructuredPostal.LABEL, "custom1")
                .put(StructuredPostal.POBOX, "1");
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.TYPE, StructuredPostal.TYPE_OTHER)
                .put(StructuredPostal.POBOX, "2");
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.TYPE, StructuredPostal.TYPE_CUSTOM)
                .put(StructuredPostal.LABEL, "custom2")
                .put(StructuredPostal.POBOX, "3");

        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithoutOrder("TEL", "", new TypeSet("HOME"))
                .addNodeWithoutOrder("EMAIL", "", new TypeSet("HOME"))
                .addNodeWithoutOrder("X-CLASS", "PUBLIC")
                .addNodeWithoutOrder("X-REDUCTION", "")
                .addNodeWithoutOrder("X-NO", "")
                .addNodeWithoutOrder("X-DCM-HMN-MODE", "")
                .addNodeWithoutOrder("ADR", Arrays.asList("2", "", "", "", "", "", ""));
        verifier.verify();
    }

    /**
     * Verifies the vCard exporter tolerates null TYPE.
     */
    public void testPostalAdrressForDoCoMo_4() {
        VCardVerifier verifier = new VCardVerifier(VCardConfig.VCARD_TYPE_DOCOMO);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.POBOX, "1");
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.TYPE, StructuredPostal.TYPE_OTHER)
                .put(StructuredPostal.POBOX, "2");
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.TYPE, StructuredPostal.TYPE_HOME)
                .put(StructuredPostal.POBOX, "3");
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.TYPE, StructuredPostal.TYPE_WORK)
                .put(StructuredPostal.POBOX, "4");
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.POBOX, "5");

        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithoutOrder("TEL", "", new TypeSet("HOME"))
                .addNodeWithoutOrder("EMAIL", "", new TypeSet("HOME"))
                .addNodeWithoutOrder("X-CLASS", "PUBLIC")
                .addNodeWithoutOrder("X-REDUCTION", "")
                .addNodeWithoutOrder("X-NO", "")
                .addNodeWithoutOrder("X-DCM-HMN-MODE", "")
                .addNodeWithoutOrder("ADR",
                        Arrays.asList("3", "", "", "", "", "", ""), new TypeSet("HOME"));
        verifier.verify();
    }

    private void testJapanesePhoneNumberCommon(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "0312341234")
                .put(Phone.TYPE, Phone.TYPE_HOME);
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "09012341234")
                .put(Phone.TYPE, Phone.TYPE_MOBILE);
        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithoutOrder("TEL", "03-1234-1234", new TypeSet("HOME"))
                .addNodeWithoutOrder("TEL", "090-1234-1234", new TypeSet("WORK"));
    }

    public void testJapanesePhoneNumberV21_1() {
        testJapanesePhoneNumberCommon(VCardConfig.VCARD_TYPE_V21_JAPANESE_UTF8);
    }

    public void testJapanesePhoneNumberDoCoMo() {
        testJapanesePhoneNumberCommon(VCardConfig.VCARD_TYPE_DOCOMO);
    }

    public void testJapanesePhoneNumberV30() {
        testJapanesePhoneNumberCommon(VCardConfig.VCARD_TYPE_V30_JAPANESE_UTF8);
    }

    public void testNoteDoCoMo() {
        VCardVerifier verifier = new VCardVerifier(VCardConfig.VCARD_TYPE_DOCOMO);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(Note.CONTENT_ITEM_TYPE)
                .put(Note.NOTE, "note1");
        entry.buildData(Note.CONTENT_ITEM_TYPE)
                .put(Note.NOTE, "note2");
        entry.buildData(Note.CONTENT_ITEM_TYPE)
                .put(Note.NOTE, "note3");

        // More than one note fields must be aggregated into one note.
        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithoutOrder("TEL", "", new TypeSet("HOME"))
                .addNodeWithoutOrder("EMAIL", "", new TypeSet("HOME"))
                .addNodeWithoutOrder("X-CLASS", "PUBLIC")
                .addNodeWithoutOrder("X-REDUCTION", "")
                .addNodeWithoutOrder("X-NO", "")
                .addNodeWithoutOrder("X-DCM-HMN-MODE", "")
                .addNodeWithoutOrder("ADR", "", new TypeSet("HOME"))
                .addNodeWithoutOrder("NOTE", "note1\nnote2\nnote3", mContentValuesForQP);
        verifier.verify();
    }
}
