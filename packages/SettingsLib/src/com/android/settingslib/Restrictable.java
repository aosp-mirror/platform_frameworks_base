/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.os.UserHandle;
/**
 * A collection of API making a Preference "restrictable"
 */
public interface Restrictable {
    /**
     * Returns RestrictedPreferenceHelper
     * @return
     */
    RestrictedPreferenceHelper getHelper();
    /**
     * call preference notifyChanged()
     */
    void notifyPreferenceChanged();
    /**
     * Set if show restriction message in Preference summary or not.
     * @param useSummary.
     */
    default void useAdminDisabledSummary(boolean useSummary) {
        getHelper().useAdminDisabledSummary(useSummary);
    }
    /**
     * Set the user restriction and disable this preference.
     *
     * @param userRestriction constant from {@link android.os.UserManager}
     */
    default void checkRestrictionAndSetDisabled(String userRestriction) {
        getHelper().checkRestrictionAndSetDisabled(userRestriction, UserHandle.myUserId());
    }
    /**
     * Set the user restriction and disable this preference for the given user.
     *
     * @param userRestriction constant from {@link android.os.UserManager}
     * @param userId          user to check the restriction for.
     */
    default void checkRestrictionAndSetDisabled(String userRestriction, int userId) {
        getHelper().checkRestrictionAndSetDisabled(userRestriction, userId);
    }
    /**
     * Disable preference based on the enforce admin.
     *
     * @param admin details of the admin who enforced the restriction. If it is {@code null}, then
     *              this preference will be enabled. Otherwise, it will be disabled.
     */
    default void setDisabledByAdmin(RestrictedLockUtils.EnforcedAdmin admin) {
        if (getHelper().setDisabledByAdmin(admin)) {
            notifyPreferenceChanged();
        }
    }
    /**
     * Check whether this preference is disabled by admin.
     *
     * @return true if this preference is disabled by admin.
     */
    default boolean isDisabledByAdmin() {
        return getHelper().isDisabledByAdmin();
    }
}
