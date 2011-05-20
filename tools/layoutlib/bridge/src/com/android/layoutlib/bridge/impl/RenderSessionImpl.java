/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.layoutlib.bridge.impl;

import static com.android.ide.common.rendering.api.Result.Status.ERROR_INFLATION;
import static com.android.ide.common.rendering.api.Result.Status.ERROR_NOT_INFLATED;
import static com.android.ide.common.rendering.api.Result.Status.ERROR_UNKNOWN;
import static com.android.ide.common.rendering.api.Result.Status.ERROR_VIEWGROUP_NO_CHILDREN;
import static com.android.ide.common.rendering.api.Result.Status.SUCCESS;

import com.android.ide.common.rendering.api.AdapterBinding;
import com.android.ide.common.rendering.api.IAnimationListener;
import com.android.ide.common.rendering.api.ILayoutPullParser;
import com.android.ide.common.rendering.api.IProjectCallback;
import com.android.ide.common.rendering.api.RenderParams;
import com.android.ide.common.rendering.api.RenderResources;
import com.android.ide.common.rendering.api.RenderSession;
import com.android.ide.common.rendering.api.ResourceReference;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.Result;
import com.android.ide.common.rendering.api.SessionParams;
import com.android.ide.common.rendering.api.ViewInfo;
import com.android.ide.common.rendering.api.Result.Status;
import com.android.ide.common.rendering.api.SessionParams.RenderingMode;
import com.android.internal.util.XmlUtils;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.android.BridgeInflater;
import com.android.layoutlib.bridge.android.BridgeLayoutParamsMapAttributes;
import com.android.layoutlib.bridge.android.BridgeWindow;
import com.android.layoutlib.bridge.android.BridgeWindowSession;
import com.android.layoutlib.bridge.android.BridgeXmlBlockParser;
import com.android.layoutlib.bridge.bars.PhoneSystemBar;
import com.android.layoutlib.bridge.bars.TitleBar;
import com.android.layoutlib.bridge.impl.binding.FakeAdapter;
import com.android.layoutlib.bridge.impl.binding.FakeExpandableAdapter;
import com.android.resources.ResourceType;
import com.android.resources.ScreenSize;
import com.android.util.Pair;

import org.xmlpull.v1.XmlPullParserException;

import android.graphics.Bitmap;
import android.graphics.Bitmap_Delegate;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.AttachInfo;
import android.view.View.MeasureSpec;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewGroup.MarginLayoutParams;
import android.widget.AbsListView;
import android.widget.AbsSpinner;
import android.widget.AdapterView;
import android.widget.ExpandableListView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TabHost;
import android.widget.TabWidget;
import android.widget.TabHost.TabSpec;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Class implementing the render session.
 *
 * A session is a stateful representation of a layout file. It is initialized with data coming
 * through the {@link Bridge} API to inflate the layout. Further actions and rendering can then
 * be done on the layout.
 *
 */
public class RenderSessionImpl extends RenderAction<SessionParams> {

    private static final int DEFAULT_TITLE_BAR_HEIGHT = 25;
    private static final int DEFAULT_STATUS_BAR_HEIGHT = 25;

    // scene state
    private RenderSession mScene;
    private BridgeXmlBlockParser mBlockParser;
    private BridgeInflater mInflater;
    private ResourceValue mWindowBackground;
    private ViewGroup mViewRoot;
    private FrameLayout mContentRoot;
    private Canvas mCanvas;
    private int mMeasuredScreenWidth = -1;
    private int mMeasuredScreenHeight = -1;
    private boolean mIsAlphaChannelImage;
    private boolean mWindowIsFloating;

    private int mStatusBarSize;
    private int mTitleBarSize;


    // information being returned through the API
    private BufferedImage mImage;
    private List<ViewInfo> mViewInfoList;

    private static final class PostInflateException extends Exception {
        private static final long serialVersionUID = 1L;

        public PostInflateException(String message) {
            super(message);
        }
    }

    /**
     * Creates a layout scene with all the information coming from the layout bridge API.
     * <p>
     * This <b>must</b> be followed by a call to {@link RenderSessionImpl#init()}, which act as a
     * call to {@link RenderSessionImpl#acquire(long)}
     *
     * @see LayoutBridge#createScene(com.android.layoutlib.api.SceneParams)
     */
    public RenderSessionImpl(SessionParams params) {
        super(new SessionParams(params));
    }

