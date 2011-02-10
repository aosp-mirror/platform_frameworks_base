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

package android.view;

import com.android.layoutlib.bridge.android.BridgeInflater;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.util.AttributeSet;

import java.io.IOException;

/**
 * Delegate used to provide new implementation of a select few methods of {@link LayoutInflater}
 *
 * Through the layoutlib_create tool, the original  methods of LayoutInflater have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 */
public class LayoutInflater_Delegate {

    /**
     * Recursive method used to descend down the xml hierarchy and instantiate
     * views, instantiate their children, and then call onFinishInflate().
     */
    @LayoutlibDelegate
    /*package*/ static void rInflate(LayoutInflater thisInflater,
            XmlPullParser parser, View parent, final AttributeSet attrs,
            boolean finishInflate) throws XmlPullParserException, IOException {

        if (finishInflate == false) {
            // this is a merge rInflate!
            if (thisInflater instanceof BridgeInflater) {
                ((BridgeInflater) thisInflater).setIsInMerge(true);
            }
        }

        // ---- START DEFAULT IMPLEMENTATION.

        final int depth = parser.getDepth();
        int type;

        while (((type = parser.next()) != XmlPullParser.END_TAG ||
                parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {

            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            final String name = parser.getName();

            if (LayoutInflater.TAG_REQUEST_FOCUS.equals(name)) {
                thisInflater.parseRequestFocus(parser, parent);
            } else if (LayoutInflater.TAG_INCLUDE.equals(name)) {
                if (parser.getDepth() == 0) {
                    throw new InflateException("<include /> cannot be the root element");
                }
                thisInflater.parseInclude(parser, parent, attrs);
            } else if (LayoutInflater.TAG_MERGE.equals(name)) {
                throw new InflateException("<merge /> must be the root element");
            } else {
                final View view = thisInflater.createViewFromTag(parent, name, attrs);
                final ViewGroup viewGroup = (ViewGroup) parent;
                final ViewGroup.LayoutParams params = viewGroup.generateLayoutParams(attrs);
                thisInflater.rInflate(parser, view, attrs, true);
                viewGroup.addView(view, params);
            }
        }

        if (finishInflate) parent.onFinishInflate();

        // ---- END DEFAULT IMPLEMENTATION.

        if (finishInflate == false) {
            // this is a merge rInflate!
            if (thisInflater instanceof BridgeInflater) {
                ((BridgeInflater) thisInflater).setIsInMerge(false);
            }
        }
    }
}
