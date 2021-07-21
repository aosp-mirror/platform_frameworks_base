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

import static junit.framework.Assert.assertEquals;
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

import android.app.INotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.testing.TestableContext;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.TypedXmlPullParser;
import android.util.Xml;

import com.android.internal.util.function.TriPredicate;
import com.android.server.UiServiceTestCase;
import com.android.server.notification.NotificationManagerService.NotificationAssistants;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NotificationAssistantsTest extends UiServiceTestCase {

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

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext.setMockPackageManager(mPm);
        mContext.addMockSystemService(Context.USER_SERVICE, mUm);
        mAssistants = spy(mNm.new NotificationAssistants(mContext, mLock, mUserProfiles, miPm));
        when(mNm.getBinderService()).thenReturn(mINm);
        mContext.ensureTestableResources();

        List<ResolveInfo> approved = new ArrayList<>();
        ResolveInfo resolve = new ResolveInfo();
        approved.add(resolve);
        ServiceInfo info = new ServiceInfo();
        info.packageName = "a";
        info.name="a";
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
    }

    @Test
    public void testXmlUpgrade() {
        mAssistants.resetDefaultAssistantsIfNecessary();

        //once per user
        verify(mNm, times(mUm.getUsers().size())).setDefaultAssistantForUser(anyInt());
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
        TriPredicate<String, Integer, String> allowedManagedServicePackages =
                mNm::canUseManagedServices;

        parser.nextTag();
        mAssistants.readXml(parser, allowedManagedServicePackages, false, UserHandle.USER_ALL);

        ArrayMap<Boolean, ArraySet<String>> approved = mAssistants.mApproved.get(0);

        // approved should not be null
        assertNotNull(approved);
        assertEquals(new ArraySet<>(), approved.get(true));
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
        TriPredicate<String, Integer, String> allowedManagedServicePackages =
                mNm::canUseManagedServices;

        parser.nextTag();
        mAssistants.readXml(parser, allowedManagedServicePackages, false, UserHandle.USER_ALL);

        verify(mAssistants, times(1)).upgradeUserSet();
        assertTrue(mAssistants.mIsUserChanged.get(0));
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
        mAssistants.readXml(parser, null, false, UserHandle.USER_ALL);

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
        mAssistants.readXml(parser, null, false, UserHandle.USER_ALL);

        verify(mNm, never()).setDefaultAssistantForUser(anyInt());
        verify(mAssistants, times(1)).addApprovedList(
                new ComponentName("b", "b").flattenToString(), 10, true, null);
    }

    @Test
    public void testXmlMigratingAllowedAdjustments() throws Exception {
        // Old tag, need migration
        String xml = "<q_allowed_adjustments types=\"adj_1\"/>";

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.toString().getBytes())), null);
        parser.nextTag();
        mAssistants.readExtraTag("q_allowed_adjustments", parser);
        assertTrue(mAssistants.isAdjustmentAllowed("adj_1"));
        assertEquals(mNm.DEFAULT_ALLOWED_ADJUSTMENTS.length + 1,
                mAssistants.getAllowedAssistantAdjustments().size());

        // New TAG
        xml = "<s_allowed_adjustments types=\"adj_2\"/>";
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.toString().getBytes())), null);
        parser.nextTag();
        mAssistants.readExtraTag("s_allowed_adjustments", parser);
        assertTrue(mAssistants.isAdjustmentAllowed("adj_2"));
        assertEquals(1, mAssistants.getAllowedAssistantAdjustments().size());
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
}
