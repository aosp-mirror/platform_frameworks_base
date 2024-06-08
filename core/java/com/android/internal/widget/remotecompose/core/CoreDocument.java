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

import com.android.internal.widget.remotecompose.core.operations.RootContentBehavior;
import com.android.internal.widget.remotecompose.core.operations.Theme;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Represents a platform independent RemoteCompose document,
 * containing RemoteCompose operations + state
 */
public class CoreDocument {

    ArrayList<Operation> mOperations;
    RemoteComposeState mRemoteComposeState = new RemoteComposeState();

    // Semantic version of the document
    Version mVersion = new Version(0, 1, 0);

    String mContentDescription; // text description of the document (used for accessibility)

    long mRequiredCapabilities = 0L; // bitmask indicating needed capabilities of the player(unused)
    int mWidth = 0; // horizontal dimension of the document in pixels
    int mHeight = 0; // vertical dimension of the document in pixels

    int mContentScroll = RootContentBehavior.NONE;
    int mContentSizing = RootContentBehavior.NONE;
    int mContentMode = RootContentBehavior.NONE;

    int mContentAlignment = RootContentBehavior.ALIGNMENT_CENTER;

    RemoteComposeBuffer mBuffer = new RemoteComposeBuffer(mRemoteComposeState);

    public String getContentDescription() {
        return mContentDescription;
    }

    public void setContentDescription(String contentDescription) {
        this.mContentDescription = contentDescription;
    }

    public long getRequiredCapabilities() {
        return mRequiredCapabilities;
    }

    public void setRequiredCapabilities(long requiredCapabilities) {
        this.mRequiredCapabilities = requiredCapabilities;
    }

    public int getWidth() {
        return mWidth;
    }

    public void setWidth(int width) {
        this.mWidth = width;
    }

    public int getHeight() {
        return mHeight;
    }

    public void setHeight(int height) {
        this.mHeight = height;
    }

    public RemoteComposeBuffer getBuffer() {
        return mBuffer;
    }

    public void setBuffer(RemoteComposeBuffer buffer) {
        this.mBuffer = buffer;
    }

    public RemoteComposeState getRemoteComposeState() {
        return mRemoteComposeState;
    }

    public void setRemoteComposeState(RemoteComposeState remoteComposeState) {
        this.mRemoteComposeState = remoteComposeState;
    }

    public int getContentScroll() {
        return mContentScroll;
    }

    public int getContentSizing() {
        return mContentSizing;
    }

    public int getContentMode() {
        return mContentMode;
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
        this.mContentScroll = scroll;
        this.mContentAlignment = alignment;
        this.mContentSizing = sizing;
        this.mContentMode = mode;
    }

    /**
     * Given dimensions w x h of where to paint the content, returns the corresponding scale factor
     * according to the contentSizing information
     *
     * @param w horizontal dimension of the rendering area
     * @param h vertical dimension of the rendering area
     * @param scaleOutput will contain the computed scale factor
     */
    public void computeScale(float w, float h, float[] scaleOutput) {
        float contentScaleX = 1f;
        float contentScaleY = 1f;
        if (mContentSizing == RootContentBehavior.SIZING_SCALE) {
            // we need to add canvas transforms ops here
            switch (mContentMode) {
                case RootContentBehavior.SCALE_INSIDE: {
                    float scaleX = w / mWidth;
                    float scaleY = h / mHeight;
                    float scale = Math.min(1f, Math.min(scaleX, scaleY));
                    contentScaleX = scale;
                    contentScaleY = scale;
                } break;
                case RootContentBehavior.SCALE_FIT: {
                    float scaleX = w / mWidth;
                    float scaleY = h / mHeight;
                    float scale = Math.min(scaleX, scaleY);
                    contentScaleX = scale;
                    contentScaleY = scale;
                } break;
                case RootContentBehavior.SCALE_FILL_WIDTH: {
                    float scale = w / mWidth;
                    contentScaleX = scale;
                    contentScaleY = scale;
                } break;
                case RootContentBehavior.SCALE_FILL_HEIGHT: {
                    float scale = h / mHeight;
                    contentScaleX = scale;
                    contentScaleY = scale;
                } break;
                case RootContentBehavior.SCALE_CROP: {
                    float scaleX = w / mWidth;
                    float scaleY = h / mHeight;
                    float scale = Math.max(scaleX, scaleY);
                    contentScaleX = scale;
                    contentScaleY = scale;
                } break;
                case RootContentBehavior.SCALE_FILL_BOUNDS: {
                    float scaleX = w / mWidth;
                    float scaleY = h / mHeight;
                    contentScaleX = scaleX;
                    contentScaleY = scaleY;
                } break;
                default:
                    // nothing
            }
        }
        scaleOutput[0] = contentScaleX;
        scaleOutput[1] = contentScaleY;
    }

