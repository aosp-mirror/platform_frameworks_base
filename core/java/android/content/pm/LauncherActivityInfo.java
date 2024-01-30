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

    private static final UnicodeSet TRIMMABLE_CHARACTERS =
            new UnicodeSet("[[:White_Space:][:Default_Ignorable_Code_Point:][:gc=Cc:]]",
                    /* ignoreWhitespace= */ false).freeze();

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

        CharSequence label = trim(getActivityInfo().loadLabel(mPm));
        // If the trimmed label is empty, use application's label instead
        if (TextUtils.isEmpty(label)) {
            label = trim(getApplicationInfo().loadLabel(mPm));
            // If the trimmed label is still empty, use package name instead
            if (TextUtils.isEmpty(label)) {
                label = getComponentName().getPackageName();
            }
        }
        // TODO: Go through LauncherAppsService
        return label;
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
     * If the {@code ch} is trimmable, return {@code true}. Otherwise, return
     * {@code false}. If the count of the code points of {@code ch} doesn't
     * equal 1, return {@code false}.
     * <p>
     * There are two types of the trimmable characters.
     * 1. The character is one of the Default_Ignorable_Code_Point in
     * <a href="
     * https://www.unicode.org/Public/UCD/latest/ucd/DerivedCoreProperties.txt">
     * DerivedCoreProperties.txt</a>, the White_Space in <a href=
     * "https://www.unicode.org/Public/UCD/latest/ucd/PropList.txt">PropList.txt
     * </a> or category Cc.
     * <p>
     * 2. The character is not supported in the current system font.
     * {@link android.graphics.Paint#hasGlyph(String)}
     * <p>
     *
     */
    private static boolean isTrimmable(@NonNull Paint paint, @NonNull CharSequence ch) {
        Objects.requireNonNull(paint);
        Objects.requireNonNull(ch);

        // if ch is empty or it is not a character (i,e, the count of code
        // point doesn't equal one), return false
        if (TextUtils.isEmpty(ch)
                || Character.codePointCount(ch, /* beginIndex= */ 0, ch.length()) != 1) {
            return false;
        }

        // Return true for the cases as below:
        // 1. The character is in the TRIMMABLE_CHARACTERS set
        // 2. The character is not supported in the system font
        return TRIMMABLE_CHARACTERS.contains(ch) || !paint.hasGlyph(ch.toString());
    }

    /**
     * If the {@code sequence} has some leading trimmable characters, creates a new copy
     * and removes the trimmable characters from the copy. Otherwise the given
     * {@code sequence} is returned as it is. Use {@link #isTrimmable(Paint, CharSequence)}
     * to determine whether the character is trimmable or not.
     *
     * @return the trimmed string or the original string that has no
     *         leading trimmable characters.
     * @see    #isTrimmable(Paint, CharSequence)
     * @see    #trim(CharSequence)
     * @see    #trimEnd(CharSequence)
     *
     * @hide
     */
    @VisibleForTesting
    @NonNull
    public static CharSequence trimStart(@NonNull CharSequence sequence) {
        Objects.requireNonNull(sequence);

        if (TextUtils.isEmpty(sequence)) {
            return sequence;
        }

        final Paint paint = new Paint();
        int trimCount = 0;
        final int[] codePoints = sequence.codePoints().toArray();
        for (int i = 0, length = codePoints.length; i < length; i++) {
            String ch = new String(new int[]{codePoints[i]}, /* offset= */ 0, /* count= */ 1);
            if (!isTrimmable(paint, ch)) {
                break;
            }
            trimCount += ch.length();
        }
        if (trimCount == 0) {
            return sequence;
        }
        return sequence.subSequence(trimCount, sequence.length());
    }

    /**
     * If the {@code sequence} has some trailing trimmable characters, creates a new copy
     * and removes the trimmable characters from the copy. Otherwise the given
     * {@code sequence} is returned as it is. Use {@link #isTrimmable(Paint, CharSequence)}
     * to determine whether the character is trimmable or not.
     *
     * @return the trimmed sequence or the original sequence that has no
     *         trailing trimmable characters.
     * @see    #isTrimmable(Paint, CharSequence)
     * @see    #trimStart(CharSequence)
     * @see    #trim(CharSequence)
     *
     * @hide
     */
    @VisibleForTesting
    @NonNull
    public static CharSequence trimEnd(@NonNull CharSequence sequence) {
        Objects.requireNonNull(sequence);

        if (TextUtils.isEmpty(sequence)) {
            return sequence;
        }

        final Paint paint = new Paint();
        int trimCount = 0;
        final int[] codePoints = sequence.codePoints().toArray();
        for (int i = codePoints.length - 1; i >= 0; i--) {
            String ch = new String(new int[]{codePoints[i]}, /* offset= */ 0, /* count= */ 1);
            if (!isTrimmable(paint, ch)) {
                break;
            }
            trimCount += ch.length();
        }

        if (trimCount == 0) {
            return sequence;
        }
        return sequence.subSequence(0, sequence.length() - trimCount);
    }

    /**
     * If the {@code sequence} has some leading or trailing trimmable characters, creates
     * a new copy and removes the trimmable characters from the copy. Otherwise the given
     * {@code sequence} is returned as it is. Use {@link #isTrimmable(Paint, CharSequence)}
     * to determine whether the character is trimmable or not.
     *
     * @return the trimmed sequence or the original sequence that has no leading or
     *         trailing trimmable characters.
     * @see    #isTrimmable(Paint, CharSequence)
     * @see    #trimStart(CharSequence)
     * @see    #trimEnd(CharSequence)
     *
     * @hide
     */
    @VisibleForTesting
    @NonNull
    public static CharSequence trim(@NonNull CharSequence sequence) {
        Objects.requireNonNull(sequence);

        if (TextUtils.isEmpty(sequence)) {
            return sequence;
        }

        CharSequence result = trimStart(sequence);
        if (TextUtils.isEmpty(result)) {
            return result;
        }

        return trimEnd(result);
    }
}
