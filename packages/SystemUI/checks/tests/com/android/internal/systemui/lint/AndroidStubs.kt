/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.systemui.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest.java

/*
 * This file contains stubs of framework APIs and System UI classes for testing purposes only. The
 * stubs are not used in the lint detectors themselves.
 */
@Suppress("UnstableApiUsage")
internal val androidStubs =
    arrayOf(
        java(
            """
package android.app;

public class ActivityManager {
    public static int getCurrentUser() {}
}
"""
        ),
        java(
            """
package android.os;
import android.content.pm.UserInfo;
import android.annotation.UserIdInt;

public class UserManager {
    public UserInfo getUserInfo(@UserIdInt int userId) {}
}
"""
        ),
        java("""
package android.annotation;

public @interface UserIdInt {}
"""),
        java("""
package android.content.pm;

public class UserInfo {}
"""),
        java("""
package android.os;

public class Looper {}
"""),
        java("""
package android.os;

public class Handler {}
"""),
        java("""
package android.content;

public class ServiceConnection {}
"""),
        java("""
package android.os;

public enum UserHandle {
    ALL
}
"""),
        java(
            """
package android.content;
import android.os.UserHandle;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Executor;

public class Context {
    public void registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {}
    public void registerReceiverAsUser(
            BroadcastReceiver receiver, UserHandle user, IntentFilter filter,
            String broadcastPermission, Handler scheduler) {}
    public void registerReceiverForAllUsers(
            BroadcastReceiver receiver, IntentFilter filter, String broadcastPermission,
            Handler scheduler) {}
    public void sendBroadcast(Intent intent) {}
    public void sendBroadcast(Intent intent, String receiverPermission) {}
    public void sendBroadcastAsUser(Intent intent, UserHandle userHandle, String permission) {}
    public void bindService(Intent intent) {}
    public void bindServiceAsUser(
            Intent intent, ServiceConnection connection, int flags, UserHandle userHandle) {}
    public void unbindService(ServiceConnection connection) {}
    public Looper getMainLooper() { return null; }
    public Executor getMainExecutor() { return null; }
    public Handler getMainThreadHandler() { return null; }
}
"""
        ),
        java(
            """
package android.app;
import android.content.Context;

public class Activity extends Context {}
"""
        ),
        java(
            """
package android.graphics;

public class Bitmap {
    public enum Config {
        ARGB_8888,
        RGB_565,
        HARDWARE
    }
    public static Bitmap createBitmap(int width, int height, Config config) {
        return null;
    }
}
"""
        ),
        java("""
package android.content;

public class BroadcastReceiver {}
"""),
        java("""
package android.content;

public class IntentFilter {}
"""),
        java(
            """
package com.android.systemui.settings;
import android.content.pm.UserInfo;

public interface UserTracker {
    int getUserId();
    UserInfo getUserInfo();
}
"""
        ),
    )
