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

package com.android.server.testables;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.util.Pair;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;

import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.mockito.Mockito;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * TestableDeviceConfig uses ExtendedMockito to replace the real implementation of DeviceConfig
 * with essentially a local HashMap in the callers process. This allows for unit testing that do not
 * modify the real DeviceConfig on the device at all.
 *
 * <p>TestableDeviceConfig should be defined as a rule on your test so it can clean up after itself.
 * Like the following:</p>
 * <pre class="prettyprint">
 * &#064;Rule
 * public final TestableDeviceConfig mTestableDeviceConfig = new TestableDeviceConfig();
 * </pre>
 */
public final class TestableDeviceConfig implements TestRule {

    private StaticMockitoSession mMockitoSession;
    private Map<DeviceConfig.OnPropertyChangedListener, Pair<String, Executor>>
            mOnPropertyChangedListenerMap = new HashMap<>();
    private Map<DeviceConfig.OnPropertiesChangedListener, Pair<String, Executor>>
            mOnPropertiesChangedListenerMap = new HashMap<>();
    private Map<String, String> mKeyValueMap = new ConcurrentHashMap<>();

    /**
     * Clears out all local overrides.
     */
    public void clearDeviceConfig() {
        mKeyValueMap.clear();
    }

    @Override
    public Statement apply(Statement base, Description description) {
        mMockitoSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .spyStatic(DeviceConfig.class)
                .startMocking();

        doAnswer((Answer<Void>) invocationOnMock -> {
            String namespace = invocationOnMock.getArgument(0);
            Executor executor = invocationOnMock.getArgument(1);
            DeviceConfig.OnPropertiesChangedListener onPropertiesChangedListener =
                    invocationOnMock.getArgument(2);
            mOnPropertiesChangedListenerMap.put(
                    onPropertiesChangedListener, new Pair<>(namespace, executor));
            return null;
        }).when(() -> DeviceConfig.addOnPropertiesChangedListener(
                anyString(), any(Executor.class),
                any(DeviceConfig.OnPropertiesChangedListener.class)));

        doAnswer((Answer<Void>) invocationOnMock -> {
            String namespace = invocationOnMock.getArgument(0);
            Executor executor = invocationOnMock.getArgument(1);
            DeviceConfig.OnPropertyChangedListener onPropertyChangedListener =
                    invocationOnMock.getArgument(2);
            mOnPropertyChangedListenerMap.put(
                    onPropertyChangedListener, new Pair<>(namespace, executor));
            return null;
        }).when(() -> DeviceConfig.addOnPropertyChangedListener(
                anyString(), any(Executor.class),
                any(DeviceConfig.OnPropertyChangedListener.class)));

        doAnswer((Answer<Boolean>) invocationOnMock -> {
                    String namespace = invocationOnMock.getArgument(0);
                    String name = invocationOnMock.getArgument(1);
                    String value = invocationOnMock.getArgument(2);
                    mKeyValueMap.put(getKey(namespace, name), value);
                    for (DeviceConfig.OnPropertiesChangedListener listener :
                            mOnPropertiesChangedListenerMap.keySet()) {
                        if (namespace.equals(mOnPropertiesChangedListenerMap.get(listener).first)) {
                            mOnPropertiesChangedListenerMap.get(listener).second.execute(
                                    () -> listener.onPropertiesChanged(
                                            getProperties(namespace, name, value)));
                        }
                    }
                    for (DeviceConfig.OnPropertyChangedListener listener :
                            mOnPropertyChangedListenerMap.keySet()) {
                        if (namespace.equals(mOnPropertyChangedListenerMap.get(listener).first)) {
                            mOnPropertyChangedListenerMap.get(listener).second.execute(
                                    () -> listener.onPropertyChanged(namespace, name, value));
                        }
                    }
                    return true;
                }
        ).when(() -> DeviceConfig.setProperty(anyString(), anyString(), anyString(), anyBoolean()));

        doAnswer((Answer<String>) invocationOnMock -> {
            String namespace = invocationOnMock.getArgument(0);
            String name = invocationOnMock.getArgument(1);
            return mKeyValueMap.get(getKey(namespace, name));
        }).when(() -> DeviceConfig.getProperty(anyString(), anyString()));

        return new TestWatcher() {
            @Override
            protected void succeeded(Description description) {
                mMockitoSession.finishMocking();
                mOnPropertyChangedListenerMap.clear();
                mOnPropertiesChangedListenerMap.clear();
            }

            @Override
            protected void failed(Throwable e, Description description) {
                mMockitoSession.finishMocking(e);
                mOnPropertyChangedListenerMap.clear();
                mOnPropertiesChangedListenerMap.clear();
            }
        }.apply(base, description);
    }

    private static String getKey(String namespace, String name) {
        return namespace + "/" + name;
    }

    private Properties getProperties(String namespace, String name, String value) {
        Properties properties = Mockito.mock(Properties.class);
        when(properties.getNamespace()).thenReturn(namespace);
        when(properties.getKeyset()).thenReturn(Collections.singleton(name));
        when(properties.getBoolean(anyString(), anyBoolean())).thenAnswer(
                invocation -> {
                    String key = invocation.getArgument(0);
                    boolean defaultValue = invocation.getArgument(1);
                    if (name.equalsIgnoreCase(key) && value != null) {
                        return Boolean.parseBoolean(value);
                    } else {
                        return defaultValue;
                    }
                }
        );
        when(properties.getFloat(anyString(), anyFloat())).thenAnswer(
                invocation -> {
                    String key = invocation.getArgument(0);
                    float defaultValue = invocation.getArgument(1);
                    if (name.equalsIgnoreCase(key) && value != null) {
                        try {
                            return Float.parseFloat(value);
                        } catch (NumberFormatException e) {
                            return defaultValue;
                        }
                    } else {
                        return defaultValue;
                    }
                }
        );
        when(properties.getInt(anyString(), anyInt())).thenAnswer(
                invocation -> {
                    String key = invocation.getArgument(0);
                    int defaultValue = invocation.getArgument(1);
                    if (name.equalsIgnoreCase(key) && value != null) {
                        try {
                            return Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                            return defaultValue;
                        }
                    } else {
                        return defaultValue;
                    }
                }
        );
        when(properties.getLong(anyString(), anyLong())).thenAnswer(
                invocation -> {
                    String key = invocation.getArgument(0);
                    long defaultValue = invocation.getArgument(1);
                    if (name.equalsIgnoreCase(key) && value != null) {
                        try {
                            return Long.parseLong(value);
                        } catch (NumberFormatException e) {
                            return defaultValue;
                        }
                    } else {
                        return defaultValue;
                    }
                }
        );
        when(properties.getString(anyString(), anyString())).thenAnswer(
                invocation -> {
                    String key = invocation.getArgument(0);
                    String defaultValue = invocation.getArgument(1);
                    if (name.equalsIgnoreCase(key) && value != null) {
                        return value;
                    } else {
                        return defaultValue;
                    }
                }
        );

        return properties;
    }

}
