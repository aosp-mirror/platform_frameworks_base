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
import static android.view.Display.INVALID_DISPLAY;

import android.annotation.Nullable;
import android.app.ActivityOptions;
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
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.util.Log;
import android.util.StatsLog;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.recents.TriangleShape;
import com.android.systemui.statusbar.AlphaOptimizedButton;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.stack.ExpandableViewState;

/**
 * Container for the expanded bubble view, handles rendering the caret and settings icon.
 */
public class BubbleExpandedView extends LinearLayout implements View.OnClickListener {
    private static final String TAG = "BubbleExpandedView";

    // The triangle pointing to the expanded view
    private View mPointerView;
    private int mPointerMargin;

    private AlphaOptimizedButton mSettingsIcon;

    // Views for expanded state
    private ExpandableNotificationRow mNotifRow;
    private ActivityView mActivityView;

    private boolean mActivityViewReady = false;
    private PendingIntent mBubbleIntent;

    private boolean mKeyboardVisible;
    private boolean mNeedsNewHeight;

    private int mMinHeight;
    private int mSettingsIconHeight;
    private int mBubbleHeight;
    private int mPointerWidth;
    private int mPointerHeight;

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
                // Custom options so there is no activity transition animation
                ActivityOptions options = ActivityOptions.makeCustomAnimation(getContext(),
                        0 /* enterResId */, 0 /* exitResId */);
                // Post to keep the lifecycle normal
                post(() -> mActivityView.startActivity(mBubbleIntent, options));
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
                post(() -> mBubbleController.removeBubble(mEntry.key,
                        BubbleController.DISMISS_TASK_FINISHED));
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
        mMinHeight = getResources().getDimensionPixelSize(
                R.dimen.bubble_expanded_default_height);
        mPointerMargin = getResources().getDimensionPixelSize(R.dimen.bubble_pointer_margin);
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
        mPointerWidth = res.getDimensionPixelSize(R.dimen.bubble_pointer_width);
        mPointerHeight = res.getDimensionPixelSize(R.dimen.bubble_pointer_height);

        TypedArray ta = getContext().obtainStyledAttributes(
                new int[] {android.R.attr.colorBackgroundFloating});
        int bgColor = ta.getColor(0, Color.WHITE);
        ta.recycle();

        ShapeDrawable triangleDrawable = new ShapeDrawable(TriangleShape.create(
                mPointerWidth, mPointerHeight, false /* pointUp */));

        triangleDrawable.setTint(bgColor);
        mPointerView.setBackground(triangleDrawable);

        mSettingsIconHeight = getContext().getResources().getDimensionPixelSize(
                R.dimen.bubble_expanded_header_height);
        mSettingsIcon = findViewById(R.id.settings_button);
        mSettingsIcon.setOnClickListener(this);

        mActivityView = new ActivityView(mContext, null /* attrs */, 0 /* defStyle */,
                true /* singleTaskInstance */);
        addView(mActivityView);

        // Make sure pointer is below activity view
        bringChildToFront(mPointerView);

