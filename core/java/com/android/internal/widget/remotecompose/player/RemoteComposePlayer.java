/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.player;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

import com.android.internal.widget.remotecompose.core.operations.RootContentBehavior;
import com.android.internal.widget.remotecompose.player.platform.RemoteComposeCanvas;

/**
 * A view to to display and play RemoteCompose documents
 */
public class RemoteComposePlayer extends FrameLayout {
    private RemoteComposeCanvas mInner;

    private static final int MAX_SUPPORTED_MAJOR_VERSION = 0;
    private static final int MAX_SUPPORTED_MINOR_VERSION = 1;

    public RemoteComposePlayer(Context context) {
        super(context);
        init(context, null, 0);
    }

    public RemoteComposePlayer(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs, 0);
    }

    public RemoteComposePlayer(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs, defStyleAttr);
    }

    /**
     * Turn on debug information
     * @param debugFlags 1 to set debug on
     */
    public void setDebug(int debugFlags) {
        if (debugFlags == 1) {
            mInner.setDebug(true);
        } else {
            mInner.setDebug(false);
        }
    }

    public void setDocument(RemoteComposeDocument value) {
        if (value != null) {
            if (value.canBeDisplayed(
                    MAX_SUPPORTED_MAJOR_VERSION,
                    MAX_SUPPORTED_MINOR_VERSION, 0L
            )
            ) {
                mInner.setDocument(value);
                int contentBehavior = value.getDocument().getContentScroll();
                applyContentBehavior(contentBehavior);
            } else {
                Log.e("RemoteComposePlayer", "Unsupported document ");
            }
        } else {
            mInner.setDocument(null);
        }
    }

    /**
     * Apply the content behavior (NONE|SCROLL_HORIZONTAL|SCROLL_VERTICAL) to the player,
     * adding or removing scrollviews as needed.
     *
     * @param contentBehavior document content behavior (NONE|SCROLL_HORIZONTAL|SCROLL_VERTICAL)
     */
    private void applyContentBehavior(int contentBehavior) {
        switch (contentBehavior) {
            case RootContentBehavior.SCROLL_HORIZONTAL: {
                if (!(mInner.getParent() instanceof HorizontalScrollView)) {
                    ((ViewGroup) mInner.getParent()).removeView(mInner);
                    removeAllViews();
                    LayoutParams layoutParamsInner = new LayoutParams(
                            LayoutParams.WRAP_CONTENT,
                            LayoutParams.MATCH_PARENT);
                    HorizontalScrollView horizontalScrollView =
                            new HorizontalScrollView(getContext());
                    horizontalScrollView.setBackgroundColor(Color.TRANSPARENT);
                    horizontalScrollView.setFillViewport(true);
                    horizontalScrollView.addView(mInner, layoutParamsInner);
                    LayoutParams layoutParams = new LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            LayoutParams.MATCH_PARENT);
                    addView(horizontalScrollView, layoutParams);
                }
            } break;
            case RootContentBehavior.SCROLL_VERTICAL: {
                if (!(mInner.getParent() instanceof ScrollView)) {
                    ((ViewGroup) mInner.getParent()).removeView(mInner);
                    removeAllViews();
                    LayoutParams layoutParamsInner = new LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            LayoutParams.WRAP_CONTENT);
                    ScrollView scrollView = new ScrollView(getContext());
                    scrollView.setBackgroundColor(Color.TRANSPARENT);
                    scrollView.setFillViewport(true);
                    scrollView.addView(mInner, layoutParamsInner);
                    LayoutParams layoutParams = new LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            LayoutParams.MATCH_PARENT);
                    addView(scrollView, layoutParams);
                }
            } break;
            default:
                if (mInner.getParent() != this)  {
                    ((ViewGroup) mInner.getParent()).removeView(mInner);
                    removeAllViews();
                    LayoutParams layoutParams = new LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            LayoutParams.MATCH_PARENT);
                    addView(mInner, layoutParams);
                }
        }
    }

    private void init(Context context, AttributeSet attrs, int defStyleAttr) {
        LayoutParams layoutParams = new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT);
        setBackgroundColor(Color.TRANSPARENT);
        mInner = new RemoteComposeCanvas(context, attrs, defStyleAttr);
        mInner.setBackgroundColor(Color.TRANSPARENT);
        addView(mInner, layoutParams);
    }

    public interface ClickCallbacks {
        void click(int id, String metadata);
    }

    /**
     * Add a callback for handling click events on the document
     *
     * @param callback the callback lambda that will be used when a click is detected
     *                 <p>
     *                 The parameter of the callback are:
     *                 id : the id of the clicked area
     *                 metadata: a client provided unstructured string associated with that area
     */
    public void addClickListener(ClickCallbacks callback) {
        mInner.addClickListener((id, metadata) -> callback.click(id, metadata));
    }

    /**
     * Set the playback theme for the document. This allows to filter operations in order
     * to have the document adapt to the given theme. This method is intended to be used
     * to support night/light themes (system or app level), not custom themes.
     *
     * @param theme the theme used for playing the document. Possible values for theme are:
     *              - Theme.UNSPECIFIED -- all instructions in the document will be executed
     *              - Theme.DARK -- only executed NON Light theme instructions
     *              - Theme.LIGHT -- only executed NON Dark theme instructions
     */
    public void setTheme(int theme) {
        if (mInner.getTheme() != theme) {
            mInner.setTheme(theme);
            mInner.invalidate();
        }
    }
}

