/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.content.ComponentName;
import android.content.IntentFilter;
import android.util.Log;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.util.XmlUtils;
import com.android.server.utils.SnapshotCache;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

class PersistentPreferredActivity extends WatchedIntentFilter {
    private static final String ATTR_NAME = "name"; // component name
    private static final String ATTR_FILTER = "filter"; // filter
    private static final String ATTR_SET_BY_DPM = "set-by-dpm"; // set by DPM

    private static final String TAG = "PersistentPreferredActivity";

    private static final boolean DEBUG_FILTERS = false;

    final ComponentName mComponent;
    final boolean mIsSetByDpm;

    // The cache for snapshots, so they are not rebuilt if the base object has not
    // changed.
    final SnapshotCache<PersistentPreferredActivity> mSnapshot;

    private SnapshotCache makeCache() {
        return new SnapshotCache<PersistentPreferredActivity>(this, this) {
            @Override
            public PersistentPreferredActivity createSnapshot() {
                PersistentPreferredActivity s = new PersistentPreferredActivity(mSource);
                s.seal();
                return s;
            }};
    }

    PersistentPreferredActivity(IntentFilter filter, ComponentName activity, boolean isSetByDpm) {
        super(filter);
        mComponent = activity;
        mIsSetByDpm = isSetByDpm;
        mSnapshot = makeCache();
    }

    PersistentPreferredActivity(WatchedIntentFilter filter, ComponentName activity,
            boolean isSetByDpm) {
        this(filter.mFilter, activity, isSetByDpm);
    }

    // Copy constructor used only to create a snapshot
    private PersistentPreferredActivity(PersistentPreferredActivity f) {
        super(f);
        mComponent = f.mComponent;
        mIsSetByDpm = f.mIsSetByDpm;
        mSnapshot = new SnapshotCache.Sealed();
    }

    PersistentPreferredActivity(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        String shortComponent = parser.getAttributeValue(null, ATTR_NAME);
        mComponent = ComponentName.unflattenFromString(shortComponent);
        if (mComponent == null) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Error in package manager settings: " +
                            "Bad activity name " + shortComponent +
                            " at " + parser.getPositionDescription());
        }
        mIsSetByDpm = parser.getAttributeBoolean(null, ATTR_SET_BY_DPM, false);

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
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Unknown element: " + tagName +
                            " at " + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        }
        if (tagName.equals(ATTR_FILTER)) {
            mFilter.readFromXml(parser);
        } else {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Missing element filter at " +
                    parser.getPositionDescription());
            XmlUtils.skipCurrentTag(parser);
        }
        mSnapshot = makeCache();
    }

    public void writeToXml(TypedXmlSerializer serializer) throws IOException {
        serializer.attribute(null, ATTR_NAME, mComponent.flattenToShortString());
        serializer.attributeBoolean(null, ATTR_SET_BY_DPM, mIsSetByDpm);
        serializer.startTag(null, ATTR_FILTER);
        mFilter.writeToXml(serializer);
        serializer.endTag(null, ATTR_FILTER);
    }

    public IntentFilter getIntentFilter() {
        return mFilter;
    }

    @Override
    public String toString() {
        return "PersistentPreferredActivity{0x" + Integer.toHexString(System.identityHashCode(this))
                + " " + mComponent.flattenToShortString()
                + ", mIsSetByDpm=" + mIsSetByDpm + "}";
    }

    public PersistentPreferredActivity snapshot() {
        return mSnapshot.snapshot();
    }
}
