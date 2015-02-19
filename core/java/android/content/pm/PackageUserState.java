/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.content.pm;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;

import android.util.ArraySet;

/**
 * Per-user state information about a package.
 * @hide
 */
public class PackageUserState {
    public boolean stopped;
    public boolean notLaunched;
    public boolean installed;
    public boolean hidden; // Is the app restricted by owner / admin
    public int enabled;
    public boolean blockUninstall;

    public String lastDisableAppCaller;

    public ArraySet<String> disabledComponents;
    public ArraySet<String> enabledComponents;

    public PackageUserState() {
        installed = true;
        hidden = false;
        enabled = COMPONENT_ENABLED_STATE_DEFAULT;
    }

    public PackageUserState(PackageUserState o) {
        installed = o.installed;
        stopped = o.stopped;
        notLaunched = o.notLaunched;
        enabled = o.enabled;
        hidden = o.hidden;
        lastDisableAppCaller = o.lastDisableAppCaller;
        disabledComponents = o.disabledComponents != null
                ? new ArraySet<String>(o.disabledComponents) : null;
        enabledComponents = o.enabledComponents != null
                ? new ArraySet<String>(o.enabledComponents) : null;
        blockUninstall = o.blockUninstall;
    }
}
