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

package com.android.wm.shell.pip.tv;

import static android.view.KeyEvent.ACTION_UP;
import static android.view.KeyEvent.KEYCODE_BACK;
import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_LEFT;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;
import static android.view.KeyEvent.KEYCODE_ENTER;

import static com.android.wm.shell.pip.tv.TvPipAction.ACTION_CLOSE;
import static com.android.wm.shell.pip.tv.TvPipAction.ACTION_CUSTOM;
import static com.android.wm.shell.pip.tv.TvPipAction.ACTION_CUSTOM_CLOSE;
import static com.android.wm.shell.pip.tv.TvPipAction.ACTION_EXPAND_COLLAPSE;
import static com.android.wm.shell.pip.tv.TvPipAction.ACTION_FULLSCREEN;
import static com.android.wm.shell.pip.tv.TvPipAction.ACTION_MOVE;

import android.app.RemoteAction;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.widget.LinearLayoutManager;
import com.android.internal.widget.RecyclerView;
import com.android.wm.shell.R;
import com.android.wm.shell.common.TvWindowMenuActionButton;
import com.android.wm.shell.pip.PipUtils;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * A View that represents Pip Menu on TV. It's responsible for displaying 3 ever-present Pip Menu
 * actions: Fullscreen, Move and Close, but could also display "additional" actions, that may be set
 * via a {@link #setAdditionalActions(List, RemoteAction, Handler)} call.
 */
public class TvPipMenuView extends FrameLayout {
    private static final String TAG = "TvPipMenuView";

    private static final int CLOSE_ACTION_INDEX = 1;
    private static final int FIRST_CUSTOM_ACTION_INDEX = 2;

    private final TvPipMenuView.Listener mListener;

    private final List<TvPipAction> mActionsList;
    private final TvPipSystemAction mDefaultCloseAction;
    private final TvPipSystemAction mExpandCollapseAction;

    private final RecyclerView mActionButtonsRecyclerView;
    private final LinearLayoutManager mButtonLayoutManager;
    private final RecyclerViewAdapter mRecyclerViewAdapter;

    private final View mPipFrameView;
    private final View mMenuFrameView;
    private final View mPipView;

    private final View mPipBackground;
    private final View mDimLayer;

    private final TvPipMenuEduTextDrawer mEduTextDrawer;

    private final int mPipMenuOuterSpace;
    private final int mPipMenuBorderWidth;

    private final int mPipMenuFadeAnimationDuration;
    private final int mResizeAnimationDuration;

    private final ImageView mArrowUp;
    private final ImageView mArrowRight;
    private final ImageView mArrowDown;
    private final ImageView mArrowLeft;
    private final TvWindowMenuActionButton mA11yDoneButton;

    private Rect mCurrentPipBounds;
    private boolean mMoveMenuIsVisible;
    private boolean mButtonMenuIsVisible;
    private boolean mSwitchingOrientation;

    private final AccessibilityManager mA11yManager;
    private final Handler mMainHandler;

    public TvPipMenuView(@NonNull Context context, @NonNull Handler mainHandler,
            @NonNull Listener listener) {
        super(context, null, 0, 0);
        inflate(context, R.layout.tv_pip_menu, this);

        mMainHandler = mainHandler;
        mListener = listener;
        mA11yManager = context.getSystemService(AccessibilityManager.class);

        mActionButtonsRecyclerView = findViewById(R.id.tv_pip_menu_action_buttons);
        mButtonLayoutManager = new LinearLayoutManager(mContext);
        mActionButtonsRecyclerView.setLayoutManager(mButtonLayoutManager);
        mActionButtonsRecyclerView.setPreserveFocusAfterLayout(true);

        mDefaultCloseAction =
                new TvPipSystemAction(ACTION_CLOSE, R.string.pip_close,
                        R.drawable.pip_ic_close_white);
        mExpandCollapseAction =
                new TvPipSystemAction(ACTION_EXPAND_COLLAPSE, R.string.pip_collapse,
                        R.drawable.pip_ic_collapse);

        mActionsList = new ArrayList<>();
        mActionsList.add(
                new TvPipSystemAction(ACTION_FULLSCREEN, R.string.pip_fullscreen,
                        R.drawable.pip_ic_fullscreen_white));
        mActionsList.add(mDefaultCloseAction);
        mActionsList.add(
                new TvPipSystemAction(ACTION_MOVE, R.string.pip_move,
                        R.drawable.pip_ic_move_white));
        mActionsList.add(mExpandCollapseAction);

        mRecyclerViewAdapter = new RecyclerViewAdapter(mActionsList);
        mActionButtonsRecyclerView.setAdapter(mRecyclerViewAdapter);

        mMenuFrameView = findViewById(R.id.tv_pip_menu_frame);
        mPipFrameView = findViewById(R.id.tv_pip_border);
        mPipView = findViewById(R.id.tv_pip);

        mPipBackground = findViewById(R.id.tv_pip_menu_background);
        mDimLayer = findViewById(R.id.tv_pip_menu_dim_layer);

        mArrowUp = findViewById(R.id.tv_pip_menu_arrow_up);
        mArrowRight = findViewById(R.id.tv_pip_menu_arrow_right);
        mArrowDown = findViewById(R.id.tv_pip_menu_arrow_down);
        mArrowLeft = findViewById(R.id.tv_pip_menu_arrow_left);
        mA11yDoneButton = findViewById(R.id.tv_pip_menu_done_button);

        mResizeAnimationDuration = context.getResources().getInteger(
                R.integer.config_pipResizeAnimationDuration);
        mPipMenuFadeAnimationDuration = context.getResources()
                .getInteger(R.integer.tv_window_menu_fade_animation_duration);

        mPipMenuOuterSpace = context.getResources()
                .getDimensionPixelSize(R.dimen.pip_menu_outer_space);
        mPipMenuBorderWidth = context.getResources()
                .getDimensionPixelSize(R.dimen.pip_menu_border_width);

        mEduTextDrawer = new TvPipMenuEduTextDrawer(mContext, mainHandler, mListener);
        ((FrameLayout) findViewById(R.id.tv_pip_menu_edu_text_drawer_placeholder))
                .addView(mEduTextDrawer);
    }

    void onPipTransitionToTargetBoundsStarted(Rect targetBounds) {
        if (targetBounds == null) {
            return;
        }

        // Fade out content by fading in view on top.
        if (mCurrentPipBounds != null) {
            boolean ratioChanged = PipUtils.aspectRatioChanged(
                    mCurrentPipBounds.width() / (float) mCurrentPipBounds.height(),
                    targetBounds.width() / (float) targetBounds.height());
            if (ratioChanged) {
                mPipBackground.animate()
                        .alpha(1f)
                        .setInterpolator(TvPipInterpolators.EXIT)
                        .setDuration(mResizeAnimationDuration / 2)
                        .start();
            }
        }

        // Update buttons.
        final boolean vertical = targetBounds.height() > targetBounds.width();
        final boolean orientationChanged =
                vertical != (mButtonLayoutManager.getOrientation() == LinearLayoutManager.VERTICAL);
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: onPipTransitionToTargetBoundsStarted(), orientation changed %b",
                TAG, orientationChanged);
        if (!orientationChanged) {
            return;
        }

        if (mButtonMenuIsVisible) {
            mSwitchingOrientation = true;
            mActionButtonsRecyclerView.animate()
                    .alpha(0)
                    .setInterpolator(TvPipInterpolators.EXIT)
                    .setDuration(mResizeAnimationDuration / 2)
                    .withEndAction(() -> {
                        mButtonLayoutManager.setOrientation(vertical
                                ? LinearLayoutManager.VERTICAL : LinearLayoutManager.HORIZONTAL);
                        // Only make buttons visible again in onPipTransitionFinished to keep in
                        // sync with PiP content alpha animation.
                    });
        } else {
            mButtonLayoutManager.setOrientation(vertical
                    ? LinearLayoutManager.VERTICAL : LinearLayoutManager.HORIZONTAL);
        }
    }

    void onPipTransitionFinished(boolean enterTransition, boolean isTvPipExpanded) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: onPipTransitionFinished()", TAG);

        // Fade in content by fading out view on top (faded out at every aspect ratio change).
        mPipBackground.animate()
                .alpha(0f)
                .setDuration(mResizeAnimationDuration / 2)
                .setInterpolator(TvPipInterpolators.ENTER)
                .start();

        if (enterTransition) {
            mEduTextDrawer.init();
        }

        // Update buttons.
        setIsExpanded(isTvPipExpanded);

        if (mSwitchingOrientation) {
            mActionButtonsRecyclerView.animate()
                    .alpha(1)
                    .setInterpolator(TvPipInterpolators.ENTER)
                    .setDuration(mResizeAnimationDuration / 2);
        }
        mSwitchingOrientation = false;
    }

    /**
     * Also updates the button gravity.
     */
    void updateBounds(Rect updatedBounds) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: updateLayout, width: %s, height: %s", TAG, updatedBounds.width(),
                updatedBounds.height());
        mCurrentPipBounds = updatedBounds;
        updatePipFrameBounds();
    }

    Rect getPipMenuContainerBounds(Rect pipBounds) {
        final Rect menuUiBounds = new Rect(pipBounds);
        menuUiBounds.inset(-mPipMenuOuterSpace, -mPipMenuOuterSpace);
        menuUiBounds.bottom += mEduTextDrawer.getHeight();
        return menuUiBounds;
    }

    /**
     * Update mPipFrameView's bounds according to the new pip window bounds. We can't
     * make mPipFrameView match_parent, because the pip menu might contain other content around
     * the pip window (e.g. edu text).
     * TvPipMenuView needs to account for this so that it can draw a white border around the whole
     * pip menu when it gains focus.
     */
    private void updatePipFrameBounds() {
        final ViewGroup.LayoutParams pipFrameParams = mPipFrameView.getLayoutParams();
        if (pipFrameParams != null) {
            pipFrameParams.width = mCurrentPipBounds.width() + 2 * mPipMenuBorderWidth;
            pipFrameParams.height = mCurrentPipBounds.height() + 2 * mPipMenuBorderWidth;
            mPipFrameView.setLayoutParams(pipFrameParams);
        }

        final ViewGroup.LayoutParams pipViewParams = mPipView.getLayoutParams();
        if (pipViewParams != null) {
            pipViewParams.width = mCurrentPipBounds.width();
            pipViewParams.height = mCurrentPipBounds.height();
            mPipView.setLayoutParams(pipViewParams);
        }

        // Keep focused button within the visible area while the PiP is changing size. Otherwise,
        // the button would lose focus which would cause a need for scrolling and re-focusing after
        // the animation finishes, which does not look good.
        View focusedChild = mActionButtonsRecyclerView.getFocusedChild();
        if (focusedChild != null) {
            mActionButtonsRecyclerView.scrollToPosition(
                    mActionButtonsRecyclerView.getChildLayoutPosition(focusedChild));
        }
    }

    void setExpandedModeEnabled(boolean enabled) {
        int actionIndex = mActionsList.indexOf(mExpandCollapseAction);
        boolean actionInList = actionIndex != -1;
        if (enabled && !actionInList) {
            mActionsList.add(mExpandCollapseAction);
            mRecyclerViewAdapter.notifyItemInserted(mActionsList.size() - 1);
        } else if (!enabled && actionInList) {
            mActionsList.remove(actionIndex);
            mRecyclerViewAdapter.notifyItemRemoved(actionIndex);
        }
    }

    void setIsExpanded(boolean expanded) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: setIsExpanded, expanded: %b", TAG, expanded);
        mExpandCollapseAction.update(expanded ? R.string.pip_collapse : R.string.pip_expand,
                expanded ? R.drawable.pip_ic_collapse : R.drawable.pip_ic_expand);
        mRecyclerViewAdapter.notifyItemChanged(mActionsList.indexOf(mExpandCollapseAction));
    }

    /**
     * @param gravity for the arrow hints
     */
    void showMoveMenu(int gravity) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE, "%s: showMoveMenu()", TAG);
        showMovementHints(gravity);
        setMenuButtonsVisible(false);
        setFrameHighlighted(true);

        animateAlphaTo(mA11yManager.isEnabled() ? 1f : 0f, mDimLayer);

        mEduTextDrawer.closeIfNeeded();
    }


    void showButtonsMenu(boolean exitingMoveMode) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: showButtonsMenu(), exitingMoveMode %b", TAG, exitingMoveMode);
        setMenuButtonsVisible(true);
        hideMovementHints();
        setFrameHighlighted(true);
        animateAlphaTo(1f, mDimLayer);
        mEduTextDrawer.closeIfNeeded();

        if (exitingMoveMode) {
            scrollAndRefocusButton(getFirstIndexOfAction(ACTION_MOVE),
                    /* alwaysScroll= */ false);
        } else {
            scrollAndRefocusButton(0, /* alwaysScroll= */ true);
        }
    }

    private void scrollAndRefocusButton(int position, boolean alwaysScroll) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: scrollAndRefocusButton, target: %d", TAG, position);

        if (alwaysScroll || !refocusButton(position)) {
            mButtonLayoutManager.scrollToPositionWithOffset(position, 0);
            mActionButtonsRecyclerView.post(() -> refocusButton(position));
        }
    }

    /**
     * @return true if focus was requested, false if focus request could not be carried out due to
     * the view for the position not being available (scrolling beforehand will be necessary).
     */
    private boolean refocusButton(int position) {
        View itemToFocus = mButtonLayoutManager.findViewByPosition(position);
        if (itemToFocus != null) {
            itemToFocus.requestFocus();
            itemToFocus.requestAccessibilityFocus();
        }
        return itemToFocus != null;
    }

    /**
     * Returns the position of the first action of the given action type or -1 if none can be found.
     */
    private int getFirstIndexOfAction(@TvPipAction.ActionType int actionType) {
        for (int i = 0; i < mActionsList.size(); i++) {
            if (mActionsList.get(i).getActionType() == actionType) {
                return i;
            }
        }
        return -1;
    }

    void hideAllUserControls() {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: hideAllUserControls()", TAG);
        setMenuButtonsVisible(false);
        hideMovementHints();
        setFrameHighlighted(false);
        animateAlphaTo(0f, mDimLayer);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            hideAllUserControls();
        }
    }

    private void animateAlphaTo(float alpha, View view) {
        if (view.getAlpha() == alpha) {
            return;
        }
        view.animate()
                .alpha(alpha)
                .setInterpolator(alpha == 0f ? TvPipInterpolators.EXIT : TvPipInterpolators.ENTER)
                .setDuration(mPipMenuFadeAnimationDuration)
                .withStartAction(() -> {
                    if (alpha != 0) {
                        view.setVisibility(VISIBLE);
                    }
                })
                .withEndAction(() -> {
                    if (alpha == 0) {
                        view.setVisibility(GONE);
                    }
                });
    }

    void setAdditionalActions(List<RemoteAction> actions, RemoteAction closeAction) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: setAdditionalActions(), %d actions", TAG, actions.size());

        int oldCustomActionCount = 0;
        for (TvPipAction action : mActionsList) {
            if (action.getActionType() == ACTION_CUSTOM) {
                oldCustomActionCount++;
            }
        }

        // Update close action.
        mActionsList.set(CLOSE_ACTION_INDEX,
                closeAction == null ? mDefaultCloseAction
                        : new TvPipCustomAction(ACTION_CUSTOM_CLOSE, closeAction));
        mRecyclerViewAdapter.notifyItemChanged(CLOSE_ACTION_INDEX);

        // Replace custom actions with new ones.
        mActionsList.removeIf(tvPipAction -> tvPipAction.getActionType() == ACTION_CUSTOM);
        List<TvPipAction> customActions = new ArrayList<>(actions.size());
        int newCustomActionCount = 0;
        for (RemoteAction action : actions) {
            if (action == null || PipUtils.remoteActionsMatch(action, closeAction)) {
                // Don't show an action if it is the same as the custom close action
                continue;
            }
            customActions.add(new TvPipCustomAction(ACTION_CUSTOM, action));
            newCustomActionCount++;
        }
        mActionsList.addAll(FIRST_CUSTOM_ACTION_INDEX, customActions);

        mRecyclerViewAdapter.notifyItemRangeChanged(
                FIRST_CUSTOM_ACTION_INDEX, Math.min(oldCustomActionCount, newCustomActionCount));

        if (newCustomActionCount > oldCustomActionCount) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: setAdditionalActions(), %d inserted starting at %d",
                    TAG, newCustomActionCount - oldCustomActionCount,
                    FIRST_CUSTOM_ACTION_INDEX + oldCustomActionCount);
            mRecyclerViewAdapter.notifyItemRangeInserted(
                    FIRST_CUSTOM_ACTION_INDEX + oldCustomActionCount,
                    newCustomActionCount - oldCustomActionCount);

        } else if (oldCustomActionCount > newCustomActionCount) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: setAdditionalActions(), %d removed starting at %d",
                    TAG, oldCustomActionCount - newCustomActionCount,
                    FIRST_CUSTOM_ACTION_INDEX + newCustomActionCount);
            mRecyclerViewAdapter.notifyItemRangeRemoved(
                    FIRST_CUSTOM_ACTION_INDEX + newCustomActionCount,
                    oldCustomActionCount - newCustomActionCount);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == ACTION_UP) {

            if (event.getKeyCode() == KEYCODE_BACK) {
                mListener.onBackPress();
                return true;
            }

            if (mA11yManager.isEnabled()) {
                return super.dispatchKeyEvent(event);
            }

            switch (event.getKeyCode()) {
                case KEYCODE_DPAD_UP:
                case KEYCODE_DPAD_DOWN:
                case KEYCODE_DPAD_LEFT:
                case KEYCODE_DPAD_RIGHT:
                    return mListener.onPipMovement(event.getKeyCode()) || super.dispatchKeyEvent(
                            event);
                case KEYCODE_ENTER:
                case KEYCODE_DPAD_CENTER:
                    return mListener.onExitMoveMode() || super.dispatchKeyEvent(event);
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Shows user hints for moving the PiP, e.g. arrows.
     */
    public void showMovementHints(int gravity) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: showMovementHints(), position: %s", TAG, Gravity.toString(gravity));
        mMoveMenuIsVisible = true;

        animateAlphaTo(checkGravity(gravity, Gravity.BOTTOM) ? 1f : 0f, mArrowUp);
        animateAlphaTo(checkGravity(gravity, Gravity.TOP) ? 1f : 0f, mArrowDown);
        animateAlphaTo(checkGravity(gravity, Gravity.RIGHT) ? 1f : 0f, mArrowLeft);
        animateAlphaTo(checkGravity(gravity, Gravity.LEFT) ? 1f : 0f, mArrowRight);

        boolean a11yEnabled = mA11yManager.isEnabled();
        setArrowA11yEnabled(mArrowUp, a11yEnabled, KEYCODE_DPAD_UP);
        setArrowA11yEnabled(mArrowDown, a11yEnabled, KEYCODE_DPAD_DOWN);
        setArrowA11yEnabled(mArrowLeft, a11yEnabled, KEYCODE_DPAD_LEFT);
        setArrowA11yEnabled(mArrowRight, a11yEnabled, KEYCODE_DPAD_RIGHT);

        animateAlphaTo(a11yEnabled ? 1f : 0f, mA11yDoneButton);
        if (a11yEnabled) {
            mA11yDoneButton.setVisibility(VISIBLE);
            mA11yDoneButton.setOnClickListener(v -> {
                mListener.onExitMoveMode();
            });
            mA11yDoneButton.requestFocus();
            mA11yDoneButton.requestAccessibilityFocus();
        }
    }

    private void setArrowA11yEnabled(View arrowView, boolean enabled, int keycode) {
        arrowView.setClickable(enabled);
        if (enabled) {
            arrowView.setOnClickListener(v -> {
                mListener.onPipMovement(keycode);
            });
        }
    }

    private boolean checkGravity(int gravity, int feature) {
        return (gravity & feature) == feature;
    }

    /**
     * Hides user hints for moving the PiP, e.g. arrows.
     */
    public void hideMovementHints() {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: hideMovementHints()", TAG);

        if (!mMoveMenuIsVisible) {
            return;
        }
        mMoveMenuIsVisible = false;

        animateAlphaTo(0, mArrowUp);
        animateAlphaTo(0, mArrowRight);
        animateAlphaTo(0, mArrowDown);
        animateAlphaTo(0, mArrowLeft);
        animateAlphaTo(0, mA11yDoneButton);
    }

    /**
     * Show or hide the pip buttons menu.
     */
    private void setMenuButtonsVisible(boolean visible) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: showUserActions: %b", TAG, visible);
        mButtonMenuIsVisible = visible;
        animateAlphaTo(visible ? 1 : 0, mActionButtonsRecyclerView);
    }

    private void setFrameHighlighted(boolean highlighted) {
        mMenuFrameView.setActivated(highlighted);
    }

    private class RecyclerViewAdapter extends
            RecyclerView.Adapter<RecyclerViewAdapter.ButtonViewHolder> {

        private final List<TvPipAction> mActionList;

        RecyclerViewAdapter(List<TvPipAction> actionList) {
            this.mActionList = actionList;
        }

        @NonNull
        @Override
        public ButtonViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ButtonViewHolder(new TvWindowMenuActionButton(mContext));
        }

        @Override
        public void onBindViewHolder(@NonNull ButtonViewHolder holder, int position) {
            TvPipAction action = mActionList.get(position);
            action.populateButton(holder.mButton, mMainHandler);
        }

        @Override
        public int getItemCount() {
            return mActionList.size();
        }

        private class ButtonViewHolder extends RecyclerView.ViewHolder implements OnClickListener {
            TvWindowMenuActionButton mButton;

            ButtonViewHolder(@NonNull View itemView) {
                super(itemView);
                mButton = (TvWindowMenuActionButton) itemView;
                mButton.setOnClickListener(this);
            }

            @Override
            public void onClick(View v) {
                TvPipAction action = mActionList.get(
                        mActionButtonsRecyclerView.getChildLayoutPosition(v));
                switch (action.getActionType()) {
                    case ACTION_FULLSCREEN:
                        mListener.onFullscreenButtonClick();
                        return;
                    case ACTION_CLOSE:
                    case ACTION_CUSTOM_CLOSE:
                        mListener.onCloseButtonClick();
                        return;
                    case ACTION_MOVE:
                        mListener.onEnterMoveMode();
                        return;
                    case ACTION_EXPAND_COLLAPSE:
                        mListener.onToggleExpandedMode();
                        return;
                    case ACTION_CUSTOM:
                        action.executePendingIntent();
                        return;
                    default:
                        ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                                "%s: No action available", TAG);
                }
            }
        }
    }

    interface Listener extends TvPipMenuEduTextDrawer.Listener {

        void onBackPress();

        void onEnterMoveMode();

        /**
         * Called when a button for exiting move mode was pressed.
         *
         * @return true if the event was handled or false if the key event should be handled by the
         * next receiver.
         */
        boolean onExitMoveMode();

        /**
         * @return whether pip movement was handled.
         */
        boolean onPipMovement(int keycode);

        void onCloseButtonClick();

        void onFullscreenButtonClick();

        void onToggleExpandedMode();
    }
}