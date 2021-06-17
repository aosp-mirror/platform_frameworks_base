/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.controls.Control;

import com.android.internal.R;
import com.android.internal.util.Preconditions;

/**
 * A template for a {@link Control} that displays an image.
 */
public final class ThumbnailTemplate extends ControlTemplate {

    private static final @TemplateType int TYPE = TYPE_THUMBNAIL;
    private static final String KEY_ICON = "key_icon";
    private static final String KEY_ACTIVE = "key_active";
    private static final String KEY_CONTENT_DESCRIPTION = "key_content_description";

    private final boolean mActive;
    private final @NonNull Icon mThumbnail;
    private final @NonNull CharSequence mContentDescription;

    /**
     * @param templateId the identifier for this template object
     * @param active whether the image corresponds to an active (live) stream.
     * @param thumbnail an image to display on the {@link Control}
     * @param contentDescription a description of the image for accessibility.
     */
    public ThumbnailTemplate(
            @NonNull String templateId,
            boolean active,
            @NonNull Icon thumbnail,
            @NonNull CharSequence contentDescription) {
        super(templateId);
        Preconditions.checkNotNull(thumbnail);
        Preconditions.checkNotNull(contentDescription);
        mActive = active;
        mThumbnail = thumbnail;
        mContentDescription = contentDescription;
    }

    /**
     * @param b
     * @hide
     */
    ThumbnailTemplate(Bundle b) {
        super(b);
        mActive = b.getBoolean(KEY_ACTIVE);
        mThumbnail = b.getParcelable(KEY_ICON);
        mContentDescription = b.getCharSequence(KEY_CONTENT_DESCRIPTION, "");
    }

    /*
     * @return {@code true} if the thumbnail corresponds to an active (live) stream.
     */
    public boolean isActive() {
        return mActive;
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

    /**
     * Rescales the image down if necessary (in the case of a Bitmap).
     *
     * @hide
     */
    @Override
    public void prepareTemplateForBinder(@NonNull Context context) {
        int width = context.getResources()
                .getDimensionPixelSize(R.dimen.controls_thumbnail_image_max_width);
        int height = context.getResources()
                .getDimensionPixelSize(R.dimen.controls_thumbnail_image_max_height);
        rescaleThumbnail(width, height);
    }

    private void rescaleThumbnail(int width, int height) {
        mThumbnail.scaleDownIfNecessary(width, height);
    }

    /**
     * @return
     * @hide
     */
    @Override
    @NonNull
    Bundle getDataBundle() {
        Bundle b = super.getDataBundle();
        b.putBoolean(KEY_ACTIVE, mActive);
        b.putObject(KEY_ICON, mThumbnail);
        b.putObject(KEY_CONTENT_DESCRIPTION, mContentDescription);
        return b;
    }
}
