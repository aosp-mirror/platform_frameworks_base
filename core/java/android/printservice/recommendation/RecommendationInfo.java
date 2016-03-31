/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.printservice.recommendation;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.printservice.PrintService;
import com.android.internal.util.Preconditions;

/**
 * A recommendation to install a {@link PrintService print service}.
 *
 * @hide
 */
@SystemApi
public final class RecommendationInfo implements Parcelable {
    /** Package name of the print service. */
    private @NonNull final CharSequence mPackageName;

    /** Display name of the print service. */
    private @NonNull final CharSequence mName;

    /** Number of printers the print service would discover if installed. */
    private @IntRange(from = 0) final int mNumDiscoveredPrinters;

    /** If the service detects printer from multiple vendors. */
    private final boolean mRecommendsMultiVendorService;

    /**
     * Create a new recommendation.
     *
     * @param packageName                  Package name of the print service
     * @param name                         Display name of the print service
     * @param numDiscoveredPrinters        Number of printers the print service would discover if
     *                                     installed
     * @param recommendsMultiVendorService If the service detects printer from multiple vendor
     */
    public RecommendationInfo(@NonNull CharSequence packageName, @NonNull CharSequence name,
            @IntRange(from = 0) int numDiscoveredPrinters, boolean recommendsMultiVendorService) {
        mPackageName = Preconditions.checkStringNotEmpty(packageName);
        mName = Preconditions.checkStringNotEmpty(name);
        mNumDiscoveredPrinters = Preconditions.checkArgumentNonnegative(numDiscoveredPrinters);
        mRecommendsMultiVendorService = recommendsMultiVendorService;
    }

    /**
     * Create a new recommendation from a parcel.
     *
     * @param parcel The parcel containing the data
     *
     * @see #CREATOR
     */
    private RecommendationInfo(@NonNull Parcel parcel) {
        this(parcel.readCharSequence(), parcel.readCharSequence(), parcel.readInt(),
                parcel.readByte() != 0);
    }

    /**
     * @return The package name the recommendations recommends.
     */
    public CharSequence getPackageName() {
        return mPackageName;
    }

    /**
     * @return Whether the recommended print service detects printers of more than one vendor.
     */
    public boolean recommendsMultiVendorService() {
        return mRecommendsMultiVendorService;
    }

    /**
     * @return The number of printer the print service would detect.
     */
    public int getNumDiscoveredPrinters() {
        return mNumDiscoveredPrinters;
    }

    /**
     * @return The name of the recommended print service.
     */
    public CharSequence getName() {
        return mName;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeCharSequence(mPackageName);
        dest.writeCharSequence(mName);
        dest.writeInt(mNumDiscoveredPrinters);
        dest.writeByte((byte) (mRecommendsMultiVendorService ? 1 : 0));
    }

    /**
     * Utility class used to create new print service recommendation objects from parcels.
     *
     * @see #RecommendationInfo(Parcel)
     */
    public static final Creator<RecommendationInfo> CREATOR =
            new Creator<RecommendationInfo>() {
                @Override
                public RecommendationInfo createFromParcel(Parcel in) {
                    return new RecommendationInfo(in);
                }

                @Override
                public RecommendationInfo[] newArray(int size) {
                    return new RecommendationInfo[size];
                }
    };
}
