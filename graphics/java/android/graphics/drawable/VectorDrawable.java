/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package android.graphics.drawable;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.PorterDuff.Mode;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.LayoutDirection;
import android.util.Log;
import android.util.PathParser;
import android.util.Xml;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Stack;

/**
 * This lets you create a drawable based on an XML vector graphic It can be
 * defined in an XML file with the <code>&lt;vector></code> element.
 * <p/>
 * The vector drawable has the following elements:
 * <p/>
 * <dl>
 * <dt><code>&lt;vector></code></dt>
 * <dd>Used to defined a vector drawable</dd>
 * <dt><code>&lt;size></code></dt>
 * <dd>Used to defined the intrinsic Width Height size of the drawable using
 * <code>android:width</code> and <code>android:height</code></dd>
 * <dt><code>&lt;viewport></code></dt>
 * <dd>Used to defined the size of the virtual canvas the paths are drawn on.
 * The size is defined using the attributes <code>android:viewportHeight</code>
 * <code>android:viewportWidth</code></dd>
 * <dt><code>&lt;group></code></dt>
 * <dd>Defines a group of paths or subgroups, plus transformation information.
 * The transformations are defined in the same coordinates as the viewport.
 * And the transformations are applied in the order of scale, rotate then translate. </dd>
 * <dt><code>android:rotation</code>
 * <dd>The degrees of rotation of the group.</dd></dt>
 * <dt><code>android:pivotX</code>
 * <dd>The X coordinate of the pivot for the scale and rotation of the group</dd></dt>
 * <dt><code>android:pivotY</code>
 * <dd>The Y coordinate of the pivot for the scale and rotation of the group</dd></dt>
 * <dt><code>android:scaleX</code>
 * <dd>The amount of scale on the X Coordinate</dd></dt>
 * <dt><code>android:scaleY</code>
 * <dd>The amount of scale on the Y coordinate</dd></dt>
 * <dt><code>android:translateX</code>
 * <dd>The amount of translation on the X coordinate</dd></dt>
 * <dt><code>android:translateY</code>
 * <dd>The amount of translation on the Y coordinate</dd></dt>
 * <dt><code>&lt;path></code></dt>
 * <dd>Defines paths to be drawn.
 * <dl>
 * <dt><code>android:name</code>
 * <dd>Defines the name of the path.</dd></dt>
 * <dt><code>android:pathData</code>
 * <dd>Defines path string. This is using exactly same format as "d" attribute
 * in the SVG's path data</dd></dt>
 * <dt><code>android:fill</code>
 * <dd>Defines the color to fill the path (none if not present).</dd></dt>
 * <dt><code>android:stroke</code>
 * <dd>Defines the color to draw the path outline (none if not present).</dd>
 * </dt>
 * <dt><code>android:strokeWidth</code>
 * <dd>The width a path stroke</dd></dt>
 * <dt><code>android:strokeOpacity</code>
 * <dd>The opacity of a path stroke</dd></dt>
 * <dt><code>android:fillOpacity</code>
 * <dd>The opacity to fill the path with</dd></dt>
 * <dt><code>android:trimPathStart</code>
 * <dd>The fraction of the path to trim from the start from 0 to 1</dd></dt>
 * <dt><code>android:trimPathEnd</code>
 * <dd>The fraction of the path to trim from the end from 0 to 1</dd></dt>
 * <dt><code>android:trimPathOffset</code>
 * <dd>Shift trim region (allows showed region to include the start and end)
 * from 0 to 1</dd></dt>
 * <dt><code>android:clipToPath</code>
 * <dd>Path will set the clip path</dd></dt>
 * <dt><code>android:strokeLineCap</code>
 * <dd>Sets the linecap for a stroked path: butt, round, square</dd></dt>
 * <dt><code>android:strokeLineJoin</code>
 * <dd>Sets the lineJoin for a stroked path: miter,round,bevel</dd></dt>
 * <dt><code>android:strokeMiterLimit</code>
 * <dd>Sets the Miter limit for a stroked path</dd></dt>
 * </dl>
 * </dd>
 */
public class VectorDrawable extends Drawable {
    private static final String LOGTAG = VectorDrawable.class.getSimpleName();

    private static final String SHAPE_SIZE = "size";
    private static final String SHAPE_VIEWPORT = "viewport";
    private static final String SHAPE_GROUP = "group";
    private static final String SHAPE_PATH = "path";
    private static final String SHAPE_VECTOR = "vector";

    private static final int LINECAP_BUTT = 0;
    private static final int LINECAP_ROUND = 1;
    private static final int LINECAP_SQUARE = 2;

    private static final int LINEJOIN_MITER = 0;
    private static final int LINEJOIN_ROUND = 1;
    private static final int LINEJOIN_BEVEL = 2;

    private static final boolean DBG_VECTOR_DRAWABLE = false;

    private VectorDrawableState mVectorState;

    private PorterDuffColorFilter mTintFilter;

    private boolean mMutated;

    // AnimatedVectorDrawable needs to turn off the cache all the time, otherwise,
    // caching the bitmap by default is allowed.
    private boolean mAllowCaching = true;

    public VectorDrawable() {
        mVectorState = new VectorDrawableState();
    }

