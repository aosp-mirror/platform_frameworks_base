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

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.Resources.Theme;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.location.Address;
import android.net.ParseException;
import android.sax.StartElementListener;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
/**
 * This lets you create a drawable based on an XML vector graphic
 * It can be defined in an XML file with the <code>&lt;vector></code> element.
 * <p/>
 * The vector drawable has 6 elements:
 * <p/>
 * <dl>
 * <dt><code>&lt;vector></code></dt><dd>
 * The attribute <code>android:trigger</code> which defines a state change that
 * will drive the animation </dd>
 * <dt><code>&lt;size></code></dt>
 * <dd>Used to defined the intrinsic Width Height size of the drawable using
 * <code>android:width</code> and <code>android:height</code> </dd>
 * <dt><code>&lt;viewport></code></dt>
 * <dd>Used to defined the size of the virtual canvas the paths are drawn on.
 * The size is defined using the attributes <code>android:viewportHeight
 * </code> <code>android:viewportWidth</code></dd>
 * <dt><code>&lt;group></code></dt>
 * <dd>Defines a "key frame" in the animation if there is only one group the
 * drawable is static 2D image that has no animation.</dd>
 * <dt><code>&lt;path></code></dt>
 * <dd>Defines paths to be drawn. The path elements must be within a group
 * <dl>
 * <dt><code>android:name</code>
 * <dd>Defines the name of the path.</dd></dt>
 * <dt><code>android:pathData</code>
 * <dd>Defines path string.</dd></dt>
 * <dt><code>android:fill</code>
 * <dd>Defines the color to fill the path (none if not present).</dd></dt>
 * <dt><code>android:stroke</code>
 * <dd>Defines the color to draw the path outline (none if not present).</dd></dt>
 * <dt><code>android:strokeWidth</code>
 * <dd>The width a path stroke</dd></dt>
 * <dt><code>android:strokeOpacity</code>
 * <dd>The opacity of a path stroke</dd></dt>
 * <dt><code>android:rotation</code>
 * <dd>The amount to rotation the path stroke.</dd></dt>
 * <dt><code>android:pivotX</code>
 * <dd>The X coordinate of the center of rotation of a path</dd></dt>
 * <dt><code>android:pivotY</code>
 * <dd>The Y coordinate of the center of rotation of a path</dd></dt>
 * <dt><code>android:fillOpacity</code>
 * <dd>The opacity to fill the path with</dd></dt>
 * <dt><code>android:trimPathStart</code>
 * <dd>The fraction of the path to trim from the start from 0 to 1</dd></dt>
 * <dt><code>android:trimPathEnd</code>
 * <dd>The fraction of the path to trim from the end from 0 to 1</dd></dt>
 * <dt><code>android:trimPathOffset</code>
 * <dd>Shift trim region (allows showed region to include the start and end) from 0 to 1</dd></dt>
 * <dt><code>android:clipToPath</code>
 * <dd>Path will set the clip path</dd></dt>
 * <dt><code>android:strokeLineCap</code>
 * <dd>Sets the linecap for a stroked path: butt, round, square</dd></dt>
 * <dt><code>android:strokeLineJoin</code>
 * <dd>Sets the lineJoin for a stroked path: miter,round,bevel</dd></dt>
 * <dt><code>android:strokeMiterLimit</code>
 * <dd>Sets the Miter limit for a stroked path</dd></dt>
 * <dt><code>android:state_pressed</code>
 * <dd>Sets a condition to be met to draw path</dd></dt>
 * <dt><code>android:state_focused</code>
 * <dd>Sets a condition to be met to draw path</dd></dt>
 * <dt><code>android:state_selected</code>
 * <dd>Sets a condition to be met to draw path</dd></dt>
 * <dt><code>android:state_window_focused</code>
 * <dd>Sets a condition to be met to draw path</dd></dt>
 * <dt><code>android:state_enabled</code>
 * <dd>Sets a condition to be met to draw path</dd></dt>
 * <dt><code>android:state_activated</code>
 * <dd>Sets a condition to be met to draw path</dd></dt>
 * <dt><code>android:state_accelerated</code>
 * <dd>Sets a condition to be met to draw path</dd></dt>
 * <dt><code>android:state_hovered</code>
 * <dd>Sets a condition to be met to draw path</dd></dt>
 * <dt><code>android:state_checked</code>
 * <dd>Sets a condition to be met to draw path</dd></dt>
 * <dt><code>android:state_checkable</code>
 * <dd>Sets a condition to be met to draw path</dd></dt>
 * </dl>
 * </dd>
 * <dt><code>&lt;animation></code></dt>
 * <dd>Used to customize the transition between two paths
 * <dl>
 * <dt><code>android:sequence</code>
 * <dd>Configures this animation sequence between the named paths.</dd></dt>
 * <dt><code>android:limitTo</code>
 * <dd>Limits an animation to only interpolate the selected variable
 * unlimited, path, rotation, trimPathStart, trimPathEnd, trimPathOffset</dd></dt>
 * <dt><code>android:repeatCount</code>
 * <dd>Number of times to loop this aspect of the animation</dd></dt>
 * <dt><code>android:durations</code>
 * <dd>The duration of each step in the animation in milliseconds
 * Must contain the number of named paths - 1</dd></dt>
 * <dt><code>android:startDelay</code>
 * <dd></dd></dt>
 * <dt><code>android:repeatStyle</code>
 *  <dd>when repeating how does it repeat back and forth or a to b: forward, inAndOut</dd></dt>
 * <dt><code>android:animate</code>
 *  <dd>linear, accelerate, decelerate, easing</dd></dt>
 * </dl>
 * </dd>
 */
public class VectorDrawable extends Drawable {
    private static final String LOGTAG = "VectorDrawable";
    public static final int INFINITE = ValueAnimator.INFINITE;
    private static final String SHAPE_SIZE = "size";
    private static final String SHAPE_VIEWPORT = "viewport";
    private static final String SHAPE_GROUP = "group";
    private static final String SHAPE_PATH = "path";
    private static final String SHAPE_TRANSITION = "transition";
    private static final String SHAPE_ANIMATION = "animation";
    private static final String SHAPE_VECTOR = "vector";

    private static final int LINECAP_BUTT = 0;
    private static final int LINECAP_ROUND = 1;
    private static final int LINECAP_SQUARE = 2;
    private static final int LINEJOIN_MITER = 0;
    private static final int LINEJOIN_ROUND = 1;
    private static final int LINEJOIN_BEVEL = 2;
    private static final int DEFAULT_DURATION = 1000;
    private static final long DEFAULT_INFINITE_DURATION = 60 * 60 * 1000;
    private VectorDrawableState mVectorState;
    private int mAlpha = 0xFF;

