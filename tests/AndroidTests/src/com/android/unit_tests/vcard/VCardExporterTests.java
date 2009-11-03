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
import android.pim.vcard.VCardComposer;
import android.pim.vcard.VCardConfig;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.Im;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;

import com.android.unit_tests.vcard.PropertyNodesVerifier.TypeSet;

import java.util.Arrays;

/**
 * Tests for the code related to vCard exporter, inculding vCard composer.
 * This test class depends on vCard importer code, so if tests for vCard importer fail,
 * the result of this class will not be reliable.
 */
public class VCardExporterTests extends VCardTestsBase {
    private static final byte[] sPhotoByteArray =
        VCardImporterTests.sPhotoByteArrayForComplicatedCase;

    private void verifyOneComposition(ExportTestResolver resolver,
            VCardVerificationHandler handler, int version) {
        final boolean isV30 = (version == V30);

        final int vcardType = (isV30 ? VCardConfig.VCARD_TYPE_V30_GENERIC_UTF8
                : VCardConfig.VCARD_TYPE_V21_GENERIC_UTF8);
        VCardComposer composer = new VCardComposer(new CustomMockContext(resolver), vcardType);
        composer.addHandler(handler);
        if (!composer.init(VCardComposer.CONTACTS_TEST_CONTENT_URI, null, null, null)) {
            fail("init() failed. Reason: " + composer.getErrorReason());
        }
        assertFalse(composer.isAfterLast());
        assertTrue(composer.createOneEntry());
        assertTrue(composer.isAfterLast());
        composer.terminate();
    }

    public void testSimpleV21() {
        ExportTestResolver resolver = new ExportTestResolver();
        ContentValues contentValues = resolver.buildData(StructuredName.CONTENT_ITEM_TYPE);
        contentValues.put(StructuredName.FAMILY_NAME, "Ando");
        contentValues.put(StructuredName.GIVEN_NAME, "Roid");

        VCardVerificationHandler handler = new VCardVerificationHandler(this, V21);
        handler.addNewVerifier()
            .addNodeWithoutOrder("FN", "Roid Ando")
            .addNodeWithoutOrder("N", "Ando;Roid;;;", Arrays.asList("Ando", "Roid", "", "", ""));

        verifyOneComposition(resolver, handler, V21);
    }

    private void testStructuredNameBasic(int version) {
        final boolean isV30 = (version == V30);
        ExportTestResolver resolver = new ExportTestResolver();

        ContentValues contentValues = resolver.buildData(StructuredName.CONTENT_ITEM_TYPE);
        contentValues.put(StructuredName.FAMILY_NAME, "AppropriateFamilyName");
        contentValues.put(StructuredName.GIVEN_NAME, "AppropriateGivenName");
        contentValues.put(StructuredName.MIDDLE_NAME, "AppropriateMiddleName");
        contentValues.put(StructuredName.PREFIX, "AppropriatePrefix");
        contentValues.put(StructuredName.SUFFIX, "AppropriateSuffix");
        contentValues.put(StructuredName.PHONETIC_FAMILY_NAME, "AppropriatePhoneticFamily");
        contentValues.put(StructuredName.PHONETIC_GIVEN_NAME, "AppropriatePhoneticGiven");
        contentValues.put(StructuredName.PHONETIC_MIDDLE_NAME, "AppropriatePhoneticMiddle");

        VCardVerificationHandler handler = new VCardVerificationHandler(this, version);
        PropertyNodesVerifier verifier = handler.addNewVerifier()
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
            verifier.addNodeWithoutOrder("SORT-STRING",
                    "AppropriatePhoneticGiven AppropriatePhoneticMiddle "
                    + "AppropriatePhoneticFamily");
        }

