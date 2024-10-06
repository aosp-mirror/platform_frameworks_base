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

import static com.android.internal.widget.remotecompose.core.documentation.Operation.INT;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteComposeOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.List;

/**
 * Set a current theme, applied to the following operations in the document.
 * This can be used to "tag" the subsequent operations to a given theme. On playback,
 * we can then filter operations depending on the chosen theme.
 *
 */
public class Theme implements RemoteComposeOperation {
    private static final int OP_CODE = Operations.THEME;
    private static final String CLASS_NAME = "Theme";
    int mTheme;
    public static final int UNSPECIFIED = -1;
    public static final int DARK = -2;
    public static final int LIGHT = -3;

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
        apply(buffer, mTheme);
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

    public static String name() {
        return CLASS_NAME;
    }


    public static int id() {
        return OP_CODE;
    }

    public static void apply(WireBuffer buffer, int theme) {
        buffer.start(OP_CODE);
        buffer.writeInt(theme);
    }


    public static void read(WireBuffer buffer, List<Operation> operations) {
        int theme = buffer.readInt();
        operations.add(new Theme(theme));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Protocol Operations", OP_CODE, CLASS_NAME)
                .description("Set a theme")
                .field(INT, "THEME", "theme id")
                .possibleValues("UNSPECIFIED", Theme.UNSPECIFIED)
                .possibleValues("DARK", Theme.DARK)
                .possibleValues("LIGHT", Theme.LIGHT);
    }
}
