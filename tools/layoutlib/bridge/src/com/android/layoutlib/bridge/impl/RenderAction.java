/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.layoutlib.bridge.impl;

import static com.android.ide.common.rendering.api.Result.Status.ERROR_LOCK_INTERRUPTED;
import static com.android.ide.common.rendering.api.Result.Status.ERROR_TIMEOUT;
import static com.android.ide.common.rendering.api.Result.Status.SUCCESS;

import com.android.ide.common.rendering.api.HardwareConfig;
import com.android.ide.common.rendering.api.LayoutLog;
import com.android.ide.common.rendering.api.RenderParams;
import com.android.ide.common.rendering.api.RenderResources;
import com.android.ide.common.rendering.api.RenderResources.FrameworkResourceIdProvider;
import com.android.ide.common.rendering.api.Result;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.resources.Density;
import com.android.resources.ResourceType;
import com.android.resources.ScreenOrientation;
import com.android.resources.ScreenSize;

import android.content.res.Configuration;
import android.os.HandlerThread_Delegate;
import android.util.DisplayMetrics;
import android.view.ViewConfiguration_Accessor;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodManager_Accessor;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Base class for rendering action.
 *
 * It provides life-cycle methods to init and stop the rendering.
 * The most important methods are:
 * {@link #init(long)} and {@link #acquire(long)} to start a rendering and {@link #release()}
 * after the rendering.
 *
 *
 * @param <T> the {@link RenderParams} implementation
 *
 */
public abstract class RenderAction<T extends RenderParams> extends FrameworkResourceIdProvider {

    /**
     * The current context being rendered. This is set through {@link #acquire(long)} and
     * {@link #init(long)}, and unset in {@link #release()}.
     */
    private static BridgeContext sCurrentContext = null;

    private final T mParams;

    private BridgeContext mContext;

    /**
     * Creates a renderAction.
     * <p>
     * This <b>must</b> be followed by a call to {@link RenderAction#init(long)}, which act as a
     * call to {@link RenderAction#acquire(long)}
     *
     * @param params the RenderParams. This must be a copy that the action can keep
     *
     */
    protected RenderAction(T params) {
        mParams = params;
    }

    /**
     * Initializes and acquires the scene, creating various Android objects such as context,
     * inflater, and parser.
     *
     * @param timeout the time to wait if another rendering is happening.
     *
     * @return whether the scene was prepared
     *
     * @see #acquire(long)
     * @see #release()
     */
    public Result init(long timeout) {
        // acquire the lock. if the result is null, lock was just acquired, otherwise, return
        // the result.
        Result result = acquireLock(timeout);
        if (result != null) {
            return result;
        }

        HardwareConfig hardwareConfig = mParams.getHardwareConfig();

        // setup the display Metrics.
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.densityDpi = metrics.noncompatDensityDpi =
                hardwareConfig.getDensity().getDpiValue();

        metrics.density = metrics.noncompatDensity =
                metrics.densityDpi / (float) DisplayMetrics.DENSITY_DEFAULT;

        metrics.scaledDensity = metrics.noncompatScaledDensity = metrics.density;

        metrics.widthPixels = metrics.noncompatWidthPixels = hardwareConfig.getScreenWidth();
        metrics.heightPixels = metrics.noncompatHeightPixels = hardwareConfig.getScreenHeight();
        metrics.xdpi = metrics.noncompatXdpi = hardwareConfig.getXdpi();
        metrics.ydpi = metrics.noncompatYdpi = hardwareConfig.getYdpi();

        RenderResources resources = mParams.getResources();

        // build the context
        mContext = new BridgeContext(mParams.getProjectKey(), metrics, resources,
                mParams.getAssets(), mParams.getProjectCallback(), getConfiguration(),
                mParams.getTargetSdkVersion(), mParams.isRtlSupported());

        setUp();

        return SUCCESS.createResult();
    }


    /**
     * Prepares the scene for action.
     * <p>
     * This call is blocking if another rendering/inflating is currently happening, and will return
     * whether the preparation worked.
     *
     * The preparation can fail if another rendering took too long and the timeout was elapsed.
     *
     * More than one call to this from the same thread will have no effect and will return
     * {@link Result.Status#SUCCESS}.
     *
     * After scene actions have taken place, only one call to {@link #release()} must be
     * done.
     *
     * @param timeout the time to wait if another rendering is happening.
     *
     * @return whether the scene was prepared
     *
     * @see #release()
     *
     * @throws IllegalStateException if {@link #init(long)} was never called.
     */
    public Result acquire(long timeout) {
        if (mContext == null) {
            throw new IllegalStateException("After scene creation, #init() must be called");
        }

        // acquire the lock. if the result is null, lock was just acquired, otherwise, return
        // the result.
        Result result = acquireLock(timeout);
        if (result != null) {
            return result;
        }

        setUp();

        return SUCCESS.createResult();
    }

    /**
     * Acquire the lock so that the scene can be acted upon.
     * <p>
     * This returns null if the lock was just acquired, otherwise it returns
     * {@link Result.Status#SUCCESS} if the lock already belonged to that thread, or another
     * instance (see {@link Result#getStatus()}) if an error occurred.
     *
     * @param timeout the time to wait if another rendering is happening.
     * @return null if the lock was just acquire or another result depending on the state.
     *
     * @throws IllegalStateException if the current context is different than the one owned by
     *      the scene.
     */
    private Result acquireLock(long timeout) {
        ReentrantLock lock = Bridge.getLock();
        if (!lock.isHeldByCurrentThread()) {
            try {
                boolean acquired = lock.tryLock(timeout, TimeUnit.MILLISECONDS);

                if (!acquired) {
                    return ERROR_TIMEOUT.createResult();
                }
            } catch (InterruptedException e) {
                return ERROR_LOCK_INTERRUPTED.createResult();
            }
        } else {
            // This thread holds the lock already. Checks that this wasn't for a different context.
            // If this is called by init, mContext will be null and so should sCurrentContext
            // anyway
            if (mContext != sCurrentContext) {
                throw new IllegalStateException("Acquiring different scenes from same thread without releases");
            }
            return SUCCESS.createResult();
        }

        return null;
    }

    /**
     * Cleans up the scene after an action.
     */
    public void release() {
        ReentrantLock lock = Bridge.getLock();

        // with the use of finally blocks, it is possible to find ourself calling this
        // without a successful call to prepareScene. This test makes sure that unlock() will
        // not throw IllegalMonitorStateException.
        if (lock.isHeldByCurrentThread()) {
            tearDown();
            lock.unlock();
        }
    }

    /**
     * Sets up the session for rendering.
     * <p/>
     * The counterpart is {@link #tearDown()}.
     */
    private void setUp() {
        // make sure the Resources object references the context (and other objects) for this
        // scene
        mContext.initResources();
        sCurrentContext = mContext;

        // create an InputMethodManager
        InputMethodManager.getInstance();

        LayoutLog currentLog = mParams.getLog();
        Bridge.setLog(currentLog);
        mContext.getRenderResources().setFrameworkResourceIdProvider(this);
        mContext.getRenderResources().setLogger(currentLog);
    }

    /**
     * Tear down the session after rendering.
     * <p/>
     * The counterpart is {@link #setUp()}.
     */
    private void tearDown() {
        // The context may be null, if there was an error during init().
        if (mContext != null) {
            // Make sure to remove static references, otherwise we could not unload the lib
            mContext.disposeResources();
        }

        if (sCurrentContext != null) {
            // quit HandlerThread created during this session.
            HandlerThread_Delegate.cleanUp(sCurrentContext);
        }

        // clear the stored ViewConfiguration since the map is per density and not per context.
        ViewConfiguration_Accessor.clearConfigurations();

        // remove the InputMethodManager
        InputMethodManager_Accessor.resetInstance();

        sCurrentContext = null;

        Bridge.setLog(null);
        if (mContext != null) {
            mContext.getRenderResources().setFrameworkResourceIdProvider(null);
            mContext.getRenderResources().setLogger(null);
        }

    }

    public static BridgeContext getCurrentContext() {
        return sCurrentContext;
    }

    protected T getParams() {
        return mParams;
    }

    protected BridgeContext getContext() {
        return mContext;
    }

    /**
     * Returns the log associated with the session.
     * @return the log or null if there are none.
     */
    public LayoutLog getLog() {
        if (mParams != null) {
            return mParams.getLog();
        }

        return null;
    }

    /**
     * Checks that the lock is owned by the current thread and that the current context is the one
     * from this scene.
     *
     * @throws IllegalStateException if the current context is different than the one owned by
     *      the scene, or if {@link #acquire(long)} was not called.
     */
    protected void checkLock() {
        ReentrantLock lock = Bridge.getLock();
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("scene must be acquired first. see #acquire(long)");
        }
        if (sCurrentContext != mContext) {
            throw new IllegalStateException("Thread acquired a scene but is rendering a different one");
        }
    }

    private Configuration getConfiguration() {
        Configuration config = new Configuration();

        HardwareConfig hardwareConfig = mParams.getHardwareConfig();

        ScreenSize screenSize = hardwareConfig.getScreenSize();
        if (screenSize != null) {
            switch (screenSize) {
                case SMALL:
                    config.screenLayout |= Configuration.SCREENLAYOUT_SIZE_SMALL;
                    break;
                case NORMAL:
                    config.screenLayout |= Configuration.SCREENLAYOUT_SIZE_NORMAL;
                    break;
                case LARGE:
                    config.screenLayout |= Configuration.SCREENLAYOUT_SIZE_LARGE;
                    break;
                case XLARGE:
                    config.screenLayout |= Configuration.SCREENLAYOUT_SIZE_XLARGE;
                    break;
            }
        }

        Density density = hardwareConfig.getDensity();
        if (density == null) {
            density = Density.MEDIUM;
        }

        config.screenWidthDp = hardwareConfig.getScreenWidth() / density.getDpiValue();
        config.screenHeightDp = hardwareConfig.getScreenHeight() / density.getDpiValue();
        if (config.screenHeightDp < config.screenWidthDp) {
            //noinspection SuspiciousNameCombination
            config.smallestScreenWidthDp = config.screenHeightDp;
        } else {
            config.smallestScreenWidthDp = config.screenWidthDp;
        }
        config.densityDpi = density.getDpiValue();

        // never run in compat mode:
        config.compatScreenWidthDp = config.screenWidthDp;
        config.compatScreenHeightDp = config.screenHeightDp;

        ScreenOrientation orientation = hardwareConfig.getOrientation();
        if (orientation != null) {
            switch (orientation) {
            case PORTRAIT:
                config.orientation = Configuration.ORIENTATION_PORTRAIT;
                break;
            case LANDSCAPE:
                config.orientation = Configuration.ORIENTATION_LANDSCAPE;
                break;
            case SQUARE:
                //noinspection deprecation
                config.orientation = Configuration.ORIENTATION_SQUARE;
                break;
            }
        } else {
            config.orientation = Configuration.ORIENTATION_UNDEFINED;
        }

        // TODO: fill in more config info.

        return config;
    }


    // --- FrameworkResourceIdProvider methods

    @Override
    public Integer getId(ResourceType resType, String resName) {
        return Bridge.getResourceId(resType, resName);
    }
}
