/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.input.debug;

import android.content.Context;
import android.graphics.Color;
import android.hardware.input.InputManager;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.Objects;
import java.util.function.Consumer;

public class TouchpadSelectionView extends LinearLayout {
    private static final float TEXT_SIZE_SP = 16.0f;

    int mCurrentTouchpadId;

    public TouchpadSelectionView(Context context, int touchpadId,
                                 Consumer<Integer> touchpadSwitchHandler) {
        super(context);
        mCurrentTouchpadId = touchpadId;
        init(context, touchpadSwitchHandler);
    }

    private void init(Context context, Consumer<Integer> touchpadSwitchHandler) {
        setOrientation(HORIZONTAL);
        setLayoutParams(new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT));
        setBackgroundColor(Color.TRANSPARENT);

        TextView nameView = new TextView(context);
        nameView.setTextSize(TEXT_SIZE_SP);
        nameView.setText(getTouchpadName(mCurrentTouchpadId));
        nameView.setGravity(Gravity.LEFT);
        nameView.setTextColor(Color.WHITE);

        LayoutParams textParams = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        textParams.rightMargin = 16;
        nameView.setLayoutParams(textParams);

        ImageButton arrowButton = new ImageButton(context);
        arrowButton.setImageDrawable(context.getDrawable(android.R.drawable.arrow_down_float));
        arrowButton.setForegroundGravity(Gravity.RIGHT);
        arrowButton.setBackgroundColor(Color.TRANSPARENT);
        arrowButton.setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        arrowButton.setOnClickListener(v -> showPopupMenu(v, context, touchpadSwitchHandler));

        addView(nameView);
        addView(arrowButton);
    }

    private void showPopupMenu(View anchorView, Context context,
                               Consumer<Integer> touchpadSwitchHandler) {
        int i = 0;
        PopupMenu popupMenu = new PopupMenu(context, anchorView);

        final InputManager inputManager = Objects.requireNonNull(
                mContext.getSystemService(InputManager.class));
        for (int deviceId : inputManager.getInputDeviceIds()) {
            InputDevice inputDevice = inputManager.getInputDevice(deviceId);
            if (Objects.requireNonNull(inputDevice).supportsSource(
                    InputDevice.SOURCE_TOUCHPAD | InputDevice.SOURCE_MOUSE)) {
                popupMenu.getMenu().add(0, deviceId, i, getTouchpadName(deviceId));
                i++;
            }
        }

        popupMenu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == mCurrentTouchpadId) {
                return false;
            }

            touchpadSwitchHandler.accept(item.getItemId());
            return true;
        });

        popupMenu.show();
    }

    private String getTouchpadName(int touchpadId) {
        return Objects.requireNonNull(Objects.requireNonNull(
                        mContext.getSystemService(InputManager.class))
                .getInputDevice(touchpadId)).getName();
    }
}
