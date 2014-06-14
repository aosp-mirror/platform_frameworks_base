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

import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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

    private final VectorDrawableState mVectorState;

    private final ArrayMap<String, Object> mVGTargetsMap = new ArrayMap<String, Object>();

    public VectorDrawable() {
        mVectorState = new VectorDrawableState(null);
    }

    private VectorDrawable(VectorDrawableState state, Resources res, Theme theme) {
        mVectorState = new VectorDrawableState(state);

        if (theme != null && canApplyTheme()) {
            applyTheme(theme);
        }
    }

    Object getTargetByName(String name) {
        return mVGTargetsMap.get(name);
    }

    @Override
    public ConstantState getConstantState() {
        return mVectorState;
    }

    @Override
    public void draw(Canvas canvas) {
        final int saveCount = canvas.save();
        final Rect bounds = getBounds();
        canvas.translate(bounds.left, bounds.top);
        mVectorState.mVPathRenderer.draw(canvas, bounds.width(), bounds.height());
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
        mVectorState.mVPathRenderer.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /**
     * Sets padding for this shape, defined by a Rect object. Define the padding
     * in the Rect object as: left, top, right, bottom.
     */
    public void setPadding(Rect padding) {
        setPadding(padding.left, padding.top, padding.right, padding.bottom);
    }

    /**
     * Sets padding for the shape.
     *
     * @param left padding for the left side (in pixels)
     * @param top padding for the top (in pixels)
     * @param right padding for the right side (in pixels)
     * @param bottom padding for the bottom (in pixels)
     */
    public void setPadding(int left, int top, int right, int bottom) {
        if ((left | top | right | bottom) == 0) {
            mVectorState.mPadding = null;
        } else {
            if (mVectorState.mPadding == null) {
                mVectorState.mPadding = new Rect();
            }
            mVectorState.mPadding.set(left, top, right, bottom);
        }
        invalidateSelf();
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
    public boolean getPadding(Rect padding) {
        if (mVectorState.mPadding != null) {
            padding.set(mVectorState.mPadding);
            return true;
        } else {
            return super.getPadding(padding);
        }
    }

    @Override
    public void inflate(Resources res, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        final VPathRenderer p = inflateInternal(res, parser, attrs, theme);
        setPathRenderer(p);
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

    private VPathRenderer inflateInternal(Resources res, XmlPullParser parser, AttributeSet attrs,
            Theme theme) throws XmlPullParserException, IOException {
        final VPathRenderer pathRenderer = new VPathRenderer();

        boolean noSizeTag = true;
        boolean noViewportTag = true;
        boolean noGroupTag = true;
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
                    currentGroup.add(path);
                    if (path.getPathName() != null) {
                        mVGTargetsMap.put(path.getPathName(), path);
                    }
                    noPathTag = false;
                } else if (SHAPE_SIZE.equals(tagName)) {
                    pathRenderer.parseSize(res, attrs);
                    noSizeTag = false;
                } else if (SHAPE_VIEWPORT.equals(tagName)) {
                    pathRenderer.parseViewport(res, attrs);
                    noViewportTag = false;
                } else if (SHAPE_GROUP.equals(tagName)) {
                    VGroup newChildGroup = new VGroup();
                    newChildGroup.inflate(res, attrs, theme);
                    currentGroup.mChildGroupList.add(newChildGroup);
                    groupStack.push(newChildGroup);
                    if (newChildGroup.getGroupName() != null) {
                        mVGTargetsMap.put(newChildGroup.getGroupName(), newChildGroup);
                    }
                    noGroupTag = false;
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

        return pathRenderer;
    }

    private void printGroupTree(VGroup currentGroup, int level) {
        String indent = "";
        for (int i = 0 ; i < level ; i++) {
            indent += "    ";
        }
        // Print the current node
        Log.v(LOGTAG, indent + "current group is :" +  currentGroup.getGroupName()
                + " rotation is " + currentGroup.mRotate);
        Log.v(LOGTAG, indent + "matrix is :" +  currentGroup.getLocalMatrix().toString());
        // Then print all the children
        for (int i = 0 ; i < currentGroup.mChildGroupList.size(); i++) {
            printGroupTree(currentGroup.mChildGroupList.get(i), level + 1);
        }
    }

    private void setPathRenderer(VPathRenderer pathRenderer) {
        mVectorState.mVPathRenderer = pathRenderer;
    }

    private static class VectorDrawableState extends ConstantState {
        int mChangingConfigurations;
        VPathRenderer mVPathRenderer;
        Rect mPadding;

        public VectorDrawableState(VectorDrawableState copy) {
            if (copy != null) {
                mChangingConfigurations = copy.mChangingConfigurations;
                // TODO: Make sure the constant state are handled correctly.
                mVPathRenderer = new VPathRenderer(copy.mVPathRenderer);
                mPadding = new Rect(copy.mPadding);
            }
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
        private final VGroup mRootGroup;

        private final Path mPath = new Path();
        private final Path mRenderPath = new Path();
        private static final Matrix IDENTITY_MATRIX = new Matrix();

        private Paint mStrokePaint;
        private Paint mFillPaint;
        private ColorFilter mColorFilter;
        private PathMeasure mPathMeasure;

        private float mBaseWidth = 0;
        private float mBaseHeight = 0;
        private float mViewportWidth = 0;
        private float mViewportHeight = 0;
        private int mRootAlpha = 0xFF;

        private final Matrix mFinalPathMatrix = new Matrix();

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
            mRootGroup = copy.mRootGroup;
            mBaseWidth = copy.mBaseWidth;
            mBaseHeight = copy.mBaseHeight;
            mViewportWidth = copy.mViewportHeight;
            mViewportHeight = copy.mViewportHeight;
        }

        public boolean canApplyTheme() {
            // If one of the paths can apply theme, then return true;
            return recursiveCanApplyTheme(mRootGroup);
        }

        private boolean recursiveCanApplyTheme(VGroup currentGroup) {
            // We can do a tree traverse here, if there is one path return true,
            // then we return true for the whole tree.
            final ArrayList<VPath> paths = currentGroup.mPathList;
            for (int j = paths.size() - 1; j >= 0; j--) {
                final VPath path = paths.get(j);
                if (path.canApplyTheme()) {
                    return true;
                }
            }

            final ArrayList<VGroup> childGroups = currentGroup.mChildGroupList;

            for (int i = 0; i < childGroups.size(); i++) {
                VGroup childGroup = childGroups.get(i);
                if (childGroup.canApplyTheme()
                        || recursiveCanApplyTheme(childGroup)) {
                    return true;
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
            final ArrayList<VPath> paths = currentGroup.mPathList;
            for (int j = paths.size() - 1; j >= 0; j--) {
                final VPath path = paths.get(j);
                if (path.canApplyTheme()) {
                    path.applyTheme(t);
                }
            }

            final ArrayList<VGroup> childGroups = currentGroup.mChildGroupList;

            for (int i = 0; i < childGroups.size(); i++) {
                VGroup childGroup = childGroups.get(i);
                if (childGroup.canApplyTheme()) {
                    childGroup.applyTheme(t);
                }
                recursiveApplyTheme(childGroup, t);
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
            drawPath(currentGroup, stackedAlpha, canvas, w, h);
            // Draw the group tree in post order.
            for (int i = 0 ; i < currentGroup.mChildGroupList.size(); i++) {
                drawGroupTree(currentGroup.mChildGroupList.get(i),
                        currentGroup.mStackedMatrix, stackedAlpha, canvas, w, h);
            }
        }

        public void draw(Canvas canvas, int w, int h) {
            // Travese the tree in pre-order to draw.
            drawGroupTree(mRootGroup, IDENTITY_MATRIX, ((float) mRootAlpha) / 0xFF, canvas, w, h);
        }

        private void drawPath(VGroup vGroup, float stackedAlpha, Canvas canvas, int w, int h) {
            final float scale = Math.min(h / mViewportHeight, w / mViewportWidth);

            mFinalPathMatrix.set(vGroup.mStackedMatrix);
            mFinalPathMatrix.postScale(scale, scale, mViewportWidth / 2f, mViewportHeight / 2f);
            mFinalPathMatrix.postTranslate(w / 2f - mViewportWidth / 2f, h / 2f - mViewportHeight / 2f);

            ArrayList<VPath> paths = vGroup.getPaths();
            for (int i = 0; i < paths.size(); i++) {
                VPath vPath = paths.get(i);
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

                        strokePaint.setStrokeMiter(vPath.mStrokeMiterlimit * scale);

                        strokePaint.setColor(applyAlpha(vPath.mStrokeColor, stackedAlpha));
                        strokePaint.setStrokeWidth(vPath.mStrokeWidth * scale);
                        canvas.drawPath(mRenderPath, strokePaint);
                    }
                }
            }
        }

        private void parseViewport(Resources r, AttributeSet attrs)
                throws XmlPullParserException {
            final TypedArray a = r.obtainAttributes(attrs, R.styleable.VectorDrawableViewport);
            mViewportWidth = a.getFloat(R.styleable.VectorDrawableViewport_viewportWidth, mViewportWidth);
            mViewportHeight = a.getFloat(R.styleable.VectorDrawableViewport_viewportHeight, mViewportHeight);

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
                throws XmlPullParserException  {
            final TypedArray a = r.obtainAttributes(attrs, R.styleable.VectorDrawableSize);
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
        private final ArrayList<VPath> mPathList = new ArrayList<VPath>();
        private final ArrayList<VGroup> mChildGroupList = new ArrayList<VGroup>();

        private float mRotate = 0;
        private float mPivotX = 0;
        private float mPivotY = 0;
        private float mScaleX = 1;
        private float mScaleY = 1;
        private float mTranslateX = 0;
        private float mTranslateY = 0;
        private float mGroupAlpha = 1;

        // mLocalMatrix is parsed from the XML.
        private final Matrix mLocalMatrix = new Matrix();
        // mStackedMatrix is only used when drawing, it combines all the
        // parents' local matrices with the current one.
        private final Matrix mStackedMatrix = new Matrix();

        private int[] mThemeAttrs;

        private String mGroupName = null;

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

        public void add(VPath path) {
            mPathList.add(path);
         }

        public boolean canApplyTheme() {
            return mThemeAttrs != null;
        }

        public void applyTheme(Theme t) {
            if (mThemeAttrs == null) {
                return;
            }

            final TypedArray a = t.resolveAttributes(
                    mThemeAttrs, R.styleable.VectorDrawablePath);

            mRotate = a.getFloat(R.styleable.VectorDrawableGroup_rotation, mRotate);
            mPivotX = a.getFloat(R.styleable.VectorDrawableGroup_pivotX, mPivotX);
            mPivotY = a.getFloat(R.styleable.VectorDrawableGroup_pivotY, mPivotY);
            mScaleX = a.getFloat(R.styleable.VectorDrawableGroup_scaleX, mScaleX);
            mScaleY = a.getFloat(R.styleable.VectorDrawableGroup_scaleY, mScaleY);
            mTranslateX = a.getFloat(R.styleable.VectorDrawableGroup_translateX, mTranslateX);
            mTranslateY = a.getFloat(R.styleable.VectorDrawableGroup_translateY, mTranslateY);
            mGroupAlpha = a.getFloat(R.styleable.VectorDrawableGroup_alpha, mGroupAlpha);
            updateLocalMatrix();
            if (a.hasValue(R.styleable.VectorDrawableGroup_name)) {
                mGroupName = a.getString(R.styleable.VectorDrawableGroup_name);
            }
            a.recycle();
        }

        public void inflate(Resources res, AttributeSet attrs, Theme theme) {
            final TypedArray a = obtainAttributes(res, theme, attrs, R.styleable.VectorDrawableGroup);
            final int[] themeAttrs = a.extractThemeAttrs();

            mThemeAttrs = themeAttrs;
            // NOTE: The set of attributes loaded here MUST match the
            // set of attributes loaded in applyTheme.

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawableGroup_rotation] == 0) {
                mRotate = a.getFloat(R.styleable.VectorDrawableGroup_rotation, mRotate);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawableGroup_pivotX] == 0) {
                mPivotX = a.getFloat(R.styleable.VectorDrawableGroup_pivotX, mPivotX);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawableGroup_pivotY] == 0) {
                mPivotY = a.getFloat(R.styleable.VectorDrawableGroup_pivotY, mPivotY);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawableGroup_scaleX] == 0) {
                mScaleX = a.getFloat(R.styleable.VectorDrawableGroup_scaleX, mScaleX);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawableGroup_scaleY] == 0) {
                mScaleY = a.getFloat(R.styleable.VectorDrawableGroup_scaleY, mScaleY);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawableGroup_translateX] == 0) {
                mTranslateX = a.getFloat(R.styleable.VectorDrawableGroup_translateX, mTranslateX);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawableGroup_translateY] == 0) {
                mTranslateY = a.getFloat(R.styleable.VectorDrawableGroup_translateY, mTranslateY);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawableGroup_name] == 0) {
                mGroupName = a.getString(R.styleable.VectorDrawableGroup_name);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawableGroup_alpha] == 0) {
                mGroupAlpha = a.getFloat(R.styleable.VectorDrawableGroup_alpha, mGroupAlpha);
            }

            updateLocalMatrix();
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

        /**
         * Must return in order of adding
         * @return ordered list of paths
         */
        public ArrayList<VPath> getPaths() {
            return mPathList;
        }

    }

    static class VPath {
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

        private VNode[] mNode = null;
        private String mPathName;

        public VPath() {
            // Empty constructor.
        }

        public void toPath(Path path) {
            path.reset();
            if (mNode != null) {
                VNode.createPath(mNode, path);
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

        /* Setters and Getters */
        int getStroke() {
            return mStrokeColor;
        }

        void setStroke(int strokeColor) {
            mStrokeColor = strokeColor;
        }

        float getStrokeWidth() {
            return mStrokeWidth;
        }

        void setStrokeWidth(float strokeWidth) {
            mStrokeWidth = strokeWidth;
        }

        float getStrokeOpacity() {
            return mStrokeOpacity;
        }

        void setStrokeOpacity(float strokeOpacity) {
            mStrokeOpacity = strokeOpacity;
        }

        int getFill() {
            return mFillColor;
        }

        void setFill(int fillColor) {
            mFillColor = fillColor;
        }

        float getFillOpacity() {
            return mFillOpacity;
        }

        void setFillOpacity(float fillOpacity) {
            mFillOpacity = fillOpacity;
        }

        float getTrimPathStart() {
            return mTrimPathStart;
        }

        void setTrimPathStart(float trimPathStart) {
            mTrimPathStart = trimPathStart;
        }

        float getTrimPathEnd() {
            return mTrimPathEnd;
        }

        void setTrimPathEnd(float trimPathEnd) {
            mTrimPathEnd = trimPathEnd;
        }

        float getTrimPathOffset() {
            return mTrimPathOffset;
        }

        void setTrimPathOffset(float trimPathOffset) {
            mTrimPathOffset = trimPathOffset;
        }

        public void inflate(Resources r, AttributeSet attrs, Theme theme) {
            final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.VectorDrawablePath);
            final int[] themeAttrs = a.extractThemeAttrs();
            mThemeAttrs = themeAttrs;

            // NOTE: The set of attributes loaded here MUST match the
            // set of attributes loaded in applyTheme.
            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawablePath_clipToPath] == 0) {
                mClip = a.getBoolean(R.styleable.VectorDrawablePath_clipToPath, mClip);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawablePath_name] == 0) {
                mPathName = a.getString(R.styleable.VectorDrawablePath_name);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawablePath_pathData] == 0) {
                mNode = parsePath(a.getString(R.styleable.VectorDrawablePath_pathData));
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawablePath_fill] == 0) {
                mFillColor = a.getColor(R.styleable.VectorDrawablePath_fill, mFillColor);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawablePath_fillOpacity] == 0) {
                mFillOpacity = a.getFloat(R.styleable.VectorDrawablePath_fillOpacity, mFillOpacity);
            }

            if (themeAttrs == null
                    || themeAttrs[R.styleable.VectorDrawablePath_strokeLineCap] == 0) {
                mStrokeLineCap = getStrokeLineCap(
                        a.getInt(R.styleable.VectorDrawablePath_strokeLineCap, -1), mStrokeLineCap);
            }

            if (themeAttrs == null
                    || themeAttrs[R.styleable.VectorDrawablePath_strokeLineJoin] == 0) {
                mStrokeLineJoin = getStrokeLineJoin(
                        a.getInt(R.styleable.VectorDrawablePath_strokeLineJoin, -1), mStrokeLineJoin);
            }

            if (themeAttrs == null
                    || themeAttrs[R.styleable.VectorDrawablePath_strokeMiterLimit] == 0) {
                mStrokeMiterlimit = a.getFloat(
                        R.styleable.VectorDrawablePath_strokeMiterLimit, mStrokeMiterlimit);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawablePath_stroke] == 0) {
                mStrokeColor = a.getColor(R.styleable.VectorDrawablePath_stroke, mStrokeColor);
            }

            if (themeAttrs == null
                    || themeAttrs[R.styleable.VectorDrawablePath_strokeOpacity] == 0) {
                mStrokeOpacity = a.getFloat(
                        R.styleable.VectorDrawablePath_strokeOpacity, mStrokeOpacity);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawablePath_strokeWidth] == 0) {
                mStrokeWidth = a.getFloat(R.styleable.VectorDrawablePath_strokeWidth, mStrokeWidth);
            }

            if (themeAttrs == null || themeAttrs[R.styleable.VectorDrawablePath_trimPathEnd] == 0) {
                mTrimPathEnd = a.getFloat(R.styleable.VectorDrawablePath_trimPathEnd, mTrimPathEnd);
            }

            if (themeAttrs == null
                    || themeAttrs[R.styleable.VectorDrawablePath_trimPathOffset] == 0) {
                mTrimPathOffset = a.getFloat(
                        R.styleable.VectorDrawablePath_trimPathOffset, mTrimPathOffset);
            }

            if (themeAttrs == null
                    || themeAttrs[R.styleable.VectorDrawablePath_trimPathStart] == 0) {
                mTrimPathStart = a.getFloat(
                        R.styleable.VectorDrawablePath_trimPathStart, mTrimPathStart);
            }

            updateColorAlphas();

            a.recycle();
        }

        public boolean canApplyTheme() {
            return mThemeAttrs != null;
        }

        public void applyTheme(Theme t) {
            if (mThemeAttrs == null) {
                return;
            }

            final TypedArray a = t.resolveAttributes(
                    mThemeAttrs, R.styleable.VectorDrawablePath);

            mClip = a.getBoolean(R.styleable.VectorDrawablePath_clipToPath, mClip);

            if (a.hasValue(R.styleable.VectorDrawablePath_name)) {
                mPathName = a.getString(R.styleable.VectorDrawablePath_name);
            }

            if (a.hasValue(R.styleable.VectorDrawablePath_pathData)) {
                mNode = parsePath(a.getString(R.styleable.VectorDrawablePath_pathData));
            }

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

        private static int nextStart(String s, int end) {
            char c;

            while (end < s.length()) {
                c = s.charAt(end);
                if (((c - 'A') * (c - 'Z') <= 0) || (((c - 'a') * (c - 'z') <= 0))) {
                    return end;
                }
                end++;
            }
            return end;
        }

        private void addNode(ArrayList<VectorDrawable.VNode> list, char cmd, float[] val) {
            list.add(new VectorDrawable.VNode(cmd, val));
        }

        /**
         * parse the floats in the string
         * this is an optimized version of
         * parseFloat(s.split(",|\\s"));
         *
         * @param s the string containing a command and list of floats
         * @return array of floats
         */
        private static float[] getFloats(String s) {
            if (s.charAt(0) == 'z' | s.charAt(0) == 'Z') {
                return new float[0];
            }
            try {
                float[] tmp = new float[s.length()];
                int count = 0;
                int pos = 1, end;
                while ((end = extract(s, pos)) >= 0) {
                    if (pos < end) {
                        tmp[count++] = Float.parseFloat(s.substring(pos, end));
                    }
                    pos = end + 1;
                }
                // handle the final float if there is one
                if (pos < s.length()) {
                    tmp[count++] = Float.parseFloat(s.substring(pos, s.length()));
                }
                return Arrays.copyOf(tmp, count);
            } catch (NumberFormatException e){
                Log.e(LOGTAG,"error in parsing \""+s+"\"");
                throw e;
            }
        }

        /**
         * calculate the position of the next comma or space
         * @param s the string to search
         * @param start the position to start searching
         * @return the position of the next comma or space or -1 if none found
         */
        private static int extract(String s, int start) {
            int space = s.indexOf(' ', start);
            int comma = s.indexOf(',', start);
            if (space == -1) {
                return comma;
            }
            if (comma == -1) {
                return space;
            }
            return (comma > space) ? space : comma;
        }

        private VectorDrawable.VNode[] parsePath(String value) {
            int start = 0;
            int end = 1;

            ArrayList<VectorDrawable.VNode> list = new ArrayList<VectorDrawable.VNode>();
            while (end < value.length()) {
                end = nextStart(value, end);
                String s = value.substring(start, end);
                float[] val = getFloats(s);
                addNode(list, s.charAt(0), val);

                start = end;
                end++;
            }
            if ((end - start) == 1 && start < value.length()) {

                addNode(list, value.charAt(start), new float[0]);
            }
            return list.toArray(new VectorDrawable.VNode[list.size()]);
        }
    }

    private static class VNode {
        private char mType;
        private float[] mParams;

        public VNode(char type, float[] params) {
            mType = type;
            mParams = params;
        }

        public VNode(VNode n) {
            mType = n.mType;
            mParams = Arrays.copyOf(n.mParams, n.mParams.length);
        }

        public static void createPath(VNode[] node, Path path) {
            float[] current = new float[4];
            char previousCommand = 'm';
            for (int i = 0; i < node.length; i++) {
                addCommand(path, current, previousCommand, node[i].mType, node[i].mParams);
                previousCommand = node[i].mType;
            }
        }

        private static void addCommand(Path path, float[] current,
                char previousCmd, char cmd, float[] val) {

            int incr = 2;
            float currentX = current[0];
            float currentY = current[1];
            float ctrlPointX = current[2];
            float ctrlPointY = current[3];
            float reflectiveCtrlPointX;
            float reflectiveCtrlPointY;

            switch (cmd) {
                case 'z':
                case 'Z':
                    path.close();
                    return;
                case 'm':
                case 'M':
                case 'l':
                case 'L':
                case 't':
                case 'T':
                    incr = 2;
                    break;
                case 'h':
                case 'H':
                case 'v':
                case 'V':
                    incr = 1;
                    break;
                case 'c':
                case 'C':
                    incr = 6;
                    break;
                case 's':
                case 'S':
                case 'q':
                case 'Q':
                    incr = 4;
                    break;
                case 'a':
                case 'A':
                    incr = 7;
                    break;
            }
            for (int k = 0; k < val.length; k += incr) {
                switch (cmd) {
                    case 'm': // moveto - Start a new sub-path (relative)
                        path.rMoveTo(val[k + 0], val[k + 1]);
                        currentX += val[k + 0];
                        currentY += val[k + 1];
                        break;
                    case 'M': // moveto - Start a new sub-path
                        path.moveTo(val[k + 0], val[k + 1]);
                        currentX = val[k + 0];
                        currentY = val[k + 1];
                        break;
                    case 'l': // lineto - Draw a line from the current point (relative)
                        path.rLineTo(val[k + 0], val[k + 1]);
                        currentX += val[k + 0];
                        currentY += val[k + 1];
                        break;
                    case 'L': // lineto - Draw a line from the current point
                        path.lineTo(val[k + 0], val[k + 1]);
                        currentX = val[k + 0];
                        currentY = val[k + 1];
                        break;
                    case 'z': // closepath - Close the current subpath
                    case 'Z': // closepath - Close the current subpath
                        path.close();
                        break;
                    case 'h': // horizontal lineto - Draws a horizontal line (relative)
                        path.rLineTo(val[k + 0], 0);
                        currentX += val[k + 0];
                        break;
                    case 'H': // horizontal lineto - Draws a horizontal line
                        path.lineTo(val[k + 0], currentY);
                        currentX = val[k + 0];
                        break;
                    case 'v': // vertical lineto - Draws a vertical line from the current point (r)
                        path.rLineTo(0, val[k + 0]);
                        currentY += val[k + 0];
                        break;
                    case 'V': // vertical lineto - Draws a vertical line from the current point
                        path.lineTo(currentX, val[k + 0]);
                        currentY = val[k + 0];
                        break;
                    case 'c': // curveto - Draws a cubic Bézier curve (relative)
                        path.rCubicTo(val[k + 0], val[k + 1], val[k + 2], val[k + 3],
                                val[k + 4], val[k + 5]);

                        ctrlPointX = currentX + val[k + 2];
                        ctrlPointY = currentY + val[k + 3];
                        currentX += val[k + 4];
                        currentY += val[k + 5];

                        break;
                    case 'C': // curveto - Draws a cubic Bézier curve
                        path.cubicTo(val[k + 0], val[k + 1], val[k + 2], val[k + 3],
                                val[k + 4], val[k + 5]);
                        currentX = val[k + 4];
                        currentY = val[k + 5];
                        ctrlPointX = val[k + 2];
                        ctrlPointY = val[k + 3];
                        break;
                    case 's': // smooth curveto - Draws a cubic Bézier curve (reflective cp)
                        reflectiveCtrlPointX = 0;
                        reflectiveCtrlPointY = 0;
                        if (previousCmd == 'c' || previousCmd == 's'
                                || previousCmd == 'C' || previousCmd == 'S') {
                            reflectiveCtrlPointX = currentX - ctrlPointX;
                            reflectiveCtrlPointY = currentY - ctrlPointY;
                        }
                        path.rCubicTo(reflectiveCtrlPointX, reflectiveCtrlPointY,
                                val[k + 0], val[k + 1],
                                val[k + 2], val[k + 3]);

                        ctrlPointX = currentX + val[k + 0];
                        ctrlPointY = currentY + val[k + 1];
                        currentX += val[k + 2];
                        currentY += val[k + 3];
                        break;
                    case 'S': // shorthand/smooth curveto Draws a cubic Bézier curve(reflective cp)
                        reflectiveCtrlPointX = currentX;
                        reflectiveCtrlPointY = currentY;
                        if (previousCmd == 'c' || previousCmd == 's'
                                || previousCmd == 'C' || previousCmd == 'S') {
                            reflectiveCtrlPointX = 2 * currentX - ctrlPointX;
                            reflectiveCtrlPointY = 2 * currentY - ctrlPointY;
                        }
                        path.cubicTo(reflectiveCtrlPointX, reflectiveCtrlPointY,
                                val[k + 0], val[k + 1], val[k + 2], val[k + 3]);
                        ctrlPointX = val[k + 0];
                        ctrlPointY = val[k + 1];
                        currentX = val[k + 2];
                        currentY = val[k + 3];
                        break;
                    case 'q': // Draws a quadratic Bézier (relative)
                        path.rQuadTo(val[k + 0], val[k + 1], val[k + 2], val[k + 3]);
                        ctrlPointX = currentX + val[k + 0];
                        ctrlPointY = currentY + val[k + 1];
                        currentX += val[k + 2];
                        currentY += val[k + 3];
                        break;
                    case 'Q': // Draws a quadratic Bézier
                        path.quadTo(val[k + 0], val[k + 1], val[k + 2], val[k + 3]);
                        ctrlPointX = val[k + 0];
                        ctrlPointY = val[k + 1];
                        currentX = val[k + 2];
                        currentY = val[k + 3];
                        break;
                    case 't': // Draws a quadratic Bézier curve(reflective control point)(relative)
                        reflectiveCtrlPointX = 0;
                        reflectiveCtrlPointY = 0;
                        if (previousCmd == 'q' || previousCmd == 't'
                                || previousCmd == 'Q' || previousCmd == 'T') {
                            reflectiveCtrlPointX = currentX - ctrlPointX;
                            reflectiveCtrlPointY = currentY - ctrlPointY;
                        }
                        path.rQuadTo(reflectiveCtrlPointX, reflectiveCtrlPointY,
                                val[k + 0], val[k + 1]);
                        ctrlPointX = currentX + reflectiveCtrlPointX;
                        ctrlPointY = currentY + reflectiveCtrlPointY;
                        currentX += val[k + 0];
                        currentY += val[k + 1];
                        break;
                    case 'T': // Draws a quadratic Bézier curve (reflective control point)
                        reflectiveCtrlPointX = currentX;
                        reflectiveCtrlPointY = currentY;
                        if (previousCmd == 'q' || previousCmd == 't'
                                || previousCmd == 'Q' || previousCmd == 'T') {
                            reflectiveCtrlPointX = 2 * currentX - ctrlPointX;
                            reflectiveCtrlPointY = 2 * currentY - ctrlPointY;
                        }
                        path.quadTo(reflectiveCtrlPointX, reflectiveCtrlPointY,
                                val[k + 0], val[k + 1]);
                        ctrlPointX = reflectiveCtrlPointX;
                        ctrlPointY = reflectiveCtrlPointY;
                        currentX = val[k + 0];
                        currentY = val[k + 1];
                        break;
                    case 'a': // Draws an elliptical arc
                        // (rx ry x-axis-rotation large-arc-flag sweep-flag x y)
                        drawArc(path,
                                currentX,
                                currentY,
                                val[k + 5] + currentX,
                                val[k + 6] + currentY,
                                val[k + 0],
                                val[k + 1],
                                val[k + 2],
                                val[k + 3] != 0,
                                val[k + 4] != 0);
                        currentX += val[k + 5];
                        currentY += val[k + 6];
                        ctrlPointX = currentX;
                        ctrlPointY = currentY;
                        break;
                    case 'A': // Draws an elliptical arc
                        drawArc(path,
                                currentX,
                                currentY,
                                val[k + 5],
                                val[k + 6],
                                val[k + 0],
                                val[k + 1],
                                val[k + 2],
                                val[k + 3] != 0,
                                val[k + 4] != 0);
                        currentX = val[k + 5];
                        currentY = val[k + 6];
                        ctrlPointX = currentX;
                        ctrlPointY = currentY;
                        break;
                }
                previousCmd = cmd;
            }
            current[0] = currentX;
            current[1] = currentY;
            current[2] = ctrlPointX;
            current[3] = ctrlPointY;
        }

        private static void drawArc(Path p,
                float x0,
                float y0,
                float x1,
                float y1,
                float a,
                float b,
                float theta,
                boolean isMoreThanHalf,
                boolean isPositiveArc) {

            /* Convert rotation angle from degrees to radians */
            double thetaD = Math.toRadians(theta);
            /* Pre-compute rotation matrix entries */
            double cosTheta = Math.cos(thetaD);
            double sinTheta = Math.sin(thetaD);
            /* Transform (x0, y0) and (x1, y1) into unit space */
            /* using (inverse) rotation, followed by (inverse) scale */
            double x0p = (x0 * cosTheta + y0 * sinTheta) / a;
            double y0p = (-x0 * sinTheta + y0 * cosTheta) / b;
            double x1p = (x1 * cosTheta + y1 * sinTheta) / a;
            double y1p = (-x1 * sinTheta + y1 * cosTheta) / b;

            /* Compute differences and averages */
            double dx = x0p - x1p;
            double dy = y0p - y1p;
            double xm = (x0p + x1p) / 2;
            double ym = (y0p + y1p) / 2;
            /* Solve for intersecting unit circles */
            double dsq = dx * dx + dy * dy;
            if (dsq == 0.0) {
                Log.w(LOGTAG, " Points are coincident");
                return; /* Points are coincident */
            }
            double disc = 1.0 / dsq - 1.0 / 4.0;
            if (disc < 0.0) {
                Log.w(LOGTAG, "Points are too far apart " + dsq);
                float adjust = (float) (Math.sqrt(dsq) / 1.99999);
                drawArc(p, x0, y0, x1, y1, a * adjust,
                        b * adjust, theta, isMoreThanHalf, isPositiveArc);
                return; /* Points are too far apart */
            }
            double s = Math.sqrt(disc);
            double sdx = s * dx;
            double sdy = s * dy;
            double cx;
            double cy;
            if (isMoreThanHalf == isPositiveArc) {
                cx = xm - sdy;
                cy = ym + sdx;
            } else {
                cx = xm + sdy;
                cy = ym - sdx;
            }

            double eta0 = Math.atan2((y0p - cy), (x0p - cx));

            double eta1 = Math.atan2((y1p - cy), (x1p - cx));

            double sweep = (eta1 - eta0);
            if (isPositiveArc != (sweep >= 0)) {
                if (sweep > 0) {
                    sweep -= 2 * Math.PI;
                } else {
                    sweep += 2 * Math.PI;
                }
            }

            cx *= a;
            cy *= b;
            double tcx = cx;
            cx = cx * cosTheta - cy * sinTheta;
            cy = tcx * sinTheta + cy * cosTheta;

            arcToBezier(p, cx, cy, a, b, x0, y0, thetaD, eta0, sweep);
        }

        /**
         * Converts an arc to cubic Bezier segments and records them in p.
         *
         * @param p The target for the cubic Bezier segments
         * @param cx The x coordinate center of the ellipse
         * @param cy The y coordinate center of the ellipse
         * @param a The radius of the ellipse in the horizontal direction
         * @param b The radius of the ellipse in the vertical direction
         * @param e1x E(eta1) x coordinate of the starting point of the arc
         * @param e1y E(eta2) y coordinate of the starting point of the arc
         * @param theta The angle that the ellipse bounding rectangle makes with horizontal plane
         * @param start The start angle of the arc on the ellipse
         * @param sweep The angle (positive or negative) of the sweep of the arc on the ellipse
         */
        private static void arcToBezier(Path p,
                double cx,
                double cy,
                double a,
                double b,
                double e1x,
                double e1y,
                double theta,
                double start,
                double sweep) {
            // Taken from equations at: http://spaceroots.org/documents/ellipse/node8.html
            // and http://www.spaceroots.org/documents/ellipse/node22.html

            // Maximum of 45 degrees per cubic Bezier segment
            int numSegments = Math.abs((int) Math.ceil(sweep * 4 / Math.PI));

            double eta1 = start;
            double cosTheta = Math.cos(theta);
            double sinTheta = Math.sin(theta);
            double cosEta1 = Math.cos(eta1);
            double sinEta1 = Math.sin(eta1);
            double ep1x = (-a * cosTheta * sinEta1) - (b * sinTheta * cosEta1);
            double ep1y = (-a * sinTheta * sinEta1) + (b * cosTheta * cosEta1);

            double anglePerSegment = sweep / numSegments;
            for (int i = 0; i < numSegments; i++) {
                double eta2 = eta1 + anglePerSegment;
                double sinEta2 = Math.sin(eta2);
                double cosEta2 = Math.cos(eta2);
                double e2x = cx + (a * cosTheta * cosEta2) - (b * sinTheta * sinEta2);
                double e2y = cy + (a * sinTheta * cosEta2) + (b * cosTheta * sinEta2);
                double ep2x = -a * cosTheta * sinEta2 - b * sinTheta * cosEta2;
                double ep2y = -a * sinTheta * sinEta2 + b * cosTheta * cosEta2;
                double tanDiff2 = Math.tan((eta2 - eta1) / 2);
                double alpha =
                        Math.sin(eta2 - eta1) * (Math.sqrt(4 + (3 * tanDiff2 * tanDiff2)) - 1) / 3;
                double q1x = e1x + alpha * ep1x;
                double q1y = e1y + alpha * ep1y;
                double q2x = e2x - alpha * ep2x;
                double q2y = e2y - alpha * ep2y;

                p.cubicTo((float) q1x,
                        (float) q1y,
                        (float) q2x,
                        (float) q2y,
                        (float) e2x,
                        (float) e2y);
                eta1 = eta2;
                e1x = e2x;
                e1y = e2y;
                ep1x = ep2x;
                ep1y = ep2y;
            }
        }

    }
}
