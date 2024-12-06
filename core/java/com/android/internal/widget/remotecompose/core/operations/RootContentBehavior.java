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

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteComposeOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;

import java.util.List;

/**
 * Describe some basic information for a RemoteCompose document
 *
 * <p>It encodes the version of the document (following semantic versioning) as well as the
 * dimensions of the document in pixels.
 */
public class RootContentBehavior extends Operation implements RemoteComposeOperation {
    private static final int OP_CODE = Operations.ROOT_CONTENT_BEHAVIOR;
    private static final String CLASS_NAME = "RootContentBehavior";
    int mScroll = NONE;
    int mSizing = NONE;

    int mAlignment = ALIGNMENT_CENTER;

    int mMode = NONE;

    protected static final String TAG = "RootContentBehavior";

    public static final int NONE = 0;

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Scrolling
    ///////////////////////////////////////////////////////////////////////////////////////////////
    public static final int SCROLL_HORIZONTAL = 1;
    public static final int SCROLL_VERTICAL = 2;

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Sizing
    ///////////////////////////////////////////////////////////////////////////////////////////////
    public static final int SIZING_LAYOUT = 1;
    public static final int SIZING_SCALE = 2;

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Sizing
    ///////////////////////////////////////////////////////////////////////////////////////////////
    public static final int ALIGNMENT_TOP = 1;
    public static final int ALIGNMENT_VERTICAL_CENTER = 2;
    public static final int ALIGNMENT_BOTTOM = 4;
    public static final int ALIGNMENT_START = 16;
    public static final int ALIGNMENT_HORIZONTAL_CENTER = 32;
    public static final int ALIGNMENT_END = 64;
    public static final int ALIGNMENT_CENTER =
            ALIGNMENT_HORIZONTAL_CENTER + ALIGNMENT_VERTICAL_CENTER;

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Layout mode
    ///////////////////////////////////////////////////////////////////////////////////////////////
    public static final int LAYOUT_HORIZONTAL_MATCH_PARENT = 1;
    public static final int LAYOUT_HORIZONTAL_WRAP_CONTENT = 2;
    public static final int LAYOUT_HORIZONTAL_FIXED = 4;
    public static final int LAYOUT_VERTICAL_MATCH_PARENT = 8;
    public static final int LAYOUT_VERTICAL_WRAP_CONTENT = 16;
    public static final int LAYOUT_VERTICAL_FIXED = 32;
    public static final int LAYOUT_MATCH_PARENT =
            LAYOUT_HORIZONTAL_MATCH_PARENT + LAYOUT_VERTICAL_MATCH_PARENT;
    public static final int LAYOUT_WRAP_CONTENT =
            LAYOUT_HORIZONTAL_WRAP_CONTENT + LAYOUT_VERTICAL_WRAP_CONTENT;

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Sizing mode
    ///////////////////////////////////////////////////////////////////////////////////////////////

    public static final int SCALE_INSIDE = 1;
    public static final int SCALE_FILL_WIDTH = 2;
    public static final int SCALE_FILL_HEIGHT = 3;
    public static final int SCALE_FIT = 4;
    public static final int SCALE_CROP = 5;
    public static final int SCALE_FILL_BOUNDS = 6;

