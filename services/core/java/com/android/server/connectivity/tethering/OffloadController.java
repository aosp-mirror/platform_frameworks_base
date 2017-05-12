/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.connectivity.tethering;

import android.net.LinkProperties;
import android.os.Handler;
import android.net.util.SharedLog;

/**
 * A wrapper around hardware offload interface.
 *
 * @hide
 */
public class OffloadController {
    private static final String TAG = OffloadController.class.getSimpleName();

    private final Handler mHandler;
    private final SharedLog mLog;
    private LinkProperties mUpstreamLinkProperties;

    public OffloadController(Handler h, SharedLog log) {
        mHandler = h;
        mLog = log.forSubComponent(TAG);
    }

    public void start() {
        // TODO: initOffload() and configure callbacks to be handled on our
        // preferred Handler.
        mLog.i("tethering offload not supported");
    }

    public void stop() {
        // TODO: stopOffload().
        mUpstreamLinkProperties = null;
    }

    public void setUpstreamLinkProperties(LinkProperties lp) {
        // TODO: setUpstreamParameters().
        mUpstreamLinkProperties = lp;
    }
}
