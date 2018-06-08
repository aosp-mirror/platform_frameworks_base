/*
 * Copyright 2018 The Android Open Source Project
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

package android.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.media.update.ApiLoader;
import android.media.update.MediaSession2Provider;
import android.os.Bundle;

import java.util.Set;

/**
 * @hide
 * Represent set of {@link SessionCommand2}.
 */
public final class SessionCommandGroup2 {
    // TODO(jaewan): Rename and move provider
    private final MediaSession2Provider.CommandGroupProvider mProvider;

    public SessionCommandGroup2() {
        mProvider = ApiLoader.getProvider().createMediaSession2CommandGroup(this, null);
    }

    public SessionCommandGroup2(@Nullable SessionCommandGroup2 others) {
        mProvider = ApiLoader.getProvider().createMediaSession2CommandGroup(this, others);
    }

    /**
     * @hide
     */
    public SessionCommandGroup2(@NonNull MediaSession2Provider.CommandGroupProvider provider) {
        mProvider = provider;
    }

    public void addCommand(@NonNull SessionCommand2 command) {
        mProvider.addCommand_impl(command);
    }

    public void addCommand(int commandCode) {
        // TODO(jaewna): Implement
    }

    public void addAllPredefinedCommands() {
        mProvider.addAllPredefinedCommands_impl();
    }

    public void removeCommand(@NonNull SessionCommand2 command) {
        mProvider.removeCommand_impl(command);
    }

    public void removeCommand(int commandCode) {
        // TODO(jaewan): Implement.
    }

    public boolean hasCommand(@NonNull SessionCommand2 command) {
        return mProvider.hasCommand_impl(command);
    }

    public boolean hasCommand(int code) {
        return mProvider.hasCommand_impl(code);
    }

    public @NonNull
    Set<SessionCommand2> getCommands() {
        return mProvider.getCommands_impl();
    }

    /**
     * @hide
     */
    public @NonNull MediaSession2Provider.CommandGroupProvider getProvider() {
        return mProvider;
    }

    /**
     * @return new bundle from the CommandGroup
     * @hide
     */
    public @NonNull Bundle toBundle() {
        return mProvider.toBundle_impl();
    }

    /**
     * @return new instance of CommandGroup from the bundle
     * @hide
     */
    public static @Nullable SessionCommandGroup2 fromBundle(Bundle commands) {
        return ApiLoader.getProvider().fromBundle_MediaSession2CommandGroup(commands);
    }
}
