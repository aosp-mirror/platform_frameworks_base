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
import android.os.UserManager;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Data structure that contains the mapping of users to user restrictions (either the user
 * restrictions that apply to them, or the user restrictions that they set, depending on the
 * circumstances).
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
        if (!UserRestrictionsUtils.isEmpty(restrictions)) {
            mUserRestrictions.put(userId, restrictions);
        } else {
            mUserRestrictions.delete(userId);
        }
        return true;
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
            @UserIdInt int deviceOwnerUserId) {
        final List<UserManager.EnforcingUser> result = new ArrayList<>();
        for (int i = 0; i < mUserRestrictions.size(); i++) {
            if (UserRestrictionsUtils.contains(mUserRestrictions.valueAt(i), restriction)) {
                result.add(getEnforcingUser(mUserRestrictions.keyAt(i), deviceOwnerUserId));
            }
        }
        return result;
    }

    private UserManager.EnforcingUser getEnforcingUser(@UserIdInt int userId,
            @UserIdInt int deviceOwnerUserId) {
        int source = deviceOwnerUserId == userId
                ? UserManager.RESTRICTION_SOURCE_DEVICE_OWNER
                : UserManager.RESTRICTION_SOURCE_PROFILE_OWNER;
        return new UserManager.EnforcingUser(userId, source);
    }

    /**
     * @return list of user restrictions for a given user. Null is returned if the user does not
     * have any restrictions.
     */
    public @Nullable Bundle getRestrictions(@UserIdInt int userId) {
        return mUserRestrictions.get(userId);
    }

    /**
     * Removes a given user from the restrictions set, returning true if the user has non-empty
     * restrictions before removal.
     */
    public boolean remove(@UserIdInt int userId) {
        boolean hasUserRestriction = mUserRestrictions.contains(userId);
        mUserRestrictions.remove(userId);
        return hasUserRestriction;
    }

    /**
     * Remove list of users and user restrictions.
     */
    public void removeAllRestrictions() {
        mUserRestrictions.clear();
    }

    /**
     * Serialize a given {@link RestrictionsSet} to XML.
     */
    public void writeRestrictions(@NonNull XmlSerializer serializer, @NonNull String outerTag)
            throws IOException {
        serializer.startTag(null, outerTag);
        for (int i = 0; i < mUserRestrictions.size(); i++) {
            serializer.startTag(null, TAG_RESTRICTIONS_USER);
            serializer.attribute(null, USER_ID, String.valueOf(mUserRestrictions.keyAt(i)));
            UserRestrictionsUtils.writeRestrictions(serializer, mUserRestrictions.valueAt(i),
                    TAG_RESTRICTIONS);
            serializer.endTag(null, TAG_RESTRICTIONS_USER);
        }
        serializer.endTag(null, outerTag);
    }

    /**
     * Read restrictions from XML.
     */
    public static RestrictionsSet readRestrictions(@NonNull XmlPullParser parser,
            @NonNull String outerTag) throws IOException, XmlPullParserException {
        RestrictionsSet restrictionsSet = new RestrictionsSet();
        int userId = 0;
        int type;

        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            String tag = parser.getName();
            if (type == XmlPullParser.END_TAG && outerTag.equals(tag)) {
                return restrictionsSet;
            } else if (type == XmlPullParser.START_TAG && TAG_RESTRICTIONS_USER.equals(tag)) {
                userId = Integer.parseInt(parser.getAttributeValue(null, USER_ID));
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
