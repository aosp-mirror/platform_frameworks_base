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

import static com.android.layoutlib.api.SceneResult.SceneStatus.ERROR_LOCK_INTERRUPTED;
import static com.android.layoutlib.api.SceneResult.SceneStatus.ERROR_TIMEOUT;
import static com.android.layoutlib.api.SceneResult.SceneStatus.SUCCESS;

import com.android.internal.util.XmlUtils;
import com.android.layoutlib.api.IProjectCallback;
import com.android.layoutlib.api.IXmlPullParser;
import com.android.layoutlib.api.LayoutBridge;
import com.android.layoutlib.api.LayoutScene;
import com.android.layoutlib.api.ResourceDensity;
import com.android.layoutlib.api.ResourceValue;
import com.android.layoutlib.api.SceneParams;
import com.android.layoutlib.api.SceneResult;
import com.android.layoutlib.api.StyleResourceValue;
import com.android.layoutlib.api.ViewInfo;
import com.android.layoutlib.api.LayoutScene.IAnimationListener;
import com.android.layoutlib.api.SceneParams.RenderingMode;
import com.android.layoutlib.api.SceneResult.SceneStatus;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.BridgeConstants;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.android.BridgeInflater;
import com.android.layoutlib.bridge.android.BridgeLayoutParamsMapAttributes;
import com.android.layoutlib.bridge.android.BridgeWindow;
import com.android.layoutlib.bridge.android.BridgeWindowSession;
import com.android.layoutlib.bridge.android.BridgeXmlBlockParser;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.LayoutTransition;
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
import android.widget.TabHost;
import android.widget.TabWidget;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Class managing a layout "scene".
 *
 * A scene is a stateful representation of a layout file. It is initialized with data coming through
 * the {@link LayoutBridge} API to inflate the layout. Further actions and rendering can then
 * be done on the layout.
 *
 */
public class LayoutSceneImpl {

    private static final int DEFAULT_TITLE_BAR_HEIGHT = 25;
    private static final int DEFAULT_STATUS_BAR_HEIGHT = 25;

    /**
     * The current context being rendered. This is set through {@link #acquire(long)} and
     * {@link #init(long)}, and unset in {@link #release()}.
     */
    private static BridgeContext sCurrentContext = null;

    private final SceneParams mParams;

    // scene state
    private LayoutScene mScene;
    private BridgeContext mContext;
    private BridgeXmlBlockParser mBlockParser;
    private BridgeInflater mInflater;
    private StyleResourceValue mCurrentTheme;
    private int mScreenOffset;
    private ResourceValue mWindowBackground;
    private FrameLayout mViewRoot;
    private Canvas mCanvas;
    private int mMeasuredScreenWidth = -1;
    private int mMeasuredScreenHeight = -1;

    // information being returned through the API
    private BufferedImage mImage;
    private ViewInfo mViewInfo;

    private static final class PostInflateException extends Exception {
        private static final long serialVersionUID = 1L;

        public PostInflateException(String message) {
            super(message);
        }
    }

