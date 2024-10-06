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
package com.android.internal.widget.remotecompose.player.platform;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;

import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.operations.FloatExpression;
import com.android.internal.widget.remotecompose.core.operations.ShaderData;
import com.android.internal.widget.remotecompose.core.operations.utilities.ArrayAccess;

import java.util.HashMap;

/**
 * An implementation of Context for Android.
 * <p>
 * This is used to play the RemoteCompose operations on Android.
 */
class AndroidRemoteContext extends RemoteContext {

    public void useCanvas(Canvas canvas) {
        if (mPaintContext == null) {
            mPaintContext = new AndroidPaintContext(this, canvas);
        } else {
            // need to make sure to update the canvas for the current one
            mPaintContext.reset();
            ((AndroidPaintContext) mPaintContext).setCanvas(canvas);
        }
        mWidth = canvas.getWidth();
        mHeight = canvas.getHeight();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Data handling
    ///////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void loadPathData(int instanceId, float[] floatPath) {
        if (!mRemoteComposeState.containsId(instanceId)) {
            mRemoteComposeState.cacheData(instanceId, floatPath);
        }
    }

    static class VarName {
        String mName;
        int mId;
        int mType;

        VarName(String name, int id, int type) {
            mName = name;
            mId = id;
            mType = type;
        }
    }

    HashMap<String, VarName> mVarNameHashMap = new HashMap<>();

    @Override
    public void loadVariableName(String varName, int varId, int varType) {
        mVarNameHashMap.put(varName, new VarName(varName, varId, varType));
    }

    @Override
    public void setNamedStringOverride(String stringName, String value) {
        if (mVarNameHashMap.get(stringName) != null) {
            int id = mVarNameHashMap.get(stringName).mId;
            overrideText(id, value);
        }
    }

    @Override
    public void clearNamedStringOverride(String stringName) {
        if (mVarNameHashMap.get(stringName) != null) {
            int id = mVarNameHashMap.get(stringName).mId;
            clearDataOverride(id);
        }
        mVarNameHashMap.put(stringName, null);
    }

    @Override
    public void setNamedIntegerOverride(String stringName, int value) {
        if (mVarNameHashMap.get(stringName) != null) {
            int id = mVarNameHashMap.get(stringName).mId;
            overrideInt(id, value);
        }
    }

    @Override
    public void clearNamedIntegerOverride(String integerName) {
        if (mVarNameHashMap.get(integerName) != null) {
            int id = mVarNameHashMap.get(integerName).mId;
            clearIntegerOverride(id);
        }
        mVarNameHashMap.put(integerName, null);
    }

    /**
     * Override a color to force it to be the color provided
     *
     * @param colorName name of color
     * @param color
     */
    public void setNamedColorOverride(String colorName, int color) {
        int id = mVarNameHashMap.get(colorName).mId;
        mRemoteComposeState.overrideColor(id, color);
    }

    @Override
    public void addCollection(int id, ArrayAccess collection) {
        mRemoteComposeState.addCollection(id, collection);
    }

    @Override
    public void runAction(int id, String metadata) {
        mDocument.performClick(id);
    }

    @Override
    public void runNamedAction(int id) {
        String text = getText(id);
        mDocument.runNamedAction(text);
    }

    /**
     * Decode a byte array into an image and cache it using the given imageId
     *
     * @param width  with of image to be loaded
     * @param height height of image to be loaded
     * @param bitmap a byte array containing the image information
     * @oaram imageId the id of the image
     */
    @Override
    public void loadBitmap(int imageId, int width, int height, byte[] bitmap) {
        if (!mRemoteComposeState.containsId(imageId)) {
            Bitmap image = BitmapFactory.decodeByteArray(bitmap, 0, bitmap.length);
            mRemoteComposeState.cacheData(imageId, image);
        }
    }

    @Override
    public void loadText(int id, String text) {
        if (!mRemoteComposeState.containsId(id)) {
            mRemoteComposeState.cacheData(id, text);
        } else {
            mRemoteComposeState.updateData(id, text);
        }
    }

    public void overrideText(int id, String text) {
        mRemoteComposeState.overrideData(id, text);
    }

    public void overrideInt(int id, int value) {
        mRemoteComposeState.overrideInteger(id, value);
    }

    public void clearDataOverride(int id) {
        mRemoteComposeState.clearDataOverride(id);
    }

    public void clearIntegerOverride(int id) {
        mRemoteComposeState.clearIntegerOverride(id);
    }

    @Override
    public String getText(int id) {
        return (String) mRemoteComposeState.getFromId(id);
    }

    @Override
    public void loadFloat(int id, float value) {
        mRemoteComposeState.updateFloat(id, value);
    }

    @Override
    public void loadInteger(int id, int value) {
        mRemoteComposeState.updateInteger(id, value);
    }

    public void overrideInteger(int id, int value) {
        mRemoteComposeState.overrideInteger(id, value);
    }

    public void overrideText(int id, int valueId) {
        String text = getText(valueId);
        overrideText(id, text);
    }

    @Override
    public void loadColor(int id, int color) {
        mRemoteComposeState.updateColor(id, color);
    }

    @Override
    public void loadAnimatedFloat(int id, FloatExpression animatedFloat) {
        mRemoteComposeState.cacheData(id, animatedFloat);
    }

    @Override
    public void loadShader(int id, ShaderData value) {
        mRemoteComposeState.cacheData(id, value);
    }

    @Override
    public float getFloat(int id) {
        return (float) mRemoteComposeState.getFloat(id);
    }

    @Override
    public int getInteger(int id) {
        return  mRemoteComposeState.getInteger(id);
    }

    @Override
    public int getColor(int id) {
        return mRemoteComposeState.getColor(id);
    }

    @Override
    public void listensTo(int id, VariableSupport variableSupport) {
        mRemoteComposeState.listenToVar(id, variableSupport);
    }

    @Override
    public int updateOps() {
        return mRemoteComposeState.getOpsToUpdate(this);
    }

    @Override
    public ShaderData getShader(int id) {
        return (ShaderData) mRemoteComposeState.getFromId(id);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Click handling
    ///////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void addClickArea(int id,
                             int contentDescriptionId,
                             float left,
                             float top,
                             float right,
                             float bottom,
                             int metadataId) {
        String contentDescription = (String) mRemoteComposeState.getFromId(contentDescriptionId);
        String metadata = (String) mRemoteComposeState.getFromId(metadataId);
        mDocument.addClickArea(id, contentDescription, left, top, right, bottom, metadata);
    }

}

