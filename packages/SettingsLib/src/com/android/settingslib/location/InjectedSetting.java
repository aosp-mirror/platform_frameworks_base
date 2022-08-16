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

package com.android.settingslib.location;

import android.content.Intent;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.Immutable;

import java.util.Objects;

/**
 * Specifies a setting that is being injected into Settings &gt; Location &gt; Location services.
 *
 * @see android.location.SettingInjectorService
 */
@Immutable
public class InjectedSetting {

    /**
     * Package for the subclass of {@link android.location.SettingInjectorService} and for the
     * settings activity.
     */
    public final String packageName;

    /**
     * Class name for the subclass of {@link android.location.SettingInjectorService} that
     * specifies dynamic values for the location setting.
     */
    public final String className;

    /**
     * The {@link androidx.preference.Preference#getTitle()} value.
     */
    public final String title;

    /**
     * The {@link androidx.preference.Preference#getIcon()} value.
     */
    public final int iconId;

    /**
     * The user/profile associated with this setting (e.g. managed profile)
     */
    public final UserHandle mUserHandle;

    /**
     * The activity to launch to allow the user to modify the settings value. Assumed to be in the
     * {@link #packageName} package.
     */
    public final String settingsActivity;

    /**
     * The user restriction associated with this setting.
     */
    public final String userRestriction;

    private InjectedSetting(Builder builder) {
        this.packageName = builder.mPackageName;
        this.className = builder.mClassName;
        this.title = builder.mTitle;
        this.iconId = builder.mIconId;
        this.mUserHandle = builder.mUserHandle;
        this.settingsActivity = builder.mSettingsActivity;
        this.userRestriction = builder.mUserRestriction;
    }

    @Override
    public String toString() {
        return "InjectedSetting{" +
                "mPackageName='" + packageName + '\'' +
                ", mClassName='" + className + '\'' +
                ", label=" + title +
                ", iconId=" + iconId +
                ", userId=" + mUserHandle.getIdentifier() +
                ", settingsActivity='" + settingsActivity + '\'' +
                ", userRestriction='" + userRestriction +
                '}';
    }

    /**
     * Returns the intent to start the {@link #className} service.
     */
    public Intent getServiceIntent() {
        Intent intent = new Intent();
        intent.setClassName(packageName, className);
        return intent;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InjectedSetting)) return false;

        InjectedSetting that = (InjectedSetting) o;

        return Objects.equals(packageName, that.packageName)
                && Objects.equals(className, that.className)
                && Objects.equals(title, that.title)
                && Objects.equals(iconId, that.iconId)
                && Objects.equals(mUserHandle, that.mUserHandle)
                && Objects.equals(settingsActivity, that.settingsActivity)
                && Objects.equals(userRestriction, that.userRestriction);
    }

    @Override
    public int hashCode() {
        int result = packageName.hashCode();
        result = 31 * result + className.hashCode();
        result = 31 * result + title.hashCode();
        result = 31 * result + iconId;
        result = 31 * result + (mUserHandle == null ? 0 : mUserHandle.hashCode());
        result = 31 * result + settingsActivity.hashCode();
        result = 31 * result + (userRestriction == null ? 0 : userRestriction.hashCode());
        return result;
    }

    public static class Builder {
        private String mPackageName;
        private String mClassName;
        private String mTitle;
        private int mIconId;
        private UserHandle mUserHandle;
        private String mSettingsActivity;
        private String mUserRestriction;

        public Builder setPackageName(String packageName) {
            mPackageName = packageName;
            return this;
        }

        public Builder setClassName(String className) {
            mClassName = className;
            return this;
        }

        public Builder setTitle(String title) {
            mTitle = title;
            return this;
        }

        public Builder setIconId(int iconId) {
            mIconId = iconId;
            return this;
        }

        public Builder setUserHandle(UserHandle userHandle) {
            mUserHandle = userHandle;
            return this;
        }

        public Builder setSettingsActivity(String settingsActivity) {
            mSettingsActivity = settingsActivity;
            return this;
        }

        public Builder setUserRestriction(String userRestriction) {
            mUserRestriction = userRestriction;
            return this;
        }

        public InjectedSetting build() {
            if (mPackageName == null || mClassName == null || TextUtils.isEmpty(mTitle)
                    || TextUtils.isEmpty(mSettingsActivity)) {
                if (Log.isLoggable(SettingsInjector.TAG, Log.WARN)) {
                    Log.w(SettingsInjector.TAG, "Illegal setting specification: package="
                            + mPackageName + ", class=" + mClassName
                            + ", title=" + mTitle + ", settingsActivity=" + mSettingsActivity);
                }
                return null;
            }
            return new InjectedSetting(this);
        }
    }
}
