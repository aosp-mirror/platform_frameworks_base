/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.webkit;

/**
 * This class provides more specific information about why the render process
 * exited. The application may use this to decide how to handle the situation.
 **/
public abstract class RenderProcessGoneDetail {
    /**
     * Indicates whether the render process was observed to crash, or whether
     * it was killed by the system.
     *
     * If the render process was killed, this is most likely caused by the
     * system being low on memory.
     *
     * @return True if render process crashed, otherwise it was killed by
     *         system.
     **/
    public abstract boolean didCrash();

    /**
     * Returns the renderer priority that was set at the time that the
     * renderer exited.  This may be greater than the priority that
     * any individual {@link WebView} requested using
     * {@link WebView#setRendererPriorityPolicy}.
     *
     * @return the priority of the renderer at exit.
     **/
    @WebView.RendererPriority
    public abstract int rendererPriorityAtExit();
}
