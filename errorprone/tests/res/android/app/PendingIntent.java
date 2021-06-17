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

package android.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;

public class PendingIntent {
    public static final int FLAG_ONE_SHOT = 1<<30;
    public static final int FLAG_IMMUTABLE = 1<<26;
    public static final int FLAG_MUTABLE = 1<<25;
    public static final int FLAG_NO_CREATE = 1<<29;

    public static PendingIntent getActivity(Context context, int requestCode,
            Intent intent, int flags) {
        throw new UnsupportedOperationException();
    }

    public static PendingIntent getActivityAsUser(Context context, int requestCode,
            Intent intent, int flags, Bundle options, UserHandle user) {
        throw new UnsupportedOperationException();
    }

    public static PendingIntent getActivities(Context context, int requestCode,
            Intent[] intents, int flags, Bundle options) {
        throw new UnsupportedOperationException();
    }

    public static PendingIntent getActivitiesAsUser(Context context, int requestCode,
            Intent[] intents, int flags, Bundle options, UserHandle user) {
        throw new UnsupportedOperationException();
    }

    public static PendingIntent getBroadcast(Context context, int requestCode,
            Intent intent, int flags) {
        throw new UnsupportedOperationException();
    }

    public static PendingIntent getBroadcastAsUser(Context context, int requestCode,
            Intent intent, int flags, UserHandle userHandle) {
        throw new UnsupportedOperationException();
    }

    public static PendingIntent getService(Context context, int requestCode,
            Intent intent, int flags) {
        throw new UnsupportedOperationException();
    }

    public static PendingIntent getForegroundService(Context context, int requestCode,
            Intent intent, int flags) {
        throw new UnsupportedOperationException();
    }
}
