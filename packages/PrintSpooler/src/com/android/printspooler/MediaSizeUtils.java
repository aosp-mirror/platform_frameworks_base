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

package com.android.printspooler;

import android.content.Context;
import android.print.PrintAttributes.MediaSize;
import android.util.ArrayMap;

import java.util.Comparator;
import java.util.Map;

/**
 * Utility functions and classes for dealing with media sizes.
 */
public class MediaSizeUtils {

    private static Map<MediaSize, String> sMediaSizeToStandardMap;

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

    private static String getStandardForMediaSize(Context context, MediaSize mediaSize) {
        if (sMediaSizeToStandardMap == null) {
            sMediaSizeToStandardMap = new ArrayMap<MediaSize, String>();
            String[] mediaSizeToStandardMapValues = context.getResources()
                    .getStringArray(R.array.mediasize_to_standard_map);
            final int mediaSizeToStandardCount = mediaSizeToStandardMapValues.length;
            for (int i = 0; i < mediaSizeToStandardCount; i += 2) {
                String mediaSizeId = mediaSizeToStandardMapValues[i];
                MediaSize key = MediaSize.getStandardMediaSizeById(mediaSizeId);
                String value = mediaSizeToStandardMapValues[i + 1];
                sMediaSizeToStandardMap.put(key, value);
            }
        }
        String standard = sMediaSizeToStandardMap.get(mediaSize);
        return (standard != null) ? standard : context.getString(
                R.string.mediasize_standard_iso);
    }

    /**
     * Comparator for ordering standard media sizes. The ones for the current
     * standard go to the top and the ones for the other standards follow grouped
     * by standard. Media sizes of the same standard are ordered alphabetically.
     */
    public static final class MediaSizeComparator implements Comparator<MediaSize> {
        private final Context mContext;

        public MediaSizeComparator(Context context) {
            mContext = context;
        }

        @Override
        public int compare(MediaSize lhs, MediaSize rhs) {
            String currentStandard = mContext.getString(R.string.mediasize_standard);
            String lhsStandard = getStandardForMediaSize(mContext, lhs);
            String rhsStandard = getStandardForMediaSize(mContext, rhs);

            // The current standard always wins.
            if (lhsStandard.equals(currentStandard)) {
                if (!rhsStandard.equals(currentStandard)) {
                    return -1;
                }
            } else if (rhsStandard.equals(currentStandard)) {
                return 1;
            }

            if (!lhsStandard.equals(rhsStandard)) {
                // Different standards - use the standard ordering.
                return lhsStandard.compareTo(rhsStandard);
            } else {
                // Same standard - sort alphabetically by label.
                return lhs.getLabel(mContext.getPackageManager()).
                        compareTo(rhs.getLabel(mContext.getPackageManager()));
            }
        }
    }
}
