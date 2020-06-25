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
public class ContextUserIdCheckerTest {
    private CompilationTestHelper compilationHelper;

    @Before
    public void setUp() {
        compilationHelper = CompilationTestHelper.newInstance(
                ContextUserIdChecker.class, getClass());
    }

    @Test
    public void testValid() {
        compilationHelper
                .addSourceFile("/android/annotation/SystemService.java")
                .addSourceFile("/android/content/Context.java")
                .addSourceFile("/android/foo/IFooService.java")
                .addSourceFile("/android/os/IInterface.java")
                .addSourceFile("/android/os/RemoteException.java")
                .addSourceLines("FooManager.java",
                        "import android.annotation.SystemService;",
                        "import android.content.Context;",
                        "import android.foo.IFooService;",
                        "import android.os.RemoteException;",
                        "@SystemService(\"foo\") public class FooManager {",
                        "  Context mContext;",
                        "  IFooService mService;",
                        "  final int mUserId;",
                        "  FooManager(Context context) {",
                        "    mUserId = mContext.getUserId();",
                        "  }",
                        "  void bar() throws RemoteException {",
                        "    mService.baz(null, mContext.getUserId());",
                        "  }",
                        "  void baz() throws RemoteException {",
                        "    mService.baz(null, mUserId);",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testInvalid() {
        compilationHelper
                .addSourceFile("/android/annotation/SystemService.java")
                .addSourceFile("/android/content/Context.java")
                .addSourceFile("/android/foo/IFooService.java")
                .addSourceFile("/android/os/IInterface.java")
                .addSourceFile("/android/os/UserHandle.java")
                .addSourceFile("/android/os/RemoteException.java")
                .addSourceLines("FooManager.java",
                        "import android.annotation.SystemService;",
                        "import android.content.Context;",
                        "import android.foo.IFooService;",
                        "import android.os.UserHandle;",
                        "import android.os.RemoteException;",
                        "@SystemService(\"foo\") public class FooManager {",
                        "  Context mContext;",
                        "  IFooService mService;",
                        "  void bar() throws RemoteException {",
                        "    // BUG: Diagnostic contains:",
                        "    mService.baz(null, 0);",
                        "  }",
                        "  void baz() throws RemoteException {",
                        "    // BUG: Diagnostic contains:",
                        "    mService.baz(null, UserHandle.myUserId());",
                        "  }",
                        "}")
                .doTest();
    }
}
