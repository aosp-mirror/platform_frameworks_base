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

package android.content.res;

import com.android.ide.common.rendering.api.IProjectCallback;
import com.android.ide.common.rendering.api.LayoutLog;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.BridgeConstants;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.android.BridgeXmlBlockParser;
import com.android.layoutlib.bridge.impl.ParserFactory;
import com.android.layoutlib.bridge.impl.ResourceHelper;
import com.android.ninepatch.NinePatch;
import com.android.resources.ResourceType;
import com.android.util.Pair;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.ViewGroup.LayoutParams;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

/**
 *
 */
public final class BridgeResources extends Resources {

    private BridgeContext mContext;
    private IProjectCallback mProjectCallback;
    private boolean[] mPlatformResourceFlag = new boolean[1];

    /**
     * Simpler wrapper around FileInputStream. This is used when the input stream represent
     * not a normal bitmap but a nine patch.
     * This is useful when the InputStream is created in a method but used in another that needs
     * to know whether this is 9-patch or not, such as BitmapFactory.
     */
    public class NinePatchInputStream extends FileInputStream {
        private boolean mFakeMarkSupport = true;
        public NinePatchInputStream(File file) throws FileNotFoundException {
            super(file);
        }

        @Override
        public boolean markSupported() {
            if (mFakeMarkSupport) {
                // this is needed so that BitmapFactory doesn't wrap this in a BufferedInputStream.
                return true;
            }

            return super.markSupported();
        }

        public void disableFakeMarkSupport() {
            // disable fake mark support so that in case codec actually try to use them
            // we don't lie to them.
            mFakeMarkSupport = false;
        }
    }

    /**
     * This initializes the static field {@link Resources#mSystem} which is used
     * by methods who get global resources using {@link Resources#getSystem()}.
     * <p/>
     * They will end up using our bridge resources.
     * <p/>
     * {@link Bridge} calls this method after setting up a new bridge.
     */
    public static Resources initSystem(BridgeContext context,
            AssetManager assets,
            DisplayMetrics metrics,
            Configuration config,
            IProjectCallback projectCallback) {
        return Resources.mSystem = new BridgeResources(context,
                assets,
                metrics,
                config,
                projectCallback);
    }

    /**
     * Disposes the static {@link Resources#mSystem} to make sure we don't leave objects
     * around that would prevent us from unloading the library.
     */
    public static void disposeSystem() {
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

    public BridgeTypedArray newTypeArray(int numEntries, boolean platformFile,
            boolean platformStyleable, String styleableName) {
        return new BridgeTypedArray(this, mContext, numEntries, platformFile,
                platformStyleable, styleableName);
    }

    private Pair<String, ResourceValue> getResourceValue(int id, boolean[] platformResFlag_out) {
        // first get the String related to this id in the framework
        Pair<ResourceType, String> resourceInfo = Bridge.resolveResourceId(id);

        if (resourceInfo != null) {
            platformResFlag_out[0] = true;
            String attributeName = resourceInfo.getSecond();

            return Pair.of(attributeName, mContext.getRenderResources().getFrameworkResource(
                    resourceInfo.getFirst(), attributeName));
        }

        // didn't find a match in the framework? look in the project.
        if (mProjectCallback != null) {
            resourceInfo = mProjectCallback.resolveResourceId(id);

            if (resourceInfo != null) {
                platformResFlag_out[0] = false;
                String attributeName = resourceInfo.getSecond();

                return Pair.of(attributeName, mContext.getRenderResources().getProjectResource(
                        resourceInfo.getFirst(), attributeName));
            }
        }

        return null;
    }

    @Override
    public Drawable getDrawable(int id) throws NotFoundException {
        Pair<String, ResourceValue> value = getResourceValue(id, mPlatformResourceFlag);

        if (value != null) {
            return ResourceHelper.getDrawable(value.getSecond(), mContext);
        }

        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);

        // this is not used since the method above always throws
        return null;
    }

    @Override
    public int getColor(int id) throws NotFoundException {
        Pair<String, ResourceValue> value = getResourceValue(id, mPlatformResourceFlag);

        if (value != null) {
            try {
                return ResourceHelper.getColor(value.getSecond().getValue());
            } catch (NumberFormatException e) {
                Bridge.getLog().error(LayoutLog.TAG_RESOURCES_FORMAT, e.getMessage(), e,
                        null /*data*/);
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
        Pair<String, ResourceValue> resValue = getResourceValue(id, mPlatformResourceFlag);

        if (resValue != null) {
            ColorStateList stateList = ResourceHelper.getColorStateList(resValue.getSecond(),
                    mContext);
            if (stateList != null) {
                return stateList;
            }
        }

        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);

        // this is not used since the method above always throws
        return null;
    }

    @Override
    public CharSequence getText(int id) throws NotFoundException {
        Pair<String, ResourceValue> value = getResourceValue(id, mPlatformResourceFlag);

        if (value != null) {
            ResourceValue resValue = value.getSecond();

            assert resValue != null;
            if (resValue != null) {
                String v = resValue.getValue();
                if (v != null) {
                    return v;
                }
            }
        }

        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);

        // this is not used since the method above always throws
        return null;
    }

