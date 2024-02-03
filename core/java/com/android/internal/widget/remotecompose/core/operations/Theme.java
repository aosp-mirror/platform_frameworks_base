/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations;

import com.android.internal.widget.remotecompose.core.CompanionOperation;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteComposeOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;

import java.util.List;

/**
 * Set a current theme, applied to the following operations in the document.
 * This can be used to "tag" the subsequent operations to a given theme. On playback,
 * we can then filter operations depending on the chosen theme.
 *
 */
public class Theme implements RemoteComposeOperation {
    int mTheme;
    public static final int UNSPECIFIED = -1;
    public static final int DARK = -2;
    public static final int LIGHT = -3;

    public static final Companion COMPANION = new Companion();

    /**
     * we can then filter operations depending on the chosen theme.
     *
     * @param theme the theme we are interested in:
     *              - Theme.UNSPECIFIED
     *              - Theme.DARK
     *              - Theme.LIGHT
     */
    public Theme(int theme) {
        this.mTheme = theme;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mTheme);
    }

    @Override
    public String toString() {
        return "SET_THEME " + mTheme;
    }

    @Override
    public void apply(RemoteContext context) {
        context.setTheme(mTheme);
    }

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }

    public static class Companion implements CompanionOperation {
        private Companion() {}

        @Override
        public String name() {
            return "SetTheme";
        }

        @Override
        public int id() {
            return Operations.THEME;
        }

        public void apply(WireBuffer buffer, int theme) {
            buffer.start(Operations.THEME);
            buffer.writeInt(theme);
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int theme = buffer.readInt();
            operations.add(new Theme(theme));
        }
    }
}
