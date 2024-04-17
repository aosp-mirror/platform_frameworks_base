/*
 * Copyright 2019 The Android Open Source Project
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

package android.security;

import android.os.ParcelFileDescriptor;
import android.os.IInstalld;

/**
 * Binder interface to communicate with FileIntegrityService.
 * @hide
 */
interface IFileIntegrityService {
    boolean isApkVeritySupported();
    boolean isAppSourceCertificateTrusted(in byte[] certificateBytes, in String packageName);

    IInstalld.IFsveritySetupAuthToken createAuthToken(in ParcelFileDescriptor authFd);

    @EnforcePermission("SETUP_FSVERITY")
    int setupFsverity(IInstalld.IFsveritySetupAuthToken authToken, in String filePath,
            in String packageName);
}
