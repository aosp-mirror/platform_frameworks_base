/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.pm;


import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.UserInfo;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;

import androidx.annotation.NonNull;

import com.android.server.om.OverlayReferenceMapper;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.pkg.component.ParsedActivity;
import com.android.server.pm.pkg.component.ParsedActivityImpl;
import com.android.server.pm.pkg.component.ParsedInstrumentationImpl;
import com.android.server.pm.pkg.component.ParsedIntentInfoImpl;
import com.android.server.pm.pkg.component.ParsedProviderImpl;
import com.android.server.pm.pkg.parsing.ParsingPackage;
import com.android.server.utils.WatchableTester;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

@Presubmit
@RunWith(JUnit4.class)
public class AppsFilterImplTest {

    private static final int DUMMY_CALLING_APPID = 10345;
    private static final int DUMMY_TARGET_APPID = 10556;
    private static final int DUMMY_ACTOR_APPID = 10656;
    private static final int DUMMY_OVERLAY_APPID = 10756;
    private static final int SYSTEM_USER = 0;
    private static final int SECONDARY_USER = 10;
    private static final int ADDED_USER = 11;
    private static final int[] USER_ARRAY = {SYSTEM_USER, SECONDARY_USER};
    private static final int[] USER_ARRAY_WITH_ADDED = {SYSTEM_USER, SECONDARY_USER, ADDED_USER};
    private static final UserInfo[] USER_INFO_LIST = toUserInfos(USER_ARRAY);
    private static final UserInfo[] USER_INFO_LIST_WITH_ADDED = toUserInfos(USER_ARRAY_WITH_ADDED);

    private static UserInfo[] toUserInfos(int[] userIds) {
        return Arrays.stream(userIds)
                .mapToObj(id -> new UserInfo(id, Integer.toString(id), 0))
                .toArray(UserInfo[]::new);
    }

    @Mock
    AppsFilterImpl.FeatureConfig mFeatureConfigMock;
    @Mock
    AppsFilterImpl.StateProvider mStateProvider;
    @Mock
    Executor mMockExecutor;

    private ArrayMap<String, PackageSetting> mExisting = new ArrayMap<>();
    private Collection<SharedUserSetting> mSharedUserSettings = new ArraySet<>();

    private static ParsingPackage pkg(String packageName) {
        return PackageImpl.forTesting(packageName)
                .setTargetSdkVersion(Build.VERSION_CODES.R);
    }

    private static ParsingPackage pkg(String packageName, Intent... queries) {
        ParsingPackage pkg = pkg(packageName);
        if (queries != null) {
            for (Intent intent : queries) {
                pkg.addQueriesIntent(intent);
            }
        }
        return pkg;
    }

    private static ParsingPackage pkgQueriesProvider(String packageName,
            String... queriesAuthorities) {
        ParsingPackage pkg = pkg(packageName);
        if (queriesAuthorities != null) {
            for (String authority : queriesAuthorities) {
                pkg.addQueriesProvider(authority);
            }
        }
        return pkg;
    }

    private static ParsingPackage pkg(String packageName, String... queriesPackages) {
        ParsingPackage pkg = pkg(packageName);
        if (queriesPackages != null) {
            for (String queryPackageName : queriesPackages) {
                pkg.addQueriesPackage(queryPackageName);
            }
        }
        return pkg;
    }

    private static ParsingPackage pkg(String packageName, IntentFilter... filters) {
        ParsedActivity activity = createActivity(packageName, filters);
        return pkg(packageName).addActivity(activity);
    }

    private static ParsingPackage pkgWithReceiver(String packageName, IntentFilter... filters) {
        ParsedActivity receiver = createActivity(packageName, filters);
        return pkg(packageName).addReceiver(receiver);
    }

    private static ParsingPackage pkgWithSharedLibrary(String packageName, String libName) {
        return pkg(packageName).addLibraryName(libName);
    }

    private static ParsedActivity createActivity(String packageName, IntentFilter[] filters) {
        ParsedActivityImpl activity = new ParsedActivityImpl();
        activity.setPackageName(packageName);
        for (IntentFilter filter : filters) {
            final ParsedIntentInfoImpl info = new ParsedIntentInfoImpl();
            final IntentFilter intentInfoFilter = info.getIntentFilter();
            if (filter.countActions() > 0) {
                filter.actionsIterator().forEachRemaining(intentInfoFilter::addAction);
            }
            if (filter.countCategories() > 0) {
                filter.actionsIterator().forEachRemaining(intentInfoFilter::addAction);
            }
            if (filter.countDataAuthorities() > 0) {
                filter.authoritiesIterator().forEachRemaining(intentInfoFilter::addDataAuthority);
            }
            if (filter.countDataSchemes() > 0) {
                filter.schemesIterator().forEachRemaining(intentInfoFilter::addDataScheme);
            }
            activity.addIntent(info);
            activity.setExported(true);
        }
        return activity;
    }

    private static ParsingPackage pkgWithInstrumentation(
            String packageName, String instrumentationTargetPackage) {
        ParsedInstrumentationImpl instrumentation = new ParsedInstrumentationImpl();
        instrumentation.setTargetPackage(instrumentationTargetPackage);
        return pkg(packageName).addInstrumentation(instrumentation);
    }

    private static ParsingPackage pkgWithProvider(String packageName, String authority) {
        ParsedProviderImpl provider = new ParsedProviderImpl();
        provider.setPackageName(packageName);
        provider.setExported(true);
        provider.setAuthority(authority);
        return pkg(packageName)
                .addProvider(provider);
    }

