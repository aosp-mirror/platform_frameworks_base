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

import com.android.ide.common.rendering.api.AdapterBinding;
import com.android.ide.common.rendering.api.HardwareConfig;
import com.android.ide.common.rendering.api.IAnimationListener;
import com.android.ide.common.rendering.api.ILayoutPullParser;
import com.android.ide.common.rendering.api.LayoutlibCallback;
import com.android.ide.common.rendering.api.RenderResources;
import com.android.ide.common.rendering.api.RenderSession;
import com.android.ide.common.rendering.api.ResourceReference;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.Result;
import com.android.ide.common.rendering.api.Result.Status;
import com.android.ide.common.rendering.api.SessionParams;
import com.android.ide.common.rendering.api.SessionParams.RenderingMode;
import com.android.ide.common.rendering.api.ViewInfo;
import com.android.ide.common.rendering.api.ViewType;
import com.android.internal.view.menu.ActionMenuItemView;
import com.android.internal.view.menu.BridgeMenuItemImpl;
import com.android.internal.view.menu.IconMenuItemView;
import com.android.internal.view.menu.ListMenuItemView;
import com.android.internal.view.menu.MenuItemImpl;
import com.android.internal.view.menu.MenuView;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.android.BridgeLayoutParamsMapAttributes;
import com.android.layoutlib.bridge.android.BridgeXmlBlockParser;
import com.android.layoutlib.bridge.android.RenderParamsFlags;
import com.android.layoutlib.bridge.android.support.DesignLibUtil;
import com.android.layoutlib.bridge.impl.binding.FakeAdapter;
import com.android.layoutlib.bridge.impl.binding.FakeExpandableAdapter;
import com.android.resources.ResourceType;
import com.android.util.Pair;

import android.animation.AnimationThread;
import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.LayoutTransition;
import android.animation.LayoutTransition.TransitionListener;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Fragment_Delegate;
import android.graphics.Bitmap;
import android.graphics.Bitmap_Delegate;
import android.graphics.Canvas;
import android.preference.Preference_Delegate;
import android.view.AttachInfo_Accessor;
import android.view.BridgeInflater;
import android.view.IWindowManager;
import android.view.IWindowManagerImpl;
import android.view.Surface;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.ViewParent;
import android.view.WindowManagerGlobal_Delegate;
import android.widget.AbsListView;
import android.widget.AbsSpinner;
import android.widget.ActionMenuView;
import android.widget.AdapterView;
import android.widget.ExpandableListView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.QuickContactBadge;
import android.widget.TabHost;
import android.widget.TabHost.TabSpec;
import android.widget.TabWidget;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.android.ide.common.rendering.api.Result.Status.ERROR_ANIM_NOT_FOUND;
import static com.android.ide.common.rendering.api.Result.Status.ERROR_INFLATION;
import static com.android.ide.common.rendering.api.Result.Status.ERROR_NOT_INFLATED;
import static com.android.ide.common.rendering.api.Result.Status.ERROR_UNKNOWN;
import static com.android.ide.common.rendering.api.Result.Status.ERROR_VIEWGROUP_NO_CHILDREN;
import static com.android.ide.common.rendering.api.Result.Status.SUCCESS;
import static com.android.layoutlib.bridge.util.ReflectionUtils.isInstanceOf;

/**
 * Class implementing the render session.
 * <p/>
 * A session is a stateful representation of a layout file. It is initialized with data coming
 * through the {@link Bridge} API to inflate the layout. Further actions and rendering can then
 * be done on the layout.
 */
public class RenderSessionImpl extends RenderAction<SessionParams> {

    // scene state
    private RenderSession mScene;
    private BridgeXmlBlockParser mBlockParser;
    private BridgeInflater mInflater;
    private ViewGroup mViewRoot;
    private FrameLayout mContentRoot;
    private Canvas mCanvas;
    private int mMeasuredScreenWidth = -1;
    private int mMeasuredScreenHeight = -1;
    private boolean mIsAlphaChannelImage;

    // information being returned through the API
    private BufferedImage mImage;
    private List<ViewInfo> mViewInfoList;
    private List<ViewInfo> mSystemViewInfoList;
    private Layout.Builder mLayoutBuilder;

