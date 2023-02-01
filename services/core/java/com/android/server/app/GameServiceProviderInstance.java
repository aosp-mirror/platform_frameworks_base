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

/**
 * Representation of an instance of a Game Service provider.
 *
 * This includes maintaining the bindings and driving the interactions with the provider's
 * implementations of {@link android.service.games.GameService} and
 * {@link android.service.games.GameSessionService}.
 */
interface GameServiceProviderInstance {
    /**
     * Begins running the Game Service provider instance.
     */
    void start();

    /**
     * Stops running the Game Service provider instance.
     */
    void stop();
}
