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

import android.graphics.Bitmap;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import java.nio.ByteBuffer;

public class TextureSource {

    private int mTexId;
    private int mTarget;
    private boolean mIsOwner;
    private boolean mIsAllocated = false;

    public static TextureSource fromTexture(int texId, int target) {
        return new TextureSource(texId, target, false);
    }

    public static TextureSource fromTexture(int texId) {
        return new TextureSource(texId, GLES20.GL_TEXTURE_2D, false);
    }

    public static TextureSource newTexture() {
        return new TextureSource(GLToolbox.generateTexture(), GLES20.GL_TEXTURE_2D, true);
    }

    public static TextureSource newExternalTexture() {
        return new TextureSource(GLToolbox.generateTexture(),
                                 GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                                 true);
    }

    public int getTextureId() {
        return mTexId;
    }

    public int getTarget() {
        return mTarget;
    }

    public void bind() {
        GLES20.glBindTexture(mTarget, mTexId);
        GLToolbox.checkGlError("glBindTexture");
    }

    public void allocate(int width, int height) {
        //Log.i("TextureSource", "Allocating empty texture " + mTexId + ": " + width + "x" + height + ".");
        GLToolbox.allocateTexturePixels(mTexId, mTarget, width, height);
        mIsAllocated = true;
    }

    public void allocateWithPixels(ByteBuffer pixels, int width, int height) {
        //Log.i("TextureSource", "Uploading pixels to texture " + mTexId + ": " + width + "x" + height + ".");
        GLToolbox.setTexturePixels(mTexId, mTarget, pixels, width, height);
        mIsAllocated = true;
    }

    public void allocateWithBitmapPixels(Bitmap bitmap) {
        //Log.i("TextureSource", "Uploading pixels to texture " + mTexId + "!");
        GLToolbox.setTexturePixels(mTexId, mTarget, bitmap);
        mIsAllocated = true;
    }

    public void generateMipmaps() {
        GLES20.glBindTexture(mTarget, mTexId);
        GLES20.glTexParameteri(mTarget,
                               GLES20.GL_TEXTURE_MIN_FILTER,
                               GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glGenerateMipmap(mTarget);
        GLES20.glBindTexture(mTarget, 0);
    }

    public void setParameter(int parameter, int value) {
        GLES20.glBindTexture(mTarget, mTexId);
        GLES20.glTexParameteri(mTarget, parameter, value);
        GLES20.glBindTexture(mTarget, 0);
    }

    /**
     * @hide
     */
    public void release() {
        if (GLToolbox.isTexture(mTexId) && mIsOwner) {
            GLToolbox.deleteTexture(mTexId);
        }
        mTexId = GLToolbox.textureNone();
    }

    @Override
    public String toString() {
        return "TextureSource(id=" + mTexId + ", target=" + mTarget + ")";
    }

    boolean isAllocated() {
        return mIsAllocated;
    }

    private TextureSource(int texId, int target, boolean isOwner) {
        mTexId = texId;
        mTarget = target;
        mIsOwner = isOwner;
    }
}

