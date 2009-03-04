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

import com.android.layoutlib.api.IProjectCallback;
import com.android.layoutlib.api.IResourceValue;

import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.ViewGroup.LayoutParams;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.InputStream;

/**
 * 
 */
public final class BridgeResources extends Resources {

    private BridgeContext mContext;
    private IProjectCallback mProjectCallback;
    private boolean[] mPlatformResourceFlag = new boolean[1];
    
    /**
     * This initializes the static field {@link Resources#mSystem} which is used
     * by methods who get global resources using {@link Resources#getSystem()}.
     * <p/>
     * They will end up using our bridge resources.
     * <p/>
     * {@link Bridge} calls this method after setting up a new bridge.
     */
    /*package*/ static Resources initSystem(BridgeContext context, 
            AssetManager assets,
            DisplayMetrics metrics,
            Configuration config,
            IProjectCallback projectCallback) {
        if (!(Resources.mSystem instanceof BridgeResources)) {
            Resources.mSystem = new BridgeResources(context,
                    assets,
                    metrics,
                    config,
                    projectCallback);
        }
        return Resources.mSystem;
    }
    
    /**
     * Clears the static {@link Resources#mSystem} to make sure we don't leave objects
     * around that would prevent us from unloading the library.
     */
    /*package*/ static void clearSystem() {
        if (Resources.mSystem instanceof BridgeResources) {
            ((BridgeResources)(Resources.mSystem)).mContext = null;
            ((BridgeResources)(Resources.mSystem)).mProjectCallback = null;
        }
        Resources.mSystem = null;
    }

    private BridgeResources(BridgeContext context, AssetManager assets, DisplayMetrics metrics,
            Configuration config, IProjectCallback projectCallback) {
        super(assets, metrics, config);
        mContext = context;
        mProjectCallback = projectCallback;
    }
    
    public BridgeTypedArray newTypeArray(int numEntries, boolean platformFile) {
        return new BridgeTypedArray(this, mContext, numEntries, platformFile);
    }
    
    private IResourceValue getResourceValue(int id, boolean[] platformResFlag_out) {
        // first get the String related to this id in the framework
        String[] resourceInfo = Bridge.resolveResourceValue(id);
        
        if (resourceInfo != null) {
            platformResFlag_out[0] = true;
            return mContext.getFrameworkResource(resourceInfo[1], resourceInfo[0]);
        }

        // didn't find a match in the framework? look in the project.
        if (mProjectCallback != null) {
            resourceInfo = mProjectCallback.resolveResourceValue(id);
            
            if (resourceInfo != null) {
                platformResFlag_out[0] = false;
                return mContext.getProjectResource(resourceInfo[1], resourceInfo[0]);
            }
        }

        return null;
    }
    
    @Override
    public Drawable getDrawable(int id) throws NotFoundException {
        IResourceValue value = getResourceValue(id, mPlatformResourceFlag);
        
        if (value != null) {
            return ResourceHelper.getDrawable(value.getValue(), mContext, value.isFramework());
        }
        
        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);
        
