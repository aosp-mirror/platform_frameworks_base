/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import android.util.ArrayMap;
import android.util.Slog;
import android.view.SurfaceControl;
import android.graphics.Rect;

/**
 * Utility class for use by a WindowContainer implementation to add "DimLayer" support, that is
 * black layers of varying opacity at various Z-levels which create the effect of a Dim.
 */
class Dimmer {
    private static final String TAG = "WindowManager";

    private class DimState {
        SurfaceControl mSurfaceControl;
        boolean mDimming;

        /**
         * Used for Dims not assosciated with a WindowContainer. See {@link Dimmer#dimAbove} for
         * details on Dim lifecycle.
         */
        boolean mDontReset;

        DimState(SurfaceControl ctl) {
            mSurfaceControl = ctl;
            mDimming = true;
        }
    };

    private ArrayMap<WindowContainer, DimState> mDimLayerUsers = new ArrayMap<>();

    /**
     * The {@link WindowContainer} that our Dim's are bounded to. We may be dimming on behalf of the
     * host, some controller of it, or one of the hosts children.
     */
    private WindowContainer mHost;

    Dimmer(WindowContainer host) {
        mHost = host;
    }

    SurfaceControl makeDimLayer() {
        final SurfaceControl control = mHost.makeChildSurface(null)
                .setParent(mHost.getSurfaceControl())
                .setColorLayer(true)
                .setName("Dim Layer for - " + mHost.getName())
                .build();
        return control;
    }

    /**
     * Retreive the DimState for a given child of the host.
     */
    DimState getDimState(WindowContainer container) {
        DimState state = mDimLayerUsers.get(container);
        if (state == null) {
            final SurfaceControl ctl = makeDimLayer();
            state = new DimState(ctl);
            /**
             * See documentation on {@link #dimAbove} to understand lifecycle management of Dim's
             * via state resetting for Dim's with containers.
             */
            if (container == null) {
                state.mDontReset = true;
            }
            mDimLayerUsers.put(container, state);
        }
        return state;
    }

    private void dim(SurfaceControl.Transaction t, WindowContainer container, int relativeLayer,
            float alpha) {
        final DimState d = getDimState(container);
        t.show(d.mSurfaceControl);
        if (container != null) {
            t.setRelativeLayer(d.mSurfaceControl,
                    container.getSurfaceControl(), relativeLayer);
        } else {
            t.setLayer(d.mSurfaceControl, Integer.MAX_VALUE);
        }
        t.setAlpha(d.mSurfaceControl, alpha);

        d.mDimming = true;
    }

    /**
     * Finish a dim started by dimAbove in the case there was no call to dimAbove.
     *
     * @param t A Transaction in which to finish the dim.
     */
    void stopDim(SurfaceControl.Transaction t) {
        DimState d = getDimState(null);
        t.hide(d.mSurfaceControl);
        d.mDontReset = false;
    }
    /**
     * Place a Dim above the entire host container. The caller is responsible for calling stopDim to
     * remove this effect. If the Dim can be assosciated with a particular child of the host
     * consider using the other variant of dimAbove which ties the Dim lifetime to the child
     * lifetime more explicitly.
     *
     * @param t A transaction in which to apply the Dim.
     * @param alpha The alpha at which to Dim.
     */
    void dimAbove(SurfaceControl.Transaction t, float alpha) {
        dim(t, null, 1, alpha);
    }

    /**
     * Place a dim above the given container, which should be a child of the host container.
     * for each call to {@link WindowContainer#prepareSurfaces} the Dim state will be reset
     * and the child should call dimAbove again to request the Dim to continue.
     *
     * @param t A transaction in which to apply the Dim.
     * @param container The container which to dim above. Should be a child of our host.
     * @param alpha The alpha at which to Dim.
     */
    void dimAbove(SurfaceControl.Transaction t, WindowContainer container, float alpha) {
        dim(t, container, 1, alpha);
    }

    /**
     * Like {@link #dimAbove} but places the dim below the given container.
     *
     * @param t A transaction in which to apply the Dim.
     * @param container The container which to dim below. Should be a child of our host.
     * @param alpha The alpha at which to Dim.
     */

    void dimBelow(SurfaceControl.Transaction t, WindowContainer container, float alpha) {
        dim(t, container, -1, alpha);
    }

    /**
     * Mark all dims as pending completion on the next call to {@link #updateDims}
     *
     * This is intended for us by the host container, to be called at the beginning of
     * {@link WindowContainer#prepareSurfaces}. After calling this, the container should
     * chain {@link WindowContainer#prepareSurfaces} down to it's children to give them
     * a chance to request dims to continue.
     */
    void resetDimStates() {
        for (int i = mDimLayerUsers.size() - 1; i >= 0; i--) {
            final DimState state = mDimLayerUsers.valueAt(i);
            if (state.mDontReset == false) {
                state.mDimming = false;
            }
        }
    }

    /**
     * Call after invoking {@link WindowContainer#prepareSurfaces} on children as
     * described in {@link #resetDimStates}.
     *
     * @param t A transaction in which to update the dims.
     * @param bounds The bounds at which to dim.
     * @return true if any Dims were updated.
     */
    boolean updateDims(SurfaceControl.Transaction t, Rect bounds) {
        boolean didSomething = false;
        for (int i = mDimLayerUsers.size() - 1; i >= 0; i--) {
            DimState state = mDimLayerUsers.valueAt(i);
            // TODO: We want to animate the addition and removal of Dim's instead of immediately
            // acting. When we do this we need to take care to account for the "Replacing Windows"
            // case (and seamless dim transfer).
            if (state.mDimming == false) {
                mDimLayerUsers.removeAt(i);
                state.mSurfaceControl.destroy();
            } else {
                didSomething = true;
                // TODO: Once we use geometry from hierarchy this falls away.
                t.setSize(state.mSurfaceControl, bounds.width(), bounds.height());
                t.setPosition(state.mSurfaceControl, bounds.left, bounds.top);
            }
        }
        return didSomething;
    }
}
