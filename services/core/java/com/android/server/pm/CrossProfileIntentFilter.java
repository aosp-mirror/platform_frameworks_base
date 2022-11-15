/*
 * Copyright 2014, The Android Open Source Project
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

import android.annotation.IntDef;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.utils.SnapshotCache;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The {@link PackageManagerService} maintains some {@link CrossProfileIntentFilter}s for each user.
 * If an {@link Intent} matches the {@link CrossProfileIntentFilter}, then activities in the user
 * {@link #mTargetUserId} can access it.
 */
class CrossProfileIntentFilter extends WatchedIntentFilter {
    private static final String ATTR_TARGET_USER_ID = "targetUserId";
    private static final String ATTR_FLAGS = "flags";
    private static final String ATTR_OWNER_PACKAGE = "ownerPackage";
    private static final String ATTR_FILTER = "filter";
    private static final String ATTR_ACCESS_CONTROL = "accessControl";

    //flag to decide if intent needs to be resolved cross profile if pkgName is already defined
    public static final int FLAG_IS_PACKAGE_FOR_FILTER = 0x00000008;

    /*
    This flag, denotes if further cross profile resolution is allowed, e.g. if profile#0 is linked
    to profile#1 and profile#2 . When intent resolution from profile#1 is started we resolve it in
    profile#1 and profile#0. The profile#0 is also linked to profile#2, we will only resolve in
    profile#2 if CrossProfileIntentFilter between profile#1 and profile#0 have set flag
    FLAG_ALLOW_CHAINED_RESOLUTION.
     */
    public static final int FLAG_ALLOW_CHAINED_RESOLUTION = 0x00000010;

    private static final String TAG = "CrossProfileIntentFilter";

