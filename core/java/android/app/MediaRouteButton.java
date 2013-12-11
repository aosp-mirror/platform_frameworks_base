/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.app;

import com.android.internal.R;
import com.android.internal.app.MediaRouteDialogPresenter;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.media.MediaRouter;
import android.media.MediaRouter.RouteGroup;
import android.media.MediaRouter.RouteInfo;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.SoundEffectConstants;
import android.view.View;
import android.widget.Toast;

public class MediaRouteButton extends View {
    private final MediaRouter mRouter;
    private final MediaRouterCallback mCallback;

    private int mRouteTypes;

    private boolean mAttachedToWindow;

    private Drawable mRemoteIndicator;
    private boolean mRemoteActive;
    private boolean mCheatSheetEnabled;
    private boolean mIsConnecting;

    private int mMinWidth;
    private int mMinHeight;

    private OnClickListener mExtendedSettingsClickListener;

    // The checked state is used when connected to a remote route.
    private static final int[] CHECKED_STATE_SET = {
        R.attr.state_checked
    };

    // The activated state is used while connecting to a remote route.
    private static final int[] ACTIVATED_STATE_SET = {
        R.attr.state_activated
    };

    public MediaRouteButton(Context context) {
        this(context, null);
    }

    public MediaRouteButton(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.mediaRouteButtonStyle);
    }

    public MediaRouteButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        mRouter = (MediaRouter)context.getSystemService(Context.MEDIA_ROUTER_SERVICE);
        mCallback = new MediaRouterCallback();

        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.MediaRouteButton, defStyleAttr, 0);
        setRemoteIndicatorDrawable(a.getDrawable(
                com.android.internal.R.styleable.MediaRouteButton_externalRouteEnabledDrawable));
        mMinWidth = a.getDimensionPixelSize(
                com.android.internal.R.styleable.MediaRouteButton_minWidth, 0);
        mMinHeight = a.getDimensionPixelSize(
                com.android.internal.R.styleable.MediaRouteButton_minHeight, 0);
        final int routeTypes = a.getInteger(
                com.android.internal.R.styleable.MediaRouteButton_mediaRouteTypes,
                MediaRouter.ROUTE_TYPE_LIVE_AUDIO);
        a.recycle();

        setClickable(true);
        setLongClickable(true);

        setRouteTypes(routeTypes);
    }

    /**
     * Gets the media route types for filtering the routes that the user can
     * select using the media route chooser dialog.
     *
     * @return The route types.
     */
    public int getRouteTypes() {
        return mRouteTypes;
    }

    /**
     * Sets the types of routes that will be shown in the media route chooser dialog
     * launched by this button.
     *
     * @param types The route types to match.
     */
    public void setRouteTypes(int types) {
        if (mRouteTypes != types) {
            if (mAttachedToWindow && mRouteTypes != 0) {
                mRouter.removeCallback(mCallback);
            }

            mRouteTypes = types;

            if (mAttachedToWindow && types != 0) {
                mRouter.addCallback(types, mCallback,
                        MediaRouter.CALLBACK_FLAG_PASSIVE_DISCOVERY);
            }

            refreshRoute();
        }
    }

    public void setExtendedSettingsClickListener(OnClickListener listener) {
        mExtendedSettingsClickListener = listener;
    }

    /**
     * Show the route chooser or controller dialog.
     * <p>
     * If the default route is selected or if the currently selected route does
     * not match the {@link #getRouteTypes route types}, then shows the route chooser dialog.
     * Otherwise, shows the route controller dialog to offer the user
     * a choice to disconnect from the route or perform other control actions
     * such as setting the route's volume.
     * </p><p>
     * This will attach a {@link DialogFragment} to the containing Activity.
     * </p>
     */
    public void showDialog() {
        showDialogInternal();
    }

    boolean showDialogInternal() {
        if (!mAttachedToWindow) {
            return false;
        }

        DialogFragment f = MediaRouteDialogPresenter.showDialogFragment(getActivity(),
                mRouteTypes, mExtendedSettingsClickListener);
        return f != null;
    }

    private Activity getActivity() {
        // Gross way of unwrapping the Activity so we can get the FragmentManager
        Context context = getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity)context;
            }
            context = ((ContextWrapper)context).getBaseContext();
        }
        throw new IllegalStateException("The MediaRouteButton's Context is not an Activity.");
    }

    /**
     * Sets whether to enable showing a toast with the content descriptor of the
     * button when the button is long pressed.
     */
    void setCheatSheetEnabled(boolean enable) {
        mCheatSheetEnabled = enable;
    }

    @Override
    public boolean performClick() {
        // Send the appropriate accessibility events and call listeners
        boolean handled = super.performClick();
        if (!handled) {
            playSoundEffect(SoundEffectConstants.CLICK);
        }
        return showDialogInternal() || handled;
    }

    @Override
    public boolean performLongClick() {
        if (super.performLongClick()) {
            return true;
        }

        if (!mCheatSheetEnabled) {
            return false;
        }

        final CharSequence contentDesc = getContentDescription();
        if (TextUtils.isEmpty(contentDesc)) {
            // Don't show the cheat sheet if we have no description
            return false;
        }

        final int[] screenPos = new int[2];
        final Rect displayFrame = new Rect();
        getLocationOnScreen(screenPos);
        getWindowVisibleDisplayFrame(displayFrame);

        final Context context = getContext();
        final int width = getWidth();
        final int height = getHeight();
        final int midy = screenPos[1] + height / 2;
        final int screenWidth = context.getResources().getDisplayMetrics().widthPixels;

        Toast cheatSheet = Toast.makeText(context, contentDesc, Toast.LENGTH_SHORT);
        if (midy < displayFrame.height()) {
            // Show along the top; follow action buttons
            cheatSheet.setGravity(Gravity.TOP | Gravity.END,
                    screenWidth - screenPos[0] - width / 2, height);
        } else {
            // Show along the bottom center
            cheatSheet.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, height);
        }
        cheatSheet.show();
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        return true;
    }

    @Override
    protected int[] onCreateDrawableState(int extraSpace) {
        final int[] drawableState = super.onCreateDrawableState(extraSpace + 1);

        // Technically we should be handling this more completely, but these
        // are implementation details here. Checked is used to express the connecting
        // drawable state and it's mutually exclusive with activated for the purposes
        // of state selection here.
        if (mIsConnecting) {
            mergeDrawableStates(drawableState, CHECKED_STATE_SET);
        } else if (mRemoteActive) {
            mergeDrawableStates(drawableState, ACTIVATED_STATE_SET);
        }
        return drawableState;
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();

        if (mRemoteIndicator != null) {
            int[] myDrawableState = getDrawableState();
            mRemoteIndicator.setState(myDrawableState);
            invalidate();
        }
    }

    private void setRemoteIndicatorDrawable(Drawable d) {
        if (mRemoteIndicator != null) {
            mRemoteIndicator.setCallback(null);
            unscheduleDrawable(mRemoteIndicator);
        }
        mRemoteIndicator = d;
        if (d != null) {
            d.setCallback(this);
            d.setState(getDrawableState());
            d.setVisible(getVisibility() == VISIBLE, false);
        }

        refreshDrawableState();
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || who == mRemoteIndicator;
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();

        if (mRemoteIndicator != null) {
            mRemoteIndicator.jumpToCurrentState();
        }
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);

        if (mRemoteIndicator != null) {
            mRemoteIndicator.setVisible(getVisibility() == VISIBLE, false);
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        mAttachedToWindow = true;
        if (mRouteTypes != 0) {
            mRouter.addCallback(mRouteTypes, mCallback,
                    MediaRouter.CALLBACK_FLAG_PASSIVE_DISCOVERY);
        }
        refreshRoute();
    }

    @Override
    public void onDetachedFromWindow() {
        mAttachedToWindow = false;
        if (mRouteTypes != 0) {
            mRouter.removeCallback(mCallback);
        }

        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        final int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);

        final int minWidth = Math.max(mMinWidth,
                mRemoteIndicator != null ? mRemoteIndicator.getIntrinsicWidth() : 0);
        final int minHeight = Math.max(mMinHeight,
                mRemoteIndicator != null ? mRemoteIndicator.getIntrinsicHeight() : 0);

        int width;
        switch (widthMode) {
            case MeasureSpec.EXACTLY:
                width = widthSize;
                break;
            case MeasureSpec.AT_MOST:
                width = Math.min(widthSize, minWidth + getPaddingLeft() + getPaddingRight());
                break;
            default:
            case MeasureSpec.UNSPECIFIED:
                width = minWidth + getPaddingLeft() + getPaddingRight();
                break;
        }

        int height;
        switch (heightMode) {
            case MeasureSpec.EXACTLY:
                height = heightSize;
                break;
            case MeasureSpec.AT_MOST:
                height = Math.min(heightSize, minHeight + getPaddingTop() + getPaddingBottom());
                break;
            default:
            case MeasureSpec.UNSPECIFIED:
                height = minHeight + getPaddingTop() + getPaddingBottom();
                break;
        }

        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mRemoteIndicator == null) return;

        final int left = getPaddingLeft();
        final int right = getWidth() - getPaddingRight();
        final int top = getPaddingTop();
        final int bottom = getHeight() - getPaddingBottom();

        final int drawWidth = mRemoteIndicator.getIntrinsicWidth();
        final int drawHeight = mRemoteIndicator.getIntrinsicHeight();
        final int drawLeft = left + (right - left - drawWidth) / 2;
        final int drawTop = top + (bottom - top - drawHeight) / 2;

        mRemoteIndicator.setBounds(drawLeft, drawTop,
                drawLeft + drawWidth, drawTop + drawHeight);
        mRemoteIndicator.draw(canvas);
    }

    private void refreshRoute() {
        if (mAttachedToWindow) {
            final MediaRouter.RouteInfo route = mRouter.getSelectedRoute();
            final boolean isRemote = !route.isDefault() && route.matchesTypes(mRouteTypes);
            final boolean isConnecting = isRemote && route.isConnecting();

            boolean needsRefresh = false;
            if (mRemoteActive != isRemote) {
                mRemoteActive = isRemote;
                needsRefresh = true;
            }
            if (mIsConnecting != isConnecting) {
                mIsConnecting = isConnecting;
                needsRefresh = true;
            }

            if (needsRefresh) {
                refreshDrawableState();
            }

            setEnabled(mRouter.isRouteAvailable(mRouteTypes,
                    MediaRouter.AVAILABILITY_FLAG_IGNORE_DEFAULT_ROUTE));
        }
    }

    private final class MediaRouterCallback extends MediaRouter.SimpleCallback {
        @Override
        public void onRouteAdded(MediaRouter router, RouteInfo info) {
            refreshRoute();
        }

        @Override
        public void onRouteRemoved(MediaRouter router, RouteInfo info) {
            refreshRoute();
        }

        @Override
        public void onRouteChanged(MediaRouter router, RouteInfo info) {
            refreshRoute();
        }

        @Override
        public void onRouteSelected(MediaRouter router, int type, RouteInfo info) {
            refreshRoute();
        }

        @Override
        public void onRouteUnselected(MediaRouter router, int type, RouteInfo info) {
            refreshRoute();
        }

        @Override
        public void onRouteGrouped(MediaRouter router, RouteInfo info, RouteGroup group,
                int index) {
            refreshRoute();
        }

        @Override
        public void onRouteUngrouped(MediaRouter router, RouteInfo info, RouteGroup group) {
            refreshRoute();
        }
    }
}
