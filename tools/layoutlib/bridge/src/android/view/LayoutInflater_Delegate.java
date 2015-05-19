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

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.content.Context;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.util.Xml;

import java.io.IOException;

/**
 * Delegate used to provide new implementation of a select few methods of {@link LayoutInflater}
 *
 * Through the layoutlib_create tool, the original  methods of LayoutInflater have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 */
public class LayoutInflater_Delegate {
    private static final String TAG_MERGE = "merge";

    private static final String ATTR_LAYOUT = "layout";

    private static final int[] ATTRS_THEME = new int[] {
            com.android.internal.R.attr.theme };

    public static boolean sIsInInclude = false;

    /**
     * Recursive method used to descend down the xml hierarchy and instantiate
     * views, instantiate their children, and then call onFinishInflate().
     *
     * This implementation just records the merge status before calling the default implementation.
     */
    @LayoutlibDelegate
    /* package */ static void rInflate(LayoutInflater thisInflater, XmlPullParser parser,
            View parent, Context context, AttributeSet attrs, boolean finishInflate)
            throws XmlPullParserException, IOException {

        if (finishInflate == false) {
            // this is a merge rInflate!
            if (thisInflater instanceof BridgeInflater) {
                ((BridgeInflater) thisInflater).setIsInMerge(true);
            }
        }

        // ---- START DEFAULT IMPLEMENTATION.

        thisInflater.rInflate_Original(parser, parent, context, attrs, finishInflate);

        // ---- END DEFAULT IMPLEMENTATION.

        if (finishInflate == false) {
            // this is a merge rInflate!
            if (thisInflater instanceof BridgeInflater) {
                ((BridgeInflater) thisInflater).setIsInMerge(false);
            }
        }
    }

    @LayoutlibDelegate
    public static void parseInclude(LayoutInflater thisInflater, XmlPullParser parser,
            Context context, View parent, AttributeSet attrs)
            throws XmlPullParserException, IOException {
        int type;

        if (parent instanceof ViewGroup) {
            // Apply a theme wrapper, if requested. This is sort of a weird
            // edge case, since developers think the <include> overwrites
            // values in the AttributeSet of the included View. So, if the
            // included View has a theme attribute, we'll need to ignore it.
            final TypedArray ta = context.obtainStyledAttributes(attrs, ATTRS_THEME);
            final int themeResId = ta.getResourceId(0, 0);
            final boolean hasThemeOverride = themeResId != 0;
            if (hasThemeOverride) {
                context = new ContextThemeWrapper(context, themeResId);
            }
            ta.recycle();

            // If the layout is pointing to a theme attribute, we have to
            // massage the value to get a resource identifier out of it.
            int layout = attrs.getAttributeResourceValue(null, ATTR_LAYOUT, 0);
            if (layout == 0) {
                final String value = attrs.getAttributeValue(null, ATTR_LAYOUT);
                if (value == null || value.length() <= 0) {
                    throw new InflateException("You must specify a layout in the"
                            + " include tag: <include layout=\"@layout/layoutID\" />");
                }

                // Attempt to resolve the "?attr/name" string to an identifier.
                layout = context.getResources().getIdentifier(value.substring(1), null, null);
            }

            // The layout might be referencing a theme attribute.
            // ---- START CHANGES
            if (layout != 0) {
                final TypedValue tempValue = new TypedValue();
                if (context.getTheme().resolveAttribute(layout, tempValue, true)) {
                    layout = tempValue.resourceId;
                }
            }
            // ---- END CHANGES

            if (layout == 0) {
                final String value = attrs.getAttributeValue(null, ATTR_LAYOUT);
                if (value == null) {
                    throw new InflateException("You must specifiy a layout in the"
                            + " include tag: <include layout=\"@layout/layoutID\" />");
                } else {
                    throw new InflateException("You must specifiy a valid layout "
                            + "reference. The layout ID " + value + " is not valid.");
                }
            } else {
                final XmlResourceParser childParser =
                    thisInflater.getContext().getResources().getLayout(layout);

                try {
                    final AttributeSet childAttrs = Xml.asAttributeSet(childParser);

                    while ((type = childParser.next()) != XmlPullParser.START_TAG &&
                            type != XmlPullParser.END_DOCUMENT) {
                        // Empty.
                    }

                    if (type != XmlPullParser.START_TAG) {
                        throw new InflateException(childParser.getPositionDescription() +
                                ": No start tag found!");
                    }

                    final String childName = childParser.getName();

                    if (TAG_MERGE.equals(childName)) {
                        // Inflate all children.
                        thisInflater.rInflate(childParser, parent, context, childAttrs, false);
                    } else {
                        final View view = thisInflater.createViewFromTag(parent, childName,
                                context, childAttrs, hasThemeOverride);
                        final ViewGroup group = (ViewGroup) parent;

                        final TypedArray a = context.obtainStyledAttributes(
                                attrs, com.android.internal.R.styleable.Include);
                        final int id = a.getResourceId(
                                com.android.internal.R.styleable.Include_id, View.NO_ID);
                        final int visibility = a.getInt(
                                com.android.internal.R.styleable.Include_visibility, -1);
                        a.recycle();

                        // We try to load the layout params set in the <include /> tag. If
                        // they don't exist, we will rely on the layout params set in the
                        // included XML file.
                        // During a layoutparams generation, a runtime exception is thrown
                        // if either layout_width or layout_height is missing. We catch
                        // this exception and set localParams accordingly: true means we
                        // successfully loaded layout params from the <include /> tag,
                        // false means we need to rely on the included layout params.
                        ViewGroup.LayoutParams params = null;
                        try {
                            // ---- START CHANGES
                            sIsInInclude = true;
                            // ---- END CHANGES

                            params = group.generateLayoutParams(attrs);
                        } catch (RuntimeException ignored) {
                            // Ignore, just fail over to child attrs.
                        } finally {
                            // ---- START CHANGES
                            sIsInInclude = false;
                            // ---- END CHANGES
                        }
                        if (params == null) {
                            params = group.generateLayoutParams(childAttrs);
                        }
                        view.setLayoutParams(params);

                        // Inflate all children.
                        thisInflater.rInflateChildren(childParser, view, childAttrs, true);

                        if (id != View.NO_ID) {
                            view.setId(id);
                        }

                        switch (visibility) {
                            case 0:
                                view.setVisibility(View.VISIBLE);
                                break;
                            case 1:
                                view.setVisibility(View.INVISIBLE);
                                break;
                            case 2:
                                view.setVisibility(View.GONE);
                                break;
                        }

                        group.addView(view);
                    }
                } finally {
                    childParser.close();
                }
            }
        } else {
            throw new InflateException("<include /> can only be used inside of a ViewGroup");
        }

        LayoutInflater.consumeChildElements(parser);
    }
}
