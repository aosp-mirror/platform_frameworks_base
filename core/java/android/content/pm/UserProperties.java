/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.multiuser.Flags.FLAG_SUPPORT_HIDING_PROFILES;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Class holding the properties of a user that derive mostly from its user type.
 *
 * @hide
 */
@SystemApi
public final class UserProperties implements Parcelable {
    private static final String LOG_TAG = UserProperties.class.getSimpleName();

    // Attribute strings for reading/writing properties to/from XML.
    private static final String ATTR_SHOW_IN_LAUNCHER = "showInLauncher";
    private static final String ATTR_START_WITH_PARENT = "startWithParent";
    private static final String ATTR_SHOW_IN_SETTINGS = "showInSettings";
    private static final String ATTR_SHOW_IN_QUIET_MODE = "showInQuietMode";
    private static final String ATTR_SHOW_IN_SHARING_SURFACES = "showInSharingSurfaces";
    private static final String ATTR_INHERIT_DEVICE_POLICY = "inheritDevicePolicy";
    private static final String ATTR_USE_PARENTS_CONTACTS = "useParentsContacts";
    private static final String ATTR_UPDATE_CROSS_PROFILE_INTENT_FILTERS_ON_OTA =
            "updateCrossProfileIntentFiltersOnOTA";
    private static final String ATTR_CROSS_PROFILE_INTENT_FILTER_ACCESS_CONTROL =
            "crossProfileIntentFilterAccessControl";
    private static final String ATTR_CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY =
            "crossProfileIntentResolutionStrategy";
    private static final String ATTR_MEDIA_SHARED_WITH_PARENT =
            "mediaSharedWithParent";
    private static final String ATTR_CREDENTIAL_SHAREABLE_WITH_PARENT =
            "credentialShareableWithParent";
    private static final String ATTR_AUTH_ALWAYS_REQUIRED_TO_DISABLE_QUIET_MODE =
            "authAlwaysRequiredToDisableQuietMode";
    private static final String ATTR_DELETE_APP_WITH_PARENT = "deleteAppWithParent";
    private static final String ATTR_ALWAYS_VISIBLE = "alwaysVisible";
    private static final String ATTR_ALLOW_STOPPING_USER_WITH_DELAYED_LOCKING =
            "allowStoppingUserWithDelayedLocking";

