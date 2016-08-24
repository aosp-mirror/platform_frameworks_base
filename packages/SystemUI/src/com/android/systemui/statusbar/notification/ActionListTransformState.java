/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification;

import android.text.Layout;
import android.text.TextUtils;
import android.util.Pools;
import android.view.View;
import android.widget.TextView;

/**
 * A transform state of the action list
*/
public class ActionListTransformState extends TransformState {

    private static Pools.SimplePool<ActionListTransformState> sInstancePool
            = new Pools.SimplePool<>(40);

    @Override
    protected boolean sameAs(TransformState otherState) {
        return otherState instanceof ActionListTransformState;
    }

    public static ActionListTransformState obtain() {
        ActionListTransformState instance = sInstancePool.acquire();
        if (instance != null) {
            return instance;
        }
        return new ActionListTransformState();
    }

    @Override
    public void transformViewFullyFrom(TransformState otherState, float transformationAmount) {
        // Don't do Y transform - let the wrapper handle this based on the content height
    }

    @Override
    public void transformViewFullyTo(TransformState otherState, float transformationAmount) {
        // Don't do Y transform - let the wrapper handle this based on the content height
    }

    @Override
    protected void resetTransformedView() {
        // We need to keep the Y transformation, because this is used to keep the action list
        // aligned at the bottom, unrelated to transforms.
        float y = getTransformedView().getTranslationY();
        super.resetTransformedView();
        getTransformedView().setTranslationY(y);
    }

    @Override
    public void recycle() {
        super.recycle();
        sInstancePool.release(this);
    }
}
