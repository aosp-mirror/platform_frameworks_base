/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.window;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;

import android.annotation.CallSuper;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityThread;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.IWindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.window.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Represents a setting request that can be applied as part of a batch to avoid multiple
 * configuration updates.
 *
 * @hide
 */
public abstract class ConfigurationChangeSetting implements Parcelable {
    /* The type of the setting for creating from a parcel. */
    public static final int SETTING_TYPE_UNKNOWN = -1;
    public static final int SETTING_TYPE_DISPLAY_DENSITY = 0;
    public static final int SETTING_TYPE_FONT_SCALE = 1;

    @IntDef(prefix = {"SETTING_TYPE_"}, value = {
            SETTING_TYPE_UNKNOWN,
            SETTING_TYPE_DISPLAY_DENSITY,
            SETTING_TYPE_FONT_SCALE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SettingType {
    }

    @SettingType
    private final int mSettingType;

    private ConfigurationChangeSetting(@SettingType int settingType) {
        if (!Flags.condenseConfigurationChangeForSimpleMode()) {
            throw new IllegalStateException(
                    "ConfigurationChangeSetting cannot be instantiated because the "
                            + "condenseConfigurationChangeForSimpleMode flag is not enabled. "
                            + "Please ensure this flag is enabled.");
        }
        mSettingType = settingType;
    }

    @CallSuper
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mSettingType);
    }

    public static final Creator<ConfigurationChangeSetting> CREATOR = new CreatorImpl();

    /**
     * Implementation of the {@link Parcelable.Creator} for {@link ConfigurationChangeSetting}.
     *
     * <p>Creates {@link ConfigurationChangeSetting} objects from a {@link Parcel}, handling
     * system/client processes. System process delegates creation to the server-side implementation.
     *
     * @hide
     */
    @VisibleForTesting(visibility = PRIVATE)
    public static class CreatorImpl implements Creator<ConfigurationChangeSetting> {
        private final boolean mIsSystem;

        private CreatorImpl() {
            this(ActivityThread.isSystem());
        }

        @VisibleForTesting(visibility = PRIVATE)
        public CreatorImpl(boolean isSystem) {
            mIsSystem = isSystem;
        }

        @Override
        public ConfigurationChangeSetting createFromParcel(@NonNull Parcel in) {
            final int settingType = in.readInt();
            if (mIsSystem) {
                return LocalServices.getService(ConfigurationChangeSettingInternal.class)
                        .createImplFromParcel(settingType, in);
            }
            switch (settingType) {
                case SETTING_TYPE_DISPLAY_DENSITY:
                    return DensitySetting.CREATOR.createFromParcel(in);
                case SETTING_TYPE_FONT_SCALE:
                    return FontScaleSetting.CREATOR.createFromParcel(in);
                default:
                    throw new IllegalArgumentException("Unknown setting type " + settingType);
            }
        }

        @Override
        public ConfigurationChangeSetting[] newArray(int size) {
            return new ConfigurationChangeSetting[size];
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Applies the specific setting request to the system.
     *
     * <p>This method should handle the logic for modifying system settings or making other
     * adjustments to achieve the intended configuration change. It is called within the
     * context of a batch update, where multiple {@link ConfigurationChangeSetting} instances
     * might be applied sequentially.
     *
     * @param userId The user for which to apply the setting
     * @hide Only for use within the system server.
     * @see IWindowManager#setConfigurationChangeSettingsForUser(ConfigurationChangeSetting[], int)
     */
    public void apply(@UserIdInt int userId) {
        // no-op in client process, the apply will be executed in server side.
    }

    /**
     * Interface for server side implementation of {@link ConfigurationChangeSetting}.
     *
     * @hide Only for use within the system server.
     */
    public interface ConfigurationChangeSettingInternal {
        /**
         * Create server side {@link ConfigurationChangeSetting} implementation from parcel.
         *
         * @param settingType the type of {@link ConfigurationChangeSetting}.
         * @param in          the {@link Parcel} to read data from.
         * @return server side {@link ConfigurationChangeSetting} implementation.
         */
        @NonNull
        ConfigurationChangeSetting createImplFromParcel(@SettingType int settingType,
                @NonNull Parcel in);
    }

    /**
     * Represents a request to change the display density.
     *
     * @hide
     */
    public static class DensitySetting extends ConfigurationChangeSetting {
        protected final int mDisplayId;
        protected final int mDensity;

        /**
         * Constructs a {@link DensitySetting}.
         *
         * @param density The new display density.
         * @hide
         */
        public DensitySetting(int displayId, int density) {
            super(SETTING_TYPE_DISPLAY_DENSITY);
            mDisplayId = displayId;
            mDensity = density;
        }

        protected DensitySetting(@NonNull Parcel in) {
            this(in.readInt(), in.readInt());
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeInt(mDisplayId);
            dest.writeInt(mDensity);
        }

        public static final Creator<DensitySetting> CREATOR = new Creator<>() {
            @Override
            public DensitySetting createFromParcel(@NonNull Parcel in) {
                return new DensitySetting(in);
            }

            @Override
            public DensitySetting[] newArray(int size) {
                return new DensitySetting[size];
            }
        };

        @Override
        public boolean equals(@Nullable Object obj) {
            if (!(obj instanceof DensitySetting other)) {
                return false;
            }
            return mDisplayId == other.mDisplayId && mDensity == other.mDensity;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mDisplayId, mDensity);
        }
    }

    /**
     * Represents a request to change the font scale.
     *
     * @hide
     */
    public static class FontScaleSetting extends ConfigurationChangeSetting {
        protected final float mFontScaleFactor;

        /**
         * Constructs a {@code FontScaleSetting}.
         *
         * @param fontScaleFactor The new font scale factor.
         * @hide
         */
        public FontScaleSetting(float fontScaleFactor) {
            super(SETTING_TYPE_FONT_SCALE);
            mFontScaleFactor = fontScaleFactor;
        }

        protected FontScaleSetting(@NonNull Parcel in) {
            this(in.readFloat());
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeFloat(mFontScaleFactor);
        }

        public static final Creator<FontScaleSetting> CREATOR = new Creator<>() {
            @Override
            public FontScaleSetting createFromParcel(@NonNull Parcel in) {
                return new FontScaleSetting(in);
            }

            @Override
            public FontScaleSetting[] newArray(int size) {
                return new FontScaleSetting[size];
            }
        };

        @Override
        public boolean equals(@Nullable Object obj) {
            if (!(obj instanceof FontScaleSetting other)) {
                return false;
            }
            return Float.compare(mFontScaleFactor, other.mFontScaleFactor) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mFontScaleFactor);
        }
    }
}
