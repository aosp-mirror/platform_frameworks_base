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

package com.android.server.pm;

import android.content.ComponentName;
import android.content.IntentFilter;
import android.util.Log;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.util.XmlUtils;
import com.android.server.utils.SnapshotCache;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;

class PreferredActivity extends WatchedIntentFilter implements PreferredComponent.Callbacks {
    private static final String TAG = "PreferredActivity";

    private static final boolean DEBUG_FILTERS = false;

    final PreferredComponent mPref;

    // The cache for snapshots, so they are not rebuilt if the base object has not
    // changed.
    final SnapshotCache<PreferredActivity> mSnapshot;

    private SnapshotCache makeCache() {
        return new SnapshotCache<PreferredActivity>(this, this) {
            @Override
            public PreferredActivity createSnapshot() {
                PreferredActivity s = new PreferredActivity(mSource);
                s.seal();
                return s;
            }};
    }

    PreferredActivity(IntentFilter filter, int match, ComponentName[] set, ComponentName activity,
            boolean always) {
        super(filter);
        mPref = new PreferredComponent(this, match, set, activity, always);
        mSnapshot = makeCache();
    }

    PreferredActivity(WatchedIntentFilter filter, int match, ComponentName[] set,
            ComponentName activity, boolean always) {
        this(filter.mFilter, match, set, activity, always);
    }

    // Copy constructor used only to create a snapshot
    private PreferredActivity(PreferredActivity f) {
        super(f);
        mPref = f.mPref;
        mSnapshot = new SnapshotCache.Sealed();
    }

    PreferredActivity(TypedXmlPullParser parser) throws XmlPullParserException, IOException {
        mPref = new PreferredComponent(this, parser);
        mSnapshot = makeCache();
    }

    public void writeToXml(TypedXmlSerializer serializer, boolean full) throws IOException {
        mPref.writeToXml(serializer, full);
        serializer.startTag(null, "filter");
        mFilter.writeToXml(serializer);
        serializer.endTag(null, "filter");
    }

    public boolean onReadTag(String tagName, TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        if (tagName.equals("filter")) {
            if (DEBUG_FILTERS) {
                Log.i(TAG, "Starting to parse filter...");
            }
            mFilter.readFromXml(parser);
            if (DEBUG_FILTERS) {
                Log.i(TAG, "Finished filter: depth=" + parser.getDepth() + " tag="
                        + parser.getName());
            }
        } else {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Unknown element under <preferred-activities>: " + parser.getName());
            XmlUtils.skipCurrentTag(parser);
        }
        return true;
    }

    public void dumpPref(PrintWriter out, String prefix, PreferredActivity filter) {
        mPref.dump(out, prefix, filter);
    }

    @Override
    public String toString() {
        return "PreferredActivity{0x" + Integer.toHexString(System.identityHashCode(this))
                + " " + mPref.mComponent.flattenToShortString() + "}";
    }

    public PreferredActivity snapshot() {
        return mSnapshot.snapshot();
    }
}
