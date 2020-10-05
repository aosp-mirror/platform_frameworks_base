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
public class RethrowFromSystemCheckerTest {
    private CompilationTestHelper compilationHelper;

    @Before
    public void setUp() {
        compilationHelper = CompilationTestHelper.newInstance(
                RethrowFromSystemChecker.class, getClass());
    }

    @Test
    public void testValid() {
        compilationHelper
                .addSourceFile("/android/annotation/SystemService.java")
                .addSourceFile("/android/foo/IFooService.java")
                .addSourceFile("/android/os/IInterface.java")
                .addSourceFile("/android/os/RemoteException.java")
                .addSourceLines("FooManager.java",
                        "import android.annotation.SystemService;",
                        "import android.foo.IFooService;",
                        "import android.os.RemoteException;",
                        "@SystemService(\"foo\") public class FooManager {",
                        "  IFooService mService;",
                        "  void bar() {",
                        "    try {",
                        "      mService.bar();",
                        "    } catch (RemoteException e) {",
                        "      throw e.rethrowFromSystemServer();",
                        "    }",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testInvalid() {
        compilationHelper
                .addSourceFile("/android/annotation/SystemService.java")
                .addSourceFile("/android/foo/IFooService.java")
                .addSourceFile("/android/os/IInterface.java")
                .addSourceFile("/android/os/RemoteException.java")
                .addSourceLines("FooManager.java",
                        "import android.annotation.SystemService;",
                        "import android.foo.IFooService;",
                        "import android.os.RemoteException;",
                        "@SystemService(\"foo\") public class FooManager {",
                        "  IFooService mService;",
                        "  void bar() {",
                        "    try {",
                        "      mService.bar();",
                        "    // BUG: Diagnostic contains:",
                        "    } catch (RemoteException e) {",
                        "      e.printStackTrace();",
                        "    }",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testIgnored() {
        compilationHelper
                .addSourceFile("/android/annotation/SystemService.java")
                .addSourceFile("/android/foo/IFooService.java")
                .addSourceFile("/android/os/IInterface.java")
                .addSourceFile("/android/os/RemoteException.java")
                .addSourceLines("FooManager.java",
                        "import android.annotation.SystemService;",
                        "import android.foo.IFooService;",
                        "import android.os.RemoteException;",
                        "@SystemService(\"foo\") public class FooManager {",
                        "  IFooService mService;",
                        "  void bar() {",
                        "    try {",
                        "      mService.bar();",
                        "    // BUG: Diagnostic contains:",
                        "    } catch (RemoteException ignored) {",
                        "    }",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testTelephony() {
        compilationHelper
                .addSourceFile("/android/annotation/SystemService.java")
                .addSourceFile("/com/android/internal/telephony/ITelephony.java")
                .addSourceFile("/android/os/IInterface.java")
                .addSourceFile("/android/os/RemoteException.java")
                .addSourceLines("TelephonyManager.java",
                        "import android.annotation.SystemService;",
                        "import com.android.internal.telephony.ITelephony;",
                        "import android.os.RemoteException;",
                        "@SystemService(\"telephony\") public class TelephonyManager {",
                        "  ITelephony mService;",
                        "  void bar() {",
                        "    try {",
                        "      mService.bar();",
                        "    } catch (RemoteException ignored) {",
                        "    }",
                        "  }",
                        "}")
                .doTest();
    }
}
