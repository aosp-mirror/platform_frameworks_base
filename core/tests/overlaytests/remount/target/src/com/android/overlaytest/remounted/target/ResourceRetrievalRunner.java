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

package com.android.overlaytest.remounted.target;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * An {@link Instrumentation} that retrieves the value of specified resources within the
 * application.
 **/
public class ResourceRetrievalRunner extends Instrumentation {
    private static final String TAG = ResourceRetrievalRunner.class.getSimpleName();

    // A list of whitespace separated resource names of which to retrieve the resource values.
    private static final String RESOURCE_LIST_TAG = "res";

    // A list of whitespace separated overlay package paths that must be present before retrieving
    // resource values.
    private static final String REQUIRED_OVERLAYS_LIST_TAG = "overlays";

    // The suffixes of the keys returned from the instrumentation. To retrieve the type of a
    // resource looked up with the instrumentation, append the {@link #RESOURCES_TYPE_SUFFIX} suffix
    // to the end of the name of the resource. For the value of a resource, use
    // {@link #RESOURCES_DATA_SUFFIX} instead.
    private static final String RESOURCES_TYPE_SUFFIX = "_type";
    private static final String RESOURCES_DATA_SUFFIX = "_data";

    // The amount of time in seconds to wait for the overlays to be present in the AssetManager.
    private static final int OVERLAY_PATH_TIMEOUT = 60;

    private final ArrayList<String> mResourceNames = new ArrayList<>();
    private final ArrayList<String> mOverlayPaths = new ArrayList<>();
    private final Bundle mResult = new Bundle();

    /**
     * Receives the instrumentation arguments and runs the resource retrieval.
     * The entry with key {@link #RESOURCE_LIST_TAG} in the {@link Bundle} arguments is a
     * whitespace separated string of resource names of which to retrieve the resource values.
     * The entry with key {@link #REQUIRED_OVERLAYS_LIST_TAG} in the {@link Bundle} arguments is a
     * whitespace separated string of overlay package paths prefixes that must be present before
     * retrieving the resource values.
     */
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        mResourceNames.addAll(Arrays.asList(arguments.getString(RESOURCE_LIST_TAG).split(" ")));
        if (arguments.containsKey(REQUIRED_OVERLAYS_LIST_TAG)) {
            mOverlayPaths.addAll(Arrays.asList(
                    arguments.getString(REQUIRED_OVERLAYS_LIST_TAG).split(" ")));
        }
        start();
    }

    @Override
    public void onStart() {
        final Resources res = getContext().getResources();
        res.getAssets().setResourceResolutionLoggingEnabled(true);

        if (!mOverlayPaths.isEmpty()) {
            Log.d(TAG, String.format("Waiting for overlay paths [%s]",
                    String.join(",", mOverlayPaths)));

            // Wait for all required overlays to be present in the AssetManager.
            final FutureTask<Boolean> overlayListener = new FutureTask<>(() -> {
                while (!mOverlayPaths.isEmpty()) {
                    final String[] apkPaths = res.getAssets().getApkPaths();
                    for (String path : apkPaths) {
                        for (String overlayPath : mOverlayPaths) {
                            if (path.startsWith(overlayPath)) {
                                mOverlayPaths.remove(overlayPath);
                                break;
                            }
                        }
                    }
                }
                return true;
            });

            try {
                final Executor executor = (t) -> new Thread(t).start();
                executor.execute(overlayListener);
                overlayListener.get(OVERLAY_PATH_TIMEOUT, TimeUnit.SECONDS);
            } catch (Exception e) {
                Log.e(TAG, String.format("Failed to wait for required overlays [%s]",
                        String.join(",", mOverlayPaths)), e);
                finish(Activity.RESULT_CANCELED, mResult);
            }
        }

        // Retrieve the values for each resource passed in.
        final TypedValue typedValue = new TypedValue();
        for (final String resourceName : mResourceNames) {
            try {
                final int resId = res.getIdentifier(resourceName, null, null);
                res.getValue(resId, typedValue, true);
                Log.d(TAG, String.format("Resolution for 0x%s: %s", Integer.toHexString(resId),
                        res.getAssets().getLastResourceResolution()));
            } catch (Resources.NotFoundException e) {
                Log.e(TAG, "Failed to retrieve value for resource " + resourceName, e);
                finish(Activity.RESULT_CANCELED, mResult);
            }

            putValue(resourceName, typedValue);
        }

        finish(Activity.RESULT_OK, mResult);
    }

    private void putValue(String resourceName, TypedValue value) {
        mResult.putInt(resourceName + RESOURCES_TYPE_SUFFIX, value.type);
        final CharSequence textValue = value.coerceToString();
        mResult.putString(resourceName + RESOURCES_DATA_SUFFIX,
                textValue == null ? "null" : textValue.toString());
    }
}