    /**
     * AccessControlLevel provides level of access for user to create/modify
     * {@link CrossProfileIntentFilter} in {@link com.android.server.pm.Settings}.
     * Each AccessControlLevel have value assigned, the higher the value
     * implies higher restriction for creation/modification.
     * AccessControlLevel allows us to protect against malicious changes in user's
     * {@link CrossProfileIntentFilter}s, which might add/remove {@link CrossProfileIntentFilter}
     * leading to unprecedented results.
     */
    @IntDef(prefix = {"ACCESS_LEVEL_"}, value = {
            ACCESS_LEVEL_ALL,
            ACCESS_LEVEL_SYSTEM,
            ACCESS_LEVEL_SYSTEM_ADD_ONLY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AccessControlLevel {
    }

    /**
     * ACCESS_LEVEL_ALL signifies that irrespective of user we would allow
     * access(addition/modification/removal) for CrossProfileIntentFilter.
     * This is the default access control level.
     */
    public static final int ACCESS_LEVEL_ALL = 0;

    /**
     * ACCESS_LEVEL_SYSTEM signifies that only system/root user would be able to
     * access(addition/modification/removal) CrossProfileIntentFilter.
     */
    public static final int ACCESS_LEVEL_SYSTEM = 10;

    /**
     * ACCESS_LEVEL_SYSTEM_ADD_ONLY signifies that only system/root user would be able to add
     * CrossProfileIntentFilter but not modify/remove. Once added, it cannot be modified or removed.
     */
    public static final int ACCESS_LEVEL_SYSTEM_ADD_ONLY = 20;

    // If the intent matches the IntentFilter, then it can be forwarded to this userId.
    final int mTargetUserId;
    final String mOwnerPackage; // packageName of the app.
    final int mFlags;
    final int mAccessControlLevel;

    // The cache for snapshots, so they are not rebuilt if the base object has not
    // changed.
    final SnapshotCache<CrossProfileIntentFilter> mSnapshot;

    private SnapshotCache makeCache() {
        return new SnapshotCache<CrossProfileIntentFilter>(this, this) {
            @Override
            public CrossProfileIntentFilter createSnapshot() {
                CrossProfileIntentFilter s = new CrossProfileIntentFilter(mSource);
                s.seal();
                return s;
            }};
    }

    CrossProfileIntentFilter(IntentFilter filter, String ownerPackage, int targetUserId,
            int flags) {
        this(filter, ownerPackage, targetUserId, flags, ACCESS_LEVEL_ALL);
    }

    CrossProfileIntentFilter(IntentFilter filter, String ownerPackage, int targetUserId,
            int flags, @AccessControlLevel int accessControlLevel) {
        super(filter);
        mTargetUserId = targetUserId;
        mOwnerPackage = ownerPackage;
        mFlags = flags;
        mAccessControlLevel = accessControlLevel;
        mSnapshot = makeCache();
    }

    CrossProfileIntentFilter(WatchedIntentFilter filter, String ownerPackage, int targetUserId,
            int flags) {
        this(filter.mFilter, ownerPackage, targetUserId, flags);
    }

    CrossProfileIntentFilter(WatchedIntentFilter filter, String ownerPackage, int targetUserId,
            int flags, @AccessControlLevel int accessControlLevel) {
        this(filter.mFilter, ownerPackage, targetUserId, flags, accessControlLevel);
    }

    // Copy constructor used only to create a snapshot.
    private CrossProfileIntentFilter(CrossProfileIntentFilter f) {
        super(f);
        mTargetUserId = f.mTargetUserId;
        mOwnerPackage = f.mOwnerPackage;
        mFlags = f.mFlags;
        mAccessControlLevel = f.mAccessControlLevel;
        mSnapshot = new SnapshotCache.Sealed();
    }

    public int getTargetUserId() {
        return mTargetUserId;
    }

    public int getFlags() {
        return mFlags;
    }

    public String getOwnerPackage() {
        return mOwnerPackage;
    }

    public @AccessControlLevel int getAccessControlLevel() {
        return mAccessControlLevel;
    }

    CrossProfileIntentFilter(TypedXmlPullParser parser) throws XmlPullParserException, IOException {
        mTargetUserId = parser.getAttributeInt(null, ATTR_TARGET_USER_ID, UserHandle.USER_NULL);
        mOwnerPackage = getStringFromXml(parser, ATTR_OWNER_PACKAGE, "");
        mAccessControlLevel = parser.getAttributeInt(null, ATTR_ACCESS_CONTROL, ACCESS_LEVEL_ALL);
        mFlags = parser.getAttributeInt(null, ATTR_FLAGS, 0);
        mSnapshot = makeCache();

        int outerDepth = parser.getDepth();
        String tagName = parser.getName();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            tagName = parser.getName();
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            } else if (type == XmlPullParser.START_TAG) {
                if (tagName.equals(ATTR_FILTER)) {
                    break;
                } else {
                    String msg = "Unknown element under "
                            + Settings.TAG_CROSS_PROFILE_INTENT_FILTERS + ": " + tagName + " at "
                            + parser.getPositionDescription();
                    PackageManagerService.reportSettingsProblem(Log.WARN, msg);
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        }
        if (tagName.equals(ATTR_FILTER)) {
            mFilter.readFromXml(parser);
        } else {
            String msg = "Missing element under " + TAG + ": " + ATTR_FILTER +
                    " at " + parser.getPositionDescription();
            PackageManagerService.reportSettingsProblem(Log.WARN, msg);
            XmlUtils.skipCurrentTag(parser);
        }
    }

    private String getStringFromXml(TypedXmlPullParser parser, String attribute,
            String defaultValue) {
        String value = parser.getAttributeValue(null, attribute);
        if (value == null) {
            String msg = "Missing element under " + TAG +": " + attribute + " at " +
                    parser.getPositionDescription();
            PackageManagerService.reportSettingsProblem(Log.WARN, msg);
            return defaultValue;
        } else {
            return value;
        }
    }

    public void writeToXml(TypedXmlSerializer serializer) throws IOException {
        serializer.attributeInt(null, ATTR_TARGET_USER_ID, mTargetUserId);
        serializer.attributeInt(null, ATTR_FLAGS, mFlags);
        serializer.attribute(null, ATTR_OWNER_PACKAGE, mOwnerPackage);
        serializer.attributeInt(null, ATTR_ACCESS_CONTROL, mAccessControlLevel);
        serializer.startTag(null, ATTR_FILTER);
        mFilter.writeToXml(serializer);
        serializer.endTag(null, ATTR_FILTER);
    }

    @Override
    public String toString() {
        return "CrossProfileIntentFilter{0x" + Integer.toHexString(System.identityHashCode(this))
                + " " + Integer.toString(mTargetUserId) + "}";
    }

    boolean equalsIgnoreFilter(CrossProfileIntentFilter other) {
        return mTargetUserId == other.mTargetUserId
                && mOwnerPackage.equals(other.mOwnerPackage)
                && mFlags == other.mFlags
                && mAccessControlLevel == other.mAccessControlLevel;
    }

    public CrossProfileIntentFilter snapshot() {
        return mSnapshot.snapshot();
    }
}
