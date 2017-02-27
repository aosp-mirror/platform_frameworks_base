/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef _ANDROID_GRAPHICS_FONT_UTILS_H_
#define _ANDROID_GRAPHICS_FONT_UTILS_H_

#include <jni.h>
#include <memory>

namespace minikin {
class FontFamily;
}  // namespace minikin

namespace android {

struct FontFamilyWrapper {
  FontFamilyWrapper(std::shared_ptr<minikin::FontFamily>&& family) : family(family) {}
  std::shared_ptr<minikin::FontFamily> family;
};

// Utility wrapper for java.util.List
class ListHelper {
public:
  ListHelper(JNIEnv* env, jobject list) : mEnv(env), mList(list) {}

  jint size() const;
  jobject get(jint index) const;

private:
  JNIEnv* mEnv;
  jobject mList;
};

// Utility wrapper for android.graphics.FontConfig$Axis
class AxisHelper {
public:
  AxisHelper(JNIEnv* env, jobject axis) : mEnv(env), mAxis(axis) {}

  jint getTag() const;
  jfloat getStyleValue() const;

private:
  JNIEnv* mEnv;
  jobject mAxis;
};

void init_FontUtils(JNIEnv* env);

}; // namespace android

#endif  // _ANDROID_GRAPHICS_FONT_UTILS_H_
