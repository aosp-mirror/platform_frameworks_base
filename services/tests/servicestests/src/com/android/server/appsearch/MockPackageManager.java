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

// TODO(b/169883602): This is purposely a different package from the path so that AppSearchImplTest
// can use it without an extra import. This should be moved into a proper package once
// AppSearchImpl-VisibilityStore's dependencies are refactored.
package com.android.server.appsearch.external.localstorage;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.pm.PackageManager;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Mock to help test package name, UID, and certificate verification. */
public class MockPackageManager {

    @Mock private PackageManager mMockPackageManager;

    public MockPackageManager() {
        MockitoAnnotations.initMocks(this);
    }

    @NonNull
    public PackageManager getMockPackageManager() {
        return mMockPackageManager;
    }

    /** Mock a checkPermission call. */
    public void mockCheckPermission(String permission, String packageName, int permissionResult) {
        when(mMockPackageManager.checkPermission(permission, packageName))
                .thenReturn(permissionResult);
    }

    /** Mock a NameNotFoundException if the package name isn't installed. */
    public void mockThrowsNameNotFoundException(String packageName) {
        try {
            when(mMockPackageManager.getPackageUidAsUser(eq(packageName), /*userId=*/ anyInt()))
                    .thenThrow(new PackageManager.NameNotFoundException());
            when(mMockPackageManager.getPackageUidAsUser(
                            eq(packageName), /*flags=*/ anyInt(), /*userId=*/ anyInt()))
                    .thenThrow(new PackageManager.NameNotFoundException());
        } catch (PackageManager.NameNotFoundException e) {
            // Shouldn't ever happen since we're mocking the exception
            e.printStackTrace();
        }
    }

    /** Mocks that {@code uid} contains the {@code packageName} */
    public void mockGetPackageUidAsUser(String packageName, @UserIdInt int callerUserId, int uid) {
        try {
            when(mMockPackageManager.getPackageUidAsUser(eq(packageName), eq(callerUserId)))
                    .thenReturn(uid);
            when(mMockPackageManager.getPackageUidAsUser(
                            eq(packageName), /*flags=*/ anyInt(), eq(callerUserId)))
                    .thenReturn(uid);
        } catch (PackageManager.NameNotFoundException e) {
            // Shouldn't ever happen since we're mocking the method.
            e.printStackTrace();
        }
    }

    /** Mocks that {@code packageName} has been signed with {@code sha256Cert}. */
    public void mockAddSigningCertificate(String packageName, byte[] sha256Cert) {
        when(mMockPackageManager.hasSigningCertificate(
                        packageName, sha256Cert, PackageManager.CERT_INPUT_SHA256))
                .thenReturn(true);
    }

    /** Mocks that {@code packageName} has NOT been signed with {@code sha256Cert}. */
    public void mockRemoveSigningCertificate(String packageName, byte[] sha256Cert) {
        when(mMockPackageManager.hasSigningCertificate(
                        packageName, sha256Cert, PackageManager.CERT_INPUT_SHA256))
                .thenReturn(false);
    }
}
