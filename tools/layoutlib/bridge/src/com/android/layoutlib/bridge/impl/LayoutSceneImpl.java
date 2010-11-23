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

import com.android.internal.util.XmlUtils;
import com.android.layoutlib.api.IProjectCallback;
import com.android.layoutlib.api.IResourceValue;
import com.android.layoutlib.api.IStyleResourceValue;
import com.android.layoutlib.api.LayoutBridge;
import com.android.layoutlib.api.SceneParams;
import com.android.layoutlib.api.SceneResult;
import com.android.layoutlib.api.ViewInfo;
import com.android.layoutlib.api.IDensityBasedResourceValue.Density;
import com.android.layoutlib.api.SceneParams.RenderingMode;
import com.android.layoutlib.bridge.BridgeConstants;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.android.BridgeInflater;
import com.android.layoutlib.bridge.android.BridgeWindow;
import com.android.layoutlib.bridge.android.BridgeWindowSession;
import com.android.layoutlib.bridge.android.BridgeXmlBlockParser;

import android.app.Fragment_Delegate;
import android.graphics.Bitmap;
import android.graphics.Bitmap_Delegate;
import android.graphics.Canvas;
import android.graphics.Canvas_Delegate;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.AttachInfo;
import android.view.View.MeasureSpec;
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

    private final SceneParams mParams;

    // scene state
    private BridgeContext mContext;
    private BridgeXmlBlockParser mBlockParser;
    private BridgeInflater mInflater;
    private IStyleResourceValue mCurrentTheme;
    private int mScreenOffset;
    private IResourceValue mWindowBackground;
    private FrameLayout mViewRoot;

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
     *
     * This also calls {@link LayoutSceneImpl#prepare()}.
     * <p>
     * <b>THIS MUST BE INSIDE A SYNCHRONIZED BLOCK on the BRIDGE OBJECT.<b>
     *
     * @see LayoutBridge#createScene(com.android.layoutlib.api.SceneParams)
     */
    public LayoutSceneImpl(SceneParams params) {
        // we need to make sure the Looper has been initialized for this thread.
        // this is required for View that creates Handler objects.
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        // copy the params.
        mParams = new SceneParams(params);

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
        Map<IStyleResourceValue, IStyleResourceValue> styleParentMap =
            new HashMap<IStyleResourceValue, IStyleResourceValue>();

        mCurrentTheme = computeStyleMaps(mParams.getThemeName(), mParams.getIsProjectTheme(),
                mParams.getProjectResources().get(BridgeConstants.RES_STYLE),
                mParams.getFrameworkResources().get(BridgeConstants.RES_STYLE), styleParentMap);

        // build the context
        mContext = new BridgeContext(mParams.getProjectKey(), metrics, mCurrentTheme,
                mParams.getProjectResources(), mParams.getFrameworkResources(),
                styleParentMap, mParams.getProjectCallback(), mParams.getLogger());

        // make sure the Resources object references the context (and other objects) for this
        // scene
        mContext.initResources();

        // get the screen offset and window-background resource
        mWindowBackground = null;
        mScreenOffset = 0;
        if (mCurrentTheme != null && mParams.isCustomBackgroundEnabled() == false) {
            mWindowBackground = mContext.findItemInStyle(mCurrentTheme, "windowBackground");
            mWindowBackground = mContext.resolveResValue(mWindowBackground);

            mScreenOffset = getScreenOffset(mParams.getFrameworkResources(), mCurrentTheme, mContext);
        }

        // build the inflater and parser.
        mInflater = new BridgeInflater(mContext, mParams.getProjectCallback());
        mContext.setBridgeInflater(mInflater);
        mInflater.setFactory2(mContext);

        mBlockParser = new BridgeXmlBlockParser(mParams.getLayoutDescription(),
                mContext, false /* platformResourceFlag */);
    }

    /**
     * Prepares the scene for action.
     * <p>
     * <b>THIS MUST BE INSIDE A SYNCHRONIZED BLOCK on the BRIDGE OBJECT.<b>
     */
    public void prepare() {
        // we need to make sure the Looper has been initialized for this thread.
        // this is required for View that creates Handler objects.
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        // make sure the Resources object references the context (and other objects) for this
        // scene
        mContext.initResources();
    }

    /**
     * Cleans up the scene after an action.
     * <p>
     * <b>THIS MUST BE INSIDE A SYNCHRONIZED BLOCK on the BRIDGE OBJECT.<b>
     */
    public void cleanup() {
        // clean up the looper
        Looper.sThreadLocal.remove();

        // Make sure to remove static references, otherwise we could not unload the lib
        mContext.disposeResources();
    }

    /**
     * Inflates the layout.
     * <p>
     * <b>THIS MUST BE INSIDE A SYNCHRONIZED BLOCK on the BRIDGE OBJECT.<b>
     */
    public SceneResult inflate() {
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

            return SceneResult.SUCCESS;
        } catch (PostInflateException e) {
            return new SceneResult("Error during post inflation process:\n" + e.getMessage());
        } catch (Throwable e) {
            // get the real cause of the exception.
            Throwable t = e;
            while (t.getCause() != null) {
                t = t.getCause();
            }

            // log it
            mParams.getLogger().error(t);

            return new SceneResult("Unknown error during inflation.", t);
        }
    }

    /**
     * Renders the scene.
     * <p>
     * <b>THIS MUST BE INSIDE A SYNCHRONIZED BLOCK on the BRIDGE OBJECT.<b>
     */
    public SceneResult render() {
        try {
            if (mViewRoot == null) {
                return new SceneResult("Layout has not been inflated!");
            }
            // measure the views
            int w_spec, h_spec;

            int renderScreenWidth = mParams.getScreenWidth();
            int renderScreenHeight = mParams.getScreenHeight();

            RenderingMode renderingMode = mParams.getRenderingMode();

            if (renderingMode != RenderingMode.NORMAL) {
                // measure the full size needed by the layout.
                w_spec = MeasureSpec.makeMeasureSpec(renderScreenWidth,
                        renderingMode.isHorizExpand() ?
                                MeasureSpec.UNSPECIFIED // this lets us know the actual needed size
                                : MeasureSpec.EXACTLY);
                h_spec = MeasureSpec.makeMeasureSpec(renderScreenHeight - mScreenOffset,
                        renderingMode.isVertExpand() ?
                                MeasureSpec.UNSPECIFIED // this lets us know the actual needed size
                                : MeasureSpec.EXACTLY);
                mViewRoot.measure(w_spec, h_spec);

                if (renderingMode.isHorizExpand()) {
                    int neededWidth = mViewRoot.getChildAt(0).getMeasuredWidth();
                    if (neededWidth > renderScreenWidth) {
                        renderScreenWidth = neededWidth;
                    }
                }

                if (renderingMode.isVertExpand()) {
                    int neededHeight = mViewRoot.getChildAt(0).getMeasuredHeight();
                    if (neededHeight > renderScreenHeight - mScreenOffset) {
                        renderScreenHeight = neededHeight + mScreenOffset;
                    }
                }
            }

            // remeasure with the size we need
            // This must always be done before the call to layout
            w_spec = MeasureSpec.makeMeasureSpec(renderScreenWidth, MeasureSpec.EXACTLY);
            h_spec = MeasureSpec.makeMeasureSpec(renderScreenHeight - mScreenOffset,
                    MeasureSpec.EXACTLY);
            mViewRoot.measure(w_spec, h_spec);

            // now do the layout.
            mViewRoot.layout(0, mScreenOffset, renderScreenWidth, renderScreenHeight);

            // draw the views
            // create the BufferedImage into which the layout will be rendered.
            mImage = new BufferedImage(renderScreenWidth, renderScreenHeight - mScreenOffset,
                    BufferedImage.TYPE_INT_ARGB);

            if (mParams.isCustomBackgroundEnabled()) {
                Graphics2D gc = mImage.createGraphics();
                gc.setColor(new Color(mParams.getCustomBackgroundColor(), true));
                gc.fillRect(0, 0, renderScreenWidth, renderScreenHeight - mScreenOffset);
                gc.dispose();
            }

            // create an Android bitmap around the BufferedImage
            Bitmap bitmap = Bitmap_Delegate.createBitmap(mImage,
                    true /*isMutable*/,
                    Density.getEnum(mParams.getDensity()));

            // create a Canvas around the Android bitmap
            Canvas canvas = new Canvas(bitmap);
            canvas.setDensity(mParams.getDensity());

            // to set the logger, get the native delegate
            Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(canvas);
            canvasDelegate.setLogger(mParams.getLogger());

            mViewRoot.draw(canvas);
            canvasDelegate.dispose();

            mViewInfo = visit(((ViewGroup)mViewRoot).getChildAt(0), mContext);

            // success!
            return SceneResult.SUCCESS;
        } catch (Throwable e) {
            // get the real cause of the exception.
            Throwable t = e;
            while (t.getCause() != null) {
                t = t.getCause();
            }

            // log it
            mParams.getLogger().error(t);

            return new SceneResult("Unknown error during inflation.", t);
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
    private IStyleResourceValue computeStyleMaps(
            String themeName, boolean isProjectTheme, Map<String,
            IResourceValue> inProjectStyleMap, Map<String, IResourceValue> inFrameworkStyleMap,
            Map<IStyleResourceValue, IStyleResourceValue> outInheritanceMap) {

        if (inProjectStyleMap != null && inFrameworkStyleMap != null) {
            // first, get the theme
            IResourceValue theme = null;

            // project theme names have been prepended with a *
            if (isProjectTheme) {
                theme = inProjectStyleMap.get(themeName);
            } else {
                theme = inFrameworkStyleMap.get(themeName);
            }

            if (theme instanceof IStyleResourceValue) {
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

                return (IStyleResourceValue)theme;
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
    private void computeStyleInheritance(Collection<IResourceValue> styles,
            Map<String, IResourceValue> inProjectStyleMap,
            Map<String, IResourceValue> inFrameworkStyleMap,
            Map<IStyleResourceValue, IStyleResourceValue> outInheritanceMap) {
        for (IResourceValue value : styles) {
            if (value instanceof IStyleResourceValue) {
                IStyleResourceValue style = (IStyleResourceValue)value;
                IStyleResourceValue parentStyle = null;

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
    private IStyleResourceValue getStyle(String parentName,
            Map<String, IResourceValue> inProjectStyleMap,
            Map<String, IResourceValue> inFrameworkStyleMap) {
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

        IResourceValue parent = null;

        // if allowed, search in the project resources.
        if (frameworkOnly == false && inProjectStyleMap != null) {
            parent = inProjectStyleMap.get(name);
        }

        // if not found, then look in the framework resources.
        if (parent == null) {
            parent = inFrameworkStyleMap.get(name);
        }

        // make sure the result is the proper class type and return it.
        if (parent instanceof IStyleResourceValue) {
            return (IStyleResourceValue)parent;
        }

        mParams.getLogger().error(
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
    private int getScreenOffset(Map<String, Map<String, IResourceValue>> frameworkResources,
            IStyleResourceValue currentTheme, BridgeContext context) {
        int offset = 0;

        // get the title bar flag from the current theme.
        IResourceValue value = context.findItemInStyle(currentTheme, "windowNoTitle");

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
            Map<String, IResourceValue> dimens = frameworkResources.get(BridgeConstants.RES_DIMEN);

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

    public BufferedImage getImage() {
        return mImage;
    }

    public ViewInfo getViewInfo() {
        return mViewInfo;
    }

    public Map<String, String> getDefaultViewPropertyValues(Object viewObject) {
        return mContext.getDefaultPropMap(viewObject);
    }
}