    /**
     * Initializes and acquires the scene, creating various Android objects such as context,
     * inflater, and parser.
     *
     * @param timeout the time to wait if another rendering is happening.
     *
     * @return whether the scene was prepared
     *
     * @see #acquire(long)
     * @see #release()
     */
    @Override
    public Result init(long timeout) {
        Result result = super.init(timeout);
        if (result.isSuccess() == false) {
            return result;
        }

        SessionParams params = getParams();
        BridgeContext context = getContext();

        RenderResources resources = getParams().getResources();
        DisplayMetrics metrics = getContext().getMetrics();

        // use default of true in case it's not found to use alpha by default
        mIsAlphaChannelImage  = getBooleanThemeValue(resources,
                "windowIsFloating", true /*defaultValue*/);

        mWindowIsFloating = getBooleanThemeValue(resources, "windowIsFloating",
                true /*defaultValue*/);

        findBackground(resources);
        findStatusBar(resources, metrics);
        findTitleBar(resources, metrics);

        // build the inflater and parser.
        mInflater = new BridgeInflater(context, params.getProjectCallback());
        context.setBridgeInflater(mInflater);

        mBlockParser = new BridgeXmlBlockParser(
                params.getLayoutDescription(), context, false /* platformResourceFlag */);

        return SUCCESS.createResult();
    }

    /**
     * Inflates the layout.
     * <p>
     * {@link #acquire(long)} must have been called before this.
     *
     * @throws IllegalStateException if the current context is different than the one owned by
     *      the scene, or if {@link #init(long)} was not called.
     */
    public Result inflate() {
        checkLock();

        try {

            SessionParams params = getParams();
            BridgeContext context = getContext();

            // the view group that receives the window background.
            ViewGroup backgroundView = null;

            if (mWindowIsFloating || params.isForceNoDecor()) {
                backgroundView = mViewRoot = mContentRoot = new FrameLayout(context);
            } else {
                /*
                 * we're creating the following layout
                 *
                   +-------------------------------------------------+
                   | System bar                                      |
                   +-------------------------------------------------+
                   | (Layout with background drawable)               |
                   | +---------------------------------------------+ |
                   | | Title (optional)                            | |
                   | +---------------------------------------------+ |
                   | | Content, vertical extending                 | |
                   | |                                             | |
                   | +---------------------------------------------+ |
                   +-------------------------------------------------+

                 */

                LinearLayout topLayout = new LinearLayout(context);
                mViewRoot = topLayout;
                topLayout.setOrientation(LinearLayout.VERTICAL);

                if (mStatusBarSize > 0) {
                    // system bar
                    try {
                        PhoneSystemBar systemBar = new PhoneSystemBar(context,
                                params.getDensity());
                        systemBar.setLayoutParams(
                                new LinearLayout.LayoutParams(
                                        LayoutParams.MATCH_PARENT, mStatusBarSize));
                        topLayout.addView(systemBar);
                    } catch (XmlPullParserException e) {

                    }
                }

                LinearLayout backgroundLayout = new LinearLayout(context);
                backgroundView = backgroundLayout;
                backgroundLayout.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                layoutParams.weight = 1;
                backgroundLayout.setLayoutParams(layoutParams);
                topLayout.addView(backgroundLayout);


                // if the theme says no title, then the size will be 0
                if (mTitleBarSize > 0) {
                    try {
                        TitleBar titleBar = new TitleBar(context,
                                params.getDensity(), params.getAppLabel());
                        titleBar.setLayoutParams(
                                new LinearLayout.LayoutParams(
                                        LayoutParams.MATCH_PARENT, mTitleBarSize));
                        backgroundLayout.addView(titleBar);
                    } catch (XmlPullParserException e) {

                    }
                }

                // content frame
                mContentRoot = new FrameLayout(context);
                layoutParams = new LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
                layoutParams.weight = 1;
                mContentRoot.setLayoutParams(layoutParams);
                backgroundLayout.addView(mContentRoot);
            }


            View view = mInflater.inflate(mBlockParser, mContentRoot);

            // done with the parser, pop it.
            context.popParser();

            // set the AttachInfo on the root view.
            AttachInfo info = new AttachInfo(new BridgeWindowSession(), new BridgeWindow(),
                    new Handler(), null);
            info.mHasWindowFocus = true;
            info.mWindowVisibility = View.VISIBLE;
            info.mInTouchMode = false; // this is so that we can display selections.
            mViewRoot.dispatchAttachedToWindow(info, 0);

            // post-inflate process. For now this supports TabHost/TabWidget
            postInflateProcess(view, params.getProjectCallback());

            // get the background drawable
            if (mWindowBackground != null && backgroundView != null) {
                Drawable d = ResourceHelper.getDrawable(mWindowBackground, context);
                backgroundView.setBackgroundDrawable(d);
            }

            return SUCCESS.createResult();
        } catch (PostInflateException e) {
            return ERROR_INFLATION.createResult(e.getMessage(), e);
        } catch (Throwable e) {
            // get the real cause of the exception.
            Throwable t = e;
            while (t.getCause() != null) {
                t = t.getCause();
            }

            return ERROR_INFLATION.createResult(t.getMessage(), t);
        }
    }

