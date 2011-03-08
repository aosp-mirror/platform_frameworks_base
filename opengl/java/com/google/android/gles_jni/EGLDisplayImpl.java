/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.google.android.gles_jni;

import javax.microedition.khronos.egl.*;

public class EGLDisplayImpl extends EGLDisplay {
    int mEGLDisplay;

    public EGLDisplayImpl(int dpy) {
        mEGLDisplay = dpy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        EGLDisplayImpl that = (EGLDisplayImpl) o;

        return mEGLDisplay == that.mEGLDisplay;

    }

    @Override
    public int hashCode() {
        return mEGLDisplay;
    }
}
