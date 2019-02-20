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

package com.android.systemui.bubbles;

import static android.content.pm.ActivityInfo.DOCUMENT_LAUNCH_ALWAYS;
import static android.util.StatsLogInternal.BUBBLE_DEVELOPER_ERROR_REPORTED__ERROR__ACTIVITY_INFO_MISSING;
import static android.util.StatsLogInternal.BUBBLE_DEVELOPER_ERROR_REPORTED__ERROR__ACTIVITY_INFO_NOT_RESIZABLE;
import static android.util.StatsLogInternal.BUBBLE_DEVELOPER_ERROR_REPORTED__ERROR__DOCUMENT_LAUNCH_NOT_ALWAYS;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.annotation.Nullable;
import android.app.ActivityView;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.util.Log;
import android.util.StatsLog;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.TriangleShape;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.stack.ExpandableViewState;

/**
 * Container for the expanded bubble view, handles rendering the caret and header of the view.
 */
public class BubbleExpandedView extends LinearLayout implements View.OnClickListener {
    private static final String TAG = "BubbleExpandedView";

    // The triangle pointing to the expanded view
    private View mPointerView;

    // Header
    private View mHeaderView;
    private TextView mHeaderTextView;
    private ImageButton mDeepLinkIcon;
    private ImageButton mSettingsIcon;

    // Permission view
    private View mPermissionView;

    // Views for expanded state
    private ExpandableNotificationRow mNotifRow;
    private ActivityView mActivityView;

    private boolean mActivityViewReady = false;
    private PendingIntent mBubbleIntent;

    private int mBubbleHeight;
    private int mDefaultHeight;

    private NotificationEntry mEntry;
    private PackageManager mPm;
    private String mAppName;
    private Drawable mAppIcon;

    private INotificationManager mNotificationManagerService;
    private BubbleController mBubbleController = Dependency.get(BubbleController.class);

    private BubbleStackView mStackView;

    private BubbleExpandedView.OnBubbleBlockedListener mOnBubbleBlockedListener;

    private ActivityView.StateCallback mStateCallback = new ActivityView.StateCallback() {
        @Override
        public void onActivityViewReady(ActivityView view) {
            if (!mActivityViewReady) {
                mActivityViewReady = true;
                mActivityView.startActivity(mBubbleIntent);
            }
        }

        @Override
        public void onActivityViewDestroyed(ActivityView view) {
            mActivityViewReady = false;
        }

        /**
         * This is only called for tasks on this ActivityView, which is also set to
         * single-task mode -- meaning never more than one task on this display. If a task
         * is being removed, it's the top Activity finishing and this bubble should
         * be removed or collapsed.
         */
        @Override
        public void onTaskRemovalStarted(int taskId) {
            if (mEntry != null) {
                // Must post because this is called from a binder thread.
                post(() -> mBubbleController.removeBubble(mEntry.key));
            }
        }
    };

    public BubbleExpandedView(Context context) {
        this(context, null);
    }

