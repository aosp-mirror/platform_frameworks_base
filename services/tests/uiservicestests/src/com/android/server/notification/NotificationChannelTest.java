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

package com.android.server.notification;

import static android.app.NotificationManager.IMPORTANCE_DEFAULT;

import static junit.framework.Assert.assertEquals;

import android.app.NotificationChannel;
import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.FastXmlSerializer;
import com.android.server.UiServiceTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationChannelTest extends UiServiceTestCase {

    @Test
    public void testWriteToParcel() {
        NotificationChannel channel =
                new NotificationChannel("1", "one", IMPORTANCE_DEFAULT);
        Parcel parcel = Parcel.obtain();
        channel.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        NotificationChannel channel1 = NotificationChannel.CREATOR.createFromParcel(parcel);
        assertEquals(channel, channel1);
    }

    @Test
    public void testSystemBlockable() {
        NotificationChannel channel = new NotificationChannel("a", "ab", IMPORTANCE_DEFAULT);
        assertEquals(false, channel.isBlockableSystem());
        channel.setBlockableSystem(true);
        assertEquals(true, channel.isBlockableSystem());
    }

    @Test
    public void testEmptyVibration_noException() throws Exception {
        NotificationChannel channel = new NotificationChannel("a", "ab", IMPORTANCE_DEFAULT);
        channel.setVibrationPattern(new long[0]);

        XmlSerializer serializer = new FastXmlSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
        channel.writeXml(serializer);
    }
}