    @Before
    public void setup() throws Exception {
        mExisting = new ArrayMap<>();

        MockitoAnnotations.initMocks(this);
        doAnswer(invocation -> {
            ((AppsFilterImpl.StateProvider.CurrentStateCallback) invocation.getArgument(0))
                    .currentState(mExisting, mSharedUserSettings, USER_INFO_LIST);
            return new Object();
        }).when(mStateProvider)
                .runWithState(any(AppsFilterImpl.StateProvider.CurrentStateCallback.class));

        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return new Object();
        }).when(mMockExecutor).execute(any(Runnable.class));

        when(mFeatureConfigMock.isGloballyEnabled()).thenReturn(true);
        when(mFeatureConfigMock.packageIsEnabled(any(AndroidPackage.class))).thenAnswer(
                (Answer<Boolean>) invocation ->
                        ((AndroidPackage) invocation.getArgument(SYSTEM_USER)).getTargetSdkVersion()
                                >= Build.VERSION_CODES.R);
    }

    @Test
    public void testSystemReadyPropogates() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        final WatchableTester watcher = new WatchableTester(appsFilter, "onChange");
        watcher.register();
        appsFilter.onSystemReady();
        watcher.verifyChangeReported("systemReady");
        verify(mFeatureConfigMock).onSystemReady();
    }

    @Test
    public void testQueriesAction_FilterMatches() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        final WatchableTester watcher = new WatchableTester(appsFilter, "onChange");
        watcher.register();
        simulateAddBasicAndroid(appsFilter);
        watcher.verifyChangeReported("addBasicAndroid");
        appsFilter.onSystemReady();
        watcher.verifyChangeReported("systemReady");

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package", new IntentFilter("TEST_ACTION")), DUMMY_TARGET_APPID);
        watcher.verifyChangeReported("add package");
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package", new Intent("TEST_ACTION")), DUMMY_CALLING_APPID);
        watcher.verifyChangeReported("add package");

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
        watcher.verifyNoChangeReported("shouldFilterAplication");
    }
    @Test
    public void testQueriesProtectedAction_FilterDoesNotMatch() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        final WatchableTester watcher = new WatchableTester(appsFilter, "onChange");
        watcher.register();
        final Signature frameworkSignature = Mockito.mock(Signature.class);
        final SigningDetails frameworkSigningDetails =
                new SigningDetails(new Signature[]{frameworkSignature}, 1);
        final ParsingPackage android = pkg("android");
        watcher.verifyNoChangeReported("prepare");
        android.addProtectedBroadcast("TEST_ACTION");
        simulateAddPackage(appsFilter, android, 1000,
                b -> b.setSigningDetails(frameworkSigningDetails));
        watcher.verifyChangeReported("addPackage");
        appsFilter.onSystemReady();
        watcher.verifyChangeReported("systemReady");

        final int activityUid = DUMMY_TARGET_APPID;
        PackageSetting targetActivity = simulateAddPackage(appsFilter,
                pkg("com.target.activity", new IntentFilter("TEST_ACTION")), activityUid);
        watcher.verifyChangeReported("addPackage");
        final int receiverUid = DUMMY_TARGET_APPID + 1;
        PackageSetting targetReceiver = simulateAddPackage(appsFilter,
                pkgWithReceiver("com.target.receiver", new IntentFilter("TEST_ACTION")),
                receiverUid);
        watcher.verifyChangeReported("addPackage");
        final int callingUid = DUMMY_CALLING_APPID;
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.calling.action", new Intent("TEST_ACTION")), callingUid);
        watcher.verifyChangeReported("addPackage");
        final int wildcardUid = DUMMY_CALLING_APPID + 1;
        PackageSetting callingWildCard = simulateAddPackage(appsFilter,
                pkg("com.calling.wildcard", new Intent("*")), wildcardUid);
        watcher.verifyChangeReported("addPackage");

        assertFalse(appsFilter.shouldFilterApplication(callingUid, calling, targetActivity,
                SYSTEM_USER));
        assertTrue(appsFilter.shouldFilterApplication(callingUid, calling, targetReceiver,
                SYSTEM_USER));

        assertFalse(appsFilter.shouldFilterApplication(
                wildcardUid, callingWildCard, targetActivity, SYSTEM_USER));
        assertTrue(appsFilter.shouldFilterApplication(
                wildcardUid, callingWildCard, targetReceiver, SYSTEM_USER));
        watcher.verifyNoChangeReported("shouldFilterApplication");
    }

    @Test
    public void testQueriesProvider_FilterMatches() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        final WatchableTester watcher = new WatchableTester(appsFilter, "onChange");
        watcher.register();
        simulateAddBasicAndroid(appsFilter);
        watcher.verifyChangeReported("addPackage");
        appsFilter.onSystemReady();
        watcher.verifyChangeReported("systemReady");

        PackageSetting target = simulateAddPackage(appsFilter,
                pkgWithProvider("com.some.package", "com.some.authority"), DUMMY_TARGET_APPID);
        watcher.verifyChangeReported("addPackage");
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkgQueriesProvider("com.some.other.package", "com.some.authority"),
                DUMMY_CALLING_APPID);
        watcher.verifyChangeReported("addPackage");

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
        watcher.verifyNoChangeReported("shouldFilterApplication");
    }

    @Test
    public void testOnUserUpdated_FilterMatches() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);

        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkgWithProvider("com.some.package", "com.some.authority"), DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkgQueriesProvider("com.some.other.package", "com.some.authority"),
                DUMMY_CALLING_APPID);

        for (int subjectUserId : USER_ARRAY) {
            for (int otherUserId : USER_ARRAY) {
                assertFalse(appsFilter.shouldFilterApplication(
                        UserHandle.getUid(DUMMY_CALLING_APPID, subjectUserId), calling, target,
                        otherUserId));
            }
        }

        // adds new user
        doAnswer(invocation -> {
            ((AppsFilterImpl.StateProvider.CurrentStateCallback) invocation.getArgument(0))
                    .currentState(mExisting, mSharedUserSettings, USER_INFO_LIST_WITH_ADDED);
            return new Object();
        }).when(mStateProvider)
                .runWithState(any(AppsFilterImpl.StateProvider.CurrentStateCallback.class));
        appsFilter.onUserCreated(ADDED_USER);

        for (int subjectUserId : USER_ARRAY_WITH_ADDED) {
            for (int otherUserId : USER_ARRAY_WITH_ADDED) {
                assertFalse(appsFilter.shouldFilterApplication(
                        UserHandle.getUid(DUMMY_CALLING_APPID, subjectUserId), calling, target,
                        otherUserId));
            }
        }

        // delete user
        doAnswer(invocation -> {
            ((AppsFilterImpl.StateProvider.CurrentStateCallback) invocation.getArgument(0))
                    .currentState(mExisting, mSharedUserSettings, USER_INFO_LIST);
            return new Object();
        }).when(mStateProvider)
                .runWithState(any(AppsFilterImpl.StateProvider.CurrentStateCallback.class));
        appsFilter.onUserDeleted(ADDED_USER);

        for (int subjectUserId : USER_ARRAY) {
            for (int otherUserId : USER_ARRAY) {
                assertFalse(appsFilter.shouldFilterApplication(
                        UserHandle.getUid(DUMMY_CALLING_APPID, subjectUserId), calling, target,
                        otherUserId));
            }
        }
    }

    @Test
    public void testQueriesDifferentProvider_Filters() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        final WatchableTester watcher = new WatchableTester(appsFilter, "onChange");
        watcher.register();
        simulateAddBasicAndroid(appsFilter);
        watcher.verifyChangeReported("addPackage");
        appsFilter.onSystemReady();
        watcher.verifyChangeReported("systemReady");

        PackageSetting target = simulateAddPackage(appsFilter,
                pkgWithProvider("com.some.package", "com.some.authority"), DUMMY_TARGET_APPID);
        watcher.verifyChangeReported("addPackage");
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkgQueriesProvider("com.some.other.package", "com.some.other.authority"),
                DUMMY_CALLING_APPID);
        watcher.verifyChangeReported("addPackage");

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
        watcher.verifyNoChangeReported("shouldFilterApplication");
    }

    @Test
    public void testQueriesProviderWithSemiColon_FilterMatches() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkgWithProvider("com.some.package", "com.some.authority;com.some.other.authority"),
                DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkgQueriesProvider("com.some.other.package", "com.some.authority"),
                DUMMY_CALLING_APPID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testQueriesAction_NoMatchingAction_Filters() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package", new Intent("TEST_ACTION")), DUMMY_CALLING_APPID);

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testQueriesAction_NoMatchingActionFilterLowSdk_DoesntFilter() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_APPID);
        ParsingPackage callingPkg = pkg("com.some.other.package",
                new Intent("TEST_ACTION"))
                .setTargetSdkVersion(Build.VERSION_CODES.P);
        PackageSetting calling = simulateAddPackage(appsFilter, callingPkg,
                DUMMY_CALLING_APPID);


        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testNoQueries_Filters() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package"), DUMMY_CALLING_APPID);

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testNoUsesLibrary_Filters() throws Exception {
        final AppsFilterImpl appsFilter = new AppsFilterImpl(mStateProvider, mFeatureConfigMock,
                new String[]{}, /* systemAppsQueryable */ false, /* overlayProvider */ null,
                mMockExecutor);

        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        final Signature mockSignature = Mockito.mock(Signature.class);
        final SigningDetails mockSigningDetails = new SigningDetails(
                new Signature[]{mockSignature},
                SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V2);

        final PackageSetting target = simulateAddPackage(appsFilter,
                pkgWithSharedLibrary("com.some.package", "com.some.shared_library"),
                DUMMY_TARGET_APPID,
                setting -> setting.setSigningDetails(mockSigningDetails)
                        .setPkgFlags(ApplicationInfo.FLAG_SYSTEM));
        final PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package"), DUMMY_CALLING_APPID);

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testUsesLibrary_DoesntFilter() throws Exception {
        final AppsFilterImpl appsFilter = new AppsFilterImpl(mStateProvider, mFeatureConfigMock,
                new String[]{}, /* systemAppsQueryable */ false, /* overlayProvider */ null,
                mMockExecutor);

        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        final Signature mockSignature = Mockito.mock(Signature.class);
        final SigningDetails mockSigningDetails = new SigningDetails(
                new Signature[]{mockSignature},
                SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V2);

        final PackageSetting target = simulateAddPackage(appsFilter,
                pkgWithSharedLibrary("com.some.package", "com.some.shared_library"),
                DUMMY_TARGET_APPID,
                setting -> setting.setSigningDetails(mockSigningDetails)
                        .setPkgFlags(ApplicationInfo.FLAG_SYSTEM));
        final PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package").addUsesLibrary("com.some.shared_library"),
                DUMMY_CALLING_APPID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testUsesOptionalLibrary_DoesntFilter() throws Exception {
        final AppsFilterImpl appsFilter = new AppsFilterImpl(mStateProvider, mFeatureConfigMock,
                new String[]{}, /* systemAppsQueryable */ false, /* overlayProvider */ null,
                mMockExecutor);

        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        final Signature mockSignature = Mockito.mock(Signature.class);
        final SigningDetails mockSigningDetails = new SigningDetails(
                new Signature[]{mockSignature},
                SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V2);

        final PackageSetting target = simulateAddPackage(appsFilter,
                pkgWithSharedLibrary("com.some.package", "com.some.shared_library"),
                DUMMY_TARGET_APPID,
                setting -> setting.setSigningDetails(mockSigningDetails)
                        .setPkgFlags(ApplicationInfo.FLAG_SYSTEM));
        final PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package").addUsesOptionalLibrary("com.some.shared_library"),
                DUMMY_CALLING_APPID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testUsesLibrary_ShareUid_DoesntFilter() throws Exception {
        final AppsFilterImpl appsFilter = new AppsFilterImpl(mStateProvider, mFeatureConfigMock,
                new String[]{}, /* systemAppsQueryable */ false, /* overlayProvider */ null,
                mMockExecutor);

        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        final Signature mockSignature = Mockito.mock(Signature.class);
        final SigningDetails mockSigningDetails = new SigningDetails(
                new Signature[]{mockSignature},
                SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V2);

        final PackageSetting target = simulateAddPackage(appsFilter,
                pkgWithSharedLibrary("com.some.package", "com.some.shared_library"),
                DUMMY_TARGET_APPID,
                setting -> setting.setSigningDetails(mockSigningDetails)
                        .setPkgFlags(ApplicationInfo.FLAG_SYSTEM));
        final PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package_a").setSharedUserId("com.some.uid"),
                DUMMY_CALLING_APPID);
        simulateAddPackage(appsFilter, pkg("com.some.other.package_b")
                .setSharedUserId("com.some.uid").addUsesLibrary("com.some.shared_library"),
                DUMMY_CALLING_APPID);

        // Although package_a doesn't use library, it should be granted visibility. It's because
        // package_a shares userId with package_b, and package_b uses that shared library.
        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testForceQueryable_SystemDoesntFilter() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package").setForceQueryable(true), DUMMY_TARGET_APPID,
                setting -> setting.setPkgFlags(ApplicationInfo.FLAG_SYSTEM));
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package"), DUMMY_CALLING_APPID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }


    @Test
    public void testForceQueryable_NonSystemFilters() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package").setForceQueryable(true), DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package"), DUMMY_CALLING_APPID);

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testForceQueryableByDevice_SystemCaller_DoesntFilter() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock,
                        new String[]{"com.some.package"}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_APPID,
                setting -> setting.setPkgFlags(ApplicationInfo.FLAG_SYSTEM));
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package"), DUMMY_CALLING_APPID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }


    @Test
    public void testSystemSignedTarget_DoesntFilter() throws CertificateException {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        appsFilter.onSystemReady();

        final Signature frameworkSignature = Mockito.mock(Signature.class);
        final SigningDetails frameworkSigningDetails =
                new SigningDetails(new Signature[]{frameworkSignature}, 1);

        final Signature otherSignature = Mockito.mock(Signature.class);
        final SigningDetails otherSigningDetails =
                new SigningDetails(new Signature[]{otherSignature}, 1);

        simulateAddPackage(appsFilter, pkg("android"), 1000,
                b -> b.setSigningDetails(frameworkSigningDetails));
        PackageSetting target = simulateAddPackage(appsFilter, pkg("com.some.package"),
                DUMMY_TARGET_APPID,
                b -> b.setSigningDetails(frameworkSigningDetails)
                        .setPkgFlags(ApplicationInfo.FLAG_SYSTEM));
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package"), DUMMY_CALLING_APPID,
                b -> b.setSigningDetails(otherSigningDetails));

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testForceQueryableByDevice_NonSystemCaller_Filters() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock,
                        new String[]{"com.some.package"}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package"), DUMMY_CALLING_APPID);

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }


    @Test
    public void testSystemQueryable_DoesntFilter() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{},
                        true /* system force queryable */, null, mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_APPID,
                setting -> setting.setPkgFlags(ApplicationInfo.FLAG_SYSTEM));
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package"), DUMMY_CALLING_APPID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testQueriesPackage_DoesntFilter() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package", "com.some.package"), DUMMY_CALLING_APPID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testNoQueries_FeatureOff_DoesntFilter() throws Exception {
        when(mFeatureConfigMock.packageIsEnabled(any(AndroidPackage.class)))
                .thenReturn(false);
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(
                appsFilter, pkg("com.some.package"), DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(
                appsFilter, pkg("com.some.other.package"), DUMMY_CALLING_APPID);

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testSystemUid_DoesntFilter() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_APPID);

        assertFalse(appsFilter.shouldFilterApplication(SYSTEM_USER, null, target, SYSTEM_USER));
        assertFalse(appsFilter.shouldFilterApplication(Process.FIRST_APPLICATION_UID - 1,
                null, target, SYSTEM_USER));
    }

    @Test
    public void testSystemUidSecondaryUser_DoesntFilter() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_APPID);

        assertFalse(appsFilter.shouldFilterApplication(0, null, target, SECONDARY_USER));
        assertFalse(appsFilter.shouldFilterApplication(
                UserHandle.getUid(SECONDARY_USER, Process.FIRST_APPLICATION_UID - 1),
                null, target, SECONDARY_USER));
    }

    @Test
    public void testNonSystemUid_NoCallingSetting_Filters() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter,
                pkg("com.some.package"), DUMMY_TARGET_APPID);

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, null, target,
                SYSTEM_USER));
    }

    @Test
    public void testNoTargetPackage_filters() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = new PackageSettingBuilder()
                .setAppId(DUMMY_TARGET_APPID)
                .setName("com.some.package")
                .setCodePath("/")
                .setPVersionCode(1L)
                .build();
        PackageSetting calling = simulateAddPackage(appsFilter,
                pkg("com.some.other.package", new Intent("TEST_ACTION")), DUMMY_CALLING_APPID);

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testActsOnTargetOfOverlay() throws Exception {
        final String actorName = "overlay://test/actorName";

        ParsingPackage target = pkg("com.some.package.target")
                .addOverlayable("overlayableName", actorName);
        ParsingPackage overlay = pkg("com.some.package.overlay")
                .setOverlay(true)
                .setOverlayTarget(target.getPackageName())
                .setOverlayTargetOverlayableName("overlayableName");
        ParsingPackage actor = pkg("com.some.package.actor");

        final AppsFilterImpl appsFilter = new AppsFilterImpl(
                mStateProvider,
                mFeatureConfigMock,
                new String[]{},
                false,
                new OverlayReferenceMapper.Provider() {
                    @Nullable
                    @Override
                    public String getActorPkg(String actorString) {
                        if (actorName.equals(actorString)) {
                            return actor.getPackageName();
                        }
                        return null;
                    }

                    @NonNull
                    @Override
                    public Map<String, Set<String>> getTargetToOverlayables(
                            @NonNull AndroidPackage pkg) {
                        if (overlay.getPackageName().equals(pkg.getPackageName())) {
                            Map<String, Set<String>> map = new ArrayMap<>();
                            Set<String> set = new ArraySet<>();
                            set.add(overlay.getOverlayTargetOverlayableName());
                            map.put(overlay.getOverlayTarget(), set);
                            return map;
                        }
                        return Collections.emptyMap();
                    }
                },
                mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        // Packages must be added in actor -> overlay -> target order so that the implicit
        // visibility of the actor into the overlay can be tested

        PackageSetting actorSetting = simulateAddPackage(appsFilter, actor, DUMMY_ACTOR_APPID);
        PackageSetting overlaySetting =
                simulateAddPackage(appsFilter, overlay, DUMMY_OVERLAY_APPID);

        // Actor can not see overlay (yet)
        assertTrue(appsFilter.shouldFilterApplication(DUMMY_ACTOR_APPID, actorSetting,
                overlaySetting, SYSTEM_USER));

        PackageSetting targetSetting = simulateAddPackage(appsFilter, target, DUMMY_TARGET_APPID);

        // Actor can see both target and overlay
        assertFalse(appsFilter.shouldFilterApplication(DUMMY_ACTOR_APPID, actorSetting,
                targetSetting, SYSTEM_USER));
        assertFalse(appsFilter.shouldFilterApplication(DUMMY_ACTOR_APPID, actorSetting,
                overlaySetting, SYSTEM_USER));

        // But target/overlay can't see each other
        assertTrue(appsFilter.shouldFilterApplication(DUMMY_TARGET_APPID, targetSetting,
                overlaySetting, SYSTEM_USER));
        assertTrue(appsFilter.shouldFilterApplication(DUMMY_OVERLAY_APPID, overlaySetting,
                targetSetting, SYSTEM_USER));

        // And can't see the actor
        assertTrue(appsFilter.shouldFilterApplication(DUMMY_TARGET_APPID, targetSetting,
                actorSetting, SYSTEM_USER));
        assertTrue(appsFilter.shouldFilterApplication(DUMMY_OVERLAY_APPID, overlaySetting,
                actorSetting, SYSTEM_USER));

        appsFilter.removePackage(targetSetting, false /* isReplace */);

        // Actor loses visibility to the overlay via removal of the target
        assertTrue(appsFilter.shouldFilterApplication(DUMMY_ACTOR_APPID, actorSetting,
                overlaySetting, SYSTEM_USER));
    }

    @Test
    public void testActsOnTargetOfOverlayThroughSharedUser() throws Exception {
//        Debug.waitForDebugger();

        final String actorName = "overlay://test/actorName";

        ParsingPackage target = pkg("com.some.package.target")
                .addOverlayable("overlayableName", actorName);
        ParsingPackage overlay = pkg("com.some.package.overlay")
                .setOverlay(true)
                .setOverlayTarget(target.getPackageName())
                .setOverlayTargetOverlayableName("overlayableName");
        ParsingPackage actorOne = pkg("com.some.package.actor.one");
        ParsingPackage actorTwo = pkg("com.some.package.actor.two");
        PackageSetting ps1 = getPackageSettingFromParsingPackage(actorOne, DUMMY_ACTOR_APPID,
                null /*settingBuilder*/);
        PackageSetting ps2 = getPackageSettingFromParsingPackage(actorTwo, DUMMY_ACTOR_APPID,
                null /*settingBuilder*/);

        final AppsFilterImpl appsFilter = new AppsFilterImpl(
                mStateProvider,
                mFeatureConfigMock,
                new String[]{},
                false,
                new OverlayReferenceMapper.Provider() {
                    @Nullable
                    @Override
                    public String getActorPkg(String actorString) {
                        // Only actorOne is mapped as a valid actor
                        if (actorName.equals(actorString)) {
                            return actorOne.getPackageName();
                        }
                        return null;
                    }

                    @NonNull
                    @Override
                    public Map<String, Set<String>> getTargetToOverlayables(
                            @NonNull AndroidPackage pkg) {
                        if (overlay.getPackageName().equals(pkg.getPackageName())) {
                            Map<String, Set<String>> map = new ArrayMap<>();
                            Set<String> set = new ArraySet<>();
                            set.add(overlay.getOverlayTargetOverlayableName());
                            map.put(overlay.getOverlayTarget(), set);
                            return map;
                        }
                        return Collections.emptyMap();
                    }
                },
                mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting targetSetting = simulateAddPackage(appsFilter, target, DUMMY_TARGET_APPID);
        SharedUserSetting actorSharedSetting = new SharedUserSetting("actorSharedUser",
                targetSetting.getFlags(), targetSetting.getPrivateFlags());
        actorSharedSetting.mAppId = 100; /* mimic a valid sharedUserSetting.mAppId */
        PackageSetting overlaySetting =
                simulateAddPackage(appsFilter, overlay, DUMMY_OVERLAY_APPID);
        simulateAddPackage(ps1, appsFilter, actorSharedSetting);
        simulateAddPackage(ps2, appsFilter, actorSharedSetting);

        // actorTwo can see both target and overlay
        assertFalse(appsFilter.shouldFilterApplication(DUMMY_ACTOR_APPID, actorSharedSetting,
                targetSetting, SYSTEM_USER));
        assertFalse(appsFilter.shouldFilterApplication(DUMMY_ACTOR_APPID, actorSharedSetting,
                overlaySetting, SYSTEM_USER));
    }

    @Test
    public void testInitiatingApp_DoesntFilter() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter, pkg("com.some.package"),
                DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter, pkg("com.some.other.package"),
                DUMMY_CALLING_APPID,
                withInstallSource(target.getPackageName(), null, null, null, false));

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testUninstalledInitiatingApp_Filters() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter, pkg("com.some.package"),
                DUMMY_TARGET_APPID);
        PackageSetting calling = simulateAddPackage(appsFilter, pkg("com.some.other.package"),
                DUMMY_CALLING_APPID,
                withInstallSource(target.getPackageName(), null, null, null, true));

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
    }

    @Test
    public void testOriginatingApp_Filters() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        final WatchableTester watcher = new WatchableTester(appsFilter, "onChange");
        watcher.register();
        simulateAddBasicAndroid(appsFilter);
        watcher.verifyChangeReported("addBasicAndroid");
        appsFilter.onSystemReady();
        watcher.verifyChangeReported("systemReady");

        PackageSetting target = simulateAddPackage(appsFilter, pkg("com.some.package"),
                DUMMY_TARGET_APPID);
        watcher.verifyChangeReported("add package");
        PackageSetting calling = simulateAddPackage(appsFilter, pkg("com.some.other.package"),
                DUMMY_CALLING_APPID, withInstallSource(null, target.getPackageName(), null, null,
                        false));
        watcher.verifyChangeReported("add package");

        assertTrue(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
        watcher.verifyNoChangeReported("shouldFilterAplication");
    }

    @Test
    public void testInstallingApp_DoesntFilter() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        final WatchableTester watcher = new WatchableTester(appsFilter, "onChange");
        watcher.register();
        simulateAddBasicAndroid(appsFilter);
        watcher.verifyChangeReported("addBasicAndroid");
        appsFilter.onSystemReady();
        watcher.verifyChangeReported("systemReady");

        PackageSetting target = simulateAddPackage(appsFilter, pkg("com.some.package"),
                DUMMY_TARGET_APPID);
        watcher.verifyChangeReported("add package");
        PackageSetting calling = simulateAddPackage(appsFilter, pkg("com.some.other.package"),
                DUMMY_CALLING_APPID, withInstallSource(null, null, target.getPackageName(), null,
                        false));
        watcher.verifyChangeReported("add package");

        assertFalse(appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, calling, target,
                SYSTEM_USER));
        watcher.verifyNoChangeReported("shouldFilterAplication");
    }

    @Test
    public void testInstrumentation_DoesntFilter() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        final WatchableTester watcher = new WatchableTester(appsFilter, "onChange");
        watcher.register();
        simulateAddBasicAndroid(appsFilter);
        watcher.verifyChangeReported("addBasicAndroid");
        appsFilter.onSystemReady();
        watcher.verifyChangeReported("systemReady");

        PackageSetting target = simulateAddPackage(appsFilter, pkg("com.some.package"),
                DUMMY_TARGET_APPID);
        watcher.verifyChangeReported("add package");
        PackageSetting instrumentation = simulateAddPackage(appsFilter,
                pkgWithInstrumentation("com.some.other.package", "com.some.package"),
                DUMMY_CALLING_APPID);
        watcher.verifyChangeReported("add package");

        assertFalse(
                appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, instrumentation, target,
                        SYSTEM_USER));
        assertFalse(
                appsFilter.shouldFilterApplication(DUMMY_TARGET_APPID, target, instrumentation,
                        SYSTEM_USER));
        watcher.verifyNoChangeReported("shouldFilterAplication");
    }

    @Test
    public void testWhoCanSee() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        final WatchableTester watcher = new WatchableTester(appsFilter, "onChange");
        watcher.register();
        simulateAddBasicAndroid(appsFilter);
        watcher.verifyChangeReported("addBasicAndroid");
        appsFilter.onSystemReady();
        watcher.verifyChangeReported("systemReady");

        final int systemAppId = Process.FIRST_APPLICATION_UID - 1;
        final int seesNothingAppId = Process.FIRST_APPLICATION_UID;
        final int hasProviderAppId = Process.FIRST_APPLICATION_UID + 1;
        final int queriesProviderAppId = Process.FIRST_APPLICATION_UID + 2;

        PackageSetting system = simulateAddPackage(appsFilter, pkg("some.system.pkg"), systemAppId);
        watcher.verifyChangeReported("add package");
        PackageSetting seesNothing = simulateAddPackage(appsFilter, pkg("com.some.package"),
                seesNothingAppId);
        watcher.verifyChangeReported("add package");
        PackageSetting hasProvider = simulateAddPackage(appsFilter,
                pkgWithProvider("com.some.other.package", "com.some.authority"), hasProviderAppId);
        watcher.verifyChangeReported("add package");
        PackageSetting queriesProvider = simulateAddPackage(appsFilter,
                pkgQueriesProvider("com.yet.some.other.package", "com.some.authority"),
                queriesProviderAppId);
        watcher.verifyChangeReported("add package");

        final SparseArray<int[]> systemFilter =
                appsFilter.getVisibilityAllowList(system, USER_ARRAY, mExisting);
        watcher.verifyNoChangeReported("getVisibility");
        assertThat(toList(systemFilter.get(SYSTEM_USER)),
                contains(seesNothingAppId, hasProviderAppId, queriesProviderAppId));
        watcher.verifyNoChangeReported("getVisibility");

        final SparseArray<int[]> seesNothingFilter =
                appsFilter.getVisibilityAllowList(seesNothing, USER_ARRAY, mExisting);
        watcher.verifyNoChangeReported("getVisibility");
        assertThat(toList(seesNothingFilter.get(SYSTEM_USER)),
                contains(seesNothingAppId));
        watcher.verifyNoChangeReported("getVisibility");
        assertThat(toList(seesNothingFilter.get(SECONDARY_USER)),
                contains(seesNothingAppId));
        watcher.verifyNoChangeReported("getVisibility");

        final SparseArray<int[]> hasProviderFilter =
                appsFilter.getVisibilityAllowList(hasProvider, USER_ARRAY, mExisting);
        assertThat(toList(hasProviderFilter.get(SYSTEM_USER)),
                contains(hasProviderAppId, queriesProviderAppId));

        SparseArray<int[]> queriesProviderFilter =
                appsFilter.getVisibilityAllowList(queriesProvider, USER_ARRAY, mExisting);
        watcher.verifyNoChangeReported("getVisibility");
        assertThat(toList(queriesProviderFilter.get(SYSTEM_USER)),
                contains(queriesProviderAppId));
        watcher.verifyNoChangeReported("getVisibility");

        // provider read
        appsFilter.grantImplicitAccess(hasProviderAppId, queriesProviderAppId,
                false /* retainOnUpdate */);
        watcher.verifyChangeReported("grantImplicitAccess");

        // ensure implicit access is included in the filter
        queriesProviderFilter =
                appsFilter.getVisibilityAllowList(queriesProvider, USER_ARRAY, mExisting);
        watcher.verifyNoChangeReported("getVisibility");
        assertThat(toList(queriesProviderFilter.get(SYSTEM_USER)),
                contains(hasProviderAppId, queriesProviderAppId));
        watcher.verifyNoChangeReported("getVisibility");
    }

    @Test
    public void testOnChangeReport() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        final WatchableTester watcher = new WatchableTester(appsFilter, "onChange");
        watcher.register();
        simulateAddBasicAndroid(appsFilter);
        watcher.verifyChangeReported("addBasic");
        appsFilter.onSystemReady();
        watcher.verifyChangeReported("systemReady");

        final int systemAppId = Process.FIRST_APPLICATION_UID - 1;
        final int seesNothingAppId = Process.FIRST_APPLICATION_UID;
        final int hasProviderAppId = Process.FIRST_APPLICATION_UID + 1;
        final int queriesProviderAppId = Process.FIRST_APPLICATION_UID + 2;

        PackageSetting system = simulateAddPackage(appsFilter, pkg("some.system.pkg"), systemAppId);
        watcher.verifyChangeReported("addPackage");
        PackageSetting seesNothing = simulateAddPackage(appsFilter, pkg("com.some.package"),
                seesNothingAppId);
        watcher.verifyChangeReported("addPackage");
        PackageSetting hasProvider = simulateAddPackage(appsFilter,
                pkgWithProvider("com.some.other.package", "com.some.authority"), hasProviderAppId);
        watcher.verifyChangeReported("addPackage");
        PackageSetting queriesProvider = simulateAddPackage(appsFilter,
                pkgQueriesProvider("com.yet.some.other.package", "com.some.authority"),
                queriesProviderAppId);
        watcher.verifyChangeReported("addPackage");

        final SparseArray<int[]> systemFilter =
                appsFilter.getVisibilityAllowList(system, USER_ARRAY, mExisting);
        assertThat(toList(systemFilter.get(SYSTEM_USER)),
                contains(seesNothingAppId, hasProviderAppId, queriesProviderAppId));
        watcher.verifyNoChangeReported("get");

        final SparseArray<int[]> seesNothingFilter =
                appsFilter.getVisibilityAllowList(seesNothing, USER_ARRAY, mExisting);
        assertThat(toList(seesNothingFilter.get(SYSTEM_USER)),
                contains(seesNothingAppId));
        assertThat(toList(seesNothingFilter.get(SECONDARY_USER)),
                contains(seesNothingAppId));
        watcher.verifyNoChangeReported("get");

        final SparseArray<int[]> hasProviderFilter =
                appsFilter.getVisibilityAllowList(hasProvider, USER_ARRAY, mExisting);
        assertThat(toList(hasProviderFilter.get(SYSTEM_USER)),
                contains(hasProviderAppId, queriesProviderAppId));
        watcher.verifyNoChangeReported("get");

        SparseArray<int[]> queriesProviderFilter =
                appsFilter.getVisibilityAllowList(queriesProvider, USER_ARRAY, mExisting);
        assertThat(toList(queriesProviderFilter.get(SYSTEM_USER)),
                contains(queriesProviderAppId));
        watcher.verifyNoChangeReported("get");

        // provider read
        appsFilter.grantImplicitAccess(
                hasProviderAppId, queriesProviderAppId, false /* retainOnUpdate */);
        watcher.verifyChangeReported("grantImplicitAccess");

        // ensure implicit access is included in the filter
        queriesProviderFilter =
                appsFilter.getVisibilityAllowList(queriesProvider, USER_ARRAY, mExisting);
        assertThat(toList(queriesProviderFilter.get(SYSTEM_USER)),
                contains(hasProviderAppId, queriesProviderAppId));
        watcher.verifyNoChangeReported("get");

        // remove a package
        appsFilter.removePackage(seesNothing, false /* isReplace */);
        watcher.verifyChangeReported("removePackage");
    }

    @Test
    public void testOnChangeReportedFilter() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();
        final WatchableTester watcher = new WatchableTester(appsFilter, "onChange filter");
        watcher.register();

        PackageSetting target = simulateAddPackage(appsFilter, pkg("com.some.package"),
                DUMMY_TARGET_APPID);
        PackageSetting instrumentation = simulateAddPackage(appsFilter,
                pkgWithInstrumentation("com.some.other.package", "com.some.package"),
                DUMMY_CALLING_APPID);
        watcher.verifyChangeReported("addPackage");

        assertFalse(
                appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, instrumentation, target,
                        SYSTEM_USER));
        assertFalse(
                appsFilter.shouldFilterApplication(DUMMY_TARGET_APPID, target, instrumentation,
                        SYSTEM_USER));
        watcher.verifyNoChangeReported("shouldFilterApplication");
    }

    @Test
    public void testAppsFilterRead() throws Exception {
        final AppsFilterImpl appsFilter =
                new AppsFilterImpl(mStateProvider, mFeatureConfigMock, new String[]{}, false, null,
                        mMockExecutor);
        simulateAddBasicAndroid(appsFilter);
        appsFilter.onSystemReady();

        PackageSetting target = simulateAddPackage(appsFilter, pkg("com.some.package"),
                DUMMY_TARGET_APPID);
        PackageSetting instrumentation = simulateAddPackage(appsFilter,
                pkgWithInstrumentation("com.some.other.package", "com.some.package"),
                DUMMY_CALLING_APPID);

        final int hasProviderAppId = Process.FIRST_APPLICATION_UID + 1;
        final int queriesProviderAppId = Process.FIRST_APPLICATION_UID + 2;
        PackageSetting queriesProvider = simulateAddPackage(appsFilter,
                pkgQueriesProvider("com.yet.some.other.package", "com.some.authority"),
                queriesProviderAppId);
        appsFilter.grantImplicitAccess(
                hasProviderAppId, queriesProviderAppId, false /* retainOnUpdate */);

        AppsFilterSnapshot snapshot = appsFilter.snapshot();
        assertFalse(
                snapshot.shouldFilterApplication(DUMMY_CALLING_APPID, instrumentation, target,
                        SYSTEM_USER));
        assertFalse(
                snapshot.shouldFilterApplication(DUMMY_TARGET_APPID, target, instrumentation,
                        SYSTEM_USER));

        SparseArray<int[]> queriesProviderFilter =
                snapshot.getVisibilityAllowList(queriesProvider, USER_ARRAY, mExisting);
        assertThat(toList(queriesProviderFilter.get(SYSTEM_USER)), contains(queriesProviderAppId));
        assertTrue(snapshot.canQueryPackage(instrumentation.getPkg(),
                target.getPackageName()));

        // New changes don't affect the snapshot
        appsFilter.removePackage(target, false);
        assertTrue(
                appsFilter.shouldFilterApplication(DUMMY_CALLING_APPID, instrumentation, target,
                        SYSTEM_USER));
        assertFalse(
                snapshot.shouldFilterApplication(DUMMY_CALLING_APPID, instrumentation, target,
                        SYSTEM_USER));

    }

    private List<Integer> toList(int[] array) {
        ArrayList<Integer> ret = new ArrayList<>(array.length);
        for (int i = 0; i < array.length; i++) {
            ret.add(i, array[i]);
        }
        return ret;
    }

    private interface WithSettingBuilder {
        PackageSettingBuilder withBuilder(PackageSettingBuilder builder);
    }

    private void simulateAddBasicAndroid(AppsFilterImpl appsFilter) throws Exception {
        final Signature frameworkSignature = Mockito.mock(Signature.class);
        final SigningDetails frameworkSigningDetails =
                new SigningDetails(new Signature[]{frameworkSignature}, 1);
        final ParsingPackage android = pkg("android");
        simulateAddPackage(appsFilter, android, 1000,
                b -> b.setSigningDetails(frameworkSigningDetails));
    }

    private PackageSetting simulateAddPackage(AppsFilterImpl filter,
            ParsingPackage newPkgBuilder, int appId) {
        return simulateAddPackage(filter, newPkgBuilder, appId, null /*settingBuilder*/);
    }

    private PackageSetting simulateAddPackage(AppsFilterImpl filter,
            ParsingPackage newPkgBuilder, int appId, @Nullable WithSettingBuilder action) {
        return simulateAddPackage(filter, newPkgBuilder, appId, action, null /*sharedUserSetting*/);
    }

    private PackageSetting simulateAddPackage(AppsFilterImpl filter,
                ParsingPackage newPkgBuilder, int appId, @Nullable WithSettingBuilder action,
            @Nullable SharedUserSetting sharedUserSetting) {
        final PackageSetting setting =
                getPackageSettingFromParsingPackage(newPkgBuilder, appId, action);
        simulateAddPackage(setting, filter, sharedUserSetting);
        return setting;
    }

    private PackageSetting getPackageSettingFromParsingPackage(ParsingPackage newPkgBuilder,
            int appId, @Nullable WithSettingBuilder action) {
        AndroidPackage newPkg = ((ParsedPackage) newPkgBuilder.hideAsParsed()).hideAsFinal();
        final PackageSettingBuilder settingBuilder = new PackageSettingBuilder()
                .setPackage(newPkg)
                .setAppId(appId)
                .setName(newPkg.getPackageName())
                .setCodePath("/")
                .setPVersionCode(1L);
        final PackageSetting setting =
                (action == null ? settingBuilder : action.withBuilder(settingBuilder)).build();
        return setting;
    }

    private void simulateAddPackage(PackageSetting setting, AppsFilterImpl filter,
            @Nullable SharedUserSetting sharedUserSetting) {
        mExisting.put(setting.getPackageName(), setting);
        if (sharedUserSetting != null) {
            sharedUserSetting.addPackage(setting);
            setting.setSharedUserAppId(sharedUserSetting.mAppId);
            mSharedUserSettings.add(sharedUserSetting);
        }
        filter.addPackage(setting);
    }

    private WithSettingBuilder withInstallSource(String initiatingPackageName,
            String originatingPackageName, String installerPackageName,
            String installerAttributionTag, boolean isInitiatingPackageUninstalled) {
        final InstallSource installSource = InstallSource.create(initiatingPackageName,
                originatingPackageName, installerPackageName, installerAttributionTag,
                /* isOrphaned= */ false, isInitiatingPackageUninstalled);
        return setting -> setting.setInstallSource(installSource);
    }
}