    /**
     * Given dimensions w x h of where to paint the content, returns the corresponding translation
     * according to the contentAlignment information
     *
     * @param w horizontal dimension of the rendering area
     * @param h vertical dimension of the rendering area
     * @param contentScaleX the horizontal scale we are going to use for the content
     * @param contentScaleY the vertical scale we are going to use for the content
     * @param translateOutput will contain the computed translation
     */
    private void computeTranslate(float w, float h, float contentScaleX, float contentScaleY,
                                  float[] translateOutput) {
        int horizontalContentAlignment = mContentAlignment & 0xF0;
        int verticalContentAlignment = mContentAlignment & 0xF;
        float translateX = 0f;
        float translateY = 0f;
        float contentWidth = mWidth * contentScaleX;
        float contentHeight = mHeight * contentScaleY;

        switch (horizontalContentAlignment) {
            case RootContentBehavior.ALIGNMENT_START: {
                // nothing
            } break;
            case RootContentBehavior.ALIGNMENT_HORIZONTAL_CENTER: {
                translateX = (w - contentWidth) / 2f;
            } break;
            case RootContentBehavior.ALIGNMENT_END: {
                translateX = w - contentWidth;
            } break;
            default:
                // nothing (same as alignment_start)
        }
        switch (verticalContentAlignment) {
            case RootContentBehavior.ALIGNMENT_TOP: {
                // nothing
            } break;
            case RootContentBehavior.ALIGNMENT_VERTICAL_CENTER: {
                translateY = (h - contentHeight) / 2f;
            } break;
            case RootContentBehavior.ALIGNMENT_BOTTOM: {
                translateY = h - contentHeight;
            } break;
            default:
                // nothing (same as alignment_top)
        }

        translateOutput[0] = translateX;
        translateOutput[1] = translateY;
    }

    public Set<ClickAreaRepresentation> getClickAreas() {
        return mClickAreas;
    }

    public interface ClickCallbacks {
        void click(int id, String metadata);
    }

    HashSet<ClickCallbacks> mClickListeners = new HashSet<>();
    HashSet<ClickAreaRepresentation> mClickAreas = new HashSet<>();

    static class Version {
        public final int major;
        public final int minor;
        public final int patchLevel;

        Version(int major, int minor, int patchLevel) {
            this.major = major;
            this.minor = minor;
            this.patchLevel = patchLevel;
        }
    }

    public static class ClickAreaRepresentation {
        int mId;
        String mContentDescription;
        float mLeft;
        float mTop;
        float mRight;
        float mBottom;
        String mMetadata;

        public ClickAreaRepresentation(int id,
                                       String contentDescription,
                                       float left,
                                       float top,
                                       float right,
                                       float bottom,
                                       String metadata) {
            this.mId = id;
            this.mContentDescription = contentDescription;
            this.mLeft = left;
            this.mTop = top;
            this.mRight = right;
            this.mBottom = bottom;
            this.mMetadata = metadata;
        }

        public boolean contains(float x, float y)  {
            return x >= mLeft && x < mRight
                    && y >= mTop && y < mBottom;
        }

        public float getLeft() {
            return mLeft;
        }

        public float getTop() {
            return mTop;
        }

        public float width() {
            return Math.max(0, mRight - mLeft);
        }

        public float height() {
            return Math.max(0, mBottom - mTop);
        }

        public int getId() {
            return mId;
        }

        public String getContentDescription() {
            return mContentDescription;
        }

        public String getMetadata() {
            return mMetadata;
        }
    }

    /**
     * Load operations from the given buffer
     */
    public void initFromBuffer(RemoteComposeBuffer buffer) {
        mOperations = new ArrayList<Operation>();
        buffer.inflateFromBuffer(mOperations);
        mBuffer = buffer;
    }

