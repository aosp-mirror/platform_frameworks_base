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

package android.util;

import com.android.ide.common.rendering.api.RenderResources;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.BridgeConstants;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.resources.ResourceType;

import org.xmlpull.v1.XmlPullParser;

import android.util.AttributeSet;
import android.util.XmlPullAttributes;

/**
 * A correct implementation of the {@link AttributeSet} interface on top of a XmlPullParser
 */
public class BridgeXmlPullAttributes extends XmlPullAttributes {

    private final BridgeContext mContext;
    private final boolean mPlatformFile;

    public BridgeXmlPullAttributes(XmlPullParser parser, BridgeContext context,
            boolean platformFile) {
        super(parser);
        mContext = context;
        mPlatformFile = platformFile;
    }

    /*
     * (non-Javadoc)
     * @see android.util.XmlPullAttributes#getAttributeNameResource(int)
     *
     * This methods must return com.android.internal.R.attr.<name> matching
     * the name of the attribute.
     * It returns 0 if it doesn't find anything.
     */
    @Override
    public int getAttributeNameResource(int index) {
        // get the attribute name.
        String name = getAttributeName(index);

        // get the attribute namespace
        String ns = mParser.getAttributeNamespace(index);

        if (BridgeConstants.NS_RESOURCES.equals(ns)) {
            Integer v = Bridge.getResourceId(ResourceType.ATTR, name);
            if (v != null) {
                return v.intValue();
            }

            return 0;
        }

        // this is not an attribute in the android namespace, we query the customviewloader, if
        // the namespaces match.
        if (mContext.getProjectCallback().getNamespace().equals(ns)) {
            Integer v = mContext.getProjectCallback().getResourceId(ResourceType.ATTR, name);
            if (v != null) {
                return v.intValue();
            }
        }

        return 0;
    }

    /*
     * (non-Javadoc)
     * @see android.util.XmlPullAttributes#getAttributeResourceValue(int, int)
     */
    @Override
    public int getAttributeResourceValue(int index, int defaultValue) {
        String value = getAttributeValue(index);

        return resolveResourceValue(value, defaultValue);
    }

    /*
     * (non-Javadoc)
     * @see android.util.XmlPullAttributes#getAttributeResourceValue(java.lang.String, java.lang.String, int)
     */
    @Override
    public int getAttributeResourceValue(String namespace, String attribute, int defaultValue) {
        String value = getAttributeValue(namespace, attribute);

        return resolveResourceValue(value, defaultValue);
    }

    private int resolveResourceValue(String value, int defaultValue) {
        // now look for this particular value
        RenderResources resources = mContext.getRenderResources();
        ResourceValue resource = resources.resolveResValue(
                resources.findResValue(value, mPlatformFile));

        if (resource != null) {
            Integer id = null;
            if (mPlatformFile || resource.isFramework()) {
                id = Bridge.getResourceId(resource.getResourceType(), resource.getName());
            } else {
                id = mContext.getProjectCallback().getResourceId(
                        resource.getResourceType(), resource.getName());
            }

            if (id != null) {
                return id;
            }
        }

        return defaultValue;
    }

}
