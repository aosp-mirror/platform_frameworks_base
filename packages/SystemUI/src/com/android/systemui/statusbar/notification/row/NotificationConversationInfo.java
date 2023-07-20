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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import static android.app.NotificationManager.BUBBLE_PREFERENCE_ALL;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_NONE;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_SELECTED;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_UNSPECIFIED;
import static android.app.NotificationManager.Policy.CONVERSATION_SENDERS_ANYONE;
import static android.app.NotificationManager.Policy.CONVERSATION_SENDERS_IMPORTANT;

import static com.android.systemui.animation.Interpolators.FAST_OUT_SLOW_IN;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.INotificationManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.notification.ConversationIconFactory;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.people.widget.PeopleSpaceWidgetManager;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.notification.NotificationChannelHelper;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.wmshell.BubblesManager;

import java.lang.annotation.Retention;
import java.util.Optional;

/**
 * The guts of a conversation notification revealed when performing a long press.
 */
public class NotificationConversationInfo extends LinearLayout implements
        NotificationGuts.GutsContent {
    private static final String TAG = "ConversationGuts";

    private INotificationManager mINotificationManager;
    private ShortcutManager mShortcutManager;
    private PackageManager mPm;
    private PeopleSpaceWidgetManager mPeopleSpaceWidgetManager;
    private ConversationIconFactory mIconFactory;
    private OnUserInteractionCallback mOnUserInteractionCallback;
    private Handler mMainHandler;
    private Handler mBgHandler;
    private Optional<BubblesManager> mBubblesManagerOptional;
    private ShadeController mShadeController;
    private String mPackageName;
    private String mAppName;
    private int mAppUid;
    private String mDelegatePkg;
    private NotificationChannel mNotificationChannel;
    private ShortcutInfo mShortcutInfo;
    private NotificationEntry mEntry;
    private StatusBarNotification mSbn;
    @Nullable private Notification.BubbleMetadata mBubbleMetadata;
    private Context mUserContext;
    private boolean mIsDeviceProvisioned;
    private int mAppBubble;

    private TextView mPriorityDescriptionView;
    private TextView mDefaultDescriptionView;
    private TextView mSilentDescriptionView;

    private @Action int mSelectedAction = -1;
    private boolean mPressedApply;

    private OnSettingsClickListener mOnSettingsClickListener;
    private NotificationGuts mGutsContainer;
    private OnConversationSettingsClickListener mOnConversationSettingsClickListener;

    @VisibleForTesting
    boolean mSkipPost = false;
    private int mActualHeight;

    @Retention(SOURCE)
    @IntDef({ACTION_DEFAULT, ACTION_HOME, ACTION_FAVORITE, ACTION_SNOOZE, ACTION_MUTE,
            ACTION_SETTINGS})
    private @interface Action {}
    static final int ACTION_DEFAULT = 0;
    static final int ACTION_HOME = 1;
    static final int ACTION_FAVORITE = 2;
    static final int ACTION_SNOOZE = 3;
    static final int ACTION_MUTE = 4;
    static final int ACTION_SETTINGS = 5;

    private OnClickListener mOnFavoriteClick = v -> {
        setSelectedAction(ACTION_FAVORITE);
        updateToggleActions(mSelectedAction, true);
    };

    private OnClickListener mOnDefaultClick = v -> {
        setSelectedAction(ACTION_DEFAULT);
        updateToggleActions(mSelectedAction, true);
    };

    private OnClickListener mOnMuteClick = v -> {
        setSelectedAction(ACTION_MUTE);
        updateToggleActions(mSelectedAction, true);
    };

    private OnClickListener mOnDone = v -> {
        mPressedApply = true;

        // If the user selected Priority and the previous selection was not priority, show a
        // People Tile add request.
        if (mSelectedAction == ACTION_FAVORITE && getPriority() != mSelectedAction) {
            mShadeController.animateCollapseShade();
            mPeopleSpaceWidgetManager.requestPinAppWidget(mShortcutInfo, new Bundle());
        }
        mGutsContainer.closeControls(v, /* save= */ true);
    };

    public NotificationConversationInfo(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public interface OnSettingsClickListener {
        void onClick(View v, NotificationChannel channel, int appUid);
    }

    public interface OnConversationSettingsClickListener {
        void onClick();
    }

    public interface OnAppSettingsClickListener {
        void onClick(View v, Intent intent);
    }

    @VisibleForTesting
    void setSelectedAction(int selectedAction) {
        if (mSelectedAction == selectedAction) {
            return;
        }

        mSelectedAction = selectedAction;
    }

    public void bindNotification(
            ShortcutManager shortcutManager,
            PackageManager pm,
            PeopleSpaceWidgetManager peopleSpaceWidgetManager,
            INotificationManager iNotificationManager,
            OnUserInteractionCallback onUserInteractionCallback,
            String pkg,
            NotificationChannel notificationChannel,
            NotificationEntry entry,
            Notification.BubbleMetadata bubbleMetadata,
            OnSettingsClickListener onSettingsClick,
            ConversationIconFactory conversationIconFactory,
            Context userContext,
            boolean isDeviceProvisioned,
            @Main Handler mainHandler,
            @Background Handler bgHandler,
            OnConversationSettingsClickListener onConversationSettingsClickListener,
            Optional<BubblesManager> bubblesManagerOptional,
            ShadeController shadeController) {
        mINotificationManager = iNotificationManager;
        mPeopleSpaceWidgetManager = peopleSpaceWidgetManager;
        mOnUserInteractionCallback = onUserInteractionCallback;
        mPackageName = pkg;
        mEntry = entry;
        mSbn = entry.getSbn();
        mPm = pm;
        mAppName = mPackageName;
        mOnSettingsClickListener = onSettingsClick;
        mNotificationChannel = notificationChannel;
        mAppUid = mSbn.getUid();
        mDelegatePkg = mSbn.getOpPkg();
        mIsDeviceProvisioned = isDeviceProvisioned;
        mOnConversationSettingsClickListener = onConversationSettingsClickListener;
        mIconFactory = conversationIconFactory;
        mUserContext = userContext;
        mBubbleMetadata = bubbleMetadata;
        mBubblesManagerOptional = bubblesManagerOptional;
        mShadeController = shadeController;
        mMainHandler = mainHandler;
        mBgHandler = bgHandler;
        mShortcutManager = shortcutManager;
        mShortcutInfo = entry.getRanking().getConversationShortcutInfo();
        if (mShortcutInfo == null) {
            throw new IllegalArgumentException("Does not have required information");
        }

        mNotificationChannel = NotificationChannelHelper.createConversationChannelIfNeeded(
                getContext(), mINotificationManager, entry, mNotificationChannel);

        try {
            mAppBubble = mINotificationManager.getBubblePreferenceForPackage(mPackageName, mAppUid);
        } catch (RemoteException e) {
            Log.e(TAG, "can't reach OS", e);
            mAppBubble = BUBBLE_PREFERENCE_SELECTED;
        }

        bindHeader();
        bindActions();

        View done = findViewById(R.id.done);
        done.setOnClickListener(mOnDone);
        done.setAccessibilityDelegate(mGutsContainer.getAccessibilityDelegate());
    }

    private boolean isSystemPackage(String packageName) {
        try {
            final UserHandle userHandle = mSbn.getUser();
            PackageManager pm = CentralSurfaces.getPackageManagerForUser(mContext,
                    userHandle.getIdentifier());
            PackageInfo packageInfo = pm.getPackageInfo(packageName,
                    PackageManager.GET_SIGNATURES);
            PackageInfo sys = pm.getPackageInfo("android", PackageManager.GET_SIGNATURES);
            return (packageInfo != null && packageInfo.signatures != null &&
                    sys.signatures[0].equals(packageInfo.signatures[0]));
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void bindActions() {

        // TODO: b/152050825
        /*
        Button home = findViewById(R.id.home);
        home.setOnClickListener(mOnHomeClick);
        home.setVisibility(mShortcutInfo != null
                && mShortcutManager.isRequestPinShortcutSupported()
                ? VISIBLE : GONE);

        Button snooze = findViewById(R.id.snooze);
        snooze.setOnClickListener(mOnSnoozeClick);
        */

        TextView defaultSummaryTextView = findViewById(R.id.default_summary);
        if (mAppBubble == BUBBLE_PREFERENCE_ALL
                && BubblesManager.areBubblesEnabled(mContext, mSbn.getUser())) {
            defaultSummaryTextView.setText(getResources().getString(
                    R.string.notification_channel_summary_default_with_bubbles, mAppName));
        } else {
            defaultSummaryTextView.setText(getResources().getString(
                    R.string.notification_channel_summary_default));
        }

        findViewById(R.id.priority).setOnClickListener(mOnFavoriteClick);
        findViewById(R.id.default_behavior).setOnClickListener(mOnDefaultClick);
        findViewById(R.id.silence).setOnClickListener(mOnMuteClick);

        final View settingsButton = findViewById(R.id.info);
        settingsButton.setOnClickListener(getSettingsOnClickListener());
        settingsButton.setVisibility(settingsButton.hasOnClickListeners() ? VISIBLE : GONE);

        // Force stop button
        final View killButton = findViewById(R.id.force_stop);
        boolean killButtonEnabled = Settings.System.getIntForUser(
                mContext.getContentResolver(),
                Settings.System.NOTIFICATION_GUTS_KILL_APP_BUTTON, 0,
                UserHandle.USER_CURRENT) != 0;
        if (killButtonEnabled && !isSystemPackage(mPackageName)) {
            killButton.setVisibility(View.VISIBLE);
            killButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    KeyguardManager keyguardManager = (KeyguardManager)
                            mContext.getSystemService(Context.KEYGUARD_SERVICE);
                    if (keyguardManager.inKeyguardRestrictedInputMode()) {
                        // Don't do anything
                        return;
                    }
                    final SystemUIDialog killDialog = new SystemUIDialog(mContext);
                    killDialog.setTitle(mContext.getText(R.string.force_stop_dlg_title));
                    killDialog.setMessage(mContext.getText(R.string.force_stop_dlg_text));
                    killDialog.setPositiveButton(
                            android.R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            // kill pkg
                            ActivityManager actMan =
                                    (ActivityManager) mContext.getSystemService(
                                    Context.ACTIVITY_SERVICE);
                            actMan.forceStopPackage(mPackageName);
                        }
                    });
                    killDialog.setNegativeButton(android.R.string.cancel, null);
                    killDialog.show();
                }
            });
        } else {
            killButton.setVisibility(View.GONE);
        }

        updateToggleActions(mSelectedAction == -1 ? getPriority() : mSelectedAction,
                false);
    }

    private void bindHeader() {
        bindConversationDetails();

        // Delegate
        bindDelegate();
    }

    private OnClickListener getSettingsOnClickListener() {
        if (mAppUid >= 0 && mOnSettingsClickListener != null && mIsDeviceProvisioned) {
            final int appUidF = mAppUid;
            return ((View view) -> {
                mOnSettingsClickListener.onClick(view, mNotificationChannel, appUidF);
            });
        }
        return null;
    }

    private void bindConversationDetails() {
        final TextView channelName = findViewById(R.id.parent_channel_name);
        channelName.setText(mNotificationChannel.getName());

        bindGroup();
        // TODO: bring back when channel name does not include name
        // bindName();
        bindPackage();
        bindIcon(mNotificationChannel.isImportantConversation());

        mPriorityDescriptionView = findViewById(R.id.priority_summary);
        if (willShowAsBubble() && willBypassDnd()) {
            mPriorityDescriptionView.setText(R.string.notification_channel_summary_priority_all);
        } else if (willShowAsBubble()) {
            mPriorityDescriptionView.setText(R.string.notification_channel_summary_priority_bubble);
        } else if (willBypassDnd()) {
            mPriorityDescriptionView.setText(R.string.notification_channel_summary_priority_dnd);
        } else {
            mPriorityDescriptionView.setText(
                    R.string.notification_channel_summary_priority_baseline);
        }
    }

    private void bindIcon(boolean important) {
        Drawable person =  mIconFactory.getBaseIconDrawable(mShortcutInfo);
        if (person == null) {
            person = mContext.getDrawable(R.drawable.ic_person).mutate();
            TypedArray ta = mContext.obtainStyledAttributes(new int[]{android.R.attr.colorAccent});
            int colorAccent = ta.getColor(0, 0);
            ta.recycle();
            person.setTint(colorAccent);
        }
        ImageView image = findViewById(R.id.conversation_icon);
        image.setImageDrawable(person);

        ImageView app = findViewById(R.id.conversation_icon_badge_icon);
        app.setImageDrawable(mIconFactory.getAppBadge(
                        mPackageName, UserHandle.getUserId(mSbn.getUid())));

        findViewById(R.id.conversation_icon_badge_ring).setVisibility(important ? VISIBLE : GONE);
    }

    private void bindPackage() {
        ApplicationInfo info;
        try {
            info = mPm.getApplicationInfo(
                    mPackageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE);
            if (info != null) {
                mAppName = String.valueOf(mPm.getApplicationLabel(info));
            }
        } catch (PackageManager.NameNotFoundException e) {
        }
        ((TextView) findViewById(R.id.pkg_name)).setText(mAppName);
    }

    private void bindDelegate() {
        TextView delegateView = findViewById(R.id.delegate_name);

        if (!TextUtils.equals(mPackageName, mDelegatePkg)) {
            // this notification was posted by a delegate!
            delegateView.setVisibility(View.VISIBLE);
        } else {
            delegateView.setVisibility(View.GONE);
        }
    }

    private void bindGroup() {
        // Set group information if this channel has an associated group.
        CharSequence groupName = null;
        if (mNotificationChannel != null && mNotificationChannel.getGroup() != null) {
            try {
                final NotificationChannelGroup notificationChannelGroup =
                        mINotificationManager.getNotificationChannelGroupForPackage(
                                mNotificationChannel.getGroup(), mPackageName, mAppUid);
                if (notificationChannelGroup != null) {
                    groupName = notificationChannelGroup.getName();
                }
            } catch (RemoteException e) {
            }
        }
        TextView groupNameView = findViewById(R.id.group_name);
        if (groupName != null) {
            groupNameView.setText(groupName);
            groupNameView.setVisibility(VISIBLE);
        } else {
            groupNameView.setVisibility(GONE);
        }
    }

    @Override
    public boolean post(Runnable action) {
        if (mSkipPost) {
            action.run();
            return true;
        } else {
            return super.post(action);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mDefaultDescriptionView = findViewById(R.id.default_summary);
        mSilentDescriptionView = findViewById(R.id.silence_summary);
    }

    @Override
    public void onFinishedClosing() { }

    @Override
    public boolean needsFalsingProtection() {
        return true;
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        if (mGutsContainer != null &&
                event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (mGutsContainer.isExposed()) {
                event.getText().add(mContext.getString(
                        R.string.notification_channel_controls_opened_accessibility, mAppName));
            } else {
                event.getText().add(mContext.getString(
                        R.string.notification_channel_controls_closed_accessibility, mAppName));
            }
        }
    }

    private void updateToggleActions(int selectedAction, boolean userTriggered) {
        if (userTriggered) {
            TransitionSet transition = new TransitionSet();
            transition.setOrdering(TransitionSet.ORDERING_TOGETHER);
            transition.addTransition(new Fade(Fade.OUT))
                    .addTransition(new ChangeBounds())
                    .addTransition(
                            new Fade(Fade.IN)
                                    .setStartDelay(150)
                                    .setDuration(200)
                                    .setInterpolator(FAST_OUT_SLOW_IN));
            transition.setDuration(350);
            transition.setInterpolator(FAST_OUT_SLOW_IN);
            TransitionManager.beginDelayedTransition(this, transition);
        }

        View priority = findViewById(R.id.priority);
        View defaultBehavior = findViewById(R.id.default_behavior);
        View silence = findViewById(R.id.silence);

        switch (selectedAction) {
            case ACTION_FAVORITE:
                mPriorityDescriptionView.setVisibility(VISIBLE);
                mDefaultDescriptionView.setVisibility(GONE);
                mSilentDescriptionView.setVisibility(GONE);
                post(() -> {
                    priority.setSelected(true);
                    defaultBehavior.setSelected(false);
                    silence.setSelected(false);
                });
                break;

            case ACTION_MUTE:
                mSilentDescriptionView.setVisibility(VISIBLE);
                mDefaultDescriptionView.setVisibility(GONE);
                mPriorityDescriptionView.setVisibility(GONE);
                post(() -> {
                    priority.setSelected(false);
                    defaultBehavior.setSelected(false);
                    silence.setSelected(true);
                });
                break;

            case ACTION_DEFAULT:
                mDefaultDescriptionView.setVisibility(VISIBLE);
                mSilentDescriptionView.setVisibility(GONE);
                mPriorityDescriptionView.setVisibility(GONE);
                post(() -> {
                    priority.setSelected(false);
                    defaultBehavior.setSelected(true);
                    silence.setSelected(false);
                });
                break;

            default:
                throw new IllegalArgumentException("Unrecognized behavior: " + mSelectedAction);
        }

        boolean isAChange = getPriority() != selectedAction;
        TextView done = findViewById(R.id.done);
        done.setText(isAChange
                ? R.string.inline_ok_button
                : R.string.inline_done_button);

        // update icon in case importance has changed
        bindIcon(selectedAction == ACTION_FAVORITE);
    }

    int getSelectedAction() {
        return mSelectedAction;
    }

    private int getPriority() {
        if (mNotificationChannel.getImportance() <= IMPORTANCE_LOW
                && mNotificationChannel.getImportance() > IMPORTANCE_UNSPECIFIED) {
            return ACTION_MUTE;
        } else {
            if (mNotificationChannel.isImportantConversation()) {
                return ACTION_FAVORITE;
            }
        }
        return ACTION_DEFAULT;
    }

    private void updateChannel() {
        mBgHandler.post(
                new UpdateChannelRunnable(mINotificationManager, mPackageName,
                        mAppUid, mSelectedAction, mNotificationChannel));
        mEntry.markForUserTriggeredMovement(true);
        mMainHandler.postDelayed(
                () -> mOnUserInteractionCallback.onImportanceChanged(mEntry),
                StackStateAnimator.ANIMATION_DURATION_STANDARD);
    }

    private boolean willBypassDnd() {
        boolean bypassesDnd = false;
        try {
            int allowedSenders = mINotificationManager
                    .getConsolidatedNotificationPolicy().priorityConversationSenders;
            bypassesDnd =  allowedSenders == CONVERSATION_SENDERS_IMPORTANT
                    || allowedSenders == CONVERSATION_SENDERS_ANYONE;
        } catch (RemoteException e) {
            Log.e(TAG, "Could not check conversation senders", e);
        }
        return bypassesDnd;
    }

    private boolean willShowAsBubble() {
        return mBubbleMetadata != null
                && BubblesManager.areBubblesEnabled(mContext, mSbn.getUser());
    }

    @Override
    public void setGutsParent(NotificationGuts guts) {
        mGutsContainer = guts;
    }

    @Override
    public boolean willBeRemoved() {
        return false;
    }

    @Override
    public boolean shouldBeSavedOnClose() {
        return mPressedApply;
    }

    @Override
    public View getContentView() {
        return this;
    }

    @Override
    public boolean handleCloseControls(boolean save, boolean force) {
        if (save && mSelectedAction > -1) {
            updateChannel();
        }

        // Clear the selected importance when closing, so when when we open again,
        // we starts from a clean state.
        mSelectedAction = -1;
        mPressedApply = false;

        return false;
    }

    @Override
    public int getActualHeight() {
        // Because we're animating the bounds, getHeight will return the small height at the
        // beginning of the animation. Instead we'd want it to already return the end value
        return mActualHeight;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mActualHeight = getHeight();
    }

    @VisibleForTesting
    public boolean isAnimating() {
        return false;
    }

    class UpdateChannelRunnable implements Runnable {

        private final INotificationManager mINotificationManager;
        private final String mAppPkg;
        private final int mAppUid;
        private  NotificationChannel mChannelToUpdate;
        private final @Action int mAction;

        public UpdateChannelRunnable(INotificationManager notificationManager,
                String packageName, int appUid, @Action int action,
                @NonNull NotificationChannel channelToUpdate) {
            mINotificationManager = notificationManager;
            mAppPkg = packageName;
            mAppUid = appUid;
            mChannelToUpdate = channelToUpdate;
            mAction = action;
        }

        @Override
        public void run() {
            try {
                switch (mAction) {
                    case ACTION_FAVORITE:
                        mChannelToUpdate.setImportantConversation(true);
                        if (mChannelToUpdate.isImportantConversation()) {
                            mChannelToUpdate.setAllowBubbles(true);
                            if (mAppBubble == BUBBLE_PREFERENCE_NONE) {
                                mINotificationManager.setBubblesAllowed(mAppPkg, mAppUid,
                                        BUBBLE_PREFERENCE_SELECTED);
                            }
                            if (mBubblesManagerOptional.isPresent()) {
                                post(() -> mBubblesManagerOptional.get()
                                        .onUserSetImportantConversation(mEntry));
                            }
                        }
                        mChannelToUpdate.setImportance(Math.max(
                                mChannelToUpdate.getOriginalImportance(), IMPORTANCE_DEFAULT));
                        break;
                    case ACTION_DEFAULT:
                        mChannelToUpdate.setImportance(Math.max(
                                mChannelToUpdate.getOriginalImportance(), IMPORTANCE_DEFAULT));
                        if (mChannelToUpdate.isImportantConversation()) {
                            mChannelToUpdate.setImportantConversation(false);
                            mChannelToUpdate.setAllowBubbles(false);
                        }
                        break;
                    case ACTION_MUTE:
                        if (mChannelToUpdate.getImportance() == IMPORTANCE_UNSPECIFIED
                                || mChannelToUpdate.getImportance() >= IMPORTANCE_DEFAULT) {
                            mChannelToUpdate.setImportance(IMPORTANCE_LOW);
                        }
                        if (mChannelToUpdate.isImportantConversation()) {
                            mChannelToUpdate.setImportantConversation(false);
                            mChannelToUpdate.setAllowBubbles(false);
                        }
                        break;
                }

                mINotificationManager.updateNotificationChannelForPackage(
                            mAppPkg, mAppUid, mChannelToUpdate);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to update notification channel", e);
            }
        }
    }
}
