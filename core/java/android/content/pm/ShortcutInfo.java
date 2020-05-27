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
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.app.Notification;
import android.app.Person;
import android.app.TaskStackBuilder;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.LocusId;
import android.content.pm.LauncherApps.ShortcutQuery;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.view.contentcapture.ContentCaptureContext;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a shortcut that can be published via {@link ShortcutManager}.
 *
 * @see ShortcutManager
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
    public static final int FLAG_ADAPTIVE_BITMAP = 1 << 9;

    /** @hide */
    public static final int FLAG_RETURNED_BY_SERVICE = 1 << 10;

    /** @hide When this is set, the bitmap icon is waiting to be saved. */
    public static final int FLAG_ICON_FILE_PENDING_SAVE = 1 << 11;

    /**
     * "Shadow" shortcuts are the ones that are restored, but the owner package hasn't been
     * installed yet.
     * @hide
     */
    public static final int FLAG_SHADOW = 1 << 12;

    /** @hide */
    public static final int FLAG_LONG_LIVED = 1 << 13;

    /**
     * TODO(b/155135057): This is a quick and temporary fix for b/155135890. ShortcutService doesn't
     *  need to be aware of the outside world. Replace this with a more extensible solution.
     * @hide
     */
    public static final int FLAG_CACHED_NOTIFICATIONS = 1 << 14;

    /** @hide */
    public static final int FLAG_HAS_ICON_URI = 1 << 15;


    /**
     * TODO(b/155135057): This is a quick and temporary fix for b/155135890. ShortcutService doesn't
     *  need to be aware of the outside world. Replace this with a more extensible solution.
     * @hide
     */
    public static final int FLAG_CACHED_BUBBLES = 1 << 30;

    /** @hide */
    public static final int FLAG_CACHED_ALL = FLAG_CACHED_NOTIFICATIONS | FLAG_CACHED_BUBBLES;

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            FLAG_DYNAMIC,
            FLAG_PINNED,
            FLAG_HAS_ICON_RES,
            FLAG_HAS_ICON_FILE,
            FLAG_KEY_FIELDS_ONLY,
            FLAG_MANIFEST,
            FLAG_DISABLED,
            FLAG_STRINGS_RESOLVED,
            FLAG_IMMUTABLE,
            FLAG_ADAPTIVE_BITMAP,
            FLAG_RETURNED_BY_SERVICE,
            FLAG_ICON_FILE_PENDING_SAVE,
            FLAG_SHADOW,
            FLAG_LONG_LIVED,
            FLAG_HAS_ICON_URI,
            FLAG_CACHED_NOTIFICATIONS,
            FLAG_CACHED_BUBBLES,
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
    public static final int CLONE_REMOVE_PERSON = 1 << 4;

    /** @hide */
    public static final int CLONE_REMOVE_FOR_CREATOR = CLONE_REMOVE_ICON | CLONE_REMOVE_RES_NAMES;

    /** @hide */
    public static final int CLONE_REMOVE_FOR_LAUNCHER = CLONE_REMOVE_ICON | CLONE_REMOVE_INTENT
            | CLONE_REMOVE_RES_NAMES | CLONE_REMOVE_PERSON;

    /** @hide */
    public static final int CLONE_REMOVE_FOR_LAUNCHER_APPROVAL = CLONE_REMOVE_INTENT
            | CLONE_REMOVE_RES_NAMES | CLONE_REMOVE_PERSON;

    /** @hide */
    public static final int CLONE_REMOVE_FOR_APP_PREDICTION = CLONE_REMOVE_ICON
            | CLONE_REMOVE_RES_NAMES;

    /** @hide */
    @IntDef(flag = true, prefix = { "CLONE_" }, value = {
            CLONE_REMOVE_ICON,
            CLONE_REMOVE_INTENT,
            CLONE_REMOVE_NON_KEY_INFO,
            CLONE_REMOVE_RES_NAMES,
            CLONE_REMOVE_PERSON,
            CLONE_REMOVE_FOR_CREATOR,
            CLONE_REMOVE_FOR_LAUNCHER,
            CLONE_REMOVE_FOR_LAUNCHER_APPROVAL,
            CLONE_REMOVE_FOR_APP_PREDICTION
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CloneFlags {}

    /**
     * Shortcut is not disabled.
     */
    public static final int DISABLED_REASON_NOT_DISABLED = 0;

    /**
     * Shortcut has been disabled by the publisher app with the
     * {@link ShortcutManager#disableShortcuts(List)} API.
     */
    public static final int DISABLED_REASON_BY_APP = 1;

    /**
     * Shortcut has been disabled due to changes to the publisher app. (e.g. a manifest shortcut
     * no longer exists.)
     */
    public static final int DISABLED_REASON_APP_CHANGED = 2;

    /**
     * Shortcut is disabled for an unknown reason.
     */
    public static final int DISABLED_REASON_UNKNOWN = 3;

    /**
     * A disabled reason that's equal to or bigger than this is due to backup and restore issue.
     * A shortcut with such a reason wil be visible to the launcher, but not to the publisher.
     * ({@link #isVisibleToPublisher()} will be false.)
     */
    private static final int DISABLED_REASON_RESTORE_ISSUE_START = 100;

    /**
     * Shortcut has been restored from the previous device, but the publisher app on the current
     * device is of a lower version. The shortcut will not be usable until the app is upgraded to
     * the same version or higher.
     */
    public static final int DISABLED_REASON_VERSION_LOWER = 100;

    /**
     * Shortcut has not been restored because the publisher app does not support backup and restore.
     */
    public static final int DISABLED_REASON_BACKUP_NOT_SUPPORTED = 101;

    /**
     * Shortcut has not been restored because the publisher app's signature has changed.
     */
    public static final int DISABLED_REASON_SIGNATURE_MISMATCH = 102;

    /**
     * Shortcut has not been restored for unknown reason.
     */
    public static final int DISABLED_REASON_OTHER_RESTORE_ISSUE = 103;

    /** @hide */
    @IntDef(prefix = { "DISABLED_REASON_" }, value = {
            DISABLED_REASON_NOT_DISABLED,
            DISABLED_REASON_BY_APP,
            DISABLED_REASON_APP_CHANGED,
            DISABLED_REASON_UNKNOWN,
            DISABLED_REASON_VERSION_LOWER,
            DISABLED_REASON_BACKUP_NOT_SUPPORTED,
            DISABLED_REASON_SIGNATURE_MISMATCH,
            DISABLED_REASON_OTHER_RESTORE_ISSUE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DisabledReason{}

    /**
     * Return a label for disabled reasons, which are *not* supposed to be shown to the user.
     * @hide
     */
    public static String getDisabledReasonDebugString(@DisabledReason int disabledReason) {
        switch (disabledReason) {
            case DISABLED_REASON_NOT_DISABLED:
                return "[Not disabled]";
            case DISABLED_REASON_BY_APP:
                return "[Disabled: by app]";
            case DISABLED_REASON_APP_CHANGED:
                return "[Disabled: app changed]";
            case DISABLED_REASON_VERSION_LOWER:
                return "[Disabled: lower version]";
            case DISABLED_REASON_BACKUP_NOT_SUPPORTED:
                return "[Disabled: backup not supported]";
            case DISABLED_REASON_SIGNATURE_MISMATCH:
                return "[Disabled: signature mismatch]";
            case DISABLED_REASON_OTHER_RESTORE_ISSUE:
                return "[Disabled: unknown restore issue]";
        }
        return "[Disabled: unknown reason:" + disabledReason + "]";
    }

    /**
     * Return a label for a disabled reason for shortcuts that are disabled due to a backup and
     * restore issue. If the reason is not due to backup & restore, then it'll return null.
     *
     * This method returns localized, user-facing strings, which will be returned by
     * {@link #getDisabledMessage()}.
     *
     * @hide
     */
    public static String getDisabledReasonForRestoreIssue(Context context,
            @DisabledReason int disabledReason) {
        final Resources res = context.getResources();

        switch (disabledReason) {
            case DISABLED_REASON_VERSION_LOWER:
                return res.getString(
                        com.android.internal.R.string.shortcut_restored_on_lower_version);
            case DISABLED_REASON_BACKUP_NOT_SUPPORTED:
                return res.getString(
                        com.android.internal.R.string.shortcut_restore_not_supported);
            case DISABLED_REASON_SIGNATURE_MISMATCH:
                return res.getString(
                        com.android.internal.R.string.shortcut_restore_signature_mismatch);
            case DISABLED_REASON_OTHER_RESTORE_ISSUE:
                return res.getString(
                        com.android.internal.R.string.shortcut_restore_unknown_issue);
            case DISABLED_REASON_UNKNOWN:
                return res.getString(
                        com.android.internal.R.string.shortcut_disabled_reason_unknown);
        }
        return null;
    }

    /** @hide */
    public static boolean isDisabledForRestoreIssue(@DisabledReason int disabledReason) {
        return disabledReason >= DISABLED_REASON_RESTORE_ISSUE_START;
    }

    /**
     * Shortcut category for messaging related actions, such as chat.
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
     * Intents *with extras removed*.
     */
    @Nullable
    private Intent[] mIntents;

    /**
     * Extras for the intents.
     */
    @Nullable
    private PersistableBundle[] mIntentPersistableExtrases;

    @Nullable
    private Person[] mPersons;

    @Nullable
    private LocusId mLocusId;

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
    private String mIconUri;

    // Internal use only.
    @Nullable
    private String mBitmapPath;

    private final int mUserId;

    /** @hide */
    public static final int VERSION_CODE_UNKNOWN = -1;

    private int mDisabledReason;

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
        mIntents = cloneIntents(b.mIntents);
        fixUpIntentExtras();
        mPersons = clonePersons(b.mPersons);
        if (b.mIsLongLived) {
            setLongLived();
        }
        mRank = b.mRank;
        mExtras = b.mExtras;
        mLocusId = b.mLocusId;

        updateTimestamp();
    }

    /**
     * Extract extras from {@link #mIntents} and set them to {@link #mIntentPersistableExtrases}
     * as {@link PersistableBundle}, and remove extras from the original intents.
     */
    private void fixUpIntentExtras() {
        if (mIntents == null) {
            mIntentPersistableExtrases = null;
            return;
        }
        mIntentPersistableExtrases = new PersistableBundle[mIntents.length];
        for (int i = 0; i < mIntents.length; i++) {
            final Intent intent = mIntents[i];
            final Bundle extras = intent.getExtras();
            if (extras == null) {
                mIntentPersistableExtrases[i] = null;
            } else {
                mIntentPersistableExtrases[i] = new PersistableBundle(extras);
                intent.replaceExtras((Bundle) null);
            }
        }
    }

    private static ArraySet<String> cloneCategories(Set<String> source) {
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

    private static Intent[] cloneIntents(Intent[] intents) {
        if (intents == null) {
            return null;
        }
        final Intent[] ret = new Intent[intents.length];
        for (int i = 0; i < ret.length; i++) {
            if (intents[i] != null) {
                ret[i] = new Intent(intents[i]);
            }
        }
        return ret;
    }

    private static PersistableBundle[] clonePersistableBundle(PersistableBundle[] bundle) {
        if (bundle == null) {
            return null;
        }
        final PersistableBundle[] ret = new PersistableBundle[bundle.length];
        for (int i = 0; i < ret.length; i++) {
            if (bundle[i] != null) {
                ret[i] = new PersistableBundle(bundle[i]);
            }
        }
        return ret;
    }

    private static Person[] clonePersons(Person[] persons) {
        if (persons == null) {
            return null;
        }
        final Person[] ret = new Person[persons.length];
        for (int i = 0; i < ret.length; i++) {
            if (persons[i] != null) {
                // Don't need to keep the icon, remove it to save space
                ret[i] = persons[i].toBuilder().setIcon(null).build();
            }
        }
        return ret;
    }

    /**
     * Throws if any of the mandatory fields is not set.
     *
     * @hide
     */
    public void enforceMandatoryFields(boolean forPinned) {
        Preconditions.checkStringNotEmpty(mId, "Shortcut ID must be provided");
        if (!forPinned) {
            Objects.requireNonNull(mActivity, "Activity must be provided");
        }
        if (mTitle == null && mTitleResId == 0) {
            throw new IllegalArgumentException("Short label must be provided");
        }
        Objects.requireNonNull(mIntents, "Shortcut Intent must be provided");
        Preconditions.checkArgument(mIntents.length > 0, "Shortcut Intent must be provided");
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
        mDisabledReason = source.mDisabledReason;
        mLocusId = source.mLocusId;

        // Just always keep it since it's cheep.
        mIconResId = source.mIconResId;

        if ((cloneFlags & CLONE_REMOVE_NON_KEY_INFO) == 0) {

            if ((cloneFlags & CLONE_REMOVE_ICON) == 0) {
                mIcon = source.mIcon;
                mBitmapPath = source.mBitmapPath;
                mIconUri = source.mIconUri;
            }

            mTitle = source.mTitle;
            mTitleResId = source.mTitleResId;
            mText = source.mText;
            mTextResId = source.mTextResId;
            mDisabledMessage = source.mDisabledMessage;
            mDisabledMessageResId = source.mDisabledMessageResId;
            mCategories = cloneCategories(source.mCategories);
            if ((cloneFlags & CLONE_REMOVE_PERSON) == 0) {
                mPersons = clonePersons(source.mPersons);
            }
            if ((cloneFlags & CLONE_REMOVE_INTENT) == 0) {
                mIntents = cloneIntents(source.mIntents);
                mIntentPersistableExtrases =
                        clonePersistableBundle(source.mIntentPersistableExtrases);
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
     *
     * @isUpdating set true if it's "update", as opposed to "replace".
     */
    public void ensureUpdatableWith(ShortcutInfo source, boolean isUpdating) {
        if (isUpdating) {
            Preconditions.checkState(isVisibleToPublisher(),
                    "[Framework BUG] Invisible shortcuts can't be updated");
        }
        Preconditions.checkState(mUserId == source.mUserId, "Owner User ID must match");
        Preconditions.checkState(mId.equals(source.mId), "ID must match");
        Preconditions.checkState(mPackageName.equals(source.mPackageName),
                "Package name must match");

        if (isVisibleToPublisher()) {
            // Don't do this check for restore-blocked shortcuts.
            Preconditions.checkState(!isImmutable(), "Target ShortcutInfo is immutable");
        }
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
        ensureUpdatableWith(source, /*isUpdating=*/ true);

        if (source.mActivity != null) {
            mActivity = source.mActivity;
        }

        if (source.mIcon != null) {
            mIcon = source.mIcon;

            mIconResId = 0;
            mIconResName = null;
            mBitmapPath = null;
            mIconUri = null;
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
        if (source.mPersons != null) {
            mPersons = clonePersons(source.mPersons);
        }
        if (source.mIntents != null) {
            mIntents = cloneIntents(source.mIntents);
            mIntentPersistableExtrases =
                    clonePersistableBundle(source.mIntentPersistableExtrases);
        }
        if (source.mRank != RANK_NOT_SET) {
            mRank = source.mRank;
        }
        if (source.mExtras != null) {
            mExtras = source.mExtras;
        }

        if (source.mLocusId != null) {
            mLocusId = source.mLocusId;
        }
    }

    /**
     * @hide
     */
    public static Icon validateIcon(Icon icon) {
        switch (icon.getType()) {
            case Icon.TYPE_RESOURCE:
            case Icon.TYPE_BITMAP:
            case Icon.TYPE_ADAPTIVE_BITMAP:
            case Icon.TYPE_URI:
            case Icon.TYPE_URI_ADAPTIVE_BITMAP:
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
                +" only the bitmap and resource types are supported");
    }

    /**
     * Builder class for {@link ShortcutInfo} objects.
     *
     * @see ShortcutManager
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

        private Intent[] mIntents;

        private Person[] mPersons;

        private boolean mIsLongLived;

        private int mRank = RANK_NOT_SET;

        private PersistableBundle mExtras;

        private LocusId mLocusId;

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
         * Sets the {@link LocusId} associated with this shortcut.
         *
         * <p>This method should be called when the {@link LocusId} is used in other places (such
         * as {@link Notification} and {@link ContentCaptureContext}) so the device's intelligence
         * services can correlate them.
         */
        @NonNull
        public Builder setLocusId(@NonNull LocusId locusId) {
            mLocusId = Objects.requireNonNull(locusId, "locusId cannot be null");
            return this;
        }

        /**
         * Sets the target activity.  A shortcut will be shown along with this activity's icon
         * on the launcher.
         *
         * When selecting a target activity, keep the following in mind:
         * <ul>
         * <li>All dynamic shortcuts must have a target activity.  When a shortcut with no target
         * activity is published using
         * {@link ShortcutManager#addDynamicShortcuts(List)} or
         * {@link ShortcutManager#setDynamicShortcuts(List)},
         * the first main activity defined in the app's <code>AndroidManifest.xml</code>
         * file is used.
         *
         * <li>Only "main" activities&mdash;ones that define the {@link Intent#ACTION_MAIN}
         * and {@link Intent#CATEGORY_LAUNCHER} intent filters&mdash;can be target
         * activities.
         *
         * <li>By default, the first main activity defined in the app's manifest is
         * the target activity.
         *
         * <li>A target activity must belong to the publisher app.
         * </ul>
         *
         * @see ShortcutInfo#getActivity()
         */
        @NonNull
        public Builder setActivity(@NonNull ComponentName activity) {
            mActivity = Objects.requireNonNull(activity, "activity cannot be null");
            return this;
        }

        /**
         * Sets an icon of a shortcut.
         *
         * <p>Icons are not available on {@link ShortcutInfo} instances
         * returned by {@link ShortcutManager} or {@link LauncherApps}.  The default launcher
         * app can use {@link LauncherApps#getShortcutIconDrawable(ShortcutInfo, int)}
         * or {@link LauncherApps#getShortcutBadgedIconDrawable(ShortcutInfo, int)} to fetch
         * shortcut icons.
         *
         * <p>Tints set with {@link Icon#setTint} or {@link Icon#setTintList} are not supported
         * and will be ignored.
         *
         * <p>Only icons created with {@link Icon#createWithBitmap(Bitmap)},
         * {@link Icon#createWithAdaptiveBitmap(Bitmap)}
         * and {@link Icon#createWithResource} are supported.
         * Other types, such as URI-based icons, are not supported.
         *
         * @see LauncherApps#getShortcutIconDrawable(ShortcutInfo, int)
         * @see LauncherApps#getShortcutBadgedIconDrawable(ShortcutInfo, int)
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
         * <p>This is a mandatory field when publishing a new shortcut with
         * {@link ShortcutManager#addDynamicShortcuts(List)} or
         * {@link ShortcutManager#setDynamicShortcuts(List)}.
         *
         * <p>This field is intended to be a concise description of a shortcut.
         *
         * <p>The recommended maximum length is 10 characters.
         *
         * @see ShortcutInfo#getShortLabel()
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
         * shows this instead of the short title when it has enough space.
         *
         * <p>The recommend maximum length is 25 characters.
         *
         * @see ShortcutInfo#getLongLabel()
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

        /**
         * Sets the message that should be shown when the user attempts to start a shortcut that
         * is disabled.
         *
         * @see ShortcutInfo#getDisabledMessage()
         */
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
         * Sets categories for a shortcut.  Launcher apps may use this information to
         * categorize shortcuts.
         *
         * @see #SHORTCUT_CATEGORY_CONVERSATION
         * @see ShortcutInfo#getCategories()
         */
        @NonNull
        public Builder setCategories(Set<String> categories) {
            mCategories = categories;
            return this;
        }

        /**
         * Sets the intent of a shortcut.  Alternatively, {@link #setIntents(Intent[])} can be used
         * to launch an activity with other activities in the back stack.
         *
         * <p>This is a mandatory field when publishing a new shortcut with
         * {@link ShortcutManager#addDynamicShortcuts(List)} or
         * {@link ShortcutManager#setDynamicShortcuts(List)}.
         *
         * <p>A shortcut can launch any intent that the publisher app has permission to
         * launch.  For example, a shortcut can launch an unexported activity within the publisher
         * app.  A shortcut intent doesn't have to point at the target activity.
         *
         * <p>The given {@code intent} can contain extras, but these extras must contain values
         * of primitive types in order for the system to persist these values.
         *
         * @see ShortcutInfo#getIntent()
         * @see #setIntents(Intent[])
         */
        @NonNull
        public Builder setIntent(@NonNull Intent intent) {
            return setIntents(new Intent[]{intent});
        }

        /**
         * Sets multiple intents instead of a single intent, in order to launch an activity with
         * other activities in back stack.  Use {@link TaskStackBuilder} to build intents. The
         * last element in the list represents the only intent that doesn't place an activity on
         * the back stack.
         * See the {@link ShortcutManager} javadoc for details.
         *
         * @see Builder#setIntent(Intent)
         * @see ShortcutInfo#getIntents()
         * @see Context#startActivities(Intent[])
         * @see TaskStackBuilder
         */
        @NonNull
        public Builder setIntents(@NonNull Intent[] intents) {
            Objects.requireNonNull(intents, "intents cannot be null");
            Objects.requireNonNull(intents.length, "intents cannot be empty");
            for (Intent intent : intents) {
                Objects.requireNonNull(intent, "intents cannot contain null");
                Objects.requireNonNull(intent.getAction(), "intent's action must be set");
            }
            // Make sure always clone incoming intents.
            mIntents = cloneIntents(intents);
            return this;
        }

        /**
         * Add a person that is relevant to this shortcut. Alternatively,
         * {@link #setPersons(Person[])} can be used to add multiple persons to a shortcut.
         *
         * <p> This is an optional field, but the addition of person may cause this shortcut to
         * appear more prominently in the user interface (e.g. ShareSheet).
         *
         * <p> A person should usually contain a uri in order to benefit from the ranking boost.
         * However, even if no uri is provided, it's beneficial to provide people in the shortcut,
         * such that listeners and voice only devices can announce and handle them properly.
         *
         * @see Person
         * @see #setPersons(Person[])
         */
        @NonNull
        public Builder setPerson(@NonNull Person person) {
            return setPersons(new Person[]{person});
        }

        /**
         * Sets multiple persons instead of a single person.
         *
         * @see Person
         * @see #setPerson(Person)
         */
        @NonNull
        public Builder setPersons(@NonNull Person[] persons) {
            Objects.requireNonNull(persons, "persons cannot be null");
            Objects.requireNonNull(persons.length, "persons cannot be empty");
            for (Person person : persons) {
                Objects.requireNonNull(person, "persons cannot contain null");
            }
            mPersons = clonePersons(persons);
            return this;
        }

        /**
         * Sets if a shortcut would be valid even if it has been unpublished/invisible by the app
         * (as a dynamic or pinned shortcut). If it is long lived, it can be cached by various
         * system services even after it has been unpublished as a dynamic shortcut.
         */
        @NonNull
        public Builder setLongLived(boolean londLived) {
            mIsLongLived = londLived;
            return this;
        }

        /**
         * "Rank" of a shortcut, which is a non-negative value that's used by the launcher app
         * to sort shortcuts.
         *
         * See {@link ShortcutInfo#getRank()} for details.
         */
        @NonNull
        public Builder setRank(int rank) {
            Preconditions.checkArgument((0 <= rank),
                    "Rank cannot be negative or bigger than MAX_RANK");
            mRank = rank;
            return this;
        }

        /**
         * Extras that the app can set for any purpose.
         *
         * <p>Apps can store arbitrary shortcut metadata in extras and retrieve the
         * metadata later using {@link ShortcutInfo#getExtras()}.
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
     * Returns the ID of a shortcut.
     *
     * <p>Shortcut IDs are unique within each publisher app and must be stable across
     * devices so that shortcuts will still be valid when restored on a different device.
     * See {@link ShortcutManager} for details.
     */
    @NonNull
    public String getId() {
        return mId;
    }

    /**
     * Gets the {@link LocusId} associated with this shortcut.
     *
     * <p>Used by the device's intelligence services to correlate objects (such as
     * {@link Notification} and {@link ContentCaptureContext}) that are correlated.
     */
    @Nullable
    public LocusId getLocusId() {
        return mLocusId;
    }

    /**
     * Return the package name of the publisher app.
     */
    @NonNull
    public String getPackage() {
        return mPackageName;
    }

    /**
     * Return the target activity.
     *
     * <p>This has nothing to do with the activity that this shortcut will launch.
     * Launcher apps should show the launcher icon for the returned activity alongside
     * this shortcut.
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
     * Returns the shortcut icon.
     *
     * @hide
     */
    @Nullable
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
     * Return the short description of a shortcut.
     *
     * @see Builder#setShortLabel(CharSequence)
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
     * Return the long description of a shortcut.
     *
     * @see Builder#setLongLabel(CharSequence)
     */
    @Nullable
    public CharSequence getLongLabel() {
        return mText;
    }

    /**
     * Returns the {@link #getLongLabel()} if it's populated, and if not, the
     * {@link #getShortLabel()}.
     * @hide
     */
    @Nullable
    public CharSequence getLabel() {
        CharSequence label = getLongLabel();
        if (TextUtils.isEmpty(label)) {
            label = getShortLabel();
        }

        return label;
    }

    /** @hide */
    public int getLongLabelResourceId() {
        return mTextResId;
    }

    /**
     * Return the message that should be shown when the user attempts to start a shortcut
     * that is disabled.
     *
     * @see Builder#setDisabledMessage(CharSequence)
     */
    @Nullable
    public CharSequence getDisabledMessage() {
        return mDisabledMessage;
    }

    /** @hide */
    public int getDisabledMessageResourceId() {
        return mDisabledMessageResId;
    }

    /** @hide */
    public void setDisabledReason(@DisabledReason int reason) {
        mDisabledReason = reason;
    }

    /**
     * Returns why a shortcut has been disabled.
     */
    @DisabledReason
    public int getDisabledReason() {
        return mDisabledReason;
    }

    /**
     * Return the shortcut's categories.
     *
     * @see Builder#setCategories(Set)
     */
    @Nullable
    public Set<String> getCategories() {
        return mCategories;
    }

    /**
     * Returns the intent that is executed when the user selects this shortcut.
     * If setIntents() was used, then return the last intent in the array.
     *
     * <p>Launcher apps <b>cannot</b> see the intent.  If a {@link ShortcutInfo} is
     * obtained via {@link LauncherApps}, then this method will always return null.
     * Launchers can only start a shortcut intent with {@link LauncherApps#startShortcut}.
     *
     * @see Builder#setIntent(Intent)
     */
    @Nullable
    public Intent getIntent() {
        if (mIntents == null || mIntents.length == 0) {
            return null;
        }
        final int last = mIntents.length - 1;
        final Intent intent = new Intent(mIntents[last]);
        return setIntentExtras(intent, mIntentPersistableExtrases[last]);
    }

    /**
     * Return the intent set with {@link Builder#setIntents(Intent[])}.
     *
     * <p>Launcher apps <b>cannot</b> see the intents.  If a {@link ShortcutInfo} is
     * obtained via {@link LauncherApps}, then this method will always return null.
     * Launchers can only start a shortcut intent with {@link LauncherApps#startShortcut}.
     *
     * @see Builder#setIntents(Intent[])
     */
    @Nullable
    public Intent[] getIntents() {
        final Intent[] ret = new Intent[mIntents.length];

        for (int i = 0; i < ret.length; i++) {
            ret[i] = new Intent(mIntents[i]);
            setIntentExtras(ret[i], mIntentPersistableExtrases[i]);
        }

        return ret;
    }

    /**
     * Return "raw" intents, which is the original intents without the extras.
     * @hide
     */
    @Nullable
    public Intent[] getIntentsNoExtras() {
        return mIntents;
    }

    /**
     * Return the Persons set with {@link Builder#setPersons(Person[])}.
     *
     * @hide
     */
    @Nullable
    @SystemApi
    public Person[] getPersons() {
        return clonePersons(mPersons);
    }

    /**
     * The extras in the intents.  We convert extras into {@link PersistableBundle} so we can
     * persist them.
     * @hide
     */
    @Nullable
    public PersistableBundle[] getIntentPersistableExtrases() {
        return mIntentPersistableExtrases;
    }

    /**
     * "Rank" of a shortcut, which is a non-negative, sequential value that's unique for each
     * {@link #getActivity} for each of the two types of shortcuts (static and dynamic).
     *
     * <p><em>Floating shortcuts</em>, or shortcuts that are neither static nor dynamic, will all
     * have rank 0, because they aren't sorted.
     *
     * See the {@link ShortcutManager}'s class javadoc for details.
     *
     * @see Builder#setRank(int)
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
     * Extras that the app can set for any purpose.
     *
     * @see Builder#setExtras(PersistableBundle)
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
     * {@link UserHandle} on which the publisher created this shortcut.
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

    /** @hide */
    public boolean isReturnedByServer() {
        return hasFlags(FLAG_RETURNED_BY_SERVICE);
    }

    /** @hide */
    public void setReturnedByServer() {
        addFlags(FLAG_RETURNED_BY_SERVICE);
    }

    /** @hide */
    public boolean isLongLived() {
        return hasFlags(FLAG_LONG_LIVED);
    }

    /** @hide */
    public void setLongLived() {
        addFlags(FLAG_LONG_LIVED);
    }

    /** @hide */
    public void setCached(@ShortcutFlags int cacheFlag) {
        addFlags(cacheFlag);
    }

    /** Return whether a shortcut is cached. */
    public boolean isCached() {
        return (getFlags() & FLAG_CACHED_ALL) != 0;
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
     * Return whether a shortcut is static; that is, whether a shortcut is
     * published from AndroidManifest.xml.  If {@code true}, the shortcut is
     * also {@link #isImmutable()}.
     *
     * <p>When an app is upgraded and a shortcut is no longer published from AndroidManifest.xml,
     * this will be set to {@code false}.  If the shortcut is not pinned, then it'll disappear.
     * However, if it's pinned, it will still be visible, {@link #isEnabled()} will be
     * {@code false} and {@link #isImmutable()} will be {@code true}.
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
     * @return true if pinned or cached, but neither static nor dynamic.
     * @hide
     */
    public boolean isFloating() {
        return (isPinned() || isCached()) && !(isDynamic() || isManifestShortcut());
    }

    /** @hide */
    public boolean isOriginallyFromManifest() {
        return hasFlags(FLAG_IMMUTABLE);
    }

    /** @hide */
    public boolean isDynamicVisible() {
        return isDynamic() && isVisibleToPublisher();
    }

    /** @hide */
    public boolean isPinnedVisible() {
        return isPinned() && isVisibleToPublisher();
    }

    /** @hide */
    public boolean isManifestVisible() {
        return isDeclaredInManifest() && isVisibleToPublisher();
    }

    /** @hide */
    public boolean isNonManifestVisible() {
        return !isDeclaredInManifest() && isVisibleToPublisher()
                && (isPinned() || isCached() || isDynamic());
    }

    /**
     * Return if a shortcut is immutable, in which case it cannot be modified with any of
     * {@link ShortcutManager} APIs.
     *
     * <p>All static shortcuts are immutable.  When a static shortcut is pinned and is then
     * disabled because it doesn't appear in AndroidManifest.xml for a newer version of the
     * app, {@link #isDeclaredInManifest()} returns {@code false}, but the shortcut
     * is still immutable.
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
        return hasFlags(FLAG_PINNED) || hasFlags(FLAG_DYNAMIC) || hasFlags(FLAG_MANIFEST)
                || isCached();
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

    /**
     * Return whether a shortcut's icon is provided via a URI.
     *
     * @hide internal/unit tests only
     */
    public boolean hasIconUri() {
        return hasFlags(FLAG_HAS_ICON_URI);
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
     * Return whether a shortcut's icon is adaptive bitmap following design guideline
     * defined in {@link android.graphics.drawable.AdaptiveIconDrawable}.
     *
     * @hide internal/unit tests only
     */
    public boolean hasAdaptiveBitmap() {
        return hasFlags(FLAG_ADAPTIVE_BITMAP);
    }

    /** @hide */
    public boolean isIconPendingSave() {
        return hasFlags(FLAG_ICON_FILE_PENDING_SAVE);
    }

    /** @hide */
    public void setIconPendingSave() {
        addFlags(FLAG_ICON_FILE_PENDING_SAVE);
    }

    /** @hide */
    public void clearIconPendingSave() {
        clearFlags(FLAG_ICON_FILE_PENDING_SAVE);
    }

    /**
     * When the system wasn't able to restore a shortcut, it'll still be registered to the system
     * but disabled, and such shortcuts will not be visible to the publisher. They're still visible
     * to launchers though.
     *
     * @hide
     */
    @TestApi
    public boolean isVisibleToPublisher() {
        return !isDisabledForRestoreIssue(mDisabledReason);
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
     *
     * <p>For performance reasons, shortcuts passed to
     * {@link LauncherApps.Callback#onShortcutsChanged(String, List, UserHandle)} as well as those
     * returned from {@link LauncherApps#getShortcuts(ShortcutQuery, UserHandle)}
     * while using the {@link ShortcutQuery#FLAG_GET_KEY_FIELDS_ONLY} option contain only key
     * information.
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
    public void setIconUri(String iconUri) {
        mIconUri = iconUri;
    }

    /**
     * Get the Uri for the icon, valid only when {@link #hasIconUri()} } is true.
     * @hide internal / tests only.
     */
    public String getIconUri() {
        return mIconUri;
    }

    /**
     * Bitmap path.  Note this will be null even if {@link #hasIconFile()} is set when the save
     * is pending.  Use {@link #isIconPendingSave()} to check it.
     *
     * @hide
     */
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
     * Replaces the intent.
     *
     * @throws IllegalArgumentException when extra is not compatible with {@link PersistableBundle}.
     *
     * @hide
     */
    public void setIntents(Intent[] intents) throws IllegalArgumentException {
        Objects.requireNonNull(intents);
        Preconditions.checkArgument(intents.length > 0);

        mIntents = cloneIntents(intents);
        fixUpIntentExtras();
    }

    /** @hide */
    public static Intent setIntentExtras(Intent intent, PersistableBundle extras) {
        if (extras == null) {
            intent.replaceExtras((Bundle) null);
        } else {
            intent.replaceExtras(new Bundle(extras));
        }
        return intent;
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
        mId = source.readString8();
        mPackageName = source.readString8();
        mActivity = source.readParcelable(cl);
        mFlags = source.readInt();
        mIconResId = source.readInt();
        mLastChangedTimestamp = source.readLong();
        mDisabledReason = source.readInt();

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
        mIntents = source.readParcelableArray(cl, Intent.class);
        mIntentPersistableExtrases = source.readParcelableArray(cl, PersistableBundle.class);
        mRank = source.readInt();
        mExtras = source.readParcelable(cl);
        mBitmapPath = source.readString8();

        mIconResName = source.readString8();
        mTitleResName = source.readString8();
        mTextResName = source.readString8();
        mDisabledMessageResName = source.readString8();

        int N = source.readInt();
        if (N == 0) {
            mCategories = null;
        } else {
            mCategories = new ArraySet<>(N);
            for (int i = 0; i < N; i++) {
                mCategories.add(source.readString8().intern());
            }
        }

        mPersons = source.readParcelableArray(cl, Person.class);
        mLocusId = source.readParcelable(cl);
        mIconUri = source.readString8();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mUserId);
        dest.writeString8(mId);
        dest.writeString8(mPackageName);
        dest.writeParcelable(mActivity, flags);
        dest.writeInt(mFlags);
        dest.writeInt(mIconResId);
        dest.writeLong(mLastChangedTimestamp);
        dest.writeInt(mDisabledReason);

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

        dest.writeParcelableArray(mIntents, flags);
        dest.writeParcelableArray(mIntentPersistableExtrases, flags);
        dest.writeInt(mRank);
        dest.writeParcelable(mExtras, flags);
        dest.writeString8(mBitmapPath);

        dest.writeString8(mIconResName);
        dest.writeString8(mTitleResName);
        dest.writeString8(mTextResName);
        dest.writeString8(mDisabledMessageResName);

        if (mCategories != null) {
            final int N = mCategories.size();
            dest.writeInt(N);
            for (int i = 0; i < N; i++) {
                dest.writeString8(mCategories.valueAt(i));
            }
        } else {
            dest.writeInt(0);
        }

        dest.writeParcelableArray(mPersons, flags);
        dest.writeParcelable(mLocusId, flags);
        dest.writeString8(mIconUri);
    }

    public static final @android.annotation.NonNull Creator<ShortcutInfo> CREATOR =
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
        return toStringInner(/* secure =*/ true, /* includeInternalData =*/ false,
                /*indent=*/ null);
    }

    /** @hide */
    public String toInsecureString() {
        return toStringInner(/* secure =*/ false, /* includeInternalData =*/ true,
                /*indent=*/ null);
    }

    /** @hide */
    public String toDumpString(String indent) {
        return toStringInner(/* secure =*/ false, /* includeInternalData =*/ true, indent);
    }

    private void addIndentOrComma(StringBuilder sb, String indent) {
        if (indent != null) {
            sb.append("\n  ");
            sb.append(indent);
        } else {
            sb.append(", ");
        }
    }

    private String toStringInner(boolean secure, boolean includeInternalData, String indent) {
        final StringBuilder sb = new StringBuilder();

        if (indent != null) {
            sb.append(indent);
        }

        sb.append("ShortcutInfo {");

        sb.append("id=");
        sb.append(secure ? "***" : mId);

        sb.append(", flags=0x");
        sb.append(Integer.toHexString(mFlags));
        sb.append(" [");
        if ((mFlags & FLAG_SHADOW) != 0) {
            // Note the shadow flag isn't actually used anywhere and it's just for dumpsys, so
            // we don't have an isXxx for this.
            sb.append("Sdw");
        }
        if (!isEnabled()) {
            sb.append("Dis");
        }
        if (isImmutable()) {
            sb.append("Im");
        }
        if (isManifestShortcut()) {
            sb.append("Man");
        }
        if (isDynamic()) {
            sb.append("Dyn");
        }
        if (isPinned()) {
            sb.append("Pin");
        }
        if (hasIconFile()) {
            sb.append("Ic-f");
        }
        if (isIconPendingSave()) {
            sb.append("Pens");
        }
        if (hasIconResource()) {
            sb.append("Ic-r");
        }
        if (hasIconUri()) {
            sb.append("Ic-u");
        }
        if (hasAdaptiveBitmap()) {
            sb.append("Ic-a");
        }
        if (hasKeyFieldsOnly()) {
            sb.append("Key");
        }
        if (hasStringResourcesResolved()) {
            sb.append("Str");
        }
        if (isReturnedByServer()) {
            sb.append("Rets");
        }
        if (isLongLived()) {
            sb.append("Liv");
        }
        sb.append("]");

        addIndentOrComma(sb, indent);

        sb.append("packageName=");
        sb.append(mPackageName);

        addIndentOrComma(sb, indent);

        sb.append("activity=");
        sb.append(mActivity);

        addIndentOrComma(sb, indent);

        sb.append("shortLabel=");
        sb.append(secure ? "***" : mTitle);
        sb.append(", resId=");
        sb.append(mTitleResId);
        sb.append("[");
        sb.append(mTitleResName);
        sb.append("]");

        addIndentOrComma(sb, indent);

        sb.append("longLabel=");
        sb.append(secure ? "***" : mText);
        sb.append(", resId=");
        sb.append(mTextResId);
        sb.append("[");
        sb.append(mTextResName);
        sb.append("]");

        addIndentOrComma(sb, indent);

        sb.append("disabledMessage=");
        sb.append(secure ? "***" : mDisabledMessage);
        sb.append(", resId=");
        sb.append(mDisabledMessageResId);
        sb.append("[");
        sb.append(mDisabledMessageResName);
        sb.append("]");

        addIndentOrComma(sb, indent);

        sb.append("disabledReason=");
        sb.append(getDisabledReasonDebugString(mDisabledReason));

        addIndentOrComma(sb, indent);

        sb.append("categories=");
        sb.append(mCategories);

        addIndentOrComma(sb, indent);

        sb.append("persons=");
        sb.append(mPersons);

        addIndentOrComma(sb, indent);

        sb.append("icon=");
        sb.append(mIcon);

        addIndentOrComma(sb, indent);

        sb.append("rank=");
        sb.append(mRank);

        sb.append(", timestamp=");
        sb.append(mLastChangedTimestamp);

        addIndentOrComma(sb, indent);

        sb.append("intents=");
        if (mIntents == null) {
            sb.append("null");
        } else {
            if (secure) {
                sb.append("size:");
                sb.append(mIntents.length);
            } else {
                final int size = mIntents.length;
                sb.append("[");
                String sep = "";
                for (int i = 0; i < size; i++) {
                    sb.append(sep);
                    sep = ", ";
                    sb.append(mIntents[i]);
                    sb.append("/");
                    sb.append(mIntentPersistableExtrases[i]);
                }
                sb.append("]");
            }
        }

        addIndentOrComma(sb, indent);

        sb.append("extras=");
        sb.append(mExtras);

        if (includeInternalData) {
            addIndentOrComma(sb, indent);

            sb.append("iconRes=");
            sb.append(mIconResId);
            sb.append("[");
            sb.append(mIconResName);
            sb.append("]");

            sb.append(", bitmapPath=");
            sb.append(mBitmapPath);

            sb.append(", iconUri=");
            sb.append(mIconUri);
        }

        if (mLocusId != null) {
            sb.append("locusId="); sb.append(mLocusId); // LocusId.toString() is PII-safe.
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
            Set<String> categories, Intent[] intentsWithExtras, int rank, PersistableBundle extras,
            long lastChangedTimestamp,
            int flags, int iconResId, String iconResName, String bitmapPath, String iconUri,
            int disabledReason, Person[] persons, LocusId locusId) {
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
        mIntents = cloneIntents(intentsWithExtras);
        fixUpIntentExtras();
        mRank = rank;
        mExtras = extras;
        mLastChangedTimestamp = lastChangedTimestamp;
        mFlags = flags;
        mIconResId = iconResId;
        mIconResName = iconResName;
        mBitmapPath = bitmapPath;
        mIconUri = iconUri;
        mDisabledReason = disabledReason;
        mPersons = persons;
        mLocusId = locusId;
    }
}
