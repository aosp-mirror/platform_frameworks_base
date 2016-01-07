/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.settingslib;

import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.util.Xml;
import android.view.InflateException;
import com.android.settingslib.drawer.Tile;
import com.android.settingslib.drawer.TileUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SuggestionParser {

    private static final String TAG = "SuggestionParser";

    private final Context mContext;
    private final List<SuggestionCategory> mSuggestionList;
    private final ArrayMap<Pair<String, String>, Tile> addCache = new ArrayMap<>();

    public SuggestionParser(Context context, int orderXml) {
        mContext = context;
        mSuggestionList = (List<SuggestionCategory>) new SuggestionOrderInflater(mContext)
                .parse(orderXml);
    }

    public List<Tile> getSuggestions() {
        List<Tile> suggestions = new ArrayList<>();
        final int N = mSuggestionList.size();
        for (int i = 0; i < N; i++) {
            readSuggestions(mSuggestionList.get(i), suggestions);
        }
        return suggestions;
    }

    private void readSuggestions(SuggestionCategory category, List<Tile> suggestions) {
        int countBefore = suggestions.size();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(category.category);
        if (category.pkg != null) {
            intent.setPackage(category.pkg);
        }
        TileUtils.getTilesForIntent(mContext, new UserHandle(UserHandle.myUserId()), intent,
                addCache, null, suggestions, true, false);
        if (!category.multiple && suggestions.size() > (countBefore + 1)) {
            // If there are too many, remove them all and only re-add the one with the highest
            // priority.
            Tile item = suggestions.remove(suggestions.size() - 1);
            while (suggestions.size() > countBefore) {
                Tile last = suggestions.remove(suggestions.size() - 1);
                if (last.priority > item.priority) {
                    item = last;
                }
            }
            suggestions.add(item);
        }
    }

    private static class SuggestionCategory {
        public String category;
        public String pkg;
        public boolean multiple;
    }

    private static class SuggestionOrderInflater {
        private static final String TAG_LIST = "optional-steps";
        private static final String TAG_ITEM = "step";

        private static final String ATTR_CATEGORY = "category";
        private static final String ATTR_PACKAGE = "package";
        private static final String ATTR_MULTIPLE = "multiple";

        private final Context mContext;

        public SuggestionOrderInflater(Context context) {
            mContext = context;
        }

        public Object parse(int resource) {
            XmlPullParser parser = mContext.getResources().getXml(resource);
            final AttributeSet attrs = Xml.asAttributeSet(parser);
            try {
                // Look for the root node.
                int type;
                do {
                    type = parser.next();
                } while (type != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT);

                if (type != XmlPullParser.START_TAG) {
                    throw new InflateException(parser.getPositionDescription()
                            + ": No start tag found!");
                }

                // Temp is the root that was found in the xml
                Object xmlRoot = onCreateItem(parser.getName(), attrs);

                // Inflate all children under temp
                rParse(parser, xmlRoot, attrs);
                return xmlRoot;
            } catch (XmlPullParserException | IOException e) {
                Log.w(TAG, "Problem parser resource " + resource, e);
                return null;
            }
        }

        /**
         * Recursive method used to descend down the xml hierarchy and instantiate
         * items, instantiate their children.
         */
        private void rParse(XmlPullParser parser, Object parent, final AttributeSet attrs)
                throws XmlPullParserException, IOException {
            final int depth = parser.getDepth();

            int type;
            while (((type = parser.next()) != XmlPullParser.END_TAG ||
                    parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }

                final String name = parser.getName();

                Object item = onCreateItem(name, attrs);
                onAddChildItem(parent, item);
                rParse(parser, item, attrs);
            }
        }

        protected void onAddChildItem(Object parent, Object child) {
            if (parent instanceof List<?> && child instanceof SuggestionCategory) {
                ((List<SuggestionCategory>) parent).add((SuggestionCategory) child);
            } else {
                throw new IllegalArgumentException("Parent was not a list");
            }
        }

        protected Object onCreateItem(String name, AttributeSet attrs) {
            if (name.equals(TAG_LIST)) {
                return new ArrayList<SuggestionCategory>();
            } else if (name.equals(TAG_ITEM)) {
                SuggestionCategory category = new SuggestionCategory();
                category.category = attrs.getAttributeValue(null, ATTR_CATEGORY);
                category.pkg = attrs.getAttributeValue(null, ATTR_PACKAGE);
                String multiple = attrs.getAttributeValue(null, ATTR_MULTIPLE);
                category.multiple = !TextUtils.isEmpty(multiple) && Boolean.parseBoolean(multiple);
                return category;
            } else {
                throw new IllegalArgumentException("Unknown item " + name);
            }
        }
    }
}

