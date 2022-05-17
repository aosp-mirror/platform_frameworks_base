/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.window;

import static android.content.pm.ActivityInfo.CONFIG_SCREEN_LAYOUT;
import static android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE;
import static android.content.pm.ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Configuration;
import android.os.Parcelable;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DataClass;

import java.util.Arrays;

/**
 * Contains size-configuration buckets used to prevent excessive configuration changes during
 * resize.
 *
 * These configurations are collected from application's resources based on size-sensitive
 * qualifiers. For example, layout-w800dp will be added to mHorizontalSizeConfigurations as 800
 * and drawable-sw400dp will be added to both as 400.
 *
 * @hide
 */
@DataClass(genAidl = true)
public final class SizeConfigurationBuckets implements Parcelable {

    /** Horizontal (screenWidthDp) buckets */
    @Nullable
    private final int[] mHorizontal;

    /** Vertical (screenHeightDp) buckets */
    @Nullable
    private final int[] mVertical;

    /** Smallest (smallestScreenWidthDp) buckets */
    @Nullable
    private final int[] mSmallest;

    /** Screen Layout Size (screenLayout & SCREENLAYOUT_SIZE_MASK) buckets */
    @Nullable
    private final int[] mScreenLayoutSize;

    /**
     * Screen Layout Long (screenLayout & SCREENLAYOUT_LONG_MASK) boolean. Only need to know if a
     * value is set because only two possible buckets, SCREENLAYOUT_LONG_NO and
     * SCREENLAYOUT_LONG_YES, so if either is set, then any change is a bucket change.
     */
    private final boolean mScreenLayoutLongSet;

    public SizeConfigurationBuckets(Configuration[] sizeConfigurations) {
        SparseIntArray horizontal = new SparseIntArray();
        SparseIntArray vertical = new SparseIntArray();
        SparseIntArray smallest = new SparseIntArray();
        SparseIntArray screenLayoutSize = new SparseIntArray();
        int curScreenLayoutSize;
        boolean screenLayoutLongSet = false;
        for (int i = sizeConfigurations.length - 1; i >= 0; i--) {
            Configuration config = sizeConfigurations[i];
            if (config.screenHeightDp != Configuration.SCREEN_HEIGHT_DP_UNDEFINED) {
                vertical.put(config.screenHeightDp, 0);
            }
            if (config.screenWidthDp != Configuration.SCREEN_WIDTH_DP_UNDEFINED) {
                horizontal.put(config.screenWidthDp, 0);
            }
            if (config.smallestScreenWidthDp != Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED) {
                smallest.put(config.smallestScreenWidthDp, 0);
            }
            if ((curScreenLayoutSize = config.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK)
                    != Configuration.SCREENLAYOUT_SIZE_UNDEFINED) {
                screenLayoutSize.put(curScreenLayoutSize, 0);
            }
            if (!screenLayoutLongSet && (config.screenLayout & Configuration.SCREENLAYOUT_LONG_MASK)
                    != Configuration.SCREENLAYOUT_LONG_UNDEFINED) {
                screenLayoutLongSet = true;
            }
        }
        mHorizontal = horizontal.copyKeys();
        mVertical = vertical.copyKeys();
        mSmallest = smallest.copyKeys();
        mScreenLayoutSize = screenLayoutSize.copyKeys();
        mScreenLayoutLongSet = screenLayoutLongSet;
    }

