/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.app;

import com.android.internal.R;

import android.app.Dialog;
import android.app.MediaRouteActionProvider;
import android.app.MediaRouteButton;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.MediaRouter;
import android.media.MediaRouter.RouteGroup;
import android.media.MediaRouter.RouteInfo;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;

/**
 * This class implements the route controller dialog for {@link MediaRouter}.
 * <p>
 * This dialog allows the user to control or disconnect from the currently selected route.
 * </p>
 *
 * @see MediaRouteButton
 * @see MediaRouteActionProvider
 *
 * TODO: Move this back into the API, as in the support library media router.
 */
public class MediaRouteControllerDialog extends Dialog {
    // Time to wait before updating the volume when the user lets go of the seek bar
    // to allow the route provider time to propagate the change and publish a new
    // route descriptor.
    private static final int VOLUME_UPDATE_DELAY_MILLIS = 250;

    private final MediaRouter mRouter;
    private final MediaRouterCallback mCallback;
    private final MediaRouter.RouteInfo mRoute;

    private boolean mCreated;
    private Drawable mMediaRouteConnectingDrawable;
    private Drawable mMediaRouteOnDrawable;
    private Drawable mCurrentIconDrawable;

    private boolean mVolumeControlEnabled = true;
    private LinearLayout mVolumeLayout;
    private SeekBar mVolumeSlider;
    private boolean mVolumeSliderTouched;

    private View mControlView;

    private Button mDisconnectButton;

    public MediaRouteControllerDialog(Context context, int theme) {
        super(context, theme);

        mRouter = (MediaRouter) context.getSystemService(Context.MEDIA_ROUTER_SERVICE);
        mCallback = new MediaRouterCallback();
        mRoute = mRouter.getSelectedRoute();
    }

    /**
     * Gets the route that this dialog is controlling.
     */
    public MediaRouter.RouteInfo getRoute() {
        return mRoute;
    }

    /**
     * Provides the subclass an opportunity to create a view that will
     * be included within the body of the dialog to offer additional media controls
     * for the currently playing content.
     *
     * @param savedInstanceState The dialog's saved instance state.
     * @return The media control view, or null if none.
     */
    public View onCreateMediaControlView(Bundle savedInstanceState) {
        return null;
    }

    /**
     * Gets the media control view that was created by {@link #onCreateMediaControlView(Bundle)}.
     *
     * @return The media control view, or null if none.
     */
    public View getMediaControlView() {
        return mControlView;
    }

    /**
     * Sets whether to enable the volume slider and volume control using the volume keys
     * when the route supports it.
     * <p>
     * The default value is true.
     * </p>
     */
    public void setVolumeControlEnabled(boolean enable) {
        if (mVolumeControlEnabled != enable) {
            mVolumeControlEnabled = enable;
            if (mCreated) {
                updateVolume();
            }
        }
    }

    /**
     * Returns whether to enable the volume slider and volume control using the volume keys
     * when the route supports it.
     */
    public boolean isVolumeControlEnabled() {
        return mVolumeControlEnabled;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().requestFeature(Window.FEATURE_LEFT_ICON);

        setContentView(R.layout.media_route_controller_dialog);

        mVolumeLayout = (LinearLayout)findViewById(R.id.media_route_volume_layout);
        mVolumeSlider = (SeekBar)findViewById(R.id.media_route_volume_slider);
        mVolumeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private final Runnable mStopTrackingTouch = new Runnable() {
                @Override
                public void run() {
                    if (mVolumeSliderTouched) {
                        mVolumeSliderTouched = false;
                        updateVolume();
                    }
                }
            };

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (mVolumeSliderTouched) {
                    mVolumeSlider.removeCallbacks(mStopTrackingTouch);
                } else {
                    mVolumeSliderTouched = true;
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Defer resetting mVolumeSliderTouched to allow the media route provider
                // a little time to settle into its new state and publish the final
                // volume update.
                mVolumeSlider.postDelayed(mStopTrackingTouch, VOLUME_UPDATE_DELAY_MILLIS);
            }

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    mRoute.requestSetVolume(progress);
                }
            }
        });

        mDisconnectButton = (Button)findViewById(R.id.media_route_disconnect_button);
        mDisconnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mRoute.isSelected()) {
                    mRouter.getDefaultRoute().select();
                }
                dismiss();
            }
        });

        mCreated = true;
        if (update()) {
            mControlView = onCreateMediaControlView(savedInstanceState);
            FrameLayout controlFrame =
                    (FrameLayout)findViewById(R.id.media_route_control_frame);
            if (mControlView != null) {
                controlFrame.addView(mControlView);
                controlFrame.setVisibility(View.VISIBLE);
            } else {
                controlFrame.setVisibility(View.GONE);
            }
        }
    }


    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        mRouter.addCallback(0, mCallback, MediaRouter.CALLBACK_FLAG_UNFILTERED_EVENTS);
        update();
    }

    @Override
    public void onDetachedFromWindow() {
        mRouter.removeCallback(mCallback);

        super.onDetachedFromWindow();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            mRoute.requestUpdateVolume(keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ? -1 : 1);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean update() {
        if (!mRoute.isSelected() || mRoute.isDefault()) {
            dismiss();
            return false;
        }

        setTitle(mRoute.getName());
        updateVolume();

        Drawable icon = getIconDrawable();
        if (icon != mCurrentIconDrawable) {
            mCurrentIconDrawable = icon;
            getWindow().setFeatureDrawable(Window.FEATURE_LEFT_ICON, icon);
        }
        return true;
    }

    private Drawable getIconDrawable() {
        if (mRoute.isConnecting()) {
            if (mMediaRouteConnectingDrawable == null) {
                mMediaRouteConnectingDrawable = getContext().getDrawable(
                        R.drawable.ic_media_route_connecting_holo_dark);
            }
            return mMediaRouteConnectingDrawable;
        } else {
            if (mMediaRouteOnDrawable == null) {
                mMediaRouteOnDrawable = getContext().getDrawable(
                        R.drawable.ic_media_route_on_holo_dark);
            }
            return mMediaRouteOnDrawable;
        }
    }

    private void updateVolume() {
        if (!mVolumeSliderTouched) {
            if (isVolumeControlAvailable()) {
                mVolumeLayout.setVisibility(View.VISIBLE);
                mVolumeSlider.setMax(mRoute.getVolumeMax());
                mVolumeSlider.setProgress(mRoute.getVolume());
            } else {
                mVolumeLayout.setVisibility(View.GONE);
            }
        }
    }

    private boolean isVolumeControlAvailable() {
        return mVolumeControlEnabled && mRoute.getVolumeHandling() ==
                MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE;
    }

    private final class MediaRouterCallback extends MediaRouter.SimpleCallback {
        @Override
        public void onRouteUnselected(MediaRouter router, int type, RouteInfo info) {
            update();
        }

        @Override
        public void onRouteChanged(MediaRouter router, MediaRouter.RouteInfo route) {
            update();
        }

        @Override
        public void onRouteVolumeChanged(MediaRouter router, MediaRouter.RouteInfo route) {
            if (route == mRoute) {
                updateVolume();
            }
        }

        @Override
        public void onRouteGrouped(MediaRouter router, RouteInfo info, RouteGroup group,
                int index) {
            update();
        }

        @Override
        public void onRouteUngrouped(MediaRouter router, RouteInfo info, RouteGroup group) {
            update();
        }
    }
}
