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

#include "Texture.h"
#include "Caches.h"
#include "utils/GLUtils.h"
#include "utils/MathUtils.h"
#include "utils/TraceUtils.h"

#include <utils/Log.h>

#include <math/mat4.h>

#include <SkCanvas.h>

namespace android {
namespace uirenderer {

// Number of bytes used by a texture in the given format
static int bytesPerPixel(GLint glFormat) {
    switch (glFormat) {
        // The wrapped-texture case, usually means a SurfaceTexture
        case 0:
            return 0;
        case GL_LUMINANCE:
        case GL_ALPHA:
            return 1;
        case GL_SRGB8:
        case GL_RGB:
            return 3;
        case GL_SRGB8_ALPHA8:
        case GL_RGBA:
            return 4;
        case GL_RGBA16F:
            return 8;
        default:
            LOG_ALWAYS_FATAL("UNKNOWN FORMAT 0x%x", glFormat);
    }
}

void Texture::setWrapST(GLenum wrapS, GLenum wrapT, bool bindTexture, bool force) {
    if (force || wrapS != mWrapS || wrapT != mWrapT) {
        mWrapS = wrapS;
        mWrapT = wrapT;

        if (bindTexture) {
            mCaches.textureState().bindTexture(mTarget, mId);
        }

        glTexParameteri(mTarget, GL_TEXTURE_WRAP_S, wrapS);
        glTexParameteri(mTarget, GL_TEXTURE_WRAP_T, wrapT);
    }
}

void Texture::setFilterMinMag(GLenum min, GLenum mag, bool bindTexture, bool force) {
    if (force || min != mMinFilter || mag != mMagFilter) {
        mMinFilter = min;
        mMagFilter = mag;

        if (bindTexture) {
            mCaches.textureState().bindTexture(mTarget, mId);
        }

        if (mipMap && min == GL_LINEAR) min = GL_LINEAR_MIPMAP_LINEAR;

        glTexParameteri(mTarget, GL_TEXTURE_MIN_FILTER, min);
        glTexParameteri(mTarget, GL_TEXTURE_MAG_FILTER, mag);
    }
}

void Texture::deleteTexture() {
    mCaches.textureState().deleteTexture(mId);
    mId = 0;
    mTarget = GL_NONE;
    if (mEglImageHandle != EGL_NO_IMAGE_KHR) {
        EGLDisplay eglDisplayHandle = eglGetCurrentDisplay();
        eglDestroyImageKHR(eglDisplayHandle, mEglImageHandle);
        mEglImageHandle = EGL_NO_IMAGE_KHR;
    }
}

bool Texture::updateLayout(uint32_t width, uint32_t height, GLint internalFormat, GLint format,
                           GLenum target) {
    if (mWidth == width && mHeight == height && mFormat == format &&
        mInternalFormat == internalFormat && mTarget == target) {
        return false;
    }
    mWidth = width;
    mHeight = height;
    mFormat = format;
    mInternalFormat = internalFormat;
    mTarget = target;
    notifySizeChanged(mWidth * mHeight * bytesPerPixel(internalFormat));
    return true;
}

void Texture::resetCachedParams() {
    mWrapS = GL_REPEAT;
    mWrapT = GL_REPEAT;
    mMinFilter = GL_NEAREST_MIPMAP_LINEAR;
    mMagFilter = GL_LINEAR;
}

void Texture::upload(GLint internalFormat, uint32_t width, uint32_t height, GLenum format,
                     GLenum type, const void* pixels) {
    GL_CHECKPOINT(MODERATE);

    // We don't have color space information, we assume the data is gamma encoded
    mIsLinear = false;

    bool needsAlloc = updateLayout(width, height, internalFormat, format, GL_TEXTURE_2D);
    if (!mId) {
        glGenTextures(1, &mId);
        needsAlloc = true;
        resetCachedParams();
    }
    mCaches.textureState().bindTexture(GL_TEXTURE_2D, mId);
    if (needsAlloc) {
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, mWidth, mHeight, 0, format, type, pixels);
    } else if (pixels) {
        glTexSubImage2D(GL_TEXTURE_2D, 0, internalFormat, mWidth, mHeight, 0, format, type, pixels);
    }
    GL_CHECKPOINT(MODERATE);
}

void Texture::uploadHardwareBitmapToTexture(GraphicBuffer* buffer) {
    EGLDisplay eglDisplayHandle = eglGetCurrentDisplay();
    if (mEglImageHandle != EGL_NO_IMAGE_KHR) {
        eglDestroyImageKHR(eglDisplayHandle, mEglImageHandle);
        mEglImageHandle = EGL_NO_IMAGE_KHR;
    }
    mEglImageHandle = eglCreateImageKHR(eglDisplayHandle, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
                                        buffer->getNativeBuffer(), 0);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, mEglImageHandle);
}