    /**
     * Renders the scene.
     * <p>
     * {@link #acquire(long)} must have been called before this.
     *
     * @param freshRender whether the render is a new one and should erase the existing bitmap (in
     *      the case where bitmaps are reused). This is typically needed when not playing
     *      animations.)
     *
     * @throws IllegalStateException if the current context is different than the one owned by
     *      the scene, or if {@link #acquire(long)} was not called.
     *
     * @see RenderParams#getRenderingMode()
     * @see RenderSession#render(long)
     */
    public Result render(boolean freshRender) {
        checkLock();

        SessionParams params = getParams();

        try {
            if (mViewRoot == null) {
                return ERROR_NOT_INFLATED.createResult();
            }

            RenderingMode renderingMode = params.getRenderingMode();

            // only do the screen measure when needed.
            boolean newRenderSize = false;
            if (mMeasuredScreenWidth == -1) {
                newRenderSize = true;
                mMeasuredScreenWidth = params.getScreenWidth();
                mMeasuredScreenHeight = params.getScreenHeight();

                if (renderingMode != RenderingMode.NORMAL) {
                    int widthMeasureSpecMode = renderingMode.isHorizExpand() ?
                            MeasureSpec.UNSPECIFIED // this lets us know the actual needed size
                            : MeasureSpec.EXACTLY;
                    int heightMeasureSpecMode = renderingMode.isVertExpand() ?
                            MeasureSpec.UNSPECIFIED // this lets us know the actual needed size
                            : MeasureSpec.EXACTLY;

                    // We used to compare the measured size of the content to the screen size but
                    // this does not work anymore due to the 2 following issues:
                    // - If the content is in a decor (system bar, title/action bar), the root view
                    //   will not resize even with the UNSPECIFIED because of the embedded layout.
                    // - If there is no decor, but a dialog frame, then the dialog padding prevents
                    //   comparing the size of the content to the screen frame (as it would not
                    //   take into account the dialog padding).

                    // The solution is to first get the content size in a normal rendering, inside
                    // the decor or the dialog padding.
                    // Then measure only the content with UNSPECIFIED to see the size difference
                    // and apply this to the screen size.

                    // first measure the full layout, with EXACTLY to get the size of the
                    // content as it is inside the decor/dialog
                    Pair<Integer, Integer> exactMeasure = measureView(
                            mViewRoot, mContentRoot.getChildAt(0),
                            mMeasuredScreenWidth, MeasureSpec.EXACTLY,
                            mMeasuredScreenHeight, MeasureSpec.EXACTLY);

                    // now measure the content only using UNSPECIFIED (where applicable, based on
                    // the rendering mode). This will give us the size the content needs.
                    Pair<Integer, Integer> result = measureView(
                            mContentRoot, mContentRoot.getChildAt(0),
                            mMeasuredScreenWidth, widthMeasureSpecMode,
                            mMeasuredScreenHeight, heightMeasureSpecMode);

                    // now look at the difference and add what is needed.
                    if (renderingMode.isHorizExpand()) {
                        int measuredWidth = exactMeasure.getFirst();
                        int neededWidth = result.getFirst();
                        if (neededWidth > measuredWidth) {
                            mMeasuredScreenWidth += neededWidth - measuredWidth;
                        }
                    }

                    if (renderingMode.isVertExpand()) {
                        int measuredHeight = exactMeasure.getSecond();
                        int neededHeight = result.getSecond();
                        if (neededHeight > measuredHeight) {
                            mMeasuredScreenHeight += neededHeight - measuredHeight;
                        }
                    }
                }
            }

            // measure again with the size we need
            // This must always be done before the call to layout
            measureView(mViewRoot, null /*measuredView*/,
                    mMeasuredScreenWidth, MeasureSpec.EXACTLY,
                    mMeasuredScreenHeight, MeasureSpec.EXACTLY);

            // now do the layout.
            mViewRoot.layout(0, 0, mMeasuredScreenWidth, mMeasuredScreenHeight);

            if (params.isLayoutOnly()) {
                // delete the canvas and image to reset them on the next full rendering
                mImage = null;
                mCanvas = null;
            } else {
                mViewRoot.mAttachInfo.mTreeObserver.dispatchOnPreDraw();

                // draw the views
                // create the BufferedImage into which the layout will be rendered.
                boolean newImage = false;
                if (newRenderSize || mCanvas == null) {
                    if (params.getImageFactory() != null) {
                        mImage = params.getImageFactory().getImage(
                                mMeasuredScreenWidth,
                                mMeasuredScreenHeight);
                    } else {
                        mImage = new BufferedImage(
                                mMeasuredScreenWidth,
                                mMeasuredScreenHeight,
                                BufferedImage.TYPE_INT_ARGB);
                        newImage = true;
                    }

                    if (params.isBgColorOverridden()) {
                        // since we override the content, it's the same as if it was a new image.
                        newImage = true;
                        Graphics2D gc = mImage.createGraphics();
                        gc.setColor(new Color(params.getOverrideBgColor(), true));
                        gc.setComposite(AlphaComposite.Src);
                        gc.fillRect(0, 0, mMeasuredScreenWidth, mMeasuredScreenHeight);
                        gc.dispose();
                    }

                    // create an Android bitmap around the BufferedImage
                    Bitmap bitmap = Bitmap_Delegate.createBitmap(mImage,
                            true /*isMutable*/, params.getDensity());

                    // create a Canvas around the Android bitmap
                    mCanvas = new Canvas(bitmap);
                    mCanvas.setDensity(params.getDensity().getDpiValue());
                }

                if (freshRender && newImage == false) {
                    Graphics2D gc = mImage.createGraphics();
                    gc.setComposite(AlphaComposite.Src);

                    gc.setColor(new Color(0x00000000, true));
                    gc.fillRect(0, 0,
                            mMeasuredScreenWidth, mMeasuredScreenHeight);

                    // done
                    gc.dispose();
                }

                mViewRoot.draw(mCanvas);
            }

            mViewInfoList = startVisitingViews(mViewRoot, 0, params.getExtendedViewInfoMode());

            // success!
            return SUCCESS.createResult();
        } catch (Throwable e) {
            // get the real cause of the exception.
            Throwable t = e;
            while (t.getCause() != null) {
                t = t.getCause();
            }

            return ERROR_UNKNOWN.createResult(t.getMessage(), t);
        }
    }

