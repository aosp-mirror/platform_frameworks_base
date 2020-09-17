/*
 * Copyright (C) 2019 The Android Open Source Project
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

#ifndef ANDROID_GRAPHICS_TYPECAST_H
#define ANDROID_GRAPHICS_TYPECAST_H

struct ABitmap;
struct ACanvas;
struct APaint;

namespace android {

    class Bitmap;
    class Canvas;
    class Paint;

    class TypeCast {
    public:
        static inline Bitmap& toBitmapRef(const ABitmap* bitmap) {
            return const_cast<Bitmap&>(reinterpret_cast<const Bitmap&>(*bitmap));
        }

        static inline Bitmap* toBitmap(ABitmap* bitmap) {
            return reinterpret_cast<Bitmap*>(bitmap);
        }

        static inline ABitmap* toABitmap(Bitmap* bitmap) {
            return reinterpret_cast<ABitmap*>(bitmap);
        }

        static inline Canvas* toCanvas(ACanvas* canvas) {
            return reinterpret_cast<Canvas*>(canvas);
        }

        static inline ACanvas* toACanvas(Canvas* canvas) {
            return reinterpret_cast<ACanvas *>(canvas);
        }

        static inline const Paint& toPaintRef(const APaint* paint) {
            return reinterpret_cast<const Paint&>(*paint);
        }

        static inline const Paint* toPaint(const APaint* paint) {
            return reinterpret_cast<const Paint*>(paint);
        }

        static inline Paint* toPaint(APaint* paint) {
            return reinterpret_cast<Paint*>(paint);
        }

        static inline APaint* toAPaint(Paint* paint) {
            return reinterpret_cast<APaint*>(paint);
        }
    };
}; // namespace android

#endif // ANDROID_GRAPHICS_TYPECAST_H
