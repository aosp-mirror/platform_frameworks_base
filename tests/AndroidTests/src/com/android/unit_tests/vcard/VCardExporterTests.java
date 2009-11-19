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
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.Im;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.Relation;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;

import com.android.unit_tests.vcard.PropertyNodesVerifierElem.TypeSet;

import java.util.Arrays;

/**
 * Tests for the code related to vCard exporter, inculding vCard composer.
 * This test class depends on vCard importer code, so if tests for vCard importer fail,
 * the result of this class will not be reliable.
 */
public class VCardExporterTests extends VCardTestsBase {
    private static final byte[] sPhotoByteArray =
        VCardImporterTests.sPhotoByteArrayForComplicatedCase;

    public void testSimpleV21() {
        VCardVerifier verifier = new VCardVerifier(V21);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "Ando")
                .put(StructuredName.GIVEN_NAME, "Roid");

        verifier.addPropertyNodesVerifierElem()
                .addNodeWithoutOrder("FN", "Roid Ando")
                .addNodeWithoutOrder("N", "Ando;Roid;;;",
                        Arrays.asList("Ando", "Roid", "", "", ""));
        verifier.verify();
    }

    private void testStructuredNameBasic(int vcardType) {
        final boolean isV30 = VCardConfig.isV30(vcardType);
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "AppropriateFamilyName")
                .put(StructuredName.GIVEN_NAME, "AppropriateGivenName")
                .put(StructuredName.MIDDLE_NAME, "AppropriateMiddleName")
                .put(StructuredName.PREFIX, "AppropriatePrefix")
                .put(StructuredName.SUFFIX, "AppropriateSuffix")
                .put(StructuredName.PHONETIC_FAMILY_NAME, "AppropriatePhoneticFamily")
                .put(StructuredName.PHONETIC_GIVEN_NAME, "AppropriatePhoneticGiven")
                .put(StructuredName.PHONETIC_MIDDLE_NAME, "AppropriatePhoneticMiddle");

        PropertyNodesVerifierElem elem = verifier.addPropertyNodesVerifierElem()
                .addNodeWithOrder("N",
                        "AppropriateFamilyName;AppropriateGivenName;AppropriateMiddleName;"
                        + "AppropriatePrefix;AppropriateSuffix",
                        Arrays.asList("AppropriateFamilyName", "AppropriateGivenName",
                                "AppropriateMiddleName", "AppropriatePrefix", "AppropriateSuffix"))
                .addNodeWithOrder("FN",
                        "AppropriatePrefix AppropriateGivenName "
                        + "AppropriateMiddleName AppropriateFamilyName AppropriateSuffix")
                .addNodeWithoutOrder("X-PHONETIC-FIRST-NAME", "AppropriatePhoneticGiven")
                .addNodeWithoutOrder("X-PHONETIC-MIDDLE-NAME", "AppropriatePhoneticMiddle")
                .addNodeWithoutOrder("X-PHONETIC-LAST-NAME", "AppropriatePhoneticFamily");

        if (isV30) {
            elem.addNodeWithoutOrder("SORT-STRING",
                    "AppropriatePhoneticGiven AppropriatePhoneticMiddle "
                    + "AppropriatePhoneticFamily");
        }

        verifier.verify();
    }

    public void testStructuredNameBasicV21() {
        testStructuredNameBasic(V21);
    }

    public void testStructuredNameBasicV30() {
        testStructuredNameBasic(V30);
    }

    /**
     * Test that only "primary" StructuredName is emitted, so that our vCard file
     * will not confuse the external importer, assuming there may be some importer
     * which presume that there's only one property toward each of  "N", "FN", etc.
     * Note that more than one "N", "FN", etc. properties are acceptable in vCard spec.
     */
    private void testStructuredNameUsePrimaryCommon(int vcardType) {
        final boolean isV30 = (vcardType == V30);
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "DoNotEmitFamilyName1")
                .put(StructuredName.GIVEN_NAME, "DoNotEmitGivenName1")
                .put(StructuredName.MIDDLE_NAME, "DoNotEmitMiddleName1")
                .put(StructuredName.PREFIX, "DoNotEmitPrefix1")
                .put(StructuredName.SUFFIX, "DoNotEmitSuffix1")
                .put(StructuredName.PHONETIC_FAMILY_NAME, "DoNotEmitPhoneticFamily1")
                .put(StructuredName.PHONETIC_GIVEN_NAME, "DoNotEmitPhoneticGiven1")
                .put(StructuredName.PHONETIC_MIDDLE_NAME, "DoNotEmitPhoneticMiddle1");

        // With "IS_PRIMARY=1". This is what we should use.
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "AppropriateFamilyName")
                .put(StructuredName.GIVEN_NAME, "AppropriateGivenName")
                .put(StructuredName.MIDDLE_NAME, "AppropriateMiddleName")
                .put(StructuredName.PREFIX, "AppropriatePrefix")
                .put(StructuredName.SUFFIX, "AppropriateSuffix")
                .put(StructuredName.PHONETIC_FAMILY_NAME, "AppropriatePhoneticFamily")
                .put(StructuredName.PHONETIC_GIVEN_NAME, "AppropriatePhoneticGiven")
                .put(StructuredName.PHONETIC_MIDDLE_NAME, "AppropriatePhoneticMiddle")
                .put(StructuredName.IS_PRIMARY, 1);

        // With "IS_PRIMARY=1", but we should ignore this time, since this is second, not first.
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "DoNotEmitFamilyName2")
                .put(StructuredName.GIVEN_NAME, "DoNotEmitGivenName2")
                .put(StructuredName.MIDDLE_NAME, "DoNotEmitMiddleName2")
                .put(StructuredName.PREFIX, "DoNotEmitPrefix2")
                .put(StructuredName.SUFFIX, "DoNotEmitSuffix2")
                .put(StructuredName.PHONETIC_FAMILY_NAME, "DoNotEmitPhoneticFamily2")
                .put(StructuredName.PHONETIC_GIVEN_NAME, "DoNotEmitPhoneticGiven2")
                .put(StructuredName.PHONETIC_MIDDLE_NAME, "DoNotEmitPhoneticMiddle2")
                .put(StructuredName.IS_PRIMARY, 1);

        PropertyNodesVerifierElem elem = verifier.addPropertyNodesVerifierElem()
                .addNodeWithOrder("N",
                        "AppropriateFamilyName;AppropriateGivenName;AppropriateMiddleName;"
                        + "AppropriatePrefix;AppropriateSuffix",
                        Arrays.asList("AppropriateFamilyName", "AppropriateGivenName",
                                "AppropriateMiddleName", "AppropriatePrefix", "AppropriateSuffix"))
                .addNodeWithOrder("FN",
                        "AppropriatePrefix AppropriateGivenName "
                        + "AppropriateMiddleName AppropriateFamilyName AppropriateSuffix")
                .addNodeWithoutOrder("X-PHONETIC-FIRST-NAME", "AppropriatePhoneticGiven")
                .addNodeWithoutOrder("X-PHONETIC-MIDDLE-NAME", "AppropriatePhoneticMiddle")
                .addNodeWithoutOrder("X-PHONETIC-LAST-NAME", "AppropriatePhoneticFamily");

        if (isV30) {
            elem.addNodeWithoutOrder("SORT-STRING",
                    "AppropriatePhoneticGiven AppropriatePhoneticMiddle "
                    + "AppropriatePhoneticFamily");
        }

        verifier.verify();
    }

    public void testStructuredNameUsePrimaryV21() {
        testStructuredNameUsePrimaryCommon(V21);
    }

    public void testStructuredNameUsePrimaryV30() {
        testStructuredNameUsePrimaryCommon(V30);
    }

    /**
     * Tests that only "super primary" StructuredName is emitted.
     * See also the comment in {@link #testStructuredNameUsePrimaryCommon(int)}.
     */
    private void testStructuredNameUseSuperPrimaryCommon(int vcardType) {
        final boolean isV30 = (vcardType == V30);
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "DoNotEmitFamilyName1")
                .put(StructuredName.GIVEN_NAME, "DoNotEmitGivenName1")
                .put(StructuredName.MIDDLE_NAME, "DoNotEmitMiddleName1")
                .put(StructuredName.PREFIX, "DoNotEmitPrefix1")
                .put(StructuredName.SUFFIX, "DoNotEmitSuffix1")
                .put(StructuredName.PHONETIC_FAMILY_NAME, "DoNotEmitPhoneticFamily1")
                .put(StructuredName.PHONETIC_GIVEN_NAME, "DoNotEmitPhoneticGiven1")
                .put(StructuredName.PHONETIC_MIDDLE_NAME, "DoNotEmitPhoneticMiddle1");

        // With "IS_PRIMARY=1", but we should ignore this time.
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "DoNotEmitFamilyName2")
                .put(StructuredName.GIVEN_NAME, "DoNotEmitGivenName2")
                .put(StructuredName.MIDDLE_NAME, "DoNotEmitMiddleName2")
                .put(StructuredName.PREFIX, "DoNotEmitPrefix2")
                .put(StructuredName.SUFFIX, "DoNotEmitSuffix2")
                .put(StructuredName.PHONETIC_FAMILY_NAME, "DoNotEmitPhoneticFamily2")
                .put(StructuredName.PHONETIC_GIVEN_NAME, "DoNotEmitPhoneticGiven2")
                .put(StructuredName.PHONETIC_MIDDLE_NAME, "DoNotEmitPhoneticMiddle2")
                .put(StructuredName.IS_PRIMARY, 1);

        // With "IS_SUPER_PRIMARY=1". This is what we should use.
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "AppropriateFamilyName")
                .put(StructuredName.GIVEN_NAME, "AppropriateGivenName")
                .put(StructuredName.MIDDLE_NAME, "AppropriateMiddleName")
                .put(StructuredName.PREFIX, "AppropriatePrefix")
                .put(StructuredName.SUFFIX, "AppropriateSuffix")
                .put(StructuredName.PHONETIC_FAMILY_NAME, "AppropriatePhoneticFamily")
                .put(StructuredName.PHONETIC_GIVEN_NAME, "AppropriatePhoneticGiven")
                .put(StructuredName.PHONETIC_MIDDLE_NAME, "AppropriatePhoneticMiddle")
                .put(StructuredName.IS_SUPER_PRIMARY, 1);

        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "DoNotEmitFamilyName3")
                .put(StructuredName.GIVEN_NAME, "DoNotEmitGivenName3")
                .put(StructuredName.MIDDLE_NAME, "DoNotEmitMiddleName3")
                .put(StructuredName.PREFIX, "DoNotEmitPrefix3")
                .put(StructuredName.SUFFIX, "DoNotEmitSuffix3")
                .put(StructuredName.PHONETIC_FAMILY_NAME, "DoNotEmitPhoneticFamily3")
                .put(StructuredName.PHONETIC_GIVEN_NAME, "DoNotEmitPhoneticGiven3")
                .put(StructuredName.PHONETIC_MIDDLE_NAME, "DoNotEmitPhoneticMiddle3")
                .put(StructuredName.IS_PRIMARY, 1);

        PropertyNodesVerifierElem elem = verifier.addPropertyNodesVerifierElem()
                .addNodeWithOrder("N",
                        "AppropriateFamilyName;AppropriateGivenName;AppropriateMiddleName;"
                        + "AppropriatePrefix;AppropriateSuffix",
                        Arrays.asList("AppropriateFamilyName", "AppropriateGivenName",
                                "AppropriateMiddleName", "AppropriatePrefix", "AppropriateSuffix"))
                .addNodeWithOrder("FN",
                        "AppropriatePrefix AppropriateGivenName "
                        + "AppropriateMiddleName AppropriateFamilyName AppropriateSuffix")
                .addNodeWithoutOrder("X-PHONETIC-FIRST-NAME", "AppropriatePhoneticGiven")
                .addNodeWithoutOrder("X-PHONETIC-MIDDLE-NAME", "AppropriatePhoneticMiddle")
                .addNodeWithoutOrder("X-PHONETIC-LAST-NAME", "AppropriatePhoneticFamily");

        if (isV30) {
            elem.addNodeWithoutOrder("SORT-STRING",
                    "AppropriatePhoneticGiven AppropriatePhoneticMiddle"
                    + " AppropriatePhoneticFamily");
        }

        verifier.verify();
    }

    public void testStructuredNameUseSuperPrimaryV21() {
        testStructuredNameUseSuperPrimaryCommon(V21);
    }

    public void testStructuredNameUseSuperPrimaryV30() {
        testStructuredNameUseSuperPrimaryCommon(V30);
    }

    public void testNickNameV30() {
        VCardVerifier verifier = new VCardVerifier(V30);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(Nickname.CONTENT_ITEM_TYPE)
                .put(Nickname.NAME, "Nicky");

        verifier.addPropertyNodesVerifierElemWithEmptyName()
            .addNodeWithOrder("NICKNAME", "Nicky");

        verifier.verify();
    }

    private void testPhoneBasicCommon(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "1")
                .put(Phone.TYPE, Phone.TYPE_HOME);

        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithoutOrder("TEL", "1", new TypeSet("HOME"));

        verifier.verify();
    }

    public void testPhoneBasicV21() {
        testPhoneBasicCommon(V21);
    }

    public void testPhoneBasicV30() {
        testPhoneBasicCommon(V30);
    }

    /**
     * Tests that vCard composer emits corresponding type param which we expect.
     */
    private void testPhoneVariousTypeSupport(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "10")
                .put(Phone.TYPE, Phone.TYPE_HOME);
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "20")
                .put(Phone.TYPE, Phone.TYPE_WORK);
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "30")
                .put(Phone.TYPE, Phone.TYPE_FAX_HOME);
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "40")
                .put(Phone.TYPE, Phone.TYPE_FAX_WORK);
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "50")
                .put(Phone.TYPE, Phone.TYPE_MOBILE);
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "60")
                .put(Phone.TYPE, Phone.TYPE_PAGER);
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "70")
                .put(Phone.TYPE, Phone.TYPE_OTHER);
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "80")
                .put(Phone.TYPE, Phone.TYPE_CAR);
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "90")
                .put(Phone.TYPE, Phone.TYPE_COMPANY_MAIN);
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "100")
                .put(Phone.TYPE, Phone.TYPE_ISDN);
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "110")
                .put(Phone.TYPE, Phone.TYPE_MAIN);
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "120")
                .put(Phone.TYPE, Phone.TYPE_OTHER_FAX);
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "130")
                .put(Phone.TYPE, Phone.TYPE_TELEX);
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "140")
                .put(Phone.TYPE, Phone.TYPE_WORK_MOBILE);
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "150")
                .put(Phone.TYPE, Phone.TYPE_WORK_PAGER);
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "160")
                .put(Phone.TYPE, Phone.TYPE_MMS);

        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithoutOrder("TEL", "10", new TypeSet("HOME"))
                .addNodeWithoutOrder("TEL", "20", new TypeSet("WORK"))
                .addNodeWithoutOrder("TEL", "30", new TypeSet("HOME", "FAX"))
                .addNodeWithoutOrder("TEL", "40", new TypeSet("WORK", "FAX"))
                .addNodeWithoutOrder("TEL", "50", new TypeSet("CELL"))
                .addNodeWithoutOrder("TEL", "60", new TypeSet("PAGER"))
                .addNodeWithoutOrder("TEL", "70", new TypeSet("VOICE"))
                .addNodeWithoutOrder("TEL", "80", new TypeSet("CAR"))
                .addNodeWithoutOrder("TEL", "90", new TypeSet("WORK", "PREF"))
                .addNodeWithoutOrder("TEL", "100", new TypeSet("ISDN"))
                .addNodeWithoutOrder("TEL", "110", new TypeSet("PREF"))
                .addNodeWithoutOrder("TEL", "120", new TypeSet("FAX"))
                .addNodeWithoutOrder("TEL", "130", new TypeSet("TLX"))
                .addNodeWithoutOrder("TEL", "140", new TypeSet("WORK", "CELL"))
                .addNodeWithoutOrder("TEL", "150", new TypeSet("WORK", "PAGER"))
                .addNodeWithoutOrder("TEL", "160", new TypeSet("MSG"));
        verifier.verify();
    }

    public void testPhoneVariousTypeSupportV21() {
        testPhoneVariousTypeSupport(V21);
    }

    public void testPhoneVariousTypeSupportV30() {
        testPhoneVariousTypeSupport(V30);
    }

    /**
     * Tests that "PREF"s are emitted appropriately.
     */
    private void testPhonePrefHandlingCommon(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "1")
                .put(Phone.TYPE, Phone.TYPE_HOME);
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "2")
                .put(Phone.TYPE, Phone.TYPE_WORK)
                .put(Phone.IS_PRIMARY, 1);
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "3")
                .put(Phone.TYPE, Phone.TYPE_FAX_HOME)
                .put(Phone.IS_PRIMARY, 1);
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "4")
                .put(Phone.TYPE, Phone.TYPE_FAX_WORK);

        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithoutOrder("TEL", "4", new TypeSet("WORK", "FAX"))
                .addNodeWithoutOrder("TEL", "3", new TypeSet("HOME", "FAX", "PREF"))
                .addNodeWithoutOrder("TEL", "2", new TypeSet("WORK", "PREF"))
                .addNodeWithoutOrder("TEL", "1", new TypeSet("HOME"));
        verifier.verify();
    }

    public void testPhonePrefHandlingV21() {
        testPhonePrefHandlingCommon(V21);
    }

    public void testPhonePrefHandlingV30() {
        testPhonePrefHandlingCommon(V30);
    }

    private void testMiscPhoneTypeHandling(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "1")
                .put(Phone.TYPE, Phone.TYPE_CUSTOM)
                .put(Phone.LABEL, "Modem");
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "2")
                .put(Phone.TYPE, Phone.TYPE_CUSTOM)
                .put(Phone.LABEL, "MSG");
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "3")
                .put(Phone.TYPE, Phone.TYPE_CUSTOM)
                .put(Phone.LABEL, "BBS");
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "4")
                .put(Phone.TYPE, Phone.TYPE_CUSTOM)
                .put(Phone.LABEL, "VIDEO");
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "5")
                .put(Phone.TYPE, Phone.TYPE_CUSTOM);
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "6")
                .put(Phone.TYPE, Phone.TYPE_CUSTOM)
                .put(Phone.LABEL, "_AUTO_CELL");  // The old indicator for the type mobile.
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "7")
                .put(Phone.TYPE, Phone.TYPE_CUSTOM)
                .put(Phone.LABEL, "\u643A\u5E2F");  // Mobile phone in Japanese Kanji
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "8")
                .put(Phone.TYPE, Phone.TYPE_CUSTOM)
                .put(Phone.LABEL, "invalid");

        PropertyNodesVerifierElem elem = verifier.addPropertyNodesVerifierElemWithEmptyName();
        elem.addNodeWithoutOrder("TEL", "1", new TypeSet("MODEM"))
                .addNodeWithoutOrder("TEL", "2", new TypeSet("MSG"))
                .addNodeWithoutOrder("TEL", "3", new TypeSet("BBS"))
                .addNodeWithoutOrder("TEL", "4", new TypeSet("VIDEO"))
                .addNodeWithoutOrder("TEL", "5", new TypeSet("VOICE"))
                .addNodeWithoutOrder("TEL", "6", new TypeSet("CELL"))
                .addNodeWithoutOrder("TEL", "7", new TypeSet("CELL"))
                .addNodeWithoutOrder("TEL", "8", new TypeSet("X-invalid"));
        verifier.verify();
    }

    public void testPhoneTypeHandlingV21() {
        testMiscPhoneTypeHandling(V21);
    }

    public void testPhoneTypeHandlingV30() {
        testMiscPhoneTypeHandling(V30);
    }

    private void testEmailBasicCommon(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(Email.CONTENT_ITEM_TYPE)
                .put(Email.DATA, "sample@example.com");

        verifier.addPropertyNodesVerifierElemWithEmptyName()
            .addNodeWithoutOrder("EMAIL", "sample@example.com");

        verifier.verify();
    }

    public void testEmailBasicV21() {
        testEmailBasicCommon(V21);
    }

    public void testEmailBasicV30() {
        testEmailBasicCommon(V30);
    }

    private void testEmailVariousTypeSupportCommon(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(Email.CONTENT_ITEM_TYPE)
                .put(Email.DATA, "type_home@example.com")
                .put(Email.TYPE, Email.TYPE_HOME);
        entry.buildData(Email.CONTENT_ITEM_TYPE)
                .put(Email.DATA, "type_work@example.com")
                .put(Email.TYPE, Email.TYPE_WORK);
        entry.buildData(Email.CONTENT_ITEM_TYPE)
                .put(Email.DATA, "type_mobile@example.com")
                .put(Email.TYPE, Email.TYPE_MOBILE);
        entry.buildData(Email.CONTENT_ITEM_TYPE)
                .put(Email.DATA, "type_other@example.com")
                .put(Email.TYPE, Email.TYPE_OTHER);

        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithoutOrder("EMAIL", "type_home@example.com", new TypeSet("HOME"))
                .addNodeWithoutOrder("EMAIL", "type_work@example.com", new TypeSet("WORK"))
                .addNodeWithoutOrder("EMAIL", "type_mobile@example.com", new TypeSet("CELL"))
                .addNodeWithoutOrder("EMAIL", "type_other@example.com");

        verifier.verify();
    }

    public void testEmailVariousTypeSupportV21() {
        testEmailVariousTypeSupportCommon(V21);
    }

    public void testEmailVariousTypeSupportV30() {
        testEmailVariousTypeSupportCommon(V30);
    }

    private void testEmailPrefHandlingCommon(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(Email.CONTENT_ITEM_TYPE)
                .put(Email.DATA, "type_home@example.com")
                .put(Email.TYPE, Email.TYPE_HOME)
                .put(Email.IS_PRIMARY, 1);
        entry.buildData(Email.CONTENT_ITEM_TYPE)
                .put(Email.DATA, "type_notype@example.com")
                .put(Email.IS_PRIMARY, 1);

        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithoutOrder("EMAIL", "type_notype@example.com", new TypeSet("PREF"))
                .addNodeWithoutOrder("EMAIL", "type_home@example.com", new TypeSet("HOME", "PREF"));

        verifier.verify();
    }

    public void testEmailPrefHandlingV21() {
        testEmailPrefHandlingCommon(V21);
    }

    public void testEmailPrefHandlingV30() {
        testEmailPrefHandlingCommon(V30);
    }

    private void testPostalAddressCommon(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.POBOX, "Pobox")
                .put(StructuredPostal.NEIGHBORHOOD, "Neighborhood")
                .put(StructuredPostal.STREET, "Street")
                .put(StructuredPostal.CITY, "City")
                .put(StructuredPostal.REGION, "Region")
                .put(StructuredPostal.POSTCODE, "100")
                .put(StructuredPostal.COUNTRY, "Country")
                .put(StructuredPostal.FORMATTED_ADDRESS, "Formatted Address")
                .put(StructuredPostal.TYPE, StructuredPostal.TYPE_WORK);

        // adr-value    = 0*6(text-value ";") text-value
        //              ; PO Box, Extended Address, Street, Locality, Region, Postal Code,
        //              ; Country Name
        //
        // The NEIGHBORHOOD field is appended after the CITY field.
        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithoutOrder("ADR",
                        Arrays.asList("Pobox", "", "Street", "City Neighborhood",
                                "Region", "100", "Country"), new TypeSet("WORK"));
        verifier.verify();
    }

    public void testPostalAddressV21() {
        testPostalAddressCommon(V21);
    }

    public void testPostalAddressV30() {
        testPostalAddressCommon(V30);
    }

    private void testPostalAddressNonNeighborhood(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.CITY, "City");
        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithoutOrder("ADR",
                        Arrays.asList("", "", "", "City", "", "", ""), new TypeSet("HOME"));
        verifier.verify();
    }

    public void testPostalAddressNonNeighborhoodV21() {
        testPostalAddressNonNeighborhood(V21);
    }

    public void testPostalAddressNonNeighborhoodV30() {
        testPostalAddressNonNeighborhood(V30);
    }

    private void testPostalAddressNonCity(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.NEIGHBORHOOD, "Neighborhood");
        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithoutOrder("ADR",
                        Arrays.asList("", "", "", "Neighborhood", "", "", ""), new TypeSet("HOME"));
        verifier.verify();
    }

    public void testPostalAddressNonCityV21() {
        testPostalAddressNonCity(V21);
    }

    public void testPostalAddressNonCityV30() {
        testPostalAddressNonCity(V30);
    }

    private void testPostalOnlyWithFormattedAddressCommon(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.REGION, "")  // Must be ignored.
                .put(StructuredPostal.FORMATTED_ADDRESS,
                "Formatted address CA 123-334 United Statue");

        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithOrder("ADR", ";Formatted address CA 123-334 United Statue;;;;;",
                        Arrays.asList("", "Formatted address CA 123-334 United Statue",
                                "", "", "", "", ""), new TypeSet("HOME"));
        verifier.verify();
    }

    public void testPostalOnlyWithFormattedAddressV21() {
        testPostalOnlyWithFormattedAddressCommon(V21);
    }

    public void testPostalOnlyWithFormattedAddressV30() {
        testPostalOnlyWithFormattedAddressCommon(V30);
    }

    /**
     * Tests that the vCard composer honors formatted data when it is available
     * even when it is partial.
     */
    private void testPostalWithBothStructuredAndFormattedCommon(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.POBOX, "Pobox")
                .put(StructuredPostal.COUNTRY, "Country")
                .put(StructuredPostal.FORMATTED_ADDRESS,
                        "Formatted address CA 123-334 United Statue");  // Should be ignored

        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithoutOrder("ADR", "Pobox;;;;;;Country",
                        Arrays.asList("Pobox", "", "", "", "", "", "Country"),
                        new TypeSet("HOME"));

        verifier.verify();
    }

    public void testPostalWithBothStructuredAndFormattedV21() {
        testPostalWithBothStructuredAndFormattedCommon(V21);
    }

    public void testPostalWithBothStructuredAndFormattedV30() {
        testPostalWithBothStructuredAndFormattedCommon(V30);
    }

    private void testOrganizationCommon(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(Organization.CONTENT_ITEM_TYPE)
                .put(Organization.COMPANY, "CompanyX")
                .put(Organization.DEPARTMENT, "DepartmentY")
                .put(Organization.TITLE, "TitleZ")
                .put(Organization.JOB_DESCRIPTION, "Description Rambda")  // Ignored.
                .put(Organization.OFFICE_LOCATION, "Mountain View")  // Ignored.
                .put(Organization.PHONETIC_NAME, "PhoneticName!")  // Ignored
                .put(Organization.SYMBOL, "(^o^)/~~");  // Ignore him (her).

        entry.buildData(Organization.CONTENT_ITEM_TYPE)
                .putNull(Organization.COMPANY)
                .put(Organization.DEPARTMENT, "DepartmentXX")
                .putNull(Organization.TITLE);

        entry.buildData(Organization.CONTENT_ITEM_TYPE)
                .put(Organization.COMPANY, "CompanyXYZ")
                .putNull(Organization.DEPARTMENT)
                .put(Organization.TITLE, "TitleXYZYX");

        // Currently we do not use group but depend on the order.
        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithOrder("ORG", "CompanyX;DepartmentY",
                        Arrays.asList("CompanyX", "DepartmentY"))
                .addNodeWithOrder("TITLE", "TitleZ")
                .addNodeWithOrder("ORG", "DepartmentXX")
                .addNodeWithOrder("ORG", "CompanyXYZ")
                .addNodeWithOrder("TITLE", "TitleXYZYX");
        verifier.verify();
    }

    public void testOrganizationV21() {
        testOrganizationCommon(V21);
    }

    public void testOrganizationV30() {
        testOrganizationCommon(V30);
    }

    private void testImVariousTypeSupportCommon(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_AIM)
                .put(Im.DATA, "aim");

        entry.buildData(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_MSN)
                .put(Im.DATA, "msn");

        entry.buildData(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_YAHOO)
                .put(Im.DATA, "yahoo");

        entry.buildData(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_SKYPE)
                .put(Im.DATA, "skype");

        entry.buildData(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_QQ)
                .put(Im.DATA, "qq");

        entry.buildData(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_GOOGLE_TALK)
                .put(Im.DATA, "google talk");

        entry.buildData(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_ICQ)
                .put(Im.DATA, "icq");

        entry.buildData(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_JABBER)
                .put(Im.DATA, "jabber");

        entry.buildData(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_NETMEETING)
                .put(Im.DATA, "netmeeting");

        // No determined way to express unknown type...
        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithoutOrder("X-JABBER", "jabber")
                .addNodeWithoutOrder("X-ICQ", "icq")
                .addNodeWithoutOrder("X-GOOGLE-TALK", "google talk")
                .addNodeWithoutOrder("X-QQ", "qq")
                .addNodeWithoutOrder("X-SKYPE-USERNAME", "skype")
                .addNodeWithoutOrder("X-YAHOO", "yahoo")
                .addNodeWithoutOrder("X-MSN", "msn")
                .addNodeWithoutOrder("X-NETMEETING", "netmeeting")
                .addNodeWithoutOrder("X-AIM", "aim");
        verifier.verify();
    }

    public void testImBasiV21() {
        testImVariousTypeSupportCommon(V21);
    }

    public void testImBasicV30() {
        testImVariousTypeSupportCommon(V30);
    }

    private void testImPrefHandlingCommon(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_AIM)
                .put(Im.DATA, "aim1");

        entry.buildData(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_AIM)
                .put(Im.DATA, "aim2")
                .put(Im.TYPE, Im.TYPE_HOME)
                .put(Im.IS_PRIMARY, 1);

        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithoutOrder("X-AIM", "aim1")
                .addNodeWithoutOrder("X-AIM", "aim2", new TypeSet("HOME", "PREF"));
        verifier.verify();
    }

    public void testImPrefHandlingV21() {
        testImPrefHandlingCommon(V21);
    }

    public void testImPrefHandlingV30() {
        testImPrefHandlingCommon(V30);
    }

    private void testWebsiteCommon(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(Website.CONTENT_ITEM_TYPE)
                .put(Website.URL, "http://website.example.android.com/index.html")
                .put(Website.TYPE, Website.TYPE_BLOG);

        entry.buildData(Website.CONTENT_ITEM_TYPE)
                .put(Website.URL, "ftp://ftp.example.android.com/index.html")
                .put(Website.TYPE, Website.TYPE_FTP);

        // We drop TYPE information since vCard (especially 3.0) does not allow us to emit it.
        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithoutOrder("URL", "ftp://ftp.example.android.com/index.html")
                .addNodeWithoutOrder("URL", "http://website.example.android.com/index.html");
        verifier.verify();
    }

    public void testWebsiteV21() {
        testWebsiteCommon(V21);
    }

    public void testWebsiteV30() {
        testWebsiteCommon(V30);
    }

    private String getAndroidPropValue(final String mimeType, String value, Integer type) {
        return getAndroidPropValue(mimeType, value, type, null);
    }

    private String getAndroidPropValue(final String mimeType, String value,
            Integer type, String label) {
        return (mimeType + ";" + value + ";"
                + (type != null ? type : "") + ";"
                + (label != null ? label : "") + ";;;;;;;;;;;;");
    }

    private void testEventCommon(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(Event.CONTENT_ITEM_TYPE)
                .put(Event.TYPE, Event.TYPE_ANNIVERSARY)
                .put(Event.START_DATE, "1982-06-16");
        entry.buildData(Event.CONTENT_ITEM_TYPE)
                .put(Event.TYPE, Event.TYPE_BIRTHDAY)
                .put(Event.START_DATE, "2008-10-22");
        entry.buildData(Event.CONTENT_ITEM_TYPE)
                .put(Event.TYPE, Event.TYPE_OTHER)
                .put(Event.START_DATE, "2018-03-12");
        entry.buildData(Event.CONTENT_ITEM_TYPE)
                .put(Event.TYPE, Event.TYPE_CUSTOM)
                .put(Event.LABEL, "The last day")
                .put(Event.START_DATE, "When the Tower of Hanoi with 64 rings is completed.");
        entry.buildData(Event.CONTENT_ITEM_TYPE)
                .put(Event.TYPE, Event.TYPE_BIRTHDAY)
                .put(Event.START_DATE, "2009-05-19");  // Should be ignored.
        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithoutOrder("BDAY", "2008-10-22")
                .addNodeWithoutOrder("X-ANDROID-CUSTOM",
                        getAndroidPropValue(
                                Event.CONTENT_ITEM_TYPE, "1982-06-16", Event.TYPE_ANNIVERSARY))
                .addNodeWithoutOrder("X-ANDROID-CUSTOM",
                        getAndroidPropValue(
                                Event.CONTENT_ITEM_TYPE, "2018-03-12", Event.TYPE_OTHER))
                .addNodeWithoutOrder("X-ANDROID-CUSTOM",
                        getAndroidPropValue(
                                Event.CONTENT_ITEM_TYPE,
                                "When the Tower of Hanoi with 64 rings is completed.",
                                Event.TYPE_CUSTOM, "The last day"));
        verifier.verify();
    }

    public void testEventV21() {
        testEventCommon(V21);
    }

    public void testEventV30() {
        testEventCommon(V30);
    }

    private void testNoteCommon(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(Note.CONTENT_ITEM_TYPE)
                .put(Note.NOTE, "note1");
        entry.buildData(Note.CONTENT_ITEM_TYPE)
                .put(Note.NOTE, "note2")
                .put(Note.IS_PRIMARY, 1);  // Just ignored.
        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithOrder("NOTE", "note1")
                .addNodeWithOrder("NOTE", "note2");
        verifier.verify();
    }

    public void testNoteV21() {
        testNoteCommon(V21);
    }

    public void testNoteV30() {
        testNoteCommon(V30);
    }

    private void testPhotoCommon(int vcardType) {
        final boolean isV30 = vcardType == V30;
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "PhotoTest");
        entry.buildData(Photo.CONTENT_ITEM_TYPE)
                .put(Photo.PHOTO, sPhotoByteArray);

        ContentValues contentValuesForPhoto = new ContentValues();
        contentValuesForPhoto.put("ENCODING", (isV30 ? "b" : "BASE64"));
        verifier.addPropertyNodesVerifierElem()
                .addNodeWithoutOrder("FN", "PhotoTest")
                .addNodeWithoutOrder("N", "PhotoTest;;;;",
                        Arrays.asList("PhotoTest", "", "", "", ""))
                .addNodeWithOrder("PHOTO", null, null, sPhotoByteArray,
                        contentValuesForPhoto, new TypeSet("JPEG"), null);
        verifier.verify();
    }

    public void testPhotoV21() {
        testPhotoCommon(V21);
    }

    public void testPhotoV30() {
        testPhotoCommon(V30);
    }

    private void testRelationCommon(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(Relation.CONTENT_ITEM_TYPE)
                .put(Relation.TYPE, Relation.TYPE_MOTHER)
                .put(Relation.NAME, "Ms. Mother");
        ImportVerifierElem elem = verifier.addImportVerifier();
        elem.addExpected(Relation.CONTENT_ITEM_TYPE)
                .put(Relation.TYPE, Relation.TYPE_MOTHER)
                .put(Relation.NAME, "Ms. Mother");
        verifier.verify();
    }

    public void testRelationV21() {
        testRelationCommon(V21);
    }

    public void testRelationV30() {
        testRelationCommon(V30);
    }

    public void testV30HandleEscape() {
        final int vcardType = V30;
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "\\")
                .put(StructuredName.GIVEN_NAME, ";")
                .put(StructuredName.MIDDLE_NAME, ",")
                .put(StructuredName.PREFIX, "\n")
                .put(StructuredName.DISPLAY_NAME, "[<{Unescaped:Asciis}>]");
        // Verifies the vCard String correctly escapes each character which must be escaped.
        verifier.addLineVerifier()
                .addExpected("N:\\\\;\\;;\\,;\\n;")
                .addExpected("FN:[<{Unescaped:Asciis}>]");
        verifier.addPropertyNodesVerifierElem()
                .addNodeWithoutOrder("FN", "[<{Unescaped:Asciis}>]")
                .addNodeWithoutOrder("N", Arrays.asList("\\", ";", ",", "\n", ""));
        verifier.verify();
    }

    /**
     * There's no "NICKNAME" property in vCard 2.1, while there is in vCard 3.0.
     * We use Android-specific "X-ANDROID-CUSTOM" property.
     * This test verifies the functionality.
     */
    public void testNickNameV21() {
        VCardVerifier verifier = new VCardVerifier(V21);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(Nickname.CONTENT_ITEM_TYPE)
                .put(Nickname.NAME, "Nicky");
        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithOrder("X-ANDROID-CUSTOM",
                        Nickname.CONTENT_ITEM_TYPE + ";Nicky;;;;;;;;;;;;;;");
        verifier.addImportVerifier()
                .addExpected(Nickname.CONTENT_ITEM_TYPE)
                        .put(Nickname.NAME, "Nicky");
        verifier.verify();
    }

    public void testTolerateBrokenPhoneNumberEntryV21() {
        VCardVerifier verifier = new VCardVerifier(V21);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.TYPE, Phone.TYPE_HOME)
                .put(Phone.NUMBER, "111-222-3333 (Miami)\n444-5555-666 (Tokyo);"
                        + "777-888-9999 (Chicago);111-222-3333 (Miami)");
        verifier.addPropertyNodesVerifierElemWithEmptyName()
                .addNodeWithoutOrder("TEL", "111-222-3333", new TypeSet("HOME"))
                .addNodeWithoutOrder("TEL", "444-555-5666", new TypeSet("HOME"))
                .addNodeWithoutOrder("TEL", "777-888-9999", new TypeSet("HOME"));
        verifier.verify();
    }

    private void testPickUpNonEmptyContentValuesCommon(int vcardType) {
        VCardVerifier verifier = new VCardVerifier(vcardType);
        ContactEntry entry = verifier.addInputEntry();
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.IS_PRIMARY, 1);  // Empty name. Should be ignored.
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "family1");  // Not primary. Should be ignored.
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.IS_PRIMARY, 1)
                .put(StructuredName.FAMILY_NAME, "family2");  // This entry is what we want.
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.IS_PRIMARY, 1)
                .put(StructuredName.FAMILY_NAME, "family3");
        entry.buildData(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "family4");
        verifier.addPropertyNodesVerifierElem()
                .addNodeWithoutOrder("N", Arrays.asList("family2", "", "", "", ""))
                .addNodeWithoutOrder("FN", "family2");
        verifier.verify();
    }

    public void testPickUpNonEmptyContentValuesV21() {
        testPickUpNonEmptyContentValuesCommon(V21);
    }

    public void testPickUpNonEmptyContentValuesV30() {
        testPickUpNonEmptyContentValuesCommon(V30);
    }
}