    /**
     * Executes {@link View#measure(int, int)} on a given view with the given parameters (used
     * to create measure specs with {@link MeasureSpec#makeMeasureSpec(int, int)}.
     *
     * if <var>measuredView</var> is non null, the method returns a {@link Pair} of (width, height)
     * for the view (using {@link View#getMeasuredWidth()} and {@link View#getMeasuredHeight()}).
     *
     * @param viewToMeasure the view on which to execute measure().
     * @param measuredView if non null, the view to query for its measured width/height.
     * @param width the width to use in the MeasureSpec.
     * @param widthMode the MeasureSpec mode to use for the width.
     * @param height the height to use in the MeasureSpec.
     * @param heightMode the MeasureSpec mode to use for the height.
     * @return the measured width/height if measuredView is non-null, null otherwise.
     */
    private Pair<Integer, Integer> measureView(ViewGroup viewToMeasure, View measuredView,
            int width, int widthMode, int height, int heightMode) {
        int w_spec = MeasureSpec.makeMeasureSpec(width, widthMode);
        int h_spec = MeasureSpec.makeMeasureSpec(height, heightMode);
        viewToMeasure.measure(w_spec, h_spec);

        if (measuredView != null) {
            return Pair.of(measuredView.getMeasuredWidth(), measuredView.getMeasuredHeight());
        }

        return null;
    }

    /**
     * Insert a new child into an existing parent.
     * <p>
     * {@link #acquire(long)} must have been called before this.
     *
     * @throws IllegalStateException if the current context is different than the one owned by
     *      the scene, or if {@link #acquire(long)} was not called.
     *
     * @see RenderSession#insertChild(Object, ILayoutPullParser, int, IAnimationListener)
     */
    public Result insertChild(final ViewGroup parentView, ILayoutPullParser childXml,
            final int index, final IAnimationListener listener) {
        checkLock();

        BridgeContext context = getContext();

        // create a block parser for the XML
        BridgeXmlBlockParser blockParser = new BridgeXmlBlockParser(
                childXml, context, false /* platformResourceFlag */);

        // inflate the child without adding it to the root since we want to control where it'll
        // get added. We do pass the parentView however to ensure that the layoutParams will
        // be created correctly.
        final View child = mInflater.inflate(blockParser, parentView, false /*attachToRoot*/);
        blockParser.ensurePopped();

        invalidateRenderingSize();

        if (listener != null) {
            // there is no support for animating views in this API level, so we fake the animation
            // through a no animation thread.
            new Thread("not animated insertChild") {
                @Override
                public void run() {
                    Result result = addView(parentView, child, index);
                    if (result.isSuccess() == false) {
                        listener.done(result);
                    }

                    // ready to do the work, acquire the scene.
                    result = acquire(250);
                    if (result.isSuccess() == false) {
                        listener.done(result);
                        return;
                    }

                    try {
                        result = render(false /*freshRender*/);
                        if (result.isSuccess()) {
                            listener.onNewFrame(RenderSessionImpl.this.getSession());
                        }
                    } finally {
                        release();
                    }

                    listener.done(result);
                }
            }.start();

            // always return success since the real status will come through the listener.
            return SUCCESS.createResult(child);
        }

        // add it to the parentView in the correct location
        Result result = addView(parentView, child, index);
        if (result.isSuccess() == false) {
            return result;
        }

        result = render(false /*freshRender*/);
        if (result.isSuccess()) {
            result = result.getCopyWithData(child);
        }

        return result;
    }

