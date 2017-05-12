/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.layoutlib.bridge.intensive;

import com.android.ide.common.rendering.api.RenderSession;
import com.android.ide.common.rendering.api.SessionParams;
import com.android.ide.common.rendering.api.SessionParams.RenderingMode;
import com.android.ide.common.rendering.api.ViewInfo;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.android.RenderParamsFlags;
import com.android.layoutlib.bridge.impl.RenderAction;
import com.android.layoutlib.bridge.intensive.setup.ConfigGenerator;
import com.android.layoutlib.bridge.intensive.setup.LayoutLibTestCallback;
import com.android.layoutlib.bridge.intensive.setup.LayoutPullParser;
import com.android.resources.Density;
import com.android.resources.Navigation;
import com.android.resources.ResourceType;

import org.junit.Test;

import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Set of render tests
 */
public class RenderTests extends RenderTestBase {

    @Test
    public void testActivity() throws ClassNotFoundException {
        renderAndVerify("activity.xml", "activity.png");
    }

    @Test
    public void testActivityOnOldTheme() throws ClassNotFoundException {
        LayoutLibTestCallback layoutLibCallback =
                new LayoutLibTestCallback(getLogger(), mDefaultClassLoader);
        layoutLibCallback.initResources();

        LayoutPullParser parser = createLayoutPullParser("simple_activity.xml");
        SessionParams params = getSessionParams(parser, ConfigGenerator.NEXUS_5,
                layoutLibCallback, "Theme.NoTitleBar", false,
                RenderingMode.NORMAL, 22);

        renderAndVerify(params, "simple_activity-old-theme.png");
    }

    @Test
    public void testTranslucentBars() throws ClassNotFoundException {
        LayoutLibTestCallback layoutLibCallback =
                new LayoutLibTestCallback(getLogger(), mDefaultClassLoader);
        layoutLibCallback.initResources();

        LayoutPullParser parser = createLayoutPullParser("four_corners.xml");
        SessionParams params = getSessionParams(parser, ConfigGenerator.NEXUS_5,
                layoutLibCallback, "Theme.Material.Light.NoActionBar.TranslucentDecor", false,
                RenderingMode.NORMAL, 22);
        renderAndVerify(params, "four_corners_translucent.png");

        parser = createLayoutPullParser("four_corners.xml");
        params = getSessionParams(parser, ConfigGenerator.NEXUS_5_LAND,
                layoutLibCallback, "Theme.Material.Light.NoActionBar.TranslucentDecor", false,
                RenderingMode.NORMAL, 22);
        renderAndVerify(params, "four_corners_translucent_land.png");

        parser = createLayoutPullParser("four_corners.xml");
        params = getSessionParams(parser, ConfigGenerator.NEXUS_5,
                layoutLibCallback, "Theme.Material.Light.NoActionBar", false,
                RenderingMode.NORMAL, 22);
        renderAndVerify(params, "four_corners.png");
    }

    @Test
    public void testAllWidgets() throws ClassNotFoundException {
        renderAndVerify("allwidgets.xml", "allwidgets.png");

        // We expect fidelity warnings for Path.isConvex. Fail for anything else.
        sRenderMessages.removeIf(message -> message.equals("Path.isConvex is not supported."));
    }

    @Test
    public void testArrayCheck() throws ClassNotFoundException {
        renderAndVerify("array_check.xml", "array_check.png");
    }

    @Test
    public void testAllWidgetsTablet() throws ClassNotFoundException {
        renderAndVerify("allwidgets.xml", "allwidgets_tab.png", ConfigGenerator.NEXUS_7_2012);

        // We expect fidelity warnings for Path.isConvex. Fail for anything else.
        sRenderMessages.removeIf(message -> message.equals("Path.isConvex is not supported."));
    }