static void uploadToTexture(bool resize, GLint internalFormat, GLenum format, GLenum type,
                            GLsizei stride, GLsizei bpp, GLsizei width, GLsizei height,
                            const GLvoid* data) {
    const bool useStride =
            stride != width && Caches::getInstance().extensions().hasUnpackRowLength();
    if ((stride == width) || useStride) {
        if (useStride) {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, stride);
        }

        if (resize) {
            glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, data);
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, format, type, data);
        }

        if (useStride) {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        }
    } else {
        //  With OpenGL ES 2.0 we need to copy the bitmap in a temporary buffer
        //  if the stride doesn't match the width

        GLvoid* temp = (GLvoid*)malloc(width * height * bpp);
        if (!temp) return;

        uint8_t* pDst = (uint8_t*)temp;
        uint8_t* pSrc = (uint8_t*)data;
        for (GLsizei i = 0; i < height; i++) {
            memcpy(pDst, pSrc, width * bpp);
            pDst += width * bpp;
            pSrc += stride * bpp;
        }

        if (resize) {
            glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, temp);
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, format, type, temp);
        }

        free(temp);
    }
}

void Texture::colorTypeToGlFormatAndType(const Caches& caches, SkColorType colorType, bool needSRGB,
                                         GLint* outInternalFormat, GLint* outFormat,
                                         GLint* outType) {
    switch (colorType) {
        case kAlpha_8_SkColorType:
            *outFormat = GL_ALPHA;
            *outInternalFormat = GL_ALPHA;
            *outType = GL_UNSIGNED_BYTE;
            break;
        case kRGB_565_SkColorType:
            if (needSRGB) {
                // We would ideally use a GL_RGB/GL_SRGB8 texture but the
                // intermediate Skia bitmap needs to be ARGB_8888
                *outFormat = GL_RGBA;
                *outInternalFormat = caches.rgbaInternalFormat();
                *outType = GL_UNSIGNED_BYTE;
            } else {
                *outFormat = GL_RGB;
                *outInternalFormat = GL_RGB;
                *outType = GL_UNSIGNED_SHORT_5_6_5;
            }
            break;
        // ARGB_4444 is upconverted to RGBA_8888
        case kARGB_4444_SkColorType:
        case kN32_SkColorType:
            *outFormat = GL_RGBA;
            *outInternalFormat = caches.rgbaInternalFormat(needSRGB);
            *outType = GL_UNSIGNED_BYTE;
            break;
        case kGray_8_SkColorType:
            *outFormat = GL_LUMINANCE;
            *outInternalFormat = GL_LUMINANCE;
            *outType = GL_UNSIGNED_BYTE;
            break;
        case kRGBA_F16_SkColorType:
            if (caches.extensions().getMajorGlVersion() >= 3) {
                // This format is always linear
                *outFormat = GL_RGBA;
                *outInternalFormat = GL_RGBA16F;
                *outType = GL_HALF_FLOAT;
            } else {
                *outFormat = GL_RGBA;
                *outInternalFormat = caches.rgbaInternalFormat(true);
                *outType = GL_UNSIGNED_BYTE;
            }
            break;
        default:
            LOG_ALWAYS_FATAL("Unsupported bitmap colorType: %d", colorType);
            break;
    }
}

