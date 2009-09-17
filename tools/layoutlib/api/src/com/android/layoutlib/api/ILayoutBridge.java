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

package com.android.layoutlib.api;

import java.util.Map;

/**
 * Entry point of the Layout Lib. Implementations of this interface provide a method to compute
 * and render a layout.
 * <p/>
 * <p/>{@link #getApiLevel()} gives the ability to know which methods are available.
 * <p/>
 * Changes in API level 4:
 * <ul>
 * <li>new render method: {@link #computeLayout(IXmlPullParser, Object, int, int, boolean, int, float, float, String, boolean, Map, Map, IProjectCallback, ILayoutLog)}</li>
 * <li>deprecated {@link #computeLayout(IXmlPullParser, Object, int, int, int, float, float, String, boolean, Map, Map, IProjectCallback, ILayoutLog)}</li>
 * </ul>
 * Changes in API level 3:
 * <ul>
 * <li>new render method: {@link #computeLayout(IXmlPullParser, Object, int, int, int, float, float, String, boolean, Map, Map, IProjectCallback, ILayoutLog)}</li>
 * <li>deprecated {@link #computeLayout(IXmlPullParser, Object, int, int, String, boolean, Map, Map, IProjectCallback, ILayoutLog)}</li>
 * </ul>
 * Changes in API level 2:
 * <ul>
 * <li>new API Level method: {@link #getApiLevel()}</li>
 * <li>new render method: {@link #computeLayout(IXmlPullParser, Object, int, int, String, boolean, Map, Map, IProjectCallback, ILayoutLog)}</li>
 * <li>deprecated {@link #computeLayout(IXmlPullParser, Object, int, int, String, Map, Map, IProjectCallback, ILayoutLog)}</li>
 * </ul>
 */
public interface ILayoutBridge {

    final int API_CURRENT = 4;

    /**
     * Returns the API level of the layout library.
     * While no methods will ever be removed, some may become deprecated, and some new ones
     * will appear.
     * <p/>If calling this method throws an {@link AbstractMethodError}, then the API level
     * should be considered to be 1.
     */
    int getApiLevel();

    /**
     * Initializes the Bridge object.
     * @param fontOsLocation the location of the fonts.
     * @param enumValueMap map attrName => { map enumFlagName => Integer value }.
     * @return true if success.
     * @since 1
     */
    boolean init(String fontOsLocation, Map<String, Map<String, Integer>> enumValueMap);

    /**
     * Computes and renders a layout
     * @param layoutDescription the {@link IXmlPullParser} letting the LayoutLib Bridge visit the
     * layout file.
     * @param projectKey An Object identifying the project. This is used for the cache mechanism.
     * @param screenWidth the screen width
     * @param screenHeight the screen height
     * @param renderFullSize if true, the rendering will render the full size needed by the
     * layout. This size is never smaller than <var>screenWidth</var> x <var>screenHeight</var>.
     * @param density the density factor for the screen.
     * @param xdpi the screen actual dpi in X
     * @param ydpi the screen actual dpi in Y
     * @param themeName The name of the theme to use.
     * @param isProjectTheme true if the theme is a project theme, false if it is a framework theme.
     * @param projectResources the resources of the project. The map contains (String, map) pairs
     * where the string is the type of the resource reference used in the layout file, and the
     * map contains (String, {@link IResourceValue}) pairs where the key is the resource name,
     * and the value is the resource value.
     * @param frameworkResources the framework resources. The map contains (String, map) pairs
     * where the string is the type of the resource reference used in the layout file, and the map
     * contains (String, {@link IResourceValue}) pairs where the key is the resource name, and the
     * value is the resource value.
     * @param projectCallback The {@link IProjectCallback} object to get information from
     * the project.
     * @param logger the object responsible for displaying warning/errors to the user.
     * @return a new {@link ILayoutResult} object that contains the result of the layout.
     * @since 4
     */
    ILayoutResult computeLayout(IXmlPullParser layoutDescription,
            Object projectKey,
            int screenWidth, int screenHeight, boolean renderFullSize,
            int density, float xdpi, float ydpi,
            String themeName, boolean isProjectTheme,
            Map<String, Map<String, IResourceValue>> projectResources,
            Map<String, Map<String, IResourceValue>> frameworkResources,
            IProjectCallback projectCallback, ILayoutLog logger);

