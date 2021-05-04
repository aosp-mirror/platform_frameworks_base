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

package android.app;

public class AppOpsManager {

    public int noteOp(String op, int uid, String packageName) {
        throw new UnsupportedOperationException();
    }

    public int noteOp(int op) {
        throw new UnsupportedOperationException();
    }

    public int noteOp(int op, int uid, String packageName) {
        throw new UnsupportedOperationException();
    }

    public int noteOp(String op, int uid, String packageName,
            String attributionTag, String message) {
        throw new UnsupportedOperationException();
    }

    public int noteOp(int op, int uid, String packageName,
            String attributionTag, String message) {
        throw new UnsupportedOperationException();
    }

    public int noteOpNoThrow(String op, int uid, String packageName) {
        throw new UnsupportedOperationException();
    }

    public int noteOpNoThrow(int op, int uid, String packageName) {
        throw new UnsupportedOperationException();
    }

    public int startOp(int op) {
        throw new UnsupportedOperationException();
    }

    public int startOp(int op, int uid, String packageName) {
        throw new UnsupportedOperationException();
    }

    public int startOp(int op, int uid, String packageName, boolean startIfModeDefault) {
        throw new UnsupportedOperationException();
    }

    public int startOp(String op, int uid, String packageName) {
        throw new UnsupportedOperationException();
    }

    public int startOpNoThrow(String op, int uid, String packageName) {
        throw new UnsupportedOperationException();
    }

    public int startOpNoThrow(int op, int uid, String packageName) {
        throw new UnsupportedOperationException();
    }

    public int startOpNoThrow(int op, int uid, String packageName, boolean startIfModeDefault) {
        throw new UnsupportedOperationException();
    }

    public int noteProxyOp(String op, String proxiedPackageName) {
        throw new UnsupportedOperationException();
    }

    public int noteProxyOp(int op, String proxiedPackageName) {
        throw new UnsupportedOperationException();
    }

    public int noteProxyOpNoThrow(String op, String proxiedPackageName) {
        throw new UnsupportedOperationException();
    }

    public int noteProxyOpNoThrow(String op, String proxiedPackageName,
            int proxiedUid) {
        throw new UnsupportedOperationException();
    }

    public void finishOp(int op) {
        throw new UnsupportedOperationException();
    }

    public void finishOp(String op, int uid, String packageName) {
        throw new UnsupportedOperationException();
    }

    public void finishOp(int op, int uid, String packageName) {
        throw new UnsupportedOperationException();
    }
}
