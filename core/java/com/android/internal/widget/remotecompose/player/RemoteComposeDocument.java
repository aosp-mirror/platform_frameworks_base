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
package com.android.internal.widget.remotecompose.player;

import com.android.internal.widget.remotecompose.core.CoreDocument;
import com.android.internal.widget.remotecompose.core.RemoteComposeBuffer;
import com.android.internal.widget.remotecompose.core.RemoteContext;

import java.io.InputStream;

/**
 * Public API to create a new RemoteComposeDocument coming from an input stream
 */
public class RemoteComposeDocument {

    CoreDocument mDocument = new CoreDocument();

    public RemoteComposeDocument(InputStream inputStream) {
        RemoteComposeBuffer buffer =
                RemoteComposeBuffer.fromInputStream(inputStream, mDocument.getRemoteComposeState());
        mDocument.initFromBuffer(buffer);
    }

    public CoreDocument getDocument() {
        return mDocument;
    }

    public void setDocument(CoreDocument document) {
        this.mDocument = document;
    }

    /**
     * Called when an initialization is needed, allowing the document to eg load
     * resources / cache them.
     */
    public void initializeContext(RemoteContext context) {
        mDocument.initializeContext(context);
    }

    /**
     * Returns the width of the document in pixels
     */
    public int getWidth() {
        return mDocument.getWidth();
    }

    /**
     * Returns the height of the document in pixels
     */
    public int getHeight() {
        return mDocument.getHeight();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Painting
    ///////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Paint the document
     *
     * @param context the provided PaintContext
     * @param theme   the theme we want to use for this document.
     */
    public void paint(RemoteContext context, int theme) {
        mDocument.paint(context, theme);
    }

    /**
     * The delay in milliseconds to next repaint -1 = not needed 0 = asap
     *
     * @return delay in milliseconds to next repaint or -1
     */
    public int needsRepaint() {
        return mDocument.needsRepaint();
    }

    /**
     * Returns true if the document can be displayed given this version of the player
     *
     * @param majorVersion the max major version supported by the player
     * @param minorVersion the max minor version supported by the player
     * @param capabilities a bitmask of capabilities the player supports (unused for now)
     */
    public boolean canBeDisplayed(int majorVersion, int minorVersion, long capabilities) {
        return mDocument.canBeDisplayed(majorVersion, minorVersion, capabilities);
    }

    @Override
    public String toString() {
        return "Document{\n"
                + mDocument + '}';
    }

    /**
     * Gets a array of Names of the named colors defined in the loaded doc.
     *
     * @return
     */
    public String[] getNamedColors() {
        return mDocument.getNamedColors();
    }

}

