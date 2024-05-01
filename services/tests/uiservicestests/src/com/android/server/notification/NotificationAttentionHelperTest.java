/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://`www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.notification;

import static android.app.Notification.FLAG_BUBBLE;
import static android.app.Notification.GROUP_ALERT_ALL;
import static android.app.Notification.GROUP_ALERT_CHILDREN;
import static android.app.Notification.GROUP_ALERT_SUMMARY;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS;
import static android.media.AudioAttributes.USAGE_NOTIFICATION;
import static android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.UserInfo;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Pair;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManager;
import android.view.accessibility.IAccessibilityManagerClient;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.config.sysui.SystemUiSystemPropertiesFlags.NotificationFlags;
import com.android.internal.config.sysui.TestableFlagResolver;
import com.android.internal.logging.InstanceIdSequence;
import com.android.internal.logging.InstanceIdSequenceFake;
import com.android.internal.util.IntPair;
import com.android.server.UiServiceTestCase;
import com.android.server.lights.LightsManager;
import com.android.server.lights.LogicalLight;
import com.android.server.pm.PackageManagerService;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.verification.VerificationMode;

import java.util.List;
import java.util.Objects;
import java.util.Set;

@SmallTest
@RunWith(AndroidJUnit4.class)
@SuppressLint("GuardedBy")
public class NotificationAttentionHelperTest extends UiServiceTestCase {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock AudioManager mAudioManager;
    @Mock Vibrator mVibrator;
    @Mock android.media.IRingtonePlayer mRingtonePlayer;
    @Mock LogicalLight mLight;
    @Mock
    NotificationManagerService.WorkerHandler mHandler;
    @Mock
    NotificationUsageStats mUsageStats;
    @Mock
    IAccessibilityManager mAccessibilityService;
    @Mock
    KeyguardManager mKeyguardManager;
    @Mock
    private UserManager mUserManager;
    @Mock
    private PackageManager mPackageManager;
    NotificationRecordLoggerFake mNotificationRecordLogger = new NotificationRecordLoggerFake();
    private InstanceIdSequence mNotificationInstanceIdSequence = new InstanceIdSequenceFake(
        1 << 30);

    private NotificationManagerService mService;
    private String mPkg = "com.android.server.notification";
    private int mId = 1001;
    private int mOtherId = 1002;
    private String mTag = null;
    private int mUid = 1000;
    private int mPid = 2000;
    private android.os.UserHandle mUser = UserHandle.of(ActivityManager.getCurrentUser());
    private NotificationChannel mChannel;

    private NotificationAttentionHelper mAttentionHelper;
    private TestableFlagResolver mTestFlagResolver = new TestableFlagResolver();
    private AccessibilityManager mAccessibilityManager;
    private static final NotificationAttentionHelper.Signals DEFAULT_SIGNALS =
        new NotificationAttentionHelper.Signals(false, 0);
    private static final NotificationAttentionHelper.Signals WORK_PROFILE_SIGNALS =
            new NotificationAttentionHelper.Signals(true, 0);

    private VibrateRepeatMatcher mVibrateOnceMatcher = new VibrateRepeatMatcher(-1);
    private VibrateRepeatMatcher mVibrateLoopMatcher = new VibrateRepeatMatcher(0);

    private static final long[] CUSTOM_VIBRATION = new long[] {
        300, 400, 300, 400, 300, 400, 300, 400, 300, 400, 300, 400,
        300, 400, 300, 400, 300, 400, 300, 400, 300, 400, 300, 400,
        300, 400, 300, 400, 300, 400, 300, 400, 300, 400, 300, 400 };
    private static final Uri CUSTOM_SOUND = Settings.System.DEFAULT_ALARM_ALERT_URI;
    private static final AudioAttributes CUSTOM_ATTRIBUTES = new AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .build();
    private static final int CUSTOM_LIGHT_COLOR = Color.BLACK;
    private static final int CUSTOM_LIGHT_ON = 10000;
    private static final int CUSTOM_LIGHT_OFF = 10000;
    private static final int MAX_VIBRATION_DELAY = 1000;
    private static final float DEFAULT_VOLUME = 1.0f;
    private BroadcastReceiver mAvalancheBroadcastReceiver;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        getContext().addMockSystemService(Vibrator.class, mVibrator);
        getContext().addMockSystemService(PackageManager.class, mPackageManager);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(false);

        when(mAudioManager.isAudioFocusExclusive()).thenReturn(false);
        when(mAudioManager.getRingtonePlayer()).thenReturn(mRingtonePlayer);
        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(10);
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        when(mAudioManager.getFocusRampTimeMs(anyInt(), any(AudioAttributes.class))).thenReturn(50);
        when(mAudioManager.shouldNotificationSoundPlay(any(AudioAttributes.class)))
                .thenReturn(true);
        when(mUsageStats.isAlertRateLimited(any())).thenReturn(false);
        when(mVibrator.hasFrequencyControl()).thenReturn(false);
        when(mKeyguardManager.isDeviceLocked(anyInt())).thenReturn(false);

        long serviceReturnValue = IntPair.of(
            AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED,
            AccessibilityEvent.TYPES_ALL_MASK);
        when(mAccessibilityService.addClient(any(), anyInt())).thenReturn(serviceReturnValue);
        mAccessibilityManager =
            new AccessibilityManager(getContext(), Handler.getMain(), mAccessibilityService, 0,
                true);
        verify(mAccessibilityService).addClient(any(IAccessibilityManagerClient.class), anyInt());
        assertTrue(mAccessibilityManager.isEnabled());

        // TODO (b/291907312): remove feature flag
        // Disable feature flags by default. Tests should enable as needed.
        mSetFlagsRule.disableFlags(Flags.FLAG_POLITE_NOTIFICATIONS,
                Flags.FLAG_CROSS_APP_POLITE_NOTIFICATIONS,
                Flags.FLAG_VIBRATE_WHILE_UNLOCKED,
                Flags.FLAG_POLITE_NOTIFICATIONS_ATTN_UPDATE);

        mService = spy(new NotificationManagerService(getContext(), mNotificationRecordLogger,
            mNotificationInstanceIdSequence));

        initAttentionHelper(mTestFlagResolver);

