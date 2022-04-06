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

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.CoreStartable;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.media.MediaData;
import com.android.systemui.media.MediaDataManager;
import com.android.systemui.media.SmartspaceMediaData;

import javax.inject.Inject;

/**
 * {@link MediaDreamSentinel} is responsible for tracking media state and registering/unregistering
 * the media complication as appropriate
 */
public class MediaDreamSentinel extends CoreStartable {
    private MediaDataManager.Listener mListener = new MediaDataManager.Listener() {
        private boolean mAdded;
        @Override
        public void onSmartspaceMediaDataRemoved(@NonNull String key, boolean immediately) {
        }

        @Override
        public void onMediaDataRemoved(@NonNull String key) {
            if (!mAdded) {
                return;
            }

            if (mMediaDataManager.hasActiveMedia()) {
                return;
            }

            mAdded = false;
            mDreamOverlayStateController.removeComplication(mComplication);
        }

        @Override
        public void onSmartspaceMediaDataLoaded(@NonNull String key,
                @NonNull SmartspaceMediaData data, boolean shouldPrioritize) {
        }

        @Override
        public void onMediaDataLoaded(@NonNull String key, @Nullable String oldKey,
                @NonNull MediaData data, boolean immediately, int receivedSmartspaceCardLatency,
                boolean isSsReactivated) {
            if (mAdded) {
                return;
            }

            if (!mMediaDataManager.hasActiveMedia()) {
                return;
            }

            mAdded = true;
            mDreamOverlayStateController.addComplication(mComplication);
        }
    };

    private final MediaDataManager mMediaDataManager;
    private final DreamOverlayStateController mDreamOverlayStateController;
    private final MediaDreamComplication mComplication;

    @Inject
    public MediaDreamSentinel(Context context, MediaDataManager mediaDataManager,
            DreamOverlayStateController dreamOverlayStateController,
            MediaDreamComplication complication) {
        super(context);
        mMediaDataManager = mediaDataManager;
        mDreamOverlayStateController = dreamOverlayStateController;
        mComplication = complication;
    }

    @Override
    public void start() {
        mMediaDataManager.addListener(mListener);
    }
}
