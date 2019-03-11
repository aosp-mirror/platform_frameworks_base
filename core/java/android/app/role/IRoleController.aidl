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

package android.app.role;

import android.os.RemoteCallback;

/**
 * @hide
 */
oneway interface IRoleController {

    void grantDefaultRoles(in RemoteCallback callback);

    void onAddRoleHolder(in String roleName, in String packageName, int flags,
            in RemoteCallback callback);

    void onRemoveRoleHolder(in String roleName, in String packageName, int flags,
            in RemoteCallback callback);

    void onClearRoleHolders(in String roleName, int flags, in RemoteCallback callback);

    void onSmsKillSwitchToggled(boolean enabled);

    void isApplicationQualifiedForRole(in String roleName, in String packageName,
            in RemoteCallback callback);

    void isRoleVisible(in String roleName, in RemoteCallback callback);
}
