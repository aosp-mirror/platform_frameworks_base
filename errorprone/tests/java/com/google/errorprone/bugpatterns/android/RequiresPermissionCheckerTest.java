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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.bugpatterns.android.RequiresPermissionChecker.ParsedRequiresPermission;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.Collection;

@RunWith(JUnit4.class)
public class RequiresPermissionCheckerTest {
    private CompilationTestHelper compilationHelper;

    private static final String RED = "red";
    private static final String BLUE = "blue";

    @Before
    public void setUp() {
        compilationHelper = CompilationTestHelper.newInstance(
                RequiresPermissionChecker.class, getClass());
    }

    private static ParsedRequiresPermission build(Collection<String> allOf,
            Collection<String> anyOf) {
        ParsedRequiresPermission res = new ParsedRequiresPermission();
        res.allOf.addAll(allOf);
        res.anyOf.addAll(anyOf);
        return res;
    }

    @Test
    public void testParser_AllOf() {
        final ParsedRequiresPermission a = build(Arrays.asList(RED, BLUE), Arrays.asList());
        final ParsedRequiresPermission b = build(Arrays.asList(RED), Arrays.asList());
        assertTrue(a.containsAll(b));
        assertFalse(b.containsAll(a));
    }

    @Test
    public void testParser_AnyOf() {
        final ParsedRequiresPermission a = build(Arrays.asList(), Arrays.asList(RED, BLUE));
        final ParsedRequiresPermission b = build(Arrays.asList(), Arrays.asList(RED));
        assertTrue(a.containsAll(b));
        assertTrue(b.containsAll(a));
    }

    @Test
    public void testParser_AnyOf_AllOf() {
        final ParsedRequiresPermission a = build(Arrays.asList(RED, BLUE), Arrays.asList());
        final ParsedRequiresPermission b = build(Arrays.asList(), Arrays.asList(RED));
        assertTrue(a.containsAll(b));
        assertFalse(b.containsAll(a));
    }

    @Test
    public void testSimple() {
        compilationHelper
                .addSourceFile("/android/annotation/RequiresPermission.java")
                .addSourceFile("/android/content/Context.java")
                .addSourceFile("/android/content/Intent.java")
                .addSourceLines("ColorManager.java",
                        "import android.annotation.RequiresPermission;",
                        "import android.content.Context;",
                        "public abstract class ColorManager extends Context {",
                        "  private static final String RED = \"red\";",
                        "  private static final String BLUE = \"blue\";",
                        "  @RequiresPermission(RED) abstract int red();",
                        "  @RequiresPermission(BLUE) abstract int blue();",
                        "  @RequiresPermission(allOf={RED, BLUE}) abstract int all();",
                        "  @RequiresPermission(anyOf={RED, BLUE}) abstract int any();",
                        "  @RequiresPermission(allOf={RED, BLUE})",
                        "  int redPlusBlue() { return red() + blue(); }",
                        "  @RequiresPermission(allOf={RED, BLUE})",
                        "  int allPlusRed() { return all() + red(); }",
                        "  @RequiresPermission(allOf={RED})",
                        "  int anyPlusRed() { return any() + red(); }",
                        "}")
                .doTest();
    }

