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

/**
 * An implementation of Context for Android.
 *
 * This is used to play the RemoteCompose operations on Android.
 */
class AndroidRemoteContext extends RemoteContext {

    public void useCanvas(Canvas canvas) {
        if (mPaintContext == null) {
            mPaintContext = new AndroidPaintContext(this, canvas);
        } else {
            // need to make sure to update the canvas for the current one
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
            mRemoteComposeState.cache(instanceId, floatPath);
        }
    }

    /**
     * Decode a byte array into an image and cache it using the given imageId
     *
     * @oaram imageId the id of the image
     * @param width with of image to be loaded
     * @param height height of image to be loaded
     * @param bitmap a byte array containing the image information
     */
    @Override
    public void loadBitmap(int imageId, int width, int height, byte[] bitmap) {
        if (!mRemoteComposeState.containsId(imageId)) {
            Bitmap image = BitmapFactory.decodeByteArray(bitmap, 0, bitmap.length);
            mRemoteComposeState.cache(imageId, image);
        }
    }

    @Override
    public void loadText(int id, String text) {
        if (!mRemoteComposeState.containsId(id)) {
            mRemoteComposeState.cache(id, text);
        }
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
        String  metadata = (String) mRemoteComposeState.getFromId(metadataId);
        mDocument.addClickArea(id, contentDescription, left, top, right, bottom, metadata);
    }
}

