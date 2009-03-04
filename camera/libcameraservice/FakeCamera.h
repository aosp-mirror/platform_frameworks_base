/*
**
** Copyright 2008, The Android Open Source Project
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

#ifndef ANDROID_HARDWARE_FAKECAMERA_H
#define ANDROID_HARDWARE_FAKECAMERA_H

#include <ui/CameraHardwareInterface.h>

namespace android {

class FakeCamera {
public:
    FakeCamera(int width, int height);
    ~FakeCamera();

    void setSize(int width, int height);
    void getNextFrameAsRgb565(uint16_t *buffer);
    void getNextFrameAsYuv422(uint8_t *buffer);
    status_t dump(int fd, const Vector<String16>& args);

private:
    void drawSquare(uint16_t *buffer, int x, int y, int size, int color, int shadow);
    void drawCheckerboard(uint16_t *buffer, int size);

    static const int kRed = 0xf800;
    static const int kGreen = 0x07c0;
    static const int kBlue = 0x003e;

    int         mWidth, mHeight;
    int         mCounter;
    int         mCheckX, mCheckY;
    uint16_t    *mTmpRgb16Buffer;
};

}; // namespace android

#endif // ANDROID_HARDWARE_FAKECAMERA_H