    public VectorDrawable() {
        mVectorState = new VectorDrawableState();
        mVectorState.mBasicAnimator = ObjectAnimator.ofFloat(this, "AnimationFraction", 0, 1);
        setDuration(DEFAULT_DURATION);
    }

    private VectorDrawable(VectorDrawableState state) {
        mVectorState = new VectorDrawableState(state);
        mVectorState.mBasicAnimator = ObjectAnimator.ofFloat(this, "AnimationFraction", 0, 1);
        long duration = mVectorState.mVAnimatedPath.getTotalAnimationDuration();
        if (duration == -1) { // if it set to infinite set to 1 hour
            duration = DEFAULT_INFINITE_DURATION; // TODO define correct approach for infinite
            mVectorState.mBasicAnimator.setFloatValues(0, duration / 1000);
            mVectorState.mBasicAnimator.setInterpolator(new LinearInterpolator());
        }
        setDuration(duration);
    }

    final static class VectorDrawableState extends ConstantState {
        int mChangingConfigurations;
        ValueAnimator mBasicAnimator;
        VAnimatedPath mVAnimatedPath = new VAnimatedPath();
        Rect mPadding;
        int mIntrinsicHeight;
        int mIntrinsicWidth;

        public VectorDrawableState(){
        }

        public VectorDrawableState(VectorDrawableState copy){
            mChangingConfigurations = copy.mChangingConfigurations;
            mVAnimatedPath = new VAnimatedPath(copy.mVAnimatedPath);
            mPadding = new Rect(copy.mPadding);
            mIntrinsicHeight = copy.mIntrinsicHeight;
            mIntrinsicWidth = copy.mIntrinsicWidth;
        }