    /**
     * Get the changes between two configurations but don't count changes in sizes if they don't
     * cross boundaries that are important to the app.
     *
     * This is a static helper to deal with null `buckets`. When no buckets have been specified,
     * this actually filters out all 3 size-configs. This is legacy behavior.
     */
    public static int filterDiff(int diff, @NonNull Configuration oldConfig,
            @NonNull Configuration newConfig, @Nullable SizeConfigurationBuckets buckets) {
        final boolean nonSizeLayoutFieldsUnchanged =
                areNonSizeLayoutFieldsUnchanged(oldConfig.screenLayout, newConfig.screenLayout);
        if (buckets == null) {
            // Only unflip CONFIG_SCREEN_LAYOUT if non-size-related  attributes of screen layout do
            // not change.
            if (nonSizeLayoutFieldsUnchanged) {
                return diff & ~(CONFIG_SCREEN_SIZE | CONFIG_SMALLEST_SCREEN_SIZE
                        | CONFIG_SCREEN_LAYOUT);
            } else {
                return diff & ~(CONFIG_SCREEN_SIZE | CONFIG_SMALLEST_SCREEN_SIZE);
            }
        }
        if ((diff & CONFIG_SCREEN_SIZE) != 0) {
            final boolean crosses = buckets.crossesHorizontalSizeThreshold(oldConfig.screenWidthDp,
                    newConfig.screenWidthDp)
                    || buckets.crossesVerticalSizeThreshold(oldConfig.screenHeightDp,
                    newConfig.screenHeightDp);
            if (!crosses) {
                diff &= ~CONFIG_SCREEN_SIZE;
            }
        }
        if ((diff & CONFIG_SMALLEST_SCREEN_SIZE) != 0) {
            final int oldSmallest = oldConfig.smallestScreenWidthDp;
            final int newSmallest = newConfig.smallestScreenWidthDp;
            if (!buckets.crossesSmallestSizeThreshold(oldSmallest, newSmallest)) {
                diff &= ~CONFIG_SMALLEST_SCREEN_SIZE;
            }
        }
        if ((diff & CONFIG_SCREEN_LAYOUT) != 0 && nonSizeLayoutFieldsUnchanged) {
            if (!buckets.crossesScreenLayoutSizeThreshold(oldConfig, newConfig)
                    && !buckets.crossesScreenLayoutLongThreshold(oldConfig.screenLayout,
                    newConfig.screenLayout)) {
                diff &= ~CONFIG_SCREEN_LAYOUT;
            }
        }
        return diff;
    }

    private boolean crossesHorizontalSizeThreshold(int firstDp, int secondDp) {
        return crossesSizeThreshold(mHorizontal, firstDp, secondDp);
    }

    private boolean crossesVerticalSizeThreshold(int firstDp, int secondDp) {
        return crossesSizeThreshold(mVertical, firstDp, secondDp);
    }

    private boolean crossesSmallestSizeThreshold(int firstDp, int secondDp) {
        return crossesSizeThreshold(mSmallest, firstDp, secondDp);
    }

