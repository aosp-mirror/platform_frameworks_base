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

package com.android.frameworkperf;

import java.io.IOException;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.content.Context;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
import android.util.Xml;
import android.view.InflateException;

public class SimpleInflater {
    /** Menu tag name in XML. */
    private static final String XML_MENU = "menu";
    
    /** Group tag name in XML. */
    private static final String XML_GROUP = "group";
    
    /** Item tag name in XML. */
    private static final String XML_ITEM = "item";

    private Context mContext;

    public SimpleInflater(Context context) {
        mContext = context;
    }

    public void inflate(int menuRes) {
        XmlResourceParser parser = null;
        try {
            parser = mContext.getResources().getLayout(menuRes);
            AttributeSet attrs = Xml.asAttributeSet(parser);
            
            parseMenu(parser, attrs);
        } catch (XmlPullParserException e) {
            throw new InflateException("Error inflating menu XML", e);
        } catch (IOException e) {
            throw new InflateException("Error inflating menu XML", e);
        } finally {
            if (parser != null) parser.close();
        }
    }

    private void parseMenu(XmlPullParser parser, AttributeSet attrs)
            throws XmlPullParserException, IOException {
        int eventType = parser.getEventType();
        String tagName;
        boolean lookingForEndOfUnknownTag = false;
        String unknownTagName = null;

        // This loop will skip to the menu start tag
        do {
            if (eventType == XmlPullParser.START_TAG) {
                tagName = parser.getName();
                if (tagName.equals(XML_MENU)) {
                    // Go to next tag
                    eventType = parser.next();
                    break;
                }
                
                throw new RuntimeException("Expecting menu, got " + tagName);
            }
            eventType = parser.next();
        } while (eventType != XmlPullParser.END_DOCUMENT);
        
        boolean reachedEndOfMenu = false;
        while (!reachedEndOfMenu) {
            switch (eventType) {
                case XmlPullParser.START_TAG:
                    if (lookingForEndOfUnknownTag) {
                        break;
                    }
                    
                    tagName = parser.getName();
                    if (tagName.equals(XML_ITEM)) {
                        readItem(attrs);
                    } else if (tagName.equals(XML_MENU)) {
                        parseMenu(parser, attrs);
                    } else {
                        lookingForEndOfUnknownTag = true;
                        unknownTagName = tagName;
                    }
                    break;
                    
                case XmlPullParser.END_TAG:
                    tagName = parser.getName();
                    if (lookingForEndOfUnknownTag && tagName.equals(unknownTagName)) {
                        lookingForEndOfUnknownTag = false;
                        unknownTagName = null;
                    } else if (tagName.equals(XML_ITEM)) {
                    } else if (tagName.equals(XML_MENU)) {
                        reachedEndOfMenu = true;
                    }
                    break;
                    
                case XmlPullParser.END_DOCUMENT:
                    throw new RuntimeException("Unexpected end of document");
            }
            
            eventType = parser.next();
        }
    }

    public void readItem(AttributeSet attrs) {
        TypedArray a = mContext.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.MenuItem);

        // Inherit attributes from the group as default value
        int itemId = a.getResourceId(R.styleable.MenuItem_android_id, 0);
        final int category = a.getInt(R.styleable.MenuItem_android_menuCategory, 0);
        final int order = a.getInt(R.styleable.MenuItem_android_orderInCategory, 0);
        CharSequence itemTitle = a.getText(R.styleable.MenuItem_android_title);
        CharSequence itemTitleCondensed = a.getText(R.styleable.MenuItem_android_titleCondensed);
        int itemIconResId = a.getResourceId(R.styleable.MenuItem_android_icon, 0);
        String itemAlphabeticShortcut = a.getString(R.styleable.MenuItem_android_alphabeticShortcut);
        String itemNumericShortcut = a.getString(R.styleable.MenuItem_android_numericShortcut);
        int itemCheckable = 0;
        if (a.hasValue(R.styleable.MenuItem_android_checkable)) {
            // Item has attribute checkable, use it
            itemCheckable = a.getBoolean(R.styleable.MenuItem_android_checkable, false) ? 1 : 0;
        }
        boolean itemChecked = a.getBoolean(R.styleable.MenuItem_android_checked, false);
        boolean itemVisible = a.getBoolean(R.styleable.MenuItem_android_visible, false);
        boolean itemEnabled = a.getBoolean(R.styleable.MenuItem_android_enabled, false);

        a.recycle();
    }
}
