/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.layoutlib.bridge.bars;

import com.android.resources.Density;
import com.android.resources.LayoutDirection;

import java.io.InputStream;

public class IconLoader {

    private final String mIconName;
    private final Density mDesiredDensity;
    private final int mPlatformVersion;
    private final LayoutDirection mDirection;

    private Density mCurrentDensity;
    private StringBuilder mCurrentPath;

    IconLoader(String iconName, Density density, int platformVersion, LayoutDirection direction) {
        mIconName = iconName;
        mDesiredDensity = density;
        mPlatformVersion = platformVersion;
        mDirection = direction;
        // An upper bound on the length of the path for the icon: /bars/v21/ldrtl-xxxhdpi/
        final int iconPathLength = 24;
        mCurrentPath = new StringBuilder(iconPathLength + iconName.length());
    }

    public InputStream getIcon() {
        for (String resourceDir : Config.getResourceDirs(mPlatformVersion)) {
            mCurrentDensity = null;
            InputStream stream = getIcon(resourceDir);
            if (stream != null) {
                return stream;
            }
        }
        return null;
    }

    /**
     * Should only be called after {@link #getIcon()}. Returns the density of the icon, if found by
     * {@code getIcon()}. If no icon was found, then the return value has no meaning.
     */
    public Density getDensity() {
        return mCurrentDensity;
    }

    /**
     * Should only be called after {@link #getIcon()}. Returns the path to the icon, if found by
     * {@code getIcon()}. If no icon was found, then the return value has no meaning.
     */
    public String getPath() {
        return mCurrentPath.toString();
    }

    /**
     * Search for icon in the resource directory. This iterates over all densities.
     * If a match is found, mCurrentDensity will be set to the icon's density.
     */
    private InputStream getIcon(String resourceDir) {
        // First check for the desired density.
        InputStream stream = getIcon(resourceDir, mDesiredDensity);
        if (stream != null) {
            mCurrentDensity = mDesiredDensity;
            return stream;
        }
        // Didn't find in the desired density. Search in all.
        for (Density density : Density.values()) {
            if (density == mDesiredDensity) {
                // Skip the desired density since it's already been checked.
                continue;
            }
            stream = getIcon(resourceDir, density);
            if (stream != null) {
                mCurrentDensity = density;
                return stream;
            }
        }
        return null;
    }

    /**
     * Returns the icon for given density present in the given resource directory, taking layout
     * direction into consideration.
     */
    private InputStream getIcon(String resourceDir, Density density) {
        mCurrentPath.setLength(0);
        // Currently we don't have any LTR only resources and hence the check is skipped. If they
        // are ever added, change to:
        // if (mDirection == LayoutDirection.RTL || mDirection == LayoutDirection.LTR) {
        if (mDirection == LayoutDirection.RTL) {
            mCurrentPath.append(resourceDir)
                    .append(mDirection.getResourceValue())
                    .append('-')
                    .append(density.getResourceValue())
                    .append('/')
                    .append(mIconName);
            InputStream stream = getClass().getResourceAsStream(mCurrentPath.toString());
            if (stream != null) {
                return stream;
            }
            mCurrentPath.setLength(0);
        }
        mCurrentPath.append(resourceDir)
                .append(density.getResourceValue())
                .append('/')
                .append(mIconName);
        return getClass().getResourceAsStream(mCurrentPath.toString());
    }
}
