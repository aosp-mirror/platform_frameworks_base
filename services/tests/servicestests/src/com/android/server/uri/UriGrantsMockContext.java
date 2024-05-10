/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.uri;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManagerInternal;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PathPermission;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.PatternMatcher;
import android.os.Process;
import android.os.UserHandle;
import android.test.mock.MockContext;
import android.test.mock.MockPackageManager;

import com.android.server.LocalServices;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class UriGrantsMockContext extends MockContext {
    static final String TAG = "UriGrants";

    static final int FLAG_READ = Intent.FLAG_GRANT_READ_URI_PERMISSION;
    static final int FLAG_WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    static final int FLAG_PERSISTABLE = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
    static final int FLAG_PREFIX = Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

    static final int USER_PRIMARY = 10;
    static final int USER_SECONDARY = 11;

    /** Typical social network app */
    static final String PKG_SOCIAL = "com.example.social";
    /** Typical camera app that allows grants */
    static final String PKG_CAMERA = "com.example.camera";
    /** Completely private app/provider that offers no grants */
    static final String PKG_PRIVATE = "com.example.private";
    /** Completely public app/provider that needs no grants */
    static final String PKG_PUBLIC = "com.example.public";
    /** Completely public app/provider that forces grants */
    static final String PKG_FORCE = "com.example.force";
    /** Complex provider that offers nested grants */
    static final String PKG_COMPLEX = "com.example.complex";

    private static final int UID_SOCIAL = android.os.Process.LAST_APPLICATION_UID - 1;
    private static final int UID_CAMERA = android.os.Process.LAST_APPLICATION_UID - 2;
    private static final int UID_PRIVATE = android.os.Process.LAST_APPLICATION_UID - 3;
    private static final int UID_PUBLIC = android.os.Process.LAST_APPLICATION_UID - 4;
    private static final int UID_FORCE = android.os.Process.LAST_APPLICATION_UID - 5;
    private static final int UID_COMPLEX = android.os.Process.LAST_APPLICATION_UID - 6;

    static final int UID_PRIMARY_SOCIAL = UserHandle.getUid(USER_PRIMARY, UID_SOCIAL);
    static final int UID_PRIMARY_CAMERA = UserHandle.getUid(USER_PRIMARY, UID_CAMERA);
    static final int UID_PRIMARY_PRIVATE = UserHandle.getUid(USER_PRIMARY, UID_PRIVATE);
    static final int UID_PRIMARY_PUBLIC = UserHandle.getUid(USER_PRIMARY, UID_PUBLIC);
    static final int UID_PRIMARY_FORCE = UserHandle.getUid(USER_PRIMARY, UID_FORCE);
    static final int UID_PRIMARY_COMPLEX = UserHandle.getUid(USER_PRIMARY, UID_COMPLEX);

    static final int UID_SECONDARY_SOCIAL = UserHandle.getUid(USER_SECONDARY, UID_SOCIAL);
    static final int UID_SECONDARY_CAMERA = UserHandle.getUid(USER_SECONDARY, UID_CAMERA);
    static final int UID_SECONDARY_PRIVATE = UserHandle.getUid(USER_SECONDARY, UID_PRIVATE);
    static final int UID_SECONDARY_PUBLIC = UserHandle.getUid(USER_SECONDARY, UID_PUBLIC);
    static final int UID_SECONDARY_FORCE = UserHandle.getUid(USER_SECONDARY, UID_FORCE);
    static final int UID_SECONDARY_COMPLEX = UserHandle.getUid(USER_SECONDARY, UID_COMPLEX);

    static final Uri URI_PHOTO_1 = Uri.parse("content://" + PKG_CAMERA + "/1");
    static final Uri URI_PHOTO_2 = Uri.parse("content://" + PKG_CAMERA + "/2");
    static final Uri URI_PRIVATE = Uri.parse("content://" + PKG_PRIVATE + "/42");
    static final Uri URI_PUBLIC = Uri.parse("content://" + PKG_PUBLIC + "/42");
    static final Uri URI_FORCE = Uri.parse("content://" + PKG_FORCE + "/42");

    private final File mDir;

    private final MockPackageManager mPackage;

    final ActivityManagerInternal mAmInternal;
    final PackageManagerInternal mPmInternal;

    public UriGrantsMockContext() throws IOException {
        mDir = Files.createTempDirectory(TAG).toFile();

        mPackage = new MockPackageManager();

        mAmInternal = mock(ActivityManagerInternal.class);
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.addService(ActivityManagerInternal.class, mAmInternal);

        mPmInternal = mock(PackageManagerInternal.class);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPmInternal);

        for (int userId : new int[] { USER_PRIMARY, USER_SECONDARY }) {
            when(mPmInternal.getPackageUid(eq(PKG_SOCIAL), anyLong(), eq(userId)))
                    .thenReturn(UserHandle.getUid(userId, UID_SOCIAL));
            when(mPmInternal.getPackageUid(eq(PKG_CAMERA), anyLong(), eq(userId)))
                    .thenReturn(UserHandle.getUid(userId, UID_CAMERA));
            when(mPmInternal.getPackageUid(eq(PKG_PRIVATE), anyLong(), eq(userId)))
                    .thenReturn(UserHandle.getUid(userId, UID_PRIVATE));
            when(mPmInternal.getPackageUid(eq(PKG_PUBLIC), anyLong(), eq(userId)))
                    .thenReturn(UserHandle.getUid(userId, UID_PUBLIC));
            when(mPmInternal.getPackageUid(eq(PKG_FORCE), anyLong(), eq(userId)))
                    .thenReturn(UserHandle.getUid(userId, UID_FORCE));
            when(mPmInternal.getPackageUid(eq(PKG_COMPLEX), anyLong(), eq(userId)))
                    .thenReturn(UserHandle.getUid(userId, UID_COMPLEX));

            when(mPmInternal.resolveContentProvider(eq(PKG_CAMERA), anyLong(), eq(userId),
                    eq(Process.SYSTEM_UID)))
                    .thenReturn(buildCameraProvider(userId));
            when(mPmInternal.resolveContentProvider(eq(PKG_CAMERA), anyLong(), eq(userId),
                    eq(UserHandle.getUid(userId, UID_CAMERA))))
                    .thenReturn(buildCameraProvider(userId));
            when(mPmInternal.resolveContentProvider(eq(PKG_PRIVATE), anyLong(), eq(userId),
                    eq(Process.SYSTEM_UID)))
                    .thenReturn(buildPrivateProvider(userId));
            when(mPmInternal.resolveContentProvider(eq(PKG_PRIVATE), anyLong(), eq(userId),
                    eq(UserHandle.getUid(userId, UID_PRIVATE))))
                    .thenReturn(buildPrivateProvider(userId));
            when(mPmInternal.resolveContentProvider(eq(PKG_PUBLIC), anyLong(), eq(userId),
                    eq(Process.SYSTEM_UID)))
                    .thenReturn(buildPublicProvider(userId));
            when(mPmInternal.resolveContentProvider(eq(PKG_PUBLIC), anyLong(), eq(userId),
                    eq(UserHandle.getUid(userId, UID_PUBLIC))))
                    .thenReturn(buildPublicProvider(userId));
            when(mPmInternal.resolveContentProvider(eq(PKG_FORCE), anyLong(), eq(userId),
                    eq(Process.SYSTEM_UID)))
                    .thenReturn(buildForceProvider(userId));
            when(mPmInternal.resolveContentProvider(eq(PKG_FORCE), anyLong(), eq(userId),
                    eq(UserHandle.getUid(userId, UID_FORCE))))
                    .thenReturn(buildForceProvider(userId));
            when(mPmInternal.resolveContentProvider(eq(PKG_COMPLEX), anyLong(), eq(userId),
                    eq(Process.SYSTEM_UID)))
                    .thenReturn(buildComplexProvider(userId));
            when(mPmInternal.resolveContentProvider(eq(PKG_COMPLEX), anyLong(), eq(userId),
                    eq(UserHandle.getUid(userId, UID_COMPLEX))))
                    .thenReturn(buildComplexProvider(userId));
        }
    }

    private static ProviderInfo buildCameraProvider(int userId) {
        final ProviderInfo pi = new ProviderInfo();
        pi.packageName = PKG_CAMERA;
        pi.authority = PKG_CAMERA;
        pi.readPermission = android.Manifest.permission.READ_EXTERNAL_STORAGE;
        pi.writePermission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
        pi.grantUriPermissions = true;
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.uid = UserHandle.getUid(userId, UID_CAMERA);
        return pi;
    }

    private static ProviderInfo buildPrivateProvider(int userId) {
        final ProviderInfo pi = new ProviderInfo();
        pi.packageName = PKG_PRIVATE;
        pi.authority = PKG_PRIVATE;
        pi.exported = false;
        pi.grantUriPermissions = false;
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.uid = UserHandle.getUid(userId, UID_PRIVATE);
        return pi;
    }

    private static ProviderInfo buildPublicProvider(int userId) {
        final ProviderInfo pi = new ProviderInfo();
        pi.packageName = PKG_PUBLIC;
        pi.authority = PKG_PUBLIC;
        pi.exported = true;
        pi.grantUriPermissions = false;
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.uid = UserHandle.getUid(userId, UID_PUBLIC);
        return pi;
    }

    private static ProviderInfo buildForceProvider(int userId) {
        final ProviderInfo pi = new ProviderInfo();
        pi.packageName = PKG_FORCE;
        pi.authority = PKG_FORCE;
        pi.exported = true;
        pi.grantUriPermissions = true;
        pi.forceUriPermissions = true;
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.uid = UserHandle.getUid(userId, UID_FORCE);
        return pi;
    }

    private static ProviderInfo buildComplexProvider(int userId) {
        final ProviderInfo pi = new ProviderInfo();
        pi.packageName = PKG_COMPLEX;
        pi.authority = PKG_COMPLEX;
        pi.exported = true;
        pi.grantUriPermissions = true;
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.uid = UserHandle.getUid(userId, UID_COMPLEX);
        pi.pathPermissions = new PathPermission[] {
                new PathPermission("/secure", PathPermission.PATTERN_PREFIX,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
        };
        pi.uriPermissionPatterns = new PatternMatcher[] {
                new PatternMatcher("/secure", PathPermission.PATTERN_PREFIX),
                new PatternMatcher("/insecure", PathPermission.PATTERN_PREFIX),
        };
        return pi;
    }

    @Override
    public PackageManager getPackageManager() {
        return mPackage;
    }

    @Override
    public File getFilesDir() {
        return mDir;
    }
}
