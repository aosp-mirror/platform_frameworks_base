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
package com.android.server.notification;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_DEFAULT;
import static android.app.AppOpsManager.OP_SYSTEM_ALERT_WINDOW;
import static android.app.NotificationChannel.CONVERSATION_CHANNEL_ID_FORMAT;
import static android.app.NotificationChannel.USER_LOCKED_IMPORTANCE;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_ALL;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_NONE;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_SELECTED;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MAX;
import static android.app.NotificationManager.IMPORTANCE_NONE;
import static android.app.NotificationManager.IMPORTANCE_UNSPECIFIED;
import static android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION;
import static android.media.AudioAttributes.USAGE_NOTIFICATION;
import static android.os.UserHandle.USER_SYSTEM;
import static android.util.StatsLog.ANNOTATION_ID_IS_UID;

import static com.android.internal.util.FrameworkStatsLog.PACKAGE_NOTIFICATION_CHANNEL_PREFERENCES;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_NOTIFICATION_PREFERENCES;
import static com.android.os.AtomsProto.PackageNotificationChannelPreferences.CHANNEL_ID_FIELD_NUMBER;
import static com.android.os.AtomsProto.PackageNotificationChannelPreferences.CHANNEL_NAME_FIELD_NUMBER;
import static com.android.os.AtomsProto.PackageNotificationChannelPreferences.IMPORTANCE_FIELD_NUMBER;
import static com.android.os.AtomsProto.PackageNotificationChannelPreferences.IS_CONVERSATION_FIELD_NUMBER;
import static com.android.os.AtomsProto.PackageNotificationChannelPreferences.IS_DELETED_FIELD_NUMBER;
import static com.android.os.AtomsProto.PackageNotificationChannelPreferences.IS_DEMOTED_CONVERSATION_FIELD_NUMBER;
import static com.android.os.AtomsProto.PackageNotificationChannelPreferences.IS_IMPORTANT_CONVERSATION_FIELD_NUMBER;
import static com.android.os.AtomsProto.PackageNotificationChannelPreferences.UID_FIELD_NUMBER;
import static com.android.server.notification.PreferencesHelper.DEFAULT_BUBBLE_PREFERENCE;
import static com.android.server.notification.PreferencesHelper.NOTIFICATION_CHANNEL_COUNT_LIMIT;
import static com.android.server.notification.PreferencesHelper.NOTIFICATION_CHANNEL_GROUP_COUNT_LIMIT;
import static com.android.server.notification.PreferencesHelper.UNKNOWN_UID;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.fail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.AttributionSource;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IContentProvider;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.service.notification.ConversationChannelWrapper;
import android.service.notification.nano.RankingHelperProto;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.TestableContentResolver;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Pair;
import android.util.StatsEvent;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.os.AtomsProto.PackageNotificationPreferences;
import com.android.server.UiServiceTestCase;
import com.android.server.notification.PermissionHelper.PackagePermission;

