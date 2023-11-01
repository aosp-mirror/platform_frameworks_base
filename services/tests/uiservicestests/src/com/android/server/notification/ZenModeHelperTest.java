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
import static android.app.NotificationManager.Policy.CONVERSATION_SENDERS_ANYONE;
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
import static android.app.NotificationManager.Policy.PRIORITY_SENDERS_STARRED;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;
import static android.provider.Settings.Global.ZEN_MODE_ALARMS;
import static android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
import static android.service.notification.Condition.STATE_FALSE;
import static android.service.notification.Condition.STATE_TRUE;

import static com.android.internal.config.sysui.SystemUiSystemPropertiesFlags.NotificationFlags.LOG_DND_STATE_EVENTS;
import static com.android.os.dnd.DNDProtoEnums.PEOPLE_STARRED;
import static com.android.os.dnd.DNDProtoEnums.ROOT_CONFIG;
import static com.android.os.dnd.DNDProtoEnums.STATE_ALLOW;
import static com.android.os.dnd.DNDProtoEnums.STATE_DISALLOW;
import static com.android.server.notification.ZenModeHelper.RULE_LIMIT_PER_PACKAGE;

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
import static org.mockito.Mockito.when;

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
import android.os.Process;
import android.os.UserHandle;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.notification.Condition;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.ScheduleInfo;
import android.service.notification.ZenModeDiff;
import android.service.notification.ZenPolicy;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
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
import com.google.protobuf.InvalidProtocolBufferException;

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