    @Test
    public void testActivityActionBar() throws ClassNotFoundException {
        LayoutPullParser parser = createLayoutPullParser("simple_activity.xml");
        LayoutLibTestCallback layoutLibCallback =
                new LayoutLibTestCallback(getLogger(), mDefaultClassLoader);
        layoutLibCallback.initResources();

        SessionParams params = getSessionParams(parser, ConfigGenerator.NEXUS_5,
                layoutLibCallback, "Theme.Material.Light.NoActionBar", false,
                RenderingMode.V_SCROLL, 22);

        renderAndVerify(params, "simple_activity_noactionbar.png");

        parser = createLayoutPullParser("simple_activity.xml");
        params = getSessionParams(parser, ConfigGenerator.NEXUS_5,
                layoutLibCallback, "Theme.Material.Light", false,
                RenderingMode.V_SCROLL, 22);

        renderAndVerify(params, "simple_activity.png");

        // This also tests that a theme with "NoActionBar" DOES HAVE an action bar when we are
        // displaying menus.
        parser = createLayoutPullParser("simple_activity.xml");
        params = getSessionParams(parser, ConfigGenerator.NEXUS_5,
                layoutLibCallback, "Theme.Material.Light.NoActionBar", false,
                RenderingMode.V_SCROLL, 22);
        params.setFlag(RenderParamsFlags.FLAG_KEY_ROOT_TAG, "menu");
        renderAndVerify(params, "simple_activity.png");
    }

    @Test
    public void testOnApplyInsetsCall()
            throws ClassNotFoundException, NoSuchFieldException, IllegalAccessException {
        // We get the widget via reflection to avoid IntelliJ complaining about the class being
        // located in the wrong package. (From the Bridge tests point of view, it is)
        Class insetsWidgetClass = Class.forName("com.android.layoutlib.test.myapplication.widgets" +
                ".InsetsWidget");
        Field field = insetsWidgetClass.getDeclaredField("sApplyInsetsCalled");
        assertFalse((Boolean)field.get(null));

        LayoutPullParser parser = createLayoutPullParser("insets.xml");
        LayoutLibTestCallback layoutLibCallback =
                new LayoutLibTestCallback(getLogger(), mDefaultClassLoader);
        layoutLibCallback.initResources();
        SessionParams params = getSessionParams(parser, ConfigGenerator.NEXUS_5,
                layoutLibCallback, "Theme.Material.Light.NoActionBar", false,
                RenderingMode.NORMAL, 22);

        render(params, -1);

        assertTrue((Boolean)field.get(null));
        field.set(null, false);
    }

    /** Test expand_layout.xml */
    @Test
    public void testExpand() throws ClassNotFoundException {
        // Create the layout pull parser.
        LayoutPullParser parser = createLayoutPullParser("expand_vert_layout.xml");
        // Create LayoutLibCallback.
        LayoutLibTestCallback layoutLibCallback =
                new LayoutLibTestCallback(getLogger(), mDefaultClassLoader);
        layoutLibCallback.initResources();

        ConfigGenerator customConfigGenerator = new ConfigGenerator()
                .setScreenWidth(300)
                .setScreenHeight(20)
                .setDensity(Density.XHIGH)
                .setNavigation(Navigation.NONAV);

        SessionParams params = getSessionParams(parser, customConfigGenerator,
                layoutLibCallback, "Theme.Material.Light.NoActionBar.Fullscreen", false,
                RenderingMode.V_SCROLL, 22);

        renderAndVerify(params, "expand_vert_layout.png");

        customConfigGenerator = new ConfigGenerator()
                .setScreenWidth(20)
                .setScreenHeight(300)
                .setDensity(Density.XHIGH)
                .setNavigation(Navigation.NONAV);
        parser = createLayoutPullParser("expand_horz_layout.xml");
        params = getSessionParams(parser, customConfigGenerator,
                layoutLibCallback, "Theme.Material.Light.NoActionBar.Fullscreen", false,
                RenderingMode.H_SCROLL, 22);

        renderAndVerify(params, "expand_horz_layout.png");
    }

