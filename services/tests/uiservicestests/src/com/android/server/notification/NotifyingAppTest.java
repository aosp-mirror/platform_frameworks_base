/**
 * Copyright (c) 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.notification;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import android.os.Parcel;
import android.service.notification.NotifyingApp;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.UiServiceTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotifyingAppTest extends UiServiceTestCase {

    @Test
    public void testConstructor() {
        NotifyingApp na = new NotifyingApp();
        assertEquals(0, na.getUid());
        assertEquals(0, na.getLastNotified());
        assertEquals(null, na.getPackage());
    }

    @Test
    public void testPackage() {
        NotifyingApp na = new NotifyingApp();
        na.setPackage("test");
        assertEquals("test", na.getPackage());
    }

    @Test
    public void testUid() {
        NotifyingApp na = new NotifyingApp();
        na.setUid(90);
        assertEquals(90, na.getUid());
    }

    @Test
    public void testLastNotified() {
        NotifyingApp na = new NotifyingApp();
        na.setLastNotified((long) 8000);
        assertEquals((long) 8000, na.getLastNotified());
    }

    @Test
    public void testWriteToParcel() {
        NotifyingApp na = new NotifyingApp();
        na.setPackage("package");
        na.setUid(200);
        na.setLastNotified(4000);

        Parcel parcel = Parcel.obtain();
        na.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        NotifyingApp na1 = NotifyingApp.CREATOR.createFromParcel(parcel);
        assertEquals(na.getLastNotified(), na1.getLastNotified());
        assertEquals(na.getPackage(), na1.getPackage());
        assertEquals(na.getUid(), na1.getUid());
    }

    @Test
    public void testCompareTo() {
        NotifyingApp na1 = new NotifyingApp();
        na1.setPackage("pkg1");
        na1.setUid(1000);
        na1.setLastNotified(6);

        NotifyingApp na2 = new NotifyingApp();
        na2.setPackage("a");
        na2.setUid(999);
        na2.setLastNotified(1);

        assertTrue(na1.compareTo(na2) < 0);
    }
}
