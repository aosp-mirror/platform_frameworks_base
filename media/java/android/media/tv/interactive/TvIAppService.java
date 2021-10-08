/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.media.tv.interactive;

import android.app.Service;
import android.view.KeyEvent;

/**
 * The TvIAppService class represents a TV interactive applications RTE.
 * @hide
 */
public abstract class TvIAppService extends Service {
    private static final boolean DEBUG = false;
    private static final String TAG = "TvIAppService";

    /**
     * Base class for derived classes to implement to provide a TV interactive app session.
     */
    public abstract static class Session implements KeyEvent.Callback {
        /**
         * Starts TvIAppService session.
         */
        public void onStartIApp() {
        }

        void startIApp() {
            onStartIApp();
        }
    }

    /**
     * Implements the internal ITvIAppSession interface.
     */
    public static class ITvIAppSessionWrapper extends ITvIAppSession.Stub {
        private final Session mSessionImpl;

        public ITvIAppSessionWrapper(Session mSessionImpl) {
            this.mSessionImpl = mSessionImpl;
        }

        @Override
        public void startIApp() {
            mSessionImpl.startIApp();
        }
    }
}
