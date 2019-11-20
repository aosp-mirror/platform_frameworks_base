/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.service.controls;

import android.annotation.NonNull;
import android.graphics.drawable.Icon;
import android.os.Parcel;

import com.android.internal.util.Preconditions;

/**
 * A template for a {@link Control} that displays an image.
 * @hide
 */
public final class ThumbnailTemplate extends ControlTemplate {

    private final @NonNull Icon mThumbnail;
    private final @NonNull CharSequence mContentDescription;

    /**
     * @param templateId the identifier for this template object
     * @param thumbnail an image to display on the {@link Control}
     * @param contentDescription a description of the image for accessibility.
     */
    public ThumbnailTemplate(@NonNull String templateId, @NonNull Icon thumbnail,
            @NonNull CharSequence contentDescription) {
        super(templateId);
        Preconditions.checkNotNull(thumbnail);
        Preconditions.checkNotNull(contentDescription);
        mThumbnail = thumbnail;
        mContentDescription = contentDescription;
    }

    ThumbnailTemplate(Parcel in) {
        super(in);
        mThumbnail = Icon.CREATOR.createFromParcel(in);
        mContentDescription = in.readCharSequence();
    }

    /**
     * The {@link Icon} (image) displayed by this template.
     */
    @NonNull
    public Icon getThumbnail() {
        return mThumbnail;
    }

    /**
     * The description of the image returned by {@link ThumbnailTemplate#getThumbnail()}
     */
    @NonNull
    public CharSequence getContentDescription() {
        return mContentDescription;
    }

    /**
     * @return {@link ControlTemplate#TYPE_THUMBNAIL}
     */
    @Override
    public int getTemplateType() {
        return TYPE_THUMBNAIL;
    }
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        mThumbnail.writeToParcel(dest, flags);
        dest.writeCharSequence(mContentDescription);
    }

    public static final Creator<ThumbnailTemplate> CREATOR = new Creator<ThumbnailTemplate>() {
        @Override
        public ThumbnailTemplate createFromParcel(Parcel source) {
            return new ThumbnailTemplate(source);
        }

        @Override
        public ThumbnailTemplate[] newArray(int size) {
            return new ThumbnailTemplate[size];
        }
    };
}
