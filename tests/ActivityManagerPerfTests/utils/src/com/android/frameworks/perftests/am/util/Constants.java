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
    public static final String EXTRA_SOURCE_PACKAGE = "source_package";
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_REQ_FINISH_ACTIVITY = "req_finish_activity";
    public static final String EXTRA_SEQ = "seq";
    public static final String EXTRA_ARG1 = "arg1";
    public static final String EXTRA_ARG2 = "arg2";

    public static final int RESULT_NO_ERROR = 0;
    public static final int RESULT_ERROR = 1;
    public static final String STUB_INIT_SERVICE_NAME = "com.android.stubs.am.InitService";

    public static final int COMMAND_BIND_SERVICE = 1;
    public static final int COMMAND_UNBIND_SERVICE = 2;
    public static final int COMMAND_ACQUIRE_CONTENT_PROVIDER = 3;
    public static final int COMMAND_RELEASE_CONTENT_PROVIDER = 4;
    public static final int COMMAND_SEND_BROADCAST = 5;
    public static final int COMMAND_START_ACTIVITY = 6;
    public static final int COMMAND_STOP_ACTIVITY = 7;

    public static final int MSG_DEFAULT = 0;
    public static final int MSG_UNBIND_DONE = 1;

    public static final int REPLY_PACKAGE_START_RESULT = 0;
    public static final int REPLY_COMMAND_RESULT = 1;

    public static final String STUB_ACTION_ACTIVITY =
            "com.android.stubs.am.ACTION_START_TEST_ACTIVITY";
    public static final String STUB_ACTION_BROADCAST =
            "com.android.stubs.am.ACTION_BROADCAST_TEST";
}
