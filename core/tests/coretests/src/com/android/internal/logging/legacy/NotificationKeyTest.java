/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.internal.logging.legacy;

import android.test.InstrumentationTestCase;

public class NotificationKeyTest extends InstrumentationTestCase {

    private final NotificationKey mKey;

    public NotificationKeyTest() {
        mKey = new NotificationKey();
    }

    public void testGoodKey() throws Throwable {
        assertTrue(mKey.parse("1|com.android.example.notificationshowcase|31338|null|10090"));

        assertEquals("com.android.example.notificationshowcase", mKey.mPackageName);
        assertEquals("", mKey.mTag);
        assertEquals(31338, mKey.mId);
        assertEquals(1, mKey.mUser);
        assertEquals(10090, mKey.mUid);
    }

    public void testTaggedKey() throws Throwable {
        assertTrue(mKey.parse("1|com.android.example.notificationshowcase|31338|foo|10090"));

        assertEquals("com.android.example.notificationshowcase", mKey.mPackageName);
        assertEquals("foo", mKey.mTag);
        assertEquals(31338, mKey.mId);
        assertEquals(1, mKey.mUser);
        assertEquals(10090, mKey.mUid);
    }

    public void testEmptyTag() throws Throwable {
        assertTrue(mKey.parse("1|com.android.example.notificationshowcase|31338||10090"));

        assertEquals("com.android.example.notificationshowcase", mKey.mPackageName);
        assertEquals("", mKey.mTag);
        assertEquals(31338, mKey.mId);
        assertEquals(1, mKey.mUser);
        assertEquals(10090, mKey.mUid);
    }

    public void testBadKeys() throws Throwable {
        assertFalse(mKey.parse(null));
        assertFalse(mKey.parse(""));
        assertFalse(mKey.parse("foo"));  // not a key
        assertFalse(mKey.parse("1|com.android.example.notificationshowcase|31338|null"));
        assertFalse(mKey.parse("bar|com.android.example.notificationshowcase|31338|null|10090"));
    }
}
