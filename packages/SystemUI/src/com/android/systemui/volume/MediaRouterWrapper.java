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
 * limitations under the License.
 */

package com.android.systemui.volume;

import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;

import java.util.List;

/**
 * Wrapper for final class MediaRouter, for testing.
 */
public class MediaRouterWrapper {

    private final MediaRouter mRouter;

    public MediaRouterWrapper(MediaRouter router)
    {
        mRouter = router;
    }

    public void addCallback(MediaRouteSelector selector, MediaRouter.Callback callback, int flags) {
        mRouter.addCallback(selector, callback, flags);
    }

    public void removeCallback(MediaRouter.Callback callback) {
        mRouter.removeCallback(callback);
    }

    public void unselect(int reason) {
        mRouter.unselect(reason);
    }

    public List<MediaRouter.RouteInfo> getRoutes() {
        return mRouter.getRoutes();
    }
}