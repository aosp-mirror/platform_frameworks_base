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

// TODO(b/169883602): This is purposely a different package from the path so that it can access
// AppSearchImpl's methods without having to make them public. This should be replaced by proper
// global query integration tests that can test AppSearchImpl-VisibilityStore integration logic.
package com.android.server.appsearch.external.localstorage;

import static android.Manifest.permission.READ_GLOBAL_APP_SEARCH_DATA;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.PackageIdentifier;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.util.ArrayMap;

import androidx.test.core.app.ApplicationProvider;

import com.android.compatibility.common.util.SystemUtil;
import com.android.server.appsearch.external.localstorage.util.PrefixUtil;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.Map;

/** This tests AppSearchImpl when it's running with a platform-backed VisibilityStore. */
public class AppSearchImplPlatformTest {
    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();
    private final Map<UserHandle, PackageManager> mMockPackageManagers = new ArrayMap<>();
    private Context mContext;
    private AppSearchImpl mAppSearchImpl;
    private int mGlobalQuerierUid;

    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        mContext = new ContextWrapper(context) {
            @Override
            public Context createContextAsUser(UserHandle user, int flags) {
                return new ContextWrapper(super.createContextAsUser(user, flags)) {
                    @Override
                    public PackageManager getPackageManager() {
                        return getMockPackageManager(user);
                    }
                };
            }

            @Override
            public PackageManager getPackageManager() {
                return createContextAsUser(getUser(), /*flags=*/ 0).getPackageManager();
            }
        };

        // Give ourselves global query permissions
        mAppSearchImpl =
                AppSearchImpl.create(
                        mTemporaryFolder.newFolder(),
                        mContext,
                        /*logger=*/ null);

