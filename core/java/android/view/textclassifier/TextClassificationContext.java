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

package android.view.textclassifier;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.textclassifier.TextClassifier.WidgetType;

import java.util.Locale;
import java.util.Objects;

/**
 * A representation of the context in which text classification would be performed.
 * @see TextClassificationManager#createTextClassificationSession(TextClassificationContext)
 */
public final class TextClassificationContext implements Parcelable {

    private String mPackageName;
    private final String mWidgetType;
    @Nullable private final String mWidgetVersion;
    private SystemTextClassifierMetadata mSystemTcMetadata;

    private TextClassificationContext(
            String packageName,
            String widgetType,
            String widgetVersion) {
        mPackageName = Objects.requireNonNull(packageName);
        mWidgetType = Objects.requireNonNull(widgetType);
        mWidgetVersion = widgetVersion;
    }

    /**
     * Returns the package name of the app that this context originated in.
     */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Sets the information about the {@link SystemTextClassifier} that sent this request.
     *
     * @hide
     */
    void setSystemTextClassifierMetadata(@Nullable SystemTextClassifierMetadata systemTcMetadata) {
        mSystemTcMetadata = systemTcMetadata;
    }

    /**
     * Returns the information about the {@link SystemTextClassifier} that sent this request.
     *
     * @hide
     */
    @Nullable
    public SystemTextClassifierMetadata getSystemTextClassifierMetadata() {
        return mSystemTcMetadata;
    }

    /**
     * Returns the widget type for this classification context.
     */
    @NonNull
    @WidgetType
    public String getWidgetType() {
        return mWidgetType;
    }

    /**
     * Returns a custom version string for the widget type.
     *
     * @see #getWidgetType()
     */
    @Nullable
    public String getWidgetVersion() {
        return mWidgetVersion;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "TextClassificationContext{"
                + "packageName=%s, widgetType=%s, widgetVersion=%s, systemTcMetadata=%s}",
                mPackageName, mWidgetType, mWidgetVersion, mSystemTcMetadata);
    }

    /**
     * A builder for building a TextClassification context.
     */
    public static final class Builder {

        private final String mPackageName;
        private final String mWidgetType;

        @Nullable private String mWidgetVersion;

        /**
         * Initializes a new builder for text classification context objects.
         *
         * @param packageName the name of the calling package
         * @param widgetType the type of widget e.g. {@link TextClassifier#WIDGET_TYPE_TEXTVIEW}
         *
         * @return this builder
         */
        public Builder(@NonNull String packageName, @NonNull @WidgetType String widgetType) {
            mPackageName = Objects.requireNonNull(packageName);
            mWidgetType = Objects.requireNonNull(widgetType);
        }

        /**
         * Sets an optional custom version string for the widget type.
         *
         * @return this builder
         */
        public Builder setWidgetVersion(@Nullable String widgetVersion) {
            mWidgetVersion = widgetVersion;
            return this;
        }

        /**
         * Builds the text classification context object.
         *
         * @return the built TextClassificationContext object
         */
        @NonNull
        public TextClassificationContext build() {
            return new TextClassificationContext(mPackageName, mWidgetType, mWidgetVersion);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mPackageName);
        parcel.writeString(mWidgetType);
        parcel.writeString(mWidgetVersion);
        parcel.writeParcelable(mSystemTcMetadata, flags);
    }

    private TextClassificationContext(Parcel in) {
        mPackageName = in.readString();
        mWidgetType = in.readString();
        mWidgetVersion = in.readString();
        mSystemTcMetadata = in.readParcelable(null);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<TextClassificationContext> CREATOR =
            new Parcelable.Creator<TextClassificationContext>() {
                @Override
                public TextClassificationContext createFromParcel(Parcel parcel) {
                    return new TextClassificationContext(parcel);
                }

                @Override
                public TextClassificationContext[] newArray(int size) {
                    return new TextClassificationContext[size];
                }
            };
}