        mChannel = new NotificationChannel("test", "test", IMPORTANCE_HIGH);
    }

    private void initAttentionHelper(TestableFlagResolver flagResolver) {
        mAttentionHelper = new NotificationAttentionHelper(getContext(), mock(LightsManager.class),
                mAccessibilityManager, mPackageManager, mUserManager, mUsageStats,
                mService.mNotificationManagerPrivate, mock(ZenModeHelper.class), flagResolver);
        mAttentionHelper.onSystemReady();
        mAttentionHelper.setVibratorHelper(spy(new VibratorHelper(getContext())));
        mAttentionHelper.setAudioManager(mAudioManager);
        mAttentionHelper.setSystemReady(true);
        mAttentionHelper.setLights(mLight);
        mAttentionHelper.setScreenOn(false);
        mAttentionHelper.setAccessibilityManager(mAccessibilityManager);
        mAttentionHelper.setKeyguardManager(mKeyguardManager);
        mAttentionHelper.setScreenOn(false);
        mAttentionHelper.setInCallStateOffHook(false);
        mAttentionHelper.mNotificationPulseEnabled = true;

        if (Flags.crossAppPoliteNotifications()) {
            // Capture BroadcastReceiver for avalanche triggers
            ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                    ArgumentCaptor.forClass(BroadcastReceiver.class);
            ArgumentCaptor<IntentFilter> intentFilterCaptor =
                    ArgumentCaptor.forClass(IntentFilter.class);
            verify(getContext(), atLeastOnce()).registerReceiverAsUser(
                    broadcastReceiverCaptor.capture(),
                    any(), intentFilterCaptor.capture(), any(), any());
            List<BroadcastReceiver> broadcastReceivers = broadcastReceiverCaptor.getAllValues();
            List<IntentFilter> intentFilters = intentFilterCaptor.getAllValues();

            assertThat(broadcastReceivers.size()).isAtLeast(1);
            assertThat(intentFilters.size()).isAtLeast(1);
            for (int i = 0; i < intentFilters.size(); i++) {
                final IntentFilter filter = intentFilters.get(i);
                if (filter.hasAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                    mAvalancheBroadcastReceiver = broadcastReceivers.get(i);
                }
            }
            assertThat(mAvalancheBroadcastReceiver).isNotNull();
        }
    }

    //
    // Convenience functions for creating notification records
    //

    private NotificationRecord getNoisyOtherNotification() {
        return getNotificationRecord(mOtherId, false /* insistent */, false /* once */,
            true /* noisy */, true /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getBeepyNotification() {
        return getNotificationRecord(mId, false /* insistent */, false /* once */,
            true /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getBeepyOtherNotification() {
        return getNotificationRecord(mOtherId, false /* insistent */, false /* once */,
            true /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getBeepyOnceNotification() {
        return getNotificationRecord(mId, false /* insistent */, true /* once */,
            true /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getQuietNotification() {
        return getNotificationRecord(mId, false /* insistent */, true /* once */,
            false /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getQuietOtherNotification() {
        return getNotificationRecord(mOtherId, false /* insistent */, false /* once */,
            false /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getQuietOnceNotification() {
        return getNotificationRecord(mId, false /* insistent */, true /* once */,
            false /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getInsistentBeepyNotification() {
        return getNotificationRecord(mId, true /* insistent */, false /* once */,
            true /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getInsistentBeepyOnceNotification() {
        return getNotificationRecord(mId, true /* insistent */, true /* once */,
            true /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getInsistentBeepyLeanbackNotification() {
        return getLeanbackNotificationRecord(mId, true /* insistent */, false /* once */,
            true /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getBuzzyNotification() {
        return getNotificationRecord(mId, false /* insistent */, false /* once */,
            false /* noisy */, true /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getBuzzyOtherNotification() {
        return getNotificationRecord(mOtherId, false /* insistent */, false /* once */,
            false /* noisy */, true /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getBuzzyOnceNotification() {
        return getNotificationRecord(mId, false /* insistent */, true /* once */,
            false /* noisy */, true /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getInsistentBuzzyNotification() {
        return getNotificationRecord(mId, true /* insistent */, false /* once */,
            false /* noisy */, true /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getBuzzyBeepyNotification() {
        return getNotificationRecord(mId, false /* insistent */, false /* once */,
            true /* noisy */, true /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getBuzzyBeepyNotification(UserHandle userHandle) {
        return getNotificationRecord(mId, false /* insistent */, false /* once */,
                true /* noisy */, true /* buzzy*/, false /* lights */, userHandle);
    }

    private NotificationRecord getLightsNotification() {
        return getNotificationRecord(mId, false /* insistent */, false /* once */,
            false /* noisy */, false /* buzzy*/, true /* lights */);
    }

    private NotificationRecord getLightsOnceNotification() {
        return getNotificationRecord(mId, false /* insistent */, true /* once */,
            false /* noisy */, false /* buzzy*/, true /* lights */);
    }

    private NotificationRecord getCallRecord(int id, NotificationChannel channel, boolean looping) {
        final Builder builder = new Builder(getContext())
            .setContentTitle("foo")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setPriority(Notification.PRIORITY_HIGH);
        Notification n = builder.build();
        if (looping) {
            n.flags |= Notification.FLAG_INSISTENT;
        }
        StatusBarNotification sbn = new StatusBarNotification(mPkg, mPkg, id, mTag, mUid,
            mPid, n, mUser, null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn, channel);
        mService.addNotification(r);

        return r;
    }

    private NotificationRecord getNotificationRecord(int id, boolean insistent, boolean once,
        boolean noisy, boolean buzzy, boolean lights) {
        return getNotificationRecord(id, insistent, once, noisy, buzzy, lights, buzzy, noisy,
            lights, null, Notification.GROUP_ALERT_ALL, false, mUser);
    }

    private NotificationRecord getNotificationRecord(int id, boolean insistent, boolean once,
            boolean noisy, boolean buzzy, boolean lights, UserHandle userHandle) {
        return getNotificationRecord(id, insistent, once, noisy, buzzy, lights, buzzy, noisy,
                lights, null, Notification.GROUP_ALERT_ALL, false, userHandle);
    }

    private NotificationRecord getLeanbackNotificationRecord(int id, boolean insistent,
        boolean once,
        boolean noisy, boolean buzzy, boolean lights) {
        return getNotificationRecord(id, insistent, once, noisy, buzzy, lights, true, true,
            true,
            null, Notification.GROUP_ALERT_ALL, true, mUser);
    }

    private NotificationRecord getBeepyNotificationRecord(String groupKey, int groupAlertBehavior) {
        return getNotificationRecord(mId, false, false, true, false, false, true, true, true,
            groupKey, groupAlertBehavior, false, mUser);
    }

    private NotificationRecord getLightsNotificationRecord(String groupKey,
        int groupAlertBehavior) {
        return getNotificationRecord(mId, false, false, false, false, true /*lights*/, true,
            true, true, groupKey, groupAlertBehavior, false, mUser);
    }

    private NotificationRecord getNotificationRecord(int id,
            boolean insistent, boolean once,
            boolean noisy, boolean buzzy, boolean lights, boolean defaultVibration,
            boolean defaultSound, boolean defaultLights, String groupKey, int groupAlertBehavior,
            boolean isLeanback, UserHandle userHandle) {
        return getNotificationRecord(id, insistent, once, noisy, buzzy, lights, defaultVibration,
                defaultSound, defaultLights, groupKey, groupAlertBehavior, isLeanback, false,
                userHandle, mPkg);
    }

    private NotificationRecord getNotificationRecord(int id,
            boolean insistent, boolean once,
            boolean noisy, boolean buzzy, boolean lights, boolean defaultVibration,
            boolean defaultSound, boolean defaultLights, String groupKey, int groupAlertBehavior,
            boolean isLeanback, UserHandle userHandle, String packageName) {
        return getNotificationRecord(id, insistent, once, noisy, buzzy, lights, defaultVibration,
                defaultSound, defaultLights, groupKey, groupAlertBehavior, isLeanback, false,
                userHandle, packageName);
    }

    private NotificationRecord getNotificationRecord(int id,
            boolean insistent, boolean once,
            boolean noisy, boolean buzzy, boolean lights, boolean defaultVibration,
            boolean defaultSound, boolean defaultLights, String groupKey, int groupAlertBehavior,
            boolean isLeanback, boolean isConversation, UserHandle userHandle, String packageName) {

        final Builder builder = new Builder(getContext())
            .setContentTitle("foo")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setPriority(Notification.PRIORITY_HIGH)
            .setOnlyAlertOnce(once);

        if (isConversation) {
            builder.setStyle(new Notification.MessagingStyle("test user"));
        }

        int defaults = 0;
        if (noisy) {
            if (defaultSound) {
                defaults |= Notification.DEFAULT_SOUND;
                mChannel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI,
                    Notification.AUDIO_ATTRIBUTES_DEFAULT);
            } else {
                builder.setSound(CUSTOM_SOUND);
                mChannel.setSound(CUSTOM_SOUND, CUSTOM_ATTRIBUTES);
            }
        } else {
            mChannel.setSound(null, null);
        }
        if (buzzy) {
            if (defaultVibration) {
                defaults |= Notification.DEFAULT_VIBRATE;
            } else {
                builder.setVibrate(CUSTOM_VIBRATION);
                mChannel.setVibrationPattern(CUSTOM_VIBRATION);
            }
            mChannel.enableVibration(true);
        } else {
            mChannel.setVibrationPattern(null);
            mChannel.enableVibration(false);
        }

        if (lights) {
            if (defaultLights) {
                defaults |= Notification.DEFAULT_LIGHTS;
            } else {
                builder.setLights(CUSTOM_LIGHT_COLOR, CUSTOM_LIGHT_ON, CUSTOM_LIGHT_OFF);
            }
            mChannel.enableLights(true);
        } else {
            mChannel.enableLights(false);
        }
        builder.setDefaults(defaults);

        builder.setGroup(groupKey);
        builder.setGroupAlertBehavior(groupAlertBehavior);

        Notification n = builder.build();
        if (insistent) {
            n.flags |= Notification.FLAG_INSISTENT;
        }

        Context context = spy(getContext());
        PackageManager packageManager = spy(context.getPackageManager());
        when(context.getPackageManager()).thenReturn(packageManager);
        when(packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
            .thenReturn(isLeanback);

        StatusBarNotification sbn = new StatusBarNotification(packageName, packageName, id, mTag,
                mUid, mPid, n, userHandle, null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(context, sbn, mChannel);
        mService.addNotification(r);
        return r;
    }

    private NotificationRecord getConversationNotificationRecord(int id,
            boolean insistent, boolean once,
            boolean noisy, boolean buzzy, boolean lights, boolean defaultVibration,
            boolean defaultSound, boolean defaultLights, String groupKey, int groupAlertBehavior,
            boolean isLeanback, UserHandle userHandle, String packageName, String shortcutId) {
        NotificationRecord r = getNotificationRecord(id, insistent, once, noisy, buzzy, lights,
                defaultVibration, defaultSound, defaultLights, groupKey, groupAlertBehavior,
                isLeanback, true, userHandle, packageName);
        ShortcutInfo.Builder sb = new ShortcutInfo.Builder(getContext());
        r.setShortcutInfo(sb.setId(shortcutId).build());
        return r;
    }

    //
    // Convenience functions for interacting with mocks
    //

    private void verifyNeverBeep() throws RemoteException {
        verify(mRingtonePlayer, never()).playAsync(any(), any(), anyBoolean(), any(), anyFloat());
    }

    private void verifyBeepUnlooped() throws RemoteException  {
        verify(mRingtonePlayer, times(1)).playAsync(any(), any(), eq(false), any(),
                eq(DEFAULT_VOLUME));
    }

    private void verifyBeepLooped() throws RemoteException  {
        verify(mRingtonePlayer, times(1)).playAsync(any(), any(), eq(true), any(),
                eq(DEFAULT_VOLUME));
    }

    private void verifyBeep(int times)  throws RemoteException  {
        verify(mRingtonePlayer, times(times)).playAsync(any(), any(), anyBoolean(), any(),
                eq(DEFAULT_VOLUME));
    }

    private void verifyBeepVolume(float volume)  throws RemoteException  {
        verify(mRingtonePlayer, times(1)).playAsync(any(), any(), anyBoolean(), any(), eq(volume));
    }

    private void verifyNeverStopAudio() throws RemoteException {
        verify(mRingtonePlayer, never()).stopAsync();
    }

    private void verifyStopAudio() throws RemoteException {
        verify(mRingtonePlayer, times(1)).stopAsync();
    }

    private void verifyNeverVibrate() {
        verify(mVibrator, never()).vibrate(anyInt(), anyString(), any(), anyString(),
            any(VibrationAttributes.class));
    }

    private void verifyVibrate() {
        verifyVibrate(/* times= */ 1);
    }

    private void verifyVibrate(int times) {
        verifyVibrate(mVibrateOnceMatcher, times(times));
    }

    private void verifyVibrateLooped() {
        verifyVibrate(mVibrateLoopMatcher, times(1));
    }

    private void verifyDelayedVibrateLooped() {
        verifyVibrate(mVibrateLoopMatcher, timeout(MAX_VIBRATION_DELAY).times(1));
    }

    private void verifyDelayedVibrate(VibrationEffect effect) {
        verifyVibrate(argument -> Objects.equals(effect, argument),
            timeout(MAX_VIBRATION_DELAY).times(1));
    }

    private void verifyDelayedNeverVibrate() {
        verify(mVibrator, after(MAX_VIBRATION_DELAY).never()).vibrate(anyInt(), anyString(), any(),
            anyString(), any(VibrationAttributes.class));
    }

    private void verifyVibrate(ArgumentMatcher<VibrationEffect> effectMatcher,
        VerificationMode verification) {
        ArgumentCaptor<VibrationAttributes> captor =
            ArgumentCaptor.forClass(VibrationAttributes.class);
        verify(mVibrator, verification).vibrate(eq(Process.SYSTEM_UID),
            eq(PackageManagerService.PLATFORM_PACKAGE_NAME), argThat(effectMatcher),
            anyString(), captor.capture());
        assertEquals(0, (captor.getValue().getFlags()
            & VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY));
    }

    private void verifyStopVibrate() {
        int alarmClassUsageFilter =
            VibrationAttributes.USAGE_CLASS_ALARM | ~VibrationAttributes.USAGE_CLASS_MASK;
        verify(mVibrator, times(1)).cancel(eq(alarmClassUsageFilter));
    }

    private void verifyNeverStopVibrate() {
        verify(mVibrator, never()).cancel();
        verify(mVibrator, never()).cancel(anyInt());
    }

    private void verifyNeverLights() {
        verify(mLight, never()).setFlashing(anyInt(), anyInt(), anyInt(), anyInt());
    }

    private void verifyLights() {
        verify(mLight, times(1)).setFlashing(anyInt(), anyInt(), anyInt(), anyInt());
    }

    //
    // Tests
    //

    @Test
    public void testLights() throws Exception {
        NotificationRecord r = getLightsNotification();
        r.setSystemImportance(NotificationManager.IMPORTANCE_DEFAULT);

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyLights();
        assertTrue(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testBeep() throws Exception {
        NotificationRecord r = getBeepyNotification();

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyBeepUnlooped();
        verifyNeverVibrate();
        verify(mAccessibilityService, times(1)).sendAccessibilityEvent(any(), anyInt());
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testLockedPrivateA11yRedaction() throws Exception {
        NotificationRecord r = getBeepyNotification();
        r.setPackageVisibilityOverride(NotificationManager.VISIBILITY_NO_OVERRIDE);
        r.getNotification().visibility = Notification.VISIBILITY_PRIVATE;
        when(mKeyguardManager.isDeviceLocked(anyInt())).thenReturn(true);
        AccessibilityManager accessibilityManager = Mockito.mock(AccessibilityManager.class);
        when(accessibilityManager.isEnabled()).thenReturn(true);
        mAttentionHelper.setAccessibilityManager(accessibilityManager);

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        ArgumentCaptor<AccessibilityEvent> eventCaptor =
            ArgumentCaptor.forClass(AccessibilityEvent.class);

        verify(accessibilityManager, times(1))
            .sendAccessibilityEvent(eventCaptor.capture());

        AccessibilityEvent event = eventCaptor.getValue();
        assertEquals(r.getNotification().publicVersion, event.getParcelableData());
    }

    @Test
    public void testLockedOverridePrivateA11yRedaction() throws Exception {
        NotificationRecord r = getBeepyNotification();
        r.setPackageVisibilityOverride(Notification.VISIBILITY_PRIVATE);
        r.getNotification().visibility = Notification.VISIBILITY_PUBLIC;
        when(mKeyguardManager.isDeviceLocked(anyInt())).thenReturn(true);
        AccessibilityManager accessibilityManager = Mockito.mock(AccessibilityManager.class);
        when(accessibilityManager.isEnabled()).thenReturn(true);
        mAttentionHelper.setAccessibilityManager(accessibilityManager);

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        ArgumentCaptor<AccessibilityEvent> eventCaptor =
            ArgumentCaptor.forClass(AccessibilityEvent.class);

        verify(accessibilityManager, times(1))
            .sendAccessibilityEvent(eventCaptor.capture());

        AccessibilityEvent event = eventCaptor.getValue();
        assertEquals(r.getNotification().publicVersion, event.getParcelableData());
    }

    @Test
    public void testLockedPublicA11yNoRedaction() throws Exception {
        NotificationRecord r = getBeepyNotification();
        r.setPackageVisibilityOverride(NotificationManager.VISIBILITY_NO_OVERRIDE);
        r.getNotification().visibility = Notification.VISIBILITY_PUBLIC;
        when(mKeyguardManager.isDeviceLocked(anyInt())).thenReturn(true);
        AccessibilityManager accessibilityManager = Mockito.mock(AccessibilityManager.class);
        when(accessibilityManager.isEnabled()).thenReturn(true);
        mAttentionHelper.setAccessibilityManager(accessibilityManager);

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        ArgumentCaptor<AccessibilityEvent> eventCaptor =
            ArgumentCaptor.forClass(AccessibilityEvent.class);

        verify(accessibilityManager, times(1))
            .sendAccessibilityEvent(eventCaptor.capture());

        AccessibilityEvent event = eventCaptor.getValue();
        assertEquals(r.getNotification(), event.getParcelableData());
    }

    @Test
    public void testUnlockedPrivateA11yNoRedaction() throws Exception {
        NotificationRecord r = getBeepyNotification();
        r.setPackageVisibilityOverride(NotificationManager.VISIBILITY_NO_OVERRIDE);
        r.getNotification().visibility = Notification.VISIBILITY_PRIVATE;
        when(mKeyguardManager.isDeviceLocked(anyInt())).thenReturn(false);
        AccessibilityManager accessibilityManager = Mockito.mock(AccessibilityManager.class);
        when(accessibilityManager.isEnabled()).thenReturn(true);
        mAttentionHelper.setAccessibilityManager(accessibilityManager);

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        ArgumentCaptor<AccessibilityEvent> eventCaptor =
            ArgumentCaptor.forClass(AccessibilityEvent.class);

        verify(accessibilityManager, times(1))
            .sendAccessibilityEvent(eventCaptor.capture());

        AccessibilityEvent event = eventCaptor.getValue();
        assertEquals(r.getNotification(), event.getParcelableData());
    }

    @Test
    public void testBeepInsistently() throws Exception {
        NotificationRecord r = getInsistentBeepyNotification();

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyBeepLooped();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testNoLeanbackBeep() throws Exception {
        NotificationRecord r = getInsistentBeepyLeanbackNotification();

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyNeverBeep();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testNoBeepForAutomotiveIfEffectsDisabled() throws Exception {
        mAttentionHelper.setIsAutomotive(true);
        mAttentionHelper.setNotificationEffectsEnabledForAutomotive(false);

        NotificationRecord r = getBeepyNotification();
        r.setSystemImportance(NotificationManager.IMPORTANCE_HIGH);

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyNeverBeep();
        assertFalse(r.isInterruptive());
    }

    @Test
    public void testNoBeepForImportanceDefaultInAutomotiveIfEffectsEnabled() throws Exception {
        mAttentionHelper.setIsAutomotive(true);
        mAttentionHelper.setNotificationEffectsEnabledForAutomotive(true);

        NotificationRecord r = getBeepyNotification();
        r.setSystemImportance(NotificationManager.IMPORTANCE_DEFAULT);

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyNeverBeep();
        assertFalse(r.isInterruptive());
    }

    @Test
    public void testBeepForImportanceHighInAutomotiveIfEffectsEnabled() throws Exception {
        mAttentionHelper.setIsAutomotive(true);
        mAttentionHelper.setNotificationEffectsEnabledForAutomotive(true);

        NotificationRecord r = getBeepyNotification();
        r.setSystemImportance(NotificationManager.IMPORTANCE_HIGH);

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyBeepUnlooped();
        assertTrue(r.isInterruptive());
    }

    @Test
    public void testNoInterruptionForMin() throws Exception {
        NotificationRecord r = getBeepyNotification();
        r.setSystemImportance(NotificationManager.IMPORTANCE_MIN);

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyNeverBeep();
        verifyNeverVibrate();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testNoInterruptionForIntercepted() throws Exception {
        NotificationRecord r = getBeepyNotification();
        r.setIntercepted(true);

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyNeverBeep();
        verifyNeverVibrate();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testBeepTwice() throws Exception {
        NotificationRecord r = getBeepyNotification();

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        Mockito.reset(mRingtonePlayer);

        // update should beep
        r.isUpdate = true;
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyBeepUnlooped();
        verify(mAccessibilityService, times(2)).sendAccessibilityEvent(any(), anyInt());
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testHonorAlertOnlyOnceForBeep() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord s = getBeepyOnceNotification();
        s.isUpdate = true;

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        Mockito.reset(mRingtonePlayer);

        // update should not beep
        mAttentionHelper.buzzBeepBlinkLocked(s, DEFAULT_SIGNALS);
        verifyNeverBeep();
        verify(mAccessibilityService, times(1)).sendAccessibilityEvent(any(), anyInt());
    }

    @Test
    public void testNoisyUpdateDoesNotCancelAudio() throws Exception {
        NotificationRecord r = getBeepyNotification();

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        r.isUpdate = true;
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyNeverStopAudio();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testNoisyOnceUpdateDoesNotCancelAudio() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord s = getBeepyOnceNotification();
        s.isUpdate = true;

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        mAttentionHelper.buzzBeepBlinkLocked(s, DEFAULT_SIGNALS);

        verifyNeverStopAudio();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
        assertFalse(s.isInterruptive());
        assertEquals(-1, s.getLastAudiblyAlertedMs());
    }

    /**
     * Tests the case where the user re-posts a {@link Notification} with looping sound where
     * {@link Notification.Builder#setOnlyAlertOnce(true)} has been called.  This should silence
     * the sound associated with the notification.
     * @throws Exception
     */
    @Test
    public void testNoisyOnceUpdateDoesCancelAudio() throws Exception {
        NotificationRecord r = getInsistentBeepyNotification();
        NotificationRecord s = getInsistentBeepyOnceNotification();
        s.isUpdate = true;

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        mAttentionHelper.buzzBeepBlinkLocked(s, DEFAULT_SIGNALS);

        verifyStopAudio();
    }

    @Test
    public void testQuietUpdateDoesNotCancelAudioFromOther() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord s = getQuietNotification();
        s.isUpdate = true;
        NotificationRecord other = getNoisyOtherNotification();

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        mAttentionHelper.buzzBeepBlinkLocked(other, DEFAULT_SIGNALS); // this takes the audio stream
        Mockito.reset(mRingtonePlayer);

        // should not stop noise, since we no longer own it
        mAttentionHelper.buzzBeepBlinkLocked(s, DEFAULT_SIGNALS); // this no longer owns the stream
        verifyNeverStopAudio();
        assertTrue(other.isInterruptive());
        assertNotEquals(-1, other.getLastAudiblyAlertedMs());
    }

    @Test
    public void testQuietInterloperDoesNotCancelAudio() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord other = getQuietOtherNotification();

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        Mockito.reset(mRingtonePlayer);

        // should not stop noise, since it does not own it
        mAttentionHelper.buzzBeepBlinkLocked(other, DEFAULT_SIGNALS);
        verifyNeverStopAudio();
    }

    @Test
    public void testQuietUpdateCancelsAudio() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord s = getQuietNotification();
        s.isUpdate = true;

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
        Mockito.reset(mRingtonePlayer);

        // quiet update should stop making noise
        mAttentionHelper.buzzBeepBlinkLocked(s, DEFAULT_SIGNALS);
        verifyStopAudio();
        assertFalse(s.isInterruptive());
        assertEquals(-1, s.getLastAudiblyAlertedMs());
    }

    @Test
    public void testQuietOnceUpdateCancelsAudio() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord s = getQuietOnceNotification();
        s.isUpdate = true;

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
        Mockito.reset(mRingtonePlayer);

        // stop making noise - this is a weird corner case, but quiet should override once
        mAttentionHelper.buzzBeepBlinkLocked(s, DEFAULT_SIGNALS);
        verifyStopAudio();
        assertFalse(s.isInterruptive());
        assertEquals(-1, s.getLastAudiblyAlertedMs());
    }

    @Test
    public void testInCallNotification() throws Exception {
        NotificationRecord r = getBeepyNotification();

        mAttentionHelper = spy(mAttentionHelper);

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        Mockito.reset(mRingtonePlayer);

        mAttentionHelper.setInCallStateOffHook(true);
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verify(mAttentionHelper, times(1)).playInCallNotification();
        verifyNeverBeep(); // doesn't play normal beep
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testNoDemoteSoundToVibrateIfVibrateGiven() throws Exception {
        NotificationRecord r = getBuzzyBeepyNotification();
        assertTrue(r.getSound() != null);

        // the phone is quiet
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);
        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(0);
        when(mAudioManager.shouldNotificationSoundPlay(any(AudioAttributes.class)))
                .thenReturn(false);

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyDelayedVibrate(r.getVibration());
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testNoDemoteSoundToVibrateIfNonNotificationStream() throws Exception {
        NotificationRecord r = getBeepyNotification();
        assertTrue(r.getSound() != null);
        assertNull(r.getVibration());

        // the phone is quiet
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);
        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(1);
        // all streams at 1 means no muting from audio framework
        when(mAudioManager.shouldNotificationSoundPlay(any())).thenReturn(true);

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyNeverVibrate();
        verifyBeepUnlooped();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testDemoteSoundToVibrate() throws Exception {
        NotificationRecord r = getBeepyNotification();
        assertTrue(r.getSound() != null);
        assertNull(r.getVibration());

        // the phone is quiet
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);
        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(0);
        when(mAudioManager.shouldNotificationSoundPlay(any())).thenReturn(false);

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyDelayedVibrate(
            mAttentionHelper.getVibratorHelper().createFallbackVibration(
                /* insistent= */ false));
        verify(mRingtonePlayer, never()).playAsync(anyObject(), anyObject(), anyBoolean(),
            anyObject(), anyFloat());
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testDemoteInsistentSoundToVibrate() throws Exception {
        NotificationRecord r = getInsistentBeepyNotification();
        assertTrue(r.getSound() != null);
        assertNull(r.getVibration());

        // the phone is quiet
        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(0);
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);
        when(mAudioManager.shouldNotificationSoundPlay(any())).thenReturn(false);

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyDelayedVibrateLooped();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testVibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyNeverBeep();
        verifyVibrate();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testInsistentVibrate() {
        NotificationRecord r = getInsistentBuzzyNotification();

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyVibrateLooped();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testVibrateTwice() {
        NotificationRecord r = getBuzzyNotification();

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        Mockito.reset(mVibrator);

        // update should vibrate
        r.isUpdate = true;
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyVibrate();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testPostSilently() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        r.setPostSilently(true);

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyNeverBeep();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertSummarySilenceChild() throws Exception {
        NotificationRecord child = getBeepyNotificationRecord("a", GROUP_ALERT_SUMMARY);

        mAttentionHelper.buzzBeepBlinkLocked(child, DEFAULT_SIGNALS);

        verifyNeverBeep();
        assertFalse(child.isInterruptive());
        assertEquals(-1, child.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertSummaryNoSilenceSummary() throws Exception {
        NotificationRecord summary = getBeepyNotificationRecord("a", GROUP_ALERT_SUMMARY);
        summary.getNotification().flags |= Notification.FLAG_GROUP_SUMMARY;

        mAttentionHelper.buzzBeepBlinkLocked(summary, DEFAULT_SIGNALS);

        verifyBeepUnlooped();
        // summaries are never interruptive for notification counts
        assertFalse(summary.isInterruptive());
        assertNotEquals(-1, summary.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertSummaryNoSilenceNonGroupChild() throws Exception {
        NotificationRecord nonGroup = getBeepyNotificationRecord(null, GROUP_ALERT_SUMMARY);

        mAttentionHelper.buzzBeepBlinkLocked(nonGroup, DEFAULT_SIGNALS);

        verifyBeepUnlooped();
        assertTrue(nonGroup.isInterruptive());
        assertNotEquals(-1, nonGroup.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertChildSilenceSummary() throws Exception {
        NotificationRecord summary = getBeepyNotificationRecord("a", GROUP_ALERT_CHILDREN);
        summary.getNotification().flags |= Notification.FLAG_GROUP_SUMMARY;

        mAttentionHelper.buzzBeepBlinkLocked(summary, DEFAULT_SIGNALS);

        verifyNeverBeep();
        assertFalse(summary.isInterruptive());
        assertEquals(-1, summary.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertChildNoSilenceChild() throws Exception {
        NotificationRecord child = getBeepyNotificationRecord("a", GROUP_ALERT_CHILDREN);

        mAttentionHelper.buzzBeepBlinkLocked(child, DEFAULT_SIGNALS);

        verifyBeepUnlooped();
        assertTrue(child.isInterruptive());
        assertNotEquals(-1, child.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertChildNoSilenceNonGroupSummary() throws Exception {
        NotificationRecord nonGroup = getBeepyNotificationRecord(null, GROUP_ALERT_CHILDREN);

        mAttentionHelper.buzzBeepBlinkLocked(nonGroup, DEFAULT_SIGNALS);

        verifyBeepUnlooped();
        assertTrue(nonGroup.isInterruptive());
        assertNotEquals(-1, nonGroup.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertAllNoSilenceGroup() throws Exception {
        NotificationRecord group = getBeepyNotificationRecord("a", GROUP_ALERT_ALL);

        mAttentionHelper.buzzBeepBlinkLocked(group, DEFAULT_SIGNALS);

        verifyBeepUnlooped();
        assertTrue(group.isInterruptive());
        assertNotEquals(-1, group.getLastAudiblyAlertedMs());
    }

    @Test
    public void testHonorAlertOnlyOnceForBuzz() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord s = getBuzzyOnceNotification();
        s.isUpdate = true;

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        Mockito.reset(mVibrator);
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());

        // update should not beep
        mAttentionHelper.buzzBeepBlinkLocked(s, DEFAULT_SIGNALS);
        verifyNeverVibrate();
        assertFalse(s.isInterruptive());
        assertEquals(-1, s.getLastAudiblyAlertedMs());
    }

    @Test
    public void testNoisyUpdateDoesNotCancelVibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        r.isUpdate = true;
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyNeverStopVibrate();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testNoisyOnceUpdateDoesNotCancelVibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord s = getBuzzyOnceNotification();
        s.isUpdate = true;

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        mAttentionHelper.buzzBeepBlinkLocked(s, DEFAULT_SIGNALS);

        verifyNeverStopVibrate();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
        assertFalse(s.isInterruptive());
        assertEquals(-1, s.getLastAudiblyAlertedMs());
    }

    @Test
    public void testQuietUpdateDoesNotCancelVibrateFromOther() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord s = getQuietNotification();
        s.isUpdate = true;
        NotificationRecord other = getNoisyOtherNotification();

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        // this takes the vibrate stream
        mAttentionHelper.buzzBeepBlinkLocked(other, DEFAULT_SIGNALS);
        Mockito.reset(mVibrator);

        // should not stop vibrate, since we no longer own it
        mAttentionHelper.buzzBeepBlinkLocked(s, DEFAULT_SIGNALS); // this no longer owns the stream
        verifyNeverStopVibrate();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
        assertTrue(other.isInterruptive());
        assertNotEquals(-1, other.getLastAudiblyAlertedMs());
        assertFalse(s.isInterruptive());
        assertEquals(-1, s.getLastAudiblyAlertedMs());
    }

    @Test
    public void testQuietInterloperDoesNotCancelVibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord other = getQuietOtherNotification();

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        Mockito.reset(mVibrator);

        // should not stop noise, since it does not own it
        mAttentionHelper.buzzBeepBlinkLocked(other, DEFAULT_SIGNALS);
        verifyNeverStopVibrate();
        assertFalse(other.isInterruptive());
        assertEquals(-1, other.getLastAudiblyAlertedMs());
    }

    @Test
    public void testQuietUpdateCancelsVibrate() {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord s = getQuietNotification();
        s.isUpdate = true;

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyVibrate();

        // quiet update should stop making noise
        mAttentionHelper.buzzBeepBlinkLocked(s, DEFAULT_SIGNALS);
        verifyStopVibrate();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
        assertFalse(s.isInterruptive());
        assertEquals(-1, s.getLastAudiblyAlertedMs());
    }

    @Test
    public void testQuietOnceUpdateCancelVibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord s = getQuietOnceNotification();
        s.isUpdate = true;

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyVibrate();

        // stop making noise - this is a weird corner case, but quiet should override once
        mAttentionHelper.buzzBeepBlinkLocked(s, DEFAULT_SIGNALS);
        verifyStopVibrate();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
        assertFalse(s.isInterruptive());
        assertEquals(-1, s.getLastAudiblyAlertedMs());
    }

    @Test
    public void testQuietUpdateCancelsDemotedVibrate() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord s = getQuietNotification();

        // the phone is quiet
        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(0);
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);
        when(mAudioManager.shouldNotificationSoundPlay(any())).thenReturn(false);

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyDelayedVibrate(mAttentionHelper.getVibratorHelper().createFallbackVibration(false));

        // quiet update should stop making noise
        mAttentionHelper.buzzBeepBlinkLocked(s, DEFAULT_SIGNALS);
        verifyStopVibrate();
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
        assertFalse(s.isInterruptive());
        assertEquals(-1, s.getLastAudiblyAlertedMs());
    }

    @Test
    public void testEmptyUriSoundTreatedAsNoSound() throws Exception {
        NotificationChannel channel = new NotificationChannel("test", "test", IMPORTANCE_HIGH);
        channel.setSound(Uri.EMPTY, null);
        final Notification n = new Builder(getContext(), "test")
            .setSmallIcon(android.R.drawable.sym_def_app_icon).build();

        StatusBarNotification sbn = new StatusBarNotification(mPkg, mPkg, 0, mTag, mUid,
            mPid, n, mUser, null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn, channel);
        mService.addNotification(r);

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyNeverBeep();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testRepeatedSoundOverLimitMuted() throws Exception {
        when(mUsageStats.isAlertRateLimited(any())).thenReturn(true);

        NotificationRecord r = getBeepyNotification();

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyNeverBeep();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testPostingSilentNotificationDoesNotAffectRateLimiting() throws Exception {
        NotificationRecord r = getQuietNotification();
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verify(mUsageStats, never()).isAlertRateLimited(any());
    }

    @Test
    public void testPostingGroupSuppressedDoesNotAffectRateLimiting() throws Exception {
        NotificationRecord summary = getBeepyNotificationRecord("a", GROUP_ALERT_CHILDREN);
        summary.getNotification().flags |= Notification.FLAG_GROUP_SUMMARY;

        mAttentionHelper.buzzBeepBlinkLocked(summary, DEFAULT_SIGNALS);
        verify(mUsageStats, never()).isAlertRateLimited(any());
    }

    @Test
    public void testGroupSuppressionFailureDoesNotAffectRateLimiting() {
        NotificationRecord summary = getBeepyNotificationRecord("a", GROUP_ALERT_SUMMARY);
        summary.getNotification().flags |= Notification.FLAG_GROUP_SUMMARY;

        mAttentionHelper.buzzBeepBlinkLocked(summary, DEFAULT_SIGNALS);
        verify(mUsageStats, times(1)).isAlertRateLimited(any());
    }

    @Test
    public void testCrossUserSoundMuted() throws Exception {
        final Notification n = new Builder(getContext(), "test")
            .setSmallIcon(android.R.drawable.sym_def_app_icon).build();

        int userId = mUser.getIdentifier() + 1;
        StatusBarNotification sbn = new StatusBarNotification(mPkg, mPkg, 0, mTag, mUid,
            mPid, n, UserHandle.of(userId), null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn,
            new NotificationChannel("test", "test", IMPORTANCE_HIGH));

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyNeverBeep();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testA11yMinInitialPost() throws Exception {
        NotificationRecord r = getQuietNotification();
        r.setSystemImportance(IMPORTANCE_MIN);
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verify(mAccessibilityService, never()).sendAccessibilityEvent(any(), anyInt());
    }

    @Test
    public void testA11yQuietInitialPost() throws Exception {
        NotificationRecord r = getQuietNotification();
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verify(mAccessibilityService, times(1)).sendAccessibilityEvent(any(), anyInt());
    }

    @Test
    public void testA11yQuietUpdate() throws Exception {
        NotificationRecord r = getQuietNotification();
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        r.isUpdate = true;
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verify(mAccessibilityService, times(1)).sendAccessibilityEvent(any(), anyInt());
    }

    @Test
    public void testA11yCrossUserEventNotSent() throws Exception {
        final Notification n = new Builder(getContext(), "test")
            .setSmallIcon(android.R.drawable.sym_def_app_icon).build();
        int userId = mUser.getIdentifier() + 1;
        StatusBarNotification sbn = new StatusBarNotification(mPkg, mPkg, 0, mTag, mUid,
            mPid, n, UserHandle.of(userId), null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn,
            new NotificationChannel("test", "test", IMPORTANCE_HIGH));

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verify(mAccessibilityService, never()).sendAccessibilityEvent(any(), anyInt());
    }

    @Test
    public void testLightsScreenOn() {
        mAttentionHelper.setScreenOn(true);
        NotificationRecord r = getLightsNotification();
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyNeverLights();
        assertTrue(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testLightsInCall() {
        mAttentionHelper.setInCallStateOffHook(true);
        NotificationRecord r = getLightsNotification();
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyNeverLights();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testLightsSilentUpdate() {
        NotificationRecord r = getLightsOnceNotification();
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyLights();
        assertTrue(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());

        r = getLightsOnceNotification();
        r.isUpdate = true;
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        // checks that lights happened once, i.e. this new call didn't trigger them again
        verifyLights();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testLightsUnimportant() {
        NotificationRecord r = getLightsNotification();
        r.setSystemImportance(IMPORTANCE_LOW);
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyNeverLights();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testLightsNoLights() {
        NotificationRecord r = getQuietNotification();
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyNeverLights();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testLightsNoLightOnDevice() {
        mAttentionHelper.mHasLight = false;
        NotificationRecord r = getLightsNotification();
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyNeverLights();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testLightsLightsOffGlobally() {
        mAttentionHelper.mNotificationPulseEnabled = false;
        NotificationRecord r = getLightsNotification();
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyNeverLights();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testLightsDndIntercepted() {
        NotificationRecord r = getLightsNotification();
        r.setSuppressedVisualEffects(SUPPRESSED_EFFECT_LIGHTS);
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyNeverLights();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertSummaryNoLightsChild() {
        NotificationRecord child = getLightsNotificationRecord("a", GROUP_ALERT_SUMMARY);

        mAttentionHelper.buzzBeepBlinkLocked(child, DEFAULT_SIGNALS);

        verifyNeverLights();
        assertFalse(child.isInterruptive());
        assertEquals(-1, child.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertSummaryLightsSummary() {
        NotificationRecord summary = getLightsNotificationRecord("a", GROUP_ALERT_SUMMARY);
        summary.getNotification().flags |= Notification.FLAG_GROUP_SUMMARY;

        mAttentionHelper.buzzBeepBlinkLocked(summary, DEFAULT_SIGNALS);

        verifyLights();
        // summaries should never count for interruptiveness counts
        assertFalse(summary.isInterruptive());
        assertEquals(-1, summary.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertSummaryLightsNonGroupChild() {
        NotificationRecord nonGroup = getLightsNotificationRecord(null, GROUP_ALERT_SUMMARY);

        mAttentionHelper.buzzBeepBlinkLocked(nonGroup, DEFAULT_SIGNALS);

        verifyLights();
        assertTrue(nonGroup.isInterruptive());
        assertEquals(-1, nonGroup.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertChildNoLightsSummary() {
        NotificationRecord summary = getLightsNotificationRecord("a", GROUP_ALERT_CHILDREN);
        summary.getNotification().flags |= Notification.FLAG_GROUP_SUMMARY;

        mAttentionHelper.buzzBeepBlinkLocked(summary, DEFAULT_SIGNALS);

        verifyNeverLights();
        assertFalse(summary.isInterruptive());
        assertEquals(-1, summary.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertChildLightsChild() {
        NotificationRecord child = getLightsNotificationRecord("a", GROUP_ALERT_CHILDREN);

        mAttentionHelper.buzzBeepBlinkLocked(child, DEFAULT_SIGNALS);

        verifyLights();
        assertTrue(child.isInterruptive());
        assertEquals(-1, child.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertChildLightsNonGroupSummary() {
        NotificationRecord nonGroup = getLightsNotificationRecord(null, GROUP_ALERT_CHILDREN);

        mAttentionHelper.buzzBeepBlinkLocked(nonGroup, DEFAULT_SIGNALS);

        verifyLights();
        assertTrue(nonGroup.isInterruptive());
        assertEquals(-1, nonGroup.getLastAudiblyAlertedMs());
    }

    @Test
    public void testGroupAlertAllLightsGroup() {
        NotificationRecord group = getLightsNotificationRecord("a", GROUP_ALERT_ALL);

        mAttentionHelper.buzzBeepBlinkLocked(group, DEFAULT_SIGNALS);

        verifyLights();
        assertTrue(group.isInterruptive());
        assertEquals(-1, group.getLastAudiblyAlertedMs());
    }

    @Test
    public void testLightsCheckCurrentUser() {
        final Notification n = new Builder(getContext(), "test")
            .setSmallIcon(android.R.drawable.sym_def_app_icon).build();
        int userId = mUser.getIdentifier() + 10;
        StatusBarNotification sbn = new StatusBarNotification(mPkg, mPkg, 0, mTag, mUid,
            mPid, n, UserHandle.of(userId), null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn,
            new NotificationChannel("test", "test", IMPORTANCE_HIGH));

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyNeverLights();
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testListenerHintCall() throws Exception {
        NotificationChannel ringtoneChannel =
            new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(Settings.System.DEFAULT_RINGTONE_URI,
            new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        NotificationRecord r = getCallRecord(1, ringtoneChannel, true);

        mAttentionHelper.buzzBeepBlinkLocked(r, new NotificationAttentionHelper.Signals(false,
            NotificationListenerService.HINT_HOST_DISABLE_CALL_EFFECTS));

        verifyNeverBeep();
    }

    @Test
    public void testListenerHintCall_notificationSound() throws Exception {
        NotificationRecord r = getBeepyNotification();

        mAttentionHelper.buzzBeepBlinkLocked(r, new NotificationAttentionHelper.Signals(false,
            NotificationListenerService.HINT_HOST_DISABLE_CALL_EFFECTS));

        verifyBeepUnlooped();
    }

    @Test
    public void testListenerHintNotification() throws Exception {
        NotificationRecord r = getBeepyNotification();

        mAttentionHelper.buzzBeepBlinkLocked(r, new NotificationAttentionHelper.Signals(false,
            NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS));

        verifyNeverBeep();
    }

    @Test
    public void testListenerHintBoth() throws Exception {
        NotificationChannel ringtoneChannel =
            new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(Settings.System.DEFAULT_RINGTONE_URI,
            new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        NotificationRecord r = getCallRecord(1, ringtoneChannel, true);
        NotificationRecord s = getBeepyNotification();

        mAttentionHelper.buzzBeepBlinkLocked(r, new NotificationAttentionHelper.Signals(false,
            NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS
                | NotificationListenerService.HINT_HOST_DISABLE_CALL_EFFECTS));
        mAttentionHelper.buzzBeepBlinkLocked(s, new NotificationAttentionHelper.Signals(false,
            NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS
                | NotificationListenerService.HINT_HOST_DISABLE_CALL_EFFECTS));

        verifyNeverBeep();
    }

    @Test
    public void testListenerHintNotification_callSound() throws Exception {
        NotificationChannel ringtoneChannel =
            new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(Settings.System.DEFAULT_RINGTONE_URI,
            new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        NotificationRecord r = getCallRecord(1, ringtoneChannel, true);

        mAttentionHelper.buzzBeepBlinkLocked(r, new NotificationAttentionHelper.Signals(false,
            NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS));

        verifyBeepLooped();
    }

    @Test
    public void testCannotInterruptRingtoneInsistentBeep() throws Exception {
        NotificationChannel ringtoneChannel =
            new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(Settings.System.DEFAULT_RINGTONE_URI,
            new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        NotificationRecord ringtoneNotification = getCallRecord(1, ringtoneChannel, true);
        mService.addNotification(ringtoneNotification);

        mAttentionHelper.buzzBeepBlinkLocked(ringtoneNotification, DEFAULT_SIGNALS);
        verifyBeepLooped();

        NotificationRecord interrupter = getBeepyOtherNotification();
        assertTrue(mAttentionHelper.shouldMuteNotificationLocked(interrupter, DEFAULT_SIGNALS));
        mAttentionHelper.buzzBeepBlinkLocked(interrupter, DEFAULT_SIGNALS);

        verifyBeep(1);

        assertFalse(interrupter.isInterruptive());
        assertEquals(-1, interrupter.getLastAudiblyAlertedMs());
    }

    @Test
    public void testRingtoneInsistentBeep_canUpdate() throws Exception {
        NotificationChannel ringtoneChannel =
            new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(Uri.fromParts("a", "b", "c"),
            new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        ringtoneChannel.enableVibration(true);
        NotificationRecord ringtoneNotification = getCallRecord(1, ringtoneChannel, true);
        mService.addNotification(ringtoneNotification);
        assertFalse(mAttentionHelper.shouldMuteNotificationLocked(ringtoneNotification,
            DEFAULT_SIGNALS));
        mAttentionHelper.buzzBeepBlinkLocked(ringtoneNotification, DEFAULT_SIGNALS);
        verifyBeepLooped();
        verifyDelayedVibrateLooped();
        Mockito.reset(mVibrator);
        Mockito.reset(mRingtonePlayer);

        assertFalse(mAttentionHelper.shouldMuteNotificationLocked(ringtoneNotification,
            DEFAULT_SIGNALS));
        mAttentionHelper.buzzBeepBlinkLocked(ringtoneNotification, DEFAULT_SIGNALS);

        // beep wasn't reset
        verifyNeverBeep();
        verifyNeverVibrate();
        verifyNeverStopAudio();
        verifyNeverStopVibrate();
    }

    @Test
    public void testRingtoneInsistentBeep_clearEffectsStopsSoundAndVibration() throws Exception {
        NotificationChannel ringtoneChannel =
            new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(Uri.fromParts("a", "b", "c"),
            new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        ringtoneChannel.enableVibration(true);
        NotificationRecord ringtoneNotification = getCallRecord(1, ringtoneChannel, true);
        mService.addNotification(ringtoneNotification);
        assertFalse(mAttentionHelper.shouldMuteNotificationLocked(ringtoneNotification,
            DEFAULT_SIGNALS));
        mAttentionHelper.buzzBeepBlinkLocked(ringtoneNotification, DEFAULT_SIGNALS);
        verifyBeepLooped();
        verifyDelayedVibrateLooped();

        mAttentionHelper.clearSoundLocked();
        mAttentionHelper.clearVibrateLocked();

        verifyStopAudio();
        verifyStopVibrate();
    }

    @Test
    public void testRingtoneInsistentBeep_neverVibratesWhenEffectsClearedBeforeDelay()
        throws Exception {
        NotificationChannel ringtoneChannel =
            new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(Uri.fromParts("a", "b", "c"),
            new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        ringtoneChannel.enableVibration(true);
        NotificationRecord ringtoneNotification = getCallRecord(1, ringtoneChannel, true);
        mService.addNotification(ringtoneNotification);
        assertFalse(mAttentionHelper.shouldMuteNotificationLocked(ringtoneNotification,
            DEFAULT_SIGNALS));
        mAttentionHelper.buzzBeepBlinkLocked(ringtoneNotification, DEFAULT_SIGNALS);
        verifyBeepLooped();
        verifyNeverVibrate();

        mAttentionHelper.clearSoundLocked();
        mAttentionHelper.clearVibrateLocked();

        verifyStopAudio();
        verifyDelayedNeverVibrate();
    }

    @Test
    public void testCannotInterruptRingtoneInsistentBuzz() {
        NotificationChannel ringtoneChannel =
            new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(Uri.EMPTY,
            new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        ringtoneChannel.enableVibration(true);
        NotificationRecord ringtoneNotification = getCallRecord(1, ringtoneChannel, true);
        assertFalse(mAttentionHelper.shouldMuteNotificationLocked(ringtoneNotification,
            DEFAULT_SIGNALS));

        mAttentionHelper.buzzBeepBlinkLocked(ringtoneNotification, DEFAULT_SIGNALS);
        verifyVibrateLooped();

        NotificationRecord interrupter = getBuzzyOtherNotification();
        assertTrue(mAttentionHelper.shouldMuteNotificationLocked(interrupter, DEFAULT_SIGNALS));
        mAttentionHelper.buzzBeepBlinkLocked(interrupter, DEFAULT_SIGNALS);

        verifyVibrate(1);

        assertFalse(interrupter.isInterruptive());
        assertEquals(-1, interrupter.getLastAudiblyAlertedMs());
    }

    @Test
    public void testCanInterruptRingtoneNonInsistentBeep() throws Exception {
        NotificationChannel ringtoneChannel =
            new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(Settings.System.DEFAULT_RINGTONE_URI,
            new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        NotificationRecord ringtoneNotification = getCallRecord(1, ringtoneChannel, false);

        mAttentionHelper.buzzBeepBlinkLocked(ringtoneNotification, DEFAULT_SIGNALS);
        verifyBeepUnlooped();

        NotificationRecord interrupter = getBeepyOtherNotification();
        mAttentionHelper.buzzBeepBlinkLocked(interrupter, DEFAULT_SIGNALS);

        verifyBeep(2);

        assertTrue(interrupter.isInterruptive());
    }

    @Test
    public void testCanInterruptRingtoneNonInsistentBuzz() {
        NotificationChannel ringtoneChannel =
            new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(null,
            new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        ringtoneChannel.enableVibration(true);
        NotificationRecord ringtoneNotification = getCallRecord(1, ringtoneChannel, false);

        mAttentionHelper.buzzBeepBlinkLocked(ringtoneNotification, DEFAULT_SIGNALS);
        verifyVibrate();

        NotificationRecord interrupter = getBuzzyOtherNotification();
        mAttentionHelper.buzzBeepBlinkLocked(interrupter, DEFAULT_SIGNALS);

        verifyVibrate(2);

        assertTrue(interrupter.isInterruptive());
    }

    @Test
    public void testRingtoneInsistentBeep_doesNotBlockFutureSoundsOnceStopped() throws Exception {
        NotificationChannel ringtoneChannel =
            new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(Settings.System.DEFAULT_RINGTONE_URI,
            new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        NotificationRecord ringtoneNotification = getCallRecord(1, ringtoneChannel, true);

        mAttentionHelper.buzzBeepBlinkLocked(ringtoneNotification, DEFAULT_SIGNALS);
        verifyBeepLooped();

        mAttentionHelper.clearSoundLocked();

        NotificationRecord interrupter = getBeepyOtherNotification();
        mAttentionHelper.buzzBeepBlinkLocked(interrupter, DEFAULT_SIGNALS);

        verifyBeep(2);

        assertTrue(interrupter.isInterruptive());
    }

    @Test
    public void testRingtoneInsistentBuzz_doesNotBlockFutureSoundsOnceStopped() {
        NotificationChannel ringtoneChannel =
            new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        ringtoneChannel.setSound(null,
            new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION_RINGTONE).build());
        ringtoneChannel.enableVibration(true);
        NotificationRecord ringtoneNotification = getCallRecord(1, ringtoneChannel, true);

        mAttentionHelper.buzzBeepBlinkLocked(ringtoneNotification, DEFAULT_SIGNALS);
        verifyVibrateLooped();

        mAttentionHelper.clearVibrateLocked();

        NotificationRecord interrupter = getBuzzyOtherNotification();
        mAttentionHelper.buzzBeepBlinkLocked(interrupter, DEFAULT_SIGNALS);

        verifyVibrate(2);

        assertTrue(interrupter.isInterruptive());
    }

    @Test
    public void testCanInterruptNonRingtoneInsistentBeep() throws Exception {
        NotificationChannel fakeRingtoneChannel =
            new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        NotificationRecord ringtoneNotification = getCallRecord(1, fakeRingtoneChannel, true);

        mAttentionHelper.buzzBeepBlinkLocked(ringtoneNotification, DEFAULT_SIGNALS);
        verifyBeepLooped();

        NotificationRecord interrupter = getBeepyOtherNotification();
        mAttentionHelper.buzzBeepBlinkLocked(interrupter, DEFAULT_SIGNALS);

        verifyBeep(2);

        assertTrue(interrupter.isInterruptive());
    }

    @Test
    public void testCanInterruptNonRingtoneInsistentBuzz() {
        NotificationChannel fakeRingtoneChannel =
            new NotificationChannel("ringtone", "", IMPORTANCE_HIGH);
        fakeRingtoneChannel.enableVibration(true);
        fakeRingtoneChannel.setSound(null,
            new AudioAttributes.Builder().setUsage(USAGE_NOTIFICATION).build());
        NotificationRecord ringtoneNotification = getCallRecord(1, fakeRingtoneChannel, true);

        mAttentionHelper.buzzBeepBlinkLocked(ringtoneNotification, DEFAULT_SIGNALS);

        NotificationRecord interrupter = getBuzzyOtherNotification();
        mAttentionHelper.buzzBeepBlinkLocked(interrupter, DEFAULT_SIGNALS);

        verifyVibrate(2);

        assertTrue(interrupter.isInterruptive());
    }

    @Test
    public void testBubbleSuppressedNotificationDoesntMakeSound() {
        Notification.BubbleMetadata metadata = new Notification.BubbleMetadata.Builder(
            mock(PendingIntent.class), mock(Icon.class))
            .build();

        NotificationRecord record = getBuzzyNotification();
        metadata.setFlags(Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION);
        record.getNotification().setBubbleMetadata(metadata);
        record.setAllowBubble(true);
        record.getNotification().flags |= FLAG_BUBBLE;
        record.isUpdate = true;
        record.setInterruptive(false);

        mAttentionHelper.buzzBeepBlinkLocked(record, DEFAULT_SIGNALS);
        verifyNeverVibrate();
    }

    @Test
    public void testOverflowBubbleSuppressedNotificationDoesntMakeSound() {
        Notification.BubbleMetadata metadata = new Notification.BubbleMetadata.Builder(
            mock(PendingIntent.class), mock(Icon.class))
            .build();

        NotificationRecord record = getBuzzyNotification();
        metadata.setFlags(Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION);
        record.getNotification().setBubbleMetadata(metadata);
        record.setFlagBubbleRemoved(true);
        record.setAllowBubble(true);
        record.isUpdate = true;
        record.setInterruptive(false);

        mAttentionHelper.buzzBeepBlinkLocked(record, DEFAULT_SIGNALS);
        verifyNeverVibrate();
    }

    @Test
    public void testBubbleUpdateMakesSound() {
        Notification.BubbleMetadata metadata = new Notification.BubbleMetadata.Builder(
            mock(PendingIntent.class), mock(Icon.class))
            .build();

        NotificationRecord record = getBuzzyNotification();
        record.getNotification().setBubbleMetadata(metadata);
        record.setAllowBubble(true);
        record.getNotification().flags |= FLAG_BUBBLE;
        record.isUpdate = true;
        record.setInterruptive(true);

        mAttentionHelper.buzzBeepBlinkLocked(record, DEFAULT_SIGNALS);
        verifyVibrate(1);
    }

    @Test
    public void testNewBubbleSuppressedNotifMakesSound() {
        Notification.BubbleMetadata metadata = new Notification.BubbleMetadata.Builder(
            mock(PendingIntent.class), mock(Icon.class))
            .build();

        NotificationRecord record = getBuzzyNotification();
        metadata.setFlags(Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION);
        record.getNotification().setBubbleMetadata(metadata);
        record.setAllowBubble(true);
        record.getNotification().flags |= FLAG_BUBBLE;
        record.isUpdate = false;
        record.setInterruptive(true);

        mAttentionHelper.buzzBeepBlinkLocked(record, DEFAULT_SIGNALS);
        verifyVibrate(1);
    }

    @Test
    public void testStartFlashNotificationEvent_receiveBeepyNotification() throws Exception {
        NotificationRecord r = getBeepyNotification();

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyBeepUnlooped();
        verifyNeverVibrate();
        verify(mAccessibilityService).startFlashNotificationEvent(any(), anyInt(),
            eq(r.getSbn().getPackageName()));
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testStartFlashNotificationEvent_receiveBuzzyNotification() throws Exception {
        NotificationRecord r = getBuzzyNotification();

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyNeverBeep();
        verifyVibrate();
        verify(mAccessibilityService).startFlashNotificationEvent(any(), anyInt(),
            eq(r.getSbn().getPackageName()));
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testStartFlashNotificationEvent_receiveBuzzyBeepyNotification() throws Exception {
        NotificationRecord r = getBuzzyBeepyNotification();

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyBeepUnlooped();
        verifyDelayedVibrate(r.getVibration());
        verify(mAccessibilityService).startFlashNotificationEvent(any(), anyInt(),
            eq(r.getSbn().getPackageName()));
        assertTrue(r.isInterruptive());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testStartFlashNotificationEvent_receiveBuzzyBeepyNotification_ringerModeSilent()
        throws Exception {
        NotificationRecord r = getBuzzyBeepyNotification();
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(0);
        when(mAudioManager.shouldNotificationSoundPlay(any())).thenReturn(false);

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verifyNeverBeep();
        verifyNeverVibrate();
        verify(mAccessibilityService).startFlashNotificationEvent(any(), anyInt(),
            eq(r.getSbn().getPackageName()));
        assertFalse(r.isInterruptive());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    // TODO b/270456865: Only one of the two strategies will be released.
    //  The other one need to be removed
    @Test
    public void testBeepVolume_politeNotif() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_POLITE_NOTIFICATIONS);
        TestableFlagResolver flagResolver = new TestableFlagResolver();
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME1, 50);
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME2, 0);
        initAttentionHelper(flagResolver);

        NotificationRecord r = getBeepyNotification();

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
        Mockito.reset(mRingtonePlayer);

        // update should beep at 50% volume
        r.isUpdate = true;
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyBeepVolume(0.5f);
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());

        // 2nd update should beep at 0% volume
        Mockito.reset(mRingtonePlayer);
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyBeepVolume(0.0f);

        verify(mAccessibilityService, times(3)).sendAccessibilityEvent(any(), anyInt());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testBeepVolume_politeNotif_AvalancheStrategy() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_POLITE_NOTIFICATIONS);
        mSetFlagsRule.enableFlags(Flags.FLAG_CROSS_APP_POLITE_NOTIFICATIONS);
        TestableFlagResolver flagResolver = new TestableFlagResolver();
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME1, 50);
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME2, 0);
        initAttentionHelper(flagResolver);

        // Trigger avalanche trigger intent
        final Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra("state", false);
        mAvalancheBroadcastReceiver.onReceive(getContext(), intent);

        NotificationRecord r = getBeepyNotification();

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
        Mockito.reset(mRingtonePlayer);

        // Use different package for next notifications
        NotificationRecord r2 = getNotificationRecord(mId, false /* insistent */, false /* once */,
                true /* noisy */, false /* buzzy*/, false /* lights */, true, true,
                false, null, Notification.GROUP_ALERT_ALL, false, mUser, "anotherPkg");

        // update should beep at 50% volume
        mAttentionHelper.buzzBeepBlinkLocked(r2, DEFAULT_SIGNALS);
        verifyBeepVolume(0.5f);
        assertNotEquals(-1, r2.getLastAudiblyAlertedMs());

        // Use different package for next notifications
        NotificationRecord r3 = getNotificationRecord(mId, false /* insistent */, false /* once */,
                true /* noisy */, false /* buzzy*/, false /* lights */, true, true,
                false, null, Notification.GROUP_ALERT_ALL, false, mUser, "yetAnotherPkg");

        // 2nd update should beep at 0% volume
        Mockito.reset(mRingtonePlayer);
        mAttentionHelper.buzzBeepBlinkLocked(r3, DEFAULT_SIGNALS);
        verifyBeepVolume(0.0f);

        verify(mAccessibilityService, times(3)).sendAccessibilityEvent(any(), anyInt());
        assertEquals(-1, r3.getLastAudiblyAlertedMs());
    }

    @Test
    public void testBeepVolume_politeNotif_AvalancheStrategy_ChannelHasUserSound()
            throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_POLITE_NOTIFICATIONS);
        mSetFlagsRule.enableFlags(Flags.FLAG_CROSS_APP_POLITE_NOTIFICATIONS);
        TestableFlagResolver flagResolver = new TestableFlagResolver();
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME1, 50);
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME2, 0);
        initAttentionHelper(flagResolver);

        // Trigger avalanche trigger intent
        final Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra("state", false);
        mAvalancheBroadcastReceiver.onReceive(getContext(), intent);

        NotificationRecord r = getBeepyNotification();

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        Mockito.reset(mRingtonePlayer);

        // Use package with user-set sounds for next notifications
        mChannel = new NotificationChannel("test2", "test2", IMPORTANCE_DEFAULT);
        mChannel.lockFields(NotificationChannel.USER_LOCKED_SOUND);
        NotificationRecord r2 = getNotificationRecord(mId, false /* insistent */, false /* once */,
                true /* noisy */, false /* buzzy*/, false /* lights */, true, true,
                false, null, Notification.GROUP_ALERT_ALL, false, mUser, "anotherPkg");

        // update should beep at 100% volume
        mAttentionHelper.buzzBeepBlinkLocked(r2, DEFAULT_SIGNALS);
        verifyBeepVolume(1.0f);

        // 2nd update should beep at 50% volume
        Mockito.reset(mRingtonePlayer);
        mAttentionHelper.buzzBeepBlinkLocked(r2, DEFAULT_SIGNALS);
        verifyBeepVolume(0.5f);

        verify(mAccessibilityService, times(3)).sendAccessibilityEvent(any(), anyInt());
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testBeepVolume_politeNotif_AvalancheStrategy_AttnUpdate() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_POLITE_NOTIFICATIONS);
        mSetFlagsRule.enableFlags(Flags.FLAG_CROSS_APP_POLITE_NOTIFICATIONS);
        mSetFlagsRule.enableFlags(Flags.FLAG_POLITE_NOTIFICATIONS_ATTN_UPDATE);
        TestableFlagResolver flagResolver = new TestableFlagResolver();
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME1, 50);
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME2, 0);
        initAttentionHelper(flagResolver);

        // Trigger avalanche trigger intent
        final Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra("state", false);
        mAvalancheBroadcastReceiver.onReceive(getContext(), intent);

        NotificationRecord r = getBeepyNotification();

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        assertEquals(-1, r.getLastAudiblyAlertedMs());
        Mockito.reset(mRingtonePlayer);

        // Use different package for next notifications
        NotificationRecord r2 = getNotificationRecord(mId, false /* insistent */, false /* once */,
                true /* noisy */, false /* buzzy*/, false /* lights */, true, true,
                false, null, Notification.GROUP_ALERT_ALL, false, mUser, "anotherPkg");

        // update should beep at 0% volume
        mAttentionHelper.buzzBeepBlinkLocked(r2, DEFAULT_SIGNALS);
        assertEquals(-1, r2.getLastAudiblyAlertedMs());
        verifyBeepVolume(0.0f);

        // Use different package for next notifications
        NotificationRecord r3 = getNotificationRecord(mId, false /* insistent */, false /* once */,
                true /* noisy */, false /* buzzy*/, false /* lights */, true, true,
                false, null, Notification.GROUP_ALERT_ALL, false, mUser, "yetAnotherPkg");

        // 2nd update should beep at 0% volume
        Mockito.reset(mRingtonePlayer);
        mAttentionHelper.buzzBeepBlinkLocked(r3, DEFAULT_SIGNALS);
        verifyBeepVolume(0.0f);

        verify(mAccessibilityService, times(3)).sendAccessibilityEvent(any(), anyInt());
        assertEquals(-1, r3.getLastAudiblyAlertedMs());
    }

    @Test
    public void testBeepVolume_politeNotif_AvalancheStrategy_exempt_AttnUpdate()
            throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_POLITE_NOTIFICATIONS);
        mSetFlagsRule.enableFlags(Flags.FLAG_CROSS_APP_POLITE_NOTIFICATIONS);
        mSetFlagsRule.enableFlags(Flags.FLAG_POLITE_NOTIFICATIONS_ATTN_UPDATE);
        TestableFlagResolver flagResolver = new TestableFlagResolver();
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME1, 50);
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME2, 0);
        initAttentionHelper(flagResolver);

        // Trigger avalanche trigger intent
        final Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra("state", false);
        mAvalancheBroadcastReceiver.onReceive(getContext(), intent);

        NotificationRecord r = getBeepyNotification();
        r.getNotification().category = Notification.CATEGORY_EVENT;

        // Should beep at 100% volume
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyBeepVolume(1.0f);
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
        Mockito.reset(mRingtonePlayer);

        // Use different package for next notifications
        NotificationRecord r2 = getConversationNotificationRecord(mId, false /* insistent */,
                false /* once */, true /* noisy */, false /* buzzy*/, false /* lights */, true,
                true, false, null, Notification.GROUP_ALERT_ALL, false, mUser, "anotherPkg",
                "shortcut");

        // Should beep at 100% volume
        mAttentionHelper.buzzBeepBlinkLocked(r2, DEFAULT_SIGNALS);
        assertNotEquals(-1, r2.getLastAudiblyAlertedMs());
        verifyBeepVolume(1.0f);

        // Use different package for next notifications
        mChannel = new NotificationChannel("test3", "test3", IMPORTANCE_DEFAULT);
        NotificationRecord r3 = getNotificationRecord(mId, false /* insistent */, false /* once */,
                true /* noisy */, false /* buzzy*/, false /* lights */, true, true,
                false, null, Notification.GROUP_ALERT_ALL, false, mUser, "yetAnotherPkg");

        r3.getNotification().category = Notification.CATEGORY_REMINDER;

        // Should beep at 100% volume
        Mockito.reset(mRingtonePlayer);
        mAttentionHelper.buzzBeepBlinkLocked(r3, DEFAULT_SIGNALS);
        assertNotEquals(-1, r3.getLastAudiblyAlertedMs());
        verifyBeepVolume(1.0f);

        // Same package as r3 for next notifications
        NotificationRecord r4 = getConversationNotificationRecord(mId, false /* insistent */,
                false /* once */, true /* noisy */, false /* buzzy*/, false /* lights */, true,
                true, false, null, Notification.GROUP_ALERT_ALL, false, mUser, "yetAnotherPkg",
                "shortcut");

        // 2nd update should beep at 50% volume
        Mockito.reset(mRingtonePlayer);
        mAttentionHelper.buzzBeepBlinkLocked(r4, DEFAULT_SIGNALS);
        verifyBeepVolume(0.5f);

        verify(mAccessibilityService, times(4)).sendAccessibilityEvent(any(), anyInt());
        assertNotEquals(-1, r4.getLastAudiblyAlertedMs());
    }

    @Test
    public void testBeepVolume_politeNotif_applyPerApp() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_POLITE_NOTIFICATIONS);
        mSetFlagsRule.disableFlags(Flags.FLAG_CROSS_APP_POLITE_NOTIFICATIONS);
        TestableFlagResolver flagResolver = new TestableFlagResolver();
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME1, 50);
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME2, 0);
        // NOTIFICATION_COOLDOWN_ALL setting is enabled
        Settings.System.putInt(getContext().getContentResolver(),
                Settings.System.NOTIFICATION_COOLDOWN_ALL, 1);
        initAttentionHelper(flagResolver);

        NotificationRecord r = getBeepyNotification();

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        Mockito.reset(mRingtonePlayer);

        // Use different channel for next notifications
        mChannel = new NotificationChannel("test2", "test2", IMPORTANCE_DEFAULT);

        // update should beep at 50% volume
        NotificationRecord r2 = getBeepyNotification();
        mAttentionHelper.buzzBeepBlinkLocked(r2, DEFAULT_SIGNALS);
        assertNotEquals(-1, r2.getLastAudiblyAlertedMs());
        verifyBeepVolume(0.5f);

        // 2nd update should beep at 0% volume
        Mockito.reset(mRingtonePlayer);
        mAttentionHelper.buzzBeepBlinkLocked(r2, DEFAULT_SIGNALS);
        assertEquals(-1, r2.getLastAudiblyAlertedMs());
        verifyBeepVolume(0.0f);

        // Use different package for next notifications
        NotificationRecord r3 = getNotificationRecord(mId, false /* insistent */, false /* once */,
                true /* noisy */, false /* buzzy*/, false /* lights */, true, true,
                false, null, Notification.GROUP_ALERT_ALL, false, mUser, "anotherPkg");

        // Update from new package should beep at 100% volume
        Mockito.reset(mRingtonePlayer);
        mAttentionHelper.buzzBeepBlinkLocked(r3, DEFAULT_SIGNALS);
        verifyBeepVolume(1.0f);

        verify(mAccessibilityService, times(4)).sendAccessibilityEvent(any(), anyInt());
        assertNotEquals(-1, r3.getLastAudiblyAlertedMs());
    }

    @Test
    public void testBeepVolume_politeNotif_applyPerApp_ChannelHasUserSound() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_POLITE_NOTIFICATIONS);
        mSetFlagsRule.disableFlags(Flags.FLAG_CROSS_APP_POLITE_NOTIFICATIONS);
        TestableFlagResolver flagResolver = new TestableFlagResolver();
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME1, 50);
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME2, 0);
        // NOTIFICATION_COOLDOWN_ALL setting is enabled
        Settings.System.putInt(getContext().getContentResolver(),
                Settings.System.NOTIFICATION_COOLDOWN_ALL, 1);
        initAttentionHelper(flagResolver);

        NotificationRecord r = getBeepyNotification();

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        Mockito.reset(mRingtonePlayer);

        // Use different channel for next notifications
        mChannel = new NotificationChannel("test2", "test2", IMPORTANCE_DEFAULT);
        mChannel.lockFields(NotificationChannel.USER_LOCKED_SOUND);

        // update should beep at 100% volume
        NotificationRecord r2 = getBeepyNotification();
        mAttentionHelper.buzzBeepBlinkLocked(r2, DEFAULT_SIGNALS);
        assertNotEquals(-1, r2.getLastAudiblyAlertedMs());
        verifyBeepVolume(1.0f);

        // 2nd update should beep at 50% volume
        Mockito.reset(mRingtonePlayer);
        mAttentionHelper.buzzBeepBlinkLocked(r2, DEFAULT_SIGNALS);
        assertNotEquals(-1, r2.getLastAudiblyAlertedMs());
        verifyBeepVolume(0.5f);

        // Use different package for next notifications
        mChannel = new NotificationChannel("test3", "test3", IMPORTANCE_DEFAULT);
        NotificationRecord r3 = getNotificationRecord(mId, false /* insistent */, false /* once */,
                true /* noisy */, false /* buzzy*/, false /* lights */, true, true,
                false, null, Notification.GROUP_ALERT_ALL, false, mUser, "anotherPkg");

        // Update from new package should beep at 100% volume
        Mockito.reset(mRingtonePlayer);
        mAttentionHelper.buzzBeepBlinkLocked(r3, DEFAULT_SIGNALS);
        verifyBeepVolume(1.0f);

        verify(mAccessibilityService, times(4)).sendAccessibilityEvent(any(), anyInt());
        assertNotEquals(-1, r3.getLastAudiblyAlertedMs());
    }

    @Test
    public void testVibrationIntensity_politeNotif() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_POLITE_NOTIFICATIONS);
        TestableFlagResolver flagResolver = new TestableFlagResolver();
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME1, 50);
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME2, 0);
        initAttentionHelper(flagResolver);

        NotificationRecord r = getBuzzyBeepyNotification();

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        VibratorHelper vibratorHelper = mAttentionHelper.getVibratorHelper();
        Mockito.reset(vibratorHelper);

        // update should buzz at 50% intensity
        r.isUpdate = true;
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        verify(vibratorHelper, times(1)).scale(any(), eq(0.5f));
        Mockito.reset(vibratorHelper);

        // 2nd update should buzz at 0% intensity
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verify(vibratorHelper, times(1)).scale(any(), eq(0.0f));
    }

    @Test
    public void testBuzzOnlyOnScreenUnlock_politeNotif() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_POLITE_NOTIFICATIONS);
        mSetFlagsRule.enableFlags(Flags.FLAG_VIBRATE_WHILE_UNLOCKED);
        TestableFlagResolver flagResolver = new TestableFlagResolver();

        // When NOTIFICATION_COOLDOWN_VIBRATE_UNLOCKED setting is enabled
        Settings.System.putInt(getContext().getContentResolver(),
                Settings.System.NOTIFICATION_COOLDOWN_VIBRATE_UNLOCKED, 1);

        initAttentionHelper(flagResolver);
        // And screen is unlocked
        mAttentionHelper.setUserPresent(true);

        NotificationRecord r = getBuzzyBeepyNotification();
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);

        // The notification attention should only buzz
        verifyNeverBeep();
        verifyVibrate();
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testBeepVolume_politeNotif_disabled() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_POLITE_NOTIFICATIONS);
        TestableFlagResolver flagResolver = new TestableFlagResolver();
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME1, 50);
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME2, 0);

        // When NOTIFICATION_COOLDOWN_ENABLED setting is disabled
        Settings.System.putInt(getContext().getContentResolver(),
                Settings.System.NOTIFICATION_COOLDOWN_ENABLED, 0);

        initAttentionHelper(flagResolver);

        NotificationRecord r = getBeepyNotification();

        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        Mockito.reset(mRingtonePlayer);

        // update should beep at 100% volume
        r.isUpdate = true;
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyBeepVolume(1.0f);

        // 2nd update should beep at 100% volume
        Mockito.reset(mRingtonePlayer);
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        verifyBeepVolume(1.0f);

        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testBeepVolume_politeNotif_workProfile() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_POLITE_NOTIFICATIONS);
        TestableFlagResolver flagResolver = new TestableFlagResolver();
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME1, 50);
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME2, 0);

        final int workProfileUserId = mUser.getIdentifier() + 1;

        // Enable notifications cooldown for work profile
        Settings.System.putIntForUser(getContext().getContentResolver(),
                Settings.System.NOTIFICATION_COOLDOWN_ENABLED, 1, workProfileUserId);

        when(mUserManager.getProfiles(mUser.getIdentifier())).thenReturn(
                List.of(new UserInfo(workProfileUserId, "work_profile", null,
                        UserInfo.FLAG_PROFILE | UserInfo.FLAG_MANAGED_PROFILE,
                        UserInfo.getDefaultUserType(UserInfo.FLAG_MANAGED_PROFILE))));

        initAttentionHelper(flagResolver);

        final NotificationRecord r = getBuzzyBeepyNotification(UserHandle.of(workProfileUserId));

        // set up internal state
        mAttentionHelper.buzzBeepBlinkLocked(r, WORK_PROFILE_SIGNALS);
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
        Mockito.reset(mRingtonePlayer);

        // update should beep at 50% volume
        r.isUpdate = true;
        mAttentionHelper.buzzBeepBlinkLocked(r, WORK_PROFILE_SIGNALS);
        verifyBeepVolume(0.5f);
        assertNotEquals(-1, r.getLastAudiblyAlertedMs());

        // 2nd update should beep at 0% volume
        Mockito.reset(mRingtonePlayer);
        mAttentionHelper.buzzBeepBlinkLocked(r, WORK_PROFILE_SIGNALS);
        verifyBeepVolume(0.0f);

        verify(mAccessibilityService, times(3)).sendAccessibilityEvent(any(), anyInt());
        assertEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testBeepVolume_politeNotif_workProfile_disabled() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_POLITE_NOTIFICATIONS);
        TestableFlagResolver flagResolver = new TestableFlagResolver();
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME1, 50);
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_VOLUME2, 0);

        final int workProfileUserId = mUser.getIdentifier() + 1;

        // Disable notifications cooldown for work profile
        Settings.System.putIntForUser(getContext().getContentResolver(),
                Settings.System.NOTIFICATION_COOLDOWN_ENABLED, 0, workProfileUserId);

        when(mUserManager.getProfiles(mUser.getIdentifier())).thenReturn(
                List.of(new UserInfo(workProfileUserId, "work_profile", null,
                        UserInfo.FLAG_PROFILE | UserInfo.FLAG_MANAGED_PROFILE,
                        UserInfo.getDefaultUserType(UserInfo.FLAG_MANAGED_PROFILE))));

        initAttentionHelper(flagResolver);

        final NotificationRecord r = getBuzzyBeepyNotification(UserHandle.of(workProfileUserId));

        mAttentionHelper.buzzBeepBlinkLocked(r, WORK_PROFILE_SIGNALS);
        Mockito.reset(mRingtonePlayer);

        // update should beep at 100% volume
        r.isUpdate = true;
        mAttentionHelper.buzzBeepBlinkLocked(r, WORK_PROFILE_SIGNALS);
        verifyBeepVolume(1.0f);

        // 2nd update should beep at 100% volume
        Mockito.reset(mRingtonePlayer);
        mAttentionHelper.buzzBeepBlinkLocked(r, WORK_PROFILE_SIGNALS);
        verifyBeepVolume(1.0f);

        assertNotEquals(-1, r.getLastAudiblyAlertedMs());
    }

    @Test
    public void testAvalancheStrategyTriggers() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_POLITE_NOTIFICATIONS);
        mSetFlagsRule.enableFlags(Flags.FLAG_CROSS_APP_POLITE_NOTIFICATIONS);
        TestableFlagResolver flagResolver = new TestableFlagResolver();
        final int avalancheTimeoutMs = 100;
        flagResolver.setFlagOverride(NotificationFlags.NOTIF_AVALANCHE_TIMEOUT, avalancheTimeoutMs);
        initAttentionHelper(flagResolver);

        // Trigger avalanche trigger intents
        for (String intentAction
                : NotificationAttentionHelper.NOTIFICATION_AVALANCHE_TRIGGER_INTENTS) {
            // Set the action and extras to trigger the avalanche strategy
            Intent intent = new Intent(intentAction);
            Pair<String, Boolean> extras =
                    NotificationAttentionHelper.NOTIFICATION_AVALANCHE_TRIGGER_EXTRAS
                        .get(intentAction);
            if (extras != null) {
                intent.putExtra(extras.first, extras.second);
            }
            mAvalancheBroadcastReceiver.onReceive(getContext(), intent);
            assertThat(mAttentionHelper.getPolitenessStrategy().isActive()).isTrue();

            // Wait for avalanche timeout
            Thread.sleep(avalancheTimeoutMs + 1);

            // Check that avalanche strategy is inactive
            assertThat(mAttentionHelper.getPolitenessStrategy().isActive()).isFalse();
        }
    }

    @Test
    public void testAvalancheStrategyTriggers_disabledExtras() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_POLITE_NOTIFICATIONS);
        mSetFlagsRule.enableFlags(Flags.FLAG_CROSS_APP_POLITE_NOTIFICATIONS);
        TestableFlagResolver flagResolver = new TestableFlagResolver();
        initAttentionHelper(flagResolver);

        for (String intentAction
                : NotificationAttentionHelper.NOTIFICATION_AVALANCHE_TRIGGER_INTENTS) {
            Intent intent = new Intent(intentAction);
            Pair<String, Boolean> extras =
                    NotificationAttentionHelper.NOTIFICATION_AVALANCHE_TRIGGER_EXTRAS
                        .get(intentAction);
            // Test only for intents with extras
            if (extras != null) {
                // Set the action extras to NOT trigger the avalanche strategy
                intent.putExtra(extras.first, !extras.second);
                mAvalancheBroadcastReceiver.onReceive(getContext(), intent);
                // Check that avalanche strategy is inactive
                assertThat(mAttentionHelper.getPolitenessStrategy().isActive()).isFalse();
            }
        }
    }

    @Test
    public void testAvalancheStrategyTriggers_nonAvalancheIntents() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_POLITE_NOTIFICATIONS);
        mSetFlagsRule.enableFlags(Flags.FLAG_CROSS_APP_POLITE_NOTIFICATIONS);
        TestableFlagResolver flagResolver = new TestableFlagResolver();
        initAttentionHelper(flagResolver);

        // Broadcast intents that are not avalanche triggers
        final Set<String> notAvalancheTriggerIntents = Set.of(
                Intent.ACTION_USER_ADDED,
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_POWER_CONNECTED
        );
        for (String intentAction : notAvalancheTriggerIntents) {
            Intent intent = new Intent(intentAction);
            mAvalancheBroadcastReceiver.onReceive(getContext(), intent);
            // Check that avalanche strategy is inactive
            assertThat(mAttentionHelper.getPolitenessStrategy().isActive()).isFalse();
        }
    }

    @Test
    public void testSoundResetsRankingTime() throws Exception {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_SORT_SECTION_BY_TIME);
        TestableFlagResolver flagResolver = new TestableFlagResolver();
        initAttentionHelper(flagResolver);

        NotificationRecord r = getBuzzyBeepyNotification();
        mAttentionHelper.buzzBeepBlinkLocked(r, DEFAULT_SIGNALS);
        assertThat(r.getRankingTimeMs()).isEqualTo(r.getSbn().getPostTime());
    }

    static class VibrateRepeatMatcher implements ArgumentMatcher<VibrationEffect> {
        private final int mRepeatIndex;

        VibrateRepeatMatcher(int repeatIndex) {
            mRepeatIndex = repeatIndex;
        }

        @Override
        public boolean matches(VibrationEffect actual) {
            if (actual instanceof VibrationEffect.Composed
                && ((VibrationEffect.Composed) actual).getRepeatIndex() == mRepeatIndex) {
                return true;
            }
            // All non-waveform effects are essentially one shots.
            return mRepeatIndex == -1;
        }

        @Override
        public String toString() {
            return "repeatIndex=" + mRepeatIndex;
        }
    }
}
