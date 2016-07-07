/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;

// TODO Enhance javadoc
/**
 *
 * Represents a shortcut from an application.
 *
 * <p>Notes about icons:
 * <ul>
 *     <li>If an {@link Icon} is a resource, the system keeps the package name and the resource ID.
 *     Otherwise, the bitmap is fetched when it's registered to ShortcutManager,
 *     then shrunk if necessary, and persisted.
 *     <li>The system disallows byte[] icons, because they can easily go over the binder size limit.
 * </ul>
 *
 * @see {@link ShortcutManager}.
 */
public final class ShortcutInfo implements Parcelable {
    static final String TAG = "Shortcut";

    private static final String RES_TYPE_STRING = "string";

    private static final String ANDROID_PACKAGE_NAME = "android";

    private static final int IMPLICIT_RANK_MASK = 0x7fffffff;

    private static final int RANK_CHANGED_BIT = ~IMPLICIT_RANK_MASK;

    /** @hide */
    public static final int RANK_NOT_SET = Integer.MAX_VALUE;

    /** @hide */
    public static final int FLAG_DYNAMIC = 1 << 0;

    /** @hide */
    public static final int FLAG_PINNED = 1 << 1;

    /** @hide */
    public static final int FLAG_HAS_ICON_RES = 1 << 2;

    /** @hide */
    public static final int FLAG_HAS_ICON_FILE = 1 << 3;

    /** @hide */
    public static final int FLAG_KEY_FIELDS_ONLY = 1 << 4;

    /** @hide */
    public static final int FLAG_MANIFEST = 1 << 5;

    /** @hide */
    public static final int FLAG_DISABLED = 1 << 6;

    /** @hide */
    public static final int FLAG_STRINGS_RESOLVED = 1 << 7;

    /** @hide */
    public static final int FLAG_IMMUTABLE = 1 << 8;

