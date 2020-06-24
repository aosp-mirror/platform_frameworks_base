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

package com.google.errorprone.bugpatterns.android;

import com.google.errorprone.CompilationTestHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class UidCheckerTest {
    private CompilationTestHelper compilationHelper;

    @Before
    public void setUp() {
        compilationHelper = CompilationTestHelper.newInstance(
                UidChecker.class, getClass());
    }

    @Test
    public void testTypical() {
        compilationHelper
                .addSourceLines("Example.java",
                        "public abstract class Example {",
                        "  abstract void bar(int pid, int uid, int userId);",
                        "  abstract int getUserId();",
                        "  void foo(int pid, int uid, int userId, int unrelated) {",
                        "    bar(0, 0, 0);",
                        "    bar(pid, uid, userId);",
                        "    bar(pid, uid, getUserId());",
                        "    bar(unrelated, unrelated, unrelated);",
                        "    // BUG: Diagnostic contains:",
                        "    bar(uid, pid, userId);",
                        "    // BUG: Diagnostic contains:",
                        "    bar(pid, userId, uid);",
                        "    // BUG: Diagnostic contains:",
                        "    bar(getUserId(), 0, 0);",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testCallingUid() {
        compilationHelper
                .addSourceFile("/android/os/Binder.java")
                .addSourceFile("/android/os/UserHandle.java")
                .addSourceLines("Example.java",
                        "import android.os.Binder;",
                        "import android.os.UserHandle;",
                        "public abstract class Example {",
                        "  int callingUserId;",
                        "  int callingUid;",
                        "  abstract void setCallingUserId(int callingUserId);",
                        "  abstract void setCallingUid(int callingUid);",
                        "  void doUserId(int callingUserId) {",
                        "    setCallingUserId(UserHandle.getUserId(Binder.getCallingUid()));",
                        "    setCallingUserId(this.callingUserId);",
                        "    setCallingUserId(callingUserId);",
                        "    // BUG: Diagnostic contains:",
                        "    setCallingUserId(Binder.getCallingUid());",
                        "    // BUG: Diagnostic contains:",
                        "    setCallingUserId(this.callingUid);",
                        "    // BUG: Diagnostic contains:",
                        "    setCallingUserId(callingUid);",
                        "  }",
                        "  void doUid(int callingUserId) {",
                        "    setCallingUid(Binder.getCallingUid());",
                        "    setCallingUid(this.callingUid);",
                        "    setCallingUid(callingUid);",
                        "    // BUG: Diagnostic contains:",
                        "    setCallingUid(UserHandle.getUserId(Binder.getCallingUid()));",
                        "    // BUG: Diagnostic contains:",
                        "    setCallingUid(this.callingUserId);",
                        "    // BUG: Diagnostic contains:",
                        "    setCallingUid(callingUserId);",
                        "  }",
                        "  void doInner() {",
                        "    // BUG: Diagnostic contains:",
                        "    setCallingUserId(UserHandle.getUserId(callingUserId));",
                        "  }",
                        "}")
                .doTest();
    }
}
