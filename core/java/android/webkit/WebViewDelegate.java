/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.annotation.SystemApi;
import android.app.ActivityThread;
import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.net.http.ErrorStrings;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.SparseArray;
import android.view.HardwareCanvas;
import android.view.View;
import android.view.ViewRootImpl;

/**
 * Delegate used by the WebView provider implementation to access
 * the required framework functionality needed to implement a {@link WebView}.
 *
 * @hide
 */
@SystemApi
public final class WebViewDelegate {

    /* package */ WebViewDelegate() { }

    /**
     * Listener that gets notified whenever tracing has been enabled/disabled.
     */
    public interface OnTraceEnabledChangeListener {
        void onTraceEnabledChange(boolean enabled);
    }

    /**
     * Register a callback to be invoked when tracing for the WebView component has been
     * enabled/disabled.
     */
    public void setOnTraceEnabledChangeListener(final OnTraceEnabledChangeListener listener) {
        SystemProperties.addChangeCallback(new Runnable() {
            @Override
            public void run() {
                listener.onTraceEnabledChange(isTraceTagEnabled());
            }
        });
    }

    /**
     * Returns true if the WebView trace tag is enabled and false otherwise.
     */
    public boolean isTraceTagEnabled() {
        return Trace.isTagEnabled(Trace.TRACE_TAG_WEBVIEW);
    }

    /**
     * Returns true if the draw GL functor can be invoked (see {@link #invokeDrawGlFunctor})
     * and false otherwise.
     */
    public boolean canInvokeDrawGlFunctor(View containerView) {
        ViewRootImpl viewRootImpl = containerView.getViewRootImpl();
         // viewRootImpl can be null during teardown when window is leaked.
        return viewRootImpl != null;
    }

    /**
     * Invokes the draw GL functor. If waitForCompletion is false the functor
     * may be invoked asynchronously.
     *
     * @param nativeDrawGLFunctor the pointer to the native functor that implements
     *        system/core/include/utils/Functor.h
     */
    public void invokeDrawGlFunctor(View containerView, long nativeDrawGLFunctor,
            boolean waitForCompletion) {
        ViewRootImpl viewRootImpl = containerView.getViewRootImpl();
        viewRootImpl.invokeFunctor(nativeDrawGLFunctor, waitForCompletion);
    }

    /**
     * Calls the function specified with the nativeDrawGLFunctor functor pointer. This
     * functionality is used by the WebView for calling into their renderer from the
     * framework display lists.
     *
     * @param canvas a hardware accelerated canvas (see {@link Canvas#isHardwareAccelerated()})
     * @param nativeDrawGLFunctor the pointer to the native functor that implements
     *        system/core/include/utils/Functor.h
     * @throws IllegalArgumentException if the canvas is not hardware accelerated
     */
    public void callDrawGlFunction(Canvas canvas, long nativeDrawGLFunctor) {
        if (!(canvas instanceof HardwareCanvas)) {
            // Canvas#isHardwareAccelerated() is only true for subclasses of HardwareCanvas.
            throw new IllegalArgumentException(canvas.getClass().getName()
                    + " is not hardware accelerated");
        }
        ((HardwareCanvas) canvas).callDrawGLFunction2(nativeDrawGLFunctor);
    }

    /**
     * Detaches the draw GL functor.
     *
     * @param nativeDrawGLFunctor the pointer to the native functor that implements
     *        system/core/include/utils/Functor.h
     */
    public void detachDrawGlFunctor(View containerView, long nativeDrawGLFunctor) {
        ViewRootImpl viewRootImpl = containerView.getViewRootImpl();
        if (nativeDrawGLFunctor != 0 && viewRootImpl != null) {
            viewRootImpl.detachFunctor(nativeDrawGLFunctor);
        }
    }

    /**
     * Returns the package id of the given {@code packageName}.
     */
    public int getPackageId(Resources resources, String packageName) {
        SparseArray<String> packageIdentifiers =
                resources.getAssets().getAssignedPackageIdentifiers();
        for (int i = 0; i < packageIdentifiers.size(); i++) {
            final String name = packageIdentifiers.valueAt(i);

            if (packageName.equals(name)) {
                return packageIdentifiers.keyAt(i);
            }
        }
        throw new RuntimeException("Package not found: " + packageName);
    }

    /**
     * Returns the application which is embedding the WebView.
     */
    public Application getApplication() {
        return ActivityThread.currentApplication();
    }

    /**
     * Returns the error string for the given {@code errorCode}.
     */
    public String getErrorString(Context context, int errorCode) {
        return ErrorStrings.getString(errorCode, context);
    }

    /**
     * Adds the WebView asset path to {@link AssetManager}.
     */
    public void addWebViewAssetPath(Context context) {
        context.getAssets().addAssetPath(
                WebViewFactory.getLoadedPackageInfo().applicationInfo.sourceDir);
    }
}
