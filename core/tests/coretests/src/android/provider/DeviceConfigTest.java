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

package android.provider;

import static android.provider.DeviceConfig.OnPropertyChangedListener;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.fail;

import android.app.ActivityThread;
import android.content.ContentResolver;
import android.os.Bundle;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tests that ensure appropriate settings are backed up. */
@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class DeviceConfigTest {
    // TODO(b/109919982): Migrate tests to CTS
    private static final String sNamespace = "namespace1";
    private static final String sKey = "key1";
    private static final String sValue = "value1";
    private static final long WAIT_FOR_PROPERTY_CHANGE_TIMEOUT_MILLIS = 2000; // 2 sec

    private final Object mLock = new Object();

    @After
    public void cleanUp() {
        deleteViaContentProvider(sNamespace, sKey);
    }

    @Test
    public void getProperty_empty() {
        String result = DeviceConfig.getProperty(sNamespace, sKey);
        assertNull(result);
    }

    @Test
    public void setAndGetProperty_sameNamespace() {
        DeviceConfig.setProperty(sNamespace, sKey, sValue, false);
        String result = DeviceConfig.getProperty(sNamespace, sKey);
        assertEquals(sValue, result);
    }

    @Test
    public void setAndGetProperty_differentNamespace() {
        String newNamespace = "namespace2";
        DeviceConfig.setProperty(sNamespace, sKey, sValue, false);
        String result = DeviceConfig.getProperty(newNamespace, sKey);
        assertNull(result);
    }

    @Test
    public void setAndGetProperty_multipleNamespaces() {
        String newNamespace = "namespace2";
        String newValue = "value2";
        DeviceConfig.setProperty(sNamespace, sKey, sValue, false);
        DeviceConfig.setProperty(newNamespace, sKey, newValue, false);
        String result = DeviceConfig.getProperty(sNamespace, sKey);
        assertEquals(sValue, result);
        result = DeviceConfig.getProperty(newNamespace, sKey);
        assertEquals(newValue, result);

        // clean up
        deleteViaContentProvider(newNamespace, sKey);
    }

    @Test
    public void setAndGetProperty_overrideValue() {
        String newValue = "value2";
        DeviceConfig.setProperty(sNamespace, sKey, sValue, false);
        DeviceConfig.setProperty(sNamespace, sKey, newValue, false);
        String result = DeviceConfig.getProperty(sNamespace, sKey);
        assertEquals(newValue, result);
    }

    @Test
    public void testListener() {
        setPropertyAndAssertSuccessfulChange(sNamespace, sKey, sValue);
    }

    private void setPropertyAndAssertSuccessfulChange(String setNamespace, String setName,
            String setValue) {
        final AtomicBoolean success = new AtomicBoolean();

        OnPropertyChangedListener changeListener = new OnPropertyChangedListener() {
                    @Override
                    public void onPropertyChanged(String namespace, String name, String value) {
                        assertEquals(setNamespace, namespace);
                        assertEquals(setName, name);
                        assertEquals(setValue, value);
                        success.set(true);

                        synchronized (mLock) {
                            mLock.notifyAll();
                        }
                    }
                };
        Executor executor = ActivityThread.currentApplication().getMainExecutor();
        DeviceConfig.addOnPropertyChangedListener(setNamespace, executor, changeListener);
        try {
            DeviceConfig.setProperty(setNamespace, setName, setValue, false);

            final long startTimeMillis = SystemClock.uptimeMillis();
            synchronized (mLock) {
                while (true) {
                    if (success.get()) {
                        return;
                    }
                    final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                    if (elapsedTimeMillis >= WAIT_FOR_PROPERTY_CHANGE_TIMEOUT_MILLIS) {
                        fail("Could not change setting for "
                                + WAIT_FOR_PROPERTY_CHANGE_TIMEOUT_MILLIS + " ms");
                    }
                    final long remainingTimeMillis = WAIT_FOR_PROPERTY_CHANGE_TIMEOUT_MILLIS
                            - elapsedTimeMillis;
                    try {
                        mLock.wait(remainingTimeMillis);
                    } catch (InterruptedException ie) {
                        /* ignore */
                    }
                }
            }
        } finally {
            DeviceConfig.removeOnPropertyChangedListener(changeListener);
        }
    }

    private static boolean deleteViaContentProvider(String namespace, String key) {
        ContentResolver resolver = InstrumentationRegistry.getContext().getContentResolver();
        String compositeName = namespace + "/" + key;
        Bundle result = resolver.call(
                DeviceConfig.CONTENT_URI, Settings.CALL_METHOD_DELETE_CONFIG, compositeName, null);
        assertNotNull(result);
        return compositeName.equals(result.getString(Settings.NameValueTable.VALUE));
    }

}