SkBitmap Texture::uploadToN32(const SkBitmap& bitmap, bool hasLinearBlending,
                              sk_sp<SkColorSpace> sRGB) {
    SkBitmap rgbaBitmap;
    rgbaBitmap.allocPixels(SkImageInfo::MakeN32(bitmap.width(), bitmap.height(),
                                                bitmap.info().alphaType(),
                                                hasLinearBlending ? sRGB : nullptr));
    rgbaBitmap.eraseColor(0);

    if (bitmap.colorType() == kRGBA_F16_SkColorType) {
        // Drawing RGBA_F16 onto ARGB_8888 is not supported
        bitmap.readPixels(rgbaBitmap.info().makeColorSpace(SkColorSpace::MakeSRGB()),
                          rgbaBitmap.getPixels(), rgbaBitmap.rowBytes(), 0, 0);
    } else {
        SkCanvas canvas(rgbaBitmap);
        canvas.drawBitmap(bitmap, 0.0f, 0.0f, nullptr);
    }

    return rgbaBitmap;
}

bool Texture::hasUnsupportedColorType(const SkImageInfo& info, bool hasLinearBlending) {
    return info.colorType() == kARGB_4444_SkColorType ||
           (info.colorType() == kRGB_565_SkColorType && hasLinearBlending &&
            info.colorSpace()->isSRGB()) ||
           (info.colorType() == kRGBA_F16_SkColorType &&
            Caches::getInstance().extensions().getMajorGlVersion() < 3);
}