        verifyOneComposition(resolver, handler, version);
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
    private void testStructuredNameUsePrimaryCommon(int version) {
        final boolean isV30 = (version == V30);
        ExportTestResolver resolver = new ExportTestResolver();

        ContentValues contentValues = resolver.buildData(StructuredName.CONTENT_ITEM_TYPE);
        contentValues.put(StructuredName.FAMILY_NAME, "DoNotEmitFamilyName1");
        contentValues.put(StructuredName.GIVEN_NAME, "DoNotEmitGivenName1");
        contentValues.put(StructuredName.MIDDLE_NAME, "DoNotEmitMiddleName1");
        contentValues.put(StructuredName.PREFIX, "DoNotEmitPrefix1");
        contentValues.put(StructuredName.SUFFIX, "DoNotEmitSuffix1");
        contentValues.put(StructuredName.PHONETIC_FAMILY_NAME, "DoNotEmitPhoneticFamily1");
        contentValues.put(StructuredName.PHONETIC_GIVEN_NAME, "DoNotEmitPhoneticGiven1");
        contentValues.put(StructuredName.PHONETIC_MIDDLE_NAME, "DoNotEmitPhoneticMiddle1");

        // With "IS_PRIMARY=1". This is what we should use.
        contentValues = resolver.buildData(StructuredName.CONTENT_ITEM_TYPE);
        contentValues.put(StructuredName.FAMILY_NAME, "AppropriateFamilyName");
        contentValues.put(StructuredName.GIVEN_NAME, "AppropriateGivenName");
        contentValues.put(StructuredName.MIDDLE_NAME, "AppropriateMiddleName");
        contentValues.put(StructuredName.PREFIX, "AppropriatePrefix");
        contentValues.put(StructuredName.SUFFIX, "AppropriateSuffix");
        contentValues.put(StructuredName.PHONETIC_FAMILY_NAME, "AppropriatePhoneticFamily");
        contentValues.put(StructuredName.PHONETIC_GIVEN_NAME, "AppropriatePhoneticGiven");
        contentValues.put(StructuredName.PHONETIC_MIDDLE_NAME, "AppropriatePhoneticMiddle");
        contentValues.put(StructuredName.IS_PRIMARY, 1);

        // With "IS_PRIMARY=1", but we should ignore this time, since this is second, not first.
        contentValues = resolver.buildData(StructuredName.CONTENT_ITEM_TYPE);
        contentValues.put(StructuredName.FAMILY_NAME, "DoNotEmitFamilyName2");
        contentValues.put(StructuredName.GIVEN_NAME, "DoNotEmitGivenName2");
        contentValues.put(StructuredName.MIDDLE_NAME, "DoNotEmitMiddleName2");
        contentValues.put(StructuredName.PREFIX, "DoNotEmitPrefix2");
        contentValues.put(StructuredName.SUFFIX, "DoNotEmitSuffix2");
        contentValues.put(StructuredName.PHONETIC_FAMILY_NAME, "DoNotEmitPhoneticFamily2");
        contentValues.put(StructuredName.PHONETIC_GIVEN_NAME, "DoNotEmitPhoneticGiven2");
        contentValues.put(StructuredName.PHONETIC_MIDDLE_NAME, "DoNotEmitPhoneticMiddle2");
        contentValues.put(StructuredName.IS_PRIMARY, 1);

        VCardVerificationHandler handler = new VCardVerificationHandler(this, version);
        PropertyNodesVerifier verifier = handler.addNewVerifier()
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
            verifier.addNodeWithoutOrder("SORT-STRING",
                    "AppropriatePhoneticGiven AppropriatePhoneticMiddle "
                    + "AppropriatePhoneticFamily");
        }

