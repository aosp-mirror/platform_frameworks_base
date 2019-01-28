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

import static android.app.Notification.FLAG_CAN_COLORIZE;
import static android.app.Notification.FLAG_FOREGROUND_SERVICE;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.media.AudioAttributes.USAGE_ALARM;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.Person;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.testing.TestableContext;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public class NotificationEntryTest {
    private String mPkg = "pkg";
    private int mUid = 2018;
    @Mock
    private IPackageManager mPackageManager;
    @Mock
    private ApplicationInfo mAppInfo;
    @Mock
    private SmsHelper mSmsHelper;

    private static final String DEFAULT_SMS_PACKAGE_NAME = "foo";

    @Rule
    public final TestableContext mContext =
            new TestableContext(InstrumentationRegistry.getContext(), null);

    private StatusBarNotification generateSbn(String channelId) {
        Notification n = new Notification.Builder(mContext, channelId)
                .setContentTitle("foo")
                .build();

        return new StatusBarNotification(mPkg, mPkg, 0, "tag", mUid, mUid, n,
                UserHandle.SYSTEM, null, 0);
    }

    private StatusBarNotification generateSbn(String channelId, String packageName) {
        Notification n = new Notification.Builder(mContext, channelId)
                .setContentTitle("foo")
                .build();

        return new StatusBarNotification(packageName, packageName, 0, "tag", mUid, mUid, n,
                UserHandle.SYSTEM, null, 0);
    }

    private StatusBarNotification generateSbn(Notification n) {
        return new StatusBarNotification(mPkg, mPkg, 0, "tag", mUid, mUid, n,
                UserHandle.SYSTEM, null, 0);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mPkg = mContext.getPackageName();
        mUid = Process.myUid();
        when(mPackageManager.getApplicationInfo(anyString(), anyInt(), anyInt()))
                .thenReturn(mAppInfo);
        mAppInfo.targetSdkVersion = Build.VERSION_CODES.P;
        when(mSmsHelper.getDefaultSmsApplication())
                .thenReturn(new ComponentName(DEFAULT_SMS_PACKAGE_NAME, "bar"));
    }

    @Test
    public void testHasPerson() {
        NotificationChannel channel = new NotificationChannel("", "", IMPORTANCE_HIGH);
        StatusBarNotification sbn = generateSbn(channel.getId());
        ArrayList<Person> people = new ArrayList<>();
        people.add(new Person.Builder().setKey("mailto:testing@android.com").build());
        sbn.getNotification().extras.putParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, people);

        NotificationEntry entry = new NotificationEntry(mPackageManager, sbn, channel, mSmsHelper);
        assertTrue(entry.involvesPeople());
    }

    @Test
    public void testNotPerson() {
        NotificationChannel channel = new NotificationChannel("", "", IMPORTANCE_HIGH);
        StatusBarNotification sbn = generateSbn(channel.getId());
        NotificationEntry entry = new NotificationEntry(mPackageManager, sbn, channel, mSmsHelper);
        assertFalse(entry.involvesPeople());
    }

    @Test
    public void testHasPerson_matchesDefaultSmsApp() {
        NotificationChannel channel = new NotificationChannel("", "", IMPORTANCE_HIGH);
        StatusBarNotification sbn = generateSbn(channel.getId(), DEFAULT_SMS_PACKAGE_NAME);
        NotificationEntry entry = new NotificationEntry(mPackageManager, sbn, channel, mSmsHelper);
        assertTrue(entry.involvesPeople());
    }

    @Test
    public void testHasPerson_doesntMatchDefaultSmsApp() {
        NotificationChannel channel = new NotificationChannel("", "", IMPORTANCE_HIGH);
        StatusBarNotification sbn = generateSbn(channel.getId(), "abc");
        NotificationEntry entry = new NotificationEntry(mPackageManager, sbn, channel, mSmsHelper);
        assertFalse(entry.involvesPeople());
    }

    @Test
    public void testIsInboxStyle() {
        NotificationChannel channel = new NotificationChannel("", "", IMPORTANCE_HIGH);

        Notification n = new Notification.Builder(mContext, channel.getId())
                .setStyle(new Notification.InboxStyle())
                .build();
        NotificationEntry entry =
                new NotificationEntry(mPackageManager, generateSbn(n), channel, mSmsHelper);
        assertTrue(entry.hasStyle(Notification.InboxStyle.class));
    }

    @Test
    public void testIsMessagingStyle() {
        NotificationChannel channel = new NotificationChannel("", "", IMPORTANCE_HIGH);

        Notification n = new Notification.Builder(mContext, channel.getId())
                .setStyle(new Notification.MessagingStyle(""))
                .build();
        NotificationEntry entry =
                new NotificationEntry(mPackageManager, generateSbn(n), channel, mSmsHelper);
        assertTrue(entry.hasStyle(Notification.MessagingStyle.class));
    }

    @Test
    public void testIsNotPersonStyle() {
        NotificationChannel channel = new NotificationChannel("", "", IMPORTANCE_HIGH);

        Notification n = new Notification.Builder(mContext, channel.getId())
                .setStyle(new Notification.BigPictureStyle())
                .build();
        NotificationEntry entry =
                new NotificationEntry(mPackageManager, generateSbn(n), channel, mSmsHelper);
        assertFalse(entry.hasStyle(Notification.InboxStyle.class));
        assertFalse(entry.hasStyle(Notification.MessagingStyle.class));
    }

    @Test
    public void testIsAudioAttributes() {
        NotificationChannel channel = new NotificationChannel("", "", IMPORTANCE_HIGH);
        channel.setSound(null, new AudioAttributes.Builder().setUsage(USAGE_ALARM).build());

        NotificationEntry entry = new NotificationEntry(
                mPackageManager, generateSbn(channel.getId()), channel, mSmsHelper);

        assertTrue(entry.isAudioAttributesUsage(USAGE_ALARM));
    }

    @Test
    public void testIsNotAudioAttributes() {
        NotificationChannel channel = new NotificationChannel("", "", IMPORTANCE_HIGH);
        NotificationEntry entry = new NotificationEntry(
                mPackageManager, generateSbn(channel.getId()), channel, mSmsHelper);

        assertFalse(entry.isAudioAttributesUsage(USAGE_ALARM));
    }

    @Test
    public void testIsCategory() {
        NotificationChannel channel = new NotificationChannel("", "", IMPORTANCE_HIGH);

        Notification n = new Notification.Builder(mContext, channel.getId())
                .setCategory(Notification.CATEGORY_EMAIL)
                .build();
        NotificationEntry entry =
                new NotificationEntry(mPackageManager, generateSbn(n), channel, mSmsHelper);

        assertTrue(entry.isCategory(Notification.CATEGORY_EMAIL));
        assertFalse(entry.isCategory(Notification.CATEGORY_MESSAGE));
    }

    @Test
    public void testIsOngoing() {
        NotificationChannel channel = new NotificationChannel("", "", IMPORTANCE_HIGH);

        Notification n = new Notification.Builder(mContext, channel.getId())
                .setFlag(FLAG_FOREGROUND_SERVICE, true)
                .build();
        NotificationEntry entry =
                new NotificationEntry(mPackageManager, generateSbn(n), channel, mSmsHelper);

        assertTrue(entry.isOngoing());
    }

    @Test
    public void testIsNotOngoing() {
        NotificationChannel channel = new NotificationChannel("", "", IMPORTANCE_HIGH);

        Notification n = new Notification.Builder(mContext, channel.getId())
                .setFlag(FLAG_CAN_COLORIZE, true)
                .build();
        NotificationEntry entry =
                new NotificationEntry(mPackageManager, generateSbn(n), channel, mSmsHelper);

        assertFalse(entry.isOngoing());
    }
}
