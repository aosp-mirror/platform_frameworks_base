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

import static com.android.ide.common.rendering.api.Result.Status.ERROR_ANIM_NOT_FOUND;
import static com.android.ide.common.rendering.api.Result.Status.ERROR_INFLATION;
import static com.android.ide.common.rendering.api.Result.Status.ERROR_LOCK_INTERRUPTED;
import static com.android.ide.common.rendering.api.Result.Status.ERROR_NOT_INFLATED;
import static com.android.ide.common.rendering.api.Result.Status.ERROR_TIMEOUT;
import static com.android.ide.common.rendering.api.Result.Status.ERROR_UNKNOWN;
import static com.android.ide.common.rendering.api.Result.Status.ERROR_VIEWGROUP_NO_CHILDREN;
import static com.android.ide.common.rendering.api.Result.Status.SUCCESS;

import com.android.ide.common.rendering.api.IAnimationListener;
import com.android.ide.common.rendering.api.ILayoutPullParser;
import com.android.ide.common.rendering.api.IProjectCallback;
import com.android.ide.common.rendering.api.LayoutLog;
import com.android.ide.common.rendering.api.Params;
import com.android.ide.common.rendering.api.RenderResources;
import com.android.ide.common.rendering.api.RenderSession;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.Result;
import com.android.ide.common.rendering.api.StyleResourceValue;
import com.android.ide.common.rendering.api.ViewInfo;
import com.android.ide.common.rendering.api.Params.RenderingMode;
import com.android.ide.common.rendering.api.RenderResources.FrameworkResourceIdProvider;
import com.android.ide.common.rendering.api.Result.Status;
import com.android.internal.util.XmlUtils;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.android.BridgeInflater;
import com.android.layoutlib.bridge.android.BridgeLayoutParamsMapAttributes;
import com.android.layoutlib.bridge.android.BridgeWindow;
import com.android.layoutlib.bridge.android.BridgeWindowSession;
import com.android.layoutlib.bridge.android.BridgeXmlBlockParser;
import com.android.resources.Density;
import com.android.resources.ResourceType;
import com.android.resources.ScreenSize;
import com.android.util.Pair;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.LayoutTransition;
import android.animation.LayoutTransition.TransitionListener;
import android.app.Fragment_Delegate;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.QuickContactBadge;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Class implementing the render session.
 *
 * A session is a stateful representation of a layout file. It is initialized with data coming
 * through the {@link Bridge} API to inflate the layout. Further actions and rendering can then
 * be done on the layout.
 *
 */
public class RenderSessionImpl extends FrameworkResourceIdProvider {

    private static final int DEFAULT_TITLE_BAR_HEIGHT = 25;
    private static final int DEFAULT_STATUS_BAR_HEIGHT = 25;

    /**
     * The current context being rendered. This is set through {@link #acquire(long)} and
     * {@link #init(long)}, and unset in {@link #release()}.
     */
    private static BridgeContext sCurrentContext = null;

    private final Params mParams;

    // scene state
    private RenderSession mScene;
    private BridgeContext mContext;
    private BridgeXmlBlockParser mBlockParser;
    private BridgeInflater mInflater;
    private ResourceValue mWindowBackground;
    private FrameLayout mViewRoot;
    private Canvas mCanvas;
    private int mMeasuredScreenWidth = -1;
    private int mMeasuredScreenHeight = -1;
    private boolean mIsAlphaChannelImage = true;

