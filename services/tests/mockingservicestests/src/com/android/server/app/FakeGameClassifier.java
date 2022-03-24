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

import java.util.HashSet;

/**
 * Fake implementation of {@link GameClassifier} used for tests.
 *
 * By default, all packages are considers not games. A package may be marked as a game using
 * {@link #recordGamePackage(String)}.
 */
final class FakeGameClassifier implements GameClassifier {
    private final HashSet<String> mGamePackages = new HashSet<>();

    /**
     * Marks the given {@code packageName} as a game.
     */
    public void recordGamePackage(String packageName) {
        mGamePackages.add(packageName);
    }

    @Override
    public boolean isGame(@NonNull String packageName, UserHandle userHandle) {
        return mGamePackages.contains(packageName);
    }
}
