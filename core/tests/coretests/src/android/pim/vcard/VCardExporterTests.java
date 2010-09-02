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

import android.content.ContentValues;
import android.pim.vcard.test_utils.ContactEntry;
import android.pim.vcard.test_utils.PropertyNodesVerifierElem;
import android.pim.vcard.test_utils.PropertyNodesVerifierElem.TypeSet;
import android.pim.vcard.test_utils.VCardTestsBase;
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
        mVerifier.initForExportTest(V21);
        mVerifier.addInputEntry().addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "Ando")
                .put(StructuredName.GIVEN_NAME, "Roid");
        mVerifier.addPropertyNodesVerifierElem()
                .addExpectedNode("FN", "Roid Ando")
                .addExpectedNode("N", "Ando;Roid;;;",
                        Arrays.asList("Ando", "Roid", "", "", ""));
    }

    private void testStructuredNameBasic(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        mVerifier.addInputEntry().addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "AppropriateFamilyName")
                .put(StructuredName.GIVEN_NAME, "AppropriateGivenName")
                .put(StructuredName.MIDDLE_NAME, "AppropriateMiddleName")
                .put(StructuredName.PREFIX, "AppropriatePrefix")
                .put(StructuredName.SUFFIX, "AppropriateSuffix")
                .put(StructuredName.DISPLAY_NAME, "DISPLAY NAME");

        mVerifier.addPropertyNodesVerifierElem()
                .addExpectedNodeWithOrder("N",
                        "AppropriateFamilyName;AppropriateGivenName;AppropriateMiddleName;"
                        + "AppropriatePrefix;AppropriateSuffix",
                        Arrays.asList("AppropriateFamilyName", "AppropriateGivenName",
                                "AppropriateMiddleName", "AppropriatePrefix", "AppropriateSuffix"))
                .addExpectedNodeWithOrder("FN", "DISPLAY NAME");
    }

    public void testStructuredNameBasicV21() {
        testStructuredNameBasic(V21);
    }

    public void testStructuredNameBasicV30() {
        testStructuredNameBasic(V30);
    }

    public void testStructuredNameBasicV40() {
        testStructuredNameBasic(V40);
    }

    /**
     * Test that only "primary" StructuredName is emitted, so that our vCard file
     * will not confuse the external importer, assuming there may be some importer
     * which presume that there's only one property toward each of  "N", "FN", etc.
     * Note that more than one "N", "FN", etc. properties are acceptable in vCard spec.
     */
    private void testStructuredNameUsePrimaryCommon(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        final ContactEntry entry = mVerifier.addInputEntry();
        entry.addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "DoNotEmitFamilyName1")
                .put(StructuredName.GIVEN_NAME, "DoNotEmitGivenName1")
                .put(StructuredName.MIDDLE_NAME, "DoNotEmitMiddleName1")
                .put(StructuredName.PREFIX, "DoNotEmitPrefix1")
                .put(StructuredName.SUFFIX, "DoNotEmitSuffix1")
                .put(StructuredName.DISPLAY_NAME, "DoNotEmitDisplayName1");

        // With "IS_PRIMARY=1". This is what we should use.
        entry.addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "AppropriateFamilyName")
                .put(StructuredName.GIVEN_NAME, "AppropriateGivenName")
                .put(StructuredName.MIDDLE_NAME, "AppropriateMiddleName")
                .put(StructuredName.PREFIX, "AppropriatePrefix")
                .put(StructuredName.SUFFIX, "AppropriateSuffix")
                .put(StructuredName.DISPLAY_NAME, "AppropriateDisplayName")
                .put(StructuredName.IS_PRIMARY, 1);

        // With "IS_PRIMARY=1", but we should ignore this time, since this is second, not first.
        // vCard 2.1 does not specify anything about the number of N properties. We choose not
        // emitting this property.
        // vCard 3.0 does (There must be one N property)
        // vCard 4.0 (rev13) does (cardinality (0, 1)).
        entry.addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "DoNotEmitFamilyName2")
                .put(StructuredName.GIVEN_NAME, "DoNotEmitGivenName2")
                .put(StructuredName.MIDDLE_NAME, "DoNotEmitMiddleName2")
                .put(StructuredName.PREFIX, "DoNotEmitPrefix2")
                .put(StructuredName.SUFFIX, "DoNotEmitSuffix2")
                .put(StructuredName.DISPLAY_NAME, "DoNotEmitDisplayName2")
                .put(StructuredName.IS_PRIMARY, 1);

       mVerifier.addPropertyNodesVerifierElem()
                .addExpectedNodeWithOrder("N",
                        "AppropriateFamilyName;AppropriateGivenName;AppropriateMiddleName;"
                        + "AppropriatePrefix;AppropriateSuffix",
                        Arrays.asList("AppropriateFamilyName", "AppropriateGivenName",
                                "AppropriateMiddleName", "AppropriatePrefix", "AppropriateSuffix"))
                .addExpectedNodeWithOrder("FN", "AppropriateDisplayName");
    }

    public void testStructuredNameUsePrimaryV21() {
        testStructuredNameUsePrimaryCommon(V21);
    }

    public void testStructuredNameUsePrimaryV30() {
        testStructuredNameUsePrimaryCommon(V30);
    }

    public void testStructuredNameUsePrimaryV40() {
        testStructuredNameUsePrimaryCommon(V40);
    }

    /**
     * Tests that only "super primary" StructuredName is emitted.
     * See also the comment in {@link #testStructuredNameUsePrimaryCommon(int)}.
     */
    private void testStructuredNameUseSuperPrimaryCommon(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        final ContactEntry entry = mVerifier.addInputEntry();
        entry.addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "DoNotEmitFamilyName1")
                .put(StructuredName.GIVEN_NAME, "DoNotEmitGivenName1")
                .put(StructuredName.MIDDLE_NAME, "DoNotEmitMiddleName1")
                .put(StructuredName.PREFIX, "DoNotEmitPrefix1")
                .put(StructuredName.SUFFIX, "DoNotEmitSuffix1")
                .put(StructuredName.DISPLAY_NAME, "DoNotEmitDisplay1");

        // With "IS_PRIMARY=1", but we should ignore this time.
        entry.addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "DoNotEmitFamilyName2")
                .put(StructuredName.GIVEN_NAME, "DoNotEmitGivenName2")
                .put(StructuredName.MIDDLE_NAME, "DoNotEmitMiddleName2")
                .put(StructuredName.PREFIX, "DoNotEmitPrefix2")
                .put(StructuredName.SUFFIX, "DoNotEmitSuffix2")
                .put(StructuredName.DISPLAY_NAME, "DoNotEmitDisplay2")
                .put(StructuredName.IS_PRIMARY, 1);

        // With "IS_SUPER_PRIMARY=1". This is what we should use.
        entry.addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "AppropriateFamilyName")
                .put(StructuredName.GIVEN_NAME, "AppropriateGivenName")
                .put(StructuredName.MIDDLE_NAME, "AppropriateMiddleName")
                .put(StructuredName.PREFIX, "AppropriatePrefix")
                .put(StructuredName.SUFFIX, "AppropriateSuffix")
                .put(StructuredName.DISPLAY_NAME, "AppropriateDisplayName")
                .put(StructuredName.IS_SUPER_PRIMARY, 1);

        entry.addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "DoNotEmitFamilyName3")
                .put(StructuredName.GIVEN_NAME, "DoNotEmitGivenName3")
                .put(StructuredName.MIDDLE_NAME, "DoNotEmitMiddleName3")
                .put(StructuredName.PREFIX, "DoNotEmitPrefix3")
                .put(StructuredName.SUFFIX, "DoNotEmitSuffix3")
                .put(StructuredName.DISPLAY_NAME, "DoNotEmitDisplay3")
                .put(StructuredName.IS_PRIMARY, 1);

        final PropertyNodesVerifierElem elem = mVerifier.addPropertyNodesVerifierElem();
        elem.addExpectedNodeWithOrder("N",
                "AppropriateFamilyName;AppropriateGivenName;AppropriateMiddleName;"
                + "AppropriatePrefix;AppropriateSuffix",
                Arrays.asList("AppropriateFamilyName", "AppropriateGivenName",
                        "AppropriateMiddleName", "AppropriatePrefix", "AppropriateSuffix"));

        elem.addExpectedNodeWithOrder("FN", "AppropriateDisplayName");
    }

    public void testStructuredNameUseSuperPrimaryV21() {
        testStructuredNameUseSuperPrimaryCommon(V21);
    }

    public void testStructuredNameUseSuperPrimaryV30() {
        testStructuredNameUseSuperPrimaryCommon(V30);
    }

    public void testStructuredNameUseSuperPrimaryV40() {
        testStructuredNameUseSuperPrimaryCommon(V40);
    }

    /**
     * Tests phonetic names field are handled correctly.
     *
     * vCard 2.1 does not have any field corresponding to them.
     * vCard 3.0 has SORT-STRING property, which does not support multiple values inside it.
     * vCard 4.0 (rev13) has SORT-AS parameter, which has three values (family, given, middle)
     * inside it.
     */
    private void testStructuredNamePhoneticNameCommon(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        final ContactEntry entry = mVerifier.addInputEntry();
        entry.addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "AppropriateFamilyName")
                .put(StructuredName.GIVEN_NAME, "AppropriateGivenName")
                .put(StructuredName.MIDDLE_NAME, "AppropriateMiddleName")
                .put(StructuredName.PREFIX, "AppropriatePrefix")
                .put(StructuredName.SUFFIX, "AppropriateSuffix")
                .put(StructuredName.DISPLAY_NAME, "AppropriateDisplayName")
                .put(StructuredName.PHONETIC_FAMILY_NAME, "AppropriatePhoneticFamily")
                .put(StructuredName.PHONETIC_GIVEN_NAME, "AppropriatePhoneticGiven")
                .put(StructuredName.PHONETIC_MIDDLE_NAME, "AppropriatePhoneticMiddle");

        final PropertyNodesVerifierElem elem = mVerifier.addPropertyNodesVerifierElem();
        if (VCardConfig.isVersion40(vcardType)) {
            final ContentValues contentValues = new ContentValues();
            contentValues.put("SORT-AS",
                    "AppropriateFamilyName;AppropriateGivenName;AppropriateMiddleName");
            // vCard 4.0 (rev13) now uses SORT-AS parameter, which is not compatible with
            // either 2.1 nor 3.0.
            elem.addExpectedNodeWithOrder("N",
                    "AppropriateFamilyName;AppropriateGivenName;AppropriateMiddleName;"
                    + "AppropriatePrefix;AppropriateSuffix",
                    Arrays.asList("AppropriateFamilyName", "AppropriateGivenName",
                            "AppropriateMiddleName", "AppropriatePrefix", "AppropriateSuffix"),
                    contentValues);
        } else {
            elem.addExpectedNodeWithOrder("N",
                    "AppropriateFamilyName;AppropriateGivenName;AppropriateMiddleName;"
                    + "AppropriatePrefix;AppropriateSuffix",
                    Arrays.asList("AppropriateFamilyName", "AppropriateGivenName",
                            "AppropriateMiddleName", "AppropriatePrefix", "AppropriateSuffix"));
            if (VCardConfig.isVersion30(vcardType)) {
                elem.addExpectedNode("SORT-STRING",
                        "AppropriatePhoneticGiven AppropriatePhoneticMiddle"
                        + " AppropriatePhoneticFamily");
            }
        }

        elem.addExpectedNodeWithOrder("FN", "AppropriateDisplayName")
            .addExpectedNode("X-PHONETIC-FIRST-NAME", "AppropriatePhoneticGiven")
            .addExpectedNode("X-PHONETIC-MIDDLE-NAME", "AppropriatePhoneticMiddle")
            .addExpectedNode("X-PHONETIC-LAST-NAME", "AppropriatePhoneticFamily");
    }

    public void testStructuredNamePhoneticNameV21() {
        testStructuredNamePhoneticNameCommon(V21);
    }

    public void testStructuredNamePhoneticNameV30() {
        testStructuredNamePhoneticNameCommon(V30);
    }

    public void testStructuredNamePhoneticNameV40() {
        testStructuredNamePhoneticNameCommon(V40);
    }

    // TODO: need to add test cases confirming escaping, empty values, etc.

    /**
     * Confirms all the other sides of the handling is correctly interpreted at one time.
     *
     * A kind of regression test for StructuredName handling.
     */
    private void testStructuredNameComplicatedCommon(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        final ContactEntry entry = mVerifier.addInputEntry();
        entry.addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "DoNotEmitFamilyName1")
                .put(StructuredName.GIVEN_NAME, "DoNotEmitGivenName1")
                .put(StructuredName.MIDDLE_NAME, "DoNotEmitMiddleName1")
                .put(StructuredName.PREFIX, "DoNotEmitPrefix1")
                .put(StructuredName.SUFFIX, "DoNotEmitSuffix1")
                .put(StructuredName.PHONETIC_FAMILY_NAME, "DoNotEmitPhoneticFamily1")
                .put(StructuredName.PHONETIC_GIVEN_NAME, "DoNotEmitPhoneticGiven1")
                .put(StructuredName.PHONETIC_MIDDLE_NAME, "DoNotEmitPhoneticMiddle1");

        // With "IS_PRIMARY=1", but we should ignore this time.
        entry.addContentValues(StructuredName.CONTENT_ITEM_TYPE)
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
        entry.addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "AppropriateFamilyName")
                .put(StructuredName.GIVEN_NAME, "AppropriateGivenName")
                .put(StructuredName.MIDDLE_NAME, "AppropriateMiddleName")
                .put(StructuredName.PREFIX, "AppropriatePrefix")
                .put(StructuredName.SUFFIX, "AppropriateSuffix")
                .put(StructuredName.PHONETIC_FAMILY_NAME, "AppropriatePhoneticFamily")
                .put(StructuredName.PHONETIC_GIVEN_NAME, "AppropriatePhoneticGiven")
                .put(StructuredName.PHONETIC_MIDDLE_NAME, "AppropriatePhoneticMiddle")
                .put(StructuredName.IS_SUPER_PRIMARY, 1);

        entry.addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "DoNotEmitFamilyName3")
                .put(StructuredName.GIVEN_NAME, "DoNotEmitGivenName3")
                .put(StructuredName.MIDDLE_NAME, "DoNotEmitMiddleName3")
                .put(StructuredName.PREFIX, "DoNotEmitPrefix3")
                .put(StructuredName.SUFFIX, "DoNotEmitSuffix3")
                .put(StructuredName.PHONETIC_FAMILY_NAME, "DoNotEmitPhoneticFamily3")
                .put(StructuredName.PHONETIC_GIVEN_NAME, "DoNotEmitPhoneticGiven3")
                .put(StructuredName.PHONETIC_MIDDLE_NAME, "DoNotEmitPhoneticMiddle3")
                .put(StructuredName.IS_PRIMARY, 1);

        final PropertyNodesVerifierElem elem = mVerifier.addPropertyNodesVerifierElem();
        if (VCardConfig.isVersion40(vcardType)) {
            final ContentValues contentValues = new ContentValues();
            contentValues.put("SORT-AS",
                    "AppropriateFamilyName;AppropriateGivenName;AppropriateMiddleName");
            // vCard 4.0 (rev13) now uses SORT-AS parameter, which is not compatible with
            // either 2.1 nor 3.0.
            elem.addExpectedNodeWithOrder("N",
                    "AppropriateFamilyName;AppropriateGivenName;AppropriateMiddleName;"
                    + "AppropriatePrefix;AppropriateSuffix",
                    Arrays.asList("AppropriateFamilyName", "AppropriateGivenName",
                            "AppropriateMiddleName", "AppropriatePrefix", "AppropriateSuffix"),
                    contentValues);
        } else {
            elem.addExpectedNodeWithOrder("N",
                    "AppropriateFamilyName;AppropriateGivenName;AppropriateMiddleName;"
                    + "AppropriatePrefix;AppropriateSuffix",
                    Arrays.asList("AppropriateFamilyName", "AppropriateGivenName",
                            "AppropriateMiddleName", "AppropriatePrefix", "AppropriateSuffix"));
            if (VCardConfig.isVersion30(vcardType)) {
                elem.addExpectedNode("SORT-STRING",
                        "AppropriatePhoneticGiven AppropriatePhoneticMiddle"
                        + " AppropriatePhoneticFamily");
            }
        }

        elem.addExpectedNodeWithOrder("FN",
                "AppropriatePrefix AppropriateGivenName "
                + "AppropriateMiddleName AppropriateFamilyName AppropriateSuffix")
            .addExpectedNode("X-PHONETIC-FIRST-NAME", "AppropriatePhoneticGiven")
            .addExpectedNode("X-PHONETIC-MIDDLE-NAME", "AppropriatePhoneticMiddle")
            .addExpectedNode("X-PHONETIC-LAST-NAME", "AppropriatePhoneticFamily");
    }

    public void testStructuredNameComplicatedV21() {
        testStructuredNameComplicatedCommon(V21);
    }

    public void testStructuredNameComplicatedV30() {
        testStructuredNameComplicatedCommon(V30);
    }

    public void testStructuredNameComplicatedV40() {
        testStructuredNameComplicatedCommon(V40);
    }

    public void testNickNameV30() {
        mVerifier.initForExportTest(V30);
        mVerifier.addInputEntry().addContentValues(Nickname.CONTENT_ITEM_TYPE)
                .put(Nickname.NAME, "Nicky");

        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
            .addExpectedNodeWithOrder("NICKNAME", "Nicky");
    }

    public void testNickNameV40() {
        mVerifier.initForExportTest(V40);
        mVerifier.addInputEntry().addContentValues(Nickname.CONTENT_ITEM_TYPE)
                .put(Nickname.NAME, "Nicky");

        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
            .addExpectedNodeWithOrder("NICKNAME", "Nicky");
    }

    private void testPhoneBasicCommon(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        mVerifier.addInputEntry().addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "1")
                .put(Phone.TYPE, Phone.TYPE_HOME);
        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
                .addExpectedNode("TEL", "1", new TypeSet("HOME"));
    }

    public void testPhoneBasicV21() {
        testPhoneBasicCommon(V21);
    }

    public void testPhoneBasicV30() {
        testPhoneBasicCommon(V30);
    }

    public void testPhoneBasicV40() {
        testPhoneBasicCommon(V40);
    }

    public void testPhoneRefrainFormatting() {
        mVerifier.initForExportTest(V21 | VCardConfig.FLAG_REFRAIN_PHONE_NUMBER_FORMATTING);
        mVerifier.addInputEntry().addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "1234567890(abcdefghijklmnopqrstuvwxyz)")
                .put(Phone.TYPE, Phone.TYPE_HOME);
        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
                .addExpectedNode("TEL", "1234567890(abcdefghijklmnopqrstuvwxyz)",
                        new TypeSet("HOME"));
    }

    /**
     * Tests that vCard composer emits corresponding type param which we expect.
     */
    private void testPhoneVariousTypeSupport(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        ContactEntry entry = mVerifier.addInputEntry();
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "10")
                .put(Phone.TYPE, Phone.TYPE_HOME);
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "20")
                .put(Phone.TYPE, Phone.TYPE_WORK);
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "30")
                .put(Phone.TYPE, Phone.TYPE_FAX_HOME);
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "40")
                .put(Phone.TYPE, Phone.TYPE_FAX_WORK);
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "50")
                .put(Phone.TYPE, Phone.TYPE_MOBILE);
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "60")
                .put(Phone.TYPE, Phone.TYPE_PAGER);
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "70")
                .put(Phone.TYPE, Phone.TYPE_OTHER);
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "80")
                .put(Phone.TYPE, Phone.TYPE_CAR);
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "90")
                .put(Phone.TYPE, Phone.TYPE_COMPANY_MAIN);
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "100")
                .put(Phone.TYPE, Phone.TYPE_ISDN);
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "110")
                .put(Phone.TYPE, Phone.TYPE_MAIN);
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "120")
                .put(Phone.TYPE, Phone.TYPE_OTHER_FAX);
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "130")
                .put(Phone.TYPE, Phone.TYPE_TELEX);
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "140")
                .put(Phone.TYPE, Phone.TYPE_WORK_MOBILE);
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "150")
                .put(Phone.TYPE, Phone.TYPE_WORK_PAGER);
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "160")
                .put(Phone.TYPE, Phone.TYPE_MMS);

        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
                .addExpectedNode("TEL", "10", new TypeSet("HOME"))
                .addExpectedNode("TEL", "20", new TypeSet("WORK"))
                .addExpectedNode("TEL", "30", new TypeSet("HOME", "FAX"))
                .addExpectedNode("TEL", "40", new TypeSet("WORK", "FAX"))
                .addExpectedNode("TEL", "50", new TypeSet("CELL"))
                .addExpectedNode("TEL", "60", new TypeSet("PAGER"))
                .addExpectedNode("TEL", "70", new TypeSet("VOICE"))
                .addExpectedNode("TEL", "80", new TypeSet("CAR"))
                .addExpectedNode("TEL", "90", new TypeSet("WORK", "PREF"))
                .addExpectedNode("TEL", "100", new TypeSet("ISDN"))
                .addExpectedNode("TEL", "110", new TypeSet("PREF"))
                .addExpectedNode("TEL", "120", new TypeSet("FAX"))
                .addExpectedNode("TEL", "130", new TypeSet("TLX"))
                .addExpectedNode("TEL", "140", new TypeSet("WORK", "CELL"))
                .addExpectedNode("TEL", "150", new TypeSet("WORK", "PAGER"))
                .addExpectedNode("TEL", "160", new TypeSet("MSG"));
    }

    public void testPhoneVariousTypeSupportV21() {
        testPhoneVariousTypeSupport(V21);
    }

    public void testPhoneVariousTypeSupportV30() {
        testPhoneVariousTypeSupport(V30);
    }

    public void testPhoneVariousTypeSupportV40() {
        testPhoneVariousTypeSupport(V40);
    }

    /**
     * Tests that "PREF"s are emitted appropriately.
     */
    private void testPhonePrefHandlingCommon(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        ContactEntry entry = mVerifier.addInputEntry();
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "1")
                .put(Phone.TYPE, Phone.TYPE_HOME);
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "2")
                .put(Phone.TYPE, Phone.TYPE_WORK)
                .put(Phone.IS_PRIMARY, 1);
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "3")
                .put(Phone.TYPE, Phone.TYPE_FAX_HOME)
                .put(Phone.IS_PRIMARY, 1);
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "4")
                .put(Phone.TYPE, Phone.TYPE_FAX_WORK);

        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
                .addExpectedNode("TEL", "4", new TypeSet("WORK", "FAX"))
                .addExpectedNode("TEL", "3", new TypeSet("HOME", "FAX", "PREF"))
                .addExpectedNode("TEL", "2", new TypeSet("WORK", "PREF"))
                .addExpectedNode("TEL", "1", new TypeSet("HOME"));
    }

    public void testPhonePrefHandlingV21() {
        testPhonePrefHandlingCommon(V21);
    }

    public void testPhonePrefHandlingV30() {
        testPhonePrefHandlingCommon(V30);
    }

    public void testPhonePrefHandlingV40() {
        testPhonePrefHandlingCommon(V40);
    }

    private void testMiscPhoneTypeHandling(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        ContactEntry entry = mVerifier.addInputEntry();
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "1")
                .put(Phone.TYPE, Phone.TYPE_CUSTOM)
                .put(Phone.LABEL, "Modem");
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "2")
                .put(Phone.TYPE, Phone.TYPE_CUSTOM)
                .put(Phone.LABEL, "MSG");
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "3")
                .put(Phone.TYPE, Phone.TYPE_CUSTOM)
                .put(Phone.LABEL, "BBS");
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "4")
                .put(Phone.TYPE, Phone.TYPE_CUSTOM)
                .put(Phone.LABEL, "VIDEO");
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "5")
                .put(Phone.TYPE, Phone.TYPE_CUSTOM);
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "6")
                .put(Phone.TYPE, Phone.TYPE_CUSTOM)
                .put(Phone.LABEL, "_AUTO_CELL");  // The old indicator for the type mobile.
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "7")
                .put(Phone.TYPE, Phone.TYPE_CUSTOM)
                .put(Phone.LABEL, "\u643A\u5E2F");  // Mobile phone in Japanese Kanji
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.NUMBER, "8")
                .put(Phone.TYPE, Phone.TYPE_CUSTOM)
                .put(Phone.LABEL, "invalid");
        PropertyNodesVerifierElem elem = mVerifier.addPropertyNodesVerifierElemWithEmptyName();
        if (VCardConfig.isVersion30(vcardType) || VCardConfig.isVersion40(vcardType)) {
            // vCard 3.0 accepts "invalid". Also stop using toUpper()
            elem.addExpectedNode("TEL", "1", new TypeSet("Modem"))
                    .addExpectedNode("TEL", "2", new TypeSet("MSG"))
                    .addExpectedNode("TEL", "3", new TypeSet("BBS"))
                    .addExpectedNode("TEL", "4", new TypeSet("VIDEO"))
                    .addExpectedNode("TEL", "5", new TypeSet("VOICE"))
                    .addExpectedNode("TEL", "6", new TypeSet("CELL"))
                    .addExpectedNode("TEL", "7", new TypeSet("CELL"))
                    .addExpectedNode("TEL", "8", new TypeSet("invalid"));
        } else {
            elem.addExpectedNode("TEL", "1", new TypeSet("MODEM"))
                    .addExpectedNode("TEL", "2", new TypeSet("MSG"))
                    .addExpectedNode("TEL", "3", new TypeSet("BBS"))
                    .addExpectedNode("TEL", "4", new TypeSet("VIDEO"))
                    .addExpectedNode("TEL", "5", new TypeSet("VOICE"))
                    .addExpectedNode("TEL", "6", new TypeSet("CELL"))
                    .addExpectedNode("TEL", "7", new TypeSet("CELL"))
                    .addExpectedNode("TEL", "8", new TypeSet("X-invalid"));
        }
    }

    public void testPhoneTypeHandlingV21() {
        testMiscPhoneTypeHandling(V21);
    }

    public void testPhoneTypeHandlingV30() {
        testMiscPhoneTypeHandling(V30);
    }

    public void testPhoneTypeHandlingV40() {
        testMiscPhoneTypeHandling(V40);
    }

    private void testEmailBasicCommon(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        mVerifier.addInputEntry().addContentValues(Email.CONTENT_ITEM_TYPE)
                .put(Email.DATA, "sample@example.com");
        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
            .addExpectedNode("EMAIL", "sample@example.com");
    }

    public void testEmailBasicV21() {
        testEmailBasicCommon(V21);
    }

    public void testEmailBasicV30() {
        testEmailBasicCommon(V30);
    }

    public void testEmailBasicV40() {
        testEmailBasicCommon(V40);
    }

    private void testEmailVariousTypeSupportCommon(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        ContactEntry entry = mVerifier.addInputEntry();
        entry.addContentValues(Email.CONTENT_ITEM_TYPE)
                .put(Email.DATA, "type_home@example.com")
                .put(Email.TYPE, Email.TYPE_HOME);
        entry.addContentValues(Email.CONTENT_ITEM_TYPE)
                .put(Email.DATA, "type_work@example.com")
                .put(Email.TYPE, Email.TYPE_WORK);
        entry.addContentValues(Email.CONTENT_ITEM_TYPE)
                .put(Email.DATA, "type_mobile@example.com")
                .put(Email.TYPE, Email.TYPE_MOBILE);
        entry.addContentValues(Email.CONTENT_ITEM_TYPE)
                .put(Email.DATA, "type_other@example.com")
                .put(Email.TYPE, Email.TYPE_OTHER);
        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
                .addExpectedNode("EMAIL", "type_home@example.com", new TypeSet("HOME"))
                .addExpectedNode("EMAIL", "type_work@example.com", new TypeSet("WORK"))
                .addExpectedNode("EMAIL", "type_mobile@example.com", new TypeSet("CELL"))
                .addExpectedNode("EMAIL", "type_other@example.com");
    }

    public void testEmailVariousTypeSupportV21() {
        testEmailVariousTypeSupportCommon(V21);
    }

    public void testEmailVariousTypeSupportV30() {
        testEmailVariousTypeSupportCommon(V30);
    }

    public void testEmailVariousTypeSupportV40() {
        testEmailVariousTypeSupportCommon(V40);
    }

    private void testEmailPrefHandlingCommon(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        ContactEntry entry = mVerifier.addInputEntry();
        entry.addContentValues(Email.CONTENT_ITEM_TYPE)
                .put(Email.DATA, "type_home@example.com")
                .put(Email.TYPE, Email.TYPE_HOME)
                .put(Email.IS_PRIMARY, 1);
        entry.addContentValues(Email.CONTENT_ITEM_TYPE)
                .put(Email.DATA, "type_notype@example.com")
                .put(Email.IS_PRIMARY, 1);

        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
                .addExpectedNode("EMAIL", "type_notype@example.com", new TypeSet("PREF"))
                .addExpectedNode("EMAIL", "type_home@example.com", new TypeSet("HOME", "PREF"));
    }

    public void testEmailPrefHandlingV21() {
        testEmailPrefHandlingCommon(V21);
    }

    public void testEmailPrefHandlingV30() {
        testEmailPrefHandlingCommon(V30);
    }

    public void testEmailPrefHandlingV40() {
        testEmailPrefHandlingCommon(V40);
    }

    private void testPostalAddressCommon(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        mVerifier.addInputEntry().addContentValues(StructuredPostal.CONTENT_ITEM_TYPE)
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
        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
                .addExpectedNode("ADR",
                        Arrays.asList("Pobox", "", "Street", "City Neighborhood",
                                "Region", "100", "Country"), new TypeSet("WORK"));
    }

    public void testPostalAddressV21() {
        testPostalAddressCommon(V21);
    }

    public void testPostalAddressV30() {
        testPostalAddressCommon(V30);
    }

    public void testPostalAddressV40() {
        testPostalAddressCommon(V40);
    }

    private void testPostalAddressNonNeighborhood(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        mVerifier.addInputEntry().addContentValues(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.CITY, "City");
        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
                .addExpectedNode("ADR",
                        Arrays.asList("", "", "", "City", "", "", ""), new TypeSet("HOME"));
    }

    public void testPostalAddressNonNeighborhoodV21() {
        testPostalAddressNonNeighborhood(V21);
    }

    public void testPostalAddressNonNeighborhoodV30() {
        testPostalAddressNonNeighborhood(V30);
    }

    public void testPostalAddressNonNeighborhoodV40() {
        testPostalAddressNonNeighborhood(V40);
    }

    private void testPostalAddressNonCity(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        mVerifier.addInputEntry().addContentValues(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.NEIGHBORHOOD, "Neighborhood");
        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
                .addExpectedNode("ADR",
                        Arrays.asList("", "", "", "Neighborhood", "", "", ""), new TypeSet("HOME"));
    }

    public void testPostalAddressNonCityV21() {
        testPostalAddressNonCity(V21);
    }

    public void testPostalAddressNonCityV30() {
        testPostalAddressNonCity(V30);
    }

    public void testPostalAddressNonCityV40() {
        testPostalAddressNonCity(V40);
    }

    private void testPostalOnlyWithFormattedAddressCommon(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        mVerifier.addInputEntry().addContentValues(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.REGION, "")  // Must be ignored.
                .put(StructuredPostal.FORMATTED_ADDRESS,
                "Formatted address CA 123-334 United Statue");
        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
                .addExpectedNodeWithOrder("ADR", ";Formatted address CA 123-334 United Statue;;;;;",
                        Arrays.asList("", "Formatted address CA 123-334 United Statue",
                                "", "", "", "", ""), new TypeSet("HOME"));
    }

    public void testPostalOnlyWithFormattedAddressV21() {
        testPostalOnlyWithFormattedAddressCommon(V21);
    }

    public void testPostalOnlyWithFormattedAddressV30() {
        testPostalOnlyWithFormattedAddressCommon(V30);
    }

    public void testPostalOnlyWithFormattedAddressV40() {
        testPostalOnlyWithFormattedAddressCommon(V40);
    }

    /**
     * Tests that the vCard composer honors formatted data when it is available
     * even when it is partial.
     */
    private void testPostalWithBothStructuredAndFormattedCommon(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        mVerifier.addInputEntry().addContentValues(StructuredPostal.CONTENT_ITEM_TYPE)
                .put(StructuredPostal.POBOX, "Pobox")
                .put(StructuredPostal.COUNTRY, "Country")
                .put(StructuredPostal.FORMATTED_ADDRESS,
                        "Formatted address CA 123-334 United Statue");  // Should be ignored
        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
                .addExpectedNode("ADR", "Pobox;;;;;;Country",
                        Arrays.asList("Pobox", "", "", "", "", "", "Country"),
                        new TypeSet("HOME"));
    }

    public void testPostalWithBothStructuredAndFormattedV21() {
        testPostalWithBothStructuredAndFormattedCommon(V21);
    }

    public void testPostalWithBothStructuredAndFormattedV30() {
        testPostalWithBothStructuredAndFormattedCommon(V30);
    }

    public void testPostalWithBothStructuredAndFormattedV40() {
        testPostalWithBothStructuredAndFormattedCommon(V40);
    }

    private void testOrganizationCommon(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        ContactEntry entry = mVerifier.addInputEntry();
        entry.addContentValues(Organization.CONTENT_ITEM_TYPE)
                .put(Organization.COMPANY, "CompanyX")
                .put(Organization.DEPARTMENT, "DepartmentY")
                .put(Organization.TITLE, "TitleZ")
                .put(Organization.JOB_DESCRIPTION, "Description Rambda")  // Ignored.
                .put(Organization.OFFICE_LOCATION, "Mountain View")  // Ignored.
                .put(Organization.PHONETIC_NAME, "PhoneticName!")  // Ignored
                .put(Organization.SYMBOL, "(^o^)/~~");  // Ignore him (her).
        entry.addContentValues(Organization.CONTENT_ITEM_TYPE)
                .putNull(Organization.COMPANY)
                .put(Organization.DEPARTMENT, "DepartmentXX")
                .putNull(Organization.TITLE);
        entry.addContentValues(Organization.CONTENT_ITEM_TYPE)
                .put(Organization.COMPANY, "CompanyXYZ")
                .putNull(Organization.DEPARTMENT)
                .put(Organization.TITLE, "TitleXYZYX");
        // Currently we do not use group but depend on the order.
        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
                .addExpectedNodeWithOrder("ORG", "CompanyX;DepartmentY",
                        Arrays.asList("CompanyX", "DepartmentY"))
                .addExpectedNodeWithOrder("TITLE", "TitleZ")
                .addExpectedNodeWithOrder("ORG", "DepartmentXX")
                .addExpectedNodeWithOrder("ORG", "CompanyXYZ")
                .addExpectedNodeWithOrder("TITLE", "TitleXYZYX");
    }

    public void testOrganizationV21() {
        testOrganizationCommon(V21);
    }

    public void testOrganizationV30() {
        testOrganizationCommon(V30);
    }

    public void testOrganizationV40() {
        testOrganizationCommon(V40);
    }

    private void testImVariousTypeSupportCommon(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        ContactEntry entry = mVerifier.addInputEntry();
        entry.addContentValues(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_AIM)
                .put(Im.DATA, "aim");
        entry.addContentValues(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_MSN)
                .put(Im.DATA, "msn");
        entry.addContentValues(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_YAHOO)
                .put(Im.DATA, "yahoo");
        entry.addContentValues(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_SKYPE)
                .put(Im.DATA, "skype");
        entry.addContentValues(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_QQ)
                .put(Im.DATA, "qq");
        entry.addContentValues(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_GOOGLE_TALK)
                .put(Im.DATA, "google talk");
        entry.addContentValues(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_ICQ)
                .put(Im.DATA, "icq");
        entry.addContentValues(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_JABBER)
                .put(Im.DATA, "jabber");
        entry.addContentValues(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_NETMEETING)
                .put(Im.DATA, "netmeeting");

        // No determined way to express unknown type...
        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
                .addExpectedNode("X-JABBER", "jabber")
                .addExpectedNode("X-ICQ", "icq")
                .addExpectedNode("X-GOOGLE-TALK", "google talk")
                .addExpectedNode("X-QQ", "qq")
                .addExpectedNode("X-SKYPE-USERNAME", "skype")
                .addExpectedNode("X-YAHOO", "yahoo")
                .addExpectedNode("X-MSN", "msn")
                .addExpectedNode("X-NETMEETING", "netmeeting")
                .addExpectedNode("X-AIM", "aim");
    }

    public void testImBasiV21() {
        testImVariousTypeSupportCommon(V21);
    }

    public void testImBasicV30() {
        testImVariousTypeSupportCommon(V30);
    }

    public void testImBasicV40() {
        testImVariousTypeSupportCommon(V40);
    }

    private void testImPrefHandlingCommon(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        ContactEntry entry = mVerifier.addInputEntry();
        entry.addContentValues(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_AIM)
                .put(Im.DATA, "aim1");
        entry.addContentValues(Im.CONTENT_ITEM_TYPE)
                .put(Im.PROTOCOL, Im.PROTOCOL_AIM)
                .put(Im.DATA, "aim2")
                .put(Im.TYPE, Im.TYPE_HOME)
                .put(Im.IS_PRIMARY, 1);

        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
                .addExpectedNode("X-AIM", "aim1")
                .addExpectedNode("X-AIM", "aim2", new TypeSet("HOME", "PREF"));
    }

    public void testImPrefHandlingV21() {
        testImPrefHandlingCommon(V21);
    }

    public void testImPrefHandlingV30() {
        testImPrefHandlingCommon(V30);
    }

    public void testImPrefHandlingV40() {
        testImPrefHandlingCommon(V40);
    }

    private void testWebsiteCommon(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        ContactEntry entry = mVerifier.addInputEntry();
        entry.addContentValues(Website.CONTENT_ITEM_TYPE)
                .put(Website.URL, "http://website.example.android.com/index.html")
                .put(Website.TYPE, Website.TYPE_BLOG);
        entry.addContentValues(Website.CONTENT_ITEM_TYPE)
                .put(Website.URL, "ftp://ftp.example.android.com/index.html")
                .put(Website.TYPE, Website.TYPE_FTP);

        // We drop TYPE information since vCard (especially 3.0) does not allow us to emit it.
        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
                .addExpectedNode("URL", "ftp://ftp.example.android.com/index.html")
                .addExpectedNode("URL", "http://website.example.android.com/index.html");
    }

    public void testWebsiteV21() {
        testWebsiteCommon(V21);
    }

    public void testWebsiteV30() {
        testWebsiteCommon(V30);
    }

    public void testWebsiteV40() {
        testWebsiteCommon(V40);
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
        mVerifier.initForExportTest(vcardType);
        ContactEntry entry = mVerifier.addInputEntry();
        entry.addContentValues(Event.CONTENT_ITEM_TYPE)
                .put(Event.TYPE, Event.TYPE_ANNIVERSARY)
                .put(Event.START_DATE, "1982-06-16");
        entry.addContentValues(Event.CONTENT_ITEM_TYPE)
                .put(Event.TYPE, Event.TYPE_BIRTHDAY)
                .put(Event.START_DATE, "2008-10-22");
        entry.addContentValues(Event.CONTENT_ITEM_TYPE)
                .put(Event.TYPE, Event.TYPE_OTHER)
                .put(Event.START_DATE, "2018-03-12");
        entry.addContentValues(Event.CONTENT_ITEM_TYPE)
                .put(Event.TYPE, Event.TYPE_CUSTOM)
                .put(Event.LABEL, "The last day")
                .put(Event.START_DATE, "When the Tower of Hanoi with 64 rings is completed.");
        entry.addContentValues(Event.CONTENT_ITEM_TYPE)
                .put(Event.TYPE, Event.TYPE_BIRTHDAY)
                .put(Event.START_DATE, "2009-05-19");  // Should be ignored.
        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
                .addExpectedNode("BDAY", "2008-10-22")
                .addExpectedNode("X-ANDROID-CUSTOM",
                        getAndroidPropValue(
                                Event.CONTENT_ITEM_TYPE, "1982-06-16", Event.TYPE_ANNIVERSARY))
                .addExpectedNode("X-ANDROID-CUSTOM",
                        getAndroidPropValue(
                                Event.CONTENT_ITEM_TYPE, "2018-03-12", Event.TYPE_OTHER))
                .addExpectedNode("X-ANDROID-CUSTOM",
                        getAndroidPropValue(
                                Event.CONTENT_ITEM_TYPE,
                                "When the Tower of Hanoi with 64 rings is completed.",
                                Event.TYPE_CUSTOM, "The last day"));
    }

    public void testEventV21() {
        testEventCommon(V21);
    }

    public void testEventV30() {
        testEventCommon(V30);
    }

    public void testEventV40() {
        testEventCommon(V40);
    }

    private void testNoteCommon(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        ContactEntry entry = mVerifier.addInputEntry();
        entry.addContentValues(Note.CONTENT_ITEM_TYPE)
                .put(Note.NOTE, "note1");
        entry.addContentValues(Note.CONTENT_ITEM_TYPE)
                .put(Note.NOTE, "note2")
                .put(Note.IS_PRIMARY, 1);  // Just ignored.
        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
                .addExpectedNodeWithOrder("NOTE", "note1")
                .addExpectedNodeWithOrder("NOTE", "note2");
    }

    public void testNoteV21() {
        testNoteCommon(V21);
    }

    public void testNoteV30() {
        testNoteCommon(V30);
    }

    public void testNoteV40() {
        testNoteCommon(V40);
    }

    private void testPhotoCommon(int vcardType) {
        final boolean useB =
            (VCardConfig.isVersion30(vcardType) || VCardConfig.isVersion40(vcardType));
        mVerifier.initForExportTest(vcardType);
        ContactEntry entry = mVerifier.addInputEntry();
        entry.addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "PhotoTest");
        entry.addContentValues(Photo.CONTENT_ITEM_TYPE)
                .put(Photo.PHOTO, sPhotoByteArray);

        ContentValues contentValuesForPhoto = new ContentValues();
        contentValuesForPhoto.put("ENCODING", (useB ? "b" : "BASE64"));
        mVerifier.addPropertyNodesVerifierElem()
                .addExpectedNode("FN", "PhotoTest")
                .addExpectedNode("N", "PhotoTest;;;;",
                        Arrays.asList("PhotoTest", "", "", "", ""))
                .addExpectedNodeWithOrder("PHOTO", null, null, sPhotoByteArray,
                        contentValuesForPhoto, new TypeSet("JPEG"), null);
    }

    public void testPhotoV21() {
        testPhotoCommon(V21);
    }

    public void testPhotoV30() {
        testPhotoCommon(V30);
    }

    public void testPhotoV40() {
        testPhotoCommon(V40);
    }

    private void testRelationCommon(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        mVerifier.addInputEntry().addContentValues(Relation.CONTENT_ITEM_TYPE)
                .put(Relation.TYPE, Relation.TYPE_MOTHER)
                .put(Relation.NAME, "Ms. Mother");
        mVerifier.addContentValuesVerifierElem().addExpected(Relation.CONTENT_ITEM_TYPE)
                .put(Relation.TYPE, Relation.TYPE_MOTHER)
                .put(Relation.NAME, "Ms. Mother");
    }

    public void testRelationV21() {
        testRelationCommon(V21);
    }

    public void testRelationV30() {
        testRelationCommon(V30);
    }

    public void testV30HandleEscape() {
        mVerifier.initForExportTest(V30);
        mVerifier.addInputEntry().addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "\\")
                .put(StructuredName.GIVEN_NAME, ";")
                .put(StructuredName.MIDDLE_NAME, ",")
                .put(StructuredName.PREFIX, "\n")
                .put(StructuredName.DISPLAY_NAME, "[<{Unescaped:Asciis}>]");
        // Verifies the vCard String correctly escapes each character which must be escaped.
        mVerifier.addLineVerifierElem()
                .addExpected("N:\\\\;\\;;\\,;\\n;")
                .addExpected("FN:[<{Unescaped:Asciis}>]");
        mVerifier.addPropertyNodesVerifierElem()
                .addExpectedNode("FN", "[<{Unescaped:Asciis}>]")
                .addExpectedNode("N", Arrays.asList("\\", ";", ",", "\n", ""));
    }

    /**
     * There's no "NICKNAME" property in vCard 2.1, while there is in vCard 3.0.
     * We use Android-specific "X-ANDROID-CUSTOM" property.
     * This test verifies the functionality.
     */
    public void testNickNameV21() {
        mVerifier.initForExportTest(V21);
        mVerifier.addInputEntry().addContentValues(Nickname.CONTENT_ITEM_TYPE)
                .put(Nickname.NAME, "Nicky");
        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
                .addExpectedNode("X-ANDROID-CUSTOM",
                        Nickname.CONTENT_ITEM_TYPE + ";Nicky;;;;;;;;;;;;;;");
        mVerifier.addContentValuesVerifierElem().addExpected(Nickname.CONTENT_ITEM_TYPE)
                .put(Nickname.NAME, "Nicky");
    }

    public void testTolerateBrokenPhoneNumberEntryV21() {
        mVerifier.initForExportTest(V21);
        ContactEntry entry = mVerifier.addInputEntry();
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.TYPE, Phone.TYPE_HOME)
                .put(Phone.NUMBER, "111-222-3333 (Miami)\n444-5555-666 (Tokyo);"
                        + "777-888-9999 (Chicago);111-222-3333 (Miami)");
        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
                .addExpectedNode("TEL", "111-222-3333", new TypeSet("HOME"))
                .addExpectedNode("TEL", "444-555-5666", new TypeSet("HOME"))
                .addExpectedNode("TEL", "777-888-9999", new TypeSet("HOME"));
    }

    private void testPickUpNonEmptyContentValuesCommon(int vcardType) {
        mVerifier.initForExportTest(vcardType);
        ContactEntry entry = mVerifier.addInputEntry();
        entry.addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.IS_PRIMARY, 1);  // Empty name. Should be ignored.
        entry.addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "family1")  // Not primary. Should be ignored.
                .put(StructuredName.DISPLAY_NAME, "display");
        entry.addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.IS_PRIMARY, 1)
                .put(StructuredName.FAMILY_NAME, "family2")  // This entry is what we want.
                .put(StructuredName.DISPLAY_NAME, "display");
        entry.addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.IS_PRIMARY, 1)
                .put(StructuredName.FAMILY_NAME, "family3")
                .put(StructuredName.DISPLAY_NAME, "display");
        entry.addContentValues(StructuredName.CONTENT_ITEM_TYPE)
                .put(StructuredName.FAMILY_NAME, "family4")
                .put(StructuredName.DISPLAY_NAME, "display");
        mVerifier.addPropertyNodesVerifierElem()
                .addExpectedNode("N", Arrays.asList("family2", "", "", "", ""))
                .addExpectedNode("FN", "display");
    }

    public void testPickUpNonEmptyContentValuesV21() {
        testPickUpNonEmptyContentValuesCommon(V21);
    }

    public void testPickUpNonEmptyContentValuesV30() {
        testPickUpNonEmptyContentValuesCommon(V30);
    }

    public void testPickUpNonEmptyContentValuesV40() {
        testPickUpNonEmptyContentValuesCommon(V40);
    }

    public void testUseMultiByteTypeV30() {
        mVerifier.initForExportTest(V30);
        final ContactEntry entry = mVerifier.addInputEntry();
        entry.addContentValues(Phone.CONTENT_ITEM_TYPE)
                .put(Phone.TYPE, Phone.TYPE_CUSTOM)
                .put(Phone.LABEL, "\u96FB\u8A71")
                .put(Phone.NUMBER, "1");
        mVerifier.addLineVerifierElem()
                .addExpected("N:")
                .addExpected("FN:")
                .addExpected("TEL;TYPE=\u96FB\u8A71:1");
        mVerifier.addPropertyNodesVerifierElemWithEmptyName()
                .addExpectedNode("TEL", "1", new TypeSet("\u96FB\u8A71"));
    }
}
