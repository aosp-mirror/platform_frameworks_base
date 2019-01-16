/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.view.textclassifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import androidx.test.InstrumentationRegistry;

import com.google.common.base.Preconditions;

import org.mockito.stubbing.Answer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A builder used to build a fake context for testing.
 */
// TODO: Consider making public.
final class FakeContextBuilder {

    /**
     * A component name that can be used for tests.
     */
    public static final ComponentName DEFAULT_COMPONENT = new ComponentName("pkg", "cls");

    private final PackageManager mPackageManager;
    private final ContextWrapper mContext;
    private final Map<String, ComponentName> mComponents = new HashMap<>();
    private @Nullable ComponentName mAllIntentComponent;

    FakeContextBuilder() {
        mPackageManager = mock(PackageManager.class);
        when(mPackageManager.resolveActivity(any(Intent.class), anyInt())).thenReturn(null);
        mContext = new ContextWrapper(InstrumentationRegistry.getTargetContext()) {
            @Override
            public PackageManager getPackageManager() {
                return mPackageManager;
            }
        };
    }

    /**
     * Sets the component name of an activity to handle the specified intent action.
     * <p>
     * <strong>NOTE: </strong>By default, no component is set to handle any intent.
     */
    public FakeContextBuilder setIntentComponent(
            String intentAction, @Nullable ComponentName component) {
        Preconditions.checkNotNull(intentAction);
        mComponents.put(intentAction, component);
        return this;
    }


    /**
     * Sets the component name of an activity to handle all intents.
     * <p>
     * <strong>NOTE: </strong>By default, no component is set to handle any intent.
     */
    public FakeContextBuilder setAllIntentComponent(@Nullable ComponentName component) {
        mAllIntentComponent = component;
        return this;
    }

    /**
     * Builds and returns a fake context.
     */
    public Context build() {
        when(mPackageManager.resolveActivity(any(Intent.class), anyInt())).thenAnswer(
                (Answer<ResolveInfo>) invocation -> {
                    final String action = ((Intent) invocation.getArgument(0)).getAction();
                    final ComponentName component = mComponents.containsKey(action)
                            ? mComponents.get(action)
                            : mAllIntentComponent;
                    return getResolveInfo(component);
                });
        return mContext;
    }

    /**
     * Returns a component name with random package and class names.
     */
    public static ComponentName newComponent() {
        return new ComponentName(UUID.randomUUID().toString(), UUID.randomUUID().toString());
    }

    private static ResolveInfo getResolveInfo(ComponentName component) {
        final ResolveInfo info;
        if (component == null) {
            info = null;
        } else {
            // NOTE: If something breaks in TextClassifier because we expect more fields to be set
            // in here, just add them.
            info = new ResolveInfo();
            info.activityInfo = new ActivityInfo();
            info.activityInfo.packageName = component.getPackageName();
            info.activityInfo.name = component.getClassName();
            info.activityInfo.exported = true;
            info.activityInfo.applicationInfo = new ApplicationInfo();
            info.activityInfo.applicationInfo.icon = 0;
        }
        return info;
    }

}