    /**
     * Called when an initialization is needed, allowing the document to eg load
     * resources / cache them.
     */
    public void initializeContext(RemoteContext context) {
        mRemoteComposeState.reset();
        mClickAreas.clear();

        context.mDocument = this;
        context.mRemoteComposeState = mRemoteComposeState;

        // mark context to be in DATA mode, which will skip the painting ops.
        context.mMode = RemoteContext.ContextMode.DATA;
        for (Operation op: mOperations) {
            op.apply(context);
        }
        context.mMode = RemoteContext.ContextMode.UNSET;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Document infos
    ///////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns true if the document can be displayed given this version of the player
     *
     * @param majorVersion the max major version supported by the player
     * @param minorVersion the max minor version supported by the player
     * @param capabilities a bitmask of capabilities the player supports (unused for now)
     */
    public boolean canBeDisplayed(int majorVersion, int minorVersion, long capabilities) {
        return mVersion.major <= majorVersion && mVersion.minor <= minorVersion;
    }

    /**
     * Set the document version, following semantic versioning.
     *
     * @param majorVersion major version number, increased upon changes breaking the compatibility
     * @param minorVersion minor version number, increased when adding new features
     * @param patch        patch level, increased upon bugfixes
     */
    void  setVersion(int majorVersion, int minorVersion, int patch) {
        mVersion = new Version(majorVersion, minorVersion, patch);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Click handling
    ///////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Add a click area to the document, in root coordinates. We are not doing any specific sorting
     * through the declared areas on click detections, which means that the first one containing
     * the click coordinates will be the one reported; the order of addition of those click areas
     * is therefore meaningful.
     *
     * @param id       the id of the area, which will be reported on click
     * @param contentDescription the content description (used for accessibility)
     * @param left     the left coordinate of the click area (in pixels)
     * @param top      the top coordinate of the click area (in pixels)
     * @param right    the right coordinate of the click area (in pixels)
     * @param bottom   the bottom coordinate of the click area (in pixels)
     * @param metadata arbitrary metadata associated with the are, also reported on click
     */
    public void addClickArea(int id, String contentDescription,
                             float left, float top, float right, float bottom, String metadata) {
        mClickAreas.add(new ClickAreaRepresentation(id,
                contentDescription, left, top, right, bottom, metadata));
    }

    /**
     * Add a click listener. This will get called when a click is detected on the document
     *
     * @param callback called when a click area has been hit, passing the click are id and metadata.
     */
    public void addClickListener(ClickCallbacks callback) {
        mClickListeners.add(callback);
    }

    /**
     * Passing a click event to the document. This will possibly result in calling the click
     * listeners.
     */
    public void onClick(float x, float y) {
        for (ClickAreaRepresentation clickArea: mClickAreas) {
            if (clickArea.contains(x, y)) {
                warnClickListeners(clickArea);
            }
        }
    }

    /**
     * Programmatically trigger the click response for the given id
     *
     * @param id the click area id
     */
    public void performClick(int id) {
        for (ClickAreaRepresentation clickArea: mClickAreas) {
            if (clickArea.mId == id) {
                warnClickListeners(clickArea);
            }
        }
    }

    /**
     * Warn click listeners when a click area is activated
     */
    private void warnClickListeners(ClickAreaRepresentation clickArea) {
        for (ClickCallbacks listener: mClickListeners) {
            listener.click(clickArea.mId, clickArea.mMetadata);
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Painting
    ///////////////////////////////////////////////////////////////////////////////////////////////

    private final float[] mScaleOutput = new float[2];
    private final float[] mTranslateOutput = new float[2];

    /**
     * Paint the document
     *
     * @param context the provided PaintContext
     * @param theme   the theme we want to use for this document.
     */
    public void paint(RemoteContext context, int theme) {
        context.mMode = RemoteContext.ContextMode.PAINT;

        // current theme starts as UNSPECIFIED, until a Theme setter
        // operation gets executed and modify it.
        context.setTheme(Theme.UNSPECIFIED);

        context.mRemoteComposeState = mRemoteComposeState;
        if (mContentSizing == RootContentBehavior.SIZING_SCALE) {
            // we need to add canvas transforms ops here
            computeScale(context.mWidth, context.mHeight, mScaleOutput);
            computeTranslate(context.mWidth, context.mHeight,
                    mScaleOutput[0], mScaleOutput[1], mTranslateOutput);
            context.mPaintContext.translate(mTranslateOutput[0], mTranslateOutput[1]);
            context.mPaintContext.scale(mScaleOutput[0], mScaleOutput[1]);
        }
        for (Operation op : mOperations) {
            // operations will only be executed if no theme is set (ie UNSPECIFIED)
            // or the theme is equal as the one passed in argument to paint.
            boolean apply = true;
            if (theme != Theme.UNSPECIFIED) {
                apply = op instanceof Theme // always apply a theme setter
                        || context.getTheme() == theme
                        || context.getTheme() == Theme.UNSPECIFIED;
            }
            if (apply) {
                op.apply(context);
            }
        }
        context.mMode = RemoteContext.ContextMode.UNSET;
    }

}

