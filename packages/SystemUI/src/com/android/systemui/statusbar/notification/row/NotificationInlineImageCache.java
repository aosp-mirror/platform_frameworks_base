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

package com.android.systemui.statusbar.notification.row;

import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * A cache for inline images of image messages.
 */
public class NotificationInlineImageCache implements NotificationInlineImageResolver.ImageCache {
    private static final String TAG = NotificationInlineImageCache.class.getSimpleName();

    private NotificationInlineImageResolver mResolver;
    private final ConcurrentHashMap<Uri, PreloadImageTask> mCache;

    public NotificationInlineImageCache() {
        mCache = new ConcurrentHashMap<>();
    }

    @Override
    public void setImageResolver(NotificationInlineImageResolver resolver) {
        mResolver = resolver;
    }

    @Override
    public boolean hasEntry(Uri uri) {
        return mCache.containsKey(uri);
    }

    @Override
    public void preload(Uri uri) {
        PreloadImageTask newTask = new PreloadImageTask(mResolver);
        newTask.executeOnExecutor(NotificationContentInflater.EXECUTOR, uri);
        mCache.put(uri, newTask);
    }

    @Override
    public Drawable get(Uri uri) {
        Drawable result = null;
        try {
            result = mCache.get(uri).get();
        } catch (InterruptedException | ExecutionException ex) {
            Log.d(TAG, "get: Failed get image from " + uri);
        }
        return result;
    }

    @Override
    public void purge() {
        Set<Uri> wantedSet = mResolver.getWantedUriSet();
        mCache.entrySet().removeIf(entry -> !wantedSet.contains(entry.getKey()));
    }

    private static class PreloadImageTask extends AsyncTask<Uri, Void, Drawable> {
        private final NotificationInlineImageResolver mResolver;

        PreloadImageTask(NotificationInlineImageResolver resolver) {
            mResolver = resolver;
        }

        @Override
        protected Drawable doInBackground(Uri... uris) {
            Drawable drawable = null;
            Uri target = uris[0];

            try {
                drawable = mResolver.resolveImage(target);
            } catch (IOException | SecurityException ex) {
                Log.d(TAG, "PreloadImageTask: Resolve failed from " + target, ex);
            }

            return drawable;
        }
    }
}