    private VectorDrawable(VectorDrawableState state, Resources res, Theme theme) {
        if (theme != null && state.canApplyTheme()) {
            // If we need to apply a theme, implicitly mutate.
            mVectorState = new VectorDrawableState(state);
            applyTheme(theme);
        } else {
            mVectorState = state;
        }

        mTintFilter = updateTintFilter(mTintFilter, state.mTint, state.mTintMode);
        mVectorState.mVPathRenderer.setColorFilter(mTintFilter);
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mVectorState = new VectorDrawableState(mVectorState);
            mMutated = true;
        }
        return this;
    }

    Object getTargetByName(String name) {
        return mVectorState.mVPathRenderer.mVGTargetsMap.get(name);
    }

    @Override
    public ConstantState getConstantState() {
        mVectorState.mChangingConfigurations = getChangingConfigurations();
        return mVectorState;
    }

    @Override
    public void draw(Canvas canvas) {
        final int saveCount = canvas.save();
        final Rect bounds = getBounds();
        final boolean needMirroring = needMirroring();

        canvas.translate(bounds.left, bounds.top);
        if (needMirroring) {
            canvas.translate(bounds.width(), 0);
            canvas.scale(-1.0f, 1.0f);
        }

        if (!mAllowCaching) {
            mVectorState.mVPathRenderer.draw(canvas, bounds.width(), bounds.height());
        } else {
            Bitmap bitmap = mVectorState.mCachedBitmap;
            if (bitmap == null || !mVectorState.canReuseCache(bounds.width(),
                    bounds.height())) {
                bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(),
                        Bitmap.Config.ARGB_8888);
                Canvas tmpCanvas = new Canvas(bitmap);
                mVectorState.mVPathRenderer.draw(tmpCanvas, bounds.width(), bounds.height());
                mVectorState.mCachedBitmap = bitmap;

                mVectorState.updateCacheStates();
            }
            canvas.drawBitmap(bitmap, null, bounds, null);
        }

        canvas.restoreToCount(saveCount);
    }

    @Override
    public int getAlpha() {
        return mVectorState.mVPathRenderer.getRootAlpha();
    }

    @Override
    public void setAlpha(int alpha) {
        if (mVectorState.mVPathRenderer.getRootAlpha() != alpha) {
            mVectorState.mVPathRenderer.setRootAlpha(alpha);
            invalidateSelf();
        }
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        final VectorDrawableState state = mVectorState;
        if (colorFilter != null) {
            // Color filter overrides tint.
            mTintFilter = null;
        } else if (state.mTint != null && state.mTintMode != null) {
            // Restore the tint filter, if we need one.
            final int color = state.mTint.getColorForState(getState(), Color.TRANSPARENT);
            mTintFilter = new PorterDuffColorFilter(color, state.mTintMode);
            colorFilter = mTintFilter;
        }

        state.mVPathRenderer.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public void setTint(ColorStateList tint, Mode tintMode) {
        final VectorDrawableState state = mVectorState;
        if (state.mTint != tint || state.mTintMode != tintMode) {
            state.mTint = tint;
            state.mTintMode = tintMode;

            mTintFilter = updateTintFilter(mTintFilter, tint, tintMode);
            mVectorState.mVPathRenderer.setColorFilter(mTintFilter);
            invalidateSelf();
        }
    }

    @Override
    protected boolean onStateChange(int[] stateSet) {
        final VectorDrawableState state = mVectorState;
        if (state.mTint != null && state.mTintMode != null) {
            mTintFilter = updateTintFilter(mTintFilter, state.mTint, state.mTintMode);
            mVectorState.mVPathRenderer.setColorFilter(mTintFilter);
            return true;
        }
        return false;
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return (int) mVectorState.mVPathRenderer.mBaseWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        return (int) mVectorState.mVPathRenderer.mBaseHeight;
    }

    @Override
    public boolean canApplyTheme() {
        return super.canApplyTheme() || mVectorState != null && mVectorState.canApplyTheme();
    }

    @Override
    public void applyTheme(Theme t) {
        super.applyTheme(t);

        final VectorDrawableState state = mVectorState;
        final VPathRenderer path = state.mVPathRenderer;
        if (path != null && path.canApplyTheme()) {
            path.applyTheme(t);
        }
    }

    /** @hide */
    public static VectorDrawable create(Resources resources, int rid) {
        try {
            final XmlPullParser xpp = resources.getXml(rid);
            final AttributeSet attrs = Xml.asAttributeSet(xpp);
            final XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);

            final VectorDrawable drawable = new VectorDrawable();
            drawable.inflate(resources, xpp, attrs);

            return drawable;
        } catch (XmlPullParserException e) {
            Log.e(LOGTAG, "parser error", e);
        } catch (IOException e) {
            Log.e(LOGTAG, "parser error", e);
        }
        return null;
    }

    private static int applyAlpha(int color, float alpha) {
        int alphaBytes = Color.alpha(color);
        color &= 0x00FFFFFF;
        color |= ((int) (alphaBytes * alpha)) << 24;
        return color;
    }

    @Override
    public void inflate(Resources res, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        final TypedArray a = obtainAttributes(res, theme, attrs, R.styleable.VectorDrawable);
        updateStateFromTypedArray(a);
        a.recycle();

        final VectorDrawableState state = mVectorState;
        mVectorState.mCacheDirty = true;
        inflateInternal(res, parser, attrs, theme);

        mTintFilter = updateTintFilter(mTintFilter, state.mTint, state.mTintMode);
        state.mVPathRenderer.setColorFilter(mTintFilter);
    }

    private void updateStateFromTypedArray(TypedArray a) {
        final VectorDrawableState state = mVectorState;

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrs = a.extractThemeAttrs();

        final int tintMode = a.getInt(R.styleable.VectorDrawable_tintMode, -1);
        if (tintMode != -1) {
            state.mTintMode = Drawable.parseTintMode(tintMode, Mode.SRC_IN);
        }

        final ColorStateList tint = a.getColorStateList(R.styleable.VectorDrawable_tint);
        if (tint != null) {
            state.mTint = tint;
        }

        state.mAutoMirrored = a.getBoolean(
                R.styleable.VectorDrawable_autoMirrored, state.mAutoMirrored);
    }

    private void inflateInternal(Resources res, XmlPullParser parser, AttributeSet attrs,
            Theme theme) throws XmlPullParserException, IOException {
        final VectorDrawableState state = mVectorState;
        final VPathRenderer pathRenderer = new VPathRenderer();
        state.mVPathRenderer = pathRenderer;

        boolean noSizeTag = true;
        boolean noViewportTag = true;
        boolean noPathTag = true;

        // Use a stack to help to build the group tree.
        // The top of the stack is always the current group.
        final Stack<VGroup> groupStack = new Stack<VGroup>();
        groupStack.push(pathRenderer.mRootGroup);

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                final String tagName = parser.getName();
                final VGroup currentGroup = groupStack.peek();

                if (SHAPE_PATH.equals(tagName)) {
                    final VPath path = new VPath();
                    path.inflate(res, attrs, theme);
                    currentGroup.mChildren.add(path);
                    if (path.getPathName() != null) {
                        pathRenderer.mVGTargetsMap.put(path.getPathName(), path);
                    }
                    noPathTag = false;
                    state.mChangingConfigurations |= path.mChangingConfigurations;
                } else if (SHAPE_SIZE.equals(tagName)) {
                    pathRenderer.parseSize(res, attrs);
                    noSizeTag = false;
                    state.mChangingConfigurations |= pathRenderer.mChangingConfigurations;
                } else if (SHAPE_VIEWPORT.equals(tagName)) {
                    pathRenderer.parseViewport(res, attrs);
                    noViewportTag = false;
                    state.mChangingConfigurations |= pathRenderer.mChangingConfigurations;
                } else if (SHAPE_GROUP.equals(tagName)) {
                    VGroup newChildGroup = new VGroup();
                    newChildGroup.inflate(res, attrs, theme);
                    currentGroup.mChildren.add(newChildGroup);
                    groupStack.push(newChildGroup);
                    if (newChildGroup.getGroupName() != null) {
                        pathRenderer.mVGTargetsMap.put(newChildGroup.getGroupName(),
                                newChildGroup);
                    }
                    state.mChangingConfigurations |= newChildGroup.mChangingConfigurations;
                }
            } else if (eventType == XmlPullParser.END_TAG) {
                final String tagName = parser.getName();
                if (SHAPE_GROUP.equals(tagName)) {
                    groupStack.pop();
                }
            }
            eventType = parser.next();
        }

        // Print the tree out for debug.
        if (DBG_VECTOR_DRAWABLE) {
            printGroupTree(pathRenderer.mRootGroup, 0);
        }

        if (noSizeTag || noViewportTag || noPathTag) {
            final StringBuffer tag = new StringBuffer();

            if (noSizeTag) {
                tag.append(SHAPE_SIZE);
            }

            if (noViewportTag) {
                if (tag.length() > 0) {
                    tag.append(" & ");
                }
                tag.append(SHAPE_SIZE);
            }

            if (noPathTag) {
                if (tag.length() > 0) {
                    tag.append(" or ");
                }
                tag.append(SHAPE_PATH);
            }

            throw new XmlPullParserException("no " + tag + " defined");
        }
    }

    private void printGroupTree(VGroup currentGroup, int level) {
        String indent = "";
        for (int i = 0; i < level; i++) {
            indent += "    ";
        }
        // Print the current node
        Log.v(LOGTAG, indent + "current group is :" + currentGroup.getGroupName()
                + " rotation is " + currentGroup.mRotate);
        Log.v(LOGTAG, indent + "matrix is :" + currentGroup.getLocalMatrix().toString());
        // Then print all the children groups
        for (int i = 0; i < currentGroup.mChildren.size(); i++) {
            Object child = currentGroup.mChildren.get(i);
            if (child instanceof VGroup) {
                printGroupTree((VGroup) child, level + 1);
            }
        }
    }

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations() | mVectorState.mChangingConfigurations;
    }

    void setAllowCaching(boolean allowCaching) {
        mAllowCaching = allowCaching;
    }

    private boolean needMirroring() {
        return isAutoMirrored() && getLayoutDirection() == LayoutDirection.RTL;
    }

    @Override
    public void setAutoMirrored(boolean mirrored) {
        if (mVectorState.mAutoMirrored != mirrored) {
            mVectorState.mAutoMirrored = mirrored;
            invalidateSelf();
        }
    }

    @Override
    public boolean isAutoMirrored() {
        return mVectorState.mAutoMirrored;
    }

    private static class VectorDrawableState extends ConstantState {
        int[] mThemeAttrs;
        int mChangingConfigurations;
        VPathRenderer mVPathRenderer;
        ColorStateList mTint;
        Mode mTintMode;
        boolean mAutoMirrored;

        Bitmap mCachedBitmap;
        int[] mCachedThemeAttrs;
        ColorStateList mCachedTint;
        Mode mCachedTintMode;
        int mCachedRootAlpha;
        boolean mCachedAutoMirrored;
        boolean mCacheDirty;

        // Deep copy for mutate() or implicitly mutate.
        public VectorDrawableState(VectorDrawableState copy) {
            if (copy != null) {
                mThemeAttrs = copy.mThemeAttrs;
                mChangingConfigurations = copy.mChangingConfigurations;
                mVPathRenderer = new VPathRenderer(copy.mVPathRenderer);
                mTint = copy.mTint;
                mTintMode = copy.mTintMode;
                mAutoMirrored = copy.mAutoMirrored;
            }
        }

        public boolean canReuseCache(int width, int height) {
            if (!mCacheDirty
                    && mCachedThemeAttrs == mThemeAttrs
                    && mCachedTint == mTint
                    && mCachedTintMode == mTintMode
                    && mCachedAutoMirrored == mAutoMirrored
                    && width == mCachedBitmap.getWidth()
                    && height == mCachedBitmap.getHeight()
                    && mCachedRootAlpha == mVPathRenderer.getRootAlpha())  {
                return true;
            }
            return false;
        }

        public void updateCacheStates() {
            // Use shallow copy here and shallow comparison in canReuseCache(),
            // likely hit cache miss more, but practically not much difference.
            mCachedThemeAttrs = mThemeAttrs;
            mCachedTint = mTint;
            mCachedTintMode = mTintMode;
            mCachedRootAlpha = mVPathRenderer.getRootAlpha();
            mCachedAutoMirrored = mAutoMirrored;
            mCacheDirty = false;
        }

        public VectorDrawableState() {
            mVPathRenderer = new VPathRenderer();
        }

        @Override
        public Drawable newDrawable() {
            return new VectorDrawable(this, null, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new VectorDrawable(this, res, null);
        }

        @Override
        public Drawable newDrawable(Resources res, Theme theme) {
            return new VectorDrawable(this, res, theme);
        }

        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }
    }

    private static class VPathRenderer {
        /* Right now the internal data structure is organized as a tree.
         * Each node can be a group node, or a path.
         * A group node can have groups or paths as children, but a path node has
         * no children.
         * One example can be:
         *                 Root Group
         *                /    |     \
         *           Group    Path    Group
         *          /     \             |
         *         Path   Path         Path
         *
         */
        // Variables that only used temporarily inside the draw() call, so there
        // is no need for deep copying.
        private final Path mPath = new Path();
        private final Path mRenderPath = new Path();
        private static final Matrix IDENTITY_MATRIX = new Matrix();
        private final Matrix mFinalPathMatrix = new Matrix();

        private Paint mStrokePaint;
        private Paint mFillPaint;
        private ColorFilter mColorFilter;
        private PathMeasure mPathMeasure;

        /////////////////////////////////////////////////////
        // Variables below need to be copied (deep copy if applicable) for mutation.
        private int mChangingConfigurations;
        private final VGroup mRootGroup;
        private float mBaseWidth = 0;
        private float mBaseHeight = 0;
        private float mViewportWidth = 0;
        private float mViewportHeight = 0;
        private int mRootAlpha = 0xFF;

        final ArrayMap<String, Object> mVGTargetsMap = new ArrayMap<String, Object>();

        public VPathRenderer() {
            mRootGroup = new VGroup();
        }

        public void setRootAlpha(int alpha) {
            mRootAlpha = alpha;
        }

        public int getRootAlpha() {
            return mRootAlpha;
        }

        public VPathRenderer(VPathRenderer copy) {
            mRootGroup = new VGroup(copy.mRootGroup, mVGTargetsMap);
            mBaseWidth = copy.mBaseWidth;
            mBaseHeight = copy.mBaseHeight;
            mViewportWidth = copy.mViewportHeight;
            mViewportHeight = copy.mViewportHeight;
            mChangingConfigurations = copy.mChangingConfigurations;
            mRootAlpha = copy.mRootAlpha;
        }

        public boolean canApplyTheme() {
            // If one of the paths can apply theme, then return true;
            return recursiveCanApplyTheme(mRootGroup);
        }

        private boolean recursiveCanApplyTheme(VGroup currentGroup) {
            // We can do a tree traverse here, if there is one path return true,
            // then we return true for the whole tree.
            final ArrayList<Object> children = currentGroup.mChildren;

            for (int i = 0; i < children.size(); i++) {
                Object child = children.get(i);
                if (child instanceof VGroup) {
                    VGroup childGroup = (VGroup) child;
                    if (childGroup.canApplyTheme()
                            || recursiveCanApplyTheme(childGroup)) {
                        return true;
                    }
                } else if (child instanceof VPath) {
                    VPath childPath = (VPath) child;
                    if (childPath.canApplyTheme()) {
                        return true;
                    }
                }
            }
            return false;
        }

        public void applyTheme(Theme t) {
            // Apply theme to every path of the tree.
            recursiveApplyTheme(mRootGroup, t);
        }

        private void recursiveApplyTheme(VGroup currentGroup, Theme t) {
            // We can do a tree traverse here, apply theme to all paths which
            // can apply theme.
            final ArrayList<Object> children = currentGroup.mChildren;
            for (int i = 0; i < children.size(); i++) {
                Object child = children.get(i);
                if (child instanceof VGroup) {
                    VGroup childGroup = (VGroup) child;
                    if (childGroup.canApplyTheme()) {
                        childGroup.applyTheme(t);
                    }
                    recursiveApplyTheme(childGroup, t);
                } else if (child instanceof VPath) {
                    VPath childPath = (VPath) child;
                    if (childPath.canApplyTheme()) {
                        childPath.applyTheme(t);
                    }
                }
            }
        }

        public void setColorFilter(ColorFilter colorFilter) {
            mColorFilter = colorFilter;

            if (mFillPaint != null) {
                mFillPaint.setColorFilter(colorFilter);
            }

            if (mStrokePaint != null) {
                mStrokePaint.setColorFilter(colorFilter);
            }

        }

        private void drawGroupTree(VGroup currentGroup, Matrix currentMatrix,
                float currentAlpha, Canvas canvas, int w, int h) {
            // Calculate current group's matrix by preConcat the parent's and
            // and the current one on the top of the stack.
            // Basically the Mfinal = Mviewport * M0 * M1 * M2;
            // Mi the local matrix at level i of the group tree.
            currentGroup.mStackedMatrix.set(currentMatrix);

            currentGroup.mStackedMatrix.preConcat(currentGroup.mLocalMatrix);

            float stackedAlpha = currentAlpha * currentGroup.mGroupAlpha;

            // Draw the group tree in the same order as the XML file.
            for (int i = 0; i < currentGroup.mChildren.size(); i++) {
                Object child = currentGroup.mChildren.get(i);
                if (child instanceof VGroup) {
                    VGroup childGroup = (VGroup) child;
                    drawGroupTree(childGroup, currentGroup.mStackedMatrix,
                            stackedAlpha, canvas, w, h);
                } else if (child instanceof VPath) {
                    VPath childPath = (VPath) child;
                    drawPath(currentGroup, childPath, stackedAlpha, canvas, w, h);
                }
            }
        }

        public void draw(Canvas canvas, int w, int h) {
            // Travese the tree in pre-order to draw.
            drawGroupTree(mRootGroup, IDENTITY_MATRIX, ((float) mRootAlpha) / 0xFF, canvas, w, h);
        }

        private void drawPath(VGroup vGroup, VPath vPath, float stackedAlpha,
                Canvas canvas, int w, int h) {
            final float scaleX =  w / mViewportWidth;
            final float scaleY = h / mViewportHeight;
            final float minScale = Math.min(scaleX, scaleY);

            mFinalPathMatrix.set(vGroup.mStackedMatrix);
            mFinalPathMatrix.postScale(scaleX, scaleY, mViewportWidth / 2f, mViewportHeight / 2f);
            mFinalPathMatrix.postTranslate(
                    w / 2f - mViewportWidth / 2f, h / 2f - mViewportHeight / 2f);

            vPath.toPath(mPath);
            final Path path = mPath;

            if (vPath.mTrimPathStart != 0.0f || vPath.mTrimPathEnd != 1.0f) {
                float start = (vPath.mTrimPathStart + vPath.mTrimPathOffset) % 1.0f;
                float end = (vPath.mTrimPathEnd + vPath.mTrimPathOffset) % 1.0f;

                if (mPathMeasure == null) {
                    mPathMeasure = new PathMeasure();
                }
                mPathMeasure.setPath(mPath, false);

                float len = mPathMeasure.getLength();
                start = start * len;
                end = end * len;
                path.reset();
                if (start > end) {
                    mPathMeasure.getSegment(start, len, path, true);
                    mPathMeasure.getSegment(0f, end, path, true);
                } else {
                    mPathMeasure.getSegment(start, end, path, true);
                }
                path.rLineTo(0, 0); // fix bug in measure
            }

            mRenderPath.reset();

            mRenderPath.addPath(path, mFinalPathMatrix);

            if (vPath.mClip) {
                canvas.clipPath(mRenderPath, Region.Op.REPLACE);
            } else {
                if (vPath.mFillColor != 0) {
                    if (mFillPaint == null) {
                        mFillPaint = new Paint();
                        mFillPaint.setColorFilter(mColorFilter);
                        mFillPaint.setStyle(Paint.Style.FILL);
                        mFillPaint.setAntiAlias(true);
                    }
                    mFillPaint.setColor(applyAlpha(vPath.mFillColor, stackedAlpha));
                    canvas.drawPath(mRenderPath, mFillPaint);
                }

                if (vPath.mStrokeColor != 0) {
                    if (mStrokePaint == null) {
                        mStrokePaint = new Paint();
                        mStrokePaint.setColorFilter(mColorFilter);
                        mStrokePaint.setStyle(Paint.Style.STROKE);
                        mStrokePaint.setAntiAlias(true);
                    }

                    final Paint strokePaint = mStrokePaint;
                    if (vPath.mStrokeLineJoin != null) {
                        strokePaint.setStrokeJoin(vPath.mStrokeLineJoin);
                    }

                    if (vPath.mStrokeLineCap != null) {
                        strokePaint.setStrokeCap(vPath.mStrokeLineCap);
                    }

                    strokePaint.setStrokeMiter(vPath.mStrokeMiterlimit * minScale);

                    strokePaint.setColor(applyAlpha(vPath.mStrokeColor, stackedAlpha));
                    strokePaint.setStrokeWidth(vPath.mStrokeWidth * minScale);
                    canvas.drawPath(mRenderPath, strokePaint);
                }
            }
        }

        private void parseViewport(Resources r, AttributeSet attrs)
                throws XmlPullParserException {
            final TypedArray a = r.obtainAttributes(attrs, R.styleable.VectorDrawableViewport);

            // Account for any configuration changes.
            mChangingConfigurations |= a.getChangingConfigurations();

            mViewportWidth = a.getFloat(
                    R.styleable.VectorDrawableViewport_viewportWidth, mViewportWidth);
            mViewportHeight = a.getFloat(
                    R.styleable.VectorDrawableViewport_viewportHeight, mViewportHeight);

            if (mViewportWidth <= 0) {
                throw new XmlPullParserException(a.getPositionDescription() +
                        "<viewport> tag requires viewportWidth > 0");
            } else if (mViewportHeight <= 0) {
                throw new XmlPullParserException(a.getPositionDescription() +
                        "<viewport> tag requires viewportHeight > 0");
            }

            a.recycle();
        }

        private void parseSize(Resources r, AttributeSet attrs)
                throws XmlPullParserException {
            final TypedArray a = r.obtainAttributes(attrs, R.styleable.VectorDrawableSize);

            // Account for any configuration changes.
            mChangingConfigurations |= a.getChangingConfigurations();

            mBaseWidth = a.getDimension(R.styleable.VectorDrawableSize_width, mBaseWidth);
            mBaseHeight = a.getDimension(R.styleable.VectorDrawableSize_height, mBaseHeight);

            if (mBaseWidth <= 0) {
                throw new XmlPullParserException(a.getPositionDescription() +
                        "<size> tag requires width > 0");
            } else if (mBaseHeight <= 0) {
                throw new XmlPullParserException(a.getPositionDescription() +
                        "<size> tag requires height > 0");
            }

            a.recycle();
        }

    }

    static class VGroup {
        // mStackedMatrix is only used temporarily when drawing, it combines all
        // the parents' local matrices with the current one.
        private final Matrix mStackedMatrix = new Matrix();

        /////////////////////////////////////////////////////
        // Variables below need to be copied (deep copy if applicable) for mutation.
        final ArrayList<Object> mChildren = new ArrayList<Object>();

        private float mRotate = 0;
        private float mPivotX = 0;
        private float mPivotY = 0;
        private float mScaleX = 1;
        private float mScaleY = 1;
        private float mTranslateX = 0;
        private float mTranslateY = 0;
        private float mGroupAlpha = 1;

        // mLocalMatrix is updated based on the update of transformation information,
        // either parsed from the XML or by animation.
        private final Matrix mLocalMatrix = new Matrix();
        private int mChangingConfigurations;
        private int[] mThemeAttrs;
        private String mGroupName = null;

        public VGroup(VGroup copy, ArrayMap<String, Object> targetsMap) {
            mRotate = copy.mRotate;
            mPivotX = copy.mPivotX;
            mPivotY = copy.mPivotY;
            mScaleX = copy.mScaleX;
            mScaleY = copy.mScaleY;
            mTranslateX = copy.mTranslateX;
            mTranslateY = copy.mTranslateY;
            mGroupAlpha = copy.mGroupAlpha;
            mThemeAttrs = copy.mThemeAttrs;
            mGroupName = copy.mGroupName;
            mChangingConfigurations = copy.mChangingConfigurations;
            if (mGroupName != null) {
                targetsMap.put(mGroupName, this);
            }

            mLocalMatrix.set(copy.mLocalMatrix);

            final ArrayList<Object> children = copy.mChildren;
            for (int i = 0; i < children.size(); i++) {
                Object copyChild = children.get(i);
                if (copyChild instanceof VGroup) {
                    VGroup copyGroup = (VGroup) copyChild;
                    mChildren.add(new VGroup(copyGroup, targetsMap));
                } else if (copyChild instanceof VPath) {
                    VPath copyPath = (VPath) copyChild;
                    VPath newPath = new VPath(copyPath);
                    mChildren.add(newPath);
                    if (newPath.mPathName != null) {
                        targetsMap.put(newPath.mPathName, newPath);
                    }
                }
            }
        }

        public VGroup() {
        }

        /* Getter and Setter */
        public float getRotation() {
            return mRotate;
        }

        public void setRotation(float rotation) {
            if (rotation != mRotate) {
                mRotate = rotation;
                updateLocalMatrix();
            }
        }

        public float getPivotX() {
            return mPivotX;
        }

        public void setPivotX(float pivotX) {
            if (pivotX != mPivotX) {
                mPivotX = pivotX;
                updateLocalMatrix();
            }
        }

        public float getPivotY() {
            return mPivotY;
        }

        public void setPivotY(float pivotY) {
            if (pivotY != mPivotY) {
                mPivotY = pivotY;
                updateLocalMatrix();
            }
        }

        public float getScaleX() {
            return mScaleX;
        }

        public void setScaleX(float scaleX) {
            if (scaleX != mScaleX) {
                mScaleX = scaleX;
                updateLocalMatrix();
            }
        }

        public float getScaleY() {
            return mScaleY;
        }

        public void setScaleY(float scaleY) {
            if (scaleY != mScaleY) {
                mScaleY = scaleY;
                updateLocalMatrix();
            }
        }

        public float getTranslateX() {
            return mTranslateX;
        }

        public void setTranslateX(float translateX) {
            if (translateX != mTranslateX) {
                mTranslateX = translateX;
                updateLocalMatrix();
            }
        }

        public float getTranslateY() {
            return mTranslateY;
        }

        public void setTranslateY(float translateY) {
            if (translateY != mTranslateY) {
                mTranslateY = translateY;
                updateLocalMatrix();
            }
        }

        public float getAlpha() {
            return mGroupAlpha;
        }

        public void setAlpha(float groupAlpha) {
            if (groupAlpha != mGroupAlpha) {
                mGroupAlpha = groupAlpha;
            }
        }

        public String getGroupName() {
            return mGroupName;
        }

        public Matrix getLocalMatrix() {
            return mLocalMatrix;
        }

        public boolean canApplyTheme() {
            return mThemeAttrs != null;
        }

        public void inflate(Resources res, AttributeSet attrs, Theme theme) {
            final TypedArray a = obtainAttributes(res, theme, attrs,
                    R.styleable.VectorDrawableGroup);
            updateStateFromTypedArray(a);
            a.recycle();
        }

        private void updateStateFromTypedArray(TypedArray a) {
            // Account for any configuration changes.
            mChangingConfigurations |= a.getChangingConfigurations();

            // Extract the theme attributes, if any.
            mThemeAttrs = a.extractThemeAttrs();

            mRotate = a.getFloat(R.styleable.VectorDrawableGroup_rotation, mRotate);
            mPivotX = a.getFloat(R.styleable.VectorDrawableGroup_pivotX, mPivotX);
            mPivotY = a.getFloat(R.styleable.VectorDrawableGroup_pivotY, mPivotY);
            mScaleX = a.getFloat(R.styleable.VectorDrawableGroup_scaleX, mScaleX);
            mScaleY = a.getFloat(R.styleable.VectorDrawableGroup_scaleY, mScaleY);
            mTranslateX = a.getFloat(R.styleable.VectorDrawableGroup_translateX, mTranslateX);
            mTranslateY = a.getFloat(R.styleable.VectorDrawableGroup_translateY, mTranslateY);
            mGroupAlpha = a.getFloat(R.styleable.VectorDrawableGroup_alpha, mGroupAlpha);

            final String groupName = a.getString(R.styleable.VectorDrawableGroup_name);
            if (groupName != null) {
                mGroupName = groupName;
            }

            updateLocalMatrix();
        }

        public void applyTheme(Theme t) {
            if (mThemeAttrs == null) {
                return;
            }

            final TypedArray a = t.resolveAttributes(mThemeAttrs, R.styleable.VectorDrawablePath);
            updateStateFromTypedArray(a);
            a.recycle();
        }

        private void updateLocalMatrix() {
            // The order we apply is the same as the
            // RenderNode.cpp::applyViewPropertyTransforms().
            mLocalMatrix.reset();
            mLocalMatrix.postTranslate(-mPivotX, -mPivotY);
            mLocalMatrix.postScale(mScaleX, mScaleY);
            mLocalMatrix.postRotate(mRotate, 0, 0);
            mLocalMatrix.postTranslate(mTranslateX + mPivotX, mTranslateY + mPivotY);
        }
    }

    private static class VPath {
        /////////////////////////////////////////////////////
        // Variables below need to be copied (deep copy if applicable) for mutation.
        private int[] mThemeAttrs;

        int mStrokeColor = 0;
        float mStrokeWidth = 0;
        float mStrokeOpacity = Float.NaN;
        int mFillColor = Color.BLACK;
        int mFillRule;
        float mFillOpacity = Float.NaN;
        float mTrimPathStart = 0;
        float mTrimPathEnd = 1;
        float mTrimPathOffset = 0;

        boolean mClip = false;
        Paint.Cap mStrokeLineCap = Paint.Cap.BUTT;
        Paint.Join mStrokeLineJoin = Paint.Join.MITER;
        float mStrokeMiterlimit = 4;

        private PathParser.PathDataNode[] mNodes = null;
        String mPathName;
        private int mChangingConfigurations;

        public VPath() {
            // Empty constructor.
        }

        public VPath(VPath copy) {
            mThemeAttrs = copy.mThemeAttrs;

            mStrokeColor = copy.mStrokeColor;
            mStrokeWidth = copy.mStrokeWidth;
            mStrokeOpacity = copy.mStrokeOpacity;
            mFillColor = copy.mFillColor;
            mFillRule = copy.mFillRule;
            mFillOpacity = copy.mFillOpacity;
            mTrimPathStart = copy.mTrimPathStart;
            mTrimPathEnd = copy.mTrimPathEnd;
            mTrimPathOffset = copy.mTrimPathOffset;

            mClip = copy.mClip;
            mStrokeLineCap = copy.mStrokeLineCap;
            mStrokeLineJoin = copy.mStrokeLineJoin;
            mStrokeMiterlimit = copy.mStrokeMiterlimit;

            mNodes = PathParser.deepCopyNodes(copy.mNodes);
            mPathName = copy.mPathName;
            mChangingConfigurations = copy.mChangingConfigurations;
        }

        public void toPath(Path path) {
            path.reset();
            if (mNodes != null) {
                PathParser.PathDataNode.nodesToPath(mNodes, path);
            }
        }

        public String getPathName() {
            return mPathName;
        }

        private Paint.Cap getStrokeLineCap(int id, Paint.Cap defValue) {
            switch (id) {
                case LINECAP_BUTT:
                    return Paint.Cap.BUTT;
                case LINECAP_ROUND:
                    return Paint.Cap.ROUND;
                case LINECAP_SQUARE:
                    return Paint.Cap.SQUARE;
                default:
                    return defValue;
            }
        }

        private Paint.Join getStrokeLineJoin(int id, Paint.Join defValue) {
            switch (id) {
                case LINEJOIN_MITER:
                    return Paint.Join.MITER;
                case LINEJOIN_ROUND:
                    return Paint.Join.ROUND;
                case LINEJOIN_BEVEL:
                    return Paint.Join.BEVEL;
                default:
                    return defValue;
            }
        }

        /* Setters and Getters, mostly used by animator from AnimatedVectorDrawable. */
        @SuppressWarnings("unused")
        public PathParser.PathDataNode[] getPathData() {
            return mNodes;
        }

        @SuppressWarnings("unused")
        public void setPathData(PathParser.PathDataNode[] nodes) {
            if (!PathParser.canMorph(mNodes, nodes)) {
                // This should not happen in the middle of animation.
                mNodes = PathParser.deepCopyNodes(nodes);
            } else {
                PathParser.updateNodes(mNodes, nodes);
            }
        }

        @SuppressWarnings("unused")
        int getStroke() {
            return mStrokeColor;
        }

        @SuppressWarnings("unused")
        void setStroke(int strokeColor) {
            mStrokeColor = strokeColor;
        }

        @SuppressWarnings("unused")
        float getStrokeWidth() {
            return mStrokeWidth;
        }

        @SuppressWarnings("unused")
        void setStrokeWidth(float strokeWidth) {
            mStrokeWidth = strokeWidth;
        }

        @SuppressWarnings("unused")
        float getStrokeOpacity() {
            return mStrokeOpacity;
        }

        @SuppressWarnings("unused")
        void setStrokeOpacity(float strokeOpacity) {
            mStrokeOpacity = strokeOpacity;
        }

        @SuppressWarnings("unused")
        int getFill() {
            return mFillColor;
        }

        @SuppressWarnings("unused")
        void setFill(int fillColor) {
            mFillColor = fillColor;
        }

        @SuppressWarnings("unused")
        float getFillOpacity() {
            return mFillOpacity;
        }

        @SuppressWarnings("unused")
        void setFillOpacity(float fillOpacity) {
            mFillOpacity = fillOpacity;
        }

        @SuppressWarnings("unused")
        float getTrimPathStart() {
            return mTrimPathStart;
        }

        @SuppressWarnings("unused")
        void setTrimPathStart(float trimPathStart) {
            mTrimPathStart = trimPathStart;
        }

        @SuppressWarnings("unused")
        float getTrimPathEnd() {
            return mTrimPathEnd;
        }

        @SuppressWarnings("unused")
        void setTrimPathEnd(float trimPathEnd) {
            mTrimPathEnd = trimPathEnd;
        }

        @SuppressWarnings("unused")
        float getTrimPathOffset() {
            return mTrimPathOffset;
        }

        @SuppressWarnings("unused")
        void setTrimPathOffset(float trimPathOffset) {
            mTrimPathOffset = trimPathOffset;
        }

        public boolean canApplyTheme() {
            return mThemeAttrs != null;
        }

        public void inflate(Resources r, AttributeSet attrs, Theme theme) {
            final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.VectorDrawablePath);
            updateStateFromTypedArray(a);
            a.recycle();
        }

        private void updateStateFromTypedArray(TypedArray a) {
            // Account for any configuration changes.
            mChangingConfigurations |= a.getChangingConfigurations();

            // Extract the theme attributes, if any.
            mThemeAttrs = a.extractThemeAttrs();

            mClip = a.getBoolean(R.styleable.VectorDrawablePath_clipToPath, mClip);

            mPathName = a.getString(R.styleable.VectorDrawablePath_name);
            mNodes = PathParser.createNodesFromPathData(a.getString(
                    R.styleable.VectorDrawablePath_pathData));

            mFillColor = a.getColor(R.styleable.VectorDrawablePath_fill, mFillColor);
            mFillOpacity = a.getFloat(R.styleable.VectorDrawablePath_fillOpacity, mFillOpacity);
            mStrokeLineCap = getStrokeLineCap(a.getInt(
                    R.styleable.VectorDrawablePath_strokeLineCap, -1), mStrokeLineCap);
            mStrokeLineJoin = getStrokeLineJoin(a.getInt(
                    R.styleable.VectorDrawablePath_strokeLineJoin, -1), mStrokeLineJoin);
            mStrokeMiterlimit = a.getFloat(
                    R.styleable.VectorDrawablePath_strokeMiterLimit, mStrokeMiterlimit);
            mStrokeColor = a.getColor(R.styleable.VectorDrawablePath_stroke, mStrokeColor);
            mStrokeOpacity = a.getFloat(
                    R.styleable.VectorDrawablePath_strokeOpacity, mStrokeOpacity);
            mStrokeWidth = a.getFloat(R.styleable.VectorDrawablePath_strokeWidth, mStrokeWidth);
            mTrimPathEnd = a.getFloat(R.styleable.VectorDrawablePath_trimPathEnd, mTrimPathEnd);
            mTrimPathOffset = a.getFloat(
                    R.styleable.VectorDrawablePath_trimPathOffset, mTrimPathOffset);
            mTrimPathStart = a.getFloat(
                    R.styleable.VectorDrawablePath_trimPathStart, mTrimPathStart);

            updateColorAlphas();
        }

        public void applyTheme(Theme t) {
            if (mThemeAttrs == null) {
                return;
            }

            final TypedArray a = t.resolveAttributes(mThemeAttrs, R.styleable.VectorDrawablePath);
            updateStateFromTypedArray(a);
            a.recycle();
        }

        private void updateColorAlphas() {
            if (!Float.isNaN(mFillOpacity)) {
                mFillColor = applyAlpha(mFillColor, mFillOpacity);
            }

            if (!Float.isNaN(mStrokeOpacity)) {
                mStrokeColor = applyAlpha(mStrokeColor, mStrokeOpacity);
            }
        }
    }
}
