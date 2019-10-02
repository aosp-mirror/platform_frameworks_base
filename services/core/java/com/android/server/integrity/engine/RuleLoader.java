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

package com.android.server.integrity.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * A helper class for loading rules to the rule evaluation engine.
 *
 * <p>Expose fine-grained APIs for loading rules to be passed to the rule evaluation engine.
 *
 * <p>It supports:
 * <ul>
 *     <li>Loading rules based on some keys, such as PACKAGE_NAME and APP_CERT.</li>
 * </ul>
 *
 * <p>It does NOT support:
 * <ul>
 *     <li>Loading the list of all rules.</li>
 *     <li>Merging rules resulting from different APIs.</li>
 * </ul>
 */
final class RuleLoader {

    List<String> loadRulesByPackageName(String packageName) {
        // TODO: Add logic based on rule storage.
        return new ArrayList<>();
    }

    List<String> loadRulesByAppCertificate(String appCertificate) {
        // TODO: Add logic based on rule storage.
        return new ArrayList<>();
    }

    List<String> loadRulesByInstallerName(String installerName) {
        // TODO: Add logic based on rule storage.
        return new ArrayList<>();
    }

    List<String> loadRulesByInstallerCertificate(String installerCertificate) {
        // TODO: Add logic based on rule storage.
        return new ArrayList<>();
    }
}