    /**
     * Creates a layout scene with all the information coming from the layout bridge API.
     * <p>
     * This <b>must</b> be followed by a call to {@link LayoutSceneImpl#init()}, which act as a
     * call to {@link LayoutSceneImpl#acquire(long)}
     *
     * @see LayoutBridge#createScene(com.android.layoutlib.api.SceneParams)
     */
    public LayoutSceneImpl(SceneParams params) {
        // copy the params.
        mParams = new SceneParams(params);
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
    public SceneResult init(long timeout) {
        // acquire the lock. if the result is null, lock was just acquired, otherwise, return
        // the result.
        SceneResult result = acquireLock(timeout);
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

        // find the current theme and compute the style inheritance map
        Map<StyleResourceValue, StyleResourceValue> styleParentMap =
            new HashMap<StyleResourceValue, StyleResourceValue>();

        mCurrentTheme = computeStyleMaps(mParams.getThemeName(), mParams.getIsProjectTheme(),
                mParams.getProjectResources().get(BridgeConstants.RES_STYLE),
                mParams.getFrameworkResources().get(BridgeConstants.RES_STYLE), styleParentMap);

        // build the context
        mContext = new BridgeContext(mParams.getProjectKey(), metrics, mCurrentTheme,
                mParams.getProjectResources(), mParams.getFrameworkResources(),
                styleParentMap, mParams.getProjectCallback());

        // set the current rendering context
        sCurrentContext = mContext;

        // make sure the Resources object references the context (and other objects) for this
        // scene
        mContext.initResources();

        // get the screen offset and window-background resource
        mWindowBackground = null;
        mScreenOffset = 0;
        if (mCurrentTheme != null && mParams.isCustomBackgroundEnabled() == false) {
            mWindowBackground = mContext.findItemInStyle(mCurrentTheme, "windowBackground");
            mWindowBackground = mContext.resolveResValue(mWindowBackground);

            mScreenOffset = getScreenOffset(mParams.getFrameworkResources(), mCurrentTheme,
                    mContext);
        }

        // build the inflater and parser.
        mInflater = new BridgeInflater(mContext, mParams.getProjectCallback());
        mContext.setBridgeInflater(mInflater);
        mInflater.setFactory2(mContext);

        mBlockParser = new BridgeXmlBlockParser(mParams.getLayoutDescription(),
                mContext, false /* platformResourceFlag */);

        return SceneStatus.SUCCESS.createResult();
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
     * {@link SceneResult#SUCCESS}.
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
    public SceneResult acquire(long timeout) {
        if (mContext == null) {
            throw new IllegalStateException("After scene creation, #init() must be called");
        }

        // acquire the lock. if the result is null, lock was just acquired, otherwise, return
        // the result.
        SceneResult result = acquireLock(timeout);
        if (result != null) {
            return result;
        }

        // make sure the Resources object references the context (and other objects) for this
        // scene
        mContext.initResources();
        sCurrentContext = mContext;
        Bridge.setLog(mParams.getLog());

        return SUCCESS.createResult();
    }

    /**
     * Acquire the lock so that the scene can be acted upon.
     * <p>
     * This returns null if the lock was just acquired, otherwise it returns
     * {@link SceneResult#SUCCESS} if the lock already belonged to that thread, or another
     * instance (see {@link SceneResult#getStatus()}) if an error occurred.
     *
     * @param timeout the time to wait if another rendering is happening.
     * @return null if the lock was just acquire or another result depending on the state.
     *
     * @throws IllegalStateException if the current context is different than the one owned by
     *      the scene.
     */
    private SceneResult acquireLock(long timeout) {
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
            // Make sure to remove static references, otherwise we could not unload the lib
            mContext.disposeResources();
            Bridge.setLog(null);
            sCurrentContext = null;

            lock.unlock();
        }
    }

    /**
     * Inflates the layout.
     * <p>
     * {@link #acquire(long)} must have been called before this.
     *
     * @throws IllegalStateException if the current context is different than the one owned by
     *      the scene, or if {@link #init(long)} was not called.
     */
    public SceneResult inflate() {
        checkLock();

        try {

            mViewRoot = new FrameLayout(mContext);

            // Sets the project callback (custom view loader) to the fragment delegate so that
            // it can instantiate the custom Fragment.
            Fragment_Delegate.setProjectCallback(mParams.getProjectCallback());

            View view = mInflater.inflate(mBlockParser, mViewRoot);

            // post-inflate process. For now this supports TabHost/TabWidget
            postInflateProcess(view, mParams.getProjectCallback());

            Fragment_Delegate.setProjectCallback(null);

            // set the AttachInfo on the root view.
            AttachInfo info = new AttachInfo(new BridgeWindowSession(), new BridgeWindow(),
                    new Handler(), null);
            info.mHasWindowFocus = true;
            info.mWindowVisibility = View.VISIBLE;
            info.mInTouchMode = false; // this is so that we can display selections.
            mViewRoot.dispatchAttachedToWindow(info, 0);

            // get the background drawable
            if (mWindowBackground != null) {
                Drawable d = ResourceHelper.getDrawable(mWindowBackground,
                        mContext, true /* isFramework */);
                mViewRoot.setBackgroundDrawable(d);
            }

            return SceneStatus.SUCCESS.createResult();
        } catch (PostInflateException e) {
            return SceneStatus.ERROR_INFLATION.createResult(e.getMessage(), e);
        } catch (Throwable e) {
            // get the real cause of the exception.
            Throwable t = e;
            while (t.getCause() != null) {
                t = t.getCause();
            }

            // log it
            mParams.getLog().error("Scene inflate failed", t);

            return SceneStatus.ERROR_INFLATION.createResult(t.getMessage(), t);
        }
    }

    /**
     * Renders the scene.
     * <p>
     * {@link #acquire(long)} must have been called before this.
     *
     * @throws IllegalStateException if the current context is different than the one owned by
     *      the scene, or if {@link #acquire(long)} was not called.
     *
     * @see SceneParams#getRenderingMode()
     * @see LayoutScene#render(long)
     */
    public SceneResult render() {
        checkLock();

        try {
            if (mViewRoot == null) {
                return SceneStatus.ERROR_NOT_INFLATED.createResult();
            }
            // measure the views
            int w_spec, h_spec;

            RenderingMode renderingMode = mParams.getRenderingMode();

            // only do the screen measure when needed.
            boolean newRenderSize = false;
            if (mMeasuredScreenWidth == -1) {
                newRenderSize = true;
                mMeasuredScreenWidth = mParams.getScreenWidth();
                mMeasuredScreenHeight = mParams.getScreenHeight();

                if (renderingMode != RenderingMode.NORMAL) {
                    // measure the full size needed by the layout.
                    w_spec = MeasureSpec.makeMeasureSpec(mMeasuredScreenWidth,
                            renderingMode.isHorizExpand() ?
                                    MeasureSpec.UNSPECIFIED // this lets us know the actual needed size
                                    : MeasureSpec.EXACTLY);
                    h_spec = MeasureSpec.makeMeasureSpec(mMeasuredScreenHeight - mScreenOffset,
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
                        if (neededHeight > mMeasuredScreenHeight - mScreenOffset) {
                            mMeasuredScreenHeight = neededHeight + mScreenOffset;
                        }
                    }
                }
            }

            // remeasure with the size we need
            // This must always be done before the call to layout
            w_spec = MeasureSpec.makeMeasureSpec(mMeasuredScreenWidth, MeasureSpec.EXACTLY);
            h_spec = MeasureSpec.makeMeasureSpec(mMeasuredScreenHeight - mScreenOffset,
                    MeasureSpec.EXACTLY);
            mViewRoot.measure(w_spec, h_spec);

            // now do the layout.
            mViewRoot.layout(0, mScreenOffset, mMeasuredScreenWidth, mMeasuredScreenHeight);

            // draw the views
            // create the BufferedImage into which the layout will be rendered.
            if (newRenderSize || mCanvas == null) {
                if (mParams.getImageFactory() != null) {
                    mImage = mParams.getImageFactory().getImage(mMeasuredScreenWidth,
                            mMeasuredScreenHeight - mScreenOffset);
                } else {
                    mImage = new BufferedImage(mMeasuredScreenWidth,
                            mMeasuredScreenHeight - mScreenOffset, BufferedImage.TYPE_INT_ARGB);
                }

                if (mParams.isCustomBackgroundEnabled()) {
                    Graphics2D gc = mImage.createGraphics();
                    gc.setColor(new Color(mParams.getCustomBackgroundColor(), true));
                    gc.fillRect(0, 0, mMeasuredScreenWidth, mMeasuredScreenHeight - mScreenOffset);
                    gc.dispose();
                }

                // create an Android bitmap around the BufferedImage
                Bitmap bitmap = Bitmap_Delegate.createBitmap(mImage,
                        true /*isMutable*/,
                        ResourceDensity.getEnum(mParams.getDensity()));

                // create a Canvas around the Android bitmap
                mCanvas = new Canvas(bitmap);
                mCanvas.setDensity(mParams.getDensity());
            }

            mViewRoot.draw(mCanvas);

            mViewInfo = visit(((ViewGroup)mViewRoot).getChildAt(0), mContext);

            // success!
            return SceneStatus.SUCCESS.createResult();
        } catch (Throwable e) {
            // get the real cause of the exception.
            Throwable t = e;
            while (t.getCause() != null) {
                t = t.getCause();
            }

            // log it
            mParams.getLog().error("Scene Render failed", t);

            return SceneStatus.ERROR_UNKNOWN.createResult(t.getMessage(), t);
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
    public SceneResult animate(Object targetObject, String animationName,
            boolean isFrameworkAnimation, IAnimationListener listener) {
        checkLock();

        // find the animation file.
        ResourceValue animationResource = null;
        int animationId = 0;
        if (isFrameworkAnimation) {
            animationResource = mContext.getFrameworkResource("anim", animationName);
            if (animationResource != null) {
                animationId = Bridge.getResourceValue("anim", animationName);
            }
        } else {
            animationResource = mContext.getProjectResource("anim", animationName);
            if (animationResource != null) {
                animationId = mContext.getProjectCallback().getResourceValue("anim", animationName);
            }
        }

        if (animationResource != null) {
            try {
                Animator anim = AnimatorInflater.loadAnimator(mContext, animationId);
                if (anim != null) {
                    anim.setTarget(targetObject);

                    new PlayAnimationThread(anim, this, animationName, listener).start();

                    return SceneStatus.SUCCESS.createResult();
                }
            } catch (Exception e) {
                // get the real cause of the exception.
                Throwable t = e;
                while (t.getCause() != null) {
                    t = t.getCause();
                }

                return SceneStatus.ERROR_UNKNOWN.createResult(t.getMessage(), t);
            }
        }

        return SceneStatus.ERROR_ANIM_NOT_FOUND.createResult();
    }

    /**
     * Insert a new child into an existing parent.
     * <p>
     * {@link #acquire(long)} must have been called before this.
     *
     * @throws IllegalStateException if the current context is different than the one owned by
     *      the scene, or if {@link #acquire(long)} was not called.
     *
     * @see LayoutScene#insertChild(Object, IXmlPullParser, int, IAnimationListener)
     */
    public SceneResult insertChild(final ViewGroup parentView, IXmlPullParser childXml,
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
                public SceneResult preAnimation() {
                    parentView.setLayoutTransition(new LayoutTransition());
                    return addView(parentView, child, index);
                }

                @Override
                public void postAnimation() {
                    parentView.setLayoutTransition(null);
                }
            }.start();

            // always return success since the real status will come through the listener.
            return SceneStatus.SUCCESS.createResult(child);
        }

        // add it to the parentView in the correct location
        SceneResult result = addView(parentView, child, index);
        if (result.isSuccess() == false) {
            return result;
        }

        result = render();
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
     * @return a SceneResult with {@link SceneStatus#SUCCESS} or
     *     {@link SceneStatus#ERROR_VIEWGROUP_NO_CHILDREN} if the given parent doesn't support
     *     adding views.
     */
    private SceneResult addView(ViewGroup parent, View view, int index) {
        try {
            parent.addView(view, index);
            return SceneStatus.SUCCESS.createResult();
        } catch (UnsupportedOperationException e) {
            // looks like this is a view class that doesn't support children manipulation!
            return SceneStatus.ERROR_VIEWGROUP_NO_CHILDREN.createResult();
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
    public SceneResult moveChild(final ViewGroup parentView, final View childView, final int index,
            Map<String, String> layoutParamsMap, IAnimationListener listener) {
        checkLock();

        invalidateRenderingSize();

        LayoutParams layoutParams = null;
        if (layoutParamsMap != null) {
            // need to create a new LayoutParams object for the new parent.
            layoutParams = parentView.generateLayoutParams(
                    new BridgeLayoutParamsMapAttributes(layoutParamsMap));
        }

        if (listener != null) {
            final LayoutParams params = layoutParams;
            new AnimationThread(this, "moveChild", listener) {

                @Override
                public SceneResult preAnimation() {
                    parentView.setLayoutTransition(new LayoutTransition());
                    return moveView(parentView, childView, index, params);
                }

                @Override
                public void postAnimation() {
                    parentView.setLayoutTransition(null);
                }
            }.start();

            // always return success since the real status will come through the listener.
            return SceneStatus.SUCCESS.createResult(layoutParams);
        }

        SceneResult result = moveView(parentView, childView, index, layoutParams);
        if (result.isSuccess() == false) {
            return result;
        }

        result = render();
        if (layoutParams != null && result.isSuccess()) {
            result = result.getCopyWithData(layoutParams);
        }

        return result;
    }

    /**
     * Moves a View from its current parent to a new given parent at a new given location, with
     * an optional new {@link LayoutParams} instance
     *
     * @param parent the new parent
     * @param view the view to move
     * @param index the new location in the new parent
     * @param params an option (can be null) {@link LayoutParams} instance.
     *
     * @return a SceneResult with {@link SceneStatus#SUCCESS} or
     *     {@link SceneStatus#ERROR_VIEWGROUP_NO_CHILDREN} if the given parent doesn't support
     *     adding views.
     */
    private SceneResult moveView(ViewGroup parent, View view, int index, LayoutParams params) {
        try {
            ViewGroup previousParent = (ViewGroup) view.getParent();
            previousParent.removeView(view);

            // add it to the parentView in the correct location

            if (params != null) {
                parent.addView(view, index, params);
            } else {
                parent.addView(view, index);
            }

            return SceneStatus.SUCCESS.createResult();
        } catch (UnsupportedOperationException e) {
            // looks like this is a view class that doesn't support children manipulation!
            return SceneStatus.ERROR_VIEWGROUP_NO_CHILDREN.createResult();
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
    public SceneResult removeChild(final View childView, IAnimationListener listener) {
        checkLock();

        invalidateRenderingSize();

        final ViewGroup parent = (ViewGroup) childView.getParent();

        if (listener != null) {
            new AnimationThread(this, "moveChild", listener) {

                @Override
                public SceneResult preAnimation() {
                    parent.setLayoutTransition(new LayoutTransition());
                    return removeView(parent, childView);
                }

                @Override
                public void postAnimation() {
                    parent.setLayoutTransition(null);
                }
            }.start();

            // always return success since the real status will come through the listener.
            return SceneStatus.SUCCESS.createResult();
        }

        SceneResult result = removeView(parent, childView);
        if (result.isSuccess() == false) {
            return result;
        }

        return render();
    }

    /**
     * Removes a given view from its current parent.
     *
     * @param view the view to remove from its parent
     *
     * @return a SceneResult with {@link SceneStatus#SUCCESS} or
     *     {@link SceneStatus#ERROR_VIEWGROUP_NO_CHILDREN} if the given parent doesn't support
     *     adding views.
     */
    private SceneResult removeView(ViewGroup parent, View view) {
        try {
            parent.removeView(view);
            return SceneStatus.SUCCESS.createResult();
        } catch (UnsupportedOperationException e) {
            // looks like this is a view class that doesn't support children manipulation!
            return SceneStatus.ERROR_VIEWGROUP_NO_CHILDREN.createResult();
        }
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


    /**
     * Compute style information from the given list of style for the project and framework.
     * @param themeName the name of the current theme.  In order to differentiate project and
     * platform themes sharing the same name, all project themes must be prepended with
     * a '*' character.
     * @param isProjectTheme Is this a project theme
     * @param inProjectStyleMap the project style map
     * @param inFrameworkStyleMap the framework style map
     * @param outInheritanceMap the map of style inheritance. This is filled by the method
     * @return the {@link IStyleResourceValue} matching <var>themeName</var>
     */
    private StyleResourceValue computeStyleMaps(
            String themeName, boolean isProjectTheme, Map<String,
            ResourceValue> inProjectStyleMap, Map<String, ResourceValue> inFrameworkStyleMap,
            Map<StyleResourceValue, StyleResourceValue> outInheritanceMap) {

        if (inProjectStyleMap != null && inFrameworkStyleMap != null) {
            // first, get the theme
            ResourceValue theme = null;

            // project theme names have been prepended with a *
            if (isProjectTheme) {
                theme = inProjectStyleMap.get(themeName);
            } else {
                theme = inFrameworkStyleMap.get(themeName);
            }

            if (theme instanceof StyleResourceValue) {
                // compute the inheritance map for both the project and framework styles
                computeStyleInheritance(inProjectStyleMap.values(), inProjectStyleMap,
                        inFrameworkStyleMap, outInheritanceMap);

                // Compute the style inheritance for the framework styles/themes.
                // Since, for those, the style parent values do not contain 'android:'
                // we want to force looking in the framework style only to avoid using
                // similarly named styles from the project.
                // To do this, we pass null in lieu of the project style map.
                computeStyleInheritance(inFrameworkStyleMap.values(), null /*inProjectStyleMap */,
                        inFrameworkStyleMap, outInheritanceMap);

                return (StyleResourceValue)theme;
            }
        }

        return null;
    }

    /**
     * Compute the parent style for all the styles in a given list.
     * @param styles the styles for which we compute the parent.
     * @param inProjectStyleMap the map of project styles.
     * @param inFrameworkStyleMap the map of framework styles.
     * @param outInheritanceMap the map of style inheritance. This is filled by the method.
     */
    private void computeStyleInheritance(Collection<ResourceValue> styles,
            Map<String, ResourceValue> inProjectStyleMap,
            Map<String, ResourceValue> inFrameworkStyleMap,
            Map<StyleResourceValue, StyleResourceValue> outInheritanceMap) {
        for (ResourceValue value : styles) {
            if (value instanceof StyleResourceValue) {
                StyleResourceValue style = (StyleResourceValue)value;
                StyleResourceValue parentStyle = null;

                // first look for a specified parent.
                String parentName = style.getParentStyle();

                // no specified parent? try to infer it from the name of the style.
                if (parentName == null) {
                    parentName = getParentName(value.getName());
                }

                if (parentName != null) {
                    parentStyle = getStyle(parentName, inProjectStyleMap, inFrameworkStyleMap);

                    if (parentStyle != null) {
                        outInheritanceMap.put(style, parentStyle);
                    }
                }
            }
        }
    }

    /**
     * Searches for and returns the {@link IStyleResourceValue} from a given name.
     * <p/>The format of the name can be:
     * <ul>
     * <li>[android:]&lt;name&gt;</li>
     * <li>[android:]style/&lt;name&gt;</li>
     * <li>@[android:]style/&lt;name&gt;</li>
     * </ul>
     * @param parentName the name of the style.
     * @param inProjectStyleMap the project style map. Can be <code>null</code>
     * @param inFrameworkStyleMap the framework style map.
     * @return The matching {@link IStyleResourceValue} object or <code>null</code> if not found.
     */
    private StyleResourceValue getStyle(String parentName,
            Map<String, ResourceValue> inProjectStyleMap,
            Map<String, ResourceValue> inFrameworkStyleMap) {
        boolean frameworkOnly = false;

        String name = parentName;

        // remove the useless @ if it's there
        if (name.startsWith(BridgeConstants.PREFIX_RESOURCE_REF)) {
            name = name.substring(BridgeConstants.PREFIX_RESOURCE_REF.length());
        }

        // check for framework identifier.
        if (name.startsWith(BridgeConstants.PREFIX_ANDROID)) {
            frameworkOnly = true;
            name = name.substring(BridgeConstants.PREFIX_ANDROID.length());
        }

        // at this point we could have the format <type>/<name>. we want only the name as long as
        // the type is style.
        if (name.startsWith(BridgeConstants.REFERENCE_STYLE)) {
            name = name.substring(BridgeConstants.REFERENCE_STYLE.length());
        } else if (name.indexOf('/') != -1) {
            return null;
        }

        ResourceValue parent = null;

        // if allowed, search in the project resources.
        if (frameworkOnly == false && inProjectStyleMap != null) {
            parent = inProjectStyleMap.get(name);
        }

        // if not found, then look in the framework resources.
        if (parent == null) {
            parent = inFrameworkStyleMap.get(name);
        }

        // make sure the result is the proper class type and return it.
        if (parent instanceof StyleResourceValue) {
            return (StyleResourceValue)parent;
        }

        assert false;
        mParams.getLog().error(null,
                String.format("Unable to resolve parent style name: %s", parentName));

        return null;
    }

    /**
     * Computes the name of the parent style, or <code>null</code> if the style is a root style.
     */
    private String getParentName(String styleName) {
        int index = styleName.lastIndexOf('.');
        if (index != -1) {
            return styleName.substring(0, index);
        }

        return null;
    }

    /**
     * Returns the top screen offset. This depends on whether the current theme defines the user
     * of the title and status bars.
     * @param frameworkResources The framework resources
     * @param currentTheme The current theme
     * @param context The context
     * @return the pixel height offset
     */
    private int getScreenOffset(Map<String, Map<String, ResourceValue>> frameworkResources,
            StyleResourceValue currentTheme, BridgeContext context) {
        int offset = 0;

        // get the title bar flag from the current theme.
        ResourceValue value = context.findItemInStyle(currentTheme, "windowNoTitle");

        // because it may reference something else, we resolve it.
        value = context.resolveResValue(value);

        // if there's a value and it's true (default is false)
        if (value == null || value.getValue() == null ||
                XmlUtils.convertValueToBoolean(value.getValue(), false /* defValue */) == false) {
            // default size of the window title bar
            int defaultOffset = DEFAULT_TITLE_BAR_HEIGHT;

            // get value from the theme.
            value = context.findItemInStyle(currentTheme, "windowTitleSize");

            // resolve it
            value = context.resolveResValue(value);

            if (value != null) {
                // get the numerical value, if available
                TypedValue typedValue = ResourceHelper.getValue(value.getValue());
                if (typedValue != null) {
                    // compute the pixel value based on the display metrics
                    defaultOffset = (int)typedValue.getDimension(context.getResources().mMetrics);
                }
            }

            offset += defaultOffset;
        }

        // get the fullscreen flag from the current theme.
        value = context.findItemInStyle(currentTheme, "windowFullscreen");

        // because it may reference something else, we resolve it.
        value = context.resolveResValue(value);

        if (value == null || value.getValue() == null ||
                XmlUtils.convertValueToBoolean(value.getValue(), false /* defValue */) == false) {

            // default value
            int defaultOffset = DEFAULT_STATUS_BAR_HEIGHT;

            // get the real value, first the list of Dimensions from the framework map
            Map<String, ResourceValue> dimens = frameworkResources.get(BridgeConstants.RES_DIMEN);

            // now get the value
            value = dimens.get("status_bar_height");
            if (value != null) {
                TypedValue typedValue = ResourceHelper.getValue(value.getValue());
                if (typedValue != null) {
                    // compute the pixel value based on the display metrics
                    defaultOffset = (int)typedValue.getDimension(context.getResources().mMetrics);
                }
            }

            // add the computed offset.
            offset += defaultOffset;
        }

        return offset;

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

        if (count == 0) {
            throw new PostInflateException(
                    "The FrameLayout for the TabHost has no content. Rendering failed.\n");
        }

        // this must be called before addTab() so that the TabHost searches its TabWidget
        // and FrameLayout.
        tabHost.setup();

        // for each child of the framelayout, add a new TabSpec
        for (int i = 0 ; i < count ; i++) {
            View child = content.getChildAt(i);
            String tabSpec = String.format("tab_spec%d", i+1);
            int id = child.getId();
            String[] resource = projectCallback.resolveResourceValue(id);
            String name;
            if (resource != null) {
                name = resource[0]; // 0 is resource name, 1 is resource type.
            } else {
                name = String.format("Tab %d", i+1); // default name if id is unresolved.
            }
            tabHost.addTab(tabHost.newTabSpec(tabSpec).setIndicator(name).setContent(id));
        }
    }


    /**
     * Visits a View and its children and generate a {@link ViewInfo} containing the
     * bounds of all the views.
     * @param view the root View
     * @param context the context.
     */
    private ViewInfo visit(View view, BridgeContext context) {
        if (view == null) {
            return null;
        }

        ViewInfo result = new ViewInfo(view.getClass().getName(),
                context.getViewKey(view),
                view.getLeft(), view.getTop(), view.getRight(), view.getBottom(),
                view, view.getLayoutParams());

        if (view instanceof ViewGroup) {
            ViewGroup group = ((ViewGroup) view);
            List<ViewInfo> children = new ArrayList<ViewInfo>();
            for (int i = 0; i < group.getChildCount(); i++) {
                children.add(visit(group.getChildAt(i), context));
            }
            result.setChildren(children);
        }

        return result;
    }

    private void invalidateRenderingSize() {
        mMeasuredScreenWidth = mMeasuredScreenHeight = -1;
    }

    public BufferedImage getImage() {
        return mImage;
    }

    public ViewInfo getViewInfo() {
        return mViewInfo;
    }

    public Map<String, String> getDefaultViewPropertyValues(Object viewObject) {
        return mContext.getDefaultPropMap(viewObject);
    }

    public void setScene(LayoutScene scene) {
        mScene = scene;
    }

    public LayoutScene getScene() {
        return mScene;
    }
}
