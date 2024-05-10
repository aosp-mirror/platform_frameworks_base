/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.provider;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.ContentProvider;
import android.content.IContentProvider;
import android.os.Bundle;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.test.mock.MockContentResolver;
import android.util.MemoryIntArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * These tests are verifying that Settings#NameValueCache is working as expected with DeviceConfig.
 * Due to how the classes are structured, we have to test it in a somewhat roundabout way. We're
 * mocking out the contentProvider and are handcrafting very specific Bundles to answer the queries.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class NameValueCacheTest {

    private static final String NAMESPACE = "namespace";
    private static final String NAMESPACE2 = "namespace2";

    private static final String SETTING = "test_setting";
    private static final String SETTING2 = "test_setting2";

    @Mock
    private IContentProvider mMockIContentProvider;
    @Mock
    private ContentProvider mMockContentProvider;
    private MockContentResolver mMockContentResolver;
    private MemoryIntArray mConfigsCacheGenerationStore;
    private MemoryIntArray mSettingsCacheGenerationStore;

    private HashMap<String, HashMap<String, String>> mConfigsStorage;
    private HashMap<String, String> mSettingsStorage;


    @Before
    public void setUp() throws Exception {
        Settings.Config.clearProviderForTest();
        Settings.Secure.clearProviderForTest();
        MockitoAnnotations.initMocks(this);
        when(mMockContentProvider.getIContentProvider()).thenReturn(mMockIContentProvider);
        mMockContentResolver = new MockContentResolver(
                InstrumentationRegistry.getInstrumentation().getContext());
        mMockContentResolver.addProvider(Settings.Config.CONTENT_URI.getAuthority(),
                mMockContentProvider);
        mMockContentResolver.addProvider(Settings.Secure.CONTENT_URI.getAuthority(),
                mMockContentProvider);
        mConfigsCacheGenerationStore = new MemoryIntArray(2);
        mConfigsCacheGenerationStore.set(0, 123);
        mConfigsCacheGenerationStore.set(1, 456);
        mSettingsCacheGenerationStore = new MemoryIntArray(3);
        mSettingsCacheGenerationStore.set(0, 234);
        mSettingsCacheGenerationStore.set(1, 567);
        mConfigsStorage = new HashMap<>();
        mSettingsStorage = new HashMap<>();

        // Stores keyValues for a given prefix and increments the generation. (Note that this
        // increments the generation no matter what, it doesn't pay attention to if anything
        // actually changed).
        when(mMockIContentProvider.call(any(), eq(Settings.Config.CONTENT_URI.getAuthority()),
                eq(Settings.CALL_METHOD_SET_ALL_CONFIG), any(), any(Bundle.class))).thenAnswer(
                invocationOnMock -> {
                    Bundle incomingBundle = invocationOnMock.getArgument(4);
                    HashMap<String, String> keyValues =
                            (HashMap<String, String>) incomingBundle.getSerializable(
                                    Settings.CALL_METHOD_FLAGS_KEY, HashMap.class);
                    String prefix = incomingBundle.getString(Settings.CALL_METHOD_PREFIX_KEY);
                    mConfigsStorage.put(prefix, keyValues);
                    int currentGeneration;
                    // Different prefixes have different generation codes
                    if (prefix.equals(NAMESPACE + "/")) {
                        currentGeneration = mConfigsCacheGenerationStore.get(0);
                        mConfigsCacheGenerationStore.set(0, ++currentGeneration);
                    } else if (prefix.equals(NAMESPACE2 + "/")) {
                        currentGeneration = mConfigsCacheGenerationStore.get(1);
                        mConfigsCacheGenerationStore.set(1, ++currentGeneration);
                    }
                    Bundle result = new Bundle();
                    result.putInt(Settings.KEY_CONFIG_SET_ALL_RETURN,
                            Settings.SET_ALL_RESULT_SUCCESS);
                    return result;
                });

        // Returns the keyValues corresponding to a namespace, or an empty map if the namespace
        // doesn't have anything stored for it. Returns the generation key if the map isn't empty
        // and the caller asked for the generation key.
        when(mMockIContentProvider.call(any(), eq(Settings.Config.CONTENT_URI.getAuthority()),
                eq(Settings.CALL_METHOD_LIST_CONFIG), any(), any(Bundle.class))).thenAnswer(
                invocationOnMock -> {
                    Bundle incomingBundle = invocationOnMock.getArgument(4);

                    String prefix = incomingBundle.getString(Settings.CALL_METHOD_PREFIX_KEY);

                    if (!mConfigsStorage.containsKey(prefix)) {
                        mConfigsStorage.put(prefix, new HashMap<>());
                    }
                    HashMap<String, String> keyValues = mConfigsStorage.get(prefix);

                    Bundle bundle = new Bundle();
                    bundle.putSerializable(Settings.NameValueTable.VALUE, keyValues);

                    if (!keyValues.isEmpty() && incomingBundle.containsKey(
                            Settings.CALL_METHOD_TRACK_GENERATION_KEY)) {
                        int index = prefix.equals(NAMESPACE + "/") ? 0 : 1;
                        bundle.putParcelable(Settings.CALL_METHOD_TRACK_GENERATION_KEY,
                                mConfigsCacheGenerationStore);
                        bundle.putInt(Settings.CALL_METHOD_GENERATION_INDEX_KEY, index);
                        bundle.putInt(Settings.CALL_METHOD_GENERATION_KEY,
                                mConfigsCacheGenerationStore.get(index));
                    }
                    return bundle;
                });

        // Stores value for a given setting's name and increments the generation. (Note that this
        // increments the generation no matter what, it doesn't pay attention to if anything
        // actually changed).
        when(mMockIContentProvider.call(any(), eq(Settings.Secure.CONTENT_URI.getAuthority()),
                eq(Settings.CALL_METHOD_PUT_SECURE), any(), any(Bundle.class))).thenAnswer(
                invocationOnMock -> {
                    Bundle incomingBundle = invocationOnMock.getArgument(4);
                    String key = invocationOnMock.getArgument(3);
                    String value = incomingBundle.getString(Settings.NameValueTable.VALUE);
                    boolean newSetting = false;
                    if (!mSettingsStorage.containsKey(key)) {
                        newSetting = true;
                    }
                    mSettingsStorage.put(key, value);
                    int currentGeneration;
                    // Different settings have different generation codes
                    if (key.equals(SETTING)) {
                        currentGeneration = mSettingsCacheGenerationStore.get(0);
                        mSettingsCacheGenerationStore.set(0, ++currentGeneration);
                    } else if (key.equals(SETTING2)) {
                        currentGeneration = mSettingsCacheGenerationStore.get(1);
                        mSettingsCacheGenerationStore.set(1, ++currentGeneration);
                    }
                    if (newSetting) {
                        // Tracking the generation of all unset settings.
                        // Increment when a new setting is inserted
                        currentGeneration = mSettingsCacheGenerationStore.get(2);
                        mSettingsCacheGenerationStore.set(2, ++currentGeneration);
                    }
                    return null;
                });

        // Returns the value corresponding to a setting, or null if the setting
        // doesn't have a value stored for it. Returns the generation key
        // if the caller asked for the generation key.
        when(mMockIContentProvider.call(any(), eq(Settings.Secure.CONTENT_URI.getAuthority()),
                eq(Settings.CALL_METHOD_GET_SECURE), any(), any(Bundle.class))).thenAnswer(
                invocationOnMock -> {
                    Bundle incomingBundle = invocationOnMock.getArgument(4);
                    String key = invocationOnMock.getArgument(3);
                    String value = mSettingsStorage.get(key);

                    Bundle bundle = new Bundle();
                    bundle.putSerializable(Settings.NameValueTable.VALUE, value);

                    if (incomingBundle.containsKey(
                            Settings.CALL_METHOD_TRACK_GENERATION_KEY)) {
                        int index;
                        if (value != null) {
                            index = key.equals(SETTING) ? 0 : 1;
                        } else {
                            // special index for unset settings
                            index = 2;
                        }
                        // Manually make a copy of the memory int array to mimic sending it over IPC
                        Parcel p = Parcel.obtain();
                        mSettingsCacheGenerationStore.writeToParcel(p, 0);
                        p.setDataPosition(0);
                        MemoryIntArray parcelArray = MemoryIntArray.CREATOR.createFromParcel(p);
                        bundle.putParcelable(Settings.CALL_METHOD_TRACK_GENERATION_KEY,
                                parcelArray);
                        bundle.putInt(Settings.CALL_METHOD_GENERATION_INDEX_KEY, index);
                        bundle.putInt(Settings.CALL_METHOD_GENERATION_KEY,
                                mSettingsCacheGenerationStore.get(index));
                        p.recycle();
                    }
                    return bundle;
                });
    }

    @After
    public void cleanUp() throws IOException {
        Settings.Config.clearProviderForTest();
        Settings.Secure.clearProviderForTest();
        mConfigsStorage.clear();
        mSettingsStorage.clear();
        mSettingsCacheGenerationStore.close();
        mConfigsCacheGenerationStore.close();
    }

    @Ignore("b/297724333")
    @Test
    public void testCaching_singleNamespace() throws Exception {
        HashMap<String, String> keyValues = new HashMap<>();
        keyValues.put("a", "b");
        Settings.Config.setStrings(mMockContentResolver, NAMESPACE, keyValues);
        verify(mMockIContentProvider, times(1)).call(any(), any(),
                eq(Settings.CALL_METHOD_SET_ALL_CONFIG), any(), any(Bundle.class));

        Map<String, String> returnedValues = Settings.Config.getStrings(mMockContentResolver,
                NAMESPACE, Collections.emptyList());
        verify(mMockIContentProvider, times(1)).call(any(), any(),
                eq(Settings.CALL_METHOD_LIST_CONFIG), any(), any(Bundle.class));
        assertThat(returnedValues).containsExactlyEntriesIn(keyValues);

        Map<String, String> cachedKeyValues = Settings.Config.getStrings(mMockContentResolver,
                NAMESPACE, Collections.emptyList());
        verifyNoMoreInteractions(mMockIContentProvider);
        assertThat(cachedKeyValues).containsExactlyEntriesIn(keyValues);

        // Modify the value to invalidate the cache.
        keyValues.put("a", "c");
        Settings.Config.setStrings(mMockContentResolver, NAMESPACE, keyValues);
        verify(mMockIContentProvider, times(2)).call(any(), any(),
                eq(Settings.CALL_METHOD_SET_ALL_CONFIG), any(), any(Bundle.class));

        Map<String, String> returnedValues2 = Settings.Config.getStrings(mMockContentResolver,
                NAMESPACE, Collections.emptyList());
        verify(mMockIContentProvider, times(2)).call(any(), any(),
                eq(Settings.CALL_METHOD_LIST_CONFIG), any(), any(Bundle.class));
        assertThat(returnedValues2).containsExactlyEntriesIn(keyValues);

        Map<String, String> cachedKeyValues2 = Settings.Config.getStrings(mMockContentResolver,
                NAMESPACE, Collections.emptyList());
        verifyNoMoreInteractions(mMockIContentProvider);
        assertThat(cachedKeyValues2).containsExactlyEntriesIn(keyValues);
    }

    @Ignore("b/297724333")
    @Test
    public void testCaching_multipleNamespaces() throws Exception {
        HashMap<String, String> keyValues = new HashMap<>();
        keyValues.put("a", "b");
        Settings.Config.setStrings(mMockContentResolver, NAMESPACE, keyValues);
        verify(mMockIContentProvider).call(any(), any(), eq(Settings.CALL_METHOD_SET_ALL_CONFIG),
                any(), any(Bundle.class));

        Map<String, String> returnedValues = Settings.Config.getStrings(mMockContentResolver,
                NAMESPACE, Collections.emptyList());
        verify(mMockIContentProvider).call(any(), any(), eq(Settings.CALL_METHOD_LIST_CONFIG),
                any(), any(Bundle.class));
        assertThat(returnedValues).containsExactlyEntriesIn(keyValues);

        HashMap<String, String> keyValues2 = new HashMap<>();
        keyValues2.put("c", "d");
        keyValues2.put("e", "f");
        Settings.Config.setStrings(mMockContentResolver, NAMESPACE2, keyValues2);
        verify(mMockIContentProvider, times(2)).call(any(), any(),
                eq(Settings.CALL_METHOD_SET_ALL_CONFIG), any(), any(Bundle.class));

        Map<String, String> returnedValues2 = Settings.Config.getStrings(mMockContentResolver,
                NAMESPACE2, Collections.emptyList());
        verify(mMockIContentProvider, times(2)).call(any(), any(),
                eq(Settings.CALL_METHOD_LIST_CONFIG), any(), any(Bundle.class));
        assertThat(returnedValues2).containsExactlyEntriesIn(keyValues2);

        Map<String, String> cachedKeyValues = Settings.Config.getStrings(mMockContentResolver,
                NAMESPACE, Collections.emptyList());
        // Modifying the second namespace doesn't affect the cache of the first namespace
        verifyNoMoreInteractions(mMockIContentProvider);
        assertThat(cachedKeyValues).containsExactlyEntriesIn(keyValues);

        Map<String, String> cachedKeyValues2 = Settings.Config.getStrings(mMockContentResolver,
                NAMESPACE2, Collections.emptyList());
        verifyNoMoreInteractions(mMockIContentProvider);
        assertThat(cachedKeyValues2).containsExactlyEntriesIn(keyValues2);
    }

    @Ignore("b/297724333")
    @Test
    public void testCaching_emptyNamespace() throws Exception {
        Map<String, String> returnedValues = Settings.Config.getStrings(mMockContentResolver,
                NAMESPACE, Collections.emptyList());
        verify(mMockIContentProvider, times(1)).call(any(), any(),
                eq(Settings.CALL_METHOD_LIST_CONFIG), any(), any(Bundle.class));
        assertThat(returnedValues).isEmpty();

        Map<String, String> cachedKeyValues = Settings.Config.getStrings(mMockContentResolver,
                NAMESPACE, Collections.emptyList());
        // Empty list won't be cached
        verify(mMockIContentProvider, times(2)).call(any(), any(),
                eq(Settings.CALL_METHOD_LIST_CONFIG), any(), any(Bundle.class));
        assertThat(cachedKeyValues).isEmpty();
    }

    @Ignore("b/297724333")
    @Test
    public void testCaching_singleSetting() throws Exception {
        Settings.Secure.putString(mMockContentResolver, SETTING, "a");
        verify(mMockIContentProvider, times(1)).call(any(), any(),
                eq(Settings.CALL_METHOD_PUT_SECURE), any(), any(Bundle.class));

        String returnedValue = Settings.Secure.getString(mMockContentResolver, SETTING);
        verify(mMockIContentProvider, times(1)).call(any(), any(),
                eq(Settings.CALL_METHOD_GET_SECURE), any(), any(Bundle.class));
        assertThat(returnedValue).isEqualTo("a");

        String cachedValue = Settings.Secure.getString(mMockContentResolver, SETTING);
        verifyNoMoreInteractions(mMockIContentProvider);
        assertThat(cachedValue).isEqualTo("a");

        // Modify the value to invalidate the cache.
        Settings.Secure.putString(mMockContentResolver, SETTING, "b");
        verify(mMockIContentProvider, times(2)).call(any(), any(),
                eq(Settings.CALL_METHOD_PUT_SECURE), any(), any(Bundle.class));

        String returnedValue2 = Settings.Secure.getString(mMockContentResolver, SETTING);
        verify(mMockIContentProvider, times(2)).call(any(), any(),
                eq(Settings.CALL_METHOD_GET_SECURE), any(), any(Bundle.class));
        assertThat(returnedValue2).isEqualTo("b");

        String cachedValue2 = Settings.Secure.getString(mMockContentResolver, SETTING);
        verifyNoMoreInteractions(mMockIContentProvider);
        assertThat(cachedValue2).isEqualTo("b");
    }

    @Ignore("b/297724333")
    @Test
    public void testCaching_multipleSettings() throws Exception {
        Settings.Secure.putString(mMockContentResolver, SETTING, "a");
        verify(mMockIContentProvider, times(1)).call(any(), any(),
                eq(Settings.CALL_METHOD_PUT_SECURE), any(), any(Bundle.class));

        String returnedValue = Settings.Secure.getString(mMockContentResolver, SETTING);
        verify(mMockIContentProvider, times(1)).call(any(), any(),
                eq(Settings.CALL_METHOD_GET_SECURE), any(), any(Bundle.class));
        assertThat(returnedValue).isEqualTo("a");

        Settings.Secure.putString(mMockContentResolver, SETTING2, "b");
        verify(mMockIContentProvider, times(2)).call(any(), any(),
                eq(Settings.CALL_METHOD_PUT_SECURE), any(), any(Bundle.class));

        String returnedValue2 = Settings.Secure.getString(mMockContentResolver, SETTING2);
        verify(mMockIContentProvider, times(2)).call(any(), any(),
                eq(Settings.CALL_METHOD_GET_SECURE), any(), any(Bundle.class));
        assertThat(returnedValue2).isEqualTo("b");

        String cachedValue = Settings.Secure.getString(mMockContentResolver, SETTING);
        // Modifying the second setting doesn't affect the cache of the first setting
        verifyNoMoreInteractions(mMockIContentProvider);
        assertThat(cachedValue).isEqualTo("a");

        String cachedValue2 = Settings.Secure.getString(mMockContentResolver, SETTING2);
        verifyNoMoreInteractions(mMockIContentProvider);
        assertThat(cachedValue2).isEqualTo("b");
    }

    @Ignore("b/297724333")
    @Test
    public void testCaching_unsetSetting() throws Exception {
        String returnedValue = Settings.Secure.getString(mMockContentResolver, SETTING);
        verify(mMockIContentProvider, times(1)).call(any(), any(),
                eq(Settings.CALL_METHOD_GET_SECURE), any(), any(Bundle.class));
        assertThat(returnedValue).isNull();

        String cachedValue = Settings.Secure.getString(mMockContentResolver, SETTING);
        // The first unset setting's generation number is cached
        verifyNoMoreInteractions(mMockIContentProvider);
        assertThat(cachedValue).isNull();

        String returnedValue2 = Settings.Secure.getString(mMockContentResolver, SETTING2);
        verify(mMockIContentProvider, times(2)).call(any(), any(),
                eq(Settings.CALL_METHOD_GET_SECURE), any(), any(Bundle.class));
        assertThat(returnedValue2).isNull();

        String cachedValue2 = Settings.Secure.getString(mMockContentResolver, SETTING);
        // The second unset setting's generation number is cached
        verifyNoMoreInteractions(mMockIContentProvider);
        assertThat(cachedValue2).isNull();

        Settings.Secure.putString(mMockContentResolver, SETTING, "a");
        // The generation for unset settings should have changed
        returnedValue2 = Settings.Secure.getString(mMockContentResolver, SETTING2);
        verify(mMockIContentProvider, times(3)).call(any(), any(),
                eq(Settings.CALL_METHOD_GET_SECURE), any(), any(Bundle.class));
        assertThat(returnedValue2).isNull();

        // The generation tracker for the first setting should have change because it's set now
        returnedValue = Settings.Secure.getString(mMockContentResolver, SETTING);
        verify(mMockIContentProvider, times(4)).call(any(), any(),
                eq(Settings.CALL_METHOD_GET_SECURE), any(), any(Bundle.class));
        assertThat(returnedValue).isEqualTo("a");
    }
}
