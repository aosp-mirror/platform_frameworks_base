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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Notification;
import android.app.Person;
import android.os.Bundle;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.SpannableString;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ValidateNotificationPeopleTest extends UiServiceTestCase {

    @Test
    public void testNoExtra() throws Exception {
        Bundle bundle = new Bundle();
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertNull("lack of extra should return null", result);
    }

    @Test
    public void testSingleString() throws Exception {
        String[] expected = { "foobar" };
        Bundle bundle = new Bundle();
        bundle.putString(Notification.EXTRA_PEOPLE_LIST, expected[0]);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("string should be in result[0]", expected, result);
    }

    @Test
    public void testSingleCharArray() throws Exception {
        String[] expected = { "foobar" };
        Bundle bundle = new Bundle();
        bundle.putCharArray(Notification.EXTRA_PEOPLE_LIST, expected[0].toCharArray());
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("char[] should be in result[0]", expected, result);
    }

    @Test
    public void testSingleCharSequence() throws Exception {
        String[] expected = { "foobar" };
        Bundle bundle = new Bundle();
        bundle.putCharSequence(Notification.EXTRA_PEOPLE_LIST, new SpannableString(expected[0]));
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("charSequence should be in result[0]", expected, result);
    }

    @Test
    public void testStringArraySingle() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "foobar" };
        bundle.putStringArray(Notification.EXTRA_PEOPLE_LIST, expected);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("wrapped string should be in result[0]", expected, result);
    }

    @Test
    public void testStringArrayMultiple() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "foo", "bar", "baz" };
        bundle.putStringArray(Notification.EXTRA_PEOPLE_LIST, expected);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("testStringArrayMultiple", expected, result);
    }

    @Test
    public void testStringArrayNulls() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "foo", null, "baz" };
        bundle.putStringArray(Notification.EXTRA_PEOPLE_LIST, expected);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("testStringArrayNulls", expected, result);
    }

    @Test
    public void testCharSequenceArrayMultiple() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "foo", "bar", "baz" };
        CharSequence[] charSeqArray = new CharSequence[expected.length];
        for (int i = 0; i < expected.length; i++) {
            charSeqArray[i] = new SpannableString(expected[i]);
        }
        bundle.putCharSequenceArray(Notification.EXTRA_PEOPLE_LIST, charSeqArray);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("testCharSequenceArrayMultiple", expected, result);
    }

    @Test
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
        bundle.putCharSequenceArray(Notification.EXTRA_PEOPLE_LIST, charSeqArray);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("testMixedCharSequenceArrayList", expected, result);
    }

    @Test
    public void testStringArrayList() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "foo", null, "baz" };
        final ArrayList<String> stringArrayList = new ArrayList<String>(expected.length);
        for (int i = 0; i < expected.length; i++) {
            stringArrayList.add(expected[i]);
        }
        bundle.putStringArrayList(Notification.EXTRA_PEOPLE_LIST, stringArrayList);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("testStringArrayList", expected, result);
    }

    @Test
    public void testCharSequenceArrayList() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "foo", "bar", "baz" };
        final ArrayList<CharSequence> stringArrayList =
                new ArrayList<CharSequence>(expected.length);
        for (int i = 0; i < expected.length; i++) {
            stringArrayList.add(new SpannableString(expected[i]));
        }
        bundle.putCharSequenceArrayList(Notification.EXTRA_PEOPLE_LIST, stringArrayList);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("testCharSequenceArrayList", expected, result);
    }

    @Test
    public void testPeopleArrayList() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "name:test" , "tel:1234" };
        final ArrayList<Person> arrayList =
                new ArrayList<>(expected.length);
        arrayList.add(new Person.Builder().setName("test").build());
        arrayList.add(new Person.Builder().setUri(expected[1]).build());
        bundle.putParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, arrayList);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("testPeopleArrayList", expected, result);
    }

    private void assertStringArrayEquals(String message, String[] expected, String[] result) {
        String expectedString = Arrays.toString(expected);
        String resultString = Arrays.toString(result);
        assertEquals(message + ": arrays differ", expectedString, resultString);
    }
}
