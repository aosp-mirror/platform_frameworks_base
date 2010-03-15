/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server.status;

import android.app.PendingIntent;
import android.widget.RemoteViews;

public class NotificationData {
    public String pkg;
    public String tag;
    public int id;
    public CharSequence tickerText;

    public long when;
    public boolean ongoingEvent;
    public boolean clearable;

    public RemoteViews contentView;
    public PendingIntent contentIntent;

    public PendingIntent deleteIntent;

    public String toString() {
        return "NotificationData(package=" + pkg + " id=" + id + " tickerText=" + tickerText
                + " ongoingEvent=" + ongoingEvent + " contentIntent=" + contentIntent
                + " deleteIntent=" + deleteIntent
                + " clearable=" + clearable
                + " contentView=" + contentView + " when=" + when + ")";
    }
}