        verifyOneComposition(resolver, handler, version);
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
    private void testStructuredNameUseSuperPrimaryCommon(int version) {
        final boolean isV30 = (version == V30);
        ExportTestResolver resolver = new ExportTestResolver();

        ContentValues contentValues = resolver.buildData(StructuredName.CONTENT_ITEM_TYPE);
        contentValues.put(StructuredName.FAMILY_NAME, "DoNotEmitFamilyName1");
        contentValues.put(StructuredName.GIVEN_NAME, "DoNotEmitGivenName1");
        contentValues.put(StructuredName.MIDDLE_NAME, "DoNotEmitMiddleName1");
        contentValues.put(StructuredName.PREFIX, "DoNotEmitPrefix1");
        contentValues.put(StructuredName.SUFFIX, "DoNotEmitSuffix1");
        contentValues.put(StructuredName.PHONETIC_FAMILY_NAME, "DoNotEmitPhoneticFamily1");
        contentValues.put(StructuredName.PHONETIC_GIVEN_NAME, "DoNotEmitPhoneticGiven1");
        contentValues.put(StructuredName.PHONETIC_MIDDLE_NAME, "DoNotEmitPhoneticMiddle1");

        // With "IS_PRIMARY=1", but we should ignore this time.
        contentValues = resolver.buildData(StructuredName.CONTENT_ITEM_TYPE);
        contentValues.put(StructuredName.FAMILY_NAME, "DoNotEmitFamilyName2");
        contentValues.put(StructuredName.GIVEN_NAME, "DoNotEmitGivenName2");
        contentValues.put(StructuredName.MIDDLE_NAME, "DoNotEmitMiddleName2");
        contentValues.put(StructuredName.PREFIX, "DoNotEmitPrefix2");
        contentValues.put(StructuredName.SUFFIX, "DoNotEmitSuffix2");
        contentValues.put(StructuredName.PHONETIC_FAMILY_NAME, "DoNotEmitPhoneticFamily2");
        contentValues.put(StructuredName.PHONETIC_GIVEN_NAME, "DoNotEmitPhoneticGiven2");
        contentValues.put(StructuredName.PHONETIC_MIDDLE_NAME, "DoNotEmitPhoneticMiddle2");
        contentValues.put(StructuredName.IS_PRIMARY, 1);

        // With "IS_SUPER_PRIMARY=1". This is what we should use.
        contentValues = resolver.buildData(StructuredName.CONTENT_ITEM_TYPE);
        contentValues.put(StructuredName.FAMILY_NAME, "AppropriateFamilyName");
        contentValues.put(StructuredName.GIVEN_NAME, "AppropriateGivenName");
        contentValues.put(StructuredName.MIDDLE_NAME, "AppropriateMiddleName");
        contentValues.put(StructuredName.PREFIX, "AppropriatePrefix");
        contentValues.put(StructuredName.SUFFIX, "AppropriateSuffix");
        contentValues.put(StructuredName.PHONETIC_FAMILY_NAME, "AppropriatePhoneticFamily");
        contentValues.put(StructuredName.PHONETIC_GIVEN_NAME, "AppropriatePhoneticGiven");
        contentValues.put(StructuredName.PHONETIC_MIDDLE_NAME, "AppropriatePhoneticMiddle");
        contentValues.put(StructuredName.IS_SUPER_PRIMARY, 1);

        contentValues = resolver.buildData(StructuredName.CONTENT_ITEM_TYPE);
        contentValues.put(StructuredName.FAMILY_NAME, "DoNotEmitFamilyName3");
        contentValues.put(StructuredName.GIVEN_NAME, "DoNotEmitGivenName3");
        contentValues.put(StructuredName.MIDDLE_NAME, "DoNotEmitMiddleName3");
        contentValues.put(StructuredName.PREFIX, "DoNotEmitPrefix3");
        contentValues.put(StructuredName.SUFFIX, "DoNotEmitSuffix3");
        contentValues.put(StructuredName.PHONETIC_FAMILY_NAME, "DoNotEmitPhoneticFamily3");
        contentValues.put(StructuredName.PHONETIC_GIVEN_NAME, "DoNotEmitPhoneticGiven3");
        contentValues.put(StructuredName.PHONETIC_MIDDLE_NAME, "DoNotEmitPhoneticMiddle3");
        contentValues.put(StructuredName.IS_PRIMARY, 1);

        VCardVerificationHandler handler = new VCardVerificationHandler(this, version);
        PropertyNodesVerifier verifier = handler.addNewVerifier()
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
            verifier.addNodeWithoutOrder("SORT-STRING",
                    "AppropriatePhoneticGiven AppropriatePhoneticMiddle"
                    + " AppropriatePhoneticFamily");
        }

