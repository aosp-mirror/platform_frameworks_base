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

import com.android.annotations.NonNull
import com.android.tools.lint.checks.infrastructure.LintDetectorTest.java
import org.intellij.lang.annotations.Language

@Suppress("UnstableApiUsage")
@NonNull
private fun indentedJava(@NonNull @Language("JAVA") source: String) = java(source).indented()

internal val commonSettingsCode =
    """
public static float getFloat(ContentResolver cr, String name) { return 0.0f; }
public static long getLong(ContentResolver cr, String name) {
    return 0L;
}
public static int getInt(ContentResolver cr, String name) {
    return 0;
}
public static String getString(ContentResolver cr, String name) {
    return "";
}
public static float getFloat(ContentResolver cr, String name, float def) {
    return 0.0f;
}
public static long getLong(ContentResolver cr, String name, long def) {
    return 0L;
}
public static int getInt(ContentResolver cr, String name, int def) {
    return 0;
}
public static String getString(ContentResolver cr, String name, String def) {
    return "";
}
public static boolean putFloat(ContentResolver cr, String name, float value) {
    return true;
}
public static boolean putLong(ContentResolver cr, String name, long value) {
    return true;
}
public static boolean putInt(ContentResolver cr, String name, int value) {
    return true;
}
public static boolean putFloat(ContentResolver cr, String name) {
    return true;
}
public static boolean putString(ContentResolver cr, String name, String value) {
    return true;
}
"""

/*
 * This file contains stubs of framework APIs and System UI classes for testing purposes only. The
 * stubs are not used in the lint detectors themselves.
 */
internal val androidStubs =
    arrayOf(
        indentedJava(
            """
package android.app;

public class ActivityManager {
    public static int getCurrentUser() {}
}
"""
        ),
        indentedJava(
            """
package android.accounts;

public class AccountManager {
    public static AccountManager get(Context context) { return null; }
}
"""
        ),
        indentedJava(
            """
package android.os;
import android.content.pm.UserInfo;
import android.annotation.UserIdInt;

public class UserManager {
    public UserInfo getUserInfo(@UserIdInt int userId) {}
}
"""
        ),
        indentedJava("""
package android.annotation;

public @interface UserIdInt {}
"""),
        indentedJava("""
package android.content.pm;

public class UserInfo {}
"""),
        indentedJava("""
package android.os;

public class Looper {}
"""),
        indentedJava("""
package android.os;

public class Handler {}
"""),
        indentedJava("""
package android.content;

public class ServiceConnection {}
"""),
        indentedJava("""
package android.os;

public enum UserHandle {
    ALL
}
"""),
        indentedJava(
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
    public final @Nullable <T> T getSystemService(@NonNull Class<T> serviceClass) { return null; }
    public abstract @Nullable Object getSystemService(@ServiceName @NonNull String name);
}
"""
        ),
        indentedJava(
            """
package android.app;
import android.content.Context;

public class Activity extends Context {}
"""
        ),
        indentedJava(
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
        indentedJava("""
package android.content;

public class BroadcastReceiver {}
"""),
        indentedJava("""
package android.content;

public class IntentFilter {}
"""),
        indentedJava(
            """
package com.android.systemui.settings;
import android.content.pm.UserInfo;

public interface UserTracker {
    int getUserId();
    UserInfo getUserInfo();
}
"""
        ),
        indentedJava(
            """
package androidx.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

@Retention(SOURCE)
@Target({METHOD,CONSTRUCTOR,TYPE,PARAMETER})
public @interface WorkerThread {
}
"""
        ),
        indentedJava(
            """
package android.provider;

public class Settings {
    public static final class Global {
        public static final String UNLOCK_SOUND = "unlock_sound";
        """ +
                commonSettingsCode +
                """
    }
    public static final class Secure {
    """ +
                commonSettingsCode +
                """
    }
    public static final class System {
    """ +
                commonSettingsCode +
                """
    }
}
"""
        ),
    )
