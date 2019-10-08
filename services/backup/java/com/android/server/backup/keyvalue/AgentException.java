/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.keyvalue;

/**
 * This represents something wrong with a specific package. For example:
 * <ul>
 *     <li>Package unknown.
 *     <li>Package is not eligible for backup anymore.
 *     <li>Backup agent timed out.
 *     <li>Backup agent wrote protected keys.
 *     <li>...
 * </ul>
 *
 * @see KeyValueBackupTask
 * @see TaskException
 */
class AgentException extends BackupException {
    static AgentException transitory() {
        return new AgentException(/* transitory */ true);
    }

    static AgentException transitory(Exception cause) {
        return new AgentException(/* transitory */ true, cause);
    }

    static AgentException permanent() {
        return new AgentException(/* transitory */ false);
    }

    static AgentException permanent(Exception cause) {
        return new AgentException(/* transitory */ false, cause);
    }

    private final boolean mTransitory;

    private AgentException(boolean transitory) {
        mTransitory = transitory;
    }

    private AgentException(boolean transitory, Exception cause) {
        super(cause);
        mTransitory = transitory;
    }

    boolean isTransitory() {
        return mTransitory;
    }
}
