/*
 * Copyright 2015, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.service.quicksettings;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;

/**
 * @hide
 */
interface IQSService {
    Tile getTile(in IBinder tile);
    void updateQsTile(in Tile tile, in IBinder service);
    void updateStatusIcon(in IBinder tile, in Icon icon,
            String contentDescription);
    void onShowDialog(in IBinder tile);
    void onStartActivity(in IBinder tile);
    void startActivity(in IBinder tile, in PendingIntent pendingIntent);
    boolean isLocked();
    boolean isSecure();
    void startUnlockAndRun(in IBinder tile);
    void onDialogHidden(in IBinder tile);
    void onStartSuccessful(in IBinder tile);
}
