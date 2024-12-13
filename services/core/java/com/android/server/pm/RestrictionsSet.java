/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.IntArray;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.BundleUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Data structure that contains the mapping of users to user restrictions.
 *
 * @hide
 */
public class RestrictionsSet {

    private static final String USER_ID = "user_id";
    private static final String TAG_RESTRICTIONS = "restrictions";
    private static final String TAG_RESTRICTIONS_USER = "restrictions_user";

    /**
     * Mapping of user restrictions.
     * Only non-empty restriction bundles are stored.
     * The key is the user id of the user.
     * userId -> restrictionBundle
     */
    private final SparseArray<Bundle> mUserRestrictions = new SparseArray<>(0);

    public RestrictionsSet() {
    }

    public RestrictionsSet(@UserIdInt int userId, @NonNull Bundle restrictions) {
        if (restrictions.isEmpty()) {
            throw new IllegalArgumentException("empty restriction bundle cannot be added.");
        }
        mUserRestrictions.put(userId, restrictions);
        UserManager.invalidateUserRestriction();
    }

    /**
     * Updates restriction bundle for a given user.
     * If new bundle is empty, record is removed from the array.
     *
     * @return whether restrictions bundle is different from the old one.
     */
    public boolean updateRestrictions(@UserIdInt int userId, @Nullable Bundle restrictions) {
        final boolean changed =
                !UserRestrictionsUtils.areEqual(mUserRestrictions.get(userId), restrictions);
        if (!changed) {
            return false;
        }
        if (!BundleUtils.isEmpty(restrictions)) {
            mUserRestrictions.put(userId, restrictions);
        } else {
            mUserRestrictions.delete(userId);
        }
        UserManager.invalidateUserRestriction();
        return true;
    }

    /**
     * Removes a particular restriction for all users.
     *
     * @return whether the restriction was removed or not.
     */
    public boolean removeRestrictionsForAllUsers(String restriction) {
        boolean removed = false;
        for (int i = 0; i < mUserRestrictions.size(); i++) {
            final Bundle restrictions = mUserRestrictions.valueAt(i);

            if (UserRestrictionsUtils.contains(restrictions, restriction)) {
                restrictions.remove(restriction);
                removed = true;
            }
        }
        if (removed) {
            UserManager.invalidateUserRestriction();
        }
        return removed;
    }

    /**
     * Moves a particular restriction from one restriction set to another, e.g. for all users.
     */
    public void moveRestriction(@NonNull RestrictionsSet destRestrictions, String restriction) {
        for (int i = 0; i < mUserRestrictions.size(); i++) {
            final int userId = mUserRestrictions.keyAt(i);
            final Bundle from = mUserRestrictions.valueAt(i);

            if (UserRestrictionsUtils.contains(from, restriction)) {
                from.remove(restriction);
                Bundle to = destRestrictions.getRestrictions(userId);
                if (to == null) {
                    to = new Bundle();
                    to.putBoolean(restriction, true);
                    destRestrictions.updateRestrictions(userId, to);
                } else {
                    to.putBoolean(restriction, true);
                }
                // Don't keep empty bundles.
                if (from.isEmpty()) {
                    mUserRestrictions.removeAt(i);
                    i--;
                }
            }
            UserManager.invalidateUserRestriction();
        }
    }

    /**
     * @return whether restrictions set has no restrictions.
     */
    public boolean isEmpty() {
        return mUserRestrictions.size() == 0;
    }

    /**
     * Merge all restrictions in restrictions set into one bundle. The original user restrictions
     * set does not get modified, instead a new bundle is returned.
     *
     * @return restrictions bundle containing all user restrictions.
     */
    public @NonNull Bundle mergeAll() {
        final Bundle result = new Bundle();
        for (int i = 0; i < mUserRestrictions.size(); i++) {
            UserRestrictionsUtils.merge(result, mUserRestrictions.valueAt(i));
        }
        return result;
    }

    /**
     * @return list of enforcing users that enforce a particular restriction.
     */
    public @NonNull List<UserManager.EnforcingUser> getEnforcingUsers(String restriction,
            @UserIdInt int userId) {
        final List<UserManager.EnforcingUser> result = new ArrayList<>();
        if (getRestrictionsNonNull(userId).containsKey(restriction)) {
            result.add(new UserManager.EnforcingUser(userId,
                    UserManager.RESTRICTION_SOURCE_PROFILE_OWNER));
        }

        if (getRestrictionsNonNull(UserHandle.USER_ALL).containsKey(restriction)) {
            result.add(new UserManager.EnforcingUser(UserHandle.USER_ALL,
                    UserManager.RESTRICTION_SOURCE_DEVICE_OWNER));
        }

        return result;
    }

