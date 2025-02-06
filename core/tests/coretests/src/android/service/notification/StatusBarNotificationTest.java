/*
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

package android.service.notification;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNotSame;
import static junit.framework.Assert.assertNull;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.metrics.LogMaker;
import android.os.UserHandle;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.view.Display;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.window.flags.Flags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class StatusBarNotificationTest {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private final Context mMockContext = mock(Context.class);
    @Mock
    private Context mRealContext;
    @Mock
    private PackageManager mPm;
    @Mock
    private Context mSecondaryDisplayContext;

    private static final String PKG = "com.example.o";
    private static final int UID = 9583;
    private static final int ID = 1;
    private static final String TAG = "tag1";
    private static final String CHANNEL_ID = "channel";
    private static final String CHANNEL_ID_LONG =
            "give_a_developer_a_string_argument_and_who_knows_what_they_will_pass_in_there";
    private static final String GROUP_ID_1 = "group1";
    private static final String GROUP_ID_2 = "group2";
    private static final String GROUP_ID_LONG =
            "0|com.foo.bar|g:content://com.foo.bar.ui/account%3A-0000000/account/";
    private static final android.os.UserHandle USER =
            UserHandle.of(ActivityManager.getCurrentUser());

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mMockContext.getResources()).thenReturn(
                InstrumentationRegistry.getContext().getResources());
        when(mMockContext.getPackageManager()).thenReturn(mPm);
        when(mMockContext.getApplicationInfo()).thenReturn(new ApplicationInfo());
        when(mMockContext.getDisplayId()).thenReturn(Display.DEFAULT_DISPLAY);
        when(mSecondaryDisplayContext.getPackageManager()).thenReturn(mPm);
        when(mSecondaryDisplayContext.getApplicationInfo()).thenReturn(new ApplicationInfo());
        when(mSecondaryDisplayContext.getDisplayId()).thenReturn(2);
        when(mPm.getApplicationLabel(any())).thenReturn("");

        mRealContext = InstrumentationRegistry.getContext();
    }

    @Test
    public void testLogMaker() {
        final LogMaker logMaker = getNotification(PKG, GROUP_ID_1, CHANNEL_ID).getLogMaker();
        assertEquals(CHANNEL_ID,
                (String) logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_CHANNEL_ID));
        assertEquals(PKG, logMaker.getPackageName());
        assertEquals(ID, logMaker.getTaggedData(MetricsEvent.NOTIFICATION_ID));
        assertEquals(TAG, logMaker.getTaggedData(MetricsEvent.NOTIFICATION_TAG));
        assertEquals(GROUP_ID_1,
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_GROUP_ID));
        assertEquals(0,
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_GROUP_SUMMARY));
        assertNull(logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_CATEGORY));
        assertNull(logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_STYLE));
        assertNull(logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_PEOPLE));

    }

    /** Verify that modifying the returned logMaker won't leave stale data behind for
     * the next caller.*/
    @Test
    public void testLogMakerNoStaleData() {
        StatusBarNotification sbn = getNotification(PKG, GROUP_ID_1, CHANNEL_ID);
        final LogMaker logMaker = sbn.getLogMaker();
        int extraTag = MetricsEvent.FIELD_NOTIFICATION_CHANNEL_GROUP_ID;  // An arbitrary new tag
        logMaker.addTaggedData(extraTag, 1);
        assertNull(sbn.getLogMaker().getTaggedData(extraTag));
    }

    @Test
    public void testLogMakerWithCategory() {
        Notification.Builder builder = getNotificationBuilder(GROUP_ID_1, CHANNEL_ID)
                        .setCategory(Notification.CATEGORY_MESSAGE);
        final LogMaker logMaker = getNotification(PKG, builder).getLogMaker();
        assertEquals(Notification.CATEGORY_MESSAGE,
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_CATEGORY));
    }

    @Test
    public void testLogMakerNoChannel() {
        final LogMaker logMaker = getNotification(PKG, GROUP_ID_1, null).getLogMaker();

        assertNull(logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_CHANNEL_ID));
    }

    @Test
    public void testLogMakerLongChannel() {
        final LogMaker logMaker = getNotification(PKG, null, CHANNEL_ID_LONG).getLogMaker();
        final String loggedId = (String) logMaker
                .getTaggedData(MetricsEvent.FIELD_NOTIFICATION_CHANNEL_ID);
        assertEquals(StatusBarNotification.MAX_LOG_TAG_LENGTH, loggedId.length());
        assertEquals(CHANNEL_ID_LONG.substring(0, 10), loggedId.substring(0, 10));
    }

    @Test
    public void testLogMakerNoGroup() {
        final LogMaker logMaker = getNotification(PKG, null, CHANNEL_ID).getLogMaker();

        assertNull(
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_GROUP_ID));
    }

    @Test
    public void testLogMakerLongGroup() {
        final LogMaker logMaker = getNotification(PKG, GROUP_ID_LONG, CHANNEL_ID)
                .getLogMaker();

        final String loggedId = (String)
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_GROUP_ID);
        assertEquals(StatusBarNotification.MAX_LOG_TAG_LENGTH, loggedId.length());
        assertEquals(GROUP_ID_LONG.substring(0, 10), loggedId.substring(0, 10));
    }

    @Test
    public void testLogMakerOverrideGroup() {
        StatusBarNotification sbn = getNotification(PKG, GROUP_ID_1, CHANNEL_ID);
        assertEquals(GROUP_ID_1,
                sbn.getLogMaker().getTaggedData(MetricsEvent.FIELD_NOTIFICATION_GROUP_ID));

        sbn.setOverrideGroupKey(GROUP_ID_2);
        assertEquals(GROUP_ID_2,
                sbn.getLogMaker().getTaggedData(MetricsEvent.FIELD_NOTIFICATION_GROUP_ID));

        sbn.setOverrideGroupKey(null);
        assertEquals(GROUP_ID_1,
                sbn.getLogMaker().getTaggedData(MetricsEvent.FIELD_NOTIFICATION_GROUP_ID));
    }

    @Test
    public void testLogMakerWithPerson() {
        Notification.Builder builder = getNotificationBuilder(GROUP_ID_1, CHANNEL_ID)
                .addPerson(new Person.Builder().build());
        final LogMaker logMaker = getNotification(PKG, builder).getLogMaker();
        assertEquals(1,
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_PEOPLE));
    }

    @Test
    public void testLogMakerWithStyle() {
        Notification.Builder builder = getNotificationBuilder(GROUP_ID_1, CHANNEL_ID)
                .setStyle(new Notification.MessagingStyle(new Person.Builder().build()));
        final LogMaker logMaker = getNotification(PKG, builder).getLogMaker();
        assertEquals("android.app.Notification$MessagingStyle".hashCode(),
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_STYLE));
    }

    @Test
    public void testIsAppGroup() {
        StatusBarNotification sbn = getNotification(PKG, GROUP_ID_1, CHANNEL_ID);
        assertTrue(sbn.isAppGroup());

        sbn = getNotification(PKG, null, CHANNEL_ID);
        assertFalse(sbn.isAppGroup());

        Notification.Builder nb = getNotificationBuilder(null, CHANNEL_ID)
                .setSortKey("something");

        sbn = getNotification(PKG, nb);
        assertTrue(sbn.isAppGroup());

    }

    @Test
    public void testGetPackageContext_worksWithUserAll() {
        String pkg = "com.android.systemui";
        int uid = 1000;
        Notification notification = getNotificationBuilder(GROUP_ID_1, CHANNEL_ID).build();
        StatusBarNotification sbn = new StatusBarNotification(
                pkg, pkg, ID, TAG, uid, uid, notification, UserHandle.ALL, null, UID);
        Context resultContext = sbn.getPackageContext(mRealContext);
        assertNotNull(resultContext);
        assertNotSame(mRealContext, resultContext);
        assertEquals(pkg, resultContext.getPackageName());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_PER_DISPLAY_PACKAGE_CONTEXT_CACHE_IN_STATUSBAR_NOTIF)
    public void testGetPackageContext_multipleDisplaysCase() {
        String pkg = "com.android.systemui";
        int uid = 1000;
        Notification notification = getNotificationBuilder(GROUP_ID_1, CHANNEL_ID).build();
        StatusBarNotification sbn = new StatusBarNotification(
                pkg, pkg, ID, TAG, uid, uid, notification, UserHandle.ALL, null, UID);
        Context defaultContext = sbn.getPackageContext(mRealContext);
        Context secondaryContext = sbn.getPackageContext(mSecondaryDisplayContext);
        assertNotSame(mRealContext, defaultContext);
        assertNotSame(defaultContext, secondaryContext);

        // Let's make sure it caches it:
        assertSame(defaultContext, sbn.getPackageContext(mRealContext));
        assertSame(secondaryContext, sbn.getPackageContext(mSecondaryDisplayContext));
    }

    @Test
    public void testGetUidFromKey() {
        StatusBarNotification sbn = getNotification("pkg", null, "channel");

        assertEquals(UID, StatusBarNotification.getUidFromKey(sbn.getKey()));

        sbn.setOverrideGroupKey("addsToKey");
        assertEquals(UID, StatusBarNotification.getUidFromKey(sbn.getKey()));
    }

    @Test
    public void testGetPkgFromKey() {
        StatusBarNotification sbn = getNotification("pkg", null, "channel");

        assertEquals("pkg", StatusBarNotification.getPkgFromKey(sbn.getKey()));

        sbn.setOverrideGroupKey("addsToKey");
        assertEquals("pkg", StatusBarNotification.getPkgFromKey(sbn.getKey()));
    }

    private StatusBarNotification getNotification(String pkg, String group, String channelId) {
        return getNotification(pkg, getNotificationBuilder(group, channelId));
    }

    private Notification.Builder getNotificationBuilder(String group, String channelId) {
        final Notification.Builder builder = new Notification.Builder(mMockContext, channelId)
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon);

        if (group != null) {
            builder.setGroup(group);
        }
        return builder;
    }

    private StatusBarNotification getNotification(String pkg, Notification.Builder builder) {

        return new StatusBarNotification(
                pkg, pkg, ID, TAG, UID, UID, builder.build(), USER, null, UID);
    }

}
