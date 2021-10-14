/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.widget;

import android.annotation.AttrRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StyleRes;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.ImageView;
import android.widget.RemoteViews;

import java.io.IOException;

/**
 * An ImageView used by BigPicture Notifications to correctly resolve the Uri in an Icon using the
 * LocalImageResolver, allowing it to support animated drawables which are not supported by
 * Icon.loadDrawable().
 */
@RemoteViews.RemoteView
public class BigPictureNotificationImageView extends ImageView {

    private static final String TAG = BigPictureNotificationImageView.class.getSimpleName();

    public BigPictureNotificationImageView(@NonNull Context context) {
        super(context);
    }

    public BigPictureNotificationImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public BigPictureNotificationImageView(@NonNull Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public BigPictureNotificationImageView(@NonNull Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr, @StyleRes int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    @android.view.RemotableViewMethod(asyncImpl = "setImageURIAsync")
    public void setImageURI(@Nullable Uri uri) {
        setImageDrawable(loadImage(uri));
    }

    /** @hide **/
    public Runnable setImageURIAsync(@Nullable Uri uri) {
        final Drawable drawable = loadImage(uri);
        return () -> setImageDrawable(drawable);
    }

    @Override
    @android.view.RemotableViewMethod(asyncImpl = "setImageIconAsync")
    public void setImageIcon(@Nullable Icon icon) {
        setImageDrawable(loadImage(icon));
    }

    /** @hide **/
    public Runnable setImageIconAsync(@Nullable Icon icon) {
        final Drawable drawable = loadImage(icon);
        return () -> setImageDrawable(drawable);
    }

    private Drawable loadImage(Uri uri) {
        if (uri == null) return null;
        try {
            return LocalImageResolver.resolveImage(uri, mContext);
        } catch (IOException ex) {
            Log.d(TAG, "Resolve failed from " + uri, ex);
            return null;
        }
    }

    private Drawable loadImage(Icon icon) {
        if (icon == null) return null;
        try {
            return LocalImageResolver.resolveImage(icon, mContext);
        } catch (IOException ex) {
            Log.d(TAG, "Resolve failed from " + icon, ex);
            return null;
        }
    }
}
