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

package com.android.systemui.media.dream;

import static com.android.systemui.media.dream.dagger.MediaComplicationComponent.MediaComplicationModule.MEDIA_COMPLICATION_CONTAINER;
import static com.android.systemui.media.dream.dagger.MediaComplicationComponent.MediaComplicationModule.MEDIA_COMPLICATION_LAYOUT_PARAMS;

import android.view.View;
import android.widget.FrameLayout;

import com.android.systemui.dreams.complication.Complication;
import com.android.systemui.dreams.complication.ComplicationLayoutParams;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * {@link Complication.ViewHolder} implementation for media control.
 */
public class MediaViewHolder implements Complication.ViewHolder {
    private final FrameLayout mContainer;
    private final MediaComplicationViewController mViewController;
    private final ComplicationLayoutParams mLayoutParams;

    @Inject
    MediaViewHolder(@Named(MEDIA_COMPLICATION_CONTAINER) FrameLayout container,
            MediaComplicationViewController controller,
            @Named(MEDIA_COMPLICATION_LAYOUT_PARAMS) ComplicationLayoutParams layoutParams) {
        mContainer = container;
        mViewController = controller;
        mViewController.init();
        mLayoutParams = layoutParams;
    }

    @Override
    public View getView() {
        return mContainer;
    }

    @Override
    public ComplicationLayoutParams getLayoutParams() {
        return mLayoutParams;
    }
}
