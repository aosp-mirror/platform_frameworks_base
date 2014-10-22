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

package com.android.layoutlib.bridge;

import com.android.ide.common.rendering.api.IAnimationListener;
import com.android.ide.common.rendering.api.ILayoutPullParser;
import com.android.ide.common.rendering.api.RenderParams;
import com.android.ide.common.rendering.api.RenderSession;
import com.android.ide.common.rendering.api.Result;
import com.android.ide.common.rendering.api.ViewInfo;
import com.android.layoutlib.bridge.impl.RenderSessionImpl;

import android.view.View;
import android.view.ViewGroup;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

/**
 * An implementation of {@link RenderSession}.
 *
 * This is a pretty basic class that does almost nothing. All of the work is done in
 * {@link RenderSessionImpl}.
 *
 */
public class BridgeRenderSession extends RenderSession {

    private final RenderSessionImpl mSession;
    private Result mLastResult;

    @Override
    public Result getResult() {
        return mLastResult;
    }

    @Override
    public BufferedImage getImage() {
        return mSession.getImage();
    }

    @Override
    public boolean isAlphaChannelImage() {
        return mSession.isAlphaChannelImage();
    }

    @Override
    public List<ViewInfo> getRootViews() {
        return mSession.getViewInfos();
    }

    @Override
    public List<ViewInfo> getSystemRootViews() {
        return mSession.getSystemViewInfos();
    }

    @Override
    public Map<String, String> getDefaultProperties(Object viewObject) {
        return mSession.getDefaultProperties(viewObject);
    }

    @Override
    public Result getProperty(Object objectView, String propertyName) {
        // pass
        return super.getProperty(objectView, propertyName);
    }

    @Override
    public Result setProperty(Object objectView, String propertyName, String propertyValue) {
        // pass
        return super.setProperty(objectView, propertyName, propertyValue);
    }

    @Override
    public Result render(long timeout) {
        try {
            Bridge.prepareThread();
            mLastResult = mSession.acquire(timeout);
            if (mLastResult.isSuccess()) {
                mLastResult = mSession.render(false /*freshRender*/);
            }
        } finally {
            mSession.release();
            Bridge.cleanupThread();
        }

        return mLastResult;
    }

    @Override
    public Result animate(Object targetObject, String animationName,
            boolean isFrameworkAnimation, IAnimationListener listener) {
        try {
            Bridge.prepareThread();
            mLastResult = mSession.acquire(RenderParams.DEFAULT_TIMEOUT);
            if (mLastResult.isSuccess()) {
                mLastResult = mSession.animate(targetObject, animationName, isFrameworkAnimation,
                        listener);
            }
        } finally {
            mSession.release();
            Bridge.cleanupThread();
        }

        return mLastResult;
    }

    @Override
    public Result insertChild(Object parentView, ILayoutPullParser childXml, int index,
            IAnimationListener listener) {
        if (parentView instanceof ViewGroup == false) {
            throw new IllegalArgumentException("parentView is not a ViewGroup");
        }

        try {
            Bridge.prepareThread();
            mLastResult = mSession.acquire(RenderParams.DEFAULT_TIMEOUT);
            if (mLastResult.isSuccess()) {
                mLastResult = mSession.insertChild((ViewGroup) parentView, childXml, index,
                        listener);
            }
        } finally {
            mSession.release();
            Bridge.cleanupThread();
        }

        return mLastResult;
    }


    @Override
    public Result moveChild(Object parentView, Object childView, int index,
            Map<String, String> layoutParams, IAnimationListener listener) {
        if (parentView instanceof ViewGroup == false) {
            throw new IllegalArgumentException("parentView is not a ViewGroup");
        }
        if (childView instanceof View == false) {
            throw new IllegalArgumentException("childView is not a View");
        }

        try {
            Bridge.prepareThread();
            mLastResult = mSession.acquire(RenderParams.DEFAULT_TIMEOUT);
            if (mLastResult.isSuccess()) {
                mLastResult = mSession.moveChild((ViewGroup) parentView, (View) childView, index,
                        layoutParams, listener);
            }
        } finally {
            mSession.release();
            Bridge.cleanupThread();
        }

        return mLastResult;
    }

    @Override
    public Result removeChild(Object childView, IAnimationListener listener) {
        if (childView instanceof View == false) {
            throw new IllegalArgumentException("childView is not a View");
        }

        try {
            Bridge.prepareThread();
            mLastResult = mSession.acquire(RenderParams.DEFAULT_TIMEOUT);
            if (mLastResult.isSuccess()) {
                mLastResult = mSession.removeChild((View) childView, listener);
            }
        } finally {
            mSession.release();
            Bridge.cleanupThread();
        }

        return mLastResult;
    }

    @Override
    public void dispose() {
    }

    /*package*/ BridgeRenderSession(RenderSessionImpl scene, Result lastResult) {
        mSession = scene;
        if (scene != null) {
            mSession.setScene(this);
        }
        mLastResult = lastResult;
    }
}
