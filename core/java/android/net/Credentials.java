/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.net;

/**
 * A class for representing UNIX credentials passed via ancillary data
 * on UNIX domain sockets. See "man 7 unix" on a desktop linux distro.
 */
public class Credentials {
    /** pid of process. root peers may lie. */
    private final int pid;
    /** uid of process. root peers may lie. */
    private final int uid;
    /** gid of process. root peers may lie. */
    private final int gid;

    public Credentials (int pid, int uid, int gid) {
        this.pid = pid;
        this.uid = uid;
        this.gid = gid;
    }

    public int getPid() {
        return pid;
    }

    public int getUid() {
        return uid;
    }
    
    public int getGid() {
        return gid;
    }
}
