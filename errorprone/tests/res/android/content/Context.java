/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.content;

public class Context {
    public int getUserId() {
        throw new UnsupportedOperationException();
    }

    public void enforceCallingOrSelfPermission(String permission, String message) {
        throw new UnsupportedOperationException();
    }

    public void sendBroadcast(Intent intent) {
        throw new UnsupportedOperationException();
    }

    public void sendBroadcast(Intent intent, String receiverPermission) {
        throw new UnsupportedOperationException();
    }

    public void sendBroadcastWithMultiplePermissions(Intent intent, String[] receiverPermissions) {
        throw new UnsupportedOperationException();
    }
}
