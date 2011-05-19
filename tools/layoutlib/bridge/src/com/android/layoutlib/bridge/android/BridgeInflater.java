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

package com.android.layoutlib.bridge.android;

import com.android.ide.common.rendering.api.IProjectCallback;
import com.android.ide.common.rendering.api.LayoutLog;
import com.android.ide.common.rendering.api.MergeCookie;
import com.android.ide.common.rendering.api.ResourceReference;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.layoutlib.bridge.Bridge;
import com.android.resources.ResourceType;
import com.android.util.Pair;

import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;

import android.content.Context;
import android.util.AttributeSet;
import android.view.InflateException;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;
import java.io.FileInputStream;

/**
 * Custom implementation of {@link LayoutInflater} to handle custom views.
 */
public final class BridgeInflater extends LayoutInflater {

    private final IProjectCallback mProjectCallback;
    private boolean mIsInMerge = false;
    private ResourceReference mResourceReference;

    /**
     * List of class prefixes which are tried first by default.
     * <p/>
     * This should match the list in com.android.internal.policy.impl.PhoneLayoutInflater.
     */
    private static final String[] sClassPrefixList = {
        "android.widget.",
        "android.webkit."
    };

    protected BridgeInflater(LayoutInflater original, Context newContext) {
        super(original, newContext);
        mProjectCallback = null;
    }

    /**
     * Instantiate a new BridgeInflater with an {@link IProjectCallback} object.
     *
     * @param context The Android application context.
     * @param projectCallback the {@link IProjectCallback} object.
     */
    public BridgeInflater(Context context, IProjectCallback projectCallback) {
        super(context);
        mProjectCallback = projectCallback;
        mConstructorArgs[0] = context;
    }

    @Override
    public View onCreateView(String name, AttributeSet attrs) throws ClassNotFoundException {
        View view = null;

        try {
            // First try to find a class using the default Android prefixes
            for (String prefix : sClassPrefixList) {
                try {
                    view = createView(name, prefix, attrs);
                    if (view != null) {
                        break;
                    }
                } catch (ClassNotFoundException e) {
                    // Ignore. We'll try again using the base class below.
                }
            }

            // Next try using the parent loader. This will most likely only work for
            // fully-qualified class names.
            try {
                if (view == null) {
                    view = super.onCreateView(name, attrs);
                }
            } catch (ClassNotFoundException e) {
                // Ignore. We'll try again using the custom view loader below.
            }

            // Finally try again using the custom view loader
            try {
                if (view == null) {
                    view = loadCustomView(name, attrs);
                }
            } catch (ClassNotFoundException e) {
                // If the class was not found, we throw the exception directly, because this
                // method is already expected to throw it.
                throw e;
            }
        } catch (Exception e) {
            // Wrap the real exception in a ClassNotFoundException, so that the calling method
            // can deal with it.
            ClassNotFoundException exception = new ClassNotFoundException("onCreateView", e);
            throw exception;
        }

        setupViewInContext(view, attrs);

        return view;
    }

    @Override
    public View createViewFromTag(String name, AttributeSet attrs) {
        View view = null;
        try {
            view = super.createViewFromTag(name, attrs);
        } catch (InflateException e) {
            // try to load the class from using the custom view loader
            try {
                view = loadCustomView(name, attrs);
            } catch (Exception e2) {
                // Wrap the real exception in an InflateException so that the calling
                // method can deal with it.
                InflateException exception = new InflateException();
                if (e2.getClass().equals(ClassNotFoundException.class) == false) {
                    exception.initCause(e2);
                } else {
                    exception.initCause(e);
                }
                throw exception;
            }
        }

        setupViewInContext(view, attrs);

        return view;
    }

    @Override
    public View inflate(int resource, ViewGroup root) {
        Context context = getContext();
        if (context instanceof BridgeContext) {
            BridgeContext bridgeContext = (BridgeContext)context;

            ResourceValue value = null;

            Pair<ResourceType, String> layoutInfo = Bridge.resolveResourceId(resource);
            if (layoutInfo != null) {
                value = bridgeContext.getRenderResources().getFrameworkResource(
                        ResourceType.LAYOUT, layoutInfo.getSecond());
            } else {
                layoutInfo = mProjectCallback.resolveResourceId(resource);

                if (layoutInfo != null) {
                    value = bridgeContext.getRenderResources().getProjectResource(
                            ResourceType.LAYOUT, layoutInfo.getSecond());
                }
            }

            if (value != null) {
                File f = new File(value.getValue());
                if (f.isFile()) {
                    try {
                        KXmlParser parser = new KXmlParser();
                        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
                        parser.setInput(new FileInputStream(f), "UTF-8"); //$NON-NLS-1$

                        BridgeXmlBlockParser bridgeParser = new BridgeXmlBlockParser(
                                parser, bridgeContext, false);

                        return inflate(bridgeParser, root);
                    } catch (Exception e) {
                        Bridge.getLog().error(LayoutLog.TAG_RESOURCES_READ,
                                "Failed to parse file " + f.getAbsolutePath(), e, null /*data*/);

                        return null;
                    }
                }
            }
        }
        return null;
    }

    private View loadCustomView(String name, AttributeSet attrs) throws ClassNotFoundException,
            Exception{
        if (mProjectCallback != null) {
            // first get the classname in case it's not the node name
            if (name.equals("view")) {
                name = attrs.getAttributeValue(null, "class");
            }

            mConstructorArgs[1] = attrs;

            Object customView = mProjectCallback.loadView(name, mConstructorSignature,
                    mConstructorArgs);

            if (customView instanceof View) {
                return (View)customView;
            }
        }

        return null;
    }

    private void setupViewInContext(View view, AttributeSet attrs) {
        if (getContext() instanceof BridgeContext) {
            BridgeContext bc = (BridgeContext) getContext();
            if (attrs instanceof BridgeXmlBlockParser) {
                BridgeXmlBlockParser parser = (BridgeXmlBlockParser) attrs;

                // get the view key
                Object viewKey = parser.getViewCookie();

                if (viewKey == null) {
                    int currentDepth = parser.getDepth();

                    // test whether we are in an included file or in a adapter binding view.
                    BridgeXmlBlockParser previousParser = bc.getPreviousParser();
                    if (previousParser != null) {
                        // looks like we inside an embedded layout.
                        // only apply the cookie of the calling node (<include>) if we are at the
                        // top level of the embedded layout. If there is a merge tag, then
                        // skip it and look for the 2nd level
                        int testDepth = mIsInMerge ? 2 : 1;
                        if (currentDepth == testDepth) {
                            viewKey = previousParser.getViewCookie();
                            // if we are in a merge, wrap the cookie in a MergeCookie.
                            if (viewKey != null && mIsInMerge) {
                                viewKey = new MergeCookie(viewKey);
                            }
                        }
                    } else if (mResourceReference != null && currentDepth == 1) {
                        // else if there's a resource reference, this means we are in an adapter
                        // binding case. Set the resource ref as the view cookie only for the top
                        // level view.
                        viewKey = mResourceReference;
                    }
                }

                if (viewKey != null) {
                    bc.addViewKey(view, viewKey);
                }
            }
        }
    }

    public void setIsInMerge(boolean isInMerge) {
        mIsInMerge = isInMerge;
    }

    public void setResourceReference(ResourceReference reference) {
        mResourceReference = reference;
    }

    @Override
    public LayoutInflater cloneInContext(Context newContext) {
        return new BridgeInflater(this, newContext);
    }
}
