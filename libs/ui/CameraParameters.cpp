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

#define LOG_TAG "CameraParams"
#include <utils/Log.h>

#include <string.h>
#include <stdlib.h>
#include <ui/CameraParameters.h>

namespace android {

static const char* portrait = "portrait";
static const char* landscape = "landscape";

CameraParameters::CameraParameters()
                : mMap()
{
}

CameraParameters::~CameraParameters()
{
}

String8 CameraParameters::flatten() const
{
    String8 flattened("");
    size_t size = mMap.size();

    for (size_t i = 0; i < size; i++) {
        String8 k, v;
        k = mMap.keyAt(i);
        v = mMap.valueAt(i);

        flattened += k;
        flattened += "=";
        flattened += v;
        if (i != size-1)
            flattened += ";";
    }

    return flattened;
}

void CameraParameters::unflatten(const String8 &params)
{
    const char *a = params.string();
    const char *b;

    mMap.clear();

    for (;;) {
        // Find the bounds of the key name.
        b = strchr(a, '=');
        if (b == 0)
            break;

        // Create the key string.
        String8 k(a, (size_t)(b-a));

        // Find the value.
        a = b+1;
        b = strchr(a, ';');
        if (b == 0) {
            // If there's no semicolon, this is the last item.
            String8 v(a);
            mMap.add(k, v);
            break;
        }

        String8 v(a, (size_t)(b-a));
        mMap.add(k, v);
        a = b+1;
    }
}


void CameraParameters::set(const char *key, const char *value)
{
    // XXX i think i can do this with strspn() 
    if (strchr(key, '=') || strchr(key, ';')) {
        //XXX LOGE("Key \"%s\"contains invalid character (= or ;)", key);
        return;
    }

    if (strchr(value, '=') || strchr(key, ';')) {
        //XXX LOGE("Value \"%s\"contains invalid character (= or ;)", value);
        return;
    }

    mMap.replaceValueFor(String8(key), String8(value));
}

void CameraParameters::set(const char *key, int value)
{
    char str[16];
    sprintf(str, "%d", value);
    set(key, str);
}

const char *CameraParameters::get(const char *key) const
{
    String8 v = mMap.valueFor(String8(key));
    if (v.length() == 0)
        return 0;
    return v.string();
}

int CameraParameters::getInt(const char *key) const
{
    const char *v = get(key);
    if (v == 0)
        return -1;
    return strtol(v, 0, 0);
}

static int parse_size(const char *str, int &width, int &height)
{
    // Find the width.
    char *end;
    int w = (int)strtol(str, &end, 10);
    // If an 'x' does not immediately follow, give up.
    if (*end != 'x')
        return -1;

    // Find the height, immediately after the 'x'.
    int h = (int)strtol(end+1, 0, 10);

    width = w;
    height = h;

    return 0;
}

void CameraParameters::setPreviewSize(int width, int height)
{
    char str[32];
    sprintf(str, "%dx%d", width, height);
    set("preview-size", str);
}

void CameraParameters::getPreviewSize(int *width, int *height) const
{
    *width = -1;
    *height = -1;

    // Get the current string, if it doesn't exist, leave the -1x-1
    const char *p = get("preview-size");
    if (p == 0)
        return;

    int w, h;
    if (parse_size(p, w, h) == 0) {
        *width = w;
        *height = h;
    }
}

void CameraParameters::setPreviewFrameRate(int fps)
{
    set("preview-frame-rate", fps);
}

int CameraParameters::getPreviewFrameRate() const
{
    return getInt("preview-frame-rate");
}

void CameraParameters::setPreviewFormat(const char *format)
{
    set("preview-format", format);
}

int CameraParameters::getOrientation() const
{
    const char* orientation = get("orientation");
    if (orientation && !strcmp(orientation, portrait))
        return CAMERA_ORIENTATION_PORTRAIT;
    return CAMERA_ORIENTATION_LANDSCAPE;
}

void CameraParameters::setOrientation(int orientation)
{
    if (orientation == CAMERA_ORIENTATION_PORTRAIT) {
        set("preview-format", portrait);
    } else {
        set("preview-format", landscape);
    }
}

const char *CameraParameters::getPreviewFormat() const
{
    return get("preview-format");
}

void CameraParameters::setPictureSize(int width, int height)
{
    char str[32];
    sprintf(str, "%dx%d", width, height);
    set("picture-size", str);
}

void CameraParameters::getPictureSize(int *width, int *height) const
{
    *width = -1;
    *height = -1;

    // Get the current string, if it doesn't exist, leave the -1x-1
    const char *p = get("picture-size");
    if (p == 0)
        return;

    int w, h;
    if (parse_size(p, w, h) == 0) {
        *width = w;
        *height = h;
    }
}

void CameraParameters::setPictureFormat(const char *format)
{
    set("picture-format", format);
}

const char *CameraParameters::getPictureFormat() const
{
    return get("picture-format");
}

void CameraParameters::dump() const
{
    LOGD("dump: mMap.size = %d", mMap.size());
    for (size_t i = 0; i < mMap.size(); i++) {
        String8 k, v;
        k = mMap.keyAt(i);
        v = mMap.valueAt(i);
        LOGD("%s: %s\n", k.string(), v.string());
    }
}

status_t CameraParameters::dump(int fd, const Vector<String16>& args) const
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    snprintf(buffer, 255, "CameraParameters::dump: mMap.size = %d\n", mMap.size());
    result.append(buffer);
    for (size_t i = 0; i < mMap.size(); i++) {
        String8 k, v;
        k = mMap.keyAt(i);
        v = mMap.valueAt(i);
        snprintf(buffer, 255, "\t%s: %s\n", k.string(), v.string());
        result.append(buffer);
    }
    write(fd, result.string(), result.size());
    return NO_ERROR;
}

}; // namespace android
