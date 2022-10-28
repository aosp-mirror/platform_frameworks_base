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

package com.android.systemui.wallpapers.gl;

import android.util.Size;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * A renderer which is responsible for making OpenGL calls to render a frame.
 */
public interface GLWallpaperRenderer {

    /**
     * Check if the content to render is a WCG content.
     */
    boolean isWcgContent();

    /**
     * Called when the surface is created or recreated.
     */
    void onSurfaceCreated();

    /**
     * Called when the surface changed size.
     * @param width surface width.
     * @param height surface height.
     */
    void onSurfaceChanged(int width, int height);

    /**
     * Called to draw the current frame.
     */
    void onDrawFrame();

    /**
     * Ask renderer to report the surface size it needs.
     */
    Size reportSurfaceSize();

    /**
     * Called when no need to render any more.
     */
    void finish();

    /**
     * Called to dump current state.
     * @param prefix prefix.
     * @param fd fd.
     * @param out out.
     * @param args args.
     */
    void dump(String prefix, FileDescriptor fd, PrintWriter out, String[] args);

}
