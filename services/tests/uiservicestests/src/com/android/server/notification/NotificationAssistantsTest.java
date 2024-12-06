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

import static android.os.UserHandle.USER_ALL;
import static android.service.notification.Adjustment.KEY_IMPORTANCE;
import static android.service.notification.Adjustment.TYPE_CONTENT_RECOMMENDATION;
import static android.service.notification.Adjustment.TYPE_NEWS;
import static android.service.notification.Adjustment.TYPE_PROMOTION;

import static com.android.server.notification.NotificationManagerService.DEFAULT_ALLOWED_ADJUSTMENTS;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.ActivityManager;
import android.app.INotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.os.UserManager;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.service.notification.Adjustment;
import android.testing.TestableContext;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Xml;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.CollectionUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.UiServiceTestCase;
import com.android.server.notification.NotificationManagerService.NotificationAssistants;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class NotificationAssistantsTest extends UiServiceTestCase {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock
    private PackageManager mPm;
    @Mock
    private IPackageManager miPm;
    @Mock
    private UserManager mUm;
    @Mock
    NotificationManagerService mNm;
    @Mock
    private INotificationManager mINm;

    NotificationAssistants mAssistants;

    @Mock
    private ManagedServices.UserProfiles mUserProfiles;

    private TestableContext mContext = spy(getContext());
    Object mLock = new Object();


    UserInfo mZero = new UserInfo(0, "zero", 0);
    UserInfo mTen = new UserInfo(10, "ten", 0);

    ComponentName mCn = new ComponentName("a", "b");


    // Helper function to hold mApproved lock, avoid GuardedBy lint errors
    private boolean isUserSetServicesEmpty(NotificationAssistants assistant, int userId) {
        synchronized (assistant.mApproved) {
            return assistant.mUserSetServices.get(userId).isEmpty();
        }
    }

    private void writeXmlAndReload(int userId) throws Exception {
        TypedXmlSerializer serializer = Xml.newFastSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
        serializer.startDocument(null, true);
        mAssistants.writeXml(serializer, false, userId);
        serializer.endDocument();
        serializer.flush();

        //fail(baos.toString("UTF-8"));

        final TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);

        parser.nextTag();
        mAssistants = spy(mNm.new NotificationAssistants(mContext, mLock, mUserProfiles, miPm));
        mAssistants.readXml(parser, mNm::canUseManagedServices, false, USER_ALL);
    }


    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext.setMockPackageManager(mPm);
        mContext.addMockSystemService(Context.USER_SERVICE, mUm);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.string.config_defaultAssistantAccessComponent,
                mCn.flattenToString());
        mAssistants = spy(mNm.new NotificationAssistants(mContext, mLock, mUserProfiles, miPm));
        when(mNm.getBinderService()).thenReturn(mINm);
        mContext.ensureTestableResources();

        List<ResolveInfo> approved = new ArrayList<>();
        ResolveInfo resolve = new ResolveInfo();
        approved.add(resolve);
        ServiceInfo info = new ServiceInfo();
        info.packageName = mCn.getPackageName();
        info.name = mCn.getClassName();
        info.permission = Manifest.permission.BIND_NOTIFICATION_ASSISTANT_SERVICE;
        resolve.serviceInfo = info;
        when(mPm.queryIntentServicesAsUser(any(), anyInt(), anyInt()))
                .thenReturn(approved);

        List<UserInfo> users = new ArrayList<>();
        users.add(mZero);
        users.add(mTen);
        users.add(new UserInfo(11, "11", 0));
        users.add(new UserInfo(12, "12", 0));
        for (UserInfo user : users) {
            when(mUm.getUserInfo(eq(user.id))).thenReturn(user);
        }
        when(mUm.getUsers()).thenReturn(users);
        when(mUm.getAliveUsers()).thenReturn(users);
        IntArray profileIds = new IntArray();
        profileIds.add(0);
        profileIds.add(11);
        profileIds.add(10);
        profileIds.add(12);
        when(mUserProfiles.getCurrentProfileIds()).thenReturn(profileIds);
        when(mNm.isNASMigrationDone(anyInt())).thenReturn(true);
        when(mNm.canUseManagedServices(any(), anyInt(), any())).thenReturn(true);
    }

    @Test
    public void testXmlUpgrade() {
        mAssistants.resetDefaultAssistantsIfNecessary();

        //once per user
        verify(mNm, times(mUm.getUsers().size())).setDefaultAssistantForUser(anyInt());
    }

    @Test
    public void testWriteXml_userTurnedOffNAS() throws Exception {
        int userId = ActivityManager.getCurrentUser();

        mAssistants.loadDefaultsFromConfig(true);

        mAssistants.setPackageOrComponentEnabled(mCn.flattenToString(), userId, true,
               true, true);

        ComponentName current = CollectionUtils.firstOrNull(
                mAssistants.getAllowedComponents(userId));
        assertNotNull(current);
        mAssistants.setUserSet(userId, true);
        mAssistants.setPackageOrComponentEnabled(current.flattenToString(), userId, true, false,
                true);

        writeXmlAndReload(USER_ALL);

        ArrayMap<Boolean, ArraySet<String>> approved =
                mAssistants.mApproved.get(ActivityManager.getCurrentUser());
        // approved should not be null
        assertNotNull(approved);
        assertEquals(new ArraySet<>(), approved.get(true));

        // user set is maintained
        assertTrue(mAssistants.mIsUserChanged.get(ActivityManager.getCurrentUser()));
    }

    @Test
    public void testWriteXml_userTurnedOffNAS_backup() throws Exception {
        int userId = 10;

        mAssistants.loadDefaultsFromConfig(true);

        mAssistants.setPackageOrComponentEnabled(mCn.flattenToString(), userId, true,
                true, true);

        ComponentName current = CollectionUtils.firstOrNull(
                mAssistants.getAllowedComponents(userId));
        mAssistants.setUserSet(userId, true);
        mAssistants.setPackageOrComponentEnabled(current.flattenToString(), userId, true, false,
                true);
        assertTrue(mAssistants.mIsUserChanged.get(userId));
        assertThat(mAssistants.getApproved(userId, true)).isEmpty();

        writeXmlAndReload(userId);

        ArrayMap<Boolean, ArraySet<String>> approved = mAssistants.mApproved.get(userId);
        // approved should not be null
        assertNotNull(approved);
        assertEquals(new ArraySet<>(), approved.get(true));

        // user set is maintained
        assertTrue(mAssistants.mIsUserChanged.get(userId));
        assertThat(mAssistants.getApproved(userId, true)).isEmpty();
    }

    @Test
    public void testReadXml_userDisabled() throws Exception {
        String xml = "<enabled_assistants version=\"4\" defaults=\"b/b\">"
                + "<service_listing approved=\"\" user=\"0\" primary=\"true\""
                + "user_changed=\"true\"/>"
                + "</enabled_assistants>";

        final TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.toString().getBytes())), null);

        parser.nextTag();
        mAssistants.readXml(parser, mNm::canUseManagedServices, false, USER_ALL);

        ArrayMap<Boolean, ArraySet<String>> approved = mAssistants.mApproved.get(0);

        // approved should not be null
        assertNotNull(approved);
        assertEquals(new ArraySet<>(), approved.get(true));
    }

    @Test
    public void testReadXml_userDisabled_restore() throws Exception {
        String xml = "<enabled_assistants version=\"4\" defaults=\"b/b\">"
                + "<service_listing approved=\"\" user=\"0\" primary=\"true\""
                + "user_changed=\"true\"/>"
                + "</enabled_assistants>";

        final TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.toString().getBytes())), null);

        parser.nextTag();
        mAssistants.readXml(parser, mNm::canUseManagedServices, true,
                ActivityManager.getCurrentUser());

        ArrayMap<Boolean, ArraySet<String>> approved = mAssistants.mApproved.get(0);

        // approved should not be null
        assertNotNull(approved);
        assertEquals(new ArraySet<>(), approved.get(true));

        // user set is maintained
        assertTrue(mAssistants.mIsUserChanged.get(ActivityManager.getCurrentUser()));
    }

    @Test
    public void testReadXml_upgradeUserSet() throws Exception {
        String xml = "<enabled_assistants version=\"3\" defaults=\"b/b\">"
                + "<service_listing approved=\"\" user=\"0\" primary=\"true\""
                + "user_set_services=\"b/b\"/>"
                + "</enabled_assistants>";

        final TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.toString().getBytes())), null);

        parser.nextTag();
        mAssistants.readXml(parser, mNm::canUseManagedServices, false, USER_ALL);

        verify(mAssistants, times(1)).upgradeUserSet();
        assertTrue(mAssistants.mIsUserChanged.get(0));
    }

    @Test
    public void testReadXml_upgradeUserSet_preS_VersionThree() throws Exception {
        String xml = "<enabled_assistants version=\"3\" defaults=\"b/b\">"
                + "<service_listing approved=\"\" user=\"0\" primary=\"true\""
                + "user_set=\"true\"/>"
                + "</enabled_assistants>";

        final TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.toString().getBytes())), null);

        parser.nextTag();
        mAssistants.readXml(parser, mNm::canUseManagedServices, false, USER_ALL);

        verify(mAssistants, times(0)).upgradeUserSet();
        assertTrue(isUserSetServicesEmpty(mAssistants, 0));
        assertTrue(mAssistants.mIsUserChanged.get(0));
    }

    @Test
    public void testReadXml_upgradeUserSet_preS_VersionOne() throws Exception {
        String xml = "<enabled_assistants version=\"1\" defaults=\"b/b\">"
                + "<service_listing approved=\"\" user=\"0\" primary=\"true\""
                + "user_set=\"true\"/>"
                + "</enabled_assistants>";

        final TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.toString().getBytes())), null);

        parser.nextTag();
        mAssistants.readXml(parser, mNm::canUseManagedServices, false, USER_ALL);

        verify(mAssistants, times(0)).upgradeUserSet();
        assertTrue(isUserSetServicesEmpty(mAssistants, 0));
        assertTrue(mAssistants.mIsUserChanged.get(0));
    }

    @Test
    public void testReadXml_upgradeUserSet_preS_noUserSet() throws Exception {
        String xml = "<enabled_assistants version=\"3\" defaults=\"b/b\">"
                + "<service_listing approved=\"b/b\" user=\"0\" primary=\"true\"/>"
                + "</enabled_assistants>";

        final TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.toString().getBytes())), null);

        parser.nextTag();
        mAssistants.readXml(parser, mNm::canUseManagedServices, false, USER_ALL);

        verify(mAssistants, times(1)).upgradeUserSet();
        assertTrue(isUserSetServicesEmpty(mAssistants, 0));
        assertFalse(mAssistants.mIsUserChanged.get(0));
    }

    @Test
    public void testReadXml_upgradeUserSet_preS_noUserSet_diffDefault() throws Exception {
        String xml = "<enabled_assistants version=\"3\" defaults=\"a/a\">"
                + "<service_listing approved=\"b/b\" user=\"0\" primary=\"true\"/>"
                + "</enabled_assistants>";

        final TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.toString().getBytes())), null);

        parser.nextTag();
        mAssistants.readXml(parser, mNm::canUseManagedServices, false, USER_ALL);

        verify(mAssistants, times(1)).upgradeUserSet();
        assertTrue(isUserSetServicesEmpty(mAssistants, 0));
        assertFalse(mAssistants.mIsUserChanged.get(0));
        assertEquals(new ArraySet<>(Arrays.asList(new ComponentName("a", "a"))),
                mAssistants.getDefaultComponents());
        assertEquals(Arrays.asList(new ComponentName("b", "b")),
                mAssistants.getAllowedComponents(0));
    }

    @Test
    public void testReadXml_multiApproved() throws Exception {
        String xml = "<enabled_assistants version=\"4\" defaults=\"b/b\">"
                + "<service_listing approved=\"a/a:b/b\" user=\"0\" primary=\"true\""
                + "user_changed=\"true\"/>"
                + "</enabled_assistants>";

        final TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.toString().getBytes())), null);

        parser.nextTag();
        mAssistants.readXml(parser, null, false, USER_ALL);

        assertEquals(1, mAssistants.getAllowedComponents(0).size());
        assertEquals(new ArrayList(Arrays.asList(new ComponentName("a", "a"))),
                mAssistants.getAllowedComponents(0));
    }

    @Test
    public void testXmlUpgradeExistingApprovedComponents() throws Exception {
        String xml = "<enabled_assistants version=\"2\" defaults=\"b\\b\">"
                + "<service_listing approved=\"b/b\" user=\"10\" primary=\"true\" />"
                + "</enabled_assistants>";

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.toString().getBytes())), null);
        parser.nextTag();
        mAssistants.readXml(parser, null, false, USER_ALL);

        verify(mNm, never()).setDefaultAssistantForUser(anyInt());
        verify(mAssistants, times(1)).addApprovedList(
                new ComponentName("b", "b").flattenToString(), 10, true, "");
    }

    @Test
    public void testSetPackageOrComponentEnabled_onlyOnePackage() throws Exception {
        ComponentName component1 = ComponentName.unflattenFromString("package/Component1");
        ComponentName component2 = ComponentName.unflattenFromString("package/Component2");
        mAssistants.setPackageOrComponentEnabled(component1.flattenToString(), mZero.id, true,
                true, true);
        verify(mNm, never()).setNotificationAssistantAccessGrantedForUserInternal(
                any(ComponentName.class), eq(mZero.id), anyBoolean(), anyBoolean());

        mAssistants.setPackageOrComponentEnabled(component2.flattenToString(), mZero.id, true,
                true, true);
        verify(mNm, times(1)).setNotificationAssistantAccessGrantedForUserInternal(
                component1, mZero.id, false, true);
    }

    @Test
    public void testSetPackageOrComponentEnabled_samePackage() throws Exception {
        ComponentName component1 = ComponentName.unflattenFromString("package/Component1");
        mAssistants.setPackageOrComponentEnabled(component1.flattenToString(), mZero.id, true,
                true, true);
        mAssistants.setPackageOrComponentEnabled(component1.flattenToString(), mZero.id, true,
                true, true);
        verify(mNm, never()).setNotificationAssistantAccessGrantedForUserInternal(
                any(ComponentName.class), eq(mZero.id), anyBoolean(), anyBoolean());
    }

    @Test
    public void testLoadDefaultsFromConfig() {
        ComponentName oldDefaultComponent = ComponentName.unflattenFromString("package/Component1");
        ComponentName newDefaultComponent = ComponentName.unflattenFromString("package/Component2");

        doReturn(new ArraySet<>(Arrays.asList(oldDefaultComponent, newDefaultComponent)))
                .when(mAssistants).queryPackageForServices(any(), anyInt(), anyInt());
        // Test loadDefaultsFromConfig() add the config value to mDefaultComponents instead of
        // mDefaultFromConfig
        when(mContext.getResources().getString(
                com.android.internal.R.string.config_defaultAssistantAccessComponent))
                .thenReturn(oldDefaultComponent.flattenToString());
        mAssistants.loadDefaultsFromConfig();
        assertEquals(new ArraySet<>(Arrays.asList(oldDefaultComponent)),
                mAssistants.getDefaultComponents());
        assertNull(mAssistants.mDefaultFromConfig);

        // Test loadDefaultFromConfig(false) only updates the mDefaultFromConfig
        when(mContext.getResources().getString(
                com.android.internal.R.string.config_defaultAssistantAccessComponent))
                .thenReturn(newDefaultComponent.flattenToString());
        mAssistants.loadDefaultsFromConfig(false);
        assertEquals(new ArraySet<>(Arrays.asList(oldDefaultComponent)),
                mAssistants.getDefaultComponents());
        assertEquals(newDefaultComponent, mAssistants.getDefaultFromConfig());

        // Test resetDefaultFromConfig updates the mDefaultComponents to new config value
        mAssistants.resetDefaultFromConfig();
        assertEquals(new ArraySet<>(Arrays.asList(newDefaultComponent)),
                mAssistants.getDefaultComponents());
        assertEquals(newDefaultComponent, mAssistants.getDefaultFromConfig());
    }

    @Test
    public void testNASSettingUpgrade_userNotSet_differentDefaultNAS() {
        ComponentName oldDefaultComponent = ComponentName.unflattenFromString("package/Component1");
        ComponentName newDefaultComponent = ComponentName.unflattenFromString("package/Component2");

        when(mNm.isNASMigrationDone(anyInt())).thenReturn(false);
        doReturn(new ArraySet<>(Arrays.asList(newDefaultComponent)))
                .when(mAssistants).queryPackageForServices(any(), anyInt(), anyInt());
        when(mContext.getResources().getString(
                com.android.internal.R.string.config_defaultAssistantAccessComponent))
                .thenReturn(newDefaultComponent.flattenToString());

        // User hasn't set the default NAS, set the oldNAS as the default with userSet=false here.
        mAssistants.setPackageOrComponentEnabled(oldDefaultComponent.flattenToString(),
                mZero.id, true, true /*enabled*/, false /*userSet*/);


        // The migration for userSet==false happens in resetDefaultAssistantsIfNecessary
        mAssistants.resetDefaultAssistantsIfNecessary();

        // Verify the migration happened: setDefaultAssistantForUser should be called to
        // update defaults
        verify(mNm, times(1)).setNASMigrationDone(eq(mZero.id));
        verify(mNm, times(1)).setDefaultAssistantForUser(eq(mZero.id));
        assertEquals(new ArraySet<>(Arrays.asList(newDefaultComponent)),
                mAssistants.getDefaultComponents());

        when(mNm.isNASMigrationDone(anyInt())).thenReturn(true);

        // Test resetDefaultAssistantsIfNecessary again since it will be called on every reboot
        mAssistants.resetDefaultAssistantsIfNecessary();

        // The migration should not happen again, the invoke time for migration should not increase
        verify(mNm, times(1)).setNASMigrationDone(eq(mZero.id));
        // The invoke time outside migration part should increase by 1
        verify(mNm, times(2)).setDefaultAssistantForUser(eq(mZero.id));
    }

    @Test
    public void testNASSettingUpgrade_userNotSet_sameDefaultNAS() {
        ComponentName defaultComponent = ComponentName.unflattenFromString("package/Component1");

        when(mNm.isNASMigrationDone(anyInt())).thenReturn(false);
        doReturn(new ArraySet<>(Arrays.asList(defaultComponent)))
                .when(mAssistants).queryPackageForServices(any(), anyInt(), anyInt());
        when(mContext.getResources().getString(
                com.android.internal.R.string.config_defaultAssistantAccessComponent))
                .thenReturn(defaultComponent.flattenToString());

        // User hasn't set the default NAS, set the oldNAS as the default with userSet=false here.
        mAssistants.setPackageOrComponentEnabled(defaultComponent.flattenToString(),
                mZero.id, true, true /*enabled*/, false /*userSet*/);

        // The migration for userSet==false happens in resetDefaultAssistantsIfNecessary
        mAssistants.resetDefaultAssistantsIfNecessary();

        verify(mNm, times(1)).setNASMigrationDone(eq(mZero.id));
        verify(mNm, times(1)).setDefaultAssistantForUser(eq(mZero.id));
        assertEquals(new ArraySet<>(Arrays.asList(defaultComponent)),
                mAssistants.getDefaultComponents());
    }

    @Test
    public void testNASSettingUpgrade_userNotSet_defaultNASNone() {
        ComponentName oldDefaultComponent = ComponentName.unflattenFromString("package/Component1");
        when(mNm.isNASMigrationDone(anyInt())).thenReturn(false);
        doReturn(new ArraySet<>(Arrays.asList(oldDefaultComponent)))
                .when(mAssistants).queryPackageForServices(any(), anyInt(), anyInt());
        // New default is none
        when(mContext.getResources().getString(
                com.android.internal.R.string.config_defaultAssistantAccessComponent))
                .thenReturn("");

        // User hasn't set the default NAS, set the oldNAS as the default with userSet=false here.
        mAssistants.setPackageOrComponentEnabled(oldDefaultComponent.flattenToString(),
                mZero.id, true, true /*enabled*/, false /*userSet*/);

        // The migration for userSet==false happens in resetDefaultAssistantsIfNecessary
        mAssistants.resetDefaultAssistantsIfNecessary();

        verify(mNm, times(1)).setNASMigrationDone(eq(mZero.id));
        verify(mNm, times(1)).setDefaultAssistantForUser(eq(mZero.id));
        assertEquals(new ArraySet<>(), mAssistants.getDefaultComponents());
    }

    @Test
    @EnableFlags(android.service.notification.Flags.FLAG_NOTIFICATION_CLASSIFICATION)
    public void testSetAdjustmentTypeSupportedState() throws Exception {
        int userId = ActivityManager.getCurrentUser();

        mAssistants.loadDefaultsFromConfig(true);
        mAssistants.setPackageOrComponentEnabled(mCn.flattenToString(), userId, true,
                true, true);
        ComponentName current = CollectionUtils.firstOrNull(
                mAssistants.getAllowedComponents(userId));
        assertNotNull(current);

        assertThat(mAssistants.getUnsupportedAdjustments(userId).size()).isEqualTo(0);

        ManagedServices.ManagedServiceInfo info =
                mAssistants.new ManagedServiceInfo(null, mCn, userId, false, null, 35, 2345256);
        mAssistants.setAdjustmentTypeSupportedState(info, Adjustment.KEY_NOT_CONVERSATION, false);

        assertThat(mAssistants.getUnsupportedAdjustments(userId)).contains(
                Adjustment.KEY_NOT_CONVERSATION);
        assertThat(mAssistants.getUnsupportedAdjustments(userId).size()).isEqualTo(1);
    }

    @Test
    @EnableFlags(android.service.notification.Flags.FLAG_NOTIFICATION_CLASSIFICATION)
    public void testSetAdjustmentTypeSupportedState_readWriteXml_entries() throws Exception {
        int userId = ActivityManager.getCurrentUser();

        mAssistants.loadDefaultsFromConfig(true);
        mAssistants.setPackageOrComponentEnabled(mCn.flattenToString(), userId, true,
                true, true);
        ComponentName current = CollectionUtils.firstOrNull(
                mAssistants.getAllowedComponents(userId));
        assertNotNull(current);

        ManagedServices.ManagedServiceInfo info =
                mAssistants.new ManagedServiceInfo(null, mCn, userId, false, null, 35, 2345256);
        mAssistants.setAdjustmentTypeSupportedState(info, Adjustment.KEY_NOT_CONVERSATION, false);

        writeXmlAndReload(USER_ALL);

        assertThat(mAssistants.getUnsupportedAdjustments(userId)).contains(
                Adjustment.KEY_NOT_CONVERSATION);
        assertThat(mAssistants.getUnsupportedAdjustments(userId).size()).isEqualTo(1);
    }

    @Test
    @EnableFlags(android.service.notification.Flags.FLAG_NOTIFICATION_CLASSIFICATION)
    public void testSetAdjustmentTypeSupportedState_readWriteXml_empty() throws Exception {
        int userId = ActivityManager.getCurrentUser();

        mAssistants.loadDefaultsFromConfig(true);
        mAssistants.setPackageOrComponentEnabled(mCn.flattenToString(), userId, true,
                true, true);
        ComponentName current = CollectionUtils.firstOrNull(
                mAssistants.getAllowedComponents(userId));
        assertNotNull(current);

        writeXmlAndReload(USER_ALL);

        assertThat(mAssistants.getUnsupportedAdjustments(userId).size()).isEqualTo(0);
    }

    @Test
    @EnableFlags(android.service.notification.Flags.FLAG_NOTIFICATION_CLASSIFICATION)
    public void testDisallowAdjustmentType() {
        mAssistants.disallowAdjustmentType(Adjustment.KEY_RANKING_SCORE);
        assertThat(mAssistants.getAllowedAssistantAdjustments())
                .doesNotContain(Adjustment.KEY_RANKING_SCORE);
        assertThat(mAssistants.getAllowedAssistantAdjustments()).contains(Adjustment.KEY_TYPE);
    }

    @Test
    @EnableFlags(android.service.notification.Flags.FLAG_NOTIFICATION_CLASSIFICATION)
    public void testAllowAdjustmentType() {
        mAssistants.disallowAdjustmentType(Adjustment.KEY_RANKING_SCORE);
        assertThat(mAssistants.getAllowedAssistantAdjustments())
                .doesNotContain(Adjustment.KEY_RANKING_SCORE);
        mAssistants.allowAdjustmentType(Adjustment.KEY_RANKING_SCORE);
        assertThat(mAssistants.getAllowedAssistantAdjustments())
                .contains(Adjustment.KEY_RANKING_SCORE);
    }

    @Test
    @EnableFlags(android.service.notification.Flags.FLAG_NOTIFICATION_CLASSIFICATION)
    public void testDisallowAdjustmentType_readWriteXml_entries() throws Exception {
        int userId = ActivityManager.getCurrentUser();

        mAssistants.loadDefaultsFromConfig(true);
        mAssistants.disallowAdjustmentType(KEY_IMPORTANCE);

        writeXmlAndReload(USER_ALL);

        assertThat(mAssistants.getAllowedAssistantAdjustments()).contains(
                Adjustment.KEY_NOT_CONVERSATION);
        assertThat(mAssistants.getAllowedAssistantAdjustments()).doesNotContain(
                KEY_IMPORTANCE);
    }

    @Test
    public void testDefaultAllowedAdjustments_readWriteXml_entries() throws Exception {
        mAssistants.loadDefaultsFromConfig(true);

        writeXmlAndReload(USER_ALL);

        assertThat(mAssistants.getAllowedAssistantAdjustments())
                .containsExactlyElementsIn(DEFAULT_ALLOWED_ADJUSTMENTS);
    }

    @Test
    @EnableFlags(android.service.notification.Flags.FLAG_NOTIFICATION_CLASSIFICATION)
    public void testSetAssistantAdjustmentKeyTypeState_allow() {
        assertThat(mAssistants.getAllowedAdjustmentKeyTypes()).asList()
                .containsExactly(TYPE_PROMOTION);

        mAssistants.setAssistantAdjustmentKeyTypeState(TYPE_CONTENT_RECOMMENDATION, true);

        assertThat(mAssistants.getAllowedAdjustmentKeyTypes()).asList()
                .containsExactlyElementsIn(List.of(TYPE_PROMOTION, TYPE_CONTENT_RECOMMENDATION));
    }

    @Test
    @EnableFlags(android.service.notification.Flags.FLAG_NOTIFICATION_CLASSIFICATION)
    public void testSetAssistantAdjustmentKeyTypeState_disallow() {
        mAssistants.setAssistantAdjustmentKeyTypeState(TYPE_PROMOTION, false);
        assertThat(mAssistants.getAllowedAdjustmentKeyTypes()).isEmpty();
    }

    @Test
    @EnableFlags(android.service.notification.Flags.FLAG_NOTIFICATION_CLASSIFICATION)
    public void testDisallowAdjustmentKeyType_readWriteXml() throws Exception {
        mAssistants.loadDefaultsFromConfig(true);
        mAssistants.setAssistantAdjustmentKeyTypeState(TYPE_PROMOTION, false);
        mAssistants.setAssistantAdjustmentKeyTypeState(TYPE_NEWS, true);
        mAssistants.setAssistantAdjustmentKeyTypeState(TYPE_CONTENT_RECOMMENDATION, true);

        writeXmlAndReload(USER_ALL);

        assertThat(mAssistants.getAllowedAdjustmentKeyTypes()).asList()
                .containsExactlyElementsIn(List.of(TYPE_NEWS, TYPE_CONTENT_RECOMMENDATION));
    }

    @Test
    @EnableFlags(android.service.notification.Flags.FLAG_NOTIFICATION_CLASSIFICATION)
    public void testDefaultAllowedKeyAdjustments_readWriteXml() throws Exception {
        mAssistants.loadDefaultsFromConfig(true);

        writeXmlAndReload(USER_ALL);

        assertThat(mAssistants.getAllowedAdjustmentKeyTypes()).asList()
                .containsExactly(TYPE_PROMOTION);
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_NOTIFICATION_CLASSIFICATION_UI)
    public void testSetAssistantAdjustmentKeyTypeStateForPackage_allowsAndDenies() {
        // Given that a package is allowed to have its type adjusted,
        String allowedPackage = "allowed.package";
        assertThat(mAssistants.getTypeAdjustmentDeniedPackages()).isEmpty();
        mAssistants.setTypeAdjustmentForPackageState(allowedPackage, true);

        assertThat(mAssistants.getTypeAdjustmentDeniedPackages()).isEmpty();
        assertTrue(mAssistants.isTypeAdjustmentAllowedForPackage(allowedPackage));

        // Set type adjustment disallowed for this package
        mAssistants.setTypeAdjustmentForPackageState(allowedPackage, false);

        // Then the package is marked as denied
        assertThat(mAssistants.getTypeAdjustmentDeniedPackages()).asList()
                .containsExactly(allowedPackage);
        assertFalse(mAssistants.isTypeAdjustmentAllowedForPackage(allowedPackage));

        // Set type adjustment allowed again
        mAssistants.setTypeAdjustmentForPackageState(allowedPackage, true);

        // Then the package is marked as allowed again
        assertThat(mAssistants.getTypeAdjustmentDeniedPackages()).isEmpty();
        assertTrue(mAssistants.isTypeAdjustmentAllowedForPackage(allowedPackage));
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_NOTIFICATION_CLASSIFICATION_UI)
    public void testSetAssistantAdjustmentKeyTypeStateForPackage_deniesMultiple() {
        // Given packages not allowed to have their type adjusted,
        String deniedPkg1 = "denied.Pkg1";
        String deniedPkg2 = "denied.Pkg2";
        String deniedPkg3 = "denied.Pkg3";
        // Set type adjustment disallowed for these packages
        mAssistants.setTypeAdjustmentForPackageState(deniedPkg1, false);
        mAssistants.setTypeAdjustmentForPackageState(deniedPkg2, false);
        mAssistants.setTypeAdjustmentForPackageState(deniedPkg3, false);

        // Then the packages are marked as denied
        assertThat(mAssistants.getTypeAdjustmentDeniedPackages()).asList()
                .containsExactlyElementsIn(List.of(deniedPkg1, deniedPkg2, deniedPkg3));
        assertFalse(mAssistants.isTypeAdjustmentAllowedForPackage(deniedPkg1));
        assertFalse(mAssistants.isTypeAdjustmentAllowedForPackage(deniedPkg2));
        assertFalse(mAssistants.isTypeAdjustmentAllowedForPackage(deniedPkg3));

        // And when we re-allow one of them,
        mAssistants.setTypeAdjustmentForPackageState(deniedPkg2, true);

        // Then the rest of the original packages are still marked as denied.
        assertThat(mAssistants.getTypeAdjustmentDeniedPackages()).asList()
                .containsExactlyElementsIn(List.of(deniedPkg1, deniedPkg3));
        assertFalse(mAssistants.isTypeAdjustmentAllowedForPackage(deniedPkg1));
        assertTrue(mAssistants.isTypeAdjustmentAllowedForPackage(deniedPkg2));
        assertFalse(mAssistants.isTypeAdjustmentAllowedForPackage(deniedPkg3));
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_NOTIFICATION_CLASSIFICATION_UI)
    public void testSetAssistantAdjustmentKeyTypeStateForPackage_readWriteXml() throws Exception {
        mAssistants.loadDefaultsFromConfig(true);
        String deniedPkg1 = "denied.Pkg1";
        String allowedPkg2 = "allowed.Pkg2";
        String deniedPkg3 = "denied.Pkg3";
        // Set type adjustment disallowed or allowed for these packages
        mAssistants.setTypeAdjustmentForPackageState(deniedPkg1, false);
        mAssistants.setTypeAdjustmentForPackageState(allowedPkg2, true);
        mAssistants.setTypeAdjustmentForPackageState(deniedPkg3, false);

        writeXmlAndReload(USER_ALL);

        assertThat(mAssistants.getTypeAdjustmentDeniedPackages()).asList()
                .containsExactlyElementsIn(List.of(deniedPkg1, deniedPkg3));
    }
}