/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core;

import com.android.internal.widget.remotecompose.core.operations.Theme;

/**
 * Specify an abstract context used to playback RemoteCompose documents
 *
 * This allows us to intercept the different operations in a document and react to them.
 *
 * We also contain a PaintContext, so that any operation can draw as needed.
 */
public abstract class RemoteContext {
    protected CoreDocument mDocument;
    public RemoteComposeState mRemoteComposeState;

    protected PaintContext mPaintContext = null;
    ContextMode mMode = ContextMode.UNSET;

    boolean mDebug = false;
    private int mTheme = Theme.UNSPECIFIED;

    public float mWidth = 0f;
    public float mHeight = 0f;

    public abstract void loadPathData(int instanceId, float[] floatPath);

    /**
     * The context can be used in a few different mode, allowing operations to skip being executed:
     * - UNSET : all operations will get executed
     * - DATA : only operations dealing with DATA (eg loading a bitmap) should execute
     * - PAINT : only operations painting should execute
     */
    public enum  ContextMode {
        UNSET, DATA, PAINT
    }

    public int getTheme() {
        return mTheme;
    }

    public void setTheme(int theme) {
        this.mTheme = theme;
    }

    public ContextMode getMode() {
        return mMode;
    }

    public void setMode(ContextMode mode) {
        this.mMode = mode;
    }

    public PaintContext getPaintContext() {
        return mPaintContext;
    }

    public void setPaintContext(PaintContext paintContext) {
        this.mPaintContext = paintContext;
    }

    public CoreDocument getDocument() {
        return mDocument;
    }

    public boolean isDebug() {
        return mDebug;
    }

    public void setDebug(boolean debug) {
        this.mDebug = debug;
    }

    public void setDocument(CoreDocument document) {
        this.mDocument = document;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Operations
    ///////////////////////////////////////////////////////////////////////////////////////////////

    public void header(int majorVersion, int minorVersion, int patchVersion,
                       int width, int height, long capabilities
    ) {
        mDocument.setVersion(majorVersion, minorVersion, patchVersion);
        mDocument.setWidth(width);
        mDocument.setHeight(height);
        mDocument.setRequiredCapabilities(capabilities);
    }

    /**
     * Sets the way the player handles the content
     *
     * @param scroll set the horizontal behavior (NONE|SCROLL_HORIZONTAL|SCROLL_VERTICAL)
     * @param alignment set the alignment of the content (TOP|CENTER|BOTTOM|START|END)
     * @param sizing set the type of sizing for the content (NONE|SIZING_LAYOUT|SIZING_SCALE)
     * @param mode set the mode of sizing, either LAYOUT modes or SCALE modes
     *             the LAYOUT modes are:
     *             - LAYOUT_MATCH_PARENT
     *             - LAYOUT_WRAP_CONTENT
     *             or adding an horizontal mode and a vertical mode:
     *             - LAYOUT_HORIZONTAL_MATCH_PARENT
     *             - LAYOUT_HORIZONTAL_WRAP_CONTENT
     *             - LAYOUT_HORIZONTAL_FIXED
     *             - LAYOUT_VERTICAL_MATCH_PARENT
     *             - LAYOUT_VERTICAL_WRAP_CONTENT
     *             - LAYOUT_VERTICAL_FIXED
     *             The LAYOUT_*_FIXED modes will use the intrinsic document size
     */
    public void setRootContentBehavior(int scroll, int alignment, int sizing, int mode) {
        mDocument.setRootContentBehavior(scroll, alignment, sizing, mode);
    }

    /**
     * Set a content description for the document
     * @param contentDescriptionId the text id pointing at the description
     */
    public void setDocumentContentDescription(int contentDescriptionId) {
        String contentDescription = (String) mRemoteComposeState.getFromId(contentDescriptionId);
        mDocument.setContentDescription(contentDescription);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Data handling
    ///////////////////////////////////////////////////////////////////////////////////////////////
    public abstract void loadBitmap(int imageId, int width, int height, byte[] bitmap);
    public abstract void loadText(int id, String text);

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Click handling
    ///////////////////////////////////////////////////////////////////////////////////////////////
    public abstract void addClickArea(
            int id,
            int contentDescription,
            float left,
            float top,
            float right,
            float bottom,
            int metadataId
    );
}

