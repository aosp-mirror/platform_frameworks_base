/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.frameworks.perftests.am.util;

public class Constants {
    public static final String TYPE_TARGET_PACKAGE_START = "target_package_start";
    public static final String TYPE_BROADCAST_RECEIVE = "broadcast_receive";
    public static final String TYPE_SERVICE_BIND = "service_bind";
    public static final String TYPE_SERVICE_START = "service_start";
    public static final String TYPE_SERVICE_CONNECTED = "service_connection_connect";

    public static final String ACTION_BROADCAST_MANIFEST_RECEIVE =
            "com.android.frameworks.perftests.ACTION_BROADCAST_MANIFEST_RECEIVE";
    public static final String ACTION_BROADCAST_REGISTERED_RECEIVE =
            "com.android.frameworks.perftests.ACTION_BROADCAST_REGISTERED_RECEIVE";

    public static final String EXTRA_RECEIVER_CALLBACK = "receiver_callback_binder";
    public static final String EXTRA_LOOPER_IDLE_CALLBACK = "looper_idle_callback_binder";
}
