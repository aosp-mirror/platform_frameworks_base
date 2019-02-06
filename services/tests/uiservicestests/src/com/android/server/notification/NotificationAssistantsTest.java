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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
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
import android.util.IntArray;
import android.util.Xml;

import com.android.server.UiServiceTestCase;
import com.android.server.notification.NotificationManagerService.NotificationAssistants;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
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

    Object mLock = new Object();

    UserInfo mZero = new UserInfo(0, "zero", 0);
    UserInfo mTen = new UserInfo(10, "ten", 0);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        getContext().setMockPackageManager(mPm);
        getContext().addMockSystemService(Context.USER_SERVICE, mUm);
        mAssistants = spy(mNm.new NotificationAssistants(getContext(), mLock, mUserProfiles, miPm));
        when(mNm.getBinderService()).thenReturn(mINm);

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
        when(mUm.getUsers(anyBoolean())).thenReturn(users);
        IntArray profileIds = new IntArray();
        profileIds.add(0);
        profileIds.add(11);
        profileIds.add(10);
        profileIds.add(12);
        when(mUserProfiles.getCurrentProfileIds()).thenReturn(profileIds);
    }

    @Test
    public void testXmlUpgrade() {
        mAssistants.ensureAssistant();

        //once per user
        verify(mNm, times(mUm.getUsers().size())).readDefaultAssistant(anyInt());
    }

    @Test
    public void testXmlUpgradeExistingApprovedComponents() throws Exception {
        String xml = "<enabled_assistants>"
                + "<service_listing approved=\"b/b\" user=\"10\" primary=\"true\" />"
                + "</enabled_assistants>";

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.toString().getBytes())), null);
        parser.nextTag();
        mAssistants.readXml(parser, null, false, UserHandle.USER_ALL);

        verify(mNm, never()).readDefaultAssistant(anyInt());
        verify(mAssistants, times(1)).addApprovedList(
                new ComponentName("b", "b").flattenToString(),10, true);
    }

    @Test
    public void testSetPackageOrComponentEnabled_onlyOnePackage() throws Exception {
        ComponentName component1 = ComponentName.unflattenFromString("package/Component1");
        ComponentName component2 = ComponentName.unflattenFromString("package/Component2");
        mAssistants.setPackageOrComponentEnabled(component1.flattenToString(), mZero.id, true,
                true);
        verify(mINm, never()).setNotificationAssistantAccessGrantedForUser(any(ComponentName.class),
                eq(mZero.id), anyBoolean());

        mAssistants.setPackageOrComponentEnabled(component2.flattenToString(), mZero.id, true,
                true);
        verify(mINm, times(1)).setNotificationAssistantAccessGrantedForUser(component1, mZero.id,
                false);
    }

    @Test
    public void testSetPackageOrComponentEnabled_samePackage() throws Exception {
        ComponentName component1 = ComponentName.unflattenFromString("package/Component1");
        mAssistants.setPackageOrComponentEnabled(component1.flattenToString(), mZero.id, true,
                true);
        mAssistants.setPackageOrComponentEnabled(component1.flattenToString(), mZero.id, true,
                true);
        verify(mINm, never()).setNotificationAssistantAccessGrantedForUser(any(ComponentName.class),
                eq(mZero.id), anyBoolean());
    }
}
