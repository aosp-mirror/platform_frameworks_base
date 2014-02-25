/*
**
** Copyright 2012, The Android Open Source Project
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

package android.opengl;

/**
 * Wrapper class for native EGLConfig objects.
 *
 */
public class EGLConfig extends EGLObjectHandle {
    private EGLConfig(long handle) {
        super(handle);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EGLConfig)) return false;

        EGLConfig that = (EGLConfig) o;
        return getNativeHandle() == that.getNativeHandle();
    }
}
