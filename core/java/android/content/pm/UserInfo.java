/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.res.Resources;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.DebugUtils;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Per-user information.
 *
 * <p>There are 3 base properties of users: {@link #FLAG_SYSTEM}, {@link #FLAG_FULL}, and
 * {@link #FLAG_PROFILE}. Every user must have one of the following combination of these
 * flags:
 * <ul>
 *    <li>FLAG_SYSTEM (user {@link UserHandle#USER_SYSTEM} on a headless-user-0 device)</li>
 *    <li>FLAG_SYSTEM and FLAG_FULL (user {@link UserHandle#USER_SYSTEM} on a regular device)</li>
 *    <li>FLAG_FULL (non-profile secondary user)</li>
 *    <li>FLAG_PROFILE (profile users)</li>
 * </ul>
 * Users can have also have additional flags (such as FLAG_GUEST) as appropriate.
 *
 * @hide
 */
@TestApi
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class UserInfo implements Parcelable {

    /**
     * *************************** NOTE ***************************
     * These flag values CAN NOT CHANGE because they are written
     * directly to storage.
     */

    /**
     * Primary user. In practice, this is just synonymous with {@link #FLAG_SYSTEM}.
     *
     * <p>On many devices, this will also be the first human user.
     * However, in {@link UserManager#isHeadlessSystemUserMode() headless system user mode}, this
     * should be regarded as unsupported since the system user may not be a human.
     *
     * @deprecated For checking for user 0, use {@link #FLAG_SYSTEM}.
     *             For checking for the designated "main human user", use {@link #FLAG_MAIN}.
     */
    @UnsupportedAppUsage
    @Deprecated
    public static final int FLAG_PRIMARY = 0x00000001;

    /**
     * User with administrative privileges. Such a user can create and
     * delete users.
     */
    public static final int FLAG_ADMIN   = 0x00000002;

    /**
     * Indicates a guest user that may be transient.
     * @deprecated Use {@link UserManager#USER_TYPE_FULL_GUEST} instead.
     */
    @Deprecated
    public static final int FLAG_GUEST   = 0x00000004;

    /**
     * Indicates the user has restrictions in privileges, in addition to those for normal users.
     * Exact meaning TBD. For instance, maybe they can't install apps or administer WiFi access pts.
     * @deprecated Use {@link UserManager#USER_TYPE_FULL_RESTRICTED} instead.
     */
    @Deprecated
    public static final int FLAG_RESTRICTED = 0x00000008;

    /**
     * Indicates that this user has gone through its first-time initialization.
     */
    public static final int FLAG_INITIALIZED = 0x00000010;

    /**
     * Indicates that this user is a profile of another user, for example holding a users
     * corporate data.
     * @deprecated Use {@link UserManager#USER_TYPE_PROFILE_MANAGED} instead.
     */
    @Deprecated
    public static final int FLAG_MANAGED_PROFILE = 0x00000020;

    /**
     * Indicates that this user is disabled.
     *
     * <p> This is currently used to indicate that a Managed Profile, when created via
     * DevicePolicyManager, has not yet been provisioned; once the DPC provisions it, a DPM call
     * will manually set it to enabled.
     *
     * <p>Users that are slated for deletion are also generally set to disabled.
     *
     * <p>Note: If an ephemeral user is disabled, it shouldn't be later re-enabled. Ephemeral users
     * are disabled as their removal is in progress to indicate that they shouldn't be re-entered.
     */
    public static final int FLAG_DISABLED = 0x00000040;

    public static final int FLAG_QUIET_MODE = 0x00000080;

    /**
     * Indicates that this user is ephemeral. I.e. the user will be removed after leaving
     * the foreground.
     */
    public static final int FLAG_EPHEMERAL = 0x00000100;

    /**
     * User is for demo purposes only and can be removed at any time.
     * @deprecated Use {@link UserManager#USER_TYPE_FULL_DEMO} instead.
     */
    @Deprecated
    public static final int FLAG_DEMO = 0x00000200;

    /**
     * Indicates that this user is a non-profile human user.
     *
     * <p>When creating a new (non-system) user, this flag will always be forced true unless the
     * user is a {@link #FLAG_PROFILE}. If user {@link UserHandle#USER_SYSTEM} is also a
     * human user, it must also be flagged as FULL.
     */
    public static final int FLAG_FULL = 0x00000400;

    /**
     * Indicates that this user is {@link UserHandle#USER_SYSTEM}. Not applicable to created users.
     */
    public static final int FLAG_SYSTEM = 0x00000800;

    /**
     * Indicates that this user is a profile human user, such as a managed profile.
     * Mutually exclusive with {@link #FLAG_FULL}.
     */
    public static final int FLAG_PROFILE = 0x00001000;

    /**
     * Indicates that this user is created in ephemeral mode via
     * {@link IUserManager} create user.
     *
     * When a user is created with {@link #FLAG_EPHEMERAL}, {@link #FLAG_EPHEMERAL_ON_CREATE}
     * is set internally within the user manager.
     *
     * When {@link #FLAG_EPHEMERAL_ON_CREATE} is set {@link IUserManager.setUserEphemeral}
     * has no effect because a user that was created ephemeral can never be made non-ephemeral.
     *
     * {@link #FLAG_EPHEMERAL_ON_CREATE} should NOT be set by client's of user manager
     *
     * @hide
     */
    public static final int FLAG_EPHEMERAL_ON_CREATE = 0x00002000;

    /**
     * Indicates that this user is the designated main user on the device. This user may have access
     * to certain features which are limited to at most one user.
     *
     * <p>Currently, this will be the first user to go through setup on the device, but in future
     * releases this status may be transferable or may even not be given to any users.
     *
     * <p>This is not necessarily the system user. For example, it will not be the system user on
     * devices for which {@link UserManager#isHeadlessSystemUserMode()} returns true.
     *
     * <p>NB: Features should ideally not limit functionality to the main user. Ideally, they
     * should either work for all users or for all admin users. If a feature should only work for
     * select users, its determination of which user should be done intelligently or be
     * customizable. Not all devices support a main user, and the idea of singling out one user as
     * special is contrary to overall multiuser goals.
     */
    public static final int FLAG_MAIN = 0x00004000;

    /**
     * Indicates that this user was created for the purposes of testing.
     *
     * <p>These users are subject to removal during tests and should not be used on actual devices
     * used by humans.
     *
     * @hide
     */
    public static final int FLAG_FOR_TESTING = 0x00008000;

    /**
     * @hide
     */
    @IntDef(flag = true, prefix = "FLAG_", value = {
            FLAG_PRIMARY,
            FLAG_ADMIN,
            FLAG_GUEST,
            FLAG_RESTRICTED,
            FLAG_INITIALIZED,
            FLAG_MANAGED_PROFILE,
            FLAG_DISABLED,
            FLAG_QUIET_MODE,
            FLAG_EPHEMERAL,
            FLAG_DEMO,
            FLAG_FULL,
            FLAG_SYSTEM,
            FLAG_PROFILE,
            FLAG_EPHEMERAL_ON_CREATE,
            FLAG_MAIN,
            FLAG_FOR_TESTING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UserInfoFlag {
    }

    public static final int NO_PROFILE_GROUP_ID = UserHandle.USER_NULL;

    @UnsupportedAppUsage
    public @UserIdInt int id;
    @UnsupportedAppUsage
    public int serialNumber;
    @UnsupportedAppUsage
    public @Nullable String name;
    @UnsupportedAppUsage
    public String iconPath;
    @UnsupportedAppUsage
    public @UserInfoFlag int flags;
    @UnsupportedAppUsage
    public long creationTime;
    @UnsupportedAppUsage
    public long lastLoggedInTime;
    public String lastLoggedInFingerprint;

    /**
     * Type of user, such as {@link UserManager#USER_TYPE_PROFILE_MANAGED}, corresponding to
     * {@link com.android.server.pm.UserTypeDetails#getName()}.
     */
    public String userType;

    /**
     * If this user is a parent user, it would be its own user id.
     * If this user is a child user, it would be its parent user id.
     * Otherwise, it would be {@link #NO_PROFILE_GROUP_ID}.
     */
    @UnsupportedAppUsage
    public int profileGroupId;
    public int restrictedProfileParentId;

    /**
     * Index for distinguishing different profiles with the same parent and user type for the
     * purpose of badging.
     * It is used for determining which badge color/label to use (if applicable) from
     * the options available for a particular user type.
     */
    public int profileBadge;

    /** User is only partially created. */
    @UnsupportedAppUsage
    public boolean partial;
    @UnsupportedAppUsage
    public boolean guestToRemove;

    /**
     * This is used to optimize the creation of a user, i.e. OEMs might choose to pre-create a
     * number of users at the first boot, so the actual creation later is faster.
     *
     * <p>A {@code preCreated} user is not a real user yet, so it should not show up on regular
     * user operations (other than user creation per se).
     *
     * <p>Once the pre-created is used to create a "real" user later on, {@code preCreated} is set
     * to {@code false}.
     *
     * <p><b>NOTE: Pre-created users are deprecated. This field remains to be able to recognize
     * pre-created users in older versions, but will eventually be removed.
     */
    public boolean preCreated;

    /**
     * When {@code true}, it indicates this user was created by converting a {@link #preCreated}
     * user.
     *
     * <p><b>NOTE: </b>only used for debugging purposes, it's not set when marshalled to a parcel.
     *
     * <p><b>NOTE: Pre-created users are deprecated. This field remains to be able to recognize
     * pre-created users in older versions, but will eventually ve removed.
     */
    public boolean convertedFromPreCreated;

    /**
     * Creates a UserInfo whose user type is determined automatically by the flags according to
     * {@link #getDefaultUserType}; can only be used for user types handled there.
     */
    @UnsupportedAppUsage
    public UserInfo(int id, String name, int flags) {
        this(id, name, null, flags);
    }

    /**
     * Creates a UserInfo whose user type is determined automatically by the flags according to
     * {@link #getDefaultUserType}; can only be used for user types handled there.
     */
    @UnsupportedAppUsage
    public UserInfo(int id, String name, String iconPath, int flags) {
        this(id, name, iconPath, flags, getDefaultUserType(flags));
    }

    public UserInfo(int id, String name, String iconPath, int flags, String userType) {
        this.id = id;
        this.name = name;
        this.flags = flags;
        this.userType = userType;
        this.iconPath = iconPath;
        this.profileGroupId = NO_PROFILE_GROUP_ID;
        this.restrictedProfileParentId = NO_PROFILE_GROUP_ID;
    }

    /**
     * Get the user type (such as {@link UserManager#USER_TYPE_PROFILE_MANAGED}) that corresponds to
     * the given {@link UserInfoFlag}s.

     * <p>The userInfoFlag can contain GUEST, RESTRICTED, MANAGED_PROFILE, DEMO, or else be
     * interpreted as a regular "secondary" user. It cannot contain more than one of these.
     * It can contain other UserInfoFlag properties (like EPHEMERAL), which will be ignored here.
     *
     * @throws IllegalArgumentException if userInfoFlag is more than one type of user or if it
     *                                  is a SYSTEM user.
     *
     * @hide
     */
    public static @NonNull String getDefaultUserType(@UserInfoFlag int userInfoFlag) {
        if ((userInfoFlag & FLAG_SYSTEM) != 0) {
            throw new IllegalArgumentException("Cannot getDefaultUserType for flags "
                    + Integer.toHexString(userInfoFlag) + " because it corresponds to a "
                    + "SYSTEM user type.");
        }
        final int supportedFlagTypes =
                FLAG_GUEST | FLAG_RESTRICTED | FLAG_MANAGED_PROFILE | FLAG_DEMO;
        switch (userInfoFlag & supportedFlagTypes) {
            case 0 :                   return UserManager.USER_TYPE_FULL_SECONDARY;
            case FLAG_GUEST:           return UserManager.USER_TYPE_FULL_GUEST;
            case FLAG_RESTRICTED:      return UserManager.USER_TYPE_FULL_RESTRICTED;
            case FLAG_MANAGED_PROFILE: return UserManager.USER_TYPE_PROFILE_MANAGED;
            case FLAG_DEMO:            return UserManager.USER_TYPE_FULL_DEMO;
            default:
                throw new IllegalArgumentException("Cannot getDefaultUserType for flags "
                        + Integer.toHexString(userInfoFlag) + " because it doesn't correspond to a "
                        + "valid user type.");
        }
    }

    /**
     * @deprecated For checking for user 0, compare {@link #id} to {@link UserHandle#USER_SYSTEM}.
     *             For checking for the designated "main human user", use {@link #isMain()}.
     */
    @UnsupportedAppUsage
    @Deprecated
    public boolean isPrimary() {
        return (flags & FLAG_PRIMARY) == FLAG_PRIMARY;
    }

    @UnsupportedAppUsage
    public boolean isAdmin() {
        return (flags & FLAG_ADMIN) == FLAG_ADMIN;
    }

    @UnsupportedAppUsage
    public boolean isGuest() {
        return UserManager.isUserTypeGuest(userType);
    }

    @UnsupportedAppUsage
    public boolean isRestricted() {
        return UserManager.isUserTypeRestricted(userType);
    }

    public boolean isProfile() {
        return (flags & FLAG_PROFILE) != 0;
    }

    @UnsupportedAppUsage
    public boolean isManagedProfile() {
        return UserManager.isUserTypeManagedProfile(userType);
    }

    public boolean isCloneProfile() {
        return UserManager.isUserTypeCloneProfile(userType);
    }

    @FlaggedApi(android.multiuser.Flags.FLAG_SUPPORT_COMMUNAL_PROFILE)
    public boolean isCommunalProfile() {
        return UserManager.isUserTypeCommunalProfile(userType);
    }

    @FlaggedApi(android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE)
    public boolean isPrivateProfile() {
        return UserManager.isUserTypePrivateProfile(userType);
    }

    /** See {@link #FLAG_DISABLED}*/
    @UnsupportedAppUsage
    public boolean isEnabled() {
        return (flags & FLAG_DISABLED) != FLAG_DISABLED;
    }

    public boolean isQuietModeEnabled() {
        return (flags & FLAG_QUIET_MODE) == FLAG_QUIET_MODE;
    }

    public boolean isEphemeral() {
        return (flags & FLAG_EPHEMERAL) == FLAG_EPHEMERAL;
    }

    /** @hide */
    @TestApi
    public boolean isForTesting() {
        return (flags & FLAG_FOR_TESTING) == FLAG_FOR_TESTING;
    }

    public boolean isInitialized() {
        return (flags & FLAG_INITIALIZED) == FLAG_INITIALIZED;
    }

    public boolean isDemo() {
        return UserManager.isUserTypeDemo(userType) || (flags & FLAG_DEMO) != 0;
    }

    public boolean isFull() {
        return (flags & FLAG_FULL) == FLAG_FULL;
    }

    /**
     * @see #FLAG_MAIN
     */
    public boolean isMain() {
        return (flags & FLAG_MAIN) == FLAG_MAIN;
    }

    /**
     * @return true if this user can be switched to.
     **/
    @android.ravenwood.annotation.RavenwoodThrow
    public boolean supportsSwitchTo() {
        if (partial || !isEnabled()) {
            // Don't support switching to disabled or partial users, which includes users with
            // removal in progress.
            return false;
        }
        if (preCreated) {
            // Don't support switching to pre-created users until they become "real" users.
            return false;
        }
        return isFull() || canSwitchToHeadlessSystemUser();
    }

    /**
     * @return true if user is of type {@link UserManager#USER_TYPE_SYSTEM_HEADLESS} and
     * {@link com.android.internal.R.bool.config_canSwitchToHeadlessSystemUser} is true.
     */
    @android.ravenwood.annotation.RavenwoodThrow
    private boolean canSwitchToHeadlessSystemUser() {
        return UserManager.USER_TYPE_SYSTEM_HEADLESS.equals(userType) && Resources.getSystem()
                .getBoolean(com.android.internal.R.bool.config_canSwitchToHeadlessSystemUser);
    }

    /**
     * @return true if this user can be switched to by end user through UI.
     * @deprecated Use {@link UserInfo#supportsSwitchTo} instead.
     */
    @Deprecated
    @android.ravenwood.annotation.RavenwoodThrow
    public boolean supportsSwitchToByUser() {
        return supportsSwitchTo();
    }

    // TODO(b/142482943): Make this logic more specific and customizable. (canHaveProfile(userType))
    /* @hide */
    public boolean canHaveProfile() {
        if (isProfile() || isGuest() || isRestricted()) {
            return false;
        }
        return isMain();
    }

    // TODO(b/142482943): Get rid of this (after removing it from all tests) if feasible.
    /**
     * @deprecated This is dangerous since it doesn't set the mandatory fields. Use a different
     * constructor instead.
     */
    @Deprecated
    @VisibleForTesting
    public UserInfo() {
    }

    public UserInfo(UserInfo orig) {
        name = orig.name;
        iconPath = orig.iconPath;
        id = orig.id;
        flags = orig.flags;
        userType = orig.userType;
        serialNumber = orig.serialNumber;
        creationTime = orig.creationTime;
        lastLoggedInTime = orig.lastLoggedInTime;
        lastLoggedInFingerprint = orig.lastLoggedInFingerprint;
        partial = orig.partial;
        preCreated = orig.preCreated;
        convertedFromPreCreated = orig.convertedFromPreCreated;
        profileGroupId = orig.profileGroupId;
        restrictedProfileParentId = orig.restrictedProfileParentId;
        guestToRemove = orig.guestToRemove;
        profileBadge = orig.profileBadge;
    }

    @UnsupportedAppUsage
    public UserHandle getUserHandle() {
        return UserHandle.of(id);
    }

    // TODO(b/142482943): Probably include mUserType here, which means updating TestDevice, etc.
    @Override
    public String toString() {
        // NOTE:  do not change this string, it's used by 'pm list users', which in turn is
        // used and parsed by TestDevice. In other words, if you change it, you'd have to change
        // TestDevice, TestDeviceTest, and possibly others....
        return "UserInfo{" + id + ":" + name + ":" + Integer.toHexString(flags) + "}";
    }

    /** @hide */
    public String toFullString() {
        return "UserInfo[id=" + id
                + ", name=" + name
                + ", type=" + userType
                + ", flags=" + flagsToString(flags)
                + (preCreated ? " (pre-created)" : "")
                + (convertedFromPreCreated ? " (converted)" : "")
                + (partial ? " (partial)" : "")
                + "]";
    }

    /** @hide */
    public static String flagsToString(int flags) {
        return DebugUtils.flagsToString(UserInfo.class, "FLAG_", flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeInt(id);
        dest.writeString8(name);
        dest.writeString8(iconPath);
        dest.writeInt(flags);
        dest.writeString8(userType);
        dest.writeInt(serialNumber);
        dest.writeLong(creationTime);
        dest.writeLong(lastLoggedInTime);
        dest.writeString8(lastLoggedInFingerprint);
        dest.writeBoolean(partial);
        dest.writeBoolean(preCreated);
        dest.writeInt(profileGroupId);
        dest.writeBoolean(guestToRemove);
        dest.writeInt(restrictedProfileParentId);
        dest.writeInt(profileBadge);
    }

    @UnsupportedAppUsage
    public static final @android.annotation.NonNull Parcelable.Creator<UserInfo> CREATOR
            = new Parcelable.Creator<UserInfo>() {
        public UserInfo createFromParcel(Parcel source) {
            return new UserInfo(source);
        }
        public UserInfo[] newArray(int size) {
            return new UserInfo[size];
        }
    };

    private UserInfo(Parcel source) {
        id = source.readInt();
        name = source.readString8();
        iconPath = source.readString8();
        flags = source.readInt();
        userType = source.readString8();
        serialNumber = source.readInt();
        creationTime = source.readLong();
        lastLoggedInTime = source.readLong();
        lastLoggedInFingerprint = source.readString8();
        partial = source.readBoolean();
        preCreated = source.readBoolean();
        profileGroupId = source.readInt();
        guestToRemove = source.readBoolean();
        restrictedProfileParentId = source.readInt();
        profileBadge = source.readInt();
    }
}
