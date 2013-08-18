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

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.LruCache;

public class ThumbnailCache extends LruCache<Uri, Bitmap> {
    private static ThumbnailCache sCache;

    public static ThumbnailCache get(Context context) {
        if (sCache == null) {
            final ActivityManager am = (ActivityManager) context.getSystemService(
                    Context.ACTIVITY_SERVICE);
            final int memoryClassBytes = am.getMemoryClass() * 1024 * 1024;
            sCache = new ThumbnailCache(memoryClassBytes / 4);
        }
        return sCache;
    }

    public ThumbnailCache(int maxSizeBytes) {
        super(maxSizeBytes);
    }

    @Override
    protected int sizeOf(Uri key, Bitmap value) {
        return value.getByteCount();
    }
}
