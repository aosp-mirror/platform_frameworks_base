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
package android.service.autofill;

import static android.view.autofill.Helper.sDebug;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;

/**
 * Holds both a generic and package-specific userData used for
 * <a href="AutofillService.html#FieldClassification">field classification</a>.
 *
 * @hide
 */
@TestApi
public final class CompositeUserData implements FieldClassificationUserData, Parcelable {

    private final UserData mGenericUserData;
    private final UserData mPackageUserData;

    private final String[] mCategories;
    private final String[] mValues;

    public CompositeUserData(@Nullable UserData genericUserData,
            @NonNull UserData packageUserData) {
        mGenericUserData = genericUserData;
        mPackageUserData = packageUserData;

        final String[] packageCategoryIds = mPackageUserData.getCategoryIds();
        final String[] packageValues = mPackageUserData.getValues();

        final ArrayList<String> categoryIds = new ArrayList<>(packageCategoryIds.length);
        final ArrayList<String> values = new ArrayList<>(packageValues.length);

        Collections.addAll(categoryIds, packageCategoryIds);
        Collections.addAll(values, packageValues);

        if (mGenericUserData != null) {
            final String[] genericCategoryIds = mGenericUserData.getCategoryIds();
            final String[] genericValues = mGenericUserData.getValues();
            final int size = mGenericUserData.getCategoryIds().length;
            for (int i = 0; i < size; i++) {
                if (!categoryIds.contains(genericCategoryIds[i])) {
                    categoryIds.add(genericCategoryIds[i]);
                    values.add(genericValues[i]);
                }
            }
        }

        mCategories = new String[categoryIds.size()];
        categoryIds.toArray(mCategories);
        mValues = new String[values.size()];
        values.toArray(mValues);
    }

    @Nullable
    @Override
    public String getFieldClassificationAlgorithm() {
        final String packageDefaultAlgo = mPackageUserData.getFieldClassificationAlgorithm();
        if (packageDefaultAlgo != null) {
            return packageDefaultAlgo;
        } else {
            return mGenericUserData == null ? null :
                    mGenericUserData.getFieldClassificationAlgorithm();
        }
    }

    @Override
    public Bundle getDefaultFieldClassificationArgs() {
        final Bundle packageDefaultArgs = mPackageUserData.getDefaultFieldClassificationArgs();
        if (packageDefaultArgs != null) {
            return packageDefaultArgs;
        } else {
            return mGenericUserData == null ? null :
                    mGenericUserData.getDefaultFieldClassificationArgs();
        }
    }

    @Nullable
    @Override
    public String getFieldClassificationAlgorithmForCategory(@NonNull String categoryId) {
        Objects.requireNonNull(categoryId);
        final ArrayMap<String, String> categoryAlgorithms = getFieldClassificationAlgorithms();
        if (categoryAlgorithms == null || !categoryAlgorithms.containsKey(categoryId)) {
            return null;
        }
        return categoryAlgorithms.get(categoryId);
    }

    @Override
    public ArrayMap<String, String> getFieldClassificationAlgorithms() {
        final ArrayMap<String, String> packageAlgos = mPackageUserData
                .getFieldClassificationAlgorithms();
        final ArrayMap<String, String> genericAlgos = mGenericUserData == null ? null :
                mGenericUserData.getFieldClassificationAlgorithms();

        ArrayMap<String, String> categoryAlgorithms = null;
        if (packageAlgos != null || genericAlgos != null) {
            categoryAlgorithms = new ArrayMap<>();
            if (genericAlgos != null) {
                categoryAlgorithms.putAll(genericAlgos);
            }
            if (packageAlgos != null) {
                categoryAlgorithms.putAll(packageAlgos);
            }
        }

        return categoryAlgorithms;
    }

    @Override
    public ArrayMap<String, Bundle> getFieldClassificationArgs() {
        final ArrayMap<String, Bundle> packageArgs = mPackageUserData.getFieldClassificationArgs();
        final ArrayMap<String, Bundle> genericArgs = mGenericUserData == null ? null :
                mGenericUserData.getFieldClassificationArgs();

        ArrayMap<String, Bundle> categoryArgs = null;
        if (packageArgs != null || genericArgs != null) {
            categoryArgs = new ArrayMap<>();
            if (genericArgs != null) {
                categoryArgs.putAll(genericArgs);
            }
            if (packageArgs != null) {
                categoryArgs.putAll(packageArgs);
            }
        }

        return categoryArgs;
    }

    @Override
    public String[] getCategoryIds() {
        return mCategories;
    }

    @Override
    public String[] getValues() {
        return mValues;
    }

    /////////////////////////////////////
    // Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        // OK to print UserData because UserData.toString() is PII-aware
        final StringBuilder builder = new StringBuilder("genericUserData=")
                .append(mGenericUserData)
                .append(", packageUserData=").append(mPackageUserData);
        return builder.toString();
    }

    /////////////////////////////////////
    // Parcelable "contract" methods. //
    /////////////////////////////////////

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeParcelable(mGenericUserData, 0);
        parcel.writeParcelable(mPackageUserData, 0);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<CompositeUserData> CREATOR =
            new Parcelable.Creator<CompositeUserData>() {
                @Override
                public CompositeUserData createFromParcel(Parcel parcel) {
                    // Always go through the builder to ensure the data ingested by
                    // the system obeys the contract of the builder to avoid attacks
                    // using specially crafted parcels.
                    final UserData genericUserData = parcel.readParcelable(null, android.service.autofill.UserData.class);
                    final UserData packageUserData = parcel.readParcelable(null, android.service.autofill.UserData.class);
                    return new CompositeUserData(genericUserData, packageUserData);
                }

                @Override
                public CompositeUserData[] newArray(int size) {
                    return new CompositeUserData[size];
                }
            };
}
