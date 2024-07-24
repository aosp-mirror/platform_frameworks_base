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

    static final boolean DIMMER_REFACTOR = Flags.introduceSmootherDimmer();

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
        return DIMMER_REFACTOR ? new SmoothDimmer(host) : new LegacyDimmer(host);
    }

    @NonNull
    WindowContainer<?> getHost() {
        return mHost;
    }

    /**
     * Position the dim relatively to the dimming container.
     * Normally called together with #setAppearance, it can be called alone to keep the dim parented
     * to a visible container until the next dimming container is ready.
     * If multiple containers call this method, only the changes relative to the topmost will be
     * applied.
     *
     * For each call to {@link WindowContainer#prepareSurfaces()} the DimState will be reset, and
     * the child of the host should call adjustRelativeLayer and {@link Dimmer#adjustAppearance} to
     * continue dimming. Indeed, this method won't be able to keep dimming or get a new DimState
     * without also adjusting the appearance.
     * @param container      The container which to dim above. Should be a child of the host.
     * @param relativeLayer  The position of the dim wrt the container
     */
    protected abstract void adjustRelativeLayer(WindowContainer container, int relativeLayer);

    /**
     * Set the aspect of the dim layer, and request to keep dimming.
     * For each call to {@link WindowContainer#prepareSurfaces} the Dim state will be reset, and the
     * child should call setAppearance again to request the Dim to continue.
     * If multiple containers call this method, only the changes relative to the topmost will be
     * applied.
     * @param container  Container requesting the dim
     * @param alpha      Dim amount
     * @param blurRadius Blur amount
     */
    protected abstract void adjustAppearance(
            WindowContainer container, float alpha, int blurRadius);

    /**
     * Mark all dims as pending completion on the next call to {@link #updateDims}
     *
     * Called before iterating on mHost's children, first step of dimming.
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
     * described in {@link #resetDimStates}.
     *
     * @param t      A transaction in which to update the dims.
     * @return true if any Dims were updated.
     */
    abstract boolean updateDims(SurfaceControl.Transaction t);
}