    public BubbleExpandedView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BubbleExpandedView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public BubbleExpandedView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mPm = context.getPackageManager();
        mDefaultHeight = getResources().getDimensionPixelSize(
                R.dimen.bubble_expanded_default_height);
        try {
            mNotificationManagerService = INotificationManager.Stub.asInterface(
                    ServiceManager.getServiceOrThrow(Context.NOTIFICATION_SERVICE));
        } catch (ServiceManager.ServiceNotFoundException e) {
            Log.w(TAG, e);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        Resources res = getResources();
        mPointerView = findViewById(R.id.pointer_view);
        int width = res.getDimensionPixelSize(R.dimen.bubble_pointer_width);
        int height = res.getDimensionPixelSize(R.dimen.bubble_pointer_height);

        TypedArray ta = getContext().obtainStyledAttributes(
                new int[] {android.R.attr.colorBackgroundFloating});
        int bgColor = ta.getColor(0, Color.WHITE);
        ta.recycle();

        ShapeDrawable triangleDrawable = new ShapeDrawable(
                TriangleShape.create(width, height, true /* pointUp */));
        triangleDrawable.setTint(bgColor);
        mPointerView.setBackground(triangleDrawable);

        FrameLayout viewWrapper = findViewById(R.id.header_permission_wrapper);
        LayoutTransition transition = new LayoutTransition();
        transition.setDuration(200);

        ObjectAnimator appearAnimator = ObjectAnimator.ofFloat(null, View.ALPHA, 0f, 1f);
        transition.setAnimator(LayoutTransition.APPEARING, appearAnimator);
        transition.setInterpolator(LayoutTransition.APPEARING, Interpolators.ALPHA_IN);

        ObjectAnimator disappearAnimator = ObjectAnimator.ofFloat(null, View.ALPHA, 1f, 0f);
        transition.setAnimator(LayoutTransition.DISAPPEARING, disappearAnimator);
        transition.setInterpolator(LayoutTransition.DISAPPEARING, Interpolators.ALPHA_OUT);

        transition.setAnimateParentHierarchy(false);
        viewWrapper.setLayoutTransition(transition);
        viewWrapper.getLayoutTransition().enableTransitionType(LayoutTransition.CHANGING);

        mHeaderView = findViewById(R.id.header_layout);
        mHeaderTextView = findViewById(R.id.header_text);
        mDeepLinkIcon = findViewById(R.id.deep_link_button);
        mSettingsIcon = findViewById(R.id.settings_button);
        mDeepLinkIcon.setOnClickListener(this);
        mSettingsIcon.setOnClickListener(this);

        mPermissionView = findViewById(R.id.permission_layout);
        findViewById(R.id.no_bubbles_button).setOnClickListener(this);
        findViewById(R.id.yes_bubbles_button).setOnClickListener(this);

        mActivityView = new ActivityView(mContext, null /* attrs */, 0 /* defStyle */,
                true /* singleTaskInstance */);
        addView(mActivityView);

        mActivityView.setOnApplyWindowInsetsListener((View view, WindowInsets insets) -> {
            ActivityView activityView = (ActivityView) view;
            // Here we assume that the position of the ActivityView on the screen
            // remains regardless of IME status. When we move ActivityView, the
            // forwardedInsets should be computed not against the current location
            // and size, but against the post-moved location and size.
            Point displaySize = new Point();
            view.getContext().getDisplay().getSize(displaySize);
            int[] windowLocation = view.getLocationOnScreen();
            final int windowBottom = windowLocation[1] + view.getHeight();
            final int keyboardHeight = insets.getSystemWindowInsetBottom()
                    - insets.getStableInsetBottom();
            final int insetsBottom = Math.max(0,
                    windowBottom + keyboardHeight - displaySize.y);
            activityView.setForwardedInsets(Insets.of(0, 0, 0, insetsBottom));
            return view.onApplyWindowInsets(insets);
        });
    }

    /**
     * Sets the listener to notify when a bubble has been blocked.
     */
    public void setOnBlockedListener(OnBubbleBlockedListener listener) {
        mOnBubbleBlockedListener = listener;
    }

    /**
     * Sets the notification entry used to populate this view.
     */
    public void setEntry(NotificationEntry entry, BubbleStackView stackView) {
        mStackView = stackView;
        mEntry = entry;

        ApplicationInfo info;
        try {
            info = mPm.getApplicationInfo(
                    entry.notification.getPackageName(),
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE);
            if (info != null) {
                mAppName = String.valueOf(mPm.getApplicationLabel(info));
                mAppIcon = mPm.getApplicationIcon(info);
            }
        } catch (PackageManager.NameNotFoundException e) {
            // Ahh... just use package name
            mAppName = entry.notification.getPackageName();
        }
        if (mAppIcon == null) {
            mAppIcon = mPm.getDefaultActivityIcon();
        }
        updateHeaderView();
        updatePermissionView();
        updateExpandedView();
    }

    /**
     * Lets activity view know it should be shown / populated.
     */
    public void populateActivityView() {
        mActivityView.setCallback(mStateCallback);
    }

    private void updateHeaderView() {
        mSettingsIcon.setContentDescription(getResources().getString(
                R.string.bubbles_settings_button_description, mAppName));
        mDeepLinkIcon.setContentDescription(getResources().getString(
                R.string.bubbles_deep_link_button_description, mAppName));
        if (mEntry != null && mEntry.getBubbleMetadata() != null) {
            mHeaderTextView.setText(mEntry.getBubbleMetadata().getTitle());
        } else {
            // This should only happen if we're auto-bubbling notification content that isn't
            // explicitly a bubble
            mHeaderTextView.setText(mAppName);
        }
    }

    private void updatePermissionView() {
        boolean hasUserApprovedBubblesForPackage = false;
        try {
            hasUserApprovedBubblesForPackage =
                    mNotificationManagerService.hasUserApprovedBubblesForPackage(
                            mEntry.notification.getPackageName(), mEntry.notification.getUid());
        } catch (RemoteException e) {
            Log.w(TAG, e);
        }
        if (hasUserApprovedBubblesForPackage) {
            mHeaderView.setVisibility(VISIBLE);
            mPermissionView.setVisibility(GONE);
        } else {
            mHeaderView.setVisibility(GONE);
            mPermissionView.setVisibility(VISIBLE);
            ((ImageView) mPermissionView.findViewById(R.id.pkgicon)).setImageDrawable(mAppIcon);
            ((TextView) mPermissionView.findViewById(R.id.pkgname)).setText(mAppName);
        }
    }

