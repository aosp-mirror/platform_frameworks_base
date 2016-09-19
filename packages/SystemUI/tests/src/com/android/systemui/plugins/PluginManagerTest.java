/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.plugins;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.PluginManager.PluginInstanceManagerFactory;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.Thread.UncaughtExceptionHandler;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PluginManagerTest extends SysuiTestCase {

    private PluginInstanceManagerFactory mMockFactory;
    private PluginInstanceManager mMockPluginInstance;
    private PluginManager mPluginManager;
    private PluginListener mMockListener;

    private UncaughtExceptionHandler mRealExceptionHandler;
    private UncaughtExceptionHandler mMockExceptionHandler;
    private UncaughtExceptionHandler mPluginExceptionHandler;

    @Before
    public void setup() throws Exception {
        mRealExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        mMockExceptionHandler = mock(UncaughtExceptionHandler.class);
        mMockFactory = mock(PluginInstanceManagerFactory.class);
        mMockPluginInstance = mock(PluginInstanceManager.class);
        when(mMockFactory.createPluginInstanceManager(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.anyBoolean(), Mockito.any(), Mockito.anyInt()))
                .thenReturn(mMockPluginInstance);
        mPluginManager = new PluginManager(getContext(), mMockFactory, true, mMockExceptionHandler);
        resetExceptionHandler();
        mMockListener = mock(PluginListener.class);
    }

    @Test
    public void testAddListener() {
        mPluginManager.addPluginListener("myAction", mMockListener, 1);

        verify(mMockPluginInstance).startListening();
    }

    @Test
    public void testRemoveListener() {
        mPluginManager.addPluginListener("myAction", mMockListener, 1);

        mPluginManager.removePluginListener(mMockListener);
        verify(mMockPluginInstance).stopListening();
    }

    @Test
    public void testNonDebuggable() {
        mPluginManager = new PluginManager(getContext(), mMockFactory, false,
                mMockExceptionHandler);
        resetExceptionHandler();
        mPluginManager.addPluginListener("myAction", mMockListener, 1);

        verify(mMockPluginInstance, Mockito.never()).startListening();
    }

    @Test
    public void testExceptionHandler_foundPlugin() {
        mPluginManager.addPluginListener("myAction", mMockListener, 1);
        when(mMockPluginInstance.checkAndDisable(Mockito.any())).thenReturn(true);

        mPluginExceptionHandler.uncaughtException(Thread.currentThread(), new Throwable());

        verify(mMockPluginInstance, Mockito.atLeastOnce()).checkAndDisable(
                ArgumentCaptor.forClass(String.class).capture());
        verify(mMockPluginInstance, Mockito.never()).disableAll();
        verify(mMockExceptionHandler).uncaughtException(
                ArgumentCaptor.forClass(Thread.class).capture(),
                ArgumentCaptor.forClass(Throwable.class).capture());
    }

    @Test
    public void testExceptionHandler_noFoundPlugin() {
        mPluginManager.addPluginListener("myAction", mMockListener, 1);
        when(mMockPluginInstance.checkAndDisable(Mockito.any())).thenReturn(false);

        mPluginExceptionHandler.uncaughtException(Thread.currentThread(), new Throwable());

        verify(mMockPluginInstance, Mockito.atLeastOnce()).checkAndDisable(
                ArgumentCaptor.forClass(String.class).capture());
        verify(mMockPluginInstance).disableAll();
        verify(mMockExceptionHandler).uncaughtException(
                ArgumentCaptor.forClass(Thread.class).capture(),
                ArgumentCaptor.forClass(Throwable.class).capture());
    }

    private void resetExceptionHandler() {
        mPluginExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        // Set back the real exception handler so the test can crash if it wants to.
        Thread.setDefaultUncaughtExceptionHandler(mRealExceptionHandler);
    }
}