        setOnApplyWindowInsetsListener((View view, WindowInsets insets) -> {
            // Keep track of IME displaying because we should not make any adjustments that might
            // cause a config change while the IME is displayed otherwise it'll loose focus.
            final int keyboardHeight = insets.getSystemWindowInsetBottom()
                    - insets.getStableInsetBottom();
            mKeyboardVisible = keyboardHeight != 0;
            if (!mKeyboardVisible && mNeedsNewHeight) {
                updateHeight();
            }
            return view.onApplyWindowInsets(insets);
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mKeyboardVisible = false;
        mNeedsNewHeight = false;
        if (mActivityView != null) {
            mActivityView.setForwardedInsets(Insets.of(0, 0, 0, 0));
        }
    }

    /**
     * Called by {@link BubbleStackView} when the insets for the expanded state should be updated.
     * This should be done post-move and post-animation.
     */
    void updateInsets(WindowInsets insets) {
        if (usingActivityView()) {
            Point displaySize = new Point();
            mActivityView.getContext().getDisplay().getSize(displaySize);
            int[] windowLocation = mActivityView.getLocationOnScreen();
            final int windowBottom = windowLocation[1] + mActivityView.getHeight();
            final int keyboardHeight = insets.getSystemWindowInsetBottom()
                    - insets.getStableInsetBottom();
            final int insetsBottom = Math.max(0,
                    windowBottom + keyboardHeight - displaySize.y);
            mActivityView.setForwardedInsets(Insets.of(0, 0, 0, insetsBottom));
        }
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
        updateTheme();
        showSettingsIcon();
        updateExpandedView();
    }

    /**
     * Lets activity view know it should be shown / populated.
     */
    public void populateExpandedView() {
        if (usingActivityView()) {
            mActivityView.setCallback(mStateCallback);
        } else {
            // We're using notification template
            ViewGroup parent = (ViewGroup) mNotifRow.getParent();
            if (parent == this) {
                // Already added
                return;
            } else if (parent != null) {
                // Still in the shade... remove it
                parent.removeView(mNotifRow);
            }
            addView(mNotifRow, 1 /* index */);
        }
    }

    /**
     * Updates the entry backing this view. This will not re-populate ActivityView, it will
     * only update the deep-links in the title, and the height of the view.
     */
    public void update(NotificationEntry entry) {
        if (entry.key.equals(mEntry.key)) {
            mEntry = entry;
            updateSettingsContentDescription();
            updateHeight();
        } else {
            Log.w(TAG, "Trying to update entry with different key, new entry: "
                    + entry.key + " old entry: " + mEntry.key);
        }
    }

    void updateTheme() {
        // Get new colors.
        TypedArray ta = mContext.obtainStyledAttributes(
                new int[]{android.R.attr.colorBackgroundFloating, android.R.attr.colorForeground});
        int backgroundColor = ta.getColor(0, Color.WHITE /* default */);
        int foregroundColor = ta.getColor(1, Color.BLACK /* default */);
        ta.recycle();

        // Update triangle color.
        ShapeDrawable triangleDrawable = new ShapeDrawable(
                TriangleShape.create(mPointerWidth, mPointerHeight, false /* pointUp */));
        triangleDrawable.setTint(backgroundColor);
        mPointerView.setBackground(triangleDrawable);
    }

    private void updateExpandedView() {
        mBubbleIntent = getBubbleIntent(mEntry);
        if (mBubbleIntent != null) {
            if (mNotifRow != null) {
                // Clear out the row if we had it previously
                removeView(mNotifRow);
                mNotifRow = null;
            }
            mActivityView.setVisibility(VISIBLE);
        } else {
            // Hide activity view if we had it previously
            mActivityView.setVisibility(GONE);
            mNotifRow = mEntry.getRow();

        }
        updateView();
    }

    boolean performBackPressIfNeeded() {
        if (!usingActivityView()) {
            return false;
        }
        mActivityView.performBackPress();
        return true;
    }

    /**
     * @return total height that the expanded view occupies.
     */
    int getExpandedSize() {
        return mBubbleHeight + mPointerView.getHeight() + mPointerMargin
                + mSettingsIconHeight;
    }

    void updateHeight() {
        if (usingActivityView()) {
            Notification.BubbleMetadata data = mEntry.getBubbleMetadata();
            float desiredHeight;
            if (data == null) {
                // This is a contentIntent based bubble, lets allow it to be the max height
                // as it was forced into this mode and not prepared to be small
                desiredHeight = mStackView.getMaxExpandedHeight();
            } else {
                boolean useRes = data.getDesiredHeightResId() != 0;
                float desiredPx;
                if (useRes) {
                    desiredPx = getDimenForPackageUser(data.getDesiredHeightResId(),
                            mEntry.notification.getPackageName(),
                            mEntry.notification.getUser().getIdentifier());
                } else {
                    desiredPx = data.getDesiredHeight()
                            * getContext().getResources().getDisplayMetrics().density;
                }
                desiredHeight = desiredPx > 0 ? desiredPx : mMinHeight;
            }
            int max = mStackView.getMaxExpandedHeight() - mSettingsIconHeight
                    - mPointerView.getHeight() - mPointerMargin;
            float height = Math.min(desiredHeight, max);
            height = Math.max(height, mMinHeight);
            LayoutParams lp = (LayoutParams) mActivityView.getLayoutParams();
            mNeedsNewHeight =  lp.height != height;
            if (!mKeyboardVisible) {
                // If the keyboard is visible... don't adjust the height because that will cause
                // a configuration change and the keyboard will be lost.
                lp.height = (int) height;
                mBubbleHeight = (int) height;
                mActivityView.setLayoutParams(lp);
                mNeedsNewHeight = false;
            }
        } else {
            mBubbleHeight = mNotifRow != null ? mNotifRow.getIntrinsicHeight() : mMinHeight;
        }
    }

    @Override
    public void onClick(View view) {
        if (mEntry == null) {
            return;
        }
        Notification n = mEntry.notification.getNotification();
        int id = view.getId();
        if (id == R.id.settings_button) {
            Intent intent = getSettingsIntent(mEntry.notification.getPackageName(),
                    mEntry.notification.getUid());
            mStackView.collapseStack(() -> {
                mContext.startActivity(intent);
                logBubbleClickEvent(mEntry,
                        StatsLog.BUBBLE_UICHANGED__ACTION__HEADER_GO_TO_SETTINGS);
            });
        }
    }

    private void updateSettingsContentDescription() {
        mSettingsIcon.setContentDescription(getResources().getString(
                R.string.bubbles_settings_button_description, mAppName));
    }

    void showSettingsIcon() {
        updateSettingsContentDescription();
        mSettingsIcon.setVisibility(VISIBLE);
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
        updateHeight();
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
    public void cleanUpExpandedState() {
        removeView(mNotifRow);

        if (mActivityView == null) {
            return;
        }
        if (mActivityViewReady) {
            mActivityView.release();
        }
        removeView(mActivityView);
        mActivityView = null;
        mActivityViewReady = false;
    }

    private boolean usingActivityView() {
        return mBubbleIntent != null && mActivityView != null;
    }

    /**
     * @return the display id of the virtual display.
     */
    public int getVirtualDisplayId() {
        if (usingActivityView()) {
            return mActivityView.getVirtualDisplayId();
        }
        return INVALID_DISPLAY;
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
        final Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName);
        intent.putExtra(Settings.EXTRA_APP_UID, appUid);
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
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
     * @param entry the bubble notification entry that user is interacting with.
     * @param action the user interaction enum.
     */
    private void logBubbleClickEvent(NotificationEntry entry, int action) {
        StatusBarNotification notification = entry.notification;
        StatsLog.write(StatsLog.BUBBLE_UI_CHANGED,
                notification.getPackageName(),
                notification.getNotification().getChannelId(),
                notification.getId(),
                mStackView.getBubbleIndex(mStackView.getExpandedBubble()),
                mStackView.getBubbleCount(),
                action,
                mStackView.getNormalizedXPosition(),
                mStackView.getNormalizedYPosition(),
                entry.showInShadeWhenBubble(),
                entry.isForegroundService(),
                BubbleController.isForegroundApp(mContext, notification.getPackageName()));
    }

    private int getDimenForPackageUser(int resId, String pkg, int userId) {
        Resources r;
        if (pkg != null) {
            try {
                if (userId == UserHandle.USER_ALL) {
                    userId = UserHandle.USER_SYSTEM;
                }
                r = mPm.getResourcesForApplicationAsUser(pkg, userId);
                return r.getDimensionPixelSize(resId);
            } catch (PackageManager.NameNotFoundException ex) {
                // Uninstalled, don't care
            } catch (Resources.NotFoundException e) {
                // Invalid res id, return 0 and user our default
                Log.e(TAG, "Couldn't find desired height res id", e);
            }
        }
        return 0;
    }
}