    @Test
    public void testManager() {
        compilationHelper
                .addSourceFile("/android/annotation/RequiresPermission.java")
                .addSourceFile("/android/foo/IColorService.java")
                .addSourceFile("/android/os/IInterface.java")
                .addSourceLines("ColorManager.java",
                        "import android.annotation.RequiresPermission;",
                        "import android.foo.IColorService;",
                        "public class ColorManager {",
                        "  IColorService mService;",
                        "  @RequiresPermission(IColorService.RED)",
                        "  void redValid() {",
                        "    mService.red();",
                        "  }",
                        "  @RequiresPermission(allOf={IColorService.RED, IColorService.BLUE})",
                        "  // BUG: Diagnostic contains:",
                        "  void redOverbroad() {",
                        "    mService.red();",
                        "  }",
                        "  @RequiresPermission(IColorService.BLUE)",
                        "  void redInvalid() {",
                        "    // BUG: Diagnostic contains:",
                        "    mService.red();",
                        "  }",
                        "  void redMissing() {",
                        "    // BUG: Diagnostic contains:",
                        "    mService.red();",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testService() {
        compilationHelper
                .addSourceFile("/android/annotation/RequiresPermission.java")
                .addSourceFile("/android/content/Context.java")
                .addSourceFile("/android/content/Intent.java")
                .addSourceFile("/android/foo/IColorService.java")
                .addSourceFile("/android/os/IInterface.java")
                .addSourceLines("ColorService.java",
                        "import android.annotation.RequiresPermission;",
                        "import android.content.Context;",
                        "import android.foo.IColorService;",
                        "class ColorService extends Context implements IColorService {",
                        "  public void none() {}",
                        "  // BUG: Diagnostic contains:",
                        "  public void red() {}",
                        "  // BUG: Diagnostic contains:",
                        "  public void redAndBlue() {}",
                        "  // BUG: Diagnostic contains:",
                        "  public void redOrBlue() {}",
                        "  void onTransact(int code) {",
                        "    red();",
                        "  }",
                        "}",
                        "class ValidService extends ColorService {",
                        "  public void red() {",
                        "    ((Context) this).enforceCallingOrSelfPermission(RED, null);",
                        "  }",
                        "}",
                        "class InvalidService extends ColorService {",
                        "  public void red() {",
                        "    // BUG: Diagnostic contains:",
                        "    ((Context) this).enforceCallingOrSelfPermission(BLUE, null);",
                        "  }",
                        "}",
                        "class NestedService extends ColorService {",
                        "  public void red() {",
                        "    enforceRed();",
                        "  }",
                        "  @RequiresPermission(RED)",
                        "  public void enforceRed() {",
                        "    ((Context) this).enforceCallingOrSelfPermission(RED, null);",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testBroadcastReceiver() {
        compilationHelper
                .addSourceFile("/android/annotation/RequiresPermission.java")
                .addSourceFile("/android/content/BroadcastReceiver.java")
                .addSourceFile("/android/content/Context.java")
                .addSourceFile("/android/content/Intent.java")
                .addSourceLines("ColorManager.java",
                        "import android.annotation.RequiresPermission;",
                        "import android.content.BroadcastReceiver;",
                        "import android.content.Context;",
                        "import android.content.Intent;",
                        "public abstract class ColorManager extends BroadcastReceiver {",
                        "  private static final String RED = \"red\";",
                        "  @RequiresPermission(RED) abstract int red();",
                        "  // BUG: Diagnostic contains:",
                        "  public void onSend() { red(); }",
                        "  public void onReceive(Context context, Intent intent) { red(); }",
                        "}")
                .doTest();
    }

    @Test
    @Ignore
    public void testContentObserver() {
        compilationHelper
                .addSourceFile("/android/annotation/RequiresPermission.java")
                .addSourceFile("/android/database/ContentObserver.java")
                .addSourceLines("ColorManager.java",
                        "import android.annotation.RequiresPermission;",
                        "import android.database.ContentObserver;",
                        "public abstract class ColorManager {",
                        "  private static final String RED = \"red\";",
                        "  @RequiresPermission(RED) abstract int red();",
                        "  public void example() {",
                        "    ContentObserver ob = new ContentObserver() {",
                        "      public void onChange(boolean selfChange) {",
                        "        red();",
                        "      }",
                        "    };",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testHandler() {
        compilationHelper
                .addSourceFile("/android/annotation/RequiresPermission.java")
                .addSourceFile("/android/os/Handler.java")
                .addSourceFile("/android/os/Message.java")
                .addSourceLines("ColorManager.java",
                        "import android.annotation.RequiresPermission;",
                        "import android.os.Handler;",
                        "import android.os.Message;",
                        "public abstract class ColorManager extends Handler {",
                        "  private static final String RED = \"red\";",
                        "  @RequiresPermission(RED) abstract int red();",
                        "  // BUG: Diagnostic contains:",
                        "  public void sendMessage() { red(); }",
                        "  public void handleMessage(Message msg) { red(); }",
                        "}")
                .doTest();
    }

    @Test
    public void testDeathRecipient() {
        compilationHelper
                .addSourceFile("/android/annotation/RequiresPermission.java")
                .addSourceFile("/android/os/IBinder.java")
                .addSourceLines("ColorManager.java",
                        "import android.annotation.RequiresPermission;",
                        "import android.os.IBinder;",
                        "public abstract class ColorManager implements IBinder.DeathRecipient {",
                        "  private static final String RED = \"red\";",
                        "  @RequiresPermission(RED) abstract int red();",
                        "  // BUG: Diagnostic contains:",
                        "  public void binderAlive() { red(); }",
                        "  public void binderDied() { red(); }",
                        "}")
                .doTest();
    }

    @Test
    public void testClearCallingIdentity() {
        compilationHelper
                .addSourceFile("/android/annotation/RequiresPermission.java")
                .addSourceFile("/android/os/Binder.java")
                .addSourceLines("ColorManager.java",
                        "import android.annotation.RequiresPermission;",
                        "import android.os.Binder;",
                        "public abstract class ColorManager {",
                        "  private static final String RED = \"red\";",
                        "  private static final String BLUE = \"blue\";",
                        "  @RequiresPermission(RED) abstract int red();",
                        "  @RequiresPermission(BLUE) abstract int blue();",
                        "  @RequiresPermission(BLUE)",
                        "  public void half() {",
                        "    final long token = Binder.clearCallingIdentity();",
                        "    try {",
                        "      red();",
                        "    } finally {",
                        "      Binder.restoreCallingIdentity(token);",
                        "    }",
                        "    blue();",
                        "  }",
                        "  public void full() {",
                        "    final long token = Binder.clearCallingIdentity();",
                        "    red();",
                        "    blue();",
                        "  }",
                        "  @RequiresPermission(allOf={RED, BLUE})",
                        "  public void none() {",
                        "    red();",
                        "    blue();",
                        "    final long token = Binder.clearCallingIdentity();",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testSuppressLint() {
        compilationHelper
                .addSourceFile("/android/annotation/RequiresPermission.java")
                .addSourceFile("/android/annotation/SuppressLint.java")
                .addSourceLines("Example.java",
                        "import android.annotation.RequiresPermission;",
                        "import android.annotation.SuppressLint;",
                        "@SuppressLint(\"AndroidFrameworkRequiresPermission\")",
                        "abstract class Parent {",
                        "  private static final String RED = \"red\";",
                        "  @RequiresPermission(RED) abstract int red();",
                        "}",
                        "abstract class Child extends Parent {",
                        "  private static final String BLUE = \"blue\";",
                        "  @RequiresPermission(BLUE) abstract int blue();",
                        "  public void toParent() { red(); }",
                        "  public void toSibling() { blue(); }",
                        "}")
                .doTest();
    }

    @Test
    public void testSendBroadcast() {
        compilationHelper
                .addSourceFile("/android/annotation/RequiresPermission.java")
                .addSourceFile("/android/annotation/SdkConstant.java")
                .addSourceFile("/android/content/Context.java")
                .addSourceFile("/android/content/Intent.java")
                .addSourceLines("FooManager.java",
                        "import android.annotation.RequiresPermission;",
                        "import android.annotation.SdkConstant;",
                        "import android.content.Context;",
                        "import android.content.Intent;",
                        "public class FooManager {",
                        "  private static final String PERMISSION_RED = \"red\";",
                        "  private static final String PERMISSION_BLUE = \"blue\";",
                        "  @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)",
                        "  private static final String ACTION_NONE = \"none\";",
                        "  @RequiresPermission(PERMISSION_RED)",
                        "  @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)",
                        "  private static final String ACTION_RED = \"red\";",
                        "  @RequiresPermission(allOf={PERMISSION_RED,PERMISSION_BLUE})",
                        "  @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)",
                        "  private static final String ACTION_RED_BLUE = \"red_blue\";",
                        "  public void exampleNone(Context context) {",
                        "    Intent intent = new Intent(ACTION_NONE);",
                        "    context.sendBroadcast(intent);",
                        "    // BUG: Diagnostic contains:",
                        "    context.sendBroadcast(intent, PERMISSION_RED);",
                        "    // BUG: Diagnostic contains:",
                        "    context.sendBroadcastWithMultiplePermissions(intent,",
                        "        new String[] { PERMISSION_RED });",
                        "    // BUG: Diagnostic contains:",
                        "    context.sendBroadcastWithMultiplePermissions(intent,",
                        "        new String[] { PERMISSION_RED, PERMISSION_BLUE });",
                        "  }",
                        "  public void exampleRed(Context context) {",
                        "    Intent intent = new Intent(FooManager.ACTION_RED);",
                        "    // BUG: Diagnostic contains:",
                        "    context.sendBroadcast(intent);",
                        "    context.sendBroadcast(intent, FooManager.PERMISSION_RED);",
                        "    context.sendBroadcastWithMultiplePermissions(intent,",
                        "        new String[] { FooManager.PERMISSION_RED });",
                        "    // BUG: Diagnostic contains:",
                        "    context.sendBroadcastWithMultiplePermissions(intent,",
                        "        new String[] { FooManager.PERMISSION_RED, PERMISSION_BLUE });",
                        "  }",
                        "  public void exampleRedBlue(Context context) {",
                        "    Intent intent = new Intent(ACTION_RED_BLUE);",
                        "    // BUG: Diagnostic contains:",
                        "    context.sendBroadcast(intent);",
                        "    // BUG: Diagnostic contains:",
                        "    context.sendBroadcast(intent, PERMISSION_RED);",
                        "    // BUG: Diagnostic contains:",
                        "    context.sendBroadcastWithMultiplePermissions(intent,",
                        "        new String[] { PERMISSION_RED });",
                        "    context.sendBroadcastWithMultiplePermissions(intent,",
                        "        new String[] { PERMISSION_RED, PERMISSION_BLUE });",
                        "  }",
                        "  public void exampleUnknown(Context context, Intent intent) {",
                        "    // BUG: Diagnostic contains:",
                        "    context.sendBroadcast(intent);",
                        "    // BUG: Diagnostic contains:",
                        "    context.sendBroadcast(intent, PERMISSION_RED);",
                        "    // BUG: Diagnostic contains:",
                        "    context.sendBroadcastWithMultiplePermissions(intent,",
                        "        new String[] { PERMISSION_RED });",
                        "    // BUG: Diagnostic contains:",
                        "    context.sendBroadcastWithMultiplePermissions(intent,",
                        "        new String[] { PERMISSION_RED, PERMISSION_BLUE });",
                        "  }",
                        "  public void exampleReuse(Context context) {",
                        "    Intent intent = new Intent(ACTION_RED);",
                        "    context.sendBroadcast(intent, PERMISSION_RED);",
                        "    intent = new Intent(ACTION_NONE);",
                        "    context.sendBroadcast(intent);",
                        "    intent.setAction(ACTION_RED);",
                        "    context.sendBroadcast(intent, PERMISSION_RED);",
                        "  }",
                        "  public void exampleScoped(Context context) {",
                        "    if (true) {",
                        "      Intent intent = new Intent(ACTION_RED);",
                        "      context.sendBroadcast(intent, PERMISSION_RED);",
                        "    } else {",
                        "      Intent intent = new Intent(ACTION_NONE);",
                        "      context.sendBroadcast(intent);",
                        "    }",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testInvalidFunctions() {
        compilationHelper
                .addSourceFile("/android/annotation/RequiresPermission.java")
                .addSourceFile("/android/annotation/SuppressLint.java")
                .addSourceFile("/android/content/Context.java")
                .addSourceLines("Example.java",
                        "import android.annotation.RequiresPermission;",
                        "import android.annotation.SuppressLint;",
                        "import android.content.Context;",
                        "class Foo extends Context {",
                        "  private static final String RED = \"red\";",
                        "  public void checkPermission() {",
                        "  }",
                        "  @RequiresPermission(RED)",
                        "  // BUG: Diagnostic contains:",
                        "  public void exampleScoped(Context context) {",
                        "    checkPermission();",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testEnforce() {
        compilationHelper
                .addSourceFile("/android/annotation/EnforcePermission.java")
                .addSourceFile("/android/content/Context.java")
                .addSourceFile("/android/foo/IBarService.java")
                .addSourceFile("/android/os/IInterface.java")
                .addSourceLines("BarService.java",
                        "import android.annotation.EnforcePermission;",
                        "import android.foo.IBarService;",
                        "class BarService extends IBarService.Stub {",
                        "  @Override",
                        "  @EnforcePermission(\"INTERNET\")",
                        "  public void bar() {",
                        "    bar_enforcePermission();",
                        "  }",
                        "}")
                .addSourceLines("BarManager.java",
                        "import android.annotation.RequiresPermission;",
                        "class BarManager {",
                        "  BarService mService;",
                        "  @RequiresPermission(\"INTERNET\")",
                        "  public void callBar() {",
                        "    mService.bar();",
                        "  }",
                        "  @RequiresPermission(\"NONE\")",
                        "  public void callBarDifferent() {",
                        "    // BUG: Diagnostic contains:",
                        "    mService.bar();",
                        "  }",
                        "  public void callBarMissing() {",
                        "    // BUG: Diagnostic contains:",
                        "    mService.bar();",
                        "  }",
                        "}")
                .doTest();
    }

}
