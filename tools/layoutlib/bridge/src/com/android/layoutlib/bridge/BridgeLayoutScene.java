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
import com.android.layoutlib.api.SceneResult;
import com.android.layoutlib.api.ViewInfo;
import com.android.layoutlib.bridge.impl.LayoutSceneImpl;

import java.awt.image.BufferedImage;

/**
 * An implementation of {@link LayoutScene}.
 *
 * This is a pretty basic class that does almost nothing. All of the work is done in
 * {@link LayoutSceneImpl}.
 *
 */
public class BridgeLayoutScene extends LayoutScene {

    private final Bridge mBridge;
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
    public SceneResult render() {

        synchronized (mBridge) {
            try {
                mScene.prepare();
                mLastResult = mScene.render();
            } finally {
                mScene.cleanup();
            }
        }

        return mLastResult;
    }

    @Override
    public void dispose() {
        // TODO Auto-generated method stub

    }

    /*package*/ BridgeLayoutScene(Bridge bridge, LayoutSceneImpl scene, SceneResult lastResult) {
        mBridge = bridge;
        mScene = scene;
        mLastResult = lastResult;
    }
}
