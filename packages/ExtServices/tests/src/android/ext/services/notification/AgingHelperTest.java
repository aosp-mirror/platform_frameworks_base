/**
 * Copyright (C) 2018 The Android Open Source Project
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

package android.ext.services.notification;

import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_MIN;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.testing.TestableContext;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class AgingHelperTest {
    private String mPkg = "pkg";
    private int mUid = 2018;

    @Rule
    public final TestableContext mContext =
            new TestableContext(InstrumentationRegistry.getTargetContext(), null);

    @Mock
    private NotificationCategorizer mCategorizer;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private IPackageManager mPackageManager;
    @Mock
    private AgingHelper.Callback mCallback;
    @Mock
    private SmsHelper mSmsHelper;

    private AgingHelper mAgingHelper;

    private StatusBarNotification generateSbn(String channelId) {
        Notification n = new Notification.Builder(mContext, channelId)
                .setContentTitle("foo")
                .build();

        return new StatusBarNotification(mPkg, mPkg, 0, "tag", mUid, mUid, n,
                UserHandle.SYSTEM, null, 0);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mPkg = mContext.getPackageName();
        mUid = Process.myUid();

        ApplicationInfo info = mock(ApplicationInfo.class);
        when(mPackageManager.getApplicationInfo(anyString(), anyInt(), anyInt()))
                .thenReturn(info);
        info.targetSdkVersion = Build.VERSION_CODES.P;

        mContext.addMockSystemService(AlarmManager.class, mAlarmManager);

        mAgingHelper = new AgingHelper(mContext, mCategorizer, mCallback);
    }

    @Test
    public void testNoSnoozingOnPost() {
        NotificationChannel channel = new NotificationChannel("", "", IMPORTANCE_HIGH);
        StatusBarNotification sbn = generateSbn(channel.getId());
        NotificationEntry entry = new NotificationEntry(mPackageManager, sbn, channel, mSmsHelper);


        mAgingHelper.onNotificationPosted(entry);
        verify(mAlarmManager, never()).setExactAndAllowWhileIdle(anyInt(), anyLong(), any());
    }

    @Test
    public void testPostResetsSnooze() {
        NotificationChannel channel = new NotificationChannel("", "", IMPORTANCE_HIGH);
        StatusBarNotification sbn = generateSbn(channel.getId());
        NotificationEntry entry = new NotificationEntry(mPackageManager, sbn, channel, mSmsHelper);


        mAgingHelper.onNotificationPosted(entry);
        verify(mAlarmManager, times(1)).cancel(any(PendingIntent.class));
    }

    @Test
    public void testSnoozingOnSeen() {
        NotificationChannel channel = new NotificationChannel("", "", IMPORTANCE_HIGH);
        StatusBarNotification sbn = generateSbn(channel.getId());
        NotificationEntry entry = new NotificationEntry(mPackageManager, sbn, channel, mSmsHelper);
        entry.setSeen();
        when(mCategorizer.getCategory(entry)).thenReturn(NotificationCategorizer.CATEGORY_PEOPLE);

        mAgingHelper.onNotificationSeen(entry);
        verify(mAlarmManager, times(1)).setExactAndAllowWhileIdle(anyInt(), anyLong(), any());
    }

    @Test
    public void testNoSnoozingOnSeenUserLocked() {
        NotificationChannel channel = new NotificationChannel("", "", IMPORTANCE_HIGH);
        channel.lockFields(NotificationChannel.USER_LOCKED_IMPORTANCE);
        StatusBarNotification sbn = generateSbn(channel.getId());
        NotificationEntry entry = new NotificationEntry(mPackageManager, sbn, channel, mSmsHelper);
        when(mCategorizer.getCategory(entry)).thenReturn(NotificationCategorizer.CATEGORY_PEOPLE);

        mAgingHelper.onNotificationSeen(entry);
        verify(mAlarmManager, never()).setExactAndAllowWhileIdle(anyInt(), anyLong(), any());
    }

    @Test
    public void testNoSnoozingOnSeenAlreadyLow() {
        NotificationEntry entry = mock(NotificationEntry.class);
        when(entry.getChannel()).thenReturn(new NotificationChannel("", "", IMPORTANCE_HIGH));
        when(entry.getImportance()).thenReturn(IMPORTANCE_MIN);

        mAgingHelper.onNotificationSeen(entry);
        verify(mAlarmManager, never()).setExactAndAllowWhileIdle(anyInt(), anyLong(), any());
    }
}