    /**
     * Returns whether a screen layout size threshold has been crossed.
     */
    @VisibleForTesting
    public boolean crossesScreenLayoutSizeThreshold(@NonNull Configuration firstConfig,
            @NonNull Configuration secondConfig) {
        // If both the old and new screen layout are equal (both can be undefined), then no
        // threshold is crossed.
        if ((firstConfig.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK)
                == (secondConfig.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK)) {
            return false;
        }
        // Any time the new layout size is smaller than the old layout size, the activity has
        // crossed a size threshold because layout size represents the smallest possible size the
        // activity can occupy.
        if (!secondConfig.isLayoutSizeAtLeast(firstConfig.screenLayout
                & Configuration.SCREENLAYOUT_SIZE_MASK)) {
            return true;
        }
        // If the new layout size is at least as large as the old layout size, then check if the new
        // layout size has crossed a threshold.
        if (mScreenLayoutSize != null) {
            for (int screenLayoutSize : mScreenLayoutSize) {
                if (firstConfig.isLayoutSizeAtLeast(screenLayoutSize)
                        != secondConfig.isLayoutSizeAtLeast(screenLayoutSize)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean crossesScreenLayoutLongThreshold(int firstScreenLayout,
            int secondScreenLayout) {
        final int firstScreenLayoutLongValue = firstScreenLayout
                & Configuration.SCREENLAYOUT_LONG_MASK;
        final int secondScreenLayoutLongValue = secondScreenLayout
                & Configuration.SCREENLAYOUT_LONG_MASK;
        return mScreenLayoutLongSet && firstScreenLayoutLongValue != secondScreenLayoutLongValue;
    }

    /**
     * Returns whether non-size related screen layout attributes have changed. If true, then
     * {@link ActivityInfo#CONFIG_SCREEN_LAYOUT} should not be filtered out in
     * {@link SizeConfigurationBuckets#filterDiff()} because the non-size related attributes
     * do not have a bucket range like the size-related attributes of screen layout.
     */
    @VisibleForTesting
    public static boolean areNonSizeLayoutFieldsUnchanged(int oldScreenLayout,
            int newScreenLayout) {
        final int nonSizeRelatedFields = Configuration.SCREENLAYOUT_LAYOUTDIR_MASK
                | Configuration.SCREENLAYOUT_ROUND_MASK | Configuration.SCREENLAYOUT_COMPAT_NEEDED;
        return (oldScreenLayout & nonSizeRelatedFields) == (newScreenLayout & nonSizeRelatedFields);
    }

    /**
     * The purpose of this method is to decide whether the activity needs to be relaunched upon
     * changing its size. In most cases the activities don't need to be relaunched, if the resize
     * is small, all the activity content has to do is relayout itself within new bounds. There are
     * cases however, where the activity's content would be completely changed in the new size and
     * the full relaunch is required.
     *
     * The activity will report to us vertical and horizontal thresholds after which a relaunch is
     * required. These thresholds are collected from the application resource qualifiers. For
     * example, if application has layout-w600dp resource directory, then it needs a relaunch when
     * we resize from width of 650dp to 550dp, as it crosses the 600dp threshold. However, if
     * it resizes width from 620dp to 700dp, it won't be relaunched as it stays on the same side
     * of the threshold.
     */
    @VisibleForTesting
    public static boolean crossesSizeThreshold(int[] thresholds, int firstDp,
            int secondDp) {
        if (thresholds == null) {
            return false;
        }
        for (int i = thresholds.length - 1; i >= 0; i--) {
            final int threshold = thresholds[i];
            if ((firstDp < threshold && secondDp >= threshold)
                    || (firstDp >= threshold && secondDp < threshold)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return Arrays.toString(mHorizontal) + " " + Arrays.toString(mVertical) + " "
                + Arrays.toString(mSmallest) + " " + Arrays.toString(mScreenLayoutSize) + " "
                + mScreenLayoutLongSet;
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/window/SizeConfigurationBuckets.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /**
     * Creates a new SizeConfigurationBuckets.
     *
     * @param horizontal
     *   Horizontal (screenWidthDp) buckets
     * @param vertical
     *   Vertical (screenHeightDp) buckets
     * @param smallest
     *   Smallest (smallestScreenWidthDp) buckets
     * @param screenLayoutSize
     *   Screen Layout Size (screenLayout & SCREENLAYOUT_SIZE_MASK) buckets
     * @param screenLayoutLongSet
     *   Screen Layout Long (screenLayout & SCREENLAYOUT_LONG_MASK) boolean. Only need to know if a
     *   value is set because only two possible buckets, SCREENLAYOUT_LONG_NO and
     *   SCREENLAYOUT_LONG_YES, so if either is set, then any change is a bucket change.
     */
    @DataClass.Generated.Member
    public SizeConfigurationBuckets(
            @Nullable int[] horizontal,
            @Nullable int[] vertical,
            @Nullable int[] smallest,
            @Nullable int[] screenLayoutSize,
            boolean screenLayoutLongSet) {
        this.mHorizontal = horizontal;
        this.mVertical = vertical;
        this.mSmallest = smallest;
        this.mScreenLayoutSize = screenLayoutSize;
        this.mScreenLayoutLongSet = screenLayoutLongSet;

        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * Horizontal (screenWidthDp) buckets
     */
    @DataClass.Generated.Member
    public @Nullable int[] getHorizontal() {
        return mHorizontal;
    }

    /**
     * Vertical (screenHeightDp) buckets
     */
    @DataClass.Generated.Member
    public @Nullable int[] getVertical() {
        return mVertical;
    }

    /**
     * Smallest (smallestScreenWidthDp) buckets
     */
    @DataClass.Generated.Member
    public @Nullable int[] getSmallest() {
        return mSmallest;
    }

    /**
     * Screen Layout Size (screenLayout & SCREENLAYOUT_SIZE_MASK) buckets
     */
    @DataClass.Generated.Member
    public @Nullable int[] getScreenLayoutSize() {
        return mScreenLayoutSize;
    }

    /**
     * Screen Layout Long (screenLayout & SCREENLAYOUT_LONG_MASK) boolean. Only need to know if a
     * value is set because only two possible buckets, SCREENLAYOUT_LONG_NO and
     * SCREENLAYOUT_LONG_YES, so if either is set, then any change is a bucket change.
     */
    @DataClass.Generated.Member
    public boolean isScreenLayoutLongSet() {
        return mScreenLayoutLongSet;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mScreenLayoutLongSet) flg |= 0x10;
        if (mHorizontal != null) flg |= 0x1;
        if (mVertical != null) flg |= 0x2;
        if (mSmallest != null) flg |= 0x4;
        if (mScreenLayoutSize != null) flg |= 0x8;
        dest.writeByte(flg);
        if (mHorizontal != null) dest.writeIntArray(mHorizontal);
        if (mVertical != null) dest.writeIntArray(mVertical);
        if (mSmallest != null) dest.writeIntArray(mSmallest);
        if (mScreenLayoutSize != null) dest.writeIntArray(mScreenLayoutSize);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ SizeConfigurationBuckets(@NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        boolean screenLayoutLongSet = (flg & 0x10) != 0;
        int[] horizontal = (flg & 0x1) == 0 ? null : in.createIntArray();
        int[] vertical = (flg & 0x2) == 0 ? null : in.createIntArray();
        int[] smallest = (flg & 0x4) == 0 ? null : in.createIntArray();
        int[] screenLayoutSize = (flg & 0x8) == 0 ? null : in.createIntArray();

        this.mHorizontal = horizontal;
        this.mVertical = vertical;
        this.mSmallest = smallest;
        this.mScreenLayoutSize = screenLayoutSize;
        this.mScreenLayoutLongSet = screenLayoutLongSet;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<SizeConfigurationBuckets> CREATOR
            = new Parcelable.Creator<SizeConfigurationBuckets>() {
        @Override
        public SizeConfigurationBuckets[] newArray(int size) {
            return new SizeConfigurationBuckets[size];
        }

        @Override
        public SizeConfigurationBuckets createFromParcel(@NonNull android.os.Parcel in) {
            return new SizeConfigurationBuckets(in);
        }
    };

    @DataClass.Generated(
            time = 1628273704583L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/window/SizeConfigurationBuckets.java",
            inputSignatures = "private final @android.annotation.Nullable int[] mHorizontal\nprivate final @android.annotation.Nullable int[] mVertical\nprivate final @android.annotation.Nullable int[] mSmallest\nprivate final @android.annotation.Nullable int[] mScreenLayoutSize\nprivate final  boolean mScreenLayoutLongSet\npublic static  int filterDiff(int,android.content.res.Configuration,android.content.res.Configuration,android.window.SizeConfigurationBuckets)\nprivate  boolean crossesHorizontalSizeThreshold(int,int)\nprivate  boolean crossesVerticalSizeThreshold(int,int)\nprivate  boolean crossesSmallestSizeThreshold(int,int)\npublic @com.android.internal.annotations.VisibleForTesting boolean crossesScreenLayoutSizeThreshold(android.content.res.Configuration,android.content.res.Configuration)\nprivate  boolean crossesScreenLayoutLongThreshold(int,int)\npublic static @com.android.internal.annotations.VisibleForTesting boolean areNonSizeLayoutFieldsUnchanged(int,int)\npublic static @com.android.internal.annotations.VisibleForTesting boolean crossesSizeThreshold(int[],int,int)\npublic @java.lang.Override java.lang.String toString()\nclass SizeConfigurationBuckets extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genAidl=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
