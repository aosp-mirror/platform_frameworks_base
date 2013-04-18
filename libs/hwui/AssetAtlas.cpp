/*
 * Copyright (C) 2013 The Android Open Source Project
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

#define LOG_TAG "OpenGLRenderer"

#include "AssetAtlas.h"

#include <GLES2/gl2ext.h>

#include <utils/Log.h>

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Lifecycle
///////////////////////////////////////////////////////////////////////////////

void AssetAtlas::init(sp<GraphicBuffer> buffer, int* map, int count) {
    if (mImage != EGL_NO_IMAGE_KHR) {
        return;
    }

    // Create the EGLImage object that maps the GraphicBuffer
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLClientBuffer clientBuffer = (EGLClientBuffer) buffer->getNativeBuffer();
    EGLint attrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };

    mImage = eglCreateImageKHR(display, EGL_NO_CONTEXT,
            EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attrs);

    if (mImage == EGL_NO_IMAGE_KHR) {
        ALOGW("Error creating atlas image (%#x)", eglGetError());
        return;
    }

    // Create a 2D texture to sample from the EGLImage
    glGenTextures(1, &mTexture);
    glBindTexture(GL_TEXTURE_2D, mTexture);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, mImage);

    mWidth = buffer->getWidth();
    mHeight = buffer->getHeight();

    createEntries(map, count);
}

void AssetAtlas::terminate() {
    if (mImage != EGL_NO_IMAGE_KHR) {
        eglDestroyImageKHR(eglGetDisplay(EGL_DEFAULT_DISPLAY), mImage);
        mImage = EGL_NO_IMAGE_KHR;

        glDeleteTextures(1, &mTexture);
        mTexture = 0;

        for (size_t i = 0; i < mEntries.size(); i++) {
            delete mEntries.valueAt(i);
        }
        mEntries.clear();

        mWidth = mHeight = 0;
    }
}

///////////////////////////////////////////////////////////////////////////////
// Entries
///////////////////////////////////////////////////////////////////////////////

AssetAtlas::Entry* AssetAtlas::getEntry(SkBitmap* const bitmap) const {
    ssize_t index = mEntries.indexOfKey(bitmap);
    return index >= 0 ? mEntries.valueAt(index) : NULL;
}

Texture* AssetAtlas::getEntryTexture(SkBitmap* const bitmap) const {
    ssize_t index = mEntries.indexOfKey(bitmap);
    return index >= 0 ? &mEntries.valueAt(index)->texture : NULL;
}

/**
 * TODO: This method does not take the rotation flag into account
 */
void AssetAtlas::createEntries(int* map, int count) {
    for (int i = 0; i < count; ) {
        SkBitmap* bitmap = (SkBitmap*) map[i++];
        int x = map[i++];
        int y = map[i++];
        bool rotated = map[i++] > 0;

        // Bitmaps should never be null, we're just extra paranoid
        if (!bitmap) continue;

        const UvMapper mapper(
                x / (float) mWidth, (x + bitmap->width()) / (float) mWidth,
                y / (float) mHeight, (y + bitmap->height()) / (float) mHeight);

        Entry* entry = new Entry(bitmap, x, y, rotated, mapper, *this);
        entry->texture.id = mTexture;
        entry->texture.blend = !bitmap->isOpaque();
        entry->texture.width = bitmap->width();
        entry->texture.height = bitmap->height();
        entry->texture.uvMapper = &entry->uvMapper;

        mEntries.add(entry->bitmap, entry);
    }
}

}; // namespace uirenderer
}; // namespace android
