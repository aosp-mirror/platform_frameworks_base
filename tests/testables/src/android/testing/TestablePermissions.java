/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package android.testing;

import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.ArrayMap;

/**
 * Simple class for simulating basic permission states for tests.
 *
 * All enforce* and check* calls on TestableContext are considered the same
 * and routed through the same check here. If more fine-grained control is
 * required, then either a sub-class or spy on TestableContext is recommended.
 */
public class TestablePermissions {

    private final ArrayMap<String, Integer> mPermissions = new ArrayMap<>();
    private final ArrayMap<Uri, Integer> mUris = new ArrayMap<>();

    /**
     * Sets the return value for checkPermission* calls on TestableContext
     * for a specific permission value. For all enforcePermission* calls
     * they will throw a security exception if value != PERMISSION_GRANTED.
     */
    public void setPermission(String permission, int value) {
        mPermissions.put(permission, value);
    }

    /**
     * Sets the return value for checkUriPermission* calls on TestableContext
     * for a specific permission value. For all enforceUriPermission* calls
     * they will throw a security exception if value != PERMISSION_GRANTED.
     */
    public void setPermission(Uri uri, int value) {
        // TODO: Support modeFlags
        mUris.put(uri, value);
    }

    boolean wantsCall(String permission) {
        return mPermissions.containsKey(permission);
    }

    boolean wantsCall(Uri uri) {
        return mUris.containsKey(uri);
    }

    int check(String permission) {
        return mPermissions.get(permission);
    }

    int check(Uri uri, int modeFlags) {
        // TODO: Support modeFlags
        return mUris.get(uri);
    }

    public void enforce(String permission) {
        if (check(permission) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException();
        }
    }

    public void enforce(Uri uri, int modeFlags) {
        if (check(uri, modeFlags) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException();
        }
    }
}