    /** Test indeterminate_progressbar.xml */
    @Test
    public void testVectorAnimation() throws ClassNotFoundException {
        // Create the layout pull parser.
        LayoutPullParser parser = createLayoutPullParser("indeterminate_progressbar.xml");
        // Create LayoutLibCallback.
        LayoutLibTestCallback layoutLibCallback =
                new LayoutLibTestCallback(getLogger(), mDefaultClassLoader);
        layoutLibCallback.initResources();

        SessionParams params = getSessionParams(parser, ConfigGenerator.NEXUS_5,
                layoutLibCallback, "Theme.Material.NoActionBar.Fullscreen", false,
                RenderingMode.V_SCROLL, 22);

        renderAndVerify(params, "animated_vector.png", TimeUnit.SECONDS.toNanos(2));

        parser = createLayoutPullParser("indeterminate_progressbar.xml");
        params = getSessionParams(parser, ConfigGenerator.NEXUS_5,
                layoutLibCallback, "Theme.Material.NoActionBar.Fullscreen", false,
                RenderingMode.V_SCROLL, 22);
        renderAndVerify(params, "animated_vector_1.png", TimeUnit.SECONDS.toNanos(3));
    }

    /**
     * Test a vector drawable that uses trimStart and trimEnd. It also tests all the primitives
     * for vector drawables (lines, moves and cubic and quadratic curves).
     */
    @Test
    public void testVectorDrawable() throws ClassNotFoundException {
        // Create the layout pull parser.
        LayoutPullParser parser = createLayoutPullParser("vector_drawable.xml");
        // Create LayoutLibCallback.
        LayoutLibTestCallback layoutLibCallback =
                new LayoutLibTestCallback(getLogger(), mDefaultClassLoader);
        layoutLibCallback.initResources();

        SessionParams params = getSessionParams(parser, ConfigGenerator.NEXUS_5,
                layoutLibCallback, "Theme.Material.NoActionBar.Fullscreen", false,
                RenderingMode.V_SCROLL, 22);

        renderAndVerify(params, "vector_drawable.png", TimeUnit.SECONDS.toNanos(2));
    }

    /**
     * Regression test for http://b.android.com/91383 and http://b.android.com/203797
     */
    @Test
    public void testVectorDrawable91383() throws ClassNotFoundException {
        // Create the layout pull parser.
        LayoutPullParser parser = createLayoutPullParser("vector_drawable_android.xml");
        // Create LayoutLibCallback.
        LayoutLibTestCallback layoutLibCallback =
                new LayoutLibTestCallback(getLogger(), mDefaultClassLoader);
        layoutLibCallback.initResources();

        SessionParams params = getSessionParams(parser, ConfigGenerator.NEXUS_5,
                layoutLibCallback, "Theme.Material.NoActionBar.Fullscreen", false,
                RenderingMode.V_SCROLL, 22);

        renderAndVerify(params, "vector_drawable_91383.png", TimeUnit.SECONDS.toNanos(2));
    }

