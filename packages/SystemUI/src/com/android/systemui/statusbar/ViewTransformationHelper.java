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

package com.android.systemui.statusbar;

import android.os.Handler;
import android.util.ArrayMap;
import android.view.View;

import com.android.systemui.statusbar.notification.TransformState;

/**
 * A view that can be transformed to and from.
 */
public class ViewTransformationHelper implements TransformableView {
    private final Handler mHandler = new Handler();
    private ArrayMap<Integer, View> mTransformedViews = new ArrayMap<>();

    public void addTransformedView(int key, View transformedView) {
        mTransformedViews.put(key, transformedView);
    }

    public void reset() {
        mTransformedViews.clear();
    }

    @Override
    public TransformState getCurrentState(int fadingView) {
        View view = mTransformedViews.get(fadingView);
        if (view != null && view.getVisibility() != View.GONE) {
            return TransformState.createFrom(view);
        }
        return null;
    }

    @Override
    public void transformTo(TransformableView notification, Runnable endRunnable) {
        Runnable runnable = endRunnable;
        for (Integer viewType : mTransformedViews.keySet()) {
            TransformState ownState = getCurrentState(viewType);
            if (ownState != null) {
                TransformState otherState = notification.getCurrentState(viewType);
                if (otherState != null) {
                    boolean run = ownState.transformViewTo(otherState, runnable);
                    otherState.recycle();
                    if (run) {
                        runnable = null;
                    }
                } else {
                    // there's no other view available
                    CrossFadeHelper.fadeOut(mTransformedViews.get(viewType), runnable);
                    runnable = null;
                }
                ownState.recycle();
            }
        }
        if (runnable != null) {
            // We need to post, since the visible type is only set after the transformation is
            // started
            mHandler.post(runnable);
        }
    }

    @Override
    public void transformFrom(TransformableView notification) {
        for (Integer viewType : mTransformedViews.keySet()) {
            TransformState ownState = getCurrentState(viewType);
            if (ownState != null) {
                TransformState otherState = notification.getCurrentState(viewType);
                if (otherState != null) {
                    ownState.transformViewFrom(otherState);
                    otherState.recycle();
                } else {
                    // There's no other view, lets fade us in
                    // Certain views need to prepare the fade in and make sure its children are
                    // completely visible. An example is the notification header.
                    ownState.prepareFadeIn();
                    CrossFadeHelper.fadeIn(mTransformedViews.get(viewType));
                }
                ownState.recycle();
            }
        }
    }

    @Override
    public void setVisible(boolean visible) {
        for (Integer viewType : mTransformedViews.keySet()) {
            TransformState ownState = getCurrentState(viewType);
            if (ownState != null) {
                ownState.setVisible(visible);
                ownState.recycle();
            }
        }
    }
}
