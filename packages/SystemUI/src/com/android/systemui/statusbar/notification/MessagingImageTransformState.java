/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.view.View;

import com.android.internal.widget.MessagingImageMessage;
import com.android.systemui.R;
import com.android.systemui.statusbar.ViewTransformationHelper;

/**
 * A transform state of a image view.
*/
public class MessagingImageTransformState extends ImageTransformState {
    private static Pools.SimplePool<MessagingImageTransformState> sInstancePool
            = new Pools.SimplePool<>(40);
    private static final int START_ACTUAL_WIDTH = R.id.transformation_start_actual_width;
    private static final int START_ACTUAL_HEIGHT = R.id.transformation_start_actual_height;
    private MessagingImageMessage mImageMessage;

    @Override
    public void initFrom(View view, TransformInfo transformInfo) {
        super.initFrom(view, transformInfo);
        mImageMessage = (MessagingImageMessage) view;
    }

    @Override
    protected boolean sameAs(TransformState otherState) {
        if (super.sameAs(otherState)) {
            return true;
        }
        if (otherState instanceof MessagingImageTransformState) {
            MessagingImageTransformState otherMessage = (MessagingImageTransformState) otherState;
            return mImageMessage.sameAs(otherMessage.mImageMessage);
        }
        return false;
    }

    public static MessagingImageTransformState obtain() {
        MessagingImageTransformState instance = sInstancePool.acquire();
        if (instance != null) {
            return instance;
        }
        return new MessagingImageTransformState();
    }

    @Override
    protected boolean transformScale(TransformState otherState) {
        return false;
    }

    @Override
    protected void transformViewFrom(TransformState otherState, int transformationFlags,
            ViewTransformationHelper.CustomTransformation customTransformation,
            float transformationAmount) {
        super.transformViewFrom(otherState, transformationFlags, customTransformation,
                transformationAmount);
        float interpolatedValue = mDefaultInterpolator.getInterpolation(
                transformationAmount);
        if (otherState instanceof MessagingImageTransformState && sameAs(otherState)) {
            MessagingImageMessage otherMessage
                    = ((MessagingImageTransformState) otherState).mImageMessage;
            if (transformationAmount == 0.0f) {
                setStartActualWidth(otherMessage.getActualWidth());
                setStartActualHeight(otherMessage.getActualHeight());
            }
            float startActualWidth = getStartActualWidth();
            mImageMessage.setActualWidth(
                    (int) NotificationUtils.interpolate(startActualWidth,
                            mImageMessage.getStaticWidth(),
                            interpolatedValue));
            float startActualHeight = getStartActualHeight();
            mImageMessage.setActualHeight(
                    (int) NotificationUtils.interpolate(startActualHeight,
                            mImageMessage.getHeight(),
                            interpolatedValue));
        }
    }

    public int getStartActualWidth() {
        Object tag = mTransformedView.getTag(START_ACTUAL_WIDTH);
        return tag == null ? -1 : (int) tag;
    }

    public void setStartActualWidth(int actualWidth) {
        mTransformedView.setTag(START_ACTUAL_WIDTH, actualWidth);
    }

    public int getStartActualHeight() {
        Object tag = mTransformedView.getTag(START_ACTUAL_HEIGHT);
        return tag == null ? -1 : (int) tag;
    }

    public void setStartActualHeight(int actualWidth) {
        mTransformedView.setTag(START_ACTUAL_HEIGHT, actualWidth);
    }

    @Override
    public void recycle() {
        super.recycle();
        if (getClass() == MessagingImageTransformState.class) {
            sInstancePool.release(this);
        }
    }

    @Override
    protected void resetTransformedView() {
        super.resetTransformedView();
        mImageMessage.setActualWidth(mImageMessage.getStaticWidth());
        mImageMessage.setActualHeight(mImageMessage.getHeight());
    }

    @Override
    protected void reset() {
        super.reset();
        mImageMessage = null;
    }
}
