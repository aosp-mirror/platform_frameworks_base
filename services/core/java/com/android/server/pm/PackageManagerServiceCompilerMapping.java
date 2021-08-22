/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.os.SystemProperties;

import com.android.server.pm.dex.DexoptOptions;

import dalvik.system.DexFile;

/**
 * Manage (retrieve) mappings from compilation reason to compilation filter.
 */
public class PackageManagerServiceCompilerMapping {
    // Names for compilation reasons.
    public static final String REASON_STRINGS[] = {
        "first-boot",
        "boot-after-ota",
        "post-boot",
        "install",
        "install-fast",
        "install-bulk",
        "install-bulk-secondary",
        "install-bulk-downgraded",
        "install-bulk-secondary-downgraded",
        "bg-dexopt",
        "ab-ota",
        "inactive",
        "cmdline",
        // "shared" must be the last entry
        "shared"
    };

    static final int REASON_SHARED_INDEX = REASON_STRINGS.length - 1;

    // Static block to ensure the strings array is of the right length.
    static {
        if (PackageManagerService.REASON_LAST + 1 != REASON_STRINGS.length) {
            throw new IllegalStateException("REASON_STRINGS not correct");
        }
        if (!"shared".equals(REASON_STRINGS[REASON_SHARED_INDEX])) {
            throw new IllegalStateException("REASON_STRINGS not correct because of shared index");
        }
    }

    private static String getSystemPropertyName(int reason) {
        if (reason < 0 || reason >= REASON_STRINGS.length) {
            throw new IllegalArgumentException("reason " + reason + " invalid");
        }

        return "pm.dexopt." + REASON_STRINGS[reason];
    }

    // Load the property for the given reason and check for validity. This will throw an
    // exception in case the reason or value are invalid.
    private static String getAndCheckValidity(int reason) {
        String sysPropValue = SystemProperties.get(getSystemPropertyName(reason));
        if (sysPropValue == null || sysPropValue.isEmpty()
                || !(sysPropValue.equals(DexoptOptions.COMPILER_FILTER_NOOP)
                        || DexFile.isValidCompilerFilter(sysPropValue))) {
            throw new IllegalStateException("Value \"" + sysPropValue +"\" not valid "
                    + "(reason " + REASON_STRINGS[reason] + ")");
        } else if (!isFilterAllowedForReason(reason, sysPropValue)) {
            throw new IllegalStateException("Value \"" + sysPropValue +"\" not allowed "
                    + "(reason " + REASON_STRINGS[reason] + ")");
        }

        return sysPropValue;
    }

    private static boolean isFilterAllowedForReason(int reason, String filter) {
        return reason != REASON_SHARED_INDEX || !DexFile.isProfileGuidedCompilerFilter(filter);
    }

    // Check that the properties are set and valid.
    // Note: this is done in a separate method so this class can be statically initialized.
    static void checkProperties() {
        // We're gonna check all properties and collect the exceptions, so we can give a general
        // overview. Store the exceptions here.
        RuntimeException toThrow = null;

        for (int reason = 0; reason <= PackageManagerService.REASON_LAST; reason++) {
            try {
                // Check that the system property name is legal.
                String sysPropName = getSystemPropertyName(reason);
                if (sysPropName == null || sysPropName.isEmpty()) {
                    throw new IllegalStateException("Reason system property name \"" +
                            sysPropName +"\" for reason " + REASON_STRINGS[reason]);
                }

                // Check validity, ignore result.
                getAndCheckValidity(reason);
            } catch (Exception exc) {
                if (toThrow == null) {
                    toThrow = new IllegalStateException("PMS compiler filter settings are bad.");
                }
                toThrow.addSuppressed(exc);
            }
        }

        if (toThrow != null) {
            throw toThrow;
        }
    }

    public static String getCompilerFilterForReason(int reason) {
        return getAndCheckValidity(reason);
    }

    /**
     * Return the default compiler filter for compilation.
     *
     * We derive that from the traditional "dalvik.vm.dex2oat-filter" property and just make
     * sure this isn't profile-guided. Returns "speed" in case of invalid (or missing) values.
     */
    public static String getDefaultCompilerFilter() {
        String value = SystemProperties.get("dalvik.vm.dex2oat-filter");
        if (value == null || value.isEmpty()) {
            return "speed";
        }

        if (!DexFile.isValidCompilerFilter(value) ||
                DexFile.isProfileGuidedCompilerFilter(value)) {
            return "speed";
        }

        return value;
    }

    public static String getReasonName(int reason) {
        if (reason < 0 || reason >= REASON_STRINGS.length) {
            throw new IllegalArgumentException("reason " + reason + " invalid");
        }
        return REASON_STRINGS[reason];
    }
}
