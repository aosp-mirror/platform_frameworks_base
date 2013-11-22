/*
 * Copyright (C) 2011 The Android Open Source Project
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

package androidx.media.filterfw;

import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

/**
 * TODO: Move this to filterpacks/base?
 */
public abstract class ViewFilter extends Filter {

    public static final int SCALE_STRETCH = 1;
    public static final int SCALE_FIT = 2;
    public static final int SCALE_FILL = 3;

    protected int mScaleMode = SCALE_FIT;
    protected float[] mClearColor = new float[] { 0f, 0f, 0f, 1f };
    protected boolean mFlipVertically = true;

    private String mRequestedScaleMode = null;

    protected ViewFilter(MffContext context, String name) {
        super(context, name);
    }

    /**
     * Binds the filter to a view.
     * View filters support visualizing data to a view. Check the specific filter documentation
     * for details. The view may be bound only if the filter's graph is not running.
     *
     * @param view the view to bind to.
     * @throws IllegalStateException if the method is called while the graph is running.
     */
    public void bindToView(View view) {
        if (isRunning()) {
            throw new IllegalStateException("Attempting to bind filter to view while it is "
                + "running!");
        }
        onBindToView(view);
    }

    public void setScaleMode(int scaleMode) {
        if (isRunning()) {
            throw new IllegalStateException("Attempting to change scale mode while filter is "
                + "running!");
        }
        mScaleMode = scaleMode;
    }

    @Override
    public Signature getSignature() {
        return new Signature()
            .addInputPort("scaleMode", Signature.PORT_OPTIONAL, FrameType.single(String.class))
            .addInputPort("flip", Signature.PORT_OPTIONAL, FrameType.single(boolean.class));
    }

    /**
     * Subclasses must override this method to bind their filter to the specified view.
     *
     * When this method is called, Filter implementations may assume that the graph is not
     * currently running.
     */
    protected abstract void onBindToView(View view);

    /**
     * TODO: Document.
     */
    protected RectF getTargetRect(Rect frameRect, Rect bufferRect) {
        RectF result = new RectF();
        if (bufferRect.width() > 0 && bufferRect.height() > 0) {
            float frameAR = (float)frameRect.width() / frameRect.height();
            float bufferAR = (float)bufferRect.width() / bufferRect.height();
            float relativeAR = bufferAR / frameAR;
            switch (mScaleMode) {
                case SCALE_STRETCH:
                    result.set(0f, 0f, 1f, 1f);
                    break;
                case SCALE_FIT:
                    if (relativeAR > 1.0f) {
                        float x = 0.5f - 0.5f / relativeAR;
                        float y = 0.0f;
                        result.set(x, y, x + 1.0f / relativeAR, y + 1.0f);
                    } else {
                        float x = 0.0f;
                        float y = 0.5f - 0.5f * relativeAR;
                        result.set(x, y, x + 1.0f, y + relativeAR);
                    }
                    break;
                case SCALE_FILL:
                    if (relativeAR > 1.0f) {
                        float x = 0.0f;
                        float y = 0.5f - 0.5f * relativeAR;
                        result.set(x, y, x + 1.0f, y + relativeAR);
                    } else {
                        float x = 0.5f - 0.5f / relativeAR;
                        float y = 0.0f;
                        result.set(x, y, x + 1.0f / relativeAR, y + 1.0f);
                    }
                    break;
            }
        }
        return result;
    }

    protected void connectViewInputs(InputPort port) {
        if (port.getName().equals("scaleMode")) {
            port.bindToListener(mScaleModeListener);
            port.setAutoPullEnabled(true);
        } else if (port.getName().equals("flip")) {
            port.bindToFieldNamed("mFlipVertically");
            port.setAutoPullEnabled(true);
        }
    }

    protected void setupShader(ImageShader shader, Rect frameRect, Rect outputRect) {
        shader.setTargetRect(getTargetRect(frameRect, outputRect));
        shader.setClearsOutput(true);
        shader.setClearColor(mClearColor);
        if (mFlipVertically) {
            shader.setSourceRect(0f, 1f, 1f, -1f);
        }
    }

    private InputPort.FrameListener mScaleModeListener = new InputPort.FrameListener() {
        @Override
        public void onFrameReceived(InputPort port, Frame frame) {
            String scaleMode = (String)frame.asFrameValue().getValue();
            if (!scaleMode.equals(mRequestedScaleMode)) {
                mRequestedScaleMode = scaleMode;
                if (scaleMode.equals("stretch")) {
                    mScaleMode = SCALE_STRETCH;
                } else if (scaleMode.equals("fit")) {
                    mScaleMode = SCALE_FIT;
                } else if (scaleMode.equals("fill")) {
                    mScaleMode = SCALE_FILL;
                } else {
                    throw new RuntimeException("Unknown scale-mode '" + scaleMode + "'!");
                }
            }
        }
    };
}