    private static final String ATTR_CROSS_PROFILE_CONTENT_SHARING_STRATEGY =
            "crossProfileContentSharingStrategy";
    private static final String ATTR_PROFILE_API_VISIBILITY = "profileApiVisibility";
    /** Index values of each property (to indicate whether they are present in this object). */
    @IntDef(prefix = "INDEX_", value = {
            INDEX_SHOW_IN_LAUNCHER,
            INDEX_START_WITH_PARENT,
            INDEX_SHOW_IN_SETTINGS,
            INDEX_INHERIT_DEVICE_POLICY,
            INDEX_USE_PARENTS_CONTACTS,
            INDEX_UPDATE_CROSS_PROFILE_INTENT_FILTERS_ON_OTA,
            INDEX_CROSS_PROFILE_INTENT_FILTER_ACCESS_CONTROL,
            INDEX_CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY,
            INDEX_MEDIA_SHARED_WITH_PARENT,
            INDEX_CREDENTIAL_SHAREABLE_WITH_PARENT,
            INDEX_DELETE_APP_WITH_PARENT,
            INDEX_ALWAYS_VISIBLE,
            INDEX_SHOW_IN_QUIET_MODE,
            INDEX_SHOW_IN_SHARING_SURFACES,
            INDEX_AUTH_ALWAYS_REQUIRED_TO_DISABLE_QUIET_MODE,
            INDEX_CROSS_PROFILE_CONTENT_SHARING_STRATEGY,
            INDEX_ALLOW_STOPPING_USER_WITH_DELAYED_LOCKING,
            INDEX_PROFILE_API_VISIBILITY
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface PropertyIndex {
    }
    private static final int INDEX_SHOW_IN_LAUNCHER = 0;
    private static final int INDEX_START_WITH_PARENT = 1;
    private static final int INDEX_SHOW_IN_SETTINGS = 2;
    private static final int INDEX_INHERIT_DEVICE_POLICY = 3;
    private static final int INDEX_USE_PARENTS_CONTACTS = 4;
    private static final int INDEX_UPDATE_CROSS_PROFILE_INTENT_FILTERS_ON_OTA = 5;
    private static final int INDEX_CROSS_PROFILE_INTENT_FILTER_ACCESS_CONTROL = 6;
    private static final int INDEX_CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY = 7;
    private static final int INDEX_MEDIA_SHARED_WITH_PARENT = 8;
    private static final int INDEX_CREDENTIAL_SHAREABLE_WITH_PARENT = 9;
    private static final int INDEX_DELETE_APP_WITH_PARENT = 10;
    private static final int INDEX_ALWAYS_VISIBLE = 11;
    private static final int INDEX_SHOW_IN_QUIET_MODE = 12;
    private static final int INDEX_AUTH_ALWAYS_REQUIRED_TO_DISABLE_QUIET_MODE = 13;
    private static final int INDEX_SHOW_IN_SHARING_SURFACES = 14;
    private static final int INDEX_CROSS_PROFILE_CONTENT_SHARING_STRATEGY = 15;
    private static final int INDEX_ALLOW_STOPPING_USER_WITH_DELAYED_LOCKING = 16;
    private static final int INDEX_PROFILE_API_VISIBILITY = 17;
    /** A bit set, mapping each PropertyIndex to whether it is present (1) or absent (0). */
    private long mPropertiesPresent = 0;


    /**
     * Possible values for whether or how to show this user in the Launcher.
     * @hide
     */
    @IntDef(prefix = "SHOW_IN_LAUNCHER_", value = {
            SHOW_IN_LAUNCHER_UNKNOWN,
            SHOW_IN_LAUNCHER_WITH_PARENT,
            SHOW_IN_LAUNCHER_SEPARATE,
            SHOW_IN_LAUNCHER_NO,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ShowInLauncher {
    }
    /**
     * Indicates that the show in launcher value for this profile is unknown or unsupported.
     * @hide
     */
    @TestApi
    @SuppressLint("UnflaggedApi") // b/306636213
    public static final int SHOW_IN_LAUNCHER_UNKNOWN = -1;
    /**
     * Suggests that the launcher should show this user's apps in the main tab.
     * That is, either this user is a full user, so its apps should be presented accordingly, or, if
     * this user is a profile, then its apps should be shown alongside its parent's apps.
     * @hide
     */
    @TestApi
    public static final int SHOW_IN_LAUNCHER_WITH_PARENT = 0;
    /**
     * Suggests that the launcher should show this user's apps, but separately from the apps of this
     * user's parent.
     * @hide
     */
    @TestApi
    public static final int SHOW_IN_LAUNCHER_SEPARATE = 1;
    /**
     * Suggests that the launcher should not show this user.
     * @hide
     */
    @TestApi
    public static final int SHOW_IN_LAUNCHER_NO = 2;

    /**
     * Possible values for whether or how to show this user in the Settings app.
     * @hide
     */
    @IntDef(prefix = "SHOW_IN_SETTINGS_", value = {
            SHOW_IN_SETTINGS_UNKNOWN,
            SHOW_IN_SETTINGS_WITH_PARENT,
            SHOW_IN_SETTINGS_SEPARATE,
            SHOW_IN_SETTINGS_NO,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ShowInSettings {
    }
    /**
     * Indicates that the show in settings value for this profile is unknown or unsupported.
     * @hide
     */
    @SuppressLint("UnflaggedApi") // b/306636213
    public static final int SHOW_IN_SETTINGS_UNKNOWN = -1;
    /**
     * Suggests that the Settings app should show this user's apps in the main tab.
     * That is, either this user is a full user, so its apps should be presented accordingly, or, if
     * this user is a profile, then its apps should be shown alongside its parent's apps.
     * @hide
     */
    public static final int SHOW_IN_SETTINGS_WITH_PARENT = 0;
    /**
     * Suggests that the Settings app should show this user's apps, but separately from the apps of
     * this user's parent.
     * @hide
     */
    public static final int SHOW_IN_SETTINGS_SEPARATE = 1;
    /**
     * Suggests that the Settings app should not show this user.
     * @hide
     */
    public static final int SHOW_IN_SETTINGS_NO = 2;

    /**
     * Possible values for whether (and from whom) to inherit select user restrictions
     * or device policies.
     *
     * @hide
     */
    @IntDef(prefix = "INHERIT_DEVICE_POLICY", value = {
            INHERIT_DEVICE_POLICY_NO,
            INHERIT_DEVICE_POLICY_FROM_PARENT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InheritDevicePolicy {
    }
    /**
     * Suggests that the given user profile should not inherit user restriction or device policy
     * from any other user. This is the default value for any new user type.
     * @hide
     */
    public static final int INHERIT_DEVICE_POLICY_NO = 0;
    /**
     * Suggests that the given user profile should inherit select user restrictions or
     * device policies from its parent profile.
     *
     *<p> All the user restrictions and device policies would be not propagated to the profile
     * with this property value. The {@link com.android.server.devicepolicy.DevicePolicyEngine}
     * uses this property to determine and propagate only select ones to the given profile.
     *
     * @hide
     */
    public static final int INHERIT_DEVICE_POLICY_FROM_PARENT = 1;

    /**
     * Reference to the default user properties for this user's user type.
     * <li>If non-null, then any absent property will use the default property from here instead.
     * <li>If null, then any absent property indicates that the caller lacks permission to see it,
     *          so attempting to get that property will trigger a SecurityException.
     */
    private final @Nullable UserProperties mDefaultProperties;

    /**
     * CrossProfileIntentFilterAccessControlLevel provides level of access for user to create/modify
     * {@link CrossProfileIntentFilter}. Each level have value assigned, the higher the value
     * implies higher restriction for creation/modification.
     * CrossProfileIntentFilterAccessControlLevel allows us to protect against malicious changes in
     * user's {@link CrossProfileIntentFilter}s, which might add/remove
     * {@link CrossProfileIntentFilter} leading to unprecedented results.
     *
     * @hide
     */
    @IntDef(prefix = {"CROSS_PROFILE_INTENT_FILTER_ACCESS_LEVEL_"}, value = {
            CROSS_PROFILE_INTENT_FILTER_ACCESS_LEVEL_ALL,
            CROSS_PROFILE_INTENT_FILTER_ACCESS_LEVEL_SYSTEM,
            CROSS_PROFILE_INTENT_FILTER_ACCESS_LEVEL_SYSTEM_ADD_ONLY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CrossProfileIntentFilterAccessControlLevel {
    }

    /**
     * CROSS_PROFILE_INTENT_FILTER_ACCESS_LEVEL_ALL signifies that irrespective of user we would
     * allow access (addition/modification/removal) for CrossProfileIntentFilter.
     * This is the default access control level.
     *
     * @hide
     */
    public static final int CROSS_PROFILE_INTENT_FILTER_ACCESS_LEVEL_ALL = 0;

    /**
     * CROSS_PROFILE_INTENT_FILTER_ACCESS_LEVEL_SYSTEM signifies that only system/root user would
     * be able to access (addition/modification/removal) CrossProfileIntentFilter.
     *
     * @hide
     */
    public static final int CROSS_PROFILE_INTENT_FILTER_ACCESS_LEVEL_SYSTEM = 10;

    /**
     * CROSS_PROFILE_INTENT_FILTER_ACCESS_LEVEL_SYSTEM_ADD_ONLY signifies that only system/root
     * user would be able to add CrossProfileIntentFilter but not modify/remove. Once added, it
     * cannot be modified or removed.
     *
     * @hide
     */
    public static final int CROSS_PROFILE_INTENT_FILTER_ACCESS_LEVEL_SYSTEM_ADD_ONLY = 20;

    /**
     * Possible values for cross profile intent resolution strategy.
     *
     * @hide
     */
    @IntDef(prefix = {"CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY_"}, value = {
            CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY_DEFAULT,
            CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY_NO_FILTERING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CrossProfileIntentResolutionStrategy {
    }

    /**
     * Signifies to use {@link DefaultCrossProfileResolver} strategy, which
     * check if it needs to skip the initiating profile, resolves intent in target profile.
     * {@link DefaultCrossProfileResolver} also filters the {@link ResolveInfo} after intent
     * resolution based on their domain approval level
     *
     * @hide
     */
    public static final int CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY_DEFAULT = 0;

    /**
     * Signifies that there is no need to filter {@link ResolveInfo} after cross profile intent
     * resolution across. This strategy is for profile acting transparent to end-user and resolves
     * all allowed intent without giving any profile priority.
     *
     * @hide
     */
    public static final int CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY_NO_FILTERING = 1;

    /**
     * Possible values for the profile visibility when in quiet mode. This affects the profile data
     * and apps surfacing in Settings, sharing surfaces, and file picker surfaces. It signifies
     * whether the profile data and apps will be shown or not.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "SHOW_IN_QUIET_MODE_",
            value = {
                    SHOW_IN_QUIET_MODE_UNKNOWN,
                    SHOW_IN_QUIET_MODE_PAUSED,
                    SHOW_IN_QUIET_MODE_HIDDEN,
                    SHOW_IN_QUIET_MODE_DEFAULT,
            }
    )
    public @interface ShowInQuietMode {
    }

    /**
     * Indicates that the show in quiet mode value for this profile is unknown.
     */
    @SuppressLint("UnflaggedApi") // b/306636213
    public static final int SHOW_IN_QUIET_MODE_UNKNOWN = -1;

    /**
     * Indicates that the profile should still be visible in quiet mode but should be shown as
     * paused (e.g. by greying out its icons).
     */
    @SuppressLint("UnflaggedApi") // b/306636213
    public static final int SHOW_IN_QUIET_MODE_PAUSED = 0;
    /**
     * Indicates that the profile should not be visible when the profile is in quiet mode.
     * For example, the profile should not be shown in tabbed views in Settings, files sharing
     * surfaces etc when in quiet mode.
     */
    @SuppressLint("UnflaggedApi") // b/306636213
    public static final int SHOW_IN_QUIET_MODE_HIDDEN = 1;
    /**
     * Indicates that quiet mode should not have any effect on the profile visibility. If the
     * profile is meant to be visible, it will remain visible and vice versa.
     */
    @SuppressLint("UnflaggedApi") // b/306636213
    public static final int SHOW_IN_QUIET_MODE_DEFAULT = 2;

    /**
     * Possible values for the profile apps visibility in sharing surfaces. This indicates the
     * profile data and apps should be shown in separate tabs or mixed with its parent user's data
     * and apps in sharing surfaces and file picker surfaces.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "SHOW_IN_SHARING_SURFACES_",
            value = {
                    SHOW_IN_SHARING_SURFACES_UNKNOWN,
                    SHOW_IN_SHARING_SURFACES_SEPARATE,
                    SHOW_IN_SHARING_SURFACES_WITH_PARENT,
                    SHOW_IN_SHARING_SURFACES_NO,
            }
    )
    public @interface ShowInSharingSurfaces {
    }

    /**
     * Indicates that the show in launcher value for this profile is unknown or unsupported.
     */
    @SuppressLint("UnflaggedApi") // b/306636213
    public static final int SHOW_IN_SHARING_SURFACES_UNKNOWN = SHOW_IN_LAUNCHER_UNKNOWN;

    /**
     * Indicates that the profile data and apps should be shown in sharing surfaces intermixed with
     * parent user's data and apps.
     */
    @SuppressLint("UnflaggedApi") // b/306636213
    public static final int SHOW_IN_SHARING_SURFACES_WITH_PARENT = SHOW_IN_LAUNCHER_WITH_PARENT;

    /**
     * Indicates that the profile data and apps should be shown in sharing surfaces separate from
     * parent user's data and apps.
     */
    @SuppressLint("UnflaggedApi") // b/306636213
    public static final int SHOW_IN_SHARING_SURFACES_SEPARATE = SHOW_IN_LAUNCHER_SEPARATE;

    /**
     * Indicates that the profile data and apps should not be shown in sharing surfaces at all.
     */
    @SuppressLint("UnflaggedApi") // b/306636213
    public static final int SHOW_IN_SHARING_SURFACES_NO = SHOW_IN_LAUNCHER_NO;
    /**
     * Possible values for cross profile content sharing strategy for this profile.
     *
     * @hide
     */
    @IntDef(prefix = {"CROSS_PROFILE_CONTENT_SHARING_"}, value = {
            CROSS_PROFILE_CONTENT_SHARING_UNKNOWN,
            CROSS_PROFILE_CONTENT_SHARING_NO_DELEGATION,
            CROSS_PROFILE_CONTENT_SHARING_DELEGATE_FROM_PARENT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CrossProfileContentSharingStrategy {
    }

    /**
     * Signifies that cross-profile content sharing strategy, both to and from this profile, is
     * unknown/unsupported.
     */
    @SuppressLint("UnflaggedApi") // b/306636213
    public static final int CROSS_PROFILE_CONTENT_SHARING_UNKNOWN = -1;

    /**
     * Signifies that cross-profile content sharing strategy, both to and from this profile, should
     * not be delegated to any other user/profile.
     * For ex:
     * If this property is set for a profile, content sharing applications (such as Android
     * Sharesheet), should not delegate the decision to share content between that profile and
     * another profile to whether content sharing is allowed between any other profile/user related
     * to those profiles. They should instead decide, based upon whether content sharing is
     * specifically allowed between the two profiles in question.
     */
    @SuppressLint("UnflaggedApi") // b/306636213
    public static final int CROSS_PROFILE_CONTENT_SHARING_NO_DELEGATION = 0;

    /**
     * Signifies that cross-profile content sharing strategy, both to and from this profile, should
     * be based upon the strategy used by the parent user of the profile.
     * For ex:
     * If this property is set for a profile A, content sharing applications (such as Android
     * Sharesheet), should share content between profile A and profile B, based upon whether content
     * sharing is allowed between the parent of profile A and profile B.
     * If it's also set for profile B, then decision should, in turn be made by considering content
     * sharing strategy between the parents of both profiles.
     */
    @SuppressLint("UnflaggedApi") // b/306636213
    public static final int CROSS_PROFILE_CONTENT_SHARING_DELEGATE_FROM_PARENT = 1;

    /**
     * Possible values for the profile visibility in public API surfaces. This indicates whether or
     * not the information linked to the profile (userId, package names) should not be returned in
     * API surfaces if a user is marked as hidden.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "PROFILE_API_VISIBILITY_",
            value = {
                    PROFILE_API_VISIBILITY_UNKNOWN,
                    PROFILE_API_VISIBILITY_VISIBLE,
                    PROFILE_API_VISIBILITY_HIDDEN,
            }
    )
    public @interface ProfileApiVisibility {
    }
    /*
    * The api visibility value for this profile user is undefined or unknown.
     */
    @FlaggedApi(FLAG_SUPPORT_HIDING_PROFILES)
    public static final int PROFILE_API_VISIBILITY_UNKNOWN = -1;

    /**
     * Indicates that information about this profile user should be shown in API surfaces.
     */
    @FlaggedApi(FLAG_SUPPORT_HIDING_PROFILES)
    public static final int PROFILE_API_VISIBILITY_VISIBLE = 0;

    /**
     * Indicates that information about this profile should be not be visible in API surfaces.
     */
    @FlaggedApi(FLAG_SUPPORT_HIDING_PROFILES)
    public static final int PROFILE_API_VISIBILITY_HIDDEN = 1;


    /**
     * Creates a UserProperties (intended for the SystemServer) that stores a reference to the given
     * default properties, which it uses for any property not subsequently set.
     * @hide
     */
    public UserProperties(@NonNull UserProperties defaultProperties) {
        mDefaultProperties = defaultProperties;
        mPropertiesPresent = 0;
    }

    /**
     * Copies the given UserProperties, excluding any information that doesn't satisfy the specified
     * permissions.
     * Can only be used on the original version (one that won't throw on permission errors).
     * Note that, internally, this does not perform an exact copy.
     * @hide
     */
    public UserProperties(UserProperties orig,
            boolean exposeAllFields,
            boolean hasManagePermission,
            boolean hasQueryOrManagePermission) {

        if (orig.mDefaultProperties == null) {
            throw new IllegalArgumentException("Attempting to copy a non-original UserProperties.");
        }

        this.mDefaultProperties = null;

        // Insert each setter into the following hierarchy based on its permission requirements.
        // NOTE: Copy each property using getters to ensure default values are copied if needed.
        if (exposeAllFields) {
            // Add items that require exposeAllFields to be true (strictest permission level).
            setStartWithParent(orig.getStartWithParent());
            setInheritDevicePolicy(orig.getInheritDevicePolicy());
            setUpdateCrossProfileIntentFiltersOnOTA(orig.getUpdateCrossProfileIntentFiltersOnOTA());
            setCrossProfileIntentFilterAccessControl(
                    orig.getCrossProfileIntentFilterAccessControl());
            setCrossProfileIntentResolutionStrategy(orig.getCrossProfileIntentResolutionStrategy());
            setDeleteAppWithParent(orig.getDeleteAppWithParent());
            setAlwaysVisible(orig.getAlwaysVisible());
            setAllowStoppingUserWithDelayedLocking(orig.getAllowStoppingUserWithDelayedLocking());
        }
        if (hasManagePermission) {
            // Add items that require MANAGE_USERS or stronger.
            setShowInSettings(orig.getShowInSettings());
            setUseParentsContacts(orig.getUseParentsContacts());
            setAuthAlwaysRequiredToDisableQuietMode(
                    orig.isAuthAlwaysRequiredToDisableQuietMode());
        }
        if (hasQueryOrManagePermission) {
            // Add items that require QUERY_USERS or stronger.
        }
        // Add items that have no permission requirements at all.
        setShowInLauncher(orig.getShowInLauncher());
        setMediaSharedWithParent(orig.isMediaSharedWithParent());
        setCredentialShareableWithParent(orig.isCredentialShareableWithParent());
        setShowInQuietMode(orig.getShowInQuietMode());
        setShowInSharingSurfaces(orig.getShowInSharingSurfaces());
        setCrossProfileContentSharingStrategy(orig.getCrossProfileContentSharingStrategy());
        if (android.multiuser.Flags.supportHidingProfiles()) {
            setProfileApiVisibility(orig.getProfileApiVisibility());
        }
    }

    /**
     * Indicates that the given property is being stored explicitly in this object.
     * If false, it means that either
     * <li>the default property for the user type should be used instead (for SystemServer callers)
     * <li>the caller lacks permission to see this property (for all other callers)
     */
    private boolean isPresent(@PropertyIndex long index) {
        return (mPropertiesPresent & (1L << index)) != 0;
    }

    /** Indicates that the given property is henceforth being explicitly stored in this object. */
    private void setPresent(@PropertyIndex long index) {
        mPropertiesPresent |= (1L << index);
    }

    /** @hide Returns the internal mPropertiesPresent value. Only for testing purposes. */
    @VisibleForTesting
    public long getPropertiesPresent() {
        return mPropertiesPresent;
    }

    /**
     * Returns whether, and how, a user should be shown in the Launcher.
     * This is generally inapplicable for non-profile users.
     *
     * Possible return values include
     *    {@link #SHOW_IN_LAUNCHER_WITH_PARENT}},
     *    {@link #SHOW_IN_LAUNCHER_SEPARATE},
     *    and {@link #SHOW_IN_LAUNCHER_NO}.
     *
     * @return whether, and how, a profile should be shown in the Launcher.
     * @hide
     */
    @TestApi
    public @ShowInLauncher int getShowInLauncher() {
        if (isPresent(INDEX_SHOW_IN_LAUNCHER)) return mShowInLauncher;
        if (mDefaultProperties != null) return mDefaultProperties.mShowInLauncher;
        throw new SecurityException("You don't have permission to query showInLauncher");
    }
    /** @hide */
    public void setShowInLauncher(@ShowInLauncher int val) {
        this.mShowInLauncher = val;
        setPresent(INDEX_SHOW_IN_LAUNCHER);
    }
    private @ShowInLauncher int mShowInLauncher;

    /**
     * Returns whether, and how, a user should be shown in the Settings app.
     * This is generally inapplicable for non-profile users.
     *
     * Possible return values include
     *    {@link #SHOW_IN_SETTINGS_WITH_PARENT}},
     *    {@link #SHOW_IN_SETTINGS_SEPARATE},
     *    and {@link #SHOW_IN_SETTINGS_NO}.
     *
     * <p> The caller must have {@link android.Manifest.permission#MANAGE_USERS} to query this
     * property.
     *
     * @return whether, and how, a profile should be shown in the Settings.
     * @hide
     */
    public @ShowInSettings int getShowInSettings() {
        if (isPresent(INDEX_SHOW_IN_SETTINGS)) return mShowInSettings;
        if (mDefaultProperties != null) return mDefaultProperties.mShowInSettings;
        throw new SecurityException("You don't have permission to query mShowInSettings");
    }
    /** @hide */
    public void setShowInSettings(@ShowInSettings int val) {
        this.mShowInSettings = val;
        setPresent(INDEX_SHOW_IN_SETTINGS);
    }
    private @ShowInSettings int mShowInSettings;

    /**
     * Returns whether a user should be shown in the Settings and sharing surfaces depending on the
     * {@link android.os.UserManager#requestQuietModeEnabled(boolean, android.os.UserHandle)
     * quiet mode}. This is only applicable to profile users since the quiet mode concept is only
     * applicable to profile users.
     *
     * <p> Please note that, in Settings, this property takes effect only if
     * {@link #getShowInSettings()} does not return {@link #SHOW_IN_SETTINGS_NO}.
     * Also note that in Sharing surfaces this property takes effect only if
     * {@link #getShowInSharingSurfaces()} does not return {@link #SHOW_IN_SHARING_SURFACES_NO}.
     *
     * @return One of {@link #SHOW_IN_QUIET_MODE_HIDDEN},
     *         {@link #SHOW_IN_QUIET_MODE_PAUSED}, or
     *         {@link #SHOW_IN_QUIET_MODE_DEFAULT} depending on whether the profile should be
     *         shown in quiet mode or not.
     */
    @SuppressLint("UnflaggedApi") // b/306636213
    public @ShowInQuietMode int getShowInQuietMode() {
        // NOTE: Launcher currently does not make use of this property.
        if (isPresent(INDEX_SHOW_IN_QUIET_MODE)) return mShowInQuietMode;
        if (mDefaultProperties != null) return mDefaultProperties.mShowInQuietMode;
        throw new SecurityException(
                "You don't have permission to query ShowInQuietMode");
    }
    /** @hide */
    public void setShowInQuietMode(@ShowInQuietMode int showInQuietMode) {
        this.mShowInQuietMode = showInQuietMode;
        setPresent(INDEX_SHOW_IN_QUIET_MODE);
    }
    private int mShowInQuietMode;

    /**
     * Returns whether a user's data and apps should be shown in sharing surfaces in a separate tab
     * or mixed with the parent user's data/apps. This is only applicable to profile users.
     *
     * @return One of {@link #SHOW_IN_SHARING_SURFACES_NO},
     *         {@link #SHOW_IN_SHARING_SURFACES_SEPARATE}, or
     *         {@link #SHOW_IN_SHARING_SURFACES_WITH_PARENT} depending on whether the profile
     *         should be shown separate from its parent's data, mixed with the parent's data, or
     *         not shown at all.
     */
    @SuppressLint("UnflaggedApi") // b/306636213
    public @ShowInSharingSurfaces int getShowInSharingSurfaces() {
        if (isPresent(INDEX_SHOW_IN_SHARING_SURFACES)) return mShowInSharingSurfaces;
        if (mDefaultProperties != null) return mDefaultProperties.mShowInSharingSurfaces;
        throw new SecurityException(
                "You don't have permission to query ShowInSharingSurfaces");
    }
    /** @hide */
    public void setShowInSharingSurfaces(@ShowInSharingSurfaces int showInSharingSurfaces) {
        this.mShowInSharingSurfaces = showInSharingSurfaces;
        setPresent(INDEX_SHOW_IN_SHARING_SURFACES);
    }
    private int mShowInSharingSurfaces;

    /**
     * Returns whether a profile should be started when its parent starts (unless in quiet mode).
     * This only applies for users that have parents (i.e. for profiles).
     * @hide
     */
    public boolean getStartWithParent() {
        if (isPresent(INDEX_START_WITH_PARENT)) return mStartWithParent;
        if (mDefaultProperties != null) return mDefaultProperties.mStartWithParent;
        throw new SecurityException("You don't have permission to query startWithParent");
    }
    /** @hide */
    public void setStartWithParent(boolean val) {
        this.mStartWithParent = val;
        setPresent(INDEX_START_WITH_PARENT);
    }
    private boolean mStartWithParent;

    /**
     * Returns whether an app in the profile should be deleted when the same package in
     * the parent user is being deleted.
     * This only applies for users that have parents (i.e. for profiles).
     * @hide
     */
    public boolean getDeleteAppWithParent() {
        if (isPresent(INDEX_DELETE_APP_WITH_PARENT)) return mDeleteAppWithParent;
        if (mDefaultProperties != null) return mDefaultProperties.mDeleteAppWithParent;
        throw new SecurityException("You don't have permission to query deleteAppWithParent");
    }
    /** @hide */
    public void setDeleteAppWithParent(boolean val) {
        this.mDeleteAppWithParent = val;
        setPresent(INDEX_DELETE_APP_WITH_PARENT);
    }
    private boolean mDeleteAppWithParent;

    /**
     * Returns whether the user should always
     * be {@link android.os.UserManager#isUserVisible() visible}.
     * The intended usage is for the Communal Profile, which is running and accessible at all times.
     * @hide
     */
    public boolean getAlwaysVisible() {
        if (isPresent(INDEX_ALWAYS_VISIBLE)) return mAlwaysVisible;
        if (mDefaultProperties != null) return mDefaultProperties.mAlwaysVisible;
        throw new SecurityException("You don't have permission to query alwaysVisible");
    }
    /** @hide */
    public void setAlwaysVisible(boolean val) {
        this.mAlwaysVisible = val;
        setPresent(INDEX_ALWAYS_VISIBLE);
    }
    private boolean mAlwaysVisible;

    /**
     * Return whether, and how, select user restrictions or device policies should be inherited
     * from other user.
     *
     * Possible return values include
     * {@link #INHERIT_DEVICE_POLICY_FROM_PARENT} or {@link #INHERIT_DEVICE_POLICY_NO}
     *
     * @hide
     */
    public @InheritDevicePolicy int getInheritDevicePolicy() {
        if (isPresent(INDEX_INHERIT_DEVICE_POLICY)) return mInheritDevicePolicy;
        if (mDefaultProperties != null) return mDefaultProperties.mInheritDevicePolicy;
        throw new SecurityException("You don't have permission to query inheritDevicePolicy");
    }
    /** @hide */
    public void setInheritDevicePolicy(@InheritDevicePolicy int val) {
        this.mInheritDevicePolicy = val;
        setPresent(INDEX_INHERIT_DEVICE_POLICY);
    }
    private @InheritDevicePolicy int mInheritDevicePolicy;

    /**
     * Returns whether the current user must use parent user's contacts. If true, writes to the
     * ContactsProvider corresponding to the current user will be disabled and reads will be
     * redirected to the parent.
     *
     * This only applies to users that have parents (i.e. profiles) and is used to ensure
     * they can access contacts from the parent profile. This will be generally inapplicable for
     * non-profile users.
     *
     * Please note that in case of the clone profiles, only the allow-listed apps would be allowed
     * to access contacts across profiles and other apps will not see any contacts.
     * TODO(b/256126819) Add link to the method returning apps allow-listed for app-cloning
     *
     * @return whether contacts access from an associated profile is enabled for the user
     * @hide
     */
    public boolean getUseParentsContacts() {
        if (isPresent(INDEX_USE_PARENTS_CONTACTS)) return mUseParentsContacts;
        if (mDefaultProperties != null) return mDefaultProperties.mUseParentsContacts;
        throw new SecurityException("You don't have permission to query useParentsContacts");
    }
    /** @hide */
    public void setUseParentsContacts(boolean val) {
        this.mUseParentsContacts = val;
        setPresent(INDEX_USE_PARENTS_CONTACTS);
    }
    /**
     * Indicates whether the current user should use parent user's contacts.
     * If this property is set true, the user will be blocked from storing any contacts in its
     * own contacts database and will serve all read contacts calls through the parent's contacts.
     */
    private boolean mUseParentsContacts;

    /**
     * Returns true if user needs to update default
     * {@link com.android.server.pm.CrossProfileIntentFilter} with its parents during an OTA update
     * @hide
     */
    public boolean getUpdateCrossProfileIntentFiltersOnOTA() {
        if (isPresent(INDEX_UPDATE_CROSS_PROFILE_INTENT_FILTERS_ON_OTA)) {
            return mUpdateCrossProfileIntentFiltersOnOTA;
        }
        if (mDefaultProperties != null) {
            return mDefaultProperties.mUpdateCrossProfileIntentFiltersOnOTA;
        }
        throw new SecurityException("You don't have permission to query "
                + "updateCrossProfileIntentFiltersOnOTA");
    }
    /** @hide */
    public void setUpdateCrossProfileIntentFiltersOnOTA(boolean val) {
        this.mUpdateCrossProfileIntentFiltersOnOTA = val;
        setPresent(INDEX_UPDATE_CROSS_PROFILE_INTENT_FILTERS_ON_OTA);
    }
    /**
     Indicate if {@link com.android.server.pm.CrossProfileIntentFilter}s need to be updated during
     OTA update between user-parent
     */
    private boolean mUpdateCrossProfileIntentFiltersOnOTA;

    /**
     * Returns whether a profile shares media with its parent user.
     * This only applies for users that have parents (i.e. for profiles).
     */
    public boolean isMediaSharedWithParent() {
        if (isPresent(INDEX_MEDIA_SHARED_WITH_PARENT)) return mMediaSharedWithParent;
        if (mDefaultProperties != null) return mDefaultProperties.mMediaSharedWithParent;
        throw new SecurityException("You don't have permission to query mediaSharedWithParent");
    }
    /** @hide */
    public void setMediaSharedWithParent(boolean val) {
        this.mMediaSharedWithParent = val;
        setPresent(INDEX_MEDIA_SHARED_WITH_PARENT);
    }
    private boolean mMediaSharedWithParent;

    /**
     * Returns whether a profile can have shared lockscreen credential with its parent user.
     * This only applies for users that have parents (i.e. for profiles).
     */
    public boolean isCredentialShareableWithParent() {
        if (isPresent(INDEX_CREDENTIAL_SHAREABLE_WITH_PARENT)) {
            return mCredentialShareableWithParent;
        }
        if (mDefaultProperties != null) return mDefaultProperties.mCredentialShareableWithParent;
        throw new SecurityException(
                "You don't have permission to query credentialShareableWithParent");
    }
    /** @hide */
    public void setCredentialShareableWithParent(boolean val) {
        this.mCredentialShareableWithParent = val;
        setPresent(INDEX_CREDENTIAL_SHAREABLE_WITH_PARENT);
    }
    private boolean mCredentialShareableWithParent;

    /**
     * Returns whether the profile always requires user authentication to disable from quiet mode.
     *
     * <p> Settings this field to true will ensure that the credential confirmation activity is
     * always shown whenever the user requests to disable quiet mode. The behavior of credential
     * checks is not guaranteed when the property is false and may vary depending on user types.
     * @hide
     */
    public boolean isAuthAlwaysRequiredToDisableQuietMode() {
        if (isPresent(INDEX_AUTH_ALWAYS_REQUIRED_TO_DISABLE_QUIET_MODE)) {
            return mAuthAlwaysRequiredToDisableQuietMode;
        }
        if (mDefaultProperties != null) {
            return mDefaultProperties.mAuthAlwaysRequiredToDisableQuietMode;
        }
        throw new SecurityException(
                "You don't have permission to query authAlwaysRequiredToDisableQuietMode");
    }
    /** @hide */
    public void setAuthAlwaysRequiredToDisableQuietMode(boolean val) {
        this.mAuthAlwaysRequiredToDisableQuietMode = val;
        setPresent(INDEX_AUTH_ALWAYS_REQUIRED_TO_DISABLE_QUIET_MODE);
    }
    private boolean mAuthAlwaysRequiredToDisableQuietMode;

    /**
     * Returns whether a user (usually a profile) is allowed to leave the CE storage unlocked when
     * stopped.
     *
     * <p> Setting this property to true will enable the user's CE storage to remain unlocked when
     * the user is stopped using
     * {@link com.android.server.am.ActivityManagerService#stopUserWithDelayedLocking(int,
     * boolean, IStopUserCallback)}.
     *
     * <p> When this property is false, delayed locking may still be applicable at a global
     * level for all users via the {@code config_multiuserDelayUserDataLocking}. That is, delayed
     * locking for a user can happen if either the device configuration is set or if this property
     * is set. When both, the config and the property value is false, the user storage is always
     * locked when the user is stopped.
     * @hide
     */
    public boolean getAllowStoppingUserWithDelayedLocking() {
        if (isPresent(INDEX_ALLOW_STOPPING_USER_WITH_DELAYED_LOCKING)) {
            return mAllowStoppingUserWithDelayedLocking;
        }
        if (mDefaultProperties != null) {
            return mDefaultProperties.mAllowStoppingUserWithDelayedLocking;
        }
        throw new SecurityException(
                "You don't have permission to query allowStoppingUserWithDelayedLocking");
    }
    /** @hide */
    public void setAllowStoppingUserWithDelayedLocking(boolean val) {
        this.mAllowStoppingUserWithDelayedLocking = val;
        setPresent(INDEX_ALLOW_STOPPING_USER_WITH_DELAYED_LOCKING);
    }
    private boolean mAllowStoppingUserWithDelayedLocking;

    /**
     * Returns the user's {@link CrossProfileIntentFilterAccessControlLevel}.
     * @hide
     */
    public @CrossProfileIntentFilterAccessControlLevel int
            getCrossProfileIntentFilterAccessControl() {
        if (isPresent(INDEX_CROSS_PROFILE_INTENT_FILTER_ACCESS_CONTROL)) {
            return mCrossProfileIntentFilterAccessControl;
        }
        if (mDefaultProperties != null) {
            return mDefaultProperties.mCrossProfileIntentFilterAccessControl;
        }
        throw new SecurityException("You don't have permission to query "
                + "crossProfileIntentFilterAccessControl");
    }
    /**
     * Sets {@link CrossProfileIntentFilterAccessControlLevel} for the user.
     * @param val access control for user
     * @hide
     */
    public void setCrossProfileIntentFilterAccessControl(
            @CrossProfileIntentFilterAccessControlLevel int val) {
        this.mCrossProfileIntentFilterAccessControl = val;
        setPresent(INDEX_CROSS_PROFILE_INTENT_FILTER_ACCESS_CONTROL);
    }
    private @CrossProfileIntentFilterAccessControlLevel int mCrossProfileIntentFilterAccessControl;

    /**
     * Returns the user's {@link CrossProfileIntentResolutionStrategy}.
     * @return user's {@link CrossProfileIntentResolutionStrategy}.
     *
     * @hide
     */
    public @CrossProfileIntentResolutionStrategy int getCrossProfileIntentResolutionStrategy() {
        if (isPresent(INDEX_CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY)) {
            return mCrossProfileIntentResolutionStrategy;
        }
        if (mDefaultProperties != null) {
            return mDefaultProperties.mCrossProfileIntentResolutionStrategy;
        }
        throw new SecurityException("You don't have permission to query "
                + "crossProfileIntentResolutionStrategy");
    }

    /** @hide */
    public void setCrossProfileIntentResolutionStrategy(
            @CrossProfileIntentResolutionStrategy int val) {
        this.mCrossProfileIntentResolutionStrategy = val;
        setPresent(INDEX_CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY);
    }
    private @CrossProfileIntentResolutionStrategy int mCrossProfileIntentResolutionStrategy;

    /**
     * Returns the user's {@link CrossProfileContentSharingStrategy}.
     *
     * Content sharing applications, such as Android Sharesheet allow sharing of content
     * (an image, for ex.) between profiles, based upon cross-profile access checks between the
     * originating and destined profile.
     * In some cases however, we may want another user (such as profile parent) to serve as the
     * delegated user to be used for such checks.
     * To effect the same, clients can fetch this property and accordingly replace the
     * originating/destined profile by another user for cross-profile access checks.
     *
     * @return user's {@link CrossProfileContentSharingStrategy}.
     */
    @SuppressLint("UnflaggedApi")  // b/306636213
    public @CrossProfileContentSharingStrategy int getCrossProfileContentSharingStrategy() {
        if (isPresent(INDEX_CROSS_PROFILE_CONTENT_SHARING_STRATEGY)) {
            return mCrossProfileContentSharingStrategy;
        }
        if (mDefaultProperties != null) {
            return mDefaultProperties.mCrossProfileContentSharingStrategy;
        }
        throw new SecurityException("You don't have permission to query "
                + "crossProfileContentSharingStrategy");
    }

    /** @hide */
    public void setCrossProfileContentSharingStrategy(
            @CrossProfileContentSharingStrategy int val) {
        this.mCrossProfileContentSharingStrategy = val;
        setPresent(INDEX_CROSS_PROFILE_CONTENT_SHARING_STRATEGY);
    }
    private @CrossProfileContentSharingStrategy int mCrossProfileContentSharingStrategy;

    /**
     * Returns the visibility of the profile user in API surfaces. Any information linked to the
     * profile (userId, package names) should be hidden API surfaces if a user is marked as hidden.
     */
    @NonNull
    @FlaggedApi(FLAG_SUPPORT_HIDING_PROFILES)
    public @ProfileApiVisibility int getProfileApiVisibility() {
        if (isPresent(INDEX_PROFILE_API_VISIBILITY)) return mProfileApiVisibility;
        if (mDefaultProperties != null) return mDefaultProperties.mProfileApiVisibility;
        throw new SecurityException("You don't have permission to query profileApiVisibility");
    }
    /** @hide */
    @NonNull
    @FlaggedApi(FLAG_SUPPORT_HIDING_PROFILES)
    public void setProfileApiVisibility(@ProfileApiVisibility int profileApiVisibility) {
        this.mProfileApiVisibility = profileApiVisibility;
        setPresent(INDEX_PROFILE_API_VISIBILITY);
    }
    private @ProfileApiVisibility int mProfileApiVisibility;

    @Override
    public String toString() {
        String profileApiVisibility =
                android.multiuser.Flags.supportHidingProfiles() ? ", mProfileApiVisibility="
                        + getProfileApiVisibility() : "";
        // Please print in increasing order of PropertyIndex.
        return "UserProperties{"
                + "mPropertiesPresent=" + Long.toBinaryString(mPropertiesPresent)
                + ", mShowInLauncher=" + getShowInLauncher()
                + ", mStartWithParent=" + getStartWithParent()
                + ", mShowInSettings=" + getShowInSettings()
                + ", mInheritDevicePolicy=" + getInheritDevicePolicy()
                + ", mUseParentsContacts=" + getUseParentsContacts()
                + ", mUpdateCrossProfileIntentFiltersOnOTA="
                + getUpdateCrossProfileIntentFiltersOnOTA()
                + ", mCrossProfileIntentFilterAccessControl="
                + getCrossProfileIntentFilterAccessControl()
                + ", mCrossProfileIntentResolutionStrategy="
                + getCrossProfileIntentResolutionStrategy()
                + ", mMediaSharedWithParent=" + isMediaSharedWithParent()
                + ", mCredentialShareableWithParent=" + isCredentialShareableWithParent()
                + ", mAuthAlwaysRequiredToDisableQuietMode="
                + isAuthAlwaysRequiredToDisableQuietMode()
                + ", mAllowStoppingUserWithDelayedLocking="
                + getAllowStoppingUserWithDelayedLocking()
                + ", mDeleteAppWithParent=" + getDeleteAppWithParent()
                + ", mAlwaysVisible=" + getAlwaysVisible()
                + ", mCrossProfileContentSharingStrategy=" + getCrossProfileContentSharingStrategy()
                + profileApiVisibility
                + "}";
    }

    /**
     * Print the UserProperties to the given PrintWriter.
     * @hide
     */
    public void println(PrintWriter pw, String prefix) {
        // Please print in increasing order of PropertyIndex.
        pw.println(prefix + "UserProperties:");
        pw.println(prefix + "    mPropertiesPresent=" + Long.toBinaryString(mPropertiesPresent));
        pw.println(prefix + "    mShowInLauncher=" + getShowInLauncher());
        pw.println(prefix + "    mStartWithParent=" + getStartWithParent());
        pw.println(prefix + "    mShowInSettings=" + getShowInSettings());
        pw.println(prefix + "    mInheritDevicePolicy=" + getInheritDevicePolicy());
        pw.println(prefix + "    mUseParentsContacts=" + getUseParentsContacts());
        pw.println(prefix + "    mUpdateCrossProfileIntentFiltersOnOTA="
                + getUpdateCrossProfileIntentFiltersOnOTA());
        pw.println(prefix + "    mCrossProfileIntentFilterAccessControl="
                + getCrossProfileIntentFilterAccessControl());
        pw.println(prefix + "    mCrossProfileIntentResolutionStrategy="
                + getCrossProfileIntentResolutionStrategy());
        pw.println(prefix + "    mMediaSharedWithParent=" + isMediaSharedWithParent());
        pw.println(prefix + "    mCredentialShareableWithParent="
                + isCredentialShareableWithParent());
        pw.println(prefix + "    mAuthAlwaysRequiredToDisableQuietMode="
                + isAuthAlwaysRequiredToDisableQuietMode());
        pw.println(prefix + "    mAllowStoppingUserWithDelayedLocking="
                + getAllowStoppingUserWithDelayedLocking());
        pw.println(prefix + "    mDeleteAppWithParent=" + getDeleteAppWithParent());
        pw.println(prefix + "    mAlwaysVisible=" + getAlwaysVisible());
        pw.println(prefix + "    mCrossProfileContentSharingStrategy="
                + getCrossProfileContentSharingStrategy());
        if (android.multiuser.Flags.supportHidingProfiles()) {
            pw.println(prefix + "    mProfileApiVisibility=" + getProfileApiVisibility());
        }
    }

    /**
     * Reads in a UserProperties from an xml file, for use by the SystemServer.
     *
     * The serializer should already be inside a tag from which to read the user properties.
     *
     * @param defaultUserPropertiesReference the default UserProperties to use for this user type.
     * @see #writeToXml
     * @hide
     */
    public UserProperties(
            TypedXmlPullParser parser,
            @NonNull UserProperties defaultUserPropertiesReference)
            throws IOException, XmlPullParserException {

        this(defaultUserPropertiesReference);
        updateFromXml(parser);
    }

    /**
     * Parses the given xml file and updates this UserProperties with its data.
     * I.e., if a piece of data is present in the xml, it will overwrite whatever was
     * previously stored in this UserProperties.
     * @hide
     */
    public void updateFromXml(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {

        final int attributeCount = parser.getAttributeCount();
        for (int i = 0; i < attributeCount; i++) {
            final String attributeName = parser.getAttributeName(i);
            switch(attributeName) {
                case ATTR_SHOW_IN_LAUNCHER:
                    setShowInLauncher(parser.getAttributeInt(i));
                    break;
                case ATTR_START_WITH_PARENT:
                    setStartWithParent(parser.getAttributeBoolean(i));
                    break;
                case ATTR_SHOW_IN_SETTINGS:
                    setShowInSettings(parser.getAttributeInt(i));
                    break;
                case ATTR_SHOW_IN_QUIET_MODE:
                    setShowInQuietMode(parser.getAttributeInt(i));
                    break;
                case ATTR_SHOW_IN_SHARING_SURFACES:
                    setShowInSharingSurfaces(parser.getAttributeInt(i));
                    break;
                case ATTR_INHERIT_DEVICE_POLICY:
                    setInheritDevicePolicy(parser.getAttributeInt(i));
                    break;
                case ATTR_USE_PARENTS_CONTACTS:
                    setUseParentsContacts(parser.getAttributeBoolean(i));
                    break;
                case ATTR_UPDATE_CROSS_PROFILE_INTENT_FILTERS_ON_OTA:
                    setUpdateCrossProfileIntentFiltersOnOTA(parser.getAttributeBoolean(i));
                    break;
                case ATTR_CROSS_PROFILE_INTENT_FILTER_ACCESS_CONTROL:
                    setCrossProfileIntentFilterAccessControl(parser.getAttributeInt(i));
                    break;
                case ATTR_CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY:
                    setCrossProfileIntentResolutionStrategy(parser.getAttributeInt(i));
                    break;
                case ATTR_MEDIA_SHARED_WITH_PARENT:
                    setMediaSharedWithParent(parser.getAttributeBoolean(i));
                    break;
                case ATTR_CREDENTIAL_SHAREABLE_WITH_PARENT:
                    setCredentialShareableWithParent(parser.getAttributeBoolean(i));
                    break;
                case ATTR_AUTH_ALWAYS_REQUIRED_TO_DISABLE_QUIET_MODE:
                    setAuthAlwaysRequiredToDisableQuietMode(parser.getAttributeBoolean(i));
                    break;
                case ATTR_ALLOW_STOPPING_USER_WITH_DELAYED_LOCKING:
                    setAllowStoppingUserWithDelayedLocking(parser.getAttributeBoolean(i));
                    break;
                case ATTR_DELETE_APP_WITH_PARENT:
                    setDeleteAppWithParent(parser.getAttributeBoolean(i));
                    break;
                case ATTR_ALWAYS_VISIBLE:
                    setAlwaysVisible(parser.getAttributeBoolean(i));
                    break;
                case ATTR_CROSS_PROFILE_CONTENT_SHARING_STRATEGY:
                    setCrossProfileContentSharingStrategy(parser.getAttributeInt(i));
                    break;
                case ATTR_PROFILE_API_VISIBILITY:
                    if (android.multiuser.Flags.supportHidingProfiles()) {
                        setProfileApiVisibility(parser.getAttributeInt(i));
                    }
                    break;
                default:
                    Slog.w(LOG_TAG, "Skipping unknown property " + attributeName);
            }
        }
    }

    /**
     * Writes the UserProperties, as used by the SystemServer, to the xml file.
     *
     * The serializer should already be inside a tag in which to write the user properties.
     *
     * @see  #UserProperties(TypedXmlPullParser, UserProperties)
     * @hide
     */
    public void writeToXml(TypedXmlSerializer serializer)
            throws IOException, XmlPullParserException {

        if (isPresent(INDEX_SHOW_IN_LAUNCHER)) {
            serializer.attributeInt(null, ATTR_SHOW_IN_LAUNCHER, mShowInLauncher);
        }
        if (isPresent(INDEX_START_WITH_PARENT)) {
            serializer.attributeBoolean(null, ATTR_START_WITH_PARENT, mStartWithParent);
        }
        if (isPresent(INDEX_SHOW_IN_SETTINGS)) {
            serializer.attributeInt(null, ATTR_SHOW_IN_SETTINGS, mShowInSettings);
        }
        if (isPresent(INDEX_SHOW_IN_QUIET_MODE)) {
            serializer.attributeInt(null, ATTR_SHOW_IN_QUIET_MODE,
                    mShowInQuietMode);
        }
        if (isPresent(INDEX_SHOW_IN_SHARING_SURFACES)) {
            serializer.attributeInt(null, ATTR_SHOW_IN_SHARING_SURFACES, mShowInSharingSurfaces);
        }
        if (isPresent(INDEX_INHERIT_DEVICE_POLICY)) {
            serializer.attributeInt(null, ATTR_INHERIT_DEVICE_POLICY,
                    mInheritDevicePolicy);
        }
        if (isPresent(INDEX_USE_PARENTS_CONTACTS)) {
            serializer.attributeBoolean(null, ATTR_USE_PARENTS_CONTACTS,
                    mUseParentsContacts);
        }
        if (isPresent(INDEX_UPDATE_CROSS_PROFILE_INTENT_FILTERS_ON_OTA)) {
            serializer.attributeBoolean(null,
                    ATTR_UPDATE_CROSS_PROFILE_INTENT_FILTERS_ON_OTA,
                    mUpdateCrossProfileIntentFiltersOnOTA);
        }
        if (isPresent(INDEX_CROSS_PROFILE_INTENT_FILTER_ACCESS_CONTROL)) {
            serializer.attributeInt(null, ATTR_CROSS_PROFILE_INTENT_FILTER_ACCESS_CONTROL,
                    mCrossProfileIntentFilterAccessControl);
        }
        if (isPresent(INDEX_CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY)) {
            serializer.attributeInt(null, ATTR_CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY,
                    mCrossProfileIntentResolutionStrategy);
        }
        if (isPresent(INDEX_MEDIA_SHARED_WITH_PARENT)) {
            serializer.attributeBoolean(null, ATTR_MEDIA_SHARED_WITH_PARENT,
                    mMediaSharedWithParent);
        }
        if (isPresent(INDEX_CREDENTIAL_SHAREABLE_WITH_PARENT)) {
            serializer.attributeBoolean(null, ATTR_CREDENTIAL_SHAREABLE_WITH_PARENT,
                    mCredentialShareableWithParent);
        }
        if (isPresent(INDEX_AUTH_ALWAYS_REQUIRED_TO_DISABLE_QUIET_MODE)) {
            serializer.attributeBoolean(null, ATTR_AUTH_ALWAYS_REQUIRED_TO_DISABLE_QUIET_MODE,
                    mAuthAlwaysRequiredToDisableQuietMode);
        }
        if (isPresent(INDEX_ALLOW_STOPPING_USER_WITH_DELAYED_LOCKING)) {
            serializer.attributeBoolean(null, ATTR_ALLOW_STOPPING_USER_WITH_DELAYED_LOCKING,
                    mAllowStoppingUserWithDelayedLocking);
        }
        if (isPresent(INDEX_DELETE_APP_WITH_PARENT)) {
            serializer.attributeBoolean(null, ATTR_DELETE_APP_WITH_PARENT,
                    mDeleteAppWithParent);
        }
        if (isPresent(INDEX_ALWAYS_VISIBLE)) {
            serializer.attributeBoolean(null, ATTR_ALWAYS_VISIBLE,
                    mAlwaysVisible);
        }
        if (isPresent(INDEX_CROSS_PROFILE_CONTENT_SHARING_STRATEGY)) {
            serializer.attributeInt(null, ATTR_CROSS_PROFILE_CONTENT_SHARING_STRATEGY,
                    mCrossProfileContentSharingStrategy);
        }
        if (isPresent(INDEX_PROFILE_API_VISIBILITY)) {
            if (android.multiuser.Flags.supportHidingProfiles()) {
                serializer.attributeInt(null, ATTR_PROFILE_API_VISIBILITY,
                        mProfileApiVisibility);
            }
        }
    }

    // For use only with an object that has already had any permission-lacking fields stripped out.
    @Override
    public void writeToParcel(@NonNull Parcel dest, int parcelableFlags) {
        dest.writeLong(mPropertiesPresent);
        dest.writeInt(mShowInLauncher);
        dest.writeBoolean(mStartWithParent);
        dest.writeInt(mShowInSettings);
        dest.writeInt(mShowInQuietMode);
        dest.writeInt(mShowInSharingSurfaces);
        dest.writeInt(mInheritDevicePolicy);
        dest.writeBoolean(mUseParentsContacts);
        dest.writeBoolean(mUpdateCrossProfileIntentFiltersOnOTA);
        dest.writeInt(mCrossProfileIntentFilterAccessControl);
        dest.writeInt(mCrossProfileIntentResolutionStrategy);
        dest.writeBoolean(mMediaSharedWithParent);
        dest.writeBoolean(mCredentialShareableWithParent);
        dest.writeBoolean(mAuthAlwaysRequiredToDisableQuietMode);
        dest.writeBoolean(mAllowStoppingUserWithDelayedLocking);
        dest.writeBoolean(mDeleteAppWithParent);
        dest.writeBoolean(mAlwaysVisible);
        dest.writeInt(mCrossProfileContentSharingStrategy);
        dest.writeInt(mProfileApiVisibility);
    }

    /**
     * Reads a UserProperties object from the parcel.
     * Not suitable for the canonical SystemServer version since it lacks mDefaultProperties.
      */
    private UserProperties(@NonNull Parcel source) {
        mDefaultProperties = null;

        mPropertiesPresent = source.readLong();
        mShowInLauncher = source.readInt();
        mStartWithParent = source.readBoolean();
        mShowInSettings = source.readInt();
        mShowInQuietMode = source.readInt();
        mShowInSharingSurfaces = source.readInt();
        mInheritDevicePolicy = source.readInt();
        mUseParentsContacts = source.readBoolean();
        mUpdateCrossProfileIntentFiltersOnOTA = source.readBoolean();
        mCrossProfileIntentFilterAccessControl = source.readInt();
        mCrossProfileIntentResolutionStrategy = source.readInt();
        mMediaSharedWithParent = source.readBoolean();
        mCredentialShareableWithParent = source.readBoolean();
        mAuthAlwaysRequiredToDisableQuietMode = source.readBoolean();
        mAllowStoppingUserWithDelayedLocking = source.readBoolean();
        mDeleteAppWithParent = source.readBoolean();
        mAlwaysVisible = source.readBoolean();
        mCrossProfileContentSharingStrategy = source.readInt();
        mProfileApiVisibility = source.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<UserProperties> CREATOR
            = new Parcelable.Creator<UserProperties>() {
        public UserProperties createFromParcel(Parcel source) {
            return new UserProperties(source);
        }
        public UserProperties[] newArray(int size) {
            return new UserProperties[size];
        }
    };

    /**
     * Builder for the SystemServer's {@link UserProperties}; see that class for documentation.
     * Intended for building default values (and so all properties are present in the built object).
     * @hide
     */
    @TestApi
    @SuppressLint("UnflaggedApi") // b/306636213
    public static final class Builder {
        // UserProperties fields and their default values.
        private @ShowInLauncher int mShowInLauncher = SHOW_IN_LAUNCHER_WITH_PARENT;
        private boolean mStartWithParent = false;
        private @ShowInSettings int mShowInSettings = SHOW_IN_SETTINGS_WITH_PARENT;
        private @ShowInQuietMode int mShowInQuietMode =
                SHOW_IN_QUIET_MODE_PAUSED;
        private @ShowInSharingSurfaces int mShowInSharingSurfaces =
                SHOW_IN_SHARING_SURFACES_SEPARATE;
        private @InheritDevicePolicy int mInheritDevicePolicy = INHERIT_DEVICE_POLICY_NO;
        private boolean mUseParentsContacts = false;
        private boolean mUpdateCrossProfileIntentFiltersOnOTA = false;
        private @CrossProfileIntentFilterAccessControlLevel int
                mCrossProfileIntentFilterAccessControl =
                CROSS_PROFILE_INTENT_FILTER_ACCESS_LEVEL_ALL;
        private @CrossProfileIntentResolutionStrategy int mCrossProfileIntentResolutionStrategy =
                CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY_DEFAULT;
        private boolean mMediaSharedWithParent = false;
        private boolean mCredentialShareableWithParent = false;
        private boolean mAuthAlwaysRequiredToDisableQuietMode = false;
        private boolean mAllowStoppingUserWithDelayedLocking = false;
        private boolean mDeleteAppWithParent = false;
        private boolean mAlwaysVisible = false;
        private @CrossProfileContentSharingStrategy int mCrossProfileContentSharingStrategy =
                CROSS_PROFILE_CONTENT_SHARING_NO_DELEGATION;
        private @ProfileApiVisibility int mProfileApiVisibility = 0;

        /**
         * @hide
         */
        @SuppressLint("UnflaggedApi") // b/306636213
        @TestApi
        public Builder() {}

        /** @hide */
        public Builder setShowInLauncher(@ShowInLauncher int showInLauncher) {
            mShowInLauncher = showInLauncher;
            return this;
        }

        /** @hide */
        public Builder setStartWithParent(boolean startWithParent) {
            mStartWithParent = startWithParent;
            return this;
        }

        /** Sets the value for {@link #mShowInSettings}
         * @hide
         */
        public Builder setShowInSettings(@ShowInSettings int showInSettings) {
            mShowInSettings = showInSettings;
            return this;
        }

        /** Sets the value for {@link #mShowInQuietMode}
         * @hide
         */
        @TestApi
        @SuppressLint("UnflaggedApi") // b/306636213
        @NonNull
        public Builder setShowInQuietMode(@ShowInQuietMode int showInQuietMode) {
            mShowInQuietMode = showInQuietMode;
            return this;
        }

        /** Sets the value for {@link #mShowInSharingSurfaces}.
         * @hide
         */
        @TestApi
        @SuppressLint("UnflaggedApi") // b/306636213
        @NonNull
        public Builder setShowInSharingSurfaces(@ShowInSharingSurfaces int showInSharingSurfaces) {
            mShowInSharingSurfaces = showInSharingSurfaces;
            return this;
        }

        /** Sets the value for {@link #mInheritDevicePolicy}
         * @hide
         */
        public Builder setInheritDevicePolicy(
                @InheritDevicePolicy int inheritRestrictionsDevicePolicy) {
            mInheritDevicePolicy = inheritRestrictionsDevicePolicy;
            return this;
        }

        /** @hide */
        public Builder setUseParentsContacts(boolean useParentsContacts) {
            mUseParentsContacts = useParentsContacts;
            return this;
        }

        /** Sets the value for {@link #mUpdateCrossProfileIntentFiltersOnOTA}
         * @hide
         */
        public Builder setUpdateCrossProfileIntentFiltersOnOTA(boolean
                updateCrossProfileIntentFiltersOnOTA) {
            mUpdateCrossProfileIntentFiltersOnOTA = updateCrossProfileIntentFiltersOnOTA;
            return this;
        }

        /** Sets the value for {@link #mCrossProfileIntentFilterAccessControl}
         * @hide
         */
        public Builder setCrossProfileIntentFilterAccessControl(
                @CrossProfileIntentFilterAccessControlLevel int
                        crossProfileIntentFilterAccessControl) {
            mCrossProfileIntentFilterAccessControl = crossProfileIntentFilterAccessControl;
            return this;
        }

        /** Sets the value for {@link #mCrossProfileIntentResolutionStrategy}
         * @hide
         */
        public Builder setCrossProfileIntentResolutionStrategy(@CrossProfileIntentResolutionStrategy
                int crossProfileIntentResolutionStrategy) {
            mCrossProfileIntentResolutionStrategy = crossProfileIntentResolutionStrategy;
            return this;
        }

        /** @hide */
        public Builder setMediaSharedWithParent(boolean mediaSharedWithParent) {
            mMediaSharedWithParent = mediaSharedWithParent;
            return this;
        }

        /** @hide */
        public Builder setCredentialShareableWithParent(boolean credentialShareableWithParent) {
            mCredentialShareableWithParent = credentialShareableWithParent;
            return this;
        }

        /** Sets the value for {@link #mAuthAlwaysRequiredToDisableQuietMode}
         * @hide
         */
        public Builder setAuthAlwaysRequiredToDisableQuietMode(
                boolean authAlwaysRequiredToDisableQuietMode) {
            mAuthAlwaysRequiredToDisableQuietMode =
                    authAlwaysRequiredToDisableQuietMode;
            return this;
        }

        /** Sets the value for {@link #mAllowStoppingUserWithDelayedLocking}
         * @hide
         */
        public Builder setAllowStoppingUserWithDelayedLocking(
                boolean allowStoppingUserWithDelayedLocking) {
            mAllowStoppingUserWithDelayedLocking =
                    allowStoppingUserWithDelayedLocking;
            return this;
        }

        /** Sets the value for {@link #mDeleteAppWithParent}
         * @hide
         */
        public Builder setDeleteAppWithParent(boolean deleteAppWithParent) {
            mDeleteAppWithParent = deleteAppWithParent;
            return this;
        }

        /** Sets the value for {@link #mAlwaysVisible}
         * @hide
         */
        public Builder setAlwaysVisible(boolean alwaysVisible) {
            mAlwaysVisible = alwaysVisible;
            return this;
        }

        /** Sets the value for {@link #mCrossProfileContentSharingStrategy}
         * @hide
         */

        @TestApi
        @SuppressLint("UnflaggedApi") // b/306636213
        @NonNull
        public Builder setCrossProfileContentSharingStrategy(@CrossProfileContentSharingStrategy
                int crossProfileContentSharingStrategy) {
            mCrossProfileContentSharingStrategy = crossProfileContentSharingStrategy;
            return this;
        }

        /**
         * Sets the value for {@link #mProfileApiVisibility}
         * @hide
         */
        @NonNull
        @FlaggedApi(FLAG_SUPPORT_HIDING_PROFILES)
        public Builder setProfileApiVisibility(@ProfileApiVisibility int profileApiVisibility){
            mProfileApiVisibility = profileApiVisibility;
            return this;
        }

        /** Builds a UserProperties object with *all* values populated.
         * @hide
         */
        @TestApi
        @SuppressLint("UnflaggedApi") // b/306636213
        @NonNull
        public UserProperties build() {
            return new UserProperties(
                    mShowInLauncher,
                    mStartWithParent,
                    mShowInSettings,
                    mShowInQuietMode,
                    mShowInSharingSurfaces,
                    mInheritDevicePolicy,
                    mUseParentsContacts,
                    mUpdateCrossProfileIntentFiltersOnOTA,
                    mCrossProfileIntentFilterAccessControl,
                    mCrossProfileIntentResolutionStrategy,
                    mMediaSharedWithParent,
                    mCredentialShareableWithParent,
                    mAuthAlwaysRequiredToDisableQuietMode,
                    mAllowStoppingUserWithDelayedLocking,
                    mDeleteAppWithParent,
                    mAlwaysVisible,
                    mCrossProfileContentSharingStrategy,
                    mProfileApiVisibility);
        }
    } // end Builder

    /** Creates a UserProperties with the given properties. Intended for building default values. */
    private UserProperties(
            @ShowInLauncher int showInLauncher,
            boolean startWithParent,
            @ShowInSettings int showInSettings,
            @ShowInQuietMode int showInQuietMode,
            @ShowInSharingSurfaces int showInSharingSurfaces,
            @InheritDevicePolicy int inheritDevicePolicy,
            boolean useParentsContacts, boolean updateCrossProfileIntentFiltersOnOTA,
            @CrossProfileIntentFilterAccessControlLevel int crossProfileIntentFilterAccessControl,
            @CrossProfileIntentResolutionStrategy int crossProfileIntentResolutionStrategy,
            boolean mediaSharedWithParent,
            boolean credentialShareableWithParent,
            boolean authAlwaysRequiredToDisableQuietMode,
            boolean allowStoppingUserWithDelayedLocking,
            boolean deleteAppWithParent,
            boolean alwaysVisible,
            @CrossProfileContentSharingStrategy int crossProfileContentSharingStrategy,
            @ProfileApiVisibility int profileApiVisibility) {
        mDefaultProperties = null;
        setShowInLauncher(showInLauncher);
        setStartWithParent(startWithParent);
        setShowInSettings(showInSettings);
        setShowInQuietMode(showInQuietMode);
        setShowInSharingSurfaces(showInSharingSurfaces);
        setInheritDevicePolicy(inheritDevicePolicy);
        setUseParentsContacts(useParentsContacts);
        setUpdateCrossProfileIntentFiltersOnOTA(updateCrossProfileIntentFiltersOnOTA);
        setCrossProfileIntentFilterAccessControl(crossProfileIntentFilterAccessControl);
        setCrossProfileIntentResolutionStrategy(crossProfileIntentResolutionStrategy);
        setMediaSharedWithParent(mediaSharedWithParent);
        setCredentialShareableWithParent(credentialShareableWithParent);
        setAuthAlwaysRequiredToDisableQuietMode(
                authAlwaysRequiredToDisableQuietMode);
        setAllowStoppingUserWithDelayedLocking(allowStoppingUserWithDelayedLocking);
        setDeleteAppWithParent(deleteAppWithParent);
        setAlwaysVisible(alwaysVisible);
        setCrossProfileContentSharingStrategy(crossProfileContentSharingStrategy);
        if (android.multiuser.Flags.supportHidingProfiles()) {
            setProfileApiVisibility(profileApiVisibility);
        }
    }
}
