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

package com.android.server.pm;

import java.util.Arrays;

/**
 * This is the key for the map of {@link android.content.pm.IntentFilterVerificationInfo}s
 * maintained by the  {@link com.android.server.pm.PackageManagerService}
 */
class IntentFilterVerificationKey {
    public String domains;
    public String packageName;
    public String className;

    public IntentFilterVerificationKey(String[] domains, String packageName, String className) {
        StringBuilder sb = new StringBuilder();
        for (String host : domains) {
            sb.append(host);
        }
        this.domains = sb.toString();
        this.packageName = packageName;
        this.className = className;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        IntentFilterVerificationKey that = (IntentFilterVerificationKey) o;

        if (domains != null ? !domains.equals(that.domains) : that.domains != null) return false;
        if (className != null ? !className.equals(that.className) : that.className != null)
            return false;
        if (packageName != null ? !packageName.equals(that.packageName) : that.packageName != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = domains != null ? domains.hashCode() : 0;
        result = 31 * result + (packageName != null ? packageName.hashCode() : 0);
        result = 31 * result + (className != null ? className.hashCode() : 0);
        return result;
    }
}
