/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.wm;

import android.annotation.NonNull;
import android.graphics.Rect;
import android.view.SurfaceControl;

import com.android.internal.annotations.VisibleForTesting;
import com.android.window.flags.Flags;

/**
 * Utility class for use by a WindowContainer implementation to add "DimLayer" support, that is
 * black layers of varying opacity at various Z-levels which create the effect of a Dim.
 */
public abstract class Dimmer {
    /**
     * The {@link WindowContainer} that our Dims are bounded to. We may be dimming on behalf of the
     * host, some controller of it, or one of the hosts children.
     */
    protected final WindowContainer mHost;

    protected Dimmer(WindowContainer host) {
        mHost = host;
    }

    // Constructs the correct type of dimmer
    static Dimmer create(WindowContainer host) {
        return Flags.dimmerRefactor() ? new SmoothDimmer(host) : new LegacyDimmer(host);
    }

    @NonNull
    WindowContainer<?> getHost() {
        return mHost;
    }

    protected abstract void dim(
            WindowContainer container, int relativeLayer, float alpha, int blurRadius);

    /**
     * Place a dim above the given container, which should be a child of the host container.
     * for each call to {@link WindowContainer#prepareSurfaces} the Dim state will be reset
     * and the child should call dimAbove again to request the Dim to continue.
     *
     * @param container The container which to dim above. Should be a child of our host.
     * @param alpha     The alpha at which to Dim.
     */
    void dimAbove(@NonNull WindowContainer container, float alpha) {
        dim(container, 1, alpha, 0);
    }

    /**
     * Like {@link #dimAbove} but places the dim below the given container.
     *
     * @param container  The container which to dim below. Should be a child of our host.
     * @param alpha      The alpha at which to Dim.
     * @param blurRadius The amount of blur added to the Dim.
     */

    void dimBelow(@NonNull WindowContainer container, float alpha, int blurRadius) {
        dim(container, -1, alpha, blurRadius);
    }

    /**
     * Mark all dims as pending completion on the next call to {@link #updateDims}
     *
     * This is intended for us by the host container, to be called at the beginning of
     * {@link WindowContainer#prepareSurfaces}. After calling this, the container should
     * chain {@link WindowContainer#prepareSurfaces} down to it's children to give them
     * a chance to request dims to continue.
     */
    abstract void resetDimStates();

    /** Returns non-null bounds if the dimmer is showing. */
    abstract Rect getDimBounds();

    abstract void dontAnimateExit();

    @VisibleForTesting
    abstract SurfaceControl getDimLayer();

    /**
     * Call after invoking {@link WindowContainer#prepareSurfaces} on children as
     * described in {@link #resetDimStates}. The dim bounds returned by {@link #resetDimStates}
     * should be set before calling this method.
     *
     * @param t      A transaction in which to update the dims.
     * @return true if any Dims were updated.
     */
    abstract boolean updateDims(SurfaceControl.Transaction t);
}
