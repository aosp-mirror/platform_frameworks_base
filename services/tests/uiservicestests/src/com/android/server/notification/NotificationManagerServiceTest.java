/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
import static android.app.Notification.CATEGORY_CALL;
import static android.app.Notification.FLAG_BUBBLE;
import static android.app.Notification.FLAG_FOREGROUND_SERVICE;
import static android.app.NotificationManager.EXTRA_BLOCKED_STATE;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MAX;
import static android.app.NotificationManager.IMPORTANCE_NONE;
import static android.app.NotificationManager.IMPORTANCE_UNSPECIFIED;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_OFF;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_ON;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR;
import static android.content.pm.PackageManager.FEATURE_WATCH;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION_CODES.O_MR1;
import static android.os.Build.VERSION_CODES.P;
import static android.service.notification.Adjustment.KEY_IMPORTANCE;
import static android.service.notification.Adjustment.KEY_USER_SENTIMENT;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEUTRAL;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.AutomaticZenRule;
import android.app.IActivityManager;
import android.app.INotificationManager;
import android.app.ITransientNotification;
import android.app.IUriGrantsManager;
import android.app.Notification;
import android.app.Notification.MessagingStyle.Message;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.usage.UsageStatsManagerInternal;
import android.companion.ICompanionDeviceManager;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.MediaStore;
import android.provider.Settings;
import android.service.notification.Adjustment;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.service.notification.ZenPolicy;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.testing.TestablePermissions;
import android.text.Html;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;

import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;