void Texture::upload(Bitmap& bitmap) {
    ATRACE_FORMAT("Upload %ux%u Texture", bitmap.width(), bitmap.height());

    // We could also enable mipmapping if both bitmap dimensions are powers
    // of 2 but we'd have to deal with size changes. Let's keep this simple
    const bool canMipMap = mCaches.extensions().hasNPot();

    // If the texture had mipmap enabled but not anymore,
    // force a glTexImage2D to discard the mipmap levels
    bool needsAlloc = canMipMap && mipMap && !bitmap.hasHardwareMipMap();
    bool setDefaultParams = false;

    if (!mId) {
        glGenTextures(1, &mId);
        needsAlloc = true;
        setDefaultParams = true;
    }

    bool hasLinearBlending = mCaches.extensions().hasLinearBlending();
    bool needSRGB = transferFunctionCloseToSRGB(bitmap.info().colorSpace());

    GLint internalFormat, format, type;
    colorTypeToGlFormatAndType(mCaches, bitmap.colorType(), needSRGB && hasLinearBlending,
                               &internalFormat, &format, &type);

    // Some devices don't support GL_RGBA16F, so we need to compare the color type
    // and internal GL format to decide what to do with 16 bit bitmaps
    bool rgba16fNeedsConversion =
            bitmap.colorType() == kRGBA_F16_SkColorType && internalFormat != GL_RGBA16F;

    // RGBA16F is always linear extended sRGB
    if (internalFormat == GL_RGBA16F) {
        mIsLinear = true;
    }

    mConnector.reset();

    // Alpha masks don't have color profiles
    // If an RGBA16F bitmap needs conversion, we know the target will be sRGB
    if (!mIsLinear && internalFormat != GL_ALPHA && !rgba16fNeedsConversion) {
        SkColorSpace* colorSpace = bitmap.info().colorSpace();
        // If the bitmap is sRGB we don't need conversion
        if (colorSpace != nullptr && !colorSpace->isSRGB()) {
            SkMatrix44 xyzMatrix(SkMatrix44::kUninitialized_Constructor);
            if (!colorSpace->toXYZD50(&xyzMatrix)) {
                ALOGW("Incompatible color space!");
            } else {
                SkColorSpaceTransferFn fn;
                if (!colorSpace->isNumericalTransferFn(&fn)) {
                    ALOGW("Incompatible color space, no numerical transfer function!");
                } else {
                    float data[16];
                    xyzMatrix.asColMajorf(data);

                    ColorSpace::TransferParameters p = {fn.fG, fn.fA, fn.fB, fn.fC,
                                                        fn.fD, fn.fE, fn.fF};
                    ColorSpace src("Unnamed", mat4f((const float*)&data[0]).upperLeft(), p);
                    mConnector.reset(new ColorSpaceConnector(src, ColorSpace::sRGB()));

                    // A non-sRGB color space might have a transfer function close enough to sRGB
                    // that we can save shader instructions by using an sRGB sampler
                    // This is only possible if we have hardware support for sRGB textures
                    if (needSRGB && internalFormat == GL_RGBA && mCaches.extensions().hasSRGB() &&
                        !bitmap.isHardware()) {
                        internalFormat = GL_SRGB8_ALPHA8;
                    }
                }
            }
        }
    }

    GLenum target = bitmap.isHardware() ? GL_TEXTURE_EXTERNAL_OES : GL_TEXTURE_2D;
    needsAlloc |= updateLayout(bitmap.width(), bitmap.height(), internalFormat, format, target);

    blend = !bitmap.isOpaque();
    mCaches.textureState().bindTexture(mTarget, mId);

    // TODO: Handle sRGB gray bitmaps
    if (CC_UNLIKELY(hasUnsupportedColorType(bitmap.info(), hasLinearBlending))) {
        SkBitmap skBitmap;
        bitmap.getSkBitmap(&skBitmap);
        sk_sp<SkColorSpace> sRGB = SkColorSpace::MakeSRGB();
        SkBitmap rgbaBitmap = uploadToN32(skBitmap, hasLinearBlending, std::move(sRGB));
        uploadToTexture(needsAlloc, internalFormat, format, type, rgbaBitmap.rowBytesAsPixels(),
                        rgbaBitmap.bytesPerPixel(), rgbaBitmap.width(), rgbaBitmap.height(),
                        rgbaBitmap.getPixels());
    } else if (bitmap.isHardware()) {
        uploadHardwareBitmapToTexture(bitmap.graphicBuffer());
    } else {
        uploadToTexture(needsAlloc, internalFormat, format, type, bitmap.rowBytesAsPixels(),
                        bitmap.info().bytesPerPixel(), bitmap.width(), bitmap.height(),
                        bitmap.pixels());
    }

    if (canMipMap) {
        mipMap = bitmap.hasHardwareMipMap();
        if (mipMap) {
            glGenerateMipmap(GL_TEXTURE_2D);
        }
    }

    if (setDefaultParams) {
        setFilter(GL_NEAREST);
        setWrap(GL_CLAMP_TO_EDGE);
    }
}

void Texture::wrap(GLuint id, uint32_t width, uint32_t height, GLint internalFormat, GLint format,
                   GLenum target) {
    mId = id;
    mWidth = width;
    mHeight = height;
    mFormat = format;
    mInternalFormat = internalFormat;
    mTarget = target;
    mConnector.reset();
    // We're wrapping an existing texture, so don't double count this memory
    notifySizeChanged(0);
}

TransferFunctionType Texture::getTransferFunctionType() const {
    if (mConnector.get() != nullptr && mInternalFormat != GL_SRGB8_ALPHA8) {
        const ColorSpace::TransferParameters& p = mConnector->getSource().getTransferParameters();
        if (MathUtils::isZero(p.e) && MathUtils::isZero(p.f)) {
            if (MathUtils::areEqual(p.a, 1.0f) && MathUtils::isZero(p.b) &&
                MathUtils::isZero(p.c) && MathUtils::isZero(p.d)) {
                if (MathUtils::areEqual(p.g, 1.0f)) {
                    return TransferFunctionType::None;
                }
                return TransferFunctionType::Gamma;
            }
            return TransferFunctionType::Limited;
        }
        return TransferFunctionType::Full;
    }
    return TransferFunctionType::None;
}

};  // namespace uirenderer
};  // namespace android
