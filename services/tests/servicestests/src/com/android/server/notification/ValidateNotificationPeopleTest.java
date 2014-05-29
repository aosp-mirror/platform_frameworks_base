/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.server.notification;

import android.app.Notification;
import android.os.Bundle;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.SpannableString;

import java.util.ArrayList;
import java.util.Arrays;

public class ValidateNotificationPeopleTest extends AndroidTestCase {

    @SmallTest
    public void testNoExtra() throws Exception {
        Bundle bundle = new Bundle();
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertNull("lack of extra should return null", result);
    }

    @SmallTest
    public void testSingleString() throws Exception {
        String[] expected = { "foobar" };
        Bundle bundle = new Bundle();
        bundle.putString(Notification.EXTRA_PEOPLE, expected[0]);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("string should be in result[0]", expected, result);
    }

    @SmallTest
    public void testSingleCharArray() throws Exception {
        String[] expected = { "foobar" };
        Bundle bundle = new Bundle();
        bundle.putCharArray(Notification.EXTRA_PEOPLE, expected[0].toCharArray());
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("char[] should be in result[0]", expected, result);
    }

    @SmallTest
    public void testSingleCharSequence() throws Exception {
        String[] expected = { "foobar" };
        Bundle bundle = new Bundle();
        bundle.putCharSequence(Notification.EXTRA_PEOPLE, new SpannableString(expected[0]));
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("charSequence should be in result[0]", expected, result);
    }

    @SmallTest
    public void testStringArraySingle() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "foobar" };
        bundle.putStringArray(Notification.EXTRA_PEOPLE, expected);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("wrapped string should be in result[0]", expected, result);
    }

    @SmallTest
    public void testStringArrayMultiple() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "foo", "bar", "baz" };
        bundle.putStringArray(Notification.EXTRA_PEOPLE, expected);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("testStringArrayMultiple", expected, result);
    }

    @SmallTest
    public void testStringArrayNulls() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "foo", null, "baz" };
        bundle.putStringArray(Notification.EXTRA_PEOPLE, expected);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("testStringArrayNulls", expected, result);
    }

    @SmallTest
    public void testCharSequenceArrayMultiple() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "foo", "bar", "baz" };
        CharSequence[] charSeqArray = new CharSequence[expected.length];
        for (int i = 0; i < expected.length; i++) {
            charSeqArray[i] = new SpannableString(expected[i]);
        }
        bundle.putCharSequenceArray(Notification.EXTRA_PEOPLE, charSeqArray);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("testCharSequenceArrayMultiple", expected, result);
    }

    @SmallTest
    public void testMixedCharSequenceArrayList() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "foo", "bar", "baz" };
        CharSequence[] charSeqArray = new CharSequence[expected.length];
        for (int i = 0; i < expected.length; i++) {
            if (i % 2 == 0) {
                charSeqArray[i] = expected[i];
            } else {
                charSeqArray[i] = new SpannableString(expected[i]);
            }
        }
        bundle.putCharSequenceArray(Notification.EXTRA_PEOPLE, charSeqArray);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("testMixedCharSequenceArrayList", expected, result);
    }

    @SmallTest
    public void testStringArrayList() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "foo", null, "baz" };
        final ArrayList<String> stringArrayList = new ArrayList<String>(expected.length);
        for (int i = 0; i < expected.length; i++) {
            stringArrayList.add(expected[i]);
        }
        bundle.putStringArrayList(Notification.EXTRA_PEOPLE, stringArrayList);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("testStringArrayList", expected, result);
    }

    @SmallTest
    public void testCharSequenceArrayList() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "foo", "bar", "baz" };
        final ArrayList<CharSequence> stringArrayList =
                new ArrayList<CharSequence>(expected.length);
        for (int i = 0; i < expected.length; i++) {
            stringArrayList.add(new SpannableString(expected[i]));
        }
        bundle.putCharSequenceArrayList(Notification.EXTRA_PEOPLE, stringArrayList);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("testCharSequenceArrayList", expected, result);
    }

    private void assertStringArrayEquals(String message, String[] expected, String[] result) {
        String expectedString = Arrays.toString(expected);
        String resultString = Arrays.toString(result);
        assertEquals(message + ": arrays differ", expectedString, resultString);
    }
}
