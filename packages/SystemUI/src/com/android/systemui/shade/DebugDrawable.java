/*
 * Copyright (c) 2022 The Android Open Source Project
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

package com.android.systemui.shade;

import android.annotation.NonNull;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

import com.android.keyguard.LockIconViewController;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;

import java.util.HashSet;
import java.util.Set;

/**
 * Drawable for NotificationPanelViewController.
 */
public class DebugDrawable extends Drawable {

    private final NotificationPanelViewController mNotificationPanelViewController;
    private final NotificationPanelView mView;
    private final NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
    private final LockIconViewController mLockIconViewController;
    private final QuickSettingsController mQsController;
    private final Set<Integer> mDebugTextUsedYPositions;
    private final Paint mDebugPaint;

    public DebugDrawable(
            NotificationPanelViewController notificationPanelViewController,
            NotificationPanelView notificationPanelView,
            NotificationStackScrollLayoutController notificationStackScrollLayoutController,
            LockIconViewController lockIconViewController,
            QuickSettingsController quickSettingsController
    ) {
        mNotificationPanelViewController = notificationPanelViewController;
        mView = notificationPanelView;
        mNotificationStackScrollLayoutController = notificationStackScrollLayoutController;
        mLockIconViewController = lockIconViewController;
        mQsController = quickSettingsController;
        mDebugTextUsedYPositions = new HashSet<>();
        mDebugPaint = new Paint();
    }

    @Override
    public void draw(@androidx.annotation.NonNull @NonNull Canvas canvas) {
        mDebugTextUsedYPositions.clear();

        mDebugPaint.setColor(Color.RED);
        mDebugPaint.setStrokeWidth(2);
        mDebugPaint.setStyle(Paint.Style.STROKE);
        mDebugPaint.setTextSize(24);
        String headerDebugInfo = mNotificationPanelViewController.getHeaderDebugInfo();
        if (headerDebugInfo != null) canvas.drawText(headerDebugInfo, 50, 100, mDebugPaint);

        drawDebugInfo(canvas, mNotificationPanelViewController.getMaxPanelHeight(),
                Color.RED, "getMaxPanelHeight()");
        drawDebugInfo(canvas, (int) mNotificationPanelViewController.getExpandedHeight(),
                Color.BLUE, "getExpandedHeight()");
        drawDebugInfo(canvas, mQsController.calculatePanelHeightExpanded(
                        mNotificationPanelViewController.getClockPositionResult()
                                .stackScrollerPadding),
                Color.GREEN, "calculatePanelHeightQsExpanded()");
        drawDebugInfo(canvas, mQsController.calculatePanelHeightExpanded(
                        mNotificationPanelViewController.getClockPositionResult()
                                .stackScrollerPadding),
                Color.YELLOW, "calculatePanelHeightShade()");
        drawDebugInfo(canvas,
                (int) mQsController.calculateNotificationsTopPadding(
                        mNotificationPanelViewController.isExpanding(),
                        mNotificationPanelViewController.getKeyguardNotificationStaticPadding(),
                        mNotificationPanelViewController.getExpandedFraction()),
                Color.MAGENTA, "calculateNotificationsTopPadding()");
        drawDebugInfo(canvas, mNotificationPanelViewController.getClockPositionResult().clockY,
                Color.GRAY, "mClockPositionResult.clockY");
        drawDebugInfo(canvas, (int) mLockIconViewController.getTop(), Color.GRAY,
                "mLockIconViewController.getTop()");

        if (mNotificationPanelViewController.getKeyguardShowing()) {
            // Notifications have the space between those two lines.
            drawDebugInfo(canvas,
                    mNotificationStackScrollLayoutController.getTop()
                            + (int) mNotificationPanelViewController
                            .getKeyguardNotificationTopPadding(),
                    Color.RED, "NSSL.getTop() + mKeyguardNotificationTopPadding");

            drawDebugInfo(canvas, mNotificationStackScrollLayoutController.getBottom()
                            - (int) mNotificationPanelViewController
                            .getKeyguardNotificationBottomPadding(),
                    Color.RED, "NSSL.getBottom() - mKeyguardNotificationBottomPadding");
        }

        mDebugPaint.setColor(Color.CYAN);
        canvas.drawLine(0,
                mNotificationPanelViewController.getClockPositionResult().stackScrollerPadding,
                mView.getWidth(), mNotificationStackScrollLayoutController.getTopPadding(),
                mDebugPaint);
    }

    private void drawDebugInfo(Canvas canvas, int y, int color, String label) {
        mDebugPaint.setColor(color);
        canvas.drawLine(/* startX= */ 0, /* startY= */ y, /* stopX= */ mView.getWidth(),
                /* stopY= */ y, mDebugPaint);
        canvas.drawText(label + " = " + y + "px", /* x= */ 0,
                /* y= */ computeDebugYTextPosition(y), mDebugPaint);
    }

    private int computeDebugYTextPosition(int lineY) {
        if (lineY - mDebugPaint.getTextSize() < 0) {
            // Avoiding drawing out of bounds
            lineY += mDebugPaint.getTextSize();
        }
        int textY = lineY;
        while (mDebugTextUsedYPositions.contains(textY)) {
            textY = (int) (textY + mDebugPaint.getTextSize());
        }
        mDebugTextUsedYPositions.add(textY);
        return textY;
    }

    @Override
    public void setAlpha(int alpha) {

    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {

    }

    @Override
    public int getOpacity() {
        return PixelFormat.UNKNOWN;
    }
}
