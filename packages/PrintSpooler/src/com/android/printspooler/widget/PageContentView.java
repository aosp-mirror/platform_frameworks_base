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
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.print.PrintAttributes.MediaSize;
import android.print.PrintAttributes.Margins;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import com.android.printspooler.model.PageContentRepository;
import com.android.printspooler.model.PageContentRepository.PageContentProvider;
import com.android.printspooler.model.PageContentRepository.RenderSpec;

/**
 * This class represents a page in the print preview list. The width of the page
 * is determined by stretching it to take maximal horizontal space while the height
 * is computed from the width using the page aspect ratio. Note that different media
 * sizes have different aspect ratios.
 */
public class PageContentView extends View
        implements PageContentRepository.OnPageContentAvailableCallback {

    private final ColorDrawable mEmptyState;

    private PageContentProvider mProvider;

    private MediaSize mMediaSize;
    private Margins mMinMargins;

    private boolean mContentRequested;

    public PageContentView(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedValue typedValue = new TypedValue();
        context.getTheme().resolveAttribute(com.android.internal.R.attr.textColorPrimary,
                typedValue, true);

        mEmptyState = new ColorDrawable(typedValue.data);

        setBackground(mEmptyState);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        requestPageContentIfNeeded();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        requestPageContentIfNeeded();
    }

    @Override
    public void onPageContentAvailable(BitmapDrawable content) {
        if (getBackground() != content) {
            setBackground(content);
        }
    }

    public PageContentProvider getPageContentProvider() {
        return mProvider;
    }

    public void init(PageContentProvider provider, MediaSize mediaSize, Margins minMargins) {
        if (mProvider == provider
                && ((mMediaSize == null) ? mediaSize == null : mMediaSize.equals(mediaSize))
                && ((mMinMargins == null) ? minMargins == null : mMinMargins.equals(minMargins))) {
            return;
        }

        mProvider = provider;
        mMediaSize = mediaSize;
        mMinMargins = minMargins;
        mContentRequested = false;

        // If there is no provider we want immediately to switch to
        // the empty state, so pages with no content appear blank.
        if (mProvider == null && getBackground() != mEmptyState) {
            setBackground(mEmptyState);
        }

        requestPageContentIfNeeded();
    }

    private void requestPageContentIfNeeded() {
        if (getWidth() > 0 && getHeight() > 0 && !mContentRequested && mProvider != null) {
            mContentRequested = true;
            mProvider.getPageContent(new RenderSpec(getWidth(), getHeight(), mMediaSize,
                    mMinMargins), this);
        }
    }
}
