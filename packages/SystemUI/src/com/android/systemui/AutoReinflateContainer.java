/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.os.LocaleList;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom {@link FrameLayout} that re-inflates when changes to {@link Configuration} happen.
 * Currently supports changes to density and locale.
 */
public class AutoReinflateContainer extends FrameLayout {

    private final List<InflateListener> mInflateListeners = new ArrayList<>();
    private final int mLayout;
    private int mDensity;
    private LocaleList mLocaleList;

    public AutoReinflateContainer(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        mDensity = context.getResources().getConfiguration().densityDpi;
        mLocaleList = context.getResources().getConfiguration().getLocales();

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.AutoReinflateContainer);
        if (!a.hasValue(R.styleable.AutoReinflateContainer_android_layout)) {
            throw new IllegalArgumentException("AutoReinflateContainer must contain a layout");
        }
        mLayout = a.getResourceId(R.styleable.AutoReinflateContainer_android_layout, 0);
        inflateLayout();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean shouldInflateLayout = false;
        final int density = newConfig.densityDpi;
        if (density != mDensity) {
            mDensity = density;
            shouldInflateLayout = true;
        }
        final LocaleList localeList = newConfig.getLocales();
        if (localeList != mLocaleList) {
            mLocaleList = localeList;
            shouldInflateLayout = true;
        }

        if (shouldInflateLayout) {
            inflateLayout();
        }
    }

    private void inflateLayout() {
        removeAllViews();
        LayoutInflater.from(getContext()).inflate(mLayout, this);
        final int N = mInflateListeners.size();
        for (int i = 0; i < N; i++) {
            mInflateListeners.get(i).onInflated(getChildAt(0));
        }
    }

    public void addInflateListener(InflateListener listener) {
        mInflateListeners.add(listener);
        listener.onInflated(getChildAt(0));
    }

    public interface InflateListener {
        /**
         * Called whenever a new view is inflated.
         */
        void onInflated(View v);
    }
}
