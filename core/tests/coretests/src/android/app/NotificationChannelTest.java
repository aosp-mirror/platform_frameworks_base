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

import android.net.Uri;
import android.os.Parcel;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.base.Strings;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NotificationChannelTest {
    private final String CLASS = "android.app.NotificationChannel";

    @Test
    public void testLongStringFields() {
        NotificationChannel channel = new NotificationChannel("id", "name", 3);

        try {
            String longString = Strings.repeat("A", 65536);
            Field mName = Class.forName(CLASS).getDeclaredField("mName");
            mName.setAccessible(true);
            mName.set(channel, longString);
            Field mId = Class.forName(CLASS).getDeclaredField("mId");
            mId.setAccessible(true);
            mId.set(channel, longString);
            Field mDesc = Class.forName(CLASS).getDeclaredField("mDesc");
            mDesc.setAccessible(true);
            mDesc.set(channel, longString);
            Field mParentId = Class.forName(CLASS).getDeclaredField("mParentId");
            mParentId.setAccessible(true);
            mParentId.set(channel, longString);
            Field mGroup = Class.forName(CLASS).getDeclaredField("mGroup");
            mGroup.setAccessible(true);
            mGroup.set(channel, longString);
            Field mConversationId = Class.forName(CLASS).getDeclaredField("mConversationId");
            mConversationId.setAccessible(true);
            mConversationId.set(channel, longString);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }

        Parcel parcel = Parcel.obtain();
        channel.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        NotificationChannel fromParcel = NotificationChannel.CREATOR.createFromParcel(parcel);
        assertEquals(NotificationChannel.MAX_TEXT_LENGTH, fromParcel.getId().length());
        assertEquals(NotificationChannel.MAX_TEXT_LENGTH, fromParcel.getName().length());
        assertEquals(NotificationChannel.MAX_TEXT_LENGTH,
                fromParcel.getDescription().length());
        assertEquals(NotificationChannel.MAX_TEXT_LENGTH,
                fromParcel.getParentChannelId().length());
        assertEquals(NotificationChannel.MAX_TEXT_LENGTH,
                fromParcel.getGroup().length());
        assertEquals(NotificationChannel.MAX_TEXT_LENGTH,
                fromParcel.getConversationId().length());
    }

    @Test
    public void testLongAlertFields() {
        NotificationChannel channel = new NotificationChannel("id", "name", 3);

        channel.setSound(Uri.parse("content://" + Strings.repeat("A",65536)),
                Notification.AUDIO_ATTRIBUTES_DEFAULT);
        channel.setVibrationPattern(new long[65550/2]);

        Parcel parcel = Parcel.obtain();
        channel.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        NotificationChannel fromParcel = NotificationChannel.CREATOR.createFromParcel(parcel);
        assertEquals(NotificationChannel.MAX_VIBRATION_LENGTH,
                fromParcel.getVibrationPattern().length);
        assertEquals(NotificationChannel.MAX_TEXT_LENGTH,
                fromParcel.getSound().toString().length());
    }
}
