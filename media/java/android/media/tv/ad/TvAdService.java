/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.media.tv.ad;

import android.app.Service;
import android.view.KeyEvent;

/**
 * The TvAdService class represents a TV client-side advertisement service.
 * @hide
 */
public abstract class TvAdService extends Service {
    private static final boolean DEBUG = false;
    private static final String TAG = "TvAdService";

    /**
     * Base class for derived classes to implement to provide a TV AD session.
     */
    public abstract static class Session implements KeyEvent.Callback {
        /**
         * Starts TvAdService session.
         */
        public void onStartAdService() {
        }

        void startAdService() {
            onStartAdService();
        }
    }

    /**
     * Implements the internal ITvAdService interface.
     */
    public static class ITvAdSessionWrapper extends ITvAdSession.Stub {
        private final Session mSessionImpl;

        public ITvAdSessionWrapper(Session mSessionImpl) {
            this.mSessionImpl = mSessionImpl;
        }

        @Override
        public void startAdService() {
            mSessionImpl.startAdService();
        }
    }
}
