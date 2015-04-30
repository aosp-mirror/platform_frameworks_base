/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.notification;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.service.notification.ConditionProviderService;
import android.service.notification.IConditionProvider;

import com.android.server.notification.NotificationManagerService.DumpFilter;

import java.io.PrintWriter;

public abstract class SystemConditionProviderService extends ConditionProviderService {

    abstract public void dump(PrintWriter pw, DumpFilter filter);
    abstract public void attachBase(Context context);
    abstract public IConditionProvider asInterface();
    abstract public ComponentName getComponent();
    abstract public boolean isValidConditionId(Uri id);
}
