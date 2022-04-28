/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.app;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.service.games.GameService;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.SystemService;
import com.android.server.app.GameServiceConfiguration.GameServiceComponentConfiguration;

import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;


/**
 * Unit tests for the {@link GameServiceProviderSelectorImpl}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public final class GameServiceProviderSelectorImplTest {

    private static final UserHandle USER_HANDLE_10 = new UserHandle(10);

    private static final int GAME_SERVICE_META_DATA_RES_ID = 1337;
    private static final String GAME_SERVICE_PACKAGE_NAME = "com.game.service.provider";
    private static final String GAME_SERVICE_CLASS_NAME = "com.game.service.provider.GameService";
    private static final ComponentName GAME_SERVICE_COMPONENT =
            new ComponentName(GAME_SERVICE_PACKAGE_NAME, GAME_SERVICE_CLASS_NAME);

    private static final int GAME_SERVICE_B_META_DATA_RES_ID = 1338;
    private static final String GAME_SERVICE_B_CLASS_NAME =
            "com.game.service.provider.GameServiceB";
    private static final ComponentName GAME_SERVICE_B_COMPONENT =
            new ComponentName(GAME_SERVICE_PACKAGE_NAME, GAME_SERVICE_B_CLASS_NAME);
    private static final ServiceInfo GAME_SERVICE_B_WITH_OUT_META_DATA =
            serviceInfo(GAME_SERVICE_PACKAGE_NAME, GAME_SERVICE_B_CLASS_NAME);
    private static final ServiceInfo GAME_SERVICE_B_SERVICE_INFO =
            addGameServiceMetaData(GAME_SERVICE_B_WITH_OUT_META_DATA,
                    GAME_SERVICE_B_META_DATA_RES_ID);

    private static final String GAME_SESSION_SERVICE_CLASS_NAME =
            "com.game.service.provider.GameSessionService";
    private static final ComponentName GAME_SESSION_SERVICE_COMPONENT =
            new ComponentName(GAME_SERVICE_PACKAGE_NAME, GAME_SESSION_SERVICE_CLASS_NAME);
    private static final ServiceInfo GAME_SERVICE_SERVICE_INFO_WITHOUT_META_DATA =
            serviceInfo(GAME_SERVICE_PACKAGE_NAME, GAME_SERVICE_CLASS_NAME);
    private static final ServiceInfo GAME_SERVICE_SERVICE_INFO =
            addGameServiceMetaData(GAME_SERVICE_SERVICE_INFO_WITHOUT_META_DATA,
                    GAME_SERVICE_META_DATA_RES_ID);

    @Mock
    private PackageManager mMockPackageManager;
    private Resources mSpyResources;
    private MockitoSession mMockingSession;
    private GameServiceProviderSelector mGameServiceProviderSelector;

    @Before
    public void setUp() throws PackageManager.NameNotFoundException {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .startMocking();

        mSpyResources = spy(
                InstrumentationRegistry.getInstrumentation().getContext().getResources());

        when(mMockPackageManager.getResourcesForApplication(anyString()))
                .thenReturn(mSpyResources);
        mGameServiceProviderSelector = new GameServiceProviderSelectorImpl(
                mSpyResources,
                mMockPackageManager);
    }

    @After
    public void tearDown() {
        mMockingSession.finishMocking();
    }

    @Test
    public void get_nullUser_returnsNull()
            throws Exception {
        seedSystemGameServicePackageName(GAME_SERVICE_PACKAGE_NAME);
        seedGameServiceResolveInfos(GAME_SERVICE_PACKAGE_NAME, USER_HANDLE_10,
                resolveInfo(GAME_SERVICE_SERVICE_INFO));
        seedServiceServiceInfo(GAME_SESSION_SERVICE_COMPONENT);
        seedGameServiceMetaDataFromFile(GAME_SERVICE_PACKAGE_NAME,
                GAME_SERVICE_META_DATA_RES_ID,
                "res/xml/game_service_metadata_valid.xml");

        GameServiceConfiguration gameServiceConfiguration =
                mGameServiceProviderSelector.get(null, null);

        assertThat(gameServiceConfiguration).isNull();
    }

    @Test
    public void get_managedUser_returnsNull()
            throws Exception {
        seedSystemGameServicePackageName(GAME_SERVICE_PACKAGE_NAME);
        seedGameServiceResolveInfos(GAME_SERVICE_PACKAGE_NAME, USER_HANDLE_10,
                resolveInfo(GAME_SERVICE_SERVICE_INFO));
        seedServiceServiceInfo(GAME_SESSION_SERVICE_COMPONENT);
        seedGameServiceMetaDataFromFile(GAME_SERVICE_PACKAGE_NAME,
                GAME_SERVICE_META_DATA_RES_ID,
                "res/xml/game_service_metadata_valid.xml");

        GameServiceConfiguration gameServiceConfiguration =
                mGameServiceProviderSelector.get(managedTargetUser(USER_HANDLE_10), null);

        assertThat(gameServiceConfiguration).isNull();
    }

    @Test
    public void get_noSystemGameService_returnsNull()
            throws Exception {
        seedSystemGameServicePackageName("");
        seedGameServiceResolveInfos(GAME_SERVICE_PACKAGE_NAME, USER_HANDLE_10,
                resolveInfo(GAME_SERVICE_SERVICE_INFO));
        seedServiceServiceInfo(GAME_SESSION_SERVICE_COMPONENT);
        seedGameServiceMetaDataFromFile(GAME_SERVICE_PACKAGE_NAME,
                GAME_SERVICE_META_DATA_RES_ID,
                "res/xml/game_service_metadata_valid.xml");

        GameServiceConfiguration gameServiceConfiguration =
                mGameServiceProviderSelector.get(eligibleTargetUser(USER_HANDLE_10), null);

        assertThat(gameServiceConfiguration).isNull();
    }

    @Test
    public void get_noGameServiceProvidersAvailable_returnsGameServicePackageName()
            throws Exception {
        seedSystemGameServicePackageName(GAME_SERVICE_PACKAGE_NAME);
        seedGameServiceResolveInfos(GAME_SERVICE_PACKAGE_NAME, USER_HANDLE_10);
        seedServiceServiceInfo(GAME_SESSION_SERVICE_COMPONENT);
        seedGameServiceMetaDataFromFile(GAME_SERVICE_PACKAGE_NAME,
                GAME_SERVICE_META_DATA_RES_ID,
                "res/xml/game_service_metadata_valid.xml");

        GameServiceConfiguration gameServiceConfiguration =
                mGameServiceProviderSelector.get(eligibleTargetUser(USER_HANDLE_10), null);

        assertThat(gameServiceConfiguration).isEqualTo(
                new GameServiceConfiguration(GAME_SERVICE_PACKAGE_NAME, null));
    }

    @Test
    public void get_gameServiceProviderHasNoMetaData_returnsGameServicePackageName()
            throws Exception {
        seedSystemGameServicePackageName(GAME_SERVICE_PACKAGE_NAME);
        seedGameServiceResolveInfos(GAME_SERVICE_PACKAGE_NAME, USER_HANDLE_10,
                resolveInfo(GAME_SERVICE_SERVICE_INFO_WITHOUT_META_DATA));
        seedServiceServiceInfo(GAME_SESSION_SERVICE_COMPONENT);

        GameServiceConfiguration gameServiceConfiguration =
                mGameServiceProviderSelector.get(eligibleTargetUser(USER_HANDLE_10), null);

        assertThat(gameServiceConfiguration).isEqualTo(
                new GameServiceConfiguration(GAME_SERVICE_PACKAGE_NAME, null));
    }

    @Test
    public void get_gameSessionServiceDoesNotExist_returnsGameServicePackageName()
            throws Exception {
        seedSystemGameServicePackageName(GAME_SERVICE_PACKAGE_NAME);
        seedGameServiceResolveInfos(GAME_SERVICE_PACKAGE_NAME, USER_HANDLE_10,
                resolveInfo(GAME_SERVICE_SERVICE_INFO));
        seedServiceServiceInfoNotFound(GAME_SESSION_SERVICE_COMPONENT);
        seedGameServiceMetaDataFromFile(GAME_SERVICE_PACKAGE_NAME,
                GAME_SERVICE_META_DATA_RES_ID,
                "res/xml/game_service_metadata_valid.xml");

        GameServiceConfiguration gameServiceConfiguration =
                mGameServiceProviderSelector.get(eligibleTargetUser(USER_HANDLE_10), null);

        assertThat(gameServiceConfiguration).isEqualTo(
                new GameServiceConfiguration(GAME_SERVICE_PACKAGE_NAME, null));
    }

    @Test
    public void get_metaDataWrongFirstTag_returnsGameServicePackageName() throws Exception {
        seedSystemGameServicePackageName(GAME_SERVICE_PACKAGE_NAME);
        seedGameServiceResolveInfos(GAME_SERVICE_PACKAGE_NAME, USER_HANDLE_10,
                resolveInfo(GAME_SERVICE_SERVICE_INFO));
        seedServiceServiceInfo(GAME_SESSION_SERVICE_COMPONENT);
        seedGameServiceMetaDataFromFile(GAME_SERVICE_PACKAGE_NAME,
                GAME_SERVICE_META_DATA_RES_ID,
                "res/xml/game_service_metadata_wrong_first_tag.xml");

        GameServiceConfiguration gameServiceConfiguration =
                mGameServiceProviderSelector.get(eligibleTargetUser(USER_HANDLE_10), null);

        assertThat(gameServiceConfiguration).isEqualTo(
                new GameServiceConfiguration(GAME_SERVICE_PACKAGE_NAME, null));
    }

    @Test
    public void get_validGameServiceProviderAvailable_returnsGameServiceProvider()
            throws Exception {
        seedSystemGameServicePackageName(GAME_SERVICE_PACKAGE_NAME);
        seedGameServiceResolveInfos(GAME_SERVICE_PACKAGE_NAME, USER_HANDLE_10,
                resolveInfo(GAME_SERVICE_SERVICE_INFO));
        seedServiceServiceInfo(GAME_SESSION_SERVICE_COMPONENT);
        seedGameServiceMetaDataFromFile(GAME_SERVICE_PACKAGE_NAME,
                GAME_SERVICE_META_DATA_RES_ID,
                "res/xml/game_service_metadata_valid.xml");

        GameServiceConfiguration gameServiceConfiguration =
                mGameServiceProviderSelector.get(eligibleTargetUser(USER_HANDLE_10), null);

        GameServiceConfiguration expectedGameServiceConfiguration =
                new GameServiceConfiguration(
                        GAME_SERVICE_PACKAGE_NAME,
                        new GameServiceComponentConfiguration(USER_HANDLE_10,
                                GAME_SERVICE_COMPONENT,
                                GAME_SESSION_SERVICE_COMPONENT));
        assertThat(gameServiceConfiguration).isEqualTo(
                expectedGameServiceConfiguration);
    }

    @Test
    public void get_multipleGameServiceProvidersAllValid_returnsFirstValidGameServiceProvider()
            throws Exception {
        seedSystemGameServicePackageName(GAME_SERVICE_PACKAGE_NAME);

        seedGameServiceResolveInfos(GAME_SERVICE_PACKAGE_NAME, USER_HANDLE_10,
                resolveInfo(GAME_SERVICE_B_SERVICE_INFO), resolveInfo(GAME_SERVICE_SERVICE_INFO));
        seedServiceServiceInfo(GAME_SESSION_SERVICE_COMPONENT);
        seedGameServiceMetaDataFromFile(GAME_SERVICE_PACKAGE_NAME,
                GAME_SERVICE_B_META_DATA_RES_ID,
                "res/xml/game_service_metadata_valid.xml");
        seedGameServiceMetaDataFromFile(GAME_SERVICE_PACKAGE_NAME,
                GAME_SERVICE_META_DATA_RES_ID,
                "res/xml/game_service_metadata_valid.xml");

        GameServiceConfiguration gameServiceConfiguration =
                mGameServiceProviderSelector.get(eligibleTargetUser(USER_HANDLE_10), null);

        GameServiceConfiguration expectedGameServiceConfiguration =
                new GameServiceConfiguration(
                        GAME_SERVICE_PACKAGE_NAME,
                        new GameServiceComponentConfiguration(USER_HANDLE_10,
                                GAME_SERVICE_B_COMPONENT,
                                GAME_SESSION_SERVICE_COMPONENT));
        assertThat(gameServiceConfiguration).isEqualTo(
                expectedGameServiceConfiguration);
    }

    @Test
    public void get_multipleGameServiceProvidersSomeInvalid_returnsFirstValidGameServiceProvider()
            throws Exception {
        seedSystemGameServicePackageName(GAME_SERVICE_PACKAGE_NAME);

        seedGameServiceResolveInfos(GAME_SERVICE_PACKAGE_NAME, USER_HANDLE_10,
                resolveInfo(GAME_SERVICE_B_SERVICE_INFO), resolveInfo(GAME_SERVICE_SERVICE_INFO));
        seedServiceServiceInfo(GAME_SESSION_SERVICE_COMPONENT);
        seedGameServiceMetaDataFromFile(GAME_SERVICE_PACKAGE_NAME,
                GAME_SERVICE_META_DATA_RES_ID,
                "res/xml/game_service_metadata_valid.xml");

        GameServiceConfiguration gameServiceConfiguration =
                mGameServiceProviderSelector.get(eligibleTargetUser(USER_HANDLE_10), null);

        GameServiceConfiguration expectedGameServiceConfiguration =
                new GameServiceConfiguration(
                        GAME_SERVICE_PACKAGE_NAME,
                        new GameServiceComponentConfiguration(USER_HANDLE_10,
                                GAME_SERVICE_COMPONENT,
                                GAME_SESSION_SERVICE_COMPONENT));
        assertThat(gameServiceConfiguration).isEqualTo(
                expectedGameServiceConfiguration);
    }

    @Test
    public void get_overridePresent_returnsDeviceConfigGameServiceProvider()
            throws Exception {
        seedSystemGameServicePackageName("other.package");

        seedGameServiceResolveInfos(GAME_SERVICE_PACKAGE_NAME, USER_HANDLE_10,
                resolveInfo(GAME_SERVICE_B_SERVICE_INFO), resolveInfo(GAME_SERVICE_SERVICE_INFO));
        seedServiceServiceInfo(GAME_SESSION_SERVICE_COMPONENT);
        seedGameServiceMetaDataFromFile(GAME_SERVICE_PACKAGE_NAME,
                GAME_SERVICE_META_DATA_RES_ID,
                "res/xml/game_service_metadata_valid.xml");

        GameServiceConfiguration gameServiceConfiguration =
                mGameServiceProviderSelector.get(eligibleTargetUser(USER_HANDLE_10),
                        GAME_SERVICE_PACKAGE_NAME);

        GameServiceConfiguration expectedGameServiceConfiguration =
                new GameServiceConfiguration(
                        GAME_SERVICE_PACKAGE_NAME,
                        new GameServiceComponentConfiguration(
                                USER_HANDLE_10,
                                GAME_SERVICE_COMPONENT,
                                GAME_SESSION_SERVICE_COMPONENT));
        assertThat(gameServiceConfiguration).isEqualTo(
                expectedGameServiceConfiguration);
    }

    private void seedSystemGameServicePackageName(String gameServicePackageName) {
        when(mSpyResources.getString(com.android.internal.R.string.config_systemGameService))
                .thenReturn(gameServicePackageName);
    }

    private void seedGameServiceResolveInfos(
            String gameServicePackageName,
            UserHandle userHandle,
            ResolveInfo... resolveInfos) {
        doReturn(ImmutableList.copyOf(resolveInfos))
                .when(mMockPackageManager).queryIntentServicesAsUser(
                        argThat(intent ->
                                intent != null
                                        && intent.getAction().equals(
                                        GameService.ACTION_GAME_SERVICE)
                                        && intent.getPackage().equals(gameServicePackageName)
                        ),
                        anyInt(),
                        eq(userHandle.getIdentifier()));
    }

    private void seedServiceServiceInfo(ComponentName componentName) throws Exception {
        when(mMockPackageManager.getServiceInfo(eq(componentName), anyInt()))
                .thenReturn(
                        serviceInfo(componentName.getPackageName(), componentName.getClassName()));
    }

    private void seedServiceServiceInfoNotFound(ComponentName componentName) throws Exception {
        when(mMockPackageManager.getServiceInfo(eq(componentName), anyInt()))
                .thenThrow(new PackageManager.NameNotFoundException());
    }

    private void seedGameServiceMetaDataFromFile(String packageName, int resId, String fileName)
            throws Exception {

        AssetManager assetManager =
                InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        XmlResourceParser xmlResourceParser =
                assetManager.openXmlResourceParser(fileName);

        when(mMockPackageManager.getXml(eq(packageName), eq(resId), any()))
                .thenReturn(xmlResourceParser);
    }

    private static UserInfo eligibleUserInfo(int uid) {
        return new UserInfo(uid, "", "", UserInfo.FLAG_FULL);
    }

    private static UserInfo managedUserInfo(int uid) {
        UserInfo userInfo = eligibleUserInfo(uid);
        userInfo.userType = UserManager.USER_TYPE_PROFILE_MANAGED;
        return userInfo;
    }

    private static ResolveInfo resolveInfo(ServiceInfo serviceInfo) {
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = serviceInfo;
        return resolveInfo;
    }

    private static ServiceInfo serviceInfo(String packageName, String name) {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = packageName;
        applicationInfo.enabled = true;

        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.applicationInfo = applicationInfo;
        serviceInfo.packageName = packageName;
        serviceInfo.name = name;
        serviceInfo.enabled = true;

        return serviceInfo;
    }

    private static ServiceInfo addGameServiceMetaData(ServiceInfo serviceInfo, int resId) {
        if (serviceInfo.metaData == null) {
            serviceInfo.metaData = new Bundle();
        }
        serviceInfo.metaData.putInt(GameService.SERVICE_META_DATA, resId);

        return serviceInfo;
    }

    private static SystemService.TargetUser managedTargetUser(UserHandle userHandle) {
        return new SystemService.TargetUser(managedUserInfo(userHandle.getIdentifier()));
    }

    private static SystemService.TargetUser eligibleTargetUser(UserHandle userHandle) {
        return new SystemService.TargetUser(eligibleUserInfo(userHandle.getIdentifier()));
    }
}