    private void updateExpandedView() {
        mBubbleIntent = getBubbleIntent(mEntry);
        if (mBubbleIntent != null) {
            if (mNotifRow != null) {
                // Clear out the row if we had it previously
                removeView(mNotifRow);
                mNotifRow = null;
            }
            Notification.BubbleMetadata data = mEntry.getBubbleMetadata();
            mBubbleHeight = data != null && data.getDesiredHeight() > 0
                    ? data.getDesiredHeight()
                    : mDefaultHeight;
            // XXX: enforce max / min height
            LayoutParams lp = (LayoutParams) mActivityView.getLayoutParams();
            lp.height = mBubbleHeight;
            mActivityView.setLayoutParams(lp);
            mActivityView.setVisibility(VISIBLE);
        } else {
            // Hide activity view if we had it previously
            mActivityView.setVisibility(GONE);

            // Use notification view
            mNotifRow = mEntry.getRow();
            addView(mNotifRow);
        }
        updateView();
    }

    boolean performBackPressIfNeeded() {
        if (mActivityView == null || !usingActivityView()) {
            return false;
        }
        mActivityView.performBackPress();
        return true;
    }

    @Override
    public void onClick(View view) {
        if (mEntry == null) {
            return;
        }
        Notification n = mEntry.notification.getNotification();
        int id = view.getId();
        if (id == R.id.deep_link_button) {
            mStackView.collapseStack(() -> {
                try {
                    n.contentIntent.send();
                    logBubbleClickEvent(mEntry.notification,
                            StatsLog.BUBBLE_UICHANGED__ACTION__HEADER_GO_TO_APP);
                } catch (PendingIntent.CanceledException e) {
                    Log.w(TAG, "Failed to send intent for bubble with key: "
                            + (mEntry != null ? mEntry.key : " null entry"));
                }
            });
        } else if (id == R.id.settings_button) {
            Intent intent = getSettingsIntent(mEntry.notification.getPackageName(),
                    mEntry.notification.getUid());
            mStackView.collapseStack(() -> {
                mContext.startActivity(intent);
                logBubbleClickEvent(mEntry.notification,
                        StatsLog.BUBBLE_UICHANGED__ACTION__HEADER_GO_TO_SETTINGS);
            });
        } else if (id == R.id.no_bubbles_button) {
            setBubblesAllowed(false);
        } else if (id == R.id.yes_bubbles_button) {
            setBubblesAllowed(true);
        }
    }

    private void setBubblesAllowed(boolean allowed) {
        try {
            mNotificationManagerService.setBubblesAllowed(
                    mEntry.notification.getPackageName(),
                    mEntry.notification.getUid(),
                    allowed);
            if (allowed) {
                mPermissionView.setVisibility(GONE);
                mHeaderView.setVisibility(VISIBLE);
            } else if (mOnBubbleBlockedListener != null) {
                mOnBubbleBlockedListener.onBubbleBlocked(mEntry);
            }
            logBubbleClickEvent(mEntry.notification,
                    allowed ? StatsLog.BUBBLE_UICHANGED__ACTION__PERMISSION_OPT_IN :
                            StatsLog.BUBBLE_UICHANGED__ACTION__PERMISSION_OPT_OUT);
        } catch (RemoteException e) {
            Log.w(TAG, e);
        }
    }

    /**
     * Update appearance of the expanded view being displayed.
     */
    public void updateView() {
        if (usingActivityView()
                && mActivityView.getVisibility() == VISIBLE
                && mActivityView.isAttachedToWindow()) {
            mActivityView.onLocationChanged();
        } else if (mNotifRow != null) {
            applyRowState(mNotifRow);
        }
    }

    /**
     * Set the x position that the tip of the triangle should point to.
     */
    public void setPointerPosition(float x) {
        // Adjust for the pointer size
        x -= (mPointerView.getWidth() / 2f);
        mPointerView.setTranslationX(x);
    }

    /**
     * Removes and releases an ActivityView if one was previously created for this bubble.
     */
    public void destroyActivityView(ViewGroup tmpParent) {
        if (mActivityView == null) {
            return;
        }
        if (!mActivityViewReady) {
            // release not needed, never initialized?
            mActivityView = null;
            return;
        }
        // HACK: release() will crash if the view is not attached.
        if (!isAttachedToWindow()) {
            mActivityView.setVisibility(View.GONE);
            tmpParent.addView(this);
        }

        mActivityView.release();
        ((ViewGroup) getParent()).removeView(this);
        mActivityView = null;
        mActivityViewReady = false;
    }

    private boolean usingActivityView() {
        return mBubbleIntent != null;
    }

