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

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentValues;
import android.net.Uri;
import android.pim.vcard.EntryCommitter;
import android.pim.vcard.VCardConfig;
import android.pim.vcard.VCardDataBuilder;
import android.pim.vcard.VCardParser;
import android.pim.vcard.VCardParser_V21;
import android.pim.vcard.VCardParser_V30;
import android.pim.vcard.exception.VCardException;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
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
import android.test.AndroidTestCase;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.text.TextUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Map.Entry;

/**
 * BaseClass for vCard unit tests with utility classes.
 * Please do not add each unit test here.
 */
/* package */ class VCardTestsBase extends AndroidTestCase {

    public class ImportVerificationResolver extends MockContentResolver {
        ImportVerificationProvider mVerificationProvider = new ImportVerificationProvider();
        @Override
        public ContentProviderResult[] applyBatch(String authority,
                ArrayList<ContentProviderOperation> operations) {
            equalsString(authority, RawContacts.CONTENT_URI.toString());
            return mVerificationProvider.applyBatch(operations);
        }

        public void addExpectedContentValues(ContentValues expectedContentValues) {
            mVerificationProvider.addExpectedContentValues(expectedContentValues);
        }

        public void verify() {
            mVerificationProvider.verify();
        }
    }

    private static final Set<String> sKnownMimeTypeSet =
        new HashSet<String>(Arrays.asList(StructuredName.CONTENT_ITEM_TYPE,
                Nickname.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE,
                Email.CONTENT_ITEM_TYPE, StructuredPostal.CONTENT_ITEM_TYPE,
                Im.CONTENT_ITEM_TYPE, Organization.CONTENT_ITEM_TYPE,
                Event.CONTENT_ITEM_TYPE, Photo.CONTENT_ITEM_TYPE,
                Note.CONTENT_ITEM_TYPE, Website.CONTENT_ITEM_TYPE,
                Relation.CONTENT_ITEM_TYPE, Event.CONTENT_ITEM_TYPE,
                GroupMembership.CONTENT_ITEM_TYPE));

    public class ImportVerificationProvider extends MockContentProvider {
        final Map<String, Collection<ContentValues>> mMimeTypeToExpectedContentValues;

        public ImportVerificationProvider() {
            mMimeTypeToExpectedContentValues =
                new HashMap<String, Collection<ContentValues>>();
            for (String acceptanbleMimeType : sKnownMimeTypeSet) {
                // Do not use HashSet since the current implementation changes the content of
                // ContentValues after the insertion, which make the result of hashCode()
                // changes...
                mMimeTypeToExpectedContentValues.put(
                        acceptanbleMimeType, new ArrayList<ContentValues>());
            }
        }

        public void addExpectedContentValues(ContentValues expectedContentValues) {
            final String mimeType = expectedContentValues.getAsString(Data.MIMETYPE);
            if (!sKnownMimeTypeSet.contains(mimeType)) {
                fail(String.format(
                        "Unknow MimeType %s in the test code. Test code should be broken.",
                        mimeType));
            }

            final Collection<ContentValues> contentValuesCollection =
                mMimeTypeToExpectedContentValues.get(mimeType);
            contentValuesCollection.add(expectedContentValues);
        }

        @Override
        public ContentProviderResult[] applyBatch(
                ArrayList<ContentProviderOperation> operations) {
            if (operations == null) {
                fail("There is no operation.");
            }

            final int size = operations.size();
            ContentProviderResult[] fakeResultArray = new ContentProviderResult[size];
            for (int i = 0; i < size; i++) {
                Uri uri = Uri.withAppendedPath(RawContacts.CONTENT_URI, String.valueOf(i));
                fakeResultArray[i] = new ContentProviderResult(uri);
            }

            for (int i = 0; i < size; i++) {
                ContentProviderOperation operation = operations.get(i);
                ContentValues actualContentValues = operation.resolveValueBackReferences(
                        fakeResultArray, i);
                final Uri uri = operation.getUri();
                if (uri.equals(RawContacts.CONTENT_URI)) {
                    assertNull(actualContentValues.get(RawContacts.ACCOUNT_NAME));
                    assertNull(actualContentValues.get(RawContacts.ACCOUNT_TYPE));
                } else if (uri.equals(Data.CONTENT_URI)) {
                    final String mimeType = actualContentValues.getAsString(Data.MIMETYPE);
                    if (!sKnownMimeTypeSet.contains(mimeType)) {
                        fail(String.format(
                                "Unknown MimeType %s. Probably added after developing this test",
                                mimeType));
                    }
                    // Remove data meaningless in this unit tests.
                    // Specifically, Data.DATA1 - DATA7 are set to null or empty String
                    // regardless of the input, but it may change depending on how
                    // resolver-related code handles it.
                    // Here, we ignore these implementation-dependent specs and
                    // just check whether vCard importer correctly inserts rellevent data.
                    Set<String> keyToBeRemoved = new HashSet<String>();
                    for (Entry<String, Object> entry : actualContentValues.valueSet()) {
                        Object value = entry.getValue();
                        if (value == null || TextUtils.isEmpty(value.toString())) {
                            keyToBeRemoved.add(entry.getKey());
                        }
                    }
                    for (String key: keyToBeRemoved) {
                        actualContentValues.remove(key);
                    }
                    /* For testing
                    Log.d("@@@",
                            String.format("MimeType: %s, data: %s",
                                    mimeType, actualContentValues.toString()));
                     */
                    // Remove RAW_CONTACT_ID entry just for safety, since we do not care
                    // how resolver-related code handles the entry in this unit test,
                    if (actualContentValues.containsKey(Data.RAW_CONTACT_ID)) {
                        actualContentValues.remove(Data.RAW_CONTACT_ID);
                    }
                    final Collection<ContentValues> contentValuesCollection =
                        mMimeTypeToExpectedContentValues.get(mimeType);
                    if (contentValuesCollection == null) {
                        fail("ContentValues for MimeType " + mimeType
                                + " is not expected at all (" + actualContentValues + ")");
                    }
                    boolean checked = false;
                    for (ContentValues expectedContentValues : contentValuesCollection) {
                        /* For testing
                        Log.d("@@@", "expected: "
                                + convertToEasilyReadableString(expectedContentValues));
                        Log.d("@@@", "actual  : "
                                + convertToEasilyReadableString(actualContentValues));
                         */
                        if (equalsForContentValues(expectedContentValues,
                                actualContentValues)) {
                            assertTrue(contentValuesCollection.remove(expectedContentValues));
                            checked = true;
                            break;
                        }
                    }
                    if (!checked) {
                        final String failMsg =
                            "Unexpected ContentValues for MimeType " + mimeType
                            + ": " + actualContentValues;
                        fail(failMsg);
                    }
                } else {
                    fail("Unexpected Uri has come: " + uri);
                }
            }  // for (int i = 0; i < size; i++) {
            return null;
        }

        public void verify() {
            StringBuilder builder = new StringBuilder();
            for (Collection<ContentValues> contentValuesCollection :
                    mMimeTypeToExpectedContentValues.values()) {
                for (ContentValues expectedContentValues: contentValuesCollection) {
                    builder.append(convertToEasilyReadableString(expectedContentValues));
                    builder.append("\n");
                }
            }
            if (builder.length() > 0) {
                final String failMsg =
                    "There is(are) remaining expected ContentValues instance(s): \n"
                        + builder.toString();
                fail(failMsg);
            }
        }
    }

    public class ContactStructVerifier {
        private final int mResourceId;
        private final int mVCardType;
        private final ImportVerificationResolver mResolver;
        // private final String mCharset;
        public ContactStructVerifier(int resId, int vCardType) {
            mResourceId = resId;
            mVCardType = vCardType;
            mResolver = new ImportVerificationResolver();
        }

        public ContentValues createExpected(String mimeType) {
            ContentValues contentValues = new ContentValues();
            contentValues.put(Data.MIMETYPE, mimeType);
            mResolver.addExpectedContentValues(contentValues);
            return contentValues;
        }

        public void verify() throws IOException, VCardException {
            InputStream is = getContext().getResources().openRawResource(mResourceId);
            final VCardParser vCardParser;
            if (VCardConfig.isV30(mVCardType)) {
                vCardParser = new VCardParser_V30(true);  // use StrictParsing
            } else {
                vCardParser = new VCardParser_V21();
            }
            VCardDataBuilder builder =
                new VCardDataBuilder(null, null, false, mVCardType, null);
            builder.addEntryHandler(new EntryCommitter(mResolver));
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
            mResolver.verify();
        }
    }

    /**
     * Utility method to print ContentValues whose content is printed with sorted keys.
     */
    private static String convertToEasilyReadableString(ContentValues contentValues) {
        if (contentValues == null) {
            return "null";
        }
        String mimeTypeValue = "";
        SortedMap<String, String> sortedMap = new TreeMap<String, String>();
        for (Entry<String, Object> entry : contentValues.valueSet()) {
            final String key = entry.getKey();
            final String value = entry.getValue().toString();
            if (Data.MIMETYPE.equals(key)) {
                mimeTypeValue = value;
            } else {
                assertNotNull(key);
                sortedMap.put(key, (value != null ? value.toString() : ""));
            }
        }
        StringBuilder builder = new StringBuilder();
        builder.append(Data.MIMETYPE);
        builder.append('=');
        builder.append(mimeTypeValue);
        for (Entry<String, String> entry : sortedMap.entrySet()) {
            final String key = entry.getKey();
            final String value = entry.getValue();
            builder.append(' ');
            builder.append(key);
            builder.append('=');
            builder.append(value);
        }
        return builder.toString();
    }

    private static boolean equalsForContentValues(
            ContentValues expected, ContentValues actual) {
        if (expected == actual) {
            return true;
        } else if (expected == null || actual == null || expected.size() != actual.size()) {
            return false;
        }
        for (Entry<String, Object> entry : expected.valueSet()) {
            final String key = entry.getKey();
            final Object value = entry.getValue();
            if (!actual.containsKey(key)) {
                return false;
            }
            if (value instanceof byte[]) {
                Object actualValue = actual.get(key);
                if (!Arrays.equals((byte[])value, (byte[])actualValue)) {
                    return false;
                }
            } else if (!value.equals(actual.get(key))) {
                    return false;
            }
        }
        return true;
    }

    private static boolean equalsString(String a, String b) {
        if (a == null || a.length() == 0) {
            return b == null || b.length() == 0;
        } else {
            return a.equals(b);
        }
    }
}