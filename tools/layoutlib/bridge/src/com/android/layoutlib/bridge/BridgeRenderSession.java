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
import com.android.tools.layoutlib.java.System_Delegate;
import com.android.util.PropertiesMap;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.view.View;
import android.view.ViewGroup;

import java.awt.image.BufferedImage;
import java.util.Collections;
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

    @Nullable
    private final RenderSessionImpl mSession;
    @NonNull
    private Result mLastResult;

    @Override
    public Result getResult() {
        return mLastResult;
    }

    @Override
    public BufferedImage getImage() {
        return mSession != null ? mSession.getImage() :
                new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    }

    @Override
    public boolean isAlphaChannelImage() {
        return mSession != null && mSession.isAlphaChannelImage();
    }

    @Override
    public List<ViewInfo> getRootViews() {
        return mSession != null ? mSession.getViewInfos() : Collections.emptyList();
    }

    @Override
    public List<ViewInfo> getSystemRootViews() {
        return mSession != null ? mSession.getSystemViewInfos() : Collections.emptyList();
    }

    @Override
    public Map<Object, PropertiesMap> getDefaultProperties() {
        return mSession != null ? mSession.getDefaultProperties() : Collections.emptyMap();
    }

    @Override
    public Result measure(long timeout) {
        if (mSession != null) {
            try {
                Bridge.prepareThread();
                mLastResult = mSession.acquire(timeout);
                if (mLastResult.isSuccess()) {
                    mSession.invalidateRenderingSize();
                    mLastResult = mSession.measure();
                }
            } finally {
                mSession.release();
                Bridge.cleanupThread();
            }
        }

        return mLastResult;
    }

    @Override
    public Result render(long timeout, boolean forceMeasure) {
        if (mSession != null) {
            try {
                Bridge.prepareThread();
                mLastResult = mSession.acquire(timeout);
                if (mLastResult.isSuccess()) {
                    if (forceMeasure) {
                        mSession.invalidateRenderingSize();
                    }
                    mLastResult = mSession.render(false /*freshRender*/);
                }
            } finally {
                mSession.release();
                Bridge.cleanupThread();
            }
        }

        return mLastResult;
    }

    @Override
    public Result animate(Object targetObject, String animationName,
            boolean isFrameworkAnimation, IAnimationListener listener) {
        if (mSession != null) {
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
        }

        return mLastResult;
    }

    @Override
    public Result insertChild(Object parentView, ILayoutPullParser childXml, int index,
            IAnimationListener listener) {
        if (!(parentView instanceof ViewGroup)) {
            throw new IllegalArgumentException("parentView is not a ViewGroup");
        }

        if (mSession != null) {
            try {
                Bridge.prepareThread();
                mLastResult = mSession.acquire(RenderParams.DEFAULT_TIMEOUT);
                if (mLastResult.isSuccess()) {
                    mLastResult =
                            mSession.insertChild((ViewGroup) parentView, childXml, index, listener);
                }
            } finally {
                mSession.release();
                Bridge.cleanupThread();
            }
        }

        return mLastResult;
    }


    @Override
    public Result moveChild(Object parentView, Object childView, int index,
            Map<String, String> layoutParams, IAnimationListener listener) {
        if (!(parentView instanceof ViewGroup)) {
            throw new IllegalArgumentException("parentView is not a ViewGroup");
        }
        if (!(childView instanceof View)) {
            throw new IllegalArgumentException("childView is not a View");
        }

        if (mSession != null) {
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
        }

        return mLastResult;
    }

    @Override
    public Result removeChild(Object childView, IAnimationListener listener) {
        if (!(childView instanceof View)) {
            throw new IllegalArgumentException("childView is not a View");
        }

        if (mSession != null) {
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
        }

        return mLastResult;
    }

    @Override
    public void setSystemTimeNanos(long nanos) {
        System_Delegate.setNanosTime(nanos);
    }

    @Override
    public void setSystemBootTimeNanos(long nanos) {
        System_Delegate.setBootTimeNanos(nanos);
    }

    @Override
    public void setElapsedFrameTimeNanos(long nanos) {
        if (mSession != null) {
            mSession.setElapsedFrameTimeNanos(nanos);
        }
    }

    @Override
    public void dispose() {
        if (mSession != null) {
            mSession.dispose();
        }
    }

    /*package*/ BridgeRenderSession(@Nullable RenderSessionImpl scene, @NonNull Result lastResult) {
        mSession = scene;
        if (scene != null) {
            mSession.setScene(this);
        }
        mLastResult = lastResult;
    }
}
