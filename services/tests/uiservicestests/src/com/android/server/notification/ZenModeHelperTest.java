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
 * distributed under the License is distriZenbuted on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import static android.app.AutomaticZenRule.TYPE_BEDTIME;
import static android.app.NotificationManager.AUTOMATIC_RULE_STATUS_ACTIVATED;
import static android.app.NotificationManager.AUTOMATIC_RULE_STATUS_DEACTIVATED;
import static android.app.NotificationManager.AUTOMATIC_RULE_STATUS_DISABLED;
import static android.app.NotificationManager.AUTOMATIC_RULE_STATUS_ENABLED;
import static android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS;
import static android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
import static android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY;
import static android.app.NotificationManager.Policy.CONVERSATION_SENDERS_ANYONE;
import static android.app.NotificationManager.Policy.CONVERSATION_SENDERS_IMPORTANT;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_CALLS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_CONVERSATIONS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_EVENTS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_REMINDERS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_SYSTEM;
import static android.app.NotificationManager.Policy.PRIORITY_SENDERS_ANY;
import static android.app.NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS;
import static android.app.NotificationManager.Policy.PRIORITY_SENDERS_STARRED;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;
import static android.provider.Settings.Global.ZEN_MODE_ALARMS;
import static android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
import static android.provider.Settings.Global.ZEN_MODE_OFF;
import static android.service.notification.Condition.SOURCE_SCHEDULE;
import static android.service.notification.Condition.SOURCE_USER_ACTION;
import static android.service.notification.Condition.STATE_FALSE;
import static android.service.notification.Condition.STATE_TRUE;
import static android.service.notification.ZenModeConfig.UPDATE_ORIGIN_APP;
import static android.service.notification.ZenModeConfig.UPDATE_ORIGIN_INIT;
import static android.service.notification.ZenModeConfig.UPDATE_ORIGIN_INIT_USER;
import static android.service.notification.ZenModeConfig.UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI;
import static android.service.notification.ZenModeConfig.UPDATE_ORIGIN_UNKNOWN;
import static android.service.notification.ZenModeConfig.UPDATE_ORIGIN_USER;
import static android.service.notification.ZenPolicy.PEOPLE_TYPE_CONTACTS;
import static android.service.notification.ZenPolicy.PEOPLE_TYPE_STARRED;

import static com.android.internal.config.sysui.SystemUiSystemPropertiesFlags.NotificationFlags.LOG_DND_STATE_EVENTS;
import static com.android.os.dnd.DNDProtoEnums.PEOPLE_STARRED;
import static com.android.os.dnd.DNDProtoEnums.ROOT_CONFIG;
import static com.android.os.dnd.DNDProtoEnums.STATE_ALLOW;
import static com.android.os.dnd.DNDProtoEnums.STATE_DISALLOW;
import static com.android.server.notification.ZenModeHelper.RULE_LIMIT_PER_PACKAGE;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.TestCase.assertTrue;
import static junit.framework.TestCase.fail;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.notNull;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.AutomaticZenRule;
import android.app.Flags;
import android.app.NotificationManager;
import android.app.NotificationManager.Policy;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioManagerInternal;
import android.media.AudioSystem;
import android.media.VolumePolicy;
import android.net.Uri;
import android.os.Parcel;
import android.os.Process;
import android.os.UserHandle;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.notification.Condition;
import android.service.notification.DeviceEffectsApplier;
import android.service.notification.ZenDeviceEffects;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.ConfigChangeOrigin;
import android.service.notification.ZenModeConfig.ScheduleInfo;
import android.service.notification.ZenModeConfig.ZenRule;
import android.service.notification.ZenModeDiff;
import android.service.notification.ZenPolicy;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.TestWithLooperRule;
import android.testing.TestableLooper;
import android.util.ArrayMap;
import android.util.Log;
import android.util.StatsEvent;
import android.util.StatsEventTestUtils;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.config.sysui.TestableFlagResolver;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.os.AtomsProto;
import com.android.os.dnd.DNDModeProto;
import com.android.os.dnd.DNDPolicyProto;
import com.android.os.dnd.DNDProtoEnums;
import com.android.server.UiServiceTestCase;
import com.android.server.notification.ManagedServices.UserProfiles;

import com.google.common.collect.ImmutableList;
import com.google.common.truth.Correspondence;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SmallTest
@SuppressLint("GuardedBy") // It's ok for this test to access guarded methods from the service.
@RunWith(TestParameterInjector.class)
@TestableLooper.RunWithLooper
public class ZenModeHelperTest extends UiServiceTestCase {

    private static final String EVENTS_DEFAULT_RULE_ID = ZenModeConfig.EVENTS_DEFAULT_RULE_ID;
    private static final String SCHEDULE_DEFAULT_RULE_ID =
            ZenModeConfig.EVERY_NIGHT_DEFAULT_RULE_ID;
    private static final String CUSTOM_PKG_NAME = "not.android";
    private static final String CUSTOM_APP_LABEL = "This is not Android";
    private static final int CUSTOM_PKG_UID = 1;
    private static final String CUSTOM_RULE_ID = "custom_rule";

    private static final String NAME = "name";
    private static final ComponentName OWNER = new ComponentName("pkg", "cls");
    private static final ComponentName CONFIG_ACTIVITY = new ComponentName("pkg", "act");
    private static final ZenPolicy POLICY = new ZenPolicy.Builder().allowAlarms(true).build();
    private static final Uri CONDITION_ID = new Uri.Builder().scheme("scheme")
            .authority("authority")
            .appendPath("path")
            .appendPath("test")
            .build();

    private static final Condition CONDITION_TRUE = new Condition(CONDITION_ID, "",
            Condition.STATE_TRUE);
    private static final Condition CONDITION_FALSE = new Condition(CONDITION_ID, "",
            Condition.STATE_FALSE);
    private static final String TRIGGER_DESC = "Every Night, 10pm to 6am";
    private static final int TYPE = TYPE_BEDTIME;
    private static final boolean ALLOW_MANUAL = true;
    private static final String ICON_RES_NAME = "com.android.server.notification:drawable/res_name";
    private static final int ICON_RES_ID = 123;
    private static final int INTERRUPTION_FILTER_ZR = Settings.Global.ZEN_MODE_ALARMS;

    private static final int INTERRUPTION_FILTER_AZR
            = NotificationManager.INTERRUPTION_FILTER_ALARMS;
    private static final boolean ENABLED = true;
    private static final int CREATION_TIME = 123;
    private static final ZenDeviceEffects NO_EFFECTS = new ZenDeviceEffects.Builder().build();

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule(
            SetFlagsRule.DefaultInitValueType.DEVICE_DEFAULT);

    @Rule(order = Integer.MAX_VALUE) // set the highest order so it's the innermost rule
    public TestWithLooperRule mLooperRule = new TestWithLooperRule();

    ConditionProviders mConditionProviders;
    @Mock NotificationManager mNotificationManager;
    @Mock PackageManager mPackageManager;
    private Resources mResources;
    private TestableLooper mTestableLooper;
    private ZenModeHelper mZenModeHelper;
    private ContentResolver mContentResolver;
    @Mock DeviceEffectsApplier mDeviceEffectsApplier;
    @Mock AppOpsManager mAppOps;
    TestableFlagResolver mTestFlagResolver = new TestableFlagResolver();
    ZenModeEventLoggerFake mZenModeEventLogger;

    @Before
    public void setUp() throws PackageManager.NameNotFoundException {
        MockitoAnnotations.initMocks(this);

        mTestableLooper = TestableLooper.get(this);
        mContext.ensureTestableResources();
        mContentResolver = mContext.getContentResolver();
        mResources = mock(Resources.class, withSettings()
                .spiedInstance(mContext.getResources()));
        String pkg = mContext.getPackageName();
        try {
            when(mResources.getXml(R.xml.default_zen_mode_config)).thenReturn(
                    getDefaultConfigParser());
        } catch (Exception e) {
            Log.d("ZenModeHelperTest", "Couldn't mock default zen mode config xml file err=" +
                    e.toString());
        }
        when(mResources.getIdentifier(ICON_RES_NAME, null, null)).thenReturn(ICON_RES_ID);
        when(mResources.getResourceName(ICON_RES_ID)).thenReturn(ICON_RES_NAME);
        when(mPackageManager.getResourcesForApplication(anyString())).thenReturn(
                mResources);

        mContext.addMockSystemService(AppOpsManager.class, mAppOps);
        mContext.addMockSystemService(NotificationManager.class, mNotificationManager);

        mConditionProviders = new ConditionProviders(mContext, new UserProfiles(),
                AppGlobals.getPackageManager());
        mConditionProviders.addSystemProvider(new CountdownConditionProvider());
        mConditionProviders.addSystemProvider(new ScheduleConditionProvider());
        mZenModeEventLogger = new ZenModeEventLoggerFake(mPackageManager);
        mZenModeHelper = new ZenModeHelper(mContext, mTestableLooper.getLooper(),
                mConditionProviders, mTestFlagResolver, mZenModeEventLogger);

        ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        when(mPackageManager.queryIntentActivitiesAsUser(any(), anyInt(), anyInt())).thenReturn(
                ImmutableList.of(ri));
        when(mPackageManager.getPackageUidAsUser(eq(CUSTOM_PKG_NAME), anyInt()))
                .thenReturn(CUSTOM_PKG_UID);
        when(mPackageManager.getPackagesForUid(anyInt())).thenReturn(
                new String[] {pkg});

        ApplicationInfo appInfoSpy = spy(new ApplicationInfo());
        appInfoSpy.icon = ICON_RES_ID;
        when(appInfoSpy.loadLabel(any())).thenReturn(CUSTOM_APP_LABEL);
        when(mPackageManager.getApplicationInfo(eq(CUSTOM_PKG_NAME), anyInt()))
                .thenReturn(appInfoSpy);
        mZenModeHelper.mPm = mPackageManager;

        mZenModeEventLogger.reset();
    }

