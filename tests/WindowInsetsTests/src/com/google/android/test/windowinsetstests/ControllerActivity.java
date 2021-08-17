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

package com.google.android.test.windowinsetstests;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsets.Type;
import android.view.WindowInsetsAnimationControlListener;
import android.view.WindowInsetsAnimationController;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ToggleButton;

public class ControllerActivity extends Activity implements View.OnApplyWindowInsetsListener {

    private ToggleButton mToggleStatus;
    private SeekBar mSeekStatus;
    private ToggleButton mToggleNavigation;
    private SeekBar mSeekNavigation;
    private ToggleButton mToggleIme;
    private SeekBar mSeekIme;
    private TextView mTextControllableInsets;
    private boolean[] mNotFromUser = {false};
    private WindowInsets mLastInsets;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.controller_activity);
        final Spinner spinnerBehavior = findViewById(R.id.spinnerBehavior);
        ArrayAdapter<CharSequence> adapterBehavior = ArrayAdapter.createFromResource(this,
                R.array.behaviors, android.R.layout.simple_spinner_item);
        adapterBehavior.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerBehavior.setAdapter(adapterBehavior);
        spinnerBehavior.setSelection(
                spinnerBehavior.getWindowInsetsController().getSystemBarsBehavior());
        spinnerBehavior.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                parent.getWindowInsetsController().setSystemBarsBehavior(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        mToggleStatus = findViewById(R.id.toggleButtonStatus);
        mToggleStatus.setTag(mNotFromUser);
        mToggleStatus.setOnCheckedChangeListener(new ToggleListener(Type.statusBars()));
        mSeekStatus = findViewById(R.id.seekBarStatus);
        mSeekStatus.setOnSeekBarChangeListener(new SeekBarListener(Type.statusBars()));
        mToggleNavigation = findViewById(R.id.toggleButtonNavigation);
        mToggleNavigation.setTag(mNotFromUser);
        mToggleNavigation.setOnCheckedChangeListener(new ToggleListener(Type.navigationBars()));
        mSeekNavigation = findViewById(R.id.seekBarNavigation);
        mSeekNavigation.setOnSeekBarChangeListener(new SeekBarListener(Type.navigationBars()));
        mToggleIme = findViewById(R.id.toggleButtonIme);
        mToggleIme.setTag(mNotFromUser);
        mToggleIme.setOnCheckedChangeListener(new ToggleListener(Type.ime()));
        mSeekIme = findViewById(R.id.seekBarIme);
        mSeekIme.setOnSeekBarChangeListener(new SeekBarListener(Type.ime()));
        mTextControllableInsets = findViewById(R.id.textViewControllableInsets);
        final View contentView = findViewById(R.id.content);
        contentView.setOnApplyWindowInsetsListener(this);
        contentView.getWindowInsetsController().addOnControllableInsetsChangedListener(
                (c, types) -> mTextControllableInsets.setText("ControllableInsetsTypes=" + types));
    }

    @Override
    public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
        mNotFromUser[0] = true;
        updateWidgets(insets, Type.statusBars(), mToggleStatus, mSeekStatus);
        updateWidgets(insets, Type.navigationBars(), mToggleNavigation, mSeekNavigation);
        updateWidgets(insets, Type.ime(), mToggleIme, mSeekIme);
        mLastInsets = insets;
        mNotFromUser[0] = false;

        // Prevent triggering system gestures while controlling seek bars.
        final Insets gestureInsets =  insets.getInsets(Type.systemGestures());
        v.setPadding(gestureInsets.left, 0, gestureInsets.right, 0);

        return v.onApplyWindowInsets(insets);
    }

    private void updateWidgets(WindowInsets insets, int types, ToggleButton toggle, SeekBar seek) {
        final boolean isVisible = insets.isVisible(types);
        final boolean wasVisible = mLastInsets != null ? mLastInsets.isVisible(types) : !isVisible;
        if (isVisible != wasVisible) {
            toggle.setChecked(isVisible);
            if (!seek.isPressed()) {
                seek.setProgress(isVisible ? seek.getMax() : seek.getMin(), true /* animate*/);
            }
        }

    }

    private static class ToggleListener implements CompoundButton.OnCheckedChangeListener {

        private final @Type.InsetsType int mTypes;

        ToggleListener(int types) {
            mTypes = types;
        }

        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (((boolean[]) buttonView.getTag())[0]) {
                // not from user
                return;
            }
            if (isChecked) {
                buttonView.getWindowInsetsController().show(mTypes);
            } else {
                buttonView.getWindowInsetsController().hide(mTypes);
            }
        }
    }

    private static class SeekBarListener implements SeekBar.OnSeekBarChangeListener {

        private final @Type.InsetsType int mTypes;

        private WindowInsetsAnimationController mController;

        SeekBarListener(int types) {
            mTypes = types;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (mController != null && fromUser) {
                final int min = seekBar.getMin();
                final float fraction = (progress - min) / (float) (seekBar.getMax() - min);
                final Insets shownInsets = mController.getShownStateInsets();
                final Insets hiddenInsets = mController.getHiddenStateInsets();
                final Insets currentInsets = Insets.of(
                        (int) (0.5f + fraction * (shownInsets.left - hiddenInsets.left)),
                        (int) (0.5f + fraction * (shownInsets.top - hiddenInsets.top)),
                        (int) (0.5f + fraction * (shownInsets.right - hiddenInsets.right)),
                        (int) (0.5f + fraction * (shownInsets.bottom - hiddenInsets.bottom)));
                mController.setInsetsAndAlpha(currentInsets, 1f /* alpha */, fraction);
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            if (mController != null) {
                return;
            }
            seekBar.getWindowInsetsController().controlWindowInsetsAnimation(mTypes,
                    -1  /* durationMs */, null /* interpolator */, null /* cancellationSignal */,
                    new WindowInsetsAnimationControlListener() {
                        @Override
                        public void onReady(WindowInsetsAnimationController controller, int types) {
                            mController = controller;
                            if (!seekBar.isPressed()) {
                                onStopTrackingTouch(seekBar);
                            }
                        }

                        @Override
                        public void onFinished(WindowInsetsAnimationController controller) {
                            mController = null;
                        }

                        @Override
                        public void onCancelled(WindowInsetsAnimationController controller) {
                            mController = null;
                        }
                    });
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            final int min = seekBar.getMin();
            final int max = seekBar.getMax();
            final boolean shown = (seekBar.getProgress() - min) * 2 > max - min;
            seekBar.setProgress(shown ? max : min);
            if (mController != null) {
                mController.finish(shown);
            }
        }
    }
}
