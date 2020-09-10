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

package android.accessibilityservice.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Collection of utilities for accessibility service.
 *
 * @hide
 */
public final class AccessibilityUtils {
    private AccessibilityUtils() {}

    // Used for html description of accessibility service. The <img> src tag must follow the
    // prefix rule. e.g. <img src="R.drawable.fileName"/>
    private static final String IMG_PREFIX = "R.drawable.";
    private static final String ANCHOR_TAG = "a";
    private static final List<String> UNSUPPORTED_TAG_LIST = new ArrayList<>(
            Collections.singletonList(ANCHOR_TAG));

    /**
     * Gets the filtered html string for
     * {@link android.accessibilityservice.AccessibilityServiceInfo} and
     * {@link android.accessibilityservice.AccessibilityShortcutInfo}. It filters
     * the <img> tag which do not meet the custom specification and the <a> tag.
     *
     * @param text the target text is html format.
     * @return the filtered html string.
     */
    public static @NonNull String getFilteredHtmlText(@NonNull String text) {
        final String replacementStart = "<invalidtag ";
        final String replacementEnd = "</invalidtag>";

        for (String tag : UNSUPPORTED_TAG_LIST) {
            final String regexStart = "(?i)<" + tag + "(\\s+|>)";
            final String regexEnd = "(?i)</" + tag + "\\s*>";
            text = Pattern.compile(regexStart).matcher(text).replaceAll(replacementStart);
            text = Pattern.compile(regexEnd).matcher(text).replaceAll(replacementEnd);
        }

        final String regexInvalidImgTag = "(?i)<img\\s+(?!src\\s*=\\s*\"(?-i)" + IMG_PREFIX + ")";
        text = Pattern.compile(regexInvalidImgTag).matcher(text).replaceAll(
                replacementStart);

        return text;
    }

    /**
     * Loads the animated image for
     * {@link android.accessibilityservice.AccessibilityServiceInfo} and
     * {@link android.accessibilityservice.AccessibilityShortcutInfo}. It checks the resource
     * whether to exceed the screen size.
     *
     * @param context the current context.
     * @param applicationInfo the current application.
     * @param resId the animated image resource id.
     * @return the animated image which is safe.
     */
    @Nullable
    public static Drawable loadSafeAnimatedImage(@NonNull Context context,
            @NonNull ApplicationInfo applicationInfo, @StringRes int resId) {
        if (resId == /* invalid */ 0) {
            return null;
        }

        final PackageManager packageManager = context.getPackageManager();
        final String packageName = applicationInfo.packageName;
        final Drawable bannerDrawable = packageManager.getDrawable(packageName, resId,
                applicationInfo);
        if (bannerDrawable == null) {
            return null;
        }

        final boolean isImageWidthOverScreenLength =
                bannerDrawable.getIntrinsicWidth() > getScreenWidthPixels(context);
        final boolean isImageHeightOverScreenLength =
                bannerDrawable.getIntrinsicHeight() > getScreenHeightPixels(context);

        return (isImageWidthOverScreenLength || isImageHeightOverScreenLength)
                ? null
                : bannerDrawable;
    }

    /**
     * Gets the width of the screen.
     *
     * @param context the current context.
     * @return the width of the screen in term of pixels.
     */
    private static int getScreenWidthPixels(@NonNull Context context) {
        final Resources resources = context.getResources();
        final int screenWidthDp = resources.getConfiguration().screenWidthDp;

        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, screenWidthDp,
                resources.getDisplayMetrics()));
    }

    /**
     * Gets the height of the screen.
     *
     * @param context the current context.
     * @return the height of the screen in term of pixels.
     */
    private static int getScreenHeightPixels(@NonNull Context context) {
        final Resources resources = context.getResources();
        final int screenHeightDp = resources.getConfiguration().screenHeightDp;

        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, screenHeightDp,
                resources.getDisplayMetrics()));
    }
}
