Copyright 2013 The Android Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.


Smart Camera / Auto Snapshot (formerly named SimpleCamera) ReadMe

Created by: Benjamin W Hendricks

How to build the application:
From root: make SmartCamera will build the apk for generic
Otherwise, to build the application for a specific device, lunch to that device
and then run mm while in the SimpleCamera directory.
Then take the given Install path (out/target/.../SmartCamera.apk)
and run adb install out/target/.../SmartCamera.apk. The application should
then appear in the launcher of your device.
You might also need to run adb sync after building to sync the
libsmartcamera_jni library
Summarized:
    make SmartCamera
    adb remount
    adb sync
    adb install -r $ANDROID_PRODUCT_OUT/data/app/SmartCamera.apk

How to run the application:
On a Nexus 7, open up the application from the launcher, and the camera preview
should appear. From there, you can go to the gallery with the gallery button or
press start to start capturing images. You can also change the number of images
to be captured by changing the number on the spinner (between 1-10).

What does it do:
The application tries to take good pictures for you automatically when in the
start mode. On stop, the application will capture whatever images are in the
bottom preview and save them to the Gallery. It does this by looking at the
following image features:
    - Sharpness
    - Brightness
    - Motion of the device
    - Colorfulness
    - Contrast
    - Exposure (over/under)

By comparing each of these features frame by frame, a score is calculated to
determine whether an image is better or worse than the previous few frames,
and from that score I can determine the great images from the bad ones.

What libraries does it use:
- Mobile Filter Framework (MFF)
- Camera2 API
- Renderscript
