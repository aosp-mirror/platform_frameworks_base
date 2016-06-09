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

package com.android.printspooler.util;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.print.PrintAttributes.MediaSize;

import com.android.printspooler.R;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility functions and classes for dealing with media sizes.
 */
public final class MediaSizeUtils {

    private static Map<MediaSize, Integer> sMediaSizeToStandardMap;

    /** The media size standard for all media sizes no standard is defined for */
    private static int sMediaSizeStandardIso;

    private MediaSizeUtils() {
        /* do nothing - hide constructor */
    }

    /**
     * Gets the default media size for the current locale.
     *
     * @param context Context for accessing resources.
     * @return The default media size.
     */
    public static MediaSize getDefault(Context context) {
        String mediaSizeId = context.getString(R.string.mediasize_default);
        return MediaSize.getStandardMediaSizeById(mediaSizeId);
    }

    /**
     * Get the standard the {@link MediaSize} belongs to.
     *
     * @param context   The context of the caller
     * @param mediaSize The {@link MediaSize} to be resolved
     *
     * @return The standard the {@link MediaSize} belongs to
     */
    private static int getStandardForMediaSize(Context context, MediaSize mediaSize) {
        if (sMediaSizeToStandardMap == null) {
            sMediaSizeStandardIso = Integer.parseInt(context.getString(
                    R.string.mediasize_standard_iso));

            sMediaSizeToStandardMap = new HashMap<>();
            String[] mediaSizeToStandardMapValues = context.getResources()
                    .getStringArray(R.array.mediasize_to_standard_map);
            final int mediaSizeToStandardCount = mediaSizeToStandardMapValues.length;
            for (int i = 0; i < mediaSizeToStandardCount; i += 2) {
                String mediaSizeId = mediaSizeToStandardMapValues[i];
                MediaSize key = MediaSize.getStandardMediaSizeById(mediaSizeId);
                int value = Integer.parseInt(mediaSizeToStandardMapValues[i + 1]);
                sMediaSizeToStandardMap.put(key, value);
            }
        }
        Integer standard = sMediaSizeToStandardMap.get(mediaSize);
        return (standard != null) ? standard : sMediaSizeStandardIso;
    }

    /**
     * Comparator for ordering standard media sizes. The ones for the current
     * standard go to the top and the ones for the other standards follow grouped
     * by standard. Media sizes of the same standard are ordered alphabetically.
     */
    public static final class MediaSizeComparator implements Comparator<MediaSize> {
        private final Context mContext;

        /** Current configuration */
        private Configuration mCurrentConfig;

        /** The standard to use for the current locale */
        private int mCurrentStandard;

        /** Mapping from media size to label */
        private final @NonNull Map<MediaSize, String> mMediaSizeToLabel;

        public MediaSizeComparator(Context context) {
            mContext = context;
            mMediaSizeToLabel = new HashMap<>();
            mCurrentStandard = Integer.parseInt(mContext.getString(R.string.mediasize_standard));
        }

        /**
         * Handle a configuration change by reloading all resources.
         *
         * @param newConfig The new configuration that will be applied.
         */
        public void onConfigurationChanged(@NonNull Configuration newConfig) {
            if (mCurrentConfig == null ||
                    (newConfig.diff(mCurrentConfig) & ActivityInfo.CONFIG_LOCALE) != 0) {
                mCurrentStandard = Integer
                        .parseInt(mContext.getString(R.string.mediasize_standard));
                mMediaSizeToLabel.clear();

                mCurrentConfig = newConfig;
            }
        }

        /**
         * Get the label for a {@link MediaSize}.
         *
         * @param context   The context the label should be loaded for
         * @param mediaSize The {@link MediaSize} to resolve
         *
         * @return The label for the media size
         */
        public @NonNull String getLabel(@NonNull Context context, @NonNull MediaSize mediaSize) {
            String label = mMediaSizeToLabel.get(mediaSize);

            if (label == null) {
                label = mediaSize.getLabel(context.getPackageManager());
                mMediaSizeToLabel.put(mediaSize, label);
            }

            return label;
        }

        @Override
        public int compare(MediaSize lhs, MediaSize rhs) {
            int lhsStandard = getStandardForMediaSize(mContext, lhs);
            int rhsStandard = getStandardForMediaSize(mContext, rhs);

            // The current standard always wins.
            if (lhsStandard == mCurrentStandard) {
                if (rhsStandard != mCurrentStandard) {
                    return -1;
                }
            } else if (rhsStandard == mCurrentStandard) {
                return 1;
            }

            if (lhsStandard != rhsStandard) {
                // Different standards - use the standard ordering.
                return Integer.valueOf(lhsStandard).compareTo(rhsStandard);
            } else {
                // Same standard - sort alphabetically by label.
                return getLabel(mContext, lhs).compareTo(getLabel(mContext, rhs));
            }
        }
    }
}