    /**
     * Adds a given view to a given parent at a given index.
     *
     * @param parent the parent to receive the view
     * @param view the view to add to the parent
     * @param index the index where to do the add.
     *
     * @return a Result with {@link Status#SUCCESS} or
     *     {@link Status#ERROR_VIEWGROUP_NO_CHILDREN} if the given parent doesn't support
     *     adding views.
     */
    private Result addView(ViewGroup parent, View view, int index) {
        try {
            parent.addView(view, index);
            return SUCCESS.createResult();
        } catch (UnsupportedOperationException e) {
            // looks like this is a view class that doesn't support children manipulation!
            return ERROR_VIEWGROUP_NO_CHILDREN.createResult();
        }
    }

    /**
     * Moves a view to a new parent at a given location
     * <p>
     * {@link #acquire(long)} must have been called before this.
     *
     * @throws IllegalStateException if the current context is different than the one owned by
     *      the scene, or if {@link #acquire(long)} was not called.
     *
     * @see RenderSession#moveChild(Object, Object, int, Map, IAnimationListener)
     */
    public Result moveChild(final ViewGroup newParentView, final View childView, final int index,
            Map<String, String> layoutParamsMap, final IAnimationListener listener) {
        checkLock();

        invalidateRenderingSize();

        LayoutParams layoutParams = null;
        if (layoutParamsMap != null) {
            // need to create a new LayoutParams object for the new parent.
            layoutParams = newParentView.generateLayoutParams(
                    new BridgeLayoutParamsMapAttributes(layoutParamsMap));
        }

        // get the current parent of the view that needs to be moved.
        final ViewGroup previousParent = (ViewGroup) childView.getParent();

        if (listener != null) {
            final LayoutParams params = layoutParams;

            // there is no support for animating views in this API level, so we fake the animation
            // through a no animation thread.
            new Thread("not animated moveChild") {
                @Override
                public void run() {
                    Result result = moveView(previousParent, newParentView, childView, index,
                            params);
                    if (result.isSuccess() == false) {
                        listener.done(result);
                    }

                    // ready to do the work, acquire the scene.
                    result = acquire(250);
                    if (result.isSuccess() == false) {
                        listener.done(result);
                        return;
                    }

                    try {
                        result = render(false /*freshRender*/);
                        if (result.isSuccess()) {
                            listener.onNewFrame(RenderSessionImpl.this.getSession());
                        }
                    } finally {
                        release();
                    }

                    listener.done(result);
                }
            }.start();

            // always return success since the real status will come through the listener.
            return SUCCESS.createResult(layoutParams);
        }

        Result result = moveView(previousParent, newParentView, childView, index, layoutParams);
        if (result.isSuccess() == false) {
            return result;
        }

        result = render(false /*freshRender*/);
        if (layoutParams != null && result.isSuccess()) {
            result = result.getCopyWithData(layoutParams);
        }

        return result;
    }

    /**
     * Moves a View from its current parent to a new given parent at a new given location, with
     * an optional new {@link LayoutParams} instance
     *
     * @param previousParent the previous parent, still owning the child at the time of the call.
     * @param newParent the new parent
     * @param movedView the view to move
     * @param index the new location in the new parent
     * @param params an option (can be null) {@link LayoutParams} instance.
     *
     * @return a Result with {@link Status#SUCCESS} or
     *     {@link Status#ERROR_VIEWGROUP_NO_CHILDREN} if the given parent doesn't support
     *     adding views.
     */
    private Result moveView(ViewGroup previousParent, final ViewGroup newParent,
            final View movedView, final int index, final LayoutParams params) {
        try {
            // standard code with no animation. pretty simple.
            previousParent.removeView(movedView);

            // add it to the parentView in the correct location
            if (params != null) {
                newParent.addView(movedView, index, params);
            } else {
                newParent.addView(movedView, index);
            }

            return SUCCESS.createResult();
        } catch (UnsupportedOperationException e) {
            // looks like this is a view class that doesn't support children manipulation!
            return ERROR_VIEWGROUP_NO_CHILDREN.createResult();
        }
    }

