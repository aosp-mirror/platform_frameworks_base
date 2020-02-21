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
 * limitations under the License
 */

package com.android.server.pm;

import android.os.Build;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.ArraySet;

import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;

import dalvik.system.VMRuntime;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides various methods for obtaining and converting of instruction sets.
 *
 * @hide
 */
public class InstructionSets {
    private static final String PREFERRED_INSTRUCTION_SET =
            VMRuntime.getInstructionSet(Build.SUPPORTED_ABIS[0]);

    public static String[] getAppDexInstructionSets(String primaryCpuAbi, String secondaryCpuAbi) {
        if (primaryCpuAbi != null) {
            if (secondaryCpuAbi != null) {
                return new String[] {
                        VMRuntime.getInstructionSet(primaryCpuAbi),
                        VMRuntime.getInstructionSet(secondaryCpuAbi) };
            } else {
                return new String[] {
                        VMRuntime.getInstructionSet(primaryCpuAbi) };
            }
        }

        return new String[] { getPreferredInstructionSet() };
    }

    public static String getPreferredInstructionSet() {
        return PREFERRED_INSTRUCTION_SET;
    }

    /**
     * Returns the instruction set that should be used to compile dex code. In the presence of
     * a native bridge this might be different than the one shared libraries use.
     */
    public static String getDexCodeInstructionSet(String sharedLibraryIsa) {
        // TODO b/19550105 Build mapping once instead of querying each time
        String dexCodeIsa = SystemProperties.get("ro.dalvik.vm.isa." + sharedLibraryIsa);
        return TextUtils.isEmpty(dexCodeIsa) ? sharedLibraryIsa : dexCodeIsa;
    }

    public static String[] getDexCodeInstructionSets(String[] instructionSets) {
        ArraySet<String> dexCodeInstructionSets = new ArraySet<String>(instructionSets.length);
        for (String instructionSet : instructionSets) {
            dexCodeInstructionSets.add(getDexCodeInstructionSet(instructionSet));
        }
        return dexCodeInstructionSets.toArray(new String[dexCodeInstructionSets.size()]);
    }

    /**
     * Returns deduplicated list of supported instructions for dex code.
     */
    public static String[] getAllDexCodeInstructionSets() {
        String[] supportedInstructionSets = new String[Build.SUPPORTED_ABIS.length];
        for (int i = 0; i < supportedInstructionSets.length; i++) {
            String abi = Build.SUPPORTED_ABIS[i];
            supportedInstructionSets[i] = VMRuntime.getInstructionSet(abi);
        }
        return getDexCodeInstructionSets(supportedInstructionSets);
    }

    public static List<String> getAllInstructionSets() {
        final String[] allAbis = Build.SUPPORTED_ABIS;
        final List<String> allInstructionSets = new ArrayList<String>(allAbis.length);

        for (String abi : allAbis) {
            final String instructionSet = VMRuntime.getInstructionSet(abi);
            if (!allInstructionSets.contains(instructionSet)) {
                allInstructionSets.add(instructionSet);
            }
        }

        return allInstructionSets;
    }

    /**
     * Calculates the primary instruction set based on the computed Abis of a given package.
     */
    public static String getPrimaryInstructionSet(PackageAbiHelper.Abis abis) {
        if (abis.primary == null) {
            return getPreferredInstructionSet();
        }

        return VMRuntime.getInstructionSet(abis.primary);
    }
}
