/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dreams.complication;

import static com.android.systemui.dreams.complication.dagger.DreamPreviewComplicationComponent.DREAM_LABEL;
import static com.android.systemui.dreams.complication.dagger.DreamPreviewComplicationComponent.DreamPreviewComplicationModule.DREAM_PREVIEW_COMPLICATION_LAYOUT_PARAMS;
import static com.android.systemui.dreams.complication.dagger.DreamPreviewComplicationComponent.DreamPreviewComplicationModule.DREAM_PREVIEW_COMPLICATION_VIEW;

import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.systemui.dreams.complication.dagger.DreamPreviewComplicationComponent;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Preview complication shown when user is previewing a dream.
 */
public class DreamPreviewComplication implements Complication {
    DreamPreviewComplicationComponent.Factory mComponentFactory;
    @Nullable
    private CharSequence mDreamLabel;

    /**
     * Default constructor for {@link DreamPreviewComplication}.
     */
    @Inject
    public DreamPreviewComplication(
            DreamPreviewComplicationComponent.Factory componentFactory) {
        mComponentFactory = componentFactory;
    }

    /**
     * Create {@link DreamPreviewViewHolder}.
     */
    @Override
    public ViewHolder createView(ComplicationViewModel model) {
        return mComponentFactory.create(model, mDreamLabel).getViewHolder();
    }

    /**
     * Sets the user-facing label for the current dream.
     */
    public void setDreamLabel(@Nullable CharSequence dreamLabel) {
        mDreamLabel = dreamLabel;
    }

    /**
     * ViewHolder to contain value/logic associated with a Preview Complication View.
     */
    public static class DreamPreviewViewHolder implements ViewHolder {
        private final TextView mView;
        private final ComplicationLayoutParams mLayoutParams;
        private final DreamPreviewViewController mViewController;

        @Inject
        DreamPreviewViewHolder(@Named(DREAM_PREVIEW_COMPLICATION_VIEW) TextView view,
                DreamPreviewViewController controller,
                @Named(DREAM_PREVIEW_COMPLICATION_LAYOUT_PARAMS)
                        ComplicationLayoutParams layoutParams,
                @Named(DREAM_LABEL) @Nullable CharSequence dreamLabel) {
            mView = view;
            mLayoutParams = layoutParams;
            mViewController = controller;
            mViewController.init();

            if (!TextUtils.isEmpty(dreamLabel)) {
                mView.setText(dreamLabel);
            }
            for (Drawable drawable : mView.getCompoundDrawablesRelative()) {
                if (drawable instanceof BitmapDrawable) {
                    drawable.setAutoMirrored(true);
                }
            }
        }

        @Override
        public View getView() {
            return mView;
        }

        @Override
        public ComplicationLayoutParams getLayoutParams() {
            return mLayoutParams;
        }

        @Override
        public int getCategory() {
            return CATEGORY_SYSTEM;
        }
    }

    /**
     * ViewController to contain value/logic associated with a Preview Complication View.
     */
    static class DreamPreviewViewController extends ViewController<TextView> {
        private final ComplicationViewModel mViewModel;

        @Inject
        DreamPreviewViewController(@Named(DREAM_PREVIEW_COMPLICATION_VIEW) TextView view,
                ComplicationViewModel viewModel) {
            super(view);
            mViewModel = viewModel;
        }

        @Override
        protected void onViewAttached() {
            mView.setOnClickListener(v -> mViewModel.exitDream());
        }

        @Override
        protected void onViewDetached() {
            mView.setOnClickListener(null);
        }
    }
}
