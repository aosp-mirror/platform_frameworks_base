/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.printspooler.widget;

import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.print.PrintAttributes.MediaSize;
import android.print.PrintAttributes.Margins;
import android.util.AttributeSet;
import android.view.View;
import com.android.printspooler.model.PageContentRepository;
import com.android.printspooler.model.PageContentRepository.RenderSpec;
import com.android.printspooler.model.PageContentRepository.PageContentProvider;

/**
 * This class represents a page in the print preview list. The width of the page
 * is determined by stretching it to take maximal horizontal space while the height
 * is computed from the width using the page aspect ratio. Note that different media
 * sizes have different aspect ratios.
 */
public class PageContentView extends View
        implements PageContentRepository.OnPageContentAvailableCallback {

    private PageContentProvider mProvider;

    private MediaSize mMediaSize;

    private Margins mMinMargins;

    private Drawable mEmptyState;

    private Drawable mErrorState;

    private boolean mContentRequested;

    private boolean mIsFailed;

    public PageContentView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mContentRequested = false;

        requestPageContentIfNeeded();
    }

    @Override
    public void onPageContentAvailable(BitmapDrawable renderedPage) {
        mIsFailed = (renderedPage == null);

        if (mIsFailed) {
            setBackground(mErrorState);
        } else {
            setBackground(renderedPage);
        }
    }

    public PageContentProvider getPageContentProvider() {
        return mProvider;
    }

    public void init(PageContentProvider provider, Drawable emptyState, Drawable errorState,
            MediaSize mediaSize, Margins minMargins) {
        final boolean providerChanged = (mProvider == null)
                ? provider != null : !mProvider.equals(provider);
        final boolean loadingDrawableChanged = (mEmptyState == null)
                ? emptyState != null : !mEmptyState.equals(emptyState);
        final boolean mediaSizeChanged = (mMediaSize == null)
                ? mediaSize != null : !mMediaSize.equals(mediaSize);
        final boolean marginsChanged = (mMinMargins == null)
                ? minMargins != null : !mMinMargins.equals(minMargins);

        if (!providerChanged && !mediaSizeChanged
                && !marginsChanged && !loadingDrawableChanged) {
            return;
        }

        mIsFailed = false;
        mProvider = provider;
        mMediaSize = mediaSize;
        mMinMargins = minMargins;

        mEmptyState = emptyState;
        mErrorState = errorState;
        mContentRequested = false;

        // If there is no provider we want immediately to switch to
        // the empty state, so pages with no content appear blank.
        if (mProvider == null) {
            setBackground(mEmptyState);
        } else if (mIsFailed) {
            setBackground(mErrorState);
        }

        requestPageContentIfNeeded();
    }

    private void requestPageContentIfNeeded() {
        if (getWidth() > 0 && getHeight() > 0 && !mContentRequested
                && mProvider != null) {
            mContentRequested = true;
            mProvider.getPageContent(new RenderSpec(getWidth(), getHeight(),
                    mMediaSize, mMinMargins), this);
        }
    }
}
