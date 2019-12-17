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

package android.service.controls.templates;

import android.annotation.NonNull;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcel;
import android.service.controls.Control;

import com.android.internal.util.Preconditions;

/**
 * A template for a {@link Control} that displays an image.
 *
 * @hide
 */
public final class ThumbnailTemplate extends ControlTemplate {

    private static final @TemplateType int TYPE = TYPE_THUMBNAIL;
    private static final String KEY_ICON = "key_icon";
    private static final String KEY_CONTENT_DESCRIPTION = "key_content_description";

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

    ThumbnailTemplate(Bundle b) {
        super(b);
        mThumbnail = b.getParcelable(KEY_ICON);
        mContentDescription = b.getCharSequence(KEY_CONTENT_DESCRIPTION, "");
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
        return TYPE;
    }

    @Override
    protected Bundle getDataBundle() {
        Bundle b = super.getDataBundle();
        b.putObject(KEY_ICON, mThumbnail);
        b.putObject(KEY_CONTENT_DESCRIPTION, mContentDescription);
        return b;
    }

    public static final Creator<ThumbnailTemplate> CREATOR = new Creator<ThumbnailTemplate>() {
        @Override
        public ThumbnailTemplate createFromParcel(Parcel source) {
            int type = source.readInt();
            verifyType(type, TYPE);
            return new ThumbnailTemplate(source.readBundle());
        }

        @Override
        public ThumbnailTemplate[] newArray(int size) {
            return new ThumbnailTemplate[size];
        }
    };
}