import com.android.internal.R;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.UiServiceTestCase;
import com.android.server.lights.Light;
import com.android.server.lights.LightsManager;
import com.android.server.notification.NotificationManagerService.NotificationAssistants;
import com.android.server.notification.NotificationManagerService.NotificationListeners;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class NotificationManagerServiceTest extends UiServiceTestCase {
    private static final String TEST_CHANNEL_ID = "NotificationManagerServiceTestChannelId";
    private static final String CLEAR_DEVICE_CONFIG_KEY_CMD =
            "device_config delete " + DeviceConfig.NAMESPACE_SYSTEMUI + " "
                    + SystemUiDeviceConfigFlags.NAS_DEFAULT_SERVICE;
    private static final String SET_DEFAULT_ASSISTANT_DEVICE_CONFIG_CMD =
            "device_config put " + DeviceConfig.NAMESPACE_SYSTEMUI + " "
                    + SystemUiDeviceConfigFlags.NAS_DEFAULT_SERVICE;

    private final int mUid = Binder.getCallingUid();
    private TestableNotificationManagerService mService;
    private INotificationManager mBinderService;
    private NotificationManagerInternal mInternalService;
    @Mock
    private IPackageManager mPackageManager;
    @Mock
    private PackageManager mPackageManagerClient;
    @Mock
    private WindowManagerInternal mWindowManagerInternal;
    private TestableContext mContext = spy(getContext());
    private final String PKG = mContext.getPackageName();
    private TestableLooper mTestableLooper;
    @Mock
    private RankingHelper mRankingHelper;
    @Mock private PreferencesHelper mPreferencesHelper;
    AtomicFile mPolicyFile;
    File mFile;
    @Mock
    private NotificationUsageStats mUsageStats;
    @Mock
    private UsageStatsManagerInternal mAppUsageStats;
    @Mock
    private AudioManager mAudioManager;
    @Mock
    ActivityManager mActivityManager;
    NotificationManagerService.WorkerHandler mHandler;
    @Mock
    Resources mResources;

    private NotificationChannel mTestNotificationChannel = new NotificationChannel(
            TEST_CHANNEL_ID, TEST_CHANNEL_ID, IMPORTANCE_DEFAULT);

    private static final int NOTIFICATION_LOCATION_UNKNOWN = 0;

    @Mock
    private NotificationListeners mListeners;
    @Mock private NotificationAssistants mAssistants;
    @Mock private ConditionProviders mConditionProviders;
    private ManagedServices.ManagedServiceInfo mListener;
    @Mock private ICompanionDeviceManager mCompanionMgr;
    @Mock SnoozeHelper mSnoozeHelper;
    @Mock GroupHelper mGroupHelper;
    @Mock
    IBinder mPermOwner;
    @Mock
    IActivityManager mAm;
    @Mock
    IUriGrantsManager mUgm;
    @Mock
    UriGrantsManagerInternal mUgmInternal;
    @Mock
    AppOpsManager mAppOpsManager;
    @Mock
    private TestableNotificationManagerService.NotificationAssistantAccessGrantedCallback
            mNotificationAssistantAccessGrantedCallback;
    @Mock
    UserManager mUm;

    // Use a Testable subclass so we can simulate calls from the system without failing.
    private static class TestableNotificationManagerService extends NotificationManagerService {
        int countSystemChecks = 0;
        boolean isSystemUid = true;
        int countLogSmartSuggestionsVisible = 0;
        @Nullable
        NotificationAssistantAccessGrantedCallback mNotificationAssistantAccessGrantedCallback;

        TestableNotificationManagerService(Context context) {
            super(context);
        }

        @Override
        protected boolean isCallingUidSystem() {
            countSystemChecks++;
            return isSystemUid;
        }

        @Override
        protected boolean isCallerSystemOrPhone() {
            countSystemChecks++;
            return isSystemUid;
        }

        @Override
        protected ICompanionDeviceManager getCompanionManager() {
            return null;
        }

        @Override
        protected void reportUserInteraction(NotificationRecord r) {
            return;
        }

        @Override
        protected void handleSavePolicyFile() {
            return;
        }

        @Override
        void logSmartSuggestionsVisible(NotificationRecord r, int notificationLocation) {
            super.logSmartSuggestionsVisible(r, notificationLocation);
            countLogSmartSuggestionsVisible++;
        }

        @Override
        protected void setNotificationAssistantAccessGrantedForUserInternal(
                ComponentName assistant, int userId, boolean granted) {
            if (mNotificationAssistantAccessGrantedCallback != null) {
                mNotificationAssistantAccessGrantedCallback.onGranted(assistant, userId, granted);
                return;
            }
            super.setNotificationAssistantAccessGrantedForUserInternal(assistant, userId, granted);
        }

        private void setNotificationAssistantAccessGrantedCallback(
                @Nullable NotificationAssistantAccessGrantedCallback callback) {
            this.mNotificationAssistantAccessGrantedCallback = callback;
        }

        interface NotificationAssistantAccessGrantedCallback {
            void onGranted(ComponentName assistant, int userId, boolean granted);
        }

        @Override
        protected boolean canLaunchInActivityView(Context context, PendingIntent pendingIntent,
                String packageName) {
            // Tests for this not being true are in CTS NotificationManagerTest
            return true;
        }
    }

    private class TestableToastCallback extends ITransientNotification.Stub {
        @Override
        public void show(IBinder windowToken) {
        }

        @Override
        public void hide() {
        }
    }

    @Before
    public void setUp() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                "android.permission.WRITE_DEVICE_CONFIG", "android.permission.READ_DEVICE_CONFIG");

        MockitoAnnotations.initMocks(this);

        LocalServices.removeServiceForTest(UriGrantsManagerInternal.class);
        LocalServices.addService(UriGrantsManagerInternal.class, mUgmInternal);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.addService(WindowManagerInternal.class, mWindowManagerInternal);

        doNothing().when(mContext).sendBroadcastAsUser(any(), any(), any());

        mService = new TestableNotificationManagerService(mContext);

        // Use this testable looper.
        mTestableLooper = TestableLooper.get(this);
        mHandler = mService.new WorkerHandler(mTestableLooper.getLooper());
        // MockPackageManager - default returns ApplicationInfo with matching calling UID
        mContext.setMockPackageManager(mPackageManagerClient);
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = mUid;
        when(mPackageManager.getApplicationInfo(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
        when(mPackageManagerClient.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
        when(mPackageManagerClient.getPackageUidAsUser(any(), anyInt())).thenReturn(mUid);
        final LightsManager mockLightsManager = mock(LightsManager.class);
        when(mockLightsManager.getLight(anyInt())).thenReturn(mock(Light.class));
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        when(mPackageManagerClient.hasSystemFeature(FEATURE_WATCH)).thenReturn(false);
        when(mUgmInternal.newUriPermissionOwner(anyString())).thenReturn(mPermOwner);
        when(mPackageManager.getPackagesForUid(mUid)).thenReturn(new String[]{PKG});
        when(mPackageManagerClient.getPackagesForUid(anyInt())).thenReturn(new String[]{PKG});
        mContext.addMockSystemService(AppOpsManager.class, mock(AppOpsManager.class));

        // write to a test file; the system file isn't readable from tests
        mFile = new File(mContext.getCacheDir(), "test.xml");
        mFile.createNewFile();
        final String preupgradeXml = "<notification-policy></notification-policy>";
        mPolicyFile = new AtomicFile(mFile);
        FileOutputStream fos = mPolicyFile.startWrite();
        fos.write(preupgradeXml.getBytes());
        mPolicyFile.finishWrite(fos);

        // Setup managed services
        mListener = mListeners.new ManagedServiceInfo(
                null, new ComponentName(PKG, "test_class"), mUid, true, null, 0);
        when(mListeners.checkServiceTokenLocked(any())).thenReturn(mListener);
        ManagedServices.Config listenerConfig = new ManagedServices.Config();
        listenerConfig.xmlTag = NotificationListeners.TAG_ENABLED_NOTIFICATION_LISTENERS;
        when(mListeners.getConfig()).thenReturn(listenerConfig);
        ManagedServices.Config assistantConfig = new ManagedServices.Config();
        assistantConfig.xmlTag = NotificationAssistants.TAG_ENABLED_NOTIFICATION_ASSISTANTS;
        when(mAssistants.getConfig()).thenReturn(assistantConfig);
        ManagedServices.Config dndConfig = new ManagedServices.Config();
        dndConfig.xmlTag = ConditionProviders.TAG_ENABLED_DND_APPS;
        when(mConditionProviders.getConfig()).thenReturn(dndConfig);

        when(mAssistants.isAdjustmentAllowed(anyString())).thenReturn(true);


        mService.init(mTestableLooper.getLooper(),
                mPackageManager, mPackageManagerClient, mockLightsManager,
                mListeners, mAssistants, mConditionProviders,
                mCompanionMgr, mSnoozeHelper, mUsageStats, mPolicyFile, mActivityManager,
                mGroupHelper, mAm, mAppUsageStats,
                mock(DevicePolicyManagerInternal.class), mUgm, mUgmInternal,
                mAppOpsManager, mUm);
        mService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);

        mService.setAudioManager(mAudioManager);

        // Tests call directly into the Binder.
        mBinderService = mService.getBinderService();
        mInternalService = mService.getInternalService();

        mBinderService.createNotificationChannels(
                PKG, new ParceledListSlice(Arrays.asList(mTestNotificationChannel)));
        assertNotNull(mBinderService.getNotificationChannel(
                PKG, mContext.getUserId(), PKG, TEST_CHANNEL_ID));
    }

    @After
    public void tearDown() throws Exception {
        mFile.delete();
        clearDeviceConfig();
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    public void waitForIdle() {
        mTestableLooper.processAllMessages();
    }

    private void setUpPrefsForBubbles(boolean globalEnabled, boolean pkgEnabled,
            boolean channelEnabled) {
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mPreferencesHelper.bubblesEnabled(any())).thenReturn(globalEnabled);
        when(mPreferencesHelper.areBubblesAllowed(anyString(), anyInt())).thenReturn(pkgEnabled);
        when(mPreferencesHelper.getNotificationChannel(
                anyString(), anyInt(), anyString(), anyBoolean())).thenReturn(
                mTestNotificationChannel);
        when(mPreferencesHelper.getImportance(anyString(), anyInt())).thenReturn(
                mTestNotificationChannel.getImportance());
        mTestNotificationChannel.setAllowBubbles(channelEnabled);
    }

    private StatusBarNotification generateSbn(String pkg, int uid, long postTime, int userId) {
        Notification.Builder nb = new Notification.Builder(mContext, "a")
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon);
        StatusBarNotification sbn = new StatusBarNotification(pkg, pkg, uid, "tag", uid, 0,
                nb.build(), new UserHandle(userId), null, postTime);
        return sbn;
    }

    private NotificationRecord generateNotificationRecord(NotificationChannel channel, int id,
            String groupKey, boolean isSummary) {
        Notification.Builder nb = new Notification.Builder(mContext, channel.getId())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setGroup(groupKey)
                .setGroupSummary(isSummary);

        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, id, "tag", mUid, 0,
                nb.build(), new UserHandle(mUid), null, 0);
        return new NotificationRecord(mContext, sbn, channel);
    }

    private NotificationRecord generateNotificationRecord(NotificationChannel channel) {
        return generateNotificationRecord(channel, null);
    }

    private NotificationRecord generateNotificationRecord(NotificationChannel channel,
            Notification.TvExtender extender) {
        return generateNotificationRecord(channel, extender, false /* isBubble */);
    }

    private NotificationRecord generateNotificationRecord(NotificationChannel channel,
            Notification.TvExtender extender, boolean isBubble) {
        if (channel == null) {
            channel = mTestNotificationChannel;
        }
        Notification.Builder nb = new Notification.Builder(mContext, channel.getId())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon);
        if (extender != null) {
            nb.extend(extender);
        }
        if (isBubble) {
            nb.setBubbleMetadata(getBasicBubbleMetadataBuilder().build());
        }
        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 1, "tag", mUid, 0,
                nb.build(), new UserHandle(mUid), null, 0);
        return new NotificationRecord(mContext, sbn, channel);
    }

    private Map<String, Answer> getSignalExtractorSideEffects() {
        Map<String, Answer> answers = new ArrayMap<>();

        answers.put("override group key", invocationOnMock -> {
            ((NotificationRecord) invocationOnMock.getArguments()[0])
                    .setOverrideGroupKey("bananas");
            return null;
        });
        answers.put("override people", invocationOnMock -> {
            ((NotificationRecord) invocationOnMock.getArguments()[0])
                    .setPeopleOverride(new ArrayList<>());
            return null;
        });
        answers.put("snooze criteria", invocationOnMock -> {
            ((NotificationRecord) invocationOnMock.getArguments()[0])
                    .setSnoozeCriteria(new ArrayList<>());
            return null;
        });
        answers.put("notification channel", invocationOnMock -> {
            ((NotificationRecord) invocationOnMock.getArguments()[0])
                    .updateNotificationChannel(new NotificationChannel("a", "", IMPORTANCE_LOW));
            return null;
        });
        answers.put("badging", invocationOnMock -> {
            NotificationRecord r = (NotificationRecord) invocationOnMock.getArguments()[0];
            r.setShowBadge(!r.canShowBadge());
            return null;
        });
        answers.put("bubbles", invocationOnMock -> {
            NotificationRecord r = (NotificationRecord) invocationOnMock.getArguments()[0];
            r.setAllowBubble(!r.canBubble());
            return null;
        });
        answers.put("package visibility", invocationOnMock -> {
            ((NotificationRecord) invocationOnMock.getArguments()[0]).setPackageVisibilityOverride(
                    Notification.VISIBILITY_SECRET);
            return null;
        });

        return answers;
    }

    private void clearDeviceConfig() {
        DeviceConfig.resetToDefaults(
                Settings.RESET_MODE_PACKAGE_DEFAULTS, DeviceConfig.NAMESPACE_SYSTEMUI);
    }

    private void setDefaultAssistantInDeviceConfig(String componentName) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.NAS_DEFAULT_SERVICE,
                componentName,
                false);
    }

    private Notification.BubbleMetadata.Builder getBasicBubbleMetadataBuilder() {
        PendingIntent pi = PendingIntent.getActivity(mContext, 0, new Intent(), 0);
        return new Notification.BubbleMetadata.Builder()
                .setIntent(pi)
                .setIcon(Icon.createWithResource(mContext, android.R.drawable.sym_def_app_icon));
    }

    @Test
    public void testCreateNotificationChannels_SingleChannel() throws Exception {
        final NotificationChannel channel =
                new NotificationChannel("id", "name", IMPORTANCE_DEFAULT);
        mBinderService.createNotificationChannels(PKG,
                new ParceledListSlice(Arrays.asList(channel)));
        final NotificationChannel createdChannel =
                mBinderService.getNotificationChannel(PKG, mContext.getUserId(), PKG, "id");
        assertTrue(createdChannel != null);
    }

    @Test
    public void testCreateNotificationChannels_NullChannelThrowsException() throws Exception {
        try {
            mBinderService.createNotificationChannels(PKG,
                    new ParceledListSlice(Arrays.asList((Object[])null)));
            fail("Exception should be thrown immediately.");
        } catch (NullPointerException e) {
            // pass
        }
    }

    @Test
    public void testCreateNotificationChannels_TwoChannels() throws Exception {
        final NotificationChannel channel1 =
                new NotificationChannel("id1", "name", IMPORTANCE_DEFAULT);
        final NotificationChannel channel2 =
                new NotificationChannel("id2", "name", IMPORTANCE_DEFAULT);
        mBinderService.createNotificationChannels(PKG,
                new ParceledListSlice(Arrays.asList(channel1, channel2)));
        assertTrue(mBinderService.getNotificationChannel(
                PKG, mContext.getUserId(), PKG, "id1") != null);
        assertTrue(mBinderService.getNotificationChannel(
                PKG, mContext.getUserId(), PKG, "id2") != null);
    }

    @Test
    public void testCreateNotificationChannels_SecondCreateDoesNotChangeImportance()
            throws Exception {
        final NotificationChannel channel =
                new NotificationChannel("id", "name", IMPORTANCE_DEFAULT);
        mBinderService.createNotificationChannels(PKG,
                new ParceledListSlice(Arrays.asList(channel)));

        // Recreating the channel doesn't throw, but ignores importance.
        final NotificationChannel dupeChannel =
                new NotificationChannel("id", "name", IMPORTANCE_HIGH);
        mBinderService.createNotificationChannels(PKG,
                new ParceledListSlice(Arrays.asList(dupeChannel)));
        final NotificationChannel createdChannel =
                mBinderService.getNotificationChannel(PKG, mContext.getUserId(), PKG, "id");
        assertEquals(IMPORTANCE_DEFAULT, createdChannel.getImportance());
    }

    @Test
    public void testCreateNotificationChannels_SecondCreateAllowedToDowngradeImportance()
            throws Exception {
        final NotificationChannel channel =
                new NotificationChannel("id", "name", IMPORTANCE_DEFAULT);
        mBinderService.createNotificationChannels(PKG,
                new ParceledListSlice(Arrays.asList(channel)));

        // Recreating with a lower importance is allowed to modify the channel.
        final NotificationChannel dupeChannel =
                new NotificationChannel("id", "name", NotificationManager.IMPORTANCE_LOW);
        mBinderService.createNotificationChannels(PKG,
                new ParceledListSlice(Arrays.asList(dupeChannel)));
        final NotificationChannel createdChannel =
                mBinderService.getNotificationChannel(PKG, mContext.getUserId(), PKG, "id");
        assertEquals(NotificationManager.IMPORTANCE_LOW, createdChannel.getImportance());
    }

    @Test
    public void testCreateNotificationChannels_CannotDowngradeImportanceIfAlreadyUpdated()
            throws Exception {
        final NotificationChannel channel =
                new NotificationChannel("id", "name", IMPORTANCE_DEFAULT);
        mBinderService.createNotificationChannels(PKG,
                new ParceledListSlice(Arrays.asList(channel)));

        // The user modifies importance directly, can no longer be changed by the app.
        final NotificationChannel updatedChannel =
                new NotificationChannel("id", "name", IMPORTANCE_HIGH);
        mBinderService.updateNotificationChannelForPackage(PKG, mUid, updatedChannel);

        // Recreating with a lower importance leaves channel unchanged.
        final NotificationChannel dupeChannel =
                new NotificationChannel("id", "name", NotificationManager.IMPORTANCE_LOW);
        mBinderService.createNotificationChannels(PKG,
                new ParceledListSlice(Arrays.asList(dupeChannel)));
        final NotificationChannel createdChannel =
                mBinderService.getNotificationChannel(PKG, mContext.getUserId(), PKG, "id");
        assertEquals(IMPORTANCE_HIGH, createdChannel.getImportance());
    }

    @Test
    public void testCreateNotificationChannels_IdenticalChannelsInListIgnoresSecond()
            throws Exception {
        final NotificationChannel channel1 =
                new NotificationChannel("id", "name", IMPORTANCE_DEFAULT);
        final NotificationChannel channel2 =
                new NotificationChannel("id", "name", IMPORTANCE_HIGH);
        mBinderService.createNotificationChannels(PKG,
                new ParceledListSlice(Arrays.asList(channel1, channel2)));
        final NotificationChannel createdChannel =
                mBinderService.getNotificationChannel(PKG, mContext.getUserId(), PKG, "id");
        assertEquals(IMPORTANCE_DEFAULT, createdChannel.getImportance());
    }

    @Test
    public void testBlockedNotifications_suspended() throws Exception {
        when(mPackageManager.isPackageSuspendedForUser(anyString(), anyInt())).thenReturn(true);

        NotificationChannel channel = new NotificationChannel("id", "name",
                IMPORTANCE_HIGH);
        NotificationRecord r = generateNotificationRecord(channel);

        // isBlocked is only used for user blocking, not app suspension
        assertFalse(mService.isBlocked(r, mUsageStats));
    }

    @Test
    public void testBlockedNotifications_blockedChannel() throws Exception {
        when(mPackageManager.isPackageSuspendedForUser(anyString(), anyInt())).thenReturn(false);

        NotificationChannel channel = new NotificationChannel("id", "name",
                NotificationManager.IMPORTANCE_NONE);
        NotificationRecord r = generateNotificationRecord(channel);
        assertTrue(mService.isBlocked(r, mUsageStats));
        verify(mUsageStats, times(1)).registerBlocked(eq(r));

        mBinderService.createNotificationChannels(
                PKG, new ParceledListSlice(Arrays.asList(channel)));
        final StatusBarNotification sbn = generateNotificationRecord(channel).sbn;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        waitForIdle();
        assertEquals(0, mBinderService.getActiveNotifications(sbn.getPackageName()).length);
    }

    @Test
    public void testEnqueuedBlockedNotifications_appBlockedChannelForegroundService()
            throws Exception {
        when(mPackageManager.isPackageSuspendedForUser(anyString(), anyInt())).thenReturn(false);

        NotificationChannel channel = new NotificationChannel("blocked", "name",
                NotificationManager.IMPORTANCE_NONE);
        mBinderService.createNotificationChannels(
                PKG, new ParceledListSlice(Arrays.asList(channel)));

        final StatusBarNotification sbn = generateNotificationRecord(channel).sbn;
        sbn.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        waitForIdle();
        assertEquals(1, mBinderService.getActiveNotifications(sbn.getPackageName()).length);
        assertEquals(IMPORTANCE_LOW,
                mService.getNotificationRecord(sbn.getKey()).getImportance());
        assertEquals(IMPORTANCE_LOW, mBinderService.getNotificationChannel(
                PKG, mContext.getUserId(), PKG, channel.getId()).getImportance());
    }

    @Test
    public void testEnqueuedBlockedNotifications_userBlockedChannelForegroundService()
            throws Exception {
        when(mPackageManager.isPackageSuspendedForUser(anyString(), anyInt())).thenReturn(false);

        NotificationChannel channel =
                new NotificationChannel("blockedbyuser", "name", IMPORTANCE_HIGH);
        mBinderService.createNotificationChannels(
                PKG, new ParceledListSlice(Arrays.asList(channel)));

        NotificationChannel update =
                new NotificationChannel("blockedbyuser", "name", IMPORTANCE_NONE);
        mBinderService.updateNotificationChannelForPackage(PKG, mUid, update);
        waitForIdle();
        assertEquals(IMPORTANCE_NONE, mBinderService.getNotificationChannel(
                PKG, mContext.getUserId(), PKG, channel.getId()).getImportance());

        StatusBarNotification sbn = generateNotificationRecord(channel).sbn;
        sbn.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        waitForIdle();
        // The first time a foreground service notification is shown, we allow the channel
        // to be updated to allow it to be seen.
        assertEquals(1, mBinderService.getActiveNotifications(sbn.getPackageName()).length);
        assertEquals(IMPORTANCE_LOW,
                mService.getNotificationRecord(sbn.getKey()).getImportance());
        assertEquals(IMPORTANCE_LOW, mBinderService.getNotificationChannel(
                PKG, mContext.getUserId(), PKG, channel.getId()).getImportance());
        mBinderService.cancelNotificationWithTag(PKG, "tag", sbn.getId(), sbn.getUserId());
        waitForIdle();

        update = new NotificationChannel("blockedbyuser", "name", IMPORTANCE_NONE);
        update.setFgServiceShown(true);
        mBinderService.updateNotificationChannelForPackage(PKG, mUid, update);
        waitForIdle();
        assertEquals(IMPORTANCE_NONE, mBinderService.getNotificationChannel(
                PKG, mContext.getUserId(), PKG, channel.getId()).getImportance());

        sbn = generateNotificationRecord(channel).sbn;
        sbn.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        waitForIdle();
        // The second time it is shown, we keep the user's preference.
        assertEquals(0, mBinderService.getActiveNotifications(sbn.getPackageName()).length);
        assertNull(mService.getNotificationRecord(sbn.getKey()));
        assertEquals(IMPORTANCE_NONE, mBinderService.getNotificationChannel(
                PKG, mContext.getUserId(), PKG, channel.getId()).getImportance());
    }

    @Test
    public void testBlockedNotifications_blockedChannelGroup() throws Exception {
        when(mPackageManager.isPackageSuspendedForUser(anyString(), anyInt())).thenReturn(false);
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mPreferencesHelper.isGroupBlocked(anyString(), anyInt(), anyString())).thenReturn(true);

        NotificationChannel channel = new NotificationChannel("id", "name",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setGroup("something");
        NotificationRecord r = generateNotificationRecord(channel);
        assertTrue(mService.isBlocked(r, mUsageStats));
        verify(mUsageStats, times(1)).registerBlocked(eq(r));
    }

    @Test
    public void testEnqueuedBlockedNotifications_blockedApp() throws Exception {
        when(mPackageManager.isPackageSuspendedForUser(anyString(), anyInt())).thenReturn(false);

        mBinderService.setNotificationsEnabledForPackage(PKG, mUid, false);

        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        waitForIdle();
        assertEquals(0, mBinderService.getActiveNotifications(sbn.getPackageName()).length);
    }

    @Test
    public void testEnqueuedBlockedNotifications_blockedAppForegroundService() throws Exception {
        when(mPackageManager.isPackageSuspendedForUser(anyString(), anyInt())).thenReturn(false);

        mBinderService.setNotificationsEnabledForPackage(PKG, mUid, false);

        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        sbn.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        waitForIdle();
        assertEquals(0, mBinderService.getActiveNotifications(sbn.getPackageName()).length);
        assertNull(mService.getNotificationRecord(sbn.getKey()));
    }

    /**
     * Confirm the system user on automotive devices can use car categories
     */
    @Test
    public void testEnqueuedRestrictedNotifications_asSystem() throws Exception {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, 0))
                .thenReturn(true);
        List<String> categories = Arrays.asList(Notification.CATEGORY_CAR_EMERGENCY,
                Notification.CATEGORY_CAR_WARNING,
                Notification.CATEGORY_CAR_INFORMATION);
        int id = 0;
        for (String category: categories) {
            final StatusBarNotification sbn =
                    generateNotificationRecord(mTestNotificationChannel, ++id, "", false).sbn;
            sbn.getNotification().category = category;
            mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                    sbn.getId(), sbn.getNotification(), sbn.getUserId());
        }
        waitForIdle();
        assertEquals(categories.size(), mBinderService.getActiveNotifications(PKG).length);
    }


    /**
     * Confirm restricted notification categories only apply to automotive.
     */
    @Test
    public void testEnqueuedRestrictedNotifications_notAutomotive() throws Exception {
        mService.isSystemUid = false;
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, 0))
                .thenReturn(false);
        List<String> categories = Arrays.asList(Notification.CATEGORY_CAR_EMERGENCY,
                Notification.CATEGORY_CAR_WARNING,
                Notification.CATEGORY_CAR_INFORMATION);
        int id = 0;
        for (String category: categories) {
            final StatusBarNotification sbn =
                    generateNotificationRecord(mTestNotificationChannel, ++id, "", false).sbn;
            sbn.getNotification().category = category;
            mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                    sbn.getId(), sbn.getNotification(), sbn.getUserId());
        }
        waitForIdle();
        assertEquals(categories.size(), mBinderService.getActiveNotifications(PKG).length);
    }

    /**
     * Confirm if a non-system user tries to use the car categories on a automotive device that
     * they will get a security exception
     */
    @Test
    public void testEnqueuedRestrictedNotifications_badUser() throws Exception {
        mService.isSystemUid = false;
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, 0))
                .thenReturn(true);
        List<String> categories = Arrays.asList(Notification.CATEGORY_CAR_EMERGENCY,
                Notification.CATEGORY_CAR_WARNING,
                Notification.CATEGORY_CAR_INFORMATION);
        for (String category: categories) {
            final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
            sbn.getNotification().category = category;
            try {
                mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                        sbn.getId(), sbn.getNotification(), sbn.getUserId());
                fail("Calls from non system apps should not allow use of restricted categories");
            } catch (SecurityException e) {
                // pass
            }
        }
        waitForIdle();
        assertEquals(0, mBinderService.getActiveNotifications(PKG).length);
    }

    @Test
    public void testBlockedNotifications_blockedByAssistant() throws Exception {
        when(mPackageManager.isPackageSuspendedForUser(anyString(), anyInt())).thenReturn(false);
        when(mAssistants.isSameUser(any(), anyInt())).thenReturn(true);

        NotificationChannel channel = new NotificationChannel("id", "name",
                NotificationManager.IMPORTANCE_HIGH);
        NotificationRecord r = generateNotificationRecord(channel);
        mService.addEnqueuedNotification(r);

        Bundle bundle = new Bundle();
        bundle.putInt(KEY_IMPORTANCE, IMPORTANCE_NONE);
        Adjustment adjustment = new Adjustment(
                r.sbn.getPackageName(), r.getKey(), bundle, "", r.getUser().getIdentifier());
        mBinderService.applyEnqueuedAdjustmentFromAssistant(null, adjustment);

        NotificationManagerService.PostNotificationRunnable runnable =
                mService.new PostNotificationRunnable(r.getKey());
        runnable.run();
        waitForIdle();

        verify(mUsageStats, never()).registerPostedByApp(any());
    }

    @Test
    public void testEnqueueNotificationWithTag_PopulatesGetActiveNotifications() throws Exception {
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag", 0,
                generateNotificationRecord(null).getNotification(), 0);
        waitForIdle();
        StatusBarNotification[] notifs = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifs.length);
        assertEquals(1, mService.getNotificationRecordCount());
    }

    @Test
    public void testCancelNotificationImmediatelyAfterEnqueue() throws Exception {
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag", 0,
                generateNotificationRecord(null).getNotification(), 0);
        mBinderService.cancelNotificationWithTag(PKG, "tag", 0, 0);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(PKG);
        assertEquals(0, notifs.length);
        assertEquals(0, mService.getNotificationRecordCount());
    }

    @Test
    public void testCancelNotificationWhilePostedAndEnqueued() throws Exception {
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag", 0,
                generateNotificationRecord(null).getNotification(), 0);
        waitForIdle();
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag", 0,
                generateNotificationRecord(null).getNotification(), 0);
        mBinderService.cancelNotificationWithTag(PKG, "tag", 0, 0);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(PKG);
        assertEquals(0, notifs.length);
        assertEquals(0, mService.getNotificationRecordCount());
        ArgumentCaptor<NotificationStats> captor = ArgumentCaptor.forClass(NotificationStats.class);
        verify(mListeners, times(1)).notifyRemovedLocked(any(), anyInt(), captor.capture());
        assertEquals(NotificationStats.DISMISSAL_OTHER, captor.getValue().getDismissalSurface());
    }

    @Test
    public void testCancelNotificationsFromListenerImmediatelyAfterEnqueue() throws Exception {
        NotificationRecord r = generateNotificationRecord(null);
        final StatusBarNotification sbn = r.sbn;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        mBinderService.cancelNotificationsFromListener(null, null);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(0, notifs.length);
        assertEquals(0, mService.getNotificationRecordCount());
    }

    @Test
    public void testCancelAllNotificationsImmediatelyAfterEnqueue() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        mBinderService.cancelAllNotifications(PKG, sbn.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(0, notifs.length);
        assertEquals(0, mService.getNotificationRecordCount());
    }

    @Test
    public void testUserInitiatedClearAll_noLeak() throws Exception {
        final NotificationRecord n = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                n.sbn.getId(), n.sbn.getNotification(), n.sbn.getUserId());
        waitForIdle();

        mService.mNotificationDelegate.onClearAll(mUid, Binder.getCallingPid(),
                n.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(n.sbn.getPackageName());
        assertEquals(0, notifs.length);
        assertEquals(0, mService.getNotificationRecordCount());
        ArgumentCaptor<NotificationStats> captor = ArgumentCaptor.forClass(NotificationStats.class);
        verify(mListeners, times(1)).notifyRemovedLocked(any(), anyInt(), captor.capture());
        assertEquals(NotificationStats.DISMISSAL_OTHER, captor.getValue().getDismissalSurface());
    }

    @Test
    public void testCancelAllNotificationsCancelsChildren() throws Exception {
        final NotificationRecord parent = generateNotificationRecord(
                mTestNotificationChannel, 1, "group1", true);
        final NotificationRecord child = generateNotificationRecord(
                mTestNotificationChannel, 2, "group1", false);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                parent.sbn.getId(), parent.sbn.getNotification(), parent.sbn.getUserId());
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                child.sbn.getId(), child.sbn.getNotification(), child.sbn.getUserId());
        waitForIdle();

        mBinderService.cancelAllNotifications(PKG, parent.sbn.getUserId());
        waitForIdle();
        assertEquals(0, mService.getNotificationRecordCount());
    }

    @Test
    public void testCancelAllNotificationsMultipleEnqueuedDoesNotCrash() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        for (int i = 0; i < 10; i++) {
            mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                    sbn.getId(), sbn.getNotification(), sbn.getUserId());
        }
        mBinderService.cancelAllNotifications(PKG, sbn.getUserId());
        waitForIdle();

        assertEquals(0, mService.getNotificationRecordCount());
    }

    @Test
    public void testCancelGroupSummaryMultipleEnqueuedChildrenDoesNotCrash() throws Exception {
        final NotificationRecord parent = generateNotificationRecord(
                mTestNotificationChannel, 1, "group1", true);
        final NotificationRecord parentAsChild = generateNotificationRecord(
                mTestNotificationChannel, 1, "group1", false);
        final NotificationRecord child = generateNotificationRecord(
                mTestNotificationChannel, 2, "group1", false);

        // fully post parent notification
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                parent.sbn.getId(), parent.sbn.getNotification(), parent.sbn.getUserId());
        waitForIdle();

        // enqueue the child several times
        for (int i = 0; i < 10; i++) {
            mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                    child.sbn.getId(), child.sbn.getNotification(), child.sbn.getUserId());
        }
        // make the parent a child, which will cancel the child notification
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                parentAsChild.sbn.getId(), parentAsChild.sbn.getNotification(),
                parentAsChild.sbn.getUserId());
        waitForIdle();

        assertEquals(0, mService.getNotificationRecordCount());
    }

    @Test
    public void testCancelAllNotifications_IgnoreForegroundService() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        sbn.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        mBinderService.cancelAllNotifications(PKG, sbn.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(1, notifs.length);
        assertEquals(1, mService.getNotificationRecordCount());
    }

    @Test
    public void testCancelAllNotifications_IgnoreOtherPackages() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        sbn.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        mBinderService.cancelAllNotifications("other_pkg_name", sbn.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(1, notifs.length);
        assertEquals(1, mService.getNotificationRecordCount());
    }

    @Test
    public void testCancelAllNotifications_NullPkgRemovesAll() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        mBinderService.cancelAllNotifications(null, sbn.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(0, notifs.length);
        assertEquals(0, mService.getNotificationRecordCount());
    }

    @Test
    public void testCancelAllNotifications_NullPkgIgnoresUserAllNotifications() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                sbn.getId(), sbn.getNotification(), UserHandle.USER_ALL);
        // Null pkg is how we signal a user switch.
        mBinderService.cancelAllNotifications(null, sbn.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(1, notifs.length);
        assertEquals(1, mService.getNotificationRecordCount());
    }

    @Test
    public void testAppInitiatedCancelAllNotifications_CancelsNoClearFlag() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        sbn.getNotification().flags |= Notification.FLAG_NO_CLEAR;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        mBinderService.cancelAllNotifications(PKG, sbn.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(0, notifs.length);
    }

    @Test
    public void testCancelAllNotifications_CancelsNoClearFlag() throws Exception {
        final NotificationRecord notif = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);
        notif.getNotification().flags |= Notification.FLAG_NO_CLEAR;
        mService.addNotification(notif);
        mService.cancelAllNotificationsInt(mUid, 0, PKG, null, 0, 0, true,
                notif.getUserId(), 0, null);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(notif.sbn.getPackageName());
        assertEquals(0, notifs.length);
    }

    @Test
    public void testUserInitiatedCancelAllOnClearAll_NoClearFlag() throws Exception {
        final NotificationRecord notif = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);
        notif.getNotification().flags |= Notification.FLAG_NO_CLEAR;
        mService.addNotification(notif);

        mService.mNotificationDelegate.onClearAll(mUid, Binder.getCallingPid(),
                notif.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(notif.sbn.getPackageName());
        assertEquals(1, notifs.length);
    }

    @Test
    public void testCancelAllCancelNotificationsFromListener_NoClearFlag() throws Exception {
        final NotificationRecord parent = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);
        final NotificationRecord child = generateNotificationRecord(
                mTestNotificationChannel, 2, "group", false);
        final NotificationRecord child2 = generateNotificationRecord(
                mTestNotificationChannel, 3, "group", false);
        child2.getNotification().flags |= Notification.FLAG_NO_CLEAR;
        final NotificationRecord newGroup = generateNotificationRecord(
                mTestNotificationChannel, 4, "group2", false);
        mService.addNotification(parent);
        mService.addNotification(child);
        mService.addNotification(child2);
        mService.addNotification(newGroup);
        mService.getBinderService().cancelNotificationsFromListener(null, null);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(parent.sbn.getPackageName());
        assertEquals(1, notifs.length);
    }

    @Test
    public void testUserInitiatedCancelAllWithGroup_NoClearFlag() throws Exception {
        final NotificationRecord parent = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);
        final NotificationRecord child = generateNotificationRecord(
                mTestNotificationChannel, 2, "group", false);
        final NotificationRecord child2 = generateNotificationRecord(
                mTestNotificationChannel, 3, "group", false);
        child2.getNotification().flags |= Notification.FLAG_NO_CLEAR;
        final NotificationRecord newGroup = generateNotificationRecord(
                mTestNotificationChannel, 4, "group2", false);
        mService.addNotification(parent);
        mService.addNotification(child);
        mService.addNotification(child2);
        mService.addNotification(newGroup);
        mService.mNotificationDelegate.onClearAll(mUid, Binder.getCallingPid(),
                parent.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(parent.sbn.getPackageName());
        assertEquals(1, notifs.length);
    }

    @Test
    public void testRemoveForegroundServiceFlag_ImmediatelyAfterEnqueue() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        sbn.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, null,
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        mInternalService.removeForegroundServiceFlagFromNotification(PKG, sbn.getId(),
                sbn.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(0, notifs[0].getNotification().flags & FLAG_FOREGROUND_SERVICE);
    }

    @Test
    public void testCancelAfterSecondEnqueueDoesNotSpecifyForegroundFlag() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        sbn.getNotification().flags =
                Notification.FLAG_ONGOING_EVENT | FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        sbn.getNotification().flags = Notification.FLAG_ONGOING_EVENT;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        mBinderService.cancelNotificationWithTag(PKG, "tag", sbn.getId(), sbn.getUserId());
        waitForIdle();
        assertEquals(0, mBinderService.getActiveNotifications(sbn.getPackageName()).length);
        assertEquals(0, mService.getNotificationRecordCount());
    }

    @Test
    public void testCancelAllCancelNotificationsFromListener_ForegroundServiceFlag()
            throws Exception {
        final NotificationRecord parent = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);
        final NotificationRecord child = generateNotificationRecord(
                mTestNotificationChannel, 2, "group", false);
        final NotificationRecord child2 = generateNotificationRecord(
                mTestNotificationChannel, 3, "group", false);
        child2.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        final NotificationRecord newGroup = generateNotificationRecord(
                mTestNotificationChannel, 4, "group2", false);
        mService.addNotification(parent);
        mService.addNotification(child);
        mService.addNotification(child2);
        mService.addNotification(newGroup);
        mService.getBinderService().cancelNotificationsFromListener(null, null);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(parent.sbn.getPackageName());
        assertEquals(0, notifs.length);
    }

    @Test
    public void testCancelAllCancelNotificationsFromListener_ForegroundServiceFlagWithParameter()
            throws Exception {
        final NotificationRecord parent = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);
        final NotificationRecord child = generateNotificationRecord(
                mTestNotificationChannel, 2, "group", false);
        final NotificationRecord child2 = generateNotificationRecord(
                mTestNotificationChannel, 3, "group", false);
        child2.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        final NotificationRecord newGroup = generateNotificationRecord(
                mTestNotificationChannel, 4, "group2", false);
        mService.addNotification(parent);
        mService.addNotification(child);
        mService.addNotification(child2);
        mService.addNotification(newGroup);
        String[] keys = {parent.sbn.getKey(), child.sbn.getKey(),
                child2.sbn.getKey(), newGroup.sbn.getKey()};
        mService.getBinderService().cancelNotificationsFromListener(null, keys);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(parent.sbn.getPackageName());
        assertEquals(1, notifs.length);
    }

    @Test
    public void testUserInitiatedCancelAllWithGroup_ForegroundServiceFlag() throws Exception {
        final NotificationRecord parent = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);
        final NotificationRecord child = generateNotificationRecord(
                mTestNotificationChannel, 2, "group", false);
        final NotificationRecord child2 = generateNotificationRecord(
                mTestNotificationChannel, 3, "group", false);
        child2.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        final NotificationRecord newGroup = generateNotificationRecord(
                mTestNotificationChannel, 4, "group2", false);
        mService.addNotification(parent);
        mService.addNotification(child);
        mService.addNotification(child2);
        mService.addNotification(newGroup);
        mService.mNotificationDelegate.onClearAll(mUid, Binder.getCallingPid(),
                parent.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(parent.sbn.getPackageName());
        assertEquals(0, notifs.length);
    }

    @Test
    public void testFindGroupNotificationsLocked() throws Exception {
        // make sure the same notification can be found in both lists and returned
        final NotificationRecord group1 = generateNotificationRecord(
                mTestNotificationChannel, 1, "group1", true);
        mService.addEnqueuedNotification(group1);
        mService.addNotification(group1);

        // should not be returned
        final NotificationRecord group2 = generateNotificationRecord(
                mTestNotificationChannel, 2, "group2", true);
        mBinderService.enqueueNotificationWithTag(PKG, PKG, null,
                group2.sbn.getId(), group2.sbn.getNotification(), group2.sbn.getUserId());
        waitForIdle();

        // should not be returned
        final NotificationRecord nonGroup = generateNotificationRecord(
                mTestNotificationChannel, 3, null, false);
        mBinderService.enqueueNotificationWithTag(PKG, PKG, null,
                nonGroup.sbn.getId(), nonGroup.sbn.getNotification(), nonGroup.sbn.getUserId());
        waitForIdle();

        // same group, child, should be returned
        final NotificationRecord group1Child = generateNotificationRecord(
                mTestNotificationChannel, 4, "group1", false);
        mBinderService.enqueueNotificationWithTag(PKG, PKG, null, group1Child.sbn.getId(),
                group1Child.sbn.getNotification(), group1Child.sbn.getUserId());
        waitForIdle();

        List<NotificationRecord> inGroup1 =
                mService.findGroupNotificationsLocked(PKG, group1.getGroupKey(),
                        group1.sbn.getUserId());
        assertEquals(3, inGroup1.size());
        for (NotificationRecord record : inGroup1) {
            assertTrue(record.getGroupKey().equals(group1.getGroupKey()));
            assertTrue(record.sbn.getId() == 1 || record.sbn.getId() == 4);
        }
    }

    @Test
    public void testCancelAllNotifications_CancelsNoClearFlagOnGoing() throws Exception {
        final NotificationRecord notif = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);
        notif.getNotification().flags |= Notification.FLAG_NO_CLEAR;
        mService.addNotification(notif);
        mService.cancelAllNotificationsInt(mUid, 0, PKG, null, 0,
                Notification.FLAG_ONGOING_EVENT, true, notif.getUserId(), 0, null);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(notif.sbn.getPackageName());
        assertEquals(0, notifs.length);
    }

    @Test
    public void testCancelAllCancelNotificationsFromListener_NoClearFlagWithParameter()
            throws Exception {
        final NotificationRecord parent = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);
        final NotificationRecord child = generateNotificationRecord(
                mTestNotificationChannel, 2, "group", false);
        final NotificationRecord child2 = generateNotificationRecord(
                mTestNotificationChannel, 3, "group", false);
        child2.getNotification().flags |= Notification.FLAG_NO_CLEAR;
        final NotificationRecord newGroup = generateNotificationRecord(
                mTestNotificationChannel, 4, "group2", false);
        mService.addNotification(parent);
        mService.addNotification(child);
        mService.addNotification(child2);
        mService.addNotification(newGroup);
        String[] keys = {parent.sbn.getKey(), child.sbn.getKey(),
                child2.sbn.getKey(), newGroup.sbn.getKey()};
        mService.getBinderService().cancelNotificationsFromListener(null, keys);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(parent.sbn.getPackageName());
        assertEquals(0, notifs.length);
    }

    @Test
    public void testAppInitiatedCancelAllNotifications_CancelsOnGoingFlag() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).sbn;
        sbn.getNotification().flags |= Notification.FLAG_ONGOING_EVENT;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        mBinderService.cancelAllNotifications(PKG, sbn.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(0, notifs.length);
    }

    @Test
    public void testCancelAllNotifications_CancelsOnGoingFlag() throws Exception {
        final NotificationRecord notif = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);
        notif.getNotification().flags |= Notification.FLAG_ONGOING_EVENT;
        mService.addNotification(notif);
        mService.cancelAllNotificationsInt(mUid, 0, PKG, null, 0, 0, true,
                notif.getUserId(), 0, null);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(notif.sbn.getPackageName());
        assertEquals(0, notifs.length);
    }

    @Test
    public void testUserInitiatedCancelAllOnClearAll_OnGoingFlag() throws Exception {
        final NotificationRecord notif = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);
        notif.getNotification().flags |= Notification.FLAG_ONGOING_EVENT;
        mService.addNotification(notif);

        mService.mNotificationDelegate.onClearAll(mUid, Binder.getCallingPid(),
                notif.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(notif.sbn.getPackageName());
        assertEquals(1, notifs.length);
    }

    @Test
    public void testCancelAllCancelNotificationsFromListener_OnGoingFlag() throws Exception {
        final NotificationRecord parent = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);
        final NotificationRecord child = generateNotificationRecord(
                mTestNotificationChannel, 2, "group", false);
        final NotificationRecord child2 = generateNotificationRecord(
                mTestNotificationChannel, 3, "group", false);
        child2.getNotification().flags |= Notification.FLAG_ONGOING_EVENT;
        final NotificationRecord newGroup = generateNotificationRecord(
                mTestNotificationChannel, 4, "group2", false);
        mService.addNotification(parent);
        mService.addNotification(child);
        mService.addNotification(child2);
        mService.addNotification(newGroup);
        mService.getBinderService().cancelNotificationsFromListener(null, null);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(parent.sbn.getPackageName());
        assertEquals(1, notifs.length);
    }

    @Test
    public void testCancelAllCancelNotificationsFromListener_OnGoingFlagWithParameter()
            throws Exception {
        final NotificationRecord parent = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);
        final NotificationRecord child = generateNotificationRecord(
                mTestNotificationChannel, 2, "group", false);
        final NotificationRecord child2 = generateNotificationRecord(
                mTestNotificationChannel, 3, "group", false);
        child2.getNotification().flags |= Notification.FLAG_ONGOING_EVENT;
        final NotificationRecord newGroup = generateNotificationRecord(
                mTestNotificationChannel, 4, "group2", false);
        mService.addNotification(parent);
        mService.addNotification(child);
        mService.addNotification(child2);
        mService.addNotification(newGroup);
        String[] keys = {parent.sbn.getKey(), child.sbn.getKey(),
                child2.sbn.getKey(), newGroup.sbn.getKey()};
        mService.getBinderService().cancelNotificationsFromListener(null, keys);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(parent.sbn.getPackageName());
        assertEquals(0, notifs.length);
    }

    @Test
    public void testUserInitiatedCancelAllWithGroup_OnGoingFlag() throws Exception {
        final NotificationRecord parent = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);
        final NotificationRecord child = generateNotificationRecord(
                mTestNotificationChannel, 2, "group", false);
        final NotificationRecord child2 = generateNotificationRecord(
                mTestNotificationChannel, 3, "group", false);
        child2.getNotification().flags |= Notification.FLAG_ONGOING_EVENT;
        final NotificationRecord newGroup = generateNotificationRecord(
                mTestNotificationChannel, 4, "group2", false);
        mService.addNotification(parent);
        mService.addNotification(child);
        mService.addNotification(child2);
        mService.addNotification(newGroup);
        mService.mNotificationDelegate.onClearAll(mUid, Binder.getCallingPid(),
                parent.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(parent.sbn.getPackageName());
        assertEquals(1, notifs.length);
    }

    @Test
    public void testTvExtenderChannelOverride_onTv() throws Exception {
        mService.setIsTelevision(true);
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mPreferencesHelper.getNotificationChannel(
                anyString(), anyInt(), eq("foo"), anyBoolean())).thenReturn(
                        new NotificationChannel("foo", "foo", IMPORTANCE_HIGH));

        Notification.TvExtender tv = new Notification.TvExtender().setChannelId("foo");
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag", 0,
                generateNotificationRecord(null, tv).getNotification(), 0);
        verify(mPreferencesHelper, times(1)).getNotificationChannel(
                anyString(), anyInt(), eq("foo"), anyBoolean());
    }

    @Test
    public void testTvExtenderChannelOverride_notOnTv() throws Exception {
        mService.setIsTelevision(false);
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mPreferencesHelper.getNotificationChannel(
                anyString(), anyInt(), anyString(), anyBoolean())).thenReturn(
                mTestNotificationChannel);

        Notification.TvExtender tv = new Notification.TvExtender().setChannelId("foo");
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag", 0,
                generateNotificationRecord(null, tv).getNotification(), 0);
        verify(mPreferencesHelper, times(1)).getNotificationChannel(
                anyString(), anyInt(), eq(mTestNotificationChannel.getId()), anyBoolean());
    }

    @Test
    public void testUpdateAppNotifyCreatorBlock() throws Exception {
        mService.setPreferencesHelper(mPreferencesHelper);

        mBinderService.setNotificationsEnabledForPackage(PKG, 0, false);
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, times(1)).sendBroadcastAsUser(captor.capture(), any(), eq(null));

        assertEquals(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED,
                captor.getValue().getAction());
        assertEquals(PKG, captor.getValue().getPackage());
        assertTrue(captor.getValue().getBooleanExtra(EXTRA_BLOCKED_STATE, false));
    }

    @Test
    public void testUpdateAppNotifyCreatorUnblock() throws Exception {
        mService.setPreferencesHelper(mPreferencesHelper);

        mBinderService.setNotificationsEnabledForPackage(PKG, 0, true);
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, times(1)).sendBroadcastAsUser(captor.capture(), any(), eq(null));

        assertEquals(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED,
                captor.getValue().getAction());
        assertEquals(PKG, captor.getValue().getPackage());
        assertFalse(captor.getValue().getBooleanExtra(EXTRA_BLOCKED_STATE, true));
    }

    @Test
    public void testUpdateChannelNotifyCreatorBlock() throws Exception {
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mPreferencesHelper.getNotificationChannel(eq(PKG), anyInt(),
                eq(mTestNotificationChannel.getId()), anyBoolean()))
                .thenReturn(mTestNotificationChannel);

        NotificationChannel updatedChannel =
                new NotificationChannel(mTestNotificationChannel.getId(),
                        mTestNotificationChannel.getName(), IMPORTANCE_NONE);

        mBinderService.updateNotificationChannelForPackage(PKG, 0, updatedChannel);
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, times(1)).sendBroadcastAsUser(captor.capture(), any(), eq(null));

        assertEquals(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED,
                captor.getValue().getAction());
        assertEquals(PKG, captor.getValue().getPackage());
        assertEquals(mTestNotificationChannel.getId(), captor.getValue().getStringExtra(
                        NotificationManager.EXTRA_NOTIFICATION_CHANNEL_ID));
        assertTrue(captor.getValue().getBooleanExtra(EXTRA_BLOCKED_STATE, false));
    }

    @Test
    public void testUpdateChannelNotifyCreatorUnblock() throws Exception {
        NotificationChannel existingChannel =
                new NotificationChannel(mTestNotificationChannel.getId(),
                        mTestNotificationChannel.getName(), IMPORTANCE_NONE);
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mPreferencesHelper.getNotificationChannel(eq(PKG), anyInt(),
                eq(mTestNotificationChannel.getId()), anyBoolean()))
                .thenReturn(existingChannel);

        mBinderService.updateNotificationChannelForPackage(PKG, 0, mTestNotificationChannel);
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, times(1)).sendBroadcastAsUser(captor.capture(), any(), eq(null));

        assertEquals(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED,
                captor.getValue().getAction());
        assertEquals(PKG, captor.getValue().getPackage());
        assertEquals(mTestNotificationChannel.getId(), captor.getValue().getStringExtra(
                NotificationManager.EXTRA_NOTIFICATION_CHANNEL_ID));
        assertFalse(captor.getValue().getBooleanExtra(EXTRA_BLOCKED_STATE, false));
    }

    @Test
    public void testUpdateChannelNoNotifyCreatorOtherChanges() throws Exception {
        NotificationChannel existingChannel =
                new NotificationChannel(mTestNotificationChannel.getId(),
                        mTestNotificationChannel.getName(), IMPORTANCE_MAX);
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mPreferencesHelper.getNotificationChannel(eq(PKG), anyInt(),
                eq(mTestNotificationChannel.getId()), anyBoolean()))
                .thenReturn(existingChannel);

        mBinderService.updateNotificationChannelForPackage(PKG, 0, mTestNotificationChannel);
        verify(mContext, never()).sendBroadcastAsUser(any(), any(), eq(null));
    }

    @Test
    public void testUpdateGroupNotifyCreatorBlock() throws Exception {
        NotificationChannelGroup existing = new NotificationChannelGroup("id", "name");
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mPreferencesHelper.getNotificationChannelGroup(eq(existing.getId()), eq(PKG), anyInt()))
                .thenReturn(existing);

        NotificationChannelGroup updated = new NotificationChannelGroup("id", "name");
        updated.setBlocked(true);

        mBinderService.updateNotificationChannelGroupForPackage(PKG, 0, updated);
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, times(1)).sendBroadcastAsUser(captor.capture(), any(), eq(null));

        assertEquals(NotificationManager.ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED,
                captor.getValue().getAction());
        assertEquals(PKG, captor.getValue().getPackage());
        assertEquals(existing.getId(), captor.getValue().getStringExtra(
                NotificationManager.EXTRA_NOTIFICATION_CHANNEL_GROUP_ID));
        assertTrue(captor.getValue().getBooleanExtra(EXTRA_BLOCKED_STATE, false));
    }

    @Test
    public void testUpdateGroupNotifyCreatorUnblock() throws Exception {
        NotificationChannelGroup existing = new NotificationChannelGroup("id", "name");
        existing.setBlocked(true);
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mPreferencesHelper.getNotificationChannelGroup(eq(existing.getId()), eq(PKG), anyInt()))
                .thenReturn(existing);

        mBinderService.updateNotificationChannelGroupForPackage(
                PKG, 0, new NotificationChannelGroup("id", "name"));
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, times(1)).sendBroadcastAsUser(captor.capture(), any(), eq(null));

        assertEquals(NotificationManager.ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED,
                captor.getValue().getAction());
        assertEquals(PKG, captor.getValue().getPackage());
        assertEquals(existing.getId(), captor.getValue().getStringExtra(
                NotificationManager.EXTRA_NOTIFICATION_CHANNEL_GROUP_ID));
        assertFalse(captor.getValue().getBooleanExtra(EXTRA_BLOCKED_STATE, false));
    }

    @Test
    public void testUpdateGroupNoNotifyCreatorOtherChanges() throws Exception {
        NotificationChannelGroup existing = new NotificationChannelGroup("id", "name");
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mPreferencesHelper.getNotificationChannelGroup(eq(existing.getId()), eq(PKG), anyInt()))
                .thenReturn(existing);

        mBinderService.updateNotificationChannelGroupForPackage(
                PKG, 0, new NotificationChannelGroup("id", "new name"));
        verify(mContext, never()).sendBroadcastAsUser(any(), any(), eq(null));
    }

    @Test
    public void testCreateChannelNotifyListener() throws Exception {
        List<String> associations = new ArrayList<>();
        associations.add("a");
        when(mCompanionMgr.getAssociations(PKG, mUid)).thenReturn(associations);
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mPreferencesHelper.getNotificationChannel(eq(PKG), anyInt(),
                eq(mTestNotificationChannel.getId()), anyBoolean()))
                .thenReturn(mTestNotificationChannel);
        NotificationChannel channel2 = new NotificationChannel("a", "b", IMPORTANCE_LOW);
        when(mPreferencesHelper.getNotificationChannel(eq(PKG), anyInt(),
                eq(channel2.getId()), anyBoolean()))
                .thenReturn(channel2);
        when(mPreferencesHelper.createNotificationChannel(eq(PKG), anyInt(),
                eq(channel2), anyBoolean(), anyBoolean()))
                .thenReturn(true);

        reset(mListeners);
        mBinderService.createNotificationChannels(PKG,
                new ParceledListSlice(Arrays.asList(mTestNotificationChannel, channel2)));
        verify(mListeners, never()).notifyNotificationChannelChanged(eq(PKG),
                eq(Process.myUserHandle()), eq(mTestNotificationChannel),
                eq(NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_ADDED));
        verify(mListeners, times(1)).notifyNotificationChannelChanged(eq(PKG),
                eq(Process.myUserHandle()), eq(channel2),
                eq(NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_ADDED));
    }

    @Test
    public void testCreateChannelGroupNotifyListener() throws Exception {
        List<String> associations = new ArrayList<>();
        associations.add("a");
        when(mCompanionMgr.getAssociations(PKG, mUid)).thenReturn(associations);
        mService.setPreferencesHelper(mPreferencesHelper);
        NotificationChannelGroup group1 = new NotificationChannelGroup("a", "b");
        NotificationChannelGroup group2 = new NotificationChannelGroup("n", "m");

        reset(mListeners);
        mBinderService.createNotificationChannelGroups(PKG,
                new ParceledListSlice(Arrays.asList(group1, group2)));
        verify(mListeners, times(1)).notifyNotificationChannelGroupChanged(eq(PKG),
                eq(Process.myUserHandle()), eq(group1),
                eq(NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_ADDED));
        verify(mListeners, times(1)).notifyNotificationChannelGroupChanged(eq(PKG),
                eq(Process.myUserHandle()), eq(group2),
                eq(NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_ADDED));
    }

    @Test
    public void testUpdateChannelNotifyListener() throws Exception {
        List<String> associations = new ArrayList<>();
        associations.add("a");
        when(mCompanionMgr.getAssociations(PKG, mUid)).thenReturn(associations);
        mService.setPreferencesHelper(mPreferencesHelper);
        mTestNotificationChannel.setLightColor(Color.CYAN);
        when(mPreferencesHelper.getNotificationChannel(eq(PKG), anyInt(),
                eq(mTestNotificationChannel.getId()), anyBoolean()))
                .thenReturn(mTestNotificationChannel);

        reset(mListeners);
        mBinderService.updateNotificationChannelForPackage(PKG, 0, mTestNotificationChannel);
        verify(mListeners, times(1)).notifyNotificationChannelChanged(eq(PKG),
                eq(Process.myUserHandle()), eq(mTestNotificationChannel),
                eq(NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_UPDATED));
    }

    @Test
    public void testDeleteChannelNotifyListener() throws Exception {
        List<String> associations = new ArrayList<>();
        associations.add("a");
        when(mCompanionMgr.getAssociations(PKG, mUid)).thenReturn(associations);
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mPreferencesHelper.getNotificationChannel(eq(PKG), anyInt(),
                eq(mTestNotificationChannel.getId()), anyBoolean()))
                .thenReturn(mTestNotificationChannel);
        reset(mListeners);
        mBinderService.deleteNotificationChannel(PKG, mTestNotificationChannel.getId());
        verify(mListeners, times(1)).notifyNotificationChannelChanged(eq(PKG),
                eq(Process.myUserHandle()), eq(mTestNotificationChannel),
                eq(NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_DELETED));
    }

    @Test
    public void testDeleteChannelGroupNotifyListener() throws Exception {
        List<String> associations = new ArrayList<>();
        associations.add("a");
        when(mCompanionMgr.getAssociations(PKG, mUid)).thenReturn(associations);
        NotificationChannelGroup ncg = new NotificationChannelGroup("a", "b/c");
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mPreferencesHelper.getNotificationChannelGroup(eq(ncg.getId()), eq(PKG), anyInt()))
                .thenReturn(ncg);
        reset(mListeners);
        mBinderService.deleteNotificationChannelGroup(PKG, ncg.getId());
        verify(mListeners, times(1)).notifyNotificationChannelGroupChanged(eq(PKG),
                eq(Process.myUserHandle()), eq(ncg),
                eq(NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_DELETED));
    }

    @Test
    public void testUpdateNotificationChannelFromPrivilegedListener_success() throws Exception {
        mService.setPreferencesHelper(mPreferencesHelper);
        List<String> associations = new ArrayList<>();
        associations.add("a");
        when(mCompanionMgr.getAssociations(PKG, mUid)).thenReturn(associations);
        when(mPreferencesHelper.getNotificationChannel(eq(PKG), anyInt(),
                eq(mTestNotificationChannel.getId()), anyBoolean()))
                .thenReturn(mTestNotificationChannel);

        mBinderService.updateNotificationChannelFromPrivilegedListener(
                null, PKG, Process.myUserHandle(), mTestNotificationChannel);

        verify(mPreferencesHelper, times(1)).updateNotificationChannel(
                anyString(), anyInt(), any(), anyBoolean());

        verify(mListeners, never()).notifyNotificationChannelChanged(eq(PKG),
                eq(Process.myUserHandle()), eq(mTestNotificationChannel),
                eq(NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_UPDATED));
    }

    @Test
    public void testUpdateNotificationChannelFromPrivilegedListener_noAccess() throws Exception {
        mService.setPreferencesHelper(mPreferencesHelper);
        List<String> associations = new ArrayList<>();
        when(mCompanionMgr.getAssociations(PKG, mUid)).thenReturn(associations);

        try {
            mBinderService.updateNotificationChannelFromPrivilegedListener(
                    null, PKG, Process.myUserHandle(), mTestNotificationChannel);
            fail("listeners that don't have a companion device shouldn't be able to call this");
        } catch (SecurityException e) {
            // pass
        }

        verify(mPreferencesHelper, never()).updateNotificationChannel(
                anyString(), anyInt(), any(), anyBoolean());

        verify(mListeners, never()).notifyNotificationChannelChanged(eq(PKG),
                eq(Process.myUserHandle()), eq(mTestNotificationChannel),
                eq(NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_UPDATED));
    }

    @Test
    public void testUpdateNotificationChannelFromPrivilegedListener_badUser() throws Exception {
        mService.setPreferencesHelper(mPreferencesHelper);
        List<String> associations = new ArrayList<>();
        associations.add("a");
        when(mCompanionMgr.getAssociations(PKG, mUid)).thenReturn(associations);
        mListener = mock(ManagedServices.ManagedServiceInfo.class);
        mListener.component = new ComponentName(PKG, PKG);
        when(mListener.enabledAndUserMatches(anyInt())).thenReturn(false);
        when(mListeners.checkServiceTokenLocked(any())).thenReturn(mListener);

        try {
            mBinderService.updateNotificationChannelFromPrivilegedListener(
                    null, PKG, UserHandle.ALL, mTestNotificationChannel);
            fail("incorrectly allowed a change to a user listener cannot see");
        } catch (SecurityException e) {
            // pass
        }

        verify(mPreferencesHelper, never()).updateNotificationChannel(
                anyString(), anyInt(), any(), anyBoolean());

        verify(mListeners, never()).notifyNotificationChannelChanged(eq(PKG),
                eq(Process.myUserHandle()), eq(mTestNotificationChannel),
                eq(NotificationListenerService.NOTIFICATION_CHANNEL_OR_GROUP_UPDATED));
    }

    @Test
    public void testGetNotificationChannelFromPrivilegedListener_cdm_success() throws Exception {
        mService.setPreferencesHelper(mPreferencesHelper);
        List<String> associations = new ArrayList<>();
        associations.add("a");
        when(mCompanionMgr.getAssociations(PKG, mUid)).thenReturn(associations);

        mBinderService.getNotificationChannelsFromPrivilegedListener(
                null, PKG, Process.myUserHandle());

        verify(mPreferencesHelper, times(1)).getNotificationChannels(
                anyString(), anyInt(), anyBoolean());
    }

    @Test
    public void testGetNotificationChannelFromPrivilegedListener_cdm_noAccess() throws Exception {
        mService.setPreferencesHelper(mPreferencesHelper);
        List<String> associations = new ArrayList<>();
        when(mCompanionMgr.getAssociations(PKG, mUid)).thenReturn(associations);

        try {
            mBinderService.getNotificationChannelsFromPrivilegedListener(
                    null, PKG, Process.myUserHandle());
            fail("listeners that don't have a companion device shouldn't be able to call this");
        } catch (SecurityException e) {
            // pass
        }

        verify(mPreferencesHelper, never()).getNotificationChannels(
                anyString(), anyInt(), anyBoolean());
    }

    @Test
    public void testGetNotificationChannelFromPrivilegedListener_assistant_success()
            throws Exception {
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mCompanionMgr.getAssociations(PKG, mUid)).thenReturn(new ArrayList<>());
        when(mAssistants.isServiceTokenValidLocked(any())).thenReturn(true);

        mBinderService.getNotificationChannelsFromPrivilegedListener(
                null, PKG, Process.myUserHandle());

        verify(mPreferencesHelper, times(1)).getNotificationChannels(
                anyString(), anyInt(), anyBoolean());
    }

    @Test
    public void testGetNotificationChannelFromPrivilegedListener_assistant_noAccess()
            throws Exception {
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mCompanionMgr.getAssociations(PKG, mUid)).thenReturn(new ArrayList<>());
        when(mAssistants.isServiceTokenValidLocked(any())).thenReturn(false);

        try {
            mBinderService.getNotificationChannelsFromPrivilegedListener(
                    null, PKG, Process.myUserHandle());
            fail("listeners that don't have a companion device shouldn't be able to call this");
        } catch (SecurityException e) {
            // pass
        }

        verify(mPreferencesHelper, never()).getNotificationChannels(
                anyString(), anyInt(), anyBoolean());
    }

    @Test
    public void testGetNotificationChannelFromPrivilegedListener_badUser() throws Exception {
        mService.setPreferencesHelper(mPreferencesHelper);
        List<String> associations = new ArrayList<>();
        associations.add("a");
        when(mCompanionMgr.getAssociations(PKG, mUid)).thenReturn(associations);
        mListener = mock(ManagedServices.ManagedServiceInfo.class);
        when(mListener.enabledAndUserMatches(anyInt())).thenReturn(false);
        when(mListeners.checkServiceTokenLocked(any())).thenReturn(mListener);

        try {
            mBinderService.getNotificationChannelsFromPrivilegedListener(
                    null, PKG, Process.myUserHandle());
            fail("listener getting channels from a user they cannot see");
        } catch (SecurityException e) {
            // pass
        }

        verify(mPreferencesHelper, never()).getNotificationChannels(
                anyString(), anyInt(), anyBoolean());
    }

    @Test
    public void testGetNotificationChannelGroupsFromPrivilegedListener_success() throws Exception {
        mService.setPreferencesHelper(mPreferencesHelper);
        List<String> associations = new ArrayList<>();
        associations.add("a");
        when(mCompanionMgr.getAssociations(PKG, mUid)).thenReturn(associations);

        mBinderService.getNotificationChannelGroupsFromPrivilegedListener(
                null, PKG, Process.myUserHandle());

        verify(mPreferencesHelper, times(1)).getNotificationChannelGroups(anyString(), anyInt());
    }

    @Test
    public void testGetNotificationChannelGroupsFromPrivilegedListener_noAccess() throws Exception {
        mService.setPreferencesHelper(mPreferencesHelper);
        List<String> associations = new ArrayList<>();
        when(mCompanionMgr.getAssociations(PKG, mUid)).thenReturn(associations);

        try {
            mBinderService.getNotificationChannelGroupsFromPrivilegedListener(
                    null, PKG, Process.myUserHandle());
            fail("listeners that don't have a companion device shouldn't be able to call this");
        } catch (SecurityException e) {
            // pass
        }

        verify(mPreferencesHelper, never()).getNotificationChannelGroups(anyString(), anyInt());
    }

    @Test
    public void testGetNotificationChannelGroupsFromPrivilegedListener_badUser() throws Exception {
        mService.setPreferencesHelper(mPreferencesHelper);
        List<String> associations = new ArrayList<>();
        when(mCompanionMgr.getAssociations(PKG, mUid)).thenReturn(associations);
        mListener = mock(ManagedServices.ManagedServiceInfo.class);
        when(mListener.enabledAndUserMatches(anyInt())).thenReturn(false);
        when(mListeners.checkServiceTokenLocked(any())).thenReturn(mListener);
        try {
            mBinderService.getNotificationChannelGroupsFromPrivilegedListener(
                    null, PKG, Process.myUserHandle());
            fail("listeners that don't have a companion device shouldn't be able to call this");
        } catch (SecurityException e) {
            // pass
        }

        verify(mPreferencesHelper, never()).getNotificationChannelGroups(anyString(), anyInt());
    }

    @Test
    public void testHasCompanionDevice_failure() throws Exception {
        when(mCompanionMgr.getAssociations(anyString(), anyInt())).thenThrow(
                new IllegalArgumentException());
        mService.hasCompanionDevice(mListener);
    }

    @Test
    public void testHasCompanionDevice_noService() {
        mService = new TestableNotificationManagerService(mContext);

        assertFalse(mService.hasCompanionDevice(mListener));
    }

    @Test
    public void testSnoozeRunnable_snoozeNonGrouped() throws Exception {
        final NotificationRecord nonGrouped = generateNotificationRecord(
                mTestNotificationChannel, 1, null, false);
        final NotificationRecord grouped = generateNotificationRecord(
                mTestNotificationChannel, 2, "group", false);
        mService.addNotification(grouped);
        mService.addNotification(nonGrouped);

        NotificationManagerService.SnoozeNotificationRunnable snoozeNotificationRunnable =
                mService.new SnoozeNotificationRunnable(
                        nonGrouped.getKey(), 100, null);
        snoozeNotificationRunnable.run();

        // only snooze the one notification
        verify(mSnoozeHelper, times(1)).snooze(any(NotificationRecord.class), anyLong());
        assertTrue(nonGrouped.getStats().hasSnoozed());
    }

    @Test
    public void testSnoozeRunnable_snoozeSummary_withChildren() throws Exception {
        final NotificationRecord parent = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);
        final NotificationRecord child = generateNotificationRecord(
                mTestNotificationChannel, 2, "group", false);
        final NotificationRecord child2 = generateNotificationRecord(
                mTestNotificationChannel, 3, "group", false);
        mService.addNotification(parent);
        mService.addNotification(child);
        mService.addNotification(child2);

        NotificationManagerService.SnoozeNotificationRunnable snoozeNotificationRunnable =
                mService.new SnoozeNotificationRunnable(
                        parent.getKey(), 100, null);
        snoozeNotificationRunnable.run();

        // snooze parent and children
        verify(mSnoozeHelper, times(3)).snooze(any(NotificationRecord.class), anyLong());
    }

    @Test
    public void testSnoozeRunnable_snoozeGroupChild_fellowChildren() throws Exception {
        final NotificationRecord parent = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);
        final NotificationRecord child = generateNotificationRecord(
                mTestNotificationChannel, 2, "group", false);
        final NotificationRecord child2 = generateNotificationRecord(
                mTestNotificationChannel, 3, "group", false);
        mService.addNotification(parent);
        mService.addNotification(child);
        mService.addNotification(child2);

        NotificationManagerService.SnoozeNotificationRunnable snoozeNotificationRunnable =
                mService.new SnoozeNotificationRunnable(
                        child2.getKey(), 100, null);
        snoozeNotificationRunnable.run();

        // only snooze the one child
        verify(mSnoozeHelper, times(1)).snooze(any(NotificationRecord.class), anyLong());
    }

    @Test
    public void testSnoozeRunnable_snoozeGroupChild_onlyChildOfSummary() throws Exception {
        final NotificationRecord parent = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);
        assertTrue(parent.sbn.getNotification().isGroupSummary());
        final NotificationRecord child = generateNotificationRecord(
                mTestNotificationChannel, 2, "group", false);
        mService.addNotification(parent);
        mService.addNotification(child);

        NotificationManagerService.SnoozeNotificationRunnable snoozeNotificationRunnable =
                mService.new SnoozeNotificationRunnable(
                        child.getKey(), 100, null);
        snoozeNotificationRunnable.run();

        // snooze child and summary
        verify(mSnoozeHelper, times(2)).snooze(any(NotificationRecord.class), anyLong());
    }

    @Test
    public void testSnoozeRunnable_snoozeGroupChild_noOthersInGroup() throws Exception {
        final NotificationRecord child = generateNotificationRecord(
                mTestNotificationChannel, 2, "group", false);
        mService.addNotification(child);

        NotificationManagerService.SnoozeNotificationRunnable snoozeNotificationRunnable =
                mService.new SnoozeNotificationRunnable(
                        child.getKey(), 100, null);
        snoozeNotificationRunnable.run();

        // snooze child only
        verify(mSnoozeHelper, times(1)).snooze(any(NotificationRecord.class), anyLong());
    }

    @Test
    public void testPostGroupChild_unsnoozeParent() throws Exception {
        final NotificationRecord child = generateNotificationRecord(
                mTestNotificationChannel, 2, "group", false);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, null,
                child.sbn.getId(), child.sbn.getNotification(), child.sbn.getUserId());
        waitForIdle();

        verify(mSnoozeHelper, times(1)).repostGroupSummary(
                anyString(), anyInt(), eq(child.getGroupKey()));
    }

    @Test
    public void testPostNonGroup_noUnsnoozing() throws Exception {
        final NotificationRecord record = generateNotificationRecord(
                mTestNotificationChannel, 2, null, false);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, null,
                record.sbn.getId(), record.sbn.getNotification(), record.sbn.getUserId());
        waitForIdle();

        verify(mSnoozeHelper, never()).repostGroupSummary(anyString(), anyInt(), anyString());
    }

    @Test
    public void testPostGroupSummary_noUnsnoozing() throws Exception {
        final NotificationRecord parent = generateNotificationRecord(
                mTestNotificationChannel, 2, "group", true);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, null,
                parent.sbn.getId(), parent.sbn.getNotification(), parent.sbn.getUserId());
        waitForIdle();

        verify(mSnoozeHelper, never()).repostGroupSummary(anyString(), anyInt(), anyString());
    }

    @Test
    public void testSetListenerAccessForUser() throws Exception {
        UserHandle user = UserHandle.of(10);
        ComponentName c = ComponentName.unflattenFromString("package/Component");
        mBinderService.setNotificationListenerAccessGrantedForUser(c, user.getIdentifier(), true);


        verify(mContext, times(1)).sendBroadcastAsUser(any(), eq(user), any());
        verify(mListeners, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), user.getIdentifier(), true, true);
        verify(mConditionProviders, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), user.getIdentifier(), false, true);
        verify(mAssistants, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    public void testSetAssistantAccessForUser() throws Exception {
        UserHandle user = UserHandle.of(10);
        List<UserInfo> uis = new ArrayList<>();
        UserInfo ui = new UserInfo();
        ui.id = 10;
        uis.add(ui);
        ComponentName c = ComponentName.unflattenFromString("package/Component");
        when(mUm.getEnabledProfiles(10)).thenReturn(uis);

        mBinderService.setNotificationAssistantAccessGrantedForUser(c, user.getIdentifier(), true);

        verify(mContext, times(1)).sendBroadcastAsUser(any(), eq(user), any());
        verify(mAssistants, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), user.getIdentifier(), true, true);
        verify(mAssistants).setUserSet(10, true);
        verify(mConditionProviders, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), user.getIdentifier(), false, true);
        verify(mListeners, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    public void testGetAssistantAllowedForUser() throws Exception {
        UserHandle user = UserHandle.of(10);
        try {
            mBinderService.getAllowedNotificationAssistantForUser(user.getIdentifier());
        } catch (IllegalStateException e) {
            if (!e.getMessage().contains("At most one NotificationAssistant")) {
                throw e;
            }
        }
        verify(mAssistants, times(1)).getAllowedComponents(user.getIdentifier());
    }

    @Test
    public void testGetAssistantAllowed() throws Exception {
        try {
            mBinderService.getAllowedNotificationAssistant();
        } catch (IllegalStateException e) {
            if (!e.getMessage().contains("At most one NotificationAssistant")) {
                throw e;
            }
        }
        verify(mAssistants, times(1)).getAllowedComponents(0);
    }

    @Test
    public void testSetDndAccessForUser() throws Exception {
        UserHandle user = UserHandle.of(10);
        ComponentName c = ComponentName.unflattenFromString("package/Component");
        mBinderService.setNotificationPolicyAccessGrantedForUser(
                c.getPackageName(), user.getIdentifier(), true);

        verify(mContext, times(1)).sendBroadcastAsUser(any(), eq(user), any());
        verify(mConditionProviders, times(1)).setPackageOrComponentEnabled(
                c.getPackageName(), user.getIdentifier(), true, true);
        verify(mAssistants, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean());
        verify(mListeners, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    public void testSetListenerAccess() throws Exception {
        ComponentName c = ComponentName.unflattenFromString("package/Component");
        mBinderService.setNotificationListenerAccessGranted(c, true);

        verify(mListeners, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), 0, true, true);
        verify(mConditionProviders, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), 0, false, true);
        verify(mAssistants, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    public void testSetAssistantAccess() throws Exception {
        List<UserInfo> uis = new ArrayList<>();
        UserInfo ui = new UserInfo();
        ui.id = 0;
        uis.add(ui);
        when(mUm.getEnabledProfiles(ui.id)).thenReturn(uis);
        ComponentName c = ComponentName.unflattenFromString("package/Component");

        mBinderService.setNotificationAssistantAccessGranted(c, true);

        verify(mAssistants, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), 0, true, true);
        verify(mConditionProviders, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), 0, false, true);
        verify(mListeners, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    public void testSetAssistantAccess_multiProfile() throws Exception {
        List<UserInfo> uis = new ArrayList<>();
        UserInfo ui = new UserInfo();
        ui.id = 0;
        uis.add(ui);
        UserInfo ui10 = new UserInfo();
        ui10.id = 10;
        uis.add(ui10);
        when(mUm.getEnabledProfiles(ui.id)).thenReturn(uis);
        ComponentName c = ComponentName.unflattenFromString("package/Component");

        mBinderService.setNotificationAssistantAccessGranted(c, true);

        verify(mAssistants, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), 0, true, true);
        verify(mAssistants, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), 10, true, true);
        verify(mConditionProviders, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), 0, false, true);
        verify(mConditionProviders, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), 10, false, true);
        verify(mListeners, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    public void testSetAssistantAccess_nullWithAllowedAssistant() throws Exception {
        ArrayList<ComponentName> componentList = new ArrayList<>();
        ComponentName c = ComponentName.unflattenFromString("package/Component");
        componentList.add(c);
        when(mAssistants.getAllowedComponents(anyInt())).thenReturn(componentList);
        List<UserInfo> uis = new ArrayList<>();
        UserInfo ui = new UserInfo();
        ui.id = 0;
        uis.add(ui);
        when(mUm.getEnabledProfiles(ui.id)).thenReturn(uis);

        mBinderService.setNotificationAssistantAccessGranted(null, true);

        verify(mAssistants, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), 0, true, false);
        verify(mConditionProviders, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), 0, false,  false);
        verify(mListeners, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    public void testSetAssistantAccessForUser_nullWithAllowedAssistant() throws Exception {
        List<UserInfo> uis = new ArrayList<>();
        UserInfo ui = new UserInfo();
        ui.id = 10;
        uis.add(ui);
        UserHandle user = ui.getUserHandle();
        ArrayList<ComponentName> componentList = new ArrayList<>();
        ComponentName c = ComponentName.unflattenFromString("package/Component");
        componentList.add(c);
        when(mAssistants.getAllowedComponents(anyInt())).thenReturn(componentList);
        when(mUm.getEnabledProfiles(10)).thenReturn(uis);

        mBinderService.setNotificationAssistantAccessGrantedForUser(
                null, user.getIdentifier(), true);

        verify(mAssistants, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), user.getIdentifier(), true, false);
        verify(mAssistants).setUserSet(10, true);
        verify(mConditionProviders, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), user.getIdentifier(), false,  false);
        verify(mListeners, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    public void testSetAssistantAccessForUser_workProfile_nullWithAllowedAssistant()
            throws Exception {
        List<UserInfo> uis = new ArrayList<>();
        UserInfo ui = new UserInfo();
        ui.id = 0;
        uis.add(ui);
        UserInfo ui10 = new UserInfo();
        ui10.id = 10;
        uis.add(ui10);
        UserHandle user = ui.getUserHandle();
        ArrayList<ComponentName> componentList = new ArrayList<>();
        ComponentName c = ComponentName.unflattenFromString("package/Component");
        componentList.add(c);
        when(mAssistants.getAllowedComponents(anyInt())).thenReturn(componentList);
        when(mUm.getEnabledProfiles(ui.id)).thenReturn(uis);

        mBinderService.setNotificationAssistantAccessGrantedForUser(
                    null, user.getIdentifier(), true);

        verify(mAssistants, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), user.getIdentifier(), true, false);
        verify(mAssistants, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), ui10.id, true, false);
        verify(mAssistants).setUserSet(0, true);
        verify(mAssistants).setUserSet(10, true);
        verify(mConditionProviders, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), user.getIdentifier(), false,  false);
        verify(mConditionProviders, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), ui10.id, false,  false);
        verify(mListeners, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    public void testSetDndAccess() throws Exception {
        ComponentName c = ComponentName.unflattenFromString("package/Component");

        mBinderService.setNotificationPolicyAccessGranted(c.getPackageName(), true);

        verify(mConditionProviders, times(1)).setPackageOrComponentEnabled(
                c.getPackageName(), 0, true, true);
        verify(mAssistants, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean());
        verify(mListeners, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    public void testSetListenerAccess_doesNothingOnLowRam() throws Exception {
        when(mActivityManager.isLowRamDevice()).thenReturn(true);
        ComponentName c = ComponentName.unflattenFromString("package/Component");
        mBinderService.setNotificationListenerAccessGranted(c, true);

        verify(mListeners, never()).setPackageOrComponentEnabled(
                anyString(), anyInt(), anyBoolean(), anyBoolean());
        verify(mConditionProviders, never()).setPackageOrComponentEnabled(
                anyString(), anyInt(), anyBoolean(), anyBoolean());
        verify(mAssistants, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    public void testSetAssistantAccess_doesNothingOnLowRam() throws Exception {
        when(mActivityManager.isLowRamDevice()).thenReturn(true);
        ComponentName c = ComponentName.unflattenFromString("package/Component");
        List<UserInfo> uis = new ArrayList<>();
        UserInfo ui = new UserInfo();
        ui.id = 0;
        uis.add(ui);
        when(mUm.getEnabledProfiles(ui.id)).thenReturn(uis);

        mBinderService.setNotificationAssistantAccessGranted(c, true);

        verify(mListeners, never()).setPackageOrComponentEnabled(
                anyString(), anyInt(), anyBoolean(), anyBoolean());
        verify(mConditionProviders, never()).setPackageOrComponentEnabled(
                anyString(), anyInt(), anyBoolean(), anyBoolean());
        verify(mAssistants, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    public void testSetDndAccess_doesNothingOnLowRam() throws Exception {
        when(mActivityManager.isLowRamDevice()).thenReturn(true);
        ComponentName c = ComponentName.unflattenFromString("package/Component");
        mBinderService.setNotificationPolicyAccessGranted(c.getPackageName(), true);

        verify(mListeners, never()).setPackageOrComponentEnabled(
                anyString(), anyInt(), anyBoolean(), anyBoolean());
        verify(mConditionProviders, never()).setPackageOrComponentEnabled(
                anyString(), anyInt(), anyBoolean(), anyBoolean());
        verify(mAssistants, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    public void testSetListenerAccess_doesNothingOnLowRam_exceptWatch() throws Exception {
        when(mPackageManagerClient.hasSystemFeature(FEATURE_WATCH)).thenReturn(true);
        when(mActivityManager.isLowRamDevice()).thenReturn(true);
        ComponentName c = ComponentName.unflattenFromString("package/Component");

        mBinderService.setNotificationListenerAccessGranted(c, true);

        verify(mListeners, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), 0, true, true);
        verify(mConditionProviders, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), 0, false, true);
        verify(mAssistants, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    public void testSetAssistantAccess_doesNothingOnLowRam_exceptWatch() throws Exception {
        when(mPackageManagerClient.hasSystemFeature(FEATURE_WATCH)).thenReturn(true);
        when(mActivityManager.isLowRamDevice()).thenReturn(true);
        ComponentName c = ComponentName.unflattenFromString("package/Component");
        List<UserInfo> uis = new ArrayList<>();
        UserInfo ui = new UserInfo();
        ui.id = 0;
        uis.add(ui);
        when(mUm.getEnabledProfiles(ui.id)).thenReturn(uis);

        mBinderService.setNotificationAssistantAccessGranted(c, true);

        verify(mListeners, never()).setPackageOrComponentEnabled(
                anyString(), anyInt(), anyBoolean(), anyBoolean());
        verify(mConditionProviders, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), 0, false, true);
        verify(mAssistants, times(1)).setPackageOrComponentEnabled(
                c.flattenToString(), 0, true, true);
    }

    @Test
    public void testSetDndAccess_doesNothingOnLowRam_exceptWatch() throws Exception {
        when(mPackageManagerClient.hasSystemFeature(FEATURE_WATCH)).thenReturn(true);
        when(mActivityManager.isLowRamDevice()).thenReturn(true);
        ComponentName c = ComponentName.unflattenFromString("package/Component");

        mBinderService.setNotificationPolicyAccessGranted(c.getPackageName(), true);

        verify(mListeners, never()).setPackageOrComponentEnabled(
                anyString(), anyInt(), anyBoolean(), anyBoolean());
        verify(mConditionProviders, times(1)).setPackageOrComponentEnabled(
                c.getPackageName(), 0, true, true);
        verify(mAssistants, never()).setPackageOrComponentEnabled(
                any(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    public void testOnlyAutogroupIfGroupChanged_noPriorNoti_autogroups() throws Exception {
        NotificationRecord r = generateNotificationRecord(mTestNotificationChannel, 0, null, false);
        mService.addEnqueuedNotification(r);
        NotificationManagerService.PostNotificationRunnable runnable =
                mService.new PostNotificationRunnable(r.getKey());
        runnable.run();
        waitForIdle();

        verify(mGroupHelper, times(1)).onNotificationPosted(any(), anyBoolean());
    }

    @Test
    public void testOnlyAutogroupIfGroupChanged_groupChanged_autogroups()
            throws Exception {
        NotificationRecord r =
                generateNotificationRecord(mTestNotificationChannel, 0, "group", false);
        mService.addNotification(r);

        r = generateNotificationRecord(mTestNotificationChannel, 0, null, false);
        mService.addEnqueuedNotification(r);
        NotificationManagerService.PostNotificationRunnable runnable =
                mService.new PostNotificationRunnable(r.getKey());
        runnable.run();
        waitForIdle();

        verify(mGroupHelper, times(1)).onNotificationPosted(any(), anyBoolean());
    }

    @Test
    public void testOnlyAutogroupIfGroupChanged_noGroupChanged_autogroups()
            throws Exception {
        NotificationRecord r = generateNotificationRecord(mTestNotificationChannel, 0, "group",
                false);
        mService.addNotification(r);
        mService.addEnqueuedNotification(r);

        NotificationManagerService.PostNotificationRunnable runnable =
                mService.new PostNotificationRunnable(r.getKey());
        runnable.run();
        waitForIdle();

        verify(mGroupHelper, never()).onNotificationPosted(any(), anyBoolean());
    }

    @Test
    public void testDontAutogroupIfCritical() throws Exception {
        NotificationRecord r = generateNotificationRecord(mTestNotificationChannel, 0, null, false);
        r.setCriticality(CriticalNotificationExtractor.CRITICAL_LOW);
        mService.addEnqueuedNotification(r);
        NotificationManagerService.PostNotificationRunnable runnable =
                mService.new PostNotificationRunnable(r.getKey());
        runnable.run();

        r = generateNotificationRecord(mTestNotificationChannel, 1, null, false);
        r.setCriticality(CriticalNotificationExtractor.CRITICAL);
        runnable = mService.new PostNotificationRunnable(r.getKey());
        mService.addEnqueuedNotification(r);

        runnable.run();
        waitForIdle();

        verify(mGroupHelper, never()).onNotificationPosted(any(), anyBoolean());
    }

    @Test
    public void testNoFakeColorizedPermission() throws Exception {
        when(mPackageManagerClient.checkPermission(any(), any())).thenReturn(PERMISSION_DENIED);
        Notification.Builder nb = new Notification.Builder(mContext,
                mTestNotificationChannel.getId())
                .setContentTitle("foo")
                .setColorized(true)
                .setFlag(Notification.FLAG_CAN_COLORIZE, true)
                .setSmallIcon(android.R.drawable.sym_def_app_icon);
        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 1, "tag", mUid, 0,
                nb.build(), new UserHandle(mUid), null, 0);
        NotificationRecord nr = new NotificationRecord(mContext, sbn, mTestNotificationChannel);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, null,
                nr.sbn.getId(), nr.sbn.getNotification(), nr.sbn.getUserId());
        waitForIdle();

        NotificationRecord posted = mService.findNotificationLocked(
                PKG, null, nr.sbn.getId(), nr.sbn.getUserId());

        assertFalse(posted.getNotification().isColorized());
    }

    @Test
    public void testGetNotificationCountLocked() throws Exception {
        for (int i = 0; i < 20; i++) {
            NotificationRecord r =
                    generateNotificationRecord(mTestNotificationChannel, i, null, false);
            mService.addEnqueuedNotification(r);
        }
        for (int i = 0; i < 20; i++) {
            NotificationRecord r =
                    generateNotificationRecord(mTestNotificationChannel, i, null, false);
            mService.addNotification(r);
        }

        // another package
        Notification n =
                new Notification.Builder(mContext, mTestNotificationChannel.getId())
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .build();

        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, "tag", mUid, 0,
                n, new UserHandle(mUid), null, 0);
        NotificationRecord otherPackage =
                new NotificationRecord(mContext, sbn, mTestNotificationChannel);
        mService.addEnqueuedNotification(otherPackage);
        mService.addNotification(otherPackage);

        // Same notifications are enqueued as posted, everything counts b/c id and tag don't match
        int userId = new UserHandle(mUid).getIdentifier();
        assertEquals(40,
                mService.getNotificationCountLocked(PKG, userId, 0, null));
        assertEquals(40,
                mService.getNotificationCountLocked(PKG, userId, 0, "tag2"));
        assertEquals(2,
                mService.getNotificationCountLocked("a", userId, 0, "banana"));

        // exclude a known notification - it's excluded from only the posted list, not enqueued
        assertEquals(39,
                mService.getNotificationCountLocked(PKG, userId, 0, "tag"));
    }

    @Test
    public void testAddAutogroup_requestsSort() throws Exception {
        RankingHandler rh = mock(RankingHandler.class);
        mService.setRankingHandler(rh);

        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);
        mService.addAutogroupKeyLocked(r.getKey());

        verify(rh, times(1)).requestSort();
    }

    @Test
    public void testRemoveAutogroup_requestsSort() throws Exception {
        RankingHandler rh = mock(RankingHandler.class);
        mService.setRankingHandler(rh);

        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        r.setOverrideGroupKey("TEST");
        mService.addNotification(r);
        mService.removeAutogroupKeyLocked(r.getKey());

        verify(rh, times(1)).requestSort();
    }

    @Test
    public void testReaddAutogroup_noSort() throws Exception {
        RankingHandler rh = mock(RankingHandler.class);
        mService.setRankingHandler(rh);

        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        r.setOverrideGroupKey("TEST");
        mService.addNotification(r);
        mService.addAutogroupKeyLocked(r.getKey());

        verify(rh, never()).requestSort();
    }

    @Test
    public void testHandleRankingSort_sendsUpdateOnSignalExtractorChange() throws Exception {
        mService.setPreferencesHelper(mPreferencesHelper);
        NotificationManagerService.WorkerHandler handler = mock(
                NotificationManagerService.WorkerHandler.class);
        mService.setHandler(handler);

        Map<String, Answer> answers = getSignalExtractorSideEffects();
        for (String message : answers.keySet()) {
            mService.clearNotifications();
            final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
            mService.addNotification(r);

            doAnswer(answers.get(message)).when(mRankingHelper).extractSignals(r);

            mService.handleRankingSort();
        }
        verify(handler, times(answers.size())).scheduleSendRankingUpdate();
    }

    @Test
    public void testHandleRankingSort_noUpdateWhenNoSignalChange() throws Exception {
        mService.setRankingHelper(mRankingHelper);
        NotificationManagerService.WorkerHandler handler = mock(
                NotificationManagerService.WorkerHandler.class);
        mService.setHandler(handler);

        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);

        mService.handleRankingSort();
        verify(handler, never()).scheduleSendRankingUpdate();
    }

    @Test
    public void testReadPolicyXml_readApprovedServicesFromXml() throws Exception {
        final String upgradeXml = "<notification-policy version=\"1\">"
                + "<ranking></ranking>"
                + "<enabled_listeners>"
                + "<service_listing approved=\"test\" user=\"0\" primary=\"true\" />"
                + "</enabled_listeners>"
                + "<enabled_assistants>"
                + "<service_listing approved=\"test\" user=\"0\" primary=\"true\" />"
                + "</enabled_assistants>"
                + "<dnd_apps>"
                + "<service_listing approved=\"test\" user=\"0\" primary=\"true\" />"
                + "</dnd_apps>"
                + "</notification-policy>";
        mService.readPolicyXml(
                new BufferedInputStream(new ByteArrayInputStream(upgradeXml.getBytes())),
                false,
                UserHandle.USER_ALL);
        verify(mListeners, times(1)).readXml(any(), any(), anyBoolean(), anyInt());
        verify(mConditionProviders, times(1)).readXml(any(), any(), anyBoolean(), anyInt());
        verify(mAssistants, times(1)).readXml(any(), any(), anyBoolean(), anyInt());

        // numbers are inflated for setup
        verify(mListeners, times(1)).migrateToXml();
        verify(mConditionProviders, times(1)).migrateToXml();
        verify(mAssistants, times(1)).migrateToXml();
        verify(mAssistants, times(2)).resetDefaultAssistantsIfNecessary();
    }

    @Test
    public void testReadPolicyXml_readApprovedServicesFromSettings() throws Exception {
        final String preupgradeXml = "<notification-policy version=\"1\">"
                + "<ranking></ranking>"
                + "</notification-policy>";
        mService.readPolicyXml(
                new BufferedInputStream(new ByteArrayInputStream(preupgradeXml.getBytes())),
                false,
                UserHandle.USER_ALL);
        verify(mListeners, never()).readXml(any(), any(), anyBoolean(), anyInt());
        verify(mConditionProviders, never()).readXml(any(), any(), anyBoolean(), anyInt());
        verify(mAssistants, never()).readXml(any(), any(), anyBoolean(), anyInt());

        // numbers are inflated for setup
        verify(mListeners, times(2)).migrateToXml();
        verify(mConditionProviders, times(2)).migrateToXml();
        verify(mAssistants, times(2)).migrateToXml();
        verify(mAssistants, times(2)).resetDefaultAssistantsIfNecessary();
    }

    @Test
    public void testReadPolicyXml_doesNotRestoreManagedServicesForManagedUser() throws Exception {
        final String policyXml = "<notification-policy version=\"1\">"
                + "<ranking></ranking>"
                + "<enabled_listeners>"
                + "<service_listing approved=\"test\" user=\"10\" primary=\"true\" />"
                + "</enabled_listeners>"
                + "<enabled_assistants>"
                + "<service_listing approved=\"test\" user=\"10\" primary=\"true\" />"
                + "</enabled_assistants>"
                + "<dnd_apps>"
                + "<service_listing approved=\"test\" user=\"10\" primary=\"true\" />"
                + "</dnd_apps>"
                + "</notification-policy>";
        when(mUm.isManagedProfile(10)).thenReturn(true);
        mService.readPolicyXml(
                new BufferedInputStream(new ByteArrayInputStream(policyXml.getBytes())),
                true,
                10);
        verify(mListeners, never()).readXml(any(), any(), eq(true), eq(10));
        verify(mConditionProviders, never()).readXml(any(), any(), eq(true), eq(10));
        verify(mAssistants, never()).readXml(any(), any(), eq(true), eq(10));
    }

    @Test
    public void testReadPolicyXml_restoresManagedServicesForNonManagedUser() throws Exception {
        final String policyXml = "<notification-policy version=\"1\">"
                + "<ranking></ranking>"
                + "<enabled_listeners>"
                + "<service_listing approved=\"test\" user=\"10\" primary=\"true\" />"
                + "</enabled_listeners>"
                + "<enabled_assistants>"
                + "<service_listing approved=\"test\" user=\"10\" primary=\"true\" />"
                + "</enabled_assistants>"
                + "<dnd_apps>"
                + "<service_listing approved=\"test\" user=\"10\" primary=\"true\" />"
                + "</dnd_apps>"
                + "</notification-policy>";
        when(mUm.isManagedProfile(10)).thenReturn(false);
        mService.readPolicyXml(
                new BufferedInputStream(new ByteArrayInputStream(policyXml.getBytes())),
                true,
                10);
        verify(mListeners, times(1)).readXml(any(), any(), eq(true), eq(10));
        verify(mConditionProviders, times(1)).readXml(any(), any(), eq(true), eq(10));
        verify(mAssistants, times(1)).readXml(any(), any(), eq(true), eq(10));
    }

    @Test
    public void testLocaleChangedCallsUpdateDefaultZenModeRules() throws Exception {
        ZenModeHelper mZenModeHelper = mock(ZenModeHelper.class);
        mService.mZenModeHelper = mZenModeHelper;
        mService.mLocaleChangeReceiver.onReceive(mContext,
                new Intent(Intent.ACTION_LOCALE_CHANGED));

        verify(mZenModeHelper, times(1)).updateDefaultZenRules();
    }

    @Test
    public void testBumpFGImportance_noChannelChangePreOApp() throws Exception {
        String preOPkg = PKG_N_MR1;
        final ApplicationInfo legacy = new ApplicationInfo();
        legacy.targetSdkVersion = Build.VERSION_CODES.N_MR1;
        when(mPackageManagerClient.getApplicationInfoAsUser(eq(preOPkg), anyInt(), anyInt()))
                .thenReturn(legacy);
        when(mPackageManagerClient.getPackageUidAsUser(eq(preOPkg), anyInt()))
                .thenReturn(Binder.getCallingUid());
        getContext().setMockPackageManager(mPackageManagerClient);

        Notification.Builder nb = new Notification.Builder(mContext,
                NotificationChannel.DEFAULT_CHANNEL_ID)
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setFlag(FLAG_FOREGROUND_SERVICE, true)
                .setPriority(Notification.PRIORITY_MIN);

        StatusBarNotification sbn = new StatusBarNotification(preOPkg, preOPkg, 9, "tag",
                Binder.getCallingUid(), 0, nb.build(), new UserHandle(Binder.getCallingUid()), null, 0);

        mBinderService.enqueueNotificationWithTag(sbn.getPackageName(), sbn.getOpPkg(),
                sbn.getTag(), sbn.getId(), sbn.getNotification(), sbn.getUserId());
        waitForIdle();

        assertEquals(IMPORTANCE_LOW,
                mService.getNotificationRecord(sbn.getKey()).getImportance());

        nb = new Notification.Builder(mContext)
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setFlag(FLAG_FOREGROUND_SERVICE, true)
                .setPriority(Notification.PRIORITY_MIN);

        sbn = new StatusBarNotification(preOPkg, preOPkg, 9, "tag", Binder.getCallingUid(),
                0, nb.build(), new UserHandle(Binder.getCallingUid()), null, 0);

        mBinderService.enqueueNotificationWithTag(preOPkg, preOPkg, "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        waitForIdle();
        assertEquals(IMPORTANCE_LOW,
                mService.getNotificationRecord(sbn.getKey()).getImportance());

        NotificationChannel defaultChannel = mBinderService.getNotificationChannel(
                preOPkg, mContext.getUserId(), preOPkg, NotificationChannel.DEFAULT_CHANNEL_ID);
        assertEquals(IMPORTANCE_UNSPECIFIED, defaultChannel.getImportance());
    }

    @Test
    public void testStats_updatedOnDirectReply() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);

        mService.mNotificationDelegate.onNotificationDirectReplied(r.getKey());
        assertTrue(mService.getNotificationRecord(r.getKey()).getStats().hasDirectReplied());
        verify(mAssistants).notifyAssistantNotificationDirectReplyLocked(eq(r.sbn));
    }

    @Test
    public void testStats_updatedOnUserExpansion() throws Exception {
        NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);

        mService.mNotificationDelegate.onNotificationExpansionChanged(r.getKey(), true, true,
                NOTIFICATION_LOCATION_UNKNOWN);
        verify(mAssistants).notifyAssistantExpansionChangedLocked(eq(r.sbn), eq(true), eq((true)));
        assertTrue(mService.getNotificationRecord(r.getKey()).getStats().hasExpanded());

        mService.mNotificationDelegate.onNotificationExpansionChanged(r.getKey(), true, false,
                NOTIFICATION_LOCATION_UNKNOWN);
        verify(mAssistants).notifyAssistantExpansionChangedLocked(eq(r.sbn), eq(true), eq((false)));
        assertTrue(mService.getNotificationRecord(r.getKey()).getStats().hasExpanded());
    }

    @Test
    public void testStats_notUpdatedOnAutoExpansion() throws Exception {
        NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);

        mService.mNotificationDelegate.onNotificationExpansionChanged(r.getKey(), false, true,
                NOTIFICATION_LOCATION_UNKNOWN);
        assertFalse(mService.getNotificationRecord(r.getKey()).getStats().hasExpanded());
        verify(mAssistants).notifyAssistantExpansionChangedLocked(eq(r.sbn), eq(false), eq((true)));

        mService.mNotificationDelegate.onNotificationExpansionChanged(r.getKey(), false, false,
                NOTIFICATION_LOCATION_UNKNOWN);
        assertFalse(mService.getNotificationRecord(r.getKey()).getStats().hasExpanded());
        verify(mAssistants).notifyAssistantExpansionChangedLocked(
                eq(r.sbn), eq(false), eq((false)));
    }

    @Test
    public void testStats_updatedOnViewSettings() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);

        mService.mNotificationDelegate.onNotificationSettingsViewed(r.getKey());
        assertTrue(mService.getNotificationRecord(r.getKey()).getStats().hasViewedSettings());
    }

    @Test
    public void testStats_updatedOnVisibilityChanged() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);

        final NotificationVisibility nv = NotificationVisibility.obtain(r.getKey(), 1, 2, true);
        mService.mNotificationDelegate.onNotificationVisibilityChanged(
                new NotificationVisibility[] {nv}, new NotificationVisibility[]{});
        assertTrue(mService.getNotificationRecord(r.getKey()).getStats().hasSeen());
        mService.mNotificationDelegate.onNotificationVisibilityChanged(
                new NotificationVisibility[] {}, new NotificationVisibility[]{nv});
        assertTrue(mService.getNotificationRecord(r.getKey()).getStats().hasSeen());
    }

    @Test
    public void testStats_dismissalSurface() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);

        final NotificationVisibility nv = NotificationVisibility.obtain(r.getKey(), 0, 1, true);
        mService.mNotificationDelegate.onNotificationClear(mUid, 0, PKG, r.sbn.getTag(),
                r.sbn.getId(), r.getUserId(), r.getKey(), NotificationStats.DISMISSAL_AOD,
                NotificationStats.DISMISS_SENTIMENT_POSITIVE, nv);
        waitForIdle();

        assertEquals(NotificationStats.DISMISSAL_AOD, r.getStats().getDismissalSurface());
    }

    @Test
    public void testStats_dismissalSentiment() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);

        final NotificationVisibility nv = NotificationVisibility.obtain(r.getKey(), 0, 1, true);
        mService.mNotificationDelegate.onNotificationClear(mUid, 0, PKG, r.sbn.getTag(),
                r.sbn.getId(), r.getUserId(), r.getKey(), NotificationStats.DISMISSAL_AOD,
                NotificationStats.DISMISS_SENTIMENT_NEGATIVE, nv);
        waitForIdle();

        assertEquals(NotificationStats.DISMISS_SENTIMENT_NEGATIVE,
                r.getStats().getDismissalSentiment());
    }

    @Test
    public void testApplyAdjustmentMultiUser() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);
        NotificationManagerService.WorkerHandler handler = mock(
                NotificationManagerService.WorkerHandler.class);
        mService.setHandler(handler);

        when(mAssistants.isSameUser(eq(null), anyInt())).thenReturn(false);

        Bundle signals = new Bundle();
        signals.putInt(Adjustment.KEY_USER_SENTIMENT,
                USER_SENTIMENT_NEGATIVE);
        Adjustment adjustment = new Adjustment(
                r.sbn.getPackageName(), r.getKey(), signals, "", r.getUser().getIdentifier());
        mBinderService.applyAdjustmentFromAssistant(null, adjustment);

        waitForIdle();

        verify(handler, timeout(300).times(0)).scheduleSendRankingUpdate();
    }

    @Test
    public void testAssistantBlockingTriggersCancel() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);
        NotificationManagerService.WorkerHandler handler = mock(
                NotificationManagerService.WorkerHandler.class);
        mService.setHandler(handler);

        Bundle signals = new Bundle();
        signals.putInt(KEY_IMPORTANCE, IMPORTANCE_NONE);
        Adjustment adjustment = new Adjustment(
                r.sbn.getPackageName(), r.getKey(), signals, "", r.getUser().getIdentifier());
        when(mAssistants.isSameUser(any(), anyInt())).thenReturn(true);
        mBinderService.applyAdjustmentFromAssistant(null, adjustment);

        waitForIdle();

        verify(handler, timeout(300).times(0)).scheduleSendRankingUpdate();
        verify(handler, times(1)).scheduleCancelNotification(any());
    }

    @Test
    public void testApplyEnqueuedAdjustmentFromAssistant_singleUser() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addEnqueuedNotification(r);
        NotificationManagerService.WorkerHandler handler = mock(
                NotificationManagerService.WorkerHandler.class);
        mService.setHandler(handler);
        when(mAssistants.isSameUser(eq(null), anyInt())).thenReturn(true);

        Bundle signals = new Bundle();
        signals.putInt(Adjustment.KEY_USER_SENTIMENT,
                USER_SENTIMENT_NEGATIVE);
        Adjustment adjustment = new Adjustment(
                r.sbn.getPackageName(), r.getKey(), signals, "", r.getUser().getIdentifier());
        mBinderService.applyEnqueuedAdjustmentFromAssistant(null, adjustment);

        assertEquals(USER_SENTIMENT_NEGATIVE, r.getUserSentiment());
    }

    @Test
    public void testApplyEnqueuedAdjustmentFromAssistant_importance() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addEnqueuedNotification(r);
        NotificationManagerService.WorkerHandler handler = mock(
                NotificationManagerService.WorkerHandler.class);
        mService.setHandler(handler);
        when(mAssistants.isSameUser(eq(null), anyInt())).thenReturn(true);

        Bundle signals = new Bundle();
        signals.putInt(KEY_IMPORTANCE, IMPORTANCE_LOW);
        Adjustment adjustment = new Adjustment(
                r.sbn.getPackageName(), r.getKey(), signals, "", r.getUser().getIdentifier());
        mBinderService.applyEnqueuedAdjustmentFromAssistant(null, adjustment);

        assertEquals(IMPORTANCE_LOW, r.getImportance());
    }

    @Test
    public void testApplyEnqueuedAdjustmentFromAssistant_crossUser() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addEnqueuedNotification(r);
        NotificationManagerService.WorkerHandler handler = mock(
                NotificationManagerService.WorkerHandler.class);
        mService.setHandler(handler);
        when(mAssistants.isSameUser(eq(null), anyInt())).thenReturn(false);

        Bundle signals = new Bundle();
        signals.putInt(Adjustment.KEY_USER_SENTIMENT,
                USER_SENTIMENT_NEGATIVE);
        Adjustment adjustment = new Adjustment(
                r.sbn.getPackageName(), r.getKey(), signals, "", r.getUser().getIdentifier());
        mBinderService.applyEnqueuedAdjustmentFromAssistant(null, adjustment);

        assertEquals(USER_SENTIMENT_NEUTRAL, r.getUserSentiment());

        waitForIdle();

        verify(handler, timeout(300).times(0)).scheduleSendRankingUpdate();
    }

    @Test
    public void testUserSentimentChangeTriggersUpdate() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);
        NotificationManagerService.WorkerHandler handler = mock(
                NotificationManagerService.WorkerHandler.class);
        mService.setHandler(handler);
        when(mAssistants.isSameUser(eq(null), anyInt())).thenReturn(true);

        Bundle signals = new Bundle();
        signals.putInt(Adjustment.KEY_USER_SENTIMENT,
                USER_SENTIMENT_NEGATIVE);
        Adjustment adjustment = new Adjustment(
                r.sbn.getPackageName(), r.getKey(), signals, "", r.getUser().getIdentifier());
        mBinderService.applyAdjustmentFromAssistant(null, adjustment);

        waitForIdle();

        verify(handler, timeout(300).times(1)).scheduleSendRankingUpdate();
    }

    @Test
    public void testTooLateAdjustmentTriggersUpdate() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);
        NotificationManagerService.WorkerHandler handler = mock(
                NotificationManagerService.WorkerHandler.class);
        mService.setHandler(handler);
        when(mAssistants.isSameUser(eq(null), anyInt())).thenReturn(true);

        Bundle signals = new Bundle();
        signals.putInt(Adjustment.KEY_USER_SENTIMENT,
                USER_SENTIMENT_NEGATIVE);
        Adjustment adjustment = new Adjustment(
                r.sbn.getPackageName(), r.getKey(), signals, "", r.getUser().getIdentifier());
        mBinderService.applyEnqueuedAdjustmentFromAssistant(null, adjustment);

        waitForIdle();

        verify(handler, timeout(300).times(1)).scheduleSendRankingUpdate();
    }

    @Test
    public void testEnqueuedAdjustmentAppliesAdjustments() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addEnqueuedNotification(r);
        NotificationManagerService.WorkerHandler handler = mock(
                NotificationManagerService.WorkerHandler.class);
        mService.setHandler(handler);
        when(mAssistants.isSameUser(eq(null), anyInt())).thenReturn(true);

        Bundle signals = new Bundle();
        signals.putInt(Adjustment.KEY_USER_SENTIMENT,
                USER_SENTIMENT_NEGATIVE);
        Adjustment adjustment = new Adjustment(
                r.sbn.getPackageName(), r.getKey(), signals, "", r.getUser().getIdentifier());
        mBinderService.applyEnqueuedAdjustmentFromAssistant(null, adjustment);

        assertEquals(USER_SENTIMENT_NEGATIVE,
                r.getUserSentiment());
    }

    @Test
    public void testRestore() throws Exception {
        int systemChecks = mService.countSystemChecks;
        mBinderService.applyRestore(null, UserHandle.USER_SYSTEM);
        assertEquals(1, mService.countSystemChecks - systemChecks);
    }

    @Test
    public void testBackup() throws Exception {
        int systemChecks = mService.countSystemChecks;
        mBinderService.getBackupPayload(1);
        assertEquals(1, mService.countSystemChecks - systemChecks);
    }

    @Test
    public void updateUriPermissions_update() throws Exception {
        NotificationChannel c = new NotificationChannel(
                TEST_CHANNEL_ID, TEST_CHANNEL_ID, IMPORTANCE_DEFAULT);
        c.setSound(null, Notification.AUDIO_ATTRIBUTES_DEFAULT);
        Message message1 = new Message("", 0, "");
        message1.setData("",
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 1));
        Message message2 = new Message("", 1, "");
        message2.setData("",
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 2));

        Notification.Builder nbA = new Notification.Builder(mContext, c.getId())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setStyle(new Notification.MessagingStyle("")
                        .addMessage(message1)
                        .addMessage(message2));
        NotificationRecord recordA = new NotificationRecord(mContext, new StatusBarNotification(
                PKG, PKG, 0, "tag", mUid, 0, nbA.build(), new UserHandle(mUid), null, 0), c);

        // First post means we grant access to both
        reset(mUgm);
        reset(mUgmInternal);
        when(mUgmInternal.newUriPermissionOwner(any())).thenReturn(new Binder());
        mService.updateUriPermissions(recordA, null, mContext.getPackageName(),
                UserHandle.USER_SYSTEM);
        verify(mUgm, times(1)).grantUriPermissionFromOwner(any(), anyInt(), any(),
                eq(message1.getDataUri()), anyInt(), anyInt(), anyInt());
        verify(mUgm, times(1)).grantUriPermissionFromOwner(any(), anyInt(), any(),
                eq(message2.getDataUri()), anyInt(), anyInt(), anyInt());

        Notification.Builder nbB = new Notification.Builder(mContext, c.getId())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setStyle(new Notification.MessagingStyle("").addMessage(message2));
        NotificationRecord recordB = new NotificationRecord(mContext, new StatusBarNotification(PKG,
                PKG, 0, "tag", mUid, 0, nbB.build(), new UserHandle(mUid), null, 0), c);

        // Update means we drop access to first
        reset(mUgmInternal);
        mService.updateUriPermissions(recordB, recordA, mContext.getPackageName(),
                UserHandle.USER_SYSTEM);
        verify(mUgmInternal, times(1)).revokeUriPermissionFromOwner(any(),
                eq(message1.getDataUri()), anyInt(), anyInt());

        // Update back means we grant access to first again
        reset(mUgm);
        mService.updateUriPermissions(recordA, recordB, mContext.getPackageName(),
                UserHandle.USER_SYSTEM);
        verify(mUgm, times(1)).grantUriPermissionFromOwner(any(), anyInt(), any(),
                eq(message1.getDataUri()), anyInt(), anyInt(), anyInt());

        // And update to empty means we drop everything
        reset(mUgmInternal);
        mService.updateUriPermissions(null, recordB, mContext.getPackageName(),
                UserHandle.USER_SYSTEM);
        verify(mUgmInternal, times(1)).revokeUriPermissionFromOwner(any(), eq(null),
                anyInt(), anyInt());
    }

    @Test
    public void testVisitUris() throws Exception {
        final Uri audioContents = Uri.parse("content://com.example/audio");
        final Uri backgroundImage = Uri.parse("content://com.example/background");

        Bundle extras = new Bundle();
        extras.putParcelable(Notification.EXTRA_AUDIO_CONTENTS_URI, audioContents);
        extras.putString(Notification.EXTRA_BACKGROUND_IMAGE_URI, backgroundImage.toString());

        Notification n = new Notification.Builder(mContext, "a")
                .setContentTitle("notification with uris")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .addExtras(extras)
                .build();

        Consumer<Uri> visitor = (Consumer<Uri>) spy(Consumer.class);
        n.visitUris(visitor);
        verify(visitor, times(1)).accept(eq(audioContents));
        verify(visitor, times(1)).accept(eq(backgroundImage));
    }

    @Test
    public void testSetNotificationPolicy_preP_setOldFields() {
        ZenModeHelper mZenModeHelper = mock(ZenModeHelper.class);
        mService.mZenModeHelper = mZenModeHelper;
        NotificationManager.Policy userPolicy =
                new NotificationManager.Policy(0, 0, 0, SUPPRESSED_EFFECT_BADGE);
        when(mZenModeHelper.getNotificationPolicy()).thenReturn(userPolicy);

        NotificationManager.Policy appPolicy = new NotificationManager.Policy(0, 0, 0,
                SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_SCREEN_OFF);

        int expected = SUPPRESSED_EFFECT_BADGE
                | SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_SCREEN_OFF
                | SUPPRESSED_EFFECT_PEEK | SUPPRESSED_EFFECT_LIGHTS
                | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
        int actual = mService.calculateSuppressedVisualEffects(appPolicy, userPolicy, O_MR1);

        assertEquals(expected, actual);
    }

    @Test
    public void testSetNotificationPolicy_preP_setNewFields() {
        ZenModeHelper mZenModeHelper = mock(ZenModeHelper.class);
        mService.mZenModeHelper = mZenModeHelper;
        NotificationManager.Policy userPolicy =
                new NotificationManager.Policy(0, 0, 0, SUPPRESSED_EFFECT_BADGE);
        when(mZenModeHelper.getNotificationPolicy()).thenReturn(userPolicy);

        NotificationManager.Policy appPolicy = new NotificationManager.Policy(0, 0, 0,
                SUPPRESSED_EFFECT_NOTIFICATION_LIST);

        int expected = SUPPRESSED_EFFECT_BADGE;
        int actual = mService.calculateSuppressedVisualEffects(appPolicy, userPolicy, O_MR1);

        assertEquals(expected, actual);
    }

    @Test
    public void testSetNotificationPolicy_preP_setOldNewFields() {
        ZenModeHelper mZenModeHelper = mock(ZenModeHelper.class);
        mService.mZenModeHelper = mZenModeHelper;
        NotificationManager.Policy userPolicy =
                new NotificationManager.Policy(0, 0, 0, SUPPRESSED_EFFECT_BADGE);
        when(mZenModeHelper.getNotificationPolicy()).thenReturn(userPolicy);

        NotificationManager.Policy appPolicy = new NotificationManager.Policy(0, 0, 0,
                SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_STATUS_BAR);

        int expected =
                SUPPRESSED_EFFECT_BADGE | SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_PEEK;
        int actual = mService.calculateSuppressedVisualEffects(appPolicy, userPolicy, O_MR1);

        assertEquals(expected, actual);
    }

    @Test
    public void testSetNotificationPolicy_P_setOldFields() {
        ZenModeHelper mZenModeHelper = mock(ZenModeHelper.class);
        mService.mZenModeHelper = mZenModeHelper;
        NotificationManager.Policy userPolicy =
                new NotificationManager.Policy(0, 0, 0, SUPPRESSED_EFFECT_BADGE);
        when(mZenModeHelper.getNotificationPolicy()).thenReturn(userPolicy);

        NotificationManager.Policy appPolicy = new NotificationManager.Policy(0, 0, 0,
                SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_SCREEN_OFF);

        int expected = SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_SCREEN_OFF
                | SUPPRESSED_EFFECT_PEEK | SUPPRESSED_EFFECT_AMBIENT
                | SUPPRESSED_EFFECT_LIGHTS | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
        int actual = mService.calculateSuppressedVisualEffects(appPolicy, userPolicy, P);

        assertEquals(expected, actual);
    }

    @Test
    public void testSetNotificationPolicy_P_setNewFields() {
        ZenModeHelper mZenModeHelper = mock(ZenModeHelper.class);
        mService.mZenModeHelper = mZenModeHelper;
        NotificationManager.Policy userPolicy =
                new NotificationManager.Policy(0, 0, 0, SUPPRESSED_EFFECT_BADGE);
        when(mZenModeHelper.getNotificationPolicy()).thenReturn(userPolicy);

        NotificationManager.Policy appPolicy = new NotificationManager.Policy(0, 0, 0,
                SUPPRESSED_EFFECT_NOTIFICATION_LIST | SUPPRESSED_EFFECT_AMBIENT
                        | SUPPRESSED_EFFECT_LIGHTS | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT);

        int expected = SUPPRESSED_EFFECT_NOTIFICATION_LIST | SUPPRESSED_EFFECT_SCREEN_OFF
                | SUPPRESSED_EFFECT_AMBIENT | SUPPRESSED_EFFECT_LIGHTS
                | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
        int actual = mService.calculateSuppressedVisualEffects(appPolicy, userPolicy, P);

        assertEquals(expected, actual);
    }

    @Test
    public void testSetNotificationPolicy_P_setOldNewFields() {
        ZenModeHelper mZenModeHelper = mock(ZenModeHelper.class);
        mService.mZenModeHelper = mZenModeHelper;
        NotificationManager.Policy userPolicy =
                new NotificationManager.Policy(0, 0, 0, SUPPRESSED_EFFECT_BADGE);
        when(mZenModeHelper.getNotificationPolicy()).thenReturn(userPolicy);

        NotificationManager.Policy appPolicy = new NotificationManager.Policy(0, 0, 0,
                SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_STATUS_BAR);

        int expected =  SUPPRESSED_EFFECT_STATUS_BAR;
        int actual = mService.calculateSuppressedVisualEffects(appPolicy, userPolicy, P);

        assertEquals(expected, actual);

        appPolicy = new NotificationManager.Policy(0, 0, 0,
                SUPPRESSED_EFFECT_SCREEN_ON | SUPPRESSED_EFFECT_AMBIENT
                        | SUPPRESSED_EFFECT_LIGHTS | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT);

        expected =  SUPPRESSED_EFFECT_SCREEN_OFF | SUPPRESSED_EFFECT_AMBIENT
                | SUPPRESSED_EFFECT_LIGHTS | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
        actual = mService.calculateSuppressedVisualEffects(appPolicy, userPolicy, P);

        assertEquals(expected, actual);
    }

    @Test
    public void testVisualDifference_foreground() {
        Notification.Builder nb1 = new Notification.Builder(mContext, "")
                .setContentTitle("foo");
        StatusBarNotification sbn1 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb1.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r1 =
                new NotificationRecord(mContext, sbn1, mock(NotificationChannel.class));

        Notification.Builder nb2 = new Notification.Builder(mContext, "")
                .setFlag(FLAG_FOREGROUND_SERVICE, true)
                .setContentTitle("bar");
        StatusBarNotification sbn2 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb2.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r2 =
                new NotificationRecord(mContext, sbn2, mock(NotificationChannel.class));

        assertFalse(mService.isVisuallyInterruptive(r1, r2));
    }

    @Test
    public void testVisualDifference_diffTitle() {
        Notification.Builder nb1 = new Notification.Builder(mContext, "")
                .setContentTitle("foo");
        StatusBarNotification sbn1 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb1.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r1 =
                new NotificationRecord(mContext, sbn1, mock(NotificationChannel.class));

        Notification.Builder nb2 = new Notification.Builder(mContext, "")
                .setContentTitle("bar");
        StatusBarNotification sbn2 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb2.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r2 =
                new NotificationRecord(mContext, sbn2, mock(NotificationChannel.class));

        assertTrue(mService.isVisuallyInterruptive(r1, r2));
    }

    @Test
    public void testVisualDifference_inboxStyle() {
        Notification.Builder nb1 = new Notification.Builder(mContext, "")
                .setStyle(new Notification.InboxStyle()
                    .addLine("line1").addLine("line2"));
        StatusBarNotification sbn1 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb1.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r1 =
                new NotificationRecord(mContext, sbn1, mock(NotificationChannel.class));

        Notification.Builder nb2 = new Notification.Builder(mContext, "")
                .setStyle(new Notification.InboxStyle()
                        .addLine("line1").addLine("line2_changed"));
        StatusBarNotification sbn2 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb2.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r2 =
                new NotificationRecord(mContext, sbn2, mock(NotificationChannel.class));

        assertTrue(mService.isVisuallyInterruptive(r1, r2)); // line 2 changed unnoticed

        Notification.Builder nb3 = new Notification.Builder(mContext, "")
                .setStyle(new Notification.InboxStyle()
                        .addLine("line1"));
        StatusBarNotification sbn3 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb3.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r3 =
                new NotificationRecord(mContext, sbn3, mock(NotificationChannel.class));

        assertTrue(mService.isVisuallyInterruptive(r1, r3)); // line 2 removed unnoticed

        Notification.Builder nb4 = new Notification.Builder(mContext, "")
                .setStyle(new Notification.InboxStyle()
                        .addLine("line1").addLine("line2").addLine("line3"));
        StatusBarNotification sbn4 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb4.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r4 =
                new NotificationRecord(mContext, sbn4, mock(NotificationChannel.class));

        assertTrue(mService.isVisuallyInterruptive(r1, r4)); // line 3 added unnoticed

        Notification.Builder nb5 = new Notification.Builder(mContext, "")
            .setContentText("not an inbox");
        StatusBarNotification sbn5 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb5.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r5 =
                new NotificationRecord(mContext, sbn5, mock(NotificationChannel.class));

        assertTrue(mService.isVisuallyInterruptive(r1, r5)); // changed Styles, went unnoticed
    }

    @Test
    public void testVisualDifference_diffText() {
        Notification.Builder nb1 = new Notification.Builder(mContext, "")
                .setContentText("foo");
        StatusBarNotification sbn1 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb1.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r1 =
                new NotificationRecord(mContext, sbn1, mock(NotificationChannel.class));

        Notification.Builder nb2 = new Notification.Builder(mContext, "")
                .setContentText("bar");
        StatusBarNotification sbn2 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb2.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r2 =
                new NotificationRecord(mContext, sbn2, mock(NotificationChannel.class));

        assertTrue(mService.isVisuallyInterruptive(r1, r2));
    }

    @Test
    public void testVisualDifference_sameText() {
        Notification.Builder nb1 = new Notification.Builder(mContext, "")
                .setContentText("foo");
        StatusBarNotification sbn1 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb1.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r1 =
                new NotificationRecord(mContext, sbn1, mock(NotificationChannel.class));

        Notification.Builder nb2 = new Notification.Builder(mContext, "")
                .setContentText("foo");
        StatusBarNotification sbn2 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb2.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r2 =
                new NotificationRecord(mContext, sbn2, mock(NotificationChannel.class));

        assertFalse(mService.isVisuallyInterruptive(r1, r2));
    }

    @Test
    public void testVisualDifference_sameTextButStyled() {
        Notification.Builder nb1 = new Notification.Builder(mContext, "")
                .setContentText(Html.fromHtml("<b>foo</b>"));
        StatusBarNotification sbn1 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb1.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r1 =
                new NotificationRecord(mContext, sbn1, mock(NotificationChannel.class));

        Notification.Builder nb2 = new Notification.Builder(mContext, "")
                .setContentText(Html.fromHtml("<b>foo</b>"));
        StatusBarNotification sbn2 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb2.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r2 =
                new NotificationRecord(mContext, sbn2, mock(NotificationChannel.class));

        assertFalse(mService.isVisuallyInterruptive(r1, r2));
    }

    @Test
    public void testVisualDifference_diffTextButStyled() {
        Notification.Builder nb1 = new Notification.Builder(mContext, "")
                .setContentText(Html.fromHtml("<b>foo</b>"));
        StatusBarNotification sbn1 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb1.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r1 =
                new NotificationRecord(mContext, sbn1, mock(NotificationChannel.class));

        Notification.Builder nb2 = new Notification.Builder(mContext, "")
                .setContentText(Html.fromHtml("<b>bar</b>"));
        StatusBarNotification sbn2 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb2.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r2 =
                new NotificationRecord(mContext, sbn2, mock(NotificationChannel.class));

        assertTrue(mService.isVisuallyInterruptive(r1, r2));
    }

    @Test
    public void testVisualDifference_diffProgress() {
        Notification.Builder nb1 = new Notification.Builder(mContext, "")
                .setProgress(100, 90, false);
        StatusBarNotification sbn1 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb1.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r1 =
                new NotificationRecord(mContext, sbn1, mock(NotificationChannel.class));

        Notification.Builder nb2 = new Notification.Builder(mContext, "")
                .setProgress(100, 100, false);
        StatusBarNotification sbn2 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb2.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r2 =
                new NotificationRecord(mContext, sbn2, mock(NotificationChannel.class));

        assertTrue(mService.isVisuallyInterruptive(r1, r2));
    }

    @Test
    public void testVisualDifference_diffProgressNotDone() {
        Notification.Builder nb1 = new Notification.Builder(mContext, "")
                .setProgress(100, 90, false);
        StatusBarNotification sbn1 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb1.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r1 =
                new NotificationRecord(mContext, sbn1, mock(NotificationChannel.class));

        Notification.Builder nb2 = new Notification.Builder(mContext, "")
                .setProgress(100, 91, false);
        StatusBarNotification sbn2 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb2.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r2 =
                new NotificationRecord(mContext, sbn2, mock(NotificationChannel.class));

        assertFalse(mService.isVisuallyInterruptive(r1, r2));
    }

    @Test
    public void testVisualDifference_sameProgressStillDone() {
        Notification.Builder nb1 = new Notification.Builder(mContext, "")
                .setProgress(100, 100, false);
        StatusBarNotification sbn1 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb1.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r1 =
                new NotificationRecord(mContext, sbn1, mock(NotificationChannel.class));

        Notification.Builder nb2 = new Notification.Builder(mContext, "")
                .setProgress(100, 100, false);
        StatusBarNotification sbn2 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb2.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r2 =
                new NotificationRecord(mContext, sbn2, mock(NotificationChannel.class));

        assertFalse(mService.isVisuallyInterruptive(r1, r2));
    }

    @Test
    public void testVisualDifference_summary() {
        Notification.Builder nb1 = new Notification.Builder(mContext, "")
                .setGroup("bananas")
                .setFlag(Notification.FLAG_GROUP_SUMMARY, true)
                .setContentText("foo");
        StatusBarNotification sbn1 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb1.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r1 =
                new NotificationRecord(mContext, sbn1, mock(NotificationChannel.class));

        Notification.Builder nb2 = new Notification.Builder(mContext, "")
                .setGroup("bananas")
                .setFlag(Notification.FLAG_GROUP_SUMMARY, true)
                .setContentText("bar");
        StatusBarNotification sbn2 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb2.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r2 =
                new NotificationRecord(mContext, sbn2, mock(NotificationChannel.class));

        assertFalse(mService.isVisuallyInterruptive(r1, r2));
    }

    @Test
    public void testVisualDifference_summaryNewNotification() {
        Notification.Builder nb2 = new Notification.Builder(mContext, "")
                .setGroup("bananas")
                .setFlag(Notification.FLAG_GROUP_SUMMARY, true)
                .setContentText("bar");
        StatusBarNotification sbn2 = new StatusBarNotification(PKG, PKG, 0, "tag", mUid, 0,
                nb2.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r2 =
                new NotificationRecord(mContext, sbn2, mock(NotificationChannel.class));

        assertFalse(mService.isVisuallyInterruptive(null, r2));
    }

    @Test
    public void testHideAndUnhideNotificationsOnSuspendedPackageBroadcast() {
        // post 2 notification from this package
        final NotificationRecord notif1 = generateNotificationRecord(
                mTestNotificationChannel, 1, null, true);
        final NotificationRecord notif2 = generateNotificationRecord(
                mTestNotificationChannel, 2, null, false);
        mService.addNotification(notif1);
        mService.addNotification(notif2);

        // on broadcast, hide the 2 notifications
        mService.simulatePackageSuspendBroadcast(true, PKG);
        ArgumentCaptor<List> captorHide = ArgumentCaptor.forClass(List.class);
        verify(mListeners, times(1)).notifyHiddenLocked(captorHide.capture());
        assertEquals(2, captorHide.getValue().size());

        // on broadcast, unhide the 2 notifications
        mService.simulatePackageSuspendBroadcast(false, PKG);
        ArgumentCaptor<List> captorUnhide = ArgumentCaptor.forClass(List.class);
        verify(mListeners, times(1)).notifyUnhiddenLocked(captorUnhide.capture());
        assertEquals(2, captorUnhide.getValue().size());
    }

    @Test
    public void testNoNotificationsHiddenOnSuspendedPackageBroadcast() {
        // post 2 notification from this package
        final NotificationRecord notif1 = generateNotificationRecord(
                mTestNotificationChannel, 1, null, true);
        final NotificationRecord notif2 = generateNotificationRecord(
                mTestNotificationChannel, 2, null, false);
        mService.addNotification(notif1);
        mService.addNotification(notif2);

        // on broadcast, nothing is hidden since no notifications are of package "test_package"
        mService.simulatePackageSuspendBroadcast(true, "test_package");
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(mListeners, times(1)).notifyHiddenLocked(captor.capture());
        assertEquals(0, captor.getValue().size());
    }

    @Test
    public void testHideAndUnhideNotificationsOnDistractingPackageBroadcast() {
        // Post 2 notifications from 2 packages
        NotificationRecord pkgA = new NotificationRecord(mContext,
                generateSbn("a", 1000, 9, 0), mTestNotificationChannel);
        mService.addNotification(pkgA);
        NotificationRecord pkgB = new NotificationRecord(mContext,
                generateSbn("b", 1001, 9, 0), mTestNotificationChannel);
        mService.addNotification(pkgB);

        // on broadcast, hide one of the packages
        mService.simulatePackageDistractionBroadcast(
                PackageManager.RESTRICTION_HIDE_NOTIFICATIONS, new String[] {"a"});
        ArgumentCaptor<List<NotificationRecord>> captorHide = ArgumentCaptor.forClass(List.class);
        verify(mListeners, times(1)).notifyHiddenLocked(captorHide.capture());
        assertEquals(1, captorHide.getValue().size());
        assertEquals("a", captorHide.getValue().get(0).sbn.getPackageName());

        // on broadcast, unhide the package
        mService.simulatePackageDistractionBroadcast(
                PackageManager.RESTRICTION_HIDE_FROM_SUGGESTIONS, new String[] {"a"});
        ArgumentCaptor<List<NotificationRecord>> captorUnhide = ArgumentCaptor.forClass(List.class);
        verify(mListeners, times(1)).notifyUnhiddenLocked(captorUnhide.capture());
        assertEquals(1, captorUnhide.getValue().size());
        assertEquals("a", captorUnhide.getValue().get(0).sbn.getPackageName());
    }

    @Test
    public void testHideAndUnhideNotificationsOnDistractingPackageBroadcast_multiPkg() {
        // Post 2 notifications from 2 packages
        NotificationRecord pkgA = new NotificationRecord(mContext,
                generateSbn("a", 1000, 9, 0), mTestNotificationChannel);
        mService.addNotification(pkgA);
        NotificationRecord pkgB = new NotificationRecord(mContext,
                generateSbn("b", 1001, 9, 0), mTestNotificationChannel);
        mService.addNotification(pkgB);

        // on broadcast, hide one of the packages
        mService.simulatePackageDistractionBroadcast(
                PackageManager.RESTRICTION_HIDE_NOTIFICATIONS, new String[] {"a", "b"});
        ArgumentCaptor<List<NotificationRecord>> captorHide = ArgumentCaptor.forClass(List.class);
        verify(mListeners, times(2)).notifyHiddenLocked(captorHide.capture());
        assertEquals(2, captorHide.getValue().size());
        assertEquals("a", captorHide.getValue().get(0).sbn.getPackageName());
        assertEquals("b", captorHide.getValue().get(1).sbn.getPackageName());

        // on broadcast, unhide the package
        mService.simulatePackageDistractionBroadcast(
                PackageManager.RESTRICTION_HIDE_FROM_SUGGESTIONS, new String[] {"a", "b"});
        ArgumentCaptor<List<NotificationRecord>> captorUnhide = ArgumentCaptor.forClass(List.class);
        verify(mListeners, times(2)).notifyUnhiddenLocked(captorUnhide.capture());
        assertEquals(2, captorUnhide.getValue().size());
        assertEquals("a", captorUnhide.getValue().get(0).sbn.getPackageName());
        assertEquals("b", captorUnhide.getValue().get(1).sbn.getPackageName());
    }

    @Test
    public void testNoNotificationsHiddenOnDistractingPackageBroadcast() {
        // post notification from this package
        final NotificationRecord notif1 = generateNotificationRecord(
                mTestNotificationChannel, 1, null, true);
        mService.addNotification(notif1);

        // on broadcast, nothing is hidden since no notifications are of package "test_package"
        mService.simulatePackageDistractionBroadcast(
                PackageManager.RESTRICTION_HIDE_NOTIFICATIONS, new String[] {"test_package"});
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(mListeners, times(1)).notifyHiddenLocked(captor.capture());
        assertEquals(0, captor.getValue().size());
    }

    @Test
    public void testCanUseManagedServicesLowRamNoWatchNullPkg() {
        when(mPackageManagerClient.hasSystemFeature(FEATURE_WATCH)).thenReturn(false);
        when(mActivityManager.isLowRamDevice()).thenReturn(true);
        when(mResources.getStringArray(R.array.config_allowedManagedServicesOnLowRamDevices))
                .thenReturn(new String[] {"a", "b", "c"});
        when(mContext.getResources()).thenReturn(mResources);

        assertEquals(false, mService.canUseManagedServices(null, 0, null));
    }

    @Test
    public void testCanUseManagedServicesLowRamNoWatchValidPkg() {
        when(mPackageManagerClient.hasSystemFeature(FEATURE_WATCH)).thenReturn(false);
        when(mActivityManager.isLowRamDevice()).thenReturn(true);
        when(mResources.getStringArray(R.array.config_allowedManagedServicesOnLowRamDevices))
                .thenReturn(new String[] {"a", "b", "c"});
        when(mContext.getResources()).thenReturn(mResources);

        assertEquals(true, mService.canUseManagedServices("b", 0, null));
    }

    @Test
    public void testCanUseManagedServicesLowRamNoWatchNoValidPkg() {
        when(mPackageManagerClient.hasSystemFeature(FEATURE_WATCH)).thenReturn(false);
        when(mActivityManager.isLowRamDevice()).thenReturn(true);
        when(mResources.getStringArray(R.array.config_allowedManagedServicesOnLowRamDevices))
                .thenReturn(new String[] {"a", "b", "c"});
        when(mContext.getResources()).thenReturn(mResources);

        assertEquals(false, mService.canUseManagedServices("d", 0, null));
    }

    @Test
    public void testCanUseManagedServicesLowRamWatchNoValidPkg() {
        when(mPackageManagerClient.hasSystemFeature(FEATURE_WATCH)).thenReturn(true);
        when(mActivityManager.isLowRamDevice()).thenReturn(true);
        when(mResources.getStringArray(R.array.config_allowedManagedServicesOnLowRamDevices))
                .thenReturn(new String[] {"a", "b", "c"});
        when(mContext.getResources()).thenReturn(mResources);

        assertEquals(true, mService.canUseManagedServices("d", 0, null));
    }

    @Test
    public void testCanUseManagedServicesNoLowRamNoWatchValidPkg() {
        when(mPackageManagerClient.hasSystemFeature(FEATURE_WATCH)).thenReturn(false);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        when(mResources.getStringArray(R.array.config_allowedManagedServicesOnLowRamDevices))
                .thenReturn(new String[] {"a", "b", "c"});
        when(mContext.getResources()).thenReturn(mResources);

        assertEquals(true, mService.canUseManagedServices("d", 0 , null));
    }

    @Test
    public void testCanUseManagedServicesNoLowRamWatchValidPkg() {
        when(mPackageManagerClient.hasSystemFeature(FEATURE_WATCH)).thenReturn(true);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        when(mResources.getStringArray(R.array.config_allowedManagedServicesOnLowRamDevices))
                .thenReturn(new String[] {"a", "b", "c"});
        when(mContext.getResources()).thenReturn(mResources);

        assertEquals(true, mService.canUseManagedServices("d", 0, null));
    }

    @Test
    public void testCanUseManagedServices_hasPermission() throws Exception {
        when(mPackageManager.checkPermission("perm", "pkg", 0))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        assertEquals(true, mService.canUseManagedServices("pkg", 0, "perm"));
    }

    @Test
    public void testCanUseManagedServices_noPermission() throws Exception {
        when(mPackageManager.checkPermission("perm", "pkg", 0))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        assertEquals(false, mService.canUseManagedServices("pkg", 0, "perm"));
    }

    @Test
    public void testCanUseManagedServices_permDoesNotMatter() {
        assertEquals(true, mService.canUseManagedServices("pkg", 0, null));
    }

    @Test
    public void testOnNotificationVisibilityChanged_triggersInterruptionUsageStat() {
        final NotificationRecord r = generateNotificationRecord(
                mTestNotificationChannel, 1, null, true);
        r.setTextChanged(true);
        mService.addNotification(r);

        mService.mNotificationDelegate.onNotificationVisibilityChanged(new NotificationVisibility[]
                {NotificationVisibility.obtain(r.getKey(), 1, 1, true)},
                new NotificationVisibility[]{});

        verify(mAppUsageStats).reportInterruptiveNotification(anyString(), anyString(), anyInt());
    }

    @Test
    public void testSetNotificationsShownFromListener_triggersInterruptionUsageStat()
            throws RemoteException {
        final NotificationRecord r = generateNotificationRecord(
                mTestNotificationChannel, 1, null, true);
        r.setTextChanged(true);
        mService.addNotification(r);

        mBinderService.setNotificationsShownFromListener(null, new String[] {r.getKey()});

        verify(mAppUsageStats).reportInterruptiveNotification(anyString(), anyString(), anyInt());
    }

    @Test
    public void testMaybeRecordInterruptionLocked_doesNotRecordTwice()
            throws RemoteException {
        final NotificationRecord r = generateNotificationRecord(
                mTestNotificationChannel, 1, null, true);
        r.setInterruptive(true);
        mService.addNotification(r);

        mService.maybeRecordInterruptionLocked(r);
        mService.maybeRecordInterruptionLocked(r);

        verify(mAppUsageStats, times(1)).reportInterruptiveNotification(
                anyString(), anyString(), anyInt());
    }

    @Test
    public void testBubble() throws Exception {
        mBinderService.setBubblesAllowed(PKG, mUid, false);
        assertFalse(mBinderService.areBubblesAllowedForPackage(PKG, mUid));
    }

    @Test
    public void testUserApprovedBubblesForPackage() throws Exception {
        assertFalse(mBinderService.hasUserApprovedBubblesForPackage(PKG, mUid));
        mBinderService.setBubblesAllowed(PKG, mUid, true);
        assertTrue(mBinderService.hasUserApprovedBubblesForPackage(PKG, mUid));
        assertTrue(mBinderService.areBubblesAllowedForPackage(PKG, mUid));
    }

    @Test
    public void testUserRejectsBubblesForPackage() throws Exception {
        assertFalse(mBinderService.hasUserApprovedBubblesForPackage(PKG, mUid));
        mBinderService.setBubblesAllowed(PKG, mUid, false);
        assertTrue(mBinderService.hasUserApprovedBubblesForPackage(PKG, mUid));
        assertFalse(mBinderService.areBubblesAllowedForPackage(PKG, mUid));
    }

    @Test
    public void testIsCallerInstantApp_primaryUser() throws Exception {
        ApplicationInfo info = new ApplicationInfo();
        info.privateFlags = ApplicationInfo.PRIVATE_FLAG_INSTANT;
        when(mPackageManager.getApplicationInfo(anyString(), anyInt(), eq(0))).thenReturn(info);
        when(mPackageManager.getPackagesForUid(anyInt())).thenReturn(new String[]{"any"});

        assertTrue(mService.isCallerInstantApp(45770, 0));

        info.privateFlags = 0;
        assertFalse(mService.isCallerInstantApp(575370, 0));
    }

    @Test
    public void testIsCallerInstantApp_secondaryUser() throws Exception {
        ApplicationInfo info = new ApplicationInfo();
        info.privateFlags = ApplicationInfo.PRIVATE_FLAG_INSTANT;
        when(mPackageManager.getApplicationInfo(anyString(), anyInt(), eq(10))).thenReturn(info);
        when(mPackageManager.getApplicationInfo(anyString(), anyInt(), eq(0))).thenReturn(null);
        when(mPackageManager.getPackagesForUid(anyInt())).thenReturn(new String[]{"any"});

        assertTrue(mService.isCallerInstantApp(68638450, 10));
    }

    @Test
    public void testIsCallerInstantApp_userAllNotification() throws Exception {
        ApplicationInfo info = new ApplicationInfo();
        info.privateFlags = ApplicationInfo.PRIVATE_FLAG_INSTANT;
        when(mPackageManager.getApplicationInfo(anyString(), anyInt(), eq(UserHandle.USER_SYSTEM)))
                .thenReturn(info);
        when(mPackageManager.getPackagesForUid(anyInt())).thenReturn(new String[]{"any"});

        assertTrue(mService.isCallerInstantApp(45770, UserHandle.USER_ALL));

        info.privateFlags = 0;
        assertFalse(mService.isCallerInstantApp(575370, UserHandle.USER_ALL ));
    }

    @Test
    public void testResolveNotificationUid_sameApp_nonSystemUser() throws Exception {
        ApplicationInfo info = new ApplicationInfo();
        info.uid = Binder.getCallingUid();
        when(mPackageManager.getApplicationInfo(anyString(), anyInt(), eq(10))).thenReturn(info);
        when(mPackageManager.getApplicationInfo(anyString(), anyInt(), eq(0))).thenReturn(null);

        int actualUid = mService.resolveNotificationUid("caller", "caller", info.uid, 10);

        assertEquals(info.uid, actualUid);
    }

    @Test
    public void testResolveNotificationUid_sameApp() throws Exception {
        ApplicationInfo info = new ApplicationInfo();
        info.uid = Binder.getCallingUid();
        when(mPackageManager.getApplicationInfo(anyString(), anyInt(), eq(0))).thenReturn(info);

        int actualUid = mService.resolveNotificationUid("caller", "caller", info.uid, 0);

        assertEquals(info.uid, actualUid);
    }

    @Test
    public void testResolveNotificationUid_sameAppDiffPackage() throws Exception {
        ApplicationInfo info = new ApplicationInfo();
        info.uid = Binder.getCallingUid();
        when(mPackageManager.getApplicationInfo(anyString(), anyInt(), eq(0))).thenReturn(info);

        int actualUid = mService.resolveNotificationUid("caller", "callerAlso", info.uid, 0);

        assertEquals(info.uid, actualUid);
    }

    @Test
    public void testResolveNotificationUid_sameAppWrongUid() throws Exception {
        ApplicationInfo info = new ApplicationInfo();
        info.uid = 1356347;
        when(mPackageManager.getApplicationInfo(anyString(), anyInt(), anyInt())).thenReturn(info);

        try {
            mService.resolveNotificationUid("caller", "caller", 9, 0);
            fail("Incorrect uid didn't throw security exception");
        } catch (SecurityException e) {
            // yay
        }
    }

    @Test
    public void testResolveNotificationUid_delegateAllowed() throws Exception {
        int expectedUid = 123;

        when(mPackageManagerClient.getPackageUidAsUser("target", 0)).thenReturn(expectedUid);
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mPreferencesHelper.isDelegateAllowed(anyString(), anyInt(), anyString(), anyInt()))
                .thenReturn(true);

        assertEquals(expectedUid, mService.resolveNotificationUid("caller", "target", 9, 0));
    }

    @Test
    public void testResolveNotificationUid_androidAllowed() throws Exception {
        int expectedUid = 123;

        when(mPackageManagerClient.getPackageUidAsUser("target", 0)).thenReturn(expectedUid);
        // no delegate

        assertEquals(expectedUid, mService.resolveNotificationUid("android", "target", 0, 0));
    }

    @Test
    public void testResolveNotificationUid_delegateNotAllowed() throws Exception {
        when(mPackageManagerClient.getPackageUidAsUser("target", 0)).thenReturn(123);
        // no delegate

        try {
            mService.resolveNotificationUid("caller", "target", 9, 0);
            fail("Incorrect uid didn't throw security exception");
        } catch (SecurityException e) {
            // yay
        }
    }

    @Test
    public void testRemoveForegroundServiceFlagFromNotification_enqueued() {
        Notification n = new Notification.Builder(mContext, "").build();
        n.flags |= FLAG_FOREGROUND_SERVICE;

        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 9, null, mUid, 0,
                n, new UserHandle(mUid), null, 0);
        NotificationRecord r = new NotificationRecord(mContext, sbn, mTestNotificationChannel);

        mService.addEnqueuedNotification(r);

        mInternalService.removeForegroundServiceFlagFromNotification(
                PKG, r.sbn.getId(), r.sbn.getUserId());

        waitForIdle();

        verify(mListeners, timeout(200).times(0)).notifyPostedLocked(any(), any());
    }

    @Test
    public void testRemoveForegroundServiceFlagFromNotification_posted() {
        Notification n = new Notification.Builder(mContext, "").build();
        n.flags |= FLAG_FOREGROUND_SERVICE;

        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 9, null, mUid, 0,
                n, new UserHandle(mUid), null, 0);
        NotificationRecord r = new NotificationRecord(mContext, sbn, mTestNotificationChannel);

        mService.addNotification(r);

        mInternalService.removeForegroundServiceFlagFromNotification(
                PKG, r.sbn.getId(), r.sbn.getUserId());

        waitForIdle();

        ArgumentCaptor<NotificationRecord> captor =
                ArgumentCaptor.forClass(NotificationRecord.class);
        verify(mListeners, times(1)).notifyPostedLocked(captor.capture(), any());

        assertEquals(0, captor.getValue().getNotification().flags);
    }

    @Test
    public void testAllowForegroundToasts() throws Exception {
        final String testPackage = "testPackageName";
        assertEquals(0, mService.mToastQueue.size());
        mService.isSystemUid = false;

        // package is not suspended
        when(mPackageManager.isPackageSuspendedForUser(testPackage, UserHandle.getUserId(mUid)))
                .thenReturn(false);

        // notifications from this package are blocked by the user
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mPreferencesHelper.getImportance(testPackage, mUid)).thenReturn(IMPORTANCE_NONE);

        // this app is in the foreground
        when(mActivityManager.getUidImportance(mUid)).thenReturn(IMPORTANCE_FOREGROUND);

        // enqueue toast -> toast should still enqueue
        ((INotificationManager)mService.mService).enqueueToast(testPackage,
                new TestableToastCallback(), 2000, 0);
        assertEquals(1, mService.mToastQueue.size());
    }

    @Test
    public void testDisallowToastsFromSuspendedPackages() throws Exception {
        final String testPackage = "testPackageName";
        assertEquals(0, mService.mToastQueue.size());
        mService.isSystemUid = false;

        // package is suspended
        when(mPackageManager.isPackageSuspendedForUser(testPackage, UserHandle.getUserId(mUid)))
                .thenReturn(true);

        // notifications from this package are NOT blocked by the user
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mPreferencesHelper.getImportance(testPackage, mUid)).thenReturn(IMPORTANCE_LOW);

        // enqueue toast -> no toasts enqueued
        ((INotificationManager)mService.mService).enqueueToast(testPackage,
                new TestableToastCallback(), 2000, 0);
        assertEquals(0, mService.mToastQueue.size());
    }

    @Test
    public void testDisallowToastsFromBlockedApps() throws Exception {
        final String testPackage = "testPackageName";
        assertEquals(0, mService.mToastQueue.size());
        mService.isSystemUid = false;

        // package is not suspended
        when(mPackageManager.isPackageSuspendedForUser(testPackage, UserHandle.getUserId(mUid)))
                .thenReturn(false);

        // notifications from this package are blocked by the user
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mPreferencesHelper.getImportance(testPackage, mUid)).thenReturn(IMPORTANCE_NONE);

        // this app is NOT in the foreground
        when(mActivityManager.getUidImportance(mUid)).thenReturn(IMPORTANCE_GONE);

        // enqueue toast -> no toasts enqueued
        ((INotificationManager)mService.mService).enqueueToast(testPackage,
                new TestableToastCallback(), 2000, 0);
        assertEquals(0, mService.mToastQueue.size());
    }

    @Test
    public void testAlwaysAllowSystemToasts() throws Exception {
        final String testPackage = "testPackageName";
        assertEquals(0, mService.mToastQueue.size());
        mService.isSystemUid = true;

        // package is suspended
        when(mPackageManager.isPackageSuspendedForUser(testPackage, UserHandle.getUserId(mUid)))
                .thenReturn(true);

        // notifications from this package ARE blocked by the user
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mPreferencesHelper.getImportance(testPackage, mUid)).thenReturn(IMPORTANCE_NONE);

        // this app is NOT in the foreground
        when(mActivityManager.getUidImportance(mUid)).thenReturn(IMPORTANCE_GONE);

        // enqueue toast -> system toast can still be enqueued
        ((INotificationManager)mService.mService).enqueueToast(testPackage,
                new TestableToastCallback(), 2000, 0);
        assertEquals(1, mService.mToastQueue.size());
    }

    @Test
    public void testOnNotificationSmartReplySent() {
        final int replyIndex = 2;
        final String reply = "Hello";
        final boolean modifiedBeforeSending = true;
        final boolean generatedByAssistant = true;

        NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        r.setSuggestionsGeneratedByAssistant(generatedByAssistant);
        mService.addNotification(r);

        mService.mNotificationDelegate.onNotificationSmartReplySent(
                r.getKey(), replyIndex, reply, NOTIFICATION_LOCATION_UNKNOWN,
                modifiedBeforeSending);
        verify(mAssistants).notifyAssistantSuggestedReplySent(
                eq(r.sbn), eq(reply), eq(generatedByAssistant));
    }

    @Test
    public void testOnNotificationActionClick() {
        final int actionIndex = 2;
        final Notification.Action action =
                new Notification.Action.Builder(null, "text", null).build();
        final boolean generatedByAssistant = false;

        NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);

        NotificationVisibility notificationVisibility =
                NotificationVisibility.obtain(r.getKey(), 1, 2, true);
        mService.mNotificationDelegate.onNotificationActionClick(
                10, 10, r.getKey(), actionIndex, action, notificationVisibility,
                generatedByAssistant);
        verify(mAssistants).notifyAssistantActionClicked(
                eq(r.sbn), eq(actionIndex), eq(action), eq(generatedByAssistant));
    }

    @Test
    public void testLogSmartSuggestionsVisible_triggerOnExpandAndVisible() {
        NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);

        mService.mNotificationDelegate.onNotificationExpansionChanged(r.getKey(), false, true,
                NOTIFICATION_LOCATION_UNKNOWN);
        NotificationVisibility[] notificationVisibility = new NotificationVisibility[] {
                NotificationVisibility.obtain(r.getKey(), 0, 0, true)
        };
        mService.mNotificationDelegate.onNotificationVisibilityChanged(notificationVisibility,
                new NotificationVisibility[0]);

        assertEquals(1, mService.countLogSmartSuggestionsVisible);
    }

    @Test
    public void testLogSmartSuggestionsVisible_noTriggerOnExpand() {
        NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);

        mService.mNotificationDelegate.onNotificationExpansionChanged(r.getKey(), false, true,
                NOTIFICATION_LOCATION_UNKNOWN);

        assertEquals(0, mService.countLogSmartSuggestionsVisible);
    }

    @Test
    public void testLogSmartSuggestionsVisible_noTriggerOnVisible() {
        NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);

        NotificationVisibility[] notificationVisibility = new NotificationVisibility[]{
                NotificationVisibility.obtain(r.getKey(), 0, 0, true)
        };
        mService.mNotificationDelegate.onNotificationVisibilityChanged(notificationVisibility,
                new NotificationVisibility[0]);

        assertEquals(0, mService.countLogSmartSuggestionsVisible);
    }

    public void testReportSeen_delegated() {
        Notification.Builder nb =
                new Notification.Builder(mContext, mTestNotificationChannel.getId())
                        .setContentTitle("foo")
                        .setSmallIcon(android.R.drawable.sym_def_app_icon);

        StatusBarNotification sbn = new StatusBarNotification(PKG, "opPkg", 0, "tag", mUid, 0,
                nb.build(), new UserHandle(mUid), null, 0);
        NotificationRecord r =  new NotificationRecord(mContext, sbn, mTestNotificationChannel);

        mService.reportSeen(r);
        verify(mAppUsageStats, never()).reportEvent(anyString(), anyInt(), anyInt());

    }

    @Test
    public void testReportSeen_notDelegated() {
        NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);

        mService.reportSeen(r);
        verify(mAppUsageStats, times(1)).reportEvent(anyString(), anyInt(), anyInt());
    }

    @Test
    public void testNotificationStats_notificationError() {
        NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);

        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 1, "tag", mUid, 0,
                new Notification.Builder(mContext, mTestNotificationChannel.getId()).build(),
                new UserHandle(mUid), null, 0);
        NotificationRecord update = new NotificationRecord(mContext, sbn, mTestNotificationChannel);
        mService.addEnqueuedNotification(update);
        assertNull(update.sbn.getNotification().getSmallIcon());

        NotificationManagerService.PostNotificationRunnable runnable =
                mService.new PostNotificationRunnable(update.getKey());
        runnable.run();
        waitForIdle();

        ArgumentCaptor<NotificationStats> captor = ArgumentCaptor.forClass(NotificationStats.class);
        verify(mListeners).notifyRemovedLocked(any(), anyInt(), captor.capture());
        assertNotNull(captor.getValue());
    }

    @Test
    public void testCanNotifyAsUser_crossUser() throws Exception {
        // same user no problem
        mBinderService.canNotifyAsPackage("src", "target", mContext.getUserId());

        // cross user, no permission, problem
        try {
            mBinderService.canNotifyAsPackage("src", "target", mContext.getUserId() + 1);
            fail("Should not be callable cross user without cross user permission");
        } catch (SecurityException e) {
            // good
        }

        // cross user, with permission, no problem
        TestablePermissions perms = mContext.getTestablePermissions();
        perms.setPermission(android.Manifest.permission.INTERACT_ACROSS_USERS, PERMISSION_GRANTED);
        mBinderService.canNotifyAsPackage("src", "target", mContext.getUserId() + 1);
    }

    @Test
    public void testgetNotificationChannels_crossUser() throws Exception {
        // same user no problem
        mBinderService.getNotificationChannels("src", "target", mContext.getUserId());

        // cross user, no permission, problem
        try {
            mBinderService.getNotificationChannels("src", "target", mContext.getUserId() + 1);
            fail("Should not be callable cross user without cross user permission");
        } catch (SecurityException e) {
            // good
        }

        // cross user, with permission, no problem
        TestablePermissions perms = mContext.getTestablePermissions();
        perms.setPermission(android.Manifest.permission.INTERACT_ACROSS_USERS, PERMISSION_GRANTED);
        mBinderService.getNotificationChannels("src", "target", mContext.getUserId() + 1);
    }

    @Test
    public void setDefaultAssistantForUser_fromConfigXml() {
        clearDeviceConfig();
        ComponentName xmlConfig = new ComponentName("config", "xml");
        when(mResources
                .getString(
                        com.android.internal.R.string.config_defaultAssistantAccessComponent))
                .thenReturn(xmlConfig.flattenToString());
        when(mContext.getResources()).thenReturn(mResources);
        when(mAssistants.queryPackageForServices(eq(null), anyInt(), eq(0)))
                .thenReturn(Collections.singleton(xmlConfig));
        mService.setNotificationAssistantAccessGrantedCallback(
                mNotificationAssistantAccessGrantedCallback);

        mService.setDefaultAssistantForUser(0);

        verify(mNotificationAssistantAccessGrantedCallback)
                .onGranted(eq(xmlConfig), eq(0), eq(true));
    }

    @Test
    public void setDefaultAssistantForUser_fromDeviceConfig() {
        ComponentName xmlConfig = new ComponentName("xml", "config");
        ComponentName deviceConfig = new ComponentName("device", "config");
        setDefaultAssistantInDeviceConfig(deviceConfig.flattenToString());
        when(mResources
                .getString(com.android.internal.R.string.config_defaultAssistantAccessComponent))
                .thenReturn(xmlConfig.flattenToString());
        when(mContext.getResources()).thenReturn(mResources);
        when(mAssistants.queryPackageForServices(eq(null), anyInt(), eq(0)))
                .thenReturn(new ArraySet<>(Arrays.asList(xmlConfig, deviceConfig)));
        mService.setNotificationAssistantAccessGrantedCallback(
                mNotificationAssistantAccessGrantedCallback);

        mService.setDefaultAssistantForUser(0);

        verify(mNotificationAssistantAccessGrantedCallback)
                .onGranted(eq(deviceConfig), eq(0), eq(true));
    }

    @Test
    public void setDefaultAssistantForUser_deviceConfigInvalid() {
        ComponentName xmlConfig = new ComponentName("xml", "config");
        ComponentName deviceConfig = new ComponentName("device", "config");
        setDefaultAssistantInDeviceConfig(deviceConfig.flattenToString());
        when(mResources
                .getString(com.android.internal.R.string.config_defaultAssistantAccessComponent))
                .thenReturn(xmlConfig.flattenToString());
        when(mContext.getResources()).thenReturn(mResources);
        // Only xmlConfig is valid, deviceConfig is not.
        when(mAssistants.queryPackageForServices(eq(null), anyInt(), eq(0)))
                .thenReturn(Collections.singleton(xmlConfig));
        mService.setNotificationAssistantAccessGrantedCallback(
                mNotificationAssistantAccessGrantedCallback);

        mService.setDefaultAssistantForUser(0);

        verify(mNotificationAssistantAccessGrantedCallback)
                .onGranted(eq(xmlConfig), eq(0), eq(true));
    }

    @Test
    public void testFlagBubble() throws RemoteException {
        // Bubbles are allowed!
        setUpPrefsForBubbles(true /* global */, true /* app */, true /* channel */);

        // Notif with bubble metadata but not our other misc requirements
        NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel,
                null /* tvExtender */, true /* isBubble */);

        // Say we're foreground
        when(mActivityManager.getPackageImportance(nr.sbn.getPackageName())).thenReturn(
                IMPORTANCE_FOREGROUND);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                nr.sbn.getId(), nr.sbn.getNotification(), nr.sbn.getUserId());
        waitForIdle();

        StatusBarNotification[] notifs = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifs.length);
        assertTrue((notifs[0].getNotification().flags & FLAG_BUBBLE) != 0);
        assertTrue(mService.getNotificationRecord(
                nr.sbn.getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubble_noFlag_appNotAllowed() throws RemoteException {
        // Bubbles are allowed!
        setUpPrefsForBubbles(true /* global */, false /* app */, true /* channel */);

        // Notif with bubble metadata but not our other misc requirements
        NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel,
                null /* tvExtender */, true /* isBubble */);

        // Say we're foreground
        when(mActivityManager.getPackageImportance(nr.sbn.getPackageName())).thenReturn(
                IMPORTANCE_FOREGROUND);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                nr.sbn.getId(), nr.sbn.getNotification(), nr.sbn.getUserId());
        waitForIdle();

        StatusBarNotification[] notifs = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifs.length);
        assertEquals((notifs[0].getNotification().flags & FLAG_BUBBLE), 0);
        assertFalse(mService.getNotificationRecord(
                nr.sbn.getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubbleNotifs_flag_appForeground() throws RemoteException {
        // Bubbles are allowed!
        setUpPrefsForBubbles(true /* global */, true /* app */, true /* channel */);

        // Notif with bubble metadata but not our other misc requirements
        NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel,
                null /* tvExtender */, true /* isBubble */);

        // Say we're foreground
        when(mActivityManager.getPackageImportance(nr.sbn.getPackageName())).thenReturn(
                IMPORTANCE_FOREGROUND);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                nr.sbn.getId(), nr.sbn.getNotification(), nr.sbn.getUserId());
        waitForIdle();

        // yes allowed, yes foreground, yes bubble
        assertTrue(mService.getNotificationRecord(
                nr.sbn.getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubbleNotifs_noFlag_appNotForeground() throws RemoteException {
        // Bubbles are allowed!
        setUpPrefsForBubbles(true /* global */, true /* app */, true /* channel */);

        // Notif with bubble metadata but not our other misc requirements
        NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel,
                null /* tvExtender */, true /* isBubble */);

        // Make sure we're NOT foreground
        when(mActivityManager.getPackageImportance(nr.sbn.getPackageName())).thenReturn(
                IMPORTANCE_VISIBLE);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                nr.sbn.getId(), nr.sbn.getNotification(), nr.sbn.getUserId());
        waitForIdle();

        // yes allowed but NOT foreground, no bubble
        assertFalse(mService.getNotificationRecord(
                nr.sbn.getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubbleNotifs_flag_previousForegroundFlag() throws RemoteException {
        // Bubbles are allowed!
        setUpPrefsForBubbles(true /* global */, true /* app */, true /* channel */);

        // Notif with bubble metadata but not our other misc requirements
        NotificationRecord nr1 = generateNotificationRecord(mTestNotificationChannel,
                null /* tvExtender */, true /* isBubble */);

        // Send notif when we're foreground
        when(mActivityManager.getPackageImportance(nr1.sbn.getPackageName())).thenReturn(
                IMPORTANCE_FOREGROUND);
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                nr1.sbn.getId(), nr1.sbn.getNotification(), nr1.sbn.getUserId());
        waitForIdle();

        // yes allowed, yes foreground, yes bubble
        assertTrue(mService.getNotificationRecord(
                nr1.sbn.getKey()).getNotification().isBubbleNotification());

        // Send a new update when we're not foreground
        NotificationRecord nr2 = generateNotificationRecord(mTestNotificationChannel,
                null /* tvExtender */, true /* isBubble */);

        when(mActivityManager.getPackageImportance(nr2.sbn.getPackageName())).thenReturn(
                IMPORTANCE_VISIBLE);
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                nr2.sbn.getId(), nr2.sbn.getNotification(), nr2.sbn.getUserId());
        waitForIdle();

        // yes allowed, previously foreground / flagged, yes bubble
        assertTrue(mService.getNotificationRecord(
                nr2.sbn.getKey()).getNotification().isBubbleNotification());

        StatusBarNotification[] notifs2 = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifs2.length);
        assertEquals(1, mService.getNotificationRecordCount());
    }

    @Test
    public void testFlagBubbleNotifs_noFlag_previousForegroundFlag_afterRemoval()
            throws RemoteException {
        // Bubbles are allowed!
        setUpPrefsForBubbles(true /* global */, true /* app */, true /* channel */);

        // Notif with bubble metadata but not our other misc requirements
        NotificationRecord nr1 = generateNotificationRecord(mTestNotificationChannel,
                null /* tvExtender */, true /* isBubble */);

        // Send notif when we're foreground
        when(mActivityManager.getPackageImportance(nr1.sbn.getPackageName())).thenReturn(
                IMPORTANCE_FOREGROUND);
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                nr1.sbn.getId(), nr1.sbn.getNotification(), nr1.sbn.getUserId());
        waitForIdle();

        // yes allowed, yes foreground, yes bubble
        assertTrue(mService.getNotificationRecord(
                nr1.sbn.getKey()).getNotification().isBubbleNotification());

        // Remove the bubble
        mBinderService.cancelNotificationWithTag(PKG, "tag", nr1.sbn.getId(),
                nr1.sbn.getUserId());
        waitForIdle();

        StatusBarNotification[] notifs = mBinderService.getActiveNotifications(PKG);
        assertEquals(0, notifs.length);
        assertEquals(0, mService.getNotificationRecordCount());

        // Send a new update when we're not foreground
        NotificationRecord nr2 = generateNotificationRecord(mTestNotificationChannel,
                null /* tvExtender */, true /* isBubble */);

        when(mActivityManager.getPackageImportance(nr2.sbn.getPackageName())).thenReturn(
                IMPORTANCE_VISIBLE);
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                nr2.sbn.getId(), nr2.sbn.getNotification(), nr2.sbn.getUserId());
        waitForIdle();

        // yes allowed, but was removed & no foreground, so no bubble
        assertFalse(mService.getNotificationRecord(
                nr2.sbn.getKey()).getNotification().isBubbleNotification());

        StatusBarNotification[] notifs2 = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifs2.length);
        assertEquals(1, mService.getNotificationRecordCount());
    }

    @Test
    public void testFlagBubbleNotifs_flag_messaging() throws RemoteException {
        // Bubbles are allowed!
        setUpPrefsForBubbles(true /* global */, true /* app */, true /* channel */);

        // Give it bubble metadata
        Notification.BubbleMetadata data = getBasicBubbleMetadataBuilder().build();
        // Give it a person
        Person person = new Person.Builder()
                .setName("bubblebot")
                .build();
        // It needs remote input to be bubble-able
        RemoteInput remoteInput = new RemoteInput.Builder("reply_key").setLabel("reply").build();
        PendingIntent inputIntent = PendingIntent.getActivity(mContext, 0, new Intent(), 0);
        Icon icon = Icon.createWithResource(mContext, android.R.drawable.sym_def_app_icon);
        Notification.Action replyAction = new Notification.Action.Builder(icon, "Reply",
                inputIntent).addRemoteInput(remoteInput)
                .build();
        // Make it messaging style
        Notification.Builder nb = new Notification.Builder(mContext,
                mTestNotificationChannel.getId())
                .setContentTitle("foo")
                .setBubbleMetadata(data)
                .setStyle(new Notification.MessagingStyle(person)
                        .setConversationTitle("Bubble Chat")
                        .addMessage("Hello?",
                                SystemClock.currentThreadTimeMillis() - 300000, person)
                        .addMessage("Is it me you're looking for?",
                                SystemClock.currentThreadTimeMillis(), person)
                )
                .setActions(replyAction)
                .setSmallIcon(android.R.drawable.sym_def_app_icon);

        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 1, null, mUid, 0,
                nb.build(), new UserHandle(mUid), null, 0);
        NotificationRecord nr = new NotificationRecord(mContext, sbn, mTestNotificationChannel);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, null,
                nr.sbn.getId(), nr.sbn.getNotification(), nr.sbn.getUserId());
        waitForIdle();

        // yes allowed, yes messaging, yes bubble
        assertTrue(mService.getNotificationRecord(
                sbn.getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubbleNotifs_flag_phonecall() throws RemoteException {
        // Bubbles are allowed!
        setUpPrefsForBubbles(true /* global */, true /* app */, true /* channel */);

        // Give it bubble metadata
        Notification.BubbleMetadata data = getBasicBubbleMetadataBuilder().build();
        // Give it a person
        Person person = new Person.Builder()
                .setName("bubblebot")
                .build();
        // Make it a phone call
        Notification.Builder nb = new Notification.Builder(mContext,
                mTestNotificationChannel.getId())
                .setCategory(CATEGORY_CALL)
                .addPerson(person)
                .setContentTitle("foo")
                .setBubbleMetadata(data)
                .setSmallIcon(android.R.drawable.sym_def_app_icon);

        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 1, null, mUid, 0,
                nb.build(), new UserHandle(mUid), null, 0);
        // Make sure it has foreground service
        sbn.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        NotificationRecord nr = new NotificationRecord(mContext, sbn, mTestNotificationChannel);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, null,
                nr.sbn.getId(), nr.sbn.getNotification(), nr.sbn.getUserId());
        waitForIdle();

        // yes phone call, yes person, yes foreground service, yes bubble
        assertTrue(mService.getNotificationRecord(
                sbn.getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubbleNotifs_noFlag_phonecall_noForegroundService() throws RemoteException {
        // Bubbles are allowed!
        setUpPrefsForBubbles(true /* global */, true /* app */, true /* channel */);

        // Give it bubble metadata
        Notification.BubbleMetadata data = getBasicBubbleMetadataBuilder().build();
        // Give it a person
        Person person = new Person.Builder()
                .setName("bubblebot")
                .build();
        // Make it a phone call
        Notification.Builder nb = new Notification.Builder(mContext,
                mTestNotificationChannel.getId())
                .setCategory(CATEGORY_CALL)
                .addPerson(person)
                .setContentTitle("foo")
                .setBubbleMetadata(data)
                .setSmallIcon(android.R.drawable.sym_def_app_icon);

        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 1, null, mUid, 0,
                nb.build(), new UserHandle(mUid), null, 0);
        NotificationRecord nr = new NotificationRecord(mContext, sbn, mTestNotificationChannel);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, null,
                nr.sbn.getId(), nr.sbn.getNotification(), nr.sbn.getUserId());
        waitForIdle();

        // yes phone call, yes person, NO foreground service, no bubble
        assertFalse(mService.getNotificationRecord(
                sbn.getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubbleNotifs_noFlag_phonecall_noPerson() throws RemoteException {
        // Bubbles are allowed!
        setUpPrefsForBubbles(true /* global */, true /* app */, true /* channel */);

        // Give it bubble metadata
        Notification.BubbleMetadata data = getBasicBubbleMetadataBuilder().build();
        // Make it a phone call
        Notification.Builder nb = new Notification.Builder(mContext,
                mTestNotificationChannel.getId())
                .setCategory(CATEGORY_CALL)
                .setContentTitle("foo")
                .setBubbleMetadata(data)
                .setSmallIcon(android.R.drawable.sym_def_app_icon);

        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 1, null, mUid, 0,
                nb.build(), new UserHandle(mUid), null, 0);
        // Make sure it has foreground service
        sbn.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        NotificationRecord nr = new NotificationRecord(mContext, sbn, mTestNotificationChannel);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, null,
                nr.sbn.getId(), nr.sbn.getNotification(), nr.sbn.getUserId());
        waitForIdle();

        // yes phone call, yes foreground service, BUT NO person, no bubble
        assertFalse(mService.getNotificationRecord(
                sbn.getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubbleNotifs_noFlag_phonecall_noCategory() throws RemoteException {
        // Bubbles are allowed!
        setUpPrefsForBubbles(true /* global */, true /* app */, true /* channel */);

        // Give it bubble metadata
        Notification.BubbleMetadata data = getBasicBubbleMetadataBuilder().build();
        // Give it a person
        Person person = new Person.Builder()
                .setName("bubblebot")
                .build();
        // No category
        Notification.Builder nb = new Notification.Builder(mContext,
                mTestNotificationChannel.getId())
                .addPerson(person)
                .setContentTitle("foo")
                .setBubbleMetadata(data)
                .setSmallIcon(android.R.drawable.sym_def_app_icon);

        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 1, null, mUid, 0,
                nb.build(), new UserHandle(mUid), null, 0);
        // Make sure it has foreground service
        sbn.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        NotificationRecord nr = new NotificationRecord(mContext, sbn, mTestNotificationChannel);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, null,
                nr.sbn.getId(), nr.sbn.getNotification(), nr.sbn.getUserId());
        waitForIdle();

        // yes person, yes foreground service, BUT NO call, no bubble
        assertFalse(mService.getNotificationRecord(
                sbn.getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubbleNotifs_noFlag_messaging_appNotAllowed() throws RemoteException {
        // Bubbles are NOT allowed!
        setUpPrefsForBubbles(false /* global */, true /* app */, true /* channel */);

        // Give it bubble metadata
        Notification.BubbleMetadata data = getBasicBubbleMetadataBuilder().build();
        // Give it a person
        Person person = new Person.Builder()
                .setName("bubblebot")
                .build();
        // Make it messaging style
        Notification.Builder nb = new Notification.Builder(mContext,
                mTestNotificationChannel.getId())
                .setContentTitle("foo")
                .setBubbleMetadata(data)
                .setStyle(new Notification.MessagingStyle(person)
                        .setConversationTitle("Bubble Chat")
                        .addMessage("Hello?",
                                SystemClock.currentThreadTimeMillis() - 300000, person)
                        .addMessage("Is it me you're looking for?",
                                SystemClock.currentThreadTimeMillis(), person)
                )
                .setSmallIcon(android.R.drawable.sym_def_app_icon);

        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 1, null, mUid, 0,
                nb.build(), new UserHandle(mUid), null, 0);
        NotificationRecord nr = new NotificationRecord(mContext, sbn, mTestNotificationChannel);

        // Post the notification
        mBinderService.enqueueNotificationWithTag(PKG, PKG, null,
                nr.sbn.getId(), nr.sbn.getNotification(), nr.sbn.getUserId());
        waitForIdle();

        // not allowed, no bubble
        assertFalse(mService.getNotificationRecord(
                sbn.getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubbleNotifs_noFlag_notBubble() throws RemoteException {
        // Bubbles are allowed!
        setUpPrefsForBubbles(true /* global */, true /* app */, true /* channel */);

        // Notif WITHOUT bubble metadata
        NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel);

        // Post the notification
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                nr.sbn.getId(), nr.sbn.getNotification(), nr.sbn.getUserId());
        waitForIdle();

        // no bubble metadata, no bubble
        assertFalse(mService.getNotificationRecord(
                nr.sbn.getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubbleNotifs_noFlag_messaging_channelNotAllowed() throws RemoteException {
        // Bubbles are allowed except on this channel
        setUpPrefsForBubbles(true /* global */, true /* app */, false /* channel */);

        // Give it bubble metadata
        Notification.BubbleMetadata data = getBasicBubbleMetadataBuilder().build();
        // Give it a person
        Person person = new Person.Builder()
                .setName("bubblebot")
                .build();
        // Make it messaging style
        Notification.Builder nb = new Notification.Builder(mContext,
                mTestNotificationChannel.getId())
                .setContentTitle("foo")
                .setBubbleMetadata(data)
                .setStyle(new Notification.MessagingStyle(person)
                        .setConversationTitle("Bubble Chat")
                        .addMessage("Hello?",
                                SystemClock.currentThreadTimeMillis() - 300000, person)
                        .addMessage("Is it me you're looking for?",
                                SystemClock.currentThreadTimeMillis(), person)
                )
                .setSmallIcon(android.R.drawable.sym_def_app_icon);

        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 1, null, mUid, 0,
                nb.build(), new UserHandle(mUid), null, 0);
        NotificationRecord nr = new NotificationRecord(mContext, sbn, mTestNotificationChannel);

        // Post the notification
        mBinderService.enqueueNotificationWithTag(PKG, PKG, null,
                nr.sbn.getId(), nr.sbn.getNotification(), nr.sbn.getUserId());
        waitForIdle();

        // channel not allowed, no bubble
        assertFalse(mService.getNotificationRecord(
                sbn.getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubbleNotifs_noFlag_phonecall_notAllowed() throws RemoteException {
        // Bubbles are not allowed!
        setUpPrefsForBubbles(false /* global */, true /* app */, true /* channel */);

        // Give it bubble metadata
        Notification.BubbleMetadata data = getBasicBubbleMetadataBuilder().build();
        // Give it a person
        Person person = new Person.Builder()
                .setName("bubblebot")
                .build();
        // Make it a phone call
        Notification.Builder nb = new Notification.Builder(mContext,
                mTestNotificationChannel.getId())
                .setCategory(CATEGORY_CALL)
                .addPerson(person)
                .setContentTitle("foo")
                .setBubbleMetadata(data)
                .setSmallIcon(android.R.drawable.sym_def_app_icon);

        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 1, null, mUid, 0,
                nb.build(), new UserHandle(mUid), null, 0);
        // Make sure it has foreground service
        sbn.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        NotificationRecord nr = new NotificationRecord(mContext, sbn, mTestNotificationChannel);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, null,
                nr.sbn.getId(), nr.sbn.getNotification(), nr.sbn.getUserId());
        waitForIdle();

        // yes phone call, yes person, yes foreground service, but not allowed, no bubble
        assertFalse(mService.getNotificationRecord(
                sbn.getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubbleNotifs_noFlag_phonecall_channelNotAllowed() throws RemoteException {
        // Bubbles are allowed, but not on channel.
        setUpPrefsForBubbles(true /* global */, true /* app */, false /* channel */);

        // Give it bubble metadata
        Notification.BubbleMetadata data = getBasicBubbleMetadataBuilder().build();
        // Give it a person
        Person person = new Person.Builder()
                .setName("bubblebot")
                .build();
        // Make it a phone call
        Notification.Builder nb = new Notification.Builder(mContext,
                mTestNotificationChannel.getId())
                .setCategory(CATEGORY_CALL)
                .addPerson(person)
                .setContentTitle("foo")
                .setBubbleMetadata(data)
                .setSmallIcon(android.R.drawable.sym_def_app_icon);

        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 1, null, mUid, 0,
                nb.build(), new UserHandle(mUid), null, 0);
        // Make sure it has foreground service
        sbn.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        NotificationRecord nr = new NotificationRecord(mContext, sbn, mTestNotificationChannel);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, null,
                nr.sbn.getId(), nr.sbn.getNotification(), nr.sbn.getUserId());
        waitForIdle();

        // yes phone call, yes person, yes foreground service, but channel not allowed, no bubble
        assertFalse(mService.getNotificationRecord(
                sbn.getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testCancelAllNotifications_cancelsBubble() throws Exception {
        final NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel);
        nr.sbn.getNotification().flags |= FLAG_BUBBLE;
        mService.addNotification(nr);

        mBinderService.cancelAllNotifications(PKG, nr.sbn.getUserId());
        waitForIdle();

        StatusBarNotification[] notifs = mBinderService.getActiveNotifications(PKG);
        assertEquals(0, notifs.length);
        assertEquals(0, mService.getNotificationRecordCount());
    }

    @Test
    public void testAppCancelNotifications_cancelsBubbles() throws Exception {
        final NotificationRecord nrBubble = generateNotificationRecord(mTestNotificationChannel);
        nrBubble.sbn.getNotification().flags |= FLAG_BUBBLE;

        // Post the notification
        mBinderService.enqueueNotificationWithTag(PKG, PKG, null,
                nrBubble.sbn.getId(), nrBubble.sbn.getNotification(), nrBubble.sbn.getUserId());
        waitForIdle();

        StatusBarNotification[] notifs = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifs.length);
        assertEquals(1, mService.getNotificationRecordCount());

        mBinderService.cancelNotificationWithTag(PKG, null, nrBubble.sbn.getId(),
                nrBubble.sbn.getUserId());
        waitForIdle();

        StatusBarNotification[] notifs2 = mBinderService.getActiveNotifications(PKG);
        assertEquals(0, notifs2.length);
        assertEquals(0, mService.getNotificationRecordCount());
    }

    @Test
    public void testCancelAllNotificationsFromListener_ignoresBubbles() throws Exception {
        final NotificationRecord nrNormal = generateNotificationRecord(mTestNotificationChannel);
        final NotificationRecord nrBubble = generateNotificationRecord(mTestNotificationChannel);
        nrBubble.sbn.getNotification().flags |= FLAG_BUBBLE;

        mService.addNotification(nrNormal);
        mService.addNotification(nrBubble);

        mService.getBinderService().cancelNotificationsFromListener(null, null);
        waitForIdle();

        StatusBarNotification[] notifs = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifs.length);
        assertEquals(1, mService.getNotificationRecordCount());
    }

    @Test
    public void testCancelNotificationsFromListener_ignoresBubbles() throws Exception {
        final NotificationRecord nrNormal = generateNotificationRecord(mTestNotificationChannel);
        final NotificationRecord nrBubble = generateNotificationRecord(mTestNotificationChannel);
        nrBubble.sbn.getNotification().flags |= FLAG_BUBBLE;

        mService.addNotification(nrNormal);
        mService.addNotification(nrBubble);

        String[] keys = {nrNormal.sbn.getKey(), nrBubble.sbn.getKey()};
        mService.getBinderService().cancelNotificationsFromListener(null, keys);
        waitForIdle();

        StatusBarNotification[] notifs = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifs.length);
        assertEquals(1, mService.getNotificationRecordCount());
    }

    @Test
    public void testGetAllowedAssistantAdjustments() throws Exception {
        List<String> capabilities = mBinderService.getAllowedAssistantAdjustments(null);
        assertNotNull(capabilities);

        for (int i = capabilities.size() - 1; i >= 0; i--) {
            String capability = capabilities.get(i);
            mBinderService.disallowAssistantAdjustment(capability);
            assertEquals(i + 1, mBinderService.getAllowedAssistantAdjustments(null).size());
            List<String> currentCapabilities = mBinderService.getAllowedAssistantAdjustments(null);
            assertNotNull(currentCapabilities);
            assertFalse(currentCapabilities.contains(capability));
        }
    }

    @Test
    public void testAdjustRestrictedKey() throws Exception {
        NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);
        when(mAssistants.isSameUser(any(), anyInt())).thenReturn(true);

        when(mAssistants.isAdjustmentAllowed(KEY_IMPORTANCE)).thenReturn(true);
        when(mAssistants.isAdjustmentAllowed(KEY_USER_SENTIMENT)).thenReturn(false);

        Bundle signals = new Bundle();
        signals.putInt(KEY_IMPORTANCE, IMPORTANCE_LOW);
        signals.putInt(KEY_USER_SENTIMENT, USER_SENTIMENT_NEGATIVE);
        Adjustment adjustment = new Adjustment(r.sbn.getPackageName(), r.getKey(), signals,
               "", r.getUser().getIdentifier());

        mBinderService.applyAdjustmentFromAssistant(null, adjustment);
        r.applyAdjustments();

        assertEquals(IMPORTANCE_LOW, r.getAssistantImportance());
        assertEquals(USER_SENTIMENT_NEUTRAL, r.getUserSentiment());
    }

    @Test
    public void testAutomaticZenRuleValidation_policyFilterAgreement() throws Exception {
        when(mConditionProviders.isPackageOrComponentAllowed(anyString(), anyInt()))
                .thenReturn(true);
        mService.setZenHelper(mock(ZenModeHelper.class));
        ComponentName owner = new ComponentName(mContext, this.getClass());
        ZenPolicy zenPolicy = new ZenPolicy.Builder().allowAlarms(true).build();
        boolean isEnabled = true;
        AutomaticZenRule rule = new AutomaticZenRule("test", owner, owner, mock(Uri.class),
                zenPolicy, NotificationManager.INTERRUPTION_FILTER_NONE, isEnabled);

        try {
            mBinderService.addAutomaticZenRule(rule);
            fail("Zen policy only applies to priority only mode");
        } catch (IllegalArgumentException e) {
            // yay
        }

        rule = new AutomaticZenRule("test", owner, owner, mock(Uri.class),
                zenPolicy, NotificationManager.INTERRUPTION_FILTER_PRIORITY, isEnabled);
        mBinderService.addAutomaticZenRule(rule);

        rule = new AutomaticZenRule("test", owner, owner, mock(Uri.class),
                null, NotificationManager.INTERRUPTION_FILTER_NONE, isEnabled);
        mBinderService.addAutomaticZenRule(rule);
    }

    @Test
    public void testAreNotificationsEnabledForPackage_crossUser() throws Exception {
        try {
            mBinderService.areNotificationsEnabledForPackage(mContext.getPackageName(),
                    mUid + UserHandle.PER_USER_RANGE);
            fail("Cannot call cross user without permission");
        } catch (SecurityException e) {
            // pass
        }

        // cross user, with permission, no problem
        TestablePermissions perms = mContext.getTestablePermissions();
        perms.setPermission(android.Manifest.permission.INTERACT_ACROSS_USERS, PERMISSION_GRANTED);
        mBinderService.areNotificationsEnabledForPackage(mContext.getPackageName(),
                mUid + UserHandle.PER_USER_RANGE);
    }

    @Test
    public void testAreBubblesAllowedForPackage_crossUser() throws Exception {
        try {
            mBinderService.areBubblesAllowedForPackage(mContext.getPackageName(),
                    mUid + UserHandle.PER_USER_RANGE);
            fail("Cannot call cross user without permission");
        } catch (SecurityException e) {
            // pass
        }

        // cross user, with permission, no problem
        TestablePermissions perms = mContext.getTestablePermissions();
        perms.setPermission(android.Manifest.permission.INTERACT_ACROSS_USERS, PERMISSION_GRANTED);
        mBinderService.areBubblesAllowedForPackage(mContext.getPackageName(),
                mUid + UserHandle.PER_USER_RANGE);
    }

    @Test
    public void testNotificationBubbleChanged_false() throws Exception {
        // Bubbles are allowed!
        setUpPrefsForBubbles(true /* global */, true /* app */, true /* channel */);

        // Notif with bubble metadata but not our other misc requirements
        NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel,
                null /* tvExtender */, true /* isBubble */);

        // Say we're foreground
        when(mActivityManager.getPackageImportance(nr.sbn.getPackageName())).thenReturn(
                IMPORTANCE_FOREGROUND);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                nr.sbn.getId(), nr.sbn.getNotification(), nr.sbn.getUserId());
        waitForIdle();

        // Reset as this is called when the notif is first sent
        reset(mListeners);

        // First we were a bubble
        StatusBarNotification[] notifsBefore = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifsBefore.length);
        assertTrue((notifsBefore[0].getNotification().flags & FLAG_BUBBLE) != 0);

        // Notify we're not a bubble
        mService.mNotificationDelegate.onNotificationBubbleChanged(nr.getKey(), false);
        waitForIdle();

        // Make sure we are not a bubble
        StatusBarNotification[] notifsAfter = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifsAfter.length);
        assertEquals((notifsAfter[0].getNotification().flags & FLAG_BUBBLE), 0);
    }

    @Test
    public void testNotificationBubbleChanged_true() throws Exception {
        // Bubbles are allowed!
        setUpPrefsForBubbles(true /* global */, true /* app */, true /* channel */);

        // Plain notification that has bubble metadata
        NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel,
                null /* tvExtender */, true /* isBubble */);
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                nr.sbn.getId(), nr.sbn.getNotification(), nr.sbn.getUserId());
        waitForIdle();

        // Would be a normal notification because wouldn't have met requirements to bubble
        StatusBarNotification[] notifsBefore = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifsBefore.length);
        assertEquals((notifsBefore[0].getNotification().flags & FLAG_BUBBLE), 0);

        // Make the package foreground so that we're allowed to be a bubble
        when(mActivityManager.getPackageImportance(nr.sbn.getPackageName())).thenReturn(
                IMPORTANCE_FOREGROUND);

        // Reset as this is called when the notif is first sent
        reset(mListeners);

        // Notify we are now a bubble
        mService.mNotificationDelegate.onNotificationBubbleChanged(nr.getKey(), true);
        waitForIdle();

        // Make sure we are a bubble
        StatusBarNotification[] notifsAfter = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifsAfter.length);
        assertTrue((notifsAfter[0].getNotification().flags & FLAG_BUBBLE) != 0);
    }

    @Test
    public void testNotificationBubbleChanged_true_notAllowed() throws Exception {
        // Bubbles are allowed!
        setUpPrefsForBubbles(true /* global */, true /* app */, true /* channel */);

        // Notif that is not a bubble
        NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel,
                null /* tvExtender */, true /* isBubble */);
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                nr.sbn.getId(), nr.sbn.getNotification(), nr.sbn.getUserId());
        waitForIdle();

        // Reset as this is called when the notif is first sent
        reset(mListeners);

        // Would be a normal notification because wouldn't have met requirements to bubble
        StatusBarNotification[] notifsBefore = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifsBefore.length);
        assertEquals((notifsBefore[0].getNotification().flags & FLAG_BUBBLE), 0);

        // Notify we are now a bubble
        mService.mNotificationDelegate.onNotificationBubbleChanged(nr.getKey(), true);
        waitForIdle();

        // We still wouldn't be a bubble because the notification didn't meet requirements
        StatusBarNotification[] notifsAfter = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifsAfter.length);
        assertEquals((notifsAfter[0].getNotification().flags & FLAG_BUBBLE), 0);
    }

    @Test
    public void testNotificationBubbles_disabled_lowRamDevice() throws Exception {
        // Bubbles are allowed!
        setUpPrefsForBubbles(true /* global */, true /* app */, true /* channel */);

        // Plain notification that has bubble metadata
        NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel,
                null /* tvExtender */, true /* isBubble */);
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                nr.sbn.getId(), nr.sbn.getNotification(), nr.sbn.getUserId());
        waitForIdle();

        // Would be a normal notification because wouldn't have met requirements to bubble
        StatusBarNotification[] notifsBefore = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifsBefore.length);
        assertEquals((notifsBefore[0].getNotification().flags & FLAG_BUBBLE), 0);

        // Make the package foreground so that we're allowed to be a bubble
        when(mActivityManager.getPackageImportance(nr.sbn.getPackageName())).thenReturn(
                IMPORTANCE_FOREGROUND);

        // And we are low ram
        when(mActivityManager.isLowRamDevice()).thenReturn(true);

        // We wouldn't be a bubble because the notification didn't meet requirements (low ram)
        StatusBarNotification[] notifsAfter = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifsAfter.length);
        assertEquals((notifsAfter[0].getNotification().flags & FLAG_BUBBLE), 0);
    }
}
