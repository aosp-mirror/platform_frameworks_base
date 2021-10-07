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

package com.android.settingslib;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.Objects;

/**
 * Utility class to host methods usable in adding a restricted padlock icon and showing admin
 * support message dialog.
 */
public class RestrictedLockUtils {
    /**
     * Get EnforcedAdmin from DevicePolicyManager
     */
    @RequiresApi(Build.VERSION_CODES.M)
    public static EnforcedAdmin getProfileOrDeviceOwner(Context context, UserHandle user) {
        return getProfileOrDeviceOwner(context, null, user);
    }

    /**
     * Get EnforcedAdmin from DevicePolicyManager
     */
    @RequiresApi(Build.VERSION_CODES.M)
    public static EnforcedAdmin getProfileOrDeviceOwner(
            Context context, String enforcedRestriction, UserHandle user) {
        if (user == null) {
            return null;
        }
        final DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }

        Context userContext;
        try {
            userContext = context.createPackageContextAsUser(context.getPackageName(), 0, user);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(e);
        }

        ComponentName adminComponent = userContext.getSystemService(
                DevicePolicyManager.class).getProfileOwner();
        if (adminComponent != null) {
            return new EnforcedAdmin(adminComponent, enforcedRestriction, user);
        }
        if (Objects.equals(dpm.getDeviceOwnerUser(), user)) {
            adminComponent = dpm.getDeviceOwnerComponentOnAnyUser();
            if (adminComponent != null) {
                return new EnforcedAdmin(adminComponent, enforcedRestriction, user);
            }
        }
        return null;
    }

    /**
     * Send the intent to trigger the {@code android.settings.ShowAdminSupportDetailsDialog}.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    public static void sendShowAdminSupportDetailsIntent(Context context, EnforcedAdmin admin) {
        final Intent intent = getShowAdminSupportDetailsIntent(context, admin);
        int targetUserId = UserHandle.myUserId();
        if (admin != null) {
            if (admin.user != null
                    && isCurrentUserOrProfile(context, admin.user.getIdentifier())) {
                targetUserId = admin.user.getIdentifier();
            }
            intent.putExtra(DevicePolicyManager.EXTRA_RESTRICTION, admin.enforcedRestriction);
        }
        context.startActivityAsUser(intent, UserHandle.of(targetUserId));
    }

    public static Intent getShowAdminSupportDetailsIntent(Context context, EnforcedAdmin admin) {
        final Intent intent = new Intent(Settings.ACTION_SHOW_ADMIN_SUPPORT_DETAILS);
        if (admin != null) {
            if (admin.component != null) {
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin.component);
            }
            intent.putExtra(Intent.EXTRA_USER, admin.user);
        }
        return intent;
    }

    /**
     * Check if current user is profile or not
     */
    @RequiresApi(Build.VERSION_CODES.M)
    public static boolean isCurrentUserOrProfile(Context context, int userId) {
        UserManager um = context.getSystemService(UserManager.class);
        return um.getUserProfiles().contains(UserHandle.of(userId));
    }

    public static class EnforcedAdmin {
        @Nullable
        public ComponentName component = null;
        /**
         * The restriction enforced by admin. It could be any user restriction or policy like
         * {@link DevicePolicyManager#POLICY_DISABLE_CAMERA}.
         */
        @Nullable
        public String enforcedRestriction = null;
        @Nullable
        public UserHandle user = null;

        // We use this to represent the case where a policy is enforced by multiple admins.
        public final static EnforcedAdmin MULTIPLE_ENFORCED_ADMIN = new EnforcedAdmin();

        public static EnforcedAdmin createDefaultEnforcedAdminWithRestriction(
                String enforcedRestriction) {
            EnforcedAdmin enforcedAdmin = new EnforcedAdmin();
            enforcedAdmin.enforcedRestriction = enforcedRestriction;
            return enforcedAdmin;
        }

        public EnforcedAdmin(ComponentName component, UserHandle user) {
            this.component = component;
            this.user = user;
        }

        public EnforcedAdmin(ComponentName component, String enforcedRestriction, UserHandle user) {
            this.component = component;
            this.enforcedRestriction = enforcedRestriction;
            this.user = user;
        }

        public EnforcedAdmin(EnforcedAdmin other) {
            if (other == null) {
                throw new IllegalArgumentException();
            }
            this.component = other.component;
            this.enforcedRestriction = other.enforcedRestriction;
            this.user = other.user;
        }

        public EnforcedAdmin() {
        }

        /**
         * Combines two {@link EnforcedAdmin} into one: if one of them is null, then just return
         * the other. If both of them are the same, then return that. Otherwise return the symbolic
         * {@link #MULTIPLE_ENFORCED_ADMIN}
         */
        public static EnforcedAdmin combine(EnforcedAdmin admin1, EnforcedAdmin admin2) {
            if (admin1 == null) {
                return admin2;
            }
            if (admin2 == null) {
                return admin1;
            }
            if (admin1.equals(admin2)) {
                return admin1;
            }
            if (!admin1.enforcedRestriction.equals(admin2.enforcedRestriction)) {
                throw new IllegalArgumentException(
                        "Admins with different restriction cannot be combined");
            }
            return MULTIPLE_ENFORCED_ADMIN;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            EnforcedAdmin that = (EnforcedAdmin) o;
            return Objects.equals(user, that.user) &&
                    Objects.equals(component, that.component) &&
                    Objects.equals(enforcedRestriction, that.enforcedRestriction);
        }

        @Override
        public int hashCode() {
            return Objects.hash(component, enforcedRestriction, user);
        }

        @Override
        public String toString() {
            return "EnforcedAdmin{" +
                    "component=" + component +
                    ", enforcedRestriction='" + enforcedRestriction +
                    ", user=" + user +
                    '}';
        }
    }
}
