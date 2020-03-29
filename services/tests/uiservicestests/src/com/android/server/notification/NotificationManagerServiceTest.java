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
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
import static android.app.Notification.FLAG_AUTO_CANCEL;
import static android.app.Notification.FLAG_BUBBLE;
import static android.app.Notification.FLAG_FOREGROUND_SERVICE;
import static android.app.NotificationManager.EXTRA_BLOCKED_STATE;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MAX;
import static android.app.NotificationManager.IMPORTANCE_NONE;
import static android.app.NotificationManager.IMPORTANCE_UNSPECIFIED;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_CALLS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_CONVERSATIONS;
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
import static android.os.UserHandle.USER_SYSTEM;
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

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
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
import android.app.StatsManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.usage.UsageStatsManagerInternal;
import android.companion.ICompanionDeviceManager;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ShortcutInfo;
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
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.MediaStore;
import android.provider.Settings;
import android.service.notification.Adjustment;
import android.service.notification.ConversationChannelWrapper;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.service.notification.ZenPolicy;
import android.telephony.TelephonyManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.testing.TestablePermissions;
import android.testing.TestableResources;
import android.text.Html;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Xml;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.logging.InstanceIdSequence;
import com.android.internal.logging.InstanceIdSequenceFake;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.util.FastXmlSerializer;
import com.android.server.DeviceIdleInternal;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.UiServiceTestCase;
import com.android.server.lights.LightsManager;
import com.android.server.lights.LogicalLight;
import com.android.server.notification.NotificationManagerService.NotificationAssistants;
import com.android.server.notification.NotificationManagerService.NotificationListeners;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

    private final int mUid = Binder.getCallingUid();
    private TestableNotificationManagerService mService;
    private INotificationManager mBinderService;
    private NotificationManagerInternal mInternalService;
    private TestableBubbleChecker mTestableBubbleChecker;
    private ShortcutHelper mShortcutHelper;
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
    private LauncherApps mLauncherApps;
    @Mock
    ActivityManager mActivityManager;
    @Mock
    Resources mResources;
    @Mock
    RankingHandler mRankingHandler;

    private static final int MAX_POST_DELAY = 1000;

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
    ActivityTaskManagerInternal mAtm;
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
    @Mock
    NotificationHistoryManager mHistoryManager;
    @Mock
    StatsManager mStatsManager;
    NotificationRecordLoggerFake mNotificationRecordLogger = new NotificationRecordLoggerFake();
    private InstanceIdSequence mNotificationInstanceIdSequence = new InstanceIdSequenceFake(
            1 << 30);
    @Mock
    StatusBarManagerInternal mStatusBar;

    // Use a Testable subclass so we can simulate calls from the system without failing.
    private static class TestableNotificationManagerService extends NotificationManagerService {
        int countSystemChecks = 0;
        boolean isSystemUid = true;
        int countLogSmartSuggestionsVisible = 0;
        @Nullable
        NotificationAssistantAccessGrantedCallback mNotificationAssistantAccessGrantedCallback;

        TestableNotificationManagerService(Context context, NotificationRecordLogger logger,
                InstanceIdSequence notificationInstanceIdSequence) {
            super(context, logger, notificationInstanceIdSequence);
        }

        RankingHelper getRankingHelper() {
            return mRankingHelper;
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
    }

    private class TestableBubbleChecker extends BubbleExtractor.BubbleChecker {

        TestableBubbleChecker(Context context, ShortcutHelper helper, RankingConfig config,
                ActivityManager manager) {
            super(context, helper, config, manager);
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
        // Shell permisssions will override permissions of our app, so add all necessary permissions
        // for this test here:
        InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                "android.permission.WRITE_DEVICE_CONFIG",
                "android.permission.READ_DEVICE_CONFIG",
                "android.permission.READ_CONTACTS");

        MockitoAnnotations.initMocks(this);

        DeviceIdleInternal deviceIdleInternal = mock(DeviceIdleInternal.class);
        when(deviceIdleInternal.getNotificationWhitelistDuration()).thenReturn(3000L);
        ActivityManagerInternal activityManagerInternal = mock(ActivityManagerInternal.class);

        LocalServices.removeServiceForTest(UriGrantsManagerInternal.class);
        LocalServices.addService(UriGrantsManagerInternal.class, mUgmInternal);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.addService(WindowManagerInternal.class, mWindowManagerInternal);
        LocalServices.removeServiceForTest(StatusBarManagerInternal.class);
        LocalServices.addService(StatusBarManagerInternal.class, mStatusBar);
        LocalServices.removeServiceForTest(DeviceIdleInternal.class);
        LocalServices.addService(DeviceIdleInternal.class, deviceIdleInternal);
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.addService(ActivityManagerInternal.class, activityManagerInternal);

        doNothing().when(mContext).sendBroadcastAsUser(any(), any(), any());

        mService = new TestableNotificationManagerService(mContext, mNotificationRecordLogger,
                mNotificationInstanceIdSequence);

        // Use this testable looper.
        mTestableLooper = TestableLooper.get(this);
        // MockPackageManager - default returns ApplicationInfo with matching calling UID
        mContext.setMockPackageManager(mPackageManagerClient);

        when(mPackageManager.getApplicationInfo(anyString(), anyInt(), anyInt()))
                .thenAnswer((Answer<ApplicationInfo>) invocation -> {
                    Object[] args = invocation.getArguments();
                    return getApplicationInfo((String) args[0], mUid);
                });
        when(mPackageManagerClient.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenAnswer((Answer<ApplicationInfo>) invocation -> {
                    Object[] args = invocation.getArguments();
                    return getApplicationInfo((String) args[0], mUid);
                });
        when(mPackageManagerClient.getPackageUidAsUser(any(), anyInt())).thenReturn(mUid);
        final LightsManager mockLightsManager = mock(LightsManager.class);
        when(mockLightsManager.getLight(anyInt())).thenReturn(mock(LogicalLight.class));
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
        ComponentName defaultComponent = ComponentName.unflattenFromString("config/device");
        ArraySet<ComponentName> components = new ArraySet<>();
        components.add(defaultComponent);
        when(mListeners.getDefaultComponents()).thenReturn(components);
        when(mConditionProviders.getDefaultPackages())
                .thenReturn(new ArraySet<>(Arrays.asList("config")));
        when(mAssistants.getDefaultComponents()).thenReturn(components);
        when(mAssistants.queryPackageForServices(
                anyString(), anyInt(), anyInt())).thenReturn(components);
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

        mService.init(mService.new WorkerHandler(mTestableLooper.getLooper()),
                mRankingHandler, mPackageManager, mPackageManagerClient, mockLightsManager,
                mListeners, mAssistants, mConditionProviders,
                mCompanionMgr, mSnoozeHelper, mUsageStats, mPolicyFile, mActivityManager,
                mGroupHelper, mAm, mAtm, mAppUsageStats,
                mock(DevicePolicyManagerInternal.class), mUgm, mUgmInternal,
                mAppOpsManager, mUm, mHistoryManager, mStatsManager,
                mock(TelephonyManager.class));
        mService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);

        mService.setAudioManager(mAudioManager);

        mShortcutHelper = mService.getShortcutHelper();
        mShortcutHelper.setLauncherApps(mLauncherApps);

        // Set the testable bubble extractor
        RankingHelper rankingHelper = mService.getRankingHelper();
        BubbleExtractor extractor = rankingHelper.findExtractor(BubbleExtractor.class);
        mTestableBubbleChecker = new TestableBubbleChecker(mContext, mShortcutHelper,
                mService.mPreferencesHelper, mActivityManager);
        extractor.setBubbleChecker(mTestableBubbleChecker);

        // Tests call directly into the Binder.
        mBinderService = mService.getBinderService();
        mInternalService = mService.getInternalService();

        mBinderService.createNotificationChannels(
                PKG, new ParceledListSlice(Arrays.asList(mTestNotificationChannel)));
        assertNotNull(mBinderService.getNotificationChannel(
                PKG, mContext.getUserId(), PKG, TEST_CHANNEL_ID));
        clearInvocations(mRankingHandler);
    }

    @After
    public void assertNotificationRecordLoggerCallsValid() {
        for (NotificationRecordLoggerFake.CallRecord call : mNotificationRecordLogger.getCalls()) {
            if (call.wasLogged) {
                assertNotNull(call.event);
            }
        }
    }

    @After
    public void tearDown() throws Exception {
        if (mFile != null) mFile.delete();
        clearDeviceConfig();
        mService.unregisterDeviceConfigChange();
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    private ArrayMap<Boolean, ArrayList<ComponentName>> generateResetComponentValues() {
        ArrayMap<Boolean, ArrayList<ComponentName>> changed = new ArrayMap<>();
        changed.put(true, new ArrayList<>());
        changed.put(false, new ArrayList<>());
        return changed;
    }
    private ApplicationInfo getApplicationInfo(String pkg, int uid) {
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = uid;
        switch (pkg) {
            case PKG_N_MR1:
                applicationInfo.targetSdkVersion = Build.VERSION_CODES.N_MR1;
                break;
            case PKG_O:
                applicationInfo.targetSdkVersion = Build.VERSION_CODES.O;
                break;
            case PKG_P:
                applicationInfo.targetSdkVersion = Build.VERSION_CODES.P;
                break;
            default:
                applicationInfo.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;
                break;
        }
        return applicationInfo;
    }

    public void waitForIdle() {
        mTestableLooper.processAllMessages();
    }

    private void setUpPrefsForBubbles(String pkg, int uid, boolean globalEnabled,
            boolean pkgEnabled, boolean channelEnabled) {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.NOTIFICATION_BUBBLES, globalEnabled ? 1 : 0);
        mService.mPreferencesHelper.updateBubblesEnabled();
        assertEquals(globalEnabled, mService.mPreferencesHelper.bubblesEnabled());
        try {
            mBinderService.setBubblesAllowed(pkg, uid, pkgEnabled);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        mTestNotificationChannel.setAllowBubbles(channelEnabled);
    }

    private StatusBarNotification generateSbn(String pkg, int uid, long postTime, int userId) {
        Notification.Builder nb = new Notification.Builder(mContext, "a")
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon);
        StatusBarNotification sbn = new StatusBarNotification(pkg, pkg, uid,
                "tag" + System.currentTimeMillis(), uid, 0,
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
        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, id,
                "tag" + System.currentTimeMillis(), mUid, 0,
                nb.build(), new UserHandle(mUid), null, 0);
        return new NotificationRecord(mContext, sbn, channel);
    }

    private NotificationRecord generateNotificationRecord(NotificationChannel channel) {
        return generateNotificationRecord(channel, null);
    }

    private NotificationRecord generateNotificationRecord(NotificationChannel channel,
            Notification.TvExtender extender) {
        if (channel == null) {
            channel = mTestNotificationChannel;
        }
        Notification.Builder nb = new Notification.Builder(mContext, channel.getId())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon);
        if (extender != null) {
            nb.extend(extender);
        }
        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 8, "tag", mUid, 0,
                nb.build(), new UserHandle(mUid), null, 0);
        return new NotificationRecord(mContext, sbn, channel);
    }

    private NotificationRecord generateNotificationRecord(NotificationChannel channel, int userId) {
        if (channel == null) {
            channel = mTestNotificationChannel;
        }
        Notification.Builder nb = new Notification.Builder(mContext, channel.getId())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon);
        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 1, "tag", mUid, 0,
                nb.build(), new UserHandle(userId), null, 0);
        return new NotificationRecord(mContext, sbn, channel);
    }

    private NotificationRecord generateMessageBubbleNotifRecord(NotificationChannel channel,
            String tag) {
        return generateMessageBubbleNotifRecord(true, channel, 1, tag, null, false);
    }

    private NotificationRecord generateMessageBubbleNotifRecord(boolean addMetadata,
            NotificationChannel channel, int id, String tag, String groupKey, boolean isSummary) {
        if (channel == null) {
            channel = mTestNotificationChannel;
        }
        if (tag == null) {
            tag = "tag";
        }
        Notification.Builder nb = getMessageStyleNotifBuilder(addMetadata, groupKey, isSummary);
        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, id,
                tag, mUid, 0,
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

    private Notification.BubbleMetadata.Builder getBubbleMetadataBuilder() {
        PendingIntent pi = PendingIntent.getActivity(mContext, 0, new Intent(), 0);
        return new Notification.BubbleMetadata.Builder(pi,
                Icon.createWithResource(mContext, android.R.drawable.sym_def_app_icon));
    }

    private Notification.Builder getMessageStyleNotifBuilder(boolean addBubbleMetadata,
            String groupKey, boolean isSummary) {
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
                .setStyle(new Notification.MessagingStyle(person)
                        .setConversationTitle("Bubble Chat")
                        .addMessage("Hello?",
                                SystemClock.currentThreadTimeMillis() - 300000, person)
                        .addMessage("Is it me you're looking for?",
                                SystemClock.currentThreadTimeMillis(), person)
                )
                .setActions(replyAction)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setGroupSummary(isSummary);
        if (groupKey != null) {
            nb.setGroup(groupKey);
        }
        if (addBubbleMetadata) {
            nb.setBubbleMetadata(getBubbleMetadataBuilder().build());
        }
        return nb;
    }

    private NotificationRecord addGroupWithBubblesAndValidateAdded(boolean summaryAutoCancel)
            throws RemoteException {

        String groupKey = "BUBBLE_GROUP";

        // Notification that has bubble metadata
        NotificationRecord nrBubble = generateMessageBubbleNotifRecord(true /* addMetadata */,
                mTestNotificationChannel, 1 /* id */, "tag", groupKey, false /* isSummary */);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, nrBubble.getSbn().getTag(),
                nrBubble.getSbn().getId(), nrBubble.getSbn().getNotification(),
                nrBubble.getSbn().getUserId());
        waitForIdle();

        // Make sure we are a bubble
        StatusBarNotification[] notifsAfter = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifsAfter.length);
        assertTrue((notifsAfter[0].getNotification().flags & FLAG_BUBBLE) != 0);

        // Notification without bubble metadata
        NotificationRecord nrPlain = generateMessageBubbleNotifRecord(false /* addMetadata */,
                mTestNotificationChannel, 2 /* id */, "tag", groupKey, false /* isSummary */);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, nrPlain.getSbn().getTag(),
                nrPlain.getSbn().getId(), nrPlain.getSbn().getNotification(),
                nrPlain.getSbn().getUserId());
        waitForIdle();

        notifsAfter = mBinderService.getActiveNotifications(PKG);
        assertEquals(2, notifsAfter.length);

        // Summary notification for both of those
        NotificationRecord nrSummary = generateMessageBubbleNotifRecord(false /* addMetadata */,
                mTestNotificationChannel, 3 /* id */, "tag", groupKey, true /* isSummary */);

        if (summaryAutoCancel) {
            nrSummary.getNotification().flags |= FLAG_AUTO_CANCEL;
        }
        mBinderService.enqueueNotificationWithTag(PKG, PKG, nrSummary.getSbn().getTag(),
                nrSummary.getSbn().getId(), nrSummary.getSbn().getNotification(),
                nrSummary.getSbn().getUserId());
        waitForIdle();

        notifsAfter = mBinderService.getActiveNotifications(PKG);
        assertEquals(3, notifsAfter.length);

        return nrSummary;
    }

    @Test
    public void testDefaultAssistant_overrideDefault() {
        final int userId = 0;
        final String testComponent = "package/class";
        final List<UserInfo> userInfos = new ArrayList<>();
        userInfos.add(new UserInfo(0, "", 0));
        final ArraySet<ComponentName> validAssistants = new ArraySet<>();
        validAssistants.add(ComponentName.unflattenFromString(testComponent));
        final String originalComponent = DeviceConfig.getProperty(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.NAS_DEFAULT_SERVICE
        );
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.NAS_DEFAULT_SERVICE,
                testComponent,
                false
        );
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        when(mAssistants.queryPackageForServices(isNull(), anyInt(), anyInt()))
                .thenReturn(validAssistants);
        when(mAssistants.getDefaultComponents()).thenReturn(new ArraySet<>());
        when(mUm.getEnabledProfiles(anyInt())).thenReturn(userInfos);

        mService.setDefaultAssistantForUser(userId);

        verify(mAssistants).setPackageOrComponentEnabled(
                eq(testComponent), eq(userId), eq(true), eq(true));

        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.NAS_DEFAULT_SERVICE,
                originalComponent,
                false
        );
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
        final StatusBarNotification sbn = generateNotificationRecord(channel).getSbn();
        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testBlockedNotifications_blockedChannel",
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

        final StatusBarNotification sbn = generateNotificationRecord(channel).getSbn();
        sbn.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, sbn.getTag(),
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

        StatusBarNotification sbn = generateNotificationRecord(channel).getSbn();
        sbn.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, sbn.getTag(),
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        waitForIdle();
        // The first time a foreground service notification is shown, we allow the channel
        // to be updated to allow it to be seen.
        assertEquals(1, mBinderService.getActiveNotifications(sbn.getPackageName()).length);
        assertEquals(IMPORTANCE_LOW,
                mService.getNotificationRecord(sbn.getKey()).getImportance());
        assertEquals(IMPORTANCE_LOW, mBinderService.getNotificationChannel(
                PKG, mContext.getUserId(), PKG, channel.getId()).getImportance());
        mBinderService.cancelNotificationWithTag(PKG, PKG, "tag", sbn.getId(), sbn.getUserId());
        waitForIdle();

        update = new NotificationChannel("blockedbyuser", "name", IMPORTANCE_NONE);
        update.setFgServiceShown(true);
        mBinderService.updateNotificationChannelForPackage(PKG, mUid, update);
        waitForIdle();
        assertEquals(IMPORTANCE_NONE, mBinderService.getNotificationChannel(
                PKG, mContext.getUserId(), PKG, channel.getId()).getImportance());

        sbn = generateNotificationRecord(channel).getSbn();
        sbn.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testEnqueuedBlockedNotifications_userBlockedChannelForegroundService",
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
        when(mPreferencesHelper.isGroupBlocked(anyString(), anyInt(), anyString())).
                thenReturn(true);

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

        final StatusBarNotification sbn = generateNotificationRecord(null).getSbn();
        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testEnqueuedBlockedNotifications_blockedApp",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        waitForIdle();
        assertEquals(0, mBinderService.getActiveNotifications(sbn.getPackageName()).length);
    }

    @Test
    public void testEnqueuedBlockedNotifications_blockedAppForegroundService() throws Exception {
        when(mPackageManager.isPackageSuspendedForUser(anyString(), anyInt())).thenReturn(false);

        mBinderService.setNotificationsEnabledForPackage(PKG, mUid, false);

        final StatusBarNotification sbn = generateNotificationRecord(null).getSbn();
        sbn.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testEnqueuedBlockedNotifications_blockedAppForegroundService",
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
                    generateNotificationRecord(mTestNotificationChannel, ++id, "", false).getSbn();
            sbn.getNotification().category = category;
            mBinderService.enqueueNotificationWithTag(PKG, PKG,
                    "testEnqueuedRestrictedNotifications_asSystem",
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
                    generateNotificationRecord(mTestNotificationChannel, ++id, "", false).getSbn();
            sbn.getNotification().category = category;
            mBinderService.enqueueNotificationWithTag(PKG, PKG,
                    "testEnqueuedRestrictedNotifications_notAutomotive",
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
            final StatusBarNotification sbn = generateNotificationRecord(null).getSbn();
            sbn.getNotification().category = category;
            try {
                mBinderService.enqueueNotificationWithTag(PKG, PKG,
                        "testEnqueuedRestrictedNotifications_badUser",
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
                r.getSbn().getPackageName(), r.getKey(), bundle, "", r.getUser().getIdentifier());
        mBinderService.applyEnqueuedAdjustmentFromAssistant(null, adjustment);

        NotificationManagerService.PostNotificationRunnable runnable =
                mService.new PostNotificationRunnable(r.getKey());
        runnable.run();
        waitForIdle();

        verify(mUsageStats, never()).registerPostedByApp(any());
    }

    @Test
    public void testEnqueueNotificationWithTag_PopulatesGetActiveNotifications() throws Exception {
        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testEnqueueNotificationWithTag_PopulatesGetActiveNotifications", 0,
                generateNotificationRecord(null).getNotification(), 0);
        waitForIdle();
        StatusBarNotification[] notifs = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifs.length);
        assertEquals(1, mService.getNotificationRecordCount());
    }

    @Test
    public void testEnqueueNotificationWithTag_WritesExpectedLogs() throws Exception {
        final String tag = "testEnqueueNotificationWithTag_WritesExpectedLog";
        mBinderService.enqueueNotificationWithTag(PKG, PKG, tag, 0,
                generateNotificationRecord(null).getNotification(), 0);
        waitForIdle();
        assertEquals(1, mNotificationRecordLogger.numCalls());

        NotificationRecordLoggerFake.CallRecord call = mNotificationRecordLogger.get(0);
        assertTrue(call.wasLogged);
        assertEquals(NotificationRecordLogger.NotificationReportedEvent.NOTIFICATION_POSTED,
                call.event);
        assertNotNull(call.r);
        assertNull(call.old);
        assertEquals(0, call.position);
        assertEquals(0, call.buzzBeepBlink);
        assertEquals(PKG, call.r.getSbn().getPackageName());
        assertEquals(0, call.r.getSbn().getId());
        assertEquals(tag, call.r.getSbn().getTag());
        assertEquals(1, call.getInstanceId());  // Fake instance IDs are assigned in order
    }

    @Test
    public void testEnqueueNotificationWithTag_LogsOnMajorUpdates() throws Exception {
        final String tag = "testEnqueueNotificationWithTag_LogsOnMajorUpdates";
        Notification original = new Notification.Builder(mContext,
                mTestNotificationChannel.getId())
                .setSmallIcon(android.R.drawable.sym_def_app_icon).build();
        mBinderService.enqueueNotificationWithTag(PKG, PKG, tag, 0, original, 0);
        Notification update = new Notification.Builder(mContext,
                mTestNotificationChannel.getId())
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setCategory(Notification.CATEGORY_ALARM).build();
        mBinderService.enqueueNotificationWithTag(PKG, PKG, tag, 0, update, 0);
        waitForIdle();
        assertEquals(2, mNotificationRecordLogger.numCalls());

        assertTrue(mNotificationRecordLogger.get(0).wasLogged);
        assertEquals(
                NotificationRecordLogger.NotificationReportedEvent.NOTIFICATION_POSTED,
                mNotificationRecordLogger.event(0));
        assertEquals(1, mNotificationRecordLogger.get(0).getInstanceId());

        assertTrue(mNotificationRecordLogger.get(1).wasLogged);
        assertEquals(
                NotificationRecordLogger.NotificationReportedEvent.NOTIFICATION_UPDATED,
                mNotificationRecordLogger.event(1));
        // Instance ID doesn't change on update of an active notification
        assertEquals(1, mNotificationRecordLogger.get(1).getInstanceId());
    }

    @Test
    public void testEnqueueNotificationWithTag_DoesNotLogOnMinorUpdate() throws Exception {
        final String tag = "testEnqueueNotificationWithTag_DoesNotLogOnMinorUpdate";
        mBinderService.enqueueNotificationWithTag(PKG, PKG, tag, 0,
                generateNotificationRecord(null).getNotification(), 0);
        mBinderService.enqueueNotificationWithTag(PKG, PKG, tag, 0,
                generateNotificationRecord(null).getNotification(), 0);
        waitForIdle();
        assertEquals(2, mNotificationRecordLogger.numCalls());
        assertTrue(mNotificationRecordLogger.get(0).wasLogged);
        assertEquals(
                NotificationRecordLogger.NotificationReportedEvent.NOTIFICATION_POSTED,
                mNotificationRecordLogger.event(0));
        assertFalse(mNotificationRecordLogger.get(1).wasLogged);
        assertNull(mNotificationRecordLogger.event(1));
    }

    @Test
    public void testEnqueueNotificationWithTag_DoesNotLogOnTitleUpdate() throws Exception {
        final String tag = "testEnqueueNotificationWithTag_DoesNotLogOnTitleUpdate";
        mBinderService.enqueueNotificationWithTag(PKG, PKG, tag, 0,
                generateNotificationRecord(null).getNotification(),
                0);
        final Notification notif = generateNotificationRecord(null).getNotification();
        notif.extras.putString(Notification.EXTRA_TITLE, "Changed title");
        mBinderService.enqueueNotificationWithTag(PKG, PKG, tag, 0, notif, 0);
        waitForIdle();
        assertEquals(2, mNotificationRecordLogger.numCalls());
        assertEquals(
                NotificationRecordLogger.NotificationReportedEvent.NOTIFICATION_POSTED,
                mNotificationRecordLogger.event(0));
        assertNull(mNotificationRecordLogger.event(1));
    }

    @Test
    public void testEnqueueNotificationWithTag_LogsAgainAfterCancel() throws Exception {
        final String tag = "testEnqueueNotificationWithTag_LogsAgainAfterCancel";
        Notification notification = new Notification.Builder(mContext,
                mTestNotificationChannel.getId())
                .setSmallIcon(android.R.drawable.sym_def_app_icon).build();
        mBinderService.enqueueNotificationWithTag(PKG, PKG, tag, 0, notification, 0);
        waitForIdle();
        mBinderService.cancelNotificationWithTag(PKG, PKG, tag, 0, 0);
        waitForIdle();
        mBinderService.enqueueNotificationWithTag(PKG, PKG, tag, 0, notification, 0);
        waitForIdle();
        assertEquals(3, mNotificationRecordLogger.numCalls());

        assertEquals(
                NotificationRecordLogger.NotificationReportedEvent.NOTIFICATION_POSTED,
                mNotificationRecordLogger.event(0));
        assertTrue(mNotificationRecordLogger.get(0).wasLogged);
        assertEquals(1, mNotificationRecordLogger.get(0).getInstanceId());

        assertEquals(
                NotificationRecordLogger.NotificationCancelledEvent.NOTIFICATION_CANCEL_APP_CANCEL,
                mNotificationRecordLogger.event(1));
        assertEquals(1, mNotificationRecordLogger.get(1).getInstanceId());

        assertEquals(
                NotificationRecordLogger.NotificationReportedEvent.NOTIFICATION_POSTED,
                mNotificationRecordLogger.event(2));
        assertTrue(mNotificationRecordLogger.get(2).wasLogged);
        // New instance ID because notification was canceled before re-post
        assertEquals(2, mNotificationRecordLogger.get(2).getInstanceId());
    }

    @Test
    public void testCancelNonexistentNotification() throws Exception {
        mBinderService.cancelNotificationWithTag(PKG, PKG,
                "testCancelNonexistentNotification", 0, 0);
        waitForIdle();
        // The notification record logger doesn't even get called when a nonexistent notification
        // is cancelled, because that happens very frequently and is not interesting.
        assertEquals(0, mNotificationRecordLogger.numCalls());
    }

    @Test
    public void testCancelNotificationImmediatelyAfterEnqueue() throws Exception {
        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testCancelNotificationImmediatelyAfterEnqueue", 0,
                generateNotificationRecord(null).getNotification(), 0);
        mBinderService.cancelNotificationWithTag(PKG, PKG,
                "testCancelNotificationImmediatelyAfterEnqueue", 0, 0);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(PKG);
        assertEquals(0, notifs.length);
        assertEquals(0, mService.getNotificationRecordCount());
    }

    @Test
    public void testPostCancelPostNotifiesListeners() throws Exception {
        // WHEN a notification is posted
        final StatusBarNotification sbn = generateNotificationRecord(null).getSbn();
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag", sbn.getId(),
                sbn.getNotification(), sbn.getUserId());
        // THEN it is canceled
        mBinderService.cancelNotificationWithTag(PKG, PKG, "tag", sbn.getId(), sbn.getUserId());
        // THEN it is posted again (before the cancel has a chance to finish)
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag", sbn.getId(),
                sbn.getNotification(), sbn.getUserId());
        // THEN the later enqueue isn't swallowed by the cancel. I.e., ordering is respected
        waitForIdle();

        // The final enqueue made it to the listener instead of being canceled
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifs.length);
        assertEquals(1, mService.getNotificationRecordCount());
    }

    @Test
    public void testCancelNotificationWhilePostedAndEnqueued() throws Exception {
        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testCancelNotificationWhilePostedAndEnqueued", 0,
                generateNotificationRecord(null).getNotification(), 0);
        waitForIdle();
        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testCancelNotificationWhilePostedAndEnqueued", 0,
                generateNotificationRecord(null).getNotification(), 0);
        mBinderService.cancelNotificationWithTag(PKG, PKG,
                "testCancelNotificationWhilePostedAndEnqueued", 0, 0);
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
        final StatusBarNotification sbn = r.getSbn();
        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testCancelNotificationsFromListenerImmediatelyAfterEnqueue",
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
        final StatusBarNotification sbn = generateNotificationRecord(null).getSbn();
        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testCancelAllNotificationsImmediatelyAfterEnqueue",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        mBinderService.cancelAllNotifications(PKG, sbn.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(sbn.getPackageName());
        assertEquals(0, notifs.length);
        assertEquals(0, mService.getNotificationRecordCount());
    }

    @Test
    public void testCancelImmediatelyAfterEnqueueNotifiesListeners_ForegroundServiceFlag()
            throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).getSbn();
        sbn.getNotification().flags =
                Notification.FLAG_ONGOING_EVENT | FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        mBinderService.cancelNotificationWithTag(PKG, PKG, "tag", sbn.getId(), sbn.getUserId());
        waitForIdle();
        verify(mListeners, times(1)).notifyPostedLocked(any(), any());
        verify(mListeners, times(1)).notifyRemovedLocked(any(), anyInt(), any());
    }

    @Test
    public void testUserInitiatedClearAll_noLeak() throws Exception {
        final NotificationRecord n = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);

        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testUserInitiatedClearAll_noLeak",
                n.getSbn().getId(), n.getSbn().getNotification(), n.getSbn().getUserId());
        waitForIdle();

        mService.mNotificationDelegate.onClearAll(mUid, Binder.getCallingPid(),
                n.getUserId());
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(n.getSbn().getPackageName());
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

        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testCancelAllNotificationsCancelsChildren",
                parent.getSbn().getId(), parent.getSbn().getNotification(),
                parent.getSbn().getUserId());
        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testCancelAllNotificationsCancelsChildren",
                child.getSbn().getId(), child.getSbn().getNotification(),
                child.getSbn().getUserId());
        waitForIdle();

        mBinderService.cancelAllNotifications(PKG, parent.getSbn().getUserId());
        waitForIdle();
        assertEquals(0, mService.getNotificationRecordCount());
    }

    @Test
    public void testCancelAllNotificationsMultipleEnqueuedDoesNotCrash() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).getSbn();
        for (int i = 0; i < 10; i++) {
            mBinderService.enqueueNotificationWithTag(PKG, PKG,
                    "testCancelAllNotificationsMultipleEnqueuedDoesNotCrash",
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
        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testCancelGroupSummaryMultipleEnqueuedChildrenDoesNotCrash",
                parent.getSbn().getId(), parent.getSbn().getNotification(),
                parent.getSbn().getUserId());
        waitForIdle();

        // enqueue the child several times
        for (int i = 0; i < 10; i++) {
            mBinderService.enqueueNotificationWithTag(PKG, PKG,
                    "testCancelGroupSummaryMultipleEnqueuedChildrenDoesNotCrash",
                    child.getSbn().getId(), child.getSbn().getNotification(),
                    child.getSbn().getUserId());
        }
        // make the parent a child, which will cancel the child notification
        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testCancelGroupSummaryMultipleEnqueuedChildrenDoesNotCrash",
                parentAsChild.getSbn().getId(), parentAsChild.getSbn().getNotification(),
                parentAsChild.getSbn().getUserId());
        waitForIdle();

        assertEquals(0, mService.getNotificationRecordCount());
    }

    @Test
    public void testAutobundledSummary_notificationAdded() {
        NotificationRecord summary =
                generateNotificationRecord(mTestNotificationChannel, 0, "pkg", true);
        summary.getNotification().flags |= Notification.FLAG_AUTOGROUP_SUMMARY;
        mService.addNotification(summary);
        mService.mSummaryByGroupKey.put("pkg", summary);
        mService.mAutobundledSummaries.put(0, new ArrayMap<>());
        mService.mAutobundledSummaries.get(0).put("pkg", summary.getKey());
        mService.updateAutobundledSummaryFlags(0, "pkg", true, false);

        assertTrue(summary.getSbn().isOngoing());
    }

    @Test
    public void testAutobundledSummary_notificationRemoved() {
        NotificationRecord summary =
                generateNotificationRecord(mTestNotificationChannel, 0, "pkg", true);
        summary.getNotification().flags |= Notification.FLAG_AUTOGROUP_SUMMARY;
        summary.getNotification().flags |= Notification.FLAG_ONGOING_EVENT;
        mService.addNotification(summary);
        mService.mAutobundledSummaries.put(0, new ArrayMap<>());
        mService.mAutobundledSummaries.get(0).put("pkg", summary.getKey());
        mService.mSummaryByGroupKey.put("pkg", summary);

        mService.updateAutobundledSummaryFlags(0, "pkg", false, false);

        assertFalse(summary.getSbn().isOngoing());
    }

    @Test
    public void testCancelAllNotifications_IgnoreForegroundService() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).getSbn();
        sbn.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testCancelAllNotifications_IgnoreForegroundService",
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
        final StatusBarNotification sbn = generateNotificationRecord(null).getSbn();
        sbn.getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testCancelAllNotifications_IgnoreOtherPackages",
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
        final StatusBarNotification sbn = generateNotificationRecord(null).getSbn();
        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testCancelAllNotifications_NullPkgRemovesAll",
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
        final StatusBarNotification sbn = generateNotificationRecord(null).getSbn();
        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testCancelAllNotifications_NullPkgIgnoresUserAllNotifications",
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
        final StatusBarNotification sbn = generateNotificationRecord(null).getSbn();
        sbn.getNotification().flags |= Notification.FLAG_NO_CLEAR;
        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testAppInitiatedCancelAllNotifications_CancelsNoClearFlag",
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
                mBinderService.getActiveNotifications(notif.getSbn().getPackageName());
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
                mBinderService.getActiveNotifications(notif.getSbn().getPackageName());
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
                mBinderService.getActiveNotifications(parent.getSbn().getPackageName());
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
                mBinderService.getActiveNotifications(parent.getSbn().getPackageName());
        assertEquals(1, notifs.length);
    }

    @Test
    public void testRemoveForegroundServiceFlag_ImmediatelyAfterEnqueue() throws Exception {
        Notification n =
                new Notification.Builder(mContext, mTestNotificationChannel.getId())
                        .setSmallIcon(android.R.drawable.sym_def_app_icon)
                        .build();
        StatusBarNotification sbn = new StatusBarNotification("a", "a", 0, null, mUid, 0,
                n, new UserHandle(mUid), null, 0);
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
        final StatusBarNotification sbn = generateNotificationRecord(null).getSbn();
        sbn.getNotification().flags =
                Notification.FLAG_ONGOING_EVENT | FLAG_FOREGROUND_SERVICE;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, sbn.getTag(),
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        sbn.getNotification().flags = Notification.FLAG_ONGOING_EVENT;
        mBinderService.enqueueNotificationWithTag(PKG, PKG, sbn.getTag(),
                sbn.getId(), sbn.getNotification(), sbn.getUserId());
        mBinderService.cancelNotificationWithTag(PKG, PKG, sbn.getTag(), sbn.getId(),
                sbn.getUserId());
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
                mBinderService.getActiveNotifications(parent.getSbn().getPackageName());
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
        String[] keys = {parent.getSbn().getKey(), child.getSbn().getKey(),
                child2.getSbn().getKey(), newGroup.getSbn().getKey()};
        mService.getBinderService().cancelNotificationsFromListener(null, keys);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(parent.getSbn().getPackageName());
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
                mBinderService.getActiveNotifications(parent.getSbn().getPackageName());
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
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "testFindGroupNotificationsLocked",
                group2.getSbn().getId(), group2.getSbn().getNotification(),
                group2.getSbn().getUserId());
        waitForIdle();

        // should not be returned
        final NotificationRecord nonGroup = generateNotificationRecord(
                mTestNotificationChannel, 3, null, false);
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "testFindGroupNotificationsLocked",
                nonGroup.getSbn().getId(), nonGroup.getSbn().getNotification(),
                nonGroup.getSbn().getUserId());
        waitForIdle();

        // same group, child, should be returned
        final NotificationRecord group1Child = generateNotificationRecord(
                mTestNotificationChannel, 4, "group1", false);
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "testFindGroupNotificationsLocked",
                group1Child.getSbn().getId(),
                group1Child.getSbn().getNotification(), group1Child.getSbn().getUserId());
        waitForIdle();

        List<NotificationRecord> inGroup1 =
                mService.findGroupNotificationsLocked(PKG, group1.getGroupKey(),
                        group1.getSbn().getUserId());
        assertEquals(3, inGroup1.size());
        for (NotificationRecord record : inGroup1) {
            assertTrue(record.getGroupKey().equals(group1.getGroupKey()));
            assertTrue(record.getSbn().getId() == 1 || record.getSbn().getId() == 4);
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
                mBinderService.getActiveNotifications(notif.getSbn().getPackageName());
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
        String[] keys = {parent.getSbn().getKey(), child.getSbn().getKey(),
                child2.getSbn().getKey(), newGroup.getSbn().getKey()};
        mService.getBinderService().cancelNotificationsFromListener(null, keys);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(parent.getSbn().getPackageName());
        assertEquals(0, notifs.length);
    }

    @Test
    public void testAppInitiatedCancelAllNotifications_CancelsOnGoingFlag() throws Exception {
        final StatusBarNotification sbn = generateNotificationRecord(null).getSbn();
        sbn.getNotification().flags |= Notification.FLAG_ONGOING_EVENT;
        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testAppInitiatedCancelAllNotifications_CancelsOnGoingFlag",
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
                mBinderService.getActiveNotifications(notif.getSbn().getPackageName());
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
                mBinderService.getActiveNotifications(notif.getSbn().getPackageName());
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
                mBinderService.getActiveNotifications(parent.getSbn().getPackageName());
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
        String[] keys = {parent.getSbn().getKey(), child.getSbn().getKey(),
                child2.getSbn().getKey(), newGroup.getSbn().getKey()};
        mService.getBinderService().cancelNotificationsFromListener(null, keys);
        waitForIdle();
        StatusBarNotification[] notifs =
                mBinderService.getActiveNotifications(parent.getSbn().getPackageName());
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
                mBinderService.getActiveNotifications(parent.getSbn().getPackageName());
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
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "testTvExtenderChannelOverride_onTv", 0,
                generateNotificationRecord(null, tv).getNotification(), 0);
        verify(mPreferencesHelper, times(1)).getConversationNotificationChannel(
                anyString(), anyInt(), eq("foo"), eq(null), anyBoolean(), anyBoolean());
    }

    @Test
    public void testTvExtenderChannelOverride_notOnTv() throws Exception {
        mService.setIsTelevision(false);
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mPreferencesHelper.getNotificationChannel(
                anyString(), anyInt(), anyString(), anyBoolean())).thenReturn(
                mTestNotificationChannel);

        Notification.TvExtender tv = new Notification.TvExtender().setChannelId("foo");
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "testTvExtenderChannelOverride_notOnTv",
                0, generateNotificationRecord(null, tv).getNotification(), 0);
        verify(mPreferencesHelper, times(1)).getConversationNotificationChannel(
                anyString(), anyInt(), eq(mTestNotificationChannel.getId()), eq(null),
                anyBoolean(), anyBoolean());
    }

    @Test
    public void testUpdateAppNotifyCreatorBlock() throws Exception {
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
    public void testUpdateAppNotifyCreatorBlock_notIfMatchesExistingSetting() throws Exception {
        mService.setPreferencesHelper(mPreferencesHelper);

        mBinderService.setNotificationsEnabledForPackage(PKG, 0, false);
        verify(mContext, never()).sendBroadcastAsUser(any(), any(), eq(null));
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
        when(mPreferencesHelper.getNotificationChannelGroup(eq(existing.getId()),
                eq(PKG), anyInt()))
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
        when(mPreferencesHelper.getNotificationChannelGroup(eq(existing.getId()),
                eq(PKG), anyInt()))
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
        when(mPreferencesHelper.getNotificationChannelGroup(
                eq(existing.getId()), eq(PKG), anyInt()))
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
        mService = new TestableNotificationManagerService(mContext,  mNotificationRecordLogger,
                mNotificationInstanceIdSequence);

        assertFalse(mService.hasCompanionDevice(mListener));
    }

    @Test
    public void testSnoozeRunnable_reSnoozeASingleSnoozedNotification() throws Exception {
        final NotificationRecord notification = generateNotificationRecord(
                mTestNotificationChannel, 1, null, true);
        mService.addNotification(notification);
        when(mSnoozeHelper.getNotification(any())).thenReturn(notification);

        NotificationManagerService.SnoozeNotificationRunnable snoozeNotificationRunnable =
                mService.new SnoozeNotificationRunnable(
                notification.getKey(), 100, null);
        snoozeNotificationRunnable.run();
        NotificationManagerService.SnoozeNotificationRunnable snoozeNotificationRunnable2 =
                mService.new SnoozeNotificationRunnable(
                notification.getKey(), 100, null);
        snoozeNotificationRunnable.run();

        // snooze twice
        verify(mSnoozeHelper, times(2)).snooze(any(NotificationRecord.class), anyLong());
    }

    @Test
    public void testSnoozeRunnable_reSnoozeASnoozedNotificationWithGroupKey() throws Exception {
        final NotificationRecord notification = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);
        mService.addNotification(notification);
        when(mSnoozeHelper.getNotification(any())).thenReturn(notification);

        NotificationManagerService.SnoozeNotificationRunnable snoozeNotificationRunnable =
                mService.new SnoozeNotificationRunnable(
                notification.getKey(), 100, null);
        snoozeNotificationRunnable.run();
        NotificationManagerService.SnoozeNotificationRunnable snoozeNotificationRunnable2 =
                mService.new SnoozeNotificationRunnable(
                notification.getKey(), 100, null);
        snoozeNotificationRunnable.run();

        // snooze twice
        verify(mSnoozeHelper, times(2)).snooze(any(NotificationRecord.class), anyLong());
    }

    @Test
    public void testSnoozeRunnable_reSnoozeMultipleNotificationsWithGroupKey() throws Exception {
        final NotificationRecord notification = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);
        final NotificationRecord notification2 = generateNotificationRecord(
                mTestNotificationChannel, 2, "group", true);
        mService.addNotification(notification);
        mService.addNotification(notification2);
        when(mSnoozeHelper.getNotification(any())).thenReturn(notification);
        when(mSnoozeHelper.getNotifications(
                anyString(), anyString(), anyInt())).thenReturn(new ArrayList<>());

        NotificationManagerService.SnoozeNotificationRunnable snoozeNotificationRunnable =
                mService.new SnoozeNotificationRunnable(
                        notification.getKey(), 100, null);
        snoozeNotificationRunnable.run();
        when(mSnoozeHelper.getNotifications(anyString(), anyString(), anyInt()))
                .thenReturn(new ArrayList<>(Arrays.asList(notification, notification2)));
        NotificationManagerService.SnoozeNotificationRunnable snoozeNotificationRunnable2 =
                mService.new SnoozeNotificationRunnable(
                        notification.getKey(), 100, null);
        snoozeNotificationRunnable.run();

        // snooze twice
        verify(mSnoozeHelper, times(4)).snooze(any(NotificationRecord.class), anyLong());
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

        assertEquals(2, mNotificationRecordLogger.numCalls());
        assertEquals(NotificationRecordLogger.NotificationEvent.NOTIFICATION_SNOOZED,
                mNotificationRecordLogger.event(0));
        assertEquals(
                NotificationRecordLogger.NotificationCancelledEvent.NOTIFICATION_CANCEL_SNOOZED,
                mNotificationRecordLogger.event(1));
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

        assertEquals(2, mNotificationRecordLogger.numCalls());
        assertEquals(NotificationRecordLogger.NotificationEvent.NOTIFICATION_SNOOZED,
                mNotificationRecordLogger.event(0));
        assertEquals(NotificationRecordLogger.NotificationCancelledEvent
                        .NOTIFICATION_CANCEL_SNOOZED, mNotificationRecordLogger.event(1));
    }

    @Test
    public void testSnoozeRunnable_snoozeGroupChild_onlyChildOfSummary() throws Exception {
        final NotificationRecord parent = generateNotificationRecord(
                mTestNotificationChannel, 1, "group", true);
        assertTrue(parent.getSbn().getNotification().isGroupSummary());
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

        assertEquals(4, mNotificationRecordLogger.numCalls());
        assertEquals(NotificationRecordLogger.NotificationEvent.NOTIFICATION_SNOOZED,
                mNotificationRecordLogger.event(0));
        assertEquals(
                NotificationRecordLogger.NotificationCancelledEvent.NOTIFICATION_CANCEL_SNOOZED,
                mNotificationRecordLogger.event(1));
        assertEquals(NotificationRecordLogger.NotificationEvent.NOTIFICATION_SNOOZED,
                mNotificationRecordLogger.event(2));
        assertEquals(
                NotificationRecordLogger.NotificationCancelledEvent.NOTIFICATION_CANCEL_SNOOZED,
                mNotificationRecordLogger.event(3));
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

        assertEquals(2, mNotificationRecordLogger.numCalls());
        assertEquals(NotificationRecordLogger.NotificationEvent.NOTIFICATION_SNOOZED,
                mNotificationRecordLogger.event(0));
        assertEquals(
                NotificationRecordLogger.NotificationCancelledEvent.NOTIFICATION_CANCEL_SNOOZED,
                mNotificationRecordLogger.event(1));
    }

    @Test
    public void testPostGroupChild_unsnoozeParent() throws Exception {
        final NotificationRecord child = generateNotificationRecord(
                mTestNotificationChannel, 2, "group", false);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, "testPostNonGroup_noUnsnoozing",
                child.getSbn().getId(), child.getSbn().getNotification(),
                child.getSbn().getUserId());
        waitForIdle();

        verify(mSnoozeHelper, times(1)).repostGroupSummary(
                anyString(), anyInt(), eq(child.getGroupKey()));
    }

    @Test
    public void testPostNonGroup_noUnsnoozing() throws Exception {
        final NotificationRecord record = generateNotificationRecord(
                mTestNotificationChannel, 2, null, false);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, "testPostNonGroup_noUnsnoozing",
                record.getSbn().getId(), record.getSbn().getNotification(),
                record.getSbn().getUserId());
        waitForIdle();

        verify(mSnoozeHelper, never()).repostGroupSummary(anyString(), anyInt(), anyString());
    }

    @Test
    public void testPostGroupSummary_noUnsnoozing() throws Exception {
        final NotificationRecord parent = generateNotificationRecord(
                mTestNotificationChannel, 2, "group", true);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, "testPostGroupSummary_noUnsnoozing",
                parent.getSbn().getId(), parent.getSbn().getNotification(),
                parent.getSbn().getUserId());
        waitForIdle();

        verify(mSnoozeHelper, never()).repostGroupSummary(anyString(), anyInt(), anyString());
    }

    @Test
    public void testSystemNotificationListenerCanUnsnooze() throws Exception {
        final NotificationRecord nr = generateNotificationRecord(
                mTestNotificationChannel, 2, "group", false);

        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testSystemNotificationListenerCanUnsnooze",
                nr.getSbn().getId(), nr.getSbn().getNotification(),
                nr.getSbn().getUserId());
        waitForIdle();
        NotificationManagerService.SnoozeNotificationRunnable snoozeNotificationRunnable =
                mService.new SnoozeNotificationRunnable(
                        nr.getKey(), 100, null);
        snoozeNotificationRunnable.run();

        ManagedServices.ManagedServiceInfo listener = mListeners.new ManagedServiceInfo(
                null, new ComponentName(PKG, "test_class"), mUid, true, null, 0);
        listener.isSystem = true;
        when(mListeners.checkServiceTokenLocked(any())).thenReturn(listener);

        mBinderService.unsnoozeNotificationFromSystemListener(null, nr.getKey());
        waitForIdle();
        StatusBarNotification[] notifs = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifs.length);
        assertNotNull(notifs[0].getKey());//mService.getNotificationRecord(nr.getSbn().getKey()));
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
    public void testSetListenerAccess_onLowRam() throws Exception {
        when(mActivityManager.isLowRamDevice()).thenReturn(true);
        ComponentName c = ComponentName.unflattenFromString("package/Component");
        mBinderService.setNotificationListenerAccessGranted(c, true);

        verify(mListeners).setPackageOrComponentEnabled(
                anyString(), anyInt(), anyBoolean(), anyBoolean());
        verify(mConditionProviders).setPackageOrComponentEnabled(
                anyString(), anyInt(), anyBoolean(), anyBoolean());
        verify(mAssistants).migrateToXml();
        verify(mAssistants).resetDefaultAssistantsIfNecessary();
    }

    @Test
    public void testSetAssistantAccess_onLowRam() throws Exception {
        when(mActivityManager.isLowRamDevice()).thenReturn(true);
        ComponentName c = ComponentName.unflattenFromString("package/Component");
        List<UserInfo> uis = new ArrayList<>();
        UserInfo ui = new UserInfo();
        ui.id = 0;
        uis.add(ui);
        when(mUm.getEnabledProfiles(ui.id)).thenReturn(uis);

        mBinderService.setNotificationAssistantAccessGranted(c, true);

        verify(mListeners).migrateToXml();
        verify(mListeners).notifyNotificationChannelChanged(anyString(), any(), any(),
                anyInt());
        verify(mConditionProviders).setPackageOrComponentEnabled(
                anyString(), anyInt(), anyBoolean(), anyBoolean());
        verify(mAssistants).migrateToXml();
        verify(mAssistants).resetDefaultAssistantsIfNecessary();
    }

    @Test
    public void testSetDndAccess_onLowRam() throws Exception {
        when(mActivityManager.isLowRamDevice()).thenReturn(true);
        ComponentName c = ComponentName.unflattenFromString("package/Component");
        mBinderService.setNotificationPolicyAccessGranted(c.getPackageName(), true);

        verify(mListeners).migrateToXml();
        verify(mListeners).notifyNotificationChannelChanged(anyString(), any(), any(),
                anyInt());
        verify(mConditionProviders).setPackageOrComponentEnabled(
                anyString(), anyInt(), anyBoolean(), anyBoolean());
        verify(mAssistants).migrateToXml();
        verify(mAssistants).resetDefaultAssistantsIfNecessary();
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
        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 1,
                "testNoFakeColorizedPermission", mUid, 0,
                nb.build(), new UserHandle(mUid), null, 0);
        NotificationRecord nr = new NotificationRecord(mContext, sbn, mTestNotificationChannel);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, sbn.getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();

        NotificationRecord posted = mService.findNotificationLocked(
                PKG, nr.getSbn().getTag(), nr.getSbn().getId(), nr.getSbn().getUserId());

        assertFalse(posted.getNotification().isColorized());
    }

    @Test
    public void testGetNotificationCountLocked() {
        String sampleTagToExclude = null;
        int sampleIdToExclude = 0;
        for (int i = 0; i < 20; i++) {
            NotificationRecord r =
                    generateNotificationRecord(mTestNotificationChannel, i, null, false);
            mService.addEnqueuedNotification(r);

        }
        for (int i = 0; i < 20; i++) {
            NotificationRecord r =
                    generateNotificationRecord(mTestNotificationChannel, i, null, false);
            mService.addNotification(r);
            sampleTagToExclude = r.getSbn().getTag();
            sampleIdToExclude = i;
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
        // anything that's currently enqueued or posted
        int userId = new UserHandle(mUid).getIdentifier();
        assertEquals(40,
                mService.getNotificationCountLocked(PKG, userId, 0, null));
        assertEquals(40,
                mService.getNotificationCountLocked(PKG, userId, 0, "tag2"));

        // return all for package "a" - "banana" tag isn't used
        assertEquals(2,
                mService.getNotificationCountLocked("a", userId, 0, "banana"));

        // exclude a known notification - it's excluded from only the posted list, not enqueued
        assertEquals(39, mService.getNotificationCountLocked(
                PKG, userId, sampleIdToExclude, sampleTagToExclude));
    }

    @Test
    public void testAddAutogroup_requestsSort() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);
        mService.addAutogroupKeyLocked(r.getKey());

        verify(mRankingHandler, times(1)).requestSort();
    }

    @Test
    public void testRemoveAutogroup_requestsSort() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        r.setOverrideGroupKey("TEST");
        mService.addNotification(r);
        mService.removeAutogroupKeyLocked(r.getKey());

        verify(mRankingHandler, times(1)).requestSort();
    }

    @Test
    public void testReaddAutogroup_noSort() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        r.setOverrideGroupKey("TEST");
        mService.addNotification(r);
        mService.addAutogroupKeyLocked(r.getKey());

        verify(mRankingHandler, never()).requestSort();
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
    public void testReadPolicyXml_readSnoozedNotificationsFromXml() throws Exception {
        final String upgradeXml = "<notification-policy version=\"1\">"
                + "<snoozed-notifications>></snoozed-notifications>"
                + "</notification-policy>";
        mService.readPolicyXml(
                new BufferedInputStream(new ByteArrayInputStream(upgradeXml.getBytes())),
                false,
                UserHandle.USER_ALL);
        verify(mSnoozeHelper, times(1)).readXml(any(XmlPullParser.class), anyLong());
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

        StatusBarNotification sbn = new StatusBarNotification(preOPkg, preOPkg, 9,
                "testBumpFGImportance_noChannelChangePreOApp",
                Binder.getCallingUid(), 0, nb.build(), new UserHandle(Binder.getCallingUid()), null,
                0);

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

        sbn = new StatusBarNotification(preOPkg, preOPkg, 9,
                "testBumpFGImportance_noChannelChangePreOApp", Binder.getCallingUid(),
                0, nb.build(), new UserHandle(Binder.getCallingUid()), null, 0);

        mBinderService.enqueueNotificationWithTag(preOPkg, preOPkg,
                "testBumpFGImportance_noChannelChangePreOApp",
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
        verify(mAssistants).notifyAssistantNotificationDirectReplyLocked(eq(r.getSbn()));

        assertEquals(1, mNotificationRecordLogger.numCalls());
        assertEquals(NotificationRecordLogger.NotificationEvent.NOTIFICATION_DIRECT_REPLIED,
                mNotificationRecordLogger.event(0));
    }

    @Test
    public void testStats_updatedOnUserExpansion() throws Exception {
        NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);

        mService.mNotificationDelegate.onNotificationExpansionChanged(r.getKey(), true, true,
                NOTIFICATION_LOCATION_UNKNOWN);
        verify(mAssistants).notifyAssistantExpansionChangedLocked(eq(r.getSbn()), eq(true),
                eq((true)));
        assertTrue(mService.getNotificationRecord(r.getKey()).getStats().hasExpanded());

        mService.mNotificationDelegate.onNotificationExpansionChanged(r.getKey(), true, false,
                NOTIFICATION_LOCATION_UNKNOWN);
        verify(mAssistants).notifyAssistantExpansionChangedLocked(eq(r.getSbn()), eq(true),
                eq((false)));
        assertTrue(mService.getNotificationRecord(r.getKey()).getStats().hasExpanded());

        assertEquals(2, mNotificationRecordLogger.numCalls());
        assertEquals(NotificationRecordLogger.NotificationEvent.NOTIFICATION_DETAIL_OPEN_USER,
                mNotificationRecordLogger.event(0));
        assertEquals(NotificationRecordLogger.NotificationEvent.NOTIFICATION_DETAIL_CLOSE_USER,
                mNotificationRecordLogger.event(1));
    }

    @Test
    public void testStats_notUpdatedOnAutoExpansion() throws Exception {
        NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);

        mService.mNotificationDelegate.onNotificationExpansionChanged(r.getKey(), false, true,
                NOTIFICATION_LOCATION_UNKNOWN);
        assertFalse(mService.getNotificationRecord(r.getKey()).getStats().hasExpanded());
        verify(mAssistants).notifyAssistantExpansionChangedLocked(eq(r.getSbn()), eq(false),
                eq((true)));

        mService.mNotificationDelegate.onNotificationExpansionChanged(r.getKey(), false, false,
                NOTIFICATION_LOCATION_UNKNOWN);
        assertFalse(mService.getNotificationRecord(r.getKey()).getStats().hasExpanded());
        verify(mAssistants).notifyAssistantExpansionChangedLocked(
                eq(r.getSbn()), eq(false), eq((false)));
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
        verify(mAssistants).notifyAssistantVisibilityChangedLocked(eq(r.getSbn()), eq(true));
        assertTrue(mService.getNotificationRecord(r.getKey()).getStats().hasSeen());
        mService.mNotificationDelegate.onNotificationVisibilityChanged(
                new NotificationVisibility[] {}, new NotificationVisibility[]{nv});
        verify(mAssistants).notifyAssistantVisibilityChangedLocked(eq(r.getSbn()), eq(false));
        assertTrue(mService.getNotificationRecord(r.getKey()).getStats().hasSeen());
    }

    @Test
    public void testStats_dismissalSurface() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        r.getSbn().setInstanceId(mNotificationInstanceIdSequence.newInstanceId());
        mService.addNotification(r);

        final NotificationVisibility nv = NotificationVisibility.obtain(r.getKey(), 0, 1, true);
        mService.mNotificationDelegate.onNotificationClear(mUid, 0, PKG, r.getSbn().getTag(),
                r.getSbn().getId(), r.getUserId(), r.getKey(), NotificationStats.DISMISSAL_AOD,
                NotificationStats.DISMISS_SENTIMENT_POSITIVE, nv);
        waitForIdle();

        assertEquals(NotificationStats.DISMISSAL_AOD, r.getStats().getDismissalSurface());

        // Using mService.addNotification() does not generate a NotificationRecordLogger log,
        // so we only get the cancel notification.
        assertEquals(1, mNotificationRecordLogger.numCalls());

        assertEquals(
                NotificationRecordLogger.NotificationCancelledEvent.NOTIFICATION_CANCEL_USER_AOD,
                mNotificationRecordLogger.event(0));
        assertEquals(1, mNotificationRecordLogger.get(0).getInstanceId());
    }

    @Test
    public void testStats_dismissalSentiment() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);

        final NotificationVisibility nv = NotificationVisibility.obtain(r.getKey(), 0, 1, true);
        mService.mNotificationDelegate.onNotificationClear(mUid, 0, PKG, r.getSbn().getTag(),
                r.getSbn().getId(), r.getUserId(), r.getKey(), NotificationStats.DISMISSAL_AOD,
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
                r.getSbn().getPackageName(), r.getKey(), signals, "", r.getUser().getIdentifier());
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
                r.getSbn().getPackageName(), r.getKey(), signals, "", r.getUser().getIdentifier());
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
                r.getSbn().getPackageName(), r.getKey(), signals, "", r.getUser().getIdentifier());
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
                r.getSbn().getPackageName(), r.getKey(), signals, "", r.getUser().getIdentifier());
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
                r.getSbn().getPackageName(), r.getKey(), signals, "", r.getUser().getIdentifier());
        mBinderService.applyEnqueuedAdjustmentFromAssistant(null, adjustment);

        assertEquals(USER_SENTIMENT_NEUTRAL, r.getUserSentiment());

        waitForIdle();

        verify(handler, timeout(300).times(0)).scheduleSendRankingUpdate();
    }

    @Test
    public void testUserSentimentChangeTriggersUpdate() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);
        when(mAssistants.isSameUser(eq(null), anyInt())).thenReturn(true);

        Bundle signals = new Bundle();
        signals.putInt(Adjustment.KEY_USER_SENTIMENT,
                USER_SENTIMENT_NEGATIVE);
        Adjustment adjustment = new Adjustment(
                r.getSbn().getPackageName(), r.getKey(), signals, "", r.getUser().getIdentifier());
        mBinderService.applyAdjustmentFromAssistant(null, adjustment);

        waitForIdle();

        verify(mRankingHandler, timeout(300).times(1)).requestSort();
    }

    @Test
    public void testTooLateAdjustmentTriggersUpdate() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addNotification(r);
        when(mAssistants.isSameUser(eq(null), anyInt())).thenReturn(true);

        Bundle signals = new Bundle();
        signals.putInt(Adjustment.KEY_USER_SENTIMENT,
                USER_SENTIMENT_NEGATIVE);
        Adjustment adjustment = new Adjustment(
                r.getSbn().getPackageName(), r.getKey(), signals, "", r.getUser().getIdentifier());
        mBinderService.applyEnqueuedAdjustmentFromAssistant(null, adjustment);

        waitForIdle();

        verify(mRankingHandler, times(1)).requestSort();
    }

    @Test
    public void testEnqueuedAdjustmentAppliesAdjustments() throws Exception {
        final NotificationRecord r = generateNotificationRecord(mTestNotificationChannel);
        mService.addEnqueuedNotification(r);
        when(mAssistants.isSameUser(eq(null), anyInt())).thenReturn(true);

        Bundle signals = new Bundle();
        signals.putInt(Adjustment.KEY_USER_SENTIMENT,
                USER_SENTIMENT_NEGATIVE);
        Adjustment adjustment = new Adjustment(
                r.getSbn().getPackageName(), r.getKey(), signals, "", r.getUser().getIdentifier());
        mBinderService.applyEnqueuedAdjustmentFromAssistant(null, adjustment);

        assertEquals(USER_SENTIMENT_NEGATIVE, r.getUserSentiment());
    }

    @Test
    public void testRestore() throws Exception {
        int systemChecks = mService.countSystemChecks;
        mBinderService.applyRestore(null, USER_SYSTEM);
        assertEquals(1, mService.countSystemChecks - systemChecks);
    }

    @Test
    public void testBackupEmptySound() throws Exception {
        NotificationChannel channel = new NotificationChannel("a", "ab", IMPORTANCE_DEFAULT);
        channel.setSound(Uri.EMPTY, null);

        XmlSerializer serializer = new FastXmlSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
        channel.writeXmlForBackup(serializer, getContext());

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        NotificationChannel restored = new NotificationChannel("a", "ab", IMPORTANCE_DEFAULT);
        restored.populateFromXmlForRestore(parser, getContext());

        assertNull(restored.getSound());
    }

    @Test
    public void testBackup() throws Exception {
        int systemChecks = mService.countSystemChecks;
        when(mListeners.queryPackageForServices(anyString(), anyInt(), anyInt()))
                .thenReturn(new ArraySet<>());
        mBinderService.getBackupPayload(1);
        assertEquals(1, mService.countSystemChecks - systemChecks);
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
                USER_SYSTEM);
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
                USER_SYSTEM);
        verify(mUgmInternal, times(1)).revokeUriPermissionFromOwner(any(),
                eq(message1.getDataUri()), anyInt(), anyInt());

        // Update back means we grant access to first again
        reset(mUgm);
        mService.updateUriPermissions(recordA, recordB, mContext.getPackageName(),
                USER_SYSTEM);
        verify(mUgm, times(1)).grantUriPermissionFromOwner(any(), anyInt(), any(),
                eq(message1.getDataUri()), anyInt(), anyInt(), anyInt());

        // And update to empty means we drop everything
        reset(mUgmInternal);
        mService.updateUriPermissions(null, recordB, mContext.getPackageName(),
                USER_SYSTEM);
        verify(mUgmInternal, times(1)).revokeUriPermissionFromOwner(any(), eq(null),
                anyInt(), anyInt());
    }

    @Test
    public void updateUriPermissions_posterDoesNotOwnUri() throws Exception {
        NotificationChannel c = new NotificationChannel(
                TEST_CHANNEL_ID, TEST_CHANNEL_ID, IMPORTANCE_DEFAULT);
        c.setSound(null, Notification.AUDIO_ATTRIBUTES_DEFAULT);
        Message message1 = new Message("", 0, "");
        message1.setData("",
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 1));

        Notification.Builder nbA = new Notification.Builder(mContext, c.getId())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setStyle(new Notification.MessagingStyle("")
                        .addMessage(message1));
        NotificationRecord recordA = new NotificationRecord(mContext, new StatusBarNotification(
                PKG, PKG, 0, "tag", mUid, 0, nbA.build(), new UserHandle(mUid), null, 0), c);

        doThrow(new SecurityException("no access")).when(mUgm)
                .grantUriPermissionFromOwner(
                        any(), anyInt(), any(), any(), anyInt(), anyInt(), anyInt());

        when(mUgmInternal.newUriPermissionOwner(any())).thenReturn(new Binder());
        mService.updateUriPermissions(recordA, null, mContext.getPackageName(),  USER_SYSTEM);

        // yay, no crash
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
        assertEquals("a", captorHide.getValue().get(0).getSbn().getPackageName());

        // on broadcast, unhide the package
        mService.simulatePackageDistractionBroadcast(
                PackageManager.RESTRICTION_HIDE_FROM_SUGGESTIONS, new String[] {"a"});
        ArgumentCaptor<List<NotificationRecord>> captorUnhide = ArgumentCaptor.forClass(List.class);
        verify(mListeners, times(1)).notifyUnhiddenLocked(captorUnhide.capture());
        assertEquals(1, captorUnhide.getValue().size());
        assertEquals("a", captorUnhide.getValue().get(0).getSbn().getPackageName());
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

        // should be called only once.
        verify(mListeners, times(1)).notifyHiddenLocked(captorHide.capture());
        assertEquals(2, captorHide.getValue().size());
        assertEquals("a", captorHide.getValue().get(0).getSbn().getPackageName());
        assertEquals("b", captorHide.getValue().get(1).getSbn().getPackageName());

        // on broadcast, unhide the package
        mService.simulatePackageDistractionBroadcast(
                PackageManager.RESTRICTION_HIDE_FROM_SUGGESTIONS, new String[] {"a", "b"});
        ArgumentCaptor<List<NotificationRecord>> captorUnhide = ArgumentCaptor.forClass(List.class);

        // should be called only once.
        verify(mListeners, times(1)).notifyUnhiddenLocked(captorUnhide.capture());
        assertEquals(2, captorUnhide.getValue().size());
        assertEquals("a", captorUnhide.getValue().get(0).getSbn().getPackageName());
        assertEquals("b", captorUnhide.getValue().get(1).getSbn().getPackageName());
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
    public void testCanUseManagedServicesNullPkg() {
        assertEquals(true, mService.canUseManagedServices(null, 0, null));
    }


    @Test
    public void testCanUseManagedServicesNoValidPkg() {
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
    public void testOnNotificationVisibilityChanged_triggersVisibilityLog() {
        final NotificationRecord r = generateNotificationRecord(
                mTestNotificationChannel, 1, null, true);
        r.setTextChanged(true);
        r.getSbn().setInstanceId(mNotificationInstanceIdSequence.newInstanceId());
        mService.addNotification(r);

        mService.mNotificationDelegate.onNotificationVisibilityChanged(new NotificationVisibility[]
                {NotificationVisibility.obtain(r.getKey(), 1, 1, true)},
                new NotificationVisibility[]{});

        assertEquals(1, mNotificationRecordLogger.numCalls());
        assertEquals(NotificationRecordLogger.NotificationEvent.NOTIFICATION_OPEN,
                mNotificationRecordLogger.event(0));
        assertEquals(1, mNotificationRecordLogger.get(0).getInstanceId());

        mService.mNotificationDelegate.onNotificationVisibilityChanged(
                new NotificationVisibility[]{},
                new NotificationVisibility[]
                        {NotificationVisibility.obtain(r.getKey(), 1, 1, true)}
        );

        assertEquals(2, mNotificationRecordLogger.numCalls());
        assertEquals(NotificationRecordLogger.NotificationEvent.NOTIFICATION_CLOSE,
                mNotificationRecordLogger.event(1));
        assertEquals(1, mNotificationRecordLogger.get(1).getInstanceId());
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
        verify(mHistoryManager, times(1)).addNotification(any());
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
        when(mPackageManager.getApplicationInfo(anyString(), anyInt(), eq(USER_SYSTEM)))
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
    public void testPostFromAndroidForNonExistentPackage() throws Exception {
        final String notReal = "NOT REAL";
        when(mPackageManagerClient.getPackageUidAsUser(anyString(), anyInt())).thenThrow(
                PackageManager.NameNotFoundException.class);
        ApplicationInfo ai = new ApplicationInfo();
        ai.uid = -1;
        when(mPackageManager.getApplicationInfo(anyString(), anyInt(), anyInt())).thenReturn(ai);

        final StatusBarNotification sbn = generateNotificationRecord(null).getSbn();
        try {
            mInternalService.enqueueNotification(notReal, "android", 0, 0,
                    "testPostFromAndroidForNonExistentPackage",
                    sbn.getId(), sbn.getNotification(), sbn.getUserId());
            fail("can't post notifications for nonexistent packages, even if you exist");
        } catch (SecurityException e) {
            // yay
        }
    }

    @Test
    public void testCancelFromAndroidForNonExistentPackage() throws Exception {
        final String notReal = "NOT REAL";
        when(mPackageManagerClient.getPackageUidAsUser(eq(notReal), anyInt())).thenThrow(
                PackageManager.NameNotFoundException.class);
        ApplicationInfo ai = new ApplicationInfo();
        ai.uid = -1;
        when(mPackageManager.getApplicationInfo(anyString(), anyInt(), anyInt())).thenReturn(ai);

        // unlike the post case, ignore instead of throwing
        final StatusBarNotification sbn = generateNotificationRecord(null).getSbn();

        mInternalService.cancelNotification(notReal, "android", 0, 0, "tag",
                sbn.getId(), sbn.getUserId());
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
                PKG, r.getSbn().getId(), r.getSbn().getUserId());

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
                PKG, r.getSbn().getId(), r.getSbn().getUserId());

        waitForIdle();

        ArgumentCaptor<NotificationRecord> captor =
                ArgumentCaptor.forClass(NotificationRecord.class);
        verify(mListeners, times(1)).notifyPostedLocked(captor.capture(), any());

        assertEquals(0, captor.getValue().getNotification().flags);
    }

    @Test
    public void testAllowForegroundCustomToasts() throws Exception {
        final String testPackage = "testPackageName";
        assertEquals(0, mService.mToastQueue.size());
        mService.isSystemUid = false;

        // package is not suspended
        when(mPackageManager.isPackageSuspendedForUser(testPackage, UserHandle.getUserId(mUid)))
                .thenReturn(false);

        // notifications from this package are blocked by the user
        mService.setPreferencesHelper(mPreferencesHelper);
        when(mPreferencesHelper.getImportance(testPackage, mUid)).thenReturn(IMPORTANCE_NONE);

        setAppInForegroundForToasts(mUid, true);

        // enqueue toast -> toast should still enqueue
        ((INotificationManager) mService.mService).enqueueToast(testPackage, new Binder(),
                new TestableToastCallback(), 2000, 0);
        assertEquals(1, mService.mToastQueue.size());
    }

    @Test
    public void testDisallowBackgroundCustomToasts() throws Exception {
        final String testPackage = "testPackageName";
        assertEquals(0, mService.mToastQueue.size());
        mService.isSystemUid = false;

        // package is not suspended
        when(mPackageManager.isPackageSuspendedForUser(testPackage, UserHandle.getUserId(mUid)))
                .thenReturn(false);

        setAppInForegroundForToasts(mUid, false);

        // enqueue toast -> no toasts enqueued
        ((INotificationManager) mService.mService).enqueueToast(testPackage, new Binder(),
                new TestableToastCallback(), 2000, 0);
        assertEquals(0, mService.mToastQueue.size());
    }

    @Test
    public void testAllowForegroundTextToasts() throws Exception {
        final String testPackage = "testPackageName";
        assertEquals(0, mService.mToastQueue.size());
        mService.isSystemUid = false;

        // package is not suspended
        when(mPackageManager.isPackageSuspendedForUser(testPackage, UserHandle.getUserId(mUid)))
                .thenReturn(false);

        setAppInForegroundForToasts(mUid, true);

        // enqueue toast -> toast should still enqueue
        ((INotificationManager) mService.mService).enqueueTextToast(testPackage, new Binder(),
                "Text", 2000, 0, null);
        assertEquals(1, mService.mToastQueue.size());
    }

    @Test
    public void testAllowBackgroundTextToasts() throws Exception {
        final String testPackage = "testPackageName";
        assertEquals(0, mService.mToastQueue.size());
        mService.isSystemUid = false;

        // package is not suspended
        when(mPackageManager.isPackageSuspendedForUser(testPackage, UserHandle.getUserId(mUid)))
                .thenReturn(false);

        setAppInForegroundForToasts(mUid, false);

        // enqueue toast -> toast should still enqueue
        ((INotificationManager) mService.mService).enqueueTextToast(testPackage, new Binder(),
                "Text", 2000, 0, null);
        assertEquals(1, mService.mToastQueue.size());
    }

    @Test
    public void testTextToastsCallStatusBar() throws Exception {
        final String testPackage = "testPackageName";
        assertEquals(0, mService.mToastQueue.size());
        mService.isSystemUid = false;

        // package is not suspended
        when(mPackageManager.isPackageSuspendedForUser(testPackage, UserHandle.getUserId(mUid)))
                .thenReturn(false);

        // enqueue toast -> no toasts enqueued
        ((INotificationManager) mService.mService).enqueueTextToast(testPackage, new Binder(),
                "Text", 2000, 0, null);
        verify(mStatusBar).showToast(any(), any(), any(), any(), anyInt(), any());
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
        ((INotificationManager) mService.mService).enqueueToast(testPackage, new Binder(),
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

        setAppInForegroundForToasts(mUid, false);

        // enqueue toast -> no toasts enqueued
        ((INotificationManager) mService.mService).enqueueToast(testPackage, new Binder(),
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

        setAppInForegroundForToasts(mUid, false);

        // enqueue toast -> system toast can still be enqueued
        ((INotificationManager) mService.mService).enqueueToast(testPackage, new Binder(),
                new TestableToastCallback(), 2000, 0);
        assertEquals(1, mService.mToastQueue.size());
    }

    private void setAppInForegroundForToasts(int uid, boolean inForeground) {
        int importance = (inForeground) ? IMPORTANCE_FOREGROUND : IMPORTANCE_NONE;
        when(mActivityManager.getUidImportance(mUid)).thenReturn(importance);
        when(mAtm.hasResumedActivity(uid)).thenReturn(inForeground);
    }

    @Test
    public void testOnPanelRevealedAndHidden() {
        int items = 5;
        mService.mNotificationDelegate.onPanelRevealed(false, items);
        verify(mAssistants, times(1)).onPanelRevealed(eq(items));

        mService.mNotificationDelegate.onPanelHidden();
        verify(mAssistants, times(1)).onPanelHidden();

        assertEquals(2, mNotificationRecordLogger.numCalls());
        assertEquals(NotificationRecordLogger.NotificationPanelEvent.NOTIFICATION_PANEL_OPEN,
                mNotificationRecordLogger.event(0));
        assertEquals(NotificationRecordLogger.NotificationPanelEvent.NOTIFICATION_PANEL_CLOSE,
                mNotificationRecordLogger.event(1));
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
                eq(r.getSbn()), eq(reply), eq(generatedByAssistant));
        assertEquals(1, mNotificationRecordLogger.numCalls());
        assertEquals(NotificationRecordLogger.NotificationEvent.NOTIFICATION_SMART_REPLIED,
                mNotificationRecordLogger.event(0));
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
                eq(r.getSbn()), eq(actionIndex), eq(action), eq(generatedByAssistant));

        assertEquals(1, mNotificationRecordLogger.numCalls());
        assertEquals(NotificationRecordLogger.NotificationEvent.NOTIFICATION_ACTION_CLICKED,
                mNotificationRecordLogger.event(0));
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

    @Test
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

        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, r.getSbn().getId(),
                r.getSbn().getTag(), mUid, 0,
                new Notification.Builder(mContext, mTestNotificationChannel.getId()).build(),
                new UserHandle(mUid), null, 0);
        NotificationRecord update = new NotificationRecord(mContext, sbn, mTestNotificationChannel);
        mService.addEnqueuedNotification(update);
        assertNull(update.getSbn().getNotification().getSmallIcon());

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
        enableInteractAcrossUsers();
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
        enableInteractAcrossUsers();
        mBinderService.getNotificationChannels("src", "target", mContext.getUserId() + 1);
    }

    @Test
    public void setDefaultAssistantForUser_fromConfigXml() {
        clearDeviceConfig();
        ComponentName xmlConfig = new ComponentName("config", "xml");
        ArraySet<ComponentName> components = new ArraySet<>(Arrays.asList(xmlConfig));
        when(mResources
                .getString(
                        com.android.internal.R.string.config_defaultAssistantAccessComponent))
                .thenReturn(xmlConfig.flattenToString());
        when(mContext.getResources()).thenReturn(mResources);
        when(mAssistants.queryPackageForServices(eq(null), anyInt(), anyInt()))
                .thenReturn(components);
        when(mAssistants.getDefaultComponents())
                .thenReturn(components);
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
        when(mAssistants.queryPackageForServices(eq(null), anyInt(), anyInt()))
                .thenReturn(new ArraySet<>(Arrays.asList(xmlConfig, deviceConfig)));
        when(mAssistants.getDefaultComponents())
                .thenReturn(new ArraySet<>(Arrays.asList(deviceConfig)));
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
                .thenReturn(new ArraySet<>(Collections.singleton(xmlConfig)));
        when(mAssistants.getDefaultComponents())
                .thenReturn(new ArraySet<>(Arrays.asList(xmlConfig, deviceConfig)));
        mService.setNotificationAssistantAccessGrantedCallback(
                mNotificationAssistantAccessGrantedCallback);

        mService.setDefaultAssistantForUser(0);

        verify(mNotificationAssistantAccessGrantedCallback)
                .onGranted(eq(xmlConfig), eq(0), eq(true));
    }

    @Test
    public void clearMultipleDefaultAssistantPackagesShouldEnableOnlyOne() throws RemoteException {
        ArrayMap<Boolean, ArrayList<ComponentName>> changedListeners =
                generateResetComponentValues();
        when(mListeners.resetComponents(anyString(), anyInt())).thenReturn(changedListeners);
        ArrayMap<Boolean, ArrayList<ComponentName>> changes = new ArrayMap<>();
        ComponentName deviceConfig1 = new ComponentName("device", "config1");
        ComponentName deviceConfig2 = new ComponentName("device", "config2");
        changes.put(true, new ArrayList(Arrays.asList(deviceConfig1, deviceConfig2)));
        changes.put(false, new ArrayList());
        when(mAssistants.resetComponents(anyString(), anyInt())).thenReturn(changes);
        mService.getBinderService().clearData("device", 0, false);
        verify(mAssistants, times(1))
                .setPackageOrComponentEnabled(
                        eq("device/config2"),
                        eq(0), eq(true), eq(false));
        verify(mConditionProviders, times(1)).setPackageOrComponentEnabled(
                eq("device"), eq(0), eq(false), eq(true));
    }

    @Test
    public void clearDefaultListenersPackageShouldEnableIt() throws RemoteException {
        ArrayMap<Boolean, ArrayList<ComponentName>> changedAssistants =
                generateResetComponentValues();
        when(mAssistants.resetComponents(anyString(), anyInt())).thenReturn(changedAssistants);
        ComponentName deviceConfig = new ComponentName("device", "config");
        ArrayMap<Boolean, ArrayList<ComponentName>> changes = new ArrayMap<>();
        changes.put(true, new ArrayList(Arrays.asList(deviceConfig)));
        changes.put(false, new ArrayList());
        when(mListeners.resetComponents(anyString(), anyInt()))
            .thenReturn(changes);
        mService.getBinderService().clearData("device", 0, false);
        verify(mConditionProviders, times(1)).setPackageOrComponentEnabled(
                eq("device"), eq(0), eq(false), eq(true));
    }

    @Test
    public void clearDefaultDnDPackageShouldEnableIt() throws RemoteException {
        ComponentName deviceConfig = new ComponentName("device", "config");
        ArrayMap<Boolean, ArrayList<ComponentName>> changed = generateResetComponentValues();
        when(mAssistants.resetComponents(anyString(), anyInt())).thenReturn(changed);
        when(mListeners.resetComponents(anyString(), anyInt())).thenReturn(changed);
        mService.getBinderService().clearData("device", 0, false);
        verify(mConditionProviders, times(1)).resetPackage(
                        eq("device"), eq(0));
    }

    @Test
    public void testFlagBubble() throws RemoteException {
        // Bubbles are allowed!
        setUpPrefsForBubbles(PKG, mUid, true /* global */, true /* app */, true /* channel */);

        NotificationRecord nr =
                generateMessageBubbleNotifRecord(mTestNotificationChannel, "testFlagBubble");

        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();

        StatusBarNotification[] notifs = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifs.length);
        assertTrue((notifs[0].getNotification().flags & FLAG_BUBBLE) != 0);
        assertTrue(mService.getNotificationRecord(
                nr.getSbn().getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubble_noFlag_appNotAllowed() throws RemoteException {
        // Bubbles are allowed!
        setUpPrefsForBubbles(PKG, mUid, true /* global */, false /* app */, true /* channel */);

        NotificationRecord nr = generateMessageBubbleNotifRecord(mTestNotificationChannel,
                        "testFlagBubble_noFlag_appNotAllowed");

        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();

        StatusBarNotification[] notifs = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifs.length);
        assertEquals((notifs[0].getNotification().flags & FLAG_BUBBLE), 0);
        assertFalse(mService.getNotificationRecord(
                nr.getSbn().getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubbleNotifs_noFlag_whenAppForeground() throws RemoteException {
        // Bubbles are allowed!
        setUpPrefsForBubbles(PKG, mUid, true /* global */, true /* app */, true /* channel */);

        // Notif with bubble metadata but not our other misc requirements
        Notification.Builder nb = new Notification.Builder(mContext,
                mTestNotificationChannel.getId())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setBubbleMetadata(getBubbleMetadataBuilder().build());
        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 1, "tag", mUid, 0,
                nb.build(), new UserHandle(mUid), null, 0);
        NotificationRecord nr = new NotificationRecord(mContext, sbn, mTestNotificationChannel);

        // Say we're foreground
        when(mActivityManager.getPackageImportance(nr.getSbn().getPackageName())).thenReturn(
                IMPORTANCE_FOREGROUND);
        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();

        // if notif isn't configured properly it doesn't get to bubble just because app is
        // foreground.
        assertFalse(mService.getNotificationRecord(
                nr.getSbn().getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubbleNotifs_flag_messaging() throws RemoteException {
        // Bubbles are allowed!
        setUpPrefsForBubbles(PKG, mUid, true /* global */, true /* app */, true /* channel */);

        NotificationRecord nr = generateMessageBubbleNotifRecord(mTestNotificationChannel,
                "testFlagBubbleNotifs_flag_messaging");

        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();

        // yes allowed, yes messaging, yes bubble
        assertTrue(mService.getNotificationRecord(
                nr.getSbn().getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubbleNotifs_noFlag_messaging_appNotAllowed() throws RemoteException {
        // Bubbles are NOT allowed!
        setUpPrefsForBubbles(PKG, mUid, true /* global */, false /* app */, true /* channel */);

        NotificationRecord nr = generateMessageBubbleNotifRecord(mTestNotificationChannel,
                "testFlagBubbleNotifs_noFlag_messaging_appNotAllowed");

        // Post the notification
        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();

        // not allowed, no bubble
        assertFalse(mService.getNotificationRecord(
                nr.getSbn().getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubbleNotifs_noFlag_notBubble() throws RemoteException {
        // Bubbles are allowed!
        setUpPrefsForBubbles(PKG, mUid, true /* global */, true /* app */, true /* channel */);

        // Messaging notif WITHOUT bubble metadata
        Notification.Builder nb = getMessageStyleNotifBuilder(false /* addBubbleMetadata */,
                null /* groupKey */, false /* isSummary */);

        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 1,
                "testFlagBubbleNotifs_noFlag_notBubble", mUid, 0,
                nb.build(), new UserHandle(mUid), null, 0);
        NotificationRecord nr = new NotificationRecord(mContext, sbn, mTestNotificationChannel);

        // Post the notification
        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();

        // no bubble metadata, no bubble
        assertFalse(mService.getNotificationRecord(
                nr.getSbn().getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubbleNotifs_noFlag_messaging_channelNotAllowed() throws RemoteException {
        // Bubbles are allowed except on this channel
        setUpPrefsForBubbles(PKG, mUid, true /* global */, true /* app */, false /* channel */);

        NotificationRecord nr = generateMessageBubbleNotifRecord(mTestNotificationChannel,
                "testFlagBubbleNotifs_noFlag_messaging_channelNotAllowed");

        // Post the notification
        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();

        // channel not allowed, no bubble
        assertFalse(mService.getNotificationRecord(
                nr.getSbn().getKey()).getNotification().isBubbleNotification());
    }

    @Test
    public void testCancelNotificationsFromApp_cancelsBubbles() throws Exception {
        final NotificationRecord nrBubble = generateNotificationRecord(mTestNotificationChannel);
        nrBubble.getSbn().getNotification().flags |= FLAG_BUBBLE;

        // Post the notification
        mBinderService.enqueueNotificationWithTag(PKG, PKG,
                "testAppCancelNotifications_cancelsBubbles",
                nrBubble.getSbn().getId(), nrBubble.getSbn().getNotification(),
                nrBubble.getSbn().getUserId());
        waitForIdle();

        StatusBarNotification[] notifs = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifs.length);
        assertEquals(1, mService.getNotificationRecordCount());

        mBinderService.cancelNotificationWithTag(PKG, PKG,
                "testAppCancelNotifications_cancelsBubbles", nrBubble.getSbn().getId(),
                nrBubble.getSbn().getUserId());
        waitForIdle();

        StatusBarNotification[] notifs2 = mBinderService.getActiveNotifications(PKG);
        assertEquals(0, notifs2.length);
        assertEquals(0, mService.getNotificationRecordCount());
    }

    @Test
    public void testCancelAllNotificationsFromApp_cancelsBubble() throws Exception {
        final NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel);
        nr.getSbn().getNotification().flags |= FLAG_BUBBLE;
        mService.addNotification(nr);

        mBinderService.cancelAllNotifications(PKG, nr.getSbn().getUserId());
        waitForIdle();

        StatusBarNotification[] notifs = mBinderService.getActiveNotifications(PKG);
        assertEquals(0, notifs.length);
        assertEquals(0, mService.getNotificationRecordCount());
    }

    @Test
    public void testCancelAllNotificationsFromListener_ignoresBubbles() throws Exception {
        final NotificationRecord nrNormal = generateNotificationRecord(mTestNotificationChannel);
        final NotificationRecord nrBubble = generateNotificationRecord(mTestNotificationChannel);
        nrBubble.getSbn().getNotification().flags |= FLAG_BUBBLE;

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
        nrBubble.getSbn().getNotification().flags |= FLAG_BUBBLE;

        mService.addNotification(nrNormal);
        mService.addNotification(nrBubble);

        String[] keys = {nrNormal.getSbn().getKey(), nrBubble.getSbn().getKey()};
        mService.getBinderService().cancelNotificationsFromListener(null, keys);
        waitForIdle();

        StatusBarNotification[] notifs = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifs.length);
        assertEquals(1, mService.getNotificationRecordCount());

        assertEquals(1, mNotificationRecordLogger.numCalls());
        assertEquals(NotificationRecordLogger.NotificationCancelledEvent
                .NOTIFICATION_CANCEL_LISTENER_CANCEL, mNotificationRecordLogger.event(0));
    }

    @Test
    public void testCancelAllNotificationsFromStatusBar_ignoresBubble() throws Exception {
        // GIVEN a notification bubble
        final NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel);
        nr.getSbn().getNotification().flags |= FLAG_BUBBLE;
        mService.addNotification(nr);

        // WHEN the status bar clears all notifications
        mService.mNotificationDelegate.onClearAll(mUid, Binder.getCallingPid(),
                nr.getSbn().getUserId());
        waitForIdle();

        // THEN the bubble notification does not get removed
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
        Adjustment adjustment = new Adjustment(r.getSbn().getPackageName(), r.getKey(), signals,
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
        enableInteractAcrossUsers();
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
        enableInteractAcrossUsers();
        mBinderService.areBubblesAllowedForPackage(mContext.getPackageName(),
                mUid + UserHandle.PER_USER_RANGE);
    }

    private void enableInteractAcrossUsers() {
        TestablePermissions perms = mContext.getTestablePermissions();
        perms.setPermission(android.Manifest.permission.INTERACT_ACROSS_USERS, PERMISSION_GRANTED);
    }

    @Test
    public void testNotificationBubbleChanged_false() throws Exception {
        // Bubbles are allowed!
        setUpPrefsForBubbles(PKG, mUid, true /* global */, true /* app */, true /* channel */);

        // Notif with bubble metadata
        NotificationRecord nr = generateMessageBubbleNotifRecord(mTestNotificationChannel,
                "testNotificationBubbleChanged_false");

        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
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
        setUpPrefsForBubbles(PKG, mUid, true /* global */, true /* app */, true /* channel */);

        // Notif that is not a bubble
        NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel,
                1, null, false);
        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();

        // Would be a normal notification because wouldn't have met requirements to bubble
        StatusBarNotification[] notifsBefore = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifsBefore.length);
        assertEquals((notifsBefore[0].getNotification().flags & FLAG_BUBBLE), 0);

        // Update the notification to be message style / meet bubble requirements
        NotificationRecord nr2 = generateMessageBubbleNotifRecord(mTestNotificationChannel,
                nr.getSbn().getTag());
        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr2.getSbn().getTag(),
                nr2.getSbn().getId(), nr2.getSbn().getNotification(), nr2.getSbn().getUserId());
        waitForIdle();

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
        setUpPrefsForBubbles(PKG, mUid, true /* global */, true /* app */, true /* channel */);

        // Notif that is not a bubble
        NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel);
        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
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
    public void testNotificationBubbleIsFlagRemoved_resetOnUpdate() throws Exception {
        // Bubbles are allowed!
        setUpPrefsForBubbles(PKG, mUid, true /* global */, true /* app */, true /* channel */);
        // Notif with bubble metadata
        NotificationRecord nr = generateMessageBubbleNotifRecord(mTestNotificationChannel,
                "testNotificationBubbleIsFlagRemoved_resetOnUpdate");

        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();
        // Flag shouldn't be modified
        NotificationRecord recordToCheck = mService.getNotificationRecord(nr.getSbn().getKey());
        assertFalse(recordToCheck.isFlagBubbleRemoved());

        // Notify we're not a bubble
        mService.mNotificationDelegate.onNotificationBubbleChanged(nr.getKey(), false);
        waitForIdle();
        // Flag should be modified
        recordToCheck = mService.getNotificationRecord(nr.getSbn().getKey());
        assertTrue(recordToCheck.isFlagBubbleRemoved());


        // Update the notif
        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();
        // And the flag is reset
        recordToCheck = mService.getNotificationRecord(nr.getSbn().getKey());
        assertFalse(recordToCheck.isFlagBubbleRemoved());
    }

    @Test
    public void testNotificationBubbleIsFlagRemoved_resetOnBubbleChangedTrue() throws Exception {
        // Bubbles are allowed!
        setUpPrefsForBubbles(PKG, mUid, true /* global */, true /* app */, true /* channel */);
        // Notif with bubble metadata
        NotificationRecord nr = generateMessageBubbleNotifRecord(mTestNotificationChannel,
                "testNotificationBubbleIsFlagRemoved_trueOnBubbleChangedTrue");

        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();
        // Flag shouldn't be modified
        NotificationRecord recordToCheck = mService.getNotificationRecord(nr.getSbn().getKey());
        assertFalse(recordToCheck.isFlagBubbleRemoved());

        // Notify we're not a bubble
        mService.mNotificationDelegate.onNotificationBubbleChanged(nr.getKey(), false);
        waitForIdle();
        // Flag should be modified
        recordToCheck = mService.getNotificationRecord(nr.getSbn().getKey());
        assertTrue(recordToCheck.isFlagBubbleRemoved());

        // Notify we are a bubble
        mService.mNotificationDelegate.onNotificationBubbleChanged(nr.getKey(), true);
        waitForIdle();
        // And the flag is reset
        assertFalse(recordToCheck.isFlagBubbleRemoved());
    }

    @Test
    public void testOnBubbleNotificationSuppressionChanged() throws Exception {
        // Bubbles are allowed!
        setUpPrefsForBubbles(PKG, mUid, true /* global */, true /* app */, true /* channel */);

        // Bubble notification
        NotificationRecord nr = generateMessageBubbleNotifRecord(mTestNotificationChannel, "tag");

        // Bubbles are allowed!
        setUpPrefsForBubbles(PKG, nr.getSbn().getUserId(), true /* global */,
                true /* app */, true /* channel */);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();

        // NOT suppressed
        Notification n =  mBinderService.getActiveNotifications(PKG)[0].getNotification();
        assertFalse(n.getBubbleMetadata().isNotificationSuppressed());

        // Reset as this is called when the notif is first sent
        reset(mListeners);

        // Test: update suppression to true
        mService.mNotificationDelegate.onBubbleNotificationSuppressionChanged(nr.getKey(), true);
        waitForIdle();

        // Check
        n =  mBinderService.getActiveNotifications(PKG)[0].getNotification();
        assertTrue(n.getBubbleMetadata().isNotificationSuppressed());

        // Reset to check again
        reset(mListeners);

        // Test: update suppression to false
        mService.mNotificationDelegate.onBubbleNotificationSuppressionChanged(nr.getKey(), false);
        waitForIdle();

        // Check
        n = mBinderService.getActiveNotifications(PKG)[0].getNotification();
        assertFalse(n.getBubbleMetadata().isNotificationSuppressed());
    }

    @Test
    public void testGrantInlineReplyUriPermission_recordExists() throws Exception {
        NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel, 0);
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();

        // A notification exists for the given record
        StatusBarNotification[] notifsBefore = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifsBefore.length);

        reset(mPackageManager);

        Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 1);

        mService.mNotificationDelegate.grantInlineReplyUriPermission(
                nr.getKey(), uri, nr.getSbn().getUser(), nr.getSbn().getPackageName(),
                nr.getSbn().getUid());

        // Grant permission called for the UID of SystemUI under the target user ID
        verify(mUgm, times(1)).grantUriPermissionFromOwner(any(),
                eq(nr.getSbn().getUid()), eq(nr.getSbn().getPackageName()), eq(uri), anyInt(),
                anyInt(), eq(nr.getSbn().getUserId()));
    }

    @Test
    public void testGrantInlineReplyUriPermission_noRecordExists() throws Exception {
        NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel, 0);
        waitForIdle();

        // No notifications exist for the given record
        StatusBarNotification[] notifsBefore = mBinderService.getActiveNotifications(PKG);
        assertEquals(0, notifsBefore.length);

        Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 1);
        int uid = 0; // sysui on primary user

        mService.mNotificationDelegate.grantInlineReplyUriPermission(
                nr.getKey(), uri, nr.getSbn().getUser(), nr.getSbn().getPackageName(),
                nr.getSbn().getUid());

        // Grant permission still called if no NotificationRecord exists for the given key
        verify(mUgm, times(1)).grantUriPermissionFromOwner(any(),
                eq(nr.getSbn().getUid()), eq(nr.getSbn().getPackageName()), eq(uri), anyInt(),
                anyInt(), eq(nr.getSbn().getUserId()));
    }

    @Test
    public void testGrantInlineReplyUriPermission_userAll() throws Exception {
        // generate a NotificationRecord for USER_ALL to make sure it's converted into USER_SYSTEM
        NotificationRecord nr =
                generateNotificationRecord(mTestNotificationChannel, UserHandle.USER_ALL);
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();

        // A notification exists for the given record
        StatusBarNotification[] notifsBefore = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifsBefore.length);

        reset(mPackageManager);

        Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 1);

        mService.mNotificationDelegate.grantInlineReplyUriPermission(
                nr.getKey(), uri, nr.getSbn().getUser(), nr.getSbn().getPackageName(),
                nr.getSbn().getUid());

        // Target user for the grant is USER_ALL instead of USER_SYSTEM
        verify(mUgm, times(1)).grantUriPermissionFromOwner(any(),
                eq(nr.getSbn().getUid()), eq(nr.getSbn().getPackageName()), eq(uri), anyInt(),
                anyInt(), eq(UserHandle.USER_SYSTEM));
    }

    @Test
    public void testGrantInlineReplyUriPermission_acrossUsers() throws Exception {
        // generate a NotificationRecord for USER_ALL to make sure it's converted into USER_SYSTEM
        int otherUserId = 11;
        NotificationRecord nr =
                generateNotificationRecord(mTestNotificationChannel, otherUserId);
        mBinderService.enqueueNotificationWithTag(PKG, PKG, "tag",
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();

        // A notification exists for the given record
        StatusBarNotification[] notifsBefore = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifsBefore.length);

        reset(mPackageManager);

        Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 1);

        int uid = 0; // sysui on primary user
        int otherUserUid = (otherUserId * 100000) + 1; // sysui as a different user
        String sysuiPackage = "sysui";
        final String[] sysuiPackages = new String[] { sysuiPackage };
        when(mPackageManager.getPackagesForUid(uid)).thenReturn(sysuiPackages);

        // Make sure to mock call for USER_SYSTEM and not USER_ALL, since it's been replaced by the
        // time this is called
        when(mPackageManager.getPackageUid(sysuiPackage, 0, otherUserId))
                .thenReturn(otherUserUid);

        mService.mNotificationDelegate.grantInlineReplyUriPermission(
                nr.getKey(), uri, nr.getSbn().getUser(), nr.getSbn().getPackageName(), uid);

        // Target user for the grant is USER_ALL instead of USER_SYSTEM
        verify(mUgm, times(1)).grantUriPermissionFromOwner(any(),
                eq(otherUserUid), eq(nr.getSbn().getPackageName()), eq(uri), anyInt(), anyInt(),
                eq(otherUserId));
    }

    @Test
    public void testClearInlineReplyUriPermission_uriRecordExists() throws Exception {
        NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel, 0);
        reset(mPackageManager);

        Uri uri1 = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 1);
        Uri uri2 = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 2);

        // create an inline record with two uris in it
        mService.mNotificationDelegate.grantInlineReplyUriPermission(
                nr.getKey(), uri1, nr.getSbn().getUser(), nr.getSbn().getPackageName(),
                nr.getSbn().getUid());
        mService.mNotificationDelegate.grantInlineReplyUriPermission(
                nr.getKey(), uri2, nr.getSbn().getUser(), nr.getSbn().getPackageName(),
                nr.getSbn().getUid());

        InlineReplyUriRecord record = mService.mInlineReplyRecordsByKey.get(nr.getKey());
        assertNotNull(record); // record exists
        assertEquals(record.getUris().size(), 2); // record has two uris in it

        mService.mNotificationDelegate.clearInlineReplyUriPermissions(nr.getKey(),
                nr.getSbn().getUid());

        // permissionOwner destroyed
        verify(mUgmInternal, times(1)).revokeUriPermissionFromOwner(
                eq(record.getPermissionOwner()), eq(null), eq(~0), eq(nr.getUserId()));
    }


    @Test
    public void testClearInlineReplyUriPermission_noUriRecordExists() throws Exception {
        NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel, 0);
        reset(mPackageManager);

        mService.mNotificationDelegate.clearInlineReplyUriPermissions(nr.getKey(),
                nr.getSbn().getUid());

        // no permissionOwner destroyed
        verify(mUgmInternal, times(0)).revokeUriPermissionFromOwner(
                any(), eq(null), eq(~0), eq(nr.getUserId()));
    }

    @Test
    public void testClearInlineReplyUriPermission_userAll() throws Exception {
        NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel,
                UserHandle.USER_ALL);
        reset(mPackageManager);

        Uri uri1 = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 1);
        Uri uri2 = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 2);

        // create an inline record a uri in it
        mService.mNotificationDelegate.grantInlineReplyUriPermission(
                nr.getKey(), uri1, nr.getSbn().getUser(), nr.getSbn().getPackageName(),
                nr.getSbn().getUid());

        InlineReplyUriRecord record = mService.mInlineReplyRecordsByKey.get(nr.getKey());
        assertNotNull(record); // record exists

        mService.mNotificationDelegate.clearInlineReplyUriPermissions(
                nr.getKey(), nr.getSbn().getUid());

        // permissionOwner destroyed for USER_SYSTEM, not USER_ALL
        verify(mUgmInternal, times(1)).revokeUriPermissionFromOwner(
                eq(record.getPermissionOwner()), eq(null), eq(~0), eq(USER_SYSTEM));
    }

    @Test
    public void testNotificationBubbles_disabled_lowRamDevice() throws Exception {
        // Bubbles are allowed!
        setUpPrefsForBubbles(PKG, mUid, true /* global */, true /* app */, true /* channel */);

        // And we are low ram
        when(mActivityManager.isLowRamDevice()).thenReturn(true);

        // Notification that would typically bubble
        NotificationRecord nr = generateMessageBubbleNotifRecord(mTestNotificationChannel,
                "testNotificationBubbles_disabled_lowRamDevice");
        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();

        // But we wouldn't be a bubble because the device is low ram & all bubbles are disabled.
        StatusBarNotification[] notifsAfter = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifsAfter.length);
        assertEquals((notifsAfter[0].getNotification().flags & FLAG_BUBBLE), 0);
    }

    @Test
    public void testRemoveLargeRemoteViews() throws Exception {
        int removeSize = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_notificationStripRemoteViewSizeBytes);

        RemoteViews rv = mock(RemoteViews.class);
        when(rv.estimateMemoryUsage()).thenReturn(removeSize);
        when(rv.clone()).thenReturn(rv);
        RemoteViews rv1 = mock(RemoteViews.class);
        when(rv1.estimateMemoryUsage()).thenReturn(removeSize);
        when(rv1.clone()).thenReturn(rv1);
        RemoteViews rv2 = mock(RemoteViews.class);
        when(rv2.estimateMemoryUsage()).thenReturn(removeSize);
        when(rv2.clone()).thenReturn(rv2);
        RemoteViews rv3 = mock(RemoteViews.class);
        when(rv3.estimateMemoryUsage()).thenReturn(removeSize);
        when(rv3.clone()).thenReturn(rv3);
        RemoteViews rv4 = mock(RemoteViews.class);
        when(rv4.estimateMemoryUsage()).thenReturn(removeSize);
        when(rv4.clone()).thenReturn(rv4);
        // note: different!
        RemoteViews rv5 = mock(RemoteViews.class);
        when(rv5.estimateMemoryUsage()).thenReturn(removeSize - 1);
        when(rv5.clone()).thenReturn(rv5);

        Notification np = new Notification.Builder(mContext, "test")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentText("test")
                .setCustomContentView(rv)
                .setCustomBigContentView(rv1)
                .setCustomHeadsUpContentView(rv2)
                .build();
        Notification n = new Notification.Builder(mContext, "test")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentText("test")
                .setCustomContentView(rv3)
                .setCustomBigContentView(rv4)
                .setCustomHeadsUpContentView(rv5)
                .setPublicVersion(np)
                .build();

        assertNotNull(np.contentView);
        assertNotNull(np.bigContentView);
        assertNotNull(np.headsUpContentView);

        assertTrue(n.publicVersion.extras.containsKey(Notification.EXTRA_CONTAINS_CUSTOM_VIEW));
        assertNotNull(n.publicVersion.contentView);
        assertNotNull(n.publicVersion.bigContentView);
        assertNotNull(n.publicVersion.headsUpContentView);

        mService.fixNotification(n, PKG, "tag", 9, 0);

        assertNull(n.contentView);
        assertNull(n.bigContentView);
        assertNotNull(n.headsUpContentView);
        assertNull(n.publicVersion.contentView);
        assertNull(n.publicVersion.bigContentView);
        assertNull(n.publicVersion.headsUpContentView);

        verify(mUsageStats, times(5)).registerImageRemoved(PKG);
    }

    @Test
    public void testNotificationBubbles_flagAutoExpandForeground_fails_notForeground()
            throws Exception {
        // Bubbles are allowed!
        setUpPrefsForBubbles(PKG, mUid, true /* global */, true /* app */, true /* channel */);

        NotificationRecord nr = generateMessageBubbleNotifRecord(mTestNotificationChannel,
                "testNotificationBubbles_flagAutoExpandForeground_fails_notForeground");
        // Modify metadata flags
        nr.getSbn().getNotification().getBubbleMetadata().setFlags(
                Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE
                        | Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION);

        // Ensure we're not foreground
        when(mActivityManager.getPackageImportance(nr.getSbn().getPackageName())).thenReturn(
                IMPORTANCE_VISIBLE);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();

        // yes allowed, yes messaging, yes bubble
        Notification notif = mService.getNotificationRecord(nr.getSbn().getKey()).getNotification();
        assertTrue(notif.isBubbleNotification());

        // Our flags should have failed since we're not foreground
        assertFalse(notif.getBubbleMetadata().getAutoExpandBubble());
        assertFalse(notif.getBubbleMetadata().isNotificationSuppressed());
    }

    @Test
    public void testNotificationBubbles_flagAutoExpandForeground_succeeds_foreground()
            throws RemoteException {
        // Bubbles are allowed!
        setUpPrefsForBubbles(PKG, mUid, true /* global */, true /* app */, true /* channel */);

        NotificationRecord nr = generateMessageBubbleNotifRecord(mTestNotificationChannel,
                "testNotificationBubbles_flagAutoExpandForeground_succeeds_foreground");
        // Modify metadata flags
        nr.getSbn().getNotification().getBubbleMetadata().setFlags(
                Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE
                        | Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION);

        // Ensure we are in the foreground
        when(mActivityManager.getPackageImportance(nr.getSbn().getPackageName())).thenReturn(
                IMPORTANCE_FOREGROUND);

        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();

        // yes allowed, yes messaging, yes bubble
        Notification notif = mService.getNotificationRecord(nr.getSbn().getKey()).getNotification();
        assertTrue(notif.isBubbleNotification());

        // Our flags should have passed since we are foreground
        assertTrue(notif.getBubbleMetadata().getAutoExpandBubble());
        assertTrue(notif.getBubbleMetadata().isNotificationSuppressed());
    }

    @Test
    public void testNotificationBubbles_flagRemoved_whenShortcutRemoved()
            throws RemoteException {
        // Bubbles are allowed!
        setUpPrefsForBubbles(PKG, mUid, true /* global */, true /* app */, true /* channel */);

        ArgumentCaptor<LauncherApps.Callback> launcherAppsCallback =
                ArgumentCaptor.forClass(LauncherApps.Callback.class);

        // Messaging notification with shortcut info
        Notification.BubbleMetadata metadata =
                new Notification.BubbleMetadata.Builder("someshortcutId").build();
        Notification.Builder nb = getMessageStyleNotifBuilder(false /* addDefaultMetadata */,
                null /* groupKey */, false /* isSummary */);
        nb.setBubbleMetadata(metadata);
        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 1,
                "tag", mUid, 0, nb.build(), new UserHandle(mUid), null, 0);
        NotificationRecord nr = new NotificationRecord(mContext, sbn, mTestNotificationChannel);

        // Pretend the shortcut exists
        List<ShortcutInfo> shortcutInfos = new ArrayList<>();
        ShortcutInfo info = mock(ShortcutInfo.class);
        when(info.isLongLived()).thenReturn(true);
        shortcutInfos.add(info);
        when(mLauncherApps.getShortcuts(any(), any())).thenReturn(shortcutInfos);

        // Test: Send the bubble notification
        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();

        // Verify:

        // Make sure we register the callback for shortcut changes
        verify(mLauncherApps, times(1)).registerCallback(launcherAppsCallback.capture(), any());

        // yes allowed, yes messaging w/shortcut, yes bubble
        Notification notif = mService.getNotificationRecord(nr.getSbn().getKey()).getNotification();
        assertTrue(notif.isBubbleNotification());

        // Test: Remove the shortcut
        when(mLauncherApps.getShortcuts(any(), any())).thenReturn(null);
        launcherAppsCallback.getValue().onShortcutsChanged(PKG, Collections.emptyList(),
                new UserHandle(mUid));
        waitForIdle();

        // Verify:

        // Make sure callback is unregistered
        verify(mLauncherApps, times(1)).unregisterCallback(launcherAppsCallback.getValue());

        // We're no longer a bubble
        Notification notif2 = mService.getNotificationRecord(
                nr.getSbn().getKey()).getNotification();
        assertFalse(notif2.isBubbleNotification());
    }


    @Test
    public void testNotificationBubbles_shortcut_stopListeningWhenNotifRemoved()
            throws RemoteException {
        // Bubbles are allowed!
        setUpPrefsForBubbles(PKG, mUid, true /* global */, true /* app */, true /* channel */);

        ArgumentCaptor<LauncherApps.Callback> launcherAppsCallback =
                ArgumentCaptor.forClass(LauncherApps.Callback.class);

        // Messaging notification with shortcut info
        Notification.BubbleMetadata metadata = new Notification.BubbleMetadata.Builder(
                "someshortcutId").build();
        Notification.Builder nb = getMessageStyleNotifBuilder(false /* addDefaultMetadata */,
                null /* groupKey */, false /* isSummary */);
        nb.setBubbleMetadata(metadata);
        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 1,
                "tag", mUid, 0, nb.build(), new UserHandle(mUid), null, 0);
        NotificationRecord nr = new NotificationRecord(mContext, sbn, mTestNotificationChannel);

        // Pretend the shortcut exists
        List<ShortcutInfo> shortcutInfos = new ArrayList<>();
        ShortcutInfo info = mock(ShortcutInfo.class);
        when(info.isLongLived()).thenReturn(true);
        shortcutInfos.add(info);
        when(mLauncherApps.getShortcuts(any(), any())).thenReturn(shortcutInfos);

        // Test: Send the bubble notification
        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();

        // Verify:

        // Make sure we register the callback for shortcut changes
        verify(mLauncherApps, times(1)).registerCallback(launcherAppsCallback.capture(), any());

        // yes allowed, yes messaging w/shortcut, yes bubble
        Notification notif = mService.getNotificationRecord(nr.getSbn().getKey()).getNotification();
        assertTrue(notif.isBubbleNotification());

        // Test: Remove the notification
        mBinderService.cancelNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getUserId());
        waitForIdle();

        // Verify:

        // Make sure callback is unregistered
        verify(mLauncherApps, times(1)).unregisterCallback(launcherAppsCallback.getValue());
    }

    @Test
    public void testNotificationBubbles_bubbleChildrenStay_whenGroupSummaryDismissed()
            throws Exception {
        // Bubbles are allowed!
        setUpPrefsForBubbles(PKG, mUid, true /* global */, true /* app */, true /* channel */);

        NotificationRecord nrSummary = addGroupWithBubblesAndValidateAdded(
                true /* summaryAutoCancel */);

        // Dismiss summary
        final NotificationVisibility nv = NotificationVisibility.obtain(nrSummary.getKey(), 1, 2,
                true);
        mService.mNotificationDelegate.onNotificationClear(mUid, 0, PKG,
                nrSummary.getSbn().getTag(),
                nrSummary.getSbn().getId(), nrSummary.getUserId(), nrSummary.getKey(),
                NotificationStats.DISMISSAL_SHADE,
                NotificationStats.DISMISS_SENTIMENT_NEUTRAL, nv);
        waitForIdle();

        // The bubble should still exist
        StatusBarNotification[] notifsAfter = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifsAfter.length);
    }

    @Test
    public void testNotificationBubbles_bubbleChildrenStay_whenGroupSummaryClicked()
            throws Exception {
        // Bubbles are allowed!
        setUpPrefsForBubbles(PKG, mUid, true /* global */, true /* app */, true /* channel */);

        NotificationRecord nrSummary = addGroupWithBubblesAndValidateAdded(
                true /* summaryAutoCancel */);

        // Click summary
        final NotificationVisibility nv = NotificationVisibility.obtain(nrSummary.getKey(), 1, 2,
                true);
        mService.mNotificationDelegate.onNotificationClick(mUid, Binder.getCallingPid(),
                nrSummary.getKey(), nv);
        waitForIdle();

        // The bubble should still exist
        StatusBarNotification[] notifsAfter = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifsAfter.length);

        // Check we got the click log and associated dismissal logs
        assertEquals(6, mNotificationRecordLogger.numCalls());
        // Skip the notification-creation logs
        assertEquals(NotificationRecordLogger.NotificationEvent.NOTIFICATION_CLICKED,
                mNotificationRecordLogger.event(3));
        assertEquals(NotificationRecordLogger.NotificationCancelledEvent.NOTIFICATION_CANCEL_CLICK,
                mNotificationRecordLogger.event(4));
        assertEquals(NotificationRecordLogger.NotificationCancelledEvent
                        .NOTIFICATION_CANCEL_GROUP_SUMMARY_CANCELED,
                mNotificationRecordLogger.event(5));
    }

    @Test
    public void testNotificationBubbles_bubbleStays_whenClicked()
            throws Exception {
        // GIVEN a notification that has the auto cancels flag (cancel on click) and is a bubble
        setUpPrefsForBubbles(PKG, mUid, true /* global */, true /* app */, true /* channel */);
        final NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel);
        nr.getSbn().getNotification().flags |= FLAG_BUBBLE | FLAG_AUTO_CANCEL;
        mService.addNotification(nr);

        // WHEN we click the notification
        final NotificationVisibility nv = NotificationVisibility.obtain(nr.getKey(), 1, 2, true);
        mService.mNotificationDelegate.onNotificationClick(mUid, Binder.getCallingPid(),
                nr.getKey(), nv);
        waitForIdle();

        // THEN the bubble should still exist
        StatusBarNotification[] notifsAfter = mBinderService.getActiveNotifications(PKG);
        assertEquals(1, notifsAfter.length);

        // Check we got the click log
        assertEquals(1, mNotificationRecordLogger.numCalls());
        assertEquals(NotificationRecordLogger.NotificationEvent.NOTIFICATION_CLICKED,
                mNotificationRecordLogger.event(0));
    }

    @Test
    public void testLoadDefaultApprovedServices_emptyResources() {
        TestableResources tr = mContext.getOrCreateTestableResources();
        tr.addOverride(com.android.internal.R.string.config_defaultListenerAccessPackages, "");
        tr.addOverride(com.android.internal.R.string.config_defaultDndAccessPackages, "");
        tr.addOverride(com.android.internal.R.string.config_defaultAssistantAccessComponent, "");
        setDefaultAssistantInDeviceConfig("");

        mService.loadDefaultApprovedServices(USER_SYSTEM);

        verify(mListeners, never()).addDefaultComponentOrPackage(anyString());
        verify(mConditionProviders, never()).addDefaultComponentOrPackage(anyString());
        verify(mAssistants, never()).addDefaultComponentOrPackage(anyString());
    }

    @Test
    public void testLoadDefaultApprovedServices_dnd() {
        TestableResources tr = mContext.getOrCreateTestableResources();
        tr.addOverride(com.android.internal.R.string.config_defaultDndAccessPackages, "test");
        when(mListeners.queryPackageForServices(anyString(), anyInt(), anyInt()))
                .thenReturn(new ArraySet<>());

        mService.loadDefaultApprovedServices(USER_SYSTEM);

        verify(mConditionProviders, times(1)).addDefaultComponentOrPackage("test");
    }

    // TODO: add tests for the rest of the non-empty cases

    @Test
    public void testOnUnlockUser() {
        UserInfo ui = new UserInfo();
        ui.id = 10;
        mService.onUnlockUser(ui);
        waitForIdle();

        verify(mHistoryManager, timeout(MAX_POST_DELAY).times(1)).onUserUnlocked(ui.id);
    }

    @Test
    public void testOnStopUser() {
        UserInfo ui = new UserInfo();
        ui.id = 10;
        mService.onStopUser(ui);
        waitForIdle();

        verify(mHistoryManager, timeout(MAX_POST_DELAY).times(1)).onUserStopped(ui.id);
    }

    @Test
    public void testOnBootPhase() {
        mService.onBootPhase(SystemService.PHASE_ACTIVITY_MANAGER_READY);

        verify(mHistoryManager, never()).onBootPhaseAppsCanStart();

        mService.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        verify(mHistoryManager, times(1)).onBootPhaseAppsCanStart();
    }

    @Test
    public void testHandleOnPackageChanged() {
        String[] pkgs = new String[] {PKG, PKG_N_MR1};
        int[] uids = new int[] {mUid, UserHandle.PER_USER_RANGE + 1};

        mService.handleOnPackageChanged(false, USER_SYSTEM, pkgs, uids);

        verify(mHistoryManager, never()).onPackageRemoved(anyInt(), anyString());

        mService.handleOnPackageChanged(true, USER_SYSTEM, pkgs, uids);

        verify(mHistoryManager, times(1)).onPackageRemoved(UserHandle.getUserId(uids[0]), pkgs[0]);
        verify(mHistoryManager, times(1)).onPackageRemoved(UserHandle.getUserId(uids[1]), pkgs[1]);
    }

    @Test
    public void testNotificationHistory_addNoisyNotification() throws Exception {
        NotificationRecord nr = generateNotificationRecord(mTestNotificationChannel,
                null /* tvExtender */);
        mBinderService.enqueueNotificationWithTag(PKG, PKG, nr.getSbn().getTag(),
                nr.getSbn().getId(), nr.getSbn().getNotification(), nr.getSbn().getUserId());
        waitForIdle();

        verify(mHistoryManager, times(1)).addNotification(any());
    }

    @Test
    public void createConversationNotificationChannel() throws Exception {
        NotificationChannel original = new NotificationChannel("a", "a", IMPORTANCE_HIGH);
        original.setAllowBubbles(!original.canBubble());
        original.setShowBadge(!original.canShowBadge());

        Parcel parcel = Parcel.obtain();
        original.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        NotificationChannel orig = NotificationChannel.CREATOR.createFromParcel(parcel);
        assertEquals(original, orig);
        assertFalse(TextUtils.isEmpty(orig.getName()));

        mBinderService.createNotificationChannels(PKG, new ParceledListSlice(Arrays.asList(
                orig)));

        mBinderService.createConversationNotificationChannelForPackage(
                PKG, mUid, "key", orig, "friend");

        NotificationChannel friendChannel = mBinderService.getConversationNotificationChannel(
                PKG, 0, PKG, original.getId(), false, "friend");

        assertEquals(original.getName(), friendChannel.getName());
        assertEquals(original.getId(), friendChannel.getParentChannelId());
        assertEquals("friend", friendChannel.getConversationId());
        assertEquals(null, original.getConversationId());
        assertEquals(original.canShowBadge(), friendChannel.canShowBadge());
        assertEquals(original.canBubble(), friendChannel.canBubble());
        assertFalse(original.getId().equals(friendChannel.getId()));
        assertNotNull(friendChannel.getId());
    }

    @Test
    public void deleteConversationNotificationChannels() throws Exception {
        NotificationChannel messagesParent =
                new NotificationChannel("messages", "messages", IMPORTANCE_HIGH);
        Parcel msgParcel = Parcel.obtain();
        messagesParent.writeToParcel(msgParcel, 0);
        msgParcel.setDataPosition(0);

        NotificationChannel callsParent =
                new NotificationChannel("calls", "calls", IMPORTANCE_HIGH);
        Parcel callParcel = Parcel.obtain();
        callsParent.writeToParcel(callParcel, 0);
        callParcel.setDataPosition(0);

        mBinderService.createNotificationChannels(PKG, new ParceledListSlice(Arrays.asList(
                messagesParent, callsParent)));

        String conversationId = "friend";

        mBinderService.createConversationNotificationChannelForPackage(
                PKG, mUid, "key", NotificationChannel.CREATOR.createFromParcel(msgParcel),
                conversationId);
        mBinderService.createConversationNotificationChannelForPackage(
                PKG, mUid, "key", NotificationChannel.CREATOR.createFromParcel(callParcel),
                conversationId);

        NotificationChannel messagesChild = mBinderService.getConversationNotificationChannel(
                PKG, 0, PKG, messagesParent.getId(), false, conversationId);
        NotificationChannel callsChild = mBinderService.getConversationNotificationChannel(
                PKG, 0, PKG, callsParent.getId(), false, conversationId);

        assertEquals(messagesParent.getId(), messagesChild.getParentChannelId());
        assertEquals(conversationId, messagesChild.getConversationId());

        assertEquals(callsParent.getId(), callsChild.getParentChannelId());
        assertEquals(conversationId, callsChild.getConversationId());

        mBinderService.deleteConversationNotificationChannels(PKG, mUid, conversationId);

        assertNull(mBinderService.getConversationNotificationChannel(
                PKG, 0, PKG, messagesParent.getId(), false, conversationId));
        assertNull(mBinderService.getConversationNotificationChannel(
                PKG, 0, PKG, callsParent.getId(), false, conversationId));
    }

    @Test
    public void testCorrectCategory_systemOn_appCannotTurnOff() {
        int requested = 0;
        int system = PRIORITY_CATEGORY_CALLS | PRIORITY_CATEGORY_CONVERSATIONS;

        int actual = mService.correctCategory(requested, PRIORITY_CATEGORY_CONVERSATIONS,
                system);

        assertEquals(PRIORITY_CATEGORY_CONVERSATIONS, actual);
    }

    @Test
    public void testCorrectCategory_systemOff_appTurnOff_noChanges() {
        int requested = PRIORITY_CATEGORY_CALLS;
        int system = PRIORITY_CATEGORY_CALLS;

        int actual = mService.correctCategory(requested, PRIORITY_CATEGORY_CONVERSATIONS,
                system);

        assertEquals(PRIORITY_CATEGORY_CALLS, actual);
    }

    @Test
    public void testCorrectCategory_systemOn_appTurnOn_noChanges() {
        int requested = PRIORITY_CATEGORY_CALLS | PRIORITY_CATEGORY_CONVERSATIONS;
        int system = PRIORITY_CATEGORY_CALLS | PRIORITY_CATEGORY_CONVERSATIONS;

        int actual = mService.correctCategory(requested, PRIORITY_CATEGORY_CONVERSATIONS,
                system);

        assertEquals(PRIORITY_CATEGORY_CALLS | PRIORITY_CATEGORY_CONVERSATIONS, actual);
    }

    @Test
    public void testCorrectCategory_systemOff_appCannotTurnOn() {
        int requested = PRIORITY_CATEGORY_CALLS | PRIORITY_CATEGORY_CONVERSATIONS;
        int system = PRIORITY_CATEGORY_CALLS;

        int actual = mService.correctCategory(requested, PRIORITY_CATEGORY_CONVERSATIONS,
                system);

        assertEquals(PRIORITY_CATEGORY_CALLS, actual);
    }

    @Test
    public void testGetConversationsForPackage_hasShortcut() throws Exception {
        mService.setPreferencesHelper(mPreferencesHelper);
        ArrayList<ConversationChannelWrapper> convos = new ArrayList<>();
        ConversationChannelWrapper convo1 = new ConversationChannelWrapper();
        NotificationChannel channel1 = new NotificationChannel("a", "a", 1);
        channel1.setConversationId("parent1", "convo 1");
        convo1.setNotificationChannel(channel1);
        convos.add(convo1);

        ConversationChannelWrapper convo2 = new ConversationChannelWrapper();
        NotificationChannel channel2 = new NotificationChannel("b", "b", 1);
        channel2.setConversationId("parent1", "convo 2");
        convo2.setNotificationChannel(channel2);
        convos.add(convo2);
        when(mPreferencesHelper.getConversations(anyString(), anyInt())).thenReturn(convos);

        ShortcutInfo si = mock(ShortcutInfo.class);
        when(si.getShortLabel()).thenReturn("Hello");
        when(si.isLongLived()).thenReturn(true);
        when(mLauncherApps.getShortcuts(any(), any())).thenReturn(Arrays.asList(si));

        List<ConversationChannelWrapper> conversations =
                mBinderService.getConversationsForPackage(PKG_P, mUid).getList();
        assertEquals(si, conversations.get(0).getShortcutInfo());
        assertEquals(si, conversations.get(1).getShortcutInfo());
    }

    @Test
    public void testGetConversationsForPackage_shortcut_notLongLived() throws Exception {
        mService.setPreferencesHelper(mPreferencesHelper);
        ArrayList<ConversationChannelWrapper> convos = new ArrayList<>();
        ConversationChannelWrapper convo1 = new ConversationChannelWrapper();
        NotificationChannel channel1 = new NotificationChannel("a", "a", 1);
        channel1.setConversationId("parent1", "convo 1");
        convo1.setNotificationChannel(channel1);
        convos.add(convo1);

        ConversationChannelWrapper convo2 = new ConversationChannelWrapper();
        NotificationChannel channel2 = new NotificationChannel("b", "b", 1);
        channel2.setConversationId("parent1", "convo 2");
        convo2.setNotificationChannel(channel2);
        convos.add(convo2);
        when(mPreferencesHelper.getConversations(anyString(), anyInt())).thenReturn(convos);

        ShortcutInfo si = mock(ShortcutInfo.class);
        when(si.getShortLabel()).thenReturn("Hello");
        when(si.isLongLived()).thenReturn(false);
        when(mLauncherApps.getShortcuts(any(), any())).thenReturn(Arrays.asList(si));

        List<ConversationChannelWrapper> conversations =
                mBinderService.getConversationsForPackage(PKG_P, mUid).getList();
        assertNull(conversations.get(0).getShortcutInfo());
        assertNull(conversations.get(1).getShortcutInfo());
    }

    @Test
    public void testGetConversationsForPackage_doesNotHaveShortcut() throws Exception {
        mService.setPreferencesHelper(mPreferencesHelper);
        ArrayList<ConversationChannelWrapper> convos = new ArrayList<>();
        ConversationChannelWrapper convo1 = new ConversationChannelWrapper();
        NotificationChannel channel1 = new NotificationChannel("a", "a", 1);
        channel1.setConversationId("parent1", "convo 1");
        convo1.setNotificationChannel(channel1);
        convos.add(convo1);

        ConversationChannelWrapper convo2 = new ConversationChannelWrapper();
        NotificationChannel channel2 = new NotificationChannel("b", "b", 1);
        channel2.setConversationId("parent1", "convo 2");
        convo2.setNotificationChannel(channel2);
        convos.add(convo2);
        when(mPreferencesHelper.getConversations(anyString(), anyInt())).thenReturn(convos);

        List<ConversationChannelWrapper> conversations =
                mBinderService.getConversationsForPackage(PKG_P, mUid).getList();
        assertNull(conversations.get(0).getShortcutInfo());
        assertNull(conversations.get(1).getShortcutInfo());
    }
}
