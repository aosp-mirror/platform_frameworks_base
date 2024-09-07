/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertTrue;

import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.text.TextUtils;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.base.Strings;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class NotificationChannelGroupTest {
    private final String CLASS = "android.app.NotificationChannelGroup";

    @Test
    public void testLongStringFields() {
        NotificationChannelGroup group = new NotificationChannelGroup("my_group_01", "groupName");

        try {
            String longString = Strings.repeat("A", 65536);
            Field mName = Class.forName(CLASS).getDeclaredField("mName");
            mName.setAccessible(true);
            mName.set(group, longString);
            Field mId = Class.forName(CLASS).getDeclaredField("mId");
            mId.setAccessible(true);
            mId.set(group, longString);
            Field mDescription = Class.forName(CLASS).getDeclaredField("mDescription");
            mDescription.setAccessible(true);
            mDescription.set(group, longString);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }

        Parcel parcel = Parcel.obtain();
        group.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        NotificationChannelGroup fromParcel =
                NotificationChannelGroup.CREATOR.createFromParcel(parcel);
        assertEquals(NotificationChannelGroup.MAX_TEXT_LENGTH, fromParcel.getId().length());
        assertEquals(NotificationChannelGroup.MAX_TEXT_LENGTH, fromParcel.getName().length());
        assertEquals(NotificationChannelGroup.MAX_TEXT_LENGTH,
                fromParcel.getDescription().length());
    }

    @Test
    public void testNullableFields() {
        NotificationChannelGroup group = new NotificationChannelGroup("my_group_01", null);

        Parcel parcel = Parcel.obtain();
        group.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        NotificationChannelGroup fromParcel =
                NotificationChannelGroup.CREATOR.createFromParcel(parcel);
        assertEquals(group.getId(), fromParcel.getId());
        assertTrue(TextUtils.isEmpty(fromParcel.getName()));
    }
}
