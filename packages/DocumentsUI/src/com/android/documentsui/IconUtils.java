/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.documentsui;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.graphics.drawable.Drawable;
import android.provider.DocumentsContract.Document;
import android.util.TypedValue;

public class IconUtils {
    public static Drawable loadPackageIcon(Context context, String authority, int icon) {
        if (icon != 0) {
            if (authority != null) {
                final PackageManager pm = context.getPackageManager();
                final ProviderInfo info = pm.resolveContentProvider(authority, 0);
                if (info != null) {
                    return pm.getDrawable(info.packageName, icon, info.applicationInfo);
                }
            } else {
                return context.getDrawable(icon);
            }
        }
        return null;
    }

    public static Drawable loadMimeIcon(
            Context context, String mimeType, String authority, String docId, int mode) {
        if (Document.MIME_TYPE_DIR.equals(mimeType)) {
            // TODO: eventually move these hacky assets into that package
            if ("com.android.providers.media.documents".equals(authority)
                    && docId.startsWith("album")) {
                return context.getDrawable(R.drawable.ic_doc_album);
            }

            if (mode == State.MODE_GRID) {
                return context.getDrawable(R.drawable.ic_grid_folder);
            } else {
                return context.getDrawable(com.android.internal.R.drawable.ic_doc_folder);
            }
        }

        return loadMimeIcon(context, mimeType);
    }

    public static Drawable loadMimeIcon(Context context, String mimeType) {
        return context.getContentResolver().getTypeDrawable(mimeType);
    }

    public static Drawable applyTintColor(Context context, int drawableId, int tintColorId) {
        final Drawable icon = context.getDrawable(drawableId);
        icon.mutate();
        icon.setTintList(context.getColorStateList(tintColorId));
        return icon;
    }

    public static Drawable applyTintAttr(Context context, int drawableId, int tintAttrId) {
        final TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(tintAttrId, outValue, true);
        return applyTintColor(context, drawableId, outValue.resourceId);
    }
}