    /**
     * Sets the way the player handles the content
     *
     * @param scroll set the horizontal behavior (NONE|SCROLL_HORIZONTAL|SCROLL_VERTICAL)
     * @param alignment set the alignment of the content (TOP|CENTER|BOTTOM|START|END)
     * @param sizing set the type of sizing for the content (NONE|SIZING_LAYOUT|SIZING_SCALE)
     * @param mode set the mode of sizing, either LAYOUT modes or SCALE modes the LAYOUT modes are:
     *     - LAYOUT_MATCH_PARENT - LAYOUT_WRAP_CONTENT or adding an horizontal mode and a vertical
     *     mode: - LAYOUT_HORIZONTAL_MATCH_PARENT - LAYOUT_HORIZONTAL_WRAP_CONTENT -
     *     LAYOUT_HORIZONTAL_FIXED - LAYOUT_VERTICAL_MATCH_PARENT - LAYOUT_VERTICAL_WRAP_CONTENT -
     *     LAYOUT_VERTICAL_FIXED The LAYOUT_*_FIXED modes will use the intrinsic document size
     */
    public RootContentBehavior(int scroll, int alignment, int sizing, int mode) {
        switch (scroll) {
            case NONE:
            case SCROLL_HORIZONTAL:
            case SCROLL_VERTICAL:
                mScroll = scroll;
                break;
            default:
                System.out.println(TAG + "incorrect scroll value " + scroll);
        }
        if (alignment == ALIGNMENT_CENTER) {
            mAlignment = alignment;
        } else {
            int horizontalContentAlignment = alignment & 0xF0;
            int verticalContentAlignment = alignment & 0xF;
            boolean validHorizontalAlignment =
                    horizontalContentAlignment == ALIGNMENT_START
                            || horizontalContentAlignment == ALIGNMENT_HORIZONTAL_CENTER
                            || horizontalContentAlignment == ALIGNMENT_END;
            boolean validVerticalAlignment =
                    verticalContentAlignment == ALIGNMENT_TOP
                            || verticalContentAlignment == ALIGNMENT_VERTICAL_CENTER
                            || verticalContentAlignment == ALIGNMENT_BOTTOM;
            if (validHorizontalAlignment && validVerticalAlignment) {
                mAlignment = alignment;
            } else {
                System.out.println(
                        TAG
                                + "incorrect alignment "
                                + " h: "
                                + horizontalContentAlignment
                                + " v: "
                                + verticalContentAlignment);
            }
        }
        switch (sizing) {
            case SIZING_LAYOUT:
                System.out.println(TAG + "sizing_layout is not yet supported");
                break;
            case SIZING_SCALE:
                mSizing = sizing;
                break;
            default:
                System.out.println(TAG + "incorrect sizing value " + sizing);
        }
        if (mSizing == SIZING_LAYOUT) {
            if (mode != NONE) {
                System.out.println(TAG + "mode for sizing layout is not yet supported");
            }
        } else if (mSizing == SIZING_SCALE) {
            switch (mode) {
                case SCALE_INSIDE:
                case SCALE_FIT:
                case SCALE_FILL_WIDTH:
                case SCALE_FILL_HEIGHT:
                case SCALE_CROP:
                case SCALE_FILL_BOUNDS:
                    mMode = mode;
                    break;
                default:
                    System.out.println(TAG + "incorrect mode for scale sizing, mode: " + mode);
            }
        }
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mScroll, mAlignment, mSizing, mMode);
    }

    @NonNull
    @Override
    public String toString() {
        return "ROOT_CONTENT_BEHAVIOR scroll: "
                + mScroll
                + " sizing: "
                + mSizing
                + " mode: "
                + mMode;
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        context.setRootContentBehavior(mScroll, mAlignment, mSizing, mMode);
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return toString();
    }

    /**
     * The name of the class
     *
     * @return the name
     */
    @NonNull
    public static String name() {
        return CLASS_NAME;
    }

    /**
     * The OP_CODE for this command
     *
     * @return the opcode
     */
    public static int id() {
        return OP_CODE;
    }

    public static void apply(
            @NonNull WireBuffer buffer, int scroll, int alignment, int sizing, int mode) {
        buffer.start(OP_CODE);
        buffer.writeInt(scroll);
        buffer.writeInt(alignment);
        buffer.writeInt(sizing);
        buffer.writeInt(mode);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int scroll = buffer.readInt();
        int alignment = buffer.readInt();
        int sizing = buffer.readInt();
        int mode = buffer.readInt();
        RootContentBehavior rootContentBehavior =
                new RootContentBehavior(scroll, alignment, sizing, mode);
        operations.add(rootContentBehavior);
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Protocol Operations", OP_CODE, CLASS_NAME)
                .description("Describes the behaviour of the root")
                .field(DocumentedOperation.INT, "scroll", "scroll")
                .possibleValues("SCROLL_HORIZONTAL", SCROLL_HORIZONTAL)
                .possibleValues("SCROLL_VERTICAL", SCROLL_VERTICAL)
                .field(DocumentedOperation.INT, "alignment", "alignment")
                .possibleValues("ALIGNMENT_TOP", ALIGNMENT_TOP)
                .possibleValues("ALIGNMENT_VERTICAL_CENTER", ALIGNMENT_VERTICAL_CENTER)
                .possibleValues("ALIGNMENT_BOTTOM", ALIGNMENT_BOTTOM)
                .possibleValues("ALIGNMENT_START", ALIGNMENT_START)
                .possibleValues("ALIGNMENT_START", ALIGNMENT_START)
                .possibleValues("ALIGNMENT_END", ALIGNMENT_END)
                .field(DocumentedOperation.INT, "sizing", "sizing")
                .possibleValues("SCALE_INSIDE", SCALE_INSIDE)
                .possibleValues("SCALE_FIT", SCALE_FIT)
                .possibleValues("SCALE_FILL_WIDTH", SCALE_FILL_WIDTH)
                .possibleValues("SCALE_FILL_HEIGHT", SCALE_FILL_HEIGHT)
                .possibleValues("SCALE_CROP", SCALE_CROP)
                .possibleValues("SCALE_FILL_BOUNDS", SCALE_FILL_BOUNDS)
                .field(DocumentedOperation.INT, "mode", "mode")
                .possibleValues("LAYOUT_HORIZONTAL_MATCH_PARENT", LAYOUT_HORIZONTAL_MATCH_PARENT)
                .possibleValues("LAYOUT_HORIZONTAL_WRAP_CONTENT", LAYOUT_HORIZONTAL_WRAP_CONTENT)
                .possibleValues("LAYOUT_HORIZONTAL_FIXED", LAYOUT_HORIZONTAL_FIXED)
                .possibleValues("LAYOUT_VERTICAL_MATCH_PARENT", LAYOUT_VERTICAL_MATCH_PARENT)
                .possibleValues("LAYOUT_VERTICAL_WRAP_CONTENT", LAYOUT_VERTICAL_WRAP_CONTENT)
                .possibleValues("LAYOUT_VERTICAL_FIXED", LAYOUT_VERTICAL_FIXED)
                .possibleValues("LAYOUT_MATCH_PARENT", LAYOUT_MATCH_PARENT)
                .possibleValues("LAYOUT_WRAP_CONTENT", LAYOUT_WRAP_CONTENT);
    }
}