    /**
     * Removes a child from its current parent.
     * <p>
     * {@link #acquire(long)} must have been called before this.
     *
     * @throws IllegalStateException if the current context is different than the one owned by
     *      the scene, or if {@link #acquire(long)} was not called.
     *
     * @see RenderSession#removeChild(Object, IAnimationListener)
     */
    public Result removeChild(final View childView, final IAnimationListener listener) {
        checkLock();

        invalidateRenderingSize();

        final ViewGroup parent = (ViewGroup) childView.getParent();

        if (listener != null) {
            // there is no support for animating views in this API level, so we fake the animation
            // through a no animation thread.
            new Thread("not animated moveChild") {
                @Override
                public void run() {
                    Result result = removeView(parent, childView);
                    if (result.isSuccess() == false) {
                        listener.done(result);
                    }

                    // ready to do the work, acquire the scene.
                    result = acquire(250);
                    if (result.isSuccess() == false) {
                        listener.done(result);
                        return;
                    }

                    try {
                        result = render(false /*freshRender*/);
                        if (result.isSuccess()) {
                            listener.onNewFrame(RenderSessionImpl.this.getSession());
                        }
                    } finally {
                        release();
                    }

                    listener.done(result);
                }
            }.start();

            // always return success since the real status will come through the listener.
            return SUCCESS.createResult();
        }

        Result result = removeView(parent, childView);
        if (result.isSuccess() == false) {
            return result;
        }

        return render(false /*freshRender*/);
    }

    /**
     * Removes a given view from its current parent.
     *
     * @param view the view to remove from its parent
     *
     * @return a Result with {@link Status#SUCCESS} or
     *     {@link Status#ERROR_VIEWGROUP_NO_CHILDREN} if the given parent doesn't support
     *     adding views.
     */
    private Result removeView(ViewGroup parent, View view) {
        try {
            parent.removeView(view);
            return SUCCESS.createResult();
        } catch (UnsupportedOperationException e) {
            // looks like this is a view class that doesn't support children manipulation!
            return ERROR_VIEWGROUP_NO_CHILDREN.createResult();
        }
    }


    private void findBackground(RenderResources resources) {
        if (getParams().isBgColorOverridden() == false) {
            mWindowBackground = resources.findItemInTheme("windowBackground");
            if (mWindowBackground != null) {
                mWindowBackground = resources.resolveResValue(mWindowBackground);
            }
        }
    }

    private void findStatusBar(RenderResources resources, DisplayMetrics metrics) {
        boolean windowFullscreen = getBooleanThemeValue(resources,
                "windowFullscreen", false /*defaultValue*/);

        if (windowFullscreen == false && mWindowIsFloating == false) {
            // default value
            mStatusBarSize = DEFAULT_STATUS_BAR_HEIGHT;

            // get the real value
            ResourceValue value = resources.getFrameworkResource(ResourceType.DIMEN,
                    "status_bar_height");

            if (value != null) {
                TypedValue typedValue = ResourceHelper.getValue(value.getValue());
                if (typedValue != null) {
                    // compute the pixel value based on the display metrics
                    mStatusBarSize = (int)typedValue.getDimension(metrics);
                }
            }
        }
    }

    private void findTitleBar(RenderResources resources, DisplayMetrics metrics) {
        if (mWindowIsFloating) {
            return;
        }

        boolean windowNoTitle = getBooleanThemeValue(resources,
                "windowNoTitle", false /*defaultValue*/);

        if (windowNoTitle == false) {

            // default size of the window title bar
            mTitleBarSize = DEFAULT_TITLE_BAR_HEIGHT;

            // get value from the theme.
            ResourceValue value = resources.findItemInTheme("windowTitleSize");

            // resolve it
            value = resources.resolveResValue(value);

            if (value != null) {
                // get the numerical value, if available
                TypedValue typedValue = ResourceHelper.getValue(value.getValue());
                if (typedValue != null) {
                    // compute the pixel value based on the display metrics
                    mTitleBarSize = (int)typedValue.getDimension(metrics);
                }
            }
        }
    }

    private boolean getBooleanThemeValue(RenderResources resources,
            String name, boolean defaultValue) {

        // get the title bar flag from the current theme.
        ResourceValue value = resources.findItemInTheme(name);

        // because it may reference something else, we resolve it.
        value = resources.resolveResValue(value);

        // if there's no value, return the default.
        if (value == null || value.getValue() == null) {
            return defaultValue;
        }

        return XmlUtils.convertValueToBoolean(value.getValue(), defaultValue);
    }

