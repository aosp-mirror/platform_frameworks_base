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

import static com.android.systemui.media.dagger.MediaModule.DREAM;
import static com.android.systemui.media.dream.dagger.MediaComplicationComponent.MediaComplicationModule.MEDIA_COMPLICATION_CONTAINER;

import android.widget.FrameLayout;

import com.android.systemui.media.MediaHierarchyManager;
import com.android.systemui.media.MediaHost;
import com.android.systemui.media.MediaHostState;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * {@link MediaComplicationViewController} handles connecting the
 * {@link com.android.systemui.dreams.complication.Complication} view to the {@link MediaHost}.
 */
public class MediaComplicationViewController extends ViewController<FrameLayout> {
    private final MediaHost mMediaHost;

    @Inject
    public MediaComplicationViewController(
            @Named(MEDIA_COMPLICATION_CONTAINER) FrameLayout view,
            @Named(DREAM) MediaHost mediaHost) {
        super(view);
        mMediaHost = mediaHost;
    }

    @Override
    protected void onInit() {
        super.onInit();
        mMediaHost.setExpansion(MediaHostState.COLLAPSED);
        mMediaHost.setShowsOnlyActiveMedia(true);
        mMediaHost.setFalsingProtectionNeeded(true);
        mMediaHost.init(MediaHierarchyManager.LOCATION_DREAM_OVERLAY);
    }

    @Override
    protected void onViewAttached() {
        mMediaHost.hostView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        mView.addView(mMediaHost.hostView);
    }

    @Override
    protected void onViewDetached() {
        mView.removeView(mMediaHost.hostView);
    }
}
