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
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.util.ArraySet;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
    /* @hide */
    public static final int FLAG_DYNAMIC = 1 << 0;

    /* @hide */
    public static final int FLAG_PINNED = 1 << 1;

    /* @hide */
    public static final int FLAG_HAS_ICON_RES = 1 << 2;

    /* @hide */
    public static final int FLAG_HAS_ICON_FILE = 1 << 3;

    /* @hide */
    public static final int FLAG_KEY_FIELDS_ONLY = 1 << 4;

    /* @hide */
    public static final int FLAG_MANIFEST = 1 << 5;

    /* @hide */
    public static final int FLAG_DISABLED = 1 << 6;

    /* @hide */
    public static final int FLAG_STRINGS_RESOLVED = 1 << 7;

    /* @hide */
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

    /* @hide */
    private static final int CLONE_REMOVE_ICON = 1 << 0;

    /* @hide */
    private static final int CLONE_REMOVE_INTENT = 1 << 1;

    /* @hide */
    public static final int CLONE_REMOVE_NON_KEY_INFO = 1 << 2;

    /* @hide */
    public static final int CLONE_REMOVE_FOR_CREATOR = CLONE_REMOVE_ICON;

    /* @hide */
    public static final int CLONE_REMOVE_FOR_LAUNCHER = CLONE_REMOVE_ICON | CLONE_REMOVE_INTENT;

    /** @hide */
    @IntDef(flag = true,
            value = {
                    CLONE_REMOVE_ICON,
                    CLONE_REMOVE_INTENT,
                    CLONE_REMOVE_NON_KEY_INFO,
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

    @Nullable
    private CharSequence mTitle;

    private int mTextResId;

    @Nullable
    private CharSequence mText;

    private int mDisabledMessageResId;

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

    @Nullable
    private PersistableBundle mExtras;

    private long mLastChangedTimestamp;

    // Internal use only.
    @ShortcutFlags
    private int mFlags;

    // Internal use only.
    private int mIconResourceId;

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
        mCategories = clone(b.mCategories);
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

    private <T> ArraySet<T> clone(Set<T> source) {
        return (source == null) ? null : new ArraySet<>(source);
    }

    /**
     * Throws if any of the mandatory fields is not set.
     *
     * @hide
     */
    public void enforceMandatoryFields() {
        Preconditions.checkStringNotEmpty(mId, "Shortcut ID must be provided");
        Preconditions.checkNotNull(mActivity, "activity must be provided");
        if (mTitle == null && mTitleResId == 0) {
            throw new IllegalArgumentException("Shortcut title must be provided");
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
        mFlags = source.mFlags;
        mLastChangedTimestamp = source.mLastChangedTimestamp;

        // Just always keep it since it's cheep.
        mIconResourceId = source.mIconResourceId;

        if ((cloneFlags & CLONE_REMOVE_NON_KEY_INFO) == 0) {
            mActivity = source.mActivity;

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
            mCategories = clone(source.mCategories);
            if ((cloneFlags & CLONE_REMOVE_INTENT) == 0) {
                mIntent = source.mIntent;
                mIntentPersistableExtras = source.mIntentPersistableExtras;
            }
            mRank = source.mRank;
            mExtras = source.mExtras;
        } else {
            // Set this bit.
            mFlags |= FLAG_KEY_FIELDS_ONLY;
        }
    }

    /** @hide */
    public void resolveStringsRequiringCrossUser(Context context) throws NameNotFoundException {
        mFlags |= FLAG_STRINGS_RESOLVED;

        if ((mTitleResId == 0) && (mTextResId == 0) && (mDisabledMessageResId == 0)) {
            return; // Bail early.
        }
        final Resources res = context.getPackageManager().getResourcesForApplicationAsUser(
                mPackageName, mUserId);

        if (mTitleResId != 0) {
            mTitle = res.getString(mTitleResId);
            mTitleResId = 0;
        }
        if (mTextResId != 0) {
            mText = res.getString(mTextResId);
            mTextResId = 0;
        }
        if (mDisabledMessageResId != 0) {
            mDisabledMessage = res.getString(mDisabledMessageResId);
            mDisabledMessageResId = 0;
        }
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
     * will be overwritten.  The timestamp will be updated.
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
        }
        if (source.mTitle != null) {
            mTitle = source.mTitle;
            mTitleResId = 0;
        } else if (source.mTitleResId != 0) {
            mTitle = null;
            mTitleResId = source.getTitleResId();
        }
        if (source.mText != null) {
            mText = source.mText;
            mTextResId = 0;
        } else if (source.mTextResId != 0) {
            mText = null;
            mTextResId = source.mTextResId;
        }
        if (source.mDisabledMessage != null) {
            mDisabledMessage = source.mDisabledMessage;
            mDisabledMessageResId = 0;
        } else if (source.mDisabledMessageResId != 0) {
            mDisabledMessage = null;
            mDisabledMessageResId = source.mDisabledMessageResId;
        }
        if (source.mCategories != null) {
            mCategories = clone(source.mCategories);
        }
        if (source.mIntent != null) {
            mIntent = source.mIntent;
            mIntentPersistableExtras = source.mIntentPersistableExtras;
        }
        if (source.mRank != 0) {
            mRank = source.mRank;
        }
        if (source.mExtras != null) {
            mExtras = source.mExtras;
        }

        updateTimestamp();
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

        private int mRank;

        private PersistableBundle mExtras;

        /** Constructor. */
        public Builder(Context context) {
            mContext = context;
        }

        /**
         * Sets the ID of the shortcut.  This is a mandatory field.
         */
        @NonNull
        public Builder setId(@NonNull String id) {
            mId = Preconditions.checkStringNotEmpty(id, "id");
            return this;
        }

        /**
         * Optionally sets the target activity.  If it's not set, and if the caller application
         * has multiple launcher icons, this shortcut will be shown on all those icons.
         * If it's set, this shortcut will be only shown on this activity.
         *
         * <p>The package name of the target activity must match the package name of the shortcut
         * publisher.
         *
         * <p>This has nothing to do with the activity that this shortcut will launch.  This is
         * a hint to the launcher app about which launcher icon to associate this shortcut with.
         */
        @NonNull
        public Builder setActivity(@NonNull ComponentName activity) {
            mActivity = Preconditions.checkNotNull(activity, "activity");
            return this;
        }

        /**
         * Optionally sets an icon.
         *
         * <ul>
         *     <li>Tints set by {@link Icon#setTint} or {@link Icon#setTintList} are not supported.
         *     <li>Bitmaps and resources are supported, but "content:" URIs are not supported.
         * </ul>
         *
         * <p>For performance reasons, icons will <b>NOT</b> be available on instances
         * returned by {@link ShortcutManager} or {@link LauncherApps}.  Launcher applications
         * can use {@link ShortcutInfo#getIconResourceId()} if {@link #hasIconResource()} is true.
         * Otherwise, if {@link #hasIconFile()} is true, use
         * {@link LauncherApps#getShortcutIconFd} to load the image.
         */
        @NonNull
        public Builder setIcon(Icon icon) {
            mIcon = validateIcon(icon);
            return this;
        }

        /** @hide */
        public Builder setTitleResId(int titleResId) {
            Preconditions.checkState(mTitle == null, "title already set");
            mTitleResId = titleResId;
            return this;
        }

        /**
         * Sets the title of a shortcut.  This is a mandatory field.
         *
         * <p>This field is intended for a concise description of a shortcut displayed under
         * an icon.  The recommend max length is 10 characters.
         */
        @NonNull
        public Builder setTitle(@NonNull String title) {
            Preconditions.checkState(mTitleResId == 0, "titleResId already set");
            mTitle = Preconditions.checkStringNotEmpty(title, "title");
            return this;
        }

        /** @hide */
        public Builder setTextResId(int textResId) {
            Preconditions.checkState(mText == null, "text already set");
            mTextResId = textResId;
            return this;
        }

        /**
         * Sets the text of a shortcut.  This is an optional field.
         *
         * <p>This field is intended to be more descriptive than the shortcut title.
         * The recommend max length is 25 characters.
         */
        @NonNull
        public Builder setText(@NonNull String text) {
            Preconditions.checkState(mTextResId == 0, "textResId already set");
            mText = Preconditions.checkStringNotEmpty(text, "text");
            return this;
        }

        /** @hide */
        public Builder setDisabledMessageResId(int disabledMessageResId) {
            Preconditions.checkState(mDisabledMessage == null, "disabledMessage already set");
            mDisabledMessageResId = disabledMessageResId;
            return this;
        }

        @NonNull
        public Builder setDisabledMessage(@NonNull String disabledMessage) {
            Preconditions.checkState(
                    mDisabledMessageResId == 0, "disabledMessageResId already set");
            mDisabledMessage =
                    Preconditions.checkStringNotEmpty(disabledMessage, "disabledMessage");
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
            mIntent = Preconditions.checkNotNull(intent, "intent");
            Preconditions.checkNotNull(mIntent.getAction(), "Intent action must be set.");
            return this;
        }

        /**
         * TODO javadoc.
         */
        @NonNull
        public Builder setRank(int rank) {
            mRank = rank;
            return this;
        }

        /**
         * Optional values that applications can set.  Applications can store any meta-data of
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

    /**
     * Return the shortcut title.
     *
     * <p>All shortcuts must have a non-empty title, but this method will return null when
     * {@link #hasKeyFieldsOnly()} is true.
     */
    @Nullable
    public CharSequence getTitle() {
        return mTitle;
    }

    /** TODO Javadoc */
    public int getTitleResId() {
        return mTitleResId;
    }

    /**
     * Return the shortcut text.
     */
    @Nullable
    public CharSequence getText() {
        return mText;
    }

    /** TODO Javadoc */
    public int getTextResId() {
        return mTextResId;
    }

    /**
     * Return the message that should be shown when a shortcut in disabled state is launched.
     */
    @Nullable
    public CharSequence getDisabledMessage() {
        return mDisabledMessage;
    }

    /** TODO Javadoc */
    public int getDisabledMessageResId() {
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
     * TODO Javadoc
     */
    public int getRank() {
        return mRank;
    }

    /**
     * Optional values that application can set.
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
    public boolean isManifestShortcut() {
        return hasFlags(FLAG_MANIFEST);
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
     * {@link #isManifestShortcut} returns {@code false}, but it is still immutable.
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
     * @see LauncherApps#getShortcutIconResId(ShortcutInfo)
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
     * @see LauncherApps#getShortcutIconFd(ShortcutInfo)
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
     *     <li>{@link #getLastChangedTimestamp()}
     *     <li>{@link #isDynamic()}
     *     <li>{@link #isPinned()}
     *     <li>{@link #hasIconResource()}
     *     <li>{@link #hasIconFile()}
     * </ul>
     */
    public boolean hasKeyFieldsOnly() {
        return hasFlags(FLAG_KEY_FIELDS_ONLY);
    }

    /** TODO Javadoc */
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
        mIconResourceId = iconResourceId;
    }

    /**
     * Get the resource ID for the icon, valid only when {@link #hasIconResource()} } is true.
     */
    public int getIconResourceId() {
        return mIconResourceId;
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
        mDisabledMessageResId = disabledMessageResId;
        mDisabledMessage = null;
    }

    /** @hide */
    public void setDisabledMessage(String disabledMessage) {
        mDisabledMessage = disabledMessage;
        mDisabledMessageResId = 0;
    }

    private ShortcutInfo(Parcel source) {
        final ClassLoader cl = getClass().getClassLoader();

        mUserId = source.readInt();
        mId = source.readString();
        mPackageName = source.readString();
        mActivity = source.readParcelable(cl);
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
        mLastChangedTimestamp = source.readLong();
        mFlags = source.readInt();
        mIconResourceId = source.readInt();
        mBitmapPath = source.readString();

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
        dest.writeLong(mLastChangedTimestamp);
        dest.writeInt(mFlags);
        dest.writeInt(mIconResourceId);
        dest.writeString(mBitmapPath);

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

        sb.append(", title=");
        sb.append(secure ? "***" : mTitle);
        sb.append(", titleResId=");
        sb.append(mTitleResId);

        sb.append(", text=");
        sb.append(secure ? "***" : mText);
        sb.append(", textResId=");
        sb.append(mTextResId);

        sb.append(", disabledMessage=");
        sb.append(secure ? "***" : mDisabledMessage);
        sb.append(", disabledMessageResId=");
        sb.append(mDisabledMessageResId);

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
            sb.append(mIconResourceId);

            sb.append(", bitmapPath=");
            sb.append(mBitmapPath);
        }

        sb.append("}");
        return sb.toString();
    }

    /** @hide */
    public ShortcutInfo(
            @UserIdInt int userId, String id, String packageName, ComponentName activity,
            Icon icon, CharSequence title, int titleResId, CharSequence text, int textResId,
            CharSequence disabledMessage, int disabledMessageResId, Set<String> categories,
            Intent intent, PersistableBundle intentPersistableExtras,
            int rank, PersistableBundle extras, long lastChangedTimestamp,
            int flags, int iconResId, String bitmapPath) {
        mUserId = userId;
        mId = id;
        mPackageName = packageName;
        mActivity = activity;
        mIcon = icon;
        mTitle = title;
        mTitleResId = titleResId;
        mText = text;
        mTextResId = textResId;
        mDisabledMessage = disabledMessage;
        mDisabledMessageResId = disabledMessageResId;
        mCategories = clone(categories);
        mIntent = intent;
        mIntentPersistableExtras = intentPersistableExtras;
        mRank = rank;
        mExtras = extras;
        mLastChangedTimestamp = lastChangedTimestamp;
        mFlags = flags;
        mIconResourceId = iconResId;
        mBitmapPath = bitmapPath;
    }
}