    /** Test activity.xml */
    @Test
    public void testScrollingAndMeasure() throws ClassNotFoundException {
        // Create the layout pull parser.
        LayoutPullParser parser = createLayoutPullParser("scrolled.xml");
        // Create LayoutLibCallback.
        LayoutLibTestCallback layoutLibCallback =
                new LayoutLibTestCallback(getLogger(), mDefaultClassLoader);
        layoutLibCallback.initResources();

        SessionParams params = getSessionParams(parser, ConfigGenerator.NEXUS_5,
                layoutLibCallback, "Theme.Material.NoActionBar.Fullscreen", false,
                RenderingMode.V_SCROLL, 22);
        params.setForceNoDecor();
        params.setExtendedViewInfoMode(true);

        // Do an only-measure pass
        RenderSession session = sBridge.createSession(params);
        session.measure();
        RenderResult result = RenderResult.getFromSession(session);
        assertNotNull(result);
        assertNotNull(result.getResult());
        assertTrue(result.getResult().isSuccess());

        ViewInfo rootLayout = result.getRootViews().get(0);
        // Check the first box in the main LinearLayout
        assertEquals(-90, rootLayout.getChildren().get(0).getTop());
        assertEquals(-30, rootLayout.getChildren().get(0).getLeft());
        assertEquals(90, rootLayout.getChildren().get(0).getBottom());
        assertEquals(150, rootLayout.getChildren().get(0).getRight());

        // Check the first box within the nested LinearLayout
        assertEquals(-450, rootLayout.getChildren().get(5).getChildren().get(0).getTop());
        assertEquals(90, rootLayout.getChildren().get(5).getChildren().get(0).getLeft());
        assertEquals(-270, rootLayout.getChildren().get(5).getChildren().get(0).getBottom());
        assertEquals(690, rootLayout.getChildren().get(5).getChildren().get(0).getRight());

        // Do a full render pass
        parser = createLayoutPullParser("scrolled.xml");

        params = getSessionParams(parser, ConfigGenerator.NEXUS_5,
                layoutLibCallback, "Theme.Material.NoActionBar.Fullscreen", false,
                RenderingMode.V_SCROLL, 22);
        params.setForceNoDecor();
        params.setExtendedViewInfoMode(true);

        result = renderAndVerify(params, "scrolled.png");
        assertNotNull(result);
        assertNotNull(result.getResult());
        assertTrue(result.getResult().isSuccess());
    }

    @Test
    public void testGetResourceNameVariants() throws Exception {
        // Setup
        // Create the layout pull parser for our resources (empty.xml can not be part of the test
        // app as it won't compile).
        LayoutPullParser parser = new LayoutPullParser("/empty.xml");
        // Create LayoutLibCallback.
        LayoutLibTestCallback layoutLibCallback =
                new LayoutLibTestCallback(getLogger(), mDefaultClassLoader);
        layoutLibCallback.initResources();
        SessionParams params = getSessionParams(parser, ConfigGenerator.NEXUS_4,
                layoutLibCallback, "AppTheme", true, RenderingMode.NORMAL, 22);
        AssetManager assetManager = AssetManager.getSystem();
        DisplayMetrics metrics = new DisplayMetrics();
        Configuration configuration = RenderAction.getConfiguration(params);
        //noinspection deprecation
        Resources resources = new Resources(assetManager, metrics, configuration);
        resources.mLayoutlibCallback = params.getLayoutlibCallback();
        resources.mContext =
                new BridgeContext(params.getProjectKey(), metrics, params.getResources(),
                        params.getAssets(), params.getLayoutlibCallback(), configuration,
                        params.getTargetSdkVersion(), params.isRtlSupported());
        // Test
        assertEquals("android:style/ButtonBar",
                resources.getResourceName(android.R.style.ButtonBar));
        assertEquals("android", resources.getResourcePackageName(android.R.style.ButtonBar));
        assertEquals("ButtonBar", resources.getResourceEntryName(android.R.style.ButtonBar));
        assertEquals("style", resources.getResourceTypeName(android.R.style.ButtonBar));
        int id = resources.mLayoutlibCallback.getResourceId(ResourceType.STRING, "app_name");
        assertEquals("com.android.layoutlib.test.myapplication:string/app_name",
                resources.getResourceName(id));
        assertEquals("com.android.layoutlib.test.myapplication",
                resources.getResourcePackageName(id));
        assertEquals("string", resources.getResourceTypeName(id));
        assertEquals("app_name", resources.getResourceEntryName(id));
    }

