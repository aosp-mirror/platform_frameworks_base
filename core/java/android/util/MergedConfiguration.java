/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package android.util;

import android.annotation.NonNull;
import android.content.res.Configuration;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.PrintWriter;

/**
 * Container that holds global and override config and their merge product.
 * Merged configuration updates automatically whenever global or override configs are updated via
 * setters.
 *
 * {@hide}
 */
public class MergedConfiguration implements Parcelable {

    private Configuration mGlobalConfig = new Configuration();
    private Configuration mOverrideConfig = new Configuration();
    private Configuration mMergedConfig = new Configuration();

    public MergedConfiguration() {
    }

    public MergedConfiguration(Configuration globalConfig, Configuration overrideConfig) {
        setConfiguration(globalConfig, overrideConfig);
    }

    public MergedConfiguration(Configuration globalConfig) {
        setGlobalConfiguration(globalConfig);
    }

    public MergedConfiguration(MergedConfiguration mergedConfiguration) {
        setConfiguration(mergedConfiguration.getGlobalConfiguration(),
                mergedConfiguration.getOverrideConfiguration());
    }

    private MergedConfiguration(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mGlobalConfig, flags);
        dest.writeParcelable(mOverrideConfig, flags);
        dest.writeParcelable(mMergedConfig, flags);
    }

    public void readFromParcel(Parcel source) {
        mGlobalConfig = source.readParcelable(Configuration.class.getClassLoader());
        mOverrideConfig = source.readParcelable(Configuration.class.getClassLoader());
        mMergedConfig = source.readParcelable(Configuration.class.getClassLoader());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Creator<MergedConfiguration> CREATOR = new Creator<MergedConfiguration>() {
        @Override
        public MergedConfiguration createFromParcel(Parcel in) {
            return new MergedConfiguration(in);
        }

        @Override
        public MergedConfiguration[] newArray(int size) {
            return new MergedConfiguration[size];
        }
    };

    /**
     * Update global and override configurations.
     * Merged configuration will automatically be updated.
     * @param globalConfig New global configuration.
     * @param overrideConfig New override configuration.
     */
    public void setConfiguration(Configuration globalConfig, Configuration overrideConfig) {
        mGlobalConfig.setTo(globalConfig);
        mOverrideConfig.setTo(overrideConfig);
        updateMergedConfig();
    }

    /**
     * Update global configurations.
     * Merged configuration will automatically be updated.
     * @param globalConfig New global configuration.
     */
    public void setGlobalConfiguration(Configuration globalConfig) {
        mGlobalConfig.setTo(globalConfig);
        updateMergedConfig();
    }

    /**
     * Update override configurations.
     * Merged configuration will automatically be updated.
     * @param overrideConfig New override configuration.
     */
    public void setOverrideConfiguration(Configuration overrideConfig) {
        mOverrideConfig.setTo(overrideConfig);
        updateMergedConfig();
    }

    public void setTo(MergedConfiguration config) {
        setConfiguration(config.mGlobalConfig, config.mOverrideConfig);
    }

    public void unset() {
        mGlobalConfig.unset();
        mOverrideConfig.unset();
        updateMergedConfig();
    }

    /**
     * @return Stored global configuration value.
     */
    @NonNull
    public Configuration getGlobalConfiguration() {
        return mGlobalConfig;
    }

    /**
     * @return Stored override configuration value.
     */
    public Configuration getOverrideConfiguration() {
        return mOverrideConfig;
    }

    /**
     * @return Stored merged configuration value.
     */
    public Configuration getMergedConfiguration() {
        return mMergedConfig;
    }

    /** Update merged config when global or override config changes. */
    private void updateMergedConfig() {
        mMergedConfig.setTo(mGlobalConfig);
        mMergedConfig.updateFrom(mOverrideConfig);
    }

    @Override
    public String toString() {
        return "{mGlobalConfig=" + mGlobalConfig + " mOverrideConfig=" + mOverrideConfig + "}";
    }

    @Override
    public int hashCode() {
        return mMergedConfig.hashCode();
    }

    @Override
    public boolean equals(Object that) {
        if (!(that instanceof MergedConfiguration)) {
            return false;
        }

        if (that == this) return true;
        return mMergedConfig.equals(((MergedConfiguration) that).mMergedConfig);
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "mGlobalConfig=" + mGlobalConfig);
        pw.println(prefix + "mOverrideConfig=" + mOverrideConfig);
    }
}
