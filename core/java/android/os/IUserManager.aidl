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
import android.os.ParcelFileDescriptor;
import android.content.pm.UserInfo;
import android.content.RestrictionEntry;
import android.graphics.Bitmap;

/**
 *  {@hide}
 */
interface IUserManager {
    UserInfo createUser(in String name, int flags);
    boolean removeUser(int userHandle);
    void setUserName(int userHandle, String name);
    void setUserIcon(int userHandle, in Bitmap icon);
    Bitmap getUserIcon(int userHandle);
    List<UserInfo> getUsers(boolean excludeDying);
    UserInfo getUserInfo(int userHandle);
    boolean isRestricted();
    void setGuestEnabled(boolean enable);
    boolean isGuestEnabled();
    void wipeUser(int userHandle);
    int getUserSerialNumber(int userHandle);
    int getUserHandle(int userSerialNumber);
    Bundle getUserRestrictions(int userHandle);
    void setUserRestrictions(in Bundle restrictions, int userHandle);
    void setApplicationRestrictions(in String packageName, in Bundle restrictions,
            int userHandle);
    Bundle getApplicationRestrictions(in String packageName);
    Bundle getApplicationRestrictionsForUser(in String packageName, int userHandle);
}
