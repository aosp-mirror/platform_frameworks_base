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

package com.android.layoutlib.bridge;

import com.android.layoutlib.api.ILayoutResult;

import java.awt.image.BufferedImage;

/**
 * Implementation of {@link ILayoutResult}
 */
public final class LayoutResult implements ILayoutResult {

    private final ILayoutViewInfo mRootView;
    private final BufferedImage mImage;
    private final int mSuccess;
    private final String mErrorMessage;

    /**
     * Creates a {@link #SUCCESS} {@link ILayoutResult} with the specified params
     * @param rootView
     * @param image
     */
    public LayoutResult(ILayoutViewInfo rootView, BufferedImage image) {
        mSuccess = SUCCESS;
        mErrorMessage = null;
        mRootView = rootView;
        mImage = image;
    }
    
    /**
     * Creates a LayoutResult with a specific success code and associated message
     * @param code
     * @param message
     */
    public LayoutResult(int code, String message) {
        mSuccess = code;
        mErrorMessage = message;
        mRootView = null;
        mImage = null;
    }

    public int getSuccess() {
        return mSuccess;
    }

    public String getErrorMessage() {
        return mErrorMessage;
    }

    public BufferedImage getImage() {
        return mImage;
    }

    public ILayoutViewInfo getRootView() {
        return mRootView;
    }
    
    /**
     * Implementation of {@link ILayoutResult.ILayoutViewInfo}
     */
    public static final class LayoutViewInfo implements ILayoutViewInfo {
        private final Object mKey;
        private final String mName;
        private final int mLeft;
        private final int mRight;
        private final int mTop;
        private final int mBottom;
        private ILayoutViewInfo[] mChildren;

        public LayoutViewInfo(String name, Object key, int left, int top, int right, int bottom) {
            mName = name;
            mKey = key;
            mLeft = left;
            mRight = right;
            mTop = top;
            mBottom = bottom;
        }
        
        public void setChildren(ILayoutViewInfo[] children) {
            mChildren = children;
        }

        public ILayoutViewInfo[] getChildren() {
            return mChildren;
        }

        public Object getViewKey() {
            return mKey;
        }

        public String getName() {
            return mName;
        }

        public int getLeft() {
            return mLeft;
        }

        public int getTop() {
            return mTop;
        }

        public int getRight() {
            return mRight;
        }

        public int getBottom() {
            return mBottom;
        }
    }
}
