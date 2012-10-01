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
import com.android.internal.app.MediaRouteChooserDialogFragment;

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
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.SoundEffectConstants;
import android.view.View;
import android.widget.Toast;

public class MediaRouteButton extends View {
    private static final String TAG = "MediaRouteButton";

    private MediaRouter mRouter;
    private final MediaRouteCallback mRouterCallback = new MediaRouteCallback();
    private int mRouteTypes;

    private boolean mAttachedToWindow;

    private Drawable mRemoteIndicator;
    private boolean mRemoteActive;
    private boolean mToggleMode;
    private boolean mCheatSheetEnabled;
    private boolean mIsConnecting;

    private int mMinWidth;
    private int mMinHeight;

    private OnClickListener mExtendedSettingsClickListener;
    private MediaRouteChooserDialogFragment mDialogFragment;

    private static final int[] CHECKED_STATE_SET = {
        R.attr.state_checked
    };

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
    public boolean performClick() {
        // Send the appropriate accessibility events and call listeners
        boolean handled = super.performClick();
        if (!handled) {
            playSoundEffect(SoundEffectConstants.CLICK);
        }

        if (mToggleMode) {
            if (mRemoteActive) {
                mRouter.selectRouteInt(mRouteTypes, mRouter.getSystemAudioRoute());
            } else {
                final int N = mRouter.getRouteCount();
                for (int i = 0; i < N; i++) {
                    final RouteInfo route = mRouter.getRouteAt(i);
                    if ((route.getSupportedTypes() & mRouteTypes) != 0 &&
                            route != mRouter.getSystemAudioRoute()) {
                        mRouter.selectRouteInt(mRouteTypes, route);
                    }
                }
            }
        } else {
            showDialog();
        }

        return handled;
    }

    void setCheatSheetEnabled(boolean enable) {
        mCheatSheetEnabled = enable;
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

    public void setRouteTypes(int types) {
        if (types == mRouteTypes) {
            // Already registered; nothing to do.
            return;
        }

        if (mAttachedToWindow && mRouteTypes != 0) {
            mRouter.removeCallback(mRouterCallback);
        }

        mRouteTypes = types;

        if (mAttachedToWindow) {
            updateRouteInfo();
            mRouter.addCallback(types, mRouterCallback);
        }
    }

    private void updateRouteInfo() {
        updateRemoteIndicator();
        updateRouteCount();
    }

    public int getRouteTypes() {
        return mRouteTypes;
    }

    void updateRemoteIndicator() {
        final RouteInfo selected = mRouter.getSelectedRoute(mRouteTypes);
        final boolean isRemote = selected != mRouter.getSystemAudioRoute();
        final boolean isConnecting = selected.getStatusCode() == RouteInfo.STATUS_CONNECTING;

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
    }

    void updateRouteCount() {
        final int N = mRouter.getRouteCount();
        int count = 0;
        boolean hasVideoRoutes = false;
        for (int i = 0; i < N; i++) {
            final RouteInfo route = mRouter.getRouteAt(i);
            final int routeTypes = route.getSupportedTypes();
            if ((routeTypes & mRouteTypes) != 0) {
                if (route instanceof RouteGroup) {
                    count += ((RouteGroup) route).getRouteCount();
                } else {
                    count++;
                }
                if ((routeTypes & MediaRouter.ROUTE_TYPE_LIVE_VIDEO) != 0) {
                    hasVideoRoutes = true;
                }
            }
        }

        setEnabled(count != 0);

        // Only allow toggling if we have more than just user routes.
        // Don't toggle if we support video routes, we may have to let the dialog scan.
        mToggleMode = count == 2 && (mRouteTypes & MediaRouter.ROUTE_TYPE_LIVE_AUDIO) != 0 &&
                !hasVideoRoutes;
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

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || who == mRemoteIndicator;
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();
        if (mRemoteIndicator != null) mRemoteIndicator.jumpToCurrentState();
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
            mRouter.addCallback(mRouteTypes, mRouterCallback);
            updateRouteInfo();
        }
    }

    @Override
    public void onDetachedFromWindow() {
        if (mRouteTypes != 0) {
            mRouter.removeCallback(mRouterCallback);
        }
        mAttachedToWindow = false;
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

        mRemoteIndicator.setBounds(drawLeft, drawTop, drawLeft + drawWidth, drawTop + drawHeight);
        mRemoteIndicator.draw(canvas);
    }

    public void setExtendedSettingsClickListener(OnClickListener listener) {
        mExtendedSettingsClickListener = listener;
        if (mDialogFragment != null) {
            mDialogFragment.setExtendedSettingsClickListener(listener);
        }
    }

    /**
     * Asynchronously show the route chooser dialog.
     * This will attach a {@link DialogFragment} to the containing Activity.
     */
    public void showDialog() {
        final FragmentManager fm = getActivity().getFragmentManager();
        if (mDialogFragment == null) {
            // See if one is already attached to this activity.
            mDialogFragment = (MediaRouteChooserDialogFragment) fm.findFragmentByTag(
                    MediaRouteChooserDialogFragment.FRAGMENT_TAG);
        }
        if (mDialogFragment != null) {
            Log.w(TAG, "showDialog(): Already showing!");
            return;
        }

        mDialogFragment = new MediaRouteChooserDialogFragment();
        mDialogFragment.setExtendedSettingsClickListener(mExtendedSettingsClickListener);
        mDialogFragment.setLauncherListener(new MediaRouteChooserDialogFragment.LauncherListener() {
            @Override
            public void onDetached(MediaRouteChooserDialogFragment detachedFragment) {
                mDialogFragment = null;
            }
        });
        mDialogFragment.setRouteTypes(mRouteTypes);
        mDialogFragment.show(fm, MediaRouteChooserDialogFragment.FRAGMENT_TAG);
    }

    private Activity getActivity() {
        // Gross way of unwrapping the Activity so we can get the FragmentManager
        Context context = getContext();
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            context = ((ContextWrapper) context).getBaseContext();
        }
        if (!(context instanceof Activity)) {
            throw new IllegalStateException("The MediaRouteButton's Context is not an Activity.");
        }

        return (Activity) context;
    }

    private class MediaRouteCallback extends MediaRouter.SimpleCallback {
        @Override
        public void onRouteSelected(MediaRouter router, int type, RouteInfo info) {
            updateRemoteIndicator();
        }

        @Override
        public void onRouteUnselected(MediaRouter router, int type, RouteInfo info) {
            updateRemoteIndicator();
        }

        @Override
        public void onRouteChanged(MediaRouter router, RouteInfo info) {
            updateRemoteIndicator();
        }

        @Override
        public void onRouteAdded(MediaRouter router, RouteInfo info) {
            updateRouteCount();
        }

        @Override
        public void onRouteRemoved(MediaRouter router, RouteInfo info) {
            updateRouteCount();
        }

        @Override
        public void onRouteGrouped(MediaRouter router, RouteInfo info, RouteGroup group,
                int index) {
            updateRouteCount();
        }

        @Override
        public void onRouteUngrouped(MediaRouter router, RouteInfo info, RouteGroup group) {
            updateRouteCount();
        }
    }
}