    @Test
    public void testStringEscaping() throws Exception {
        // Setup
        // Create the layout pull parser for our resources (empty.xml can not be part of the test
        // app as it won't compile).
        LayoutPullParser parser = new LayoutPullParser("/empty.xml");
        // Create LayoutLibCallback.
        LayoutLibTestCallback layoutLibCallback =
                new LayoutLibTestCallback(RenderTestBase.getLogger(), mDefaultClassLoader);
        layoutLibCallback.initResources();
        SessionParams params = getSessionParams(parser, ConfigGenerator.NEXUS_4,
                layoutLibCallback, "AppTheme", true, RenderingMode.NORMAL, 22);
        AssetManager assetManager = AssetManager.getSystem();
        DisplayMetrics metrics = new DisplayMetrics();
        Configuration configuration = RenderAction.getConfiguration(params);
        //noinspection deprecation
        Resources resources = new Resources(assetManager, metrics, configuration);
        resources.mLayoutlibCallback = params.getLayoutlibCallback();
        resources.mContext =
                new BridgeContext(params.getProjectKey(), metrics, params.getResources(),
                        params.getAssets(), params.getLayoutlibCallback(), configuration,
                        params.getTargetSdkVersion(), params.isRtlSupported());

        int id = resources.mLayoutlibCallback.getResourceId(ResourceType.ARRAY, "string_array");
        String[] strings = resources.getStringArray(id);
        assertArrayEquals(
                new String[]{"mystring", "Hello world!", "candidates", "Unknown", "?EC"},
                strings);
        assertTrue(sRenderMessages.isEmpty());
    }

    //@Test
    // Temporarily disabled (b/37725933)
    public void testFonts() throws ClassNotFoundException {
        // TODO: styles seem to be broken in TextView
        renderAndVerify("fonts_test.xml", "font_test.png");
    }

    @Test
    public void testAdaptiveIcon() throws ClassNotFoundException {
        // Create the layout pull parser.
        LayoutPullParser parser = createLayoutPullParser("adaptive_icon.xml");
        // Create LayoutLibCallback.
        LayoutLibTestCallback layoutLibCallback =
                new LayoutLibTestCallback(getLogger(), mDefaultClassLoader);
        layoutLibCallback.initResources();

        SessionParams params = getSessionParams(parser, ConfigGenerator.NEXUS_5,
                layoutLibCallback, "Theme.Material.NoActionBar.Fullscreen", false,
                RenderingMode.V_SCROLL, 22);

        renderAndVerify(params, "adaptive_icon.png");
    }

    @Test
    public void testColorTypedValue() throws Exception {
        // Setup
        // Create the layout pull parser for our resources (empty.xml can not be part of the test
        // app as it won't compile).
        LayoutPullParser parser = new LayoutPullParser("/empty.xml");
        // Create LayoutLibCallback.
        LayoutLibTestCallback layoutLibCallback =
                new LayoutLibTestCallback(RenderTestBase.getLogger(), mDefaultClassLoader);
        layoutLibCallback.initResources();
        SessionParams params = getSessionParams(parser, ConfigGenerator.NEXUS_4,
                layoutLibCallback, "AppTheme", true, RenderingMode.NORMAL, 22);
        AssetManager assetManager = AssetManager.getSystem();
        DisplayMetrics metrics = new DisplayMetrics();
        Configuration configuration = RenderAction.getConfiguration(params);
        //noinspection deprecation
        Resources resources = new Resources(assetManager, metrics, configuration);
        resources.mLayoutlibCallback = params.getLayoutlibCallback();
        resources.mContext =
                new BridgeContext(params.getProjectKey(), metrics, params.getResources(),
                        params.getAssets(), params.getLayoutlibCallback(), configuration,
                        params.getTargetSdkVersion(), params.isRtlSupported());

        TypedValue outValue = new TypedValue();
        resources.mContext.resolveThemeAttribute(android.R.attr.colorPrimary, outValue, true);
        assertEquals(TypedValue.TYPE_INT_COLOR_ARGB8, outValue.type);
        assertNotEquals(0, outValue.data);
        assertTrue(sRenderMessages.isEmpty());
    }

    @Test
    public void testRectangleShadow() throws Exception {
        renderAndVerify("shadows_test.xml", "shadows_test.png");
    }
}
