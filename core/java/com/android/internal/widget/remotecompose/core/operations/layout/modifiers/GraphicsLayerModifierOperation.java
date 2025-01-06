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
package com.android.internal.widget.remotecompose.core.operations.layout.modifiers;

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.FLOAT;
import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.layout.AnimatableValue;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/**
 * Represents a padding modifier. Padding modifiers can be chained and will impact following
 * modifiers.
 */
public class GraphicsLayerModifierOperation extends DecoratorModifierOperation {
    private static final int OP_CODE = Operations.MODIFIER_GRAPHICS_LAYER;
    public static final String CLASS_NAME = "GraphicsLayerModifierOperation";

    AnimatableValue mScaleX;
    AnimatableValue mScaleY;
    AnimatableValue mRotationX;
    AnimatableValue mRotationY;
    AnimatableValue mRotationZ;
    AnimatableValue mTransformOriginX;
    AnimatableValue mTransformOriginY;
    AnimatableValue mShadowElevation;
    AnimatableValue mAlpha;
    AnimatableValue mCameraDistance;
    int mBlendMode;
    int mSpotShadowColorId;
    int mAmbientShadowColorId;
    int mColorFilterId;
    int mRenderEffectId;

    public GraphicsLayerModifierOperation(
            float scaleX,
            float scaleY,
            float rotationX,
            float rotationY,
            float rotationZ,
            float shadowElevation,
            float transformOriginX,
            float transformOriginY,
            float alpha,
            float cameraDistance,
            int blendMode,
            int spotShadowColorId,
            int ambientShadowColorId,
            int colorFilterId,
            int renderEffectId) {
        mScaleX = new AnimatableValue(scaleX);
        mScaleY = new AnimatableValue(scaleY);
        mRotationX = new AnimatableValue(rotationX);
        mRotationY = new AnimatableValue(rotationY);
        mRotationZ = new AnimatableValue(rotationZ);
        mShadowElevation = new AnimatableValue(shadowElevation);
        mTransformOriginX = new AnimatableValue(transformOriginX);
        mTransformOriginY = new AnimatableValue(transformOriginY);
        mAlpha = new AnimatableValue(alpha);
        mCameraDistance = new AnimatableValue(cameraDistance);
        mBlendMode = blendMode;
        mSpotShadowColorId = spotShadowColorId;
        mAmbientShadowColorId = ambientShadowColorId;
        mColorFilterId = colorFilterId;
        mRenderEffectId = renderEffectId;
    }

    public float getScaleX() {
        return mScaleX.getValue();
    }

    public float getScaleY() {
        return mScaleY.getValue();
    }

    public float getRotationX() {
        return mRotationX.getValue();
    }

    public float getRotationY() {
        return mRotationY.getValue();
    }

    public float getRotationZ() {
        return mRotationZ.getValue();
    }

    public float getShadowElevation() {
        return mShadowElevation.getValue();
    }

    public float getTransformOriginX() {
        return mTransformOriginX.getValue();
    }

    public float getTransformOriginY() {
        return mTransformOriginY.getValue();
    }

    public float getAlpha() {
        return mAlpha.getValue();
    }

    public float getCameraDistance() {
        return mCameraDistance.getValue();
    }

    // TODO: add implementation for blendmode
    public int getBlendModeId() {
        return mBlendMode;
    }

    // TODO: add implementation for shadow
    public int getSpotShadowColorId() {
        return mSpotShadowColorId;
    }

    public int getAmbientShadowColorId() {
        return mAmbientShadowColorId;
    }

    // TODO: add implementation for color filters
    public int getColorFilterId() {
        return mColorFilterId;
    }

    public int getRenderEffectId() {
        return mRenderEffectId;
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(
                buffer,
                mScaleX.getValue(),
                mScaleY.getValue(),
                mRotationX.getValue(),
                mRotationY.getValue(),
                mRotationZ.getValue(),
                mShadowElevation.getValue(),
                mTransformOriginX.getValue(),
                mTransformOriginY.getValue(),
                mAlpha.getValue(),
                mCameraDistance.getValue(),
                mBlendMode,
                mSpotShadowColorId,
                mAmbientShadowColorId,
                mColorFilterId,
                mRenderEffectId);
    }

