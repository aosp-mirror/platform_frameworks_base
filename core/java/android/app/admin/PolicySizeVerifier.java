/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.admin;

import android.content.ComponentName;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.PersistableBundle;

import com.android.internal.util.Preconditions;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Utility class containing methods to verify the max allowed size of certain policy types.
 *
 * @hide
 */
public class PolicySizeVerifier {

    // Binary XML serializer doesn't support longer strings
    public static final int MAX_POLICY_STRING_LENGTH = 65535;
    // FrameworkParsingPackageUtils#MAX_FILE_NAME_SIZE, Android packages are used in dir names.
    public static final int MAX_PACKAGE_NAME_LENGTH = 223;

    public static final int MAX_PROFILE_NAME_LENGTH = 200;
    public static final int MAX_LONG_SUPPORT_MESSAGE_LENGTH = 20000;
    public static final int MAX_SHORT_SUPPORT_MESSAGE_LENGTH = 200;
    public static final int MAX_ORG_NAME_LENGTH = 200;

    /**
     * Throw if string argument is too long to be serialized.
     */
    public static void enforceMaxStringLength(String str, String argName) {
        Preconditions.checkArgument(
                str.length() <= MAX_POLICY_STRING_LENGTH, argName + " loo long");
    }

    /**
     * Throw if package name exceeds max size allowed by the system.
     */
    public static void enforceMaxPackageNameLength(String pkg) {
        Preconditions.checkArgument(
                pkg.length() <= MAX_PACKAGE_NAME_LENGTH, "Package name too long");
    }

    /**
     * Throw if persistable bundle contains any string that's too long to be serialized.
     */
    public static void enforceMaxStringLength(PersistableBundle bundle, String argName) {
        // Persistable bundles can have other persistable bundles as values, traverse with a queue.
        Queue<PersistableBundle> queue = new ArrayDeque<>();
        queue.add(bundle);
        while (!queue.isEmpty()) {
            PersistableBundle current = queue.remove();
            for (String key : current.keySet()) {
                enforceMaxStringLength(key, "key in " + argName);
                Object value = current.get(key);
                if (value instanceof String str) {
                    enforceMaxStringLength(str, "string value in " + argName);
                } else if (value instanceof String[] strArray) {
                    for (String str : strArray) {
                        enforceMaxStringLength(str, "string value in " + argName);
                    }
                } else if (value instanceof PersistableBundle persistableBundle) {
                    queue.add(persistableBundle);
                }
            }
        }
    }

    /**
     * Throw if bundle contains any string that's too long to be serialized. This follows the
     * serialization logic in BundlePolicySerializer#writeBundle.
     */
    public static void enforceMaxBundleFieldsLength(Bundle bundle) {
        Queue<Bundle> queue = new ArrayDeque<>();
        queue.add(bundle);
        while (!queue.isEmpty()) {
            Bundle current = queue.remove();
            for (String key : current.keySet()) {
                enforceMaxStringLength(key, "key in Bundle");
                Object value = current.get(key);
                if (value instanceof String str) {
                    enforceMaxStringLength(str, "string value in Bundle with "
                            + "key" + key);
                } else if (value instanceof String[] strArray) {
                    for (String str : strArray) {
                        enforceMaxStringLength(str, "string value in Bundle with"
                                + " key" + key);
                    }
                } else if (value instanceof Bundle b) {
                    queue.add(b);
                }
                else if (value instanceof Parcelable[] parcelableArray) {
                    for (Parcelable parcelable : parcelableArray) {
                        if (!(parcelable instanceof Bundle)) {
                            throw new IllegalArgumentException("bundle-array can only hold "
                                    + "Bundles");
                        }
                        queue.add((Bundle) parcelable);
                    }
                }
            }
        }
    }

    /**
     * Throw if ComponentName contains any string that's too long to be serialized.
     */
    public static void enforceMaxComponentNameLength(ComponentName componentName) {
        enforceMaxPackageNameLength(componentName.getPackageName());
        enforceMaxStringLength(componentName.flattenToString(), "componentName");
    }

    /**
     * Truncates char sequence to maximum length, nulls are ignored.
     */
    public static CharSequence truncateIfLonger(CharSequence input, int maxLength) {
        return input == null || input.length() <= maxLength
                ? input
                : input.subSequence(0, maxLength);
    }
}