    private int mStatusBarSize;
    private int mTopBarSize;
    private int mSystemBarSize;
    private int mTopOffset;
    private int mTotalBarSize;


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
    public RenderSessionImpl(Params params) {
        // copy the params.
        mParams = new Params(params);
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
    public Result init(long timeout) {
        // acquire the lock. if the result is null, lock was just acquired, otherwise, return
        // the result.
        Result result = acquireLock(timeout);
        if (result != null) {
            return result;
        }

        // setup the display Metrics.
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.densityDpi = mParams.getDensity();
        metrics.density = mParams.getDensity() / (float) DisplayMetrics.DENSITY_DEFAULT;
        metrics.scaledDensity = metrics.density;
        metrics.widthPixels = mParams.getScreenWidth();
        metrics.heightPixels = mParams.getScreenHeight();
        metrics.xdpi = mParams.getXdpi();
        metrics.ydpi = mParams.getYdpi();

        RenderResources resources = mParams.getResources();

        // build the context
        mContext = new BridgeContext(mParams.getProjectKey(), metrics, resources,
                mParams.getProjectCallback(), mParams.getTargetSdkVersion());

        // use default of true in case it's not found to use alpha by default
        mIsAlphaChannelImage  = getBooleanThemeValue(resources,
                "windowIsFloating", true /*defaultValue*/);


        setUp();

        findBackground(resources);
        findStatusBar(resources, metrics);
        findTopBar(resources, metrics);
        findSystemBar(resources, metrics);

        mTopOffset = mStatusBarSize + mTopBarSize;
        mTotalBarSize = mTopOffset + mSystemBarSize;

        // build the inflater and parser.
        mInflater = new BridgeInflater(mContext, mParams.getProjectCallback());
        mContext.setBridgeInflater(mInflater);
        mInflater.setFactory2(mContext);

        mBlockParser = new BridgeXmlBlockParser(mParams.getLayoutDescription(),
                mContext, false /* platformResourceFlag */);

        return SUCCESS.createResult();
    }

    /**
     * Prepares the scene for action.
     * <p>
     * This call is blocking if another rendering/inflating is currently happening, and will return
     * whether the preparation worked.
     *
     * The preparation can fail if another rendering took too long and the timeout was elapsed.
     *
     * More than one call to this from the same thread will have no effect and will return
     * {@link Result#SUCCESS}.
     *
     * After scene actions have taken place, only one call to {@link #release()} must be
     * done.
     *
     * @param timeout the time to wait if another rendering is happening.
     *
     * @return whether the scene was prepared
     *
     * @see #release()
     *
     * @throws IllegalStateException if {@link #init(long)} was never called.
     */
    public Result acquire(long timeout) {
        if (mContext == null) {
            throw new IllegalStateException("After scene creation, #init() must be called");
        }

        // acquire the lock. if the result is null, lock was just acquired, otherwise, return
        // the result.
        Result result = acquireLock(timeout);
        if (result != null) {
            return result;
        }

        setUp();

        return SUCCESS.createResult();
    }

    /**
     * Acquire the lock so that the scene can be acted upon.
     * <p>
     * This returns null if the lock was just acquired, otherwise it returns
     * {@link Result#SUCCESS} if the lock already belonged to that thread, or another
     * instance (see {@link Result#getStatus()}) if an error occurred.
     *
     * @param timeout the time to wait if another rendering is happening.
     * @return null if the lock was just acquire or another result depending on the state.
     *
     * @throws IllegalStateException if the current context is different than the one owned by
     *      the scene.
     */
    private Result acquireLock(long timeout) {
        ReentrantLock lock = Bridge.getLock();
        if (lock.isHeldByCurrentThread() == false) {
            try {
                boolean acquired = lock.tryLock(timeout, TimeUnit.MILLISECONDS);

                if (acquired == false) {
                    return ERROR_TIMEOUT.createResult();
                }
            } catch (InterruptedException e) {
                return ERROR_LOCK_INTERRUPTED.createResult();
            }
        } else {
            // This thread holds the lock already. Checks that this wasn't for a different context.
            // If this is called by init, mContext will be null and so should sCurrentContext
            // anyway
            if (mContext != sCurrentContext) {
                throw new IllegalStateException("Acquiring different scenes from same thread without releases");
            }
            return SUCCESS.createResult();
        }

        return null;
    }

    /**
     * Cleans up the scene after an action.
     */
    public void release() {
        ReentrantLock lock = Bridge.getLock();

        // with the use of finally blocks, it is possible to find ourself calling this
        // without a successful call to prepareScene. This test makes sure that unlock() will
        // not throw IllegalMonitorStateException.
        if (lock.isHeldByCurrentThread()) {
            tearDown();
            lock.unlock();
        }
    }

    /**
     * Sets up the session for rendering.
     * <p/>
     * The counterpart is {@link #tearDown()}.
     */
    private void setUp() {
        // make sure the Resources object references the context (and other objects) for this
        // scene
        mContext.initResources();
        sCurrentContext = mContext;

        LayoutLog currentLog = mParams.getLog();
        Bridge.setLog(currentLog);
        mContext.getRenderResources().setFrameworkResourceIdProvider(this);
        mContext.getRenderResources().setLogger(currentLog);
    }

    /**
     * Tear down the session after rendering.
     * <p/>
     * The counterpart is {@link #setUp()}.
     */
    private void tearDown() {
        // Make sure to remove static references, otherwise we could not unload the lib
        mContext.disposeResources();
        sCurrentContext = null;

        Bridge.setLog(null);
        mContext.getRenderResources().setFrameworkResourceIdProvider(null);
        mContext.getRenderResources().setLogger(null);
    }

    public static BridgeContext getCurrentContext() {
        return sCurrentContext;
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

            mViewRoot = new FrameLayout(mContext);

            // Sets the project callback (custom view loader) to the fragment delegate so that
            // it can instantiate the custom Fragment.
            Fragment_Delegate.setProjectCallback(mParams.getProjectCallback());

            View view = mInflater.inflate(mBlockParser, mViewRoot);

            Fragment_Delegate.setProjectCallback(null);

            // set the AttachInfo on the root view.
            AttachInfo info = new AttachInfo(new BridgeWindowSession(), new BridgeWindow(),
                    new Handler(), null);
            info.mHasWindowFocus = true;
            info.mWindowVisibility = View.VISIBLE;
            info.mInTouchMode = false; // this is so that we can display selections.
            info.mHardwareAccelerated = false;
            mViewRoot.dispatchAttachedToWindow(info, 0);

            // post-inflate process. For now this supports TabHost/TabWidget
            postInflateProcess(view, mParams.getProjectCallback());

            // get the background drawable
            if (mWindowBackground != null) {
                Drawable d = ResourceHelper.getDrawable(mWindowBackground,
                        mContext, true /* isFramework */);
                mViewRoot.setBackgroundDrawable(d);
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
     * @see SceneParams#getRenderingMode()
     * @see LayoutScene#render(long)
     */
    public Result render(boolean freshRender) {
        checkLock();

        try {
            if (mViewRoot == null) {
                return ERROR_NOT_INFLATED.createResult();
            }
            // measure the views
            int w_spec, h_spec;

            RenderingMode renderingMode = mParams.getRenderingMode();

            // only do the screen measure when needed.
            boolean newRenderSize = false;
            if (mMeasuredScreenWidth == -1) {
                newRenderSize = true;
                mMeasuredScreenWidth = mParams.getScreenWidth();
                mMeasuredScreenHeight = mParams.getScreenHeight() - mTotalBarSize;

                if (renderingMode != RenderingMode.NORMAL) {
                    // measure the full size needed by the layout.
                    w_spec = MeasureSpec.makeMeasureSpec(mMeasuredScreenWidth,
                            renderingMode.isHorizExpand() ?
                                    MeasureSpec.UNSPECIFIED // this lets us know the actual needed size
                                    : MeasureSpec.EXACTLY);
                    h_spec = MeasureSpec.makeMeasureSpec(mMeasuredScreenHeight,
                            renderingMode.isVertExpand() ?
                                    MeasureSpec.UNSPECIFIED // this lets us know the actual needed size
                                    : MeasureSpec.EXACTLY);
                    mViewRoot.measure(w_spec, h_spec);

                    if (renderingMode.isHorizExpand()) {
                        int neededWidth = mViewRoot.getChildAt(0).getMeasuredWidth();
                        if (neededWidth > mMeasuredScreenWidth) {
                            mMeasuredScreenWidth = neededWidth;
                        }
                    }

                    if (renderingMode.isVertExpand()) {
                        int neededHeight = mViewRoot.getChildAt(0).getMeasuredHeight();
                        if (neededHeight > mMeasuredScreenHeight) {
                            mMeasuredScreenHeight = neededHeight;
                        }
                    }
                }
            }

            // remeasure with the size we need
            // This must always be done before the call to layout
            w_spec = MeasureSpec.makeMeasureSpec(mMeasuredScreenWidth, MeasureSpec.EXACTLY);
            h_spec = MeasureSpec.makeMeasureSpec(mMeasuredScreenHeight, MeasureSpec.EXACTLY);
            mViewRoot.measure(w_spec, h_spec);

            // now do the layout.
            mViewRoot.layout(0, 0, mMeasuredScreenWidth, mMeasuredScreenHeight);

            mViewRoot.mAttachInfo.mTreeObserver.dispatchOnPreDraw();

            // draw the views
            // create the BufferedImage into which the layout will be rendered.
            boolean newImage = false;
            if (newRenderSize || mCanvas == null) {
                if (mParams.getImageFactory() != null) {
                    mImage = mParams.getImageFactory().getImage(
                            mMeasuredScreenWidth,
                            mMeasuredScreenHeight + mTotalBarSize);
                } else {
                    mImage = new BufferedImage(
                            mMeasuredScreenWidth,
                            mMeasuredScreenHeight + mTotalBarSize,
                            BufferedImage.TYPE_INT_ARGB);
                    newImage = true;
                }

                if (mParams.isBgColorOverridden()) {
                    // since we override the content, it's the same as if it was a new image.
                    newImage = true;
                    Graphics2D gc = mImage.createGraphics();
                    gc.setColor(new Color(mParams.getOverrideBgColor(), true));
                    gc.setComposite(AlphaComposite.Src);
                    gc.fillRect(0, 0, mMeasuredScreenWidth,
                            mMeasuredScreenHeight + mTotalBarSize);
                    gc.dispose();
                }

                // create an Android bitmap around the BufferedImage
                Bitmap bitmap = Bitmap_Delegate.createBitmap(mImage,
                        true /*isMutable*/,
                        Density.getEnum(mParams.getDensity()));

                // create a Canvas around the Android bitmap
                mCanvas = new Canvas(bitmap);
                mCanvas.setDensity(mParams.getDensity());
                mCanvas.translate(0, mTopOffset);
            }

            if (freshRender && newImage == false) {
                Graphics2D gc = mImage.createGraphics();
                gc.setComposite(AlphaComposite.Src);

                if (mStatusBarSize > 0) {
                    gc.setColor(new Color(0xFF3C3C3C, true));
                    gc.fillRect(0, 0, mMeasuredScreenWidth, mStatusBarSize);
                }

                if (mTopBarSize > 0) {
                    gc.setColor(new Color(0xFF7F7F7F, true));
                    gc.fillRect(0, mStatusBarSize, mMeasuredScreenWidth, mTopOffset);
                }

                // erase the rest
                gc.setColor(new Color(0x00000000, true));
                gc.fillRect(0, mTopOffset,
                        mMeasuredScreenWidth, mMeasuredScreenHeight + mTopOffset);

                if (mSystemBarSize > 0) {
                    gc.setColor(new Color(0xFF3C3C3C, true));
                    gc.fillRect(0, mMeasuredScreenHeight + mTopOffset,
                            mMeasuredScreenWidth, mMeasuredScreenHeight + mTotalBarSize);
                }

                // done
                gc.dispose();
            }

            mViewRoot.draw(mCanvas);

            mViewInfoList = visitAllChildren((ViewGroup)mViewRoot, mContext, mTopOffset);

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
     * Animate an object
     * <p>
     * {@link #acquire(long)} must have been called before this.
     *
     * @throws IllegalStateException if the current context is different than the one owned by
     *      the scene, or if {@link #acquire(long)} was not called.
     *
     * @see LayoutScene#animate(Object, String, boolean, IAnimationListener)
     */
    public Result animate(Object targetObject, String animationName,
            boolean isFrameworkAnimation, IAnimationListener listener) {
        checkLock();

        // find the animation file.
        ResourceValue animationResource = null;
        int animationId = 0;
        if (isFrameworkAnimation) {
            animationResource = mContext.getRenderResources().getFrameworkResource(
                    ResourceType.ANIMATOR, animationName);
            if (animationResource != null) {
                animationId = Bridge.getResourceId(ResourceType.ANIMATOR, animationName);
            }
        } else {
            animationResource = mContext.getRenderResources().getProjectResource(
                    ResourceType.ANIMATOR, animationName);
            if (animationResource != null) {
                animationId = mContext.getProjectCallback().getResourceId(
                        ResourceType.ANIMATOR, animationName);
            }
        }

        if (animationResource != null) {
            try {
                Animator anim = AnimatorInflater.loadAnimator(mContext, animationId);
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
     * @see LayoutScene#insertChild(Object, ILayoutPullParser, int, IAnimationListener)
     */
    public Result insertChild(final ViewGroup parentView, ILayoutPullParser childXml,
            final int index, IAnimationListener listener) {
        checkLock();

        // create a block parser for the XML
        BridgeXmlBlockParser blockParser = new BridgeXmlBlockParser(childXml, mContext,
                false /* platformResourceFlag */);

        // inflate the child without adding it to the root since we want to control where it'll
        // get added. We do pass the parentView however to ensure that the layoutParams will
        // be created correctly.
        final View child = mInflater.inflate(blockParser, parentView, false /*attachToRoot*/);

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
     * @see LayoutScene#moveChild(Object, Object, int, Map, IAnimationListener)
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

                    public void startTransition(LayoutTransition transition, ViewGroup container,
                            View view, int transitionType) {
                        if (transitionType == LayoutTransition.CHANGE_DISAPPEARING) {
                            mChangeDisappearingCount++;
                        }
                    }

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
     * @see LayoutScene#removeChild(Object, IAnimationListener)
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

    /**
     * Returns the log associated with the session.
     * @return the log or null if there are none.
     */
    public LayoutLog getLog() {
        if (mParams != null) {
            return mParams.getLog();
        }

        return null;
    }

    /**
     * Checks that the lock is owned by the current thread and that the current context is the one
     * from this scene.
     *
     * @throws IllegalStateException if the current context is different than the one owned by
     *      the scene, or if {@link #acquire(long)} was not called.
     */
    private void checkLock() {
        ReentrantLock lock = Bridge.getLock();
        if (lock.isHeldByCurrentThread() == false) {
            throw new IllegalStateException("scene must be acquired first. see #acquire(long)");
        }
        if (sCurrentContext != mContext) {
            throw new IllegalStateException("Thread acquired a scene but is rendering a different one");
        }
    }

    private void findBackground(RenderResources resources) {
        if (mParams.isBgColorOverridden() == false) {
            mWindowBackground = resources.findItemInTheme("windowBackground");
            if (mWindowBackground != null) {
                mWindowBackground = resources.resolveResValue(mWindowBackground);
            }
        }
    }

    private boolean isTabletUi() {
        return mParams.getConfigScreenSize() == ScreenSize.XLARGE;
    }

    private boolean isHCApp() {
        RenderResources resources = mContext.getRenderResources();

        // the app must say it targets 11+ and the theme name must extend Theme.Holo or
        // Theme.Holo.Light (which does not extend Theme.Holo, but Theme.Light)
        if (mParams.getTargetSdkVersion() < 11) {
            return false;
        }

        StyleResourceValue currentTheme = resources.getCurrentTheme();
        StyleResourceValue holoTheme = resources.getTheme("Theme.Holo", true /*frameworkTheme*/);

        if (currentTheme == holoTheme ||
                resources.themeIsParentOf(holoTheme, currentTheme)) {
            return true;
        }

        StyleResourceValue holoLightTheme = resources.getTheme("Theme.Holo.Light",
                true /*frameworkTheme*/);

        if (currentTheme == holoLightTheme ||
                resources.themeIsParentOf(holoLightTheme, currentTheme)) {
            return true;
        }

        return false;
    }

    private void findStatusBar(RenderResources resources, DisplayMetrics metrics) {
        if (isTabletUi() == false) {
            boolean windowFullscreen = getBooleanThemeValue(resources,
                    "windowFullscreen", false /*defaultValue*/);

            if (windowFullscreen == false) {
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
    }

    private void findTopBar(RenderResources resources, DisplayMetrics metrics) {
        boolean windowIsFloating = getBooleanThemeValue(resources,
                "windowIsFloating", true /*defaultValue*/);

        if (windowIsFloating == false) {
            if (isHCApp()) {
                findActionBar(resources, metrics);
            } else {
                findTitleBar(resources, metrics);
            }
        }
    }

    private void findActionBar(RenderResources resources, DisplayMetrics metrics) {
        boolean windowActionBar = getBooleanThemeValue(resources,
                "windowActionBar", true /*defaultValue*/);

        // if there's a value and it's false (default is true)
        if (windowActionBar) {

            // default size of the window title bar
            mTopBarSize = DEFAULT_TITLE_BAR_HEIGHT;

            // get value from the theme.
            ResourceValue value = resources.findItemInTheme("actionBarSize");

            // resolve it
            value = resources.resolveResValue(value);

            if (value != null) {
                // get the numerical value, if available
                TypedValue typedValue = ResourceHelper.getValue(value.getValue());
                if (typedValue != null) {
                    // compute the pixel value based on the display metrics
                    mTopBarSize = (int)typedValue.getDimension(metrics);
                }
            }
        }
    }

    private void findTitleBar(RenderResources resources, DisplayMetrics metrics) {
        boolean windowNoTitle = getBooleanThemeValue(resources,
                "windowNoTitle", false /*defaultValue*/);

        if (windowNoTitle == false) {

            // default size of the window title bar
            mTopBarSize = DEFAULT_TITLE_BAR_HEIGHT;

            // get value from the theme.
            ResourceValue value = resources.findItemInTheme("windowTitleSize");

            // resolve it
            value = resources.resolveResValue(value);

            if (value != null) {
                // get the numerical value, if available
                TypedValue typedValue = ResourceHelper.getValue(value.getValue());
                if (typedValue != null) {
                    // compute the pixel value based on the display metrics
                    mTopBarSize = (int)typedValue.getDimension(metrics);
                }
            }
        }
    }

    private void findSystemBar(RenderResources resources, DisplayMetrics metrics) {
        if (isTabletUi() && getBooleanThemeValue(
                resources, "windowIsFloating", true /*defaultValue*/) == false) {

            // default value
            mSystemBarSize = 56; // ??

            // get the real value
            ResourceValue value = resources.getFrameworkResource(ResourceType.DIMEN,
                    "status_bar_height");

            if (value != null) {
                TypedValue typedValue = ResourceHelper.getValue(value.getValue());
                if (typedValue != null) {
                    // compute the pixel value based on the display metrics
                    mSystemBarSize = (int)typedValue.getDimension(metrics);
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
        } else if (view instanceof QuickContactBadge) {
            QuickContactBadge badge = (QuickContactBadge) view;
            badge.setImageToDefault();
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
                            return new LinearLayout(mContext);
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


    /**
     * Visits a View and its children and generate a {@link ViewInfo} containing the
     * bounds of all the views.
     * @param view the root View
     * @param context the context.
     */
    private ViewInfo visit(View view, BridgeContext context, int offset) {
        if (view == null) {
            return null;
        }

        ViewInfo result = new ViewInfo(view.getClass().getName(),
                context.getViewKey(view),
                view.getLeft(), view.getTop() + offset, view.getRight(), view.getBottom() + offset,
                view, view.getLayoutParams());

        if (view instanceof ViewGroup) {
            ViewGroup group = ((ViewGroup) view);
            result.setChildren(visitAllChildren(group, context, 0 /*offset*/));
        }

        return result;
    }

    private List<ViewInfo> visitAllChildren(ViewGroup viewGroup, BridgeContext context,
            int offset) {
        if (viewGroup == null) {
            return null;
        }

        List<ViewInfo> children = new ArrayList<ViewInfo>();
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            children.add(visit(viewGroup.getChildAt(i), context, offset));
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
        return mContext.getDefaultPropMap(viewObject);
    }

    public void setScene(RenderSession session) {
        mScene = session;
    }

    public RenderSession getSession() {
        return mScene;
    }

    // --- FrameworkResourceIdProvider methods

    @Override
    public Integer getId(ResourceType resType, String resName) {
        return Bridge.getResourceId(resType, resName);
    }
}
