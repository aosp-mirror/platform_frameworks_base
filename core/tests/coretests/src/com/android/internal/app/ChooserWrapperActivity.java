/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.app;

import static org.mockito.Mockito.mock;

import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Size;

import java.util.function.Function;

public class ChooserWrapperActivity extends ChooserActivity {
    /*
     * Simple wrapper around chooser activity to be able to initiate it under test
     */
    static final OverrideData sOverrides = new OverrideData();
    private UsageStatsManager mUsm;

    ResolveListAdapter getAdapter() {
        return mAdapter;
    }

    boolean getIsSelected() { return mIsSuccessfullySelected; }

    UsageStatsManager getUsageStatsManager() {
        if (mUsm == null) {
            mUsm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        }
        return mUsm;
    }

    @Override
    public boolean isVoiceInteraction() {
        if (sOverrides.isVoiceInteraction != null) {
            return sOverrides.isVoiceInteraction;
        }
        return super.isVoiceInteraction();
    }

    @Override
    public void safelyStartActivity(TargetInfo cti) {
        if (sOverrides.onSafelyStartCallback != null &&
                sOverrides.onSafelyStartCallback.apply(cti)) {
            return;
        }
        super.safelyStartActivity(cti);
    }

    @Override
    protected ResolverListController createListController() {
        return sOverrides.resolverListController;
    }

    @Override
    public PackageManager getPackageManager() {
        if (sOverrides.createPackageManager != null) {
            return sOverrides.createPackageManager.apply(super.getPackageManager());
        }
        return super.getPackageManager();
    }

    @Override
    protected Bitmap loadThumbnail(Uri uri, Size size) {
        if (sOverrides.previewThumbnail != null) {
            return sOverrides.previewThumbnail;
        }
        return super.loadThumbnail(uri, size);
    }

    /**
     * We cannot directly mock the activity created since instrumentation creates it.
     * <p>
     * Instead, we use static instances of this object to modify behavior.
     */
    static class OverrideData {
        @SuppressWarnings("Since15")
        public Function<PackageManager, PackageManager> createPackageManager;
        public Function<TargetInfo, Boolean> onSafelyStartCallback;
        public ResolverListController resolverListController;
        public Boolean isVoiceInteraction;
        public Bitmap previewThumbnail;

        public void reset() {
            onSafelyStartCallback = null;
            isVoiceInteraction = null;
            createPackageManager = null;
            previewThumbnail = null;
            resolverListController = mock(ResolverListController.class);
        }
    }
}
