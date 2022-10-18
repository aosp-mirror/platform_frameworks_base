/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.devicepolicy;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;

import com.android.role.RoleManagerLocal;
import com.android.server.LocalManagerRegistry;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class EnforcingAdmin {
    static final String ROLE_AUTHORITY_PREFIX = "role:";
    static final String DPC_AUTHORITY = "enterprise";
    static final String DEVICE_ADMIN_AUTHORITY = "device_admin";
    static final String DEFAULT_AUTHORITY = "default";

    private final String mPackageName;
    // This is needed for DPCs and device admins
    @Nullable private final ComponentName mComponentName;
    // TODO: implement lazy loading for authorities
    private final Set<String> mAuthorities;

    static EnforcingAdmin createEnforcingAdmin(@NonNull String packageName, int userId) {
        Objects.requireNonNull(packageName);
        return new EnforcingAdmin(packageName, getRoleAuthoritiesOrDefault(packageName, userId));
    }

    static EnforcingAdmin createEnterpriseEnforcingAdmin(ComponentName componentName) {
        Objects.requireNonNull(componentName);
        return new EnforcingAdmin(componentName, Set.of(DPC_AUTHORITY));
    }

    static EnforcingAdmin createDeviceAdminEnforcingAdmin(ComponentName componentName) {
        Objects.requireNonNull(componentName);
        return new EnforcingAdmin(componentName, Set.of(DEVICE_ADMIN_AUTHORITY));
    }

    private EnforcingAdmin(String packageName, Set<String> authorities) {
        mPackageName = packageName;
        mComponentName = null;
        mAuthorities = new HashSet<>(authorities);
    }

    private EnforcingAdmin(ComponentName componentName, Set<String> authorities) {
        mPackageName = componentName.getPackageName();
        mComponentName = componentName;
        mAuthorities = new HashSet<>(authorities);
    }

    private static Set<String> getRoleAuthoritiesOrDefault(String packageName, int userId) {
        Set<String> roles = getRoles(packageName, userId);
        Set<String> authorities = new HashSet<>();
        for (String role : roles) {
            authorities.add(ROLE_AUTHORITY_PREFIX + role);
        }
        return authorities.isEmpty() ? Set.of(DEFAULT_AUTHORITY) : authorities;
    }

    // TODO(b/259042794): move this logic to RoleManagerLocal
    private static Set<String> getRoles(String packageName, int userId) {
        RoleManagerLocal roleManagerLocal = LocalManagerRegistry.getManager(
                RoleManagerLocal.class);
        Set<String> roles = new HashSet<>();
        Map<String, Set<String>> rolesAndHolders = roleManagerLocal.getRolesAndHolders(userId);
        for (String role : rolesAndHolders.keySet()) {
            if (rolesAndHolders.get(role).contains(packageName)) {
                roles.add(role);
            }
        }
        return roles;
    }

    boolean hasAuthority(String authority) {
        return mAuthorities.contains(authority);
    }

    /**
     * For two EnforcingAdmins to be equal they must:
     *
     * <ul>
     *     <li> have the same package names and component names and either
     *     <li> have exactly the same authorities ({@link #DPC_AUTHORITY} or
     *     {@link #DEVICE_ADMIN_AUTHORITY}), or have any role or default authorities.
     * </ul>
     *
     * <p>EnforcingAdmins are considered equal if they have any role authority as they can have
     * roles granted/revoked between calls.
     */
    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EnforcingAdmin other = (EnforcingAdmin) o;
        return Objects.equals(mPackageName, other.mPackageName)
                && Objects.equals(mComponentName, other.mComponentName)
                && hasMatchingAuthorities(this, other);
    }

    private static boolean hasMatchingAuthorities(EnforcingAdmin admin1, EnforcingAdmin admin2) {
        if (admin1.mAuthorities.equals(admin2.mAuthorities)) {
            return true;
        }
        return !admin1.hasAuthority(DPC_AUTHORITY) && !admin1.hasAuthority(DEVICE_ADMIN_AUTHORITY)
                && !admin2.hasAuthority(DPC_AUTHORITY) && !admin2.hasAuthority(
                DEVICE_ADMIN_AUTHORITY);
    }

    @Override
    public int hashCode() {
        if (mAuthorities.contains(DPC_AUTHORITY) || mAuthorities.contains(DEVICE_ADMIN_AUTHORITY)) {
            return Objects.hash(mComponentName, mAuthorities);
        } else {
            return Objects.hash(mPackageName);
        }
    }
}
