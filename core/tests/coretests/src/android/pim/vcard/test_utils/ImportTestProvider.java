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
package android.pim.vcard.test_utils;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentValues;
import android.net.Uri;
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
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.test.AndroidTestCase;
import android.test.mock.MockContentProvider;
import android.text.TextUtils;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

public class ImportTestProvider extends MockContentProvider {
    private static final Set<String> sKnownMimeTypeSet =
        new HashSet<String>(Arrays.asList(StructuredName.CONTENT_ITEM_TYPE,
                Nickname.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE,
                Email.CONTENT_ITEM_TYPE, StructuredPostal.CONTENT_ITEM_TYPE,
                Im.CONTENT_ITEM_TYPE, Organization.CONTENT_ITEM_TYPE,
                Event.CONTENT_ITEM_TYPE, Photo.CONTENT_ITEM_TYPE,
                Note.CONTENT_ITEM_TYPE, Website.CONTENT_ITEM_TYPE,
                Relation.CONTENT_ITEM_TYPE, Event.CONTENT_ITEM_TYPE,
                GroupMembership.CONTENT_ITEM_TYPE));

    final Map<String, Collection<ContentValues>> mMimeTypeToExpectedContentValues;

    public ImportTestProvider(AndroidTestCase androidTestCase) {
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
            TestCase.fail(String.format(
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
            TestCase.fail("There is no operation.");
        }

        final int size = operations.size();
        ContentProviderResult[] fakeResultArray = new ContentProviderResult[size];
        for (int i = 0; i < size; i++) {
            Uri uri = Uri.withAppendedPath(RawContacts.CONTENT_URI, String.valueOf(i));
            fakeResultArray[i] = new ContentProviderResult(uri);
        }

        for (int i = 0; i < size; i++) {
            ContentProviderOperation operation = operations.get(i);
            ContentValues contentValues = operation.resolveValueBackReferences(
                    fakeResultArray, i);
        }
        for (int i = 0; i < size; i++) {
            ContentProviderOperation operation = operations.get(i);
            ContentValues actualContentValues = operation.resolveValueBackReferences(
                    fakeResultArray, i);
            final Uri uri = operation.getUri();
            if (uri.equals(RawContacts.CONTENT_URI)) {
                TestCase.assertNull(actualContentValues.get(RawContacts.ACCOUNT_NAME));
                TestCase.assertNull(actualContentValues.get(RawContacts.ACCOUNT_TYPE));
            } else if (uri.equals(Data.CONTENT_URI)) {
                final String mimeType = actualContentValues.getAsString(Data.MIMETYPE);
                if (!sKnownMimeTypeSet.contains(mimeType)) {
                    TestCase.fail(String.format(
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
                /* for testing
                Log.d("@@@",
                        String.format("MimeType: %s, data: %s",
                                mimeType, actualContentValues.toString())); */
                // Remove RAW_CONTACT_ID entry just for safety, since we do not care
                // how resolver-related code handles the entry in this unit test,
                if (actualContentValues.containsKey(Data.RAW_CONTACT_ID)) {
                    actualContentValues.remove(Data.RAW_CONTACT_ID);
                }
                final Collection<ContentValues> contentValuesCollection =
                    mMimeTypeToExpectedContentValues.get(mimeType);
                if (contentValuesCollection.isEmpty()) {
                    TestCase.fail("ContentValues for MimeType " + mimeType
                            + " is not expected at all (" + actualContentValues + ")");
                }
                boolean checked = false;
                for (ContentValues expectedContentValues : contentValuesCollection) {
                    /*for testing
                    Log.d("@@@", "expected: "
                            + convertToEasilyReadableString(expectedContentValues));
                    Log.d("@@@", "actual  : "
                            + convertToEasilyReadableString(actualContentValues));*/
                    if (equalsForContentValues(expectedContentValues,
                            actualContentValues)) {
                        TestCase.assertTrue(contentValuesCollection.remove(expectedContentValues));
                        checked = true;
                        break;
                    }
                }
                if (!checked) {
                    final StringBuilder builder = new StringBuilder();
                    builder.append("\n");
                    builder.append("Unexpected: ");
                    builder.append(convertToEasilyReadableString(actualContentValues));
                    builder.append("\n");
                    builder.append("Expected  : ");
                    for (ContentValues expectedContentValues : contentValuesCollection) {
                        builder.append(convertToEasilyReadableString(expectedContentValues));
                    }
                    TestCase.fail(builder.toString());
                }
            } else {
                TestCase.fail("Unexpected Uri has come: " + uri);
            }
        }  // for (int i = 0; i < size; i++) {
        return fakeResultArray;
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
            TestCase.fail(failMsg);
        }
    }

    /**
     * Utility method to print ContentValues whose content is printed with sorted keys.
     */
    private String convertToEasilyReadableString(ContentValues contentValues) {
        if (contentValues == null) {
            return "null";
        }
        String mimeTypeValue = "";
        SortedMap<String, String> sortedMap = new TreeMap<String, String>();
        for (Entry<String, Object> entry : contentValues.valueSet()) {
            final String key = entry.getKey();
            final Object value = entry.getValue();
            final String valueString = (value != null ? value.toString() : null);
            if (Data.MIMETYPE.equals(key)) {
                mimeTypeValue = valueString;
            } else {
                TestCase.assertNotNull(key);
                sortedMap.put(key, valueString);
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
            builder.append("=\"");
            builder.append(value);
            builder.append('"');
        }
        return builder.toString();
    }

    private static boolean equalsForContentValues(
            final ContentValues expected, final ContentValues actual) {
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
            // Type mismatch usuall happens as importer doesn't care the type of each value.
            // For example, variable type might be Integer when importing the type of TEL,
            // while variable type would be String when importing the type of RELATION.
            final Object actualValue = actual.get(key);
            if (value instanceof byte[]) {
                if (!Arrays.equals((byte[])value, (byte[])actualValue)) {
                    byte[] e = (byte[])value;
                    byte[] a = (byte[])actualValue;
                    return false;
                }
            } else if (!value.equals(actualValue) &&
                    !value.toString().equals(actualValue.toString())) {
                return false;
            }
        }
        return true;
    }
}