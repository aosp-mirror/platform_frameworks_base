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

package com.android.server.wm;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.window.DisplayAreaOrganizer.FEATURE_DEFAULT_TASK_CONTAINER;
import static android.window.DisplayAreaOrganizer.FEATURE_ROOT;
import static android.window.DisplayAreaOrganizer.FEATURE_RUNTIME_TASK_CONTAINER_FIRST;
import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_FIRST;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;
import android.window.DisplayAreaAppearedInfo;
import android.window.DisplayAreaInfo;
import android.window.IDisplayAreaOrganizer;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Build/Install/Run:
 *  atest WmTests:DisplayAreaOrganizerTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class DisplayAreaOrganizerTest extends WindowTestsBase {

    private DisplayArea mTestDisplayArea;
    private DisplayAreaOrganizerController mOrganizerController;

    @Before
    public void setUp() {
        mOrganizerController =
                mWm.mAtmService.mWindowOrganizerController.mDisplayAreaOrganizerController;
        WindowContainer parentWindow = mDisplayContent.getDefaultTaskDisplayArea().getParent();
        mTestDisplayArea = new DisplayArea(mWm, DisplayArea.Type.ANY,
                "TestDisplayArea", FEATURE_VENDOR_FIRST);
        parentWindow.addChild(mTestDisplayArea,
                parentWindow.mChildren.indexOf(mDisplayContent.getDefaultTaskDisplayArea()) + 1);
    }

    @After
    public void tearDown() {
        mTestDisplayArea.removeImmediately();
    }

    private IDisplayAreaOrganizer registerMockOrganizer(int feature) {
        return registerMockOrganizer(feature, new Binder());
    }

    private IDisplayAreaOrganizer registerMockOrganizer(int feature, Binder binder) {
        final IDisplayAreaOrganizer organizer = createMockOrganizer(binder);
        mOrganizerController.registerOrganizer(organizer, feature);
        return organizer;
    }

    private IDisplayAreaOrganizer createMockOrganizer(Binder binder) {
        final IDisplayAreaOrganizer organizer = mock(IDisplayAreaOrganizer.class);
        when(organizer.asBinder()).thenReturn(binder);
        return organizer;
    }

    @Test
    public void testRegisterOrganizer() throws RemoteException {
        final IDisplayAreaOrganizer organizer = createMockOrganizer(new Binder());
        List<DisplayAreaAppearedInfo> infos = mOrganizerController
                .registerOrganizer(organizer, FEATURE_VENDOR_FIRST).getList();

        // Return a list contains the DA, and no onDisplayAreaAppeared triggered.
        assertThat(infos).hasSize(1);
        assertThat(infos.get(0).getDisplayAreaInfo().token)
                .isEqualTo(mTestDisplayArea.getDisplayAreaInfo().token);
        verify(organizer, never()).onDisplayAreaAppeared(any(DisplayAreaInfo.class),
                any(SurfaceControl.class));
    }

    @Test
    public void testRegisterOrganizer_alreadyRegisteredFeature() {
        registerMockOrganizer(FEATURE_VENDOR_FIRST);
        assertThrows(IllegalStateException.class,
                () -> registerMockOrganizer(FEATURE_VENDOR_FIRST));
    }

    @Test
    public void testRegisterOrganizer_ignoreUntrustedDisplay() throws RemoteException {
        doReturn(false).when(mDisplayContent).isTrusted();

        final IDisplayAreaOrganizer organizer = createMockOrganizer(new Binder());
        List<DisplayAreaAppearedInfo> infos = mOrganizerController
                .registerOrganizer(organizer, FEATURE_VENDOR_FIRST).getList();

        assertThat(infos).isEmpty();
        verify(organizer, never()).onDisplayAreaAppeared(any(DisplayAreaInfo.class),
                any(SurfaceControl.class));
    }

    @Test
    public void testCreateTaskDisplayArea_topBelowRoot() {
        final String newTdaName = "testTda";
        final IDisplayAreaOrganizer organizer = createMockOrganizer(new Binder());
        final DisplayAreaAppearedInfo tdaInfo = mOrganizerController.createTaskDisplayArea(
                organizer, DEFAULT_DISPLAY, FEATURE_ROOT, newTdaName);

        final int newTdaIndex =
                mTestDisplayArea.getParent().mChildren.indexOf(mTestDisplayArea) + 1;
        final WindowContainer wc = mTestDisplayArea.getParent().getChildAt(newTdaIndex);

        // A new TaskDisplayArea is created on the top.
        assertThat(wc).isInstanceOf(TaskDisplayArea.class);
        assertThat(tdaInfo.getDisplayAreaInfo().displayId).isEqualTo(DEFAULT_DISPLAY);
        assertThat(tdaInfo.getDisplayAreaInfo().token)
                .isEqualTo(wc.mRemoteToken.toWindowContainerToken());

        final TaskDisplayArea tda = wc.asTaskDisplayArea();

        assertThat(tda.getName()).isEqualTo(newTdaName);
        assertThat(tda.mFeatureId).isEqualTo(tdaInfo.getDisplayAreaInfo().featureId);
        assertThat(tda.mCreatedByOrganizer).isTrue();
        assertThat(tda.mOrganizer).isEqualTo(organizer);
    }

    @Test
    public void testCreateTaskDisplayArea_topBelowAnotherTaskDisplayArea() {
        final String newTdaName = "testTda";
        final TaskDisplayArea parentTda = mDisplayContent.getDefaultTaskDisplayArea();
        final IDisplayAreaOrganizer organizer = createMockOrganizer(new Binder());
        final DisplayAreaAppearedInfo tdaInfo = mOrganizerController.createTaskDisplayArea(
                organizer, DEFAULT_DISPLAY, FEATURE_DEFAULT_TASK_CONTAINER, newTdaName);

        final WindowContainer wc = parentTda.getChildAt(parentTda.getChildCount() - 1);

        // A new TaskDisplayArea is created on the top.
        assertThat(wc).isInstanceOf(TaskDisplayArea.class);
        assertThat(tdaInfo.getDisplayAreaInfo().displayId).isEqualTo(DEFAULT_DISPLAY);
        assertThat(tdaInfo.getDisplayAreaInfo().token)
                .isEqualTo(wc.mRemoteToken.toWindowContainerToken());

        final TaskDisplayArea tda = wc.asTaskDisplayArea();

        assertThat(tda.getName()).isEqualTo(newTdaName);
        assertThat(tda.mFeatureId).isEqualTo(tdaInfo.getDisplayAreaInfo().featureId);
        assertThat(tda.mCreatedByOrganizer).isTrue();
        assertThat(tda.mOrganizer).isEqualTo(organizer);
    }

    @Test
    public void testCreateTaskDisplayArea_incrementalTdaFeatureId() {
        final String newTdaName = "testTda";
        final IDisplayAreaOrganizer organizer = createMockOrganizer(new Binder());
        final DisplayAreaAppearedInfo tdaInfo1 = mOrganizerController.createTaskDisplayArea(
                organizer, DEFAULT_DISPLAY, FEATURE_ROOT, newTdaName);
        final DisplayAreaAppearedInfo tdaInfo2 = mOrganizerController.createTaskDisplayArea(
                organizer, DEFAULT_DISPLAY, FEATURE_ROOT, newTdaName);

        // New created TDA has unique feature id starting from FEATURE_RUNTIME_TASK_CONTAINER_FIRST.
        assertThat(tdaInfo1.getDisplayAreaInfo().featureId).isEqualTo(
                FEATURE_RUNTIME_TASK_CONTAINER_FIRST);
        assertThat(tdaInfo2.getDisplayAreaInfo().featureId).isEqualTo(
                FEATURE_RUNTIME_TASK_CONTAINER_FIRST + 1);
    }


    @Test
    public void testCreateTaskDisplayArea_invalidDisplayAndRoot() {
        final IDisplayAreaOrganizer organizer = createMockOrganizer(new Binder());

        assertThrows(IllegalArgumentException.class, () ->
                mOrganizerController.createTaskDisplayArea(
                        organizer, SystemServicesTestRule.sNextDisplayId + 1, FEATURE_ROOT,
                        "testTda"));

        assertThrows(IllegalArgumentException.class, () ->
                mOrganizerController.createTaskDisplayArea(
                        organizer, DEFAULT_DISPLAY, FEATURE_ROOT - 1, "testTda"));

        doReturn(false).when(mDisplayContent).isTrusted();
        assertThrows(IllegalArgumentException.class, () ->
                mOrganizerController.createTaskDisplayArea(
                        organizer, DEFAULT_DISPLAY, FEATURE_ROOT, "testTda"));
    }

    @Test
    public void testDeleteTaskDisplayArea() {
        final String newTdaName = "testTda";
        final IDisplayAreaOrganizer organizer = createMockOrganizer(new Binder());
        final DisplayAreaAppearedInfo tdaInfo = mOrganizerController.createTaskDisplayArea(
                organizer, DEFAULT_DISPLAY, FEATURE_ROOT, newTdaName);
        final int tdaFeatureId = tdaInfo.getDisplayAreaInfo().featureId;

        final TaskDisplayArea newTda = mDisplayContent.getItemFromDisplayAreas(
                da -> da.mFeatureId == tdaFeatureId ? da.asTaskDisplayArea() : null);
        spyOn(newTda);

        mOrganizerController.deleteTaskDisplayArea(newTda.mRemoteToken.toWindowContainerToken());

        verify(newTda).remove();
        verify(newTda).removeImmediately();
        assertThat(newTda.mOrganizer).isNull();
        assertThat(newTda.isRemoved()).isTrue();

        final TaskDisplayArea curTda = mDisplayContent.getItemFromDisplayAreas(
                da -> da.mFeatureId == tdaFeatureId ? da.asTaskDisplayArea() : null);

        assertThat(curTda).isNull();
    }

    @Test
    public void testUnregisterOrganizer_deleteNewCreatedTaskDisplayArea() {
        final String newTdaName = "testTda";
        final IDisplayAreaOrganizer organizer = createMockOrganizer(new Binder());
        final DisplayAreaAppearedInfo tdaInfo = mOrganizerController.createTaskDisplayArea(
                organizer, DEFAULT_DISPLAY, FEATURE_ROOT, newTdaName);
        final int tdaFeatureId = tdaInfo.getDisplayAreaInfo().featureId;

        final TaskDisplayArea newTda = mDisplayContent.getItemFromDisplayAreas(
                da -> da.mFeatureId == tdaFeatureId ? da.asTaskDisplayArea() : null);
        spyOn(newTda);

        mOrganizerController.unregisterOrganizer(organizer);

        verify(newTda).remove();
        verify(newTda).removeImmediately();
        assertThat(newTda.mOrganizer).isNull();
        assertThat(newTda.isRemoved()).isTrue();

        final TaskDisplayArea curTda = mDisplayContent.getItemFromDisplayAreas(
                da -> da.mFeatureId == tdaFeatureId ? da.asTaskDisplayArea() : null);

        assertThat(curTda).isNull();
    }

    @Test
    public void testDeleteTaskDisplayArea_invalidTaskDisplayArea() {
        final TaskDisplayArea tda = mDisplayContent.getDefaultTaskDisplayArea();
        assertThrows(IllegalArgumentException.class, () ->
                mOrganizerController.deleteTaskDisplayArea(
                        tda.mRemoteToken.toWindowContainerToken()));
    }

    @Test
    public void testAppearedVanished() throws RemoteException {
        final IDisplayAreaOrganizer organizer = registerMockOrganizer(FEATURE_VENDOR_FIRST);
        mOrganizerController.unregisterOrganizer(organizer);

        verify(organizer).onDisplayAreaVanished(any());
    }

    @Test
    public void testChanged() throws RemoteException {
        final IDisplayAreaOrganizer organizer = registerMockOrganizer(FEATURE_VENDOR_FIRST);
        mDisplayContent.setBounds(new Rect(0, 0, 1000, 1000));

        verify(organizer).onDisplayAreaInfoChanged(any());

        Configuration tmpConfiguration = new Configuration();
        tmpConfiguration.setTo(mDisplayContent.getRequestedOverrideConfiguration());
        mDisplayContent.onRequestedOverrideConfigurationChanged(tmpConfiguration);

        // Ensure it was still only called once if the bounds didn't change
        verify(organizer).onDisplayAreaInfoChanged(any());
    }

    @Test
    public void testUnregisterOrganizer() {
        final Binder binder = new Binder();
        registerMockOrganizer(FEATURE_VENDOR_FIRST, binder);

        assertThat(mTestDisplayArea.mOrganizer).isNotNull();

        mOrganizerController.unregisterOrganizer(createMockOrganizer(binder));

        assertThat(mTestDisplayArea.mOrganizer).isNull();
    }
}
