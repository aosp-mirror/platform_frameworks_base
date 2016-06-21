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

import android.util.Pools;
import android.view.NotificationHeaderView;
import android.view.View;

import com.android.systemui.statusbar.CrossFadeHelper;

/**
 * A transform state of a text view.
*/
public class HeaderTransformState extends TransformState {

    private static Pools.SimplePool<HeaderTransformState> sInstancePool
            = new Pools.SimplePool<>(40);
    private View mExpandButton;
    private View mWorkProfileIcon;
    private TransformState mWorkProfileState;

    @Override
    public void initFrom(View view) {
        super.initFrom(view);
        if (view instanceof NotificationHeaderView) {
            NotificationHeaderView header = (NotificationHeaderView) view;
            mExpandButton = header.getExpandButton();
            mWorkProfileState = TransformState.obtain();
            mWorkProfileIcon = header.getWorkProfileIcon();
            mWorkProfileState.initFrom(mWorkProfileIcon);
        }
    }

    @Override
    public boolean transformViewTo(TransformState otherState, float transformationAmount) {
        // if the transforming notification has a header, we have ensured that it looks the same
        // but the expand button, so lets fade just that one and transform the work profile icon.
        if (!(mTransformedView instanceof NotificationHeaderView)) {
            return false;
        }
        NotificationHeaderView header = (NotificationHeaderView) mTransformedView;
        int childCount = header.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View headerChild = header.getChildAt(i);
            if (headerChild.getVisibility() == View.GONE) {
                continue;
            }
            if (headerChild != mExpandButton) {
                headerChild.setVisibility(View.INVISIBLE);
            } else {
                CrossFadeHelper.fadeOut(mExpandButton, transformationAmount);
            }
        }
        return true;
    }

    @Override
    public void transformViewFrom(TransformState otherState, float transformationAmount) {
        // if the transforming notification has a header, we have ensured that it looks the same
        // but the expand button, so lets fade just that one and transform the work profile icon.
        if (!(mTransformedView instanceof NotificationHeaderView)) {
            return;
        }
        NotificationHeaderView header = (NotificationHeaderView) mTransformedView;
        header.setVisibility(View.VISIBLE);
        header.setAlpha(1.0f);
        int childCount = header.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View headerChild = header.getChildAt(i);
            if (headerChild.getVisibility() == View.GONE) {
                continue;
            }
            if (headerChild == mExpandButton) {
                CrossFadeHelper.fadeIn(mExpandButton, transformationAmount);
            } else {
                headerChild.setVisibility(View.VISIBLE);
                if (headerChild == mWorkProfileIcon) {
                    mWorkProfileState.transformViewFullyFrom(
                            ((HeaderTransformState) otherState).mWorkProfileState,
                            transformationAmount);
                }
            }
        }
        return;
    }

    public static HeaderTransformState obtain() {
        HeaderTransformState instance = sInstancePool.acquire();
        if (instance != null) {
            return instance;
        }
        return new HeaderTransformState();
    }

    @Override
    public void recycle() {
        super.recycle();
        sInstancePool.release(this);
    }

    @Override
    protected void reset() {
        super.reset();
        mExpandButton = null;
        mWorkProfileState = null;
        if (mWorkProfileState != null) {
            mWorkProfileState.recycle();
            mWorkProfileState = null;
        }
    }

    @Override
    public void setVisible(boolean visible, boolean force) {
        super.setVisible(visible, force);
        if (!(mTransformedView instanceof NotificationHeaderView)) {
            return;
        }
        NotificationHeaderView header = (NotificationHeaderView) mTransformedView;
        int childCount = header.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View headerChild = header.getChildAt(i);
            if (!force && headerChild.getVisibility() == View.GONE) {
                continue;
            }
            headerChild.animate().cancel();
            if (headerChild.getVisibility() != View.GONE) {
                headerChild.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            }
            if (headerChild == mExpandButton) {
                headerChild.setAlpha(visible ? 1.0f : 0.0f);
            }
            if (headerChild == mWorkProfileIcon) {
                headerChild.setTranslationX(0);
                headerChild.setTranslationY(0);
            }
        }
    }

    @Override
    public void prepareFadeIn() {
        super.prepareFadeIn();
        if (!(mTransformedView instanceof NotificationHeaderView)) {
            return;
        }
        NotificationHeaderView header = (NotificationHeaderView) mTransformedView;
        int childCount = header.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View headerChild = header.getChildAt(i);
            if (headerChild.getVisibility() == View.GONE) {
                continue;
            }
            headerChild.animate().cancel();
            headerChild.setVisibility(View.VISIBLE);
            headerChild.setAlpha(1.0f);
            if (headerChild == mWorkProfileIcon) {
                headerChild.setTranslationX(0);
                headerChild.setTranslationY(0);
            }
        }
    }
}
