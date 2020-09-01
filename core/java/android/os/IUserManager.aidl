/*
**
** Copyright 2012, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.os;

import android.os.Bundle;
import android.os.IUserRestrictionsListener;
import android.os.PersistableBundle;
import android.os.UserManager;
import android.content.pm.UserInfo;
import android.content.IntentSender;
import android.content.RestrictionEntry;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;

/**
 *  {@hide}
 */
interface IUserManager {

    /*
     * DO NOT MOVE - UserManager.h depends on the ordering of this function.
     */
    int getCredentialOwnerProfile(int userId);
    int getProfileParentId(int userId);
    /*
     * END OF DO NOT MOVE
     */

    UserInfo createUserWithThrow(in String name, in String userType, int flags);
    UserInfo preCreateUserWithThrow(in String userType);
    UserInfo createProfileForUserWithThrow(in String name, in String userType, int flags, int userId,
            in String[] disallowedPackages);
    UserInfo createRestrictedProfileWithThrow(String name, int parentUserHandle);
    void setUserEnabled(int userId);
    void setUserAdmin(int userId);
    void evictCredentialEncryptionKey(int userId);
    boolean removeUser(int userId);
    boolean removeUserEvenWhenDisallowed(int userId);
    void setUserName(int userId, String name);
    void setUserIcon(int userId, in Bitmap icon);
    ParcelFileDescriptor getUserIcon(int userId);
    UserInfo getPrimaryUser();
    List<UserInfo> getUsers(boolean excludePartial, boolean excludeDying, boolean excludePreCreated);
    List<UserInfo> getProfiles(int userId, boolean enabledOnly);
    int[] getProfileIds(int userId, boolean enabledOnly);
    boolean canAddMoreProfilesToUser(in String userType, int userId, boolean allowedToRemoveOne);
    boolean canAddMoreManagedProfiles(int userId, boolean allowedToRemoveOne);
    UserInfo getProfileParent(int userId);
    boolean isSameProfileGroup(int userId, int otherUserHandle);
    boolean isUserOfType(int userId, in String userType);
    @UnsupportedAppUsage
    UserInfo getUserInfo(int userId);
    String getUserAccount(int userId);
    void setUserAccount(int userId, String accountName);
    long getUserCreationTime(int userId);
    boolean isRestricted();
    boolean canHaveRestrictedProfile(int userId);
    int getUserSerialNumber(int userId);
    int getUserHandle(int userSerialNumber);
    int getUserRestrictionSource(String restrictionKey, int userId);
    List<UserManager.EnforcingUser> getUserRestrictionSources(String restrictionKey, int userId);
    Bundle getUserRestrictions(int userId);
    boolean hasBaseUserRestriction(String restrictionKey, int userId);
    boolean hasUserRestriction(in String restrictionKey, int userId);
    boolean hasUserRestrictionOnAnyUser(in String restrictionKey);
    boolean isSettingRestrictedForUser(in String setting, int userId, in String value, int callingUid);
    void addUserRestrictionsListener(IUserRestrictionsListener listener);
    void setUserRestriction(String key, boolean value, int userId);
    void setApplicationRestrictions(in String packageName, in Bundle restrictions, int userId);
    Bundle getApplicationRestrictions(in String packageName);
    Bundle getApplicationRestrictionsForUser(in String packageName, int userId);
    void setDefaultGuestRestrictions(in Bundle restrictions);
    Bundle getDefaultGuestRestrictions();
    boolean markGuestForDeletion(int userId);
    UserInfo findCurrentGuestUser();
    boolean isQuietModeEnabled(int userId);
    void setSeedAccountData(int userId, in String accountName,
            in String accountType, in PersistableBundle accountOptions, boolean persist);
    String getSeedAccountName();
    String getSeedAccountType();
    PersistableBundle getSeedAccountOptions();
    void clearSeedAccountData();
    boolean someUserHasSeedAccount(in String accountName, in String accountType);
    boolean isProfile(int userId);
    boolean isManagedProfile(int userId);
    boolean isDemoUser(int userId);
    boolean isPreCreated(int userId);
    UserInfo createProfileForUserEvenWhenDisallowedWithThrow(in String name, in String userType, int flags,
            int userId, in String[] disallowedPackages);
    boolean isUserUnlockingOrUnlocked(int userId);
    int getUserIconBadgeResId(int userId);
    int getUserBadgeResId(int userId);
    int getUserBadgeNoBackgroundResId(int userId);
    int getUserBadgeLabelResId(int userId);
    int getUserBadgeColorResId(int userId);
    int getUserBadgeDarkColorResId(int userId);
    boolean hasBadge(int userId);
    boolean isUserUnlocked(int userId);
    boolean isUserRunning(int userId);
    boolean isUserNameSet(int userId);
    boolean hasRestrictedProfiles();
    boolean requestQuietModeEnabled(String callingPackage, boolean enableQuietMode, int userId, in IntentSender target, int flags);
    String getUserName();
    long getUserStartRealtime();
    long getUserUnlockRealtime();
}