        mGlobalQuerierUid =
                mContext.getPackageManager().getPackageUid(mContext.getPackageName(), /*flags=*/ 0);
    }

    @Test
    public void testSetSchema_existingSchemaRetainsVisibilitySetting() throws Exception {
        // Values for a "foo" client
        String packageNameFoo = "packageFoo";
        byte[] sha256CertFoo = new byte[] {10};
        int uidFoo = 1;

        // Make sure foo package will pass package manager checks.
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager.getPackageUid(eq(packageNameFoo), /*flags=*/ anyInt()))
                .thenReturn(uidFoo);
        when(mockPackageManager.hasSigningCertificate(
                packageNameFoo, sha256CertFoo, PackageManager.CERT_INPUT_SHA256))
                .thenReturn(true);

        // Make sure we have global query privileges and "foo" doesn't
        when(mockPackageManager.checkPermission(
                READ_GLOBAL_APP_SEARCH_DATA, mContext.getPackageName()))
                .thenReturn(PERMISSION_GRANTED);
        when(mockPackageManager.checkPermission(READ_GLOBAL_APP_SEARCH_DATA, packageNameFoo))
                .thenReturn(PERMISSION_DENIED);

        // Set schema1
        String prefix = PrefixUtil.createPrefix("package", "database");
        mAppSearchImpl.setSchema(
                "package",
                "database",
                Collections.singletonList(new AppSearchSchema.Builder("schema1").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.singletonList("schema1"),
                /*schemasPackageAccessible=*/ ImmutableMap.of(
                        "schema1",
                        ImmutableList.of(new PackageIdentifier(packageNameFoo, sha256CertFoo))),
                /*forceOverride=*/ false,
                /*schemaVersion=*/ 0);

        // "schema1" is platform hidden now and package visible to package1
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        "package",
                                        "database",
                                        prefix + "schema1",
                                        mContext.getPackageName(),
                                        mGlobalQuerierUid))
                .isFalse();

        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        "package",
                                        "database",
                                        prefix + "schema1",
                                        packageNameFoo,
                                        uidFoo))
                .isTrue();

        // Add a new schema, and include the already-existing "schema1"
        mAppSearchImpl.setSchema(
                "package",
                "database",
                ImmutableList.of(
                        new AppSearchSchema.Builder("schema1").build(),
                        new AppSearchSchema.Builder("schema2").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.singletonList("schema1"),
                /*schemasPackageAccessible=*/ ImmutableMap.of(
                        "schema1",
                        ImmutableList.of(new PackageIdentifier(packageNameFoo, sha256CertFoo))),
                /*forceOverride=*/ false,
                /*schemaVersion=*/ 0);

        // Check that "schema1" still has the same visibility settings
        SystemUtil.runWithShellPermissionIdentity(() -> assertThat(
                mAppSearchImpl
                        .getVisibilityStoreLocked()
                        .isSchemaSearchableByCaller(
                                "package",
                                "database",
                                prefix + "schema1",
                                mContext.getPackageName(),
                                mGlobalQuerierUid))
                        .isFalse(),
                READ_GLOBAL_APP_SEARCH_DATA);

        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        "package",
                                        "database",
                                        prefix + "schema1",
                                        packageNameFoo,
                                        uidFoo))
                .isTrue();

        // "schema2" has default visibility settings
        SystemUtil.runWithShellPermissionIdentity(() -> assertThat(
                mAppSearchImpl
                        .getVisibilityStoreLocked()
                        .isSchemaSearchableByCaller(
                                "package",
                                "database",
                                prefix + "schema2",
                                mContext.getPackageName(),
                                mGlobalQuerierUid))
                        .isTrue(),
                READ_GLOBAL_APP_SEARCH_DATA);

        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        "package",
                                        "database",
                                        prefix + "schema2",
                                        packageNameFoo,
                                        uidFoo))
                .isFalse();
    }

    @Test
    public void testRemoveSchema_removedFromVisibilityStore() throws Exception {
        // Values for a "foo" client
        String packageNameFoo = "packageFoo";
        byte[] sha256CertFoo = new byte[] {10};
        int uidFoo = 1;

        // Make sure foo package will pass package manager checks.
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager.getPackageUid(eq(packageNameFoo), /*flags=*/ anyInt()))
                .thenReturn(uidFoo);
        when(mockPackageManager.hasSigningCertificate(
                packageNameFoo, sha256CertFoo, PackageManager.CERT_INPUT_SHA256))
                .thenReturn(true);

        // Make sure we have global query privileges and "foo" doesn't
        when(mockPackageManager.checkPermission(
                READ_GLOBAL_APP_SEARCH_DATA, mContext.getPackageName()))
                .thenReturn(PERMISSION_GRANTED);
        when(mockPackageManager.checkPermission(READ_GLOBAL_APP_SEARCH_DATA, packageNameFoo))
                .thenReturn(PERMISSION_DENIED);

        String prefix = PrefixUtil.createPrefix("package", "database");
        mAppSearchImpl.setSchema(
                "package",
                "database",
                Collections.singletonList(new AppSearchSchema.Builder("schema1").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.singletonList("schema1"),
                /*schemasPackageAccessible=*/ ImmutableMap.of(
                        "schema1",
                        ImmutableList.of(new PackageIdentifier(packageNameFoo, sha256CertFoo))),
                /*forceOverride=*/ false,
                /*schemaVersion=*/ 0);

        // "schema1" is platform hidden now and package accessible
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        "package",
                                        "database",
                                        prefix + "schema1",
                                        mContext.getPackageName(),
                                        mGlobalQuerierUid))
                .isFalse();

        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        "package",
                                        "database",
                                        prefix + "schema1",
                                        packageNameFoo,
                                        uidFoo))
                .isTrue();

        // Remove "schema1" by force overriding
        mAppSearchImpl.setSchema(
                "package",
                "database",
                /*schemas=*/ Collections.emptyList(),
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ true,
                /*schemaVersion=*/ 0);

        // Check that "schema1" is no longer considered platform hidden or package accessible
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        "package",
                                        "database",
                                        prefix + "schema1",
                                        mContext.getPackageName(),
                                        mGlobalQuerierUid))
                .isTrue();

        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        "package",
                                        "database",
                                        prefix + "schema1",
                                        packageNameFoo,
                                        uidFoo))
                .isFalse();

        // Add "schema1" back, it gets default visibility settings which means it's not platform
        // hidden and not package accessible
        mAppSearchImpl.setSchema(
                "package",
                "database",
                Collections.singletonList(new AppSearchSchema.Builder("schema1").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*schemaVersion=*/ 0);

        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        "package",
                                        "database",
                                        prefix + "schema1",
                                        mContext.getPackageName(),
                                        mGlobalQuerierUid))
                .isTrue();
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        "package",
                                        "database",
                                        prefix + "schema1",
                                        packageNameFoo,
                                        uidFoo))
                .isFalse();
    }

    @Test
    public void testSetSchema_defaultPlatformVisible() throws Exception {
        // Make sure we have global query privileges
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager.checkPermission(
                READ_GLOBAL_APP_SEARCH_DATA, mContext.getPackageName()))
                .thenReturn(PERMISSION_GRANTED);

        String prefix = PrefixUtil.createPrefix("package", "database");
        mAppSearchImpl.setSchema(
                "package",
                "database",
                Collections.singletonList(new AppSearchSchema.Builder("Schema").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*schemaVersion=*/ 0);

        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        "package",
                                        "database",
                                        prefix + "Schema",
                                        mContext.getPackageName(),
                                        mGlobalQuerierUid))
                .isTrue();
    }

    @Test
    public void testSetSchema_platformHidden() throws Exception {
        // Make sure we have global query privileges
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager.checkPermission(
                READ_GLOBAL_APP_SEARCH_DATA, mContext.getPackageName()))
                .thenReturn(PERMISSION_GRANTED);

        String prefix = PrefixUtil.createPrefix("package", "database");
        mAppSearchImpl.setSchema(
                "package",
                "database",
                Collections.singletonList(new AppSearchSchema.Builder("Schema").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.singletonList("Schema"),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*schemaVersion=*/ 0);

        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        "package",
                                        "database",
                                        prefix + "Schema",
                                        mContext.getPackageName(),
                                        mGlobalQuerierUid))
                .isFalse();
    }

    @Test
    public void testSetSchema_defaultNotPackageAccessible() throws Exception {
        String packageName = "com.package";

        // Make sure package doesn't global query privileges
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager.checkPermission(
                READ_GLOBAL_APP_SEARCH_DATA, packageName)).thenReturn(PERMISSION_DENIED);

        String prefix = PrefixUtil.createPrefix("package", "database");
        mAppSearchImpl.setSchema(
                "package",
                "database",
                Collections.singletonList(new AppSearchSchema.Builder("Schema").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*schemaVersion=*/ 0);
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        "package",
                                        "database",
                                        prefix + "Schema",
                                        packageName,
                                        /*callerUid=*/ 42))
                .isFalse();
    }

    @Test
    public void testSetSchema_packageAccessible() throws Exception {
        // Values for a "foo" client
        String packageNameFoo = "packageFoo";
        byte[] sha256CertFoo = new byte[] {10};
        int uidFoo = 1;

        // Make sure foo package will pass package manager checks.
        PackageManager mockPackageManager = getMockPackageManager(mContext.getUser());
        when(mockPackageManager.getPackageUid(eq(packageNameFoo), /*flags=*/ anyInt()))
                .thenReturn(uidFoo);
        when(mockPackageManager.hasSigningCertificate(
                packageNameFoo, sha256CertFoo, PackageManager.CERT_INPUT_SHA256))
                .thenReturn(true);

        // Make sure foo doesn't have global query privileges
        when(mockPackageManager.checkPermission(READ_GLOBAL_APP_SEARCH_DATA, packageNameFoo))
                .thenReturn(PERMISSION_DENIED);

        String prefix = PrefixUtil.createPrefix("package", "database");
        mAppSearchImpl.setSchema(
                "package",
                "database",
                Collections.singletonList(new AppSearchSchema.Builder("Schema").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ ImmutableMap.of(
                        "Schema",
                        ImmutableList.of(new PackageIdentifier(packageNameFoo, sha256CertFoo))),
                /*forceOverride=*/ false,
                /*schemaVersion=*/ 0);
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        "package",
                                        "database",
                                        prefix + "Schema",
                                        packageNameFoo,
                                        uidFoo))
                .isTrue();
    }

    @NonNull
    private PackageManager getMockPackageManager(@NonNull UserHandle user) {
        PackageManager pm = mMockPackageManagers.get(user);
        if (pm == null) {
            pm = Mockito.mock(PackageManager.class);
            mMockPackageManagers.put(user, pm);
        }
        return pm;
    }
}
