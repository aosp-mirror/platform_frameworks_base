/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.opengl;

import javax.microedition.khronos.opengles.GL;
import javax.microedition.khronos.opengles.GL10;
import javax.microedition.khronos.opengles.GL10Ext;
import javax.microedition.khronos.opengles.GL11;
import javax.microedition.khronos.opengles.GL11Ext;
import javax.microedition.khronos.opengles.GL11ExtensionPack;

/**
 * The abstract base class for a GL wrapper. Provides
 * some convenient instance variables and default implementations.
 */
abstract class GLWrapperBase
    implements GL, GL10, GL10Ext, GL11, GL11Ext, GL11ExtensionPack {
    public GLWrapperBase(GL gl) {
        mgl = (GL10) gl;
        if (gl instanceof GL10Ext) {
            mgl10Ext = (GL10Ext) gl;
        }
        if (gl instanceof GL11) {
            mgl11 = (GL11) gl;
        }
        if (gl instanceof GL11Ext) {
            mgl11Ext = (GL11Ext) gl;
        }
        if (gl instanceof GL11ExtensionPack) {
            mgl11ExtensionPack = (GL11ExtensionPack) gl;
        }
    }

    protected GL10 mgl;
    protected GL10Ext mgl10Ext;
    protected GL11 mgl11;
    protected GL11Ext mgl11Ext;
    protected GL11ExtensionPack mgl11ExtensionPack;
}
