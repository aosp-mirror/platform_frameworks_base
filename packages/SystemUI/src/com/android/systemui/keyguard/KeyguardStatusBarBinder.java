/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.keyguard;

import com.android.systemui.statusbar.CommandQueue;

import android.os.Binder;
import android.os.IBinder;

/**
 * Communication interface from status bar to {@link com.android.systemui.keyguard.KeyguardService}.
 */
public abstract class KeyguardStatusBarBinder extends Binder {

    public abstract void register(CommandQueue commandQueue);

    public abstract void dismissKeyguard();
}
