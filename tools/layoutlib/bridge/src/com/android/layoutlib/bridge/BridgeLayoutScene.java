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

import com.android.layoutlib.api.LayoutScene;
import com.android.layoutlib.api.SceneParams;
import com.android.layoutlib.api.SceneResult;
import com.android.layoutlib.api.ViewInfo;
import com.android.layoutlib.bridge.impl.LayoutSceneImpl;

import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * An implementation of {@link LayoutScene}.
 *
 * This is a pretty basic class that does almost nothing. All of the work is done in
 * {@link LayoutSceneImpl}.
 *
 */
public class BridgeLayoutScene extends LayoutScene {

    private final LayoutSceneImpl mScene;
    private SceneResult mLastResult;

    @Override
    public SceneResult getResult() {
        return mLastResult;
    }

    @Override
    public BufferedImage getImage() {
        return mScene.getImage();
    }

    @Override
    public ViewInfo getRootView() {
        return mScene.getViewInfo();
    }

    @Override
    public Map<String, String> getDefaultViewPropertyValues(Object viewObject) {
        return mScene.getDefaultViewPropertyValues(viewObject);
    }

    @Override
    public SceneResult render(long timeout) {
        try {
            mScene.prepareThread();
            mLastResult = mScene.acquire(timeout);
            if (mLastResult == SceneResult.SUCCESS) {
                mLastResult = mScene.render();
            }
        } finally {
            mScene.release();
            mScene.cleanupThread();
        }

        return mLastResult;
    }

    @Override
    public SceneResult animate(Object targetObject, String animationName,
            boolean isFrameworkAnimation, IAnimationListener listener) {
        try {
            mScene.prepareThread();
            mLastResult = mScene.acquire(SceneParams.DEFAULT_TIMEOUT);
            if (mLastResult == SceneResult.SUCCESS) {
                mLastResult = mScene.animate(targetObject, animationName, isFrameworkAnimation,
                        listener);
            }
        } finally {
            mScene.release();
            mScene.cleanupThread();
        }

        return mLastResult;
    }

    @Override
    public void dispose() {
        // TODO Auto-generated method stub

    }

    /*package*/ BridgeLayoutScene(LayoutSceneImpl scene, SceneResult lastResult) {
        mScene = scene;
        mLastResult = lastResult;
    }
}
