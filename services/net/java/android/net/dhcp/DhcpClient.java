/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.dhcp;

/**
 * TODO: remove this class after migrating clients.
 */
public class DhcpClient {
    public static final int CMD_PRE_DHCP_ACTION = 1003;
    public static final int CMD_POST_DHCP_ACTION = 1004;
    public static final int CMD_PRE_DHCP_ACTION_COMPLETE = 1006;

    public static final int DHCP_SUCCESS = 1;
    public static final int DHCP_FAILURE = 2;
}
