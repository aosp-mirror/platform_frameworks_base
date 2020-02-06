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
 * limitations under the License.
 */

package android.content.integrity;

import java.util.Map;

/**
 * An atomic formula that evaluates to true if the installer of the current install is specified in
 * the "allowed installer" field in the android manifest. Note that an empty "allowed installer" by
 * default means containing all possible installers.
 *
 * @hide
 */
public class InstallerAllowedByManifestFormula extends IntegrityFormula {

    @Override
    public int getTag() {
        return IntegrityFormula.INSTALLER_ALLOWED_BY_MANIFEST_FORMULA_TAG;
    }

    @Override
    public boolean matches(AppInstallMetadata appInstallMetadata) {
        Map<String, String> allowedInstallersAndCertificates =
                appInstallMetadata.getAllowedInstallersAndCertificates();
        return allowedInstallersAndCertificates.isEmpty()
                || installerInAllowedInstallersFromManifest(
                appInstallMetadata, allowedInstallersAndCertificates);
    }

    @Override
    public boolean isAppCertificateFormula() {
        return false;
    }

    @Override
    public boolean isInstallerFormula() {
        return true;
    }

    private static boolean installerInAllowedInstallersFromManifest(
            AppInstallMetadata appInstallMetadata,
            Map<String, String> allowedInstallersAndCertificates) {
        return allowedInstallersAndCertificates.containsKey(appInstallMetadata.getInstallerName())
                && appInstallMetadata.getInstallerCertificates()
                .contains(
                        allowedInstallersAndCertificates
                        .get(appInstallMetadata.getInstallerName()));
    }
}
