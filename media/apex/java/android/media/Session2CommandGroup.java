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

import static android.media.Session2Command.COMMAND_CODE_CUSTOM;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * A set of {@link Session2Command} which represents a command group.
 * <p>
 * This API is not generally intended for third party application developers.
 * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
 * <a href="{@docRoot}reference/androidx/media2/package-summary.html">Media2 Library</a>
 * for consistent behavior across all devices.
 * </p>
 */
public final class Session2CommandGroup implements Parcelable {
    private static final String TAG = "Session2CommandGroup";

    public static final Parcelable.Creator<Session2CommandGroup> CREATOR =
            new Parcelable.Creator<Session2CommandGroup>() {
                @Override
                public Session2CommandGroup createFromParcel(Parcel in) {
                    return new Session2CommandGroup(in);
                }

                @Override
                public Session2CommandGroup[] newArray(int size) {
                    return new Session2CommandGroup[size];
                }
            };

    Set<Session2Command> mCommands = new HashSet<>();

    /**
     * Creates a new Session2CommandGroup with commands copied from another object.
     *
     * @param commands The collection of commands to copy.
     */
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    Session2CommandGroup(@Nullable Collection<Session2Command> commands) {
        if (commands != null) {
            mCommands.addAll(commands);
        }
    }

    /**
     * Used by parcelable creator.
     */
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    Session2CommandGroup(Parcel in) {
        Parcelable[] commands = in.readParcelableArray(Session2Command.class.getClassLoader());
        if (commands != null) {
            for (Parcelable command : commands) {
                mCommands.add((Session2Command) command);
            }
        }
    }

    /**
     * Checks whether this command group has a command that matches given {@code command}.
     *
     * @param command A command to find. Shouldn't be {@code null}.
     */
    public boolean hasCommand(@NonNull Session2Command command) {
        if (command == null) {
            throw new IllegalArgumentException("command shouldn't be null");
        }
        return mCommands.contains(command);
    }

    /**
     * Checks whether this command group has a command that matches given {@code commandCode}.
     *
     * @param commandCode A command code to find.
     *                    Shouldn't be {@link Session2Command#COMMAND_CODE_CUSTOM}.
     */
    public boolean hasCommand(int commandCode) {
        if (commandCode == COMMAND_CODE_CUSTOM) {
            throw new IllegalArgumentException("Use hasCommand(Command) for custom command");
        }
        for (Session2Command command : mCommands) {
            if (command.getCommandCode() == commandCode) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets all commands of this command group.
     */
    @NonNull
    public Set<Session2Command> getCommands() {
        return new HashSet<>(mCommands);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        if (dest == null) {
            throw new IllegalArgumentException("parcel shouldn't be null");
        }
        dest.writeParcelableArray(mCommands.toArray(new Session2Command[0]), 0);
    }

    /**
     * Builds a {@link Session2CommandGroup} object.
     */
    public static final class Builder {
        private Set<Session2Command> mCommands;

        public Builder() {
            mCommands = new HashSet<>();
        }

        /**
         * Creates a new builder for {@link Session2CommandGroup} with commands copied from another
         * {@link Session2CommandGroup} object.
         * @param commandGroup
         */
        public Builder(@NonNull Session2CommandGroup commandGroup) {
            if (commandGroup == null) {
                throw new IllegalArgumentException("command group shouldn't be null");
            }
            mCommands = commandGroup.getCommands();
        }

        /**
         * Adds a command to this command group.
         *
         * @param command A command to add. Shouldn't be {@code null}.
         */
        @NonNull
        public Builder addCommand(@NonNull Session2Command command) {
            if (command == null) {
                throw new IllegalArgumentException("command shouldn't be null");
            }
            mCommands.add(command);
            return this;
        }

        /**
         * Adds a predefined command with given {@code commandCode} to this command group.
         *
         * @param commandCode A command code to add.
         *                    Shouldn't be {@link Session2Command#COMMAND_CODE_CUSTOM}.
         */
        @NonNull
        public Builder addCommand(int commandCode) {
            if (commandCode == COMMAND_CODE_CUSTOM) {
                throw new IllegalArgumentException(
                        "Use addCommand(Session2Command) for COMMAND_CODE_CUSTOM.");
            }
            mCommands.add(new Session2Command(commandCode));
            return this;
        }

        /**
         * Removes a command from this group which matches given {@code command}.
         *
         * @param command A command to find. Shouldn't be {@code null}.
         */
        @NonNull
        public Builder removeCommand(@NonNull Session2Command command) {
            if (command == null) {
                throw new IllegalArgumentException("command shouldn't be null");
            }
            mCommands.remove(command);
            return this;
        }

        /**
         * Removes a command from this group which matches given {@code commandCode}.
         *
         * @param commandCode A command code to find.
         *                    Shouldn't be {@link Session2Command#COMMAND_CODE_CUSTOM}.
         */
        @NonNull
        public Builder removeCommand(int commandCode) {
            if (commandCode == COMMAND_CODE_CUSTOM) {
                throw new IllegalArgumentException("commandCode shouldn't be COMMAND_CODE_CUSTOM");
            }
            mCommands.remove(new Session2Command(commandCode));
            return this;
        }

        /**
         * Builds {@link Session2CommandGroup}.
         *
         * @return a new {@link Session2CommandGroup}.
         */
        @NonNull
        public Session2CommandGroup build() {
            return new Session2CommandGroup(mCommands);
        }
    }
}