    /**
     * @return list of user restrictions for a given user. Null is returned if the user does not
     * have any restrictions.
     */
    public @Nullable Bundle getRestrictions(@UserIdInt int userId) {
        return mUserRestrictions.get(userId);
    }

    /** @return list of user restrictions for a given user that is not null. */
    public @NonNull Bundle getRestrictionsNonNull(@UserIdInt int userId) {
        return UserRestrictionsUtils.nonNull(mUserRestrictions.get(userId));
    }

    /**
     * Removes a given user from the restrictions set, returning true if the user has non-empty
     * restrictions before removal.
     */
    public boolean remove(@UserIdInt int userId) {
        boolean hasUserRestriction = mUserRestrictions.contains(userId);
        mUserRestrictions.remove(userId);
        UserManager.invalidateUserRestriction();
        return hasUserRestriction;
    }

    /**
     * Remove list of users and user restrictions.
     */
    public void removeAllRestrictions() {
        mUserRestrictions.clear();
        UserManager.invalidateUserRestriction();
    }

    /**
     * Serialize a given {@link RestrictionsSet} to XML.
     */
    public void writeRestrictions(@NonNull TypedXmlSerializer serializer, @NonNull String outerTag)
            throws IOException {
        serializer.startTag(null, outerTag);
        for (int i = 0; i < mUserRestrictions.size(); i++) {
            serializer.startTag(null, TAG_RESTRICTIONS_USER);
            serializer.attributeInt(null, USER_ID, mUserRestrictions.keyAt(i));
            UserRestrictionsUtils.writeRestrictions(serializer, mUserRestrictions.valueAt(i),
                    TAG_RESTRICTIONS);
            serializer.endTag(null, TAG_RESTRICTIONS_USER);
        }
        serializer.endTag(null, outerTag);
    }

    /**
     * Read restrictions from XML.
     */
    public static RestrictionsSet readRestrictions(@NonNull TypedXmlPullParser parser,
            @NonNull String outerTag) throws IOException, XmlPullParserException {
        RestrictionsSet restrictionsSet = new RestrictionsSet();
        int userId = 0;
        int type;

        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            String tag = parser.getName();
            if (type == XmlPullParser.END_TAG && outerTag.equals(tag)) {
                return restrictionsSet;
            } else if (type == XmlPullParser.START_TAG && TAG_RESTRICTIONS_USER.equals(tag)) {
                userId = parser.getAttributeInt(null, USER_ID);
            } else if (type == XmlPullParser.START_TAG && TAG_RESTRICTIONS.equals(tag)) {
                Bundle restrictions = UserRestrictionsUtils.readRestrictions(parser);
                restrictionsSet.updateRestrictions(userId, restrictions);
            }
        }
        throw new XmlPullParserException("restrictions cannot be read as xml is malformed.");
    }

    /**
     * Dumps {@link RestrictionsSet}.
     */
    public void dumpRestrictions(PrintWriter pw, String prefix) {
        boolean noneSet = true;
        for (int i = 0; i < mUserRestrictions.size(); i++) {
            pw.println(prefix + "User Id: " + mUserRestrictions.keyAt(i));
            UserRestrictionsUtils.dumpRestrictions(pw, prefix + "  ", mUserRestrictions.valueAt(i));
            noneSet = false;
        }
        if (noneSet) {
            pw.println(prefix + "none");
        }
    }

    /** @return list of users in this restriction set. */
    public IntArray getUserIds() {
        IntArray userIds = new IntArray(mUserRestrictions.size());
        for (int i = 0; i < mUserRestrictions.size(); i++) {
            userIds.add(mUserRestrictions.keyAt(i));
        }
        return userIds;
    }

    public boolean containsKey(@UserIdInt int userId) {
        return mUserRestrictions.contains(userId);
    }

    @VisibleForTesting
    public int size() {
        return mUserRestrictions.size();
    }

    @VisibleForTesting
    public int keyAt(int index) {
        return mUserRestrictions.keyAt(index);
    }

    @VisibleForTesting
    public Bundle valueAt(int index) {
        return mUserRestrictions.valueAt(index);
    }

}
