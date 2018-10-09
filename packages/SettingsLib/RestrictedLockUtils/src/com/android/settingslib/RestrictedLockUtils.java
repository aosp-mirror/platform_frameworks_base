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
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;

import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * Utility class to host methods usable in adding a restricted padlock icon and showing admin
 * support message dialog.
 */
public class RestrictedLockUtils {
    public static EnforcedAdmin getProfileOrDeviceOwner(Context context, int userId) {
        return getProfileOrDeviceOwner(context, null, userId);
    }

    public static EnforcedAdmin getProfileOrDeviceOwner(
            Context context, String enforcedRestriction, int userId) {
        if (userId == UserHandle.USER_NULL) {
            return null;
        }
        final DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }
        ComponentName adminComponent = dpm.getProfileOwnerAsUser(userId);
        if (adminComponent != null) {
            return new EnforcedAdmin(adminComponent, enforcedRestriction, userId);
        }
        if (dpm.getDeviceOwnerUserId() == userId) {
            adminComponent = dpm.getDeviceOwnerComponentOnAnyUser();
            if (adminComponent != null) {
                return new EnforcedAdmin(adminComponent, enforcedRestriction, userId);
            }
        }
        return null;
    }

    /**
     * Send the intent to trigger the {@code android.settings.ShowAdminSupportDetailsDialog}.
     */
    public static void sendShowAdminSupportDetailsIntent(Context context, EnforcedAdmin admin) {
        final Intent intent = getShowAdminSupportDetailsIntent(context, admin);
        int targetUserId = UserHandle.myUserId();
        if (admin != null && admin.userId != UserHandle.USER_NULL
                && isCurrentUserOrProfile(context, admin.userId)) {
            targetUserId = admin.userId;
        }
        intent.putExtra(DevicePolicyManager.EXTRA_RESTRICTION, admin.enforcedRestriction);
        context.startActivityAsUser(intent, UserHandle.of(targetUserId));
    }

    public static Intent getShowAdminSupportDetailsIntent(Context context, EnforcedAdmin admin) {
        final Intent intent = new Intent(Settings.ACTION_SHOW_ADMIN_SUPPORT_DETAILS);
        if (admin != null) {
            if (admin.component != null) {
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin.component);
            }
            int adminUserId = UserHandle.myUserId();
            if (admin.userId != UserHandle.USER_NULL) {
                adminUserId = admin.userId;
            }
            intent.putExtra(Intent.EXTRA_USER_ID, adminUserId);
        }
        return intent;
    }

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
        public int userId = UserHandle.USER_NULL;

        // We use this to represent the case where a policy is enforced by multiple admins.
        public final static EnforcedAdmin MULTIPLE_ENFORCED_ADMIN = new EnforcedAdmin();

        public static EnforcedAdmin createDefaultEnforcedAdminWithRestriction(
                String enforcedRestriction) {
            EnforcedAdmin enforcedAdmin = new EnforcedAdmin();
            enforcedAdmin.enforcedRestriction = enforcedRestriction;
            return enforcedAdmin;
        }

        public EnforcedAdmin(ComponentName component, int userId) {
            this.component = component;
            this.userId = userId;
        }

        public EnforcedAdmin(ComponentName component, String enforcedRestriction, int userId) {
            this.component = component;
            this.enforcedRestriction = enforcedRestriction;
            this.userId = userId;
        }

        public EnforcedAdmin(EnforcedAdmin other) {
            if (other == null) {
                throw new IllegalArgumentException();
            }
            this.component = other.component;
            this.enforcedRestriction = other.enforcedRestriction;
            this.userId = other.userId;
        }

        public EnforcedAdmin() {
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            EnforcedAdmin that = (EnforcedAdmin) o;
            return userId == that.userId &&
                    Objects.equals(component, that.component) &&
                    Objects.equals(enforcedRestriction, that.enforcedRestriction);
        }

        @Override
        public int hashCode() {
            return Objects.hash(component, enforcedRestriction, userId);
        }

        @Override
        public String toString() {
            return "EnforcedAdmin{" +
                    "component=" + component +
                    ", enforcedRestriction='" + enforcedRestriction +
                    ", userId=" + userId +
                    '}';
        }
    }
}
