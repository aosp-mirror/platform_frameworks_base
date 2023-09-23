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


package com.android.systemui.statusbar.notification.row;

import static android.widget.Toast.LENGTH_SHORT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.DragEvent;
import android.view.HapticFeedbackConstants;
import android.view.SurfaceControl;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.VisibleForTesting;

import com.android.app.animation.Interpolators;
import com.android.internal.logging.InstanceId;
import com.android.internal.logging.InstanceIdSequence;
import com.android.systemui.res.R;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.notification.logging.NotificationPanelLogger;
import com.android.systemui.statusbar.policy.HeadsUpManager;

import javax.inject.Inject;

/**
 * Controller for Notification to window.
 */
public class ExpandableNotificationRowDragController {
    private static final String TAG = ExpandableNotificationRowDragController.class.getSimpleName();
    private int mIconSize;

    private final Context mContext;
    private final HeadsUpManager mHeadsUpManager;
    private final ShadeController mShadeController;
    private NotificationPanelLogger mNotificationPanelLogger;

    @Inject
    public ExpandableNotificationRowDragController(Context context,
            HeadsUpManager headsUpManager,
            ShadeController shadeController,
            NotificationPanelLogger notificationPanelLogger) {
        mContext = context;
        mHeadsUpManager = headsUpManager;
        mShadeController = shadeController;
        mNotificationPanelLogger = notificationPanelLogger;

        init();
    }

    private void init() {
        mIconSize = mContext.getResources().getDimensionPixelSize(R.dimen.drag_and_drop_icon_size);
    }

    /**
     * Called when drag event beyond the touchslop,
     * and start drag and drop.
     *
     * @param view notification that was long pressed and started to drag and drop.
     */
    @VisibleForTesting
    public void startDragAndDrop(View view) {
        ExpandableNotificationRow enr = null;
        if (view instanceof ExpandableNotificationRow) {
            enr = (ExpandableNotificationRow) view;
        }

        StatusBarNotification sn = enr.getEntry().getSbn();
        Notification notification = sn.getNotification();
        final PendingIntent contentIntent = notification.contentIntent != null
                ? notification.contentIntent
                : notification.fullScreenIntent;
        if (contentIntent == null) {
            if (!enr.isPinned()) {
                // We dismiss the shade for consistency, but also because toasts currently don't
                // show above the shade
                dismissShade();
            }
            Toast.makeText(mContext, R.string.drag_split_not_supported, LENGTH_SHORT)
                 .show();
            return;
        }
        Bitmap iconBitmap = getBitmapFromDrawable(
                getPkgIcon(enr.getEntry().getSbn().getPackageName()));

        final ImageView snapshot = new ImageView(mContext);
        snapshot.setImageBitmap(iconBitmap);
        snapshot.layout(0, 0, mIconSize, mIconSize);

        ClipDescription clipDescription = new ClipDescription("Drag And Drop",
                new String[]{ClipDescription.MIMETYPE_APPLICATION_ACTIVITY});
        Intent dragIntent = new Intent();
        dragIntent.putExtra(ClipDescription.EXTRA_PENDING_INTENT, contentIntent);
        dragIntent.putExtra(Intent.EXTRA_USER, android.os.Process.myUserHandle());
        ClipData.Item item = new ClipData.Item(dragIntent);
        InstanceId instanceId = new InstanceIdSequence(Integer.MAX_VALUE).newInstanceId();
        item.getIntent().putExtra(ClipDescription.EXTRA_LOGGING_INSTANCE_ID, instanceId);
        ClipData dragData = new ClipData(clipDescription, item);
        View.DragShadowBuilder myShadow = new View.DragShadowBuilder(snapshot);
        view.setOnDragListener(getDraggedViewDragListener());
        boolean result = view.startDragAndDrop(dragData, myShadow, null, View.DRAG_FLAG_GLOBAL
                | View.DRAG_FLAG_REQUEST_SURFACE_FOR_RETURN_ANIMATION);
        if (result) {
            // Log notification drag only if it succeeds
            mNotificationPanelLogger.logNotificationDrag(enr.getEntry());
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (enr.isPinned()) {
                mHeadsUpManager.releaseAllImmediately();
            } else {
                dismissShade();
            }
        }
    }

    private void dismissShade() {
        // Speed up dismissing the shade since the drag needs to be handled by
        // the shell layer underneath
        mShadeController.animateCollapseShade(CommandQueue.FLAG_EXCLUDE_NONE, true /* force */,
                false /* delayed */, 1.1f /* speedUpFactor */);
    }

    private Drawable getPkgIcon(String pkgName) {
        Drawable pkgicon = null;
        PackageManager pm = mContext.getPackageManager();
        ApplicationInfo info;
        try {
            info = pm.getApplicationInfo(
                    pkgName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE);
            if (info != null) {
                pkgicon = pm.getApplicationIcon(info);
            } else {
                Log.d(TAG, " application info is null ");
                pkgicon = pm.getDefaultActivityIcon();
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, "can not find package with : " + pkgName);
            pkgicon = pm.getDefaultActivityIcon();
        }

        return pkgicon;
    }

    private Bitmap getBitmapFromDrawable(@NonNull Drawable drawable) {
        final Bitmap bmp = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bmp);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bmp;
    }

    private View.OnDragListener getDraggedViewDragListener() {
        return (view, dragEvent) -> {
            switch (dragEvent.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    if (dragEvent.getResult()) {
                        if (view instanceof ExpandableNotificationRow) {
                            ExpandableNotificationRow enr = (ExpandableNotificationRow) view;
                            enr.dragAndDropSuccess();
                        }
                    } else {
                        // Fade out the drag surface in place instead of animating back to the
                        // start position now that the shade is closed
                        fadeOutAndRemoveDragSurface(dragEvent);
                    }
                    // Clear the drag listener set above
                    view.setOnDragListener(null);
                    return true;
            }
            return false;
        };
    }

    private void fadeOutAndRemoveDragSurface(DragEvent dragEvent) {
        SurfaceControl dragSurface = dragEvent.getDragSurface();
        SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
        ValueAnimator returnAnimator = ValueAnimator.ofFloat(0f, 1f);
        returnAnimator.setDuration(200);
        returnAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        returnAnimator.addUpdateListener(animation -> {
            float t = animation.getAnimatedFraction();
            float alpha = 1f - t;
            tx.setAlpha(dragSurface, alpha);
            tx.apply();
        });
        returnAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean mCanceled = false;

            @Override
            public void onAnimationCancel(Animator animation) {
                cleanUpSurface();
                mCanceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mCanceled) {
                    // Already handled above
                    return;
                }
                cleanUpSurface();
            }

            private void cleanUpSurface() {
                tx.remove(dragSurface);
                tx.apply();
                tx.close();
            }
        });
        returnAnimator.start();
    }
}
