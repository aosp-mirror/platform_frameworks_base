/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package lockedregioncodeinjection;

public class TestScopedTarget {

    public final TestScopedLock mLock;;

    private boolean mNextEnterThrows = false;
    private boolean mNextExitThrows = false;

    TestScopedTarget() {
        mLock = new TestScopedLock(this);
    }

    TestScopedLock scopedLock() {
        return mLock;
    }

    Object untypedLock() {
        return mLock;
    }

    void enter(int level) {
        if (mNextEnterThrows) {
            mNextEnterThrows = false;
            throw new RuntimeException();
        }
    }

    void exit(int level) {
        if (mNextExitThrows) {
            mNextExitThrows = false;
            throw new RuntimeException();
        }
    }

    void throwOnEnter(boolean b) {
        mNextEnterThrows = b;
    }

    void throwOnExit(boolean b) {
        mNextExitThrows = b;
    }
}
