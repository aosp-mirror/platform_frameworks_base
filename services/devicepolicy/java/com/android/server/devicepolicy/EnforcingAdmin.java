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
import android.app.admin.Authority;
import android.app.admin.DeviceAdminAuthority;
import android.app.admin.DpcAuthority;
import android.app.admin.PackagePermissionPolicyKey;
import android.app.admin.RoleAuthority;
import android.app.admin.UnknownAuthority;
import android.content.ComponentName;
import android.os.UserHandle;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.role.RoleManagerLocal;
import com.android.server.LocalManagerRegistry;
import com.android.server.utils.Slogf;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code EnforcingAdmins} can have the following authority types:
 *
 * <ul>
 *     <li> {@link #DPC_AUTHORITY} meaning it's an enterprise admin (e.g. PO, DO, COPE)
 *     <li> {@link #DEVICE_ADMIN_AUTHORITY} which is a legacy non enterprise admin
 *     <li> Or a role authority, in which case {@link #mAuthorities} contains a list of all roles
 *     held by the given {@code packageName}
 * </ul>
 *
 */
final class EnforcingAdmin {

    static final String TAG = "EnforcingAdmin";

    static final String ROLE_AUTHORITY_PREFIX = "role:";
    static final String DPC_AUTHORITY = "enterprise";
    static final String DEVICE_ADMIN_AUTHORITY = "device_admin";
    static final String DEFAULT_AUTHORITY = "default";

    private static final String ATTR_PACKAGE_NAME = "package-name";
    private static final String ATTR_CLASS_NAME = "class-name";
    private static final String ATTR_AUTHORITIES = "authorities";
    private static final String ATTR_AUTHORITIES_SEPARATOR = ";";
    private static final String ATTR_USER_ID = "user-id";
    private static final String ATTR_IS_ROLE = "is-role";

    private final String mPackageName;
    // This is needed for DPCs and active admins
    private final ComponentName mComponentName;
    private Set<String> mAuthorities;
    private final int mUserId;
    private final boolean mIsRoleAuthority;
    private final ActiveAdmin mActiveAdmin;

    static EnforcingAdmin createEnforcingAdmin(@NonNull String packageName, int userId,
            ActiveAdmin admin) {
        Objects.requireNonNull(packageName);
        return new EnforcingAdmin(packageName, userId, admin);
    }

    static EnforcingAdmin createEnterpriseEnforcingAdmin(
            @NonNull ComponentName componentName, int userId) {
        Objects.requireNonNull(componentName);
        return new EnforcingAdmin(
                componentName.getPackageName(), componentName, Set.of(DPC_AUTHORITY), userId,
                /* activeAdmin=*/ null);
    }

    static EnforcingAdmin createEnterpriseEnforcingAdmin(
            @NonNull ComponentName componentName, int userId, ActiveAdmin activeAdmin) {
        Objects.requireNonNull(componentName);
        return new EnforcingAdmin(
                componentName.getPackageName(), componentName, Set.of(DPC_AUTHORITY), userId,
                activeAdmin);
    }

    static EnforcingAdmin createDeviceAdminEnforcingAdmin(ComponentName componentName, int userId,
            ActiveAdmin activeAdmin) {
        Objects.requireNonNull(componentName);
        return new EnforcingAdmin(
                componentName.getPackageName(), componentName, Set.of(DEVICE_ADMIN_AUTHORITY),
                userId, activeAdmin);
    }

    static EnforcingAdmin createEnforcingAdmin(android.app.admin.EnforcingAdmin admin) {
        Objects.requireNonNull(admin);
        Authority authority = admin.getAuthority();
        Set<String> internalAuthorities = new HashSet<>();
        if (DpcAuthority.DPC_AUTHORITY.equals(authority)) {
            return new EnforcingAdmin(
                    admin.getPackageName(), admin.getComponentName(),
                    Set.of(DPC_AUTHORITY), admin.getUserHandle().getIdentifier(),
                    /* activeAdmin = */ null);
        } else if (DeviceAdminAuthority.DEVICE_ADMIN_AUTHORITY.equals(authority)) {
            return new EnforcingAdmin(
                    admin.getPackageName(), admin.getComponentName(),
                    Set.of(DEVICE_ADMIN_AUTHORITY), admin.getUserHandle().getIdentifier(),
                    /* activeAdmin = */ null);
        } else if (authority instanceof RoleAuthority roleAuthority) {
            return new EnforcingAdmin(
                    admin.getPackageName(), admin.getComponentName(),
                    Set.of(DEVICE_ADMIN_AUTHORITY), admin.getUserHandle().getIdentifier(),
                    /* activeAdmin = */ null,
                    /* isRoleAuthority = */ true);
        }
        return new EnforcingAdmin(admin.getPackageName(), admin.getComponentName(),
                Set.of(), admin.getUserHandle().getIdentifier(),
                /* activeAdmin = */ null);
    }

    static String getRoleAuthorityOf(String roleName) {
        return ROLE_AUTHORITY_PREFIX + roleName;
    }

    static Authority getParcelableAuthority(String authority) {
        if (authority == null || authority.isEmpty()) {
            return UnknownAuthority.UNKNOWN_AUTHORITY;
        }
        if (DPC_AUTHORITY.equals(authority)) {
            return DpcAuthority.DPC_AUTHORITY;
        }
        if (DEVICE_ADMIN_AUTHORITY.equals(authority)) {
            return DeviceAdminAuthority.DEVICE_ADMIN_AUTHORITY;
        }
        if (authority.startsWith(ROLE_AUTHORITY_PREFIX)) {
            String role = authority.substring(ROLE_AUTHORITY_PREFIX.length());
            return new RoleAuthority(Set.of(role));
        }
        return UnknownAuthority.UNKNOWN_AUTHORITY;
    }

    private EnforcingAdmin(
            String packageName, @Nullable ComponentName componentName, Set<String> authorities,
            int userId, @Nullable ActiveAdmin activeAdmin) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(authorities);

        // Role authorities should not be using this constructor
        mIsRoleAuthority = false;
        mPackageName = packageName;
        mComponentName = componentName;
        mAuthorities = new HashSet<>(authorities);
        mUserId = userId;
        mActiveAdmin = activeAdmin;
    }

    private EnforcingAdmin(String packageName, int userId, ActiveAdmin activeAdmin) {
        Objects.requireNonNull(packageName);

        // Only role authorities use this constructor.
        mIsRoleAuthority = true;
        mPackageName = packageName;
        mUserId = userId;
        mComponentName = null;
        // authorities will be loaded when needed
        mAuthorities = null;
        mActiveAdmin = activeAdmin;
    }

    private EnforcingAdmin(
            String packageName, @Nullable ComponentName componentName, Set<String> authorities,
            int userId, @Nullable ActiveAdmin activeAdmin, boolean isRoleAuthority) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(authorities);

        mIsRoleAuthority = isRoleAuthority;
        mPackageName = packageName;
        mComponentName = componentName;
        mAuthorities = new HashSet<>(authorities);
        mUserId = userId;
        mActiveAdmin = activeAdmin;
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

    private Set<String> getAuthorities() {
        if (mAuthorities == null && mIsRoleAuthority) {
            mAuthorities = getRoleAuthoritiesOrDefault(mPackageName, mUserId);
        }
        return mAuthorities;
    }

    void reloadRoleAuthorities() {
        if (mIsRoleAuthority) {
            mAuthorities = getRoleAuthoritiesOrDefault(mPackageName, mUserId);
        }
    }

    boolean hasAuthority(String authority) {
        return getAuthorities().contains(authority);
    }

    @NonNull
    String getPackageName() {
        return mPackageName;
    }

    int getUserId() {
        return mUserId;
    }

    @Nullable
    public ActiveAdmin getActiveAdmin() {
        return mActiveAdmin;
    }

    @NonNull
    android.app.admin.EnforcingAdmin getParcelableAdmin() {
        Authority authority;
        if (mIsRoleAuthority) {
            Set<String> roles = getRoles(mPackageName, mUserId);
            if (roles.isEmpty()) {
                authority = UnknownAuthority.UNKNOWN_AUTHORITY;
            } else {
                authority = new RoleAuthority(roles);
            }
        } else if (mAuthorities.contains(DPC_AUTHORITY)) {
            authority = DpcAuthority.DPC_AUTHORITY;
        } else if (mAuthorities.contains(DEVICE_ADMIN_AUTHORITY)) {
            authority = DeviceAdminAuthority.DEVICE_ADMIN_AUTHORITY;
        } else {
            authority = UnknownAuthority.UNKNOWN_AUTHORITY;
        }
        return new android.app.admin.EnforcingAdmin(
                mPackageName,
                authority,
                UserHandle.of(mUserId),
                mComponentName);
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
                && Objects.equals(mIsRoleAuthority, other.mIsRoleAuthority)
                && hasMatchingAuthorities(this, other);
    }

    private static boolean hasMatchingAuthorities(EnforcingAdmin admin1, EnforcingAdmin admin2) {
        if (admin1.mIsRoleAuthority && admin2.mIsRoleAuthority) {
            return true;
        }
        return admin1.getAuthorities().equals(admin2.getAuthorities());
    }

    @Override
    public int hashCode() {
        if (mIsRoleAuthority) {
            return Objects.hash(mPackageName, mUserId);
        } else {
            return Objects.hash(
                    mComponentName == null ? mPackageName : mComponentName,
                    mUserId,
                    getAuthorities());
        }
    }

    void saveToXml(TypedXmlSerializer serializer) throws IOException {
        serializer.attribute(/* namespace= */ null, ATTR_PACKAGE_NAME, mPackageName);
        serializer.attributeBoolean(/* namespace= */ null, ATTR_IS_ROLE, mIsRoleAuthority);
        serializer.attributeInt(/* namespace= */ null, ATTR_USER_ID, mUserId);
        if (!mIsRoleAuthority) {
            if (mComponentName != null) {
                serializer.attribute(
                        /* namespace= */ null, ATTR_CLASS_NAME, mComponentName.getClassName());
            }
            // Role authorities get recomputed on load so no need to save them.
            serializer.attribute(
                    /* namespace= */ null,
                    ATTR_AUTHORITIES,
                    String.join(ATTR_AUTHORITIES_SEPARATOR, getAuthorities()));
        }
    }

    @Nullable
    static EnforcingAdmin readFromXml(TypedXmlPullParser parser)
            throws XmlPullParserException {
        String packageName = parser.getAttributeValue(/* namespace= */ null, ATTR_PACKAGE_NAME);
        boolean isRoleAuthority = parser.getAttributeBoolean(/* namespace= */ null, ATTR_IS_ROLE);
        String authoritiesStr = parser.getAttributeValue(/* namespace= */ null, ATTR_AUTHORITIES);
        int userId = parser.getAttributeInt(/* namespace= */ null, ATTR_USER_ID);

        if (isRoleAuthority) {
            if (packageName == null) {
                Slogf.wtf(TAG, "Error parsing EnforcingAdmin with RoleAuthority, packageName is "
                        + "null.");
                return null;
            }
            // TODO(b/281697976): load active admin
            return new EnforcingAdmin(packageName, userId, null);
        } else {
            if (packageName == null || authoritiesStr == null) {
                Slogf.wtf(TAG, "Error parsing EnforcingAdmin, packageName is "
                        + (packageName == null ? "null" : packageName) + ", and authorities is "
                        + (authoritiesStr == null ? "null" : authoritiesStr) + ".");
                return null;
            }
            String className = parser.getAttributeValue(/* namespace= */ null, ATTR_CLASS_NAME);
            ComponentName componentName = className == null
                    ? null :  new ComponentName(packageName, className);
            Set<String> authorities = Set.of(authoritiesStr.split(ATTR_AUTHORITIES_SEPARATOR));
            // TODO(b/281697976): load active admin
            return new EnforcingAdmin(packageName, componentName, authorities, userId, null);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("EnforcingAdmin { mPackageName= ");
        sb.append(mPackageName);
        if (mComponentName != null) {
            sb.append(", mComponentName= ");
            sb.append(mComponentName);
        }
        if (mAuthorities != null) {
            sb.append(", mAuthorities= ");
            sb.append(mAuthorities);
        }
        sb.append(", mUserId= ");
        sb.append(mUserId);
        sb.append(", mIsRoleAuthority= ");
        sb.append(mIsRoleAuthority);
        sb.append(" }");
        return sb.toString();
    }
}
