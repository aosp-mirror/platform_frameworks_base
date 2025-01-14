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

package android.app;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.os.UserHandle;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class NotificationManagerTest {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private NotificationManagerWithMockService mNotificationManager;
    private final FakeClock mClock = new FakeClock();

    private PackageTestableContext mContext;

    @Before
    public void setUp() {
        mContext = new PackageTestableContext(ApplicationProvider.getApplicationContext());
        mNotificationManager = new NotificationManagerWithMockService(mContext, mClock);

        // Caches must be in test mode in order to be used in tests.
        PropertyInvalidatedCache.setTestMode(true);
        mNotificationManager.setChannelCachesToTestMode();
    }

    @After
    public void tearDown() {
        PropertyInvalidatedCache.setTestMode(false);
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void notify_rapidUpdate_isThrottled() throws Exception {
        Notification n = exampleNotification();

        for (int i = 0; i < 100; i++) {
            mNotificationManager.notify(1, n);
            mClock.advanceByMillis(5);
        }

        verify(mNotificationManager.mBackendService, atLeast(20)).enqueueNotificationWithTag(any(),
                any(), any(), anyInt(), any(), anyInt());
        verify(mNotificationManager.mBackendService, atMost(30)).enqueueNotificationWithTag(any(),
                any(), any(), anyInt(), any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void notify_reasonableUpdate_isNotThrottled() throws Exception {
        Notification n = exampleNotification();

        for (int i = 0; i < 100; i++) {
            mNotificationManager.notify(1, n);
            mClock.advanceByMillis(300);
        }

        verify(mNotificationManager.mBackendService, times(100)).enqueueNotificationWithTag(any(),
                any(), any(), anyInt(), any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void notify_rapidAdd_isNotThrottled() throws Exception {
        Notification n = exampleNotification();

        for (int i = 0; i < 100; i++) {
            mNotificationManager.notify(i, n);
            mClock.advanceByMillis(5);
        }

        verify(mNotificationManager.mBackendService, times(100)).enqueueNotificationWithTag(any(),
                any(), any(), anyInt(), any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void notifyAsPackage_rapidUpdate_isThrottled() throws Exception {
        Notification n = exampleNotification();

        for (int i = 0; i < 100; i++) {
            mNotificationManager.notifyAsPackage("some.package.name", "tag", 1, n);
            mClock.advanceByMillis(5);
        }

        verify(mNotificationManager.mBackendService, atLeast(20)).enqueueNotificationWithTag(
                eq("some.package.name"), any(), any(), anyInt(), any(), anyInt());
        verify(mNotificationManager.mBackendService, atMost(30)).enqueueNotificationWithTag(
                eq("some.package.name"), any(), any(), anyInt(), any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void cancel_unnecessaryAndRapid_isThrottled() throws Exception {

        for (int i = 0; i < 100; i++) {
            mNotificationManager.cancel(1);
            mClock.advanceByMillis(5);
        }

        verify(mNotificationManager.mBackendService, atLeast(20)).cancelNotificationWithTag(any(),
                any(), any(), anyInt(), anyInt());
        verify(mNotificationManager.mBackendService, atMost(30)).cancelNotificationWithTag(any(),
                any(), any(), anyInt(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void cancel_unnecessaryButReasonable_isNotThrottled() throws Exception {
        // Scenario: the app tries to repeatedly cancel a single notification, but at a reasonable
        // rate. Strange, but not what we're trying to block with NM_BINDER_PERF_THROTTLE_NOTIFY.
        for (int i = 0; i < 100; i++) {
            mNotificationManager.cancel(1);
            mClock.advanceByMillis(500);
        }

        verify(mNotificationManager.mBackendService, times(100)).cancelNotificationWithTag(any(),
                any(), any(), anyInt(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void cancel_necessaryAndRapid_isNotThrottled() throws Exception {
        // Scenario: the app posts and immediately cancels a bunch of notifications. Strange,
        // but not what we're trying to block with NM_BINDER_PERF_THROTTLE_NOTIFY.
        Notification n = exampleNotification();

        for (int i = 0; i < 100; i++) {
            mNotificationManager.notify(1, n);
            mNotificationManager.cancel(1);
            mClock.advanceByMillis(5);
        }

        verify(mNotificationManager.mBackendService, times(100)).enqueueNotificationWithTag(any(),
                any(), any(), anyInt(), any(), anyInt());
        verify(mNotificationManager.mBackendService, times(100)).cancelNotificationWithTag(any(),
                any(), any(), anyInt(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void cancel_maybeNecessaryAndRapid_isNotThrottled() throws Exception {
        // Scenario: the app posted a lot of notifications, is killed, then restarts (so NM client
        // doesn't know about them), then cancels them one by one. We don't want to throttle this
        // case.
        for (int i = 0; i < 100; i++) {
            mNotificationManager.cancel(i);
            mClock.advanceByMillis(1);
        }

        verify(mNotificationManager.mBackendService, times(100)).cancelNotificationWithTag(any(),
                any(), any(), anyInt(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void enqueue_afterCancel_isNotUpdateAndIsNotThrottled() throws Exception {
        // First, hit the enqueue threshold.
        Notification n = exampleNotification();
        for (int i = 0; i < 100; i++) {
            mNotificationManager.notify(1, n);
            mClock.advanceByMillis(1);
        }
        verify(mNotificationManager.mBackendService, atMost(30)).enqueueNotificationWithTag(any(),
                any(), any(), anyInt(), any(), anyInt());
        reset(mNotificationManager.mBackendService);

        // Now cancel that notification and then post it again. That should work.
        mNotificationManager.cancel(1);
        mNotificationManager.notify(1, n);
        verify(mNotificationManager.mBackendService).enqueueNotificationWithTag(any(), any(), any(),
                anyInt(), any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void enqueue_afterCancelAsPackage_isNotUpdateAndIsNotThrottled() throws Exception {
        // First, hit the enqueue threshold.
        Notification n = exampleNotification();
        for (int i = 0; i < 100; i++) {
            mNotificationManager.notify(1, n);
            mClock.advanceByMillis(1);
        }
        verify(mNotificationManager.mBackendService, atMost(30)).enqueueNotificationWithTag(any(),
                any(), any(), anyInt(), any(), anyInt());
        reset(mNotificationManager.mBackendService);

        // Now cancel that notification and then post it again. That should work.
        mNotificationManager.cancelAsPackage(mContext.getPackageName(), /* tag= */ null, 1);
        mNotificationManager.notify(1, n);
        verify(mNotificationManager.mBackendService).enqueueNotificationWithTag(any(), any(), any(),
                anyInt(), any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void enqueue_afterCancelAll_isNotUpdateAndIsNotThrottled() throws Exception {
        // First, hit the enqueue threshold.
        Notification n = exampleNotification();
        for (int i = 0; i < 100; i++) {
            mNotificationManager.notify(1, n);
            mClock.advanceByMillis(1);
        }
        verify(mNotificationManager.mBackendService, atMost(30)).enqueueNotificationWithTag(any(),
                any(), any(), anyInt(), any(), anyInt());
        reset(mNotificationManager.mBackendService);

        // Now cancel all notifications and then post it again. That should work.
        mNotificationManager.cancelAll();
        mNotificationManager.notify(1, n);
        verify(mNotificationManager.mBackendService).enqueueNotificationWithTag(any(), any(), any(),
                anyInt(), any(), anyInt());
    }

    @Test
    @EnableFlags({Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY,
            Flags.FLAG_NM_BINDER_PERF_LOG_NM_THROTTLING})
    public void notify_rapidUpdate_logsOncePerSecond() throws Exception {
        Notification n = exampleNotification();

        for (int i = 0; i < 650; i++) {
            mNotificationManager.notify(1, n);
            mClock.advanceByMillis(10);
        }

        // Runs for a total of 6.5 seconds, so should log once (when RateEstimator catches up) + 6
        // more times (after 1 second each).
        verify(mNotificationManager.mBackendService, times(7)).incrementCounter(
                eq("notifications.value_client_throttled_notify_update"));
    }

    @Test
    @EnableFlags({Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY,
            Flags.FLAG_NM_BINDER_PERF_LOG_NM_THROTTLING})
    public void cancel_unnecessaryAndRapid_logsOncePerSecond() throws Exception {
        for (int i = 0; i < 650; i++) {
            mNotificationManager.cancel(1);
            mClock.advanceByMillis(10);
        }

        // Runs for a total of 6.5 seconds, so should log once (when RateEstimator catches up) + 6
        // more times (after 1 second each).
        verify(mNotificationManager.mBackendService, times(7)).incrementCounter(
                eq("notifications.value_client_throttled_cancel_duplicate"));
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_CACHE_CHANNELS)
    public void getNotificationChannel_cachedUntilInvalidated() throws Exception {
        // Invalidate the cache first because the cache won't do anything until then
        NotificationManager.invalidateNotificationChannelCache();

        // It doesn't matter what the returned contents are, as long as we return a channel.
        // This setup must set up getNotificationChannels(), as that's the method called.
        when(mNotificationManager.mBackendService.getNotificationChannels(any(), any(),
                anyInt())).thenReturn(new ParceledListSlice<>(List.of(exampleChannel())));

        // ask for the same channel 100 times without invalidating the cache
        for (int i = 0; i < 100; i++) {
            NotificationChannel unused = mNotificationManager.getNotificationChannel("id");
        }

        // invalidate the cache; then ask again
        NotificationManager.invalidateNotificationChannelCache();
        NotificationChannel unused = mNotificationManager.getNotificationChannel("id");

        verify(mNotificationManager.mBackendService, times(2))
                .getNotificationChannels(any(), any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_CACHE_CHANNELS)
    public void getNotificationChannel_sameApp_oneCall() throws Exception {
        NotificationManager.invalidateNotificationChannelCache();

        NotificationChannel c1 = new NotificationChannel("id1", "name1",
                NotificationManager.IMPORTANCE_DEFAULT);
        NotificationChannel c2 = new NotificationChannel("id2", "name2",
                NotificationManager.IMPORTANCE_NONE);

        when(mNotificationManager.mBackendService.getNotificationChannels(any(), any(),
                anyInt())).thenReturn(new ParceledListSlice<>(List.of(c1, c2)));

        assertThat(mNotificationManager.getNotificationChannel("id1")).isEqualTo(c1);
        assertThat(mNotificationManager.getNotificationChannel("id2")).isEqualTo(c2);
        assertThat(mNotificationManager.getNotificationChannel("id3")).isNull();

        verify(mNotificationManager.mBackendService, times(1))
                .getNotificationChannels(any(), any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_CACHE_CHANNELS)
    public void getNotificationChannels_cachedUntilInvalidated() throws Exception {
        NotificationManager.invalidateNotificationChannelCache();
        when(mNotificationManager.mBackendService.getNotificationChannels(any(), any(),
                anyInt())).thenReturn(new ParceledListSlice<>(List.of(exampleChannel())));

        // ask for channels 5 times without invalidating the cache
        for (int i = 0; i < 5; i++) {
            List<NotificationChannel> unused = mNotificationManager.getNotificationChannels();
        }

        // invalidate the cache; then ask again
        NotificationManager.invalidateNotificationChannelCache();
        List<NotificationChannel> res = mNotificationManager.getNotificationChannels();

        verify(mNotificationManager.mBackendService, times(2))
                .getNotificationChannels(any(), any(), anyInt());
        assertThat(res).containsExactlyElementsIn(List.of(exampleChannel()));
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_CACHE_CHANNELS)
    public void getNotificationChannel_channelAndConversationLookup() throws Exception {
        NotificationManager.invalidateNotificationChannelCache();

        // Full list of channels: c1; conv1 = child of c1; c2 is unrelated
        NotificationChannel c1 = new NotificationChannel("id", "name",
                NotificationManager.IMPORTANCE_DEFAULT);
        NotificationChannel conv1 = new NotificationChannel("", "name_conversation",
                NotificationManager.IMPORTANCE_DEFAULT);
        conv1.setConversationId("id", "id_conversation");
        NotificationChannel c2 = new NotificationChannel("other", "name2",
                NotificationManager.IMPORTANCE_DEFAULT);

        when(mNotificationManager.mBackendService.getNotificationChannels(any(), any(),
                anyInt())).thenReturn(new ParceledListSlice<>(List.of(c1, conv1, c2)));

        // Lookup for channel c1 and c2: returned as expected
        assertThat(mNotificationManager.getNotificationChannel("id")).isEqualTo(c1);
        assertThat(mNotificationManager.getNotificationChannel("other")).isEqualTo(c2);

        // Lookup for conv1 should return conv1
        assertThat(mNotificationManager.getNotificationChannel("id", "id_conversation")).isEqualTo(
                conv1);

        // Lookup for a different conversation channel that doesn't exist, whose parent channel id
        // is "id", should return c1
        assertThat(mNotificationManager.getNotificationChannel("id", "nonexistent")).isEqualTo(c1);

        // Lookup of a nonexistent channel is null
        assertThat(mNotificationManager.getNotificationChannel("id3")).isNull();

        // All of that should have been one call to getNotificationChannels()
        verify(mNotificationManager.mBackendService, times(1))
                .getNotificationChannels(any(), any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_CACHE_CHANNELS)
    public void getNotificationChannel_differentPackages() throws Exception {
        NotificationManager.invalidateNotificationChannelCache();
        final String pkg1 = "one";
        final String pkg2 = "two";
        final int userId = 0;
        final int userId1 = 1;

        // multiple channels with the same ID, but belonging to different packages/users
        NotificationChannel channel1 = new NotificationChannel("id", "name1",
                NotificationManager.IMPORTANCE_DEFAULT);
        NotificationChannel channel2 = channel1.copy();
        channel2.setName("name2");
        NotificationChannel channel3 = channel1.copy();
        channel3.setName("name3");

        when(mNotificationManager.mBackendService.getNotificationChannels(any(), eq(pkg1),
                eq(userId))).thenReturn(new ParceledListSlice<>(List.of(channel1)));
        when(mNotificationManager.mBackendService.getNotificationChannels(any(), eq(pkg2),
                eq(userId))).thenReturn(new ParceledListSlice<>(List.of(channel2)));
        when(mNotificationManager.mBackendService.getNotificationChannels(any(), eq(pkg1),
                eq(userId1))).thenReturn(new ParceledListSlice<>(List.of(channel3)));

        // set our context to pretend to be from package 1 and userId 0
        mContext.setParameters(pkg1, pkg1, userId);
        assertThat(mNotificationManager.getNotificationChannel("id")).isEqualTo(channel1);

        // now package 2
        mContext.setParameters(pkg2, pkg2, userId);
        assertThat(mNotificationManager.getNotificationChannel("id")).isEqualTo(channel2);

        // now pkg1 for a different user
        mContext.setParameters(pkg1, pkg1, userId1);
        assertThat(mNotificationManager.getNotificationChannel("id")).isEqualTo(channel3);

        // Those should have been three different calls
        verify(mNotificationManager.mBackendService, times(3))
                .getNotificationChannels(any(), any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_CACHE_CHANNELS)
    public void getNotificationChannelGroup_cachedUntilInvalidated() throws Exception {
        // Data setup: group has some channels in it
        NotificationChannelGroup g1 = new NotificationChannelGroup("g1", "group one");

        NotificationChannel nc1 = new NotificationChannel("nc1", "channel one",
                NotificationManager.IMPORTANCE_DEFAULT);
        nc1.setGroup("g1");
        NotificationChannel nc2 = new NotificationChannel("nc2", "channel two",
                NotificationManager.IMPORTANCE_DEFAULT);
        nc2.setGroup("g1");

        NotificationManager.invalidateNotificationChannelCache();
        NotificationManager.invalidateNotificationChannelGroupCache();
        when(mNotificationManager.mBackendService.getNotificationChannelGroupsWithoutChannels(
                any())).thenReturn(new ParceledListSlice<>(List.of(g1)));

        // getting notification channel groups also involves looking for channels
        when(mNotificationManager.mBackendService.getNotificationChannels(any(), any(), anyInt()))
                .thenReturn(new ParceledListSlice<>(List.of(nc1, nc2)));

        // ask for group 5 times without invalidating the cache
        for (int i = 0; i < 5; i++) {
            NotificationChannelGroup unused = mNotificationManager.getNotificationChannelGroup(
                    "g1");
        }

        // invalidate group cache but not channels cache; then ask for groups again
        NotificationManager.invalidateNotificationChannelGroupCache();
        NotificationChannelGroup receivedG1 = mNotificationManager.getNotificationChannelGroup(
                "g1");

        verify(mNotificationManager.mBackendService, times(1))
                .getNotificationChannels(any(), any(), anyInt());
        verify(mNotificationManager.mBackendService,
                times(2)).getNotificationChannelGroupsWithoutChannels(any());

        // Also confirm that we got sensible information in the return value
        assertThat(receivedG1).isNotNull();
        assertThat(receivedG1.getChannels()).hasSize(2);
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_CACHE_CHANNELS)
    public void getNotificationChannelGroups_cachedUntilInvalidated() throws Exception {
        NotificationChannelGroup g1 = new NotificationChannelGroup("g1", "group one");
        NotificationChannelGroup g2 = new NotificationChannelGroup("g2", "group two");
        NotificationChannel nc1 = new NotificationChannel("nc1", "channel one",
                NotificationManager.IMPORTANCE_DEFAULT);
        nc1.setGroup("g1");

        NotificationManager.invalidateNotificationChannelCache();
        NotificationManager.invalidateNotificationChannelGroupCache();
        when(mNotificationManager.mBackendService.getNotificationChannelGroupsWithoutChannels(
                any())).thenReturn(new ParceledListSlice<>(List.of(g1, g2)));
        when(mNotificationManager.mBackendService.getNotificationChannels(any(), any(), anyInt()))
                .thenReturn(new ParceledListSlice<>(List.of(nc1)));

        // ask for groups 5 times without invalidating the cache
        for (int i = 0; i < 5; i++) {
            List<NotificationChannelGroup> unused =
                    mNotificationManager.getNotificationChannelGroups();
        }

        // invalidate group cache; ask again
        NotificationManager.invalidateNotificationChannelGroupCache();
        List<NotificationChannelGroup> result = mNotificationManager.getNotificationChannelGroups();

        verify(mNotificationManager.mBackendService,
                times(2)).getNotificationChannelGroupsWithoutChannels(any());

        NotificationChannelGroup expectedG1 = g1.clone();
        expectedG1.setChannels(List.of(nc1));
        NotificationChannelGroup expectedG2 = g2.clone();
        expectedG2.setChannels(new ArrayList<>());

        assertThat(result).containsExactly(expectedG1, expectedG2);
    }

    @Test
    @EnableFlags({Flags.FLAG_MODES_API, Flags.FLAG_MODES_UI})
    public void areAutomaticZenRulesUserManaged_handheld_isTrue() {
        PackageManager pm = mock(PackageManager.class);
        when(pm.hasSystemFeature(any())).thenReturn(false);
        mContext.setPackageManager(pm);

        assertThat(mNotificationManager.areAutomaticZenRulesUserManaged()).isTrue();
    }

    @Test
    @EnableFlags({Flags.FLAG_MODES_API, Flags.FLAG_MODES_UI})
    public void areAutomaticZenRulesUserManaged_auto_isFalse() {
        PackageManager pm = mock(PackageManager.class);
        when(pm.hasSystemFeature(eq(PackageManager.FEATURE_AUTOMOTIVE))).thenReturn(true);
        mContext.setPackageManager(pm);

        assertThat(mNotificationManager.areAutomaticZenRulesUserManaged()).isFalse();
    }

    @Test
    @EnableFlags({Flags.FLAG_MODES_API, Flags.FLAG_MODES_UI})
    public void areAutomaticZenRulesUserManaged_tv_isFalse() {
        PackageManager pm = mock(PackageManager.class);
        when(pm.hasSystemFeature(eq(PackageManager.FEATURE_LEANBACK))).thenReturn(true);
        mContext.setPackageManager(pm);

        assertThat(mNotificationManager.areAutomaticZenRulesUserManaged()).isFalse();
    }

    @Test
    @EnableFlags({Flags.FLAG_MODES_API, Flags.FLAG_MODES_UI})
    public void areAutomaticZenRulesUserManaged_watch_isFalse() {
        PackageManager pm = mock(PackageManager.class);
        when(pm.hasSystemFeature(eq(PackageManager.FEATURE_WATCH))).thenReturn(true);
        mContext.setPackageManager(pm);

        assertThat(mNotificationManager.areAutomaticZenRulesUserManaged()).isFalse();
    }

    private Notification exampleNotification() {
        return new Notification.Builder(mContext, "channel")
                .setSmallIcon(android.R.drawable.star_big_on)
                .build();
    }

    private NotificationChannel exampleChannel() {
        return new NotificationChannel("id", "channel_name",
                NotificationManager.IMPORTANCE_DEFAULT);
    }

    private static class NotificationManagerWithMockService extends NotificationManager {

        private final INotificationManager mBackendService;

        NotificationManagerWithMockService(Context context, InstantSource clock) {
            super(context, clock);
            mBackendService = mock(INotificationManager.class);
        }

        @Override
        public INotificationManager service() {
            return mBackendService;
        }
    }

    // Helper context wrapper class where we can control just the return values of getPackageName,
    // getOpPackageName, and getUserId (used in getNotificationChannels).
    private static class PackageTestableContext extends ContextWrapper {
        private PackageManager mPm;
        private String mPackage;
        private String mOpPackage;
        private Integer mUserId;

        PackageTestableContext(Context base) {
            super(base);
        }

        void setPackageManager(@Nullable PackageManager pm) {
            mPm = pm;
        }

        void setParameters(String packageName, String opPackageName, int userId) {
            mPackage = packageName;
            mOpPackage = opPackageName;
            mUserId = userId;
        }

        @Override
        public PackageManager getPackageManager() {
            if (mPm != null) return mPm;
            return super.getPackageManager();
        }

        @Override
        public String getPackageName() {
            if (mPackage != null) return mPackage;
            return super.getPackageName();
        }

        @Override
        public String getOpPackageName() {
            if (mOpPackage != null) return mOpPackage;
            return super.getOpPackageName();
        }

        @Override
        public int getUserId() {
            if (mUserId != null) return mUserId;
            return super.getUserId();
        }

        @Override
        public UserHandle getUser() {
            if (mUserId != null) return UserHandle.of(mUserId);
            return super.getUser();
        }
    }

    private static class FakeClock implements InstantSource {

        private long mNowMillis = 441644400000L;

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(mNowMillis);
        }

        private void advanceByMillis(long millis) {
            mNowMillis += millis;
        }
    }
}
