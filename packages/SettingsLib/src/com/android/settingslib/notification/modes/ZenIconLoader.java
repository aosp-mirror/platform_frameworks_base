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

import static com.google.common.util.concurrent.Futures.immediateFuture;

import static java.util.Objects.requireNonNull;

import android.annotation.Nullable;
import android.app.AutomaticZenRule;
import android.content.Context;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.service.notification.SystemZenRules;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.content.res.AppCompatResources;

import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
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

    private final LruCache<String, Drawable> mCache;
    private final ListeningExecutorService mBackgroundExecutor;

    public static ZenIconLoader getInstance() {
        if (sInstance == null) {
            sInstance = new ZenIconLoader();
        }
        return sInstance;
    }

    private ZenIconLoader() {
        this(Executors.newFixedThreadPool(4));
    }

    @VisibleForTesting
    ZenIconLoader(ExecutorService backgroundExecutor) {
        mCache = new LruCache<>(50);
        mBackgroundExecutor =
                MoreExecutors.listeningDecorator(backgroundExecutor);
    }

    @NonNull
    ListenableFuture<Drawable> getIcon(Context context, @NonNull AutomaticZenRule rule) {
        if (rule.getIconResId() == 0) {
            return Futures.immediateFuture(getFallbackIcon(context, rule.getType()));
        }

        return FluentFuture.from(loadIcon(context, rule.getPackageName(), rule.getIconResId()))
                .transform(icon ->
                                icon != null ? icon : getFallbackIcon(context, rule.getType()),
                        MoreExecutors.directExecutor());
    }

    @NonNull
    private ListenableFuture</* @Nullable */ Drawable> loadIcon(Context context, String pkg,
            int iconResId) {
        String cacheKey = pkg + ":" + iconResId;
        synchronized (mCache) {
            Drawable cachedValue = mCache.get(cacheKey);
            if (cachedValue != null) {
                return immediateFuture(cachedValue != MISSING ? cachedValue : null);
            }
        }

        return FluentFuture.from(mBackgroundExecutor.submit(() -> {
            if (TextUtils.isEmpty(pkg) || SystemZenRules.PACKAGE_ANDROID.equals(pkg)) {
                return context.getDrawable(iconResId);
            } else {
                Context appContext = context.createPackageContext(pkg, 0);
                Drawable appDrawable = AppCompatResources.getDrawable(appContext, iconResId);
                return getMonochromeIconIfPresent(appDrawable);
            }
        })).catching(Exception.class, ex -> {
            // If we cannot resolve the icon, then store MISSING in the cache below, so
            // we don't try again.
            Log.e(TAG, "Error while loading icon " + cacheKey, ex);
            return null;
        }, MoreExecutors.directExecutor()).transform(drawable -> {
            synchronized (mCache) {
                mCache.put(cacheKey, drawable != null ? drawable : MISSING);
            }
            return drawable;
        }, MoreExecutors.directExecutor());
    }

    private static Drawable getFallbackIcon(Context context, int ruleType) {
        int iconResIdFromType = switch (ruleType) {
            case AutomaticZenRule.TYPE_UNKNOWN ->
                    com.android.internal.R.drawable.ic_zen_mode_type_unknown;
            case AutomaticZenRule.TYPE_OTHER ->
                    com.android.internal.R.drawable.ic_zen_mode_type_other;
            case AutomaticZenRule.TYPE_SCHEDULE_TIME ->
                    com.android.internal.R.drawable.ic_zen_mode_type_schedule_time;
            case AutomaticZenRule.TYPE_SCHEDULE_CALENDAR ->
                    com.android.internal.R.drawable.ic_zen_mode_type_schedule_calendar;
            case AutomaticZenRule.TYPE_BEDTIME ->
                    com.android.internal.R.drawable.ic_zen_mode_type_bedtime;
            case AutomaticZenRule.TYPE_DRIVING ->
                    com.android.internal.R.drawable.ic_zen_mode_type_driving;
            case AutomaticZenRule.TYPE_IMMERSIVE ->
                    com.android.internal.R.drawable.ic_zen_mode_type_immersive;
            case AutomaticZenRule.TYPE_THEATER ->
                    com.android.internal.R.drawable.ic_zen_mode_type_theater;
            case AutomaticZenRule.TYPE_MANAGED ->
                    com.android.internal.R.drawable.ic_zen_mode_type_managed;
            default -> com.android.internal.R.drawable.ic_zen_mode_type_unknown;
        };
        return requireNonNull(context.getDrawable(iconResIdFromType));
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
