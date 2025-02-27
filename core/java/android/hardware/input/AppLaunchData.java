/*
 * Copyright 2024 The Android Open Source Project
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

package android.hardware.input;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.text.TextUtils;

import java.util.Objects;

/**
 * Provides data for launching an application.
 *
 * @hide
 */
public interface AppLaunchData {

    /** Creates AppLaunchData for the provided category */
    @NonNull
    static AppLaunchData createLaunchDataForCategory(@NonNull String category) {
        return new CategoryData(category);
    }

    /** Creates AppLaunchData for the provided role */
    @NonNull
    static AppLaunchData createLaunchDataForRole(@NonNull String role) {
        return new RoleData(role);
    }

    /** Creates AppLaunchData for the target package name and class name */
    @NonNull
    static AppLaunchData createLaunchDataForComponent(@NonNull String packageName,
            @NonNull String className) {
        return new ComponentData(packageName, className);
    }

    @Nullable
    static AppLaunchData createLaunchData(@Nullable String category, @Nullable String role,
            @Nullable String packageName, @Nullable String className) {
        if (!TextUtils.isEmpty(category)) {
            return new CategoryData(category);
        }
        if (!TextUtils.isEmpty(role)) {
            return new RoleData(role);
        }
        if (!TextUtils.isEmpty(packageName) && !TextUtils.isEmpty(className)) {
            return new ComponentData(packageName, className);
        }
        return null;
    }

    /** Intent category based app launch data */
    class CategoryData implements AppLaunchData {
        @NonNull
        private final String mCategory;
        public CategoryData(@NonNull String category) {
            mCategory = category;
        }

        @NonNull
        public String getCategory() {
            return mCategory;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CategoryData that)) return false;
            return Objects.equals(mCategory, that.mCategory);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mCategory);
        }

        @Override
        public String toString() {
            return "CategoryData{" +
                    "mCategory='" + mCategory + '\'' +
                    '}';
        }
    }

    /** Role based app launch data */
    class RoleData implements AppLaunchData {
        @NonNull
        private final String mRole;
        public RoleData(@NonNull String role) {
            mRole = role;
        }

        @NonNull
        public String getRole() {
            return mRole;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RoleData roleData)) return false;
            return Objects.equals(mRole, roleData.mRole);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mRole);
        }

        @Override
        public String toString() {
            return "RoleData{" +
                    "mRole='" + mRole + '\'' +
                    '}';
        }
    }

    /** Target application launch data */
    class ComponentData implements AppLaunchData {
        @NonNull
        private final String mPackageName;

        @NonNull
        private final String mClassName;

        public ComponentData(@NonNull String packageName, @NonNull String className) {
            mPackageName = packageName;
            mClassName = className;
        }

        @NonNull
        public String getPackageName() {
            return mPackageName;
        }

        @NonNull
        public String getClassName() {
            return mClassName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ComponentData that)) return false;
            return Objects.equals(mPackageName, that.mPackageName) && Objects.equals(
                    mClassName, that.mClassName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mPackageName, mClassName);
        }

        @Override
        public String toString() {
            return "ComponentData{" +
                    "mPackageName='" + mPackageName + '\'' +
                    ", mClassName='" + mClassName + '\'' +
                    '}';
        }
    }
}
