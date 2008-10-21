/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.layoutlib.bridge;

import com.android.layoutlib.api.ILayoutResult;
import com.android.layoutlib.api.IResourceValue;
import com.android.layoutlib.api.IStyleResourceValue;
import com.android.layoutlib.api.IXmlPullParser;
import com.android.layoutlib.api.ILayoutResult.ILayoutViewInfo;

import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileReader;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import junit.framework.TestCase;

public class BridgeTest extends TestCase {

    /** the class being tested */
    private Bridge mBridge;
    /** the path to the sample layout.xml file */
    private String mLayoutXml1Path;
    private String mTextOnlyXmlPath;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mBridge = new Bridge();
        
        // FIXME: need some fonts somewhere.
        mBridge.init(null /* fontOsLocation */, getAttributeValues());
        
        URL url = this.getClass().getClassLoader().getResource("data/layout1.xml");
        mLayoutXml1Path = url.getFile();

        url = this.getClass().getClassLoader().getResource("data/textonly.xml");
        mTextOnlyXmlPath = url.getFile();
    }
    
    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    // ---------------

    /**
     * Test parser that implements {@link IXmlPullParser}.
     */
    private static class TestParser extends KXmlParser implements IXmlPullParser {
        public Object getViewKey() {
            return null;
        }
    }

    public void testComputeLayout() throws Exception {
        
        TestParser parser = new TestParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(new FileReader(new File(mLayoutXml1Path)));

        Map<String, Map<String, IResourceValue>> projectResources = getProjectResources();

        Map<String, Map<String, IResourceValue>> frameworkResources = getFrameworkResources();
        
        int screenWidth = 320;
        int screenHeight = 480;
        
        // FIXME need a dummy font for the tests!
        ILayoutResult result = mBridge.computeLayout(parser, new Integer(1) /* projectKey */, 
                screenWidth, screenHeight,
                "Theme", projectResources, frameworkResources, null, null);
                
        display(result.getRootView(), "");
    }

    private Map<String, Map<String, Integer>> getAttributeValues() {
        Map<String, Map<String, Integer>> attributeValues =
            new HashMap<String, Map<String,Integer>>();
        
        // lets create a map for the orientation attribute
        Map<String, Integer> attributeMap = new HashMap<String, Integer>();
        
        attributeMap.put("horizontal", Integer.valueOf(0));
        attributeMap.put("vertical", Integer.valueOf(1));
        
        attributeValues.put("orientation", attributeMap);
        
        return attributeValues;
    }

    private Map<String, Map<String, IResourceValue>> getFrameworkResources() {
        Map<String, Map<String, IResourceValue>> frameworkResources =
            new HashMap<String, Map<String, IResourceValue>>();
        
        // create the style map
        Map<String, IResourceValue> styleMap = new HashMap<String, IResourceValue>();
        frameworkResources.put("style", styleMap);
        
        // create a button style.
        IStyleResourceValue style = createStyle("Widget.Button",
                "background",        "@android:drawable/something",
                "focusable",        "true",
                "clickable",        "true",
                "textAppearance",   "?android:attr/textAppearanceSmallInverse",
                "textColor",        "?android:attr/textColorBrightInverseNoDisable",
                "gravity",          "center_vertical|center_horizontal"
                );
        styleMap.put(style.getName(), style);

        // create the parent style of button style
        style = createStyle("Widget",
                "textAppearance", "?textAppearance");
        styleMap.put(style.getName(), style);

        // link the buttonStyle info in the default theme.
        style = createStyle("Theme",
                BridgeConstants.RES_STYLE, "buttonStyle",                      "@android:style/Widget.Button",
                BridgeConstants.RES_STYLE, "textAppearance",                   "@android:style/TextAppearance",
                BridgeConstants.RES_STYLE, "textAppearanceSmallInverse",       "@android:style/TextAppearance.Small.Inverse",
                BridgeConstants.RES_COLOR, "textColorBrightInverseNoDisable",  "@android:color/bright_text_light_nodisable"
                );
        styleMap.put(style.getName(), style);

        // create a dummy drawable to go with it
        Map<String, IResourceValue> drawableMap = new HashMap<String, IResourceValue>();
        frameworkResources.put("drawable", drawableMap);
        
        // get the 9 patch test location
        URL url = this.getClass().getClassLoader().getResource("data/button.9.png");

        IResourceValue drawable = new ResourceValue(BridgeConstants.RES_DRAWABLE, "something",
                url.getPath());
        drawableMap.put(drawable.getName(), drawable);
        return frameworkResources;
    }
    
    private Map<String, Map<String, IResourceValue>> getProjectResources() {
        Map<String, Map<String, IResourceValue>> projectResources =
            new HashMap<String, Map<String, IResourceValue>>();

        // create the style map (even empty there should be one)
        Map<String, IResourceValue> styleMap = new HashMap<String, IResourceValue>();
        projectResources.put("style", styleMap);

        return projectResources;
    }


    private void display(ILayoutViewInfo result, String offset) {

        String msg = String.format("%s%s L:%d T:%d R:%d B:%d",
                offset,
                result.getName(),
                result.getLeft(), result.getTop(), result.getRight(), result.getBottom());

        System.out.println(msg);
        ILayoutViewInfo[] children = result.getChildren();
        if (children != null) {
            offset += "+-";
            for (ILayoutViewInfo child : children) {
                display(child, offset);
            }
        }
    }
    
    /**
     * Creates a {@link IStyleResourceValue} based on the given values.
     * @param styleName the name of the style.
     * @param items An array of Strings. Even indices contain a style item name, and odd indices
     * a style item value. If the number of string in the array is not even, an exception is thrown.
     */
    private IStyleResourceValue createStyle(String styleName, String... items) {
        StyleResourceValue value = new StyleResourceValue(styleName);
        
        if (items.length % 3 == 0) {
            for (int i = 0 ; i < items.length;) {
                value.addItem(new ResourceValue(items[i++], items[i++], items[i++]));
            }
        } else {
            throw new IllegalArgumentException("Need a multiple of 3 for the number of strings");
        }
        
        return value;
    }

    // ---------------

    public void testTextLayout() throws Exception {
        
        TestParser parser = new TestParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(new FileReader(new File(mTextOnlyXmlPath)));

        Map<String, Map<String, IResourceValue>> projectResources = getProjectResources();
        Map<String, Map<String, IResourceValue>> frameworkResources = getFrameworkResources();
        
        int screenWidth = 320;
        int screenHeight = 480;

        // FIXME need a dummy font for the tests!
        ILayoutResult result = mBridge.computeLayout(parser, new Integer(1) /* projectKey */,
                screenWidth, screenHeight,
                "Theme", projectResources, frameworkResources, null, null);
                
        display(result.getRootView(), "");
    }

}
