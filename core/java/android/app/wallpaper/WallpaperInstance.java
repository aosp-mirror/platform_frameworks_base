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

package android.app.wallpaper;

import static android.app.Flags.FLAG_LIVE_WALLPAPER_CONTENT_HANDLING;

import android.annotation.FlaggedApi;
import android.app.WallpaperInfo;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * Describes a wallpaper that has been set as a current wallpaper.
 *
 * <p>This class is used by {@link android.app.WallpaperManager} to store information about a
 * wallpaper that is currently in use. Because it has been set as an active wallpaper it offers
 * some guarantees that {@link WallpaperDescription} does not:
 * <ul>
 *     <li>It contains the {@link WallpaperInfo} corresponding to the
 *     {@link android.content.ComponentName}</li> specified in the description
 *     <li>{@link #getId()} is guaranteed to be non-null</li>
 * </ul>
 * </p>
 */
@FlaggedApi(FLAG_LIVE_WALLPAPER_CONTENT_HANDLING)
public final class WallpaperInstance implements Parcelable {
    private static final String DEFAULT_ID = "default_id";
    @Nullable private final WallpaperInfo mInfo;
    @NonNull private final WallpaperDescription mDescription;
    @Nullable private final String mIdOverride;

    /**
     * Create a WallpaperInstance for the wallpaper given by {@link WallpaperDescription}.
     *
     * @param info the live wallpaper info for this wallpaper, or null if static
     * @param description description of the wallpaper for this instance
     */
    public WallpaperInstance(@Nullable WallpaperInfo info,
            @NonNull WallpaperDescription description) {
        this(info, description, null);
    }

    /**
     * Create a WallpaperInstance for the wallpaper given by {@link WallpaperDescription}.
     *
     * This is provided as an escape hatch to provide an explicit id for cases where the
     * description id and {@link WallpaperInfo} are both {@code null}.
     *
     * @param info the live wallpaper info for this wallpaper, or null if static
     * @param description description of the wallpaper for this instance
     * @param idOverride optional id to override the value given in the description
     *
     * @hide
     */
    public WallpaperInstance(@Nullable WallpaperInfo info,
            @NonNull WallpaperDescription description, @Nullable String idOverride) {
        mInfo = info;
        mDescription = description;
        mIdOverride = idOverride;
    }

    /** @return the live wallpaper info, or {@code null} if static */
    @Nullable public WallpaperInfo getInfo() {
        return mInfo;
    }

    /**
     * See {@link WallpaperDescription.Builder#getId()} for rules about id uniqueness.
     *
     * @return the ID of the wallpaper instance if given by the wallpaper description, otherwise a
     * default value
     */
    @NonNull public String getId() {
        if (mIdOverride != null) {
            return mIdOverride;
        } else if (mDescription.getId() != null) {
            return mDescription.getId();
        } else if (mInfo != null) {
            return mInfo.getComponent().flattenToString();
        } else {
            return DEFAULT_ID;
        }
    }

    /** @return the description for this wallpaper */
    @NonNull public WallpaperDescription getDescription() {
        return mDescription;
    }

    ////// Comparison overrides

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WallpaperInstance that)) return false;
        if (mInfo == null) {
            return that.mInfo == null && Objects.equals(getId(), that.getId());
        } else {
            return that.mInfo != null
                    && Objects.equals(mInfo.getComponent(), that.mInfo.getComponent())
                    && Objects.equals(getId(), that.getId());
        }
    }

    @Override
    public int hashCode() {
        return (mInfo != null) ? Objects.hash(mInfo.getComponent(), getId()) : Objects.hash(
                getId());
    }

    ////// Parcelable implementation

    WallpaperInstance(@NonNull Parcel in) {
        mInfo = in.readTypedObject(WallpaperInfo.CREATOR);
        mDescription = WallpaperDescription.CREATOR.createFromParcel(in);
        mIdOverride = in.readString8();
    }

    @NonNull
    public static final Creator<WallpaperInstance> CREATOR = new Creator<>() {
        @Override
        public WallpaperInstance createFromParcel(Parcel in) {
            return new WallpaperInstance(in);
        }

        @Override
        public WallpaperInstance[] newArray(int size) {
            return new WallpaperInstance[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mInfo, flags);
        mDescription.writeToParcel(dest, flags);
        dest.writeString8(mIdOverride);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
