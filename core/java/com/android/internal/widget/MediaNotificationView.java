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

package com.android.internal.widget;

import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import java.util.ArrayList;

/**
 * The Layout class which handles template details for the Notification.MediaStyle
 *
 * @hide
 */
@RemoteViews.RemoteView
public class MediaNotificationView extends FrameLayout {

    private ArrayList<VisibilityChangeListener> mListeners;

    public MediaNotificationView(Context context) {
        this(context, null);
    }

    public MediaNotificationView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MediaNotificationView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public MediaNotificationView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (mListeners != null) {
            for (int i = 0; i < mListeners.size(); i++) {
                mListeners.get(i).onAggregatedVisibilityChanged(isVisible);
            }
        }
    }

    /**
     * Add a listener to receive updates on the visibility of this view
     *
     * @param listener The listener to add.
     */
    public void addVisibilityListener(VisibilityChangeListener listener) {
        if (mListeners == null) {
            mListeners = new ArrayList<>();
        }
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    /**
     * Remove the specified listener
     *
     * @param listener The listener to remove.
     */
    public void removeVisibilityListener(VisibilityChangeListener listener) {
        if (mListeners != null) {
            mListeners.remove(listener);
        }
    }

    /**
     * Interface for receiving updates when the view's visibility changes
     */
    public interface VisibilityChangeListener {
        /**
         * Method called when the visibility of this view has changed
         * @param isVisible true if the view is now visible
         */
        void onAggregatedVisibilityChanged(boolean isVisible);
    }
}
