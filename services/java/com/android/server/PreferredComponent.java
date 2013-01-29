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

package com.android.server;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.content.ComponentName;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.util.Slog;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

public class PreferredComponent {
    public final int mMatch;
    public final ComponentName mComponent;

    private final String[] mSetPackages;
    private final String[] mSetClasses;
    private final String[] mSetComponents;
    private final String mShortComponent;
    private String mParseError;

    private final Callbacks mCallbacks;

    public interface Callbacks {
        public boolean onReadTag(String tagName, XmlPullParser parser)
                throws XmlPullParserException, IOException;
    }

    public PreferredComponent(Callbacks callbacks, int match, ComponentName[] set,
            ComponentName component) {
        mCallbacks = callbacks;
        mMatch = match&IntentFilter.MATCH_CATEGORY_MASK;
        mComponent = component;
        mShortComponent = component.flattenToShortString();
        mParseError = null;
        if (set != null) {
            final int N = set.length;
            String[] myPackages = new String[N];
            String[] myClasses = new String[N];
            String[] myComponents = new String[N];
            for (int i=0; i<N; i++) {
                ComponentName cn = set[i];
                if (cn == null) {
                    mSetPackages = null;
                    mSetClasses = null;
                    mSetComponents = null;
                    return;
                }
                myPackages[i] = cn.getPackageName().intern();
                myClasses[i] = cn.getClassName().intern();
                myComponents[i] = cn.flattenToShortString().intern();
            }
            mSetPackages = myPackages;
            mSetClasses = myClasses;
            mSetComponents = myComponents;
        } else {
            mSetPackages = null;
            mSetClasses = null;
            mSetComponents = null;
        }
    }

    public PreferredComponent(Callbacks callbacks, XmlPullParser parser)
            throws XmlPullParserException, IOException {
        mCallbacks = callbacks;
        mShortComponent = parser.getAttributeValue(null, "name");
        mComponent = ComponentName.unflattenFromString(mShortComponent);
        if (mComponent == null) {
            mParseError = "Bad activity name " + mShortComponent;
        }
        String matchStr = parser.getAttributeValue(null, "match");
        mMatch = matchStr != null ? Integer.parseInt(matchStr, 16) : 0;
        String setCountStr = parser.getAttributeValue(null, "set");
        int setCount = setCountStr != null ? Integer.parseInt(setCountStr) : 0;

        String[] myPackages = setCount > 0 ? new String[setCount] : null;
        String[] myClasses = setCount > 0 ? new String[setCount] : null;
        String[] myComponents = setCount > 0 ? new String[setCount] : null;

        int setPos = 0;

        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG
                       || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG
                    || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            //Log.i(TAG, "Parse outerDepth=" + outerDepth + " depth="
            //        + parser.getDepth() + " tag=" + tagName);
            if (tagName.equals("set")) {
                String name = parser.getAttributeValue(null, "name");
                if (name == null) {
                    if (mParseError == null) {
                        mParseError = "No name in set tag in preferred activity "
                            + mShortComponent;
                    }
                } else if (setPos >= setCount) {
                    if (mParseError == null) {
                        mParseError = "Too many set tags in preferred activity "
                            + mShortComponent;
                    }
                } else {
                    ComponentName cn = ComponentName.unflattenFromString(name);
                    if (cn == null) {
                        if (mParseError == null) {
                            mParseError = "Bad set name " + name + " in preferred activity "
                                + mShortComponent;
                        }
                    } else {
                        myPackages[setPos] = cn.getPackageName();
                        myClasses[setPos] = cn.getClassName();
                        myComponents[setPos] = name;
                        setPos++;
                    }
                }
                XmlUtils.skipCurrentTag(parser);
            } else if (!mCallbacks.onReadTag(tagName, parser)) {
                Slog.w("PreferredComponent", "Unknown element: " + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }

        if (setPos != setCount) {
            if (mParseError == null) {
                mParseError = "Not enough set tags (expected " + setCount
                    + " but found " + setPos + ") in " + mShortComponent;
            }
        }

        mSetPackages = myPackages;
        mSetClasses = myClasses;
        mSetComponents = myComponents;
    }

    public String getParseError() {
        return mParseError;
    }

    public void writeToXml(XmlSerializer serializer, boolean full) throws IOException {
        final int NS = mSetClasses != null ? mSetClasses.length : 0;
        serializer.attribute(null, "name", mShortComponent);
        if (full) {
            if (mMatch != 0) {
                serializer.attribute(null, "match", Integer.toHexString(mMatch));
            }
            serializer.attribute(null, "set", Integer.toString(NS));
            for (int s=0; s<NS; s++) {
                serializer.startTag(null, "set");
                serializer.attribute(null, "name", mSetComponents[s]);
                serializer.endTag(null, "set");
            }
        }
    }

    public boolean sameSet(List<ResolveInfo> query, int priority) {
        if (mSetPackages == null) return false;
        final int NQ = query.size();
        final int NS = mSetPackages.length;
        int numMatch = 0;
        for (int i=0; i<NQ; i++) {
            ResolveInfo ri = query.get(i);
            if (ri.priority != priority) continue;
            ActivityInfo ai = ri.activityInfo;
            boolean good = false;
            for (int j=0; j<NS; j++) {
                if (mSetPackages[j].equals(ai.packageName)
                        && mSetClasses[j].equals(ai.name)) {
                    numMatch++;
                    good = true;
                    break;
                }
            }
            if (!good) return false;
        }
        return numMatch == NS;
    }

    public void dump(PrintWriter out, String prefix, Object ident) {
        out.print(prefix); out.print(
                Integer.toHexString(System.identityHashCode(ident)));
                out.print(' ');
                out.print(mComponent.flattenToShortString());
                out.print(" match=0x");
                out.println( Integer.toHexString(mMatch));
        if (mSetComponents != null) {
            out.print(prefix); out.println("  Selected from:");
            for (int i=0; i<mSetComponents.length; i++) {
                out.print(prefix); out.print("    ");
                        out.println(mSetComponents[i]);
            }
        }
    }
}