    private static final class PostInflateException extends Exception {
        private static final long serialVersionUID = 1L;

        public PostInflateException(String message) {
            super(message);
        }
    }

    /**
     * Creates a layout scene with all the information coming from the layout bridge API.
     * <p>
     * This <b>must</b> be followed by a call to {@link RenderSessionImpl#init(long)},
     * which act as a
     * call to {@link RenderSessionImpl#acquire(long)}
     *
     * @see Bridge#createSession(SessionParams)
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
        if (!result.isSuccess()) {
            return result;
        }

        SessionParams params = getParams();
        BridgeContext context = getContext();

        // use default of true in case it's not found to use alpha by default
        mIsAlphaChannelImage = ResourceHelper.getBooleanThemeValue(params.getResources(),
                "windowIsFloating", true, true);

        mLayoutBuilder = new Layout.Builder(params, context);

        // FIXME: find those out, and possibly add them to the render params
        boolean hasNavigationBar = true;
        //noinspection ConstantConditions
        IWindowManager iwm = new IWindowManagerImpl(getContext().getConfiguration(),
                context.getMetrics(), Surface.ROTATION_0, hasNavigationBar);
        WindowManagerGlobal_Delegate.setWindowManagerService(iwm);

        // build the inflater and parser.
        mInflater = new BridgeInflater(context, params.getLayoutlibCallback());
        context.setBridgeInflater(mInflater);

        mBlockParser = new BridgeXmlBlockParser(params.getLayoutDescription(), context, false);

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
            mViewRoot = new Layout(mLayoutBuilder);
            mLayoutBuilder = null;  // Done with the builder.
            mContentRoot = ((Layout) mViewRoot).getContentRoot();
            SessionParams params = getParams();
            BridgeContext context = getContext();

            // Sets the project callback (custom view loader) to the fragment delegate so that
            // it can instantiate the custom Fragment.
            Fragment_Delegate.setLayoutlibCallback(params.getLayoutlibCallback());

            String rootTag = params.getFlag(RenderParamsFlags.FLAG_KEY_ROOT_TAG);
            boolean isPreference = "PreferenceScreen".equals(rootTag);
            View view;
            if (isPreference) {
                view = Preference_Delegate.inflatePreference(getContext(), mBlockParser,
                        mContentRoot);
            } else {
                view = mInflater.inflate(mBlockParser, mContentRoot);
            }

            // done with the parser, pop it.
            context.popParser();

            Fragment_Delegate.setLayoutlibCallback(null);

            // set the AttachInfo on the root view.
            AttachInfo_Accessor.setAttachInfo(mViewRoot);

            // post-inflate process. For now this supports TabHost/TabWidget
            postInflateProcess(view, params.getLayoutlibCallback(), isPreference ? view : null);
            mInflater.onDoneInflation();

            setActiveToolbar(view, context, params);

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
     * @see SessionParams#getRenderingMode()
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
            HardwareConfig hardwareConfig = params.getHardwareConfig();

            // only do the screen measure when needed.
            boolean newRenderSize = false;
            if (mMeasuredScreenWidth == -1) {
                newRenderSize = true;
                mMeasuredScreenWidth = hardwareConfig.getScreenWidth();
                mMeasuredScreenHeight = hardwareConfig.getScreenHeight();

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
                    @SuppressWarnings("deprecation")
                    Pair<Integer, Integer> exactMeasure = measureView(
                            mViewRoot, mContentRoot.getChildAt(0),
                            mMeasuredScreenWidth, MeasureSpec.EXACTLY,
                            mMeasuredScreenHeight, MeasureSpec.EXACTLY);

                    // now measure the content only using UNSPECIFIED (where applicable, based on
                    // the rendering mode). This will give us the size the content needs.
                    @SuppressWarnings("deprecation")
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
                        if (mMeasuredScreenWidth < measuredWidth) {
                            // If the screen width is less than the exact measured width,
                            // expand to match.
                            mMeasuredScreenWidth = measuredWidth;
                        }
                    }

                    if (renderingMode.isVertExpand()) {
                        int measuredHeight = exactMeasure.getSecond();
                        int neededHeight = result.getSecond();
                        if (neededHeight > measuredHeight) {
                            mMeasuredScreenHeight += neededHeight - measuredHeight;
                        }
                        if (mMeasuredScreenHeight < measuredHeight) {
                            // If the screen height is less than the exact measured height,
                            // expand to match.
                            mMeasuredScreenHeight = measuredHeight;
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

            handleScrolling(mViewRoot);

            if (params.isLayoutOnly()) {
                // delete the canvas and image to reset them on the next full rendering
                mImage = null;
                mCanvas = null;
            } else {
                AttachInfo_Accessor.dispatchOnPreDraw(mViewRoot);

                // draw the views
                // create the BufferedImage into which the layout will be rendered.
                boolean newImage = false;

                // When disableBitmapCaching is true, we do not reuse mImage and
                // we create a new one in every render.
                // This is useful when mImage is just a wrapper of Graphics2D so
                // it doesn't get cached.
                boolean disableBitmapCaching = Boolean.TRUE.equals(params.getFlag(
                    RenderParamsFlags.FLAG_KEY_DISABLE_BITMAP_CACHING));
                if (newRenderSize || mCanvas == null || disableBitmapCaching) {
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
                            true /*isMutable*/, hardwareConfig.getDensity());

                    if (mCanvas == null) {
                        // create a Canvas around the Android bitmap
                        mCanvas = new Canvas(bitmap);
                    } else {
                        mCanvas.setBitmap(bitmap);
                    }
                    mCanvas.setDensity(hardwareConfig.getDensity().getDpiValue());
                }

                if (freshRender && !newImage) {
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

            mSystemViewInfoList = visitAllChildren(mViewRoot, 0, params.getExtendedViewInfoMode(),
                    false);

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
    @SuppressWarnings("deprecation")  // For the use of Pair
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
     * Animate an object
     * <p>
     * {@link #acquire(long)} must have been called before this.
     *
     * @throws IllegalStateException if the current context is different than the one owned by
     *      the scene, or if {@link #acquire(long)} was not called.
     *
     * @see RenderSession#animate(Object, String, boolean, IAnimationListener)
     */
    public Result animate(Object targetObject, String animationName,
            boolean isFrameworkAnimation, IAnimationListener listener) {
        checkLock();

        BridgeContext context = getContext();

        // find the animation file.
        ResourceValue animationResource;
        int animationId = 0;
        if (isFrameworkAnimation) {
            animationResource = context.getRenderResources().getFrameworkResource(
                    ResourceType.ANIMATOR, animationName);
            if (animationResource != null) {
                animationId = Bridge.getResourceId(ResourceType.ANIMATOR, animationName);
            }
        } else {
            animationResource = context.getRenderResources().getProjectResource(
                    ResourceType.ANIMATOR, animationName);
            if (animationResource != null) {
                animationId = context.getLayoutlibCallback().getResourceId(
                        ResourceType.ANIMATOR, animationName);
            }
        }

        if (animationResource != null) {
            try {
                Animator anim = AnimatorInflater.loadAnimator(context, animationId);
                if (anim != null) {
                    anim.setTarget(targetObject);

                    new PlayAnimationThread(anim, this, animationName, listener).start();

                    return SUCCESS.createResult();
                }
            } catch (Exception e) {
                // get the real cause of the exception.
                Throwable t = e;
                while (t.getCause() != null) {
                    t = t.getCause();
                }

                return ERROR_UNKNOWN.createResult(t.getMessage(), t);
            }
        }

        return ERROR_ANIM_NOT_FOUND.createResult();
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
            final int index, IAnimationListener listener) {
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
            new AnimationThread(this, "insertChild", listener) {

                @Override
                public Result preAnimation() {
                    parentView.setLayoutTransition(new LayoutTransition());
                    return addView(parentView, child, index);
                }

                @Override
                public void postAnimation() {
                    parentView.setLayoutTransition(null);
                }
            }.start();

            // always return success since the real status will come through the listener.
            return SUCCESS.createResult(child);
        }

        // add it to the parentView in the correct location
        Result result = addView(parentView, child, index);
        if (!result.isSuccess()) {
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

            // there is no support for animating views across layouts, so in case the new and old
            // parent views are different we fake the animation through a no animation thread.
            if (previousParent != newParentView) {
                new Thread("not animated moveChild") {
                    @Override
                    public void run() {
                        Result result = moveView(previousParent, newParentView, childView, index,
                                params);
                        if (!result.isSuccess()) {
                            listener.done(result);
                        }

                        // ready to do the work, acquire the scene.
                        result = acquire(250);
                        if (!result.isSuccess()) {
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
            } else {
                new AnimationThread(this, "moveChild", listener) {

                    @Override
                    public Result preAnimation() {
                        // set up the transition for the parent.
                        LayoutTransition transition = new LayoutTransition();
                        previousParent.setLayoutTransition(transition);

                        // tweak the animation durations and start delays (to match the duration of
                        // animation playing just before).
                        // Note: Cannot user Animation.setDuration() directly. Have to set it
                        // on the LayoutTransition.
                        transition.setDuration(LayoutTransition.DISAPPEARING, 100);
                        // CHANGE_DISAPPEARING plays after DISAPPEARING
                        transition.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, 100);

                        transition.setDuration(LayoutTransition.CHANGE_DISAPPEARING, 100);

                        transition.setDuration(LayoutTransition.CHANGE_APPEARING, 100);
                        // CHANGE_APPEARING plays after CHANGE_APPEARING
                        transition.setStartDelay(LayoutTransition.APPEARING, 100);

                        transition.setDuration(LayoutTransition.APPEARING, 100);

                        return moveView(previousParent, newParentView, childView, index, params);
                    }

                    @Override
                    public void postAnimation() {
                        previousParent.setLayoutTransition(null);
                        newParentView.setLayoutTransition(null);
                    }
                }.start();
            }

            // always return success since the real status will come through the listener.
            return SUCCESS.createResult(layoutParams);
        }

        Result result = moveView(previousParent, newParentView, childView, index, layoutParams);
        if (!result.isSuccess()) {
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
            // check if there is a transition on the previousParent.
            LayoutTransition previousTransition = previousParent.getLayoutTransition();
            if (previousTransition != null) {
                // in this case there is an animation. This means we have to wait for the child's
                // parent reference to be null'ed out so that we can add it to the new parent.
                // It is technically removed right before the DISAPPEARING animation is done (if
                // the animation of this type is not null, otherwise it's after which is impossible
                // to handle).
                // Because there is no move animation, if the new parent is the same as the old
                // parent, we need to wait until the CHANGE_DISAPPEARING animation is done before
                // adding the child or the child will appear in its new location before the
                // other children have made room for it.

                // add a listener to the transition to be notified of the actual removal.
                previousTransition.addTransitionListener(new TransitionListener() {
                    private int mChangeDisappearingCount = 0;

                    @Override
                    public void startTransition(LayoutTransition transition, ViewGroup container,
                            View view, int transitionType) {
                        if (transitionType == LayoutTransition.CHANGE_DISAPPEARING) {
                            mChangeDisappearingCount++;
                        }
                    }

                    @Override
                    public void endTransition(LayoutTransition transition, ViewGroup container,
                            View view, int transitionType) {
                        if (transitionType == LayoutTransition.CHANGE_DISAPPEARING) {
                            mChangeDisappearingCount--;
                        }

                        if (transitionType == LayoutTransition.CHANGE_DISAPPEARING &&
                                mChangeDisappearingCount == 0) {
                            // add it to the parentView in the correct location
                            if (params != null) {
                                newParent.addView(movedView, index, params);
                            } else {
                                newParent.addView(movedView, index);
                            }
                        }
                    }
                });

                // remove the view from the current parent.
                previousParent.removeView(movedView);

                // and return since adding the view to the new parent is done in the listener.
                return SUCCESS.createResult();
            } else {
                // standard code with no animation. pretty simple.
                previousParent.removeView(movedView);

                // add it to the parentView in the correct location
                if (params != null) {
                    newParent.addView(movedView, index, params);
                } else {
                    newParent.addView(movedView, index);
                }

                return SUCCESS.createResult();
            }
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
    public Result removeChild(final View childView, IAnimationListener listener) {
        checkLock();

        invalidateRenderingSize();

        final ViewGroup parent = (ViewGroup) childView.getParent();

        if (listener != null) {
            new AnimationThread(this, "moveChild", listener) {

                @Override
                public Result preAnimation() {
                    parent.setLayoutTransition(new LayoutTransition());
                    return removeView(parent, childView);
                }

                @Override
                public void postAnimation() {
                    parent.setLayoutTransition(null);
                }
            }.start();

            // always return success since the real status will come through the listener.
            return SUCCESS.createResult();
        }

        Result result = removeView(parent, childView);
        if (!result.isSuccess()) {
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

    /**
     * Post process on a view hierarchy that was just inflated.
     * <p/>
     * At the moment this only supports TabHost: If {@link TabHost} is detected, look for the
     * {@link TabWidget}, and the corresponding {@link FrameLayout} and make new tabs automatically
     * based on the content of the {@link FrameLayout}.
     * @param view the root view to process.
     * @param layoutlibCallback callback to the project.
     * @param skip the view and it's children are not processed.
     */
    @SuppressWarnings("deprecation")  // For the use of Pair
    private void postInflateProcess(View view, LayoutlibCallback layoutlibCallback, View skip)
            throws PostInflateException {
        if (view == skip) {
            return;
        }
        if (view instanceof TabHost) {
            setupTabHost((TabHost) view, layoutlibCallback);
        } else if (view instanceof QuickContactBadge) {
            QuickContactBadge badge = (QuickContactBadge) view;
            badge.setImageToDefault();
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
                    binding = layoutlibCallback.getAdapterBinding(
                            listRef, context.getViewKey(view), view);
                }

                if (binding != null) {

                    if (view instanceof AbsListView) {
                        if ((binding.getFooterCount() > 0 || binding.getHeaderCount() > 0) &&
                                view instanceof ListView) {
                            ListView list = (ListView) view;

                            boolean skipCallbackParser = false;

                            int count = binding.getHeaderCount();
                            for (int i = 0; i < count; i++) {
                                Pair<View, Boolean> pair = context.inflateView(
                                        binding.getHeaderAt(i),
                                        list, false, skipCallbackParser);
                                if (pair.getFirst() != null) {
                                    list.addHeaderView(pair.getFirst());
                                }

                                skipCallbackParser |= pair.getSecond();
                            }

                            count = binding.getFooterCount();
                            for (int i = 0; i < count; i++) {
                                Pair<View, Boolean> pair = context.inflateView(
                                        binding.getFooterAt(i),
                                        list, false, skipCallbackParser);
                                if (pair.getFirst() != null) {
                                    list.addFooterView(pair.getFirst());
                                }

                                skipCallbackParser |= pair.getSecond();
                            }
                        }

                        if (view instanceof ExpandableListView) {
                            ((ExpandableListView) view).setAdapter(
                                    new FakeExpandableAdapter(listRef, binding, layoutlibCallback));
                        } else {
                            ((AbsListView) view).setAdapter(
                                    new FakeAdapter(listRef, binding, layoutlibCallback));
                        }
                    } else if (view instanceof AbsSpinner) {
                        ((AbsSpinner) view).setAdapter(
                                new FakeAdapter(listRef, binding, layoutlibCallback));
                    }
                }
            }
        } else if (view instanceof ViewGroup) {
            mInflater.postInflateProcess(view);
            ViewGroup group = (ViewGroup) view;
            final int count = group.getChildCount();
            for (int c = 0; c < count; c++) {
                View child = group.getChildAt(c);
                postInflateProcess(child, layoutlibCallback, skip);
            }
        }
    }

    /**
     * If the root layout is a CoordinatorLayout with an AppBar:
     * Set the title of the AppBar to the title of the activity context.
     */
    private void setActiveToolbar(View view, BridgeContext context, SessionParams params) {
        View coordinatorLayout = findChildView(view, DesignLibUtil.CN_COORDINATOR_LAYOUT);
        if (coordinatorLayout == null) {
            return;
        }
        View appBar = findChildView(coordinatorLayout, DesignLibUtil.CN_APPBAR_LAYOUT);
        if (appBar == null) {
            return;
        }
        ViewGroup collapsingToolbar =
                (ViewGroup) findChildView(appBar, DesignLibUtil.CN_COLLAPSING_TOOLBAR_LAYOUT);
        if (collapsingToolbar == null) {
            return;
        }
        if (!hasToolbar(collapsingToolbar)) {
            return;
        }
        RenderResources res = context.getRenderResources();
        String title = params.getAppLabel();
        ResourceValue titleValue = res.findResValue(title, false);
        if (titleValue != null && titleValue.getValue() != null) {
            title = titleValue.getValue();
        }
        DesignLibUtil.setTitle(collapsingToolbar, title);
    }

    private View findChildView(View view, String className) {
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (isInstanceOf(group.getChildAt(i), className)) {
                return group.getChildAt(i);
            }
        }
        return null;
    }

    private boolean hasToolbar(View collapsingToolbar) {
        if (!(collapsingToolbar instanceof ViewGroup)) {
            return false;
        }
        ViewGroup group = (ViewGroup) collapsingToolbar;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (isInstanceOf(group.getChildAt(i), DesignLibUtil.CN_TOOLBAR)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Set the vertical scroll position on all the components with the "scrollY" attribute. If the
     * component supports nested scrolling attempt that first, then use the unconsumed scroll part
     * to scroll the content in the component.
     */
    private void handleScrolling(View view) {
        BridgeContext context = getContext();
        int scrollPos = context.getScrollYPos(view);
        if (scrollPos != 0) {
            if (view.isNestedScrollingEnabled()) {
                int[] consumed = new int[2];
                if (view.startNestedScroll(DesignLibUtil.SCROLL_AXIS_VERTICAL)) {
                    view.dispatchNestedPreScroll(0, scrollPos, consumed, null);
                    view.dispatchNestedScroll(consumed[0], consumed[1], 0, scrollPos, null);
                    view.stopNestedScroll();
                    scrollPos -= consumed[1];
                }
            }
            if (scrollPos != 0) {
                view.scrollBy(0, scrollPos);
            }
        }

        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            handleScrolling(child);
        }
    }

    /**
     * Sets up a {@link TabHost} object.
     * @param tabHost the TabHost to setup.
     * @param layoutlibCallback The project callback object to access the project R class.
     * @throws PostInflateException
     */
    private void setupTabHost(TabHost tabHost, LayoutlibCallback layoutlibCallback)
            throws PostInflateException {
        // look for the TabWidget, and the FrameLayout. They have their own specific names
        View v = tabHost.findViewById(android.R.id.tabs);

        if (v == null) {
            throw new PostInflateException(
                    "TabHost requires a TabWidget with id \"android:id/tabs\".\n");
        }

        if (!(v instanceof TabWidget)) {
            throw new PostInflateException(String.format(
                    "TabHost requires a TabWidget with id \"android:id/tabs\".\n" +
                    "View found with id 'tabs' is '%s'", v.getClass().getCanonicalName()));
        }

        v = tabHost.findViewById(android.R.id.tabcontent);

        if (v == null) {
            // TODO: see if we can fake tabs even without the FrameLayout (same below when the frameLayout is empty)
            //noinspection SpellCheckingInspection
            throw new PostInflateException(
                    "TabHost requires a FrameLayout with id \"android:id/tabcontent\".");
        }

        if (!(v instanceof FrameLayout)) {
            //noinspection SpellCheckingInspection
            throw new PostInflateException(String.format(
                    "TabHost requires a FrameLayout with id \"android:id/tabcontent\".\n" +
                    "View found with id 'tabcontent' is '%s'", v.getClass().getCanonicalName()));
        }

        FrameLayout content = (FrameLayout)v;

        // now process the content of the frameLayout and dynamically create tabs for it.
        final int count = content.getChildCount();

        // this must be called before addTab() so that the TabHost searches its TabWidget
        // and FrameLayout.
        tabHost.setup();

        if (count == 0) {
            // Create a dummy child to get a single tab
            TabSpec spec = tabHost.newTabSpec("tag")
                    .setIndicator("Tab Label", tabHost.getResources()
                            .getDrawable(android.R.drawable.ic_menu_info_details, null))
                    .setContent(new TabHost.TabContentFactory() {
                        @Override
                        public View createTabContent(String tag) {
                            return new LinearLayout(getContext());
                        }
                    });
            tabHost.addTab(spec);
        } else {
            // for each child of the frameLayout, add a new TabSpec
            for (int i = 0 ; i < count ; i++) {
                View child = content.getChildAt(i);
                String tabSpec = String.format("tab_spec%d", i+1);
                @SuppressWarnings("ConstantConditions")  // child cannot be null.
                int id = child.getId();
                @SuppressWarnings("deprecation")
                Pair<ResourceType, String> resource = layoutlibCallback.resolveResourceId(id);
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

    /**
     * Visits a {@link View} and its children and generate a {@link ViewInfo} containing the
     * bounds of all the views.
     *
     * @param view the root View
     * @param offset an offset for the view bounds.
     * @param setExtendedInfo whether to set the extended view info in the {@link ViewInfo} object.
     * @param isContentFrame {@code true} if the {@code ViewInfo} to be created is part of the
     *                       content frame.
     *
     * @return {@code ViewInfo} containing the bounds of the view and it children otherwise.
     */
    private ViewInfo visit(View view, int offset, boolean setExtendedInfo,
            boolean isContentFrame) {
        ViewInfo result = createViewInfo(view, offset, setExtendedInfo, isContentFrame);

        if (view instanceof ViewGroup) {
            ViewGroup group = ((ViewGroup) view);
            result.setChildren(visitAllChildren(group, isContentFrame ? 0 : offset,
                    setExtendedInfo, isContentFrame));
        }
        return result;
    }

    /**
     * Visits all the children of a given ViewGroup and generates a list of {@link ViewInfo}
     * containing the bounds of all the views. It also initializes the {@link #mViewInfoList} with
     * the children of the {@code mContentRoot}.
     *
     * @param viewGroup the root View
     * @param offset an offset from the top for the content view frame.
     * @param setExtendedInfo whether to set the extended view info in the {@link ViewInfo} object.
     * @param isContentFrame {@code true} if the {@code ViewInfo} to be created is part of the
     *                       content frame. {@code false} if the {@code ViewInfo} to be created is
     *                       part of the system decor.
     */
    private List<ViewInfo> visitAllChildren(ViewGroup viewGroup, int offset,
            boolean setExtendedInfo, boolean isContentFrame) {
        if (viewGroup == null) {
            return null;
        }

        if (!isContentFrame) {
            offset += viewGroup.getTop();
        }

        int childCount = viewGroup.getChildCount();
        if (viewGroup == mContentRoot) {
            List<ViewInfo> childrenWithoutOffset = new ArrayList<ViewInfo>(childCount);
            List<ViewInfo> childrenWithOffset = new ArrayList<ViewInfo>(childCount);
            for (int i = 0; i < childCount; i++) {
                ViewInfo[] childViewInfo = visitContentRoot(viewGroup.getChildAt(i), offset,
                        setExtendedInfo);
                childrenWithoutOffset.add(childViewInfo[0]);
                childrenWithOffset.add(childViewInfo[1]);
            }
            mViewInfoList = childrenWithOffset;
            return childrenWithoutOffset;
        } else {
            List<ViewInfo> children = new ArrayList<ViewInfo>(childCount);
            for (int i = 0; i < childCount; i++) {
                children.add(visit(viewGroup.getChildAt(i), offset, setExtendedInfo,
                        isContentFrame));
            }
            return children;
        }
    }

    /**
     * Visits the children of {@link #mContentRoot} and generates {@link ViewInfo} containing the
     * bounds of all the views. It returns two {@code ViewInfo} objects with the same children,
     * one with the {@code offset} and other without the {@code offset}. The offset is needed to
     * get the right bounds if the {@code ViewInfo} hierarchy is accessed from
     * {@code mViewInfoList}. When the hierarchy is accessed via {@code mSystemViewInfoList}, the
     * offset is not needed.
     *
     * @return an array of length two, with ViewInfo at index 0 is without offset and ViewInfo at
     *         index 1 is with the offset.
     */
    @NonNull
    private ViewInfo[] visitContentRoot(View view, int offset, boolean setExtendedInfo) {
        ViewInfo[] result = new ViewInfo[2];
        if (view == null) {
            return result;
        }

        result[0] = createViewInfo(view, 0, setExtendedInfo, true);
        result[1] = createViewInfo(view, offset, setExtendedInfo, true);
        if (view instanceof ViewGroup) {
            List<ViewInfo> children = visitAllChildren((ViewGroup) view, 0, setExtendedInfo, true);
            result[0].setChildren(children);
            result[1].setChildren(children);
        }
        return result;
    }

    /**
     * Creates a {@link ViewInfo} for the view. The {@code ViewInfo} corresponding to the children
     * of the {@code view} are not created. Consequently, the children of {@code ViewInfo} is not
     * set.
     * @param offset an offset for the view bounds. Used only if view is part of the content frame.
     */
    private ViewInfo createViewInfo(View view, int offset, boolean setExtendedInfo,
            boolean isContentFrame) {
        if (view == null) {
            return null;
        }

        ViewInfo result;
        if (isContentFrame) {
            // The view is part of the layout added by the user. Hence,
            // the ViewCookie may be obtained only through the Context.
            result = new ViewInfo(view.getClass().getName(),
                    getContext().getViewKey(view),
                    view.getLeft(), view.getTop() + offset, view.getRight(),
                    view.getBottom() + offset, view, view.getLayoutParams());
        } else {
            // We are part of the system decor.
            SystemViewInfo r = new SystemViewInfo(view.getClass().getName(),
                    getViewKey(view),
                    view.getLeft(), view.getTop(), view.getRight(),
                    view.getBottom(), view, view.getLayoutParams());
            result = r;
            // We currently mark three kinds of views:
            // 1. Menus in the Action Bar
            // 2. Menus in the Overflow popup.
            // 3. The overflow popup button.
            if (view instanceof ListMenuItemView) {
                // Mark 2.
                // All menus in the popup are of type ListMenuItemView.
                r.setViewType(ViewType.ACTION_BAR_OVERFLOW_MENU);
            } else {
                // Mark 3.
                ViewGroup.LayoutParams lp = view.getLayoutParams();
                if (lp instanceof ActionMenuView.LayoutParams &&
                        ((ActionMenuView.LayoutParams) lp).isOverflowButton) {
                    r.setViewType(ViewType.ACTION_BAR_OVERFLOW);
                } else {
                    // Mark 1.
                    // A view is a menu in the Action Bar is it is not the overflow button and of
                    // its parent is of type ActionMenuView. We can also check if the view is
                    // instanceof ActionMenuItemView but that will fail for menus using
                    // actionProviderClass.
                    ViewParent parent = view.getParent();
                    while (parent != mViewRoot && parent instanceof ViewGroup) {
                        if (parent instanceof ActionMenuView) {
                            r.setViewType(ViewType.ACTION_BAR_MENU);
                            break;
                        }
                        parent = parent.getParent();
                    }
                }
            }
        }

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

        return result;
    }

    /* (non-Javadoc)
     * The cookie for menu items are stored in menu item and not in the map from View stored in
     * BridgeContext.
     */
    @Nullable
    private Object getViewKey(View view) {
        BridgeContext context = getContext();
        if (!(view instanceof MenuView.ItemView)) {
            return context.getViewKey(view);
        }
        MenuItemImpl menuItem;
        if (view instanceof ActionMenuItemView) {
            menuItem = ((ActionMenuItemView) view).getItemData();
        } else if (view instanceof ListMenuItemView) {
            menuItem = ((ListMenuItemView) view).getItemData();
        } else if (view instanceof IconMenuItemView) {
            menuItem = ((IconMenuItemView) view).getItemData();
        } else {
            menuItem = null;
        }
        if (menuItem instanceof BridgeMenuItemImpl) {
            return ((BridgeMenuItemImpl) menuItem).getViewCookie();
        }

        return null;
    }

    public void invalidateRenderingSize() {
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

    public List<ViewInfo> getSystemViewInfos() {
        return mSystemViewInfoList;
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
