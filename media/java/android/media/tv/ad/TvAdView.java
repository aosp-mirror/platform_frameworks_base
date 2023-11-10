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

import android.content.Context;
import android.util.Log;
import android.view.ViewGroup;

/**
 * Displays contents of TV AD services.
 * @hide
 */
public class TvAdView extends ViewGroup {
    private static final String TAG = "TvAdView";
    private static final boolean DEBUG = false;

    // TODO: create session
    private TvAdManager.Session mSession;

    public TvAdView(Context context) {
        super(context, /* attrs = */null, /* defStyleAttr = */0);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (DEBUG) {
            Log.d(TAG,
                    "onLayout (left=" + l + ", top=" + t + ", right=" + r + ", bottom=" + b + ",)");
        }
    }

    /**
     * Starts the AD service.
     */
    public void startAdService() {
        if (DEBUG) {
            Log.d(TAG, "start");
        }
        if (mSession != null) {
            mSession.startAdService();
        }
    }
}