    /**
     * Post process on a view hierachy that was just inflated.
     * <p/>At the moment this only support TabHost: If {@link TabHost} is detected, look for the
     * {@link TabWidget}, and the corresponding {@link FrameLayout} and make new tabs automatically
     * based on the content of the {@link FrameLayout}.
     * @param view the root view to process.
     * @param projectCallback callback to the project.
     */
    private void postInflateProcess(View view, IProjectCallback projectCallback)
            throws PostInflateException {
        if (view instanceof TabHost) {
            setupTabHost((TabHost)view, projectCallback);
        } else if (view instanceof AdapterView<?>) {
            // get the view ID.
            int id = view.getId();

            BridgeContext context = getContext();

            // get a ResourceReference from the integer ID.
            ResourceReference listRef = context.resolveId(id);

            if (listRef != null) {
                SessionParams params = getParams();
                AdapterBinding binding = params.getAdapterBindings().get(listRef);

                // if there was no adapter binding, trying to get it from the call back.
                if (binding == null) {
                    binding = params.getProjectCallback().getAdapterBinding(listRef,
                            context.getViewKey(view), view);
                }

                if (binding != null) {

                    if (view instanceof AbsListView) {
                        if ((binding.getFooterCount() > 0 || binding.getHeaderCount() > 0) &&
                                view instanceof ListView) {
                            ListView list = (ListView) view;

                            boolean skipCallbackParser = false;

                            int count = binding.getHeaderCount();
                            for (int i = 0 ; i < count ; i++) {
                                Pair<View, Boolean> pair = context.inflateView(
                                        binding.getHeaderAt(i),
                                        list, false /*attachToRoot*/, skipCallbackParser);
                                if (pair.getFirst() != null) {
                                    list.addHeaderView(pair.getFirst());
                                }

                                skipCallbackParser |= pair.getSecond();
                            }

                            count = binding.getFooterCount();
                            for (int i = 0 ; i < count ; i++) {
                                Pair<View, Boolean> pair = context.inflateView(
                                        binding.getFooterAt(i),
                                        list, false /*attachToRoot*/, skipCallbackParser);
                                if (pair.getFirst() != null) {
                                    list.addFooterView(pair.getFirst());
                                }

                                skipCallbackParser |= pair.getSecond();
                            }
                        }

                        if (view instanceof ExpandableListView) {
                            ((ExpandableListView) view).setAdapter(
                                    new FakeExpandableAdapter(
                                            listRef, binding, params.getProjectCallback()));
                        } else {
                            ((AbsListView) view).setAdapter(
                                    new FakeAdapter(
                                            listRef, binding, params.getProjectCallback()));
                        }
                    } else if (view instanceof AbsSpinner) {
                        ((AbsSpinner) view).setAdapter(
                                new FakeAdapter(
                                        listRef, binding, params.getProjectCallback()));
                    }
                }
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            final int count = group.getChildCount();
            for (int c = 0 ; c < count ; c++) {
                View child = group.getChildAt(c);
                postInflateProcess(child, projectCallback);
            }
        }
    }

    /**
     * Sets up a {@link TabHost} object.
     * @param tabHost the TabHost to setup.
     * @param projectCallback The project callback object to access the project R class.
     * @throws PostInflateException
     */
    private void setupTabHost(TabHost tabHost, IProjectCallback projectCallback)
            throws PostInflateException {
        // look for the TabWidget, and the FrameLayout. They have their own specific names
        View v = tabHost.findViewById(android.R.id.tabs);

        if (v == null) {
            throw new PostInflateException(
                    "TabHost requires a TabWidget with id \"android:id/tabs\".\n");
        }

        if ((v instanceof TabWidget) == false) {
            throw new PostInflateException(String.format(
                    "TabHost requires a TabWidget with id \"android:id/tabs\".\n" +
                    "View found with id 'tabs' is '%s'", v.getClass().getCanonicalName()));
        }

        v = tabHost.findViewById(android.R.id.tabcontent);

        if (v == null) {
            // TODO: see if we can fake tabs even without the FrameLayout (same below when the framelayout is empty)
            throw new PostInflateException(
                    "TabHost requires a FrameLayout with id \"android:id/tabcontent\".");
        }

        if ((v instanceof FrameLayout) == false) {
            throw new PostInflateException(String.format(
                    "TabHost requires a FrameLayout with id \"android:id/tabcontent\".\n" +
                    "View found with id 'tabcontent' is '%s'", v.getClass().getCanonicalName()));
        }

        FrameLayout content = (FrameLayout)v;

        // now process the content of the framelayout and dynamically create tabs for it.
        final int count = content.getChildCount();

        // this must be called before addTab() so that the TabHost searches its TabWidget
        // and FrameLayout.
        tabHost.setup();

        if (count == 0) {
            // Create a dummy child to get a single tab
            TabSpec spec = tabHost.newTabSpec("tag").setIndicator("Tab Label",
                    tabHost.getResources().getDrawable(android.R.drawable.ic_menu_info_details))
                    .setContent(new TabHost.TabContentFactory() {
                        public View createTabContent(String tag) {
                            return new LinearLayout(getContext());
                        }
                    });
            tabHost.addTab(spec);
            return;
        } else {
            // for each child of the framelayout, add a new TabSpec
            for (int i = 0 ; i < count ; i++) {
                View child = content.getChildAt(i);
                String tabSpec = String.format("tab_spec%d", i+1);
                int id = child.getId();
                Pair<ResourceType, String> resource = projectCallback.resolveResourceId(id);
                String name;
                if (resource != null) {
                    name = resource.getSecond();
                } else {
                    name = String.format("Tab %d", i+1); // default name if id is unresolved.
                }
                tabHost.addTab(tabHost.newTabSpec(tabSpec).setIndicator(name).setContent(id));
            }
        }
    }

    private List<ViewInfo> startVisitingViews(View view, int offset, boolean setExtendedInfo) {
        if (view == null) {
            return null;
        }

        // adjust the offset to this view.
        offset += view.getTop();

        if (view == mContentRoot) {
            return visitAllChildren(mContentRoot, offset, setExtendedInfo);
        }

        // otherwise, look for mContentRoot in the children
        if (view instanceof ViewGroup) {
            ViewGroup group = ((ViewGroup) view);

            for (int i = 0; i < group.getChildCount(); i++) {
                List<ViewInfo> list = startVisitingViews(group.getChildAt(i), offset,
                        setExtendedInfo);
                if (list != null) {
                    return list;
                }
            }
        }

        return null;
    }

    /**
     * Visits a View and its children and generate a {@link ViewInfo} containing the
     * bounds of all the views.
     * @param view the root View
     * @param offset an offset for the view bounds.
     * @param setExtendedInfo whether to set the extended view info in the {@link ViewInfo} object.
     */
    private ViewInfo visit(View view, int offset, boolean setExtendedInfo) {
        if (view == null) {
            return null;
        }

        ViewInfo result = new ViewInfo(view.getClass().getName(),
                getContext().getViewKey(view),
                view.getLeft(), view.getTop() + offset, view.getRight(), view.getBottom() + offset,
                view, view.getLayoutParams());

        if (setExtendedInfo) {
            MarginLayoutParams marginParams = null;
            LayoutParams params = view.getLayoutParams();
            if (params instanceof MarginLayoutParams) {
                marginParams = (MarginLayoutParams) params;
            }
            result.setExtendedInfo(view.getBaseline(),
                    marginParams != null ? marginParams.leftMargin : 0,
                    marginParams != null ? marginParams.topMargin : 0,
                    marginParams != null ? marginParams.rightMargin : 0,
                    marginParams != null ? marginParams.bottomMargin : 0);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = ((ViewGroup) view);
            result.setChildren(visitAllChildren(group, 0 /*offset*/, setExtendedInfo));
        }

        return result;
    }

    /**
     * Visits all the children of a given ViewGroup generate a list of {@link ViewInfo}
     * containing the bounds of all the views.
     * @param view the root View
     * @param offset an offset for the view bounds.
     * @param setExtendedInfo whether to set the extended view info in the {@link ViewInfo} object.
     */
    private List<ViewInfo> visitAllChildren(ViewGroup viewGroup, int offset,
            boolean setExtendedInfo) {
        if (viewGroup == null) {
            return null;
        }

        List<ViewInfo> children = new ArrayList<ViewInfo>();
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            children.add(visit(viewGroup.getChildAt(i), offset, setExtendedInfo));
        }
        return children;
    }


    private void invalidateRenderingSize() {
        mMeasuredScreenWidth = mMeasuredScreenHeight = -1;
    }

    public BufferedImage getImage() {
        return mImage;
    }

    public boolean isAlphaChannelImage() {
        return mIsAlphaChannelImage;
    }

    public List<ViewInfo> getViewInfos() {
        return mViewInfoList;
    }

    public Map<String, String> getDefaultProperties(Object viewObject) {
        return getContext().getDefaultPropMap(viewObject);
    }

    public void setScene(RenderSession session) {
        mScene = session;
    }

    public RenderSession getSession() {
        return mScene;
    }
}
