/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.packageinstaller.permission.ui.handheld;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.transition.ChangeBounds;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.transition.TransitionValues;
import android.transition.Visibility;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.WindowManager.LayoutParams;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Space;
import android.widget.TextView;

import com.android.packageinstaller.R;
import com.android.packageinstaller.permission.ui.ButtonBarLayout;
import com.android.packageinstaller.permission.ui.GrantPermissionsViewHandler;
import com.android.packageinstaller.permission.ui.ManagePermissionsActivity;
import com.android.packageinstaller.permission.ui.ManualLayoutFrame;

public class GrantPermissionsViewHandlerImpl implements GrantPermissionsViewHandler,
        OnClickListener, RadioGroup.OnCheckedChangeListener {

    public static final String ARG_GROUP_NAME = "ARG_GROUP_NAME";
    public static final String ARG_GROUP_COUNT = "ARG_GROUP_COUNT";
    public static final String ARG_GROUP_INDEX = "ARG_GROUP_INDEX";
    public static final String ARG_GROUP_ICON = "ARG_GROUP_ICON";
    public static final String ARG_GROUP_MESSAGE = "ARG_GROUP_MESSAGE";
    private static final String ARG_GROUP_DETAIL_MESSAGE = "ARG_GROUP_DETAIL_MESSAGE";

    public static final String ARG_GROUP_SHOW_DO_NOT_ASK = "ARG_GROUP_SHOW_DO_NOT_ASK";
    private static final String ARG_GROUP_SHOW_FOREGOUND_CHOOSER =
            "ARG_GROUP_SHOW_FOREGOUND_CHOOSER";

    public static final String ARG_GROUP_DO_NOT_ASK_CHECKED = "ARG_GROUP_DO_NOT_ASK_CHECKED";
    private static final String ARG_GROUP_ALWAYS_OPTION_CHECKED = "ARG_GROUP_ALWAYS_OPTION_CHECKED";

    // Animation parameters.
    private static final long SWITCH_TIME_MILLIS = 75;
    private static final long ANIMATION_DURATION_MILLIS = 200;

    private final Activity mActivity;
    private final String mAppPackageName;
    private final boolean mPermissionsIndividuallyControlled;

    private ResultListener mResultListener;

    // Configuration of the current dialog
    private String mGroupName;
    private int mGroupCount;
    private int mGroupIndex;
    private Icon mGroupIcon;
    private CharSequence mGroupMessage;
    private CharSequence mDetailMessage;

    private boolean mShowDonNotAsk;
    private boolean mShowForegroundChooser;

    // Views
    private ImageView mIconView;
    private TextView mCurrentGroupView;
    private TextView mMessageView;
    private TextView mDetailMessageView;
    private CheckBox mDoNotAskCheckbox;
    private RadioGroup mForegroundChooser;
    private RadioButton mForegroundOnlyOption;
    private RadioButton mAlwaysOption;
    private RadioButton mDenyAndDontAskAgainOption;
    private Button mAllowButton;
    private Button mMoreInfoButton;
    private ManualLayoutFrame mRootView;
    private ViewGroup mContentContainer;
    private Space mSpacer;

    public GrantPermissionsViewHandlerImpl(Activity activity, String appPackageName) {
        mActivity = activity;
        mAppPackageName = appPackageName;
        mPermissionsIndividuallyControlled =
                activity.getPackageManager().arePermissionsIndividuallyControlled();
    }

    @Override
    public GrantPermissionsViewHandlerImpl setResultListener(ResultListener listener) {
        mResultListener = listener;
        return this;
    }

    @Override
    public void saveInstanceState(Bundle arguments) {
        arguments.putString(ARG_GROUP_NAME, mGroupName);
        arguments.putInt(ARG_GROUP_COUNT, mGroupCount);
        arguments.putInt(ARG_GROUP_INDEX, mGroupIndex);
        arguments.putParcelable(ARG_GROUP_ICON, mGroupIcon);
        arguments.putCharSequence(ARG_GROUP_MESSAGE, mGroupMessage);
        arguments.putCharSequence(ARG_GROUP_DETAIL_MESSAGE, mDetailMessage);

        arguments.putBoolean(ARG_GROUP_SHOW_DO_NOT_ASK, mShowDonNotAsk);
        arguments.putBoolean(ARG_GROUP_SHOW_FOREGOUND_CHOOSER, mShowForegroundChooser);

        arguments.putBoolean(ARG_GROUP_DO_NOT_ASK_CHECKED, isDoNotAskAgainChecked());
        arguments.putBoolean(ARG_GROUP_ALWAYS_OPTION_CHECKED, mAlwaysOption.isSelected());
    }

    @Override
    public void loadInstanceState(Bundle savedInstanceState) {
        mGroupName = savedInstanceState.getString(ARG_GROUP_NAME);
        mGroupMessage = savedInstanceState.getCharSequence(ARG_GROUP_MESSAGE);
        mGroupIcon = savedInstanceState.getParcelable(ARG_GROUP_ICON);
        mGroupCount = savedInstanceState.getInt(ARG_GROUP_COUNT);
        mGroupIndex = savedInstanceState.getInt(ARG_GROUP_INDEX);
        mDetailMessage = savedInstanceState.getCharSequence(ARG_GROUP_DETAIL_MESSAGE);

        mShowDonNotAsk = savedInstanceState.getBoolean(ARG_GROUP_SHOW_DO_NOT_ASK);
        mShowForegroundChooser = savedInstanceState.getBoolean(ARG_GROUP_SHOW_FOREGOUND_CHOOSER);

        updateAll(savedInstanceState.getBoolean(ARG_GROUP_DO_NOT_ASK_CHECKED),
                savedInstanceState.getBoolean(ARG_GROUP_ALWAYS_OPTION_CHECKED));
    }

    @Override
    public void updateUi(String groupName, int groupCount, int groupIndex, Icon icon,
            CharSequence message, CharSequence detailMessage, boolean showForegroundChooser,
            boolean showDonNotAsk) {
        boolean isNewGroup = mGroupIndex != groupIndex;
        boolean isDoNotAskAgainChecked = mDoNotAskCheckbox.isChecked();
        boolean isAlwaysOptionChecked = mAlwaysOption.isChecked();
        if (isNewGroup) {
            isDoNotAskAgainChecked = false;
            isAlwaysOptionChecked = false;
        }

        mGroupName = groupName;
        mGroupCount = groupCount;
        mGroupIndex = groupIndex;
        mGroupIcon = icon;
        mGroupMessage = message;
        mShowDonNotAsk = showDonNotAsk;
        mDetailMessage = detailMessage;
        mShowForegroundChooser = showForegroundChooser;

        // If this is a second (or later) permission and the views exist, then animate.
        if (mIconView != null) {
            if (isNewGroup) {
                animateToPermission(isDoNotAskAgainChecked, isAlwaysOptionChecked);
            } else {
                updateAll(isDoNotAskAgainChecked, isAlwaysOptionChecked);
            }
        }
    }

    private void updateAll(boolean isDoNotAskAgainChecked, boolean isAlwaysOptionChecked) {
        updateDescription();
        updateDetailDescription();
        updateGroup();
        updateDoNotAskCheckBoxAndForegroundOption(isDoNotAskAgainChecked, isAlwaysOptionChecked);
    }

    private void animateToPermission(boolean isDoNotAskAgainChecked,
            boolean isAlwaysOptionChecked) {
        final View newContent = bindNewContent();

        updateDescription();
        updateDetailDescription();
        updateDoNotAskCheckBoxAndForegroundOption(isDoNotAskAgainChecked, isAlwaysOptionChecked);
        // Update group when the content changes (in onAppear below)

        final View oldView = mContentContainer.getChildAt(0);

        // Grow or shrink the content container to size of new content
        ChangeBounds growShrinkToNewContentSize = new ChangeBounds();
        growShrinkToNewContentSize.setDuration(ANIMATION_DURATION_MILLIS);
        growShrinkToNewContentSize.setInterpolator(AnimationUtils.loadInterpolator(mActivity,
                android.R.interpolator.fast_out_slow_in));

        // With a delay hide the old content and show the new content
        Visibility changeContent = new Visibility() {
            @Override
            public Animator onAppear(ViewGroup sceneRoot, View view, TransitionValues startValues,
                    TransitionValues endValues) {
                view.setVisibility(View.INVISIBLE);

                ValueAnimator v = ValueAnimator.ofFloat(0, 1);

                v.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        view.setVisibility(View.VISIBLE);
                        updateGroup();
                    }
                });

                return v;
            }

            @Override
            public Animator onDisappear(ViewGroup sceneRoot, final View view,
                    TransitionValues startValues, TransitionValues endValues) {
                ValueAnimator v =  ValueAnimator.ofFloat(0, 1);

                int[] location = new int[2];
                // The removed view is put into the overlay that is relative to the window. Hence
                // it does not get moved along with the changing parent view. This is done manually
                // here.
                v.addUpdateListener(animation -> {
                    mContentContainer.getLocationInWindow(location);
                    view.setTop(location[1]);
                });

                return v;
            }
        };
        changeContent.setDuration(SWITCH_TIME_MILLIS);

        TransitionSet combinedAnimation = new TransitionSet();
        combinedAnimation.addTransition(growShrinkToNewContentSize);
        combinedAnimation.addTransition(changeContent);
        combinedAnimation.setOrdering(TransitionSet.ORDERING_TOGETHER);
        combinedAnimation.setMatchOrder(Transition.MATCH_INSTANCE);

        TransitionManager.beginDelayedTransition(mRootView, combinedAnimation);
        mContentContainer.removeView(oldView);
        mContentContainer.addView(newContent);
    }

    /**
     * Update this objects fields to point to the a content view. A content view encapsulates the
     * permission request message, the detail message, the always deny checkbox, and the foreground
     * chooser.
     *
     * @return The new content view
     */
    private View bindNewContent() {
        ViewGroup content = (ViewGroup) LayoutInflater.from(mActivity)
                .inflate(R.layout.grant_permissions_content, mContentContainer, false);

        mMessageView = content.requireViewById(R.id.permission_message);
        mDetailMessageView = content.requireViewById(R.id.detail_message);
        mIconView = content.requireViewById(R.id.permission_icon);
        mDoNotAskCheckbox = content.requireViewById(R.id.do_not_ask_checkbox);
        mSpacer = content.requireViewById(R.id.detail_message_do_not_ask_checkbox_space);
        mForegroundChooser = content.requireViewById(R.id.foreground_or_always_radiogroup);
        mForegroundOnlyOption = content.requireViewById(R.id.foreground_only_radio_button);
        mAlwaysOption = content.requireViewById(R.id.always_radio_button);
        mDenyAndDontAskAgainOption = content.requireViewById(R.id.deny_dont_ask_again_radio_button);

        mDoNotAskCheckbox.setOnClickListener(this);
        mDenyAndDontAskAgainOption.setOnClickListener(this);
        mForegroundChooser.setOnCheckedChangeListener(this);

        return content;
    }

    @Override
    public View createView() {
        mRootView = (ManualLayoutFrame) LayoutInflater.from(mActivity)
                .inflate(R.layout.grant_permissions, null);
        mContentContainer = mRootView.requireViewById(R.id.content_container);
        mContentContainer.removeAllViews();
        mContentContainer.addView(bindNewContent());

        mCurrentGroupView = (TextView) mRootView.findViewById(R.id.current_page_text);
        mAllowButton = (Button) mRootView.findViewById(R.id.permission_allow_button);
        mAllowButton.setOnClickListener(this);

        if (mPermissionsIndividuallyControlled) {
            mMoreInfoButton = (Button) mRootView.findViewById(R.id.permission_more_info_button);
            mMoreInfoButton.setVisibility(View.VISIBLE);
            mMoreInfoButton.setOnClickListener(this);
        }

        mRootView.findViewById(R.id.permission_deny_button).setOnClickListener(this);

        ((ButtonBarLayout) mRootView.requireViewById(R.id.button_group)).setAllowStacking(true);

        if (mGroupName != null) {
            updateAll(false, false);
        }

        return mRootView;
    }

    @Override
    public void updateWindowAttributes(LayoutParams outLayoutParams) {
        // No-op
    }

    private void updateDescription() {
        if (mGroupIcon != null) {
            mIconView.setImageDrawable(mGroupIcon.loadDrawable(mActivity));
        }
        mMessageView.setText(mGroupMessage);
    }

    private void updateDetailDescription() {
        if (mDetailMessage == null) {
            mDetailMessageView.setVisibility(View.GONE);
            mSpacer.setVisibility(View.GONE);
        } else {
            if (mShowDonNotAsk) {
                mSpacer.setVisibility(View.VISIBLE);
            } else {
                mSpacer.setVisibility(View.GONE);
            }

            mDetailMessageView.setText(mDetailMessage);
            mDetailMessageView.setVisibility(View.VISIBLE);
        }
    }

    private void updateGroup() {
        if (mGroupCount > 1) {
            mCurrentGroupView.setVisibility(View.VISIBLE);
            mCurrentGroupView.setText(mActivity.getString(R.string.current_permission_template,
                    mGroupIndex + 1, mGroupCount));
        } else {
            mCurrentGroupView.setVisibility(View.GONE);
        }
    }

    private void updateDoNotAskCheckBoxAndForegroundOption(boolean isDoNotAskAgainChecked,
            boolean isAlwaysSelected) {
        if (mShowForegroundChooser) {
            mForegroundChooser.setVisibility(View.VISIBLE);
            mDoNotAskCheckbox.setVisibility(View.GONE);

            if (isAlwaysSelected) {
                mAlwaysOption.setSelected(true);
            }

            if (mShowDonNotAsk) {
                mDenyAndDontAskAgainOption.setSelected(isDoNotAskAgainChecked);
                mDenyAndDontAskAgainOption.setVisibility(View.VISIBLE);
            } else {
                mDenyAndDontAskAgainOption.setVisibility(View.GONE);
            }
        } else {
            mForegroundChooser.setVisibility(View.GONE);
            if (mShowDonNotAsk) {
                mDoNotAskCheckbox.setVisibility(View.VISIBLE);
                mDoNotAskCheckbox.setChecked(isDoNotAskAgainChecked);
            } else {
                mDoNotAskCheckbox.setVisibility(View.GONE);
            }
        }

        mAllowButton.setEnabled(!isDoNotAskAgainChecked() && isOptionChosenIfNeeded());
    }

    private boolean isDoNotAskAgainChecked() {
        return (mDoNotAskCheckbox.getVisibility() == View.VISIBLE
                && mDoNotAskCheckbox.isChecked())
                || (mDenyAndDontAskAgainOption.getVisibility() == View.VISIBLE
                && mDenyAndDontAskAgainOption.isChecked());
    }

    private boolean isOptionChosenIfNeeded() {
        return !mShowForegroundChooser
                || (mForegroundOnlyOption.isChecked()
                || (mDenyAndDontAskAgainOption.getVisibility() == View.VISIBLE
                && mDenyAndDontAskAgainOption.isChecked())
                || (mAlwaysOption.getVisibility() == View.VISIBLE && mAlwaysOption.isChecked()));
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.permission_allow_button:
                if (mResultListener != null) {
                    view.performAccessibilityAction(
                            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null);
                    if (mShowForegroundChooser && mForegroundOnlyOption.isChecked()) {
                        mResultListener.onPermissionGrantResult(mGroupName,
                                GRANTED_FOREGROUND_ONLY);
                    } else {
                        mResultListener.onPermissionGrantResult(mGroupName, GRANTED_ALWAYS);
                    }
                }
                break;
            case R.id.permission_deny_button:
                if (mResultListener != null) {
                    view.performAccessibilityAction(
                            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null);
                    if (isDoNotAskAgainChecked()) {
                        mResultListener.onPermissionGrantResult(mGroupName,
                                DENIED_DO_NOT_ASK_AGAIN);
                    } else {
                        mResultListener.onPermissionGrantResult(mGroupName, DENIED);
                    }
                }
                break;
            case R.id.permission_more_info_button:
                Intent intent = new Intent(Intent.ACTION_MANAGE_APP_PERMISSIONS);
                intent.putExtra(Intent.EXTRA_PACKAGE_NAME, mAppPackageName);
                intent.putExtra(ManagePermissionsActivity.EXTRA_ALL_PERMISSIONS, true);
                mActivity.startActivity(intent);
                break;
        }

        mAllowButton.setEnabled(!isDoNotAskAgainChecked() && isOptionChosenIfNeeded());

    }

    @Override
    public void onBackPressed() {
        if (mResultListener != null) {
            mResultListener.onPermissionGrantResult(mGroupName, DENIED);
        }
    }

    @Override
    public void onCheckedChanged(RadioGroup group, int checkedId) {
        mAllowButton.setEnabled(!isDoNotAskAgainChecked() && isOptionChosenIfNeeded());
    }
}
