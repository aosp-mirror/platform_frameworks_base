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

package com.android.settingslib.users;

import android.content.Intent;

/**
 * An interface to start activities for result. This is used as a callback from controllers where
 * activity starting isn't possible but we want to keep the intent building logic there.
 */
public interface ActivityStarter {

    /**
     * Launch an activity for which you would like a result when it finished.
     */
    void startActivityForResult(Intent intent, int requestCode);
}
