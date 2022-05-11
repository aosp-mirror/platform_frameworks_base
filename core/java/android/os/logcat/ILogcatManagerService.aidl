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

package android.os.logcat;

/**
 * @hide
 */
oneway interface ILogcatManagerService {
    /**
     * The function is called by logd to notify LogcatManagerService
     * that a client makes privileged log data access request.
     *
     * @param uid The UID of client who makes the request.
     * @param gid The GID of client who makes the request.
     * @param pid The PID of client who makes the request.
     * @param fd  The FD (Socket) of client who makes the request.
     */
    void startThread(in int uid, in int gid, in int pid, in int fd);


    /**
     * The function is called by logd to notify LogcatManagerService
     * that a client finished the privileged log data access.
     *
     * @param uid The UID of client who makes the request.
     * @param gid The GID of client who makes the request.
     * @param pid The PID of client who makes the request.
     * @param fd  The FD (Socket) of client who makes the request.
     */
    void finishThread(in int uid, in int gid, in int pid, in int fd);
}
