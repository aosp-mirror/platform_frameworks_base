/* libs/opengles/dxt.h
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

#ifndef ANDROID_OPENGLES_TEXTURE_H
#define ANDROID_OPENGLES_TEXTURE_H

#include <stdlib.h>

#include <GLES/gl.h>

namespace android {

  bool DXT1HasAlpha(const GLvoid *data, int width, int height);
  void decodeDXT(const GLvoid *data, int width, int height,
                 void *surface, int stride, int format);

} // namespace android

#endif // ANDROID_OPENGLES_TEXTURE_H