        @Override
        public Drawable newDrawable() {
            return new VectorDrawable(this);
        }

        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }
    }

    /* (non-Javadoc)
     * @see android.graphics.drawable.Drawable#getConstantState()
     */
    @Override
    public ConstantState getConstantState() {
        return mVectorState;
    }

    /**
     * start the animation
     */
    public void start() {
        mVectorState.mBasicAnimator.start();
    }

    /**
     * Stop the animation.
     */
    public void stop() {
        mVectorState.mBasicAnimator.end();
    }

    /**
     * Get the current time point in the animation
     *
     * @return the current point on the animation
     */
    public float geAnimationFraction() {
        return mVectorState.mVAnimatedPath.getValue();
    }

    /**
     * set the time point in the animation
     *
     * @param value the point along the animation typically between 0 and 1
     */
    public void setAnimationFraction(float value) {
        mVectorState.mVAnimatedPath.setAnimationFraction(value);
        invalidateSelf();
    }

    /**
     * set the amount of time the animation will take
     *
     * @param duration amount of time in milliseconds
     */
    public void setDuration(long duration) {
        mVectorState.mBasicAnimator.setDuration(duration);
    }

    /**
     * Defines what this animation should do when it reaches the end. This setting is applied only
     * when the repeat count is either greater than 0 or {@link #INFINITE}.
     */
    public void setRepeatMode(int mode) {
        mVectorState.mBasicAnimator.setRepeatMode(mode);
    }

    /**
     * Sets animation to repeat
     *
     * @param repeat True if this drawable repeats its animation
     */
    public void setRepeatCount(int repeat) {
        mVectorState.mBasicAnimator.setRepeatCount(repeat);
    }

    /**
     * @return True if this drawable repeats its animation
     */
    public int getRepeatCount() {
        return mVectorState.mBasicAnimator.getRepeatCount();
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        mVectorState.mVAnimatedPath.setState(state);
        int direction = mVectorState.mVAnimatedPath.getTrigger(state);
        if (direction>0) {
            animateForward();
        } else if (direction<0) {
            animateBackward();
        }
        super.onStateChange(state);
        invalidateSelf();
        return true;
    }

    private void animateForward(){
        if (!mVectorState.mBasicAnimator.isStarted()) {
            mVectorState.mBasicAnimator.setFloatValues(0,1);
            start();
        }
    }

    private void animateBackward(){
        if (!mVectorState.mBasicAnimator.isStarted()) {
            mVectorState.mBasicAnimator.setFloatValues(.99f,0);
            start();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        mVectorState.mVAnimatedPath.draw(canvas);
    }

    @Override
    public void setAlpha(int alpha) {
        // TODO correct handling of transparent
        if (mAlpha != alpha) {
            mAlpha = alpha;
            invalidateSelf();
        }
    }

    /**
     * Not implemented yet
     * @hide
     */
    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // TODO: support color filter
    }

    /**
     * Returns a {@link android.graphics.PixelFormat graphics.PixelFormat}
     * value of TRANSLUCENT.
     */
    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /**
     * Sets padding for this shape, defined by a Rect object. Define the padding in the Rect object
     * as: left, top, right, bottom.
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

    /**
     * Sets the intrinsic (default) width for this shape.
     *
     * @param width the intrinsic width (in pixels)
     */
    public void setIntrinsicWidth(int width) {
        if (mVectorState.mIntrinsicWidth != width) {
            mVectorState.mIntrinsicWidth = width;
            invalidateSelf();
        }
    }

    /**
     * Sets the intrinsic (default) height for this shape.
     *
     * @param height the intrinsic height (in pixels)
     */
    public void setIntrinsicHeight(int height) {
        if (mVectorState.mIntrinsicHeight != height) {
            mVectorState.mIntrinsicHeight = height;
            invalidateSelf();
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return mVectorState.mIntrinsicWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        return mVectorState.mIntrinsicHeight;
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

    /** @hide */
    public static VectorDrawable create(Resources resources, int rid) {
        try {
            VectorDrawable drawable = new VectorDrawable();
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser xpp = resources.getXml(rid);
            AttributeSet attrs = Xml.asAttributeSet(xpp);
            drawable.inflate(resources, xpp, attrs);
            drawable.setAnimationFraction(0);
            return drawable;
        } catch (XmlPullParserException e) {
            Log.e(LOGTAG, "parser error", e);
        } catch (IOException e) {
            Log.e(LOGTAG, "parser error", e);
        }
        return null;
    }

    @Override
    public void inflate(Resources res, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        setAnimatedPath(inflateInternal(res, parser, attrs));
    }

    private VAnimatedPath inflateInternal(Resources res, XmlPullParser parser, AttributeSet attrs)
            throws XmlPullParserException, IOException {
        boolean noSizeTag = true;
        boolean noViewportTag = true;
        boolean noGroupTag = true;
        boolean noPathTag = true;
        final VAnimatedPath animatedPath = new VAnimatedPath();
        VectorDrawable.VGroup currentGroup = null;

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                String tagName = parser.getName();
                if (SHAPE_PATH.equals(tagName)) {
                    VectorDrawable.VPath p = new VPath(res, attrs);
                    currentGroup.add(p);
                    noPathTag = false;
                } else if (SHAPE_ANIMATION.equals(tagName)) {
                    VectorDrawable.VAnimation anim =
                            new VAnimation(res, attrs, animatedPath.mGroupList);
                    animatedPath.addAnimation(anim);
                } else if (SHAPE_SIZE.equals(tagName)) {
                    animatedPath.parseSize(res, attrs);
                    noSizeTag = false;
                } else if (SHAPE_VIEWPORT.equals(tagName)) {
                    animatedPath.parseViewport(res, attrs);
                    noViewportTag = false;
                } else if (SHAPE_GROUP.equals(tagName)) {
                    currentGroup = new VectorDrawable.VGroup();
                    animatedPath.mGroupList.add(currentGroup);
                    noGroupTag = false;
                }  else if (SHAPE_VECTOR.equals(tagName)) {
                    TypedArray a = res.obtainAttributes(attrs, R.styleable.VectorDrawable);
                    animatedPath.setTrigger(a.getInteger(R.styleable.VectorDrawable_trigger, 0));
                    a.recycle();
                }
            }
            eventType = parser.next();
        }
        if (noSizeTag || noViewportTag || noGroupTag || noPathTag) {
            StringBuffer tag = new StringBuffer();
            if (noSizeTag) {
                tag.append("size");
            }
            if  (noViewportTag){
                if (tag.length()>0) {
                    tag.append(" & ");
                }
                tag.append("size");
            }
            if  (noGroupTag){
                if (tag.length()>0) {
                    tag.append(" & ");
                }
                tag.append("group");
            }
            if  (noPathTag){
                if (tag.length()>0) {
                    tag.append(" or ");
                }
                tag.append("path");
            }
            throw new XmlPullParserException("no "+tag+" defined");
        }
        // post parse cleanup
        animatedPath.parseFinish();
        return animatedPath;
    }

    private void setAnimatedPath(VAnimatedPath animatedPath) {
        mVectorState.mVAnimatedPath = animatedPath;
        setIntrinsicWidth((int) mVectorState.mVAnimatedPath.mBaseWidth);
        setIntrinsicHeight((int) mVectorState.mVAnimatedPath.mBaseHeight);
        long duration = mVectorState.mVAnimatedPath.getTotalAnimationDuration();
        if (duration == -1) { // if it set to infinite set to 1 hour
            duration = DEFAULT_INFINITE_DURATION; // TODO define correct approach for infinite
            mVectorState.mBasicAnimator.setFloatValues(0, duration / 1000);
            mVectorState.mBasicAnimator.setInterpolator(new LinearInterpolator());
        }
        setDuration(duration);

        setAnimationFraction(0);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (visible) {
            if (changed || restart) {
                setAnimationFraction(0);
            }
        } else {
            stop();
        }
        return changed;
    }

    private static class VAnimatedPath {
        private static final String LOGTAG = "VAnimatedPath";

        private ArrayList<VAnimation> mCurrentAnimList = null;
        private VPath[] mCurrentPaths;
        private float mAnimationValue = 0; // value goes from 0 to 1
        private Paint mStrokePaint = null;
        private Paint mFillPaint = null;
        private PathMeasure mPathMeasure;
        private Path mPath = new Path();
        private Path mRenderPath = new Path();
        private Matrix mMatrix = new Matrix();
        private long mTotalDuration;
        private int[] mCurrentState = new int[0];
        private int mTrigger;
        private boolean mTriggerState;
        ArrayList<VGroup> mGroupList = new ArrayList<VGroup>();
        float mBaseWidth = 1;
        float mBaseHeight = 1;
        float mViewportWidth;
        float mViewportHeight;

        public VAnimatedPath() {
            setup();
        }
        public VAnimatedPath(VAnimatedPath copy) {
            setup();
            mCurrentAnimList = new ArrayList<VAnimation>(copy.mCurrentAnimList);
            mGroupList = new ArrayList<VGroup>(copy.mGroupList);
            if (copy.mCurrentPaths != null) {
                mCurrentPaths = new VPath[copy.mCurrentPaths.length];
                for (int i = 0; i < mCurrentPaths.length; i++) {
                    mCurrentPaths[i] = new VPath(copy.mCurrentPaths[i]);
                }
            }
            mAnimationValue = copy.mAnimationValue; // time goes from 0 to 1

            mBaseWidth = copy.mBaseWidth;
            mBaseHeight = copy.mBaseHeight;
            mViewportWidth = copy.mViewportHeight;
            mViewportHeight = copy.mViewportHeight;
            mTotalDuration = copy.mTotalDuration;
            mTrigger = copy.mTrigger;
            mCurrentState = new int[0];
        }

        public void setTrigger(int trigger){
            int []lut = { 0,
                    R.attr.state_pressed,
                    R.attr.state_focused,
                    R.attr.state_hovered,
                    R.attr.state_selected,
                    R.attr.state_checkable,
                    R.attr.state_checked,
                    R.attr.state_activated,
                    R.attr.state_focused
            };
            mTrigger = lut[trigger];
         }

        private void setup(){
            mStrokePaint = new Paint();
            mStrokePaint.setStyle(Paint.Style.STROKE);
            mStrokePaint.setAntiAlias(true);
            mFillPaint = new Paint();
            mFillPaint.setStyle(Paint.Style.FILL);
            mFillPaint.setAntiAlias(true);
        }

        public long getTotalAnimationDuration() {
            mTotalDuration = 0;
            int size = mCurrentAnimList.size();
            for (int i = 0; i < size; i++) {
                VAnimation vAnimation = mCurrentAnimList.get(i);
                long t = vAnimation.getTotalDuration();
                if (t == -1) {
                    mTotalDuration = -1;
                    return -1;
                }
                mTotalDuration = Math.max(mTotalDuration, t);
            }

            return mTotalDuration;
        }

        public float getValue() {
            return mAnimationValue;
        }

        /**
         * @param value the point along the animations to show typically between 0.0f and 1.0f
         * @return true if you need to keep repeating
         */
        public boolean setAnimationFraction(float value) {
            int len = mCurrentPaths.length;
            getTotalAnimationDuration();
            long animationTime = (long) (value * mTotalDuration);

            for (int i = 0; i < len; i++) {
                VPath path = mCurrentPaths[i];
                animationTime =
                        (long) ((mTotalDuration == -1) ? value * 1000 : mTotalDuration * value);
                int size = mCurrentAnimList.size();
                for (int j = 0; j < size; j++) {
                    VAnimation vAnimation = mCurrentAnimList.get(j);
                    if (vAnimation.doesAdjustPath(path)) {
                        mCurrentPaths[i] =  vAnimation.getPathAtTime(animationTime, path);
                    }
                }
            }
            this.mAnimationValue = value;
            if (mTotalDuration == -1) {
                return true;
            } else {
                return animationTime < mTotalDuration;
            }
        }

        public void draw(Canvas canvas) {
            int w = canvas.getWidth();
            int h = canvas.getHeight();
            float scale = w / mViewportWidth;
            scale = Math.min(h / mViewportHeight, scale);

            if (mCurrentPaths == null) {
                Log.e(LOGTAG,"mCurrentPaths == null");
                return;
            }

            for (int i = 0; i < mCurrentPaths.length; i++) {
                if (mCurrentPaths[i] != null && mCurrentPaths[i].isVisible(mCurrentState)) {
                    drawPath(mCurrentPaths[i], canvas, w, h);
                }
            }
        }

        private void drawPath(VPath vPath, Canvas canvas, int w, int h) {
            float scale = w / mViewportWidth;
            scale = Math.min(h / mViewportHeight, scale);

            vPath.toPath(mPath);
            Path path = mPath;

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
            mMatrix.reset();

            mMatrix.postRotate(vPath.mRotate, vPath.mPivotX, vPath.mPivotY);
            mMatrix.postScale(scale, scale, mViewportWidth / 2f, mViewportHeight / 2f);
            mMatrix.postTranslate(w / 2f - mViewportWidth / 2f, h / 2f - mViewportHeight / 2f);

            mRenderPath.addPath(path, mMatrix);

            if (vPath.mClip) {
                canvas.clipPath(mRenderPath, Region.Op.REPLACE);
            }
            if (vPath.mFillColor != 0) {
                mFillPaint.setColor(vPath.mFillColor);
                int alpha = 0xFF & (vPath.mFillColor >> 24);
                mFillPaint.setAlpha(alpha);
                canvas.drawPath(mRenderPath, mFillPaint);
            }
            if (vPath.mStrokeColor != 0) {
                if (vPath.mStrokelineJoin != null) {
                    mStrokePaint.setStrokeJoin(vPath.mStrokelineJoin);
                }
                if (vPath.mStrokelineCap != null) {
                    mStrokePaint.setStrokeCap(vPath.mStrokelineCap);
                }
                mStrokePaint.setStrokeMiter(vPath.mStrokeMiterlimit * scale);
                mStrokePaint.setColor(vPath.mStrokeColor);
                mStrokePaint.setAlpha(0xFF & (vPath.mStrokeColor >> 24));
                mStrokePaint.setStrokeWidth(vPath.mStrokeWidth * scale);
                canvas.drawPath(mRenderPath, mStrokePaint);
            }
        }

        /**
         * Ensure there is at least one animation for every path in group (linking them by names)
         * Build the "current" path based on the first group
         * TODO: improve memory use & performance or move to C++
         */
        public void parseFinish() {
            HashMap<String, VAnimation> newAnimations = new HashMap<String, VAnimation>();
            for (VGroup group : mGroupList) {
                for (VPath vPath : group.getPaths()) {
                    if (!vPath.mAnimated) {
                        VAnimation ap = null;

                        if (!newAnimations.containsKey(vPath.getID())) {
                            newAnimations.put(vPath.getID(), ap = new VAnimation());
                        } else {
                            ap = newAnimations.get(vPath.getID());
                        }
                        ap.addPath(vPath);
                        vPath.mAnimated = true;
                    }
                }
            }
            if (mCurrentAnimList == null) {
                mCurrentAnimList = new ArrayList<VectorDrawable.VAnimation>();
            }
            mCurrentAnimList.addAll(newAnimations.values());
            Collection<VPath> paths = mGroupList.get(0).getPaths();
            mCurrentPaths = paths.toArray(new VPath[paths.size()]);
            for (int i = 0; i < mCurrentPaths.length; i++) {
                mCurrentPaths[i] = new VPath(mCurrentPaths[i]);
            }
        }

        public void setState(int[] state) {
            mCurrentState = Arrays.copyOf(state, state.length);
        }

        int getTrigger(int []state){
            if (mTrigger == 0) return 0;
            for (int i = 0; i < state.length; i++) {
                if (state[i] == mTrigger){
                    if (mTriggerState)
                        return 0;
                    mTriggerState = true;
                    return 1;
                }
            }
            if (mTriggerState) {
                mTriggerState = false;
                return -1;
            }
            return 0;
        }

        public void addAnimation(VAnimation anim) {
            if (mCurrentAnimList == null) {
                mCurrentAnimList = new ArrayList<VectorDrawable.VAnimation>();
            }
            mCurrentAnimList.add(anim);
        }

        private void parseViewport(Resources r, AttributeSet attrs)
                throws XmlPullParserException {
            TypedArray a = r.obtainAttributes(attrs, R.styleable.VectorDrawableViewport);
            mViewportWidth = a.getFloat(R.styleable.VectorDrawableViewport_viewportWidth, 0);
            mViewportHeight = a.getFloat(R.styleable.VectorDrawableViewport_viewportHeight, 0);
            if (mViewportWidth == 0 || mViewportHeight == 0) {
                throw new XmlPullParserException(a.getPositionDescription()+
                        "<viewport> tag requires viewportWidth & viewportHeight to be set");
            }
            a.recycle();
        }

        private void parseSize(Resources r, AttributeSet attrs)
                throws XmlPullParserException  {
            TypedArray a = r.obtainAttributes(attrs, R.styleable.VectorDrawableSize);
            mBaseWidth = a.getDimension(R.styleable.VectorDrawableSize_width, 0);
            mBaseHeight = a.getDimension(R.styleable.VectorDrawableSize_height, 0);
            if (mBaseWidth == 0 || mBaseHeight == 0) {
                throw new XmlPullParserException(a.getPositionDescription()+
                        "<size> tag requires width & height to be set");
            }
            a.recycle();
        }
    }

    private static class VAnimation {
        private static final String LOGTAG = "VAnimation";
        private VPath[] mPaths = new VPath[0];

        public enum Style {
            INTERPOLATE, CROSSFADE, WIPE
        }
        Interpolator mAnimInterpolator = new AccelerateDecelerateInterpolator();
        private Style mStyle;
        private int mLimitProperty = 0;
        private long[] mDuration = {DEFAULT_DURATION};
        private long mStartOffset;
        private long mRepeat = 1;
        private HashSet<String>mSeqMap = new HashSet<String>();
        private long mWipeDirection;
        private int mMode = 0; // forward = 0 inAndOut = 1;
        private int mInterpolatorType;
        private String mId;
        private final static int DIRECTION_FORWARD = 0;
        private final static int DIRECTION_IN_AND_OUT = 1;

        public VAnimation() {
        }

        public boolean doesAdjustPath(VPath path) {
            return mSeqMap.contains(path.getID());
        }

        public VAnimation(Resources r, AttributeSet attrs, ArrayList<VGroup> groups)
                throws XmlPullParserException {
            String value;
            String[] sp;
            int name;

            TypedArray a = r.obtainAttributes(attrs, R.styleable.VectorDrawableAnimation);

            value = a.getString(R.styleable.VectorDrawableAnimation_sequence);
            if (value != null) {
                sp = value.split(",");
                VectorDrawable.VPath[] paths = new VectorDrawable.VPath[sp.length];

                for (int j = 0; j < sp.length; j++) {
                    mSeqMap.add(sp[j].trim());
                    VectorDrawable.VPath path = groups.get(j).get(sp[j]);
                    path.mAnimated = true;
                    paths[j] = path;
                }
                setPaths(paths);
            }

            setLimitProperty(a.getInt(R.styleable.VectorDrawableAnimation_limitTo, 0));

            name = R.styleable.VectorDrawableAnimation_durations;
            value = a.getString(name);
            if (value != null) {
                long totalDuration = 0;
                sp = value.split(",");
                long[] dur = new long[sp.length];
                for (int j = 0; j < dur.length; j++) {
                    dur[j] = Long.parseLong(sp[j]);
                    totalDuration +=  dur[j];
                }
                if (totalDuration == 0){
                    throw new XmlPullParserException(a.getPositionDescription()+
                            "total duration must not be zero");
                }
                setDuration(dur);
            }

            setRepeat(a.getInt(R.styleable.VectorDrawableAnimation_repeatCount, 1));

            setStartOffset(a.getInt(R.styleable.VectorDrawableAnimation_startDelay, 0));

            setMode(a.getInt(R.styleable.VectorDrawableAnimation_repeatStyle, 0));

            fixMissingParameters();
            a.recycle();
        }

        public String getId() {
            if (mId == null) {
                mId = mPaths[0].getID();
                for (int i = 1; i < mPaths.length; i++) {
                    mId += mPaths[i].getID();
                }
            }
            return mId;
        }

        public String getPathName() {
            return mPaths[0].getID();
        }

        public Style getStyle() {
            return mStyle;
        }

        public void setStyle(Style style) {
            this.mStyle = style;
        }

        public int getLimitProperty() {
            return mLimitProperty;
        }

        public void setLimitProperty(int limitProperty) {
            this.mLimitProperty = limitProperty;
        }

        public long[] getDuration() {
            return mDuration;
        }

        public void setDuration(long[] duration) {
            this.mDuration = duration;
        }

        public long getRepeat() {
            return mRepeat;
        }

        public void setRepeat(long repeat) {
            this.mRepeat = repeat;
        }

        public long getStartOffset() {
            return mStartOffset;
        }

        public void setStartOffset(long startOffset) {
            this.mStartOffset = startOffset;
        }

        public long getWipeDirection() {
            return mWipeDirection;
        }

        public void setWipeDirection(long wipeDirection) {
            this.mWipeDirection = wipeDirection;
        }

        public int getMode() {
            return mMode;
        }

        public void setMode(int mode) {
            this.mMode = mode;
        }

        public int getInterpolator() {
            return mInterpolatorType;
        }

        public void setInterpolator(int interpolator) {
            this.mInterpolatorType = interpolator;
        }

        /**
         * compute the total time in milliseconds
         *
         * @return the total time in milliseconds the animation will take
         */
        public long getTotalDuration() {
            long total = mStartOffset;
            if (getRepeat() == -1) {
                return -1;
            }
            for (int i = 0; i < mDuration.length; i++) {
                if (mRepeat > 1) {
                    total += mDuration[i] * mRepeat;
                } else {
                    total += mDuration[i];
                }
            }
            return total;
        }

        public void setPaths(VPath[] paths) {
            this.mPaths = paths;
        }

        public void addPath(VPath path) {
            mPaths = Arrays.copyOf(mPaths, mPaths.length + 1);
            mPaths[mPaths.length - 1] = path;
        }

        public boolean containsPath(String pathid) {
            for (int i = 0; i < mPaths.length; i++) {
                if (mPaths[i].getID().equals(pathid)) {
                    return true;
                }
            }
            return false;
        }

        public void interpolate(VPath p1, VPath p2, float time, VPath dest) {
            dest.interpolate(time, p1, p2, dest, mLimitProperty);
        }

        public VPath getPathAtTime(long milliseconds, VPath dest) {
            if (mPaths.length == 1) {
                dest.copyFrom(mPaths[0]);
                return dest;
            }
            long point = milliseconds - mStartOffset;
            if (point < 0) {
                point = 0;
            }
            float time = 0;
            long sum = mDuration[0];
            for (int i = 1; i < mDuration.length; i++) {
                sum += mDuration[i];
            }

            if (mRepeat > 1) {
                time = point / (float) (sum * mRepeat);
                time = mAnimInterpolator.getInterpolation(time);

                if (mMode == DIRECTION_IN_AND_OUT) {
                    point = ((long) (time * sum * 2 * mRepeat)) % (sum * 2);
                    if (point > sum) {
                        point = sum * 2 - point;
                    }
                } else {
                    point = ((long) (time * sum * mRepeat)) % sum;
                }
            } else if (mRepeat == 1) {
                time = point / (float) (sum * mRepeat);
                time = mAnimInterpolator.getInterpolation(time);
                if (mMode == DIRECTION_IN_AND_OUT) {
                    point = ((long) (time * sum * 2 * mRepeat));
                    if (point > sum) {
                        point = sum * 2 - point;
                    }
                } else {
                    point = Math.min(((long) (time * sum * mRepeat)), sum);
                }

            } else { // repeat = -1
                if (mMode == DIRECTION_IN_AND_OUT) {
                    point = point % (sum * 2);
                    if (point > sum) {
                        point = sum * 2 - point;
                    }
                    time = point / (float) sum;
                } else {
                    point = point % sum;
                    time = point / (float) sum;
                }
            }

            int transition = 0;
            while (point > mDuration[transition]) {
                point -= mDuration[transition++];
            }
            if (mPaths.length > (transition + 1)) {
                if (mPaths[transition].getID() != dest.getID()) {
                    dest.copyFrom(mPaths[transition]);
                }
                interpolate(mPaths[transition], mPaths[transition + 1],
                        point / (float) mDuration[transition], dest);
            } else {
                interpolate(mPaths[transition], mPaths[transition], 0, dest);
            }
            return dest;
        }

        void fixMissingParameters() {
            // fix missing points
            float rotation = Float.NaN;
            float rotationY = Float.NaN;
            float rotationX = Float.NaN;
            for (int i = 0; i < mPaths.length; i++) {
                if (mPaths[i].mPivotX > 0) {
                    rotationX = mPaths[i].mPivotX;
                }
                if (mPaths[i].mPivotY > 0) {
                    rotationY = mPaths[i].mPivotY;
                }
                if (mPaths[i].mRotate > 0) {
                    rotation = mPaths[i].mRotate;
                }
            }
            if (rotation > 0) {
                for (int i = 0; i < mPaths.length; i++) {
                    if (mPaths[i].mPivotX == 0) {
                        mPaths[i].mPivotX = rotationX;
                    }
                    if (mPaths[i].mPivotY == 0) {
                        mPaths[i].mPivotY = rotationY;
                    }
                }
            }
        }
    }

    private static class VGroup {
        private HashMap<String, VPath> mVGPathMap = new HashMap<String, VPath>();
        private ArrayList<VPath> mVGList = new ArrayList<VPath>();

        public void add(VPath path) {
            String id = path.getID();
            mVGPathMap.put(id, path);
            mVGList.add(path);
         }

        public VPath get(String name) {
            return mVGPathMap.get(name);
        }

        /**
         * Must return in order of adding
         * @return ordered list of paths
         */
        public Collection<VPath> getPaths() {
            return mVGList;
        }

        public int size() {
            return mVGPathMap.size();
        }
    }

    private static class VPath {
        private static final int LIMIT_ALL = 0;
        private static final int LIMIT_PATH = 1;
        private static final int LIMIT_ROTATE = 2;
        private static final int LIMIT_TRIM_PATH_START = 3;
        private static final int LIMIT_TRIM_PATH_OFFSET = 5;
        private static final int LIMIT_TRIM_PATH_END = 4;
        private static final int STATE_UNDEFINED=0;
        private static final int STATE_TRUE=1;
        private static final int STATE_FALSE=2;
        private static final int MAX_STATES = 10;
        private VNode[] mNode = null;
        private String mId;
        int mStrokeColor = 0;
        float mStrokeWidth = 0;
        float mStrokeOpacity = Float.NaN;
        int mFillColor = 0;
        int mFillRule;
        float mFillOpacity = Float.NaN;
        float mRotate = 0;
        float mPivotX = 0;
        float mPivotY = 0;
        float mTrimPathStart = 0;
        float mTrimPathEnd = 1;
        float mTrimPathOffset = 0;
        boolean mAnimated = false;
        boolean mClip = false;
        public Paint.Cap mStrokelineCap = null;
        public Paint.Join mStrokelineJoin = null;
        float mStrokeMiterlimit = 4;
        private int[] mCheckState = new int[MAX_STATES];
        private boolean[] mCheckValue = new boolean[MAX_STATES];
        private int mNumberOfStates = 0;
        private int mNumberOfTrue = 0;

        public void addStateFilter(int state, boolean condition) {
            int k = 0;
            while (k < mNumberOfStates) {
                if (mCheckState[mNumberOfStates] == state)
                    break;
                k++;
            }
            mCheckState[k] = state;
            mCheckValue[k] = condition;
            if (k==mNumberOfStates){
                mNumberOfStates++;
            }
            if (condition) {
                mNumberOfTrue++;
            }
        }

        int getState(int state){
            for (int i = 0; i < mNumberOfStates; i++) {
                if (mCheckState[mNumberOfStates] == state){
                    return (mCheckValue[i])?STATE_TRUE:STATE_FALSE;
                }
            }
            return STATE_UNDEFINED;
        }
        /**
         * @return the name of the path
         */
        public String getName() {
            return mId;
        }

        public void toPath(Path path) {
            path.reset();
            if (mNode != null) {
                VNode.createPath(mNode, path);
            }
        }

        public VPath() {
            mId = this.toString(); // to ensure paths have unique names
        }

        public VPath(VPath p) {
            copyFrom(p);
        }

        public String getID(){
            return mId;
        }

        public VPath(Resources r, AttributeSet attrs) {
            TypedArray a = r.obtainAttributes(attrs, R.styleable.VectorDrawablePath);
            mClip = a.getBoolean(R.styleable.VectorDrawablePath_clipToPath, false);
            mId = a.getString(R.styleable.VectorDrawablePath_name);
            mNode = parsePath(a.getString(R.styleable.VectorDrawablePath_pathData));
            mFillColor = a.getColor(R.styleable.VectorDrawablePath_fill, 0);
            mFillOpacity = a.getFloat(R.styleable.VectorDrawablePath_fillOpacity, Float.NaN);

            if (!Float.isNaN(mFillOpacity)) {
                mFillColor &= 0x00FFFFFF;
                mFillColor |= ((int) (0xFF * mFillOpacity)) << 24;
            }
            mRotate = a.getFloat(R.styleable.VectorDrawablePath_rotation, 0);
            mPivotX = a.getFloat(R.styleable.VectorDrawablePath_pivotX, 0);
            mPivotY = a.getFloat(R.styleable.VectorDrawablePath_pivotY, 0);
            int lineCap  = a.getInt(R.styleable.VectorDrawablePath_strokeLineCap, 0);
            switch (lineCap) {
                case LINECAP_BUTT:
                    mStrokelineCap = Paint.Cap.BUTT;
                    break;
                case LINECAP_ROUND:
                    mStrokelineCap = Paint.Cap.ROUND;
                    break;
                case LINECAP_SQUARE:
                    mStrokelineCap = Paint.Cap.SQUARE;
                    break;
            }
            int lineJoin =  a.getInt(R.styleable.VectorDrawablePath_strokeLineJoin, 0);
            switch (lineJoin) {
                case LINEJOIN_MITER:
                    mStrokelineJoin = Paint.Join.MITER;
                    break;
                case LINEJOIN_ROUND:
                    mStrokelineJoin = Paint.Join.ROUND;
                    break;
                case LINEJOIN_BEVEL:
                    mStrokelineJoin = Paint.Join.BEVEL;
                    break;
            }
            mStrokeMiterlimit = a.getFloat(R.styleable.VectorDrawablePath_strokeMiterLimit,
                    mStrokeMiterlimit);
            mStrokeColor = a.getColor(R.styleable.VectorDrawablePath_stroke, mStrokeColor);
            mStrokeOpacity = a.getFloat(R.styleable.VectorDrawablePath_strokeOpacity, Float.NaN);
            if (!Float.isNaN(mStrokeOpacity)) {
                mStrokeColor &= 0x00FFFFFF;
                mStrokeColor |= ((int) (0xFF * mStrokeOpacity)) << 24;
            }
            mStrokeWidth = a.getFloat(R.styleable.VectorDrawablePath_strokeWidth, 0);
            mTrimPathEnd = a.getFloat(R.styleable.VectorDrawablePath_trimPathEnd, 1);
            mTrimPathOffset = a.getFloat(R.styleable.VectorDrawablePath_trimPathOffset, 0);
            mTrimPathStart = a.getFloat(R.styleable.VectorDrawablePath_trimPathStart, 0);
            int[] states = {R.styleable.VectorDrawablePath_state_activated,
                    R.styleable.VectorDrawablePath_state_checkable,
                    R.styleable.VectorDrawablePath_state_checked,
                    R.styleable.VectorDrawablePath_state_enabled,
                    R.styleable.VectorDrawablePath_state_focused,
                    R.styleable.VectorDrawablePath_state_hovered,
                    R.styleable.VectorDrawablePath_state_pressed,
                    R.styleable.VectorDrawablePath_state_selected,
                    R.styleable.VectorDrawablePath_state_window_focused};
            for (int state : states) {
                if (a.hasValue(state)) {
                    addStateFilter(state, a.getBoolean(state, false));
                }
            }
            a.recycle();
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

        public void copyFrom(VPath p1) {
            mNode = new VNode[p1.mNode.length];
            for (int i = 0; i < mNode.length; i++) {
                mNode[i] = new VNode(p1.mNode[i]);
            }
            mId = p1.mId;
            mStrokeColor = p1.mStrokeColor;
            mFillColor = p1.mFillColor;
            mStrokeWidth = p1.mStrokeWidth;
            mRotate = p1.mRotate;
            mPivotX = p1.mPivotX;
            mPivotY = p1.mPivotY;
            mAnimated = p1.mAnimated;
            mTrimPathStart = p1.mTrimPathStart;
            mTrimPathEnd = p1.mTrimPathEnd;
            mTrimPathOffset = p1.mTrimPathOffset;
            mStrokelineCap = p1.mStrokelineCap;
            mStrokelineJoin = p1.mStrokelineJoin;
            mStrokeMiterlimit = p1.mStrokeMiterlimit;
            mNumberOfStates = p1.mNumberOfStates;
            for (int i = 0; i < mNumberOfStates; i++) {
                mCheckState[i] = p1.mCheckState[i];
                mCheckValue[i] = p1.mCheckValue[i];
            }

            mFillRule = p1.mFillRule;
        }

        public static VPath interpolate(float t, VPath p1, VPath p2, VPath returnPath, int limit) {
            if (limit == LIMIT_ALL || limit == LIMIT_PATH) {
                if (returnPath.mNode == null || returnPath.mNode.length != p1.mNode.length) {
                    returnPath.mNode = new VNode[p1.mNode.length];
                }
                for (int i = 0; i < returnPath.mNode.length; i++) {
                    if (returnPath.mNode[i] == null) {
                        returnPath.mNode[i] = new VNode(p1.mNode[i], p2.mNode[i], t);
                    } else {
                        returnPath.mNode[i].interpolate(p1.mNode[i], p2.mNode[i], t);
                    }
                }
            }
            float t1 = 1 - t;
            switch (limit) {
                case LIMIT_ALL:
                    returnPath.mRotate = t1 * p1.mRotate + t * p2.mRotate;
                    returnPath.mPivotX = t1 * p1.mPivotX + t * p2.mPivotX;
                    returnPath.mPivotY = t1 * p1.mPivotY + t * p2.mPivotY;
                    returnPath.mClip = p1.mClip | p2.mClip;

                    returnPath.mTrimPathStart = t1 * p1.mTrimPathStart + t * p2.mTrimPathStart;
                    returnPath.mTrimPathEnd = t1 * p1.mTrimPathEnd + t * p2.mTrimPathEnd;
                    returnPath.mTrimPathOffset = t1 * p1.mTrimPathOffset + t * p2.mTrimPathOffset;
                    returnPath.mStrokeMiterlimit =
                            t1 * p1.mStrokeMiterlimit + t * p2.mStrokeMiterlimit;
                    returnPath.mStrokelineCap = p1.mStrokelineCap;
                    if (returnPath.mStrokelineCap == null) {
                        returnPath.mStrokelineCap = p2.mStrokelineCap;
                    }
                    returnPath.mStrokelineJoin = p1.mStrokelineJoin;
                    if (returnPath.mStrokelineJoin == null) {
                        returnPath.mStrokelineJoin = p2.mStrokelineJoin;
                    }
                    returnPath.mFillRule = p1.mFillRule;

                    returnPath.mStrokeColor = rgbInterpolate(t, p1.mStrokeColor, p2.mStrokeColor);
                    returnPath.mFillColor = rgbInterpolate(t, p1.mFillColor, p2.mFillColor);
                    returnPath.mStrokeWidth = t1 * p1.mStrokeWidth + t * p2.mStrokeWidth;
                    returnPath.mNumberOfStates = p1.mNumberOfStates;
                    for (int i = 0; i < returnPath.mNumberOfStates; i++) {
                        returnPath.mCheckState[i] = p1.mCheckState[i];
                        returnPath.mCheckValue[i] = p1.mCheckValue[i];
                    }
                    for (int i = 0; i < p2.mNumberOfStates; i++) {
                        returnPath.addStateFilter(p2.mCheckState[i], p2.mCheckValue[i]);
                    }

                    int count = 0;
                    for (int i = 0; i < returnPath.mNumberOfStates; i++) {
                        if (returnPath.mCheckValue[i]) {
                            count++;
                        }
                    }
                    returnPath.mNumberOfTrue = count;
                    break;
                case LIMIT_ROTATE:
                    returnPath.mRotate = t1 * p1.mRotate + t * p2.mRotate;
                    break;
                case LIMIT_TRIM_PATH_END:
                    returnPath.mTrimPathEnd = t1 * p1.mTrimPathEnd + t * p2.mTrimPathEnd;
                    break;
                case LIMIT_TRIM_PATH_OFFSET:
                    returnPath.mTrimPathOffset = t1 * p1.mTrimPathOffset + t * p2.mTrimPathOffset;
                    break;
                case LIMIT_TRIM_PATH_START:
                    returnPath.mTrimPathStart = t1 * p1.mTrimPathStart + t * p2.mTrimPathStart;
                    break;
            }
            return returnPath;
        }

        private static int rgbInterpolate(float t, int color1, int color2) {
            int ret;
            if (color1 == color2) {
                return color2;
            }
            if (color1 == 0) {
                return color2;
            }
            if (color2 == 0) {
                return color1;
            }

            float t1 = 1 - t;
            ret = 0xFF & (((int) ((color1 & 0xFF) * t1 + (color2 & 0xFF) * t)));
            color1 >>= 8;
                    color2 >>= 8;

                    ret |= 0xFF00 & (((int) ((color1 & 0xFF) * t1 + (color2 & 0xFF) * t)) << 8);
                    color1 >>= 8;
                    color2 >>= 8;
            ret |= 0xFF0000 & (((int) ((color1 & 0xFF) * t1 + (color2 & 0xFF) * t)) << 16);
            color1 >>= 8;
            color2 >>= 8;
            ret |= 0xFF000000 & (((int) ((color1 & 0xFF) * t1 + (color2 & 0xFF) * t)) << 24);

            return ret;
        }

        public boolean isVisible(int[] state) {
            int match = 0;
            for (int i = 0; i < state.length; i++) {
                int v = getState(state[i]);
                if (v != STATE_UNDEFINED) {
                    if (v==STATE_TRUE) {
                        match++;
                    } else {
                        return false;
                    }
                }
            }
            return match == mNumberOfTrue;
        }
    }

    private static class VNode {
        char type;
        float[] params;
        private static float[] current = new float[4];
        public VNode(char type, float[] params) {
            this.type = type;
            this.params = params;
        }

        public VNode(VNode n) {
            this.type = n.type;
            this.params = Arrays.copyOf(n.params, n.params.length);
        }

        public VNode(VNode n1, VNode n2, float t) {
            this.type = n1.type;
            this.params = new float[n1.params.length];
            interpolate(n1, n2, t);
        }

        private boolean match(VNode n) {
            if (n.type != type) {
                return false;
            }
            return (params.length == n.params.length);
        }

        public void interpolate(VNode n1, VNode n2, float t) {
            for (int i = 0; i < n1.params.length; i++) {
                params[i] = n1.params[i] * (1 - t) + n2.params[i] * t;
            }
        }

        private void nodeListToPath(VNode[] node, Path path) {
            float[] current = new float[4];
            for (int i = 0; i < node.length; i++) {
                addCommand(path, current, node[i].type, node[i].params);
            }
        }

        public static void createPath(VNode[] node, Path path) {
            Arrays.fill(current,0);
            for (int i = 0; i < node.length; i++) {
                addCommand(path, current, node[i].type, node[i].params);
            }
        }

        private static void addCommand(Path path, float[] current, char cmd, float[] val) {

            int incr = 2;
            float currentX = current[0];
            float currentY = current[1];
            float ctrlPointX = current[2];
            float ctrlPointY = current[3];

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
                // TODO: build test to prove all permutations work
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
                        path.rCubicTo(val[k + 0],
                                val[k + 1],
                                val[k + 2],
                                val[k + 3],
                                val[k + 4],
                                val[k + 5]);

                        ctrlPointX = currentX + val[k + 2];
                        ctrlPointY = currentY + val[k + 3];
                        currentX += val[k + 4];
                        currentY += val[k + 5];

                        break;
                    case 'C': // curveto - Draws a cubic Bézier curve
                        path.cubicTo(val[k + 0],
                                val[k + 1],
                                val[k + 2],
                                val[k + 3],
                                val[k + 4],
                                val[k + 5]);
                        currentX = val[k + 4];
                        currentY = val[k + 5];
                        ctrlPointX = val[k + 2];
                        ctrlPointY = val[k + 3];

                        break;
                    case 's': // smooth curveto - Draws a cubic Bézier curve (reflective cp)
                        path.rCubicTo(currentX - ctrlPointX, currentY  - ctrlPointY,
                                val[k + 0], val[k + 1],
                                val[k + 2], val[k + 3]);

                        ctrlPointX = currentX + val[k + 0];
                        ctrlPointY = currentY + val[k + 1];
                        currentX += val[k + 2];
                        currentY += val[k + 3];
                        break;
                    case 'S': // shorthand/smooth curveto Draws a cubic Bézier curve(reflective cp)
                        path.cubicTo(2 * currentX - ctrlPointX,
                                2 * currentY - ctrlPointY,
                                val[k + 0],
                                val[k + 1],
                                val[k + 2],
                                val[k + 3]);
                        currentX = val[k + 2];
                        currentY = val[k + 3];
                        ctrlPointX = val[k + 0];
                        ctrlPointY = val[k + 1];
                        break;
                    case 'q': // Draws a quadratic Bézier (relative)
                        path.rQuadTo(val[k + 0], val[k + 1], val[k + 2], val[k + 3]);
                        currentX += val[k + 2];
                        currentY += val[k + 3];
                        ctrlPointX = val[k + 0];
                        ctrlPointY = val[k + 1];
                        break;
                    case 'Q': // Draws a quadratic Bézier
                        path.quadTo(val[k + 0], val[k + 1], val[k + 2], val[k + 3]);
                        currentX = val[k + 2];
                        currentY = val[k + 3];
                        ctrlPointX = val[k + 0];
                        ctrlPointY = val[k + 1];
                        break;
                    case 't': // Draws a quadratic Bézier curve(reflective control point)(relative)
                        path.rQuadTo(currentX - ctrlPointX, currentY - ctrlPointY,
                                val[k + 0], val[k + 1]);
                        ctrlPointX = ctrlPointX + currentX;
                        ctrlPointY = ctrlPointY + currentY;
                        currentX += val[k + 0];
                        currentY += val[k + 1];

                        break;
                    case 'T': // Draws a quadratic Bézier curve (reflective control point)
                        path.quadTo(currentX * 2 - ctrlPointX, currentY * 2 - ctrlPointY,
                                val[k + 0], val[k + 1]);
                        currentX = val[k + 0];
                        currentY = val[k + 1]; // TODO: Check this logic
                        ctrlPointX = -(val[k + 0] - currentX);
                        ctrlPointY = -(val[k + 1] - currentY);
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