    private XmlResourceParser getDefaultConfigParser() throws IOException, XmlPullParserException {
        String xml = "<zen version=\"8\" user=\"0\">\n"
                + "<allow calls=\"false\" repeatCallers=\"false\" messages=\"true\" "
                + "reminders=\"false\" events=\"false\" callsFrom=\"1\" messagesFrom=\"2\" "
                + "visualScreenOff=\"true\" alarms=\"true\" "
                + "media=\"true\" system=\"false\" conversations=\"true\""
                + " conversationsFrom=\"2\"/>\n"
                + "<automatic ruleId=\"" + EVENTS_DEFAULT_RULE_ID
                + "\" enabled=\"false\" snoozing=\"false\""
                + " name=\"Event\" zen=\"1\""
                + " component=\"android/com.android.server.notification.EventConditionProvider\""
                + " conditionId=\"condition://android/event?userId=-10000&amp;calendar=&amp;"
                + "reply=1\"/>\n"
                + "<automatic ruleId=\"" + SCHEDULE_DEFAULT_RULE_ID + "\" enabled=\"false\""
                + " snoozing=\"false\" name=\"Sleeping\" zen=\"1\""
                + " component=\"android/com.android.server.notification.ScheduleConditionProvider\""
                + " conditionId=\"condition://android/schedule?days=1.2.3.4.5.6.7 &amp;start=22.0"
                + "&amp;end=7.0&amp;exitAtAlarm=true\"/>"
                + "<disallow visualEffects=\"511\" />"
                + "</zen>";
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(xml.getBytes())), null);
        parser.nextTag();
        return new XmlResourceParserImpl(parser);
    }

    private ByteArrayOutputStream writeXmlAndPurge(Integer version) throws Exception {
        TypedXmlSerializer serializer = Xml.newFastSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
        serializer.startDocument(null, true);
        mZenModeHelper.writeXml(serializer, false, version, UserHandle.USER_ALL);
        serializer.endDocument();
        serializer.flush();
        mZenModeHelper.setConfig(new ZenModeConfig(), null, UPDATE_ORIGIN_INIT, "writing xml",
                Process.SYSTEM_UID);
        return baos;
    }

    private ByteArrayOutputStream writeXmlAndPurgeForUser(Integer version, int userId)
            throws Exception {
        TypedXmlSerializer serializer = Xml.newFastSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
        serializer.startDocument(null, true);
        mZenModeHelper.writeXml(serializer, true, version, userId);
        serializer.endDocument();
        serializer.flush();
        ZenModeConfig newConfig = new ZenModeConfig();
        newConfig.user = userId;
        mZenModeHelper.setConfig(newConfig, null, UPDATE_ORIGIN_INIT, "writing xml",
                Process.SYSTEM_UID);
        return baos;
    }

    private TypedXmlPullParser getParserForByteStream(ByteArrayOutputStream baos) throws Exception {
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(
                new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        return parser;
    }

    private ArrayMap<String, ZenModeConfig.ZenRule> getCustomAutomaticRules() {
        return getCustomAutomaticRules(ZEN_MODE_IMPORTANT_INTERRUPTIONS);
    }

    private ArrayMap<String, ZenModeConfig.ZenRule> getCustomAutomaticRules(int zenMode) {
        ArrayMap<String, ZenModeConfig.ZenRule> automaticRules = new ArrayMap<>();
        ZenModeConfig.ZenRule rule = createCustomAutomaticRule(zenMode, CUSTOM_RULE_ID);
        automaticRules.put(rule.id, rule);
        return automaticRules;
    }

    private ZenModeConfig.ZenRule createCustomAutomaticRule(int zenMode, String id) {
        ZenModeConfig.ZenRule customRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo customRuleInfo = new ScheduleInfo();
        customRule.enabled = true;
        customRule.creationTime = 0;
        customRule.id = id;
        customRule.name = "Custom Rule with id=" + id;
        customRule.zenMode = zenMode;
        customRule.conditionId = ZenModeConfig.toScheduleConditionId(customRuleInfo);
        customRule.configurationActivity =
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider");
        customRule.pkg = customRule.configurationActivity.getPackageName();
        return customRule;
    }

    // Verify that the appropriate appOpps operations are called for the restrictions requested.
    // Note that this method assumes that priority only DND exempt packages is set to something
    // in order to be able to distinguish it from the null case, so callers should make sure
    // setPriorityOnlyDndExemptPackages has been called bofre this verify statement.
    private void verifyApplyRestrictions(boolean zenPriorityOnly, boolean mute, int usage) {
        int expectedMode = mute ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED;
        verify(mAppOps, atLeastOnce()).setRestriction(eq(AppOpsManager.OP_VIBRATE), eq(usage),
                eq(expectedMode), zenPriorityOnly ? notNull() : eq(null));
        verify(mAppOps, atLeastOnce()).setRestriction(eq(AppOpsManager.OP_PLAY_AUDIO), eq(usage),
                eq(expectedMode), zenPriorityOnly ? notNull() : eq(null));
    }

    @Test
    public void testZenOff_NoMuteApplied() {
        mZenModeHelper.mZenMode = ZEN_MODE_OFF;
        mZenModeHelper.setPriorityOnlyDndExemptPackages(new String[] {PKG_O});
        mZenModeHelper.mConsolidatedPolicy = new Policy(Policy.PRIORITY_CATEGORY_ALARMS
                | PRIORITY_CATEGORY_MEDIA, 0, 0, 0, 0, 0);
        mZenModeHelper.applyRestrictions();

        // Check that we call through to applyRestrictions with usages USAGE_ALARM and USAGE_MEDIA
        verifyApplyRestrictions(false, false, AudioAttributes.USAGE_ALARM);
        verifyApplyRestrictions(false, false, AudioAttributes.USAGE_MEDIA);
    }

    @Test
    public void testZenOn_NotificationApplied() {
        mZenModeHelper.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelper.setPriorityOnlyDndExemptPackages(new String[] {PKG_O});
        // The most permissive policy
        mZenModeHelper.mConsolidatedPolicy = new Policy(Policy.PRIORITY_CATEGORY_ALARMS
                | PRIORITY_CATEGORY_MEDIA | PRIORITY_CATEGORY_MESSAGES
                | PRIORITY_CATEGORY_CONVERSATIONS | PRIORITY_CATEGORY_CALLS
                | PRIORITY_CATEGORY_ALARMS | PRIORITY_CATEGORY_EVENTS | PRIORITY_CATEGORY_REMINDERS
                | PRIORITY_CATEGORY_REPEAT_CALLERS | PRIORITY_CATEGORY_SYSTEM, PRIORITY_SENDERS_ANY,
                PRIORITY_SENDERS_ANY, 0, CONVERSATION_SENDERS_ANYONE);
        mZenModeHelper.applyRestrictions();

        verifyApplyRestrictions(true, true, AudioAttributes.USAGE_NOTIFICATION);
        verifyApplyRestrictions(true, true, AudioAttributes.USAGE_NOTIFICATION_EVENT);
        verifyApplyRestrictions(true, true,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED);
        verifyApplyRestrictions(true, true,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT);
    }

    @Test
    public void testZenOn_StarredCallers_CallTypesBlocked() {
        mZenModeHelper.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelper.setPriorityOnlyDndExemptPackages(new String[] {PKG_O});
        // The most permissive policy
        mZenModeHelper.mConsolidatedPolicy = new Policy(Policy.PRIORITY_CATEGORY_ALARMS
                | PRIORITY_CATEGORY_MEDIA | PRIORITY_CATEGORY_MESSAGES
                | PRIORITY_CATEGORY_CONVERSATIONS | PRIORITY_CATEGORY_CALLS
                | PRIORITY_CATEGORY_ALARMS | PRIORITY_CATEGORY_EVENTS | PRIORITY_CATEGORY_REMINDERS
                | PRIORITY_CATEGORY_SYSTEM,
                PRIORITY_SENDERS_STARRED,
                PRIORITY_SENDERS_ANY, 0, CONVERSATION_SENDERS_ANYONE);
        mZenModeHelper.applyRestrictions();

        verifyApplyRestrictions(true, true,
                AudioAttributes.USAGE_NOTIFICATION_RINGTONE);
        verifyApplyRestrictions(true, true,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST);
    }

    @Test
    public void testZenOn_AllCallers_CallTypesAllowed() {
        mZenModeHelper.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelper.setPriorityOnlyDndExemptPackages(new String[] {PKG_O});
        // The most permissive policy
        mZenModeHelper.mConsolidatedPolicy = new Policy(Policy.PRIORITY_CATEGORY_ALARMS
                | PRIORITY_CATEGORY_MEDIA | PRIORITY_CATEGORY_MESSAGES
                | PRIORITY_CATEGORY_CONVERSATIONS | PRIORITY_CATEGORY_CALLS
                | PRIORITY_CATEGORY_ALARMS | PRIORITY_CATEGORY_EVENTS | PRIORITY_CATEGORY_REMINDERS
                | PRIORITY_CATEGORY_REPEAT_CALLERS | PRIORITY_CATEGORY_SYSTEM,
                PRIORITY_SENDERS_ANY,
                PRIORITY_SENDERS_ANY, 0, CONVERSATION_SENDERS_ANYONE);
        mZenModeHelper.applyRestrictions();

        verifyApplyRestrictions(true, false, AudioAttributes.USAGE_NOTIFICATION_RINGTONE);
        verifyApplyRestrictions(true, false,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST);
    }

    @Test
    public void testZenOn_AllowAlarmsMedia_NoAlarmMediaMuteApplied() {
        mZenModeHelper.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelper.setPriorityOnlyDndExemptPackages(new String[] {PKG_O});
        mZenModeHelper.mConsolidatedPolicy = new Policy(Policy.PRIORITY_CATEGORY_ALARMS
                | PRIORITY_CATEGORY_MEDIA, 0, 0, 0, 0, 0);

        mZenModeHelper.applyRestrictions();
        verifyApplyRestrictions(true, false, AudioAttributes.USAGE_ALARM);
        verifyApplyRestrictions(true, false, AudioAttributes.USAGE_MEDIA);
    }

    @Test
    public void testZenOn_DisallowAlarmsMedia_AlarmMediaMuteApplied() {
        mZenModeHelper.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelper.setPriorityOnlyDndExemptPackages(new String[] {PKG_O});
        mZenModeHelper.mConsolidatedPolicy = new Policy(0, 0, 0, 0, 0, 0);
        mZenModeHelper.applyRestrictions();
        verifyApplyRestrictions(true, true, AudioAttributes.USAGE_ALARM);

        // Media is a catch-all that includes games
        verifyApplyRestrictions(true, true, AudioAttributes.USAGE_MEDIA);
        verifyApplyRestrictions(true, true, AudioAttributes.USAGE_GAME);
    }

    @Test
    public void testTotalSilence() {
        mZenModeHelper.mZenMode = Settings.Global.ZEN_MODE_NO_INTERRUPTIONS;
        mZenModeHelper.setPriorityOnlyDndExemptPackages(new String[] {PKG_O});
        mZenModeHelper.mConsolidatedPolicy = new Policy(Policy.PRIORITY_CATEGORY_ALARMS
                | PRIORITY_CATEGORY_MEDIA, 0, 0, 0, 0, 0);
        mZenModeHelper.applyRestrictions();

        // Total silence will silence alarms, media and system noises (but not vibrations)
        verifyApplyRestrictions(false, true, AudioAttributes.USAGE_ALARM);
        verifyApplyRestrictions(false, true, AudioAttributes.USAGE_MEDIA);
        verifyApplyRestrictions(false, true, AudioAttributes.USAGE_GAME);
        verify(mAppOps, atLeastOnce()).setRestriction(AppOpsManager.OP_PLAY_AUDIO,
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, AppOpsManager.MODE_IGNORED, null);
        verify(mAppOps, atLeastOnce()).setRestriction(AppOpsManager.OP_VIBRATE,
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, AppOpsManager.MODE_ALLOWED, null);
        verifyApplyRestrictions(false, true, AudioAttributes.USAGE_UNKNOWN);
    }

    @Test
    public void testAlarmsOnly_alarmMediaMuteNotApplied() {
        mZenModeHelper.mZenMode = Settings.Global.ZEN_MODE_ALARMS;
        mZenModeHelper.setPriorityOnlyDndExemptPackages(new String[] {PKG_O});
        mZenModeHelper.mConsolidatedPolicy = new Policy(0, 0, 0, 0, 0, 0);
        mZenModeHelper.applyRestrictions();

        // Alarms only mode will not silence alarms
        verifyApplyRestrictions(false, false, AudioAttributes.USAGE_ALARM);

        // Alarms only mode will not silence media
        verifyApplyRestrictions(false, false, AudioAttributes.USAGE_MEDIA);
        verifyApplyRestrictions(false, false, AudioAttributes.USAGE_GAME);
        verifyApplyRestrictions(false, false, AudioAttributes.USAGE_UNKNOWN);

        // Alarms only will silence system noises (but not vibrations)
        verify(mAppOps, atLeastOnce()).setRestriction(AppOpsManager.OP_PLAY_AUDIO,
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, AppOpsManager.MODE_IGNORED, null);
    }

    @Test
    public void testAlarmsOnly_callsMuteApplied() {
        mZenModeHelper.mZenMode = Settings.Global.ZEN_MODE_ALARMS;
        mZenModeHelper.setPriorityOnlyDndExemptPackages(new String[] {PKG_O});
        mZenModeHelper.mConsolidatedPolicy = new Policy(0, 0, 0, 0, 0, 0);
        mZenModeHelper.applyRestrictions();

        // Alarms only mode will silence calls despite priority-mode config
        verifyApplyRestrictions(false, true, AudioAttributes.USAGE_NOTIFICATION_RINGTONE);
        verifyApplyRestrictions(false, true,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST);
    }

    @Test
    public void testAlarmsOnly_allZenConfigToggledCannotBypass_alarmMuteNotApplied() {
        // Only audio attributes with SUPPRESIBLE_NEVER can bypass
        mZenModeHelper.mZenMode = Settings.Global.ZEN_MODE_ALARMS;
        mZenModeHelper.setPriorityOnlyDndExemptPackages(new String[] {PKG_O});
        mZenModeHelper.mConsolidatedPolicy = new Policy(0, 0, 0, 0, 0, 0);
        mZenModeHelper.applyRestrictions();

        verifyApplyRestrictions(false, false, AudioAttributes.USAGE_ALARM);
    }

    @Test
    public void testZenAllCannotBypass() {
        // Only audio attributes with SUPPRESIBLE_NEVER can bypass
        // with special case USAGE_ASSISTANCE_SONIFICATION
        mZenModeHelper.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelper.setPriorityOnlyDndExemptPackages(new String[] {PKG_O});
        mZenModeHelper.mConsolidatedPolicy = new Policy(0, 0, 0, 0, 0, 0);
        mZenModeHelper.applyRestrictions();

        for (int usage : AudioAttributes.getSdkUsages()) {
            if (usage == AudioAttributes.USAGE_ASSISTANCE_SONIFICATION) {
                // only mute audio, not vibrations
                verify(mAppOps, atLeastOnce()).setRestriction(eq(AppOpsManager.OP_PLAY_AUDIO),
                        eq(usage), eq(AppOpsManager.MODE_IGNORED), notNull());
                verify(mAppOps, atLeastOnce()).setRestriction(eq(AppOpsManager.OP_VIBRATE),
                        eq(usage), eq(AppOpsManager.MODE_ALLOWED), notNull());
            } else {
                boolean shouldMute = AudioAttributes.SUPPRESSIBLE_USAGES.get(usage)
                        != AudioAttributes.SUPPRESSIBLE_NEVER;
                verifyApplyRestrictions(true, shouldMute, usage);
            }
        }
    }

    @Test
    public void testApplyRestrictions_whitelist_priorityOnlyMode() {
        mZenModeHelper.setPriorityOnlyDndExemptPackages(new String[] {PKG_O});
        mZenModeHelper.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelper.mConsolidatedPolicy = new Policy(0, 0, 0, 0, 0, 0);
        mZenModeHelper.applyRestrictions();

        for (int usage : AudioAttributes.getSdkUsages()) {
            verify(mAppOps).setRestriction(
                    eq(AppOpsManager.OP_PLAY_AUDIO), eq(usage), anyInt(), eq(new String[]{PKG_O}));
            verify(mAppOps).setRestriction(
                    eq(AppOpsManager.OP_VIBRATE), eq(usage), anyInt(), eq(new String[]{PKG_O}));
        }
    }

    @Test
    public void testApplyRestrictions_whitelist_alarmsOnlyMode() {
        mZenModeHelper.setPriorityOnlyDndExemptPackages(new String[] {PKG_O});
        mZenModeHelper.mZenMode = Global.ZEN_MODE_ALARMS;
        mZenModeHelper.mConsolidatedPolicy = new Policy(0, 0, 0, 0, 0, 0);
        mZenModeHelper.applyRestrictions();

        for (int usage : AudioAttributes.getSdkUsages()) {
            verify(mAppOps).setRestriction(
                    eq(AppOpsManager.OP_PLAY_AUDIO), eq(usage), anyInt(), eq(null));
            verify(mAppOps).setRestriction(
                    eq(AppOpsManager.OP_VIBRATE), eq(usage), anyInt(), eq(null));
        }
    }

    @Test
    public void testApplyRestrictions_whitelist_totalSilenceMode() {
        mZenModeHelper.setPriorityOnlyDndExemptPackages(new String[] {PKG_O});
        mZenModeHelper.mZenMode = Global.ZEN_MODE_NO_INTERRUPTIONS;
        mZenModeHelper.mConsolidatedPolicy = new Policy(0, 0, 0, 0, 0, 0);
        mZenModeHelper.applyRestrictions();

        for (int usage : AudioAttributes.getSdkUsages()) {
            verify(mAppOps).setRestriction(
                    eq(AppOpsManager.OP_PLAY_AUDIO), eq(usage), anyInt(), eq(null));
            verify(mAppOps).setRestriction(
                    eq(AppOpsManager.OP_VIBRATE), eq(usage), anyInt(), eq(null));
        }
    }

    @Test
    public void testZenUpgradeNotification() {
        /**
         * Commit a485ec65b5ba947d69158ad90905abf3310655cf disabled DND status change
         * notification on watches. So, assume that the device is not watch.
         */
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)).thenReturn(false);

        // shows zen upgrade notification if stored settings says to shows,
        // zen has not been updated, boot is completed
        // and we're setting zen mode on
        Settings.Secure.putInt(mContentResolver, Settings.Secure.SHOW_ZEN_UPGRADE_NOTIFICATION, 1);
        Settings.Secure.putInt(mContentResolver, Settings.Secure.ZEN_SETTINGS_UPDATED, 0);
        mZenModeHelper.mIsSystemServicesReady = true;
        mZenModeHelper.mConsolidatedPolicy = new Policy(0, 0, 0, 0, 0, 0);
        mZenModeHelper.setZenModeSetting(ZEN_MODE_IMPORTANT_INTERRUPTIONS);

        verify(mNotificationManager, times(1)).notify(eq(ZenModeHelper.TAG),
                eq(SystemMessage.NOTE_ZEN_UPGRADE), any());
        assertEquals(0, Settings.Secure.getInt(mContentResolver,
                Settings.Secure.SHOW_ZEN_UPGRADE_NOTIFICATION, -1));
    }

    @Test
    public void testNoZenUpgradeNotification() {
        // doesn't show upgrade notification if stored settings says don't show
        Settings.Secure.putInt(mContentResolver, Settings.Secure.SHOW_ZEN_UPGRADE_NOTIFICATION, 0);
        Settings.Secure.putInt(mContentResolver, Settings.Secure.ZEN_SETTINGS_UPDATED, 0);
        mZenModeHelper.mIsSystemServicesReady = true;
        mZenModeHelper.setZenModeSetting(ZEN_MODE_IMPORTANT_INTERRUPTIONS);

        verify(mNotificationManager, never()).notify(eq(ZenModeHelper.TAG),
                eq(SystemMessage.NOTE_ZEN_UPGRADE), any());
    }

    @Test
    public void testNoZenUpgradeNotificationZenUpdated() {
        // doesn't show upgrade notification since zen was already updated
        Settings.Secure.putInt(mContentResolver, Settings.Secure.SHOW_ZEN_UPGRADE_NOTIFICATION, 0);
        Settings.Secure.putInt(mContentResolver, Settings.Secure.ZEN_SETTINGS_UPDATED, 1);
        mZenModeHelper.mIsSystemServicesReady = true;
        mZenModeHelper.setZenModeSetting(ZEN_MODE_IMPORTANT_INTERRUPTIONS);

        verify(mNotificationManager, never()).notify(eq(ZenModeHelper.TAG),
                eq(SystemMessage.NOTE_ZEN_UPGRADE), any());
    }

    @Test
    public void testZenSetInternalRinger_AllPriorityNotificationSoundsMuted() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelper.mAudioManager = mAudioManager;
        Global.putString(mContext.getContentResolver(), Global.ZEN_MODE_RINGER_LEVEL,
                Integer.toString(AudioManager.RINGER_MODE_NORMAL));

        // 1. Current ringer is normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        // Set zen to priority-only with all notification sounds muted (so ringer will be muted)
        mZenModeHelper.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelper.mConfig.allowReminders = false;
        mZenModeHelper.mConfig.allowCalls = false;
        mZenModeHelper.mConfig.allowMessages = false;
        mZenModeHelper.mConfig.allowEvents = false;
        mZenModeHelper.mConfig.allowRepeatCallers = false;
        mZenModeHelper.mConfig.allowConversations = false;

        // 2. apply priority only zen - verify ringer is unchanged
        mZenModeHelper.applyZenToRingerMode();
        verify(mAudioManager, never()).setRingerModeInternal(AudioManager.RINGER_MODE_SILENT,
                mZenModeHelper.TAG);

        // 3. apply zen off - verify zen is set to previous ringer (normal)
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        mZenModeHelper.mZenMode = ZEN_MODE_OFF;
        mZenModeHelper.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelper.TAG);
    }

    @Test
    public void testRingerAffectedStreamsTotalSilence() {
        // in total silence:
        // ringtone, notification, system, alarm, streams, music are affected by ringer mode
        mZenModeHelper.mZenMode = Settings.Global.ZEN_MODE_NO_INTERRUPTIONS;
        ZenModeHelper.RingerModeDelegate ringerModeDelegate =
                mZenModeHelper.new RingerModeDelegate();
        int ringerModeAffectedStreams = ringerModeDelegate.getRingerModeAffectedStreams(0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_RING)) != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_NOTIFICATION))
                != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_SYSTEM)) != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_ALARM)) != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_MUSIC)) != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_ASSISTANT)) != 0);
    }

    @Test
    public void testRingerAffectedStreamsPriorityOnly() {
        // in priority only mode:
        // ringtone, notification and system streams are affected by ringer mode
        mZenModeHelper.mConfig.allowAlarms = true;
        mZenModeHelper.mConfig.allowReminders = true;
        mZenModeHelper.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        ZenModeHelper.RingerModeDelegate ringerModeDelegateRingerMuted =
                mZenModeHelper.new RingerModeDelegate();

        int ringerModeAffectedStreams =
                ringerModeDelegateRingerMuted.getRingerModeAffectedStreams(0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_RING)) != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_NOTIFICATION))
                != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_SYSTEM)) != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_ALARM)) == 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_MUSIC)) == 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_ASSISTANT)) == 0);

        // even when ringer is muted (since all ringer sounds cannot bypass DND),
        // system stream is still affected by ringer mode
        mZenModeHelper.mConfig.allowSystem = false;
        mZenModeHelper.mConfig.allowReminders = false;
        mZenModeHelper.mConfig.allowCalls = false;
        mZenModeHelper.mConfig.allowMessages = false;
        mZenModeHelper.mConfig.allowEvents = false;
        mZenModeHelper.mConfig.allowRepeatCallers = false;
        mZenModeHelper.mConfig.allowConversations = false;
        ZenModeHelper.RingerModeDelegate ringerModeDelegateRingerNotMuted =
                mZenModeHelper.new RingerModeDelegate();

        int ringerMutedRingerModeAffectedStreams =
                ringerModeDelegateRingerNotMuted.getRingerModeAffectedStreams(0);
        assertTrue((ringerMutedRingerModeAffectedStreams & (1 << AudioSystem.STREAM_RING)) != 0);
        assertTrue((ringerMutedRingerModeAffectedStreams & (1 << AudioSystem.STREAM_NOTIFICATION))
                != 0);
        assertTrue((ringerMutedRingerModeAffectedStreams & (1 << AudioSystem.STREAM_SYSTEM))
                != 0);
        assertTrue((ringerMutedRingerModeAffectedStreams & (1 << AudioSystem.STREAM_ALARM)) == 0);
        assertTrue((ringerMutedRingerModeAffectedStreams & (1 << AudioSystem.STREAM_MUSIC)) == 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_ASSISTANT)) == 0);
    }

    @Test
    public void testZenSetInternalRinger_NotAllPriorityNotificationSoundsMuted_StartNormal() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelper.mAudioManager = mAudioManager;
        Global.putString(mContext.getContentResolver(), Global.ZEN_MODE_RINGER_LEVEL,
                Integer.toString(AudioManager.RINGER_MODE_NORMAL));

        // 1. Current ringer is normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        mZenModeHelper.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelper.mConfig.allowReminders = true;

        // 2. apply priority only zen - verify ringer is normal
        mZenModeHelper.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelper.TAG);

        // 3.  apply zen off - verify ringer remains normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        mZenModeHelper.mZenMode = ZEN_MODE_OFF;
        mZenModeHelper.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelper.TAG);
    }

    @Test
    public void testZenSetInternalRinger_NotAllPriorityNotificationSoundsMuted_StartSilent() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelper.mAudioManager = mAudioManager;
        Global.putString(mContext.getContentResolver(), Global.ZEN_MODE_RINGER_LEVEL,
                Integer.toString(AudioManager.RINGER_MODE_SILENT));

        // 1. Current ringer is silent
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        mZenModeHelper.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelper.mConfig.allowReminders = true;

        // 2. apply priority only zen - verify ringer is silent
        mZenModeHelper.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_SILENT,
                mZenModeHelper.TAG);

        // 3. apply zen-off - verify ringer is still silent
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        mZenModeHelper.mZenMode = ZEN_MODE_OFF;
        mZenModeHelper.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_SILENT,
                mZenModeHelper.TAG);
    }

    @Test
    public void testZenSetInternalRinger_NotAllPriorityNotificationSoundsMuted_RingerChanges() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelper.mAudioManager = mAudioManager;
        Global.putString(mContext.getContentResolver(), Global.ZEN_MODE_RINGER_LEVEL,
                Integer.toString(AudioManager.RINGER_MODE_NORMAL));

        // 1. Current ringer is normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        // Set zen to priority-only with all notification sounds muted (so ringer will be muted)
        mZenModeHelper.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelper.mConfig.allowReminders = true;

        // 2. apply priority only zen - verify zen will still be normal
        mZenModeHelper.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelper.TAG);

        // 3. change ringer from normal to silent, verify previous ringer set to new ringer (silent)
        ZenModeHelper.RingerModeDelegate ringerModeDelegate =
                mZenModeHelper.new RingerModeDelegate();
        ringerModeDelegate.onSetRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                AudioManager.RINGER_MODE_SILENT, "test", AudioManager.RINGER_MODE_NORMAL,
                VolumePolicy.DEFAULT);
        assertEquals(AudioManager.RINGER_MODE_SILENT, Global.getInt(mContext.getContentResolver(),
                Global.ZEN_MODE_RINGER_LEVEL, AudioManager.RINGER_MODE_NORMAL));

        // 4.  apply zen off - verify ringer still silenced
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        mZenModeHelper.mZenMode = ZEN_MODE_OFF;
        mZenModeHelper.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_SILENT,
                mZenModeHelper.TAG);
    }

    @Test
    public void testSilentRingerSavedInZenOff_startsZenOff() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelper.mConfig = new ZenModeConfig();
        mZenModeHelper.mAudioManager = mAudioManager;

        // apply zen off multiple times - verify ringer is not set to normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        mZenModeHelper.mZenMode = ZEN_MODE_OFF;
        mZenModeHelper.mConfig = null; // will evaluate config to zen mode off
        for (int i = 0; i < 3; i++) {
            // if zen doesn't change, zen should not reapply itself to the ringer
            mZenModeHelper.evaluateZenModeLocked(UPDATE_ORIGIN_UNKNOWN, "test", true);
        }
        verify(mAudioManager, never()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelper.TAG);
    }

    @Test
    public void testSilentRingerSavedOnZenOff_startsZenOn() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelper.mAudioManager = mAudioManager;
        mZenModeHelper.mZenMode = ZEN_MODE_OFF;
        mZenModeHelper.mConfig = new ZenModeConfig();

        // previously set silent ringer
        ZenModeHelper.RingerModeDelegate ringerModeDelegate =
                mZenModeHelper.new RingerModeDelegate();
        ringerModeDelegate.onSetRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                AudioManager.RINGER_MODE_SILENT, "test", AudioManager.RINGER_MODE_NORMAL,
                VolumePolicy.DEFAULT);
        assertEquals(AudioManager.RINGER_MODE_SILENT, Global.getInt(mContext.getContentResolver(),
                Global.ZEN_MODE_RINGER_LEVEL, AudioManager.RINGER_MODE_NORMAL));

        // apply zen off multiple times - verify ringer is not set to normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        mZenModeHelper.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        for (int i = 0; i < 3; i++) {
            // if zen doesn't change, zen should not reapply itself to the ringer
            mZenModeHelper.evaluateZenModeLocked(UPDATE_ORIGIN_UNKNOWN, "test", true);
        }
        verify(mAudioManager, never()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelper.TAG);
    }

    @Test
    public void testVibrateRingerSavedOnZenOff_startsZenOn() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelper.mAudioManager = mAudioManager;
        mZenModeHelper.mZenMode = ZEN_MODE_OFF;
        mZenModeHelper.mConfig = new ZenModeConfig();

        // previously set silent ringer
        ZenModeHelper.RingerModeDelegate ringerModeDelegate =
                mZenModeHelper.new RingerModeDelegate();
        ringerModeDelegate.onSetRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                AudioManager.RINGER_MODE_VIBRATE, "test", AudioManager.RINGER_MODE_NORMAL,
                VolumePolicy.DEFAULT);
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, Global.getInt(mContext.getContentResolver(),
                Global.ZEN_MODE_RINGER_LEVEL, AudioManager.RINGER_MODE_NORMAL));

        // apply zen off multiple times - verify ringer is not set to normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);
        mZenModeHelper.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        for (int i = 0; i < 3; i++) {
            // if zen doesn't change, zen should not reapply itself to the ringer
            mZenModeHelper.evaluateZenModeLocked(UPDATE_ORIGIN_UNKNOWN, "test", true);
        }
        verify(mAudioManager, never()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelper.TAG);
    }

    @Test
    public void testSetConfig_updatesAudioEventually() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelper.mAudioManager = mAudioManager;
        setupZenConfig();

        // Turn manual zen mode on
        mZenModeHelper.setManualZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null, UPDATE_ORIGIN_APP,
                null, "test", CUSTOM_PKG_UID);

        // audio manager shouldn't do anything until the handler processes its messages
        verify(mAudioManager, never()).updateRingerModeAffectedStreamsInternal();

        // now process the looper's messages
        mTestableLooper.processAllMessages();

        // Expect calls to audio manager
        verify(mAudioManager, times(1)).updateRingerModeAffectedStreamsInternal();

        // called during applyZenToRingerMode(), which should be true since zen changed
        verify(mAudioManager, atLeastOnce()).getRingerModeInternal();
    }

    @Test
    public void testParcelConfig() {
        mZenModeHelper.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelper.mConfig.allowAlarms = false;
        mZenModeHelper.mConfig.allowMedia = false;
        mZenModeHelper.mConfig.allowSystem = false;
        mZenModeHelper.mConfig.allowReminders = true;
        mZenModeHelper.mConfig.allowCalls = true;
        mZenModeHelper.mConfig.allowMessages = true;
        mZenModeHelper.mConfig.allowEvents = true;
        mZenModeHelper.mConfig.allowRepeatCallers = true;
        mZenModeHelper.mConfig.allowConversations = true;
        mZenModeHelper.mConfig.allowConversationsFrom = ZenPolicy.CONVERSATION_SENDERS_ANYONE;
        mZenModeHelper.mConfig.suppressedVisualEffects = SUPPRESSED_EFFECT_BADGE;
        mZenModeHelper.mConfig.manualRule = new ZenModeConfig.ZenRule();
        mZenModeHelper.mConfig.manualRule.component = new ComponentName("a", "a");
        mZenModeHelper.mConfig.manualRule.enabled = true;
        mZenModeHelper.mConfig.manualRule.snoozing = true;

        ZenModeConfig actual = mZenModeHelper.mConfig.copy();

        assertEquals(mZenModeHelper.mConfig, actual);
    }

    @Test
    public void testWriteXml() throws Exception {
        mZenModeHelper.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelper.mConfig.allowAlarms = false;
        mZenModeHelper.mConfig.allowMedia = false;
        mZenModeHelper.mConfig.allowSystem = false;
        mZenModeHelper.mConfig.allowReminders = true;
        mZenModeHelper.mConfig.allowCalls = true;
        mZenModeHelper.mConfig.allowMessages = true;
        mZenModeHelper.mConfig.allowEvents = true;
        mZenModeHelper.mConfig.allowRepeatCallers = true;
        mZenModeHelper.mConfig.allowConversations = true;
        mZenModeHelper.mConfig.allowConversationsFrom = ZenPolicy.CONVERSATION_SENDERS_ANYONE;
        mZenModeHelper.mConfig.suppressedVisualEffects = SUPPRESSED_EFFECT_BADGE;
        mZenModeHelper.mConfig.manualRule = new ZenModeConfig.ZenRule();
        mZenModeHelper.mConfig.manualRule.zenMode =
                ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelper.mConfig.manualRule.component = new ComponentName("a", "a");
        mZenModeHelper.mConfig.manualRule.pkg = "a";
        mZenModeHelper.mConfig.manualRule.enabled = true;

        ZenModeConfig expected = mZenModeHelper.mConfig.copy();

        ByteArrayOutputStream baos = writeXmlAndPurge(null);
        TypedXmlPullParser parser = getParserForByteStream(baos);
        mZenModeHelper.readXml(parser, false, UserHandle.USER_ALL);

        assertEquals("Config mismatch: current vs expected: "
                + new ZenModeDiff.ConfigDiff(mZenModeHelper.mConfig, expected), expected,
                mZenModeHelper.mConfig);
    }

    @Test
    public void testProto() throws InvalidProtocolBufferException {
        mZenModeHelper.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        // existence of manual rule means it should be in output
        mZenModeHelper.mConfig.manualRule = new ZenModeConfig.ZenRule();
        mZenModeHelper.mConfig.manualRule.pkg = "android";  // system
        mZenModeHelper.mConfig.automaticRules = new ArrayMap<>(); // no automatic rules

        List<String> ids = new ArrayList<>();
        ids.add(ZenModeConfig.MANUAL_RULE_ID);
        ids.add(""); // for ROOT_CONFIG, logged with empty string as id

        List<StatsEvent> events = new LinkedList<>();
        mZenModeHelper.pullRules(events);
        assertEquals(2, events.size());  // manual rule + root config
        for (StatsEvent ev : events) {
            AtomsProto.Atom atom = StatsEventTestUtils.convertToAtom(ev);
            assertTrue(atom.hasDndModeRule());
            DNDModeProto cfg = atom.getDndModeRule();
            // Additional check for ID to clearly identify the root config because there's some
            // odd behavior in the test util around enum value of 0 (the usual default, but not in
            // this case).
            if (cfg.getZenMode().getNumber() == ROOT_CONFIG && cfg.getId().equals("")) {
                assertTrue(cfg.getEnabled());
                assertFalse(cfg.getChannelsBypassing());
            }
            assertEquals(Process.SYSTEM_UID, cfg.getUid());
            String name = cfg.getId();
            assertTrue("unexpected rule id", ids.contains(name));
            ids.remove(name);
        }
        assertEquals("extra rule in output", 0, ids.size());
    }

    @Test
    public void testProtoWithAutoRule() throws Exception {
        setupZenConfig();
        // one enabled automatic rule. we use a non-usual zen mode value (though it has to be
        // a real one in the enum because non-valid enum values are reverted to default).
        mZenModeHelper.mConfig.automaticRules = getCustomAutomaticRules(ZEN_MODE_ALARMS);

        List<StatsEvent> events = new LinkedList<>();
        mZenModeHelper.pullRules(events);

        boolean foundCustomEvent = false;
        for (StatsEvent ev : events) {
            AtomsProto.Atom atom = StatsEventTestUtils.convertToAtom(ev);
            assertTrue(atom.hasDndModeRule());
            DNDModeProto cfg = atom.getDndModeRule();
            if (cfg.getZenMode().getNumber() == ZEN_MODE_ALARMS) {
                foundCustomEvent = true;
                assertEquals(CUSTOM_PKG_UID, cfg.getUid());
                assertTrue(cfg.getEnabled());
            }
        }
        assertTrue("couldn't find custom rule", foundCustomEvent);
    }

    @Test
    public void testProtoWithDefaultAutoRules() throws Exception {
        setupZenConfig();
        // clear the automatic rules so we can reset to only the default rules
        mZenModeHelper.mConfig.automaticRules = new ArrayMap<>();

        // read in XML to restore the default rules
        ByteArrayOutputStream baos = writeXmlAndPurge(5);
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        mZenModeHelper.readXml(parser, false, UserHandle.USER_ALL);
        List<StatsEvent> events = new LinkedList<>();
        mZenModeHelper.pullRules(events);

        // list for tracking which ids we've seen in the pulled atom output
        List<String> ids = new ArrayList<>();
        ids.addAll(ZenModeConfig.DEFAULT_RULE_IDS);
        ids.add("");  // empty string for root config

        for (StatsEvent ev : events) {
            AtomsProto.Atom atom = StatsEventTestUtils.convertToAtom(ev);
            assertTrue(atom.hasDndModeRule());
            DNDModeProto cfg = atom.getDndModeRule();
            if (!ids.contains(cfg.getId())) {
                fail("unexpected ID found: " + cfg.getId());
            }
            ids.remove(cfg.getId());
        }
        assertEquals("default ID(s) not found", 0, ids.size());
    }

    @Test
    public void testProtoWithAutoRuleCustomPolicy_classic() throws Exception {
        setupZenConfig();
        // clear any automatic rules just to make sure
        mZenModeHelper.mConfig.automaticRules = new ArrayMap<>();

        // Add an automatic rule with a custom policy
        ZenRule rule = createCustomAutomaticRule(ZEN_MODE_IMPORTANT_INTERRUPTIONS, CUSTOM_RULE_ID);
        rule.zenPolicy = new ZenPolicy.Builder()
                .allowAlarms(true)
                .allowRepeatCallers(false)
                .allowCalls(PEOPLE_TYPE_STARRED)
                .build();
        mZenModeHelper.mConfig.automaticRules.put(rule.id, rule);
        List<StatsEvent> events = new LinkedList<>();
        mZenModeHelper.pullRules(events);

        boolean foundCustomEvent = false;
        for (StatsEvent ev : events) {
            AtomsProto.Atom atom = StatsEventTestUtils.convertToAtom(ev);
            assertTrue(atom.hasDndModeRule());
            DNDModeProto cfg = atom.getDndModeRule();
            if (cfg.getUid() == CUSTOM_PKG_UID) {
                foundCustomEvent = true;
                // Check that the pieces of the policy are applied.
                assertThat(cfg.hasPolicy()).isTrue();
                DNDPolicyProto policy = cfg.getPolicy();
                assertThat(policy.getAlarms().getNumber()).isEqualTo(DNDProtoEnums.STATE_ALLOW);
                assertThat(policy.getRepeatCallers().getNumber())
                        .isEqualTo(DNDProtoEnums.STATE_DISALLOW);
                assertThat(policy.getCalls().getNumber()).isEqualTo(DNDProtoEnums.STATE_ALLOW);
                assertThat(policy.getAllowCallsFrom().getNumber())
                        .isEqualTo(DNDProtoEnums.PEOPLE_STARRED);
            }
        }
        assertTrue("couldn't find custom rule", foundCustomEvent);
    }

    @Test
    public void testProtoWithAutoRuleCustomPolicy() throws Exception {
        // allowChannels is only valid under modes_api.
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);

        setupZenConfig();
        // clear any automatic rules just to make sure
        mZenModeHelper.mConfig.automaticRules = new ArrayMap<>();

        // Add an automatic rule with a custom policy
        ZenRule rule = createCustomAutomaticRule(ZEN_MODE_IMPORTANT_INTERRUPTIONS, CUSTOM_RULE_ID);
        rule.zenPolicy = new ZenPolicy.Builder()
                .allowAlarms(true)
                .allowRepeatCallers(false)
                .allowCalls(PEOPLE_TYPE_STARRED)
                .allowChannels(ZenPolicy.CHANNEL_TYPE_NONE)
                .build();
        mZenModeHelper.mConfig.automaticRules.put(rule.id, rule);
        List<StatsEvent> events = new LinkedList<>();
        mZenModeHelper.pullRules(events);

        boolean foundCustomEvent = false;
        for (StatsEvent ev : events) {
            AtomsProto.Atom atom = StatsEventTestUtils.convertToAtom(ev);
            assertTrue(atom.hasDndModeRule());
            DNDModeProto cfg = atom.getDndModeRule();
            if (cfg.getUid() == CUSTOM_PKG_UID) {
                foundCustomEvent = true;
                // Check that the pieces of the policy are applied.
                assertThat(cfg.hasPolicy()).isTrue();
                DNDPolicyProto policy = cfg.getPolicy();
                assertThat(policy.getAlarms().getNumber()).isEqualTo(DNDProtoEnums.STATE_ALLOW);
                assertThat(policy.getRepeatCallers().getNumber())
                        .isEqualTo(DNDProtoEnums.STATE_DISALLOW);
                assertThat(policy.getCalls().getNumber()).isEqualTo(DNDProtoEnums.STATE_ALLOW);
                assertThat(policy.getAllowCallsFrom().getNumber())
                        .isEqualTo(DNDProtoEnums.PEOPLE_STARRED);
                assertThat(policy.getAllowChannels().getNumber())
                        .isEqualTo(DNDProtoEnums.CHANNEL_TYPE_NONE);
            }
        }
        assertTrue("couldn't find custom rule", foundCustomEvent);
    }

    @Test
    public void ruleUidsCached() throws Exception {
        setupZenConfig();
        // one enabled automatic rule
        mZenModeHelper.mConfig.automaticRules = getCustomAutomaticRules();
        List<StatsEvent> events = new LinkedList<>();
        // first time retrieving uid:
        mZenModeHelper.pullRules(events);
        verify(mPackageManager, atLeastOnce()).getPackageUidAsUser(anyString(), anyInt());

        // second time retrieving uid:
        reset(mPackageManager);
        mZenModeHelper.pullRules(events);
        verify(mPackageManager, never()).getPackageUidAsUser(anyString(), anyInt());

        // new rule from same package + user added
        reset(mPackageManager);
        ZenModeConfig.ZenRule rule = createCustomAutomaticRule(ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                CUSTOM_RULE_ID + "2");
        mZenModeHelper.mConfig.automaticRules.put(rule.id, rule);
        mZenModeHelper.pullRules(events);
        verify(mPackageManager, never()).getPackageUidAsUser(anyString(), anyInt());
    }

    @Test
    public void ruleUidAutomaticZenRuleRemovedUpdatesCache() throws Exception {
        when(mContext.checkCallingPermission(anyString()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        setupZenConfig();
        // one enabled automatic rule
        mZenModeHelper.mConfig.automaticRules = getCustomAutomaticRules();
        List<StatsEvent> events = new LinkedList<>();

        mZenModeHelper.pullRules(events);
        mZenModeHelper.removeAutomaticZenRule(CUSTOM_RULE_ID, UPDATE_ORIGIN_APP, "test",
                CUSTOM_PKG_UID);
        assertTrue(-1
                == mZenModeHelper.mRulesUidCache.getOrDefault(CUSTOM_PKG_NAME + "|" + 0, -1));
    }

    @Test
    public void testProtoRedactsIds() throws Exception {
        setupZenConfig();
        // one enabled automatic rule
        mZenModeHelper.mConfig.automaticRules = getCustomAutomaticRules();

        List<StatsEvent> events = new LinkedList<>();
        mZenModeHelper.pullRules(events);

        for (StatsEvent ev : events) {
            AtomsProto.Atom atom = StatsEventTestUtils.convertToAtom(ev);
            assertTrue(atom.hasDndModeRule());
            DNDModeProto cfg = atom.getDndModeRule();
            if ("customRule".equals(cfg.getId())) {
                fail("non-default IDs should be redacted");
            }
        }
    }

    @Test
    public void testProtoWithManualRule() throws Exception {
        setupZenConfig();
        mZenModeHelper.mConfig.automaticRules = getCustomAutomaticRules();
        mZenModeHelper.mConfig.manualRule = new ZenModeConfig.ZenRule();
        mZenModeHelper.mConfig.manualRule.enabled = true;

        List<StatsEvent> events = new LinkedList<>();
        mZenModeHelper.pullRules(events);

        boolean foundManualRule = false;
        for (StatsEvent ev : events) {
            AtomsProto.Atom atom = StatsEventTestUtils.convertToAtom(ev);
            assertTrue(atom.hasDndModeRule());
            DNDModeProto cfg = atom.getDndModeRule();
            if (ZenModeConfig.MANUAL_RULE_ID.equals(cfg.getId())) {
                assertEquals(0, cfg.getUid());
                foundManualRule = true;
            }
        }
        assertTrue("couldn't find manual rule", foundManualRule);
    }

    @Test
    public void testWriteXml_onlyBackupsTargetUser() throws Exception {
        // Setup configs for user 10 and 11.
        setupZenConfig();
        ZenModeConfig config10 = mZenModeHelper.mConfig.copy();
        config10.user = 10;
        config10.allowAlarms = true;
        config10.allowMedia = true;
        mZenModeHelper.setConfig(config10, null, UPDATE_ORIGIN_INIT, "writeXml",
                Process.SYSTEM_UID);
        ZenModeConfig config11 = mZenModeHelper.mConfig.copy();
        config11.user = 11;
        config11.allowAlarms = false;
        config11.allowMedia = false;
        mZenModeHelper.setConfig(config11, null, UPDATE_ORIGIN_INIT, "writeXml",
                Process.SYSTEM_UID);

        // Backup user 10 and reset values.
        ByteArrayOutputStream baos = writeXmlAndPurgeForUser(null, 10);
        ZenModeConfig newConfig11 = new ZenModeConfig();
        newConfig11.user = 11;
        mZenModeHelper.mConfigs.put(11, newConfig11);

        // Parse backup data.
        TypedXmlPullParser parser = getParserForByteStream(baos);
        mZenModeHelper.readXml(parser, true, 10);
        mZenModeHelper.readXml(parser, true, 11);

        ZenModeConfig actual = mZenModeHelper.mConfigs.get(10);
        assertEquals(
                "Config mismatch: current vs expected: "
                        + new ZenModeDiff.ConfigDiff(actual, config10), config10, actual);
        assertNotEquals("Expected config mismatch", config11, mZenModeHelper.mConfigs.get(11));
    }

    @Test
    public void testReadXmlRestore_forSystemUser() throws Exception {
        setupZenConfig();
        // one enabled automatic rule
        mZenModeHelper.mConfig.automaticRules = getCustomAutomaticRules();
        ZenModeConfig original = mZenModeHelper.mConfig.copy();

        ByteArrayOutputStream baos = writeXmlAndPurgeForUser(null, UserHandle.USER_SYSTEM);
        TypedXmlPullParser parser = getParserForByteStream(baos);
        mZenModeHelper.readXml(parser, true, UserHandle.USER_SYSTEM);

        assertEquals("Config mismatch: current vs original: "
                + new ZenModeDiff.ConfigDiff(mZenModeHelper.mConfig, original),
                original, mZenModeHelper.mConfig);
        assertEquals(original.hashCode(), mZenModeHelper.mConfig.hashCode());
    }

    /** Restore should ignore the data's user id and restore for the target user. */
    @Test
    public void testReadXmlRestore_forNonSystemUser() throws Exception {
        // Setup config.
        setupZenConfig();
        mZenModeHelper.mConfig.automaticRules = getCustomAutomaticRules();
        ZenModeConfig expected = mZenModeHelper.mConfig.copy();

        // Backup data for user 0.
        ByteArrayOutputStream baos = writeXmlAndPurgeForUser(null, UserHandle.USER_SYSTEM);

        // Restore data for user 10.
        TypedXmlPullParser parser = getParserForByteStream(baos);
        mZenModeHelper.readXml(parser, true, 10);

        ZenModeConfig actual = mZenModeHelper.mConfigs.get(10);
        expected.user = 10;
        assertEquals("Config mismatch: current vs original: "
                        + new ZenModeDiff.ConfigDiff(actual, expected),
                expected, actual);
        assertEquals(expected.hashCode(), actual.hashCode());
        expected.user = 0;
        assertNotEquals(expected, mZenModeHelper.mConfig);
    }

    @Test
    public void testWriteXmlWithZenPolicy() throws Exception {
        final String ruleId = "customRule";
        setupZenConfig();

        // one enabled automatic rule with zen policy
        ArrayMap<String, ZenModeConfig.ZenRule> automaticRules = new ArrayMap<>();
        ZenModeConfig.ZenRule customRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo customRuleInfo = new ScheduleInfo();
        customRule.enabled = true;
        customRule.creationTime = 0;
        customRule.id = "customRule";
        customRule.name = "Custom Rule";
        customRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        customRule.conditionId = ZenModeConfig.toScheduleConditionId(customRuleInfo);
        customRule.configurationActivity =
                new ComponentName("android", "ScheduleConditionProvider");
        customRule.pkg = customRule.configurationActivity.getPackageName();
        customRule.zenPolicy = new ZenPolicy.Builder()
                .allowAlarms(false)
                .allowMedia(false)
                .allowRepeatCallers(false)
                .allowCalls(ZenPolicy.PEOPLE_TYPE_NONE)
                .allowMessages(PEOPLE_TYPE_CONTACTS)
                .allowEvents(true)
                .allowReminders(false)
                .build();
        automaticRules.put("customRule", customRule);
        mZenModeHelper.mConfig.automaticRules = automaticRules;

        ZenModeConfig expected = mZenModeHelper.mConfig.copy();

        ByteArrayOutputStream baos = writeXmlAndPurge(null);
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        mZenModeHelper.readXml(parser, false, UserHandle.USER_ALL);

        ZenModeConfig.ZenRule original = expected.automaticRules.get(ruleId);
        ZenModeConfig.ZenRule current = mZenModeHelper.mConfig.automaticRules.get(ruleId);

        assertEquals("Automatic rules mismatch", original, current);
    }

    @Test
    public void testReadXmlRestoreWithZenPolicy_forSystemUser() throws Exception {
        final String ruleId = "customRule";
        setupZenConfig();

        // one enabled automatic rule with zen policy
        ArrayMap<String, ZenModeConfig.ZenRule> automaticRules = new ArrayMap<>();
        ZenModeConfig.ZenRule customRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo customRuleInfo = new ScheduleInfo();
        customRule.enabled = true;
        customRule.creationTime = 0;
        customRule.id = ruleId;
        customRule.name = "Custom Rule";
        customRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        customRule.conditionId = ZenModeConfig.toScheduleConditionId(customRuleInfo);
        customRule.configurationActivity =
                new ComponentName("android", "ScheduleConditionProvider");
        customRule.pkg = customRule.configurationActivity.getPackageName();
        customRule.zenPolicy = new ZenPolicy.Builder()
                .allowSystem(true)
                .allowCalls(ZenPolicy.PEOPLE_TYPE_ANYONE)
                .allowReminders(true)
                .build();
        automaticRules.put(ruleId, customRule);
        mZenModeHelper.mConfig.automaticRules = automaticRules;

        ZenModeConfig expected = mZenModeHelper.mConfig.copy();

        ByteArrayOutputStream baos = writeXmlAndPurgeForUser(null, UserHandle.USER_SYSTEM);
        TypedXmlPullParser parser = getParserForByteStream(baos);
        mZenModeHelper.readXml(parser, true, UserHandle.USER_SYSTEM);

        ZenModeConfig.ZenRule original = expected.automaticRules.get(ruleId);
        ZenModeConfig.ZenRule current = mZenModeHelper.mConfig.automaticRules.get(ruleId);

        assertEquals("Automatic rules mismatch", original, current);
    }

    @Test
    public void testReadXmlRulesNotOverriden() throws Exception {
        setupZenConfig();

        // automatic zen rule is enabled on upgrade so rules should not be overriden to default
        ArrayMap<String, ZenModeConfig.ZenRule> enabledAutoRule = new ArrayMap<>();
        ZenModeConfig.ZenRule customRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo weeknights = new ScheduleInfo();
        customRule.enabled = true;
        customRule.name = "Custom Rule";
        customRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        customRule.conditionId = ZenModeConfig.toScheduleConditionId(weeknights);
        customRule.component = new ComponentName("android", "ScheduleConditionProvider");
        enabledAutoRule.put("customRule", customRule);
        mZenModeHelper.mConfig.automaticRules = enabledAutoRule;

        // set previous version
        ByteArrayOutputStream baos = writeXmlAndPurge(5);
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        mZenModeHelper.readXml(parser, false, UserHandle.USER_ALL);

        assertTrue(mZenModeHelper.mConfig.automaticRules.containsKey("customRule"));
        setupZenConfigMaintained();
    }

    @Test
    public void testMigrateSuppressedVisualEffects_oneExistsButOff() throws Exception {
        String xml = "<zen version=\"6\" user=\"0\">\n"
                + "<allow calls=\"false\" repeatCallers=\"false\" messages=\"true\" "
                + "reminders=\"false\" events=\"false\" callsFrom=\"1\" messagesFrom=\"2\" "
                + "visualScreenOff=\"true\" alarms=\"true\" "
                + "media=\"true\" system=\"false\" />\n"
                + "<disallow visualEffects=\"511\" />"
                + "</zen>";

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.getBytes())), null);
        parser.nextTag();
        mZenModeHelper.readXml(parser, false, UserHandle.USER_ALL);

        assertEquals(0, mZenModeHelper.mConfig.suppressedVisualEffects);

        xml = "<zen version=\"6\" user=\"0\">\n"
                + "<allow calls=\"false\" repeatCallers=\"false\" messages=\"true\" "
                + "reminders=\"false\" events=\"false\" callsFrom=\"1\" messagesFrom=\"2\" "
                + "visualScreenOn=\"true\" alarms=\"true\" "
                + "media=\"true\" system=\"false\" />\n"
                + "<disallow visualEffects=\"511\" />"
                + "</zen>";

        parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.getBytes())), null);
        parser.nextTag();
        mZenModeHelper.readXml(parser, false, UserHandle.USER_ALL);

        assertEquals(0, mZenModeHelper.mConfig.suppressedVisualEffects);
    }

    @Test
    public void testMigrateSuppressedVisualEffects_bothExistButOff() throws Exception {
        String xml = "<zen version=\"6\" user=\"0\">\n"
                + "<allow calls=\"false\" repeatCallers=\"false\" messages=\"true\" "
                + "reminders=\"false\" events=\"false\" callsFrom=\"1\" messagesFrom=\"2\" "
                + "visualScreenOff=\"true\" visualScreenOn=\"true\" alarms=\"true\" "
                + "media=\"true\" system=\"false\" />\n"
                + "<disallow visualEffects=\"511\" />"
                + "</zen>";

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.getBytes())), null);
        parser.nextTag();
        mZenModeHelper.readXml(parser, false, UserHandle.USER_ALL);

        assertEquals(0, mZenModeHelper.mConfig.suppressedVisualEffects);
    }

    @Test
    public void testMigrateSuppressedVisualEffects_bothExistButOn() throws Exception {
        String xml = "<zen version=\"6\" user=\"0\">\n"
                + "<allow calls=\"false\" repeatCallers=\"false\" messages=\"true\" "
                + "reminders=\"false\" events=\"false\" callsFrom=\"1\" messagesFrom=\"2\" "
                + "visualScreenOff=\"false\" visualScreenOn=\"false\" alarms=\"true\" "
                + "media=\"true\" system=\"false\" />\n"
                + "<disallow visualEffects=\"511\" />"
                + "</zen>";

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.getBytes())), null);
        parser.nextTag();
        mZenModeHelper.readXml(parser, false, UserHandle.USER_ALL);

        assertEquals(SUPPRESSED_EFFECT_FULL_SCREEN_INTENT
                        | SUPPRESSED_EFFECT_LIGHTS
                        | SUPPRESSED_EFFECT_AMBIENT
                        | SUPPRESSED_EFFECT_PEEK,
                mZenModeHelper.mConfig.suppressedVisualEffects);

        xml = "<zen version=\"6\" user=\"0\">\n"
                + "<allow calls=\"false\" repeatCallers=\"false\" messages=\"true\" "
                + "reminders=\"false\" events=\"false\" callsFrom=\"1\" messagesFrom=\"2\" "
                + "visualScreenOff=\"true\" visualScreenOn=\"false\" alarms=\"true\" "
                + "media=\"true\" system=\"false\" />\n"
                + "<disallow visualEffects=\"511\" />"
                + "</zen>";

        parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.getBytes())), null);
        parser.nextTag();
        mZenModeHelper.readXml(parser, false, UserHandle.USER_ALL);

        assertEquals(SUPPRESSED_EFFECT_PEEK, mZenModeHelper.mConfig.suppressedVisualEffects);

        xml = "<zen version=\"6\" user=\"0\">\n"
                + "<allow calls=\"false\" repeatCallers=\"false\" messages=\"true\" "
                + "reminders=\"false\" events=\"false\" callsFrom=\"1\" messagesFrom=\"2\" "
                + "visualScreenOff=\"false\" visualScreenOn=\"true\" alarms=\"true\" "
                + "media=\"true\" system=\"false\" />\n"
                + "<disallow visualEffects=\"511\" />"
                + "</zen>";

        parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.getBytes())), null);
        parser.nextTag();
        mZenModeHelper.readXml(parser, false, UserHandle.USER_ALL);

        assertEquals(SUPPRESSED_EFFECT_FULL_SCREEN_INTENT
                        | SUPPRESSED_EFFECT_LIGHTS
                        | SUPPRESSED_EFFECT_AMBIENT,
                mZenModeHelper.mConfig.suppressedVisualEffects);
    }

    @Test
    public void testReadXmlResetDefaultRules() throws Exception {
        setupZenConfig();

        // no enabled automatic zen rules and no default rules
        // so rules should be overriden by default rules
        mZenModeHelper.mConfig.automaticRules = new ArrayMap<>();

        // set previous version
        ByteArrayOutputStream baos = writeXmlAndPurge(5);
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        mZenModeHelper.readXml(parser, false, UserHandle.USER_ALL);

        // check default rules
        ArrayMap<String, ZenModeConfig.ZenRule> rules = mZenModeHelper.mConfig.automaticRules;
        assertTrue(rules.size() != 0);
        for (String defaultId : ZenModeConfig.DEFAULT_RULE_IDS) {
            assertTrue(rules.containsKey(defaultId));
        }

        setupZenConfigMaintained();
    }


    @Test
    public void testReadXmlAllDisabledRulesResetDefaultRules() throws Exception {
        setupZenConfig();

        // all automatic zen rules are disabled on upgrade (and default rules don't already exist)
        // so rules should be overriden by default rules
        ArrayMap<String, ZenModeConfig.ZenRule> disabledAutoRule = new ArrayMap<>();
        ZenModeConfig.ZenRule customRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo weeknights = new ScheduleInfo();
        customRule.enabled = false;
        customRule.name = "Custom Rule";
        customRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        customRule.conditionId = ZenModeConfig.toScheduleConditionId(weeknights);
        customRule.component = new ComponentName("android", "ScheduleConditionProvider");
        disabledAutoRule.put("customRule", customRule);
        mZenModeHelper.mConfig.automaticRules = disabledAutoRule;

        // set previous version
        ByteArrayOutputStream baos = writeXmlAndPurge(5);
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        mZenModeHelper.readXml(parser, false, UserHandle.USER_ALL);

        // check default rules
        ArrayMap<String, ZenModeConfig.ZenRule> rules = mZenModeHelper.mConfig.automaticRules;
        assertTrue(rules.size() != 0);
        for (String defaultId : ZenModeConfig.DEFAULT_RULE_IDS) {
            assertTrue(rules.containsKey(defaultId));
        }
        assertFalse(rules.containsKey("customRule"));

        setupZenConfigMaintained();
    }

    @Test
    public void testReadXmlOnlyOneDefaultRuleExists() throws Exception {
        setupZenConfig();

        // all automatic zen rules are disabled on upgrade and only one default rule exists
        // so rules should be overriden to the default rules
        ArrayMap<String, ZenModeConfig.ZenRule> automaticRules = new ArrayMap<>();
        ZenModeConfig.ZenRule customRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo customRuleInfo = new ScheduleInfo();
        customRule.enabled = false;
        customRule.name = "Custom Rule";
        customRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        customRule.conditionId = ZenModeConfig.toScheduleConditionId(customRuleInfo);
        customRule.component = new ComponentName("android", "ScheduleConditionProvider");
        customRule.zenPolicy = new ZenPolicy.Builder()
                .allowReminders(true)
                .allowMessages(ZenPolicy.PEOPLE_TYPE_ANYONE)
                .build();
        automaticRules.put("customRule", customRule);

        ZenModeConfig.ZenRule defaultScheduleRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo defaultScheduleRuleInfo = new ScheduleInfo();
        defaultScheduleRule.enabled = false;
        defaultScheduleRule.name = "Default Schedule Rule";
        defaultScheduleRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        defaultScheduleRule.conditionId = ZenModeConfig.toScheduleConditionId(
                defaultScheduleRuleInfo);
        customRule.component = new ComponentName("android", "ScheduleConditionProvider");
        defaultScheduleRule.id = ZenModeConfig.EVERY_NIGHT_DEFAULT_RULE_ID;
        automaticRules.put(ZenModeConfig.EVERY_NIGHT_DEFAULT_RULE_ID, defaultScheduleRule);

        mZenModeHelper.mConfig.automaticRules = automaticRules;

        // set previous version
        ByteArrayOutputStream baos = writeXmlAndPurge(5);
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        mZenModeHelper.readXml(parser, false, UserHandle.USER_ALL);

        // check default rules
        ArrayMap<String, ZenModeConfig.ZenRule> rules = mZenModeHelper.mConfig.automaticRules;
        assertTrue(rules.size() != 0);
        for (String defaultId : ZenModeConfig.DEFAULT_RULE_IDS) {
            assertTrue(rules.containsKey(defaultId));
        }
        assertFalse(rules.containsKey("customRule"));

        setupZenConfigMaintained();
    }

    @Test
    public void testReadXmlDefaultRulesExist() throws Exception {
        setupZenConfig();

        // Default rules exist so rules should not be overridden by defaults
        ArrayMap<String, ZenModeConfig.ZenRule> automaticRules = new ArrayMap<>();
        ZenModeConfig.ZenRule customRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo customRuleInfo = new ScheduleInfo();
        customRule.enabled = false;
        customRule.name = "Custom Rule";
        customRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        customRule.conditionId = ZenModeConfig.toScheduleConditionId(customRuleInfo);
        customRule.component = new ComponentName("android", "ScheduleConditionProvider");
        customRule.zenPolicy = new ZenPolicy.Builder()
                .allowReminders(true)
                .allowMessages(ZenPolicy.PEOPLE_TYPE_ANYONE)
                .build();
        automaticRules.put("customRule", customRule);

        ZenModeConfig.ZenRule defaultScheduleRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo defaultScheduleRuleInfo = new ScheduleInfo();
        defaultScheduleRule.enabled = false;
        defaultScheduleRule.name = "Default Schedule Rule";
        defaultScheduleRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        defaultScheduleRule.conditionId = ZenModeConfig.toScheduleConditionId(
                defaultScheduleRuleInfo);
        defaultScheduleRule.id = ZenModeConfig.EVERY_NIGHT_DEFAULT_RULE_ID;
        defaultScheduleRule.zenPolicy = new ZenPolicy.Builder()
                .allowEvents(true)
                .allowMessages(ZenPolicy.PEOPLE_TYPE_ANYONE)
                .build();
        automaticRules.put(ZenModeConfig.EVERY_NIGHT_DEFAULT_RULE_ID, defaultScheduleRule);

        ZenModeConfig.ZenRule defaultEventRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo defaultEventRuleInfo = new ScheduleInfo();
        defaultEventRule.enabled = false;
        defaultEventRule.name = "Default Event Rule";
        defaultEventRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        defaultEventRule.conditionId = ZenModeConfig.toScheduleConditionId(
                defaultEventRuleInfo);
        defaultEventRule.id = ZenModeConfig.EVENTS_DEFAULT_RULE_ID;
        defaultScheduleRule.zenPolicy = new ZenPolicy.Builder()
                .allowAlarms(false)
                .allowMedia(false)
                .allowRepeatCallers(false)
                .build();
        automaticRules.put(ZenModeConfig.EVENTS_DEFAULT_RULE_ID, defaultEventRule);

        mZenModeHelper.mConfig.automaticRules = automaticRules;

        // set previous version
        ByteArrayOutputStream baos = writeXmlAndPurge(5);
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        mZenModeHelper.readXml(parser, false, UserHandle.USER_ALL);

        // check default rules
        ArrayMap<String, ZenModeConfig.ZenRule> rules = mZenModeHelper.mConfig.automaticRules;
        assertTrue(rules.size() != 0);
        for (String defaultId : ZenModeConfig.DEFAULT_RULE_IDS) {
            assertTrue(rules.containsKey(defaultId));
        }
        assertTrue(rules.containsKey("customRule"));

        setupZenConfigMaintained();

        List<StatsEvent> events = new LinkedList<>();
        mZenModeHelper.pullRules(events);
        assertEquals(4, events.size());
    }

    @Test
    public void testCountdownConditionSubscription() throws Exception {
        ZenModeConfig config = new ZenModeConfig();
        mZenModeHelper.mConfig = config;
        mZenModeHelper.mConditions.evaluateConfig(mZenModeHelper.mConfig, null, true);
        assertEquals(0, mZenModeHelper.mConditions.mSubscriptions.size());

        mZenModeHelper.mConfig.manualRule = new ZenModeConfig.ZenRule();
        Uri conditionId = ZenModeConfig.toCountdownConditionId(9000000, false);
        mZenModeHelper.mConfig.manualRule.conditionId = conditionId;
        mZenModeHelper.mConfig.manualRule.component = new ComponentName("android",
                CountdownConditionProvider.class.getName());
        mZenModeHelper.mConfig.manualRule.condition = new Condition(conditionId, "", "", "", 0,
                STATE_TRUE, Condition.FLAG_RELEVANT_NOW);
        mZenModeHelper.mConfig.manualRule.enabled = true;
        ZenModeConfig originalConfig = mZenModeHelper.mConfig.copy();

        mZenModeHelper.mConditions.evaluateConfig(mZenModeHelper.mConfig, null, true);

        assertEquals(true, ZenModeConfig.isValidCountdownConditionId(conditionId));
        assertEquals(originalConfig, mZenModeHelper.mConfig);
        assertEquals(1, mZenModeHelper.mConditions.mSubscriptions.size());
    }

    @Test
    public void testEmptyDefaultRulesMap() {
        List<StatsEvent> events = new LinkedList<>();
        ZenModeConfig config = new ZenModeConfig();
        config.automaticRules = new ArrayMap<>();
        mZenModeHelper.mConfig = config;
        mZenModeHelper.updateDefaultZenRules(Process.SYSTEM_UID); // shouldn't throw null pointer
        mZenModeHelper.pullRules(events); // shouldn't throw null pointer
    }

    @Test
    public void testDoNotUpdateModifiedDefaultAutoRule() {
        // mDefaultConfig is set to default config in setup by getDefaultConfigParser
        when(mContext.checkCallingPermission(anyString()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        // shouldn't update rule that's been modified
        ZenModeConfig.ZenRule updatedDefaultRule = new ZenModeConfig.ZenRule();
        updatedDefaultRule.modified = true;
        updatedDefaultRule.enabled = false;
        updatedDefaultRule.creationTime = 0;
        updatedDefaultRule.id = SCHEDULE_DEFAULT_RULE_ID;
        updatedDefaultRule.name = "Schedule Default Rule";
        updatedDefaultRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        updatedDefaultRule.conditionId = ZenModeConfig.toScheduleConditionId(new ScheduleInfo());
        updatedDefaultRule.component = new ComponentName("android", "ScheduleConditionProvider");

        ArrayMap<String, ZenModeConfig.ZenRule> autoRules = new ArrayMap<>();
        autoRules.put(SCHEDULE_DEFAULT_RULE_ID, updatedDefaultRule);
        mZenModeHelper.mConfig.automaticRules = autoRules;

        mZenModeHelper.updateDefaultZenRules(Process.SYSTEM_UID);
        assertEquals(updatedDefaultRule,
                mZenModeHelper.mConfig.automaticRules.get(SCHEDULE_DEFAULT_RULE_ID));
    }

    @Test
    public void testDoNotUpdateEnabledDefaultAutoRule() {
        // mDefaultConfig is set to default config in setup by getDefaultConfigParser
        when(mContext.checkCallingPermission(anyString()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        // shouldn't update the rule that's enabled
        ZenModeConfig.ZenRule updatedDefaultRule = new ZenModeConfig.ZenRule();
        updatedDefaultRule.enabled = true;
        updatedDefaultRule.modified = false;
        updatedDefaultRule.creationTime = 0;
        updatedDefaultRule.id = SCHEDULE_DEFAULT_RULE_ID;
        updatedDefaultRule.name = "Schedule Default Rule";
        updatedDefaultRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        updatedDefaultRule.conditionId = ZenModeConfig.toScheduleConditionId(new ScheduleInfo());
        updatedDefaultRule.component = new ComponentName("android", "ScheduleConditionProvider");

        ArrayMap<String, ZenModeConfig.ZenRule> autoRules = new ArrayMap<>();
        autoRules.put(SCHEDULE_DEFAULT_RULE_ID, updatedDefaultRule);
        mZenModeHelper.mConfig.automaticRules = autoRules;

        mZenModeHelper.updateDefaultZenRules(Process.SYSTEM_UID);
        assertEquals(updatedDefaultRule,
                mZenModeHelper.mConfig.automaticRules.get(SCHEDULE_DEFAULT_RULE_ID));
    }

    @Test
    public void testUpdateDefaultAutoRule() {
        // mDefaultConfig is set to default config in setup by getDefaultConfigParser
        final String defaultRuleName = "rule name test";
        when(mContext.checkCallingPermission(anyString()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        // will update rule that is not enabled and modified
        ZenModeConfig.ZenRule customDefaultRule = new ZenModeConfig.ZenRule();
        customDefaultRule.enabled = false;
        customDefaultRule.modified = false;
        customDefaultRule.creationTime = 0;
        customDefaultRule.id = SCHEDULE_DEFAULT_RULE_ID;
        customDefaultRule.name = "Schedule Default Rule";
        customDefaultRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        customDefaultRule.conditionId = ZenModeConfig.toScheduleConditionId(new ScheduleInfo());
        customDefaultRule.component = new ComponentName("android", "ScheduleConditionProvider");

        ArrayMap<String, ZenModeConfig.ZenRule> autoRules = new ArrayMap<>();
        autoRules.put(SCHEDULE_DEFAULT_RULE_ID, customDefaultRule);
        mZenModeHelper.mConfig.automaticRules = autoRules;

        mZenModeHelper.updateDefaultZenRules(Process.SYSTEM_UID);
        ZenModeConfig.ZenRule ruleAfterUpdating =
                mZenModeHelper.mConfig.automaticRules.get(SCHEDULE_DEFAULT_RULE_ID);
        assertEquals(customDefaultRule.enabled, ruleAfterUpdating.enabled);
        assertEquals(customDefaultRule.modified, ruleAfterUpdating.modified);
        assertEquals(customDefaultRule.id, ruleAfterUpdating.id);
        assertEquals(customDefaultRule.conditionId, ruleAfterUpdating.conditionId);
        assertFalse(Objects.equals(defaultRuleName, ruleAfterUpdating.name)); // update name
    }

    @Test
    public void testAddAutomaticZenRule_beyondSystemLimit() {
        for (int i = 0; i < RULE_LIMIT_PER_PACKAGE; i++) {
            ScheduleInfo si = new ScheduleInfo();
            si.startHour = i;
            AutomaticZenRule zenRule = new AutomaticZenRule("name" + i,
                    null,
                    new ComponentName("android", "ScheduleConditionProvider"),
                    ZenModeConfig.toScheduleConditionId(si),
                    new ZenPolicy.Builder().build(),
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
            // We need the package name to be something that's not "android" so there aren't any
            // existing rules under that package.
            String id = mZenModeHelper.addAutomaticZenRule("pkgname", zenRule, UPDATE_ORIGIN_APP,
                    "test", CUSTOM_PKG_UID);
            assertNotNull(id);
        }
        try {
            AutomaticZenRule zenRule = new AutomaticZenRule("name",
                    null,
                    new ComponentName("android", "ScheduleConditionProvider"),
                    ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                    new ZenPolicy.Builder().build(),
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
            String id = mZenModeHelper.addAutomaticZenRule("pkgname", zenRule, UPDATE_ORIGIN_APP,
                    "test", CUSTOM_PKG_UID);
            fail("allowed too many rules to be created");
        } catch (IllegalArgumentException e) {
            // yay
        }
    }

    @Test
    public void testAddAutomaticZenRule_beyondSystemLimit_differentComponents() {
        // Make sure the system limit is enforced per-package even with different component provider
        // names.
        for (int i = 0; i < RULE_LIMIT_PER_PACKAGE; i++) {
            ScheduleInfo si = new ScheduleInfo();
            si.startHour = i;
            AutomaticZenRule zenRule = new AutomaticZenRule("name" + i,
                    null,
                    new ComponentName("android", "ScheduleConditionProvider" + i),
                    ZenModeConfig.toScheduleConditionId(si),
                    new ZenPolicy.Builder().build(),
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
            String id = mZenModeHelper.addAutomaticZenRule("pkgname", zenRule, UPDATE_ORIGIN_APP,
                    "test", CUSTOM_PKG_UID);
            assertNotNull(id);
        }
        try {
            AutomaticZenRule zenRule = new AutomaticZenRule("name",
                    null,
                    new ComponentName("android", "ScheduleConditionProviderFinal"),
                    ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                    new ZenPolicy.Builder().build(),
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
            String id = mZenModeHelper.addAutomaticZenRule("pkgname", zenRule, UPDATE_ORIGIN_APP,
                    "test", CUSTOM_PKG_UID);
            fail("allowed too many rules to be created");
        } catch (IllegalArgumentException e) {
            // yay
        }
    }

    @Test
    public void testAddAutomaticZenRule_claimedSystemOwner() {
        // Make sure anything that claims to have a "system" owner but not actually part of the
        // system package still gets limited on number of rules
        for (int i = 0; i < RULE_LIMIT_PER_PACKAGE; i++) {
            ScheduleInfo si = new ScheduleInfo();
            si.startHour = i;
            AutomaticZenRule zenRule = new AutomaticZenRule("name" + i,
                    new ComponentName("android", "ScheduleConditionProvider" + i),
                    null, // configuration activity
                    ZenModeConfig.toScheduleConditionId(si),
                    new ZenPolicy.Builder().build(),
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
            String id = mZenModeHelper.addAutomaticZenRule("pkgname", zenRule, UPDATE_ORIGIN_APP,
                    "test", CUSTOM_PKG_UID);
            assertNotNull(id);
        }
        try {
            AutomaticZenRule zenRule = new AutomaticZenRule("name",
                    new ComponentName("android", "ScheduleConditionProviderFinal"),
                    null, // configuration activity
                    ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                    new ZenPolicy.Builder().build(),
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
            String id = mZenModeHelper.addAutomaticZenRule("pkgname", zenRule, UPDATE_ORIGIN_APP,
                    "test", CUSTOM_PKG_UID);
            fail("allowed too many rules to be created");
        } catch (IllegalArgumentException e) {
            // yay
        }
    }

    @Test
    public void testAddAutomaticZenRule_CA() {
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName("android", "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                new ZenPolicy.Builder().build(),
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id = mZenModeHelper.addAutomaticZenRule("android", zenRule,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);

        assertTrue(id != null);
        ZenModeConfig.ZenRule ruleInConfig = mZenModeHelper.mConfig.automaticRules.get(id);
        assertTrue(ruleInConfig != null);
        assertEquals(zenRule.isEnabled(), ruleInConfig.enabled);
        assertEquals(zenRule.isModified(), ruleInConfig.modified);
        assertEquals(zenRule.getConditionId(), ruleInConfig.conditionId);
        assertEquals(NotificationManager.zenModeFromInterruptionFilter(
                zenRule.getInterruptionFilter(), -1), ruleInConfig.zenMode);
        assertEquals(zenRule.getName(), ruleInConfig.name);
        assertEquals("android", ruleInConfig.pkg);
    }

    @Test
    public void testAddAutomaticZenRule_CPS() {
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                new ComponentName("android", "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id = mZenModeHelper.addAutomaticZenRule("android", zenRule,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);

        assertTrue(id != null);
        ZenModeConfig.ZenRule ruleInConfig = mZenModeHelper.mConfig.automaticRules.get(id);
        assertTrue(ruleInConfig != null);
        assertEquals(zenRule.isEnabled(), ruleInConfig.enabled);
        assertEquals(zenRule.isModified(), ruleInConfig.modified);
        assertEquals(zenRule.getConditionId(), ruleInConfig.conditionId);
        assertEquals(NotificationManager.zenModeFromInterruptionFilter(
                zenRule.getInterruptionFilter(), -1), ruleInConfig.zenMode);
        assertEquals(zenRule.getName(), ruleInConfig.name);
        assertEquals("android", ruleInConfig.pkg);
    }

    @Test
    public void testSetAutomaticZenRuleState_nullPkg() {
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(mContext.getPackageName(), "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                new ZenPolicy.Builder().build(),
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);

        String id = mZenModeHelper.addAutomaticZenRule(null, zenRule, UPDATE_ORIGIN_APP, "test",
                CUSTOM_PKG_UID);
        mZenModeHelper.setAutomaticZenRuleState(zenRule.getConditionId(),
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                UPDATE_ORIGIN_APP,
                CUSTOM_PKG_UID);

        ZenModeConfig.ZenRule ruleInConfig = mZenModeHelper.mConfig.automaticRules.get(id);
        assertEquals(STATE_TRUE, ruleInConfig.condition.state);
    }

    @Test
    public void testUpdateAutomaticZenRule_nullPkg() {
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(mContext.getPackageName(), "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                new ZenPolicy.Builder().build(),
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);

        String id = mZenModeHelper.addAutomaticZenRule(null, zenRule, UPDATE_ORIGIN_APP, "test",
                CUSTOM_PKG_UID);

        AutomaticZenRule zenRule2 = new AutomaticZenRule("NEW",
                null,
                new ComponentName(mContext.getPackageName(), "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                new ZenPolicy.Builder().build(),
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);

        mZenModeHelper.updateAutomaticZenRule(id, zenRule2, UPDATE_ORIGIN_APP, "", CUSTOM_PKG_UID);

        ZenModeConfig.ZenRule ruleInConfig = mZenModeHelper.mConfig.automaticRules.get(id);
        assertEquals("NEW", ruleInConfig.name);
    }

    @Test
    public void testRemoveAutomaticZenRule_nullPkg() {
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(mContext.getPackageName(), "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                new ZenPolicy.Builder().build(),
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);

        String id = mZenModeHelper.addAutomaticZenRule(null, zenRule, UPDATE_ORIGIN_APP, "test",
                CUSTOM_PKG_UID);

        assertTrue(id != null);
        ZenModeConfig.ZenRule ruleInConfig = mZenModeHelper.mConfig.automaticRules.get(id);
        assertTrue(ruleInConfig != null);
        assertEquals(zenRule.getName(), ruleInConfig.name);

        mZenModeHelper.removeAutomaticZenRule(id, UPDATE_ORIGIN_APP, "test", CUSTOM_PKG_UID);
        assertNull(mZenModeHelper.mConfig.automaticRules.get(id));
    }

    @Test
    public void testRemoveAutomaticZenRules_nullPkg() {
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(mContext.getPackageName(), "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                new ZenPolicy.Builder().build(),
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id = mZenModeHelper.addAutomaticZenRule(null, zenRule, UPDATE_ORIGIN_APP, "test",
                CUSTOM_PKG_UID);

        assertTrue(id != null);
        ZenModeConfig.ZenRule ruleInConfig = mZenModeHelper.mConfig.automaticRules.get(id);
        assertTrue(ruleInConfig != null);
        assertEquals(zenRule.getName(), ruleInConfig.name);

        mZenModeHelper.removeAutomaticZenRules(mContext.getPackageName(), UPDATE_ORIGIN_APP, "test",
                CUSTOM_PKG_UID);
        assertNull(mZenModeHelper.mConfig.automaticRules.get(id));
    }

    @Test
    public void testRulesWithSameUri() {
        // needs to be a valid schedule info object for the subscription to happen properly
        ScheduleInfo scheduleInfo = new ScheduleInfo();
        scheduleInfo.days = new int[]{1, 2};
        scheduleInfo.endHour = 1;
        Uri sharedUri = ZenModeConfig.toScheduleConditionId(scheduleInfo);
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                new ComponentName("android", "ScheduleConditionProvider"),
                sharedUri,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id = mZenModeHelper.addAutomaticZenRule("android", zenRule,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);
        AutomaticZenRule zenRule2 = new AutomaticZenRule("name2",
                new ComponentName("android", "ScheduleConditionProvider"),
                sharedUri,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id2 = mZenModeHelper.addAutomaticZenRule("android", zenRule2,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);

        Condition condition = new Condition(sharedUri, "", STATE_TRUE);
        mZenModeHelper.setAutomaticZenRuleState(sharedUri, condition,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, Process.SYSTEM_UID);

        for (ZenModeConfig.ZenRule rule : mZenModeHelper.mConfig.automaticRules.values()) {
            if (rule.id.equals(id)) {
                assertNotNull(rule.condition);
                assertTrue(rule.condition.state == STATE_TRUE);
            }
            if (rule.id.equals(id2)) {
                assertNotNull(rule.condition);
                assertTrue(rule.condition.state == STATE_TRUE);
            }
        }

        condition = new Condition(sharedUri, "", STATE_FALSE);
        mZenModeHelper.setAutomaticZenRuleState(sharedUri, condition,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, Process.SYSTEM_UID);

        for (ZenModeConfig.ZenRule rule : mZenModeHelper.mConfig.automaticRules.values()) {
            if (rule.id.equals(id)) {
                assertNotNull(rule.condition);
                assertTrue(rule.condition.state == STATE_FALSE);
            }
            if (rule.id.equals(id2)) {
                assertNotNull(rule.condition);
                assertTrue(rule.condition.state == STATE_FALSE);
            }
        }
    }

    @Test
    public void addAutomaticZenRule_fromApp_ignoresHiddenEffects() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);

        ZenDeviceEffects zde = new ZenDeviceEffects.Builder()
                .setShouldDisplayGrayscale(true)
                .setShouldSuppressAmbientDisplay(true)
                .setShouldDimWallpaper(true)
                .setShouldUseNightMode(true)
                .setShouldDisableAutoBrightness(true)
                .setShouldDisableTapToWake(true)
                .setShouldDisableTiltToWake(true)
                .setShouldDisableTouch(true)
                .setShouldMinimizeRadioUsage(true)
                .setShouldMaximizeDoze(true)
                .build();

        String ruleId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                new AutomaticZenRule.Builder("Rule", CONDITION_ID)
                        .setOwner(OWNER)
                        .setDeviceEffects(zde)
                        .build(),
                UPDATE_ORIGIN_APP, "reasons", 0);

        AutomaticZenRule savedRule = mZenModeHelper.getAutomaticZenRule(ruleId);
        assertThat(savedRule.getDeviceEffects()).isEqualTo(
                new ZenDeviceEffects.Builder()
                        .setShouldDisplayGrayscale(true)
                        .setShouldSuppressAmbientDisplay(true)
                        .setShouldDimWallpaper(true)
                        .setShouldUseNightMode(true)
                        .build());
    }

    @Test
    public void addAutomaticZenRule_fromSystem_respectsHiddenEffects() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);

        ZenDeviceEffects zde = new ZenDeviceEffects.Builder()
                .setShouldDisplayGrayscale(true)
                .setShouldSuppressAmbientDisplay(true)
                .setShouldDimWallpaper(true)
                .setShouldUseNightMode(true)
                .setShouldDisableAutoBrightness(true)
                .setShouldDisableTapToWake(true)
                .setShouldDisableTiltToWake(true)
                .setShouldDisableTouch(true)
                .setShouldMinimizeRadioUsage(true)
                .setShouldMaximizeDoze(true)
                .build();

        String ruleId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                new AutomaticZenRule.Builder("Rule", CONDITION_ID)
                        .setOwner(OWNER)
                        .setDeviceEffects(zde)
                        .build(),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "reasons", 0);

        AutomaticZenRule savedRule = mZenModeHelper.getAutomaticZenRule(ruleId);
        assertThat(savedRule.getDeviceEffects()).isEqualTo(zde);
    }

    @Test
    public void addAutomaticZenRule_fromUser_respectsHiddenEffects() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);

        ZenDeviceEffects zde = new ZenDeviceEffects.Builder()
                .setShouldDisplayGrayscale(true)
                .setShouldSuppressAmbientDisplay(true)
                .setShouldDimWallpaper(true)
                .setShouldUseNightMode(true)
                .setShouldDisableAutoBrightness(true)
                .setShouldDisableTapToWake(true)
                .setShouldDisableTiltToWake(true)
                .setShouldDisableTouch(true)
                .setShouldMinimizeRadioUsage(true)
                .setShouldMaximizeDoze(true)
                .build();

        String ruleId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                new AutomaticZenRule.Builder("Rule", CONDITION_ID)
                        .setOwner(OWNER)
                        .setDeviceEffects(zde)
                        .build(),
                UPDATE_ORIGIN_USER,
                "reasons", 0);

        AutomaticZenRule savedRule = mZenModeHelper.getAutomaticZenRule(ruleId);

        // savedRule.getDeviceEffects() is equal to zde, except for the userModifiedFields.
        // So we clear before comparing.
        ZenDeviceEffects savedEffects = new ZenDeviceEffects.Builder(savedRule.getDeviceEffects())
                .setUserModifiedFields(0).build();

        assertThat(savedEffects).isEqualTo(zde);
    }

    @Test
    public void updateAutomaticZenRule_fromApp_preservesPreviousHiddenEffects() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
        ZenDeviceEffects original = new ZenDeviceEffects.Builder()
                .setShouldDisableTapToWake(true)
                .build();
        String ruleId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                new AutomaticZenRule.Builder("Rule", CONDITION_ID)
                        .setOwner(OWNER)
                        .setDeviceEffects(original)
                        .build(),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "reasons", 0);

        ZenDeviceEffects updateFromApp = new ZenDeviceEffects.Builder()
                .setShouldUseNightMode(true) // Good
                .setShouldMaximizeDoze(true) // Bad
                .build();
        mZenModeHelper.updateAutomaticZenRule(ruleId,
                new AutomaticZenRule.Builder("Rule", CONDITION_ID)
                        .setOwner(OWNER)
                        .setDeviceEffects(updateFromApp)
                        .build(),
                UPDATE_ORIGIN_APP, "reasons", 0);

        AutomaticZenRule savedRule = mZenModeHelper.getAutomaticZenRule(ruleId);
        assertThat(savedRule.getDeviceEffects()).isEqualTo(
                new ZenDeviceEffects.Builder()
                        .setShouldUseNightMode(true) // From update.
                        .setShouldDisableTapToWake(true) // From original.
                        .build());
    }

    @Test
    public void updateAutomaticZenRule_fromSystem_updatesHiddenEffects() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
        ZenDeviceEffects original = new ZenDeviceEffects.Builder()
                .setShouldDisableTapToWake(true)
                .build();
        String ruleId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                new AutomaticZenRule.Builder("Rule", CONDITION_ID)
                        .setOwner(OWNER)
                        .setDeviceEffects(original)
                        .build(),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "reasons", 0);

        ZenDeviceEffects updateFromSystem = new ZenDeviceEffects.Builder()
                .setShouldUseNightMode(true) // Good
                .setShouldMaximizeDoze(true) // Also good
                .build();
        mZenModeHelper.updateAutomaticZenRule(ruleId,
                new AutomaticZenRule.Builder("Rule", CONDITION_ID)
                        .setDeviceEffects(updateFromSystem)
                        .build(),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "reasons", 0);

        AutomaticZenRule savedRule = mZenModeHelper.getAutomaticZenRule(ruleId);
        assertThat(savedRule.getDeviceEffects()).isEqualTo(updateFromSystem);
    }

    @Test
    public void updateAutomaticZenRule_fromUser_updatesHiddenEffects() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
        ZenDeviceEffects original = new ZenDeviceEffects.Builder()
                .setShouldDisableTapToWake(true)
                .build();
        String ruleId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                new AutomaticZenRule.Builder("Rule", CONDITION_ID)
                        .setOwner(OWNER)
                        .setDeviceEffects(original)
                        .build(),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "reasons", 0);

        ZenDeviceEffects updateFromUser = new ZenDeviceEffects.Builder()
                .setShouldUseNightMode(true)
                .setShouldMaximizeDoze(true)
                // Just to emphasize that unset values default to false;
                // even with this line removed, tap to wake would be set to false.
                .setShouldDisableTapToWake(false)
                .build();
        mZenModeHelper.updateAutomaticZenRule(ruleId,
                new AutomaticZenRule.Builder("Rule", CONDITION_ID)
                        .setDeviceEffects(updateFromUser)
                        .build(),
                UPDATE_ORIGIN_USER, "reasons", 0);

        AutomaticZenRule savedRule = mZenModeHelper.getAutomaticZenRule(ruleId);

        // savedRule.getDeviceEffects() is equal to updateFromUser, except for the
        // userModifiedFields, so we clear before comparing.
        ZenDeviceEffects savedEffects = new ZenDeviceEffects.Builder(savedRule.getDeviceEffects())
                .setUserModifiedFields(0).build();

        assertThat(savedEffects).isEqualTo(updateFromUser);
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void addAutomaticZenRule_withTypeBedtime_replacesDisabledSleeping() {
        ZenRule sleepingRule = createCustomAutomaticRule(ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                ZenModeConfig.EVERY_NIGHT_DEFAULT_RULE_ID);
        sleepingRule.enabled = false;
        sleepingRule.userModifiedFields = 0;
        sleepingRule.name = "ZZZZZZZ...";
        mZenModeHelper.mConfig.automaticRules.clear();
        mZenModeHelper.mConfig.automaticRules.put(sleepingRule.id, sleepingRule);

        AutomaticZenRule bedtime = new AutomaticZenRule.Builder("Bedtime Mode (TM)", CONDITION_ID)
                .setType(TYPE_BEDTIME)
                .build();
        String bedtimeRuleId = mZenModeHelper.addAutomaticZenRule("pkg", bedtime, UPDATE_ORIGIN_APP,
                "reason", CUSTOM_PKG_UID);

        assertThat(mZenModeHelper.mConfig.automaticRules.keySet()).containsExactly(bedtimeRuleId);
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void addAutomaticZenRule_withTypeBedtime_keepsEnabledSleeping() {
        ZenRule sleepingRule = createCustomAutomaticRule(ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                ZenModeConfig.EVERY_NIGHT_DEFAULT_RULE_ID);
        sleepingRule.enabled = true;
        sleepingRule.userModifiedFields = 0;
        sleepingRule.name = "ZZZZZZZ...";
        mZenModeHelper.mConfig.automaticRules.clear();
        mZenModeHelper.mConfig.automaticRules.put(sleepingRule.id, sleepingRule);

        AutomaticZenRule bedtime = new AutomaticZenRule.Builder("Bedtime Mode (TM)", CONDITION_ID)
                .setType(TYPE_BEDTIME)
                .build();
        String bedtimeRuleId = mZenModeHelper.addAutomaticZenRule("pkg", bedtime, UPDATE_ORIGIN_APP,
                "reason", CUSTOM_PKG_UID);

        assertThat(mZenModeHelper.mConfig.automaticRules.keySet()).containsExactly(
                ZenModeConfig.EVERY_NIGHT_DEFAULT_RULE_ID, bedtimeRuleId);
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void addAutomaticZenRule_withTypeBedtime_keepsCustomizedSleeping() {
        ZenRule sleepingRule = createCustomAutomaticRule(ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                ZenModeConfig.EVERY_NIGHT_DEFAULT_RULE_ID);
        sleepingRule.enabled = false;
        sleepingRule.userModifiedFields = AutomaticZenRule.FIELD_INTERRUPTION_FILTER;
        sleepingRule.name = "ZZZZZZZ...";
        mZenModeHelper.mConfig.automaticRules.clear();
        mZenModeHelper.mConfig.automaticRules.put(sleepingRule.id, sleepingRule);

        AutomaticZenRule bedtime = new AutomaticZenRule.Builder("Bedtime Mode (TM)", CONDITION_ID)
                .setType(TYPE_BEDTIME)
                .build();
        String bedtimeRuleId = mZenModeHelper.addAutomaticZenRule("pkg", bedtime, UPDATE_ORIGIN_APP,
                "reason", CUSTOM_PKG_UID);

        assertThat(mZenModeHelper.mConfig.automaticRules.keySet()).containsExactly(
                ZenModeConfig.EVERY_NIGHT_DEFAULT_RULE_ID, bedtimeRuleId);
    }

    @Test
    public void testSetManualZenMode() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
        setupZenConfig();

        // note that caller=null because that's how it comes in from NMS.setZenMode
        mZenModeHelper.setManualZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "", null, Process.SYSTEM_UID);

        // confirm that setting zen mode via setManualZenMode changed the zen mode correctly
        assertEquals(ZEN_MODE_IMPORTANT_INTERRUPTIONS, mZenModeHelper.mZenMode);
        assertEquals(true, mZenModeHelper.mConfig.manualRule.allowManualInvocation);

        // and also that it works to turn it back off again
        mZenModeHelper.setManualZenMode(Global.ZEN_MODE_OFF, null, UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI,
                "", null, Process.SYSTEM_UID);

        assertEquals(Global.ZEN_MODE_OFF, mZenModeHelper.mZenMode);
    }

    @Test
    public void testSetManualZenMode_legacy() {
        setupZenConfig();

        // note that caller=null because that's how it comes in from NMS.setZenMode
        mZenModeHelper.setManualZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "", null, Process.SYSTEM_UID);

        // confirm that setting zen mode via setManualZenMode changed the zen mode correctly
        assertEquals(ZEN_MODE_IMPORTANT_INTERRUPTIONS, mZenModeHelper.mZenMode);

        // and also that it works to turn it back off again
        mZenModeHelper.setManualZenMode(ZEN_MODE_OFF, null, UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "",
                null, Process.SYSTEM_UID);

        assertEquals(ZEN_MODE_OFF, mZenModeHelper.mZenMode);
    }

    private enum ModesApiFlag {
        ENABLED(true, /* originForUserActionInSystemUi= */ UPDATE_ORIGIN_USER),
        DISABLED(false, /* originForUserActionInSystemUi= */ UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI);

        private final boolean mEnabled;
        @ConfigChangeOrigin private final int mOriginForUserActionInSystemUi;

        ModesApiFlag(boolean enabled, @ConfigChangeOrigin int originForUserActionInSystemUi) {
            this.mEnabled = enabled;
            this.mOriginForUserActionInSystemUi = originForUserActionInSystemUi;
        }

        void applyFlag(SetFlagsRule setFlagsRule) {
            if (mEnabled) {
                setFlagsRule.enableFlags(Flags.FLAG_MODES_API);
            } else {
                setFlagsRule.disableFlags(Flags.FLAG_MODES_API);
            }
        }
    }

    @Test
    public void testZenModeEventLog_setManualZenMode(@TestParameter ModesApiFlag modesApiFlag)
            throws IllegalArgumentException {
        modesApiFlag.applyFlag(mSetFlagsRule);
        mTestFlagResolver.setFlagOverride(LOG_DND_STATE_EVENTS, true);
        setupZenConfig();

        // Turn zen mode on (to important_interruptions)
        // Need to additionally call the looper in order to finish the post-apply-config process
        mZenModeHelper.setManualZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null,
                modesApiFlag.mOriginForUserActionInSystemUi, "", null, Process.SYSTEM_UID);

        // Now turn zen mode off, but via a different package UID -- this should get registered as
        // "not an action by the user" because some other app is changing zen mode
        mZenModeHelper.setManualZenMode(ZEN_MODE_OFF, null, UPDATE_ORIGIN_APP, "", null,
                CUSTOM_PKG_UID);

        // In total, this should be 2 loggable changes
        assertEquals(2, mZenModeEventLogger.numLoggedChanges());

        // we expect the following changes from turning zen mode on:
        //   - manual rule added
        //   - zen mode -> ZEN_MODE_IMPORTANT_INTERRUPTIONS
        // This should combine to 1 log event (zen mode turns on) with the following properties:
        //   - event ID: DND_TURNED_ON
        //   - new zen mode = important interruptions; prev zen mode = off
        //   - changed rule type = manual
        //   - rules active = 1
        //   - user action = true (system-based turning zen mode on)
        //   - package uid = system (as set above)
        //   - resulting DNDPolicyProto the same as the values in setupZenConfig()
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_TURNED_ON.getId(),
                mZenModeEventLogger.getEventId(0));
        assertEquals(ZEN_MODE_OFF, mZenModeEventLogger.getPrevZenMode(0));
        assertEquals(ZEN_MODE_IMPORTANT_INTERRUPTIONS, mZenModeEventLogger.getNewZenMode(0));
        assertEquals(DNDProtoEnums.MANUAL_RULE, mZenModeEventLogger.getChangedRuleType(0));
        assertEquals(1, mZenModeEventLogger.getNumRulesActive(0));
        assertThat(mZenModeEventLogger.getFromSystemOrSystemUi(0)).isEqualTo(
                modesApiFlag == ModesApiFlag.DISABLED);
        assertTrue(mZenModeEventLogger.getIsUserAction(0));
        assertEquals(Process.SYSTEM_UID, mZenModeEventLogger.getPackageUid(0));
        checkDndProtoMatchesSetupZenConfig(mZenModeEventLogger.getPolicyProto(0));

        // and from turning zen mode off:
        //   - event ID: DND_TURNED_OFF
        //   - new zen mode = off; previous = important interruptions
        //   - changed rule type = manual
        //   - rules active = 0
        //   - user action = false
        //   - package uid = custom one passed in above
        //   - DNDPolicyProto still the same
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_TURNED_OFF.getId(),
                mZenModeEventLogger.getEventId(1));
        assertEquals(ZEN_MODE_IMPORTANT_INTERRUPTIONS, mZenModeEventLogger.getPrevZenMode(1));
        assertEquals(ZEN_MODE_OFF, mZenModeEventLogger.getNewZenMode(1));
        assertEquals(DNDProtoEnums.MANUAL_RULE, mZenModeEventLogger.getChangedRuleType(1));
        assertEquals(0, mZenModeEventLogger.getNumRulesActive(1));
        assertFalse(mZenModeEventLogger.getIsUserAction(1));
        assertEquals(CUSTOM_PKG_UID, mZenModeEventLogger.getPackageUid(1));
        if (Flags.modesApi()) {
            assertThat(mZenModeEventLogger.getPolicyProto(1)).isNull();
        } else {
            checkDndProtoMatchesSetupZenConfig(mZenModeEventLogger.getPolicyProto(1));
        }
    }

    @Test
    public void testZenModeEventLog_automaticRules(@TestParameter ModesApiFlag modesApiFlag)
            throws IllegalArgumentException {
        modesApiFlag.applyFlag(mSetFlagsRule);
        mTestFlagResolver.setFlagOverride(LOG_DND_STATE_EVENTS, true);
        setupZenConfig();

        // Add a new automatic zen rule that's enabled
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);

        // Event 1: Mimic the rule coming on automatically by setting the Condition to STATE_TRUE
        mZenModeHelper.setAutomaticZenRuleState(id,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI,
                Process.SYSTEM_UID);

        // Event 2: "User" turns off the automatic rule (sets it to not enabled)
        zenRule.setEnabled(false);
        mZenModeHelper.updateAutomaticZenRule(id, zenRule,
                modesApiFlag.mOriginForUserActionInSystemUi, "", Process.SYSTEM_UID);

        // Add a new system rule
        AutomaticZenRule systemRule = new AutomaticZenRule("systemRule",
                null,
                new ComponentName("android", "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String systemId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), systemRule,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);

        // Event 3: turn on the system rule
        mZenModeHelper.setAutomaticZenRuleState(systemId,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, Process.SYSTEM_UID);

        // Event 4: "User" deletes the rule
        mZenModeHelper.removeAutomaticZenRule(systemId, modesApiFlag.mOriginForUserActionInSystemUi,
                "", Process.SYSTEM_UID);

        // In total, this represents 4 events
        assertEquals(4, mZenModeEventLogger.numLoggedChanges());

        // We should see an event from the automatic rule turning on; it should have the following
        // properties:
        //   - event ID: DND_TURNED_ON
        //   - zen mode: OFF -> IMPORTANT_INTERRUPTIONS
        //   - automatic rule change
        //   - 1 rule (newly) active
        //   - automatic (is not a user action)
        //   - package UID is written to be the rule *owner* even though it "comes from system"
        //   - zen policy is the same as the set-up zen config
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_TURNED_ON.getId(),
                mZenModeEventLogger.getEventId(0));
        assertEquals(ZEN_MODE_OFF, mZenModeEventLogger.getPrevZenMode(0));
        assertEquals(ZEN_MODE_IMPORTANT_INTERRUPTIONS, mZenModeEventLogger.getNewZenMode(0));
        assertEquals(DNDProtoEnums.AUTOMATIC_RULE, mZenModeEventLogger.getChangedRuleType(0));
        assertEquals(1, mZenModeEventLogger.getNumRulesActive(0));
        assertFalse(mZenModeEventLogger.getIsUserAction(0));
        assertEquals(CUSTOM_PKG_UID, mZenModeEventLogger.getPackageUid(0));
        checkDndProtoMatchesSetupZenConfig(mZenModeEventLogger.getPolicyProto(0));

        // When the automatic rule is disabled, this should turn off zen mode and also count as a
        // user action.
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_TURNED_OFF.getId(),
                mZenModeEventLogger.getEventId(1));
        assertEquals(ZEN_MODE_IMPORTANT_INTERRUPTIONS, mZenModeEventLogger.getPrevZenMode(1));
        assertEquals(ZEN_MODE_OFF, mZenModeEventLogger.getNewZenMode(1));
        assertEquals(DNDProtoEnums.AUTOMATIC_RULE, mZenModeEventLogger.getChangedRuleType(1));
        assertEquals(0, mZenModeEventLogger.getNumRulesActive(1));
        assertTrue(mZenModeEventLogger.getIsUserAction(1));
        assertEquals(Process.SYSTEM_UID, mZenModeEventLogger.getPackageUid(1));
        if (Flags.modesApi()) {
            assertThat(mZenModeEventLogger.getPolicyProto(1)).isNull();
        } else {
            checkDndProtoMatchesSetupZenConfig(mZenModeEventLogger.getPolicyProto(1));
        }

        // When the system rule is enabled, this counts as an automatic action that comes from the
        // system and turns on DND
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_TURNED_ON.getId(),
                mZenModeEventLogger.getEventId(2));
        assertEquals(DNDProtoEnums.AUTOMATIC_RULE, mZenModeEventLogger.getChangedRuleType(2));
        assertEquals(1, mZenModeEventLogger.getNumRulesActive(2));
        assertFalse(mZenModeEventLogger.getIsUserAction(2));
        assertEquals(Process.SYSTEM_UID, mZenModeEventLogger.getPackageUid(2));

        // When the system rule is deleted, we consider this a user action that turns DND off
        // (again)
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_TURNED_OFF.getId(),
                mZenModeEventLogger.getEventId(3));
        assertEquals(DNDProtoEnums.AUTOMATIC_RULE, mZenModeEventLogger.getChangedRuleType(3));
        assertEquals(0, mZenModeEventLogger.getNumRulesActive(3));
        assertTrue(mZenModeEventLogger.getIsUserAction(3));
        assertEquals(Process.SYSTEM_UID, mZenModeEventLogger.getPackageUid(3));
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void testZenModeEventLog_automaticRuleActivatedFromAppByAppAndUser()
            throws IllegalArgumentException {
        mTestFlagResolver.setFlagOverride(LOG_DND_STATE_EVENTS, true);
        setupZenConfig();

        // Ann app adds an automatic zen rule
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule,
                UPDATE_ORIGIN_APP, "test", CUSTOM_PKG_UID);

        // Event 1: Mimic the rule coming on manually when the user turns it on in the app
        // ("Turn on bedtime now" because user goes to bed earlier).
        mZenModeHelper.setAutomaticZenRuleState(id,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE, SOURCE_USER_ACTION),
                UPDATE_ORIGIN_USER, CUSTOM_PKG_UID);

        // Event 2: App deactivates the rule automatically (it's 8 AM, bedtime schedule ends)
        mZenModeHelper.setAutomaticZenRuleState(id,
                new Condition(zenRule.getConditionId(), "", STATE_FALSE, SOURCE_SCHEDULE),
                UPDATE_ORIGIN_APP, CUSTOM_PKG_UID);

        // Event 3: App activates the rule automatically (it's now 11 PM, bedtime schedule starts)
        mZenModeHelper.setAutomaticZenRuleState(id,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE, SOURCE_SCHEDULE),
                UPDATE_ORIGIN_APP, CUSTOM_PKG_UID);

        // Event 4: User deactivates the rule manually (they get up before 8 AM on the next day)
        mZenModeHelper.setAutomaticZenRuleState(id,
                new Condition(zenRule.getConditionId(), "", STATE_FALSE, SOURCE_USER_ACTION),
                UPDATE_ORIGIN_USER, CUSTOM_PKG_UID);

        // In total, this represents 4 events
        assertEquals(4, mZenModeEventLogger.numLoggedChanges());

        // Automatic rule turning on manually:
        //   - event ID: DND_TURNED_ON
        //   - 1 rule (newly) active
        //   - is a user action
        //   - package UID is the calling package
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_TURNED_ON.getId(),
                mZenModeEventLogger.getEventId(0));
        assertEquals(1, mZenModeEventLogger.getNumRulesActive(0));
        assertTrue(mZenModeEventLogger.getIsUserAction(0));
        assertEquals(CUSTOM_PKG_UID, mZenModeEventLogger.getPackageUid(0));

        // Automatic rule turned off automatically by app:
        //   - event ID: DND_TURNED_OFF
        //   - 0 rules active
        //   - is not a user action
        //   - package UID is the calling package
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_TURNED_OFF.getId(),
                mZenModeEventLogger.getEventId(1));
        assertEquals(0, mZenModeEventLogger.getNumRulesActive(1));
        assertFalse(mZenModeEventLogger.getIsUserAction(1));
        assertEquals(CUSTOM_PKG_UID, mZenModeEventLogger.getPackageUid(1));

        // Automatic rule turned on automatically by app:
        //   - event ID: DND_TURNED_ON
        //   - 1 rule (newly) active
        //   - is not a user action
        //   - package UID is the calling package
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_TURNED_ON.getId(),
                mZenModeEventLogger.getEventId(2));
        assertEquals(DNDProtoEnums.AUTOMATIC_RULE, mZenModeEventLogger.getChangedRuleType(2));
        assertEquals(1, mZenModeEventLogger.getNumRulesActive(2));
        assertFalse(mZenModeEventLogger.getIsUserAction(2));
        assertEquals(CUSTOM_PKG_UID, mZenModeEventLogger.getPackageUid(2));

        // Automatic rule turned off automatically by the user:
        //   - event ID: DND_TURNED_ON
        //   - 0 rules active
        //   - is a user action
        //   - package UID is the calling package
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_TURNED_OFF.getId(),
                mZenModeEventLogger.getEventId(3));
        assertEquals(0, mZenModeEventLogger.getNumRulesActive(3));
        assertTrue(mZenModeEventLogger.getIsUserAction(3));
        assertEquals(CUSTOM_PKG_UID, mZenModeEventLogger.getPackageUid(3));
    }

    @Test
    public void testZenModeEventLog_policyChanges(@TestParameter ModesApiFlag modesApiFlag)
            throws IllegalArgumentException {
        modesApiFlag.applyFlag(mSetFlagsRule);
        mTestFlagResolver.setFlagOverride(LOG_DND_STATE_EVENTS, true);
        setupZenConfig();

        // First just turn zen mode on
        mZenModeHelper.setManualZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null,
                modesApiFlag.mOriginForUserActionInSystemUi, "", null, Process.SYSTEM_UID);

        // Now change the policy slightly; want to confirm that this'll be reflected in the logs
        ZenModeConfig newConfig = mZenModeHelper.mConfig.copy();
        newConfig.allowAlarms = true;
        newConfig.allowRepeatCallers = false;
        mZenModeHelper.setNotificationPolicy(newConfig.toNotificationPolicy(),
                modesApiFlag.mOriginForUserActionInSystemUi, Process.SYSTEM_UID);

        // Turn zen mode off; we want to make sure policy changes do not get logged when zen mode
        // is off.
        mZenModeHelper.setManualZenMode(ZEN_MODE_OFF, null, UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "",
                null, Process.SYSTEM_UID);

        // Change the policy again
        newConfig.allowMessages = false;
        newConfig.allowRepeatCallers = true;
        mZenModeHelper.setNotificationPolicy(newConfig.toNotificationPolicy(),
                modesApiFlag.mOriginForUserActionInSystemUi, Process.SYSTEM_UID);

        // Total events: we only expect ones for turning on, changing policy, and turning off
        assertEquals(3, mZenModeEventLogger.numLoggedChanges());

        // The first event is just turning DND on; make sure the policy is what we expect there
        // before it changes in the next stage
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_TURNED_ON.getId(),
                mZenModeEventLogger.getEventId(0));
        checkDndProtoMatchesSetupZenConfig(mZenModeEventLogger.getPolicyProto(0));

        // Second message where we change the policy:
        //   - DND_POLICY_CHANGED (indicates only the policy changed and nothing else)
        //   - rule type: unknown (it's a policy change, not a rule change)
        //   - user action (because it comes from a "system" uid)
        //   - check the specific things changed above
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_POLICY_CHANGED.getId(),
                mZenModeEventLogger.getEventId(1));
        assertEquals(DNDProtoEnums.UNKNOWN_RULE, mZenModeEventLogger.getChangedRuleType(1));
        assertTrue(mZenModeEventLogger.getIsUserAction(1));
        assertEquals(Process.SYSTEM_UID, mZenModeEventLogger.getPackageUid(1));
        DNDPolicyProto dndProto = mZenModeEventLogger.getPolicyProto(1);
        assertEquals(STATE_ALLOW, dndProto.getAlarms().getNumber());
        assertEquals(STATE_DISALLOW, dndProto.getRepeatCallers().getNumber());

        // The third and final event should turn DND off
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_TURNED_OFF.getId(),
                mZenModeEventLogger.getEventId(2));

        // There should be no fourth event for changing the policy the second time.
    }

    @Test
    public void testZenModeEventLog_ruleCounts(@TestParameter ModesApiFlag modesApiFlag)
            throws IllegalArgumentException {
        modesApiFlag.applyFlag(mSetFlagsRule);
        mTestFlagResolver.setFlagOverride(LOG_DND_STATE_EVENTS, true);
        setupZenConfig();

        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);

        // Rule 2, same as rule 1
        AutomaticZenRule zenRule2 = new AutomaticZenRule("name2",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id2 = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule2,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);

        // Rule 3, has stricter settings than the default settings
        ZenModeConfig ruleConfig = mZenModeHelper.mConfig.copy();
        ruleConfig.allowReminders = false;
        ruleConfig.allowCalls = false;
        ruleConfig.allowMessages = false;
        AutomaticZenRule zenRule3 = new AutomaticZenRule("name3",
                null,
                new ComponentName("android", "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                ruleConfig.toZenPolicy(),
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id3 = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule3,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);

        // First: turn on rule 1
        mZenModeHelper.setAutomaticZenRuleState(id,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI,  Process.SYSTEM_UID);

        // Second: turn on rule 2
        mZenModeHelper.setAutomaticZenRuleState(id2,
                new Condition(zenRule2.getConditionId(), "", STATE_TRUE),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI,  Process.SYSTEM_UID);

        // Third: turn on rule 3
        mZenModeHelper.setAutomaticZenRuleState(id3,
                new Condition(zenRule3.getConditionId(), "", STATE_TRUE),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI,  Process.SYSTEM_UID);

        // Fourth: Turn *off* rule 2
        mZenModeHelper.setAutomaticZenRuleState(id2,
                new Condition(zenRule2.getConditionId(), "", STATE_FALSE),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI,  Process.SYSTEM_UID);

        // This should result in a total of four events
        assertEquals(4, mZenModeEventLogger.numLoggedChanges());

        // Event 1: rule 1 turns on. We expect this to turn on DND (zen mode) overall, so that's
        // what the event should reflect. At this time, the policy is the same as initial setup.
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_TURNED_ON.getId(),
                mZenModeEventLogger.getEventId(0));
        assertEquals(ZEN_MODE_OFF, mZenModeEventLogger.getPrevZenMode(0));
        assertEquals(ZEN_MODE_IMPORTANT_INTERRUPTIONS, mZenModeEventLogger.getNewZenMode(0));
        assertEquals(1, mZenModeEventLogger.getNumRulesActive(0));
        assertFalse(mZenModeEventLogger.getIsUserAction(0));
        assertEquals(CUSTOM_PKG_UID, mZenModeEventLogger.getPackageUid(0));
        checkDndProtoMatchesSetupZenConfig(mZenModeEventLogger.getPolicyProto(0));

        // Event 2: rule 2 turns on. This should not change anything about the policy, so the only
        // change is that there are more rules active now.
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_ACTIVE_RULES_CHANGED.getId(),
                mZenModeEventLogger.getEventId(1));
        assertEquals(2, mZenModeEventLogger.getNumRulesActive(1));
        assertFalse(mZenModeEventLogger.getIsUserAction(1));
        assertEquals(CUSTOM_PKG_UID, mZenModeEventLogger.getPackageUid(1));
        checkDndProtoMatchesSetupZenConfig(mZenModeEventLogger.getPolicyProto(1));

        // Event 3: rule 3 turns on. This should trigger a policy change, and be classified as such,
        // but meanwhile also change the number of active rules.
        // Rule 3 is also set up to be a "system"-owned rule, so the caller UID should remain system
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_POLICY_CHANGED.getId(),
                mZenModeEventLogger.getEventId(2));
        assertEquals(3, mZenModeEventLogger.getNumRulesActive(2));
        assertFalse(mZenModeEventLogger.getIsUserAction(2));
        assertEquals(Process.SYSTEM_UID, mZenModeEventLogger.getPackageUid(2));
        DNDPolicyProto dndProto = mZenModeEventLogger.getPolicyProto(2);
        assertEquals(STATE_DISALLOW, dndProto.getReminders().getNumber());
        assertEquals(STATE_DISALLOW, dndProto.getCalls().getNumber());
        assertEquals(STATE_DISALLOW, dndProto.getMessages().getNumber());

        // Event 4: rule 2 turns off. Because rule 3 is still on and stricter than rule 1 (also
        // still on), there should be no policy change as a result of rule 2 going away. Therefore
        // this event should again only be an active rule change.
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_ACTIVE_RULES_CHANGED.getId(),
                mZenModeEventLogger.getEventId(3));
        assertEquals(2, mZenModeEventLogger.getNumRulesActive(3));
        assertFalse(mZenModeEventLogger.getIsUserAction(3));
    }

    @Test
    public void testZenModeEventLog_noLogWithNoConfigChange(
            @TestParameter ModesApiFlag modesApiFlag) throws IllegalArgumentException {
        // If evaluateZenMode is called independently of a config change, don't log.
        modesApiFlag.applyFlag(mSetFlagsRule);
        mTestFlagResolver.setFlagOverride(LOG_DND_STATE_EVENTS, true);
        setupZenConfig();

        // Artificially turn zen mode "on". Re-evaluating zen mode should cause it to turn back off
        // given that we don't have any zen rules active.
        mZenModeHelper.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelper.evaluateZenModeLocked(UPDATE_ORIGIN_UNKNOWN, "test", true);

        // Check that the change actually took: zen mode should be off now
        assertEquals(ZEN_MODE_OFF, mZenModeHelper.mZenMode);

        // but still, nothing should've been logged
        assertEquals(0, mZenModeEventLogger.numLoggedChanges());
    }

    @Test
    public void testZenModeEventLog_reassignUid(@TestParameter ModesApiFlag modesApiFlag)
            throws IllegalArgumentException {
        // Test that, only in specific cases, we reassign the calling UID to one associated with
        // the automatic rule owner.
        modesApiFlag.applyFlag(mSetFlagsRule);
        mTestFlagResolver.setFlagOverride(LOG_DND_STATE_EVENTS, true);
        setupZenConfig();

        // Rule 1, owned by a package
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule,
                UPDATE_ORIGIN_APP, "test", Process.SYSTEM_UID);

        // Rule 2, same as rule 1 but owned by the system
        AutomaticZenRule zenRule2 = new AutomaticZenRule("name2",
                null,
                new ComponentName("android", "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id2 = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule2,
                modesApiFlag.mOriginForUserActionInSystemUi, "test", Process.SYSTEM_UID);

        // Turn on rule 1; call looks like it's from the system. Because setting a condition is
        // typically an automatic (non-user-initiated) action, expect the calling UID to be
        // re-evaluated to the one associated with CUSTOM_PKG_NAME.
        mZenModeHelper.setAutomaticZenRuleState(id,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, Process.SYSTEM_UID);

        // Second: turn on rule 2. This is a system-owned rule and the UID should not be modified
        // (nor even looked up; the mock PackageManager won't handle "android" as input).
        mZenModeHelper.setAutomaticZenRuleState(id2,
                new Condition(zenRule2.getConditionId(), "", STATE_TRUE),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, Process.SYSTEM_UID);

        // Disable rule 1. Because this looks like a user action, the UID should not be modified
        // from the system-provided one.
        zenRule.setEnabled(false);
        mZenModeHelper.updateAutomaticZenRule(id, zenRule,
                modesApiFlag.mOriginForUserActionInSystemUi, "", Process.SYSTEM_UID);

        // Add a manual rule. Any manual rule changes should not get calling uids reassigned.
        mZenModeHelper.setManualZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null, UPDATE_ORIGIN_APP,
                "", null, CUSTOM_PKG_UID);

        // Change rule 2's condition, but from some other UID. Since it doesn't look like it's from
        // the system, we keep the UID info.
        mZenModeHelper.setAutomaticZenRuleState(id2,
                new Condition(zenRule2.getConditionId(), "", STATE_FALSE),
                UPDATE_ORIGIN_APP, 12345);

        // That was 5 events total
        assertEquals(5, mZenModeEventLogger.numLoggedChanges());

        // The first event (activating rule 1) should be of type "zen mode turns on", automatic,
        // have a package UID of CUSTOM_PKG_UID
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_TURNED_ON.getId(),
                mZenModeEventLogger.getEventId(0));
        assertEquals(DNDProtoEnums.AUTOMATIC_RULE, mZenModeEventLogger.getChangedRuleType(0));
        assertFalse(mZenModeEventLogger.getIsUserAction(0));
        assertEquals(CUSTOM_PKG_UID, mZenModeEventLogger.getPackageUid(0));

        // The second event (activating rule 2) should have similar other properties but the UID
        // should be system.
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_ACTIVE_RULES_CHANGED.getId(),
                mZenModeEventLogger.getEventId(1));
        assertEquals(DNDProtoEnums.AUTOMATIC_RULE, mZenModeEventLogger.getChangedRuleType(1));
        assertFalse(mZenModeEventLogger.getIsUserAction(1));
        assertEquals(Process.SYSTEM_UID, mZenModeEventLogger.getPackageUid(1));

        // Third event: disable rule 1. This looks like a user action so UID should be left alone.
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_ACTIVE_RULES_CHANGED.getId(),
                mZenModeEventLogger.getEventId(2));
        assertEquals(DNDProtoEnums.AUTOMATIC_RULE, mZenModeEventLogger.getChangedRuleType(2));
        assertTrue(mZenModeEventLogger.getIsUserAction(2));
        assertEquals(Process.SYSTEM_UID, mZenModeEventLogger.getPackageUid(2));

        // Fourth event: turns on manual mode. Doesn't change effective policy so this is just a
        // change in active rules. Confirm that the package UID is left unchanged.
        // Because it's a manual mode change not from the system, isn't considered a user action.
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_ACTIVE_RULES_CHANGED.getId(),
                mZenModeEventLogger.getEventId(3));
        assertEquals(DNDProtoEnums.MANUAL_RULE, mZenModeEventLogger.getChangedRuleType(3));
        assertFalse(mZenModeEventLogger.getIsUserAction(3));
        assertEquals(CUSTOM_PKG_UID, mZenModeEventLogger.getPackageUid(3));

        // Fourth event: changed condition on rule 2 (turning it off via condition).
        // This comes from a random different UID so we expect that to remain untouched.
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_ACTIVE_RULES_CHANGED.getId(),
                mZenModeEventLogger.getEventId(4));
        assertEquals(DNDProtoEnums.AUTOMATIC_RULE, mZenModeEventLogger.getChangedRuleType(4));
        assertFalse(mZenModeEventLogger.getIsUserAction(4));
        assertEquals(12345, mZenModeEventLogger.getPackageUid(4));
    }

    @Test
    public void testZenModeEventLog_channelsBypassingChanges(
            @TestParameter ModesApiFlag modesApiFlag) {
        // Verify that the right thing happens when the canBypassDnd value changes.
        modesApiFlag.applyFlag(mSetFlagsRule);
        mTestFlagResolver.setFlagOverride(LOG_DND_STATE_EVENTS, true);
        setupZenConfig();

        // Turn on zen mode with a manual rule with an enabler set. This should *not* count
        // as a user action, and *should* get its UID reassigned.
        mZenModeHelper.setManualZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "", CUSTOM_PKG_NAME, Process.SYSTEM_UID);

        // Now change apps bypassing to true
        ZenModeConfig newConfig = mZenModeHelper.mConfig.copy();
        newConfig.areChannelsBypassingDnd = true;
        mZenModeHelper.setNotificationPolicy(newConfig.toNotificationPolicy(),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, Process.SYSTEM_UID);

        // and then back to false, all without changing anything else
        newConfig.areChannelsBypassingDnd = false;
        mZenModeHelper.setNotificationPolicy(newConfig.toNotificationPolicy(),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, Process.SYSTEM_UID);

        // Turn off manual mode, call from a package: don't reset UID even though enabler is set
        mZenModeHelper.setManualZenMode(ZEN_MODE_OFF, null, UPDATE_ORIGIN_APP, "",
                CUSTOM_PKG_NAME, 12345);

        // And likewise when turning it back on again
        mZenModeHelper.setManualZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null, UPDATE_ORIGIN_APP,
                "", CUSTOM_PKG_NAME, 12345);

        // These are 5 events in total.
        assertEquals(5, mZenModeEventLogger.numLoggedChanges());

        // First event: turns on, UID reassigned for manual mode
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_TURNED_ON.getId(),
                mZenModeEventLogger.getEventId(0));
        assertFalse(mZenModeEventLogger.getIsUserAction(0));
        assertEquals(CUSTOM_PKG_UID, mZenModeEventLogger.getPackageUid(0));

        // Second event should be a policy-only change with are channels bypassing = true
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_POLICY_CHANGED.getId(),
                mZenModeEventLogger.getEventId(1));
        assertTrue(mZenModeEventLogger.getAreChannelsBypassing(1));

        // Third event also a policy-only change but with channels bypassing now false
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_POLICY_CHANGED.getId(),
                mZenModeEventLogger.getEventId(2));
        assertFalse(mZenModeEventLogger.getAreChannelsBypassing(2));

        // Fourth event: should turn DND off, not have UID reassigned
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_TURNED_OFF.getId(),
                mZenModeEventLogger.getEventId(3));
        assertFalse(mZenModeEventLogger.getIsUserAction(3));
        assertEquals(12345, mZenModeEventLogger.getPackageUid(3));

        // Fifth event: turn DND back on, not have UID reassigned
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_TURNED_ON.getId(),
                mZenModeEventLogger.getEventId(4));
        assertFalse(mZenModeEventLogger.getIsUserAction(4));
        assertEquals(12345, mZenModeEventLogger.getPackageUid(4));
    }

    @Test
    public void testZenModeEventLog_policyAllowChannels() {
        // when modes_api flag is on, ensure that any change in allow_channels gets logged,
        // even when there are no other changes.
        mTestFlagResolver.setFlagOverride(LOG_DND_STATE_EVENTS, true);
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);

        // Default zen config has allow channels = priority (aka on)
        setupZenConfig();

        // First just turn zen mode on
        mZenModeHelper.setManualZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "", null, Process.SYSTEM_UID);

        // Now change only the channels part of the policy; want to confirm that this'll be
        // reflected in the logs
        ZenModeConfig newConfig = mZenModeHelper.mConfig.copy();
        newConfig.allowPriorityChannels = false;
        mZenModeHelper.setNotificationPolicy(newConfig.toNotificationPolicy(),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, Process.SYSTEM_UID);

        // Total events: one for turning on, one for changing policy
        assertThat(mZenModeEventLogger.numLoggedChanges()).isEqualTo(2);

        // The first event is just turning DND on; make sure the policy is what we expect there
        // before it changes in the next stage
        assertThat(mZenModeEventLogger.getEventId(0))
                .isEqualTo(ZenModeEventLogger.ZenStateChangedEvent.DND_TURNED_ON.getId());
        DNDPolicyProto origDndProto = mZenModeEventLogger.getPolicyProto(0);
        checkDndProtoMatchesSetupZenConfig(origDndProto);
        assertThat(origDndProto.getAllowChannels().getNumber())
                .isEqualTo(DNDProtoEnums.CHANNEL_TYPE_PRIORITY);

        // Second message where we change the policy:
        //   - DND_POLICY_CHANGED (indicates only the policy changed and nothing else)
        //   - rule type: unknown (it's a policy change, not a rule change)
        //   - change is in allow channels, and final policy
        assertThat(mZenModeEventLogger.getEventId(1))
                .isEqualTo(ZenModeEventLogger.ZenStateChangedEvent.DND_POLICY_CHANGED.getId());
        assertThat(mZenModeEventLogger.getChangedRuleType(1))
                .isEqualTo(DNDProtoEnums.UNKNOWN_RULE);
        DNDPolicyProto dndProto = mZenModeEventLogger.getPolicyProto(1);
        assertThat(dndProto.getAllowChannels().getNumber())
                .isEqualTo(DNDProtoEnums.CHANNEL_TYPE_NONE);
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void testZenModeEventLog_ruleWithInterruptionFilterAll_notLoggedAsDndChange() {
        mTestFlagResolver.setFlagOverride(LOG_DND_STATE_EVENTS, true);
        setupZenConfig();

        // An app adds an automatic zen rule
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "cls"),
                Uri.parse("condition"),
                null,
                NotificationManager.INTERRUPTION_FILTER_ALL, true);
        String id = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule,
                UPDATE_ORIGIN_APP, "test", CUSTOM_PKG_UID);

        // Event 1: App activates the rule automatically.
        mZenModeHelper.setAutomaticZenRuleState(id,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE, SOURCE_SCHEDULE),
                UPDATE_ORIGIN_APP, CUSTOM_PKG_UID);

        // Event 2: App deactivates the rule automatically.
        mZenModeHelper.setAutomaticZenRuleState(id,
                new Condition(zenRule.getConditionId(), "", STATE_FALSE, SOURCE_SCHEDULE),
                UPDATE_ORIGIN_APP, CUSTOM_PKG_UID);

        // In total, this represents 2 events.
        assertEquals(2, mZenModeEventLogger.numLoggedChanges());

        // However, they are not DND_TURNED_ON/_OFF (no notification filtering is taking place).
        // Also, no consolidated ZenPolicy is logged (because of the same reason).
        assertThat(mZenModeEventLogger.getEventId(0)).isEqualTo(
                ZenModeEventLogger.ZenStateChangedEvent.DND_ACTIVE_RULES_CHANGED.getId());
        assertThat(mZenModeEventLogger.getNumRulesActive(0)).isEqualTo(1);
        assertThat(mZenModeEventLogger.getPolicyProto(0)).isNull();

        assertThat(mZenModeEventLogger.getEventId(1)).isEqualTo(
                ZenModeEventLogger.ZenStateChangedEvent.DND_ACTIVE_RULES_CHANGED.getId());
        assertThat(mZenModeEventLogger.getNumRulesActive(1)).isEqualTo(0);
        assertThat(mZenModeEventLogger.getPolicyProto(1)).isNull();
    }

    @Test
    public void testUpdateConsolidatedPolicy_defaultRulesOnly() {
        setupZenConfig();

        // When there's one automatic rule active and it doesn't specify a policy, test that the
        // resulting consolidated policy is one that matches the default rule settings.
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);

        // enable the rule
        mZenModeHelper.setAutomaticZenRuleState(id,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, Process.SYSTEM_UID);

        // inspect the consolidated policy. Based on setupZenConfig() values.
        assertFalse(mZenModeHelper.mConsolidatedPolicy.allowAlarms());
        assertFalse(mZenModeHelper.mConsolidatedPolicy.allowMedia());
        assertFalse(mZenModeHelper.mConsolidatedPolicy.allowSystem());
        assertTrue(mZenModeHelper.mConsolidatedPolicy.allowReminders());
        assertTrue(mZenModeHelper.mConsolidatedPolicy.allowCalls());
        assertEquals(PRIORITY_SENDERS_STARRED, mZenModeHelper.mConsolidatedPolicy.allowCallsFrom());
        assertTrue(mZenModeHelper.mConsolidatedPolicy.allowMessages());
        assertTrue(mZenModeHelper.mConsolidatedPolicy.allowConversations());
        assertTrue(mZenModeHelper.mConsolidatedPolicy.allowRepeatCallers());
        assertFalse(mZenModeHelper.mConsolidatedPolicy.showBadges());
    }

    @Test
    public void testUpdateConsolidatedPolicy_customPolicyOnly() {
        setupZenConfig();

        // when there's only one automatic rule active and it has a custom policy, make sure that's
        // what the consolidated policy reflects whether or not it's stricter than what the default
        // would specify.
        ZenPolicy customPolicy = new ZenPolicy.Builder()
                .allowAlarms(true)  // more lenient than default
                .allowMedia(true)  // more lenient than default
                .allowRepeatCallers(false)  // more restrictive than default
                .allowCalls(ZenPolicy.PEOPLE_TYPE_NONE)  // more restrictive than default
                .showBadges(true)  // more lenient
                .showPeeking(false)  // more restrictive
                .build();

        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                customPolicy,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);

        // enable the rule; this will update the consolidated policy
        mZenModeHelper.setAutomaticZenRuleState(id,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, Process.SYSTEM_UID);

        // since this is the only active rule, the consolidated policy should match the custom
        // policy for every field specified, and take default values for unspecified things
        assertTrue(mZenModeHelper.mConsolidatedPolicy.allowAlarms());  // custom
        assertTrue(mZenModeHelper.mConsolidatedPolicy.allowMedia());  // custom
        assertFalse(mZenModeHelper.mConsolidatedPolicy.allowSystem());  // default
        assertTrue(mZenModeHelper.mConsolidatedPolicy.allowReminders());  // default
        assertFalse(mZenModeHelper.mConsolidatedPolicy.allowCalls());  // custom
        assertTrue(mZenModeHelper.mConsolidatedPolicy.allowMessages()); // default
        assertTrue(mZenModeHelper.mConsolidatedPolicy.allowConversations());  // default
        assertFalse(mZenModeHelper.mConsolidatedPolicy.allowRepeatCallers());  // custom
        assertTrue(mZenModeHelper.mConsolidatedPolicy.showBadges());  // custom
        assertFalse(mZenModeHelper.mConsolidatedPolicy.showPeeking());  // custom
    }

    @Test
    public void testUpdateConsolidatedPolicy_defaultAndCustomActive() {
        setupZenConfig();

        // when there are two rules active, one inheriting the default policy and one setting its
        // own custom policy, they should be merged to form the most restrictive combination.

        // rule 1: no custom policy
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);

        // enable rule 1
        mZenModeHelper.setAutomaticZenRuleState(id,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, Process.SYSTEM_UID);

        // custom policy for rule 2
        ZenPolicy customPolicy = new ZenPolicy.Builder()
                .allowAlarms(true)  // more lenient than default
                .allowMedia(true)  // more lenient than default
                .allowRepeatCallers(false)  // more restrictive than default
                .allowCalls(ZenPolicy.PEOPLE_TYPE_NONE)  // more restrictive than default
                .showBadges(true)  // more lenient
                .showPeeking(false)  // more restrictive
                .build();

        AutomaticZenRule zenRule2 = new AutomaticZenRule("name2",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                customPolicy,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id2 = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule2,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);

        // enable rule 2; this will update the consolidated policy
        mZenModeHelper.setAutomaticZenRuleState(id2,
                new Condition(zenRule2.getConditionId(), "", STATE_TRUE),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, Process.SYSTEM_UID);

        // now both rules should be on, and the consolidated policy should reflect the most
        // restrictive option of each of the two
        assertFalse(mZenModeHelper.mConsolidatedPolicy.allowAlarms());  // default stricter
        assertFalse(mZenModeHelper.mConsolidatedPolicy.allowMedia());  // default stricter
        assertFalse(mZenModeHelper.mConsolidatedPolicy.allowSystem());  // default, unset in custom
        assertTrue(mZenModeHelper.mConsolidatedPolicy.allowReminders());  // default
        assertFalse(mZenModeHelper.mConsolidatedPolicy.allowCalls());  // custom stricter
        assertTrue(mZenModeHelper.mConsolidatedPolicy.allowMessages()); // default, unset in custom
        assertTrue(mZenModeHelper.mConsolidatedPolicy.allowConversations());  // default
        assertFalse(mZenModeHelper.mConsolidatedPolicy.allowRepeatCallers());  // custom stricter
        assertFalse(mZenModeHelper.mConsolidatedPolicy.showBadges());  // default stricter
        assertFalse(mZenModeHelper.mConsolidatedPolicy.showPeeking());  // custom stricter
    }

    @Test
    public void testUpdateConsolidatedPolicy_allowChannels() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
        setupZenConfig();

        // one rule, custom policy, allows channels
        ZenPolicy customPolicy = new ZenPolicy.Builder()
                .allowChannels(ZenPolicy.CHANNEL_TYPE_PRIORITY)
                .build();

        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                customPolicy,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);

        // enable the rule; this will update the consolidated policy
        mZenModeHelper.setAutomaticZenRuleState(id,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, Process.SYSTEM_UID);

        // confirm that channels make it through
        assertTrue(mZenModeHelper.mConsolidatedPolicy.allowPriorityChannels());

        // add new rule with policy that disallows channels
        ZenPolicy strictPolicy = new ZenPolicy.Builder()
                .allowChannels(ZenPolicy.CHANNEL_TYPE_NONE)
                .build();

        AutomaticZenRule zenRule2 = new AutomaticZenRule("name2",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                strictPolicy,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id2 = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule2,
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);

        // enable rule 2; this will update the consolidated policy
        mZenModeHelper.setAutomaticZenRuleState(id2,
                new Condition(zenRule2.getConditionId(), "", STATE_TRUE),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, Process.SYSTEM_UID);

        // rule 2 should override rule 1
        assertFalse(mZenModeHelper.mConsolidatedPolicy.allowPriorityChannels());
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void testUpdateConsolidatedPolicy_ignoresActiveRulesWithInterruptionFilterAll() {
        setupZenConfig();

        // Rules with INTERRUPTION_FILTER_ALL are skipped when calculating consolidated policy.
        // Note: rules with filter != PRIORITY should not have a custom policy. However, as of V
        // this is only validated on rule addition, but not on rule update. :/

        // Rule 1: PRIORITY, custom policy but not very strict (in fact, less strict than default).
        AutomaticZenRule zenRuleWithPriority = new AutomaticZenRule("Priority",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "cls"),
                Uri.parse("priority"),
                new ZenPolicy.Builder().allowMedia(true).build(),
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String rule1Id = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                zenRuleWithPriority, UPDATE_ORIGIN_APP, "test", CUSTOM_PKG_UID);
        mZenModeHelper.setAutomaticZenRuleState(rule1Id,
                new Condition(zenRuleWithPriority.getConditionId(), "", STATE_TRUE),
                UPDATE_ORIGIN_APP, CUSTOM_PKG_UID);

        // Rule 2: ALL, but somehow with a super strict ZenPolicy.
        AutomaticZenRule zenRuleWithAll = new AutomaticZenRule("All",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "cls"),
                Uri.parse("priority"),
                new ZenPolicy.Builder().disallowAllSounds().build(),
                NotificationManager.INTERRUPTION_FILTER_ALL, true);
        String rule2Id = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                zenRuleWithAll, UPDATE_ORIGIN_APP, "test", CUSTOM_PKG_UID);
        mZenModeHelper.setAutomaticZenRuleState(rule2Id,
                new Condition(zenRuleWithPriority.getConditionId(), "", STATE_TRUE),
                UPDATE_ORIGIN_APP, CUSTOM_PKG_UID);

        // Consolidated Policy should be default + rule1.
        assertThat(mZenModeHelper.mConsolidatedPolicy.allowAlarms()).isFalse();  // default
        assertThat(mZenModeHelper.mConsolidatedPolicy.allowMedia()).isTrue(); // priority rule
        assertThat(mZenModeHelper.mConsolidatedPolicy.allowSystem()).isFalse();  // default
        assertThat(mZenModeHelper.mConsolidatedPolicy.allowReminders()).isTrue();  // default
        assertThat(mZenModeHelper.mConsolidatedPolicy.allowCalls()).isTrue();  // default
        assertThat(mZenModeHelper.mConsolidatedPolicy.allowMessages()).isTrue(); // default
        assertThat(mZenModeHelper.mConsolidatedPolicy.allowConversations()).isTrue();  // default
        assertThat(mZenModeHelper.mConsolidatedPolicy.allowRepeatCallers()).isTrue(); // default
    }

    @Test
    public void zenRuleToAutomaticZenRule_allFields() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
        when(mPackageManager.getPackagesForUid(anyInt())).thenReturn(
                new String[] {OWNER.getPackageName()});

        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();
        rule.configurationActivity = CONFIG_ACTIVITY;
        rule.component = OWNER;
        rule.conditionId = CONDITION_ID;
        rule.condition = CONDITION_TRUE;
        rule.enabled = ENABLED;
        rule.creationTime = 123;
        rule.id = "id";
        rule.zenMode = INTERRUPTION_FILTER_ZR;
        rule.modified = true;
        rule.name = NAME;
        rule.snoozing = true;
        rule.pkg = OWNER.getPackageName();
        rule.zenPolicy = POLICY;

        rule.allowManualInvocation = ALLOW_MANUAL;
        rule.type = TYPE;
        rule.userModifiedFields = AutomaticZenRule.FIELD_NAME;
        rule.iconResName = ICON_RES_NAME;
        rule.triggerDescription = TRIGGER_DESC;

        mZenModeHelper.mConfig.automaticRules.put(rule.id, rule);
        AutomaticZenRule actual = mZenModeHelper.getAutomaticZenRule(rule.id);

        assertEquals(NAME, actual.getName());
        assertEquals(OWNER, actual.getOwner());
        assertEquals(CONDITION_ID, actual.getConditionId());
        assertEquals(INTERRUPTION_FILTER_AZR, actual.getInterruptionFilter());
        assertEquals(ENABLED, actual.isEnabled());
        assertEquals(POLICY, actual.getZenPolicy());
        assertEquals(CONFIG_ACTIVITY, actual.getConfigurationActivity());
        assertEquals(TYPE, actual.getType());
        assertEquals(AutomaticZenRule.FIELD_NAME, actual.getUserModifiedFields());
        assertEquals(ALLOW_MANUAL, actual.isManualInvocationAllowed());
        assertEquals(CREATION_TIME, actual.getCreationTime());
        assertEquals(OWNER.getPackageName(), actual.getPackageName());
        assertEquals(ICON_RES_ID, actual.getIconResId());
        assertEquals(TRIGGER_DESC, actual.getTriggerDescription());
    }

    @Test
    public void automaticZenRuleToZenRule_allFields() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
        when(mPackageManager.getPackagesForUid(anyInt())).thenReturn(
                new String[] {OWNER.getPackageName()});

        AutomaticZenRule azr = new AutomaticZenRule.Builder(NAME, CONDITION_ID)
                .setEnabled(true)
                .setConfigurationActivity(CONFIG_ACTIVITY)
                .setTriggerDescription(TRIGGER_DESC)
                .setCreationTime(CREATION_TIME)
                .setIconResId(ICON_RES_ID)
                .setZenPolicy(POLICY)
                .setInterruptionFilter(INTERRUPTION_FILTER_AZR)
                .setType(TYPE)
                .setOwner(OWNER)
                .setManualInvocationAllowed(ALLOW_MANUAL)
                .build();

        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();

        mZenModeHelper.populateZenRule(OWNER.getPackageName(), azr, rule, UPDATE_ORIGIN_APP, true);

        assertEquals(NAME, rule.name);
        assertEquals(OWNER, rule.component);
        assertEquals(CONDITION_ID, rule.conditionId);
        assertEquals(INTERRUPTION_FILTER_ZR, rule.zenMode);
        assertEquals(ENABLED, rule.enabled);
        assertEquals(POLICY, rule.zenPolicy);
        assertEquals(CONFIG_ACTIVITY, rule.configurationActivity);
        assertEquals(TYPE, rule.type);
        assertEquals(ALLOW_MANUAL, rule.allowManualInvocation);
        assertEquals(OWNER.getPackageName(), rule.getPkg());
        assertEquals(ICON_RES_NAME, rule.iconResName);
        // Because the origin of the update is the app, we don't expect the bitmask to change.
        assertEquals(0, rule.userModifiedFields);
        assertEquals(TRIGGER_DESC, rule.triggerDescription);
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void automaticZenRuleToZenRule_updatesNameUnlessUserModified() {
        // Add a starting rule with the name OriginalName.
        AutomaticZenRule azrBase = new AutomaticZenRule.Builder("OriginalName", CONDITION_ID)
                .setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY)
                .build();
        String ruleId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                azrBase, UPDATE_ORIGIN_APP, "reason", Process.SYSTEM_UID);
        AutomaticZenRule rule = mZenModeHelper.getAutomaticZenRule(ruleId);

        // Checks the name can be changed by the app because the user has not modified it.
        AutomaticZenRule azrUpdate = new AutomaticZenRule.Builder(rule)
                .setName("NewName")
                .build();
        mZenModeHelper.updateAutomaticZenRule(ruleId, azrUpdate, UPDATE_ORIGIN_APP, "reason",
                Process.SYSTEM_UID);
        rule = mZenModeHelper.getAutomaticZenRule(ruleId);
        assertThat(rule.getName()).isEqualTo("NewName");
        assertThat(rule.canUpdate()).isTrue();

        // The user modifies some other field in the rule, which makes the rule as a whole not
        // app modifiable.
        azrUpdate = new AutomaticZenRule.Builder(rule)
                .setInterruptionFilter(INTERRUPTION_FILTER_ALARMS)
                .build();
        mZenModeHelper.updateAutomaticZenRule(ruleId, azrUpdate, UPDATE_ORIGIN_USER, "reason",
                Process.SYSTEM_UID);
        rule = mZenModeHelper.getAutomaticZenRule(ruleId);
        assertThat(rule.getUserModifiedFields())
                .isEqualTo(AutomaticZenRule.FIELD_INTERRUPTION_FILTER);
        assertThat(rule.canUpdate()).isFalse();

        // ...but the app can still modify the name, because the name itself hasn't been modified
        // by the user.
        azrUpdate = new AutomaticZenRule.Builder(rule)
                .setName("NewAppName")
                .build();
        mZenModeHelper.updateAutomaticZenRule(ruleId, azrUpdate, UPDATE_ORIGIN_APP, "reason",
                Process.SYSTEM_UID);
        rule = mZenModeHelper.getAutomaticZenRule(ruleId);
        assertThat(rule.getName()).isEqualTo("NewAppName");

        // The user modifies the name.
        azrUpdate = new AutomaticZenRule.Builder(rule)
                .setName("UserProvidedName")
                .build();
        mZenModeHelper.updateAutomaticZenRule(ruleId, azrUpdate, UPDATE_ORIGIN_USER, "reason",
                Process.SYSTEM_UID);
        rule = mZenModeHelper.getAutomaticZenRule(ruleId);
        assertThat(rule.getName()).isEqualTo("UserProvidedName");
        assertThat(rule.getUserModifiedFields()).isEqualTo(AutomaticZenRule.FIELD_NAME
                | AutomaticZenRule.FIELD_INTERRUPTION_FILTER);

        // The app is no longer able to modify the name.
        azrUpdate = new AutomaticZenRule.Builder(rule)
                .setName("NewAppName")
                .build();
        mZenModeHelper.updateAutomaticZenRule(ruleId, azrUpdate, UPDATE_ORIGIN_APP, "reason",
                Process.SYSTEM_UID);
        rule = mZenModeHelper.getAutomaticZenRule(ruleId);
        assertThat(rule.getName()).isEqualTo("UserProvidedName");
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void automaticZenRuleToZenRule_updatesBitmaskAndValueForUserOrigin() {
        // Adds a starting rule with empty zen policies and device effects
        AutomaticZenRule azrBase = new AutomaticZenRule.Builder(NAME, CONDITION_ID)
                .setZenPolicy(new ZenPolicy.Builder().build())
                .setDeviceEffects(new ZenDeviceEffects.Builder().build())
                .build();
        // Adds the rule using the app, to avoid having any user modified bits set.
        String ruleId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                azrBase, UPDATE_ORIGIN_APP, "reason", Process.SYSTEM_UID);
        AutomaticZenRule rule = mZenModeHelper.getAutomaticZenRule(ruleId);

        // Modifies the zen policy and device effects
        ZenPolicy policy = new ZenPolicy.Builder(rule.getZenPolicy())
                .allowChannels(ZenPolicy.CHANNEL_TYPE_PRIORITY)
                .build();
        ZenDeviceEffects deviceEffects =
                new ZenDeviceEffects.Builder(rule.getDeviceEffects())
                .setShouldDisplayGrayscale(true)
                .build();
        AutomaticZenRule azrUpdate = new AutomaticZenRule.Builder(rule)
                .setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY)
                .setZenPolicy(policy)
                .setDeviceEffects(deviceEffects)
                .build();

        // Update the rule with the AZR from origin user.
        mZenModeHelper.updateAutomaticZenRule(ruleId, azrUpdate, UPDATE_ORIGIN_USER, "reason",
                Process.SYSTEM_UID);
        rule = mZenModeHelper.getAutomaticZenRule(ruleId);

        // UPDATE_ORIGIN_USER should change the bitmask and change the values.
        assertThat(rule.getInterruptionFilter()).isEqualTo(INTERRUPTION_FILTER_PRIORITY);
        assertThat(rule.getUserModifiedFields())
                .isEqualTo(AutomaticZenRule.FIELD_INTERRUPTION_FILTER);
        assertThat(rule.getZenPolicy().getUserModifiedFields())
                .isEqualTo(ZenPolicy.FIELD_ALLOW_CHANNELS);
        assertThat(rule.getZenPolicy().getAllowedChannels())
                .isEqualTo(ZenPolicy.CHANNEL_TYPE_PRIORITY);
        assertThat(rule.getDeviceEffects().getUserModifiedFields())
                .isEqualTo(ZenDeviceEffects.FIELD_GRAYSCALE);
        assertThat(rule.getDeviceEffects().shouldDisplayGrayscale()).isTrue();
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void automaticZenRuleToZenRule_doesNotUpdateValuesForInitUserOrigin() {
        // Adds a starting rule with empty zen policies and device effects
        AutomaticZenRule azrBase = new AutomaticZenRule.Builder(NAME, CONDITION_ID)
                .setInterruptionFilter(INTERRUPTION_FILTER_ALL) // Already the default, no change
                .setZenPolicy(new ZenPolicy.Builder()
                        .allowReminders(false)
                        .build())
                .setDeviceEffects(new ZenDeviceEffects.Builder()
                        .setShouldDisplayGrayscale(false)
                        .build())
                .build();
        // Adds the rule using the user, to set user-modified bits.
        String ruleId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                azrBase, UPDATE_ORIGIN_USER, "reason", Process.SYSTEM_UID);
        AutomaticZenRule rule = mZenModeHelper.getAutomaticZenRule(ruleId);
        assertThat(rule.canUpdate()).isFalse();
        assertThat(rule.getUserModifiedFields()).isEqualTo(AutomaticZenRule.FIELD_NAME);

        ZenPolicy policy = new ZenPolicy.Builder(rule.getZenPolicy())
                .allowReminders(true)
                .build();
        ZenDeviceEffects deviceEffects = new ZenDeviceEffects.Builder(rule.getDeviceEffects())
                .setShouldDisplayGrayscale(true)
                .build();
        AutomaticZenRule azrUpdate = new AutomaticZenRule.Builder(rule)
                .setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY)
                .setZenPolicy(policy)
                .setDeviceEffects(deviceEffects)
                .build();

        // Attempts to update the rule with the AZR from origin init user.
        mZenModeHelper.updateAutomaticZenRule(ruleId, azrUpdate, UPDATE_ORIGIN_INIT_USER, "reason",
                Process.SYSTEM_UID);
        AutomaticZenRule unchangedRule = mZenModeHelper.getAutomaticZenRule(ruleId);

        // UPDATE_ORIGIN_INIT_USER does not change the bitmask or values if rule is user modified.
        // TODO: b/318506692 - Remove once we check that INIT origins can't call add/updateAZR.
        assertThat(unchangedRule.getUserModifiedFields()).isEqualTo(rule.getUserModifiedFields());
        assertThat(unchangedRule.getInterruptionFilter()).isEqualTo(INTERRUPTION_FILTER_ALL);
        assertThat(unchangedRule.getZenPolicy().getUserModifiedFields()).isEqualTo(
                rule.getZenPolicy().getUserModifiedFields());
        assertThat(unchangedRule.getZenPolicy().getPriorityCategoryReminders()).isEqualTo(
                ZenPolicy.STATE_DISALLOW);
        assertThat(unchangedRule.getDeviceEffects().getUserModifiedFields()).isEqualTo(
                rule.getDeviceEffects().getUserModifiedFields());
        assertThat(unchangedRule.getDeviceEffects().shouldDisplayGrayscale()).isFalse();

        // Creates a new rule with the AZR from origin init user.
        String newRuleId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                azrUpdate, UPDATE_ORIGIN_INIT_USER, "reason", Process.SYSTEM_UID);
        AutomaticZenRule newRule = mZenModeHelper.getAutomaticZenRule(newRuleId);

        // UPDATE_ORIGIN_INIT_USER does change the values if the rule is new,
        // but does not update the bitmask.
        assertThat(newRule.getUserModifiedFields()).isEqualTo(0);
        assertThat(newRule.getInterruptionFilter()).isEqualTo(INTERRUPTION_FILTER_PRIORITY);
        assertThat(newRule.getZenPolicy().getUserModifiedFields()).isEqualTo(0);
        assertThat(newRule.getZenPolicy().getPriorityCategoryReminders())
                .isEqualTo(ZenPolicy.STATE_ALLOW);
        assertThat(newRule.getDeviceEffects().getUserModifiedFields()).isEqualTo(0);
        assertThat(newRule.getDeviceEffects().shouldDisplayGrayscale()).isTrue();
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void automaticZenRuleToZenRule_updatesValuesForSystemUiOrigin() {
        // Adds a starting rule with empty zen policies and device effects
        AutomaticZenRule azrBase = new AutomaticZenRule.Builder(NAME, CONDITION_ID)
                .setInterruptionFilter(INTERRUPTION_FILTER_ALL)
                .setZenPolicy(new ZenPolicy.Builder()
                        .allowReminders(false)
                        .build())
                .setDeviceEffects(new ZenDeviceEffects.Builder()
                        .setShouldDisplayGrayscale(false)
                        .build())
                .build();
        // Adds the rule using the app, to avoid having any user modified bits set.
        String ruleId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                azrBase, UPDATE_ORIGIN_APP, "reason", Process.SYSTEM_UID);
        AutomaticZenRule rule = mZenModeHelper.getAutomaticZenRule(ruleId);

        // Modifies the zen policy and device effects
        ZenPolicy policy = new ZenPolicy.Builder(rule.getZenPolicy())
                .allowReminders(true)
                .build();
        ZenDeviceEffects deviceEffects =
                new ZenDeviceEffects.Builder(rule.getDeviceEffects())
                        .setShouldDisplayGrayscale(true)
                        .build();
        AutomaticZenRule azrUpdate = new AutomaticZenRule.Builder(rule)
                .setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY)
                .setZenPolicy(policy)
                .setDeviceEffects(deviceEffects)
                .build();

        // Update the rule with the AZR from origin systemUI.
        mZenModeHelper.updateAutomaticZenRule(ruleId, azrUpdate, UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI,
                "reason", Process.SYSTEM_UID);
        rule = mZenModeHelper.getAutomaticZenRule(ruleId);

        // UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI should change the value but NOT update the bitmask.
        assertThat(rule.getUserModifiedFields()).isEqualTo(0);
        assertThat(rule.getZenPolicy().getUserModifiedFields()).isEqualTo(0);
        assertThat(rule.getZenPolicy().getPriorityCategoryReminders())
                .isEqualTo(ZenPolicy.STATE_ALLOW);
        assertThat(rule.getDeviceEffects().getUserModifiedFields()).isEqualTo(0);
        assertThat(rule.getDeviceEffects().shouldDisplayGrayscale()).isTrue();
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void automaticZenRuleToZenRule_updatesValuesIfRuleNotUserModified() {
        // Adds a starting rule with empty zen policies and device effects
        AutomaticZenRule azrBase = new AutomaticZenRule.Builder(NAME, CONDITION_ID)
                .setInterruptionFilter(INTERRUPTION_FILTER_ALL)
                .setZenPolicy(new ZenPolicy.Builder()
                        .allowReminders(false)
                        .build())
                .setDeviceEffects(new ZenDeviceEffects.Builder()
                        .setShouldDisplayGrayscale(false)
                        .build())
                .build();
        // Adds the rule using the app, to avoid having any user modified bits set.
        String ruleId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                azrBase, UPDATE_ORIGIN_APP, "reason", Process.SYSTEM_UID);
        AutomaticZenRule rule = mZenModeHelper.getAutomaticZenRule(ruleId);
        assertThat(rule.canUpdate()).isTrue();

        ZenPolicy policy = new ZenPolicy.Builder()
                .allowReminders(true)
                .build();
        ZenDeviceEffects deviceEffects = new ZenDeviceEffects.Builder()
                .setShouldDisplayGrayscale(true)
                .build();
        AutomaticZenRule azrUpdate =  new AutomaticZenRule.Builder(rule)
                .setInterruptionFilter(INTERRUPTION_FILTER_ALARMS)
                .setZenPolicy(policy)
                .setDeviceEffects(deviceEffects)
                .build();

        // Since the rule is not already user modified, UPDATE_ORIGIN_UNKNOWN can modify the rule.
        // The bitmask is not modified.
        mZenModeHelper.updateAutomaticZenRule(ruleId, azrUpdate, UPDATE_ORIGIN_UNKNOWN, "reason",
                Process.SYSTEM_UID);
        AutomaticZenRule unchangedRule = mZenModeHelper.getAutomaticZenRule(ruleId);

        assertThat(unchangedRule.getUserModifiedFields()).isEqualTo(rule.getUserModifiedFields());
        assertThat(unchangedRule.getInterruptionFilter()).isEqualTo(INTERRUPTION_FILTER_ALARMS);
        assertThat(unchangedRule.getZenPolicy().getUserModifiedFields()).isEqualTo(
                rule.getZenPolicy().getUserModifiedFields());
        assertThat(unchangedRule.getZenPolicy().getPriorityCategoryReminders())
                .isEqualTo(ZenPolicy.STATE_ALLOW);
        assertThat(unchangedRule.getDeviceEffects().getUserModifiedFields()).isEqualTo(
                rule.getDeviceEffects().getUserModifiedFields());
        assertThat(unchangedRule.getDeviceEffects().shouldDisplayGrayscale()).isTrue();

        // Creates another rule, this time from user. This will have user modified bits set.
        String ruleIdUser = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                azrBase, UPDATE_ORIGIN_USER, "reason", Process.SYSTEM_UID);
        AutomaticZenRule ruleUser = mZenModeHelper.getAutomaticZenRule(ruleIdUser);
        assertThat(ruleUser.canUpdate()).isFalse();

        // Zen rule update coming from unknown origin. This cannot fully update the rule, because
        // the rule is already considered user modified.
        mZenModeHelper.updateAutomaticZenRule(ruleIdUser, azrUpdate, UPDATE_ORIGIN_UNKNOWN,
                "reason", Process.SYSTEM_UID);
        ruleUser = mZenModeHelper.getAutomaticZenRule(ruleIdUser);

        // UPDATE_ORIGIN_UNKNOWN can only change the value if the rule is not already user modified,
        // so the rule is not changed, and neither is the bitmask.
        assertThat(ruleUser.getInterruptionFilter()).isEqualTo(INTERRUPTION_FILTER_ALL);
        // Interruption Filter All is the default value, so it's not included as a modified field.
        assertThat(ruleUser.getUserModifiedFields() | AutomaticZenRule.FIELD_NAME).isGreaterThan(0);
        assertThat(ruleUser.getZenPolicy().getUserModifiedFields()
                | ZenPolicy.FIELD_PRIORITY_CATEGORY_REMINDERS).isGreaterThan(0);
        assertThat(ruleUser.getZenPolicy().getPriorityCategoryReminders())
                .isEqualTo(ZenPolicy.STATE_DISALLOW);
        assertThat(ruleUser.getDeviceEffects().getUserModifiedFields()
                | ZenDeviceEffects.FIELD_GRAYSCALE).isGreaterThan(0);
        assertThat(ruleUser.getDeviceEffects().shouldDisplayGrayscale()).isFalse();
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void automaticZenRuleToZenRule_updatesValuesIfRuleNew() {
        // Adds a starting rule with empty zen policies and device effects
        AutomaticZenRule azrBase = new AutomaticZenRule.Builder(NAME, CONDITION_ID)
                .setInterruptionFilter(INTERRUPTION_FILTER_ALARMS)
                .setZenPolicy(new ZenPolicy.Builder()
                        .allowReminders(true)
                        .build())
                .setDeviceEffects(new ZenDeviceEffects.Builder()
                        .setShouldDisplayGrayscale(true)
                        .build())
                .build();
        // Adds the rule using origin unknown, to show that a new rule is always allowed.
        String ruleId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                azrBase, UPDATE_ORIGIN_UNKNOWN, "reason", Process.SYSTEM_UID);
        AutomaticZenRule rule = mZenModeHelper.getAutomaticZenRule(ruleId);

        // The values are modified but the bitmask is not.
        assertThat(rule.canUpdate()).isTrue();
        assertThat(rule.getZenPolicy().getPriorityCategoryReminders())
                .isEqualTo(ZenPolicy.STATE_ALLOW);
        assertThat(rule.getDeviceEffects().shouldDisplayGrayscale()).isTrue();
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void automaticZenRuleToZenRule_nullDeviceEffectsUpdate() {
        // Adds a starting rule with empty zen policies and device effects
        AutomaticZenRule azrBase = new AutomaticZenRule.Builder(NAME, CONDITION_ID)
                .setDeviceEffects(new ZenDeviceEffects.Builder().build())
                .build();
        // Adds the rule using the app, to avoid having any user modified bits set.
        String ruleId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                azrBase, UPDATE_ORIGIN_APP, "reason", Process.SYSTEM_UID);
        AutomaticZenRule rule = mZenModeHelper.getAutomaticZenRule(ruleId);

        AutomaticZenRule azr = new AutomaticZenRule.Builder(azrBase)
                // Sets Device Effects to null
                .setDeviceEffects(null)
                .build();

        // Zen rule update coming from unknown origin, but since the rule isn't already
        // user modified, it can be updated.
        mZenModeHelper.updateAutomaticZenRule(ruleId, azr, UPDATE_ORIGIN_UNKNOWN, "reason",
                Process.SYSTEM_UID);
        rule = mZenModeHelper.getAutomaticZenRule(ruleId);

        // When AZR's ZenDeviceEffects is null, the updated rule's device effects will be null.
        assertThat(rule.getDeviceEffects()).isNull();
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void automaticZenRuleToZenRule_nullPolicyUpdate() {
        // Adds a starting rule with empty zen policies and device effects
        AutomaticZenRule azrBase = new AutomaticZenRule.Builder(NAME, CONDITION_ID)
                .setZenPolicy(new ZenPolicy.Builder().build())
                .build();
        // Adds the rule using the app, to avoid having any user modified bits set.
        String ruleId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                azrBase, UPDATE_ORIGIN_APP, "reason", Process.SYSTEM_UID);
        AutomaticZenRule rule = mZenModeHelper.getAutomaticZenRule(ruleId);
        assertThat(rule.canUpdate()).isTrue();

        AutomaticZenRule azr = new AutomaticZenRule.Builder(azrBase)
                // Set zen policy to null
                .setZenPolicy(null)
                .build();

        // Zen rule update coming from unknown origin, but since the rule isn't already
        // user modified, it can be updated.
        mZenModeHelper.updateAutomaticZenRule(ruleId, azr, UPDATE_ORIGIN_UNKNOWN, "reason",
                Process.SYSTEM_UID);
        rule = mZenModeHelper.getAutomaticZenRule(ruleId);

        // When AZR's ZenPolicy is null, we expect the updated rule's policy to be null.
        assertThat(rule.getZenPolicy()).isNull();
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void automaticZenRuleToZenRule_nullToNonNullPolicyUpdate() {
        when(mContext.checkCallingPermission(anyString()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        // Adds a starting rule with empty zen policies and device effects
        AutomaticZenRule azrBase = new AutomaticZenRule.Builder(NAME, CONDITION_ID)
                .setZenPolicy(null)
                // .setDeviceEffects(new ZenDeviceEffects.Builder().build())
                .build();
        // Adds the rule using the app, to avoid having any user modified bits set.
        String ruleId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                azrBase, UPDATE_ORIGIN_APP, "reason", Process.SYSTEM_UID);
        AutomaticZenRule rule = mZenModeHelper.getAutomaticZenRule(ruleId);
        assertThat(rule.canUpdate()).isTrue();

        // Create a fully populated ZenPolicy.
        ZenPolicy policy = new ZenPolicy.Builder()
                .allowChannels(ZenPolicy.CHANNEL_TYPE_NONE) // Differs from the default
                .allowReminders(true) // Differs from the default
                .allowEvents(true) // Differs from the default
                .allowConversations(ZenPolicy.CONVERSATION_SENDERS_IMPORTANT)
                .allowMessages(PEOPLE_TYPE_STARRED)
                .allowCalls(PEOPLE_TYPE_STARRED)
                .allowRepeatCallers(true)
                .allowAlarms(true)
                .allowMedia(true)
                .allowSystem(true) // Differs from the default
                .showFullScreenIntent(true) // Differs from the default
                .showLights(true) // Differs from the default
                .showPeeking(true) // Differs from the default
                .showStatusBarIcons(true)
                .showBadges(true)
                .showInAmbientDisplay(true) // Differs from the default
                .showInNotificationList(true)
                .build();
        AutomaticZenRule azr = new AutomaticZenRule.Builder(azrBase)
                .setZenPolicy(policy)
                .build();

        // Applies the update to the rule.
        // Default config defined in getDefaultConfigParser() is used as the original rule.
        mZenModeHelper.updateAutomaticZenRule(ruleId, azr, UPDATE_ORIGIN_USER, "reason",
                Process.SYSTEM_UID);
        rule = mZenModeHelper.getAutomaticZenRule(ruleId);

        // New ZenPolicy differs from the default config
        assertThat(rule.getZenPolicy()).isNotNull();
        assertThat(rule.getZenPolicy().getAllowedChannels()).isEqualTo(ZenPolicy.CHANNEL_TYPE_NONE);
        assertThat(rule.canUpdate()).isFalse();
        assertThat(rule.getZenPolicy().getUserModifiedFields()).isEqualTo(
                ZenPolicy.FIELD_ALLOW_CHANNELS
                | ZenPolicy.FIELD_PRIORITY_CATEGORY_REMINDERS
                | ZenPolicy.FIELD_PRIORITY_CATEGORY_EVENTS
                | ZenPolicy.FIELD_PRIORITY_CATEGORY_SYSTEM
                | ZenPolicy.FIELD_VISUAL_EFFECT_FULL_SCREEN_INTENT
                | ZenPolicy.FIELD_VISUAL_EFFECT_LIGHTS
                | ZenPolicy.FIELD_VISUAL_EFFECT_PEEK
                | ZenPolicy.FIELD_VISUAL_EFFECT_AMBIENT
        );
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void automaticZenRuleToZenRule_nullToNonNullDeviceEffectsUpdate() {
        // Adds a starting rule with empty zen policies and device effects
        AutomaticZenRule azrBase = new AutomaticZenRule.Builder(NAME, CONDITION_ID)
                .setDeviceEffects(null)
                .build();
        // Adds the rule using the app, to avoid having any user modified bits set.
        String ruleId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                azrBase, UPDATE_ORIGIN_APP, "reason", Process.SYSTEM_UID);
        AutomaticZenRule rule = mZenModeHelper.getAutomaticZenRule(ruleId);
        assertThat(rule.canUpdate()).isTrue();

        ZenDeviceEffects deviceEffects = new ZenDeviceEffects.Builder()
                .setShouldDisplayGrayscale(true)
                .build();
        AutomaticZenRule azr = new AutomaticZenRule.Builder(rule)
                .setDeviceEffects(deviceEffects)
                .build();

        // Applies the update to the rule.
        mZenModeHelper.updateAutomaticZenRule(ruleId, azr, UPDATE_ORIGIN_USER, "reason",
                Process.SYSTEM_UID);
        rule = mZenModeHelper.getAutomaticZenRule(ruleId);

        // New ZenDeviceEffects is used; all fields considered set, since previously were null.
        assertThat(rule.getDeviceEffects()).isNotNull();
        assertThat(rule.getDeviceEffects().shouldDisplayGrayscale()).isTrue();
        assertThat(rule.canUpdate()).isFalse();
        assertThat(rule.getDeviceEffects().getUserModifiedFields()).isEqualTo(
                ZenDeviceEffects.FIELD_GRAYSCALE);
    }

    @Test
    public void testUpdateAutomaticRule_disabled_triggersBroadcast() throws Exception {
        setupZenConfig();

        // Add a new automatic zen rule that's enabled
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        final String createdId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                zenRule, UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);

        CountDownLatch latch = new CountDownLatch(1);
        final int[] actualStatus = new int[1];
        ZenModeHelper.Callback callback = new ZenModeHelper.Callback() {
            @Override
            void onAutomaticRuleStatusChanged(int userId, String pkg, String id, int status) {
                if (Objects.equals(createdId, id)) {
                    actualStatus[0] = status;
                    latch.countDown();
                }
            }
        };
        mZenModeHelper.addCallback(callback);

        zenRule.setEnabled(false);
        mZenModeHelper.updateAutomaticZenRule(createdId, zenRule, UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI,
                "", Process.SYSTEM_UID);

        assertTrue(latch.await(500, TimeUnit.MILLISECONDS));
        assertEquals(AUTOMATIC_RULE_STATUS_DISABLED, actualStatus[0]);
    }

    @Test
    public void testUpdateAutomaticRule_enabled_triggersBroadcast() throws Exception {
        setupZenConfig();

        // Add a new automatic zen rule that's enabled
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, false);
        final String createdId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                zenRule, UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);

        CountDownLatch latch = new CountDownLatch(1);
        final int[] actualStatus = new int[1];
        ZenModeHelper.Callback callback = new ZenModeHelper.Callback() {
            @Override
            void onAutomaticRuleStatusChanged(int userId, String pkg, String id, int status) {
                if (Objects.equals(createdId, id)) {
                    actualStatus[0] = status;
                    latch.countDown();
                }
            }
        };
        mZenModeHelper.addCallback(callback);

        zenRule.setEnabled(true);
        mZenModeHelper.updateAutomaticZenRule(createdId, zenRule, UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI,
                "", Process.SYSTEM_UID);

        assertTrue(latch.await(500, TimeUnit.MILLISECONDS));
        assertEquals(AUTOMATIC_RULE_STATUS_ENABLED, actualStatus[0]);
    }

    @Test
    public void testUpdateAutomaticRule_activated_triggersBroadcast() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
        setupZenConfig();

        // Add a new automatic zen rule that's enabled
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        final String createdId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                zenRule, UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);

        CountDownLatch latch = new CountDownLatch(1);
        final int[] actualStatus = new int[1];
        ZenModeHelper.Callback callback = new ZenModeHelper.Callback() {
            @Override
            void onAutomaticRuleStatusChanged(int userId, String pkg, String id, int status) {
                if (Objects.equals(createdId, id)) {
                    actualStatus[0] = status;
                    latch.countDown();
                }
            }
        };
        mZenModeHelper.addCallback(callback);

        mZenModeHelper.setAutomaticZenRuleState(createdId,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, Process.SYSTEM_UID);

        assertTrue(latch.await(500, TimeUnit.MILLISECONDS));
        assertEquals(AUTOMATIC_RULE_STATUS_ACTIVATED, actualStatus[0]);
    }

    @Test
    public void testUpdateAutomaticRule_deactivatedByUser_triggersBroadcast() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
        setupZenConfig();

        // Add a new automatic zen rule that's enabled
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        final String createdId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                zenRule, UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);

        CountDownLatch latch = new CountDownLatch(1);
        final int[] actualStatus = new int[2];
        ZenModeHelper.Callback callback = new ZenModeHelper.Callback() {
            int i = 0;
            @Override
            void onAutomaticRuleStatusChanged(int userId, String pkg, String id, int status) {
                if (Objects.equals(createdId, id)) {
                    actualStatus[i++] = status;
                    latch.countDown();
                }
            }
        };
        mZenModeHelper.addCallback(callback);

        mZenModeHelper.setAutomaticZenRuleState(createdId,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, Process.SYSTEM_UID);

        mZenModeHelper.setManualZenMode(Global.ZEN_MODE_OFF, null, UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI,
                null, "", Process.SYSTEM_UID);

        assertTrue(latch.await(500, TimeUnit.MILLISECONDS));
        assertEquals(AUTOMATIC_RULE_STATUS_DEACTIVATED, actualStatus[1]);
    }

    @Test
    public void testUpdateAutomaticRule_deactivatedByApp_triggersBroadcast() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
        setupZenConfig();

        // Add a new automatic zen rule that's enabled
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        final String createdId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                zenRule, UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);

        CountDownLatch latch = new CountDownLatch(1);
        final int[] actualStatus = new int[2];
        ZenModeHelper.Callback callback = new ZenModeHelper.Callback() {
            int i = 0;
            @Override
            void onAutomaticRuleStatusChanged(int userId, String pkg, String id, int status) {
                if (Objects.equals(createdId, id)) {
                    actualStatus[i++] = status;
                    latch.countDown();
                }
            }
        };
        mZenModeHelper.addCallback(callback);

        mZenModeHelper.setAutomaticZenRuleState(createdId,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, Process.SYSTEM_UID);

        mZenModeHelper.setAutomaticZenRuleState(createdId,
                new Condition(zenRule.getConditionId(), "", STATE_FALSE),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, Process.SYSTEM_UID);

        assertTrue(latch.await(500, TimeUnit.MILLISECONDS));
        assertEquals(AUTOMATIC_RULE_STATUS_DEACTIVATED, actualStatus[1]);
    }

    @Test
    public void testUpdateAutomaticRule_unsnoozes() throws IllegalArgumentException {
        setupZenConfig();

        // Add a new automatic zen rule that's enabled
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        final String createdId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(),
                zenRule, UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, "test", Process.SYSTEM_UID);

        // Event 1: Mimic the rule coming on automatically by setting the Condition to STATE_TRUE
        mZenModeHelper.setAutomaticZenRuleState(createdId,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI, Process.SYSTEM_UID);

        // Event 2: Snooze rule by turning off DND
        mZenModeHelper.setManualZenMode(Global.ZEN_MODE_OFF, null, UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI,
                "", null, Process.SYSTEM_UID);

        // Event 3: "User" turns off the automatic rule (sets it to not enabled)
        zenRule.setEnabled(false);
        mZenModeHelper.updateAutomaticZenRule(createdId, zenRule, UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI,
                "", Process.SYSTEM_UID);

        assertEquals(false, mZenModeHelper.mConfig.automaticRules.get(createdId).snoozing);
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void removeAutomaticZenRule_propagatesOriginToEffectsApplier() {
        mZenModeHelper.setDeviceEffectsApplier(mDeviceEffectsApplier);
        reset(mDeviceEffectsApplier);

        String ruleId = addRuleWithEffects(new ZenDeviceEffects.Builder()
                .setShouldSuppressAmbientDisplay(true)
                .setShouldDimWallpaper(true)
                .build());
        mZenModeHelper.setAutomaticZenRuleState(ruleId, CONDITION_TRUE, UPDATE_ORIGIN_APP,
                CUSTOM_PKG_UID);
        mTestableLooper.processAllMessages();
        verify(mDeviceEffectsApplier).apply(any(), eq(UPDATE_ORIGIN_APP));

        // Now delete the (currently active!) rule. For example, assume this is done from settings.
        mZenModeHelper.removeAutomaticZenRule(ruleId, UPDATE_ORIGIN_USER, "remove",
                Process.SYSTEM_UID);
        mTestableLooper.processAllMessages();

        verify(mDeviceEffectsApplier).apply(eq(NO_EFFECTS), eq(UPDATE_ORIGIN_USER));
    }

    @Test
    public void testDeviceEffects_applied() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);
        mZenModeHelper.setDeviceEffectsApplier(mDeviceEffectsApplier);
        verify(mDeviceEffectsApplier).apply(eq(NO_EFFECTS), eq(UPDATE_ORIGIN_INIT));

        ZenDeviceEffects effects = new ZenDeviceEffects.Builder()
                .setShouldSuppressAmbientDisplay(true)
                .setShouldDimWallpaper(true)
                .build();
        String ruleId = addRuleWithEffects(effects);
        verifyNoMoreInteractions(mDeviceEffectsApplier);

        mZenModeHelper.setAutomaticZenRuleState(ruleId, CONDITION_TRUE, UPDATE_ORIGIN_APP,
                CUSTOM_PKG_UID);
        mTestableLooper.processAllMessages();

        verify(mDeviceEffectsApplier).apply(eq(effects), eq(UPDATE_ORIGIN_APP));
    }

    @Test
    public void testDeviceEffects_onDeactivateRule_applied() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);
        mZenModeHelper.setDeviceEffectsApplier(mDeviceEffectsApplier);

        ZenDeviceEffects zde = new ZenDeviceEffects.Builder().setShouldUseNightMode(true).build();
        String ruleId = addRuleWithEffects(zde);
        mZenModeHelper.setAutomaticZenRuleState(ruleId, CONDITION_TRUE, UPDATE_ORIGIN_APP,
                CUSTOM_PKG_UID);
        mTestableLooper.processAllMessages();
        verify(mDeviceEffectsApplier).apply(eq(zde), eq(UPDATE_ORIGIN_APP));

        mZenModeHelper.setAutomaticZenRuleState(ruleId, CONDITION_FALSE, UPDATE_ORIGIN_APP,
                CUSTOM_PKG_UID);
        mTestableLooper.processAllMessages();

        verify(mDeviceEffectsApplier).apply(eq(NO_EFFECTS), eq(UPDATE_ORIGIN_APP));
    }

    @Test
    public void testDeviceEffects_changeToConsolidatedEffects_applied() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);
        mZenModeHelper.setDeviceEffectsApplier(mDeviceEffectsApplier);
        verify(mDeviceEffectsApplier).apply(eq(NO_EFFECTS), eq(UPDATE_ORIGIN_INIT));

        String ruleId = addRuleWithEffects(
                new ZenDeviceEffects.Builder().setShouldDisplayGrayscale(true).build());
        mZenModeHelper.setAutomaticZenRuleState(ruleId, CONDITION_TRUE, UPDATE_ORIGIN_APP,
                CUSTOM_PKG_UID);
        mTestableLooper.processAllMessages();
        verify(mDeviceEffectsApplier).apply(
                eq(new ZenDeviceEffects.Builder()
                        .setShouldDisplayGrayscale(true)
                        .build()),
                eq(UPDATE_ORIGIN_APP));

        // Now create and activate a second rule that adds more effects.
        String secondRuleId = addRuleWithEffects(
                new ZenDeviceEffects.Builder().setShouldDimWallpaper(true).build());
        mZenModeHelper.setAutomaticZenRuleState(secondRuleId, CONDITION_TRUE, UPDATE_ORIGIN_APP,
                CUSTOM_PKG_UID);
        mTestableLooper.processAllMessages();

        verify(mDeviceEffectsApplier).apply(
                eq(new ZenDeviceEffects.Builder()
                        .setShouldDisplayGrayscale(true)
                        .setShouldDimWallpaper(true)
                        .build()),
                eq(UPDATE_ORIGIN_APP));
    }
    @Test
    public void testDeviceEffects_noChangeToConsolidatedEffects_notApplied() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);
        mZenModeHelper.setDeviceEffectsApplier(mDeviceEffectsApplier);
        verify(mDeviceEffectsApplier).apply(eq(NO_EFFECTS), eq(UPDATE_ORIGIN_INIT));

        ZenDeviceEffects zde = new ZenDeviceEffects.Builder().setShouldUseNightMode(true).build();
        String ruleId = addRuleWithEffects(zde);
        mZenModeHelper.setAutomaticZenRuleState(ruleId, CONDITION_TRUE, UPDATE_ORIGIN_APP,
                CUSTOM_PKG_UID);
        mTestableLooper.processAllMessages();
        verify(mDeviceEffectsApplier).apply(eq(zde), eq(UPDATE_ORIGIN_APP));

        // Now create and activate a second rule that doesn't add any more effects.
        String secondRuleId = addRuleWithEffects(zde);
        mZenModeHelper.setAutomaticZenRuleState(secondRuleId, CONDITION_TRUE, UPDATE_ORIGIN_APP,
                CUSTOM_PKG_UID);
        mTestableLooper.processAllMessages();

        verifyNoMoreInteractions(mDeviceEffectsApplier);
    }

    @Test
    public void testDeviceEffects_activeBeforeApplierProvided_appliedWhenProvided() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);

        ZenDeviceEffects zde = new ZenDeviceEffects.Builder().setShouldUseNightMode(true).build();
        String ruleId = addRuleWithEffects(zde);
        verify(mDeviceEffectsApplier, never()).apply(any(), anyInt());

        mZenModeHelper.setAutomaticZenRuleState(ruleId, CONDITION_TRUE, UPDATE_ORIGIN_APP,
                CUSTOM_PKG_UID);
        mTestableLooper.processAllMessages();
        verify(mDeviceEffectsApplier, never()).apply(any(), anyInt());

        mZenModeHelper.setDeviceEffectsApplier(mDeviceEffectsApplier);
        verify(mDeviceEffectsApplier).apply(eq(zde), eq(UPDATE_ORIGIN_INIT));
    }

    @Test
    public void testDeviceEffects_onUserSwitch_appliedImmediately() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);
        mZenModeHelper.setDeviceEffectsApplier(mDeviceEffectsApplier);
        verify(mDeviceEffectsApplier).apply(eq(NO_EFFECTS), eq(UPDATE_ORIGIN_INIT));

        // Initialize default configurations (default rules) for both users.
        mZenModeHelper.onUserSwitched(1);
        mZenModeHelper.onUserSwitched(2);

        // Current user is now 2. Tweak a rule for user 1 so it's active and has effects.
        ZenRule user1Rule = mZenModeHelper.mConfigs.get(1).automaticRules.valueAt(0);
        user1Rule.enabled = true;
        user1Rule.condition = new Condition(user1Rule.conditionId, "on", STATE_TRUE);
        user1Rule.zenDeviceEffects = new ZenDeviceEffects.Builder()
                .setShouldDimWallpaper(true)
                .setShouldUseNightMode(true)
                .build();
        verifyNoMoreInteractions(mDeviceEffectsApplier);

        mZenModeHelper.onUserSwitched(1);
        mTestableLooper.processAllMessages();

        verify(mDeviceEffectsApplier).apply(eq(user1Rule.zenDeviceEffects),
                eq(UPDATE_ORIGIN_INIT_USER));
    }

    private String addRuleWithEffects(ZenDeviceEffects effects) {
        AutomaticZenRule rule = new AutomaticZenRule.Builder("Test", CONDITION_ID)
                .setDeviceEffects(effects)
                .build();
        return mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), rule,
                UPDATE_ORIGIN_APP, "reasons", CUSTOM_PKG_UID);
    }

    @Test
    public void applyGlobalZenModeAsImplicitZenRule_createsImplicitRuleAndActivatesIt() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);
        mZenModeHelper.mConfig.automaticRules.clear();

        mZenModeHelper.applyGlobalZenModeAsImplicitZenRule(CUSTOM_PKG_NAME, CUSTOM_PKG_UID,
                ZEN_MODE_IMPORTANT_INTERRUPTIONS);

        assertThat(mZenModeHelper.mConfig.automaticRules.values())
                .comparingElementsUsing(IGNORE_TIMESTAMPS)
                .containsExactly(
                        expectedImplicitRule(CUSTOM_PKG_NAME, ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                                null, true));
    }

    @Test
    public void applyGlobalZenModeAsImplicitZenRule_updatesImplicitRuleAndActivatesIt() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);
        mZenModeHelper.mConfig.automaticRules.clear();

        mZenModeHelper.applyGlobalZenModeAsImplicitZenRule(CUSTOM_PKG_NAME, CUSTOM_PKG_UID,
                ZEN_MODE_IMPORTANT_INTERRUPTIONS);
        mZenModeHelper.setManualZenMode(ZEN_MODE_OFF, null, UPDATE_ORIGIN_APP, "test", "test", 0);
        assertThat(mZenModeHelper.mConfig.automaticRules).hasSize(1);

        mZenModeHelper.applyGlobalZenModeAsImplicitZenRule(CUSTOM_PKG_NAME, CUSTOM_PKG_UID,
                ZEN_MODE_ALARMS);

        assertThat(mZenModeHelper.mConfig.automaticRules.values())
                .comparingElementsUsing(IGNORE_TIMESTAMPS)
                .containsExactly(
                        expectedImplicitRule(CUSTOM_PKG_NAME, ZEN_MODE_ALARMS, null, true));
    }

    @Test
    public void applyGlobalZenModeAsImplicitZenRule_modeOff_deactivatesImplicitRule() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);
        mZenModeHelper.mConfig.automaticRules.clear();
        mZenModeHelper.applyGlobalZenModeAsImplicitZenRule(CUSTOM_PKG_NAME, CUSTOM_PKG_UID,
                ZEN_MODE_IMPORTANT_INTERRUPTIONS);
        assertThat(mZenModeHelper.mConfig.automaticRules).hasSize(1);
        assertThat(mZenModeHelper.mConfig.automaticRules.valueAt(0).condition.state)
                .isEqualTo(STATE_TRUE);

        mZenModeHelper.applyGlobalZenModeAsImplicitZenRule(CUSTOM_PKG_NAME, CUSTOM_PKG_UID,
                ZEN_MODE_OFF);

        assertThat(mZenModeHelper.mConfig.automaticRules.valueAt(0).condition.state)
                .isEqualTo(STATE_FALSE);
    }

    @Test
    public void applyGlobalZenModeAsImplicitZenRule_modeOffButNoPreviousRule_ignored() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);
        mZenModeHelper.mConfig.automaticRules.clear();

        mZenModeHelper.applyGlobalZenModeAsImplicitZenRule(CUSTOM_PKG_NAME, CUSTOM_PKG_UID,
                ZEN_MODE_OFF);

        assertThat(mZenModeHelper.mConfig.automaticRules).isEmpty();
    }

    @Test
    public void applyGlobalZenModeAsImplicitZenRule_update_unsnoozesRule() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);
        mZenModeHelper.mConfig.automaticRules.clear();

        mZenModeHelper.applyGlobalZenModeAsImplicitZenRule(CUSTOM_PKG_NAME, CUSTOM_PKG_UID,
                ZEN_MODE_IMPORTANT_INTERRUPTIONS);
        assertThat(mZenModeHelper.mConfig.automaticRules).hasSize(1);
        assertThat(mZenModeHelper.mConfig.automaticRules.valueAt(0).snoozing).isFalse();

        mZenModeHelper.setManualZenMode(ZEN_MODE_OFF, null, UPDATE_ORIGIN_APP, "test", "test", 0);
        assertThat(mZenModeHelper.mConfig.automaticRules.valueAt(0).snoozing).isTrue();

        mZenModeHelper.applyGlobalZenModeAsImplicitZenRule(CUSTOM_PKG_NAME, CUSTOM_PKG_UID,
                ZEN_MODE_ALARMS);

        assertThat(mZenModeHelper.mConfig.automaticRules.valueAt(0).snoozing).isFalse();
        assertThat(mZenModeHelper.mConfig.automaticRules.valueAt(0).condition.state)
                .isEqualTo(STATE_TRUE);
    }

    @Test
    public void applyGlobalZenModeAsImplicitZenRule_flagOff_ignored() {
        mSetFlagsRule.disableFlags(android.app.Flags.FLAG_MODES_API);
        mZenModeHelper.mConfig.automaticRules.clear();

        withoutWtfCrash(
                () -> mZenModeHelper.applyGlobalZenModeAsImplicitZenRule(CUSTOM_PKG_NAME,
                        CUSTOM_PKG_UID,
                        ZEN_MODE_IMPORTANT_INTERRUPTIONS));

        assertThat(mZenModeHelper.mConfig.automaticRules).isEmpty();
    }

    @Test
    public void applyGlobalPolicyAsImplicitZenRule_createsImplicitRule() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);
        mZenModeHelper.mConfig.automaticRules.clear();

        Policy policy = new Policy(PRIORITY_CATEGORY_CALLS | PRIORITY_CATEGORY_CONVERSATIONS,
                PRIORITY_SENDERS_CONTACTS, PRIORITY_SENDERS_STARRED,
                Policy.getAllSuppressedVisualEffects(), CONVERSATION_SENDERS_IMPORTANT);
        mZenModeHelper.applyGlobalPolicyAsImplicitZenRule(CUSTOM_PKG_NAME, CUSTOM_PKG_UID, policy,
                UPDATE_ORIGIN_APP);

        ZenPolicy expectedZenPolicy = new ZenPolicy.Builder()
                .disallowAllSounds()
                .allowCalls(PEOPLE_TYPE_CONTACTS)
                .allowConversations(CONVERSATION_SENDERS_IMPORTANT)
                .hideAllVisualEffects()
                .allowChannels(ZenPolicy.CHANNEL_TYPE_PRIORITY)
                .build();
        assertThat(mZenModeHelper.mConfig.automaticRules.values())
                .comparingElementsUsing(IGNORE_TIMESTAMPS)
                .containsExactly(
                        expectedImplicitRule(CUSTOM_PKG_NAME, ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                                expectedZenPolicy, /* conditionActive= */ null));
    }

    @Test
    public void applyGlobalPolicyAsImplicitZenRule_updatesImplicitRule() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);
        mZenModeHelper.mConfig.automaticRules.clear();

        Policy original = new Policy(PRIORITY_CATEGORY_CALLS | PRIORITY_CATEGORY_CONVERSATIONS,
                PRIORITY_SENDERS_CONTACTS, PRIORITY_SENDERS_STARRED,
                Policy.getAllSuppressedVisualEffects(), CONVERSATION_SENDERS_IMPORTANT);
        mZenModeHelper.applyGlobalPolicyAsImplicitZenRule(CUSTOM_PKG_NAME, CUSTOM_PKG_UID,
                original, UPDATE_ORIGIN_APP);

        // Change priorityCallSenders: contacts -> starred.
        Policy updated = new Policy(PRIORITY_CATEGORY_CALLS | PRIORITY_CATEGORY_CONVERSATIONS,
                PRIORITY_SENDERS_STARRED, PRIORITY_SENDERS_STARRED,
                Policy.getAllSuppressedVisualEffects(), CONVERSATION_SENDERS_IMPORTANT);
        mZenModeHelper.applyGlobalPolicyAsImplicitZenRule(CUSTOM_PKG_NAME, CUSTOM_PKG_UID, updated,
                UPDATE_ORIGIN_APP);

        ZenPolicy expectedZenPolicy = new ZenPolicy.Builder()
                .disallowAllSounds()
                .allowCalls(PEOPLE_TYPE_STARRED)
                .allowConversations(CONVERSATION_SENDERS_IMPORTANT)
                .hideAllVisualEffects()
                .allowChannels(ZenPolicy.CHANNEL_TYPE_PRIORITY)
                .build();
        assertThat(mZenModeHelper.mConfig.automaticRules.values())
                .comparingElementsUsing(IGNORE_TIMESTAMPS)
                .containsExactly(
                        expectedImplicitRule(CUSTOM_PKG_NAME, ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                                expectedZenPolicy, /* conditionActive= */ null));
    }

    @Test
    public void applyGlobalPolicyAsImplicitZenRule_flagOff_ignored() {
        mSetFlagsRule.disableFlags(android.app.Flags.FLAG_MODES_API);
        mZenModeHelper.mConfig.automaticRules.clear();

        withoutWtfCrash(
                () -> mZenModeHelper.applyGlobalPolicyAsImplicitZenRule(CUSTOM_PKG_NAME,
                        CUSTOM_PKG_UID, new Policy(0, 0, 0), UPDATE_ORIGIN_APP));

        assertThat(mZenModeHelper.mConfig.automaticRules).isEmpty();
    }

    @Test
    public void getNotificationPolicyFromImplicitZenRule_returnsSetPolicy() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);
        Policy writtenPolicy = new Policy(PRIORITY_CATEGORY_CALLS | PRIORITY_CATEGORY_CONVERSATIONS,
                PRIORITY_SENDERS_CONTACTS, PRIORITY_SENDERS_STARRED,
                Policy.getAllSuppressedVisualEffects(), STATE_FALSE,
                CONVERSATION_SENDERS_IMPORTANT);
        mZenModeHelper.applyGlobalPolicyAsImplicitZenRule(CUSTOM_PKG_NAME, CUSTOM_PKG_UID,
                writtenPolicy, UPDATE_ORIGIN_APP);

        Policy readPolicy = mZenModeHelper.getNotificationPolicyFromImplicitZenRule(
                CUSTOM_PKG_NAME);

        assertThat(readPolicy).isEqualTo(writtenPolicy);
    }

    @Test
    public void getNotificationPolicyFromImplicitZenRule_ruleWithoutPolicy_returnsGlobalPolicy() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);

        mZenModeHelper.applyGlobalZenModeAsImplicitZenRule(CUSTOM_PKG_NAME, CUSTOM_PKG_UID,
                ZEN_MODE_ALARMS);
        mZenModeHelper.mConfig.allowCalls = true;
        mZenModeHelper.mConfig.allowConversations = false;

        Policy readPolicy = mZenModeHelper.getNotificationPolicyFromImplicitZenRule(
                CUSTOM_PKG_NAME);

        assertThat(readPolicy).isNotNull();
        assertThat(readPolicy.allowCalls()).isTrue();
        assertThat(readPolicy.allowConversations()).isFalse();
    }

    @Test
    public void getNotificationPolicyFromImplicitZenRule_noImplicitRule_returnsGlobalPolicy() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);

        mZenModeHelper.mConfig.allowCalls = true;
        mZenModeHelper.mConfig.allowConversations = false;

        Policy readPolicy = mZenModeHelper.getNotificationPolicyFromImplicitZenRule(
                CUSTOM_PKG_NAME);

        assertThat(readPolicy).isNotNull();
        assertThat(readPolicy.allowCalls()).isTrue();
        assertThat(readPolicy.allowConversations()).isFalse();
    }

    private static final Correspondence<ZenRule, ZenRule> IGNORE_TIMESTAMPS =
            Correspondence.transforming(zr -> {
                Parcel p = Parcel.obtain();
                try {
                    zr.writeToParcel(p, 0);
                    p.setDataPosition(0);
                    ZenRule copy = new ZenRule(p);
                    copy.creationTime = 0;
                    return copy;
                } finally {
                    p.recycle();
                }
            },
            "Ignoring timestamps");

    private ZenRule expectedImplicitRule(String ownerPkg, int zenMode, ZenPolicy policy,
            @Nullable Boolean conditionActive) {
        ZenRule rule = new ZenModeConfig.ZenRule();
        rule.id = "implicit_" + ownerPkg;
        rule.conditionId = Uri.parse("condition://android/implicit/" + ownerPkg);
        if (conditionActive != null) {
            rule.condition = conditionActive
                    ? new Condition(rule.conditionId,
                            mContext.getString(R.string.zen_mode_implicit_activated), STATE_TRUE)
                    : new Condition(rule.conditionId,
                            mContext.getString(R.string.zen_mode_implicit_deactivated),
                            STATE_FALSE);
        }
        rule.zenMode = zenMode;
        rule.zenPolicy = policy;
        rule.pkg = ownerPkg;
        rule.name = CUSTOM_APP_LABEL;
        rule.iconResName = ICON_RES_NAME;
        rule.triggerDescription = mContext.getString(R.string.zen_mode_implicit_trigger_description,
                CUSTOM_APP_LABEL);
        rule.type = AutomaticZenRule.TYPE_OTHER;
        rule.enabled = true;
        return rule;
    }

    // TODO: b/310620812 - Update setup methods to include allowChannels() when MODES_API is inlined
    private void setupZenConfig() {
        mZenModeHelper.mZenMode = ZEN_MODE_OFF;
        mZenModeHelper.mConfig.allowAlarms = false;
        mZenModeHelper.mConfig.allowMedia = false;
        mZenModeHelper.mConfig.allowSystem = false;
        mZenModeHelper.mConfig.allowReminders = true;
        mZenModeHelper.mConfig.allowCalls = true;
        mZenModeHelper.mConfig.allowCallsFrom = PRIORITY_SENDERS_STARRED;
        mZenModeHelper.mConfig.allowMessages = true;
        mZenModeHelper.mConfig.allowConversations = true;
        mZenModeHelper.mConfig.allowEvents = true;
        mZenModeHelper.mConfig.allowRepeatCallers = true;
        mZenModeHelper.mConfig.suppressedVisualEffects = SUPPRESSED_EFFECT_BADGE;
        mZenModeHelper.mConfig.manualRule = null;
    }

    private void setupZenConfigMaintained() {
        // config is still the same as when it was setup (setupZenConfig)
        assertFalse(mZenModeHelper.mConfig.allowAlarms);
        assertFalse(mZenModeHelper.mConfig.allowMedia);
        assertFalse(mZenModeHelper.mConfig.allowSystem);
        assertTrue(mZenModeHelper.mConfig.allowReminders);
        assertTrue(mZenModeHelper.mConfig.allowCalls);
        assertEquals(PRIORITY_SENDERS_STARRED, mZenModeHelper.mConfig.allowCallsFrom);
        assertTrue(mZenModeHelper.mConfig.allowMessages);
        assertTrue(mZenModeHelper.mConfig.allowConversations);
        assertTrue(mZenModeHelper.mConfig.allowEvents);
        assertTrue(mZenModeHelper.mConfig.allowRepeatCallers);
        assertEquals(SUPPRESSED_EFFECT_BADGE, mZenModeHelper.mConfig.suppressedVisualEffects);
    }

    private void checkDndProtoMatchesSetupZenConfig(DNDPolicyProto dndProto) {
        assertEquals(STATE_DISALLOW, dndProto.getAlarms().getNumber());
        assertEquals(STATE_DISALLOW, dndProto.getMedia().getNumber());
        assertEquals(STATE_DISALLOW, dndProto.getSystem().getNumber());
        assertEquals(STATE_ALLOW, dndProto.getReminders().getNumber());
        assertEquals(STATE_ALLOW, dndProto.getCalls().getNumber());
        assertEquals(PEOPLE_STARRED, dndProto.getAllowCallsFrom().getNumber());
        assertEquals(STATE_ALLOW, dndProto.getMessages().getNumber());
        assertEquals(STATE_ALLOW, dndProto.getEvents().getNumber());
        assertEquals(STATE_ALLOW, dndProto.getRepeatCallers().getNumber());
        assertEquals(STATE_ALLOW, dndProto.getFullscreen().getNumber());
        assertEquals(STATE_ALLOW, dndProto.getLights().getNumber());
        assertEquals(STATE_ALLOW, dndProto.getPeek().getNumber());
        assertEquals(STATE_ALLOW, dndProto.getStatusBar().getNumber());
        assertEquals(STATE_DISALLOW, dndProto.getBadge().getNumber());
        assertEquals(STATE_ALLOW, dndProto.getAmbient().getNumber());
        assertEquals(STATE_ALLOW, dndProto.getNotificationList().getNumber());
    }

    private static void withoutWtfCrash(Runnable test) {
        Log.TerribleFailureHandler oldHandler = Log.setWtfHandler((tag, what, system) -> {});
        try {
            test.run();
        } finally {
            Log.setWtfHandler(oldHandler);
        }
    }

    /**
     * Wrapper to use TypedXmlPullParser as XmlResourceParser for Resources.getXml()
     */
    final class XmlResourceParserImpl implements XmlResourceParser {
        private TypedXmlPullParser parser;

        public XmlResourceParserImpl(TypedXmlPullParser parser) {
            this.parser = parser;
        }

        public int getEventType() throws XmlPullParserException {
            return parser.getEventType();
        }

        @Override
        public void setFeature(String name, boolean state) throws XmlPullParserException {
            parser.setFeature(name, state);
        }

        @Override
        public boolean getFeature(String name) {
            return false;
        }

        @Override
        public void setProperty(String name, Object value) throws XmlPullParserException {
            parser.setProperty(name, value);
        }

        @Override
        public Object getProperty(String name) {
            return parser.getProperty(name);
        }

        @Override
        public void setInput(Reader in) throws XmlPullParserException {
            parser.setInput(in);
        }

        @Override
        public void setInput(InputStream inputStream, String inputEncoding)
                throws XmlPullParserException {
            parser.setInput(inputStream, inputEncoding);
        }

        @Override
        public String getInputEncoding() {
            return parser.getInputEncoding();
        }

        @Override
        public void defineEntityReplacementText(String entityName, String replacementText)
                throws XmlPullParserException {
            parser.defineEntityReplacementText(entityName, replacementText);
        }

        @Override
        public int getNamespaceCount(int depth) throws XmlPullParserException {
            return parser.getNamespaceCount(depth);
        }

        @Override
        public String getNamespacePrefix(int pos) throws XmlPullParserException {
            return parser.getNamespacePrefix(pos);
        }

        @Override
        public String getNamespaceUri(int pos) throws XmlPullParserException {
            return parser.getNamespaceUri(pos);
        }

        @Override
        public String getNamespace(String prefix) {
            return parser.getNamespace(prefix);
        }

        @Override
        public int getDepth() {
            return parser.getDepth();
        }

        @Override
        public String getPositionDescription() {
            return parser.getPositionDescription();
        }

        @Override
        public int getLineNumber() {
            return parser.getLineNumber();
        }

        @Override
        public int getColumnNumber() {
            return parser.getColumnNumber();
        }

        @Override
        public boolean isWhitespace() throws XmlPullParserException {
            return parser.isWhitespace();
        }

        @Override
        public String getText() {
            return parser.getText();
        }

        @Override
        public char[] getTextCharacters(int[] holderForStartAndLength) {
            return parser.getTextCharacters(holderForStartAndLength);
        }

        @Override
        public String getNamespace() {
            return parser.getNamespace();
        }

        @Override
        public String getName() {
            return parser.getName();
        }

        @Override
        public String getPrefix() {
            return parser.getPrefix();
        }

        @Override
        public boolean isEmptyElementTag() throws XmlPullParserException {
            return false;
        }

        @Override
        public int getAttributeCount() {
            return parser.getAttributeCount();
        }

        public int next() throws IOException, XmlPullParserException {
            return parser.next();
        }

        @Override
        public int nextToken() throws XmlPullParserException, IOException {
            return parser.next();
        }

        @Override
        public void require(int type, String namespace, String name)
                throws XmlPullParserException, IOException {
            parser.require(type, namespace, name);
        }

        @Override
        public String nextText() throws XmlPullParserException, IOException {
            return parser.nextText();
        }

        @Override
        public String getAttributeNamespace(int index) {
            return "";
        }

        @Override
        public String getAttributeName(int index) {
            return parser.getAttributeName(index);
        }

        @Override
        public String getAttributePrefix(int index) {
            return parser.getAttributePrefix(index);
        }

        @Override
        public String getAttributeType(int index) {
            return parser.getAttributeType(index);
        }

        @Override
        public boolean isAttributeDefault(int index) {
            return parser.isAttributeDefault(index);
        }

        @Override
        public String getAttributeValue(int index) {
            return parser.getAttributeValue(index);
        }

        @Override
        public String getAttributeValue(String namespace, String name) {
            return parser.getAttributeValue(namespace, name);
        }

        @Override
        public int getAttributeNameResource(int index) {
            return 0;
        }

        @Override
        public int getAttributeListValue(String namespace, String attribute, String[] options,
                int defaultValue) {
            return 0;
        }

        @Override
        public boolean getAttributeBooleanValue(String namespace, String attribute,
                boolean defaultValue) {
            return false;
        }

        @Override
        public int getAttributeResourceValue(String namespace, String attribute, int defaultValue) {
            return 0;
        }

        @Override
        public int getAttributeIntValue(String namespace, String attribute, int defaultValue) {
            return 0;
        }

        @Override
        public int getAttributeUnsignedIntValue(String namespace, String attribute,
                int defaultValue) {
            return 0;
        }

        @Override
        public float getAttributeFloatValue(String namespace, String attribute,
                float defaultValue) {
            return 0;
        }

        @Override
        public int getAttributeListValue(int index, String[] options, int defaultValue) {
            return 0;
        }

        @Override
        public boolean getAttributeBooleanValue(int index, boolean defaultValue) {
            return false;
        }

        @Override
        public int getAttributeResourceValue(int index, int defaultValue) {
            return 0;
        }

        @Override
        public int getAttributeIntValue(int index, int defaultValue) {
            return 0;
        }

        @Override
        public int getAttributeUnsignedIntValue(int index, int defaultValue) {
            return 0;
        }

        @Override
        public float getAttributeFloatValue(int index, float defaultValue) {
            return 0;
        }

        @Override
        public String getIdAttribute() {
            return null;
        }

        @Override
        public String getClassAttribute() {
            return null;
        }

        @Override
        public int getIdAttributeResourceValue(int defaultValue) {
            return 0;
        }

        @Override
        public int getStyleAttribute() {
            return 0;
        }

        @Override
        public void close() {
        }

        @Override
        public int nextTag() throws IOException, XmlPullParserException {
            return parser.nextTag();
        }
    }
}
