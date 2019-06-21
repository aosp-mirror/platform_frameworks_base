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
package android.gameperformance;

import android.annotation.NonNull;

/**
 * Tests that verifies how many UI controls can be handled to keep FPS close to device refresh rate.
 * As a test UI control ImageView with an infinite animation is chosen. The animation has refresh
 * rate ~67Hz that forces all devices to refresh UI at highest possible rate.
 */
public class ControlsTest extends BaseTest {
    public ControlsTest(@NonNull GamePerformanceActivity activity) {
        super(activity);
    }

    @NonNull
    public CustomControlView getView() {
        return getActivity().getControlView();
    }

    @Override
    protected void initProbePass(int probe) {
        try {
            getActivity().attachControlView();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        initUnits(probe * getUnitScale());
    }

    @Override
    protected void freeProbePass() {
    }

    @Override
    public String getName() {
        return "control_count";
    }

    @Override
    public String getUnitName() {
        return "controls";
    }

    @Override
    public double getUnitScale() {
        return 5.0;
    }

    @Override
    public void initUnits(double controlCount) {
        try {
            getView().createControls(getActivity(), (int)Math.round(controlCount));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}