@SmallTest
@SuppressLint("GuardedBy") // It's ok for this test to access guarded methods from the service.
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ZenModeHelperTest extends UiServiceTestCase {

    private static final String EVENTS_DEFAULT_RULE_ID = "EVENTS_DEFAULT_RULE";
    private static final String SCHEDULE_DEFAULT_RULE_ID = "EVERY_NIGHT_DEFAULT_RULE";
    private static final String CUSTOM_PKG_NAME = "not.android";
    private static final int CUSTOM_PKG_UID = 1;
    private static final String CUSTOM_RULE_ID = "custom_rule";

    private final String NAME = "name";
    private final ComponentName OWNER = new ComponentName("pkg", "cls");
    private final ComponentName CONFIG_ACTIVITY = new ComponentName("pkg", "act");
    private final ZenPolicy POLICY = new ZenPolicy.Builder().allowAlarms(true).build();
    private final Uri CONDITION_ID = new Uri.Builder().scheme("scheme")
            .authority("authority")
            .appendPath("path")
            .appendPath("test")
            .build();

    private final Condition CONDITION = new Condition(CONDITION_ID, "", Condition.STATE_TRUE);
    private final String TRIGGER_DESC = "Every Night, 10pm to 6am";
    private final int TYPE = TYPE_BEDTIME;
    private final boolean ALLOW_MANUAL = true;
    private final int ICON_RES_ID = 1234;
    private final int INTERRUPTION_FILTER = Settings.Global.ZEN_MODE_ALARMS;
    private final boolean ENABLED = true;
    private final int CREATION_TIME = 123;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    ConditionProviders mConditionProviders;
    @Mock NotificationManager mNotificationManager;
    @Mock PackageManager mPackageManager;
    private Resources mResources;
    private TestableLooper mTestableLooper;
    private ZenModeHelper mZenModeHelper;
    private ContentResolver mContentResolver;
    @Mock AppOpsManager mAppOps;
    TestableFlagResolver mTestFlagResolver = new TestableFlagResolver();
    ZenModeEventLoggerFake mZenModeEventLogger;

    @Before
    public void setUp() throws PackageManager.NameNotFoundException {
        MockitoAnnotations.initMocks(this);

        mTestableLooper = TestableLooper.get(this);
        mContentResolver = mContext.getContentResolver();
        mResources = spy(mContext.getResources());
        String pkg = mContext.getPackageName();
        try {
            when(mResources.getXml(R.xml.default_zen_mode_config)).thenReturn(
                    getDefaultConfigParser());
        } catch (Exception e) {
            Log.d("ZenModeHelperTest", "Couldn't mock default zen mode config xml file err=" +
                    e.toString());
        }

        when(mContext.getSystemService(AppOpsManager.class)).thenReturn(mAppOps);
        when(mContext.getSystemService(NotificationManager.class)).thenReturn(mNotificationManager);
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
        mZenModeHelper.setConfig(new ZenModeConfig(), null, "writing xml", Process.SYSTEM_UID,
                true);
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
        mZenModeHelper.setConfig(newConfig, null, "writing xml", Process.SYSTEM_UID, true);
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
        mZenModeHelper.mZenMode = Settings.Global.ZEN_MODE_OFF;
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

        for (int usage : AudioAttributes.SDK_USAGES) {
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

        for (int usage : AudioAttributes.SDK_USAGES) {
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

        for (int usage : AudioAttributes.SDK_USAGES) {
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

        for (int usage : AudioAttributes.SDK_USAGES) {
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
        mZenModeHelper.mIsBootComplete = true;
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
        mZenModeHelper.mIsBootComplete = true;
        mZenModeHelper.setZenModeSetting(ZEN_MODE_IMPORTANT_INTERRUPTIONS);

        verify(mNotificationManager, never()).notify(eq(ZenModeHelper.TAG),
                eq(SystemMessage.NOTE_ZEN_UPGRADE), any());
    }

    @Test
    public void testNoZenUpgradeNotificationZenUpdated() {
        // doesn't show upgrade notification since zen was already updated
        Settings.Secure.putInt(mContentResolver, Settings.Secure.SHOW_ZEN_UPGRADE_NOTIFICATION, 0);
        Settings.Secure.putInt(mContentResolver, Settings.Secure.ZEN_SETTINGS_UPDATED, 1);
        mZenModeHelper.mIsBootComplete = true;
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
        mZenModeHelper.mZenMode = Global.ZEN_MODE_OFF;
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
        mZenModeHelper.mZenMode = Global.ZEN_MODE_OFF;
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
        mZenModeHelper.mZenMode = Global.ZEN_MODE_OFF;
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
        mZenModeHelper.mZenMode = Global.ZEN_MODE_OFF;
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
        mZenModeHelper.mZenMode = Global.ZEN_MODE_OFF;
        mZenModeHelper.mConfig = null; // will evaluate config to zen mode off
        for (int i = 0; i < 3; i++) {
            // if zen doesn't change, zen should not reapply itself to the ringer
            mZenModeHelper.evaluateZenModeLocked("test", true);
        }
        verify(mAudioManager, never()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelper.TAG);
    }

    @Test
    public void testSilentRingerSavedOnZenOff_startsZenOn() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelper.mAudioManager = mAudioManager;
        mZenModeHelper.mZenMode = Global.ZEN_MODE_OFF;
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
            mZenModeHelper.evaluateZenModeLocked("test", true);
        }
        verify(mAudioManager, never()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelper.TAG);
    }

    @Test
    public void testVibrateRingerSavedOnZenOff_startsZenOn() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelper.mAudioManager = mAudioManager;
        mZenModeHelper.mZenMode = Global.ZEN_MODE_OFF;
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
            mZenModeHelper.evaluateZenModeLocked("test", true);
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
        mZenModeHelper.setManualZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null, null,
                "test", CUSTOM_PKG_UID, false);

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
        mZenModeHelper.removeAutomaticZenRule(CUSTOM_RULE_ID, "test", CUSTOM_PKG_UID, false);
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
        mZenModeHelper.setConfig(config10, null, "writeXml", Process.SYSTEM_UID, true);
        ZenModeConfig config11 = mZenModeHelper.mConfig.copy();
        config11.user = 11;
        config11.allowAlarms = false;
        config11.allowMedia = false;
        mZenModeHelper.setConfig(config11, null, "writeXml", Process.SYSTEM_UID, true);

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
                .allowMessages(ZenPolicy.PEOPLE_TYPE_CONTACTS)
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
        mZenModeHelper.updateDefaultZenRules(
                Process.SYSTEM_UID, true); // shouldn't throw null pointer
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

        mZenModeHelper.updateDefaultZenRules(Process.SYSTEM_UID, true);
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

        mZenModeHelper.updateDefaultZenRules(Process.SYSTEM_UID, true);
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

        mZenModeHelper.updateDefaultZenRules(Process.SYSTEM_UID, true);
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
            String id = mZenModeHelper.addAutomaticZenRule("pkgname", zenRule, "test",
                    CUSTOM_PKG_UID, false);
            assertNotNull(id);
        }
        try {
            AutomaticZenRule zenRule = new AutomaticZenRule("name",
                    null,
                    new ComponentName("android", "ScheduleConditionProvider"),
                    ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                    new ZenPolicy.Builder().build(),
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
            String id = mZenModeHelper.addAutomaticZenRule("pkgname", zenRule, "test",
                    CUSTOM_PKG_UID, false);
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
            String id = mZenModeHelper.addAutomaticZenRule("pkgname", zenRule, "test",
                    CUSTOM_PKG_UID, false);
            assertNotNull(id);
        }
        try {
            AutomaticZenRule zenRule = new AutomaticZenRule("name",
                    null,
                    new ComponentName("android", "ScheduleConditionProviderFinal"),
                    ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                    new ZenPolicy.Builder().build(),
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
            String id = mZenModeHelper.addAutomaticZenRule("pkgname", zenRule, "test",
                    CUSTOM_PKG_UID, false);
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
            String id = mZenModeHelper.addAutomaticZenRule("pkgname", zenRule, "test",
                    CUSTOM_PKG_UID, false);
            assertNotNull(id);
        }
        try {
            AutomaticZenRule zenRule = new AutomaticZenRule("name",
                    new ComponentName("android", "ScheduleConditionProviderFinal"),
                    null, // configuration activity
                    ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                    new ZenPolicy.Builder().build(),
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
            String id = mZenModeHelper.addAutomaticZenRule("pkgname", zenRule, "test",
                    CUSTOM_PKG_UID, false);
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
        String id = mZenModeHelper.addAutomaticZenRule("android", zenRule, "test",
                Process.SYSTEM_UID, true);

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
        String id = mZenModeHelper.addAutomaticZenRule("android", zenRule, "test",
                Process.SYSTEM_UID, true);

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

        String id = mZenModeHelper.addAutomaticZenRule(null, zenRule, "test",
                CUSTOM_PKG_UID, false);
        mZenModeHelper.setAutomaticZenRuleState(zenRule.getConditionId(),
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                CUSTOM_PKG_UID, false);

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

        String id = mZenModeHelper.addAutomaticZenRule(null, zenRule, "test",
                CUSTOM_PKG_UID, false);

        AutomaticZenRule zenRule2 = new AutomaticZenRule("NEW",
                null,
                new ComponentName(mContext.getPackageName(), "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                new ZenPolicy.Builder().build(),
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);

        mZenModeHelper.updateAutomaticZenRule(id, zenRule2, "", CUSTOM_PKG_UID, false);

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

        String id = mZenModeHelper.addAutomaticZenRule(null, zenRule, "test",
                CUSTOM_PKG_UID, false);

        assertTrue(id != null);
        ZenModeConfig.ZenRule ruleInConfig = mZenModeHelper.mConfig.automaticRules.get(id);
        assertTrue(ruleInConfig != null);
        assertEquals(zenRule.getName(), ruleInConfig.name);

        mZenModeHelper.removeAutomaticZenRule(id, "test", CUSTOM_PKG_UID, false);
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
        String id = mZenModeHelper.addAutomaticZenRule(null, zenRule, "test",
                CUSTOM_PKG_UID, false);

        assertTrue(id != null);
        ZenModeConfig.ZenRule ruleInConfig = mZenModeHelper.mConfig.automaticRules.get(id);
        assertTrue(ruleInConfig != null);
        assertEquals(zenRule.getName(), ruleInConfig.name);

        mZenModeHelper.removeAutomaticZenRules(mContext.getPackageName(), "test",
                CUSTOM_PKG_UID, false);
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
        String id = mZenModeHelper.addAutomaticZenRule("android", zenRule, "test",
                Process.SYSTEM_UID, true);
        AutomaticZenRule zenRule2 = new AutomaticZenRule("name2",
                new ComponentName("android", "ScheduleConditionProvider"),
                sharedUri,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id2 = mZenModeHelper.addAutomaticZenRule("android", zenRule2, "test",
                Process.SYSTEM_UID, true);

        Condition condition = new Condition(sharedUri, "", STATE_TRUE);
        mZenModeHelper.setAutomaticZenRuleState(sharedUri, condition, Process.SYSTEM_UID, true);

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
        mZenModeHelper.setAutomaticZenRuleState(sharedUri, condition, Process.SYSTEM_UID, true);

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
    public void testSetManualZenMode() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
        setupZenConfig();

        // note that caller=null because that's how it comes in from NMS.setZenMode
        mZenModeHelper.setManualZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null, null, "",
                Process.SYSTEM_UID, true);

        // confirm that setting zen mode via setManualZenMode changed the zen mode correctly
        assertEquals(ZEN_MODE_IMPORTANT_INTERRUPTIONS, mZenModeHelper.mZenMode);
        assertEquals(true, mZenModeHelper.mConfig.manualRule.allowManualInvocation);

        // and also that it works to turn it back off again
        mZenModeHelper.setManualZenMode(Global.ZEN_MODE_OFF, null, null, "",
                Process.SYSTEM_UID, true);

        assertEquals(Global.ZEN_MODE_OFF, mZenModeHelper.mZenMode);
    }

    @Test
    public void testSetManualZenMode_legacy() {
        setupZenConfig();

        // note that caller=null because that's how it comes in from NMS.setZenMode
        mZenModeHelper.setManualZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null, null, "",
                Process.SYSTEM_UID, true);

        // confirm that setting zen mode via setManualZenMode changed the zen mode correctly
        assertEquals(ZEN_MODE_IMPORTANT_INTERRUPTIONS, mZenModeHelper.mZenMode);

        // and also that it works to turn it back off again
        mZenModeHelper.setManualZenMode(Global.ZEN_MODE_OFF, null, null, "",
                Process.SYSTEM_UID, true);

        assertEquals(Global.ZEN_MODE_OFF, mZenModeHelper.mZenMode);
    }

    @Test
    public void testZenModeEventLog_setManualZenMode() throws IllegalArgumentException {
        mTestFlagResolver.setFlagOverride(LOG_DND_STATE_EVENTS, true);
        setupZenConfig();

        // Turn zen mode on (to important_interruptions)
        // Need to additionally call the looper in order to finish the post-apply-config process
        mZenModeHelper.setManualZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null, null, "",
                Process.SYSTEM_UID, true);

        // Now turn zen mode off, but via a different package UID -- this should get registered as
        // "not an action by the user" because some other app is changing zen mode
        mZenModeHelper.setManualZenMode(Global.ZEN_MODE_OFF, null, null, "", CUSTOM_PKG_UID,
                false);

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
        assertEquals(Global.ZEN_MODE_OFF, mZenModeEventLogger.getPrevZenMode(0));
        assertEquals(ZEN_MODE_IMPORTANT_INTERRUPTIONS, mZenModeEventLogger.getNewZenMode(0));
        assertEquals(DNDProtoEnums.MANUAL_RULE, mZenModeEventLogger.getChangedRuleType(0));
        assertEquals(1, mZenModeEventLogger.getNumRulesActive(0));
        assertTrue(mZenModeEventLogger.getFromSystemOrSystemUi(0));
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
        assertEquals(Global.ZEN_MODE_OFF, mZenModeEventLogger.getNewZenMode(1));
        assertEquals(DNDProtoEnums.MANUAL_RULE, mZenModeEventLogger.getChangedRuleType(1));
        assertEquals(0, mZenModeEventLogger.getNumRulesActive(1));
        assertFalse(mZenModeEventLogger.getIsUserAction(1));
        assertEquals(CUSTOM_PKG_UID, mZenModeEventLogger.getPackageUid(1));
        checkDndProtoMatchesSetupZenConfig(mZenModeEventLogger.getPolicyProto(1));
    }

    @Test
    public void testZenModeEventLog_automaticRules() throws IllegalArgumentException {
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
                "test", Process.SYSTEM_UID, true);

        // Event 1: Mimic the rule coming on automatically by setting the Condition to STATE_TRUE
        mZenModeHelper.setAutomaticZenRuleState(id,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                Process.SYSTEM_UID, true);

        // Event 2: "User" turns off the automatic rule (sets it to not enabled)
        zenRule.setEnabled(false);
        mZenModeHelper.updateAutomaticZenRule(id, zenRule, "", Process.SYSTEM_UID, true);

        // Add a new system rule
        AutomaticZenRule systemRule = new AutomaticZenRule("systemRule",
                null,
                new ComponentName("android", "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String systemId = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), systemRule,
                "test", Process.SYSTEM_UID, true);

        // Event 3: turn on the system rule
        mZenModeHelper.setAutomaticZenRuleState(systemId,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                Process.SYSTEM_UID, true);

        // Event 4: "User" deletes the rule
        mZenModeHelper.removeAutomaticZenRule(systemId, "", Process.SYSTEM_UID, true);

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
        assertEquals(Global.ZEN_MODE_OFF, mZenModeEventLogger.getPrevZenMode(0));
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
        assertEquals(Global.ZEN_MODE_OFF, mZenModeEventLogger.getNewZenMode(1));
        assertEquals(DNDProtoEnums.AUTOMATIC_RULE, mZenModeEventLogger.getChangedRuleType(1));
        assertEquals(0, mZenModeEventLogger.getNumRulesActive(1));
        assertTrue(mZenModeEventLogger.getIsUserAction(1));
        assertEquals(Process.SYSTEM_UID, mZenModeEventLogger.getPackageUid(1));
        checkDndProtoMatchesSetupZenConfig(mZenModeEventLogger.getPolicyProto(1));

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
    public void testZenModeEventLog_policyChanges() throws IllegalArgumentException {
        mTestFlagResolver.setFlagOverride(LOG_DND_STATE_EVENTS, true);
        setupZenConfig();

        // First just turn zen mode on
        mZenModeHelper.setManualZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null, null, "",
                Process.SYSTEM_UID, true);

        // Now change the policy slightly; want to confirm that this'll be reflected in the logs
        ZenModeConfig newConfig = mZenModeHelper.mConfig.copy();
        newConfig.allowAlarms = true;
        newConfig.allowRepeatCallers = false;
        mZenModeHelper.setNotificationPolicy(newConfig.toNotificationPolicy(), Process.SYSTEM_UID,
                true);

        // Turn zen mode off; we want to make sure policy changes do not get logged when zen mode
        // is off.
        mZenModeHelper.setManualZenMode(Global.ZEN_MODE_OFF, null, null, "",
                Process.SYSTEM_UID, true);

        // Change the policy again
        newConfig.allowMessages = false;
        newConfig.allowRepeatCallers = true;
        mZenModeHelper.setNotificationPolicy(newConfig.toNotificationPolicy(), Process.SYSTEM_UID,
                true);

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
    public void testZenModeEventLog_ruleCounts() throws IllegalArgumentException {
        mTestFlagResolver.setFlagOverride(LOG_DND_STATE_EVENTS, true);
        setupZenConfig();

        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule, "test",
                Process.SYSTEM_UID, true);

        // Rule 2, same as rule 1
        AutomaticZenRule zenRule2 = new AutomaticZenRule("name2",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id2 = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule2, "test",
                Process.SYSTEM_UID, true);

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
        String id3 = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule3, "test",
                Process.SYSTEM_UID, true);

        // First: turn on rule 1
        mZenModeHelper.setAutomaticZenRuleState(id,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                Process.SYSTEM_UID, true);

        // Second: turn on rule 2
        mZenModeHelper.setAutomaticZenRuleState(id2,
                new Condition(zenRule2.getConditionId(), "", STATE_TRUE),
                Process.SYSTEM_UID, true);

        // Third: turn on rule 3
        mZenModeHelper.setAutomaticZenRuleState(id3,
                new Condition(zenRule3.getConditionId(), "", STATE_TRUE),
                Process.SYSTEM_UID, true);

        // Fourth: Turn *off* rule 2
        mZenModeHelper.setAutomaticZenRuleState(id2,
                new Condition(zenRule2.getConditionId(), "", STATE_FALSE),
                Process.SYSTEM_UID, true);

        // This should result in a total of four events
        assertEquals(4, mZenModeEventLogger.numLoggedChanges());

        // Event 1: rule 1 turns on. We expect this to turn on DND (zen mode) overall, so that's
        // what the event should reflect. At this time, the policy is the same as initial setup.
        assertEquals(ZenModeEventLogger.ZenStateChangedEvent.DND_TURNED_ON.getId(),
                mZenModeEventLogger.getEventId(0));
        assertEquals(Global.ZEN_MODE_OFF, mZenModeEventLogger.getPrevZenMode(0));
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
    public void testZenModeEventLog_noLogWithNoConfigChange() throws IllegalArgumentException {
        // If evaluateZenMode is called independently of a config change, don't log.
        mTestFlagResolver.setFlagOverride(LOG_DND_STATE_EVENTS, true);
        setupZenConfig();

        // Artificially turn zen mode "on". Re-evaluating zen mode should cause it to turn back off
        // given that we don't have any zen rules active.
        mZenModeHelper.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelper.evaluateZenModeLocked("test", true);

        // Check that the change actually took: zen mode should be off now
        assertEquals(Global.ZEN_MODE_OFF, mZenModeHelper.mZenMode);

        // but still, nothing should've been logged
        assertEquals(0, mZenModeEventLogger.numLoggedChanges());
    }

    @Test
    public void testZenModeEventLog_reassignUid() throws IllegalArgumentException {
        // Test that, only in specific cases, we reassign the calling UID to one associated with
        // the automatic rule owner.
        mTestFlagResolver.setFlagOverride(LOG_DND_STATE_EVENTS, true);
        setupZenConfig();

        // Rule 1, owned by a package
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                null,
                new ComponentName(CUSTOM_PKG_NAME, "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule, "test",
                Process.SYSTEM_UID, true);

        // Rule 2, same as rule 1 but owned by the system
        AutomaticZenRule zenRule2 = new AutomaticZenRule("name2",
                null,
                new ComponentName("android", "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id2 = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule2, "test",
                Process.SYSTEM_UID, true);

        // Turn on rule 1; call looks like it's from the system. Because setting a condition is
        // typically an automatic (non-user-initiated) action, expect the calling UID to be
        // re-evaluated to the one associat.d with CUSTOM_PKG_NAME.
        mZenModeHelper.setAutomaticZenRuleState(id,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                Process.SYSTEM_UID, true);

        // Second: turn on rule 2. This is a system-owned rule and the UID should not be modified
        // (nor even looked up; the mock PackageManager won't handle "android" as input).
        mZenModeHelper.setAutomaticZenRuleState(id2,
                new Condition(zenRule2.getConditionId(), "", STATE_TRUE),
                Process.SYSTEM_UID, true);

        // Disable rule 1. Because this looks like a user action, the UID should not be modified
        // from the system-provided one.
        zenRule.setEnabled(false);
        mZenModeHelper.updateAutomaticZenRule(id, zenRule, "", Process.SYSTEM_UID, true);

        // Add a manual rule. Any manual rule changes should not get calling uids reassigned.
        mZenModeHelper.setManualZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null, null, "",
                CUSTOM_PKG_UID, false);

        // Change rule 2's condition, but from some other UID. Since it doesn't look like it's from
        // the system, we keep the UID info.
        mZenModeHelper.setAutomaticZenRuleState(id2,
                new Condition(zenRule2.getConditionId(), "", STATE_FALSE),
                12345, false);

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
    public void testZenModeEventLog_channelsBypassingChanges() {
        // Verify that the right thing happens when the canBypassDnd value changes.
        mTestFlagResolver.setFlagOverride(LOG_DND_STATE_EVENTS, true);
        setupZenConfig();

        // Turn on zen mode with a manual rule with an enabler set. This should *not* count
        // as a user action, and *should* get its UID reassigned.
        mZenModeHelper.setManualZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null,
                CUSTOM_PKG_NAME, "", Process.SYSTEM_UID, true);

        // Now change apps bypassing to true
        ZenModeConfig newConfig = mZenModeHelper.mConfig.copy();
        newConfig.areChannelsBypassingDnd = true;
        mZenModeHelper.setNotificationPolicy(newConfig.toNotificationPolicy(), Process.SYSTEM_UID,
                true);

        // and then back to false, all without changing anything else
        newConfig.areChannelsBypassingDnd = false;
        mZenModeHelper.setNotificationPolicy(newConfig.toNotificationPolicy(), Process.SYSTEM_UID,
                true);

        // Turn off manual mode, call from a package: don't reset UID even though enabler is set
        mZenModeHelper.setManualZenMode(Global.ZEN_MODE_OFF, null,
                CUSTOM_PKG_NAME, "", 12345, false);

        // And likewise when turning it back on again
        mZenModeHelper.setManualZenMode(ZEN_MODE_IMPORTANT_INTERRUPTIONS, null,
                CUSTOM_PKG_NAME, "", 12345, false);

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
        String id = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule, "test",
                Process.SYSTEM_UID, true);

        // enable the rule
        mZenModeHelper.setAutomaticZenRuleState(id,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                Process.SYSTEM_UID, true);

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
        String id = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule, "test",
                Process.SYSTEM_UID, true);

        // enable the rule; this will update the consolidated policy
        mZenModeHelper.setAutomaticZenRuleState(id,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                Process.SYSTEM_UID, true);

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
        String id = mZenModeHelper.addAutomaticZenRule(mContext.getPackageName(), zenRule, "test",
                Process.SYSTEM_UID, true);

        // enable rule 1
        mZenModeHelper.setAutomaticZenRuleState(id,
                new Condition(zenRule.getConditionId(), "", STATE_TRUE),
                Process.SYSTEM_UID, true);

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
                "test", Process.SYSTEM_UID, true);

        // enable rule 2; this will update the consolidated policy
        mZenModeHelper.setAutomaticZenRuleState(id2,
                new Condition(zenRule2.getConditionId(), "", STATE_TRUE),
                Process.SYSTEM_UID, true);

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
    public void testCreateAutomaticZenRule_allFields() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();
        rule.configurationActivity = CONFIG_ACTIVITY;
        rule.component = OWNER;
        rule.conditionId = CONDITION_ID;
        rule.condition = CONDITION;
        rule.enabled = ENABLED;
        rule.creationTime = 123;
        rule.id = "id";
        rule.zenMode = INTERRUPTION_FILTER;
        rule.modified = true;
        rule.name = NAME;
        rule.snoozing = true;
        rule.pkg = OWNER.getPackageName();
        rule.zenPolicy = POLICY;

        rule.allowManualInvocation = ALLOW_MANUAL;
        rule.type = TYPE;
        rule.iconResId = ICON_RES_ID;
        rule.triggerDescription = TRIGGER_DESC;

        AutomaticZenRule actual = mZenModeHelper.createAutomaticZenRule(rule);

        assertEquals(NAME, actual.getName());
        assertEquals(OWNER, actual.getOwner());
        assertEquals(CONDITION_ID, actual.getConditionId());
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALARMS,
                actual.getInterruptionFilter());
        assertEquals(ENABLED, actual.isEnabled());
        assertEquals(POLICY, actual.getZenPolicy());
        assertEquals(CONFIG_ACTIVITY, actual.getConfigurationActivity());
        assertEquals(TYPE, actual.getType());
        assertEquals(ALLOW_MANUAL, actual.isManualInvocationAllowed());
        assertEquals(CREATION_TIME, actual.getCreationTime());
        assertEquals(OWNER.getPackageName(), actual.getPackageName());
        assertEquals(ICON_RES_ID, actual.getIconResId());
        assertEquals(TRIGGER_DESC, actual.getTriggerDescription());
    }

    private void setupZenConfig() {
        mZenModeHelper.mZenMode = Global.ZEN_MODE_OFF;
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
