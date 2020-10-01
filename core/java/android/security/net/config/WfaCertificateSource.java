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

package android.security.net.config;

import java.io.File;

/**
 * {@link CertificateSource} based on the system WFA CA store.
 * @hide
 */
public final class WfaCertificateSource extends DirectoryCertificateSource {
    private static final String CACERTS_WFA_PATH =
            "/apex/com.android.wifi/etc/security/cacerts_wfa";

    private static class NoPreloadHolder {
        private static final WfaCertificateSource INSTANCE = new WfaCertificateSource();
    }

    private WfaCertificateSource() {
        super(new File(CACERTS_WFA_PATH));
    }

    public static WfaCertificateSource getInstance() {
        return NoPreloadHolder.INSTANCE;
    }

    @Override
    protected boolean isCertMarkedAsRemoved(String caFile) {
        return false;
    }
}
