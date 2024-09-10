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

package android.content.pm;

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.icu.text.UnicodeSet;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

/**
 * A representation of an activity that can belong to this user or a managed
 * profile associated with this user. It can be used to query the label, icon
 * and badged icon for the activity.
 */
public class LauncherActivityInfo {
    private final PackageManager mPm;
    private final LauncherActivityInfoInternal mInternal;

    private static final UnicodeSet INVISIBLE_CHARACTERS =
            new UnicodeSet("[[:White_Space:][:Default_Ignorable_Code_Point:][:gc=Cc:]]",
                    /* ignoreWhitespace= */ false).freeze();
    // Only allow 3 consecutive invisible characters in the prefix of the string.
    private static final int PREFIX_CONSECUTIVE_INVISIBLE_CHARACTERS_MAXIMUM = 3;

    /**
     * Create a launchable activity object for a given ResolveInfo and user.
     *
     * @param context The context for fetching resources.

     */
    LauncherActivityInfo(Context context, LauncherActivityInfoInternal internal) {
        mPm = context.getPackageManager();
        mInternal = internal;
    }

    /**
     * Returns the component name of this activity.
     *
     * @return ComponentName of the activity
     */
    public ComponentName getComponentName() {
        return mInternal.getComponentName();
    }

    /**
     * Returns the user handle of the user profile that this activity belongs to. In order to
     * persist the identity of the profile, do not store the UserHandle. Instead retrieve its
     * serial number from UserManager. You can convert the serial number back to a UserHandle
     * for later use.
     *
     * @see UserManager#getSerialNumberForUser(UserHandle)
     * @see UserManager#getUserForSerialNumber(long)
     *
     * @return The UserHandle of the profile.
     */
    public UserHandle getUser() {
        return mInternal.getUser();
    }

    /**
     * Retrieves the label for the activity.
     *
     * @return The label for the activity.
     */
    public CharSequence getLabel() {
        if (!Flags.lightweightInvisibleLabelDetection()) {
            // TODO: Go through LauncherAppsService
            return getActivityInfo().loadLabel(mPm);
        }

        CharSequence label = getActivityInfo().loadLabel(mPm).toString().trim();
        // If the activity label is visible to the user, return the original activity label
        if (isVisible(label)) {
            return label;
        }

        // Use application label instead
        label = getApplicationInfo().loadLabel(mPm).toString().trim();
        // If the application label is visible to the user, return the original application label
        if (isVisible(label)) {
            return label;
        }

        // Use package name instead
        return getComponentName().getPackageName();
    }

    /**
     * @return Package loading progress, range between [0, 1].
     */
    public @FloatRange(from = 0.0, to = 1.0) float getLoadingProgress() {
        return mInternal.getIncrementalStatesInfo().getProgress();
    }

    /**
     * Returns the icon for this activity, without any badging for the profile.
     * @param density The preferred density of the icon, zero for default density. Use
     * density DPI values from {@link DisplayMetrics}.
     * @see #getBadgedIcon(int)
     * @see DisplayMetrics
     * @return The drawable associated with the activity.
     */
    public Drawable getIcon(int density) {
        // TODO: Go through LauncherAppsService
        final int iconRes = getActivityInfo().getIconResource();
        Drawable icon = null;
        // Get the preferred density icon from the app's resources
        if (density != 0 && iconRes != 0) {
            try {
                final Resources resources = mPm.getResourcesForApplication(
                        getActivityInfo().applicationInfo);
                icon = resources.getDrawableForDensity(iconRes, density);
            } catch (NameNotFoundException | Resources.NotFoundException exc) {
            }
        }
        // Get the default density icon
        if (icon == null) {
            icon = getActivityInfo().loadIcon(mPm);
        }
        return icon;
    }

    /**
     * Returns the application flags from the ApplicationInfo of the activity.
     *
     * @return Application flags
     * @hide remove before shipping
     */
    public int getApplicationFlags() {
        return getActivityInfo().flags;
    }

    /**
     * Returns the ActivityInfo of the activity.
     *
     * @return Activity Info
     */
    @NonNull
    public ActivityInfo getActivityInfo() {
        return mInternal.getActivityInfo();
    }

