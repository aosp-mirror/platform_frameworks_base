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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
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
 */
public final class UserProperties implements Parcelable {
    private static final String LOG_TAG = UserProperties.class.getSimpleName();

    // Attribute strings for reading/writing properties to/from XML.
    private static final String ATTR_SHOW_IN_LAUNCHER = "showInLauncher";
    private static final String ATTR_START_WITH_PARENT = "startWithParent";
    private static final String ATTR_SHOW_IN_SETTINGS = "showInSettings";
    private static final String ATTR_INHERIT_DEVICE_POLICY = "inheritDevicePolicy";
    private static final String ATTR_USE_PARENTS_CONTACTS = "useParentsContacts";
    private static final String ATTR_UPDATE_CROSS_PROFILE_INTENT_FILTERS_ON_OTA =
            "updateCrossProfileIntentFiltersOnOTA";
    private static final String ATTR_CROSS_PROFILE_INTENT_FILTER_ACCESS_CONTROL =
            "crossProfileIntentFilterAccessControl";
    private static final String ATTR_CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY =
            "crossProfileIntentResolutionStrategy";

    /** Index values of each property (to indicate whether they are present in this object). */
    @IntDef(prefix = "INDEX_", value = {
            INDEX_SHOW_IN_LAUNCHER,
            INDEX_START_WITH_PARENT,
            INDEX_SHOW_IN_SETTINGS,
            INDEX_INHERIT_DEVICE_POLICY,
            INDEX_USE_PARENTS_CONTACTS,
            INDEX_UPDATE_CROSS_PROFILE_INTENT_FILTERS_ON_OTA,
            INDEX_CROSS_PROFILE_INTENT_FILTER_ACCESS_CONTROL,
            INDEX_CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY
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
    /** A bit set, mapping each PropertyIndex to whether it is present (1) or absent (0). */
    private long mPropertiesPresent = 0;


    /**
     * Possible values for whether or how to show this user in the Launcher.
     * @hide
     */
    @IntDef(prefix = "SHOW_IN_LAUNCHER_", value = {
            SHOW_IN_LAUNCHER_WITH_PARENT,
            SHOW_IN_LAUNCHER_SEPARATE,
            SHOW_IN_LAUNCHER_NO,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ShowInLauncher {
    }
    /**
     * Suggests that the launcher should show this user's apps in the main tab.
     * That is, either this user is a full user, so its apps should be presented accordingly, or, if
     * this user is a profile, then its apps should be shown alongside its parent's apps.
     */
    public static final int SHOW_IN_LAUNCHER_WITH_PARENT = 0;
    /**
     * Suggests that the launcher should show this user's apps, but separately from the apps of this
     * user's parent.
     */
    public static final int SHOW_IN_LAUNCHER_SEPARATE = 1;
    /**
     * Suggests that the launcher should not show this user.
     */
    public static final int SHOW_IN_LAUNCHER_NO = 2;

    /**
     * Possible values for whether or how to show this user in the Settings app.
     * @hide
     */
    @IntDef(prefix = "SHOW_IN_SETTINGS_", value = {
            SHOW_IN_SETTINGS_WITH_PARENT,
            SHOW_IN_SETTINGS_SEPARATE,
            SHOW_IN_SETTINGS_NO,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ShowInSettings {
    }
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
     * with this property value. The {(TODO:b/256978256) @link DevicePolicyEngine}
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
        }
        if (hasManagePermission) {
            // Add items that require MANAGE_USERS or stronger.
            setShowInSettings(orig.getShowInSettings());
            setUseParentsContacts(orig.getUseParentsContacts());
        }
        if (hasQueryOrManagePermission) {
            // Add items that require QUERY_USERS or stronger.
        }
        // Add items that have no permission requirements at all.
        setShowInLauncher(orig.getShowInLauncher());
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
     */
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

    /*
     Indicate if {@link com.android.server.pm.CrossProfileIntentFilter}s need to be updated during
     OTA update between user-parent
     */
    private boolean mUpdateCrossProfileIntentFiltersOnOTA;


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
     * Returns the user's {@link CrossProfileIntentResolutionStrategy}. If not explicitly
     * configured, default value is {@link #CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY_DEFAULT}.
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
    /**
     * Sets {@link CrossProfileIntentResolutionStrategy} for the user.
     * @param val resolution strategy for user
     * @hide
     */
    public void setCrossProfileIntentResolutionStrategy(
            @CrossProfileIntentResolutionStrategy int val) {
        this.mCrossProfileIntentResolutionStrategy = val;
        setPresent(INDEX_CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY);
    }
    private @CrossProfileIntentResolutionStrategy int mCrossProfileIntentResolutionStrategy;


    @Override
    public String toString() {
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
    }

    // For use only with an object that has already had any permission-lacking fields stripped out.
    @Override
    public void writeToParcel(@NonNull Parcel dest, int parcelableFlags) {
        dest.writeLong(mPropertiesPresent);
        dest.writeInt(mShowInLauncher);
        dest.writeBoolean(mStartWithParent);
        dest.writeInt(mShowInSettings);
        dest.writeInt(mInheritDevicePolicy);
        dest.writeBoolean(mUseParentsContacts);
        dest.writeBoolean(mUpdateCrossProfileIntentFiltersOnOTA);
        dest.writeInt(mCrossProfileIntentFilterAccessControl);
        dest.writeInt(mCrossProfileIntentResolutionStrategy);
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
        mInheritDevicePolicy = source.readInt();
        mUseParentsContacts = source.readBoolean();
        mUpdateCrossProfileIntentFiltersOnOTA = source.readBoolean();
        mCrossProfileIntentFilterAccessControl = source.readInt();
        mCrossProfileIntentResolutionStrategy = source.readInt();
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
    public static final class Builder {
        // UserProperties fields and their default values.
        private @ShowInLauncher int mShowInLauncher = SHOW_IN_LAUNCHER_WITH_PARENT;
        private boolean mStartWithParent = false;
        private @ShowInSettings int mShowInSettings = SHOW_IN_SETTINGS_WITH_PARENT;
        private @InheritDevicePolicy int mInheritDevicePolicy = INHERIT_DEVICE_POLICY_NO;
        private boolean mUseParentsContacts = false;
        private boolean mUpdateCrossProfileIntentFiltersOnOTA = false;
        private @CrossProfileIntentFilterAccessControlLevel int
                mCrossProfileIntentFilterAccessControl =
                CROSS_PROFILE_INTENT_FILTER_ACCESS_LEVEL_ALL;
        private @CrossProfileIntentResolutionStrategy int mCrossProfileIntentResolutionStrategy =
                CROSS_PROFILE_INTENT_RESOLUTION_STRATEGY_DEFAULT;

        public Builder setShowInLauncher(@ShowInLauncher int showInLauncher) {
            mShowInLauncher = showInLauncher;
            return this;
        }

        public Builder setStartWithParent(boolean startWithParent) {
            mStartWithParent = startWithParent;
            return this;
        }

        /** Sets the value for {@link #mShowInSettings} */
        public Builder setShowInSettings(@ShowInSettings int showInSettings) {
            mShowInSettings = showInSettings;
            return this;
        }

        /** Sets the value for {@link #mInheritDevicePolicy}*/
        public Builder setInheritDevicePolicy(
                @InheritDevicePolicy int inheritRestrictionsDevicePolicy) {
            mInheritDevicePolicy = inheritRestrictionsDevicePolicy;
            return this;
        }

        public Builder setUseParentsContacts(boolean useParentsContacts) {
            mUseParentsContacts = useParentsContacts;
            return this;
        }

        /** Sets the value for {@link #mUpdateCrossProfileIntentFiltersOnOTA} */
        public Builder setUpdateCrossProfileIntentFiltersOnOTA(boolean
                updateCrossProfileIntentFiltersOnOTA) {
            mUpdateCrossProfileIntentFiltersOnOTA = updateCrossProfileIntentFiltersOnOTA;
            return this;
        }

        /** Sets the value for {@link #mCrossProfileIntentFilterAccessControl} */
        public Builder setCrossProfileIntentFilterAccessControl(
                @CrossProfileIntentFilterAccessControlLevel int
                        crossProfileIntentFilterAccessControl) {
            mCrossProfileIntentFilterAccessControl = crossProfileIntentFilterAccessControl;
            return this;
        }

        /** Sets the value for {@link #mCrossProfileIntentResolutionStrategy} */
        public Builder setCrossProfileIntentResolutionStrategy(@CrossProfileIntentResolutionStrategy
                int crossProfileIntentResolutionStrategy) {
            mCrossProfileIntentResolutionStrategy = crossProfileIntentResolutionStrategy;
            return this;
        }

        /** Builds a UserProperties object with *all* values populated. */
        public UserProperties build() {
            return new UserProperties(
                    mShowInLauncher,
                    mStartWithParent,
                    mShowInSettings,
                    mInheritDevicePolicy,
                    mUseParentsContacts,
                    mUpdateCrossProfileIntentFiltersOnOTA,
                    mCrossProfileIntentFilterAccessControl,
                    mCrossProfileIntentResolutionStrategy);
        }
    } // end Builder

    /** Creates a UserProperties with the given properties. Intended for building default values. */
    private UserProperties(
            @ShowInLauncher int showInLauncher,
            boolean startWithParent,
            @ShowInSettings int showInSettings,
            @InheritDevicePolicy int inheritDevicePolicy,
            boolean useParentsContacts, boolean updateCrossProfileIntentFiltersOnOTA,
            @CrossProfileIntentFilterAccessControlLevel int crossProfileIntentFilterAccessControl,
            @CrossProfileIntentResolutionStrategy int crossProfileIntentResolutionStrategy) {

        mDefaultProperties = null;
        setShowInLauncher(showInLauncher);
        setStartWithParent(startWithParent);
        setShowInSettings(showInSettings);
        setInheritDevicePolicy(inheritDevicePolicy);
        setUseParentsContacts(useParentsContacts);
        setUpdateCrossProfileIntentFiltersOnOTA(updateCrossProfileIntentFiltersOnOTA);
        setCrossProfileIntentFilterAccessControl(crossProfileIntentFilterAccessControl);
        setCrossProfileIntentResolutionStrategy(crossProfileIntentResolutionStrategy);
    }
}