    @Override
    public XmlResourceParser getLayout(int id) throws NotFoundException {
        Pair<String, ResourceValue> v = getResourceValue(id, mPlatformResourceFlag);

        if (v != null) {
            ResourceValue value = v.getSecond();
            XmlPullParser parser = null;

            try {
                // check if the current parser can provide us with a custom parser.
                if (mPlatformResourceFlag[0] == false) {
                    parser = mProjectCallback.getParser(value);
                }

                // create a new one manually if needed.
                if (parser == null) {
                    File xml = new File(value.getValue());
                    if (xml.isFile()) {
                        // we need to create a pull parser around the layout XML file, and then
                        // give that to our XmlBlockParser
                        parser = ParserFactory.create(xml);
                    }
                }

                if (parser != null) {
                    return new BridgeXmlBlockParser(parser, mContext, mPlatformResourceFlag[0]);
                }
            } catch (XmlPullParserException e) {
                Bridge.getLog().error(LayoutLog.TAG_BROKEN,
                        "Failed to configure parser for " + value.getValue(), e, null /*data*/);
                // we'll return null below.
            } catch (FileNotFoundException e) {
                // this shouldn't happen since we check above.
            }

        }

        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);

        // this is not used since the method above always throws
        return null;
    }

    @Override
    public XmlResourceParser getAnimation(int id) throws NotFoundException {
        Pair<String, ResourceValue> v = getResourceValue(id, mPlatformResourceFlag);

        if (v != null) {
            ResourceValue value = v.getSecond();
            XmlPullParser parser = null;

            try {
                File xml = new File(value.getValue());
                if (xml.isFile()) {
                    // we need to create a pull parser around the layout XML file, and then
                    // give that to our XmlBlockParser
                    parser = ParserFactory.create(xml);

                    return new BridgeXmlBlockParser(parser, mContext, mPlatformResourceFlag[0]);
                }
            } catch (XmlPullParserException e) {
                Bridge.getLog().error(LayoutLog.TAG_BROKEN,
                        "Failed to configure parser for " + value.getValue(), e, null /*data*/);
                // we'll return null below.
            } catch (FileNotFoundException e) {
                // this shouldn't happen since we check above.
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
        Pair<String, ResourceValue> value = getResourceValue(id, mPlatformResourceFlag);

        if (value != null) {
            ResourceValue resValue = value.getSecond();

            assert resValue != null;
            if (resValue != null) {
                String v = resValue.getValue();
                if (v != null) {
                    if (v.equals(BridgeConstants.MATCH_PARENT) ||
                            v.equals(BridgeConstants.FILL_PARENT)) {
                        return LayoutParams.MATCH_PARENT;
                    } else if (v.equals(BridgeConstants.WRAP_CONTENT)) {
                        return LayoutParams.WRAP_CONTENT;
                    }

                    if (ResourceHelper.parseFloatAttribute(
                            value.getFirst(), v, mTmpValue, true /*requireUnit*/) &&
                            mTmpValue.type == TypedValue.TYPE_DIMENSION) {
                        return mTmpValue.getDimension(getDisplayMetrics());
                    }
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
        Pair<String, ResourceValue> value = getResourceValue(id, mPlatformResourceFlag);

        if (value != null) {
            ResourceValue resValue = value.getSecond();

            assert resValue != null;
            if (resValue != null) {
                String v = resValue.getValue();
                if (v != null) {
                    if (ResourceHelper.parseFloatAttribute(
                            value.getFirst(), v, mTmpValue, true /*requireUnit*/) &&
                            mTmpValue.type == TypedValue.TYPE_DIMENSION) {
                        return TypedValue.complexToDimensionPixelOffset(mTmpValue.data,
                                getDisplayMetrics());
                    }
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
        Pair<String, ResourceValue> value = getResourceValue(id, mPlatformResourceFlag);

        if (value != null) {
            ResourceValue resValue = value.getSecond();

            assert resValue != null;
            if (resValue != null) {
                String v = resValue.getValue();
                if (v != null) {
                    if (ResourceHelper.parseFloatAttribute(
                            value.getFirst(), v, mTmpValue, true /*requireUnit*/) &&
                            mTmpValue.type == TypedValue.TYPE_DIMENSION) {
                        return TypedValue.complexToDimensionPixelSize(mTmpValue.data,
                                getDisplayMetrics());
                    }
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
        Pair<String, ResourceValue> value = getResourceValue(id, mPlatformResourceFlag);

        if (value != null) {
            ResourceValue resValue = value.getSecond();

            assert resValue != null;
            if (resValue != null) {
                String v = resValue.getValue();
                if (v != null) {
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
            }
        }

        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);

        // this is not used since the method above always throws
        return 0;
    }

    @Override
    public boolean getBoolean(int id) throws NotFoundException {
        Pair<String, ResourceValue> value = getResourceValue(id, mPlatformResourceFlag);

        if (value != null) {
            ResourceValue resValue = value.getSecond();

            assert resValue != null;
            if (resValue != null) {
                String v = resValue.getValue();
                if (v != null) {
                    return Boolean.parseBoolean(v);
                }
            }
        }

        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);

        // this is not used since the method above always throws
        return false;
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
        Pair<String, ResourceValue> value = getResourceValue(id, mPlatformResourceFlag);

        if (value != null && value.getSecond().getValue() != null) {
            return value.getSecond().getValue();
        }

        // id was not found or not resolved. Throw a NotFoundException.
        throwException(id);

        // this is not used since the method above always throws
        return null;
    }

    @Override
    public void getValue(int id, TypedValue outValue, boolean resolveRefs)
            throws NotFoundException {
        Pair<String, ResourceValue> value = getResourceValue(id, mPlatformResourceFlag);

        if (value != null) {
            String v = value.getSecond().getValue();

            if (v != null) {
                if (ResourceHelper.parseFloatAttribute(value.getFirst(), v, outValue,
                        false /*requireUnit*/)) {
                    return;
                }

                // else it's a string
                outValue.type = TypedValue.TYPE_STRING;
                outValue.string = v;
                return;
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
        Pair<String, ResourceValue> value = getResourceValue(id, mPlatformResourceFlag);

        if (value != null) {
            String v = value.getSecond().getValue();

            if (v != null) {
                // check this is a file
                File f = new File(v);
                if (f.isFile()) {
                    try {
                        XmlPullParser parser = ParserFactory.create(f);

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
    public XmlResourceParser loadXmlResourceParser(String file, int id,
            int assetCookie, String type) throws NotFoundException {
        // even though we know the XML file to load directly, we still need to resolve the
        // id so that we can know if it's a platform or project resource.
        // (mPlatformResouceFlag will get the result and will be used later).
        getResourceValue(id, mPlatformResourceFlag);

        File f = new File(file);
        try {
            XmlPullParser parser = ParserFactory.create(f);

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


    @Override
    public InputStream openRawResource(int id) throws NotFoundException {
        Pair<String, ResourceValue> value = getResourceValue(id, mPlatformResourceFlag);

        if (value != null) {
            String path = value.getSecond().getValue();

            if (path != null) {
                // check this is a file
                File f = new File(path);
                if (f.isFile()) {
                    try {
                        // if it's a nine-patch return a custom input stream so that
                        // other methods (mainly bitmap factory) can detect it's a 9-patch
                        // and actually load it as a 9-patch instead of a normal bitmap
                        if (path.toLowerCase().endsWith(NinePatch.EXTENSION_9PATCH)) {
                            return new NinePatchInputStream(f);
                        }
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
    public InputStream openRawResource(int id, TypedValue value) throws NotFoundException {
        getValue(id, value, true);

        String path = value.string.toString();

        File f = new File(path);
        if (f.isFile()) {
            try {
                // if it's a nine-patch return a custom input stream so that
                // other methods (mainly bitmap factory) can detect it's a 9-patch
                // and actually load it as a 9-patch instead of a normal bitmap
                if (path.toLowerCase().endsWith(NinePatch.EXTENSION_9PATCH)) {
                    return new NinePatchInputStream(f);
                }
                return new FileInputStream(f);
            } catch (FileNotFoundException e) {
                NotFoundException exception = new NotFoundException();
                exception.initCause(e);
                throw exception;
            }
        }

        throw new NotFoundException();
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
        Pair<ResourceType, String> resourceInfo = Bridge.resolveResourceId(id);

        // if the name is unknown in the framework, get it from the custom view loader.
        if (resourceInfo == null && mProjectCallback != null) {
            resourceInfo = mProjectCallback.resolveResourceId(id);
        }

        String message = null;
        if (resourceInfo != null) {
            message = String.format(
                    "Could not find %1$s resource matching value 0x%2$X (resolved name: %3$s) in current configuration.",
                    resourceInfo.getFirst(), id, resourceInfo.getSecond());
        } else {
            message = String.format(
                    "Could not resolve resource value: 0x%1$X.", id);
        }

        throw new NotFoundException(message);
    }
}
