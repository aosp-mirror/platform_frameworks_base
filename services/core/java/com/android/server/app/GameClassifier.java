/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.app;

import android.annotation.NonNull;
import android.os.UserHandle;

/**
 * Responsible for determining if a given application is a game.
 */
interface GameClassifier {

    /**
     * Returns {@code true} if the application associated with the given {@code packageName} is
     * considered to be a game. The application is queried as the user associated with the given
     * {@code userHandle}.
     */
    boolean isGame(@NonNull String packageName, @NonNull UserHandle userHandle);
}