    /** @hide */
    @IntDef(flag = true,
            value = {
            FLAG_DYNAMIC,
            FLAG_PINNED,
            FLAG_HAS_ICON_RES,
            FLAG_HAS_ICON_FILE,
            FLAG_KEY_FIELDS_ONLY,
            FLAG_MANIFEST,
            FLAG_DISABLED,
            FLAG_STRINGS_RESOLVED,
            FLAG_IMMUTABLE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ShortcutFlags {}

    // Cloning options.

    /** @hide */
    private static final int CLONE_REMOVE_ICON = 1 << 0;

    /** @hide */
    private static final int CLONE_REMOVE_INTENT = 1 << 1;

    /** @hide */
    public static final int CLONE_REMOVE_NON_KEY_INFO = 1 << 2;

    /** @hide */
    public static final int CLONE_REMOVE_RES_NAMES = 1 << 3;

    /** @hide */
    public static final int CLONE_REMOVE_FOR_CREATOR = CLONE_REMOVE_ICON | CLONE_REMOVE_RES_NAMES;

    /** @hide */
    public static final int CLONE_REMOVE_FOR_LAUNCHER = CLONE_REMOVE_ICON | CLONE_REMOVE_INTENT
            | CLONE_REMOVE_RES_NAMES;

    /** @hide */
    @IntDef(flag = true,
            value = {
                    CLONE_REMOVE_ICON,
                    CLONE_REMOVE_INTENT,
                    CLONE_REMOVE_NON_KEY_INFO,
                    CLONE_REMOVE_RES_NAMES,
                    CLONE_REMOVE_FOR_CREATOR,
                    CLONE_REMOVE_FOR_LAUNCHER
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CloneFlags {}

    /**
     * Shortcut category for
     */
    public static final String SHORTCUT_CATEGORY_CONVERSATION = "android.shortcut.conversation";

    private final String mId;

    @NonNull
    private final String mPackageName;

    @Nullable
    private ComponentName mActivity;

    @Nullable
    private Icon mIcon;

    private int mTitleResId;

    private String mTitleResName;

    @Nullable
    private CharSequence mTitle;

    private int mTextResId;

    private String mTextResName;

    @Nullable
    private CharSequence mText;

    private int mDisabledMessageResId;

    private String mDisabledMessageResName;

    @Nullable
    private CharSequence mDisabledMessage;

    @Nullable
    private ArraySet<String> mCategories;

    /**
     * Intent *with extras removed*.
     */
    @Nullable
    private Intent mIntent;

    /**
     * Extras for the intent.
     */
    @Nullable
    private PersistableBundle mIntentPersistableExtras;

    private int mRank;

    /**
     * Internally used for auto-rank-adjustment.
     *
     * RANK_CHANGED_BIT is used to denote that the rank of a shortcut is changing.
     * The rest of the bits are used to denote the order in which shortcuts are passed to
     * APIs, which is used to preserve the argument order when ranks are tie.
     */
    private int mImplicitRank;

    @Nullable
    private PersistableBundle mExtras;

    private long mLastChangedTimestamp;

    // Internal use only.
    @ShortcutFlags
    private int mFlags;

    // Internal use only.
    private int mIconResId;

    private String mIconResName;

    // Internal use only.
    @Nullable
    private String mBitmapPath;

    private final int mUserId;

    private ShortcutInfo(Builder b) {
        mUserId = b.mContext.getUserId();

        mId = Preconditions.checkStringNotEmpty(b.mId, "Shortcut ID must be provided");

        // Note we can't do other null checks here because SM.updateShortcuts() takes partial
        // information.
        mPackageName = b.mContext.getPackageName();
        mActivity = b.mActivity;
        mIcon = b.mIcon;
        mTitle = b.mTitle;
        mTitleResId = b.mTitleResId;
        mText = b.mText;
        mTextResId = b.mTextResId;
        mDisabledMessage = b.mDisabledMessage;
        mDisabledMessageResId = b.mDisabledMessageResId;
        mCategories = cloneCategories(b.mCategories);
        mIntent = b.mIntent;
        if (mIntent != null) {
            final Bundle intentExtras = mIntent.getExtras();
            if (intentExtras != null) {
                mIntent.replaceExtras((Bundle) null);
                mIntentPersistableExtras = new PersistableBundle(intentExtras);
            }
        }
        mRank = b.mRank;
        mExtras = b.mExtras;
        updateTimestamp();
    }

    private ArraySet<String> cloneCategories(Set<String> source) {
        if (source == null) {
            return null;
        }
        final ArraySet<String> ret = new ArraySet<>(source.size());
        for (CharSequence s : source) {
            if (!TextUtils.isEmpty(s)) {
                ret.add(s.toString().intern());
            }
        }
        return ret;
    }

    /**
     * Throws if any of the mandatory fields is not set.
     *
     * @hide
     */
    public void enforceMandatoryFields() {
        Preconditions.checkStringNotEmpty(mId, "Shortcut ID must be provided");
        Preconditions.checkNotNull(mActivity, "Activity must be provided");
        if (mTitle == null && mTitleResId == 0) {
            throw new IllegalArgumentException("Short label must be provided");
        }
        Preconditions.checkNotNull(mIntent, "Shortcut Intent must be provided");
    }

    /**
     * Copy constructor.
     */
    private ShortcutInfo(ShortcutInfo source, @CloneFlags int cloneFlags) {
        mUserId = source.mUserId;
        mId = source.mId;
        mPackageName = source.mPackageName;
        mActivity = source.mActivity;
        mFlags = source.mFlags;
        mLastChangedTimestamp = source.mLastChangedTimestamp;

        // Just always keep it since it's cheep.
        mIconResId = source.mIconResId;

        if ((cloneFlags & CLONE_REMOVE_NON_KEY_INFO) == 0) {

            if ((cloneFlags & CLONE_REMOVE_ICON) == 0) {
                mIcon = source.mIcon;
                mBitmapPath = source.mBitmapPath;
            }

            mTitle = source.mTitle;
            mTitleResId = source.mTitleResId;
            mText = source.mText;
            mTextResId = source.mTextResId;
            mDisabledMessage = source.mDisabledMessage;
            mDisabledMessageResId = source.mDisabledMessageResId;
            mCategories = cloneCategories(source.mCategories);
            if ((cloneFlags & CLONE_REMOVE_INTENT) == 0) {
                mIntent = source.mIntent;
                mIntentPersistableExtras = source.mIntentPersistableExtras;
            }
            mRank = source.mRank;
            mExtras = source.mExtras;

            if ((cloneFlags & CLONE_REMOVE_RES_NAMES) == 0) {
                mTitleResName = source.mTitleResName;
                mTextResName = source.mTextResName;
                mDisabledMessageResName = source.mDisabledMessageResName;
                mIconResName = source.mIconResName;
            }
        } else {
            // Set this bit.
            mFlags |= FLAG_KEY_FIELDS_ONLY;
        }
    }

    /**
     * Load a string resource from the publisher app.
     *
     * @param resId resource ID
     * @param defValue default value to be returned when the specified resource isn't found.
     */
    private CharSequence getResourceString(Resources res, int resId, CharSequence defValue) {
        try {
            return res.getString(resId);
        } catch (NotFoundException e) {
            Log.e(TAG, "Resource for ID=" + resId + " not found in package " + mPackageName);
            return defValue;
        }
    }

    /**
     * Load the string resources for the text fields and set them to the actual value fields.
     * This will set {@link #FLAG_STRINGS_RESOLVED}.
     *
     * @param res {@link Resources} for the publisher.  Must have been loaded with
     * {@link PackageManager#getResourcesForApplicationAsUser}.
     *
     * @hide
     */
    public void resolveResourceStrings(@NonNull Resources res) {
        mFlags |= FLAG_STRINGS_RESOLVED;

        if ((mTitleResId == 0) && (mTextResId == 0) && (mDisabledMessageResId == 0)) {
            return; // Bail early.
        }

        if (mTitleResId != 0) {
            mTitle = getResourceString(res, mTitleResId, mTitle);
        }
        if (mTextResId != 0) {
            mText = getResourceString(res, mTextResId, mText);
        }
        if (mDisabledMessageResId != 0) {
            mDisabledMessage = getResourceString(res, mDisabledMessageResId, mDisabledMessage);
        }
    }

    /**
     * Look up resource name for a given resource ID.
     *
     * @return a simple resource name (e.g. "text_1") when {@code withType} is false, or with the
     * type (e.g. "string/text_1").
     *
     * @hide
     */
    @VisibleForTesting
    public static String lookUpResourceName(@NonNull Resources res, int resId, boolean withType,
            @NonNull String packageName) {
        if (resId == 0) {
            return null;
        }
        try {
            final String fullName = res.getResourceName(resId);

            if (ANDROID_PACKAGE_NAME.equals(getResourcePackageName(fullName))) {
                // If it's a framework resource, the value won't change, so just return the ID
                // value as a string.
                return String.valueOf(resId);
            }
            return withType ? getResourceTypeAndEntryName(fullName)
                    : getResourceEntryName(fullName);
        } catch (NotFoundException e) {
            Log.e(TAG, "Resource name for ID=" + resId + " not found in package " + packageName
                    + ". Resource IDs may change when the application is upgraded, and the system"
                    + " may not be able to find the correct resource.");
            return null;
        }
    }

    /**
     * Extract the package name from a fully-donated resource name.
     * e.g. "com.android.app1:drawable/icon1" -> "com.android.app1"
     * @hide
     */
    @VisibleForTesting
    public static String getResourcePackageName(@NonNull String fullResourceName) {
        final int p1 = fullResourceName.indexOf(':');
        if (p1 < 0) {
            return null;
        }
        return fullResourceName.substring(0, p1);
    }

    /**
     * Extract the type name from a fully-donated resource name.
     * e.g. "com.android.app1:drawable/icon1" -> "drawable"
     * @hide
     */
    @VisibleForTesting
    public static String getResourceTypeName(@NonNull String fullResourceName) {
        final int p1 = fullResourceName.indexOf(':');
        if (p1 < 0) {
            return null;
        }
        final int p2 = fullResourceName.indexOf('/', p1 + 1);
        if (p2 < 0) {
            return null;
        }
        return fullResourceName.substring(p1 + 1, p2);
    }

    /**
     * Extract the type name + the entry name from a fully-donated resource name.
     * e.g. "com.android.app1:drawable/icon1" -> "drawable/icon1"
     * @hide
     */
    @VisibleForTesting
    public static String getResourceTypeAndEntryName(@NonNull String fullResourceName) {
        final int p1 = fullResourceName.indexOf(':');
        if (p1 < 0) {
            return null;
        }
        return fullResourceName.substring(p1 + 1);
    }

    /**
     * Extract the entry name from a fully-donated resource name.
     * e.g. "com.android.app1:drawable/icon1" -> "icon1"
     * @hide
     */
    @VisibleForTesting
    public static String getResourceEntryName(@NonNull String fullResourceName) {
        final int p1 = fullResourceName.indexOf('/');
        if (p1 < 0) {
            return null;
        }
        return fullResourceName.substring(p1 + 1);
    }

    /**
     * Return the resource ID for a given resource ID.
     *
     * Basically its' a wrapper over {@link Resources#getIdentifier(String, String, String)}, except
     * if {@code resourceName} is an integer then it'll just return its value.  (Which also the
     * aforementioned method would do internally, but not documented, so doing here explicitly.)
     *
     * @param res {@link Resources} for the publisher.  Must have been loaded with
     * {@link PackageManager#getResourcesForApplicationAsUser}.
     *
     * @hide
     */
    @VisibleForTesting
    public static int lookUpResourceId(@NonNull Resources res, @Nullable String resourceName,
            @Nullable String resourceType, String packageName) {
        if (resourceName == null) {
            return 0;
        }
        try {
            try {
                // It the name can be parsed as an integer, just use it.
                return Integer.parseInt(resourceName);
            } catch (NumberFormatException ignore) {
            }

            return res.getIdentifier(resourceName, resourceType, packageName);
        } catch (NotFoundException e) {
            Log.e(TAG, "Resource ID for name=" + resourceName + " not found in package "
                    + packageName);
            return 0;
        }
    }

    /**
     * Look up resource names from the resource IDs for the icon res and the text fields, and fill
     * in the resource name fields.
     *
     * @param res {@link Resources} for the publisher.  Must have been loaded with
     * {@link PackageManager#getResourcesForApplicationAsUser}.
     *
     * @hide
     */
    public void lookupAndFillInResourceNames(@NonNull Resources res) {
        if ((mTitleResId == 0) && (mTextResId == 0) && (mDisabledMessageResId == 0)
                && (mIconResId == 0)) {
            return; // Bail early.
        }

        // We don't need types for strings because their types are always "string".
        mTitleResName = lookUpResourceName(res, mTitleResId, /*withType=*/ false, mPackageName);
        mTextResName = lookUpResourceName(res, mTextResId, /*withType=*/ false, mPackageName);
        mDisabledMessageResName = lookUpResourceName(res, mDisabledMessageResId,
                /*withType=*/ false, mPackageName);

        // But icons have multiple possible types, so include the type.
        mIconResName = lookUpResourceName(res, mIconResId, /*withType=*/ true, mPackageName);
    }

    /**
     * Look up resource IDs from the resource names for the icon res and the text fields, and fill
     * in the resource ID fields.
     *
     * This is called when an app is updated.
     *
     * @hide
     */
    public void lookupAndFillInResourceIds(@NonNull Resources res) {
        if ((mTitleResName == null) && (mTextResName == null) && (mDisabledMessageResName == null)
                && (mIconResName == null)) {
            return; // Bail early.
        }

        mTitleResId = lookUpResourceId(res, mTitleResName, RES_TYPE_STRING, mPackageName);
        mTextResId = lookUpResourceId(res, mTextResName, RES_TYPE_STRING, mPackageName);
        mDisabledMessageResId = lookUpResourceId(res, mDisabledMessageResName, RES_TYPE_STRING,
                mPackageName);

        // mIconResName already contains the type, so the third argument is not needed.
        mIconResId = lookUpResourceId(res, mIconResName, null, mPackageName);
    }

    /**
     * Copy a {@link ShortcutInfo}, optionally removing fields.
     * @hide
     */
    public ShortcutInfo clone(@CloneFlags int cloneFlags) {
        return new ShortcutInfo(this, cloneFlags);
    }

    /**
     * @hide
     */
    public void ensureUpdatableWith(ShortcutInfo source) {
        Preconditions.checkState(mUserId == source.mUserId, "Owner User ID must match");
        Preconditions.checkState(mId.equals(source.mId), "ID must match");
        Preconditions.checkState(mPackageName.equals(source.mPackageName),
                "Package name must match");
        Preconditions.checkState(!isImmutable(), "Target ShortcutInfo is immutable");
    }

    /**
     * Copy non-null/zero fields from another {@link ShortcutInfo}.  Only "public" information
     * will be overwritten.  The timestamp will *not* be updated to be consistent with other
     * setters (and also the clock is not injectable in this file).
     *
     * - Flags will not change
     * - mBitmapPath will not change
     * - Current time will be set to timestamp
     *
     * @throws IllegalStateException if source is not compatible.
     *
     * @hide
     */
    public void copyNonNullFieldsFrom(ShortcutInfo source) {
        ensureUpdatableWith(source);

        if (source.mActivity != null) {
            mActivity = source.mActivity;
        }

        if (source.mIcon != null) {
            mIcon = source.mIcon;

            mIconResId = 0;
            mIconResName = null;
            mBitmapPath = null;
        }
        if (source.mTitle != null) {
            mTitle = source.mTitle;
            mTitleResId = 0;
            mTitleResName = null;
        } else if (source.mTitleResId != 0) {
            mTitle = null;
            mTitleResId = source.mTitleResId;
            mTitleResName = null;
        }

        if (source.mText != null) {
            mText = source.mText;
            mTextResId = 0;
            mTextResName = null;
        } else if (source.mTextResId != 0) {
            mText = null;
            mTextResId = source.mTextResId;
            mTextResName = null;
        }
        if (source.mDisabledMessage != null) {
            mDisabledMessage = source.mDisabledMessage;
            mDisabledMessageResId = 0;
            mDisabledMessageResName = null;
        } else if (source.mDisabledMessageResId != 0) {
            mDisabledMessage = null;
            mDisabledMessageResId = source.mDisabledMessageResId;
            mDisabledMessageResName = null;
        }
        if (source.mCategories != null) {
            mCategories = cloneCategories(source.mCategories);
        }
        if (source.mIntent != null) {
            mIntent = source.mIntent;
            mIntentPersistableExtras = source.mIntentPersistableExtras;
        }
        if (source.mRank != RANK_NOT_SET) {
            mRank = source.mRank;
        }
        if (source.mExtras != null) {
            mExtras = source.mExtras;
        }
    }

    /**
     * @hide
     */
    public static Icon validateIcon(Icon icon) {
        switch (icon.getType()) {
            case Icon.TYPE_RESOURCE:
            case Icon.TYPE_BITMAP:
                break; // OK
            default:
                throw getInvalidIconException();
        }
        if (icon.hasTint()) {
            throw new IllegalArgumentException("Icons with tints are not supported");
        }

        return icon;
    }

    /** @hide */
    public static IllegalArgumentException getInvalidIconException() {
        return new IllegalArgumentException("Unsupported icon type:"
                +" only bitmap, resource and content URI are supported");
    }

    /**
     * Builder class for {@link ShortcutInfo} objects.
     */
    public static class Builder {
        private final Context mContext;

        private String mId;

        private ComponentName mActivity;

        private Icon mIcon;

        private int mTitleResId;

        private CharSequence mTitle;

        private int mTextResId;

        private CharSequence mText;

        private int mDisabledMessageResId;

        private CharSequence mDisabledMessage;

        private Set<String> mCategories;

        private Intent mIntent;

        private int mRank = RANK_NOT_SET;

        private PersistableBundle mExtras;

        /**
         * Old style constructor.
         * @hide
         */
        @Deprecated
        public Builder(Context context) {
            mContext = context;
        }

        /**
         * Used with the old style constructor, kept for unit tests.
         * @hide
         */
        @NonNull
        @Deprecated
        public Builder setId(@NonNull String id) {
            mId = Preconditions.checkStringNotEmpty(id, "id cannot be empty");
            return this;
        }

        /**
         * Constructor.
         *
         * @param context Client context.
         * @param id ID of the shortcut.
         */
        public Builder(Context context, String id) {
            mContext = context;
            mId = Preconditions.checkStringNotEmpty(id, "id cannot be empty");
        }

        /**
         * Sets the target activity. A shortcut will be shown with this activity on the launcher.
         *
         * <p>Only "main" activities -- i.e. ones with an intent filter for
         * {@link Intent#ACTION_MAIN} and {@link Intent#CATEGORY_LAUNCHER} can be target activities.
         *
         * <p>By default, the first main activity defined in the application manifest will be
         * the target.
         *
         * <p>The package name of the target activity must match the package name of the shortcut
         * publisher.
         *
         * <p>This has nothing to do with the activity that this shortcut will launch.  This is
         * a hint to the launcher app about which launcher icon to associate this shortcut with.
         */
        @NonNull
        public Builder setActivity(@NonNull ComponentName activity) {
            mActivity = Preconditions.checkNotNull(activity, "activity cannot be null");
            return this;
        }

        /**
         * Sets an icon.
         *
         * <ul>
         *     <li>Tints set by {@link Icon#setTint} or {@link Icon#setTintList} are not supported.
         *     <li>Bitmaps and resources are supported, but "content:" URIs are not supported.
         * </ul>
         *
         * <p>For performance and security reasons, icons will <b>NOT</b> be available on instances
         * returned by {@link ShortcutManager} or {@link LauncherApps}.  Default launcher application
         * can use {@link LauncherApps#getShortcutIconDrawable(ShortcutInfo, int)}
         * or {@link LauncherApps#getShortcutBadgedIconDrawable(ShortcutInfo, int)} to fetch
         * shortcut icons.
         */
        @NonNull
        public Builder setIcon(Icon icon) {
            mIcon = validateIcon(icon);
            return this;
        }

        /**
         * @hide We don't support resource strings for dynamic shortcuts for now.  (But unit tests
         * use it.)
         */
        @Deprecated
        public Builder setShortLabelResId(int shortLabelResId) {
            Preconditions.checkState(mTitle == null, "shortLabel already set");
            mTitleResId = shortLabelResId;
            return this;
        }

        /**
         * Sets the short title of a shortcut.
         *
         * <p>This is a mandatory field, unless it's passed to
         * {@link ShortcutManager#updateShortcuts(List)}.
         *
         * <p>This field is intended for a concise description of a shortcut displayed under
         * an icon.  The recommend max length is 10 characters.
         */
        @NonNull
        public Builder setShortLabel(@NonNull CharSequence shortLabel) {
            Preconditions.checkState(mTitleResId == 0, "shortLabelResId already set");
            mTitle = Preconditions.checkStringNotEmpty(shortLabel, "shortLabel cannot be empty");
            return this;
        }

        /**
         * @hide We don't support resource strings for dynamic shortcuts for now.  (But unit tests
         * use it.)
         */
        @Deprecated
        public Builder setLongLabelResId(int longLabelResId) {
            Preconditions.checkState(mText == null, "longLabel already set");
            mTextResId = longLabelResId;
            return this;
        }

        /**
         * Sets the text of a shortcut.
         *
         * <p>This field is intended to be more descriptive than the shortcut title.  The launcher
         * shows this instead of the short title, when it has enough space.
         * The recommend max length is 25 characters.
         */
        @NonNull
        public Builder setLongLabel(@NonNull CharSequence longLabel) {
            Preconditions.checkState(mTextResId == 0, "longLabelResId already set");
            mText = Preconditions.checkStringNotEmpty(longLabel, "longLabel cannot be empty");
            return this;
        }

        /** @hide -- old signature, the internal code still uses it. */
        @Deprecated
        public Builder setTitle(@NonNull CharSequence value) {
            return setShortLabel(value);
        }

        /** @hide -- old signature, the internal code still uses it. */
        @Deprecated
        public Builder setTitleResId(int value) {
            return setShortLabelResId(value);
        }

        /** @hide -- old signature, the internal code still uses it. */
        @Deprecated
        public Builder setText(@NonNull CharSequence value) {
            return setLongLabel(value);
        }

        /** @hide -- old signature, the internal code still uses it. */
        @Deprecated
        public Builder setTextResId(int value) {
            return setLongLabelResId(value);
        }

        /**
         * @hide We don't support resource strings for dynamic shortcuts for now.  (But unit tests
         * use it.)
         */
        @Deprecated
        public Builder setDisabledMessageResId(int disabledMessageResId) {
            Preconditions.checkState(mDisabledMessage == null, "disabledMessage already set");
            mDisabledMessageResId = disabledMessageResId;
            return this;
        }

        @NonNull
        public Builder setDisabledMessage(@NonNull CharSequence disabledMessage) {
            Preconditions.checkState(
                    mDisabledMessageResId == 0, "disabledMessageResId already set");
            mDisabledMessage =
                    Preconditions.checkStringNotEmpty(disabledMessage,
                            "disabledMessage cannot be empty");
            return this;
        }

        /**
         * Sets categories for a shortcut.  Launcher applications may use this information to
         * categorise shortcuts.
         *
         * @see #SHORTCUT_CATEGORY_CONVERSATION
         */
        @NonNull
        public Builder setCategories(Set<String> categories) {
            mCategories = categories;
            return this;
        }

        /**
         * Sets the intent of a shortcut.  This is a mandatory field.  The extras must only contain
         * persistable information.  (See {@link PersistableBundle}).
         */
        @NonNull
        public Builder setIntent(@NonNull Intent intent) {
            mIntent = Preconditions.checkNotNull(intent, "intent cannot be null");
            Preconditions.checkNotNull(mIntent.getAction(), "intent's action must be set");
            return this;
        }

        /**
         * "Rank" of a shortcut, which is a non-negative value that's used by the launcher app
         * to sort shortcuts.
         */
        @NonNull
        public Builder setRank(int rank) {
            Preconditions.checkArgument((0 <= rank),
                    "Rank cannot be negative or bigger than MAX_RANK");
            mRank = rank;
            return this;
        }

        /**
         * Extras that application can set to any purposes.
         *
         * <p>Applications can store any meta-data of
         * shortcuts in this, and retrieve later from {@link ShortcutInfo#getExtras()}.
         */
        @NonNull
        public Builder setExtras(@NonNull PersistableBundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Creates a {@link ShortcutInfo} instance.
         */
        @NonNull
        public ShortcutInfo build() {
            return new ShortcutInfo(this);
        }
    }

    /**
     * Return the ID of the shortcut.
     */
    @NonNull
    public String getId() {
        return mId;
    }

    /**
     * Return the package name of the creator application.
     */
    @NonNull
    public String getPackage() {
        return mPackageName;
    }

    /**
     * Return the target activity, which may be null, in which case the shortcut is not associated
     * with a specific activity.
     *
     * <p>This has nothing to do with the activity that this shortcut will launch.  This is
     * a hint to the launcher app that on which launcher icon this shortcut should be shown.
     *
     * @see Builder#setActivity
     */
    @Nullable
    public ComponentName getActivity() {
        return mActivity;
    }

    /** @hide */
    public void setActivity(ComponentName activity) {
        mActivity = activity;
    }

    /**
     * Icon.
     *
     * For performance reasons, this will <b>NOT</b> be available when an instance is returned
     * by {@link ShortcutManager} or {@link LauncherApps}.  A launcher application needs to use
     * other APIs in LauncherApps to fetch the bitmap.
     *
     * @hide
     */
    @Nullable
    public Icon getIcon() {
        return mIcon;
    }

    /** @hide -- old signature, the internal code still uses it. */
    @Nullable
    @Deprecated
    public CharSequence getTitle() {
        return mTitle;
    }

    /** @hide -- old signature, the internal code still uses it. */
    @Deprecated
    public int getTitleResId() {
        return mTitleResId;
    }

    /** @hide -- old signature, the internal code still uses it. */
    @Nullable
    @Deprecated
    public CharSequence getText() {
        return mText;
    }

    /** @hide -- old signature, the internal code still uses it. */
    @Deprecated
    public int getTextResId() {
        return mTextResId;
    }

    /**
     * Return the shorter version of the shortcut title.
     *
     * <p>All shortcuts must have a non-empty title, but this method will return null when
     * {@link #hasKeyFieldsOnly()} is true.
     */
    @Nullable
    public CharSequence getShortLabel() {
        return mTitle;
    }

    /** @hide */
    public int getShortLabelResourceId() {
        return mTitleResId;
    }

    /**
     * Return the shortcut text.
     */
    @Nullable
    public CharSequence getLongLabel() {
        return mText;
    }

    /** @hide */
    public int getLongLabelResourceId() {
        return mTextResId;
    }

    /**
     * Return the message that should be shown when a shortcut in disabled state is launched.
     */
    @Nullable
    public CharSequence getDisabledMessage() {
        return mDisabledMessage;
    }

    /** @hide */
    public int getDisabledMessageResourceId() {
        return mDisabledMessageResId;
    }

    /**
     * Return the categories.
     */
    @Nullable
    public Set<String> getCategories() {
        return mCategories;
    }

    /**
     * Return the intent.
     *
     * <p>All shortcuts must have an intent, but this method will return null when
     * {@link #hasKeyFieldsOnly()} is true.
     *
     * <p>Launcher apps <b>cannot</b> see the intent.  If a {@link ShortcutInfo} is obtained via
     * {@link LauncherApps}, then this method will always return null.  Launcher apps can only
     * start a shortcut intent with {@link LauncherApps#startShortcut}.
     */
    @Nullable
    public Intent getIntent() {
        if (mIntent == null) {
            return null;
        }
        final Intent intent = new Intent(mIntent);
        intent.replaceExtras(
                mIntentPersistableExtras != null ? new Bundle(mIntentPersistableExtras) : null);
        return intent;
    }

    /**
     * Return "raw" intent, which is the original intent without the extras.
     * @hide
     */
    @Nullable
    public Intent getIntentNoExtras() {
        return mIntent;
    }

    /**
     * The extras in the intent.  We convert extras into {@link PersistableBundle} so we can
     * persist them.
     * @hide
     */
    @Nullable
    public PersistableBundle getIntentPersistableExtras() {
        return mIntentPersistableExtras;
    }

    /**
     * "Rank" of a shortcut, which is a non-negative, sequential value that's unique for each
     * {@link #getActivity} for each of the two kinds, dynamic shortcuts and manifest shortcuts.
     *
     * <p>Because manifest shortcuts and dynamic shortcuts have overlapping ranks,
     * when a launcher application shows shortcuts for an activity, it should first show
     * the manifest shortcuts followed by the dynamic shortcuts.  Within each of those categories,
     * shortcuts should be sorted by rank in ascending order.
     *
     * <p>"Floating" shortcuts (i.e. shortcuts that are neither dynamic nor manifest) will all
     * have rank 0, because there's no sorting for them.
     */
    public int getRank() {
        return mRank;
    }

    /** @hide */
    public boolean hasRank() {
        return mRank != RANK_NOT_SET;
    }

    /** @hide */
    public void setRank(int rank) {
        mRank = rank;
    }

    /** @hide */
    public void clearImplicitRankAndRankChangedFlag() {
        mImplicitRank = 0;
    }

    /** @hide */
    public void setImplicitRank(int rank) {
        // Make sure to keep RANK_CHANGED_BIT.
        mImplicitRank = (mImplicitRank & RANK_CHANGED_BIT) | (rank & IMPLICIT_RANK_MASK);
    }

    /** @hide */
    public int getImplicitRank() {
        return mImplicitRank & IMPLICIT_RANK_MASK;
    }

    /** @hide */
    public void setRankChanged() {
        mImplicitRank |= RANK_CHANGED_BIT;
    }

    /** @hide */
    public boolean isRankChanged() {
        return (mImplicitRank & RANK_CHANGED_BIT) != 0;
    }

    /**
     * Extras that application can set to any purposes.
     */
    @Nullable
    public PersistableBundle getExtras() {
        return mExtras;
    }

    /** @hide */
    public int getUserId() {
        return mUserId;
    }

    /**
     * {@link UserHandle} on which the publisher created shortcuts.
     */
    public UserHandle getUserHandle() {
        return UserHandle.of(mUserId);
    }

    /**
     * Last time when any of the fields was updated.
     */
    public long getLastChangedTimestamp() {
        return mLastChangedTimestamp;
    }

    /** @hide */
    @ShortcutFlags
    public int getFlags() {
        return mFlags;
    }

    /** @hide*/
    public void replaceFlags(@ShortcutFlags int flags) {
        mFlags = flags;
    }

    /** @hide*/
    public void addFlags(@ShortcutFlags int flags) {
        mFlags |= flags;
    }

    /** @hide*/
    public void clearFlags(@ShortcutFlags int flags) {
        mFlags &= ~flags;
    }

    /** @hide*/
    public boolean hasFlags(@ShortcutFlags int flags) {
        return (mFlags & flags) == flags;
    }

    /** Return whether a shortcut is dynamic. */
    public boolean isDynamic() {
        return hasFlags(FLAG_DYNAMIC);
    }

    /** Return whether a shortcut is pinned. */
    public boolean isPinned() {
        return hasFlags(FLAG_PINNED);
    }

    /**
     * Return whether a shortcut is published via AndroidManifest.xml or not.  If {@code true},
     * it's also {@link #isImmutable()}.
     *
     * <p>When an app is upgraded and a shortcut is no longer published from AndroidManifest.xml,
     * this will be set to {@code false}.  If the shortcut is not pinned, then it'll just disappear.
     * However, if it's pinned, it will still be alive, and {@link #isEnabled()} will be
     * {@code false} and {@link #isImmutable()} will be {@code true}.
     *
     * <p>NOTE this is whether a shortcut is published from the <b>current version's</b>
     * AndroidManifest.xml.
     */
    public boolean isDeclaredInManifest() {
        return hasFlags(FLAG_MANIFEST);
    }

    /** @hide kept for unit tests */
    @Deprecated
    public boolean isManifestShortcut() {
        return isDeclaredInManifest();
    }

    /**
     * @return true if pinned but neither dynamic nor manifest.
     * @hide
     */
    public boolean isFloating() {
        return isPinned() && !(isDynamic() || isManifestShortcut());
    }

    /** @hide */
    public boolean isOriginallyFromManifest() {
        return hasFlags(FLAG_IMMUTABLE);
    }

    /**
     * Return if a shortcut is immutable, in which case it cannot be modified with any of
     * {@link ShortcutManager} APIs.
     *
     * <p>All manifest shortcuts are immutable.  When a manifest shortcut is pinned and then
     * disabled because the app is upgraded and its AndroidManifest.xml no longer publishes it,
     * {@link #isDeclaredInManifest()} returns {@code false}, but it is still immutable.
     *
     * <p>All shortcuts originally published via the {@link ShortcutManager} APIs
     * are all mutable.
     */
    public boolean isImmutable() {
        return hasFlags(FLAG_IMMUTABLE);
    }

    /**
     * Returns {@code false} if a shortcut is disabled with
     * {@link ShortcutManager#disableShortcuts}.
     */
    public boolean isEnabled() {
        return !hasFlags(FLAG_DISABLED);
    }

    /** @hide */
    public boolean isAlive() {
        return hasFlags(FLAG_PINNED) || hasFlags(FLAG_DYNAMIC) || hasFlags(FLAG_MANIFEST);
    }

    /** @hide */
    public boolean usesQuota() {
        return hasFlags(FLAG_DYNAMIC) || hasFlags(FLAG_MANIFEST);
    }

    /**
     * Return whether a shortcut's icon is a resource in the owning package.
     *
     * @hide internal/unit tests only
     */
    public boolean hasIconResource() {
        return hasFlags(FLAG_HAS_ICON_RES);
    }

    /** @hide */
    public boolean hasStringResources() {
        return (mTitleResId != 0) || (mTextResId != 0) || (mDisabledMessageResId != 0);
    }

    /** @hide */
    public boolean hasAnyResources() {
        return hasIconResource() || hasStringResources();
    }

    /**
     * Return whether a shortcut's icon is stored as a file.
     *
     * @hide internal/unit tests only
     */
    public boolean hasIconFile() {
        return hasFlags(FLAG_HAS_ICON_FILE);
    }

    /**
     * Return whether a shortcut only contains "key" information only or not.  If true, only the
     * following fields are available.
     * <ul>
     *     <li>{@link #getId()}
     *     <li>{@link #getPackage()}
     *     <li>{@link #getActivity()}
     *     <li>{@link #getLastChangedTimestamp()}
     *     <li>{@link #isDynamic()}
     *     <li>{@link #isPinned()}
     *     <li>{@link #isDeclaredInManifest()}
     *     <li>{@link #isImmutable()}
     *     <li>{@link #isEnabled()}
     *     <li>{@link #getUserHandle()}
     * </ul>
     */
    public boolean hasKeyFieldsOnly() {
        return hasFlags(FLAG_KEY_FIELDS_ONLY);
    }

    /** @hide */
    public boolean hasStringResourcesResolved() {
        return hasFlags(FLAG_STRINGS_RESOLVED);
    }

    /** @hide */
    public void updateTimestamp() {
        mLastChangedTimestamp = System.currentTimeMillis();
    }

    /** @hide */
    // VisibleForTesting
    public void setTimestamp(long value) {
        mLastChangedTimestamp = value;
    }

    /** @hide */
    public void clearIcon() {
        mIcon = null;
    }

    /** @hide */
    public void setIconResourceId(int iconResourceId) {
        if (mIconResId != iconResourceId) {
            mIconResName = null;
        }
        mIconResId = iconResourceId;
    }

    /**
     * Get the resource ID for the icon, valid only when {@link #hasIconResource()} } is true.
     * @hide internal / tests only.
     */
    public int getIconResourceId() {
        return mIconResId;
    }

    /** @hide */
    public String getBitmapPath() {
        return mBitmapPath;
    }

    /** @hide */
    public void setBitmapPath(String bitmapPath) {
        mBitmapPath = bitmapPath;
    }

    /** @hide */
    public void setDisabledMessageResId(int disabledMessageResId) {
        if (mDisabledMessageResId != disabledMessageResId) {
            mDisabledMessageResName = null;
        }
        mDisabledMessageResId = disabledMessageResId;
        mDisabledMessage = null;
    }

    /** @hide */
    public void setDisabledMessage(String disabledMessage) {
        mDisabledMessage = disabledMessage;
        mDisabledMessageResId = 0;
        mDisabledMessageResName = null;
    }

    /** @hide */
    public String getTitleResName() {
        return mTitleResName;
    }

    /** @hide */
    public void setTitleResName(String titleResName) {
        mTitleResName = titleResName;
    }

    /** @hide */
    public String getTextResName() {
        return mTextResName;
    }

    /** @hide */
    public void setTextResName(String textResName) {
        mTextResName = textResName;
    }

    /** @hide */
    public String getDisabledMessageResName() {
        return mDisabledMessageResName;
    }

    /** @hide */
    public void setDisabledMessageResName(String disabledMessageResName) {
        mDisabledMessageResName = disabledMessageResName;
    }

    /** @hide */
    public String getIconResName() {
        return mIconResName;
    }

    /** @hide */
    public void setIconResName(String iconResName) {
        mIconResName = iconResName;
    }

    /**
     * Replaces the intent
     *
     * @throws IllegalArgumentException when extra is not compatible with {@link PersistableBundle}.
     *
     * @hide
     */
    public void setIntent(Intent intent) throws IllegalArgumentException {
        Preconditions.checkNotNull(intent);

        final Bundle intentExtras = intent.getExtras();

        mIntent = intent;

        if (intentExtras != null) {
            intent.replaceExtras((Bundle) null);
            mIntentPersistableExtras = new PersistableBundle(intentExtras);
        } else {
            mIntentPersistableExtras = null;
        }
    }

    /**
     * Replaces the categories.
     *
     * @hide
     */
    public void setCategories(Set<String> categories) {
        mCategories = cloneCategories(categories);
    }

    private ShortcutInfo(Parcel source) {
        final ClassLoader cl = getClass().getClassLoader();

        mUserId = source.readInt();
        mId = source.readString();
        mPackageName = source.readString();
        mActivity = source.readParcelable(cl);
        mFlags = source.readInt();
        mIconResId = source.readInt();
        mLastChangedTimestamp = source.readLong();

        if (source.readInt() == 0) {
            return; // key information only.
        }

        mIcon = source.readParcelable(cl);
        mTitle = source.readCharSequence();
        mTitleResId = source.readInt();
        mText = source.readCharSequence();
        mTextResId = source.readInt();
        mDisabledMessage = source.readCharSequence();
        mDisabledMessageResId = source.readInt();
        mIntent = source.readParcelable(cl);
        mIntentPersistableExtras = source.readParcelable(cl);
        mRank = source.readInt();
        mExtras = source.readParcelable(cl);
        mBitmapPath = source.readString();

        mIconResName = source.readString();
        mTitleResName = source.readString();
        mTextResName = source.readString();
        mDisabledMessageResName = source.readString();

        int N = source.readInt();
        if (N == 0) {
            mCategories = null;
        } else {
            mCategories = new ArraySet<>(N);
            for (int i = 0; i < N; i++) {
                mCategories.add(source.readString().intern());
            }
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mUserId);
        dest.writeString(mId);
        dest.writeString(mPackageName);
        dest.writeParcelable(mActivity, flags);
        dest.writeInt(mFlags);
        dest.writeInt(mIconResId);
        dest.writeLong(mLastChangedTimestamp);

        if (hasKeyFieldsOnly()) {
            dest.writeInt(0);
            return;
        }
        dest.writeInt(1);

        dest.writeParcelable(mIcon, flags);
        dest.writeCharSequence(mTitle);
        dest.writeInt(mTitleResId);
        dest.writeCharSequence(mText);
        dest.writeInt(mTextResId);
        dest.writeCharSequence(mDisabledMessage);
        dest.writeInt(mDisabledMessageResId);

        dest.writeParcelable(mIntent, flags);
        dest.writeParcelable(mIntentPersistableExtras, flags);
        dest.writeInt(mRank);
        dest.writeParcelable(mExtras, flags);
        dest.writeString(mBitmapPath);

        dest.writeString(mIconResName);
        dest.writeString(mTitleResName);
        dest.writeString(mTextResName);
        dest.writeString(mDisabledMessageResName);

        if (mCategories != null) {
            final int N = mCategories.size();
            dest.writeInt(N);
            for (int i = 0; i < N; i++) {
                dest.writeString(mCategories.valueAt(i));
            }
        } else {
            dest.writeInt(0);
        }
    }

    public static final Creator<ShortcutInfo> CREATOR =
            new Creator<ShortcutInfo>() {
                public ShortcutInfo createFromParcel(Parcel source) {
                    return new ShortcutInfo(source);
                }
                public ShortcutInfo[] newArray(int size) {
                    return new ShortcutInfo[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Return a string representation, intended for logging.  Some fields will be retracted.
     */
    @Override
    public String toString() {
        return toStringInner(/* secure =*/ true, /* includeInternalData =*/ false);
    }

    /** @hide */
    public String toInsecureString() {
        return toStringInner(/* secure =*/ false, /* includeInternalData =*/ true);
    }

    private String toStringInner(boolean secure, boolean includeInternalData) {
        final StringBuilder sb = new StringBuilder();
        sb.append("ShortcutInfo {");

        sb.append("id=");
        sb.append(secure ? "***" : mId);

        sb.append(", flags=0x");
        sb.append(Integer.toHexString(mFlags));
        sb.append(" [");
        if (!isEnabled()) {
            sb.append("X");
        }
        if (isImmutable()) {
            sb.append("Im");
        }
        if (isManifestShortcut()) {
            sb.append("M");
        }
        if (isDynamic()) {
            sb.append("D");
        }
        if (isPinned()) {
            sb.append("P");
        }
        if (hasIconFile()) {
            sb.append("If");
        }
        if (hasIconResource()) {
            sb.append("Ir");
        }
        if (hasKeyFieldsOnly()) {
            sb.append("K");
        }
        if (hasStringResourcesResolved()) {
            sb.append("Sr");
        }
        sb.append("]");

        sb.append(", packageName=");
        sb.append(mPackageName);

        sb.append(", activity=");
        sb.append(mActivity);

        sb.append(", shortLabel=");
        sb.append(secure ? "***" : mTitle);
        sb.append(", resId=");
        sb.append(mTitleResId);
        sb.append("[");
        sb.append(mTitleResName);
        sb.append("]");

        sb.append(", longLabel=");
        sb.append(secure ? "***" : mText);
        sb.append(", resId=");
        sb.append(mTextResId);
        sb.append("[");
        sb.append(mTextResName);
        sb.append("]");

        sb.append(", disabledMessage=");
        sb.append(secure ? "***" : mDisabledMessage);
        sb.append(", resId=");
        sb.append(mDisabledMessageResId);
        sb.append("[");
        sb.append(mDisabledMessageResName);
        sb.append("]");

        sb.append(", categories=");
        sb.append(mCategories);

        sb.append(", icon=");
        sb.append(mIcon);

        sb.append(", rank=");
        sb.append(mRank);

        sb.append(", timestamp=");
        sb.append(mLastChangedTimestamp);

        sb.append(", intent=");
        sb.append(mIntent);

        sb.append(", intentExtras=");
        sb.append(secure ? "***" : mIntentPersistableExtras);

        sb.append(", extras=");
        sb.append(mExtras);

        if (includeInternalData) {

            sb.append(", iconRes=");
            sb.append(mIconResId);
            sb.append("[");
            sb.append(mIconResName);
            sb.append("]");

            sb.append(", bitmapPath=");
            sb.append(mBitmapPath);
        }

        sb.append("}");
        return sb.toString();
    }

    /** @hide */
    public ShortcutInfo(
            @UserIdInt int userId, String id, String packageName, ComponentName activity,
            Icon icon, CharSequence title, int titleResId, String titleResName,
            CharSequence text, int textResId, String textResName,
            CharSequence disabledMessage, int disabledMessageResId, String disabledMessageResName,
            Set<String> categories,
            Intent intent, PersistableBundle intentPersistableExtras,
            int rank, PersistableBundle extras, long lastChangedTimestamp,
            int flags, int iconResId, String iconResName, String bitmapPath) {
        mUserId = userId;
        mId = id;
        mPackageName = packageName;
        mActivity = activity;
        mIcon = icon;
        mTitle = title;
        mTitleResId = titleResId;
        mTitleResName = titleResName;
        mText = text;
        mTextResId = textResId;
        mTextResName = textResName;
        mDisabledMessage = disabledMessage;
        mDisabledMessageResId = disabledMessageResId;
        mDisabledMessageResName = disabledMessageResName;
        mCategories = cloneCategories(categories);
        mIntent = intent;
        mIntentPersistableExtras = intentPersistableExtras;
        mRank = rank;
        mExtras = extras;
        mLastChangedTimestamp = lastChangedTimestamp;
        mFlags = flags;
        mIconResId = iconResId;
        mIconResName = iconResName;
        mBitmapPath = bitmapPath;
    }
}
