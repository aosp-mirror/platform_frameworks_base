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
public class PendingIntentMutabilityCheckerTest {
    private CompilationTestHelper mCompilationHelper;

    @Before
    public void setUp() {
        mCompilationHelper = CompilationTestHelper.newInstance(
                PendingIntentMutabilityChecker.class, getClass());
    }

    @Test
    public void testGetActivity() {
        mCompilationHelper
                .addSourceFile("/android/app/PendingIntent.java")
                .addSourceFile("/android/content/Context.java")
                .addSourceFile("/android/content/Intent.java")
                .addSourceFile("/android/os/UserHandle.java")
                .addSourceFile("/android/os/Bundle.java")
                .addSourceLines("Example.java",
                        "import android.app.PendingIntent;",
                        "import android.content.Context;",
                        "import android.content.Intent;",
                        "public class Example {",
                        "  Context context;",
                        "  Intent intent;",
                        "  void example() {",
                        "    PendingIntent.getActivity(context, 42, intent, PendingIntent.FLAG_MUTABLE);",
                        "    PendingIntent.getActivity(context, 42, intent, PendingIntent.FLAG_IMMUTABLE);",
                        "    PendingIntent.getActivity(context, 42, intent, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);",
                        "    PendingIntent.getActivity(context, 42, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);",
                        "    PendingIntent.getActivity(context, 42, intent, 0 | PendingIntent.FLAG_MUTABLE);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getActivity(context, 42, intent, PendingIntent.FLAG_ONE_SHOT);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getActivity(context, 42, intent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_NO_CREATE);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getActivity(context, 42, intent, 0);",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testGetActivityAsUser() {
        mCompilationHelper
                .addSourceFile("/android/app/PendingIntent.java")
                .addSourceFile("/android/content/Context.java")
                .addSourceFile("/android/content/Intent.java")
                .addSourceFile("/android/os/UserHandle.java")
                .addSourceFile("/android/os/Bundle.java")
                .addSourceLines("Example.java",
                        "import android.app.PendingIntent;",
                        "import android.content.Context;",
                        "import android.content.Intent;",
                        "public class Example {",
                        "  Context context;",
                        "  Intent intent;",
                        "  void example() {",
                        "    PendingIntent.getActivityAsUser(context, 42, intent, PendingIntent.FLAG_MUTABLE, null, null);",
                        "    PendingIntent.getActivityAsUser(context, 42, intent, PendingIntent.FLAG_IMMUTABLE, null, null);",
                        "    PendingIntent.getActivityAsUser(context, 42, intent, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT, null, null);",
                        "    PendingIntent.getActivityAsUser(context, 42, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT, null, null);",
                        "    PendingIntent.getActivityAsUser(context, 42, intent, 0 | PendingIntent.FLAG_MUTABLE, null, null);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getActivityAsUser(context, 42, intent, PendingIntent.FLAG_ONE_SHOT, null, null);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getActivityAsUser(context, 42, intent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_NO_CREATE, null, null);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getActivityAsUser(context, 42, intent, 0, null, null);",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testGetActivities() {
        mCompilationHelper
                .addSourceFile("/android/app/PendingIntent.java")
                .addSourceFile("/android/content/Context.java")
                .addSourceFile("/android/content/Intent.java")
                .addSourceFile("/android/os/UserHandle.java")
                .addSourceFile("/android/os/Bundle.java")
                .addSourceLines("Example.java",
                        "import android.app.PendingIntent;",
                        "import android.content.Context;",
                        "import android.content.Intent;",
                        "public class Example {",
                        "  Context context;",
                        "  Intent[] intents;",
                        "  void example() {",
                        "    PendingIntent.getActivities(context, 42, intents, PendingIntent.FLAG_MUTABLE, null);",
                        "    PendingIntent.getActivities(context, 42, intents, PendingIntent.FLAG_IMMUTABLE, null);",
                        "    PendingIntent.getActivities(context, 42, intents, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT, null);",
                        "    PendingIntent.getActivities(context, 42, intents, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT, null);",
                        "    PendingIntent.getActivities(context, 42, intents, 0 | PendingIntent.FLAG_MUTABLE, null);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getActivities(context, 42, intents, PendingIntent.FLAG_ONE_SHOT, null);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getActivities(context, 42, intents, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_NO_CREATE, null);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getActivities(context, 42, intents, 0, null);",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testGetActivitiesAsUser() {
        mCompilationHelper
                .addSourceFile("/android/app/PendingIntent.java")
                .addSourceFile("/android/content/Context.java")
                .addSourceFile("/android/content/Intent.java")
                .addSourceFile("/android/os/UserHandle.java")
                .addSourceFile("/android/os/Bundle.java")
                .addSourceLines("Example.java",
                        "import android.app.PendingIntent;",
                        "import android.content.Context;",
                        "import android.content.Intent;",
                        "public class Example {",
                        "  Context context;",
                        "  Intent[] intents;",
                        "  void example() {",
                        "    PendingIntent.getActivitiesAsUser(context, 42, intents, PendingIntent.FLAG_MUTABLE, null, null);",
                        "    PendingIntent.getActivitiesAsUser(context, 42, intents, PendingIntent.FLAG_IMMUTABLE, null, null);",
                        "    PendingIntent.getActivitiesAsUser(context, 42, intents, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT, null, null);",
                        "    PendingIntent.getActivitiesAsUser(context, 42, intents, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT, null, null);",
                        "    PendingIntent.getActivitiesAsUser(context, 42, intents, 0 | PendingIntent.FLAG_MUTABLE, null, null);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getActivitiesAsUser(context, 42, intents, PendingIntent.FLAG_ONE_SHOT, null, null);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getActivitiesAsUser(context, 42, intents, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_NO_CREATE, null, null);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getActivitiesAsUser(context, 42, intents, 0, null, null);",
                        "  }",
                        "}")
                .doTest();
    }


    @Test
    public void testGetBroadcast() {
        mCompilationHelper
                .addSourceFile("/android/app/PendingIntent.java")
                .addSourceFile("/android/content/Context.java")
                .addSourceFile("/android/content/Intent.java")
                .addSourceFile("/android/os/UserHandle.java")
                .addSourceFile("/android/os/Bundle.java")
                .addSourceLines("Example.java",
                        "import android.app.PendingIntent;",
                        "import android.content.Context;",
                        "import android.content.Intent;",
                        "public class Example {",
                        "  Context context;",
                        "  Intent intent;",
                        "  void example() {",
                        "    PendingIntent.getBroadcast(context, 42, intent, PendingIntent.FLAG_MUTABLE);",
                        "    PendingIntent.getBroadcast(context, 42, intent, PendingIntent.FLAG_IMMUTABLE);",
                        "    PendingIntent.getBroadcast(context, 42, intent, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);",
                        "    PendingIntent.getBroadcast(context, 42, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);",
                        "    PendingIntent.getBroadcast(context, 42, intent, 0 | PendingIntent.FLAG_MUTABLE);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getBroadcast(context, 42, intent, PendingIntent.FLAG_ONE_SHOT);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getBroadcast(context, 42, intent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_NO_CREATE);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getBroadcast(context, 42, intent, 0);",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testGetBroadcastAsUser() {
        mCompilationHelper
                .addSourceFile("/android/app/PendingIntent.java")
                .addSourceFile("/android/content/Context.java")
                .addSourceFile("/android/content/Intent.java")
                .addSourceFile("/android/os/UserHandle.java")
                .addSourceFile("/android/os/Bundle.java")
                .addSourceLines("Example.java",
                        "import android.app.PendingIntent;",
                        "import android.content.Context;",
                        "import android.content.Intent;",
                        "public class Example {",
                        "  Context context;",
                        "  Intent intent;",
                        "  void example() {",
                        "    PendingIntent.getBroadcastAsUser(context, 42, intent, PendingIntent.FLAG_MUTABLE, null);",
                        "    PendingIntent.getBroadcastAsUser(context, 42, intent, PendingIntent.FLAG_IMMUTABLE, null);",
                        "    PendingIntent.getBroadcastAsUser(context, 42, intent, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT, null);",
                        "    PendingIntent.getBroadcastAsUser(context, 42, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT, null);",
                        "    PendingIntent.getBroadcastAsUser(context, 42, intent, 0 | PendingIntent.FLAG_MUTABLE, null);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getBroadcastAsUser(context, 42, intent, PendingIntent.FLAG_ONE_SHOT, null);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getBroadcastAsUser(context, 42, intent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_NO_CREATE, null);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getBroadcastAsUser(context, 42, intent, 0, null);",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testGetService() {
        mCompilationHelper
                .addSourceFile("/android/app/PendingIntent.java")
                .addSourceFile("/android/content/Context.java")
                .addSourceFile("/android/content/Intent.java")
                .addSourceFile("/android/os/UserHandle.java")
                .addSourceFile("/android/os/Bundle.java")
                .addSourceLines("Example.java",
                        "import android.app.PendingIntent;",
                        "import android.content.Context;",
                        "import android.content.Intent;",
                        "public class Example {",
                        "  Context context;",
                        "  Intent intent;",
                        "  void example() {",
                        "    PendingIntent.getService(context, 42, intent, PendingIntent.FLAG_MUTABLE);",
                        "    PendingIntent.getService(context, 42, intent, PendingIntent.FLAG_IMMUTABLE);",
                        "    PendingIntent.getService(context, 42, intent, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);",
                        "    PendingIntent.getService(context, 42, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);",
                        "    PendingIntent.getService(context, 42, intent, 0 | PendingIntent.FLAG_MUTABLE);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getService(context, 42, intent, PendingIntent.FLAG_ONE_SHOT);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getService(context, 42, intent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_NO_CREATE);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getService(context, 42, intent, 0);",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testGetForegroundService() {
        mCompilationHelper
                .addSourceFile("/android/app/PendingIntent.java")
                .addSourceFile("/android/content/Context.java")
                .addSourceFile("/android/content/Intent.java")
                .addSourceFile("/android/os/UserHandle.java")
                .addSourceFile("/android/os/Bundle.java")
                .addSourceLines("Example.java",
                        "import android.app.PendingIntent;",
                        "import android.content.Context;",
                        "import android.content.Intent;",
                        "public class Example {",
                        "  Context context;",
                        "  Intent intent;",
                        "  void example() {",
                        "    PendingIntent.getForegroundService(context, 42, intent, PendingIntent.FLAG_MUTABLE);",
                        "    PendingIntent.getForegroundService(context, 42, intent, PendingIntent.FLAG_IMMUTABLE);",
                        "    PendingIntent.getForegroundService(context, 42, intent, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);",
                        "    PendingIntent.getForegroundService(context, 42, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);",
                        "    PendingIntent.getForegroundService(context, 42, intent, 0 | PendingIntent.FLAG_MUTABLE);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getForegroundService(context, 42, intent, PendingIntent.FLAG_ONE_SHOT);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getForegroundService(context, 42, intent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_NO_CREATE);",
                        "    // BUG: Diagnostic contains:",
                        "    PendingIntent.getForegroundService(context, 42, intent, 0);",
                        "  }",
                        "}")
                .doTest();
    }
}