    @Override
    public void serializeToString(int indent, StringSerializer serializer) {
        serializer.append(indent, "GRAPHICS_LAYER = [" + mScaleX + ", " + mScaleY + "]");
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return (indent != null ? indent : "") + toString();
    }

    @Override
    public void paint(PaintContext context) {
        mScaleX.evaluate(context);
        mScaleY.evaluate(context);
        mRotationX.evaluate(context);
        mRotationY.evaluate(context);
        mRotationZ.evaluate(context);
        mTransformOriginX.evaluate(context);
        mTransformOriginY.evaluate(context);
        mShadowElevation.evaluate(context);
        mAlpha.evaluate(context);
        mCameraDistance.evaluate(context);
    }

    @Override
    public String toString() {
        return "GraphicsLayerModifierOperation(" + mScaleX + ", " + mScaleY + ")";
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
            WireBuffer buffer,
            float scaleX,
            float scaleY,
            float rotationX,
            float rotationY,
            float rotationZ,
            float shadowElevation,
            float transformOriginX,
            float transformOriginY,
            float alpha,
            float cameraDistance,
            int blendMode,
            int spotShadowColorId,
            int ambientShadowColorId,
            int colorFilterId,
            int renderEffectId) {
        buffer.start(OP_CODE);
        buffer.writeFloat(scaleX);
        buffer.writeFloat(scaleY);
        buffer.writeFloat(rotationX);
        buffer.writeFloat(rotationY);
        buffer.writeFloat(rotationZ);
        buffer.writeFloat(shadowElevation);
        buffer.writeFloat(transformOriginX);
        buffer.writeFloat(transformOriginY);
        buffer.writeFloat(alpha);
        buffer.writeFloat(cameraDistance);
        buffer.writeInt(blendMode);
        buffer.writeInt(spotShadowColorId);
        buffer.writeInt(ambientShadowColorId);
        buffer.writeInt(colorFilterId);
        buffer.writeInt(renderEffectId);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        float scaleX = buffer.readFloat();
        float scaleY = buffer.readFloat();
        float rotationX = buffer.readFloat();
        float rotationY = buffer.readFloat();
        float rotationZ = buffer.readFloat();
        float shadowElevation = buffer.readFloat();
        float transformOriginX = buffer.readFloat();
        float transformOriginY = buffer.readFloat();
        float alpha = buffer.readFloat();
        float cameraDistance = buffer.readFloat();
        int blendMode = buffer.readInt();
        int spotShadowColorId = buffer.readInt();
        int ambientShadowColorId = buffer.readInt();
        int colorFilterId = buffer.readInt();
        int renderEffectId = buffer.readInt();
        operations.add(
                new GraphicsLayerModifierOperation(
                        scaleX,
                        scaleY,
                        rotationX,
                        rotationY,
                        rotationZ,
                        shadowElevation,
                        transformOriginX,
                        transformOriginY,
                        alpha,
                        cameraDistance,
                        blendMode,
                        spotShadowColorId,
                        ambientShadowColorId,
                        colorFilterId,
                        renderEffectId));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Modifier Operations", OP_CODE, CLASS_NAME)
                .description("define the GraphicsLayer Modifier")
                .field(FLOAT, "scaleX", "")
                .field(FLOAT, "scaleY", "")
                .field(FLOAT, "rotationX", "")
                .field(FLOAT, "rotationY", "")
                .field(FLOAT, "rotationZ", "")
                .field(FLOAT, "shadowElevation", "")
                .field(FLOAT, "transformOriginX", "")
                .field(FLOAT, "transformOriginY", "")
                .field(FLOAT, "alpha", "")
                .field(FLOAT, "cameraDistance", "")
                .field(INT, "blendMode", "")
                .field(INT, "spotShadowColorId", "")
                .field(INT, "ambientShadowColorId", "")
                .field(INT, "colorFilterId", "")
                .field(INT, "renderEffectId", "");
    }

    @Override
    public void layout(RemoteContext context, Component component, float width, float height) {}
}
