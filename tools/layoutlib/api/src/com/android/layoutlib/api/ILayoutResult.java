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

import java.awt.image.BufferedImage;

/**
 * The result of a layout computation through
 * {@link ILayoutLibBridge#computeLayout(IXmlPullParser, int, int, String, java.util.Map, java.util.Map, java.util.Map, IFontLoader, ILayoutLibLog, ICustomViewLoader)}
 */
public interface ILayoutResult {
    /**
     * Success return code
     */
    final static int SUCCESS = 0;

    /**
     * Error return code, in which case an error message is guaranteed to be defined.
     * @See {@link #getErrorMessage()}
     */
    final static int ERROR = 1;

    /**
     * Returns the result code.
     * @see #SUCCESS
     * @see #ERROR
     */
    int getSuccess();

    /**
     * Returns the {@link ILayoutViewInfo} object for the top level view.
     */
    ILayoutViewInfo getRootView();

    /**
     * Returns the rendering of the full layout.
     */
    BufferedImage getImage();

    /**
     * Returns the error message.
     * <p/>Only valid when {@link #getSuccess()} returns {@link #ERROR}
     */
    String getErrorMessage();

    /**
     * Layout information for a specific view.
     */
    public interface ILayoutViewInfo {

        /**
         * Returns the list of children views.
         */
        ILayoutViewInfo[] getChildren();

        /**
         * Returns the key associated with the node.
         * @see IXmlPullParser#getViewKey()
         */
        Object getViewKey();

        /**
         * Returns the name of the view.
         */
        String getName();

        /**
         * Returns the left of the view bounds.
         */
        int getLeft();

        /**
         * Returns the top of the view bounds.
         */
        int getTop();

        /**
         * Returns the right of the view bounds.
         */
        int getRight();

        /**
         * Returns the bottom of the view bounds.
         */
        int getBottom();
    }
}
