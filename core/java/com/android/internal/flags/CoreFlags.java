/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.flags;

import android.flags.BooleanFlag;
import android.flags.DynamicBooleanFlag;
import android.flags.FeatureFlags;
import android.flags.FusedOffFlag;
import android.flags.FusedOnFlag;
import android.flags.SyncableFlag;

import java.util.ArrayList;
import java.util.List;

/**
 * Flags defined here are can be read by code in core.
 *
 * Flags not defined here will throw a security exception if third-party processes attempts to read
 * them.
 *
 * DO NOT define a flag here unless you explicitly intend for that flag to be readable by code that
 * runs inside a third party process.
 */
public abstract class CoreFlags {
    private static final List<SyncableFlag> sKnownFlags = new ArrayList<>();

    public static BooleanFlag BOOL_FLAG = booleanFlag("core", "bool_flag", false);
    public static FusedOffFlag OFF_FLAG = fusedOffFlag("core", "off_flag");
    public static FusedOnFlag ON_FLAG = fusedOnFlag("core", "on_flag");
    public static DynamicBooleanFlag DYN_FLAG = dynamicBooleanFlag("core", "dyn_flag", true);

    /** Returns true if the passed in flag matches a flag in this class. */
    public static boolean isCoreFlag(SyncableFlag flag) {
        for (SyncableFlag knownFlag : sKnownFlags) {
            if (knownFlag.getName().equals(flag.getName())
                    && knownFlag.getNamespace().equals(flag.getNamespace())) {
                return true;
            }
        }
        return false;
    }

    public static List<SyncableFlag> getCoreFlags() {
        return sKnownFlags;
    }

    private static BooleanFlag booleanFlag(String namespace, String name, boolean defaultValue) {
        BooleanFlag f = FeatureFlags.booleanFlag(namespace, name, defaultValue);

        sKnownFlags.add(new SyncableFlag(namespace, name, Boolean.toString(defaultValue), false));

        return f;
    }

    private static FusedOffFlag fusedOffFlag(String namespace, String name) {
        FusedOffFlag f = FeatureFlags.fusedOffFlag(namespace, name);

        sKnownFlags.add(new SyncableFlag(namespace, name, "false", false));

        return f;
    }

    private static FusedOnFlag fusedOnFlag(String namespace, String name) {
        FusedOnFlag f = FeatureFlags.fusedOnFlag(namespace, name);

        sKnownFlags.add(new SyncableFlag(namespace, name, "true", false));

        return f;
    }

    private static DynamicBooleanFlag dynamicBooleanFlag(
            String namespace, String name, boolean defaultValue) {
        DynamicBooleanFlag f = FeatureFlags.dynamicBooleanFlag(namespace, name, defaultValue);

        sKnownFlags.add(new SyncableFlag(namespace, name, Boolean.toString(defaultValue), true));

        return f;
    }
}