        // this is not used since the method above always throws
        return null;
    }
    
    @Override
    public int getColor(int id) throws NotFoundException {
        IResourceValue value = getResourceValue(id, mPlatformResourceFlag);
        
        if (value != null) {
            try {
                return ResourceHelper.getColor(value.getValue());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        
        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);
        
        // this is not used since the method above always throws
        return 0;
    }
    
    @Override
    public ColorStateList getColorStateList(int id) throws NotFoundException {
        IResourceValue value = getResourceValue(id, mPlatformResourceFlag);
        
        if (value != null) {
            try {
                int color = ResourceHelper.getColor(value.getValue());
                return ColorStateList.valueOf(color);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        
        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);
        
        // this is not used since the method above always throws
        return null;
    }
    
    @Override
    public CharSequence getText(int id) throws NotFoundException {
        IResourceValue value = getResourceValue(id, mPlatformResourceFlag);
        
        if (value != null) {
            return value.getValue();
        }
        
        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);
        
        // this is not used since the method above always throws
        return null;
    }
    
    @Override
    public XmlResourceParser getLayout(int id) throws NotFoundException {
        IResourceValue value = getResourceValue(id, mPlatformResourceFlag);
        
        if (value != null) {
            File xml = new File(value.getValue());
            if (xml.isFile()) {
                // we need to create a pull parser around the layout XML file, and then
                // give that to our XmlBlockParser
                try {
                    KXmlParser parser = new KXmlParser();
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
                    parser.setInput(new FileReader(xml));
                    
                    return new BridgeXmlBlockParser(parser, mContext, mPlatformResourceFlag[0]);
                } catch (XmlPullParserException e) {
                    mContext.getLogger().error(e);

                    // we'll return null below.
                } catch (FileNotFoundException e) {
                    // this shouldn't happen since we check above.
                }
            }
        }

        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);
        
        // this is not used since the method above always throws
        return null;
    }
    
    @Override
    public TypedArray obtainAttributes(AttributeSet set, int[] attrs) {
        return mContext.obtainStyledAttributes(set, attrs);
    }
    
    @Override
    public TypedArray obtainTypedArray(int id) throws NotFoundException {
        throw new UnsupportedOperationException();
    }
    
    
    @Override
    public float getDimension(int id) throws NotFoundException {
        IResourceValue value = getResourceValue(id, mPlatformResourceFlag);

        if (value != null) {
            String v = value.getValue();

            if (v != null) {
                if (v.equals(BridgeConstants.FILL_PARENT)) {
                    return LayoutParams.FILL_PARENT;
                } else if (v.equals(BridgeConstants.WRAP_CONTENT)) {
                    return LayoutParams.WRAP_CONTENT;
                }
            
                if (ResourceHelper.stringToFloat(v, mTmpValue) &&
                        mTmpValue.type == TypedValue.TYPE_DIMENSION) {
                    return mTmpValue.getDimension(mMetrics);
                }
            }
        }

        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);
        
        // this is not used since the method above always throws
        return 0;
    }

    @Override
    public int getDimensionPixelOffset(int id) throws NotFoundException {
        IResourceValue value = getResourceValue(id, mPlatformResourceFlag);

        if (value != null) {
            String v = value.getValue();

            if (v != null) {
                if (ResourceHelper.stringToFloat(v, mTmpValue) &&
                        mTmpValue.type == TypedValue.TYPE_DIMENSION) {
                    return TypedValue.complexToDimensionPixelOffset(mTmpValue.data, mMetrics);
                }
            }
        }

        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);
        
        // this is not used since the method above always throws
        return 0;
    }

    @Override
    public int getDimensionPixelSize(int id) throws NotFoundException {
        IResourceValue value = getResourceValue(id, mPlatformResourceFlag);

        if (value != null) {
            String v = value.getValue();

            if (v != null) {
                if (ResourceHelper.stringToFloat(v, mTmpValue) &&
                        mTmpValue.type == TypedValue.TYPE_DIMENSION) {
                    return TypedValue.complexToDimensionPixelSize(mTmpValue.data, mMetrics);
                }
            }
        }

        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);
        
        // this is not used since the method above always throws
        return 0;
    }

    @Override
    public int getInteger(int id) throws NotFoundException {
        IResourceValue value = getResourceValue(id, mPlatformResourceFlag);
        
        if (value != null && value.getValue() != null) {
            String v = value.getValue();
            int radix = 10;
            if (v.startsWith("0x")) {
                v = v.substring(2);
                radix = 16;
            }
            try {
                return Integer.parseInt(v, radix);
            } catch (NumberFormatException e) {
                // return exception below
            }
        }
        
        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);
        
        // this is not used since the method above always throws
        return 0;
    }

    @Override
    public String getResourceEntryName(int resid) throws NotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getResourceName(int resid) throws NotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getResourceTypeName(int resid) throws NotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getString(int id, Object... formatArgs) throws NotFoundException {
        String s = getString(id);
        if (s != null) {
            return String.format(s, formatArgs);
            
        }

        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);
        
        // this is not used since the method above always throws
        return null;
    }

    @Override
    public String getString(int id) throws NotFoundException {
        IResourceValue value = getResourceValue(id, mPlatformResourceFlag);
        
        if (value != null && value.getValue() != null) {
            return value.getValue();
        }

        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);
        
        // this is not used since the method above always throws
        return null;
    }

    @Override
    public void getValue(int id, TypedValue outValue, boolean resolveRefs)
            throws NotFoundException {
        IResourceValue value = getResourceValue(id, mPlatformResourceFlag);

        if (value != null) {
            String v = value.getValue();

            if (v != null) {
                if (ResourceHelper.stringToFloat(v, outValue)) {
                    return;
                }
            }
        }

        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);
    }

    @Override
    public void getValue(String name, TypedValue outValue, boolean resolveRefs)
            throws NotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public XmlResourceParser getXml(int id) throws NotFoundException {
        IResourceValue value = getResourceValue(id, mPlatformResourceFlag);

        if (value != null) {
            String v = value.getValue();

            if (v != null) {
                // check this is a file
                File f = new File(value.getValue());
                if (f.isFile()) {
                    try {
                        KXmlParser parser = new KXmlParser();
                        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
                        parser.setInput(new FileReader(f));
                        
                        return new BridgeXmlBlockParser(parser, mContext, mPlatformResourceFlag[0]);
                    } catch (XmlPullParserException e) {
                        NotFoundException newE = new NotFoundException();
                        newE.initCause(e);
                        throw newE;
                    } catch (FileNotFoundException e) {
                        NotFoundException newE = new NotFoundException();
                        newE.initCause(e);
                        throw newE;
                    }
                }
            }
        }

        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);

        // this is not used since the method above always throws
        return null;
    }

    @Override
    public InputStream openRawResource(int id) throws NotFoundException {
        IResourceValue value = getResourceValue(id, mPlatformResourceFlag);

        if (value != null) {
            String v = value.getValue();

            if (v != null) {
                // check this is a file
                File f = new File(value.getValue());
                if (f.isFile()) {
                    try {
                        return new FileInputStream(f);
                    } catch (FileNotFoundException e) {
                        NotFoundException newE = new NotFoundException();
                        newE.initCause(e);
                        throw newE;
                    }
                }
            }
        }

        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);

        // this is not used since the method above always throws
        return null;
    }

    @Override
    public AssetFileDescriptor openRawResourceFd(int id) throws NotFoundException {
        throw new UnsupportedOperationException();
    }

    /**
     * Builds and throws a {@link Resources.NotFoundException} based on a resource id and a resource type.
     * @param id the id of the resource
     * @throws NotFoundException
     */
    private void throwException(int id) throws NotFoundException {
        // first get the String related to this id in the framework
        String[] resourceInfo = Bridge.resolveResourceValue(id);

        // if the name is unknown in the framework, get it from the custom view loader.
        if (resourceInfo == null && mProjectCallback != null) {
            resourceInfo = mProjectCallback.resolveResourceValue(id);
        }
        
        String message = null;
        if (resourceInfo != null) {
            message = String.format(
                    "Could not find %1$s resource matching value 0x%2$X (resolved name: %3$s) in current configuration.",
                    resourceInfo[1], id, resourceInfo[0]);
        } else {
            message = String.format(
                    "Could not resolve resource value: 0x%1$X.", id);
        }
        
        throw new NotFoundException(message);
    }
}