    private void applyRowState(ExpandableNotificationRow view) {
        view.reset();
        view.setHeadsUp(false);
        view.resetTranslation();
        view.setOnKeyguard(false);
        view.setOnAmbient(false);
        view.setClipBottomAmount(0);
        view.setClipTopAmount(0);
        view.setContentTransformationAmount(0, false);
        view.setIconsVisible(true);

        // TODO - Need to reset this (and others) when view goes back in shade, leave for now
        // view.setTopRoundness(1, false);
        // view.setBottomRoundness(1, false);

        ExpandableViewState viewState = view.getViewState();
        viewState = viewState == null ? new ExpandableViewState() : viewState;
        viewState.height = view.getIntrinsicHeight();
        viewState.gone = false;
        viewState.hidden = false;
        viewState.dimmed = false;
        viewState.dark = false;
        viewState.alpha = 1f;
        viewState.notGoneIndex = -1;
        viewState.xTranslation = 0;
        viewState.yTranslation = 0;
        viewState.zTranslation = 0;
        viewState.scaleX = 1;
        viewState.scaleY = 1;
        viewState.inShelf = true;
        viewState.headsUpIsVisible = false;
        viewState.applyToView(view);
    }

    private Intent getSettingsIntent(String packageName, final int appUid) {
        final Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName);
        intent.putExtra(Settings.EXTRA_APP_UID, appUid);
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    @Nullable
    private PendingIntent getBubbleIntent(NotificationEntry entry) {
        Notification notif = entry.notification.getNotification();
        String packageName = entry.notification.getPackageName();
        Notification.BubbleMetadata data = notif.getBubbleMetadata();
        if (data != null && canLaunchInActivityView(data.getIntent(), true /* enableLogging */,
                packageName)) {
            return data.getIntent();
        } else if (BubbleController.shouldUseContentIntent(mContext)
                && canLaunchInActivityView(notif.contentIntent, false /* enableLogging */,
                packageName)) {
            return notif.contentIntent;
        }
        return null;
    }

    /**
     * Whether an intent is properly configured to display in an {@link android.app.ActivityView}.
     *
     * @param intent the pending intent of the bubble.
     * @param enableLogging whether bubble developer error should be logged.
     * @param packageName the notification package name for this bubble.
     * @return
     */
    private boolean canLaunchInActivityView(PendingIntent intent, boolean enableLogging,
            String packageName) {
        if (intent == null) {
            return false;
        }
        ActivityInfo info =
                intent.getIntent().resolveActivityInfo(mContext.getPackageManager(), 0);
        if (info == null) {
            if (enableLogging) {
                StatsLog.write(StatsLog.BUBBLE_DEVELOPER_ERROR_REPORTED, packageName,
                        BUBBLE_DEVELOPER_ERROR_REPORTED__ERROR__ACTIVITY_INFO_MISSING);
            }
            return false;
        }
        if (!ActivityInfo.isResizeableMode(info.resizeMode)) {
            if (enableLogging) {
                StatsLog.write(StatsLog.BUBBLE_DEVELOPER_ERROR_REPORTED, packageName,
                        BUBBLE_DEVELOPER_ERROR_REPORTED__ERROR__ACTIVITY_INFO_NOT_RESIZABLE);
            }
            return false;
        }
        if (info.documentLaunchMode != DOCUMENT_LAUNCH_ALWAYS) {
            if (enableLogging) {
                StatsLog.write(StatsLog.BUBBLE_DEVELOPER_ERROR_REPORTED, packageName,
                        BUBBLE_DEVELOPER_ERROR_REPORTED__ERROR__DOCUMENT_LAUNCH_NOT_ALWAYS);
            }
            return false;
        }
        return (info.flags & ActivityInfo.FLAG_ALLOW_EMBEDDED) != 0;
    }

    /**
     * Listener that is notified when a bubble is blocked.
     */
    public interface OnBubbleBlockedListener {
        /**
         * Called when a bubble is blocked for the provided entry.
         */
        void onBubbleBlocked(NotificationEntry entry);
    }

    /**
     * Logs bubble UI click event.
     *
     * @param notification the bubble notification that user is interacting with.
     * @param action the user interaction enum.
     */
    private void logBubbleClickEvent(StatusBarNotification notification, int action) {
        StatsLog.write(StatsLog.BUBBLE_UI_CHANGED,
                notification.getPackageName(),
                notification.getNotification().getChannelId(),
                notification.getId(),
                mStackView.getBubbleIndex(mStackView.getExpandedBubble()),
                mStackView.getBubbleCount(),
                action,
                mStackView.getNormalizedXPosition(),
                mStackView.getNormalizedYPosition());
    }
}