    /**
     * Computes and renders a layout
     * @param layoutDescription the {@link IXmlPullParser} letting the LayoutLib Bridge visit the
     * layout file.
     * @param projectKey An Object identifying the project. This is used for the cache mechanism.
     * @param screenWidth the screen width
     * @param screenHeight the screen height
     * @param density the density factor for the screen.
     * @param xdpi the screen actual dpi in X
     * @param ydpi the screen actual dpi in Y
     * @param themeName The name of the theme to use.
     * @param isProjectTheme true if the theme is a project theme, false if it is a framework theme.
     * @param projectResources the resources of the project. The map contains (String, map) pairs
     * where the string is the type of the resource reference used in the layout file, and the
     * map contains (String, {@link IResourceValue}) pairs where the key is the resource name,
     * and the value is the resource value.
     * @param frameworkResources the framework resources. The map contains (String, map) pairs
     * where the string is the type of the resource reference used in the layout file, and the map
     * contains (String, {@link IResourceValue}) pairs where the key is the resource name, and the
     * value is the resource value.
     * @param projectCallback The {@link IProjectCallback} object to get information from
     * the project.
     * @param logger the object responsible for displaying warning/errors to the user.
     * @return a new {@link ILayoutResult} object that contains the result of the layout.
     * @since 3
     */
    @Deprecated
    ILayoutResult computeLayout(IXmlPullParser layoutDescription,
            Object projectKey,
            int screenWidth, int screenHeight, int density, float xdpi, float ydpi,
            String themeName, boolean isProjectTheme,
            Map<String, Map<String, IResourceValue>> projectResources,
            Map<String, Map<String, IResourceValue>> frameworkResources,
            IProjectCallback projectCallback, ILayoutLog logger);

    /**
     * Computes and renders a layout
     * @param layoutDescription the {@link IXmlPullParser} letting the LayoutLib Bridge visit the
     * layout file.
     * @param projectKey An Object identifying the project. This is used for the cache mechanism.
     * @param screenWidth the screen width
     * @param screenHeight the screen height
     * @param themeName The name of the theme to use.
     * @param isProjectTheme true if the theme is a project theme, false if it is a framework theme.
     * @param projectResources the resources of the project. The map contains (String, map) pairs
     * where the string is the type of the resource reference used in the layout file, and the
     * map contains (String, {@link IResourceValue}) pairs where the key is the resource name,
     * and the value is the resource value.
     * @param frameworkResources the framework resources. The map contains (String, map) pairs
     * where the string is the type of the resource reference used in the layout file, and the map
     * contains (String, {@link IResourceValue}) pairs where the key is the resource name, and the
     * value is the resource value.
     * @param projectCallback The {@link IProjectCallback} object to get information from
     * the project.
     * @param logger the object responsible for displaying warning/errors to the user.
     * @return a new {@link ILayoutResult} object that contains the result of the layout.
     * @deprecated Use {@link #computeLayout(IXmlPullParser, Object, int, int, int, float, float, String, boolean, Map, Map, IProjectCallback, ILayoutLog)}
     * @since 2
     */
    @Deprecated
    ILayoutResult computeLayout(IXmlPullParser layoutDescription,
            Object projectKey,
            int screenWidth, int screenHeight, String themeName, boolean isProjectTheme,
            Map<String, Map<String, IResourceValue>> projectResources,
            Map<String, Map<String, IResourceValue>> frameworkResources,
            IProjectCallback projectCallback, ILayoutLog logger);

    /**
     * Computes and renders a layout
     * @param layoutDescription the {@link IXmlPullParser} letting the LayoutLib Bridge visit the
     * layout file.
     * @param projectKey An Object identifying the project. This is used for the cache mechanism.
     * @param screenWidth
     * @param screenHeight
     * @param themeName The name of the theme to use. In order to differentiate project and platform
     * themes sharing the same name, all project themes must be prepended with a '*' character.
     * @param projectResources the resources of the project. The map contains (String, map) pairs
     * where the string is the type of the resource reference used in the layout file, and the
     * map contains (String, {@link IResourceValue}) pairs where the key is the resource name,
     * and the value is the resource value.
     * @param frameworkResources the framework resources. The map contains (String, map) pairs
     * where the string is the type of the resource reference used in the layout file, and the map
     * contains (String, {@link IResourceValue}) pairs where the key is the resource name, and the
     * value is the resource value.
     * @param projectCallback The {@link IProjectCallback} object to get information from
     * the project.
     * @param logger the object responsible for displaying warning/errors to the user.
     * @return a new {@link ILayoutResult} object that contains the result of the layout.
     * @deprecated Use {@link #computeLayout(IXmlPullParser, Object, int, int, int, float, float, String, boolean, Map, Map, IProjectCallback, ILayoutLog)}
     * @since 1
     */
    @Deprecated
    ILayoutResult computeLayout(IXmlPullParser layoutDescription,
            Object projectKey,
            int screenWidth, int screenHeight, String themeName,
            Map<String, Map<String, IResourceValue>> projectResources,
            Map<String, Map<String, IResourceValue>> frameworkResources,
            IProjectCallback projectCallback, ILayoutLog logger);

    /**
     * Clears the resource cache for a specific project.
     * <p/>This cache contains bitmaps and nine patches that are loaded from the disk and reused
     * until this method is called.
     * <p/>The cache is not configuration dependent and should only be cleared when a
     * resource changes (at this time only bitmaps and 9 patches go into the cache).
     * @param projectKey the key for the project.
     * @since 1
     */
    void clearCaches(Object projectKey);
}
