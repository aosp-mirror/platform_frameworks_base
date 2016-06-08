/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui;

import android.support.annotation.Nullable;

import com.android.documentsui.dirlist.MultiSelectManager.Selection;

/**
 * Object used to collect retained state from activity and fragments. Used
 * with Activity#onRetainNonConfigurationInstance. Information stored in
 * this class should be primarily ephemeral as instances of the class
 * only last across configuration changes (like device rotation). When
 * an application is fully town down, all instances are lost, fa-evah!
 */
public final class RetainedState {
    public @Nullable Selection selection;

    public boolean hasSelection() {
        return selection != null;
    }
}
