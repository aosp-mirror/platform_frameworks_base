/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.notification.modes;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ZenIconLoader {

    private static final String TAG = "ZenIconLoader";

    private static final Drawable MISSING = new ColorDrawable();

    @Nullable // Until first usage
    private static ZenIconLoader sInstance;

    private final LruCache<ZenIcon.Key, Drawable> mCache;
    private final ListeningExecutorService mBackgroundExecutor;

    /** Obtains the singleton {@link ZenIconLoader}. */
    public static ZenIconLoader getInstance() {
        if (sInstance == null) {
            sInstance = new ZenIconLoader(Executors.newFixedThreadPool(4));
        }
        return sInstance;
    }

    /**
     * Constructs a ZenIconLoader with the specified {@code backgroundExecutor}.
     *
     * <p>ZenIconLoader <em>should be a singleton</em>, so this should only be used to instantiate
     * and provide the singleton instance in a module. If the app doesn't support dependency
     * injection, use {@link #getInstance} instead.
     */
    public ZenIconLoader(@NonNull ExecutorService backgroundExecutor) {
        mCache = new LruCache<>(50);
        mBackgroundExecutor =
                MoreExecutors.listeningDecorator(backgroundExecutor);
    }

    /**
     * Loads the {@link Drawable} corresponding to a {@link ZenMode} in a background thread, and
     * caches it for future calls.
     *
     * <p>The {@link ZenIcon#drawable()} will always correspond to the resource indicated by
     * {@link ZenIcon#key()}. In turn, this will match the value of {@link ZenMode#getIconKey()}
     * for the supplied mode -- except for the rare case where the mode has an apparently valid
     * drawable resource id that we fail to load for some reason, thus needing a "fallback" icon.
     */
    @NonNull
    public ListenableFuture<ZenIcon> getIcon(@NonNull Context context, @NonNull ZenMode mode) {
        ZenIcon.Key key = mode.getIconKey();

        return FluentFuture.from(loadIcon(context, key, /* useMonochromeIfPresent= */ true))
                .transformAsync(drawable ->
                        drawable != null
                            ? immediateFuture(new ZenIcon(key, drawable))
                            : getFallbackIcon(context, mode),
                mBackgroundExecutor);
    }

    private ListenableFuture<ZenIcon> getFallbackIcon(Context context, ZenMode mode) {
        ZenIcon.Key key = ZenIconKeys.forType(mode.getType());
        return FluentFuture.from(loadIcon(context, key, /* useMonochromeIfPresent= */ false))
                .transform(drawable -> {
                    checkNotNull(drawable, "Couldn't load DEFAULT icon for mode %s!", mode);
                    return new ZenIcon(key, drawable);
                },
                directExecutor());
    }

    @NonNull
    private ListenableFuture</* @Nullable */ Drawable> loadIcon(Context context,
            ZenIcon.Key key, boolean useMonochromeIfPresent) {
        synchronized (mCache) {
            Drawable cachedValue = mCache.get(key);
            if (cachedValue != null) {
                return immediateFuture(cachedValue != MISSING ? cachedValue : null);
            }
        }

        return FluentFuture.from(mBackgroundExecutor.submit(() -> {
            if (TextUtils.isEmpty(key.resPackage())) {
                return context.getDrawable(key.resId());
            } else {
                Context appContext = context.createPackageContext(key.resPackage(), 0);
                Drawable appDrawable = appContext.getDrawable(key.resId());
                return useMonochromeIfPresent
                        ? getMonochromeIconIfPresent(appDrawable)
                        : appDrawable;
            }
        })).catching(Exception.class, ex -> {
            // If we cannot resolve the icon, then store MISSING in the cache below, so
            // we don't try again.
            Log.e(TAG, "Error while loading mode icon " + key, ex);
            return null;
        }, directExecutor()).transform(drawable -> {
            synchronized (mCache) {
                mCache.put(key, drawable != null ? drawable : MISSING);
            }
            return drawable;
        }, directExecutor());
    }

    private static Drawable getMonochromeIconIfPresent(Drawable icon) {
        // For created rules, the app should've provided a monochrome Drawable. However, implicit
        // rules have the app's icon, which is not -- but might have a monochrome layer. Thus
        // we choose it, if present.
        if (icon instanceof AdaptiveIconDrawable adaptiveIcon) {
            if (adaptiveIcon.getMonochrome() != null) {
                // Wrap with negative inset => scale icon (inspired from BaseIconFactory)
                return new InsetDrawable(adaptiveIcon.getMonochrome(),
                        -2.0f * AdaptiveIconDrawable.getExtraInsetFraction());
            }
        }
        return icon;
    }
}