import com.google.common.collect.ImmutableList;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PreferencesHelperTest extends UiServiceTestCase {
    private static final int UID_N_MR1 = 0;
    private static final int UID_HEADLESS = 1000000;
    private static final UserHandle USER = UserHandle.of(0);
    private static final int UID_O = 1111;
    private static final int UID_P = 2222;
    private static final String SYSTEM_PKG = "android";
    private static final int SYSTEM_UID = 1000;
    private static final UserHandle USER2 = UserHandle.of(10);
    private static final String TEST_CHANNEL_ID = "test_channel_id";
    private static final String TEST_AUTHORITY = "test";
    private static final Uri SOUND_URI =
            Uri.parse("content://" + TEST_AUTHORITY + "/internal/audio/media/10");
    private static final Uri CANONICAL_SOUND_URI =
            Uri.parse("content://" + TEST_AUTHORITY
                    + "/internal/audio/media/10?title=Test&canonical=1");

    @Mock PermissionHelper mPermissionHelper;
    @Mock RankingHandler mHandler;
    @Mock PackageManager mPm;
    IContentProvider mTestIContentProvider;
    @Mock Context mContext;
    @Mock ZenModeHelper mMockZenModeHelper;
    @Mock AppOpsManager mAppOpsManager;

    private NotificationManager.Policy mTestNotificationPolicy;

    private PreferencesHelper mHelper;
    private AudioAttributes mAudioAttributes;
    private NotificationChannelLoggerFake mLogger = new NotificationChannelLoggerFake();
    private WrappedSysUiStatsEvent.WrappedBuilderFactory mStatsEventBuilderFactory;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        final ApplicationInfo legacy = new ApplicationInfo();
        legacy.targetSdkVersion = Build.VERSION_CODES.N_MR1;
        final ApplicationInfo upgrade = new ApplicationInfo();
        upgrade.targetSdkVersion = Build.VERSION_CODES.O;
        when(mPm.getApplicationInfoAsUser(eq(PKG_N_MR1), anyInt(), anyInt())).thenReturn(legacy);
        when(mPm.getApplicationInfoAsUser(eq(PKG_O), anyInt(), anyInt())).thenReturn(upgrade);
        when(mPm.getApplicationInfoAsUser(eq(PKG_P), anyInt(), anyInt())).thenReturn(upgrade);
        when(mPm.getApplicationInfoAsUser(eq(SYSTEM_PKG), anyInt(), anyInt())).thenReturn(upgrade);
        when(mPm.getPackageUidAsUser(eq(PKG_N_MR1), anyInt())).thenReturn(UID_N_MR1);
        when(mPm.getPackageUidAsUser(eq(PKG_O), anyInt())).thenReturn(UID_O);
        when(mPm.getPackageUidAsUser(eq(PKG_P), anyInt())).thenReturn(UID_P);
        when(mPm.getPackageUidAsUser(eq(SYSTEM_PKG), anyInt())).thenReturn(SYSTEM_UID);
        PackageInfo info = mock(PackageInfo.class);
        info.signatures = new Signature[] {mock(Signature.class)};
        when(mPm.getPackageInfoAsUser(eq(SYSTEM_PKG), anyInt(), anyInt())).thenReturn(info);
        when(mPm.getPackageInfoAsUser(eq(PKG_N_MR1), anyInt(), anyInt()))
                .thenReturn(mock(PackageInfo.class));
        when(mContext.getResources()).thenReturn(
                InstrumentationRegistry.getContext().getResources());
        when(mContext.getContentResolver()).thenReturn(
                InstrumentationRegistry.getContext().getContentResolver());
        when(mContext.getPackageManager()).thenReturn(mPm);
        when(mContext.getApplicationInfo()).thenReturn(legacy);
        // most tests assume badging is enabled
        TestableContentResolver contentResolver = getContext().getContentResolver();
        contentResolver.setFallbackToExisting(false);
        Secure.putIntForUser(contentResolver,
                Secure.NOTIFICATION_BADGING, 1, UserHandle.getUserId(UID_N_MR1));
        Secure.putIntForUser(contentResolver, Secure.NOTIFICATION_BUBBLES, 1,
                UserHandle.getUserId(UID_N_MR1));

        ContentProvider testContentProvider = mock(ContentProvider.class);
        mTestIContentProvider = mock(IContentProvider.class, invocation -> {
            throw new UnsupportedOperationException("unimplemented mock method");
        });
        doAnswer(invocation -> {
            AttributionSource attributionSource = invocation.getArgument(0);
            Uri uri = invocation.getArgument(1);
            RemoteCallback cb = invocation.getArgument(2);
            IContentProvider mock = (IContentProvider) (invocation.getMock());
            AsyncTask.SERIAL_EXECUTOR.execute(() -> {
                final Bundle bundle = new Bundle();
                try {
                    bundle.putParcelable(ContentResolver.REMOTE_CALLBACK_RESULT,
                            mock.canonicalize(attributionSource, uri));
                } catch (RemoteException e) { /* consume */ }
                cb.sendResult(bundle);
            });
            return null;
        }).when(mTestIContentProvider).canonicalizeAsync(any(), any(), any());
        doAnswer(invocation -> {
            AttributionSource attributionSource = invocation.getArgument(0);
            Uri uri = invocation.getArgument(1);
            RemoteCallback cb = invocation.getArgument(2);
            IContentProvider mock = (IContentProvider) (invocation.getMock());
            AsyncTask.SERIAL_EXECUTOR.execute(() -> {
                final Bundle bundle = new Bundle();
                try {
                    bundle.putParcelable(ContentResolver.REMOTE_CALLBACK_RESULT,
                            mock.uncanonicalize(attributionSource, uri));
                } catch (RemoteException e) { /* consume */ }
                cb.sendResult(bundle);
            });
            return null;
        }).when(mTestIContentProvider).uncanonicalizeAsync(any(), any(), any());
        doAnswer(invocation -> {
            Uri uri = invocation.getArgument(0);
            RemoteCallback cb = invocation.getArgument(1);
            IContentProvider mock = (IContentProvider) (invocation.getMock());
            AsyncTask.SERIAL_EXECUTOR.execute(() -> {
                final Bundle bundle = new Bundle();
                try {
                    bundle.putString(ContentResolver.REMOTE_CALLBACK_RESULT, mock.getType(uri));
                } catch (RemoteException e) { /* consume */ }
                cb.sendResult(bundle);
            });
            return null;
        }).when(mTestIContentProvider).getTypeAsync(any(), any());

        when(testContentProvider.getIContentProvider()).thenReturn(mTestIContentProvider);
        contentResolver.addProvider(TEST_AUTHORITY, testContentProvider);

        doReturn(CANONICAL_SOUND_URI)
                .when(mTestIContentProvider).canonicalize(any(), eq(SOUND_URI));
        doReturn(CANONICAL_SOUND_URI)
                .when(mTestIContentProvider).canonicalize(any(), eq(CANONICAL_SOUND_URI));
        doReturn(SOUND_URI)
                .when(mTestIContentProvider).uncanonicalize(any(), eq(CANONICAL_SOUND_URI));

        mTestNotificationPolicy = new NotificationManager.Policy(0, 0, 0, 0,
                NotificationManager.Policy.STATE_CHANNELS_BYPASSING_DND, 0);
        when(mMockZenModeHelper.getNotificationPolicy()).thenReturn(mTestNotificationPolicy);
        when(mAppOpsManager.noteOpNoThrow(anyInt(), anyInt(),
                anyString(), eq(null), anyString())).thenReturn(MODE_DEFAULT);

        ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> appPermissions = new ArrayMap<>();
        appPermissions.put(new Pair(UID_P, PKG_P), new Pair(true, false));
        appPermissions.put(new Pair(UID_O, PKG_O), new Pair(true, false));
        appPermissions.put(new Pair(UID_N_MR1, PKG_N_MR1), new Pair(true, false));

        when(mPermissionHelper.getNotificationPermissionValues(USER_SYSTEM))
                .thenReturn(appPermissions);

        mStatsEventBuilderFactory = new WrappedSysUiStatsEvent.WrappedBuilderFactory();

        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger, mAppOpsManager, mStatsEventBuilderFactory, false);
        resetZenModeHelper();

        mAudioAttributes = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                .build();

        // make sure that the settings for review notification permissions are unset to begin with
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                NotificationManagerService.REVIEW_NOTIF_STATE_UNKNOWN);
    }

    private ByteArrayOutputStream writeXmlAndPurge(
            String pkg, int uid, boolean forBackup, int userId, String... channelIds)
            throws Exception {
        TypedXmlSerializer serializer = Xml.newFastSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
        serializer.startDocument(null, true);
        mHelper.writeXml(serializer, forBackup, userId);
        serializer.endDocument();
        serializer.flush();
        for (String channelId : channelIds) {
            mHelper.permanentlyDeleteNotificationChannel(pkg, uid, channelId);
        }
        return baos;
    }

    private void loadStreamXml(ByteArrayOutputStream stream, boolean forRestore, int userId)
            throws Exception {
        loadByteArrayXml(stream.toByteArray(), forRestore, userId);
    }

    private void loadByteArrayXml(byte[] byteArray, boolean forRestore, int userId)
            throws Exception {
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(byteArray)), null);
        parser.nextTag();
        mHelper.readXml(parser, forRestore, userId);
    }

    private void compareChannels(NotificationChannel expected, NotificationChannel actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getDescription(), actual.getDescription());
        assertEquals(expected.shouldVibrate(), actual.shouldVibrate());
        assertEquals(expected.shouldShowLights(), actual.shouldShowLights());
        assertEquals(expected.getImportance(), actual.getImportance());
        assertEquals(expected.getLockscreenVisibility(), actual.getLockscreenVisibility());
        assertEquals(expected.getSound(), actual.getSound());
        assertEquals(expected.canBypassDnd(), actual.canBypassDnd());
        assertTrue(Arrays.equals(expected.getVibrationPattern(), actual.getVibrationPattern()));
        assertEquals(expected.getGroup(), actual.getGroup());
        assertEquals(expected.getAudioAttributes(), actual.getAudioAttributes());
        assertEquals(expected.getLightColor(), actual.getLightColor());
        assertEquals(expected.getParentChannelId(), actual.getParentChannelId());
        assertEquals(expected.getConversationId(), actual.getConversationId());
        assertEquals(expected.isDemoted(), actual.isDemoted());
    }

    private void compareChannelsParentChild(NotificationChannel parent,
            NotificationChannel actual, String conversationId) {
        assertEquals(parent.getName(), actual.getName());
        assertEquals(parent.getDescription(), actual.getDescription());
        assertEquals(parent.shouldVibrate(), actual.shouldVibrate());
        assertEquals(parent.shouldShowLights(), actual.shouldShowLights());
        assertEquals(parent.getImportance(), actual.getImportance());
        assertEquals(parent.getLockscreenVisibility(), actual.getLockscreenVisibility());
        assertEquals(parent.getSound(), actual.getSound());
        assertEquals(parent.canBypassDnd(), actual.canBypassDnd());
        assertTrue(Arrays.equals(parent.getVibrationPattern(), actual.getVibrationPattern()));
        assertEquals(parent.getGroup(), actual.getGroup());
        assertEquals(parent.getAudioAttributes(), actual.getAudioAttributes());
        assertEquals(parent.getLightColor(), actual.getLightColor());
        assertEquals(parent.getId(), actual.getParentChannelId());
        assertEquals(conversationId, actual.getConversationId());
    }

    private void compareGroups(NotificationChannelGroup expected, NotificationChannelGroup actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getDescription(), actual.getDescription());
        assertEquals(expected.isBlocked(), actual.isBlocked());
    }

    private NotificationChannel getChannel() {
        return new NotificationChannel("id", "name", IMPORTANCE_LOW);
    }

    private NotificationChannel findChannel(List<NotificationChannel> channels, String id) {
        for (NotificationChannel channel : channels) {
            if (channel.getId().equals(id)) {
                return channel;
            }
        }
        return null;
    }

    private void resetZenModeHelper() {
        reset(mMockZenModeHelper);
        when(mMockZenModeHelper.getNotificationPolicy()).thenReturn(mTestNotificationPolicy);
    }

    private void setUpPackageWithUid(String packageName, int uid) throws Exception {
        when(mPm.getApplicationInfoAsUser(eq(packageName), anyInt(), anyInt()))
                .thenReturn(new ApplicationInfo());
        when(mPm.getPackageUidAsUser(eq(packageName), anyInt())).thenReturn(uid);
    }

    @Test
    public void testWriteXml_onlyBackupsTargetUser() throws Exception {
        // Setup package notifications.
        String package0 = "test.package.user0";
        int uid0 = 1001;
        setUpPackageWithUid(package0, uid0);
        NotificationChannel channel0 = new NotificationChannel("id0", "name0", IMPORTANCE_HIGH);
        assertTrue(mHelper.createNotificationChannel(package0, uid0, channel0, true, false));

        String package10 = "test.package.user10";
        int uid10 = 1001001;
        setUpPackageWithUid(package10, uid10);
        NotificationChannel channel10 = new NotificationChannel("id10", "name10", IMPORTANCE_HIGH);
        assertTrue(mHelper.createNotificationChannel(package10, uid10, channel10, true, false));

        ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> appPermissions = new ArrayMap<>();
        appPermissions.put(new Pair(uid0, package0), new Pair(false, false));
        appPermissions.put(new Pair(uid10, package10), new Pair(true, false));

        when(mPermissionHelper.getNotificationPermissionValues(10))
                .thenReturn(appPermissions);

        ByteArrayOutputStream baos = writeXmlAndPurge(package10, uid10, true, 10);

        // Reset state.
        mHelper.onPackagesChanged(true, 0, new String[] {package0}, new int[] {uid0});
        mHelper.onPackagesChanged(true, 10, new String[] {package10}, new int[] {uid10});

        // Parse backup data.
        loadStreamXml(baos, true, 0);
        loadStreamXml(baos, true, 10);

        assertEquals(
                channel10,
                mHelper.getNotificationChannel(package10, uid10, channel10.getId(), false));
        assertNull(mHelper.getNotificationChannel(package0, uid0, channel0.getId(), false));
    }

    @Test
    public void testReadXml_onlyRestoresTargetUser() throws Exception {
        // Setup package in user 0.
        String package0 = "test.package.user0";
        int uid0 = 1001;
        setUpPackageWithUid(package0, uid0);
        NotificationChannel channel0 = new NotificationChannel("id0", "name0", IMPORTANCE_HIGH);
        assertTrue(mHelper.createNotificationChannel(package0, uid0, channel0, true, false));

        ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> appPermissions = new ArrayMap<>();
        appPermissions.put(new Pair(uid0, package0), new Pair(true, false));

        when(mPermissionHelper.getNotificationPermissionValues(USER_SYSTEM))
                .thenReturn(appPermissions);

        ByteArrayOutputStream baos = writeXmlAndPurge(package0, uid0, true, 0);

        // Reset state.
        mHelper.onPackagesChanged(true, 0, new String[] {package0}, new int[] {uid0});

        // Restore should convert the uid according to the target user.
        int expectedUid = 1001001;
        setUpPackageWithUid(package0, expectedUid);
        // Parse backup data.
        loadStreamXml(baos, true, 10);

        assertEquals(
                channel0,
                mHelper.getNotificationChannel(package0, expectedUid, channel0.getId(), false));
        assertNull(mHelper.getNotificationChannel(package0, uid0, channel0.getId(), false));
    }

    @Test
    public void testChannelXml() throws Exception {
        NotificationChannelGroup ncg = new NotificationChannelGroup("1", "bye");
        ncg.setBlocked(true);
        ncg.setDescription("group desc");
        NotificationChannelGroup ncg2 = new NotificationChannelGroup("2", "hello");
        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel2.setDescription("descriptions for all");
        channel2.setSound(new Uri.Builder().scheme("test").build(), mAudioAttributes);
        channel2.enableLights(true);
        channel2.setBypassDnd(true);
        channel2.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        channel2.enableVibration(true);
        channel2.setGroup(ncg.getId());
        channel2.setVibrationPattern(new long[]{100, 67, 145, 156});
        channel2.setLightColor(Color.BLUE);
        channel2.setConversationId("id1", "conversation");
        channel2.setDemoted(true);

        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg, true);
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg2, true);
        assertTrue(mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel1, true, false));
        assertTrue(mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel2, false, false));

        mHelper.setShowBadge(PKG_N_MR1, UID_N_MR1, true);

        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_N_MR1, UID_N_MR1, false,
                UserHandle.USER_ALL, channel1.getId(), channel2.getId(),
                NotificationChannel.DEFAULT_CHANNEL_ID);
        mHelper.onPackagesChanged(true, UserHandle.myUserId(), new String[]{PKG_N_MR1}, new int[]{
                UID_N_MR1});

        loadStreamXml(baos, false, UserHandle.USER_ALL);

        assertTrue(mHelper.canShowBadge(PKG_N_MR1, UID_N_MR1));
        assertEquals(channel1,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, channel1.getId(), false));
        compareChannels(channel2,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, channel2.getId(), false));

        List<NotificationChannelGroup> actualGroups = mHelper.getNotificationChannelGroups(
                PKG_N_MR1, UID_N_MR1, false, true, false).getList();
        boolean foundNcg = false;
        for (NotificationChannelGroup actual : actualGroups) {
            if (ncg.getId().equals(actual.getId())) {
                foundNcg = true;
                compareGroups(ncg, actual);
            } else if (ncg2.getId().equals(actual.getId())) {
                compareGroups(ncg2, actual);
            }
        }
        assertTrue(foundNcg);

        boolean foundChannel2Group = false;
        for (NotificationChannelGroup actual : actualGroups) {
            if (channel2.getGroup().equals(actual.getChannels().get(0).getGroup())) {
                foundChannel2Group = true;
                break;
            }
        }
        assertTrue(foundChannel2Group);
    }

    @Test
    public void testChannelXmlForBackup() throws Exception {
        NotificationChannelGroup ncg = new NotificationChannelGroup("1", "bye");
        NotificationChannelGroup ncg2 = new NotificationChannelGroup("2", "hello");
        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel2.setDescription("descriptions for all");
        channel2.setSound(SOUND_URI, mAudioAttributes);
        channel2.enableLights(true);
        channel2.setBypassDnd(true);
        channel2.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        channel2.enableVibration(false);
        channel2.setGroup(ncg.getId());
        channel2.setLightColor(Color.BLUE);
        NotificationChannel channel3 = new NotificationChannel("id3", "NAM3", IMPORTANCE_HIGH);
        channel3.enableVibration(true);

        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg, true);
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg2, true);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel1, true, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel2, false, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel3, false, false);
        mHelper.createNotificationChannel(PKG_O, UID_O, getChannel(), true, false);

        mHelper.setShowBadge(PKG_N_MR1, UID_N_MR1, true);
        mHelper.setInvalidMessageSent(PKG_P, UID_P);
        mHelper.setValidMessageSent(PKG_P, UID_P);
        mHelper.setInvalidMsgAppDemoted(PKG_P, UID_P, true);
        mHelper.setValidBubbleSent(PKG_P, UID_P);

        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_N_MR1, UID_N_MR1, true,
                USER_SYSTEM, channel1.getId(), channel2.getId(), channel3.getId(),
                NotificationChannel.DEFAULT_CHANNEL_ID);
        mHelper.onPackagesChanged(true, UserHandle.myUserId(), new String[]{PKG_N_MR1, PKG_O},
                new int[]{UID_N_MR1, UID_O});

        mHelper.setShowBadge(PKG_O, UID_O, true);

        loadStreamXml(baos, true, USER_SYSTEM);

        assertTrue(mHelper.canShowBadge(PKG_N_MR1, UID_N_MR1));
        assertTrue(mHelper.hasSentInvalidMsg(PKG_P, UID_P));
        assertFalse(mHelper.hasSentInvalidMsg(PKG_N_MR1, UID_N_MR1));
        assertTrue(mHelper.hasSentValidMsg(PKG_P, UID_P));
        assertTrue(mHelper.didUserEverDemoteInvalidMsgApp(PKG_P, UID_P));
        assertTrue(mHelper.hasSentValidBubble(PKG_P, UID_P));
        assertEquals(channel1,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, channel1.getId(), false));
        compareChannels(channel2,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, channel2.getId(), false));
        compareChannels(channel3,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, channel3.getId(), false));

        List<NotificationChannelGroup> actualGroups = mHelper.getNotificationChannelGroups(
                PKG_N_MR1, UID_N_MR1, false, true, false).getList();
        boolean foundNcg = false;
        for (NotificationChannelGroup actual : actualGroups) {
            if (ncg.getId().equals(actual.getId())) {
                foundNcg = true;
                compareGroups(ncg, actual);
            } else if (ncg2.getId().equals(actual.getId())) {
                compareGroups(ncg2, actual);
            }
        }
        assertTrue(foundNcg);

        boolean foundChannel2Group = false;
        for (NotificationChannelGroup actual : actualGroups) {
            if (channel2.getGroup().equals(actual.getChannels().get(0).getGroup())) {
                foundChannel2Group = true;
                break;
            }
        }
        assertTrue(foundChannel2Group);
    }

    @Test
    public void testReadXml_oldXml_migrates() throws Exception {
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger, mAppOpsManager, mStatsEventBuilderFactory, true);

        String xml = "<ranking version=\"2\">\n"
                + "<package name=\"" + PKG_N_MR1 + "\" uid=\"" + UID_N_MR1
                + "\" show_badge=\"true\">\n"
                + "<channel id=\"idn\" name=\"name\" importance=\"2\" />\n"
                + "<channel id=\"miscellaneous\" name=\"Uncategorized\" />\n"
                + "</package>\n"
                + "<package name=\"" + PKG_O + "\" uid=\"" + UID_O + "\" importance=\"0\">\n"
                + "<channel id=\"ido\" name=\"name2\" importance=\"2\" show_badge=\"true\"/>\n"
                + "</package>\n"
                + "<package name=\"" + PKG_P + "\" uid=\"" + UID_P + "\" importance=\"2\">\n"
                + "<channel id=\"idp\" name=\"name3\" importance=\"4\" locked=\"2\" />\n"
                + "</package>\n"
                + "</ranking>\n";

        loadByteArrayXml(xml.getBytes(), false, USER_SYSTEM);

        // expected values
        NotificationChannel idn = new NotificationChannel("idn", "name", IMPORTANCE_LOW);
        idn.setSound(null, new AudioAttributes.Builder()
                .setUsage(USAGE_NOTIFICATION)
                .setContentType(CONTENT_TYPE_SONIFICATION)
                .setFlags(0)
                .build());
        idn.setShowBadge(false);
        NotificationChannel ido = new NotificationChannel("ido", "name2", IMPORTANCE_LOW);
        ido.setShowBadge(true);
        ido.setSound(null, new AudioAttributes.Builder()
                .setUsage(USAGE_NOTIFICATION)
                .setContentType(CONTENT_TYPE_SONIFICATION)
                .setFlags(0)
                .build());
        NotificationChannel idp = new NotificationChannel("idp", "name3", IMPORTANCE_HIGH);
        idp.lockFields(2);
        idp.setSound(null, new AudioAttributes.Builder()
                .setUsage(USAGE_NOTIFICATION)
                .setContentType(CONTENT_TYPE_SONIFICATION)
                .setFlags(0)
                .build());

        // Notifications enabled, not user set
        PackagePermission nMr1Expected = new PackagePermission(PKG_N_MR1, 0, true, false);
        // Notifications not enabled, so user set
        PackagePermission oExpected = new PackagePermission(PKG_O, 0, false, true);
        // Notifications enabled, user set b/c channel modified
        PackagePermission pExpected = new PackagePermission(PKG_P, 0, true, true);

        // verify data
        assertTrue(mHelper.canShowBadge(PKG_N_MR1, UID_N_MR1));

        assertEquals(idn, mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, idn.getId(), false));
        compareChannels(ido, mHelper.getNotificationChannel(PKG_O, UID_O, ido.getId(), false));
        compareChannels(idp, mHelper.getNotificationChannel(PKG_P, UID_P, idp.getId(), false));

        verify(mPermissionHelper).setNotificationPermission(nMr1Expected);
        verify(mPermissionHelper).setNotificationPermission(oExpected);
        verify(mPermissionHelper).setNotificationPermission(pExpected);

        // verify that we also write a state for review_permissions_notification to eventually
        // show a notification
        assertEquals(NotificationManagerService.REVIEW_NOTIF_STATE_SHOULD_SHOW,
                Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                        NotificationManagerService.REVIEW_NOTIF_STATE_UNKNOWN));
    }

    @Test
    public void testReadXml_oldXml_backup_migratesWhenPkgInstalled() throws Exception {
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger, mAppOpsManager, mStatsEventBuilderFactory, false);

        when(mPm.getPackageUidAsUser("pkg1", USER_SYSTEM)).thenReturn(UNKNOWN_UID);
        when(mPm.getPackageUidAsUser("pkg2", USER_SYSTEM)).thenReturn(UNKNOWN_UID);
        when(mPm.getPackageUidAsUser("pkg3", USER_SYSTEM)).thenReturn(UNKNOWN_UID);
        when(mPm.getApplicationInfoAsUser(eq("pkg1"), anyInt(), anyInt())).thenThrow(
                new PackageManager.NameNotFoundException());
        when(mPm.getApplicationInfoAsUser(eq("pkg2"), anyInt(), anyInt())).thenThrow(
                new PackageManager.NameNotFoundException());
        when(mPm.getApplicationInfoAsUser(eq("pkg3"), anyInt(), anyInt())).thenThrow(
                new PackageManager.NameNotFoundException());

        String xml = "<ranking version=\"2\">\n"
                + "<package name=\"pkg1\" show_badge=\"true\">\n"
                + "<channel id=\"idn\" name=\"name\" importance=\"2\" />\n"
                + "<channel id=\"miscellaneous\" name=\"Uncategorized\" />\n"
                + "</package>\n"
                + "<package name=\"pkg2\" importance=\"0\">\n"
                + "<channel id=\"ido\" name=\"name2\" importance=\"2\" show_badge=\"true\"/>\n"
                + "</package>\n"
                + "<package name=\"pkg3\" importance=\"2\">\n"
                + "<channel id=\"idp\" name=\"name3\" importance=\"4\" locked=\"2\" />\n"
                + "</package>\n"
                + "</ranking>\n";
        NotificationChannel idn = new NotificationChannel("idn", "name", IMPORTANCE_LOW);
        idn.setSound(null, new AudioAttributes.Builder()
                .setUsage(USAGE_NOTIFICATION)
                .setContentType(CONTENT_TYPE_SONIFICATION)
                .setFlags(0)
                .build());
        idn.setShowBadge(false);
        NotificationChannel ido = new NotificationChannel("ido", "name2", IMPORTANCE_LOW);
        ido.setShowBadge(true);
        ido.setSound(null, new AudioAttributes.Builder()
                .setUsage(USAGE_NOTIFICATION)
                .setContentType(CONTENT_TYPE_SONIFICATION)
                .setFlags(0)
                .build());
        NotificationChannel idp = new NotificationChannel("idp", "name3", IMPORTANCE_HIGH);
        idp.lockFields(2);
        idp.setSound(null, new AudioAttributes.Builder()
                .setUsage(USAGE_NOTIFICATION)
                .setContentType(CONTENT_TYPE_SONIFICATION)
                .setFlags(0)
                .build());

        // Notifications enabled, not user set
        PackagePermission pkg1Expected = new PackagePermission("pkg1", 0, true, false);
        // Notifications not enabled, so user set
        PackagePermission pkg2Expected = new PackagePermission("pkg2", 0, false, true);
        // Notifications enabled, user set b/c channel modified
        PackagePermission pkg3Expected = new PackagePermission("pkg3", 0, true, true);

        loadByteArrayXml(xml.getBytes(), true, USER_SYSTEM);

        verify(mPermissionHelper, never()).setNotificationPermission(any());

        when(mPm.getPackageUidAsUser("pkg1", USER_SYSTEM)).thenReturn(11);
        when(mPm.getPackageUidAsUser("pkg2", USER_SYSTEM)).thenReturn(12);
        when(mPm.getPackageUidAsUser("pkg3", USER_SYSTEM)).thenReturn(13);

        mHelper.onPackagesChanged(
                false, 0, new String[]{"pkg1", "pkg2", "pkg3"}, new int[] {11, 12, 13});

        assertTrue(mHelper.canShowBadge("pkg1", 11));

        assertEquals(idn, mHelper.getNotificationChannel("pkg1", 11, idn.getId(), false));
        compareChannels(ido, mHelper.getNotificationChannel("pkg2", 12, ido.getId(), false));
        compareChannels(idp, mHelper.getNotificationChannel("pkg3", 13, idp.getId(), false));

        verify(mPermissionHelper).setNotificationPermission(pkg1Expected);
        verify(mPermissionHelper).setNotificationPermission(pkg2Expected);
        verify(mPermissionHelper).setNotificationPermission(pkg3Expected);
    }

    @Test
    public void testReadXml_newXml_noMigration_showPermissionNotification() throws Exception {
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger, mAppOpsManager, mStatsEventBuilderFactory, true);

        String xml = "<ranking version=\"3\">\n"
                + "<package name=\"" + PKG_N_MR1 + "\" show_badge=\"true\">\n"
                + "<channel id=\"idn\" name=\"name\" importance=\"2\"/>\n"
                + "<channel id=\"miscellaneous\" name=\"Uncategorized\" />\n"
                + "</package>\n"
                + "<package name=\"" + PKG_O + "\" >\n"
                + "<channel id=\"ido\" name=\"name2\" importance=\"2\" show_badge=\"true\"/>\n"
                + "</package>\n"
                + "<package name=\"" + PKG_P + "\" >\n"
                + "<channel id=\"idp\" name=\"name3\" importance=\"4\" locked=\"2\" />\n"
                + "</package>\n"
                + "</ranking>\n";
        NotificationChannel idn = new NotificationChannel("idn", "name", IMPORTANCE_LOW);
        idn.setSound(null, new AudioAttributes.Builder()
                .setUsage(USAGE_NOTIFICATION)
                .setContentType(CONTENT_TYPE_SONIFICATION)
                .setFlags(0)
                .build());
        idn.setShowBadge(false);
        NotificationChannel ido = new NotificationChannel("ido", "name2", IMPORTANCE_LOW);
        ido.setShowBadge(true);
        ido.setSound(null, new AudioAttributes.Builder()
                .setUsage(USAGE_NOTIFICATION)
                .setContentType(CONTENT_TYPE_SONIFICATION)
                .setFlags(0)
                .build());
        NotificationChannel idp = new NotificationChannel("idp", "name3", IMPORTANCE_HIGH);
        idp.lockFields(2);
        idp.setSound(null, new AudioAttributes.Builder()
                .setUsage(USAGE_NOTIFICATION)
                .setContentType(CONTENT_TYPE_SONIFICATION)
                .setFlags(0)
                .build());

        loadByteArrayXml(xml.getBytes(), true, USER_SYSTEM);

        assertTrue(mHelper.canShowBadge(PKG_N_MR1, UID_N_MR1));

        assertEquals(idn, mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, idn.getId(), false));
        compareChannels(ido, mHelper.getNotificationChannel(PKG_O, UID_O, ido.getId(), false));
        compareChannels(idp, mHelper.getNotificationChannel(PKG_P, UID_P, idp.getId(), false));

        verify(mPermissionHelper, never()).setNotificationPermission(any());

        // verify that we do, however, write a state for review_permissions_notification to
        // eventually show a notification, since this XML version is older than the notification
        assertEquals(NotificationManagerService.REVIEW_NOTIF_STATE_SHOULD_SHOW,
                Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                        NotificationManagerService.REVIEW_NOTIF_STATE_UNKNOWN));
    }

    @Test
    public void testReadXml_newXml_permissionNotificationOff() throws Exception {
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger, mAppOpsManager, mStatsEventBuilderFactory, false);

        String xml = "<ranking version=\"3\">\n"
                + "<package name=\"" + PKG_N_MR1 + "\" show_badge=\"true\">\n"
                + "<channel id=\"idn\" name=\"name\" importance=\"2\"/>\n"
                + "<channel id=\"miscellaneous\" name=\"Uncategorized\" />\n"
                + "</package>\n"
                + "<package name=\"" + PKG_O + "\" >\n"
                + "<channel id=\"ido\" name=\"name2\" importance=\"2\" show_badge=\"true\"/>\n"
                + "</package>\n"
                + "<package name=\"" + PKG_P + "\" >\n"
                + "<channel id=\"idp\" name=\"name3\" importance=\"4\" locked=\"2\" />\n"
                + "</package>\n"
                + "</ranking>\n";
        NotificationChannel idn = new NotificationChannel("idn", "name", IMPORTANCE_LOW);
        idn.setSound(null, new AudioAttributes.Builder()
                .setUsage(USAGE_NOTIFICATION)
                .setContentType(CONTENT_TYPE_SONIFICATION)
                .setFlags(0)
                .build());
        idn.setShowBadge(false);
        NotificationChannel ido = new NotificationChannel("ido", "name2", IMPORTANCE_LOW);
        ido.setShowBadge(true);
        ido.setSound(null, new AudioAttributes.Builder()
                .setUsage(USAGE_NOTIFICATION)
                .setContentType(CONTENT_TYPE_SONIFICATION)
                .setFlags(0)
                .build());
        NotificationChannel idp = new NotificationChannel("idp", "name3", IMPORTANCE_HIGH);
        idp.lockFields(2);
        idp.setSound(null, new AudioAttributes.Builder()
                .setUsage(USAGE_NOTIFICATION)
                .setContentType(CONTENT_TYPE_SONIFICATION)
                .setFlags(0)
                .build());

        loadByteArrayXml(xml.getBytes(), true, USER_SYSTEM);

        assertTrue(mHelper.canShowBadge(PKG_N_MR1, UID_N_MR1));

        assertEquals(idn, mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, idn.getId(), false));
        compareChannels(ido, mHelper.getNotificationChannel(PKG_O, UID_O, ido.getId(), false));
        compareChannels(idp, mHelper.getNotificationChannel(PKG_P, UID_P, idp.getId(), false));

        verify(mPermissionHelper, never()).setNotificationPermission(any());

        // while this is the same case as above, if the permission helper is set to not show the
        // review permissions notification it should not write anything to the settings int
        assertEquals(NotificationManagerService.REVIEW_NOTIF_STATE_UNKNOWN,
                Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                        NotificationManagerService.REVIEW_NOTIF_STATE_UNKNOWN));
    }

    @Test
    public void testReadXml_newXml_noMigration_noPermissionNotification() throws Exception {
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger, mAppOpsManager, mStatsEventBuilderFactory, true);

        String xml = "<ranking version=\"4\">\n"
                + "<package name=\"" + PKG_N_MR1 + "\" show_badge=\"true\">\n"
                + "<channel id=\"idn\" name=\"name\" importance=\"2\"/>\n"
                + "<channel id=\"miscellaneous\" name=\"Uncategorized\" />\n"
                + "</package>\n"
                + "<package name=\"" + PKG_O + "\" >\n"
                + "<channel id=\"ido\" name=\"name2\" importance=\"2\" show_badge=\"true\"/>\n"
                + "</package>\n"
                + "<package name=\"" + PKG_P + "\" >\n"
                + "<channel id=\"idp\" name=\"name3\" importance=\"4\" locked=\"2\" />\n"
                + "</package>\n"
                + "</ranking>\n";
        NotificationChannel idn = new NotificationChannel("idn", "name", IMPORTANCE_LOW);
        idn.setSound(null, new AudioAttributes.Builder()
                .setUsage(USAGE_NOTIFICATION)
                .setContentType(CONTENT_TYPE_SONIFICATION)
                .setFlags(0)
                .build());
        idn.setShowBadge(false);
        NotificationChannel ido = new NotificationChannel("ido", "name2", IMPORTANCE_LOW);
        ido.setShowBadge(true);
        ido.setSound(null, new AudioAttributes.Builder()
                .setUsage(USAGE_NOTIFICATION)
                .setContentType(CONTENT_TYPE_SONIFICATION)
                .setFlags(0)
                .build());
        NotificationChannel idp = new NotificationChannel("idp", "name3", IMPORTANCE_HIGH);
        idp.lockFields(2);
        idp.setSound(null, new AudioAttributes.Builder()
                .setUsage(USAGE_NOTIFICATION)
                .setContentType(CONTENT_TYPE_SONIFICATION)
                .setFlags(0)
                .build());

        loadByteArrayXml(xml.getBytes(), true, USER_SYSTEM);

        assertTrue(mHelper.canShowBadge(PKG_N_MR1, UID_N_MR1));

        assertEquals(idn, mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, idn.getId(), false));
        compareChannels(ido, mHelper.getNotificationChannel(PKG_O, UID_O, ido.getId(), false));
        compareChannels(idp, mHelper.getNotificationChannel(PKG_P, UID_P, idp.getId(), false));

        verify(mPermissionHelper, never()).setNotificationPermission(any());

        // this XML is new enough, we should not be attempting to show a notification or anything
        assertEquals(NotificationManagerService.REVIEW_NOTIF_STATE_UNKNOWN,
                Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                        NotificationManagerService.REVIEW_NOTIF_STATE_UNKNOWN));
    }

    @Test
    public void testReadXml_oldXml_migration_NoUid() throws Exception {
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger, mAppOpsManager, mStatsEventBuilderFactory, false);

        when(mPm.getPackageUidAsUser("something", USER_SYSTEM)).thenReturn(UNKNOWN_UID);
        String xml = "<ranking version=\"2\">\n"
                + "<package name=\"something\" show_badge=\"true\">\n"
                + "<channel id=\"idn\" name=\"name\" importance=\"2\"/>\n"
                + "</package>\n"
                + "</ranking>\n";
        NotificationChannel idn = new NotificationChannel("idn", "name", IMPORTANCE_LOW);
        idn.setSound(null, new AudioAttributes.Builder()
                .setUsage(USAGE_NOTIFICATION)
                .setContentType(CONTENT_TYPE_SONIFICATION)
                .setFlags(0)
                .build());
        idn.setShowBadge(false);

        loadByteArrayXml(xml.getBytes(), true, USER_SYSTEM);
        verify(mPermissionHelper, never()).setNotificationPermission(any());

        when(mPm.getPackageUidAsUser("something", USER_SYSTEM)).thenReturn(1234);
        final ApplicationInfo app = new ApplicationInfo();
        app.targetSdkVersion = Build.VERSION_CODES.N_MR1 + 1;
        when(mPm.getApplicationInfoAsUser(eq("something"), anyInt(), anyInt())).thenReturn(app);

        mHelper.onPackagesChanged(false, 0, new String[] {"something"}, new int[] {1234});


        verify(mPermissionHelper, times(1)).setNotificationPermission(any());
    }

    @Test
    public void testReadXml_newXml_noMigration_NoUid() throws Exception {
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger, mAppOpsManager, mStatsEventBuilderFactory, false);

        when(mPm.getPackageUidAsUser("something", USER_SYSTEM)).thenReturn(UNKNOWN_UID);
        String xml = "<ranking version=\"3\">\n"
                + "<package name=\"something\" show_badge=\"true\">\n"
                + "<channel id=\"idn\" name=\"name\" importance=\"2\"/>\n"
                + "</package>\n"
                + "</ranking>\n";
        NotificationChannel idn = new NotificationChannel("idn", "name", IMPORTANCE_LOW);
        idn.setSound(null, new AudioAttributes.Builder()
                .setUsage(USAGE_NOTIFICATION)
                .setContentType(CONTENT_TYPE_SONIFICATION)
                .setFlags(0)
                .build());
        idn.setShowBadge(false);

        loadByteArrayXml(xml.getBytes(), true, USER_SYSTEM);

        when(mPm.getPackageUidAsUser("something", USER_SYSTEM)).thenReturn(1234);
        final ApplicationInfo app = new ApplicationInfo();
        app.targetSdkVersion = Build.VERSION_CODES.N_MR1 + 1;
        when(mPm.getApplicationInfoAsUser(eq("something"), anyInt(), anyInt())).thenReturn(app);

        mHelper.onPackagesChanged(false, 0, new String[] {"something"}, new int[] {1234});


        verify(mPermissionHelper, never()).setNotificationPermission(any());
    }

    @Test
    public void testChannelXmlForNonBackup_postMigration() throws Exception {
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger, mAppOpsManager, mStatsEventBuilderFactory, false);

        ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> appPermissions = new ArrayMap<>();
        appPermissions.put(new Pair(1, "first"), new Pair(true, false));
        appPermissions.put(new Pair(3, "third"), new Pair(false, false));
        appPermissions.put(new Pair(UID_P, PKG_P), new Pair(true, false));
        appPermissions.put(new Pair(UID_O, PKG_O), new Pair(false, false));
        appPermissions.put(new Pair(UID_N_MR1, PKG_N_MR1), new Pair(true, false));

        when(mPermissionHelper.getNotificationPermissionValues(USER_SYSTEM))
                .thenReturn(appPermissions);

        NotificationChannelGroup ncg = new NotificationChannelGroup("1", "bye");
        NotificationChannelGroup ncg2 = new NotificationChannelGroup("2", "hello");
        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        channel1.setSound(null, null);
        NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel2.setDescription("descriptions for all");
        channel2.setSound(null, null);
        channel2.enableLights(true);
        channel2.setBypassDnd(true);
        channel2.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        channel2.enableVibration(false);
        channel2.setGroup(ncg.getId());
        channel2.setLightColor(Color.BLUE);
        NotificationChannel channel3 = new NotificationChannel("id3", "NAM3", IMPORTANCE_HIGH);
        channel3.enableVibration(true);

        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg, true);
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg2, true);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel1, true, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel2, false, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel3, false, false);
        mHelper.createNotificationChannel(PKG_O, UID_O, getChannel(), true, false);

        mHelper.setShowBadge(PKG_N_MR1, UID_N_MR1, true);
        mHelper.setInvalidMessageSent(PKG_P, UID_P);
        mHelper.setValidMessageSent(PKG_P, UID_P);
        mHelper.setInvalidMsgAppDemoted(PKG_P, UID_P, true);

        ByteArrayOutputStream baos = writeXmlAndPurge(
                PKG_N_MR1, UID_N_MR1, false, USER_SYSTEM);
        String expected = "<ranking version=\"4\">\n"
                + "<package name=\"com.example.o\" show_badge=\"true\" "
                + "app_user_locked_fields=\"0\" sent_invalid_msg=\"false\" "
                + "sent_valid_msg=\"false\" user_demote_msg_app=\"false\" uid=\"1111\">\n"
                + "<channel id=\"id\" name=\"name\" importance=\"2\" "
                + "sound=\"content://settings/system/notification_sound\" usage=\"5\" "
                + "content_type=\"4\" flags=\"0\" show_badge=\"true\" orig_imp=\"2\" />\n"
                + "</package>\n"
                + "<package name=\"com.example.p\" show_badge=\"true\" "
                + "app_user_locked_fields=\"0\" sent_invalid_msg=\"true\" sent_valid_msg=\"true\""
                + " user_demote_msg_app=\"true\" uid=\"2222\" />\n"
                + "<package name=\"com.example.n_mr1\" show_badge=\"true\" "
                + "app_user_locked_fields=\"0\" sent_invalid_msg=\"false\" "
                + "sent_valid_msg=\"false\" user_demote_msg_app=\"false\" uid=\"0\">\n"
                + "<channelGroup id=\"1\" name=\"bye\" blocked=\"false\" locked=\"0\" />\n"
                + "<channelGroup id=\"2\" name=\"hello\" blocked=\"false\" locked=\"0\" />\n"
                + "<channel id=\"id1\" name=\"name1\" importance=\"4\" show_badge=\"true\" "
                + "orig_imp=\"4\" />\n"
                + "<channel id=\"id2\" name=\"name2\" desc=\"descriptions for all\" "
                + "importance=\"2\" priority=\"2\" visibility=\"-1\" lights=\"true\" "
                + "light_color=\"-16776961\" show_badge=\"true\" group=\"1\" orig_imp=\"2\" />\n"
                + "<channel id=\"id3\" name=\"NAM3\" importance=\"4\" "
                + "sound=\"content://settings/system/notification_sound\" usage=\"5\" "
                + "content_type=\"4\" flags=\"0\" vibration_enabled=\"true\" show_badge=\"true\" "
                + "orig_imp=\"4\" />\n"
                + "<channel id=\"miscellaneous\" name=\"Uncategorized\" "
                + "sound=\"content://settings/system/notification_sound\" usage=\"5\" "
                + "content_type=\"4\" flags=\"0\" show_badge=\"true\" />\n"
                + "</package>\n"
                + "</ranking>";
        assertThat(baos.toString()).contains(expected);
    }

    @Test
    public void testChannelXmlForBackup_postMigration() throws Exception {
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger, mAppOpsManager, mStatsEventBuilderFactory, false);

        ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> appPermissions = new ArrayMap<>();
        appPermissions.put(new Pair(1, "first"), new Pair(true, false));
        appPermissions.put(new Pair(3, "third"), new Pair(false, false));
        appPermissions.put(new Pair(UID_P, PKG_P), new Pair(true, false));
        appPermissions.put(new Pair(UID_O, PKG_O), new Pair(false, false));
        appPermissions.put(new Pair(UID_N_MR1, PKG_N_MR1), new Pair(true, false));

        when(mPermissionHelper.getNotificationPermissionValues(USER_SYSTEM))
                .thenReturn(appPermissions);

        NotificationChannelGroup ncg = new NotificationChannelGroup("1", "bye");
        NotificationChannelGroup ncg2 = new NotificationChannelGroup("2", "hello");
        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        channel1.setSound(null, null);
        NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel2.setDescription("descriptions for all");
        channel2.setSound(null, null);
        channel2.enableLights(true);
        channel2.setBypassDnd(true);
        channel2.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        channel2.enableVibration(false);
        channel2.setGroup(ncg.getId());
        channel2.setLightColor(Color.BLUE);
        NotificationChannel channel3 = new NotificationChannel("id3", "NAM3", IMPORTANCE_HIGH);
        channel3.enableVibration(true);

        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg, true);
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg2, true);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel1, true, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel2, false, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel3, false, false);
        mHelper.createNotificationChannel(PKG_O, UID_O, getChannel(), true, false);

        mHelper.setShowBadge(PKG_N_MR1, UID_N_MR1, true);
        mHelper.setInvalidMessageSent(PKG_P, UID_P);
        mHelper.setValidMessageSent(PKG_P, UID_P);
        mHelper.setInvalidMsgAppDemoted(PKG_P, UID_P, true);

        ByteArrayOutputStream baos = writeXmlAndPurge(
                PKG_N_MR1, UID_N_MR1, true, USER_SYSTEM);
        String expected = "<ranking version=\"4\">\n"
                // Importance 0 because off in permissionhelper
                + "<package name=\"com.example.o\" importance=\"0\" show_badge=\"true\" "
                + "app_user_locked_fields=\"0\" sent_invalid_msg=\"false\" "
                + "sent_valid_msg=\"false\" user_demote_msg_app=\"false\">\n"
                + "<channel id=\"id\" name=\"name\" importance=\"2\" "
                + "sound=\"content://settings/system/notification_sound\" usage=\"5\" "
                + "content_type=\"4\" flags=\"0\" show_badge=\"true\" orig_imp=\"2\" />\n"
                + "</package>\n"
                // Importance default because on in permission helper
                + "<package name=\"com.example.p\" importance=\"3\" show_badge=\"true\" "
                + "app_user_locked_fields=\"0\" sent_invalid_msg=\"true\" sent_valid_msg=\"true\""
                + " user_demote_msg_app=\"true\" />\n"
                // Importance default because on in permission helper
                + "<package name=\"com.example.n_mr1\" importance=\"3\" show_badge=\"true\" "
                + "app_user_locked_fields=\"0\" sent_invalid_msg=\"false\" "
                + "sent_valid_msg=\"false\" user_demote_msg_app=\"false\">\n"
                + "<channelGroup id=\"1\" name=\"bye\" blocked=\"false\" locked=\"0\" />\n"
                + "<channelGroup id=\"2\" name=\"hello\" blocked=\"false\" locked=\"0\" />\n"
                + "<channel id=\"id1\" name=\"name1\" importance=\"4\" show_badge=\"true\" "
                + "orig_imp=\"4\" />\n"
                + "<channel id=\"id2\" name=\"name2\" desc=\"descriptions for all\" "
                + "importance=\"2\" priority=\"2\" visibility=\"-1\" lights=\"true\" "
                + "light_color=\"-16776961\" show_badge=\"true\" group=\"1\" orig_imp=\"2\" />\n"
                + "<channel id=\"id3\" name=\"NAM3\" importance=\"4\" "
                + "sound=\"content://settings/system/notification_sound\" usage=\"5\" "
                + "content_type=\"4\" flags=\"0\" vibration_enabled=\"true\" show_badge=\"true\" "
                + "orig_imp=\"4\" />\n"
                + "<channel id=\"miscellaneous\" name=\"Uncategorized\" "
                + "sound=\"content://settings/system/notification_sound\" usage=\"5\" "
                + "content_type=\"4\" flags=\"0\" show_badge=\"true\" />\n"
                + "</package>\n"
                // Packages that exist solely in permissionhelper
                + "<package name=\"first\" importance=\"3\" />\n"
                + "<package name=\"third\" importance=\"0\" />\n"
                + "</ranking>";
        assertThat(baos.toString()).contains(expected);
    }

    @Test
    public void testChannelXmlForBackup_postMigration_noExternal() throws Exception {
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger, mAppOpsManager, mStatsEventBuilderFactory, false);

        ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> appPermissions = new ArrayMap<>();
        appPermissions.put(new Pair(UID_P, PKG_P), new Pair(true, false));
        appPermissions.put(new Pair(UID_O, PKG_O), new Pair(false, false));
        when(mPermissionHelper.getNotificationPermissionValues(USER_SYSTEM))
                .thenReturn(appPermissions);

        NotificationChannelGroup ncg = new NotificationChannelGroup("1", "bye");
        NotificationChannelGroup ncg2 = new NotificationChannelGroup("2", "hello");
        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        channel1.setSound(null, null);
        NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel2.setDescription("descriptions for all");
        channel2.setSound(null, null);
        channel2.enableLights(true);
        channel2.setBypassDnd(true);
        channel2.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        channel2.enableVibration(false);
        channel2.setGroup(ncg.getId());
        channel2.setLightColor(Color.BLUE);
        NotificationChannel channel3 = new NotificationChannel("id3", "NAM3", IMPORTANCE_HIGH);
        channel3.enableVibration(true);

        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg, true);
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg2, true);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel1, true, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel2, false, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel3, false, false);
        mHelper.createNotificationChannel(PKG_O, UID_O, getChannel(), true, false);

        mHelper.setShowBadge(PKG_N_MR1, UID_N_MR1, true);
        mHelper.setInvalidMessageSent(PKG_P, UID_P);
        mHelper.setValidMessageSent(PKG_P, UID_P);
        mHelper.setInvalidMsgAppDemoted(PKG_P, UID_P, true);

        ByteArrayOutputStream baos = writeXmlAndPurge(
                PKG_N_MR1, UID_N_MR1, true, USER_SYSTEM);
        String expected = "<ranking version=\"4\">\n"
                // Importance 0 because off in permissionhelper
                + "<package name=\"com.example.o\" importance=\"0\" show_badge=\"true\" "
                + "app_user_locked_fields=\"0\" sent_invalid_msg=\"false\" "
                + "sent_valid_msg=\"false\" user_demote_msg_app=\"false\">\n"
                + "<channel id=\"id\" name=\"name\" importance=\"2\" "
                + "sound=\"content://settings/system/notification_sound\" usage=\"5\" "
                + "content_type=\"4\" flags=\"0\" show_badge=\"true\" orig_imp=\"2\" />\n"
                + "</package>\n"
                // Importance default because on in permission helper
                + "<package name=\"com.example.p\" importance=\"3\" show_badge=\"true\" "
                + "app_user_locked_fields=\"0\" sent_invalid_msg=\"true\" sent_valid_msg=\"true\""
                + " user_demote_msg_app=\"true\" />\n"
                // Importance missing because missing from permission helper
                + "<package name=\"com.example.n_mr1\" show_badge=\"true\" "
                + "app_user_locked_fields=\"0\" sent_invalid_msg=\"false\" "
                + "sent_valid_msg=\"false\" user_demote_msg_app=\"false\">\n"
                + "<channelGroup id=\"1\" name=\"bye\" blocked=\"false\" locked=\"0\" />\n"
                + "<channelGroup id=\"2\" name=\"hello\" blocked=\"false\" locked=\"0\" />\n"
                + "<channel id=\"id1\" name=\"name1\" importance=\"4\" show_badge=\"true\" "
                + "orig_imp=\"4\" />\n"
                + "<channel id=\"id2\" name=\"name2\" desc=\"descriptions for all\" "
                + "importance=\"2\" priority=\"2\" visibility=\"-1\" lights=\"true\" "
                + "light_color=\"-16776961\" show_badge=\"true\" group=\"1\" orig_imp=\"2\" />\n"
                + "<channel id=\"id3\" name=\"NAM3\" importance=\"4\" "
                + "sound=\"content://settings/system/notification_sound\" usage=\"5\" "
                + "content_type=\"4\" flags=\"0\" vibration_enabled=\"true\" show_badge=\"true\" "
                + "orig_imp=\"4\" />\n"
                + "<channel id=\"miscellaneous\" name=\"Uncategorized\" "
                + "sound=\"content://settings/system/notification_sound\" usage=\"5\" "
                + "content_type=\"4\" flags=\"0\" show_badge=\"true\" />\n"
                + "</package>\n"
                + "</ranking>";
        assertThat(baos.toString()).contains(expected);
    }

    @Test
    public void testChannelXmlForBackup_postMigration_noLocalSettings() throws Exception {
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger, mAppOpsManager, mStatsEventBuilderFactory, false);

        ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> appPermissions = new ArrayMap<>();
        appPermissions.put(new Pair(1, "first"), new Pair(true, false));
        appPermissions.put(new Pair(3, "third"), new Pair(false, false));
        appPermissions.put(new Pair(UID_P, PKG_P), new Pair(true, false));
        appPermissions.put(new Pair(UID_O, PKG_O), new Pair(false, false));
        appPermissions.put(new Pair(UID_N_MR1, PKG_N_MR1), new Pair(true, false));

        when(mPermissionHelper.getNotificationPermissionValues(USER_SYSTEM))
                .thenReturn(appPermissions);

        ByteArrayOutputStream baos = writeXmlAndPurge(
                PKG_N_MR1, UID_N_MR1, true, USER_SYSTEM);
        String expected = "<ranking version=\"4\">\n"
                // Packages that exist solely in permissionhelper
                + "<package name=\"" + PKG_P + "\" importance=\"3\" />\n"
                + "<package name=\"" + PKG_O + "\" importance=\"0\" />\n"
                + "<package name=\"" + PKG_N_MR1 + "\" importance=\"3\" />\n"
                + "<package name=\"first\" importance=\"3\" />\n"
                + "<package name=\"third\" importance=\"0\" />\n"
                + "</ranking>";
        assertThat(baos.toString()).contains(expected);
    }

    @Test
    public void testBackupXml_backupCanonicalizedSoundUri() throws Exception {
        NotificationChannel channel =
                new NotificationChannel("id", "name", IMPORTANCE_LOW);
        channel.setSound(SOUND_URI, mAudioAttributes);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel, true, false);

        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_N_MR1, UID_N_MR1, true,
                USER_SYSTEM, channel.getId());

        // Testing that in restore we are given the canonical version
        loadStreamXml(baos, true, USER_SYSTEM);
        verify(mTestIContentProvider).uncanonicalize(any(), eq(CANONICAL_SOUND_URI));
    }

    @Test
    public void testRestoreXml_withExistentCanonicalizedSoundUri() throws Exception {
        Uri localUri = Uri.parse("content://" + TEST_AUTHORITY + "/local/url");
        Uri canonicalBasedOnLocal = localUri.buildUpon()
                .appendQueryParameter("title", "Test")
                .appendQueryParameter("canonical", "1")
                .build();
        doReturn(canonicalBasedOnLocal)
                .when(mTestIContentProvider).canonicalize(any(), eq(CANONICAL_SOUND_URI));
        doReturn(localUri)
                .when(mTestIContentProvider).uncanonicalize(any(), eq(CANONICAL_SOUND_URI));
        doReturn(localUri)
                .when(mTestIContentProvider).uncanonicalize(any(), eq(canonicalBasedOnLocal));

        NotificationChannel channel =
                new NotificationChannel("id", "name", IMPORTANCE_LOW);
        channel.setSound(SOUND_URI, mAudioAttributes);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel, true, false);
        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_N_MR1, UID_N_MR1, true,
                USER_SYSTEM, channel.getId());

        loadStreamXml(baos, true, USER_SYSTEM);

        NotificationChannel actualChannel = mHelper.getNotificationChannel(
                PKG_N_MR1, UID_N_MR1, channel.getId(), false);
        assertEquals(localUri, actualChannel.getSound());
    }

    @Test
    public void testRestoreXml_withNonExistentCanonicalizedSoundUri() throws Exception {
        Thread.sleep(3000);
        doReturn(null)
                .when(mTestIContentProvider).canonicalize(any(), eq(CANONICAL_SOUND_URI));
        doReturn(null)
                .when(mTestIContentProvider).uncanonicalize(any(), eq(CANONICAL_SOUND_URI));

        NotificationChannel channel =
                new NotificationChannel("id", "name", IMPORTANCE_LOW);
        channel.setSound(SOUND_URI, mAudioAttributes);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel, true, false);
        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_N_MR1, UID_N_MR1, true,
                USER_SYSTEM, channel.getId());

        loadStreamXml(baos, true, USER_SYSTEM);

        NotificationChannel actualChannel = mHelper.getNotificationChannel(
                PKG_N_MR1, UID_N_MR1, channel.getId(), false);
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, actualChannel.getSound());
    }


    /**
     * Although we don't make backups with uncanonicalized uris anymore, we used to, so we have to
     * handle its restore properly.
     */
    @Test
    public void testRestoreXml_withUncanonicalizedNonLocalSoundUri() throws Exception {
        // Not a local uncanonicalized uri, simulating that it fails to exist locally
        doReturn(null)
                .when(mTestIContentProvider).canonicalize(any(), eq(SOUND_URI));
        String id = "id";
        String backupWithUncanonicalizedSoundUri = "<ranking version=\"1\">\n"
                + "<package name=\"" + PKG_N_MR1 + "\" show_badge=\"true\">\n"
                + "<channel id=\"" + id + "\" name=\"name\" importance=\"2\" "
                + "sound=\"" + SOUND_URI + "\" "
                + "usage=\"6\" content_type=\"0\" flags=\"1\" show_badge=\"true\" />\n"
                + "<channel id=\"miscellaneous\" name=\"Uncategorized\" usage=\"5\" "
                + "content_type=\"4\" flags=\"0\" show_badge=\"true\" />\n"
                + "</package>\n"
                + "</ranking>\n";

        loadByteArrayXml(
                backupWithUncanonicalizedSoundUri.getBytes(), true, USER_SYSTEM);

        NotificationChannel actualChannel = mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, id, false);
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, actualChannel.getSound());
    }

    @Test
    public void testBackupRestoreXml_withNullSoundUri() throws Exception {
        ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> appPermissions = new ArrayMap<>();
        appPermissions.put(new Pair(UID_N_MR1, PKG_N_MR1), new Pair(true, false));

        when(mPermissionHelper.getNotificationPermissionValues(USER_SYSTEM))
                .thenReturn(appPermissions);

        NotificationChannel channel =
                new NotificationChannel("id", "name", IMPORTANCE_LOW);
        channel.setSound(null, mAudioAttributes);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel, true, false);
        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_N_MR1, UID_N_MR1, true,
                USER_SYSTEM, channel.getId());

        loadStreamXml(baos, true, USER_SYSTEM);

        NotificationChannel actualChannel = mHelper.getNotificationChannel(
                PKG_N_MR1, UID_N_MR1, channel.getId(), false);
        assertEquals(null, actualChannel.getSound());
    }

    @Test
    public void testChannelXml_backup() throws Exception {
        NotificationChannelGroup ncg = new NotificationChannelGroup("1", "bye");
        NotificationChannelGroup ncg2 = new NotificationChannelGroup("2", "hello");
        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", IMPORTANCE_HIGH);
        NotificationChannel channel3 =
                new NotificationChannel("id3", "name3", IMPORTANCE_LOW);
        channel3.setGroup(ncg.getId());

        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg, true);
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg2, true);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel1, true, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel2, false, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel3, true, false);

        mHelper.deleteNotificationChannel(PKG_N_MR1, UID_N_MR1, channel1.getId());
        mHelper.deleteNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg.getId());
        assertEquals(channel2,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, channel2.getId(), false));

        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_N_MR1, UID_N_MR1, true,
                USER_SYSTEM, channel1.getId(), channel2.getId(), channel3.getId(),
                NotificationChannel.DEFAULT_CHANNEL_ID);
        mHelper.onPackagesChanged(true, UserHandle.myUserId(), new String[]{PKG_N_MR1}, new int[]{
                UID_N_MR1});

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, true, USER_SYSTEM);

        assertNull(mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, channel1.getId(), false));
        assertNull(mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, channel3.getId(), false));
        assertNull(mHelper.getNotificationChannelGroup(ncg.getId(), PKG_N_MR1, UID_N_MR1));
        assertEquals(channel2,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, channel2.getId(), false));
    }

    @Test
    public void testChannelXml_defaultChannelLegacyApp_noUserSettings() throws Exception {
        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_N_MR1, UID_N_MR1, false,
                UserHandle.USER_ALL, NotificationChannel.DEFAULT_CHANNEL_ID);

        loadStreamXml(baos, false, UserHandle.USER_ALL);

        final NotificationChannel updated = mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1,
                NotificationChannel.DEFAULT_CHANNEL_ID, false);
        assertEquals(NotificationManager.IMPORTANCE_UNSPECIFIED, updated.getImportance());
        assertFalse(updated.canBypassDnd());
        assertEquals(NotificationManager.VISIBILITY_NO_OVERRIDE, updated.getLockscreenVisibility());
        assertEquals(0, updated.getUserLockedFields());
    }

    @Test
    public void testChannelXml_defaultChannelUpdatedApp_userSettings() throws Exception {
        final NotificationChannel defaultChannel = mHelper.getNotificationChannel(PKG_N_MR1,
                UID_N_MR1,
                NotificationChannel.DEFAULT_CHANNEL_ID, false);
        defaultChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        mHelper.updateNotificationChannel(PKG_N_MR1, UID_N_MR1, defaultChannel, true);

        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_N_MR1, UID_N_MR1, false,
                UserHandle.USER_ALL, NotificationChannel.DEFAULT_CHANNEL_ID);

        loadStreamXml(baos, false, UserHandle.USER_ALL);

        assertEquals(NotificationManager.IMPORTANCE_LOW, mHelper.getNotificationChannel(
                PKG_N_MR1, UID_N_MR1, NotificationChannel.DEFAULT_CHANNEL_ID, false).getImportance());
    }

    @Test
    public void testChannelXml_upgradeCreateDefaultChannel() throws Exception {
        final String preupgradeXml = "<ranking version=\"1\">\n"
                + "<package name=\"" + PKG_N_MR1
                + "\" importance=\"" + NotificationManager.IMPORTANCE_HIGH
                + "\" priority=\"" + Notification.PRIORITY_MAX + "\" visibility=\""
                + Notification.VISIBILITY_SECRET + "\"" +" uid=\"" + UID_N_MR1 + "\" />\n"
                + "<package name=\"" + PKG_O + "\" uid=\"" + UID_O + "\" visibility=\""
                + Notification.VISIBILITY_PRIVATE + "\" />\n"
                + "</ranking>";
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(preupgradeXml.getBytes())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, false, UserHandle.USER_ALL);

        final NotificationChannel updated1 =
            mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, NotificationChannel.DEFAULT_CHANNEL_ID, false);
        assertEquals(NotificationManager.IMPORTANCE_HIGH, updated1.getImportance());
        assertTrue(updated1.canBypassDnd());
        assertEquals(Notification.VISIBILITY_SECRET, updated1.getLockscreenVisibility());
        assertEquals(NotificationChannel.USER_LOCKED_IMPORTANCE
                | NotificationChannel.USER_LOCKED_PRIORITY
                | NotificationChannel.USER_LOCKED_VISIBILITY,
                updated1.getUserLockedFields());

        // No Default Channel created for updated packages
        assertEquals(null, mHelper.getNotificationChannel(PKG_O, UID_O,
                NotificationChannel.DEFAULT_CHANNEL_ID, false));
    }

    @Test
    public void testChannelXml_upgradeDeletesDefaultChannel() throws Exception {
        final NotificationChannel defaultChannel = mHelper.getNotificationChannel(
                PKG_N_MR1, UID_N_MR1, NotificationChannel.DEFAULT_CHANNEL_ID, false);
        assertTrue(defaultChannel != null);
        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_N_MR1, UID_N_MR1, false,
                UserHandle.USER_ALL, NotificationChannel.DEFAULT_CHANNEL_ID);
        // Load package at higher sdk.
        final ApplicationInfo upgraded = new ApplicationInfo();
        upgraded.targetSdkVersion = Build.VERSION_CODES.N_MR1 + 1;
        when(mPm.getApplicationInfoAsUser(eq(PKG_N_MR1), anyInt(), anyInt())).thenReturn(upgraded);
        loadStreamXml(baos, false, UserHandle.USER_ALL);

        // Default Channel should be gone.
        assertEquals(null, mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1,
                NotificationChannel.DEFAULT_CHANNEL_ID, false));
    }

    @Test
    public void testDeletesDefaultChannelAfterChannelIsCreated() throws Exception {
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1,
                new NotificationChannel("bananas", "bananas", IMPORTANCE_LOW), true, false);
        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_N_MR1, UID_N_MR1, false,
                UserHandle.USER_ALL, NotificationChannel.DEFAULT_CHANNEL_ID, "bananas");

        // Load package at higher sdk.
        final ApplicationInfo upgraded = new ApplicationInfo();
        upgraded.targetSdkVersion = Build.VERSION_CODES.N_MR1 + 1;
        when(mPm.getApplicationInfoAsUser(eq(PKG_N_MR1), anyInt(), anyInt())).thenReturn(upgraded);
        loadStreamXml(baos, false, UserHandle.USER_ALL);

        // Default Channel should be gone.
        assertEquals(null, mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1,
                NotificationChannel.DEFAULT_CHANNEL_ID, false));
    }

    @Test
    public void testLoadingOldChannelsDoesNotDeleteNewlyCreatedChannels() throws Exception {
        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_N_MR1, UID_N_MR1, false,
                UserHandle.USER_ALL, NotificationChannel.DEFAULT_CHANNEL_ID, "bananas");
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1,
                new NotificationChannel("bananas", "bananas", IMPORTANCE_LOW), true, false);

        loadStreamXml(baos, false, UserHandle.USER_ALL);

        // Should still have the newly created channel that wasn't in the xml.
        assertTrue(mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, "bananas", false) != null);
    }

    @Test
    public void testCreateChannel_badImportance() throws Exception {
        try {
            mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1,
                    new NotificationChannel("bananas", "bananas", IMPORTANCE_NONE - 1),
                    true, false);
            fail("Was allowed to create a channel with invalid importance");
        } catch (IllegalArgumentException e) {
            // yay
        }
        try {
            mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1,
                    new NotificationChannel("bananas", "bananas", IMPORTANCE_UNSPECIFIED),
                    true, false);
            fail("Was allowed to create a channel with invalid importance");
        } catch (IllegalArgumentException e) {
            // yay
        }
        try {
            mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1,
                    new NotificationChannel("bananas", "bananas", IMPORTANCE_MAX + 1),
                    true, false);
            fail("Was allowed to create a channel with invalid importance");
        } catch (IllegalArgumentException e) {
            // yay
        }
        assertTrue(mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1,
                new NotificationChannel("bananas", "bananas", IMPORTANCE_NONE), true, false));
        assertFalse(mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1,
                new NotificationChannel("bananas", "bananas", IMPORTANCE_MAX), true, false));
    }


    @Test
    public void testUpdate() throws Exception {
        // no fields locked by user
        final NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel.setSound(new Uri.Builder().scheme("test").build(), mAudioAttributes);
        channel.enableLights(true);
        channel.setBypassDnd(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);

        assertTrue(mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel, false, false));

        // same id, try to update all fields
        final NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", NotificationManager.IMPORTANCE_HIGH);
        channel2.setSound(new Uri.Builder().scheme("test2").build(), mAudioAttributes);
        channel2.enableLights(false);
        channel2.setBypassDnd(false);
        channel2.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        mHelper.updateNotificationChannel(PKG_N_MR1, UID_N_MR1, channel2, true);

        // all fields should be changed
        assertEquals(channel2,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, channel.getId(), false));

        verify(mHandler, times(1)).requestSort();
    }

    @Test
    public void testUpdate_preUpgrade_updatesAppFields() throws Exception {
        assertTrue(mHelper.canShowBadge(PKG_N_MR1, UID_N_MR1));
        assertEquals(Notification.PRIORITY_DEFAULT, mHelper.getPackagePriority(PKG_N_MR1, UID_N_MR1));
        assertEquals(NotificationManager.VISIBILITY_NO_OVERRIDE,
                mHelper.getPackageVisibility(PKG_N_MR1, UID_N_MR1));

        NotificationChannel defaultChannel = mHelper.getNotificationChannel(
                PKG_N_MR1, UID_N_MR1, NotificationChannel.DEFAULT_CHANNEL_ID, false);

        defaultChannel.setShowBadge(false);
        defaultChannel.setImportance(IMPORTANCE_NONE);
        defaultChannel.setBypassDnd(true);
        defaultChannel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);

        mHelper.setAppImportanceLocked(PKG_N_MR1, UID_N_MR1);
        mHelper.updateNotificationChannel(PKG_N_MR1, UID_N_MR1, defaultChannel, true);

        // ensure app level fields are changed
        assertFalse(mHelper.canShowBadge(PKG_N_MR1, UID_N_MR1));
        assertEquals(Notification.PRIORITY_MAX, mHelper.getPackagePriority(PKG_N_MR1, UID_N_MR1));
        assertEquals(Notification.VISIBILITY_SECRET, mHelper.getPackageVisibility(PKG_N_MR1,
                UID_N_MR1));
    }

    @Test
    public void testUpdate_postUpgrade_noUpdateAppFields() throws Exception {
        final NotificationChannel channel = new NotificationChannel("id2", "name2", IMPORTANCE_LOW);

        mHelper.createNotificationChannel(PKG_O, UID_O, channel, false, false);
        assertTrue(mHelper.canShowBadge(PKG_O, UID_O));
        assertEquals(Notification.PRIORITY_DEFAULT, mHelper.getPackagePriority(PKG_O, UID_O));
        assertEquals(NotificationManager.VISIBILITY_NO_OVERRIDE,
                mHelper.getPackageVisibility(PKG_O, UID_O));

        channel.setShowBadge(false);
        channel.setImportance(IMPORTANCE_NONE);
        channel.setBypassDnd(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);

        mHelper.updateNotificationChannel(PKG_O, UID_O, channel, true);

        // ensure app level fields are not changed
        assertTrue(mHelper.canShowBadge(PKG_O, UID_O));
        assertEquals(Notification.PRIORITY_DEFAULT, mHelper.getPackagePriority(PKG_O, UID_O));
        assertEquals(NotificationManager.VISIBILITY_NO_OVERRIDE,
                mHelper.getPackageVisibility(PKG_O, UID_O));
    }

    @Test
    public void testUpdate_preUpgrade_noUpdateAppFieldsWithMultipleChannels() throws Exception {
        final NotificationChannel channel = new NotificationChannel("id2", "name2", IMPORTANCE_LOW);

        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel, false, false);
        assertTrue(mHelper.canShowBadge(PKG_N_MR1, UID_N_MR1));
        assertEquals(Notification.PRIORITY_DEFAULT, mHelper.getPackagePriority(PKG_N_MR1, UID_N_MR1));
        assertEquals(NotificationManager.VISIBILITY_NO_OVERRIDE,
                mHelper.getPackageVisibility(PKG_N_MR1, UID_N_MR1));

        channel.setShowBadge(false);
        channel.setImportance(IMPORTANCE_NONE);
        channel.setBypassDnd(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);

        mHelper.updateNotificationChannel(PKG_N_MR1, UID_N_MR1, channel, true);

        NotificationChannel defaultChannel = mHelper.getNotificationChannel(
                PKG_N_MR1, UID_N_MR1, NotificationChannel.DEFAULT_CHANNEL_ID, false);

        defaultChannel.setShowBadge(false);
        defaultChannel.setImportance(IMPORTANCE_NONE);
        defaultChannel.setBypassDnd(true);
        defaultChannel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);

        mHelper.updateNotificationChannel(PKG_N_MR1, UID_N_MR1, defaultChannel, true);

        // ensure app level fields are not changed
        assertTrue(mHelper.canShowBadge(PKG_N_MR1, UID_N_MR1));
        assertEquals(Notification.PRIORITY_DEFAULT, mHelper.getPackagePriority(PKG_N_MR1, UID_N_MR1));
        assertEquals(NotificationManager.VISIBILITY_NO_OVERRIDE,
                mHelper.getPackageVisibility(PKG_N_MR1, UID_N_MR1));
    }

    @Test
    public void testGetNotificationChannel_ReturnsNullForUnknownChannel() throws Exception {
        assertEquals(null, mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, "garbage", false));
    }

    @Test
    public void testCreateChannel_CannotChangeHiddenFields() {
        final NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_HIGH);
        channel.setSound(new Uri.Builder().scheme("test").build(), mAudioAttributes);
        channel.enableLights(true);
        channel.setBypassDnd(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        channel.setShowBadge(true);
        channel.setAllowBubbles(false);
        channel.setImportantConversation(true);
        int lockMask = 0;
        for (int i = 0; i < NotificationChannel.LOCKABLE_FIELDS.length; i++) {
            lockMask |= NotificationChannel.LOCKABLE_FIELDS[i];
        }
        channel.lockFields(lockMask);

        assertTrue(mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel, true, false));

        NotificationChannel savedChannel =
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, channel.getId(), false);

        assertEquals(channel.getName(), savedChannel.getName());
        assertEquals(channel.shouldShowLights(), savedChannel.shouldShowLights());
        assertFalse(savedChannel.canBypassDnd());
        assertFalse(Notification.VISIBILITY_SECRET == savedChannel.getLockscreenVisibility());
        assertFalse(channel.isImportantConversation());
        assertEquals(channel.canShowBadge(), savedChannel.canShowBadge());
        assertEquals(channel.canBubble(), savedChannel.canBubble());

        verify(mHandler, never()).requestSort();
    }

    @Test
    public void testCreateChannel_CannotChangeHiddenFieldsAssistant() {
        final NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_HIGH);
        channel.setSound(new Uri.Builder().scheme("test").build(), mAudioAttributes);
        channel.enableLights(true);
        channel.setBypassDnd(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        channel.setShowBadge(true);
        channel.setAllowBubbles(false);
        int lockMask = 0;
        for (int i = 0; i < NotificationChannel.LOCKABLE_FIELDS.length; i++) {
            lockMask |= NotificationChannel.LOCKABLE_FIELDS[i];
        }
        channel.lockFields(lockMask);

        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel, true, false);

        NotificationChannel savedChannel =
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, channel.getId(), false);

        assertEquals(channel.getName(), savedChannel.getName());
        assertEquals(channel.shouldShowLights(), savedChannel.shouldShowLights());
        assertFalse(savedChannel.canBypassDnd());
        assertFalse(Notification.VISIBILITY_SECRET == savedChannel.getLockscreenVisibility());
        assertEquals(channel.canShowBadge(), savedChannel.canShowBadge());
        assertEquals(channel.canBubble(), savedChannel.canBubble());
    }

    @Test
    public void testClearLockedFields() {
        final NotificationChannel channel = getChannel();
        mHelper.clearLockedFieldsLocked(channel);
        assertEquals(0, channel.getUserLockedFields());

        channel.lockFields(NotificationChannel.USER_LOCKED_PRIORITY
                | NotificationChannel.USER_LOCKED_IMPORTANCE);
        mHelper.clearLockedFieldsLocked(channel);
        assertEquals(0, channel.getUserLockedFields());
    }

    @Test
    public void testLockFields_soundAndVibration() {
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, getChannel(), true, false);

        final NotificationChannel update1 = getChannel();
        update1.setSound(new Uri.Builder().scheme("test").build(),
                new AudioAttributes.Builder().build());
        update1.lockFields(NotificationChannel.USER_LOCKED_PRIORITY);
        mHelper.updateNotificationChannel(PKG_N_MR1, UID_N_MR1, update1, true);
        assertEquals(NotificationChannel.USER_LOCKED_PRIORITY
                | NotificationChannel.USER_LOCKED_SOUND,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, update1.getId(), false)
                        .getUserLockedFields());

        NotificationChannel update2 = getChannel();
        update2.enableVibration(true);
        mHelper.updateNotificationChannel(PKG_N_MR1, UID_N_MR1, update2, true);
        assertEquals(NotificationChannel.USER_LOCKED_PRIORITY
                        | NotificationChannel.USER_LOCKED_SOUND
                        | NotificationChannel.USER_LOCKED_VIBRATION,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, update2.getId(), false)
                        .getUserLockedFields());
    }

    @Test
    public void testLockFields_vibrationAndLights() {
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, getChannel(), true, false);

        final NotificationChannel update1 = getChannel();
        update1.setVibrationPattern(new long[]{7945, 46 ,246});
        mHelper.updateNotificationChannel(PKG_N_MR1, UID_N_MR1, update1, true);
        assertEquals(NotificationChannel.USER_LOCKED_VIBRATION,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, update1.getId(), false)
                        .getUserLockedFields());

        final NotificationChannel update2 = getChannel();
        update2.enableLights(true);
        mHelper.updateNotificationChannel(PKG_N_MR1, UID_N_MR1, update2, true);
        assertEquals(NotificationChannel.USER_LOCKED_VIBRATION
                        | NotificationChannel.USER_LOCKED_LIGHTS,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, update2.getId(), false)
                        .getUserLockedFields());
    }

    @Test
    public void testLockFields_lightsAndImportance() {
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, getChannel(), true, false);

        final NotificationChannel update1 = getChannel();
        update1.setLightColor(Color.GREEN);
        mHelper.updateNotificationChannel(PKG_N_MR1, UID_N_MR1, update1, true);
        assertEquals(NotificationChannel.USER_LOCKED_LIGHTS,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, update1.getId(), false)
                        .getUserLockedFields());

        final NotificationChannel update2 = getChannel();
        update2.setImportance(IMPORTANCE_DEFAULT);
        mHelper.updateNotificationChannel(PKG_N_MR1, UID_N_MR1, update2, true);
        assertEquals(NotificationChannel.USER_LOCKED_LIGHTS
                        | NotificationChannel.USER_LOCKED_IMPORTANCE,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, update2.getId(), false)
                        .getUserLockedFields());
    }

    @Test
    public void testLockFields_visibilityAndDndAndBadge() {
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, getChannel(), true, false);
        assertEquals(0,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, getChannel().getId(), false)
                        .getUserLockedFields());

        final NotificationChannel update1 = getChannel();
        update1.setBypassDnd(true);
        mHelper.updateNotificationChannel(PKG_N_MR1, UID_N_MR1, update1, true);
        assertEquals(NotificationChannel.USER_LOCKED_PRIORITY,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, update1.getId(), false)
                        .getUserLockedFields());

        final NotificationChannel update2 = getChannel();
        update2.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        mHelper.updateNotificationChannel(PKG_N_MR1, UID_N_MR1, update2, true);
        assertEquals(NotificationChannel.USER_LOCKED_PRIORITY
                        | NotificationChannel.USER_LOCKED_VISIBILITY,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, update2.getId(), false)
                        .getUserLockedFields());

        final NotificationChannel update3 = getChannel();
        update3.setShowBadge(false);
        mHelper.updateNotificationChannel(PKG_N_MR1, UID_N_MR1, update3, true);
        assertEquals(NotificationChannel.USER_LOCKED_PRIORITY
                        | NotificationChannel.USER_LOCKED_VISIBILITY
                        | NotificationChannel.USER_LOCKED_SHOW_BADGE,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, update3.getId(), false)
                        .getUserLockedFields());
    }

    @Test
    public void testLockFields_allowBubble() {
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, getChannel(), true, false);
        assertEquals(0,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, getChannel().getId(), false)
                        .getUserLockedFields());

        final NotificationChannel update = getChannel();
        update.setAllowBubbles(true);
        mHelper.updateNotificationChannel(PKG_N_MR1, UID_N_MR1, update, true);
        assertEquals(NotificationChannel.USER_LOCKED_ALLOW_BUBBLE,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, update.getId(), false)
                        .getUserLockedFields());
    }

    @Test
    public void testDeleteNonExistentChannel() throws Exception {
        mHelper.deleteNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, "does not exist");
    }

    @Test
    public void testDoubleDeleteChannel() throws Exception {
        NotificationChannel channel = getChannel();
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel, true, false);
        mHelper.deleteNotificationChannel(PKG_N_MR1, UID_N_MR1, channel.getId());
        mHelper.deleteNotificationChannel(PKG_N_MR1, UID_N_MR1, channel.getId());
        assertEquals(2, mLogger.getCalls().size());
        assertEquals(
                NotificationChannelLogger.NotificationChannelEvent.NOTIFICATION_CHANNEL_CREATED,
                mLogger.get(0).event);
        assertEquals(
                NotificationChannelLogger.NotificationChannelEvent.NOTIFICATION_CHANNEL_DELETED,
                mLogger.get(1).event);
        // No log for the second delete of the same channel.
    }

    @Test
    public void testGetDeletedChannel() throws Exception {
        NotificationChannel channel = getChannel();
        channel.setSound(new Uri.Builder().scheme("test").build(), mAudioAttributes);
        channel.enableLights(true);
        channel.setBypassDnd(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{100, 67, 145, 156});

        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel, true, false);
        mHelper.deleteNotificationChannel(PKG_N_MR1, UID_N_MR1, channel.getId());

        // Does not return deleted channel
        NotificationChannel response =
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, channel.getId(), false);
        assertNull(response);

        // Returns deleted channel
        response = mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, channel.getId(), true);
        compareChannels(channel, response);
        assertTrue(response.isDeleted());
    }

    @Test
    public void testGetDeletedChannels() throws Exception {
        Map<String, NotificationChannel> channelMap = new HashMap<>();
        NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel.setSound(new Uri.Builder().scheme("test").build(), mAudioAttributes);
        channel.enableLights(true);
        channel.setBypassDnd(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{100, 67, 145, 156});
        channelMap.put(channel.getId(), channel);
        NotificationChannel channel2 =
                new NotificationChannel("id4", "a", NotificationManager.IMPORTANCE_HIGH);
        channelMap.put(channel2.getId(), channel2);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel, true, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel2, true, false);

        mHelper.deleteNotificationChannel(PKG_N_MR1, UID_N_MR1, channel.getId());

        // Returns only non-deleted channels
        List<NotificationChannel> channels =
                mHelper.getNotificationChannels(PKG_N_MR1, UID_N_MR1, false).getList();
        assertEquals(2, channels.size());   // Default channel + non-deleted channel
        for (NotificationChannel nc : channels) {
            if (!NotificationChannel.DEFAULT_CHANNEL_ID.equals(nc.getId())) {
                compareChannels(channel2, nc);
            }
        }

        // Returns deleted channels too
        channels = mHelper.getNotificationChannels(PKG_N_MR1, UID_N_MR1, true).getList();
        assertEquals(3, channels.size());               // Includes default channel
        for (NotificationChannel nc : channels) {
            if (!NotificationChannel.DEFAULT_CHANNEL_ID.equals(nc.getId())) {
                compareChannels(channelMap.get(nc.getId()), nc);
            }
        }
    }

    @Test
    public void testGetDeletedChannelCount() throws Exception {
        NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        NotificationChannel channel2 =
                new NotificationChannel("id4", "a", NotificationManager.IMPORTANCE_HIGH);
        NotificationChannel channel3 =
                new NotificationChannel("id5", "a", NotificationManager.IMPORTANCE_HIGH);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel, true, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel2, true, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel3, true, false);

        mHelper.deleteNotificationChannel(PKG_N_MR1, UID_N_MR1, channel.getId());
        mHelper.deleteNotificationChannel(PKG_N_MR1, UID_N_MR1, channel3.getId());

        assertEquals(2, mHelper.getDeletedChannelCount(PKG_N_MR1, UID_N_MR1));
        assertEquals(0, mHelper.getDeletedChannelCount("pkg2", UID_O));
    }

    @Test
    public void testGetBlockedChannelCount() throws Exception {
        NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        NotificationChannel channel2 =
                new NotificationChannel("id4", "a", NotificationManager.IMPORTANCE_NONE);
        NotificationChannel channel3 =
                new NotificationChannel("id5", "a", NotificationManager.IMPORTANCE_NONE);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel, true, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel2, true, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel3, true, false);

        mHelper.deleteNotificationChannel(PKG_N_MR1, UID_N_MR1, channel3.getId());

        assertEquals(1, mHelper.getBlockedChannelCount(PKG_N_MR1, UID_N_MR1));
        assertEquals(0, mHelper.getBlockedChannelCount("pkg2", UID_O));
    }

    @Test
    public void testGetChannelsBypassingDndCount_noChannelsBypassing() throws Exception {
        assertEquals(0, mHelper.getNotificationChannelsBypassingDnd(PKG_N_MR1,
                UID_N_MR1).getList().size());
    }

    @Test
    public void testGetChannelsBypassingDnd_noChannelsForUidBypassing()
            throws Exception {
        int uid = 222;
        NotificationChannel channel = new NotificationChannel("id", "name",
                NotificationManager.IMPORTANCE_MAX);
        channel.setBypassDnd(true);
        mHelper.createNotificationChannel(PKG_N_MR1, 111, channel, true, true);

        assertEquals(0, mHelper.getNotificationChannelsBypassingDnd(PKG_N_MR1,
                uid).getList().size());
    }

    @Test
    public void testGetChannelsBypassingDndCount_oneChannelBypassing_groupBlocked() {
        int uid = UID_N_MR1;
        NotificationChannelGroup ncg = new NotificationChannelGroup("group1", "name1");
        NotificationChannel channel1 = new NotificationChannel("id1", "name1",
                NotificationManager.IMPORTANCE_MAX);
        channel1.setBypassDnd(true);
        channel1.setGroup(ncg.getId());
        mHelper.createNotificationChannelGroup(PKG_N_MR1, uid, ncg,  /* fromTargetApp */ true);
        mHelper.createNotificationChannel(PKG_N_MR1, uid, channel1, true, /*has DND access*/ true);

        assertEquals(1, mHelper.getNotificationChannelsBypassingDnd(PKG_N_MR1,
                uid).getList().size());

        // disable group
        ncg.setBlocked(true);
        mHelper.createNotificationChannelGroup(PKG_N_MR1, uid, ncg,  /* fromTargetApp */ false);
        assertEquals(0, mHelper.getNotificationChannelsBypassingDnd(PKG_N_MR1,
                uid).getList().size());
    }

    @Test
    public void testGetChannelsBypassingDndCount_multipleChannelsBypassing() {
        int uid = UID_N_MR1;
        NotificationChannel channel1 = new NotificationChannel("id1", "name1",
                NotificationManager.IMPORTANCE_MAX);
        NotificationChannel channel2 = new NotificationChannel("id2", "name2",
                NotificationManager.IMPORTANCE_MAX);
        NotificationChannel channel3 = new NotificationChannel("id3", "name3",
                NotificationManager.IMPORTANCE_MAX);
        channel1.setBypassDnd(true);
        channel2.setBypassDnd(true);
        channel3.setBypassDnd(true);
        // has DND access, so can set bypassDnd attribute
        mHelper.createNotificationChannel(PKG_N_MR1, uid, channel1, true, /*has DND access*/ true);
        mHelper.createNotificationChannel(PKG_N_MR1, uid, channel2, true, true);
        mHelper.createNotificationChannel(PKG_N_MR1, uid, channel3, true, true);
        assertEquals(3, mHelper.getNotificationChannelsBypassingDnd(PKG_N_MR1,
                uid).getList().size());

        // setBypassDnd false for some channels
        channel1.setBypassDnd(false);
        channel2.setBypassDnd(false);
        assertEquals(1, mHelper.getNotificationChannelsBypassingDnd(PKG_N_MR1,
                uid).getList().size());

        // setBypassDnd false for rest of the channels
        channel3.setBypassDnd(false);
        assertEquals(0, mHelper.getNotificationChannelsBypassingDnd(PKG_N_MR1,
                uid).getList().size());
    }

    @Test
    public void testCreateAndDeleteCanChannelsBypassDnd_localSettings() {
        int uid = UserManager.isHeadlessSystemUserMode() ? UID_HEADLESS : UID_N_MR1;
        when(mPermissionHelper.hasPermission(uid)).thenReturn(true);

        // create notification channel that can't bypass dnd
        // expected result: areChannelsBypassingDnd = false
        // setNotificationPolicy isn't called since areChannelsBypassingDnd was already false
        NotificationChannel channel = new NotificationChannel("id1", "name1", IMPORTANCE_LOW);
        mHelper.createNotificationChannel(PKG_N_MR1, uid, channel, true, false);
        assertFalse(mHelper.areChannelsBypassingDnd());
        verify(mMockZenModeHelper, never()).setNotificationPolicy(any());
        resetZenModeHelper();

        // create notification channel that can bypass dnd
        // expected result: areChannelsBypassingDnd = true
        NotificationChannel channel2 = new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel2.setBypassDnd(true);
        mHelper.createNotificationChannel(PKG_N_MR1, uid, channel2, true, true);
        assertTrue(mHelper.areChannelsBypassingDnd());
        verify(mMockZenModeHelper, times(1)).setNotificationPolicy(any());
        resetZenModeHelper();

        // delete channels
        mHelper.deleteNotificationChannel(PKG_N_MR1, uid, channel.getId());
        assertTrue(mHelper.areChannelsBypassingDnd()); // channel2 can still bypass DND
        verify(mMockZenModeHelper, never()).setNotificationPolicy(any());
        resetZenModeHelper();

        mHelper.deleteNotificationChannel(PKG_N_MR1, uid, channel2.getId());
        assertFalse(mHelper.areChannelsBypassingDnd());
        verify(mMockZenModeHelper, times(1)).setNotificationPolicy(any());
        resetZenModeHelper();
    }

    @Test
    public void testCreateAndUpdateChannelsBypassingDnd_permissionHelper() {
        int uid = UserManager.isHeadlessSystemUserMode() ? UID_HEADLESS : UID_N_MR1;
        when(mPermissionHelper.hasPermission(uid)).thenReturn(true);

        // create notification channel that can't bypass dnd
        // expected result: areChannelsBypassingDnd = false
        // setNotificationPolicy isn't called since areChannelsBypassingDnd was already false
        NotificationChannel channel = new NotificationChannel("id1", "name1", IMPORTANCE_LOW);
        mHelper.createNotificationChannel(PKG_N_MR1, uid, channel, true, false);
        assertFalse(mHelper.areChannelsBypassingDnd());
        verify(mMockZenModeHelper, never()).setNotificationPolicy(any());
        resetZenModeHelper();

        // Recreate a channel & now the app has dnd access granted and can set the bypass dnd field
        NotificationChannel update = new NotificationChannel("id1", "name1", IMPORTANCE_LOW);
        update.setBypassDnd(true);
        mHelper.createNotificationChannel(PKG_N_MR1, uid, update, true, true);

        assertTrue(mHelper.areChannelsBypassingDnd());
        verify(mMockZenModeHelper, times(1)).setNotificationPolicy(any());
        resetZenModeHelper();
    }

    @Test
    public void testCreateAndDeleteCanChannelsBypassDnd_permissionHelper() {
        int uid = UserManager.isHeadlessSystemUserMode() ? UID_HEADLESS : UID_N_MR1;
        when(mPermissionHelper.hasPermission(uid)).thenReturn(true);

        // create notification channel that can't bypass dnd
        // expected result: areChannelsBypassingDnd = false
        // setNotificationPolicy isn't called since areChannelsBypassingDnd was already false
        NotificationChannel channel = new NotificationChannel("id1", "name1", IMPORTANCE_LOW);
        mHelper.createNotificationChannel(PKG_N_MR1, uid, channel, true, false);
        assertFalse(mHelper.areChannelsBypassingDnd());
        verify(mMockZenModeHelper, never()).setNotificationPolicy(any());
        resetZenModeHelper();

        // create notification channel that can bypass dnd, using local app level settings
        // expected result: areChannelsBypassingDnd = true
        NotificationChannel channel2 = new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel2.setBypassDnd(true);
        mHelper.createNotificationChannel(PKG_N_MR1, uid, channel2, true, true);
        assertTrue(mHelper.areChannelsBypassingDnd());
        verify(mMockZenModeHelper, times(1)).setNotificationPolicy(any());
        resetZenModeHelper();

        // delete channels
        mHelper.deleteNotificationChannel(PKG_N_MR1, uid, channel.getId());
        assertTrue(mHelper.areChannelsBypassingDnd()); // channel2 can still bypass DND
        verify(mMockZenModeHelper, never()).setNotificationPolicy(any());
        resetZenModeHelper();

        mHelper.deleteNotificationChannel(PKG_N_MR1, uid, channel2.getId());
        assertFalse(mHelper.areChannelsBypassingDnd());
        verify(mMockZenModeHelper, times(1)).setNotificationPolicy(any());
        resetZenModeHelper();
    }

    @Test
    public void testBlockedGroupDoesNotBypassDnd() {
        int uid = UserManager.isHeadlessSystemUserMode() ? UID_HEADLESS : UID_N_MR1;
        when(mPermissionHelper.hasPermission(uid)).thenReturn(true);

        // start in a 'allowed to bypass dnd state'
        mTestNotificationPolicy = new NotificationManager.Policy(0, 0, 0, 0,
                NotificationManager.Policy.STATE_CHANNELS_BYPASSING_DND, 0);
        when(mMockZenModeHelper.getNotificationPolicy()).thenReturn(mTestNotificationPolicy);
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);


        // create notification channel that can bypass dnd, but app is blocked
        // expected result: areChannelsBypassingDnd = false
        NotificationChannelGroup group = new NotificationChannelGroup("group", "group");
        group.setBlocked(true);
        mHelper.createNotificationChannelGroup(PKG_N_MR1, uid, group, false);
        NotificationChannel channel2 = new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel2.setGroup("group");
        channel2.setBypassDnd(true);
        mHelper.createNotificationChannel(PKG_N_MR1, uid, channel2, true, true);
        assertFalse(mHelper.areChannelsBypassingDnd());
        verify(mMockZenModeHelper, times(1)).setNotificationPolicy(any());
        resetZenModeHelper();
    }

    @Test
    public void testBlockedAppsDoNotBypassDnd_localSettings() {
        int uid = UserManager.isHeadlessSystemUserMode() ? UID_HEADLESS : UID_N_MR1;
        when(mPermissionHelper.hasPermission(uid)).thenReturn(false);

        // start in a 'allowed to bypass dnd state'
        mTestNotificationPolicy = new NotificationManager.Policy(0, 0, 0, 0,
                NotificationManager.Policy.STATE_CHANNELS_BYPASSING_DND, 0);
        when(mMockZenModeHelper.getNotificationPolicy()).thenReturn(mTestNotificationPolicy);
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);

        // create notification channel that can bypass dnd, but app is blocked
        // expected result: areChannelsBypassingDnd = false
        NotificationChannel channel2 = new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel2.setBypassDnd(true);
        mHelper.createNotificationChannel(PKG_N_MR1, uid, channel2, true, true);
        assertFalse(mHelper.areChannelsBypassingDnd());
        verify(mMockZenModeHelper, times(1)).setNotificationPolicy(any());
        resetZenModeHelper();
    }

    @Test
    public void testBlockedAppsDoNotBypassDnd_permissionHelper() {
        int uid = UserManager.isHeadlessSystemUserMode() ? UID_HEADLESS : UID_N_MR1;
        when(mPermissionHelper.hasPermission(uid)).thenReturn(false);

        // start in a 'allowed to bypass dnd state'
        mTestNotificationPolicy = new NotificationManager.Policy(0, 0, 0, 0,
                NotificationManager.Policy.STATE_CHANNELS_BYPASSING_DND, 0);
        when(mMockZenModeHelper.getNotificationPolicy()).thenReturn(mTestNotificationPolicy);
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);

        // create notification channel that can bypass dnd, but app is blocked
        // expected result: areChannelsBypassingDnd = false
        NotificationChannel channel2 = new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel2.setBypassDnd(true);
        mHelper.createNotificationChannel(PKG_N_MR1, uid, channel2, true, true);
        assertFalse(mHelper.areChannelsBypassingDnd());
        verify(mMockZenModeHelper, times(1)).setNotificationPolicy(any());
        resetZenModeHelper();
    }

    @Test
    public void testUpdateCanChannelsBypassDnd() {
        int uid = UserManager.isHeadlessSystemUserMode() ? UID_HEADLESS : UID_N_MR1;
        when(mPermissionHelper.hasPermission(uid)).thenReturn(true);

        // create notification channel that can't bypass dnd
        // expected result: areChannelsBypassingDnd = false
        // setNotificationPolicy isn't called since areChannelsBypassingDnd was already false
        NotificationChannel channel = new NotificationChannel("id1", "name1", IMPORTANCE_LOW);
        mHelper.createNotificationChannel(PKG_N_MR1, uid, channel, true, false);
        assertFalse(mHelper.areChannelsBypassingDnd());
        verify(mMockZenModeHelper, never()).setNotificationPolicy(any());
        resetZenModeHelper();

        // update channel so it CAN bypass dnd:
        // expected result: areChannelsBypassingDnd = true
        channel.setBypassDnd(true);
        mHelper.updateNotificationChannel(PKG_N_MR1, uid, channel, true);
        assertTrue(mHelper.areChannelsBypassingDnd());
        verify(mMockZenModeHelper, times(1)).setNotificationPolicy(any());
        resetZenModeHelper();

        // update channel so it can't bypass dnd:
        // expected result: areChannelsBypassingDnd = false
        channel.setBypassDnd(false);
        mHelper.updateNotificationChannel(PKG_N_MR1, uid, channel, true);
        assertFalse(mHelper.areChannelsBypassingDnd());
        verify(mMockZenModeHelper, times(1)).setNotificationPolicy(any());
        resetZenModeHelper();
    }

    @Test
    public void testSetupNewZenModeHelper_canBypass() {
        // start notification policy off with mAreChannelsBypassingDnd = true, but
        // RankingHelper should change to false
        mTestNotificationPolicy = new NotificationManager.Policy(0, 0, 0, 0,
                NotificationManager.Policy.STATE_CHANNELS_BYPASSING_DND, 0);
        when(mMockZenModeHelper.getNotificationPolicy()).thenReturn(mTestNotificationPolicy);
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);
        assertFalse(mHelper.areChannelsBypassingDnd());
        verify(mMockZenModeHelper, times(1)).setNotificationPolicy(any());
        resetZenModeHelper();
    }

    @Test
    public void testSetupNewZenModeHelper_cannotBypass() {
        // start notification policy off with mAreChannelsBypassingDnd = false
        mTestNotificationPolicy = new NotificationManager.Policy(0, 0, 0, 0, 0, 0);
        when(mMockZenModeHelper.getNotificationPolicy()).thenReturn(mTestNotificationPolicy);
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);
        assertFalse(mHelper.areChannelsBypassingDnd());
        verify(mMockZenModeHelper, never()).setNotificationPolicy(any());
        resetZenModeHelper();
    }

    @Test
    public void testCreateDeletedChannel() throws Exception {
        long[] vibration = new long[]{100, 67, 145, 156};
        NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel.setVibrationPattern(vibration);

        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel, true, false);
        mHelper.deleteNotificationChannel(PKG_N_MR1, UID_N_MR1, channel.getId());

        NotificationChannel newChannel = new NotificationChannel(
                channel.getId(), channel.getName(), NotificationManager.IMPORTANCE_HIGH);
        newChannel.setVibrationPattern(new long[]{100});

        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, newChannel, true, false);

        // No long deleted, using old settings
        compareChannels(channel,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, newChannel.getId(), false));
    }

    @Test
    public void testOnlyHasDefaultChannel() throws Exception {
        assertTrue(mHelper.onlyHasDefaultChannel(PKG_N_MR1, UID_N_MR1));
        assertFalse(mHelper.onlyHasDefaultChannel(PKG_O, UID_O));

        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, getChannel(), true, false);
        assertFalse(mHelper.onlyHasDefaultChannel(PKG_N_MR1, UID_N_MR1));
    }

    @Test
    public void testCreateChannel_defaultChannelId() throws Exception {
        try {
            mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, new NotificationChannel(
                    NotificationChannel.DEFAULT_CHANNEL_ID, "ha", IMPORTANCE_HIGH), true, false);
            fail("Allowed to create default channel");
        } catch (IllegalArgumentException e) {
            // pass
        }
    }

    @Test
    public void testCreateChannel_alreadyExists() throws Exception {
        long[] vibration = new long[]{100, 67, 145, 156};
        NotificationChannel channel =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        channel.setVibrationPattern(vibration);

        assertTrue(mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel, true, false));

        NotificationChannel newChannel = new NotificationChannel(
                channel.getId(), channel.getName(), NotificationManager.IMPORTANCE_HIGH);
        newChannel.setVibrationPattern(new long[]{100});
        newChannel.setAllowBubbles(!channel.canBubble());
        newChannel.setLightColor(Color.BLUE);
        newChannel.setSound(Uri.EMPTY, null);
        newChannel.setShowBadge(!channel.canShowBadge());

        assertFalse(mHelper.createNotificationChannel(
                PKG_N_MR1, UID_N_MR1, newChannel, true, false));

        // Old settings not overridden
        compareChannels(channel,
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, newChannel.getId(), false));

        assertEquals(1, mLogger.getCalls().size());
        assertEquals(
                NotificationChannelLogger.NotificationChannelEvent.NOTIFICATION_CHANNEL_CREATED,
                mLogger.get(0).event);
    }

    @Test
    public void testCreateChannel_noOverrideSound() throws Exception {
        Uri sound = new Uri.Builder().scheme("test").build();
        final NotificationChannel channel = new NotificationChannel("id2", "name2",
                 NotificationManager.IMPORTANCE_DEFAULT);
        channel.setSound(sound, mAudioAttributes);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel, true, false);
        assertEquals(sound, mHelper.getNotificationChannel(
                PKG_N_MR1, UID_N_MR1, channel.getId(), false).getSound());
    }

    @Test
    public void testPermanentlyDeleteChannels() throws Exception {
        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);

        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel1, true, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel2, false, false);

        mHelper.permanentlyDeleteNotificationChannels(PKG_N_MR1, UID_N_MR1);

        // Only default channel remains
        assertEquals(1, mHelper.getNotificationChannels(PKG_N_MR1, UID_N_MR1, true).getList().size());
    }

    @Test
    public void testDeleteGroup() throws Exception {
        NotificationChannelGroup notDeleted = new NotificationChannelGroup("not", "deleted");
        NotificationChannelGroup deleted = new NotificationChannelGroup("totally", "deleted");
        NotificationChannel nonGroupedNonDeletedChannel =
                new NotificationChannel("no group", "so not deleted", IMPORTANCE_HIGH);
        NotificationChannel groupedButNotDeleted =
                new NotificationChannel("not deleted", "belongs to notDeleted", IMPORTANCE_DEFAULT);
        groupedButNotDeleted.setGroup("not");
        NotificationChannel groupedAndDeleted =
                new NotificationChannel("deleted", "belongs to deleted", IMPORTANCE_DEFAULT);
        groupedAndDeleted.setGroup("totally");

        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, notDeleted, true);
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, deleted, true);
        mHelper.createNotificationChannel(
                PKG_N_MR1, UID_N_MR1, nonGroupedNonDeletedChannel, true, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, groupedAndDeleted, true, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, groupedButNotDeleted, true, false);

        mHelper.deleteNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, deleted.getId());

        assertNull(mHelper.getNotificationChannelGroup(deleted.getId(), PKG_N_MR1, UID_N_MR1));
        assertNotNull(
                mHelper.getNotificationChannelGroup(notDeleted.getId(), PKG_N_MR1, UID_N_MR1));

        assertNull(mHelper.getNotificationChannel(
                PKG_N_MR1, UID_N_MR1, groupedAndDeleted.getId(), false));
        compareChannels(groupedAndDeleted, mHelper.getNotificationChannel(
                PKG_N_MR1, UID_N_MR1, groupedAndDeleted.getId(), true));

        compareChannels(groupedButNotDeleted, mHelper.getNotificationChannel(
                PKG_N_MR1, UID_N_MR1, groupedButNotDeleted.getId(), false));
        compareChannels(nonGroupedNonDeletedChannel, mHelper.getNotificationChannel(
                PKG_N_MR1, UID_N_MR1, nonGroupedNonDeletedChannel.getId(), false));

        // notDeleted
        assertEquals(1, mHelper.getNotificationChannelGroups(PKG_N_MR1, UID_N_MR1).size());

        verify(mHandler, never()).requestSort();

        assertEquals(7, mLogger.getCalls().size());
        assertEquals(
                NotificationChannelLogger.NotificationChannelEvent
                        .NOTIFICATION_CHANNEL_GROUP_DELETED,
                mLogger.get(5).event);  // Next-to-last log is the deletion of the channel group.
        assertEquals(
                NotificationChannelLogger.NotificationChannelEvent
                        .NOTIFICATION_CHANNEL_DELETED,
                mLogger.get(6).event);  // Final log is the deletion of the channel.
    }

    @Test
    public void testOnUserRemoved() throws Exception {
        int[] user0Uids = {98, 235, 16, 3782};
        int[] user1Uids = new int[user0Uids.length];
        for (int i = 0; i < user0Uids.length; i++) {
            user1Uids[i] = UserHandle.PER_USER_RANGE + user0Uids[i];

            final ApplicationInfo legacy = new ApplicationInfo();
            legacy.targetSdkVersion = Build.VERSION_CODES.N_MR1;
            when(mPm.getApplicationInfoAsUser(eq(PKG_N_MR1), anyInt(), anyInt())).thenReturn(legacy);

            // create records with the default channel for all user 0 and user 1 uids
            mHelper.canShowBadge(PKG_N_MR1, user0Uids[i]);
            mHelper.canShowBadge(PKG_N_MR1, user1Uids[i]);
        }

        mHelper.onUserRemoved(1);

        // user 0 records remain
        for (int i = 0; i < user0Uids.length; i++) {
            assertEquals(1,
                    mHelper.getNotificationChannels(PKG_N_MR1, user0Uids[i], false).getList().size());
        }
        // user 1 records are gone
        for (int i = 0; i < user1Uids.length; i++) {
            assertEquals(0,
                    mHelper.getNotificationChannels(PKG_N_MR1, user1Uids[i], false).getList().size());
        }
    }

    @Test
    public void testOnPackageChanged_packageRemoval() throws Exception {
        // Deleted
        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel1, true, false);

        assertTrue(mHelper.onPackagesChanged(true, USER_SYSTEM, new String[]{PKG_N_MR1},
                new int[]{UID_N_MR1}));

        assertEquals(0, mHelper.getNotificationChannels(
                PKG_N_MR1, UID_N_MR1, true).getList().size());

        // Not deleted
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel1, true, false);

        assertFalse(mHelper.onPackagesChanged(false, USER_SYSTEM,
                new String[]{PKG_N_MR1}, new int[]{UID_N_MR1}));
        assertEquals(2, mHelper.getNotificationChannels(PKG_N_MR1, UID_N_MR1, false).getList().size());
    }

    @Test
    public void testOnPackageChanged_packageRemoval_groups() throws Exception {
        NotificationChannelGroup ncg = new NotificationChannelGroup("group1", "name1");
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg, true);
        NotificationChannelGroup ncg2 = new NotificationChannelGroup("group2", "name2");
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg2, true);

        mHelper.onPackagesChanged(true, USER_SYSTEM, new String[]{PKG_N_MR1}, new int[]{
                UID_N_MR1});

        assertEquals(0, mHelper.getNotificationChannelGroups(
                PKG_N_MR1, UID_N_MR1, true, true, false).getList().size());
    }

    @Test
    public void testOnPackageChange_downgradeTargetSdk() throws Exception {
        // create channel as api 26
        mHelper.createNotificationChannel(PKG_O, UID_O, getChannel(), true, false);

        // install new app version targeting 25
        final ApplicationInfo legacy = new ApplicationInfo();
        legacy.targetSdkVersion = Build.VERSION_CODES.N_MR1;
        when(mPm.getApplicationInfoAsUser(eq(PKG_O), anyInt(), anyInt())).thenReturn(legacy);
        mHelper.onPackagesChanged(
                false, USER_SYSTEM, new String[]{PKG_O}, new int[]{UID_O});

        // make sure the default channel was readded
        //assertEquals(2, mHelper.getNotificationChannels(PKG_O, UID_O, false).getList().size());
        assertNotNull(mHelper.getNotificationChannel(
                PKG_O, UID_O, NotificationChannel.DEFAULT_CHANNEL_ID, false));
    }

    @Test
    public void testClearData() {
        ArraySet<String> pkg = new ArraySet<>();
        pkg.add(PKG_O);
        ArraySet<Pair<String, Integer>> pkgPair = new ArraySet<>();
        pkgPair.add(new Pair(PKG_O, UID_O));
        mHelper.createNotificationChannel(PKG_O, UID_O, getChannel(), true, false);
        mHelper.createNotificationChannelGroup(
                PKG_O, UID_O, new NotificationChannelGroup("1", "bye"), true);
        mHelper.updateDefaultApps(UserHandle.getUserId(UID_O), null, pkgPair);
        mHelper.setNotificationDelegate(PKG_O, UID_O, "", 1);
        mHelper.setBubblesAllowed(PKG_O, UID_O, DEFAULT_BUBBLE_PREFERENCE);
        mHelper.setShowBadge(PKG_O, UID_O, false);
        mHelper.setAppImportanceLocked(PKG_O, UID_O);

        mHelper.clearData(PKG_O, UID_O);

        assertEquals(mHelper.getBubblePreference(PKG_O, UID_O), DEFAULT_BUBBLE_PREFERENCE);
        assertTrue(mHelper.canShowBadge(PKG_O, UID_O));
        assertNull(mHelper.getNotificationDelegate(PKG_O, UID_O));
        assertEquals(0, mHelper.getAppLockedFields(PKG_O, UID_O));
        assertEquals(0, mHelper.getNotificationChannels(PKG_O, UID_O, true).getList().size());
        assertEquals(0, mHelper.getNotificationChannelGroups(PKG_O, UID_O).size());

        NotificationChannel channel = getChannel();
        mHelper.createNotificationChannel(PKG_O, UID_O, channel, true, false);

        assertTrue(channel.isImportanceLockedByCriticalDeviceFunction());
    }

    @Test
    public void testRecordDefaults() throws Exception {
        assertEquals(true, mHelper.canShowBadge(PKG_N_MR1, UID_N_MR1));
        assertEquals(1, mHelper.getNotificationChannels(PKG_N_MR1, UID_N_MR1, false).getList().size());
    }

    @Test
    public void testCreateGroup() {
        NotificationChannelGroup ncg = new NotificationChannelGroup("group1", "name1");
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg, true);
        assertEquals(ncg,
                mHelper.getNotificationChannelGroups(PKG_N_MR1, UID_N_MR1).iterator().next());
        verify(mHandler, never()).requestSort();
        assertEquals(1, mLogger.getCalls().size());
        assertEquals(
                NotificationChannelLogger.NotificationChannelEvent
                        .NOTIFICATION_CHANNEL_GROUP_CREATED,
                mLogger.get(0).event);
    }

    @Test
    public void testCannotCreateChannel_badGroup() {
        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        channel1.setGroup("garbage");
        try {
            mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel1, true, false);
            fail("Created a channel with a bad group");
        } catch (IllegalArgumentException e) {
        }
        assertEquals(0, mLogger.getCalls().size());
    }

    @Test
    public void testCannotCreateChannel_goodGroup() {
        NotificationChannelGroup ncg = new NotificationChannelGroup("group1", "name1");
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg, true);
        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        channel1.setGroup(ncg.getId());
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel1, true, false);

        assertEquals(ncg.getId(), mHelper.getNotificationChannel(
                PKG_N_MR1, UID_N_MR1, channel1.getId(), false).getGroup());
    }

    @Test
    public void testGetChannelGroups() {
        NotificationChannelGroup unused = new NotificationChannelGroup("unused", "s");
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, unused, true);
        NotificationChannelGroup ncg = new NotificationChannelGroup("group1", "name1");
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg, true);
        NotificationChannelGroup ncg2 = new NotificationChannelGroup("group2", "name2");
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg2, true);

        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        channel1.setGroup(ncg.getId());
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel1, true, false);
        NotificationChannel channel1a =
                new NotificationChannel("id1a", "name1", NotificationManager.IMPORTANCE_HIGH);
        channel1a.setGroup(ncg.getId());
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel1a, true, false);

        NotificationChannel channel2 =
                new NotificationChannel("id2", "name1", NotificationManager.IMPORTANCE_HIGH);
        channel2.setGroup(ncg2.getId());
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel2, true, false);

        NotificationChannel channel3 =
                new NotificationChannel("id3", "name1", NotificationManager.IMPORTANCE_HIGH);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel3, true, false);

        List<NotificationChannelGroup> actual = mHelper.getNotificationChannelGroups(
                PKG_N_MR1, UID_N_MR1, true, true, false).getList();
        assertEquals(3, actual.size());
        for (NotificationChannelGroup group : actual) {
            if (group.getId() == null) {
                assertEquals(2, group.getChannels().size()); // misc channel too
                assertTrue(channel3.getId().equals(group.getChannels().get(0).getId())
                        || channel3.getId().equals(group.getChannels().get(1).getId()));
            } else if (group.getId().equals(ncg.getId())) {
                assertEquals(2, group.getChannels().size());
                if (group.getChannels().get(0).getId().equals(channel1.getId())) {
                    assertTrue(group.getChannels().get(1).getId().equals(channel1a.getId()));
                } else if (group.getChannels().get(0).getId().equals(channel1a.getId())) {
                    assertTrue(group.getChannels().get(1).getId().equals(channel1.getId()));
                } else {
                    fail("expected channel not found");
                }
            } else if (group.getId().equals(ncg2.getId())) {
                assertEquals(1, group.getChannels().size());
                assertEquals(channel2.getId(), group.getChannels().get(0).getId());
            }
        }
    }

    @Test
    public void testGetChannelGroups_noSideEffects() {
        NotificationChannelGroup ncg = new NotificationChannelGroup("group1", "name1");
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg, true);

        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        channel1.setGroup(ncg.getId());
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel1, true, false);
        mHelper.getNotificationChannelGroups(PKG_N_MR1, UID_N_MR1, true, true, false).getList();

        channel1.setImportance(IMPORTANCE_LOW);
        mHelper.updateNotificationChannel(PKG_N_MR1, UID_N_MR1, channel1, true);

        List<NotificationChannelGroup> actual = mHelper.getNotificationChannelGroups(
                PKG_N_MR1, UID_N_MR1, true, true, false).getList();

        assertEquals(2, actual.size());
        for (NotificationChannelGroup group : actual) {
            if (Objects.equals(group.getId(), ncg.getId())) {
                assertEquals(1, group.getChannels().size());
            }
        }
    }

    @Test
    public void testGetChannelGroups_includeEmptyGroups() {
        NotificationChannelGroup ncg = new NotificationChannelGroup("group1", "name1");
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncg, true);
        NotificationChannelGroup ncgEmpty = new NotificationChannelGroup("group2", "name2");
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, ncgEmpty, true);

        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        channel1.setGroup(ncg.getId());
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel1, true, false);

        List<NotificationChannelGroup> actual = mHelper.getNotificationChannelGroups(
                PKG_N_MR1, UID_N_MR1, false, false, true).getList();

        assertEquals(2, actual.size());
        for (NotificationChannelGroup group : actual) {
            if (Objects.equals(group.getId(), ncg.getId())) {
                assertEquals(1, group.getChannels().size());
            }
            if (Objects.equals(group.getId(), ncgEmpty.getId())) {
                assertEquals(0, group.getChannels().size());
            }
        }
    }

    @Test
    public void testCreateChannel_updateName() {
        NotificationChannel nc = new NotificationChannel("id", "hello", IMPORTANCE_DEFAULT);
        assertTrue(mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, nc, true, false));
        NotificationChannel actual =
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, "id", false);
        assertEquals("hello", actual.getName());

        nc = new NotificationChannel("id", "goodbye", IMPORTANCE_HIGH);
        assertTrue(mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, nc, true, false));

        actual = mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, "id", false);
        assertEquals("goodbye", actual.getName());
        assertEquals(IMPORTANCE_DEFAULT, actual.getImportance());

        verify(mHandler, times(1)).requestSort();
    }

    @Test
    public void testCreateChannel_addToGroup() {
        NotificationChannelGroup group = new NotificationChannelGroup("group", "");
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, group, true);
        NotificationChannel nc = new NotificationChannel("id", "hello", IMPORTANCE_DEFAULT);
        assertTrue(mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, nc, true, false));
        NotificationChannel actual =
                mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, "id", false);
        assertNull(actual.getGroup());

        nc = new NotificationChannel("id", "hello", IMPORTANCE_HIGH);
        nc.setGroup(group.getId());
        assertTrue(mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, nc, true, false));

        actual = mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, "id", false);
        assertNotNull(actual.getGroup());
        assertEquals(IMPORTANCE_DEFAULT, actual.getImportance());

        verify(mHandler, times(1)).requestSort();
        assertEquals(3, mLogger.getCalls().size());
        assertEquals(
                NotificationChannelLogger.NotificationChannelEvent
                        .NOTIFICATION_CHANNEL_GROUP_CREATED,
                mLogger.get(0).event);
        assertEquals(
                NotificationChannelLogger.NotificationChannelEvent.NOTIFICATION_CHANNEL_CREATED,
                mLogger.get(1).event);
        assertEquals(
                NotificationChannelLogger.NotificationChannelEvent.NOTIFICATION_CHANNEL_UPDATED,
                mLogger.get(2).event);
    }

    @Test
    public void testDumpChannelsJson() throws Exception {
        final ApplicationInfo upgrade = new ApplicationInfo();
        upgrade.targetSdkVersion = Build.VERSION_CODES.O;
        try {
            when(mPm.getApplicationInfoAsUser(
                    anyString(), anyInt(), anyInt())).thenReturn(upgrade);
        } catch (PackageManager.NameNotFoundException e) {
        }
        ArrayMap<String, Integer> expectedChannels = new ArrayMap<>();
        int numPackages = ThreadLocalRandom.current().nextInt(1, 5);
        for (int i = 0; i < numPackages; i++) {
            String pkgName = "pkg" + i;
            int numChannels = ThreadLocalRandom.current().nextInt(1, 10);
            for (int j = 0; j < numChannels; j++) {
                mHelper.createNotificationChannel(pkgName, UID_N_MR1,
                        new NotificationChannel("" + j, "a", IMPORTANCE_HIGH), true, false);
            }
            expectedChannels.put(pkgName, numChannels);
        }

        // delete the first channel of the first package
        String pkg = expectedChannels.keyAt(0);
        mHelper.deleteNotificationChannel("pkg" + 0, UID_N_MR1, "0");
        // dump should not include deleted channels
        int count = expectedChannels.get(pkg);
        expectedChannels.put(pkg, count - 1);

        JSONArray actual = mHelper.dumpChannelsJson(new NotificationManagerService.DumpFilter());
        assertEquals(numPackages, actual.length());
        for (int i = 0; i < numPackages; i++) {
            JSONObject object = actual.getJSONObject(i);
            assertTrue(expectedChannels.containsKey(object.get("packageName")));
            assertEquals(expectedChannels.get(object.get("packageName")).intValue(),
                    object.getInt("channelCount"));
        }
    }

    @Test
    public void testDumpJson_postPermissionMigration() throws Exception {
        // when getting a json dump, we want to verify that:
        //   - all notification importance info should come from the permission, even if the data
        //     isn't there yet but is present in package preferences
        //   - if there are permissions granted or denied from packages PreferencesHelper doesn't
        //     know about, those should still be included

        // package permissions map to be passed in
        ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> appPermissions = new ArrayMap<>();
        appPermissions.put(new Pair(1, "first"), new Pair(true, false));    // not in local prefs
        appPermissions.put(new Pair(3, "third"), new Pair(false, false));   // not in local prefs
        appPermissions.put(new Pair(UID_P, PKG_P), new Pair(true, false));  // in local prefs
        appPermissions.put(new Pair(UID_O, PKG_O), new Pair(false, false)); // in local prefs

        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);
        NotificationChannel channel2 =
                new NotificationChannel("id2", "name2", IMPORTANCE_LOW);
        NotificationChannel channel3 = new NotificationChannel("id3", "name3", IMPORTANCE_HIGH);

        mHelper.createNotificationChannel(PKG_P, UID_P, channel1, true, false);
        mHelper.createNotificationChannel(PKG_P, UID_P, channel2, false, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, channel3, false, false);
        mHelper.createNotificationChannel(PKG_O, UID_O, getChannel(), true, false);

        // in the json array, all of the individual package preferences are simply elements in the
        // values array. this set is to collect expected outputs for each of our packages.
        // the key/value pairs are: (userId, package name) -> expected importance
        ArrayMap<Pair<Integer, String>, String> expected = new ArrayMap<>();

        // packages that only exist via the app permissions; should be present
        expected.put(new Pair(UserHandle.getUserId(1), "first"), "DEFAULT");
        expected.put(new Pair(UserHandle.getUserId(3), "third"), "NONE");

        // packages that exist in both app permissions & local preferences
        expected.put(new Pair(UserHandle.getUserId(UID_P), PKG_P), "DEFAULT");
        expected.put(new Pair(UserHandle.getUserId(UID_O), PKG_O), "NONE");

        // package that only exists in local preferences; expect no importance output
        expected.put(new Pair(UserHandle.getUserId(UID_N_MR1), PKG_N_MR1), null);

        JSONArray actual = (JSONArray) mHelper.dumpJson(
                new NotificationManagerService.DumpFilter(), appPermissions)
                .get("PackagePreferencess");
        assertThat(actual.length()).isEqualTo(expected.size());
        for (int i = 0; i < actual.length(); i++) {
            JSONObject pkgInfo = actual.getJSONObject(i);
            Pair<Integer, String> pkgKey =
                    new Pair(pkgInfo.getInt("userId"), pkgInfo.getString("packageName"));
            assertTrue(expected.containsKey(pkgKey));
            if (pkgInfo.has("importance")) {
                assertThat(pkgInfo.getString("importance")).isEqualTo(expected.get(pkgKey));
            } else {
                assertThat(expected.get(pkgKey)).isNull();
            }
        }
    }

    @Test
    public void testDumpJson_givenNullInput_postMigration() throws Exception {
        // simple test just to make sure nothing dies if we pass in null input even post migration
        // for some reason, even though in practice this should not be how one calls this method

        // some packages exist
        mHelper.canShowBadge(PKG_O, UID_O);
        mHelper.canShowBadge(PKG_P, UID_P);

        JSONArray actual = (JSONArray) mHelper.dumpJson(
                new NotificationManagerService.DumpFilter(), null)
                .get("PackagePreferencess");

        // there should still be info for the packages
        assertThat(actual.length()).isEqualTo(2);

        // but they should not have importance info because the migration is enabled and it got
        // no info
        for (int i = 0; i < actual.length(); i++) {
            assertFalse(actual.getJSONObject(i).has("importance"));
        }
    }

    @Test
    public void testDumpBansJson_postPermissionMigration() throws Exception {
        // confirm that the package bans that are in the output include all packages that
        // have their permission set to false, and not based on PackagePreferences importance

        ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> appPermissions = new ArrayMap<>();
        appPermissions.put(new Pair(1, "first"), new Pair(true, false));    // not in local prefs
        appPermissions.put(new Pair(3, "third"), new Pair(false, false));   // not in local prefs
        appPermissions.put(new Pair(UID_O, PKG_O), new Pair(false, false)); // in local prefs

        mHelper.canShowBadge(PKG_O, UID_O);

        // expected output
        ArraySet<Pair<Integer, String>> expected = new ArraySet<>();
        expected.add(new Pair(UserHandle.getUserId(3), "third"));
        expected.add(new Pair(UserHandle.getUserId(UID_O), PKG_O));

        // make sure that's the only thing in the package ban output
        JSONArray actual = mHelper.dumpBansJson(
                new NotificationManagerService.DumpFilter(), appPermissions);
        assertThat(actual.length()).isEqualTo(expected.size());

        for (int i = 0; i < actual.length(); i++) {
            JSONObject ban = actual.getJSONObject(i);
            assertTrue(expected.contains(
                    new Pair(ban.getInt("userId"), ban.getString("packageName"))));
        }
    }

    @Test
    public void testDumpBansJson_givenNullInput() throws Exception {
        // no one should do this, but...

        JSONArray actual = mHelper.dumpBansJson(
                new NotificationManagerService.DumpFilter(), null);
        assertThat(actual.length()).isEqualTo(0);
    }

    @Test
    public void testDumpString_postPermissionMigration() {
        // confirm that the string resulting from dumpImpl contains only importances from permission

        ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> appPermissions = new ArrayMap<>();
        appPermissions.put(new Pair(1, "first"), new Pair(true, false));    // not in local prefs
        appPermissions.put(new Pair(3, "third"), new Pair(false, true));   // not in local prefs
        appPermissions.put(new Pair(UID_O, PKG_O), new Pair(false, false)); // in local prefs

        // local package preferences
        mHelper.canShowBadge(PKG_O, UID_O);
        mHelper.canShowBadge(PKG_P, UID_P);

        // get dump output as a string so we can inspect the contents later
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mHelper.dump(pw, "", new NotificationManagerService.DumpFilter(), appPermissions);
        pw.flush();
        String actual = sw.toString();

        // expected (substring) output for each preference via permissions
        ArrayList<String> expected = new ArrayList<>();
        expected.add("first (1) importance=DEFAULT userSet=false");
        expected.add("third (3) importance=NONE userSet=true");
        expected.add(PKG_O + " (" + UID_O + ") importance=NONE userSet=false");
        expected.add(PKG_P + " (" + UID_P + ")");

        // make sure we don't have package preference info
        ArrayList<String> notExpected = new ArrayList<>();
        notExpected.add(PKG_O + " (" + UID_O + ") importance=HIGH");
        notExpected.add(PKG_P + " (" + UID_P + ") importance=");  // no importance for PKG_P

        for (String exp : expected) {
            assertTrue(actual.contains(exp));
        }

        for (String notExp : notExpected) {
            assertFalse(actual.contains(notExp));
        }
    }

    @Test
    public void testDumpString_givenNullInput() {
        // test that this doesn't choke on null input

        // local package preferences
        mHelper.canShowBadge(PKG_O, UID_O);
        mHelper.canShowBadge(PKG_P, UID_P);

        // get dump output
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mHelper.dump(pw, "", new NotificationManagerService.DumpFilter(), null);
        pw.flush();
        String actual = sw.toString();

        // nobody gets any importance
        assertFalse(actual.contains("importance="));
    }

    @Test
    public void testDumpProto_postPermissionMigration() throws Exception {
        // test that dumping to proto gets the importances from the right place

        // permissions -- these should take precedence
        ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> appPermissions = new ArrayMap<>();
        appPermissions.put(new Pair(1, "first"), new Pair(true, false));    // not in local prefs
        appPermissions.put(new Pair(3, "third"), new Pair(false, false));   // not in local prefs
        appPermissions.put(new Pair(UID_O, PKG_O), new Pair(false, false)); // in local prefs

        // local package preferences
        mHelper.canShowBadge(PKG_O, UID_O);
        mHelper.canShowBadge(PKG_P, UID_P);

        // expected output: all the packages, but only the ones provided via appPermissions
        // should have importance set (aka not PKG_P)
        // map format: (uid, package name) -> importance (int)
        ArrayMap<Pair<Integer, String>, Integer> expected = new ArrayMap<>();
        expected.put(new Pair(1, "first"), IMPORTANCE_DEFAULT);
        expected.put(new Pair(3, "third"), IMPORTANCE_NONE);
        expected.put(new Pair(UID_O, PKG_O), IMPORTANCE_NONE);

        // unfortunately, due to how nano protos work, there's no distinction between unset
        // fields and default-value fields, so we have no choice here but to check for a value of 0.
        // at least we can make sure the local importance for PKG_P in this test is not 0 (NONE).
        expected.put(new Pair(UID_P, PKG_P), 0);

        // get the proto output and inspect its contents
        ProtoOutputStream proto = new ProtoOutputStream();
        mHelper.dump(proto, new NotificationManagerService.DumpFilter(), appPermissions);

        RankingHelperProto actual = RankingHelperProto.parseFrom(proto.getBytes());
        assertThat(actual.records.length).isEqualTo(expected.size());
        for (int i = 0; i < actual.records.length; i++) {
            RankingHelperProto.RecordProto record = actual.records[i];
            Pair<Integer, String> pkgKey = new Pair(record.uid, record.package_);
            assertTrue(expected.containsKey(pkgKey));
            assertThat(record.importance).isEqualTo(expected.get(pkgKey));
        }
    }

    @Test
    public void testBadgingOverrideTrue() throws Exception {
        Secure.putIntForUser(getContext().getContentResolver(),
                Secure.NOTIFICATION_BADGING, 1,
                USER.getIdentifier());
        mHelper.updateBadgingEnabled(); // would be called by settings observer
        assertTrue(mHelper.badgingEnabled(USER));
    }

    @Test
    public void testBadgingOverrideFalse() throws Exception {
        Secure.putIntForUser(getContext().getContentResolver(),
                Secure.NOTIFICATION_BADGING, 0,
                USER.getIdentifier());
        mHelper.updateBadgingEnabled(); // would be called by settings observer
        assertFalse(mHelper.badgingEnabled(USER));
    }

    @Test
    public void testBadgingForUserAll() throws Exception {
        try {
            mHelper.badgingEnabled(UserHandle.ALL);
        } catch (Exception e) {
            fail("just don't throw");
        }
    }

    @Test
    public void testBadgingOverrideUserIsolation() throws Exception {
        Secure.putIntForUser(getContext().getContentResolver(),
                Secure.NOTIFICATION_BADGING, 0,
                USER.getIdentifier());
        Secure.putIntForUser(getContext().getContentResolver(),
                Secure.NOTIFICATION_BADGING, 1,
                USER2.getIdentifier());
        mHelper.updateBadgingEnabled(); // would be called by settings observer
        assertFalse(mHelper.badgingEnabled(USER));
        assertTrue(mHelper.badgingEnabled(USER2));
    }

    @Test
    public void testBubblesOverrideTrue() {
        Secure.putIntForUser(getContext().getContentResolver(),
                Secure.NOTIFICATION_BUBBLES, 1,
                USER.getIdentifier());
        mHelper.updateBubblesEnabled(); // would be called by settings observer
        assertTrue(mHelper.bubblesEnabled(USER));
    }

    @Test
    public void testBubblesOverrideFalse() {
        Secure.putIntForUser(getContext().getContentResolver(),
                Secure.NOTIFICATION_BUBBLES, 0,
                USER.getIdentifier());
        mHelper.updateBubblesEnabled(); // would be called by settings observer
        assertFalse(mHelper.bubblesEnabled(USER));
    }

    @Test
    public void testBubblesOverrideUserIsolation() throws Exception {
        Secure.putIntForUser(getContext().getContentResolver(),
                Secure.NOTIFICATION_BUBBLES, 0,
                USER.getIdentifier());
        Secure.putIntForUser(getContext().getContentResolver(),
                Secure.NOTIFICATION_BUBBLES, 1,
                USER2.getIdentifier());
        mHelper.updateBubblesEnabled(); // would be called by settings observer
        assertFalse(mHelper.bubblesEnabled(USER));
        assertTrue(mHelper.bubblesEnabled(USER2));
    }

    @Test
    public void testShowQSMediaOverrideTrue() {
        Global.putInt(getContext().getContentResolver(),
                Global.SHOW_MEDIA_ON_QUICK_SETTINGS, 1);
        mHelper.updateMediaNotificationFilteringEnabled(); // would be called by settings observer
        assertTrue(mHelper.isMediaNotificationFilteringEnabled());
    }

    @Test
    public void testShowQSMediaOverrideFalse() {
        Global.putInt(getContext().getContentResolver(),
                Global.SHOW_MEDIA_ON_QUICK_SETTINGS, 0);
        mHelper.updateMediaNotificationFilteringEnabled(); // would be called by settings observer
        assertFalse(mHelper.isMediaNotificationFilteringEnabled());
    }

    @Test
    public void testOnLocaleChanged_updatesDefaultChannels() throws Exception {
        String newLabel = "bananas!";
        final NotificationChannel defaultChannel = mHelper.getNotificationChannel(PKG_N_MR1,
                UID_N_MR1,
                NotificationChannel.DEFAULT_CHANNEL_ID, false);
        assertFalse(newLabel.equals(defaultChannel.getName()));

        Resources res = mock(Resources.class);
        when(mContext.getResources()).thenReturn(res);
        when(res.getString(com.android.internal.R.string.default_notification_channel_label))
                .thenReturn(newLabel);

        mHelper.onLocaleChanged(mContext, USER.getIdentifier());

        assertEquals(newLabel, mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1,
                NotificationChannel.DEFAULT_CHANNEL_ID, false).getName());
    }

    @Test
    public void testIsGroupBlocked_noGroup() throws Exception {
        assertFalse(mHelper.isGroupBlocked(PKG_N_MR1, UID_N_MR1, null));

        assertFalse(mHelper.isGroupBlocked(PKG_N_MR1, UID_N_MR1, "non existent group"));
    }

    @Test
    public void testIsGroupBlocked_notBlocked() throws Exception {
        NotificationChannelGroup group = new NotificationChannelGroup("id", "name");
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, group, true);

        assertFalse(mHelper.isGroupBlocked(PKG_N_MR1, UID_N_MR1, group.getId()));
    }

    @Test
    public void testIsGroupBlocked_blocked() throws Exception {
        NotificationChannelGroup group = new NotificationChannelGroup("id", "name");
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, group, true);
        group.setBlocked(true);
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, group, false);

        assertTrue(mHelper.isGroupBlocked(PKG_N_MR1, UID_N_MR1, group.getId()));
    }

    @Test
    public void testIsGroupBlocked_appCannotCreateAsBlocked() throws Exception {
        NotificationChannelGroup group = new NotificationChannelGroup("id", "name");
        group.setBlocked(true);
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, group, true);
        assertFalse(mHelper.isGroupBlocked(PKG_N_MR1, UID_N_MR1, group.getId()));

        NotificationChannelGroup group3 = group.clone();
        group3.setBlocked(false);
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, group3, true);
        assertFalse(mHelper.isGroupBlocked(PKG_N_MR1, UID_N_MR1, group.getId()));
    }

    @Test
    public void testIsGroup_appCannotResetBlock() throws Exception {
        NotificationChannelGroup group = new NotificationChannelGroup("id", "name");
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, group, true);
        NotificationChannelGroup group2 = group.clone();
        group2.setBlocked(true);
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, group2, false);
        assertTrue(mHelper.isGroupBlocked(PKG_N_MR1, UID_N_MR1, group.getId()));

        NotificationChannelGroup group3 = group.clone();
        group3.setBlocked(false);
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, group3, true);
        assertTrue(mHelper.isGroupBlocked(PKG_N_MR1, UID_N_MR1, group.getId()));
    }

    @Test
    public void testGetNotificationChannelGroupWithChannels() throws Exception {
        NotificationChannelGroup group = new NotificationChannelGroup("group", "");
        NotificationChannelGroup other = new NotificationChannelGroup("something else", "");
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, group, true);
        mHelper.createNotificationChannelGroup(PKG_N_MR1, UID_N_MR1, other, true);

        NotificationChannel a = new NotificationChannel("a", "a", IMPORTANCE_DEFAULT);
        a.setGroup(group.getId());
        NotificationChannel b = new NotificationChannel("b", "b", IMPORTANCE_DEFAULT);
        b.setGroup(other.getId());
        NotificationChannel c = new NotificationChannel("c", "c", IMPORTANCE_DEFAULT);
        c.setGroup(group.getId());
        NotificationChannel d = new NotificationChannel("d", "d", IMPORTANCE_DEFAULT);

        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, a, true, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, b, true, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, c, true, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, d, true, false);
        mHelper.deleteNotificationChannel(PKG_N_MR1, UID_N_MR1, c.getId());

        NotificationChannelGroup retrieved = mHelper.getNotificationChannelGroupWithChannels(
                PKG_N_MR1, UID_N_MR1, group.getId(), true);
        assertEquals(2, retrieved.getChannels().size());
        compareChannels(a, findChannel(retrieved.getChannels(), a.getId()));
        compareChannels(c, findChannel(retrieved.getChannels(), c.getId()));

        retrieved = mHelper.getNotificationChannelGroupWithChannels(
                PKG_N_MR1, UID_N_MR1, group.getId(), false);
        assertEquals(1, retrieved.getChannels().size());
        compareChannels(a, findChannel(retrieved.getChannels(), a.getId()));
    }

    @Test
    public void testAndroidPkgCannotBypassDnd_creation() {
        NotificationChannel test = new NotificationChannel("A", "a", IMPORTANCE_LOW);
        test.setBypassDnd(true);

        mHelper.createNotificationChannel(SYSTEM_PKG, SYSTEM_UID, test, true, false);

        assertFalse(mHelper.getNotificationChannel(SYSTEM_PKG, SYSTEM_UID, "A", false)
                .canBypassDnd());
    }

    @Test
    public void testDndPkgCanBypassDnd_creation() {
        NotificationChannel test = new NotificationChannel("A", "a", IMPORTANCE_LOW);
        test.setBypassDnd(true);

        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, test, true, true);

        assertTrue(mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, "A", false).canBypassDnd());
    }

    @Test
    public void testNormalPkgCannotBypassDnd_creation() {
        NotificationChannel test = new NotificationChannel("A", "a", IMPORTANCE_LOW);
        test.setBypassDnd(true);

        mHelper.createNotificationChannel(PKG_N_MR1, 1000, test, true, false);

        assertFalse(mHelper.getNotificationChannel(PKG_N_MR1, 1000, "A", false).canBypassDnd());
    }

    @Test
    public void testAndroidPkgCannotBypassDnd_update() throws Exception {
        NotificationChannel test = new NotificationChannel("A", "a", IMPORTANCE_LOW);
        mHelper.createNotificationChannel(SYSTEM_PKG, SYSTEM_UID, test, true, false);

        NotificationChannel update = new NotificationChannel("A", "a", IMPORTANCE_LOW);
        update.setBypassDnd(true);
        assertFalse(mHelper.createNotificationChannel(SYSTEM_PKG, SYSTEM_UID, update, true, false));

        assertFalse(mHelper.getNotificationChannel(SYSTEM_PKG, SYSTEM_UID, "A", false)
                .canBypassDnd());
    }

    @Test
    public void testDndPkgCanBypassDnd_update() throws Exception {
        NotificationChannel test = new NotificationChannel("A", "a", IMPORTANCE_LOW);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, test, true, true);

        NotificationChannel update = new NotificationChannel("A", "a", IMPORTANCE_LOW);
        update.setBypassDnd(true);
        assertTrue(mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, update, true, true));

        assertTrue(mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, "A", false).canBypassDnd());
    }

    @Test
    public void testNormalPkgCannotBypassDnd_update() {
        NotificationChannel test = new NotificationChannel("A", "a", IMPORTANCE_LOW);
        mHelper.createNotificationChannel(PKG_N_MR1, 1000, test, true, false);
        NotificationChannel update = new NotificationChannel("A", "a", IMPORTANCE_LOW);
        update.setBypassDnd(true);
        mHelper.createNotificationChannel(PKG_N_MR1, 1000, update, true, false);
        assertFalse(mHelper.getNotificationChannel(PKG_N_MR1, 1000, "A", false).canBypassDnd());
    }

    @Test
    public void testXml_statusBarIcons_default() throws Exception {
        String preQXml = "<ranking version=\"1\">\n"
                + "<package name=\"" + PKG_N_MR1 + "\" show_badge=\"true\">\n"
                + "<channel id=\"something\" name=\"name\" importance=\"2\" "
                + "show_badge=\"true\" />\n"
                + "<channel id=\"miscellaneous\" name=\"Uncategorized\" usage=\"5\" "
                + "content_type=\"4\" flags=\"0\" show_badge=\"true\" />\n"
                + "</package>\n"
                + "</ranking>\n";
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);
        loadByteArrayXml(preQXml.getBytes(), true, USER_SYSTEM);

        assertEquals(PreferencesHelper.DEFAULT_HIDE_SILENT_STATUS_BAR_ICONS,
                mHelper.shouldHideSilentStatusIcons());
    }

    @Test
    public void testXml_statusBarIcons() throws Exception {
        mHelper.setHideSilentStatusIcons(!PreferencesHelper.DEFAULT_HIDE_SILENT_STATUS_BAR_ICONS);

        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_O, UID_O, false, UserHandle.USER_ALL);
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);
        loadStreamXml(baos, false, UserHandle.USER_ALL);

        assertEquals(!PreferencesHelper.DEFAULT_HIDE_SILENT_STATUS_BAR_ICONS,
                mHelper.shouldHideSilentStatusIcons());
    }

    @Test
    public void testSetNotificationDelegate() {
        mHelper.setNotificationDelegate(PKG_O, UID_O, "other", 53);
        assertEquals("other", mHelper.getNotificationDelegate(PKG_O, UID_O));
    }

    @Test
    public void testRevokeNotificationDelegate() {
        mHelper.setNotificationDelegate(PKG_O, UID_O, "other", 53);
        mHelper.revokeNotificationDelegate(PKG_O, UID_O);

        assertNull(mHelper.getNotificationDelegate(PKG_O, UID_O));
    }

    @Test
    public void testRevokeNotificationDelegate_noDelegateExistsNoCrash() {
        mHelper.revokeNotificationDelegate(PKG_O, UID_O);

        assertNull(mHelper.getNotificationDelegate(PKG_O, UID_O));
    }

    @Test
    public void testToggleNotificationDelegate() {
        mHelper.setNotificationDelegate(PKG_O, UID_O, "other", 53);
        mHelper.toggleNotificationDelegate(PKG_O, UID_O, false);

        assertNull(mHelper.getNotificationDelegate(PKG_O, UID_O));

        mHelper.toggleNotificationDelegate(PKG_O, UID_O, true);
        assertEquals("other", mHelper.getNotificationDelegate(PKG_O, UID_O));
    }

    @Test
    public void testToggleNotificationDelegate_noDelegateExistsNoCrash() {
        mHelper.toggleNotificationDelegate(PKG_O, UID_O, false);
        assertNull(mHelper.getNotificationDelegate(PKG_O, UID_O));

        mHelper.toggleNotificationDelegate(PKG_O, UID_O, true);
        assertNull(mHelper.getNotificationDelegate(PKG_O, UID_O));
    }

    @Test
    public void testIsDelegateAllowed_noSource() {
        assertFalse(mHelper.isDelegateAllowed("does not exist", -1, "whatever", 0));
    }

    @Test
    public void testIsDelegateAllowed_noDelegate() {
        mHelper.canShowBadge(PKG_O, UID_O);

        assertFalse(mHelper.isDelegateAllowed(PKG_O, UID_O, "whatever", 0));
    }

    @Test
    public void testIsDelegateAllowed_delegateDisabledByApp() {
        mHelper.setNotificationDelegate(PKG_O, UID_O, "other", 53);
        mHelper.revokeNotificationDelegate(PKG_O, UID_O);

        assertFalse(mHelper.isDelegateAllowed(PKG_O, UID_O, "other", 53));
    }

    @Test
    public void testIsDelegateAllowed_wrongDelegate() {
        mHelper.setNotificationDelegate(PKG_O, UID_O, "other", 53);
        mHelper.revokeNotificationDelegate(PKG_O, UID_O);

        assertFalse(mHelper.isDelegateAllowed(PKG_O, UID_O, "banana", 27));
    }

    @Test
    public void testIsDelegateAllowed_delegateDisabledByUser() {
        mHelper.setNotificationDelegate(PKG_O, UID_O, "other", 53);
        mHelper.toggleNotificationDelegate(PKG_O, UID_O, false);

        assertFalse(mHelper.isDelegateAllowed(PKG_O, UID_O, "other", 53));
    }

    @Test
    public void testIsDelegateAllowed() {
        mHelper.setNotificationDelegate(PKG_O, UID_O, "other", 53);

        assertTrue(mHelper.isDelegateAllowed(PKG_O, UID_O, "other", 53));
    }

    @Test
    public void testDelegateXml_noDelegate() throws Exception {
        mHelper.canShowBadge(PKG_O, UID_O);

        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_O, UID_O, false, UserHandle.USER_ALL);
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);
        loadStreamXml(baos, false, UserHandle.USER_ALL);

        assertNull(mHelper.getNotificationDelegate(PKG_O, UID_O));
    }

    @Test
    public void testDelegateXml_delegate() throws Exception {
        mHelper.setNotificationDelegate(PKG_O, UID_O, "other", 53);

        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_O, UID_O, false, UserHandle.USER_ALL);
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);
        loadStreamXml(baos, false, UserHandle.USER_ALL);

        assertEquals("other", mHelper.getNotificationDelegate(PKG_O, UID_O));
    }

    @Test
    public void testDelegateXml_disabledDelegate() throws Exception {
        mHelper.setNotificationDelegate(PKG_O, UID_O, "other", 53);
        mHelper.revokeNotificationDelegate(PKG_O, UID_O);

        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_O, UID_O, false, UserHandle.USER_ALL);
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);
        loadStreamXml(baos, false, UserHandle.USER_ALL);

        assertNull(mHelper.getNotificationDelegate(PKG_O, UID_O));
    }

    @Test
    public void testDelegateXml_userDisabledDelegate() throws Exception {
        mHelper.setNotificationDelegate(PKG_O, UID_O, "other", 53);
        mHelper.toggleNotificationDelegate(PKG_O, UID_O, false);

        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_O, UID_O, false, UserHandle.USER_ALL);
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);
        loadStreamXml(baos, false, UserHandle.USER_ALL);

        // appears disabled
        assertNull(mHelper.getNotificationDelegate(PKG_O, UID_O));

        // but was loaded and can be toggled back on
        mHelper.toggleNotificationDelegate(PKG_O, UID_O, true);
        assertEquals("other", mHelper.getNotificationDelegate(PKG_O, UID_O));
    }

    @Test
    public void testDelegateXml_entirelyDisabledDelegate() throws Exception {
        mHelper.setNotificationDelegate(PKG_O, UID_O, "other", 53);
        mHelper.toggleNotificationDelegate(PKG_O, UID_O, false);
        mHelper.revokeNotificationDelegate(PKG_O, UID_O);

        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_O, UID_O, false, UserHandle.USER_ALL);
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);
        loadStreamXml(baos, false, UserHandle.USER_ALL);

        // appears disabled
        assertNull(mHelper.getNotificationDelegate(PKG_O, UID_O));

        mHelper.setNotificationDelegate(PKG_O, UID_O, "other", 53);
        assertNull(mHelper.getNotificationDelegate(PKG_O, UID_O));

        mHelper.toggleNotificationDelegate(PKG_O, UID_O, true);
        assertEquals("other", mHelper.getNotificationDelegate(PKG_O, UID_O));
    }

    @Test
    public void testBubblePreference_defaults() throws Exception {
        assertEquals(BUBBLE_PREFERENCE_NONE, mHelper.getBubblePreference(PKG_O, UID_O));

        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_O, UID_O, false, UserHandle.USER_ALL);
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);
        loadStreamXml(baos, false, UserHandle.USER_ALL);

        assertEquals(BUBBLE_PREFERENCE_NONE, mHelper.getBubblePreference(PKG_O, UID_O));
        assertEquals(0, mHelper.getAppLockedFields(PKG_O, UID_O));
    }

    @Test
    public void testBubblePreference_upgradeWithSAWPermission() throws Exception {
        when(mAppOpsManager.noteOpNoThrow(eq(OP_SYSTEM_ALERT_WINDOW), anyInt(),
                anyString(), eq(null), anyString())).thenReturn(MODE_ALLOWED);

        final String xml = "<ranking version=\"1\">\n"
                + "<package name=\"" + PKG_O + "\" uid=\"" + UID_O + "\">\n"
                + "<channel id=\"someId\" name=\"hi\""
                + " importance=\"3\"/>"
                + "</package>"
                + "</ranking>";
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(xml.getBytes())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, false, UserHandle.USER_ALL);

        assertEquals(BUBBLE_PREFERENCE_ALL, mHelper.getBubblePreference(PKG_O, UID_O));
        assertEquals(0, mHelper.getAppLockedFields(PKG_O, UID_O));
    }

    @Test
    public void testBubblePreference_upgradeWithSAWThenUserOverride() throws Exception {
        when(mAppOpsManager.noteOpNoThrow(eq(OP_SYSTEM_ALERT_WINDOW), anyInt(),
                anyString(), eq(null), anyString())).thenReturn(MODE_ALLOWED);

        final String xml = "<ranking version=\"1\">\n"
                + "<package name=\"" + PKG_O + "\" uid=\"" + UID_O + "\">\n"
                + "<channel id=\"someId\" name=\"hi\""
                + " importance=\"3\"/>"
                + "</package>"
                + "</ranking>";
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(xml.getBytes())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, false, UserHandle.USER_ALL);

        assertEquals(BUBBLE_PREFERENCE_ALL, mHelper.getBubblePreference(PKG_O, UID_O));
        assertEquals(0, mHelper.getAppLockedFields(PKG_O, UID_O));

        mHelper.setBubblesAllowed(PKG_O, UID_O, BUBBLE_PREFERENCE_SELECTED);
        assertEquals(BUBBLE_PREFERENCE_SELECTED, mHelper.getBubblePreference(PKG_O, UID_O));
        assertEquals(PreferencesHelper.LockableAppFields.USER_LOCKED_BUBBLE,
                mHelper.getAppLockedFields(PKG_O, UID_O));

        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_O, UID_O, false, UserHandle.USER_ALL);
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);
        loadStreamXml(baos, false, UserHandle.USER_ALL);

        assertEquals(BUBBLE_PREFERENCE_SELECTED, mHelper.getBubblePreference(PKG_O, UID_O));
        assertEquals(PreferencesHelper.LockableAppFields.USER_LOCKED_BUBBLE,
                mHelper.getAppLockedFields(PKG_O, UID_O));
    }

    @Test
    public void testBubblePrefence_noSAWCheckForUnknownUid() throws Exception {
        final String xml = "<ranking version=\"1\">\n"
                + "<package name=\"" + PKG_O + "\" uid=\"" + UNKNOWN_UID + "\">\n"
                + "<channel id=\"someId\" name=\"hi\""
                + " importance=\"3\"/>"
                + "</package>"
                + "</ranking>";
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(xml.getBytes())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, false, UserHandle.USER_ALL);

        assertEquals(DEFAULT_BUBBLE_PREFERENCE, mHelper.getBubblePreference(PKG_O, UID_O));
        assertEquals(0, mHelper.getAppLockedFields(PKG_O, UID_O));
        verify(mAppOpsManager, never()).noteOpNoThrow(eq(OP_SYSTEM_ALERT_WINDOW), anyInt(),
                anyString(), eq(null), anyString());
    }

    @Test
    public void testBubblePreference_xml() throws Exception {
        mHelper.setBubblesAllowed(PKG_O, UID_O, BUBBLE_PREFERENCE_NONE);
        assertEquals(mHelper.getBubblePreference(PKG_O, UID_O), BUBBLE_PREFERENCE_NONE);
        assertEquals(PreferencesHelper.LockableAppFields.USER_LOCKED_BUBBLE,
                mHelper.getAppLockedFields(PKG_O, UID_O));

        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_O, UID_O, false, UserHandle.USER_ALL);
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);
        loadStreamXml(baos, false, UserHandle.USER_ALL);

        assertEquals(mHelper.getBubblePreference(PKG_O, UID_O), BUBBLE_PREFERENCE_NONE);
        assertEquals(PreferencesHelper.LockableAppFields.USER_LOCKED_BUBBLE,
                mHelper.getAppLockedFields(PKG_O, UID_O));
    }

    @Test
    public void testUpdateNotificationChannel_fixedPermission() {
        List<UserInfo> users = ImmutableList.of(new UserInfo(UserHandle.USER_SYSTEM, "user0", 0));
        when(mPermissionHelper.isPermissionFixed(PKG_O, 0)).thenReturn(true);
        PackageInfo pm = new PackageInfo();
        pm.packageName = PKG_O;
        pm.applicationInfo = new ApplicationInfo();
        pm.applicationInfo.uid = UID_O;
        List<PackageInfo> packages = ImmutableList.of(pm);
        when(mPm.getInstalledPackagesAsUser(any(), anyInt())).thenReturn(packages);
        mHelper.updateFixedImportance(users);

        assertTrue(mHelper.isImportanceLocked(PKG_O, UID_O));

        NotificationChannel a = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        mHelper.createNotificationChannel(PKG_O, UID_O, a, true, false);

        NotificationChannel update = new NotificationChannel("a", "a", IMPORTANCE_NONE);
        update.setAllowBubbles(false);

        mHelper.updateNotificationChannel(PKG_O, UID_O, update, true);

        assertEquals(IMPORTANCE_HIGH,
                mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false).getImportance());
        assertEquals(false,
                mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false).canBubble());
    }

    @Test
    public void testUpdateNotificationChannel_defaultApp() {
        ArraySet<Pair<String, Integer>> toAdd = new ArraySet<>();
        toAdd.add(new Pair(PKG_O, UID_O));
        mHelper.updateDefaultApps(0, null, toAdd);
        NotificationChannel a = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        mHelper.createNotificationChannel(PKG_O, UID_O, a, true, false);

        NotificationChannel update = new NotificationChannel("a", "a", IMPORTANCE_NONE);
        update.setAllowBubbles(false);

        mHelper.updateNotificationChannel(PKG_O, UID_O, update, true);

        assertEquals(IMPORTANCE_HIGH,
                mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false).getImportance());
        assertEquals(false,
                mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false).canBubble());
    }

    @Test
    public void testUpdateNotificationChannel_fixedPermission_butUserPreviouslyBlockedIt() {
        when(mPermissionHelper.isPermissionFixed(PKG_O, 0)).thenReturn(true);

        NotificationChannel a = new NotificationChannel("a", "a", IMPORTANCE_NONE);
        mHelper.createNotificationChannel(PKG_O, UID_O, a, false, false);

        NotificationChannel update = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        update.setAllowBubbles(false);

        mHelper.updateNotificationChannel(PKG_O, UID_O, update, true);

        assertEquals(IMPORTANCE_HIGH,
                mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false).getImportance());
        assertEquals(false,
                mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false).canBubble());
    }

    @Test
    public void testUpdateNotificationChannel_fixedPermission_butAppAllowsIt() {
        when(mPermissionHelper.isPermissionFixed(PKG_O, 0)).thenReturn(true);

        NotificationChannel a = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        a.setBlockable(true);
        mHelper.createNotificationChannel(PKG_O, UID_O, a, true, false);

        NotificationChannel update = new NotificationChannel("a", "a", IMPORTANCE_NONE);
        update.setAllowBubbles(false);

        mHelper.updateNotificationChannel(PKG_O, UID_O, update, true);

        assertEquals(IMPORTANCE_NONE,
                mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false).getImportance());
        assertEquals(false,
                mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false).canBubble());
    }

    @Test
    public void testUpdateNotificationChannel_notFixedPermission() {
        when(mPermissionHelper.isPermissionFixed(PKG_O, 0)).thenReturn(false);

        NotificationChannel a = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        mHelper.createNotificationChannel(PKG_O, UID_O, a, true, false);

        NotificationChannel update = new NotificationChannel("a", "a", IMPORTANCE_NONE);
        update.setAllowBubbles(false);

        mHelper.updateNotificationChannel(PKG_O, UID_O, update, true);

        assertEquals(IMPORTANCE_NONE,
                mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false).getImportance());
        assertEquals(false,
                mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false).canBubble());
    }

    @Test
    public void testUpdateFixedImportance_multiUser() {
        NotificationChannel a = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        NotificationChannel b = new NotificationChannel("b", "b", IMPORTANCE_LOW);
        NotificationChannel c = new NotificationChannel("c", "c", IMPORTANCE_DEFAULT);
        // different uids, same package
        mHelper.createNotificationChannel(PKG_O, UID_O, a, true, false);
        mHelper.createNotificationChannel(PKG_O, UID_O, b, false, false);
        mHelper.createNotificationChannel(PKG_O, UserHandle.PER_USER_RANGE + 1, c, true, true);

        UserInfo user = new UserInfo();
        user.id = 0;
        List<UserInfo> users = ImmutableList.of(user);
        when(mPermissionHelper.isPermissionFixed(PKG_O, 0)).thenReturn(true);
        PackageInfo pm = new PackageInfo();
        pm.packageName = PKG_O;
        pm.applicationInfo = new ApplicationInfo();
        pm.applicationInfo.uid = UID_O;
        List<PackageInfo> packages = ImmutableList.of(pm);
        when(mPm.getInstalledPackagesAsUser(any(), eq(0))).thenReturn(packages);
        mHelper.updateFixedImportance(users);

        assertTrue(mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());
        assertTrue(mHelper.getNotificationChannel(PKG_O, UID_O, b.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());
        assertFalse(mHelper.getNotificationChannel(
                        PKG_O, UserHandle.PER_USER_RANGE + 1, c.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());
    }

    @Test
    public void testUpdateFixedImportance_channelDoesNotExistYet() {
        UserInfo user = new UserInfo();
        user.id = 0;
        List<UserInfo> users = ImmutableList.of(user);
        when(mPermissionHelper.isPermissionFixed(PKG_O, 0)).thenReturn(true);
        PackageInfo pm = new PackageInfo();
        pm.packageName = PKG_O;
        pm.applicationInfo = new ApplicationInfo();
        pm.applicationInfo.uid = UID_O;
        List<PackageInfo> packages = ImmutableList.of(pm);
        when(mPm.getInstalledPackagesAsUser(any(), eq(0))).thenReturn(packages);
        mHelper.updateFixedImportance(users);

        NotificationChannel a = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        mHelper.createNotificationChannel(PKG_O, UID_O, a, true, false);

        assertTrue(mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());
    }

    @Test
    public void testUpdateDefaultApps_add_multiUser() {
        NotificationChannel a = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        NotificationChannel b = new NotificationChannel("b", "b", IMPORTANCE_LOW);
        NotificationChannel c = new NotificationChannel("c", "c", IMPORTANCE_DEFAULT);
        // different uids, same package
        mHelper.createNotificationChannel(PKG_O, UID_O, a, true, false);
        mHelper.createNotificationChannel(PKG_O, UID_O, b, false, false);
        mHelper.createNotificationChannel(PKG_O, UserHandle.PER_USER_RANGE + 1, c, true, true);

        ArraySet<Pair<String, Integer>> toAdd = new ArraySet<>();
        toAdd.add(new Pair(PKG_O, UID_O));
        mHelper.updateDefaultApps(UserHandle.getUserId(UID_O), null, toAdd);

        assertTrue(mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());
        assertTrue(mHelper.getNotificationChannel(PKG_O, UID_O, b.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());
        assertFalse(mHelper.getNotificationChannel(
                PKG_O, UserHandle.PER_USER_RANGE + 1, c.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());
    }

    @Test
    public void testUpdateDefaultApps_add_onlyGivenPkg() {
        NotificationChannel a = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        NotificationChannel b = new NotificationChannel("b", "b", IMPORTANCE_LOW);
        mHelper.createNotificationChannel(PKG_O, UID_O, a, true, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, b, false, false);

        ArraySet<Pair<String, Integer>> toAdd = new ArraySet<>();
        toAdd.add(new Pair(PKG_O, UID_O));
        mHelper.updateDefaultApps(UserHandle.getUserId(UID_O), null, toAdd);

        assertTrue(mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());
        assertFalse(mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, b.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());
    }

    @Test
    public void testUpdateDefaultApps_remove() {
        NotificationChannel a = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        NotificationChannel b = new NotificationChannel("b", "b", IMPORTANCE_LOW);
        // different uids, same package
        mHelper.createNotificationChannel(PKG_O, UID_O, a, true, false);
        mHelper.createNotificationChannel(PKG_O, UID_O, b, false, false);

        ArraySet<Pair<String, Integer>> toAdd = new ArraySet<>();
        toAdd.add(new Pair(PKG_O, UID_O));
        mHelper.updateDefaultApps(UserHandle.getUserId(UID_O), null, toAdd);

        assertTrue(mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());
        assertTrue(mHelper.getNotificationChannel(PKG_O, UID_O, b.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());

        ArraySet<String> toRemove = new ArraySet<>();
        toRemove.add(PKG_O);
        mHelper.updateDefaultApps(UserHandle.getUserId(UID_O), toRemove, null);

        assertFalse(mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());
        assertFalse(mHelper.getNotificationChannel(PKG_O, UID_O, b.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());
    }

    @Test
    public void testUpdateDefaultApps_addAndRemove() {
        NotificationChannel a = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        NotificationChannel b = new NotificationChannel("b", "b", IMPORTANCE_LOW);
        mHelper.createNotificationChannel(PKG_O, UID_O, a, true, false);
        mHelper.createNotificationChannel(PKG_N_MR1, UID_N_MR1, b, false, false);

        ArraySet<Pair<String, Integer>> toAdd = new ArraySet<>();
        toAdd.add(new Pair(PKG_O, UID_O));
        mHelper.updateDefaultApps(UserHandle.getUserId(UID_O), null, toAdd);


        assertTrue(mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());
        assertFalse(mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, b.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());

        // now the default is PKG_N_MR1
        ArraySet<String> toRemove = new ArraySet<>();
        toRemove.add(PKG_O);
        toAdd = new ArraySet<>();
        toAdd.add(new Pair(PKG_N_MR1, UID_N_MR1));
        mHelper.updateDefaultApps(USER.getIdentifier(), toRemove, toAdd);

        assertFalse(mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());
        assertTrue(mHelper.getNotificationChannel(PKG_N_MR1, UID_N_MR1, b.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());
    }

    @Test
    public void testUpdateDefaultApps_appDoesNotExist_noCrash() {
        ArraySet<Pair<String, Integer>> toAdd = new ArraySet<>();
        toAdd.add(new Pair(PKG_O, UID_O));
        ArraySet<String> toRemove = new ArraySet<>();
        toRemove.add(PKG_N_MR1);
        mHelper.updateDefaultApps(UserHandle.getUserId(UID_O), toRemove, toAdd);
    }

    @Test
    public void testUpdateDefaultApps_channelDoesNotExistYet() {
        NotificationChannel a = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        NotificationChannel b = new NotificationChannel("b", "b", IMPORTANCE_LOW);
        mHelper.createNotificationChannel(PKG_O, UID_O, a, true, false);

        ArraySet<Pair<String, Integer>> toAdd = new ArraySet<>();
        toAdd.add(new Pair(PKG_O, UID_O));
        mHelper.updateDefaultApps(UserHandle.getUserId(UID_O), null, toAdd);

        assertTrue(mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());

        mHelper.createNotificationChannel(PKG_O, UID_O, b, true, false);
        assertTrue(mHelper.getNotificationChannel(PKG_O, UID_O, b.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());
    }

    @Test
    public void testUpdateNotificationChannel_defaultAppLockedImportance() {
        NotificationChannel a = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        mHelper.createNotificationChannel(PKG_O, UID_O, a, true, false);
        ArraySet<Pair<String, Integer>> toAdd = new ArraySet<>();
        toAdd.add(new Pair(PKG_O, UID_O));
        mHelper.updateDefaultApps(UserHandle.getUserId(UID_O), null, toAdd);

        NotificationChannel update = new NotificationChannel("a", "a", IMPORTANCE_NONE);
        update.setAllowBubbles(false);

        mHelper.updateNotificationChannel(PKG_O, UID_O, update, true);
        assertEquals(IMPORTANCE_HIGH,
                mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false).getImportance());
        assertEquals(false,
                mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false).canBubble());

        mHelper.updateNotificationChannel(PKG_O, UID_O, update, false);
        assertEquals(IMPORTANCE_HIGH,
                mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false).getImportance());

        NotificationChannel updateImportanceLow = new NotificationChannel("a", "a",
                IMPORTANCE_LOW);
        mHelper.updateNotificationChannel(PKG_O, UID_O, updateImportanceLow, true);
        assertEquals(IMPORTANCE_LOW,
                mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false).getImportance());
    }

    @Test
    public void testDefaultApp_appHasNoSettingsYet() {
        ArraySet<Pair<String, Integer>> toAdd = new ArraySet<>();
        toAdd.add(new Pair(PKG_O, UID_O));
        mHelper.updateDefaultApps(UserHandle.getUserId(UID_O), null, toAdd);

        NotificationChannel a = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        mHelper.createNotificationChannel(PKG_O, UID_O, a, true, false);

        assertTrue(a.isImportanceLockedByCriticalDeviceFunction());
    }

    @Test
    public void testUpdateFixedImportance_thenDefaultAppsRemoves() {
        UserInfo user = new UserInfo();
        user.id = 0;
        List<UserInfo> users = ImmutableList.of(user);
        when(mPermissionHelper.isPermissionFixed(PKG_O, 0)).thenReturn(true);
        PackageInfo pm = new PackageInfo();
        pm.packageName = PKG_O;
        pm.applicationInfo = new ApplicationInfo();
        pm.applicationInfo.uid = UID_O;
        List<PackageInfo> packages = ImmutableList.of(pm);
        when(mPm.getInstalledPackagesAsUser(any(), eq(0))).thenReturn(packages);
        mHelper.updateFixedImportance(users);

        ArraySet<String> toRemove = new ArraySet<>();
        toRemove.add(PKG_O);
        mHelper.updateDefaultApps(0, toRemove, null);

        assertTrue(mHelper.isImportanceLocked(PKG_O, UID_O));

        NotificationChannel a = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        mHelper.createNotificationChannel(PKG_O, UID_O, a, true, false);

        // Still locked by permission if not role
        assertTrue(mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());
    }

    @Test
    public void testUpdateDefaultApps_thenNotFixedPermission() {
        ArraySet<Pair<String, Integer>> toAdd = new ArraySet<>();
        toAdd.add(new Pair(PKG_O, UID_O));
        mHelper.updateDefaultApps(0, null, toAdd);

        UserInfo user = new UserInfo();
        user.id = 0;
        List<UserInfo> users = ImmutableList.of(user);
        when(mPermissionHelper.isPermissionFixed(PKG_O, 0)).thenReturn(false);
        PackageInfo pm = new PackageInfo();
        pm.packageName = PKG_O;
        pm.applicationInfo = new ApplicationInfo();
        pm.applicationInfo.uid = UID_O;
        List<PackageInfo> packages = ImmutableList.of(pm);
        when(mPm.getInstalledPackagesAsUser(any(), eq(0))).thenReturn(packages);
        mHelper.updateFixedImportance(users);

        assertTrue(mHelper.isImportanceLocked(PKG_O, UID_O));

        NotificationChannel a = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        mHelper.createNotificationChannel(PKG_O, UID_O, a, true, false);

        // Still locked by role if not permission
        assertTrue(mHelper.getNotificationChannel(PKG_O, UID_O, a.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());
    }

    @Test
    public void testChannelXml_backupDefaultApp() throws Exception {
        NotificationChannel channel1 =
                new NotificationChannel("id1", "name1", NotificationManager.IMPORTANCE_HIGH);

        mHelper.createNotificationChannel(PKG_O, UID_O, channel1, true, false);

        // clear data
        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_O, UID_O, true,
                USER_SYSTEM, channel1.getId(), NotificationChannel.DEFAULT_CHANNEL_ID);
        mHelper.onPackagesChanged(true, UserHandle.myUserId(), new String[]{PKG_O}, new int[]{
                UID_O});

        ArraySet<Pair<String, Integer>> toAdd = new ArraySet<>();
        toAdd.add(new Pair(PKG_O, UID_O));
        mHelper.updateDefaultApps(UserHandle.getUserId(UID_O), null, toAdd);

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, true, USER_SYSTEM);

        assertTrue(mHelper.getNotificationChannel(PKG_O, UID_O, channel1.getId(), false)
                .isImportanceLockedByCriticalDeviceFunction());
    }

    @Test
    public void testSetBubblesAllowed_none() {
        // Change it to non-default first
        mHelper.setBubblesAllowed(PKG_O, UID_O, BUBBLE_PREFERENCE_ALL);
        assertEquals(mHelper.getBubblePreference(PKG_O, UID_O), BUBBLE_PREFERENCE_ALL);
        verify(mHandler, times(1)).requestSort();
        reset(mHandler);
        // Now test
        mHelper.setBubblesAllowed(PKG_O, UID_O, BUBBLE_PREFERENCE_NONE);
        assertEquals(mHelper.getBubblePreference(PKG_O, UID_O), BUBBLE_PREFERENCE_NONE);
        verify(mHandler, times(1)).requestSort();
    }

    @Test
    public void testSetBubblesAllowed_all() {
        mHelper.setBubblesAllowed(PKG_O, UID_O, BUBBLE_PREFERENCE_ALL);
        assertEquals(mHelper.getBubblePreference(PKG_O, UID_O), BUBBLE_PREFERENCE_ALL);
        verify(mHandler, times(1)).requestSort();
    }

    @Test
    public void testSetBubblesAllowed_selected() {
        mHelper.setBubblesAllowed(PKG_O, UID_O, BUBBLE_PREFERENCE_SELECTED);
        assertEquals(mHelper.getBubblePreference(PKG_O, UID_O), BUBBLE_PREFERENCE_SELECTED);
        verify(mHandler, times(1)).requestSort();
    }

    @Test
    public void testTooManyChannels() {
        for (int i = 0; i < NOTIFICATION_CHANNEL_COUNT_LIMIT; i++) {
            NotificationChannel channel = new NotificationChannel(String.valueOf(i),
                    String.valueOf(i), NotificationManager.IMPORTANCE_HIGH);
            mHelper.createNotificationChannel(PKG_O, UID_O, channel, true, true);
        }
        try {
            NotificationChannel channel = new NotificationChannel(
                    String.valueOf(NOTIFICATION_CHANNEL_COUNT_LIMIT),
                    String.valueOf(NOTIFICATION_CHANNEL_COUNT_LIMIT),
                    NotificationManager.IMPORTANCE_HIGH);
            mHelper.createNotificationChannel(PKG_O, UID_O, channel, true, true);
            fail("Allowed to create too many notification channels");
        } catch (IllegalStateException e) {
            // great
        }
    }

    @Test
    public void testTooManyChannels_xml() throws Exception {
        String extraChannel = "EXTRA";
        String extraChannel1 = "EXTRA1";

        // create first... many... directly so we don't need a big xml blob in this test
        for (int i = 0; i < NOTIFICATION_CHANNEL_COUNT_LIMIT; i++) {
            NotificationChannel channel = new NotificationChannel(String.valueOf(i),
                    String.valueOf(i), NotificationManager.IMPORTANCE_HIGH);
            mHelper.createNotificationChannel(PKG_O, UID_O, channel, true, true);
        }

        final String xml = "<ranking version=\"1\">\n"
                + "<package name=\"" + PKG_O + "\" uid=\"" + UID_O + "\" >\n"
                + "<channel id=\"" + extraChannel + "\" name=\"hi\" importance=\"3\"/>"
                + "<channel id=\"" + extraChannel1 + "\" name=\"hi\" importance=\"3\"/>"
                + "</package>"
                + "</ranking>";
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(xml.getBytes())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, false, UserHandle.USER_ALL);

        assertNull(mHelper.getNotificationChannel(PKG_O, UID_O, extraChannel, true));
        assertNull(mHelper.getNotificationChannel(PKG_O, UID_O, extraChannel1, true));
    }

    @Test
    public void testTooManyGroups() {
        for (int i = 0; i < NOTIFICATION_CHANNEL_GROUP_COUNT_LIMIT; i++) {
            NotificationChannelGroup group = new NotificationChannelGroup(String.valueOf(i),
                    String.valueOf(i));
            mHelper.createNotificationChannelGroup(PKG_O, UID_O, group, true);
        }
        try {
            NotificationChannelGroup group = new NotificationChannelGroup(
                    String.valueOf(NOTIFICATION_CHANNEL_GROUP_COUNT_LIMIT),
                    String.valueOf(NOTIFICATION_CHANNEL_GROUP_COUNT_LIMIT));
            mHelper.createNotificationChannelGroup(PKG_O, UID_O, group, true);
            fail("Allowed to create too many notification channel groups");
        } catch (IllegalStateException e) {
            // great
        }
    }

    @Test
    public void testTooManyGroups_xml() throws Exception {
        String extraGroup = "EXTRA";
        String extraGroup1 = "EXTRA1";

        // create first... many... directly so we don't need a big xml blob in this test
        for (int i = 0; i < NOTIFICATION_CHANNEL_GROUP_COUNT_LIMIT; i++) {
            NotificationChannelGroup group = new NotificationChannelGroup(String.valueOf(i),
                    String.valueOf(i));
            mHelper.createNotificationChannelGroup(PKG_O, UID_O, group, true);
        }

        final String xml = "<ranking version=\"1\">\n"
                + "<package name=\"" + PKG_O + "\" uid=\"" + UID_O + "\" >\n"
                + "<channelGroup id=\"" + extraGroup + "\" name=\"hi\"/>"
                + "<channelGroup id=\"" + extraGroup1 + "\" name=\"hi2\"/>"
                + "</package>"
                + "</ranking>";
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(xml.getBytes())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, false, UserHandle.USER_ALL);

        assertNull(mHelper.getNotificationChannelGroup(extraGroup, PKG_O, UID_O));
        assertNull(mHelper.getNotificationChannelGroup(extraGroup1, PKG_O, UID_O));
    }

    @Test
    public void testRestoreMultiUser() throws Exception {
        String pkg = "restore_pkg";
        String channelId = "channelId";
        int user0Importance = 3;
        int user10Importance = 4;
        when(mPm.getPackageUidAsUser(eq(pkg), anyInt())).thenReturn(UserHandle.USER_NULL);

        // both users have the same package, but different notification settings
        final String xmlUser0 = "<ranking version=\"1\">\n"
                + "<package name=\"" + pkg + "\" >\n"
                + "<channel id=\"" + channelId + "\" name=\"hi\""
                + " importance=\"" + user0Importance + "\"/>"
                + "</package>"
                + "</ranking>";
        final String xmlUser10 = "<ranking version=\"1\">\n"
                + "<package name=\"" + pkg + "\" >\n"
                + "<channel id=\"" + channelId + "\" name=\"hi\""
                + " importance=\"" + user10Importance + "\"/>"
                + "</package>"
                + "</ranking>";

        // trigger a restore for both users
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(xmlUser0.getBytes())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, true, 0);
        parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(xmlUser10.getBytes())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, true, 10);

        // "install" package on both users
        String[] pkgList = new String[] {pkg};
        int[] uidList0 = new int[] {UserHandle.PER_USER_RANGE};
        int[] uidList10 = new int[] {UserHandle.PER_USER_RANGE + 1};
        when(mPm.getPackageUidAsUser(pkg, 0)).thenReturn(uidList0[0]);
        when(mPm.getPackageUidAsUser(pkg, 10)).thenReturn(uidList10[0]);
        ApplicationInfo info = new ApplicationInfo();
        info.targetSdkVersion = Build.VERSION_CODES.Q;
        when(mPm.getApplicationInfoAsUser(eq(pkg), anyInt(), anyInt())).thenReturn(info);

        mHelper.onPackagesChanged(false, 0, pkgList, uidList0);
        mHelper.onPackagesChanged(false, 10, pkgList, uidList10);

        assertEquals(user0Importance,
                mHelper.getNotificationChannel(pkg, uidList0[0], channelId, false).getImportance());
        assertEquals(user10Importance, mHelper.getNotificationChannel(
                pkg, uidList10[0], channelId, false).getImportance());
    }

    @Test
    public void testGetConversationNotificationChannel() {
        String conversationId = "friend";

        NotificationChannel parent =
                new NotificationChannel("parent", "messages", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_O, UID_O, parent, true, false);

        NotificationChannel friend = new NotificationChannel(String.format(
                CONVERSATION_CHANNEL_ID_FORMAT, parent.getId(), conversationId),
                "messages", IMPORTANCE_DEFAULT);
        friend.setConversationId(parent.getId(), conversationId);
        mHelper.createNotificationChannel(PKG_O, UID_O, friend, true, false);

        compareChannelsParentChild(parent, mHelper.getConversationNotificationChannel(
                PKG_O, UID_O, parent.getId(), conversationId, false, false), conversationId);
    }

    @Test
    public void testGetNotificationChannel_conversationProvidedByNotCustomizedYet() {
        String conversationId = "friend";

        NotificationChannel parent =
                new NotificationChannel("parent", "messages", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_O, UID_O, parent, true, false);

        compareChannels(parent, mHelper.getConversationNotificationChannel(
                PKG_O, UID_O, parent.getId(), conversationId, true, false));
    }

    @Test
    public void testConversationNotificationChannelsRequireParents() {
        String parentId = "does not exist";
        String conversationId = "friend";

        NotificationChannel friend = new NotificationChannel(String.format(
                CONVERSATION_CHANNEL_ID_FORMAT, parentId, conversationId),
                "messages", IMPORTANCE_DEFAULT);
        friend.setConversationId(parentId, conversationId);

        try {
            mHelper.createNotificationChannel(PKG_O, UID_O, friend, true, false);
            fail("allowed creation of conversation channel without a parent");
        } catch (IllegalArgumentException e) {
            // good
        }
    }

    @Test
    public void testPlaceholderConversationId_shortcutRequired() throws Exception {
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);

        final String xml = "<ranking version=\"1\">\n"
                + "<package name=\"" + PKG_O + "\" uid=\"" + UID_O + "\" >\n"
                + "<channel id=\"id\" name=\"hi\" importance=\"3\" conv_id=\"foo:placeholder_id\"/>"
                + "</package>"
                + "</ranking>";
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(xml.getBytes())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, false, UserHandle.USER_ALL);

        assertNull(mHelper.getNotificationChannel(PKG_O, UID_O, "id", true));
    }

    @Test
    public void testNormalConversationId_shortcutRequired() throws Exception {
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);

        final String xml = "<ranking version=\"1\">\n"
                + "<package name=\"" + PKG_O + "\" uid=\"" + UID_O + "\" >\n"
                + "<channel id=\"id\" name=\"hi\" importance=\"3\" conv_id=\"other\"/>"
                + "</package>"
                + "</ranking>";
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(xml.getBytes())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, false, UserHandle.USER_ALL);

        assertNotNull(mHelper.getNotificationChannel(PKG_O, UID_O, "id", true));
    }

    @Test
    public void testNoConversationId_shortcutRequired() throws Exception {
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);

        final String xml = "<ranking version=\"1\">\n"
                + "<package name=\"" + PKG_O + "\" uid=\"" + UID_O + "\" >\n"
                + "<channel id=\"id\" name=\"hi\" importance=\"3\"/>"
                + "</package>"
                + "</ranking>";
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(xml.getBytes())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, false, UserHandle.USER_ALL);

        assertNotNull(mHelper.getNotificationChannel(PKG_O, UID_O, "id", true));
    }

    @Test
    public void testDeleted_noTime() throws Exception {
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);

        final String xml = "<ranking version=\"1\">\n"
                + "<package name=\"" + PKG_O + "\" uid=\"" + UID_O + "\" >\n"
                + "<channel id=\"id\" name=\"hi\" importance=\"3\" deleted=\"true\"/>"
                + "</package>"
                + "</ranking>";
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(xml.getBytes())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, false, UserHandle.USER_ALL);

        assertNull(mHelper.getNotificationChannel(PKG_O, UID_O, "id", true));
    }

    @Test
    public void testDeleted_twice() throws Exception {
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);

        mHelper.createNotificationChannel(
                PKG_P, UID_P, new NotificationChannel("id", "id", 2), true, false);
        assertTrue(mHelper.deleteNotificationChannel(PKG_P, UID_P, "id"));
        assertFalse(mHelper.deleteNotificationChannel(PKG_P, UID_P, "id"));
    }

    @Test
    public void testDeleted_recentTime() throws Exception {
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);

        mHelper.createNotificationChannel(
                PKG_P, UID_P, new NotificationChannel("id", "id", 2), true, false);
        mHelper.deleteNotificationChannel(PKG_P, UID_P, "id");
        NotificationChannel nc1 = mHelper.getNotificationChannel(PKG_P, UID_P, "id", true);
        assertTrue(DateUtils.isToday(nc1.getDeletedTimeMs()));
        assertTrue(nc1.isDeleted());

        ByteArrayOutputStream baos = writeXmlAndPurge(PKG_P, UID_P, false,
                USER_SYSTEM, "id", NotificationChannel.DEFAULT_CHANNEL_ID);

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())),
                null);
        parser.nextTag();
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);
        mHelper.readXml(parser, true, USER_SYSTEM);

        NotificationChannel nc = mHelper.getNotificationChannel(PKG_P, UID_P, "id", true);
        assertTrue(DateUtils.isToday(nc.getDeletedTimeMs()));
        assertTrue(nc.isDeleted());
    }

    @Test
    public void testUnDelete_time() throws Exception {
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);

        mHelper.createNotificationChannel(
                PKG_P, UID_P, new NotificationChannel("id", "id", 2), true, false);
        mHelper.deleteNotificationChannel(PKG_P, UID_P, "id");
        NotificationChannel nc1 = mHelper.getNotificationChannel(PKG_P, UID_P, "id", true);
        assertTrue(DateUtils.isToday(nc1.getDeletedTimeMs()));
        assertTrue(nc1.isDeleted());

        mHelper.createNotificationChannel(
                PKG_P, UID_P, new NotificationChannel("id", "id", 2), true, false);
        nc1 = mHelper.getNotificationChannel(PKG_P, UID_P, "id", true);
        assertEquals(-1, nc1.getDeletedTimeMs());
        assertFalse(nc1.isDeleted());
    }

    @Test
    public void testDeleted_longTime() throws Exception {
        mHelper = new PreferencesHelper(getContext(), mPm, mHandler, mMockZenModeHelper,
                mPermissionHelper, mLogger,
                mAppOpsManager, mStatsEventBuilderFactory, false);

        long time = System.currentTimeMillis() - (DateUtils.DAY_IN_MILLIS * 30);

        final String xml = "<ranking version=\"1\">\n"
                + "<package name=\"" + PKG_O + "\" uid=\"" + UID_O + "\" >\n"
                + "<channel id=\"id\" name=\"hi\" importance=\"3\" deleted=\"true\" del_time=\""
                + time + "\"/>"
                + "</package>"
                + "</ranking>";
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(xml.getBytes())),
                null);
        parser.nextTag();
        mHelper.readXml(parser, false, UserHandle.USER_ALL);

        NotificationChannel nc = mHelper.getNotificationChannel(PKG_O, UID_O, "id", true);
        assertNull(nc);
    }

    @Test
    public void testGetConversations_all() {
        String convoId = "convo";
        NotificationChannel messages =
                new NotificationChannel("messages", "Messages", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_O, UID_O, messages, true, false);
        NotificationChannel calls =
                new NotificationChannel("calls", "Calls", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_O, UID_O, calls, true, false);
        NotificationChannel p =
                new NotificationChannel("p calls", "Calls", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_P, UID_P, p, true, false);

        NotificationChannel channel =
                new NotificationChannel("A person msgs", "messages from A", IMPORTANCE_DEFAULT);
        channel.setConversationId(messages.getId(), convoId);
        mHelper.createNotificationChannel(PKG_O, UID_O, channel, true, false);

        NotificationChannel diffConvo =
                new NotificationChannel("B person msgs", "messages from B", IMPORTANCE_DEFAULT);
        diffConvo.setConversationId(p.getId(), "different convo");
        mHelper.createNotificationChannel(PKG_P, UID_P, diffConvo, true, false);

        NotificationChannel channel2 =
                new NotificationChannel("A person calls", "calls from A", IMPORTANCE_DEFAULT);
        channel2.setConversationId(calls.getId(), convoId);
        channel2.setImportantConversation(true);
        mHelper.createNotificationChannel(PKG_O, UID_O, channel2, false, false);

        List<ConversationChannelWrapper> convos =
                mHelper.getConversations(IntArray.wrap(new int[] {0}), false);

        assertEquals(3, convos.size());
        assertTrue(conversationWrapperContainsChannel(convos, channel));
        assertTrue(conversationWrapperContainsChannel(convos, diffConvo));
        assertTrue(conversationWrapperContainsChannel(convos, channel2));
    }

    @Test
    public void testGetConversations_multiUser() {
        String convoId = "convo";
        NotificationChannel messages =
                new NotificationChannel("messages", "Messages", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_O, UID_O, messages, true, false);

        NotificationChannel messagesUser10 =
                new NotificationChannel("messages", "Messages", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(
                PKG_O, UID_O + UserHandle.PER_USER_RANGE, messagesUser10, true, false);

        NotificationChannel messagesFromB =
                new NotificationChannel("B person msgs", "messages from B", IMPORTANCE_DEFAULT);
        messagesFromB.setConversationId(messages.getId(), "different convo");
        mHelper.createNotificationChannel(PKG_O, UID_O, messagesFromB, true, false);

        NotificationChannel messagesFromBUser10 =
                new NotificationChannel("B person msgs", "messages from B", IMPORTANCE_DEFAULT);
        messagesFromBUser10.setConversationId(messagesUser10.getId(), "different convo");
        mHelper.createNotificationChannel(
                PKG_O, UID_O + UserHandle.PER_USER_RANGE, messagesFromBUser10, true, false);


        List<ConversationChannelWrapper> convos =
                mHelper.getConversations(IntArray.wrap(new int[] {0}), false);

        assertEquals(1, convos.size());
        assertTrue(conversationWrapperContainsChannel(convos, messagesFromB));

        convos =
                mHelper.getConversations(IntArray.wrap(new int[] {0, UserHandle.getUserId(UID_O + UserHandle.PER_USER_RANGE)}), false);

        assertEquals(2, convos.size());
        assertTrue(conversationWrapperContainsChannel(convos, messagesFromB));
        assertTrue(conversationWrapperContainsChannel(convos, messagesFromBUser10));
    }

    @Test
    public void testGetConversations_notDemoted() {
        String convoId = "convo";
        NotificationChannel messages =
                new NotificationChannel("messages", "Messages", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_O, UID_O, messages, true, false);
        NotificationChannel calls =
                new NotificationChannel("calls", "Calls", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_O, UID_O, calls, true, false);
        NotificationChannel p =
                new NotificationChannel("p calls", "Calls", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_P, UID_P, p, true, false);

        NotificationChannel channel =
                new NotificationChannel("A person msgs", "messages from A", IMPORTANCE_DEFAULT);
        channel.setConversationId(messages.getId(), convoId);
        mHelper.createNotificationChannel(PKG_O, UID_O, channel, true, false);

        NotificationChannel diffConvo =
                new NotificationChannel("B person msgs", "messages from B", IMPORTANCE_DEFAULT);
        diffConvo.setConversationId(p.getId(), "different convo");
        diffConvo.setDemoted(true);
        mHelper.createNotificationChannel(PKG_P, UID_P, diffConvo, true, false);

        NotificationChannel channel2 =
                new NotificationChannel("A person calls", "calls from A", IMPORTANCE_DEFAULT);
        channel2.setConversationId(calls.getId(), convoId);
        channel2.setImportantConversation(true);
        mHelper.createNotificationChannel(PKG_O, UID_O, channel2, false, false);

        List<ConversationChannelWrapper> convos =
                mHelper.getConversations(IntArray.wrap(new int[] {0}), false);

        assertEquals(2, convos.size());
        assertTrue(conversationWrapperContainsChannel(convos, channel));
        assertFalse(conversationWrapperContainsChannel(convos, diffConvo));
        assertTrue(conversationWrapperContainsChannel(convos, channel2));
    }

    @Test
    public void testGetConversations_onlyImportant() {
        String convoId = "convo";
        NotificationChannel messages =
                new NotificationChannel("messages", "Messages", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_O, UID_O, messages, true, false);
        NotificationChannel calls =
                new NotificationChannel("calls", "Calls", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_O, UID_O, calls, true, false);
        NotificationChannel p =
                new NotificationChannel("p calls", "Calls", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_P, UID_P, p, true, false);

        NotificationChannel channel =
                new NotificationChannel("A person msgs", "messages from A", IMPORTANCE_DEFAULT);
        channel.setConversationId(messages.getId(), convoId);
        channel.setImportantConversation(true);
        mHelper.createNotificationChannel(PKG_O, UID_O, channel, false, false);

        NotificationChannel diffConvo =
                new NotificationChannel("B person msgs", "messages from B", IMPORTANCE_DEFAULT);
        diffConvo.setConversationId(p.getId(), "different convo");
        diffConvo.setImportantConversation(true);
        mHelper.createNotificationChannel(PKG_P, UID_P, diffConvo, false, false);

        NotificationChannel channel2 =
                new NotificationChannel("A person calls", "calls from A", IMPORTANCE_DEFAULT);
        channel2.setConversationId(calls.getId(), convoId);
        mHelper.createNotificationChannel(PKG_O, UID_O, channel2, true, false);

        List<ConversationChannelWrapper> convos =
                mHelper.getConversations(IntArray.wrap(new int[] {0}), true);

        assertEquals(2, convos.size());
        assertTrue(conversationWrapperContainsChannel(convos, channel));
        assertTrue(conversationWrapperContainsChannel(convos, diffConvo));
        assertFalse(conversationWrapperContainsChannel(convos, channel2));
    }

    @Test
    public void testGetConversations_parentDeleted() {
        String convoId = "convo";
        NotificationChannel messages =
                new NotificationChannel("messages", "Messages", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_O, UID_O, messages, true, false);

        NotificationChannel channel =
                new NotificationChannel("A person msgs", "messages from A", IMPORTANCE_DEFAULT);
        channel.setConversationId(messages.getId(), convoId);
        channel.setImportantConversation(true);
        mHelper.createNotificationChannel(PKG_O, UID_O, channel, false, false);

        mHelper.permanentlyDeleteNotificationChannel(PKG_O, UID_O, "messages");

        List<ConversationChannelWrapper> convos =
                mHelper.getConversations(IntArray.wrap(new int[] {0}), true);

        assertEquals(1, convos.size());
        assertTrue(conversationWrapperContainsChannel(convos, channel));
    }

    private boolean conversationWrapperContainsChannel(List<ConversationChannelWrapper> list,
            NotificationChannel expected) {
        for (ConversationChannelWrapper ccw : list) {
            if (ccw.getNotificationChannel().equals(expected)) {
                return true;
            }
        }

        return false;
    }

    @Test
    public void testGetConversations_invalidPkg() {
        assertThat(mHelper.getConversations("bad", 1)).isEmpty();
    }

    @Test
    public void testGetConversations_noConversations() {
        NotificationChannel channel =
                new NotificationChannel("not_convo", "not_convo", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_O, UID_O, channel, true, false);

        assertThat(mHelper.getConversations(PKG_O, UID_O)).isEmpty();
    }

    @Test
    public void testGetConversations_noDisabledGroups() {
        NotificationChannelGroup group = new NotificationChannelGroup("a", "a");
        group.setBlocked(true);
        mHelper.createNotificationChannelGroup(PKG_O, UID_O, group, false);
        NotificationChannel parent = new NotificationChannel("parent", "p", 1);
        mHelper.createNotificationChannel(PKG_O, UID_O, parent, true, false);

        NotificationChannel channel =
                new NotificationChannel("convo", "convo", IMPORTANCE_DEFAULT);
        channel.setConversationId("parent", "convo");
        channel.setGroup(group.getId());
        mHelper.createNotificationChannel(PKG_O, UID_O, channel, true, false);

        assertThat(mHelper.getConversations(PKG_O, UID_O)).isEmpty();
    }

    @Test
    public void testGetConversations_noDeleted() {
        NotificationChannel parent = new NotificationChannel("parent", "p", 1);
        mHelper.createNotificationChannel(PKG_O, UID_O, parent, true, false);
        NotificationChannel channel =
                new NotificationChannel("convo", "convo", IMPORTANCE_DEFAULT);
        channel.setConversationId("parent", "convo");
        mHelper.createNotificationChannel(PKG_O, UID_O, channel, true, false);
        mHelper.deleteNotificationChannel(PKG_O, UID_O, channel.getId());

        assertThat(mHelper.getConversations(PKG_O, UID_O)).isEmpty();
    }

    @Test
    public void testGetConversations_noDemoted() {
        NotificationChannel parent = new NotificationChannel("parent", "p", 1);
        mHelper.createNotificationChannel(PKG_O, UID_O, parent, true, false);
        NotificationChannel channel =
                new NotificationChannel("convo", "convo", IMPORTANCE_DEFAULT);
        channel.setConversationId("parent", "convo");
        channel.setDemoted(true);
        mHelper.createNotificationChannel(PKG_O, UID_O, channel, true, false);

        assertThat(mHelper.getConversations(PKG_O, UID_O)).isEmpty();
    }

    @Test
    public void testGetConversations() {
        NotificationChannelGroup group = new NotificationChannelGroup("acct", "account_name");
        mHelper.createNotificationChannelGroup(PKG_O, UID_O, group, true);

        NotificationChannel messages =
                new NotificationChannel("messages", "Messages", IMPORTANCE_DEFAULT);
        messages.setGroup(group.getId());
        mHelper.createNotificationChannel(PKG_O, UID_O, messages, true, false);
        NotificationChannel calls =
                new NotificationChannel("calls", "Calls", IMPORTANCE_HIGH);
        mHelper.createNotificationChannel(PKG_O, UID_O, calls, true, false);

        NotificationChannel channel =
                new NotificationChannel("A person", "A lovely person", IMPORTANCE_DEFAULT);
        channel.setGroup(group.getId());
        channel.setConversationId(messages.getId(), channel.getName().toString());
        mHelper.createNotificationChannel(PKG_O, UID_O, channel, true, false);

        NotificationChannel channel2 =
                new NotificationChannel("B person", "B fabulous person", IMPORTANCE_DEFAULT);
        channel2.setConversationId(calls.getId(), channel2.getName().toString());
        mHelper.createNotificationChannel(PKG_O, UID_O, channel2, true, false);

        Map<String, NotificationChannel> expected = new HashMap<>();
        expected.put(channel.getId(), channel);
        expected.put(channel2.getId(), channel2);

        Map<String, CharSequence> expectedGroup = new HashMap<>();
        expectedGroup.put(channel.getId(), group.getName());
        expectedGroup.put(channel2.getId(), null);

        Map<String, CharSequence> expectedParentLabel= new HashMap<>();
        expectedParentLabel.put(channel.getId(), messages.getName());
        expectedParentLabel.put(channel2.getId(), calls.getName());

        ArrayList<ConversationChannelWrapper> convos = mHelper.getConversations(PKG_O, UID_O);
        assertThat(convos).hasSize(2);

        for (ConversationChannelWrapper convo : convos) {
            assertThat(convo.getNotificationChannel())
                    .isEqualTo(expected.get(convo.getNotificationChannel().getId()));
            assertThat(convo.getParentChannelLabel())
                    .isEqualTo(expectedParentLabel.get(convo.getNotificationChannel().getId()));
            assertThat(convo.getGroupLabel())
                    .isEqualTo(expectedGroup.get(convo.getNotificationChannel().getId()));
        }
    }

    @Test
    public void testDeleteConversations() {
        String convoId = "convo";
        String convoIdC = "convoC";
        NotificationChannel messages =
                new NotificationChannel("messages", "Messages", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_O, UID_O, messages, true, false);
        NotificationChannel calls =
                new NotificationChannel("calls", "Calls", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_O, UID_O, calls, true, false);

        NotificationChannel channel =
                new NotificationChannel("A person msgs", "messages from A", IMPORTANCE_DEFAULT);
        channel.setConversationId(messages.getId(), convoId);
        mHelper.createNotificationChannel(PKG_O, UID_O, channel, true, false);

        NotificationChannel noMatch =
                new NotificationChannel("B person msgs", "messages from B", IMPORTANCE_DEFAULT);
        noMatch.setConversationId(messages.getId(), "different convo");
        mHelper.createNotificationChannel(PKG_O, UID_O, noMatch, true, false);

        NotificationChannel channel2 =
                new NotificationChannel("A person calls", "calls from A", IMPORTANCE_DEFAULT);
        channel2.setConversationId(calls.getId(), convoId);
        mHelper.createNotificationChannel(PKG_O, UID_O, channel2, true, false);

        NotificationChannel channel3 =
                new NotificationChannel("C person msgs", "msgs from C", IMPORTANCE_DEFAULT);
        channel3.setConversationId(messages.getId(), convoIdC);
        mHelper.createNotificationChannel(PKG_O, UID_O, channel3, true, false);

        assertEquals(channel, mHelper.getNotificationChannel(PKG_O, UID_O, channel.getId(), false));
        assertEquals(channel2,
                mHelper.getNotificationChannel(PKG_O, UID_O, channel2.getId(), false));
        List<String> deleted = mHelper.deleteConversations(PKG_O, UID_O, Set.of(convoId, convoIdC));
        assertEquals(3, deleted.size());

        assertEquals(messages,
                mHelper.getNotificationChannel(PKG_O, UID_O, messages.getId(), false));
        assertEquals(noMatch,
                mHelper.getNotificationChannel(PKG_O, UID_O, noMatch.getId(), false));

        assertNull(mHelper.getNotificationChannel(PKG_O, UID_O, channel.getId(), false));
        assertNull(mHelper.getNotificationChannel(PKG_O, UID_O, channel2.getId(), false));
        assertEquals(channel, mHelper.getNotificationChannel(PKG_O, UID_O, channel.getId(), true));
        assertEquals(channel2,
                mHelper.getNotificationChannel(PKG_O, UID_O, channel2.getId(), true));

        assertEquals(9, mLogger.getCalls().size());
        assertEquals(
                NotificationChannelLogger.NotificationChannelEvent.NOTIFICATION_CHANNEL_CREATED,
                mLogger.get(0).event);  // Channel messages
        assertEquals(
                NotificationChannelLogger.NotificationChannelEvent.NOTIFICATION_CHANNEL_CREATED,
                mLogger.get(1).event);  // Channel calls
        assertEquals(
                NotificationChannelLogger.NotificationChannelEvent
                        .NOTIFICATION_CHANNEL_CONVERSATION_CREATED,
                mLogger.get(2).event);  // Channel channel - Conversation A person msgs
        assertEquals(
                NotificationChannelLogger.NotificationChannelEvent
                        .NOTIFICATION_CHANNEL_CONVERSATION_CREATED,
                mLogger.get(3).event);  // Channel noMatch - Conversation B person msgs
        assertEquals(
                NotificationChannelLogger.NotificationChannelEvent
                        .NOTIFICATION_CHANNEL_CONVERSATION_CREATED,
                mLogger.get(4).event);  // Channel channel2 - Conversation A person calls
        assertEquals(
                NotificationChannelLogger.NotificationChannelEvent
                        .NOTIFICATION_CHANNEL_CONVERSATION_CREATED,
                mLogger.get(5).event);  // Channel channel3 - Conversation C person msgs
        assertEquals(
                NotificationChannelLogger.NotificationChannelEvent
                        .NOTIFICATION_CHANNEL_CONVERSATION_DELETED,
                mLogger.get(6).event);  // Delete Channel channel - Conversation A person msgs
        assertEquals(
                NotificationChannelLogger.NotificationChannelEvent
                        .NOTIFICATION_CHANNEL_CONVERSATION_DELETED,
                mLogger.get(7).event);  // Delete Channel channel2 - Conversation A person calls
        assertEquals(
                NotificationChannelLogger.NotificationChannelEvent
                        .NOTIFICATION_CHANNEL_CONVERSATION_DELETED,
                mLogger.get(8).event);  // Delete Channel channel3 - Conversation C person msgs
    }

    @Test
    public void testInvalidMessageSent() {
        // create package preferences
        mHelper.canShowBadge(PKG_P, UID_P);

        // check default value
        assertFalse(mHelper.isInInvalidMsgState(PKG_P, UID_P));

        // change it
        mHelper.setInvalidMessageSent(PKG_P, UID_P);
        assertTrue(mHelper.isInInvalidMsgState(PKG_P, UID_P));
        assertTrue(mHelper.hasSentInvalidMsg(PKG_P, UID_P));
    }

    @Test
    public void testValidMessageSent() {
        // create package preferences
        mHelper.canShowBadge(PKG_P, UID_P);

        // get into the bad state
        mHelper.setInvalidMessageSent(PKG_P, UID_P);

        // and then fix it
        mHelper.setValidMessageSent(PKG_P, UID_P);

        assertTrue(mHelper.hasSentValidMsg(PKG_P, UID_P));
        assertFalse(mHelper.isInInvalidMsgState(PKG_P, UID_P));
    }

    @Test
    public void testUserDemotedInvalidMsgApp() {
        // create package preferences
        mHelper.canShowBadge(PKG_P, UID_P);

        // demotion means nothing before msg notif sent
        mHelper.setInvalidMsgAppDemoted(PKG_P, UID_P, true);
        assertFalse(mHelper.hasUserDemotedInvalidMsgApp(PKG_P, UID_P));

        // it's valid when incomplete msgs have been sent
        mHelper.setInvalidMessageSent(PKG_P, UID_P);
        assertTrue(mHelper.hasUserDemotedInvalidMsgApp(PKG_P, UID_P));

        // and is invalid once complete msgs are sent
        mHelper.setValidMessageSent(PKG_P, UID_P);
        assertFalse(mHelper.hasUserDemotedInvalidMsgApp(PKG_P, UID_P));
    }

    @Test
    public void testValidBubbleSent() {
        // create package preferences
        mHelper.canShowBadge(PKG_P, UID_P);
        // false by default
        assertFalse(mHelper.hasSentValidBubble(PKG_P, UID_P));

        // set something valid was sent
        mHelper.setValidBubbleSent(PKG_P, UID_P);
        assertTrue(mHelper.hasSentValidBubble(PKG_P, UID_P));
    }

    @Test
    public void testPullPackageChannelPreferencesStats() {
        String channelId = "parent";
        String name = "messages";
        NotificationChannel fodderA = new NotificationChannel("a", "a", IMPORTANCE_LOW);
        mHelper.createNotificationChannel(PKG_O, UID_O, fodderA, true, false);
        NotificationChannel channel =
                new NotificationChannel(channelId, name, IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_O, UID_O, channel, true, false);
        NotificationChannel fodderB = new NotificationChannel("b", "b", IMPORTANCE_HIGH);
        mHelper.createNotificationChannel(PKG_O, UID_O, fodderB, true, false);

        ArrayList<StatsEvent> events = new ArrayList<>();
        mHelper.pullPackageChannelPreferencesStats(events);

        int found = 0;
        for (WrappedSysUiStatsEvent.WrappedBuilder builder : mStatsEventBuilderFactory.builders) {
            if (builder.getAtomId() == PACKAGE_NOTIFICATION_CHANNEL_PREFERENCES
                    && channelId.equals(builder.getValue(CHANNEL_ID_FIELD_NUMBER))) {
                ++found;
                assertEquals("uid", UID_O, builder.getValue(UID_FIELD_NUMBER));
                assertTrue("uid annotation", builder.getBooleanAnnotation(
                        UID_FIELD_NUMBER, ANNOTATION_ID_IS_UID));
                assertEquals("importance", IMPORTANCE_DEFAULT, builder.getValue(
                        IMPORTANCE_FIELD_NUMBER));
                assertEquals("name", name, builder.getValue(CHANNEL_NAME_FIELD_NUMBER));
                assertFalse("isconv", builder.getBoolean(IS_CONVERSATION_FIELD_NUMBER));
                assertFalse("deleted", builder.getBoolean(IS_DELETED_FIELD_NUMBER));
            }
        }
    }

    @Test
    public void testPullPackageChannelPreferencesStats_one_to_one() {
        NotificationChannel channelA = new NotificationChannel("a", "a", IMPORTANCE_LOW);
        mHelper.createNotificationChannel(PKG_O, UID_O, channelA, true, false);
        NotificationChannel channelB = new NotificationChannel("b", "b", IMPORTANCE_LOW);
        mHelper.createNotificationChannel(PKG_O, UID_O, channelB, true, false);
        NotificationChannel channelC = new NotificationChannel("c", "c", IMPORTANCE_HIGH);
        mHelper.createNotificationChannel(PKG_O, UID_O, channelC, true, false);

        List<String> channels = new LinkedList<>(Arrays.asList("a", "b", "c"));

        ArrayList<StatsEvent> events = new ArrayList<>();
        mHelper.pullPackageChannelPreferencesStats(events);

        int found = 0;
        for (WrappedSysUiStatsEvent.WrappedBuilder builder : mStatsEventBuilderFactory.builders) {
            if (builder.getAtomId() == PACKAGE_NOTIFICATION_CHANNEL_PREFERENCES) {
                Object id = builder.getValue(CHANNEL_ID_FIELD_NUMBER);
                assertTrue("missing channel in the output", channels.contains(id));
                channels.remove(id);
            }
        }
        assertTrue("unexpected channel in output", channels.isEmpty());
    }

    @Test
    public void testPullPackageChannelPreferencesStats_conversation() {
        String conversationId = "friend";

        NotificationChannel parent =
                new NotificationChannel("parent", "messages", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_O, UID_O, parent, true, false);

        String channelId = String.format(
                CONVERSATION_CHANNEL_ID_FORMAT, parent.getId(), conversationId);
        String name = "conversation";
        NotificationChannel friend = new NotificationChannel(channelId,
                name, IMPORTANCE_DEFAULT);
        friend.setConversationId(parent.getId(), conversationId);
        mHelper.createNotificationChannel(PKG_O, UID_O, friend, true, false);

        ArrayList<StatsEvent> events = new ArrayList<>();
        mHelper.pullPackageChannelPreferencesStats(events);

        for (WrappedSysUiStatsEvent.WrappedBuilder builder : mStatsEventBuilderFactory.builders) {
            if (builder.getAtomId() == PACKAGE_NOTIFICATION_CHANNEL_PREFERENCES
                    && channelId.equals(builder.getValue(CHANNEL_ID_FIELD_NUMBER))) {
                assertTrue("isConveration should be true", builder.getBoolean(
                        IS_CONVERSATION_FIELD_NUMBER));
                assertFalse("not demoted", builder.getBoolean(
                        IS_DEMOTED_CONVERSATION_FIELD_NUMBER));
                assertFalse("not important", builder.getBoolean(
                        IS_IMPORTANT_CONVERSATION_FIELD_NUMBER));
            }
        }
    }

    @Test
    public void testPullPackageChannelPreferencesStats_conversation_demoted() {
        NotificationChannel parent =
                new NotificationChannel("parent", "messages", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_O, UID_O, parent, true, false);
        String channelId = String.format(
                CONVERSATION_CHANNEL_ID_FORMAT, parent.getId(), "friend");
        NotificationChannel friend = new NotificationChannel(channelId,
                "conversation", IMPORTANCE_DEFAULT);
        friend.setConversationId(parent.getId(), "friend");
        friend.setDemoted(true);
        mHelper.createNotificationChannel(PKG_O, UID_O, friend, true, false);

        ArrayList<StatsEvent> events = new ArrayList<>();
        mHelper.pullPackageChannelPreferencesStats(events);

        for (WrappedSysUiStatsEvent.WrappedBuilder builder : mStatsEventBuilderFactory.builders) {
            if (builder.getAtomId() == PACKAGE_NOTIFICATION_CHANNEL_PREFERENCES
                    && channelId.equals(builder.getValue(CHANNEL_ID_FIELD_NUMBER))) {
                assertTrue("isConveration should be true", builder.getBoolean(
                        IS_CONVERSATION_FIELD_NUMBER));
                assertTrue("is demoted", builder.getBoolean(
                        IS_DEMOTED_CONVERSATION_FIELD_NUMBER));
                assertFalse("not important", builder.getBoolean(
                        IS_IMPORTANT_CONVERSATION_FIELD_NUMBER));
            }
        }
    }

    @Test
    public void testPullPackageChannelPreferencesStats_conversation_priority() {
        NotificationChannel parent =
                new NotificationChannel("parent", "messages", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_O, UID_O, parent, true, false);
        String channelId = String.format(
                CONVERSATION_CHANNEL_ID_FORMAT, parent.getId(), "friend");
        NotificationChannel friend = new NotificationChannel(channelId,
                "conversation", IMPORTANCE_DEFAULT);
        friend.setConversationId(parent.getId(), "friend");
        friend.setImportantConversation(true);
        mHelper.createNotificationChannel(PKG_O, UID_O, friend, false, false);

        ArrayList<StatsEvent> events = new ArrayList<>();
        mHelper.pullPackageChannelPreferencesStats(events);

        for (WrappedSysUiStatsEvent.WrappedBuilder builder : mStatsEventBuilderFactory.builders) {
            if (builder.getAtomId() == PACKAGE_NOTIFICATION_CHANNEL_PREFERENCES
                    && channelId.equals(builder.getValue(CHANNEL_ID_FIELD_NUMBER))) {
                assertTrue("isConveration should be true", builder.getBoolean(
                        IS_CONVERSATION_FIELD_NUMBER));
                assertFalse("not demoted", builder.getBoolean(
                        IS_DEMOTED_CONVERSATION_FIELD_NUMBER));
                assertTrue("is important", builder.getBoolean(
                        IS_IMPORTANT_CONVERSATION_FIELD_NUMBER));
            }
        }
    }

    @Test
    public void testPullPackagePreferencesStats_postPermissionMigration() {

        // build a collection of app permissions that should be passed in but ignored
        ArrayMap<Pair<Integer, String>, Pair<Boolean, Boolean>> appPermissions = new ArrayMap<>();
        appPermissions.put(new Pair(1, "first"), new Pair(true, false));    // not in local prefs
        appPermissions.put(new Pair(3, "third"), new Pair(false, true));   // not in local prefs
        appPermissions.put(new Pair(UID_O, PKG_O), new Pair(false, true)); // in local prefs

        // local preferences
        mHelper.canShowBadge(PKG_O, UID_O);
        mHelper.canShowBadge(PKG_P, UID_P);

        // expected output. format: uid -> importance, as only uid (and not package name)
        // is in PackageNotificationPreferences
        ArrayMap<Integer, Pair<Integer, Boolean>> expected = new ArrayMap<>();
        expected.put(1, new Pair(IMPORTANCE_DEFAULT, false));
        expected.put(3, new Pair(IMPORTANCE_NONE, true));
        expected.put(UID_O, new Pair(IMPORTANCE_NONE, true));     // banned by permissions
        expected.put(UID_P, new Pair(IMPORTANCE_NONE, false));    // defaults to none, false

        ArrayList<StatsEvent> events = new ArrayList<>();
        mHelper.pullPackagePreferencesStats(events, appPermissions);

        for (WrappedSysUiStatsEvent.WrappedBuilder builder : mStatsEventBuilderFactory.builders) {
            if (builder.getAtomId() == PACKAGE_NOTIFICATION_PREFERENCES) {
                int uid = builder.getInt(PackageNotificationPreferences.UID_FIELD_NUMBER);
                boolean userSet = builder.getBoolean(
                        PackageNotificationPreferences.USER_SET_IMPORTANCE_FIELD_NUMBER);

                // if it's one of the expected ids, then make sure the importance matches
                assertTrue(expected.containsKey(uid));
                assertThat(expected.get(uid).first).isEqualTo(
                        builder.getInt(PackageNotificationPreferences.IMPORTANCE_FIELD_NUMBER));
                assertThat(expected.get(uid).second).isEqualTo(userSet);
            }
        }
    }

    @Test
    public void testUnlockNotificationChannelImportance() {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_LOW);
        mHelper.createNotificationChannel(PKG_O, UID_O, channel, true, false);
        channel.lockFields(USER_LOCKED_IMPORTANCE);
        assertTrue((channel.getUserLockedFields() & USER_LOCKED_IMPORTANCE) != 0);

        mHelper.unlockNotificationChannelImportance(PKG_O, UID_O, channel.getId());
        assertTrue((channel.getUserLockedFields() & USER_LOCKED_IMPORTANCE) == 0);

    }

    @Test
    public void testUnlockAllNotificationChannels() {
        NotificationChannel channelA = new NotificationChannel("a", "a", IMPORTANCE_LOW);
        mHelper.createNotificationChannel(PKG_O, UID_O, channelA, true, false);
        NotificationChannel channelB = new NotificationChannel("b", "b", IMPORTANCE_DEFAULT);
        mHelper.createNotificationChannel(PKG_P, UID_P, channelB, true, false);
        NotificationChannel channelC = new NotificationChannel("c", "c", IMPORTANCE_HIGH);
        mHelper.createNotificationChannel(PKG_P, UID_O, channelC, false, false);

        channelA.lockFields(USER_LOCKED_IMPORTANCE);
        channelB.lockFields(USER_LOCKED_IMPORTANCE);
        channelC.lockFields(USER_LOCKED_IMPORTANCE);

        assertTrue((channelA.getUserLockedFields() & USER_LOCKED_IMPORTANCE) != 0);
        assertTrue((channelB.getUserLockedFields() & USER_LOCKED_IMPORTANCE) != 0);
        assertTrue((channelC.getUserLockedFields() & USER_LOCKED_IMPORTANCE) != 0);

        mHelper.unlockAllNotificationChannels();
        assertTrue((channelA.getUserLockedFields() & USER_LOCKED_IMPORTANCE) == 0);
        assertTrue((channelB.getUserLockedFields() & USER_LOCKED_IMPORTANCE) == 0);
        assertTrue((channelC.getUserLockedFields() & USER_LOCKED_IMPORTANCE) == 0);
    }
}
