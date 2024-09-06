/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.app.Notification.CATEGORY_MESSAGE;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.platform.test.flag.junit.SetFlagsRule.DefaultInitValueType.DEVICE_DEFAULT;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.app.Person;
import android.graphics.Color;
import android.media.session.MediaSession;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.service.notification.StatusBarNotification;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;
import com.android.server.UiServiceTestCase;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationTimeComparatorTest extends UiServiceTestCase {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule(DEVICE_DEFAULT);

    @Test
    @EnableFlags({android.app.Flags.FLAG_SORT_SECTION_BY_TIME})
    public void testCompare() {
        NotificationRecord one = mock(NotificationRecord.class);
        when(one.getRankingTimeMs()).thenReturn(1L);

        NotificationRecord five = mock(NotificationRecord.class);
        when(five.getRankingTimeMs()).thenReturn(5L);

        NotificationRecord ten = mock(NotificationRecord.class);
        when(ten.getRankingTimeMs()).thenReturn(10L);

        List<NotificationRecord> expected = new ArrayList<>();
        expected.add(ten);
        expected.add(five);
        expected.add(one);

        List<NotificationRecord> actual = new ArrayList<>();
        actual.addAll(expected);
        Collections.shuffle(actual);

        Collections.sort(actual, new NotificationTimeComparator());

        assertThat(actual).containsExactlyElementsIn(expected).inOrder();
    }
}
