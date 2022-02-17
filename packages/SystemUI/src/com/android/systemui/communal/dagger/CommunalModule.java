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
 * limitations under the License.
 */

package com.android.systemui.communal.dagger;

import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.android.systemui.R;
import com.android.systemui.communal.CommunalSource;
import com.android.systemui.communal.PackageObserver;
import com.android.systemui.communal.conditions.CommunalSettingCondition;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.idle.AmbientLightModeMonitor;
import com.android.systemui.idle.LightSensorEventsDebounceAlgorithm;
import com.android.systemui.idle.dagger.IdleViewComponent;
import com.android.systemui.util.condition.Condition;
import com.android.systemui.util.condition.Monitor;
import com.android.systemui.util.condition.dagger.MonitorComponent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.inject.Named;
import javax.inject.Provider;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.IntoMap;
import dagger.multibindings.StringKey;

/**
 * Dagger Module providing Communal-related functionality.
 */
@Module(subcomponents = {
        CommunalViewComponent.class,
        IdleViewComponent.class,
})
public interface CommunalModule {
    String IDLE_VIEW = "idle_view";
    String COMMUNAL_CONDITIONS = "communal_conditions";

    /** */
    @Provides
    @Named(IDLE_VIEW)
    static View provideIdleView(Context context) {
        FrameLayout view = new FrameLayout(context);
        return view;
    }

    /** */
    @Provides
    static Optional<CommunalSource.Observer> provideCommunalSourcePackageObserver(
            Context context, @Main Resources resources) {
        final String componentName = resources.getString(R.string.config_communalSourceComponent);

        if (TextUtils.isEmpty(componentName)) {
            return Optional.empty();
        }

        return Optional.of(new PackageObserver(context,
                ComponentName.unflattenFromString(componentName).getPackageName()));
    }

    /**
     * Provides LightSensorEventsDebounceAlgorithm as an instance to DebounceAlgorithm interface.
     * @param algorithm the instance of algorithm that is bound to the interface.
     * @return the interface that is bound to.
     */
    @Binds
    AmbientLightModeMonitor.DebounceAlgorithm ambientLightDebounceAlgorithm(
            LightSensorEventsDebounceAlgorithm algorithm);

    /**
     * Provides a set of conditions that need to be fulfilled in order for Communal Mode to display.
     */
    @Provides
    @ElementsIntoSet
    @Named(COMMUNAL_CONDITIONS)
    static Set<Condition> provideCommunalConditions(
            CommunalSettingCondition communalSettingCondition) {
        return new HashSet<>(Collections.singletonList(communalSettingCondition));
    }

    /**
     * TODO(b/205638389): Remove when there is a base implementation of
     * {@link CommunalSource.Connector}. Currently a place holder to allow a map to be present.
     */
    @Provides
    @IntoMap
    @Nullable
    @StringKey("empty")
    static CommunalSource.Connector provideEmptyCommunalSourceConnector() {
        return null;
    }

    /** */
    @Provides
    static Optional<CommunalSource.Connector> provideCommunalSourceConnector(
            @Main Resources resources,
            Map<Class<?>, Provider<CommunalSource.Connector>> connectorCreators) {
        final String className = resources.getString(R.string.config_communalSourceConnector);

        if (TextUtils.isEmpty(className)) {
            return Optional.empty();
        }

        try {
            Class<?> clazz = Class.forName(className);
            Provider<CommunalSource.Connector> provider = connectorCreators.get(clazz);
            return provider != null ? Optional.of(provider.get()) : Optional.empty();
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }

    /** */
    @Provides
    @Named(COMMUNAL_CONDITIONS)
    static Monitor provideCommunalSourceMonitor(
            @Named(COMMUNAL_CONDITIONS) Set<Condition> communalConditions,
            MonitorComponent.Factory factory) {
        final MonitorComponent component = factory.create(communalConditions, new HashSet<>());
        return component.getMonitor();
    }
}
