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
 * limitations under the License.
 */

package com.android.internal.app;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.UserHandle;

import com.android.internal.app.chooser.TargetInfo;

import java.util.List;
import java.util.function.Function;

/*
 * Simple wrapper around chooser activity to be able to initiate it under test
 */
public class ResolverWrapperActivity extends ResolverActivity {
    static final OverrideData sOverrides = new OverrideData();
    private UsageStatsManager mUsm;

    public ResolverWrapperActivity() {
        super(/* isIntentPicker= */ true);
    }

    @Override
    public ResolverListAdapter createResolverListAdapter(Context context,
            List<Intent> payloadIntents, Intent[] initialIntents, List<ResolveInfo> rList,
            boolean filterLastUsed, UserHandle userHandle) {
        return new ResolverWrapperAdapter(context, payloadIntents, initialIntents, rList,
                filterLastUsed, createListController(userHandle), this);
    }

    @Override
    protected AbstractMultiProfilePagerAdapter createMultiProfilePagerAdapter(
            Intent[] initialIntents, List<ResolveInfo> rList, boolean filterLastUsed) {
        AbstractMultiProfilePagerAdapter multiProfilePagerAdapter =
                super.createMultiProfilePagerAdapter(initialIntents, rList, filterLastUsed);
        multiProfilePagerAdapter.setInjector(sOverrides.multiPagerAdapterInjector);
        return multiProfilePagerAdapter;
    }

    ResolverWrapperAdapter getAdapter() {
        return (ResolverWrapperAdapter) mMultiProfilePagerAdapter.getActiveListAdapter();
    }

    ResolverListAdapter getPersonalListAdapter() {
        return ((ResolverListAdapter) mMultiProfilePagerAdapter.getAdapterForIndex(0));
    }

    ResolverListAdapter getWorkListAdapter() {
        if (mMultiProfilePagerAdapter.getInactiveListAdapter() == null) {
            return null;
        }
        return ((ResolverListAdapter) mMultiProfilePagerAdapter.getAdapterForIndex(1));
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
    protected ResolverListController createListController(UserHandle userHandle) {
        if (userHandle == UserHandle.SYSTEM) {
            when(sOverrides.resolverListController.getUserHandle()).thenReturn(UserHandle.SYSTEM);
            return sOverrides.resolverListController;
        }
        when(sOverrides.workResolverListController.getUserHandle()).thenReturn(userHandle);
        return sOverrides.workResolverListController;
    }

    @Override
    public PackageManager getPackageManager() {
        if (sOverrides.createPackageManager != null) {
            return sOverrides.createPackageManager.apply(super.getPackageManager());
        }
        return super.getPackageManager();
    }

    protected UserHandle getCurrentUserHandle() {
        return mMultiProfilePagerAdapter.getCurrentUserHandle();
    }

    @Override
    protected UserHandle getWorkProfileUserHandle() {
        return sOverrides.workProfileUserHandle;
    }

    @Override
    public void startActivityAsUser(Intent intent, Bundle options, UserHandle user) {
        super.startActivityAsUser(intent, options, user);
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
        public ResolverListController workResolverListController;
        public Boolean isVoiceInteraction;
        public UserHandle workProfileUserHandle;
        public boolean hasCrossProfileIntents;
        public boolean isQuietModeEnabled;
        public AbstractMultiProfilePagerAdapter.Injector multiPagerAdapterInjector;

        public void reset() {
            onSafelyStartCallback = null;
            isVoiceInteraction = null;
            createPackageManager = null;
            resolverListController = mock(ResolverListController.class);
            workResolverListController = mock(ResolverListController.class);
            workProfileUserHandle = null;
            hasCrossProfileIntents = true;
            isQuietModeEnabled = false;
            multiPagerAdapterInjector = new AbstractMultiProfilePagerAdapter.Injector() {
                @Override
                public boolean hasCrossProfileIntents(List<Intent> intents, int sourceUserId,
                        int targetUserId) {
                    return hasCrossProfileIntents;
                }

                @Override
                public boolean isQuietModeEnabled(UserHandle workProfileUserHandle) {
                    return isQuietModeEnabled;
                }

                @Override
                public void requestQuietModeEnabled(boolean enabled,
                        UserHandle workProfileUserHandle) {
                    isQuietModeEnabled = enabled;
                }
            };
        }
    }
}