    /**
     * Returns the application info for the application this activity belongs to.
     * @return
     */
    public ApplicationInfo getApplicationInfo() {
        return getActivityInfo().applicationInfo;
    }

    /**
     * Returns the time at which the package was first installed.
     *
     * @return The time of installation of the package, in milliseconds.
     */
    public long getFirstInstallTime() {
        try {
            // TODO: Go through LauncherAppsService
            return mPm.getPackageInfo(getActivityInfo().packageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES).firstInstallTime;
        } catch (NameNotFoundException nnfe) {
            // Sorry, can't find package
            return 0;
        }
    }

    /**
     * Returns the name for the activity from  android:name in the manifest.
     * @return the name from android:name for the activity.
     */
    public String getName() {
        return getActivityInfo().name;
    }

    /**
     * Returns the activity icon with badging appropriate for the profile.
     * @param density Optional density for the icon, or 0 to use the default density. Use
     * {@link DisplayMetrics} for DPI values.
     * @see DisplayMetrics
     * @return A badged icon for the activity.
     */
    public Drawable getBadgedIcon(int density) {
        Drawable originalIcon = getIcon(density);

        return mPm.getUserBadgedIcon(originalIcon, mInternal.getUser());
    }

    /**
     * Check whether the {@code sequence} is visible to the user or not.
     * <p>
     * Return {@code false} when one of these conditions are satisfied:
     * 1. The {@code sequence} starts with at least consecutive three invisible characters.
     * 2. The sequence is composed of the invisible characters and non-glyph characters.
     * <p>
     * Invisible character is one of the Default_Ignorable_Code_Point in
     * <a href="
     * https://www.unicode.org/Public/UCD/latest/ucd/DerivedCoreProperties.txt">
     * DerivedCoreProperties.txt</a>, the White_Space in <a href=
     * "https://www.unicode.org/Public/UCD/latest/ucd/PropList.txt">PropList.txt
     * </a> or category Cc.
     * <p>
     * Non-glyph character means the character is not supported in the current system font.
     * {@link android.graphics.Paint#hasGlyph(String)}
     * <p>
     *
     * @hide
     */
    @VisibleForTesting
    public static boolean isVisible(@NonNull CharSequence sequence) {
        Objects.requireNonNull(sequence);
        if (TextUtils.isEmpty(sequence)) {
            return false;
        }

        final Paint paint = new Paint();
        int invisibleCharCount = 0;
        int notSupportedCharCount = 0;
        final int[] codePoints = sequence.codePoints().toArray();
        for (int i = 0, length = codePoints.length; i < length; i++) {
            String ch = new String(new int[]{codePoints[i]}, /* offset= */ 0, /* count= */ 1);

            // The check steps:
            // 1. If the character is contained in INVISIBLE_CHARACTERS, invisibleCharCount++.
            //    1.1 Check whether the invisibleCharCount is larger or equal to
            //        PREFIX_INVISIBLE_CHARACTERS_MAXIMUM when notSupportedCharCount is zero.
            //        It means that there are three consecutive invisible characters at the
            //        start of the string, return false.
            //    Otherwise, continue.
            // 2. If the character is not supported on the system:
            //    notSupportedCharCount++, continue
            // 3. If it does not continue or return on the above two cases, it means the
            //    character is visible and supported on the system, break.
            // After going through the whole string, if the sum of invisibleCharCount
            // and notSupportedCharCount is smaller than the length of the string, it
            // means the string has the other visible characters, return true.
            // Otherwise, return false.
            if (INVISIBLE_CHARACTERS.contains(ch)) {
                invisibleCharCount++;
                // If there are three successive invisible characters at the start of the
                // string, it is hard to visible to the user.
                if (notSupportedCharCount == 0
                        && invisibleCharCount >= PREFIX_CONSECUTIVE_INVISIBLE_CHARACTERS_MAXIMUM) {
                    return false;
                }
                continue;
            }

            // The character is not supported on the system, but it may not be an invisible
            // character. E.g. tofu (a rectangle).
            if (!paint.hasGlyph(ch)) {
                notSupportedCharCount++;
                continue;
            }
            // The character is visible and supported on the system, break the for loop
            break;
        }

        return (invisibleCharCount + notSupportedCharCount < codePoints.length);
    }
}
