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

import com.android.internal.util.XmlUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;
import android.content.IntentFilter;
import android.util.Log;
import java.io.IOException;
import android.os.UserHandle;

/**
 * The {@link PackageManagerService} maintains some {@link CrossProfileIntentFilter}s for each user.
 * If an {@link Intent} matches the {@link CrossProfileIntentFilter}, then activities in the user
 * {@link #mTargetUserId} can access it.
 */
class CrossProfileIntentFilter extends IntentFilter {
    private static final String ATTR_TARGET_USER_ID = "targetUserId";
    private static final String ATTR_USER_ID_DEST = "userIdDest";//Old name. Kept for compatibility.
    private static final String ATTR_REMOVABLE = "removable";
    private static final String ATTR_FILTER = "filter";

    private static final String TAG = "CrossProfileIntentFilter";

    // If the intent matches the IntentFilter, then it can be forwarded to this userId.
    final int mTargetUserId;
    boolean mRemovable;

    CrossProfileIntentFilter(IntentFilter filter, boolean removable, int targetUserId) {
        super(filter);
        mTargetUserId = targetUserId;
        mRemovable = removable;
    }

    public int getTargetUserId() {
        return mTargetUserId;
    }

    public boolean isRemovable() {
        return mRemovable;
    }

    CrossProfileIntentFilter(XmlPullParser parser) throws XmlPullParserException, IOException {
        String targetUserIdString = parser.getAttributeValue(null, ATTR_TARGET_USER_ID);
        if (targetUserIdString == null) {
            targetUserIdString = parser.getAttributeValue(null, ATTR_USER_ID_DEST);
        }
        if (targetUserIdString == null) {
            String msg = "Missing element under " + TAG +": " + ATTR_TARGET_USER_ID + " at " +
                    parser.getPositionDescription();
            PackageManagerService.reportSettingsProblem(Log.WARN, msg);
            mTargetUserId = UserHandle.USER_NULL;
        } else {
            mTargetUserId = Integer.parseInt(targetUserIdString);
        }
        String removableString = parser.getAttributeValue(null, ATTR_REMOVABLE);
        if (removableString != null) {
            mRemovable = Boolean.parseBoolean(removableString);
        }
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
                    String msg = "Unknown element under " + Settings.TAG_FORWARDING_INTENT_FILTERS
                            + ": " + tagName + " at " + parser.getPositionDescription();
                    PackageManagerService.reportSettingsProblem(Log.WARN, msg);
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        }
        if (tagName.equals(ATTR_FILTER)) {
            readFromXml(parser);
        } else {
            String msg = "Missing element under " + TAG + ": " + ATTR_FILTER +
                    " at " + parser.getPositionDescription();
            PackageManagerService.reportSettingsProblem(Log.WARN, msg);
            XmlUtils.skipCurrentTag(parser);
        }
    }

    public void writeToXml(XmlSerializer serializer) throws IOException {
        serializer.attribute(null, ATTR_TARGET_USER_ID, Integer.toString(mTargetUserId));
        serializer.attribute(null, ATTR_REMOVABLE, Boolean.toString(mRemovable));
        serializer.startTag(null, ATTR_FILTER);
            super.writeToXml(serializer);
        serializer.endTag(null, ATTR_FILTER);
    }

    @Override
    public String toString() {
        return "CrossProfileIntentFilter{0x" + Integer.toHexString(System.identityHashCode(this))
                + " " + Integer.toString(mTargetUserId) + "}";
    }
}
