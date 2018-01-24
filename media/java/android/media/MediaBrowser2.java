/*
 * Copyright 2018 The Android Open Source Project
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

package android.media;

import android.annotation.Nullable;
import android.content.Context;
import android.media.update.ApiLoader;
import android.media.update.MediaBrowser2Provider;
import android.os.Bundle;

import java.util.concurrent.Executor;

/**
 * Browses media content offered by a {@link MediaLibraryService2}.
 * @hide
 */
public class MediaBrowser2 extends MediaController2 {
    // Equals to the ((MediaBrowser2Provider) getProvider())
    private final MediaBrowser2Provider mProvider;

    /**
     * Callback to listen events from {@link MediaLibraryService2}.
     */
    public abstract static class BrowserCallback extends MediaController2.ControllerCallback {
        /**
         * Called with the result of {@link #getBrowserRoot(Bundle)}.
         * <p>
         * {@code rootMediaId} and {@code rootExtra} can be {@code null} if the browser root isn't
         * available.
         *
         * @param rootHints rootHints that you previously requested.
         * @param rootMediaId media id of the browser root. Can be {@code null}
         * @param rootExtra extra of the browser root. Can be {@code null}
         */
        public abstract void onGetRootResult(Bundle rootHints, @Nullable String rootMediaId,
                @Nullable Bundle rootExtra);
    }

    public MediaBrowser2(Context context, SessionToken token, BrowserCallback callback,
            Executor executor) {
        super(context, token, callback, executor);
        mProvider = (MediaBrowser2Provider) getProvider();
    }

    @Override
    MediaBrowser2Provider createProvider(Context context, SessionToken token,
            ControllerCallback callback, Executor executor) {
        return ApiLoader.getProvider(context)
                .createMediaBrowser2(this, context, token, (BrowserCallback) callback, executor);
    }

    public void getBrowserRoot(Bundle rootHints) {
        mProvider.getBrowserRoot_impl(rootHints);
    }
}