        verifyOneComposition(resolver, handler, version);
    }

    public void testStructuredNameUseSuperPrimaryV21() {
        testStructuredNameUseSuperPrimaryCommon(V21);
    }

    public void testStructuredNameUseSuperPrimaryV30() {
        testStructuredNameUseSuperPrimaryCommon(V30);
    }

    public void testNickNameV30() {
        ExportTestResolver resolver = new ExportTestResolver();
        ContentValues contentValues = resolver.buildData(Nickname.CONTENT_ITEM_TYPE);
        contentValues.put(Nickname.NAME, "Nicky");

        VCardVerificationHandler handler = new VCardVerificationHandler(this, V30);
        handler.addNewVerifierWithEmptyName()
            .addNodeWithOrder("NICKNAME", "Nicky");

        verifyOneComposition(resolver, handler, V30);
    }

    private void testPhoneBasicCommon(int version) {
        ExportTestResolver resolver = new ExportTestResolver();

        ContentValues contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "1");
        contentValues.put(Phone.TYPE, Phone.TYPE_HOME);

        VCardVerificationHandler handler = new VCardVerificationHandler(this, version);
        handler.addNewVerifierWithEmptyName()
            .addNodeWithoutOrder("TEL", "1", new TypeSet("HOME", "VOICE"));

        verifyOneComposition(resolver, handler, version);
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
    private void testPhoneVariousTypeSupport(int version) {
        ExportTestResolver resolver = new ExportTestResolver();

        ContentValues contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "10");
        contentValues.put(Phone.TYPE, Phone.TYPE_HOME);

        contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "20");
        contentValues.put(Phone.TYPE, Phone.TYPE_WORK);

        contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "30");
        contentValues.put(Phone.TYPE, Phone.TYPE_FAX_HOME);

        contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "40");
        contentValues.put(Phone.TYPE, Phone.TYPE_FAX_WORK);

        contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "50");
        contentValues.put(Phone.TYPE, Phone.TYPE_MOBILE);

        contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "60");
        contentValues.put(Phone.TYPE, Phone.TYPE_PAGER);

        contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "70");
        contentValues.put(Phone.TYPE, Phone.TYPE_OTHER);

        contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "80");
        contentValues.put(Phone.TYPE, Phone.TYPE_CAR);

        contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "90");
        contentValues.put(Phone.TYPE, Phone.TYPE_COMPANY_MAIN);

        contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "100");
        contentValues.put(Phone.TYPE, Phone.TYPE_ISDN);

        contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "110");
        contentValues.put(Phone.TYPE, Phone.TYPE_MAIN);

        contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "120");
        contentValues.put(Phone.TYPE, Phone.TYPE_OTHER_FAX);

        contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "130");
        contentValues.put(Phone.TYPE, Phone.TYPE_TELEX);

        contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "140");
        contentValues.put(Phone.TYPE, Phone.TYPE_WORK_MOBILE);

        contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "150");
        contentValues.put(Phone.TYPE, Phone.TYPE_WORK_PAGER);

        contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "160");
        contentValues.put(Phone.TYPE, Phone.TYPE_MMS);

        VCardVerificationHandler handler = new VCardVerificationHandler(this, version);
        handler.addNewVerifierWithEmptyName()
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
            .addNodeWithoutOrder("TEL", "140", new TypeSet("WORK", "MOBILE"))
            .addNodeWithoutOrder("TEL", "150", new TypeSet("WORK", "PAGER"))
            .addNodeWithoutOrder("TEL", "160", new TypeSet("MSG"));
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
    private void testPhonePrefHandlingCommon(int version) {
        ExportTestResolver resolver = new ExportTestResolver();

        ContentValues contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "1");
        contentValues.put(Phone.TYPE, Phone.TYPE_HOME);

        contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "2");
        contentValues.put(Phone.TYPE, Phone.TYPE_WORK);
        contentValues.put(Phone.IS_PRIMARY, 1);

        contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "3");
        contentValues.put(Phone.TYPE, Phone.TYPE_FAX_HOME);
        contentValues.put(Phone.IS_PRIMARY, 1);

        contentValues = resolver.buildData(Phone.CONTENT_ITEM_TYPE);
        contentValues.put(Phone.NUMBER, "4");
        contentValues.put(Phone.TYPE, Phone.TYPE_FAX_WORK);

        VCardVerificationHandler handler = new VCardVerificationHandler(this, version);
        handler.addNewVerifierWithEmptyName()
            .addNodeWithoutOrder("TEL", "4", new TypeSet("WORK", "FAX"))
            .addNodeWithoutOrder("TEL", "3", new TypeSet("HOME", "FAX", "PREF"))
            .addNodeWithoutOrder("TEL", "2", new TypeSet("WORK", "VOICE", "PREF"))
            .addNodeWithoutOrder("TEL", "1", new TypeSet("HOME", "VOICE"));

        verifyOneComposition(resolver, handler, version);
    }

    public void testPhonePrefHandlingV21() {
        testPhonePrefHandlingCommon(V21);
    }

    public void testPhonePrefHandlingV30() {
        testPhonePrefHandlingCommon(V30);
    }

    private void testEmailBasicCommon(int version) {
        ExportTestResolver resolver = new ExportTestResolver();
        ContentValues contentValues = resolver.buildData(Email.CONTENT_ITEM_TYPE);
        contentValues.put(Email.DATA, "sample@example.com");

        VCardVerificationHandler handler = new VCardVerificationHandler(this, version);

        handler.addNewVerifierWithEmptyName()
            .addNodeWithoutOrder("EMAIL", "sample@example.com");

        verifyOneComposition(resolver, handler, version);
    }

    public void testEmailBasicV21() {
        testEmailBasicCommon(V21);
    }

    public void testEmailBasicV30() {
        testEmailBasicCommon(V30);
    }

    private void testEmailVariousTypeSupportCommon(int version) {
        ExportTestResolver resolver = new ExportTestResolver();

        ContentValues contentValues = resolver.buildData(Email.CONTENT_ITEM_TYPE);
        contentValues.put(Email.DATA, "type_home@example.com");
        contentValues.put(Email.TYPE, Email.TYPE_HOME);

        contentValues = resolver.buildData(Email.CONTENT_ITEM_TYPE);
        contentValues.put(Email.DATA, "type_work@example.com");
        contentValues.put(Email.TYPE, Email.TYPE_WORK);

        contentValues = resolver.buildData(Email.CONTENT_ITEM_TYPE);
        contentValues.put(Email.DATA, "type_mobile@example.com");
        contentValues.put(Email.TYPE, Email.TYPE_MOBILE);

        contentValues = resolver.buildData(Email.CONTENT_ITEM_TYPE);
        contentValues.put(Email.DATA, "type_other@example.com");
        contentValues.put(Email.TYPE, Email.TYPE_OTHER);

        VCardVerificationHandler handler = new VCardVerificationHandler(this, version);

        handler.addNewVerifierWithEmptyName()
            .addNodeWithoutOrder("EMAIL", "type_home@example.com", new TypeSet("HOME"))
            .addNodeWithoutOrder("EMAIL", "type_work@example.com", new TypeSet("WORK"))
            .addNodeWithoutOrder("EMAIL", "type_mobile@example.com", new TypeSet("CELL"))
            .addNodeWithoutOrder("EMAIL", "type_other@example.com");

        verifyOneComposition(resolver, handler, version);
    }

    public void testEmailVariousTypeSupportV21() {
        testEmailVariousTypeSupportCommon(V21);
    }

    public void testEmailVariousTypeSupportV30() {
        testEmailVariousTypeSupportCommon(V30);
    }

    private void testEmailPrefHandlingCommon(int version) {
        ExportTestResolver resolver = new ExportTestResolver();

        ContentValues contentValues = resolver.buildData(Email.CONTENT_ITEM_TYPE);
        contentValues.put(Email.DATA, "type_home@example.com");
        contentValues.put(Email.TYPE, Email.TYPE_HOME);
        contentValues.put(Email.IS_PRIMARY, 1);

        contentValues = resolver.buildData(Email.CONTENT_ITEM_TYPE);
        contentValues.put(Email.DATA, "type_notype@example.com");
        contentValues.put(Email.IS_PRIMARY, 1);

        VCardVerificationHandler handler = new VCardVerificationHandler(this, version);

        handler.addNewVerifierWithEmptyName()
            .addNodeWithoutOrder("EMAIL", "type_notype@example.com", new TypeSet("PREF"))
            .addNodeWithoutOrder("EMAIL", "type_home@example.com", new TypeSet("HOME", "PREF"));

        verifyOneComposition(resolver, handler, version);
    }

    public void testEmailPrefHandlingV21() {
        testEmailPrefHandlingCommon(V21);
    }

    public void testEmailPrefHandlingV30() {
        testEmailPrefHandlingCommon(V30);
    }

    private void testPostalOnlyWithStructuredDataCommon(int version) {
        ExportTestResolver resolver = new ExportTestResolver();

        // adr-value    = 0*6(text-value ";") text-value
        //              ; PO Box, Extended Address, Street, Locality, Region, Postal Code,
        //              ; Country Name
        ContentValues contentValues = resolver.buildData(StructuredPostal.CONTENT_ITEM_TYPE);
        contentValues.put(StructuredPostal.POBOX, "Pobox");
        contentValues.put(StructuredPostal.NEIGHBORHOOD, "Neighborhood");
        contentValues.put(StructuredPostal.STREET, "Street");
        contentValues.put(StructuredPostal.CITY, "City");
        contentValues.put(StructuredPostal.REGION, "Region");
        contentValues.put(StructuredPostal.POSTCODE, "100");
        contentValues.put(StructuredPostal.COUNTRY, "Country");
        VCardVerificationHandler handler = new VCardVerificationHandler(this, version);
        handler.addNewVerifierWithEmptyName()
            .addNodeWithoutOrder("ADR", "Pobox;Neighborhood;Street;City;Region;100;Country",
                    Arrays.asList("Pobox", "Neighborhood", "Street", "City",
                            "Region", "100", "Country"), new TypeSet("HOME"));

        verifyOneComposition(resolver, handler, version);
    }

    public void testPostalOnlyWithStructuredDataV21() {
        testPostalOnlyWithStructuredDataCommon(V21);
    }

    public void testPostalOnlyWithStructuredDataV30() {
        testPostalOnlyWithStructuredDataCommon(V30);
    }

    private void testPostalOnlyWithFormattedAddressCommon(int version) {
        ExportTestResolver resolver = new ExportTestResolver();

        ContentValues contentValues = resolver.buildData(StructuredPostal.CONTENT_ITEM_TYPE);
        contentValues.put(StructuredPostal.FORMATTED_ADDRESS,
                "Formatted address CA 123-334 United Statue");

        VCardVerificationHandler handler = new VCardVerificationHandler(this, version);
        handler.addNewVerifierWithEmptyName()
            .addNodeWithOrder("ADR", ";Formatted address CA 123-334 United Statue;;;;;",
                    Arrays.asList("", "Formatted address CA 123-334 United Statue",
                            "", "", "", "", ""), new TypeSet("HOME"));

        verifyOneComposition(resolver, handler, version);
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
    private void testPostalWithBothStructuredAndFormattedCommon(int version) {
        ExportTestResolver resolver = new ExportTestResolver();

        ContentValues contentValues = resolver.buildData(StructuredPostal.CONTENT_ITEM_TYPE);
        contentValues.put(StructuredPostal.POBOX, "Pobox");
        contentValues.put(StructuredPostal.COUNTRY, "Country");
        contentValues.put(StructuredPostal.FORMATTED_ADDRESS,
                "Formatted address CA 123-334 United Statue");  // Should be ignored

        VCardVerificationHandler handler = new VCardVerificationHandler(this, version);
        handler.addNewVerifierWithEmptyName()
            .addNodeWithoutOrder("ADR", "Pobox;;;;;;Country",
                    Arrays.asList("Pobox", "", "", "", "", "", "Country"), new TypeSet("HOME"));

        verifyOneComposition(resolver, handler, version);
    }

    public void testPostalWithBothStructuredAndFormattedV21() {
        testPostalWithBothStructuredAndFormattedCommon(V21);
    }

    public void testPostalWithBothStructuredAndFormattedV30() {
        testPostalWithBothStructuredAndFormattedCommon(V30);
    }

    private void testOrganizationCommon(int version) {
        ExportTestResolver resolver = new ExportTestResolver();
        ContentValues contentValues = resolver.buildData(Organization.CONTENT_ITEM_TYPE);
        contentValues.put(Organization.COMPANY, "CompanyX");
        contentValues.put(Organization.DEPARTMENT, "DepartmentY");
        contentValues.put(Organization.TITLE, "TitleZ");
        contentValues.put(Organization.JOB_DESCRIPTION, "Description Rambda");  // Ignored.
        contentValues.put(Organization.OFFICE_LOCATION, "Mountain View");  // Ignored.
        contentValues.put(Organization.PHONETIC_NAME, "PhoneticName!");  // Ignored
        contentValues.put(Organization.SYMBOL, "(^o^)/~~");  // Ignore him (her).

        contentValues = resolver.buildData(Organization.CONTENT_ITEM_TYPE);
        contentValues.putNull(Organization.COMPANY);
        contentValues.put(Organization.DEPARTMENT, "DepartmentXX");
        contentValues.putNull(Organization.TITLE);

        contentValues = resolver.buildData(Organization.CONTENT_ITEM_TYPE);
        contentValues.put(Organization.COMPANY, "CompanyXYZ");
        contentValues.putNull(Organization.DEPARTMENT);
        contentValues.put(Organization.TITLE, "TitleXYZYX");

        VCardVerificationHandler handler = new VCardVerificationHandler(this, version);

        // Currently we do not use group but depend on the order.
        handler.addNewVerifierWithEmptyName()
            .addNodeWithOrder("ORG", "CompanyX;DepartmentY",
                    Arrays.asList("CompanyX", "DepartmentY"))
            .addNodeWithOrder("TITLE", "TitleZ")
            .addNodeWithOrder("ORG", "DepartmentXX")
            .addNodeWithOrder("ORG", "CompanyXYZ")
            .addNodeWithOrder("TITLE", "TitleXYZYX");

        verifyOneComposition(resolver, handler, version);
    }

    public void testOrganizationV21() {
        testOrganizationCommon(V21);
    }

    public void testOrganizationV30() {
        testOrganizationCommon(V30);
    }

    private void testImVariousTypeSupportCommon(int version) {
        ExportTestResolver resolver = new ExportTestResolver();

        ContentValues contentValues = resolver.buildData(Im.CONTENT_ITEM_TYPE);
        contentValues.put(Im.PROTOCOL, Im.PROTOCOL_AIM);
        contentValues.put(Im.DATA, "aim");

        contentValues = resolver.buildData(Im.CONTENT_ITEM_TYPE);
        contentValues.put(Im.PROTOCOL, Im.PROTOCOL_MSN);
        contentValues.put(Im.DATA, "msn");

        contentValues = resolver.buildData(Im.CONTENT_ITEM_TYPE);
        contentValues.put(Im.PROTOCOL, Im.PROTOCOL_YAHOO);
        contentValues.put(Im.DATA, "yahoo");

        contentValues = resolver.buildData(Im.CONTENT_ITEM_TYPE);
        contentValues.put(Im.PROTOCOL, Im.PROTOCOL_SKYPE);
        contentValues.put(Im.DATA, "skype");

        contentValues = resolver.buildData(Im.CONTENT_ITEM_TYPE);
        contentValues.put(Im.PROTOCOL, Im.PROTOCOL_QQ);
        contentValues.put(Im.DATA, "qq");

        contentValues = resolver.buildData(Im.CONTENT_ITEM_TYPE);
        contentValues.put(Im.PROTOCOL, Im.PROTOCOL_GOOGLE_TALK);
        contentValues.put(Im.DATA, "google talk");

        contentValues = resolver.buildData(Im.CONTENT_ITEM_TYPE);
        contentValues.put(Im.PROTOCOL, Im.PROTOCOL_ICQ);
        contentValues.put(Im.DATA, "icq");

        contentValues = resolver.buildData(Im.CONTENT_ITEM_TYPE);
        contentValues.put(Im.PROTOCOL, Im.PROTOCOL_JABBER);
        contentValues.put(Im.DATA, "jabber");

        contentValues = resolver.buildData(Im.CONTENT_ITEM_TYPE);
        contentValues.put(Im.PROTOCOL, Im.PROTOCOL_NETMEETING);
        contentValues.put(Im.DATA, "netmeeting");

        // No determined way to express unknown type...

        VCardVerificationHandler handler = new VCardVerificationHandler(this, version);
        handler.addNewVerifierWithEmptyName()
            .addNodeWithoutOrder("X-JABBER", "jabber")
            .addNodeWithoutOrder("X-ICQ", "icq")
            .addNodeWithoutOrder("X-GOOGLE-TALK", "google talk")
            .addNodeWithoutOrder("X-QQ", "qq")
            .addNodeWithoutOrder("X-SKYPE-USERNAME", "skype")
            .addNodeWithoutOrder("X-YAHOO", "yahoo")
            .addNodeWithoutOrder("X-MSN", "msn")
            .addNodeWithoutOrder("X-NETMEETING", "netmeeting")
            .addNodeWithoutOrder("X-AIM", "aim");

        verifyOneComposition(resolver, handler, version);
    }

    public void testImBasiV21() {
        testImVariousTypeSupportCommon(V21);
    }

    public void testImBasicV30() {
        testImVariousTypeSupportCommon(V30);
    }

    private void testImPrefHandlingCommon(int version) {
        ExportTestResolver resolver = new ExportTestResolver();

        ContentValues contentValues = resolver.buildData(Im.CONTENT_ITEM_TYPE);
        contentValues.put(Im.PROTOCOL, Im.PROTOCOL_AIM);
        contentValues.put(Im.DATA, "aim1");

        contentValues = resolver.buildData(Im.CONTENT_ITEM_TYPE);
        contentValues.put(Im.PROTOCOL, Im.PROTOCOL_AIM);
        contentValues.put(Im.DATA, "aim2");
        contentValues.put(Im.TYPE, Im.TYPE_HOME);
        contentValues.put(Im.IS_PRIMARY, 1);

        VCardVerificationHandler handler = new VCardVerificationHandler(this, version);
        handler.addNewVerifierWithEmptyName()
            .addNodeWithoutOrder("X-AIM", "aim1")
            .addNodeWithoutOrder("X-AIM", "aim2", new TypeSet("HOME", "PREF"));

        verifyOneComposition(resolver, handler, version);
    }

    public void testImPrefHandlingV21() {
        testImPrefHandlingCommon(V21);
    }

    public void testImPrefHandlingV30() {
        testImPrefHandlingCommon(V30);
    }

    private void testWebsiteCommon(int version) {
        ExportTestResolver resolver = new ExportTestResolver();

        ContentValues contentValues = resolver.buildData(Website.CONTENT_ITEM_TYPE);
        contentValues.put(Website.URL, "http://website.example.android.com/index.html");
        contentValues.put(Website.TYPE, Website.TYPE_BLOG);

        contentValues = resolver.buildData(Website.CONTENT_ITEM_TYPE);
        contentValues.put(Website.URL, "ftp://ftp.example.android.com/index.html");
        contentValues.put(Website.TYPE, Website.TYPE_FTP);

        // We drop TYPE information since vCard (especially 3.0) does not allow us to emit it.
        VCardVerificationHandler handler = new VCardVerificationHandler(this, version);
        handler.addNewVerifierWithEmptyName()
            .addNodeWithoutOrder("URL", "ftp://ftp.example.android.com/index.html")
            .addNodeWithoutOrder("URL", "http://website.example.android.com/index.html");

        verifyOneComposition(resolver, handler, version);
    }

    public void testWebsiteV21() {
        testWebsiteCommon(V21);
    }

    public void testWebsiteV30() {
        testWebsiteCommon(V30);
    }

    private void testEventCommon(int version) {
        ExportTestResolver resolver = new ExportTestResolver();

        ContentValues contentValues = resolver.buildData(Event.CONTENT_ITEM_TYPE);
        contentValues.put(Event.TYPE, Event.TYPE_ANNIVERSARY);
        contentValues.put(Event.START_DATE, "1982-06-16");

        contentValues = resolver.buildData(Event.CONTENT_ITEM_TYPE);
        contentValues.put(Event.TYPE, Event.TYPE_BIRTHDAY);
        contentValues.put(Event.START_DATE, "2008-10-22");

        contentValues = resolver.buildData(Event.CONTENT_ITEM_TYPE);
        contentValues.put(Event.TYPE, Event.TYPE_OTHER);
        contentValues.put(Event.START_DATE, "2018-03-12");

        contentValues = resolver.buildData(Event.CONTENT_ITEM_TYPE);
        contentValues.put(Event.TYPE, Event.TYPE_CUSTOM);
        contentValues.put(Event.LABEL, "The last day");
        contentValues.put(Event.START_DATE, "When the Tower of Hanoi with 64 rings is completed.");

        contentValues = resolver.buildData(Event.CONTENT_ITEM_TYPE);
        contentValues.put(Event.TYPE, Event.TYPE_BIRTHDAY);
        contentValues.put(Event.START_DATE, "2009-05-19");

        VCardVerificationHandler handler = new VCardVerificationHandler(this, version);
        handler.addNewVerifierWithEmptyName()
            .addNodeWithoutOrder("BDAY", "2008-10-22");

        verifyOneComposition(resolver, handler, version);
    }

    public void testEventV21() {
        testEventCommon(V21);
    }

    public void testEventV30() {
        testEventCommon(V30);
    }

    private void testNoteCommon(int version) {
        ExportTestResolver resolver = new ExportTestResolver();

        ContentValues contentValues = resolver.buildData(Note.CONTENT_ITEM_TYPE);
        contentValues.put(Note.NOTE, "note1");

        contentValues = resolver.buildData(Note.CONTENT_ITEM_TYPE);
        contentValues.put(Note.NOTE, "note2");
        contentValues.put(Note.IS_PRIMARY, 1);  // Just ignored.

        VCardVerificationHandler handler = new VCardVerificationHandler(this, version);
        handler.addNewVerifierWithEmptyName()
            .addNodeWithOrder("NOTE", "note1")
            .addNodeWithOrder("NOTE", "note2");

        verifyOneComposition(resolver, handler, version);
    }

    public void testNoteV21() {
        testNoteCommon(V21);
    }

    public void testNoteV30() {
        testNoteCommon(V30);
    }

    private void testPhotoCommon(int version) {
        final boolean isV30 = version == V30;
        ExportTestResolver resolver = new ExportTestResolver();
        ContentValues contentValues = resolver.buildData(StructuredName.CONTENT_ITEM_TYPE);
        contentValues.put(StructuredName.FAMILY_NAME, "PhotoTest");

        contentValues = resolver.buildData(Photo.CONTENT_ITEM_TYPE);
        contentValues.put(Photo.PHOTO, sPhotoByteArray);

        ContentValues contentValuesForPhoto = new ContentValues();
        contentValuesForPhoto.put("ENCODING", (isV30 ? "b" : "BASE64"));
        VCardVerificationHandler handler = new VCardVerificationHandler(this, version);
        handler.addNewVerifier()
            .addNodeWithoutOrder("FN", "PhotoTest")
            .addNodeWithoutOrder("N", "PhotoTest;;;;", Arrays.asList("PhotoTest", "", "", "", ""))
            .addNodeWithOrder("PHOTO", null, null, sPhotoByteArray,
                    contentValuesForPhoto, new TypeSet("JPEG"), null);

        verifyOneComposition(resolver, handler, version);
    }

    public void testPhotoV21() {
        testPhotoCommon(V21);
    }

    public void testPhotoV30() {
        testPhotoCommon(V30);
    }

    public void testV30HandleEscape() {
        final int version = V30;
        ExportTestResolver resolver = new ExportTestResolver();
        ContentValues contentValues = resolver.buildData(StructuredName.CONTENT_ITEM_TYPE);
        contentValues.put(StructuredName.FAMILY_NAME, "\\");
        contentValues.put(StructuredName.GIVEN_NAME, ";");
        contentValues.put(StructuredName.MIDDLE_NAME, ",");
        contentValues.put(StructuredName.PREFIX, "\n");
        contentValues.put(StructuredName.DISPLAY_NAME, "[<{Unescaped:Asciis}>]");
        VCardVerificationHandler handler = new VCardVerificationHandler(this, version);
        // Verifies the vCard String correctly escapes each character which must be escaped.
        handler.addExpectedLine("N:\\\\;\\;;\\,;\\n;")
            .addExpectedLine("FN:[<{Unescaped:Asciis}>]");
        handler.addNewVerifier()
            .addNodeWithoutOrder("FN", "[<{Unescaped:Asciis}>]")
            .addNodeWithoutOrder("N", Arrays.asList("\\", ";", ",", "\n", ""));

        verifyOneComposition(resolver, handler, version);
    }

    /**
     * There's no "NICKNAME" property in vCard 2.1, while there is in vCard 3.0.
     * We use Android-specific "X-ANDROID-CUSTOM" property.
     * This test verifies the functionality.
     */
    public void testNickNameV21() {
        ExportTestResolver resolver = new ExportTestResolver();
        ContentValues contentValues = resolver.buildData(Nickname.CONTENT_ITEM_TYPE);
        contentValues.put(Nickname.NAME, "Nicky");

        ContactStructVerifier verifier = new ContactStructVerifier();
        contentValues = verifier.createExpected(Nickname.CONTENT_ITEM_TYPE);
        contentValues.put(Nickname.NAME, "Nicky");

        VCardVerificationHandler handler = new VCardVerificationHandler(this, verifier, V21);
        handler.addNewVerifierWithEmptyName()
            .addNodeWithOrder("X-ANDROID-CUSTOM",
                    Nickname.CONTENT_ITEM_TYPE + ";Nicky;;;;;;;;;;;;;;");

        verifyOneComposition(resolver, handler, V21);
    }
}
