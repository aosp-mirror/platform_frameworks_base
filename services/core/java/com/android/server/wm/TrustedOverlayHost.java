/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;

import java.util.ArrayList;

/**
 * Utility class to assist WindowContainer in the hosting of
 * SurfacePackage based overlays. Manages overlays inside
 * one parent control, and manages the lifetime of that parent control
 * in order to obscure details from WindowContainer.
 *
 * Also handles multiplexing of event dispatch and tracking of overlays
 * to make things easier for WindowContainer.
 *
 * These overlays are to be used for various types of System UI and UI
 * under the systems control. Provided SurfacePackages will be able
 * to overlay application content, without engaging the usual cross process
 * obscured touch filtering mechanisms. It's imperative that all UI provided
 * be under complete control of the system.
 */
class TrustedOverlayHost {
    // Lazily initialized when required
    SurfaceControl mSurfaceControl;
    final ArrayList<SurfaceControlViewHost.SurfacePackage> mOverlays = new ArrayList<>();
    final WindowManagerService mWmService;

    TrustedOverlayHost(WindowManagerService wms) {
        mWmService = wms;
    }

    void requireOverlaySurfaceControl() {
        if (mSurfaceControl == null) {
            final SurfaceControl.Builder b = mWmService.makeSurfaceBuilder(null)
                .setContainerLayer()
                .setHidden(true)
                .setName("Overlay Host Leash");

            mSurfaceControl = b.build();
            SurfaceControl.Transaction t = mWmService.mTransactionFactory.get();
            t.setTrustedOverlay(mSurfaceControl, true).apply();
        }
    }

    void setParent(SurfaceControl.Transaction t, SurfaceControl newParent) {
        if (mSurfaceControl == null) {
            return;
        }
        t.reparent(mSurfaceControl, newParent);
        if (newParent != null) {
            t.show(mSurfaceControl);
        } else {
            t.hide(mSurfaceControl);
        }
    }

    void setLayer(SurfaceControl.Transaction t, int layer) {
        if (mSurfaceControl != null) {
            t.setLayer(mSurfaceControl, layer);
        }
    }

    void setVisibility(SurfaceControl.Transaction t, boolean visible) {
        if (mSurfaceControl != null) {
            t.setVisibility(mSurfaceControl, visible);
        }
    }

    void addOverlay(SurfaceControlViewHost.SurfacePackage p, SurfaceControl currentParent) {
        requireOverlaySurfaceControl();

        boolean hasExistingOverlay = false;
        for (int i = mOverlays.size() - 1; i >= 0; i--) {
            SurfaceControlViewHost.SurfacePackage l = mOverlays.get(i);
            if (l.getSurfaceControl().isSameSurface(p.getSurfaceControl())) {
                hasExistingOverlay = true;
            }
        }
        if (!hasExistingOverlay) {
            mOverlays.add(p);
        }

        SurfaceControl.Transaction t = mWmService.mTransactionFactory.get();
        t.reparent(p.getSurfaceControl(), mSurfaceControl)
            .show(p.getSurfaceControl());
        setParent(t,currentParent);
        t.apply();
    }

    boolean removeOverlay(SurfaceControlViewHost.SurfacePackage p) {
        final SurfaceControl.Transaction t = mWmService.mTransactionFactory.get();

        for (int i = mOverlays.size() - 1; i >= 0; i--) {
           SurfaceControlViewHost.SurfacePackage l = mOverlays.get(i);
           if (l.getSurfaceControl().isSameSurface(p.getSurfaceControl())) {
               mOverlays.remove(i);
               t.reparent(l.getSurfaceControl(), null);
               l.release();
           }
        }
        t.apply();
        return mOverlays.size() > 0;
    }

    void dispatchConfigurationChanged(Configuration c) {
        for (int i = mOverlays.size() - 1; i >= 0; i--) {
           SurfaceControlViewHost.SurfacePackage l = mOverlays.get(i);
           try {
               l.getRemoteInterface().onConfigurationChanged(c);
           } catch (Exception e) {
               removeOverlay(l);
           }
        }
    }

    private void dispatchDetachedFromWindow() {
        for (int i = mOverlays.size() - 1; i >= 0; i--) {
            SurfaceControlViewHost.SurfacePackage l = mOverlays.get(i);
            try {
                l.getRemoteInterface().onDispatchDetachedFromWindow();
            } catch (Exception e) {
                // Oh well we are tearing down anyway.
            }
            l.release();
        }
    }

    void dispatchInsetsChanged(InsetsState s, Rect insetFrame) {
        for (int i = mOverlays.size() - 1; i >= 0; i--) {
            SurfaceControlViewHost.SurfacePackage l = mOverlays.get(i);
            try {
                l.getRemoteInterface().onInsetsChanged(s, insetFrame);
            } catch (Exception e) {
            }
        }
    }

    void release() {
        dispatchDetachedFromWindow();
        mOverlays.clear();
        final SurfaceControl.Transaction t = mWmService.mTransactionFactory.get();
        t.remove(mSurfaceControl).apply();
        mSurfaceControl = null;
    }
}
