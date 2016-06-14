/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.net.config;

import android.os.Environment;
import android.os.UserHandle;
import java.io.File;

/**
 * {@link CertificateSource} based on the user-installed trusted CA store.
 * @hide
 */
public final class UserCertificateSource extends DirectoryCertificateSource {
    private static class NoPreloadHolder {
        private static final UserCertificateSource INSTANCE = new UserCertificateSource();
    }

    private UserCertificateSource() {
        super(new File(
                Environment.getUserConfigDirectory(UserHandle.myUserId()), "cacerts-added"));
    }

    public static UserCertificateSource getInstance() {
        return NoPreloadHolder.INSTANCE;
    }

    @Override
    protected boolean isCertMarkedAsRemoved(String caFile) {
        return false;
    }
}
