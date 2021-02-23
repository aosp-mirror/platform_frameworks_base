/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.policy;

import static android.provider.Settings.Global.DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES;
import static android.provider.Settings.Global.DEVELOPMENT_RENDER_SHADOWS_IN_COMPOSITOR;
import static android.view.View.SYSTEM_UI_LAYOUT_FLAGS;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiContext;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.SearchManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.transition.Scene;
import android.transition.Transition;
import android.transition.TransitionInflater;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.AndroidRuntimeException;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.IRotationWatcher.Stub;
import android.view.IScrollCaptureCallbacks;
import android.view.IWindowManager;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputQueue;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScrollCaptureCallback;
import android.view.SearchEvent;
import android.view.SurfaceHolder.Callback2;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewManager;
import android.view.ViewParent;
import android.view.ViewRootImpl;
import android.view.ViewRootImpl.ActivityConfigCallback;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.view.menu.ContextMenuBuilder;
import com.android.internal.view.menu.IconMenuPresenter;
import com.android.internal.view.menu.ListMenuPresenter;
import com.android.internal.view.menu.MenuBuilder;
import com.android.internal.view.menu.MenuDialogHelper;
import com.android.internal.view.menu.MenuHelper;
import com.android.internal.view.menu.MenuPresenter;
import com.android.internal.view.menu.MenuView;
import com.android.internal.widget.DecorContentParent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Android-specific Window.
 * <p>
 * todo: need to pull the generic functionality out into a base class
 * in android.widget.
 *
 * @hide
 */
public class PhoneWindow extends Window implements MenuBuilder.Callback {

    private final static String TAG = "PhoneWindow";

    /**
     * @see Window#setDecorFitsSystemWindows
     */
    private static final OnContentApplyWindowInsetsListener sDefaultContentInsetsApplier =
            (view, insets) -> {
                if ((view.getWindowSystemUiVisibility() & SYSTEM_UI_LAYOUT_FLAGS) != 0) {
                    return new Pair<>(Insets.NONE, insets);
                }
                Insets insetsToApply = insets.getSystemWindowInsets();
                return new Pair<>(insetsToApply,
                        insets.inset(insetsToApply).consumeSystemWindowInsets());
            };

    /* If true, shadows drawn around the window will be rendered by the system compositor. If
     * false, shadows will be drawn by the client by setting an elevation on the root view and
     * the contents will be inset by the shadow radius. */
    public final boolean mRenderShadowsInCompositor;

    private static final boolean DEBUG = false;

    private final static int DEFAULT_BACKGROUND_FADE_DURATION_MS = 300;

    private static final int CUSTOM_TITLE_COMPATIBLE_FEATURES = DEFAULT_FEATURES |
            (1 << FEATURE_CUSTOM_TITLE) |
            (1 << FEATURE_CONTENT_TRANSITIONS) |
            (1 << FEATURE_ACTIVITY_TRANSITIONS) |
            (1 << FEATURE_ACTION_MODE_OVERLAY);

    private static final Transition USE_DEFAULT_TRANSITION = new TransitionSet();

    /**
     * Simple callback used by the context menu and its submenus. The options
     * menu submenus do not use this (their behavior is more complex).
     */
    final PhoneWindowMenuCallback mContextMenuCallback = new PhoneWindowMenuCallback(this);

    final TypedValue mMinWidthMajor = new TypedValue();
    final TypedValue mMinWidthMinor = new TypedValue();
    TypedValue mFixedWidthMajor;
    TypedValue mFixedWidthMinor;
    TypedValue mFixedHeightMajor;
    TypedValue mFixedHeightMinor;

    // This is the top-level view of the window, containing the window decor.
    private DecorView mDecor;

    // When we reuse decor views, we need to recreate the content root. This happens when the decor
    // view is requested, so we need to force the recreating without introducing an infinite loop.
    private boolean mForceDecorInstall = false;

    // This is the view in which the window contents are placed. It is either
    // mDecor itself, or a child of mDecor where the contents go.
    ViewGroup mContentParent;
    // Whether the client has explicitly set the content view. If false and mContentParent is not
    // null, then the content parent was set due to window preservation.
    private boolean mContentParentExplicitlySet = false;

    Callback2 mTakeSurfaceCallback;

    InputQueue.Callback mTakeInputQueueCallback;

    boolean mIsFloating;
    private boolean mIsTranslucent;

    private LayoutInflater mLayoutInflater;

    private TextView mTitleView;

    DecorContentParent mDecorContentParent;
    private ActionMenuPresenterCallback mActionMenuPresenterCallback;
    private PanelMenuPresenterCallback mPanelMenuPresenterCallback;

    private TransitionManager mTransitionManager;
    private Scene mContentScene;

    // The icon resource has been explicitly set elsewhere
    // and should not be overwritten with a default.
    static final int FLAG_RESOURCE_SET_ICON = 1 << 0;

    // The logo resource has been explicitly set elsewhere
    // and should not be overwritten with a default.
    static final int FLAG_RESOURCE_SET_LOGO = 1 << 1;

    // The icon resource is currently configured to use the system fallback
    // as no default was previously specified. Anything can override this.
    static final int FLAG_RESOURCE_SET_ICON_FALLBACK = 1 << 2;

    int mResourcesSetFlags;
    int mIconRes;
    int mLogoRes;

    private DrawableFeatureState[] mDrawables;

    private PanelFeatureState[] mPanels;

    /**
     * The panel that is prepared or opened (the most recent one if there are
     * multiple panels). Shortcuts will go to this panel. It gets set in
     * {@link #preparePanel} and cleared in {@link #closePanel}.
     */
    PanelFeatureState mPreparedPanel;

    /**
     * The keycode that is currently held down (as a modifier) for chording. If
     * this is 0, there is no key held down.
     */
    int mPanelChordingKey;

    // This stores if the system supports Picture-in-Picture
    // to see if KEYCODE_WINDOW should be handled here or not.
    private boolean mSupportsPictureInPicture;

    private ImageView mLeftIconView;

    private ImageView mRightIconView;

    private ProgressBar mCircularProgressBar;

    private ProgressBar mHorizontalProgressBar;

    Drawable mBackgroundDrawable = null;
    Drawable mBackgroundFallbackDrawable = null;

    int mBackgroundBlurRadius = 0;

    private boolean mLoadElevation = true;
    private float mElevation;

    /** Whether window content should be clipped to the background outline. */
    private boolean mClipToOutline;

    private int mFrameResource = 0;

    private int mTextColor = 0;
    int mStatusBarColor = 0;
    int mNavigationBarColor = 0;
    int mNavigationBarDividerColor = 0;
    private boolean mForcedStatusBarColor = false;
    private boolean mForcedNavigationBarColor = false;

    boolean mEnsureStatusBarContrastWhenTransparent;
    boolean mEnsureNavigationBarContrastWhenTransparent;

    @UnsupportedAppUsage
    private CharSequence mTitle = null;

    private int mTitleColor = 0;

    private boolean mAlwaysReadCloseOnTouchAttr = false;

    ContextMenuBuilder mContextMenu;
    MenuHelper mContextMenuHelper;
    private boolean mClosingActionMenu;

    private int mVolumeControlStreamType = AudioManager.USE_DEFAULT_STREAM_TYPE;
    private MediaController mMediaController;

    private AudioManager mAudioManager;
    private KeyguardManager mKeyguardManager;
    private MediaSessionManager mMediaSessionManager;

    private int mUiOptions = 0;

    private boolean mInvalidatePanelMenuPosted;
    private int mInvalidatePanelMenuFeatures;
    private final Runnable mInvalidatePanelMenuRunnable = new Runnable() {
        @Override public void run() {
            for (int i = 0; i <= FEATURE_MAX; i++) {
                if ((mInvalidatePanelMenuFeatures & 1 << i) != 0) {
                    doInvalidatePanelMenu(i);
                }
            }
            mInvalidatePanelMenuPosted = false;
            mInvalidatePanelMenuFeatures = 0;
        }
    };

    private Transition mEnterTransition = null;
    private Transition mReturnTransition = USE_DEFAULT_TRANSITION;
    private Transition mExitTransition = null;
    private Transition mReenterTransition = USE_DEFAULT_TRANSITION;
    private Transition mSharedElementEnterTransition = null;
    private Transition mSharedElementReturnTransition = USE_DEFAULT_TRANSITION;
    private Transition mSharedElementExitTransition = null;
    private Transition mSharedElementReenterTransition = USE_DEFAULT_TRANSITION;
    private Boolean mAllowReturnTransitionOverlap;
    private Boolean mAllowEnterTransitionOverlap;
    private long mBackgroundFadeDurationMillis = -1;
    private Boolean mSharedElementsUseOverlay;

    private boolean mIsStartingWindow;
    private int mTheme = -1;

    private int mDecorCaptionShade = DECOR_CAPTION_SHADE_AUTO;

    private boolean mUseDecorContext = false;

    /** @see ViewRootImpl#mActivityConfigCallback */
    private ActivityConfigCallback mActivityConfigCallback;

    boolean mDecorFitsSystemWindows = true;

    static class WindowManagerHolder {
        static final IWindowManager sWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService("window"));
    }

    static final RotationWatcher sRotationWatcher = new RotationWatcher();

    @UnsupportedAppUsage
    public PhoneWindow(@UiContext Context context) {
        super(context);
        mLayoutInflater = LayoutInflater.from(context);
        mRenderShadowsInCompositor = Settings.Global.getInt(context.getContentResolver(),
                DEVELOPMENT_RENDER_SHADOWS_IN_COMPOSITOR, 1) != 0;
    }

    /**
     * Constructor for main window of an activity.
     */
    public PhoneWindow(@UiContext Context context, Window preservedWindow,
            ActivityConfigCallback activityConfigCallback) {
        this(context);
        // Only main activity windows use decor context, all the other windows depend on whatever
        // context that was given to them.
        mUseDecorContext = true;
        if (preservedWindow != null) {
            mDecor = (DecorView) preservedWindow.getDecorView();
            mElevation = preservedWindow.getElevation();
            mLoadElevation = false;
            mForceDecorInstall = true;
            // If we're preserving window, carry over the app token from the preserved
            // window, as we'll be skipping the addView in handleResumeActivity(), and
            // the token will not be updated as for a new window.
            getAttributes().token = preservedWindow.getAttributes().token;
        }
        // Even though the device doesn't support picture-in-picture mode,
        // an user can force using it through developer options.
        boolean forceResizable = Settings.Global.getInt(context.getContentResolver(),
                DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES, 0) != 0;
        mSupportsPictureInPicture = forceResizable || context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_PICTURE_IN_PICTURE);
        mActivityConfigCallback = activityConfigCallback;
    }

    @Override
    public final void setContainer(Window container) {
        super.setContainer(container);
    }

    @Override
    public boolean requestFeature(int featureId) {
        if (mContentParentExplicitlySet) {
            throw new AndroidRuntimeException("requestFeature() must be called before adding content");
        }
        final int features = getFeatures();
        final int newFeatures = features | (1 << featureId);
        if ((newFeatures & (1 << FEATURE_CUSTOM_TITLE)) != 0 &&
                (newFeatures & ~CUSTOM_TITLE_COMPATIBLE_FEATURES) != 0) {
            // Another feature is enabled and the user is trying to enable the custom title feature
            // or custom title feature is enabled and the user is trying to enable another feature
            throw new AndroidRuntimeException(
                    "You cannot combine custom titles with other title features");
        }
        if ((features & (1 << FEATURE_NO_TITLE)) != 0 && featureId == FEATURE_ACTION_BAR) {
            return false; // Ignore. No title dominates.
        }
        if ((features & (1 << FEATURE_ACTION_BAR)) != 0 && featureId == FEATURE_NO_TITLE) {
            // Remove the action bar feature if we have no title. No title dominates.
            removeFeature(FEATURE_ACTION_BAR);
        }

        if (featureId == FEATURE_INDETERMINATE_PROGRESS &&
                getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH)) {
            throw new AndroidRuntimeException("You cannot use indeterminate progress on a watch.");
        }
        return super.requestFeature(featureId);
    }

    @Override
    public void setUiOptions(int uiOptions) {
        mUiOptions = uiOptions;
    }

    @Override
    public void setUiOptions(int uiOptions, int mask) {
        mUiOptions = (mUiOptions & ~mask) | (uiOptions & mask);
    }

    @Override
    public TransitionManager getTransitionManager() {
        return mTransitionManager;
    }

    @Override
    public void setTransitionManager(TransitionManager tm) {
        mTransitionManager = tm;
    }

    @Override
    public Scene getContentScene() {
        return mContentScene;
    }

    @Override
    public void setContentView(int layoutResID) {
        // Note: FEATURE_CONTENT_TRANSITIONS may be set in the process of installing the window
        // decor, when theme attributes and the like are crystalized. Do not check the feature
        // before this happens.
        if (mContentParent == null) {
            installDecor();
        } else if (!hasFeature(FEATURE_CONTENT_TRANSITIONS)) {
            mContentParent.removeAllViews();
        }

        if (hasFeature(FEATURE_CONTENT_TRANSITIONS)) {
            final Scene newScene = Scene.getSceneForLayout(mContentParent, layoutResID,
                    getContext());
            transitionTo(newScene);
        } else {
            mLayoutInflater.inflate(layoutResID, mContentParent);
        }
        mContentParent.requestApplyInsets();
        final Callback cb = getCallback();
        if (cb != null && !isDestroyed()) {
            cb.onContentChanged();
        }
        mContentParentExplicitlySet = true;
    }

    @Override
    public void setContentView(View view) {
        setContentView(view, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        // Note: FEATURE_CONTENT_TRANSITIONS may be set in the process of installing the window
        // decor, when theme attributes and the like are crystalized. Do not check the feature
        // before this happens.
        if (mContentParent == null) {
            installDecor();
        } else if (!hasFeature(FEATURE_CONTENT_TRANSITIONS)) {
            mContentParent.removeAllViews();
        }

        if (hasFeature(FEATURE_CONTENT_TRANSITIONS)) {
            view.setLayoutParams(params);
            final Scene newScene = new Scene(mContentParent, view);
            transitionTo(newScene);
        } else {
            mContentParent.addView(view, params);
        }
        mContentParent.requestApplyInsets();
        final Callback cb = getCallback();
        if (cb != null && !isDestroyed()) {
            cb.onContentChanged();
        }
        mContentParentExplicitlySet = true;
    }

    @Override
    public void addContentView(View view, ViewGroup.LayoutParams params) {
        if (mContentParent == null) {
            installDecor();
        }
        if (hasFeature(FEATURE_CONTENT_TRANSITIONS)) {
            // TODO Augment the scenes/transitions API to support this.
            Log.v(TAG, "addContentView does not support content transitions");
        }
        mContentParent.addView(view, params);
        mContentParent.requestApplyInsets();
        final Callback cb = getCallback();
        if (cb != null && !isDestroyed()) {
            cb.onContentChanged();
        }
    }

    @Override
    public void clearContentView() {
        if (mDecor != null) {
            mDecor.clearContentView();
        }
    }

    private void transitionTo(Scene scene) {
        if (mContentScene == null) {
            scene.enter();
        } else {
            mTransitionManager.transitionTo(scene);
        }
        mContentScene = scene;
    }

    @Override
    public View getCurrentFocus() {
        return mDecor != null ? mDecor.findFocus() : null;
    }

    @Override
    public void takeSurface(Callback2 callback) {
        mTakeSurfaceCallback = callback;
    }

    public void takeInputQueue(InputQueue.Callback callback) {
        mTakeInputQueueCallback = callback;
    }

    @Override
    public boolean isFloating() {
        return mIsFloating;
    }

    public boolean isTranslucent() {
        return mIsTranslucent;
    }

    /**
     * @return Whether the window is currently showing the wallpaper.
     */
    boolean isShowingWallpaper() {
        return (getAttributes().flags & FLAG_SHOW_WALLPAPER) != 0;
    }

    /**
     * Return a LayoutInflater instance that can be used to inflate XML view layout
     * resources for use in this Window.
     *
     * @return LayoutInflater The shared LayoutInflater.
     */
    @Override
    public LayoutInflater getLayoutInflater() {
        return mLayoutInflater;
    }

    @Override
    public void setTitle(CharSequence title) {
        setTitle(title, true);
    }

    public void setTitle(CharSequence title, boolean updateAccessibilityTitle) {
        if (mTitleView != null) {
            mTitleView.setText(title);
        } else if (mDecorContentParent != null) {
            mDecorContentParent.setWindowTitle(title);
        }
        mTitle = title;
        if (updateAccessibilityTitle) {
            WindowManager.LayoutParams params = getAttributes();
            if (!TextUtils.equals(title, params.accessibilityTitle)) {
                params.accessibilityTitle = TextUtils.stringOrSpannedString(title);
                if (mDecor != null) {
                    // ViewRootImpl will make sure the change propagates to WindowManagerService
                    ViewRootImpl vr = mDecor.getViewRootImpl();
                    if (vr != null) {
                        vr.onWindowTitleChanged();
                    }
                }
                dispatchWindowAttributesChanged(getAttributes());
            }
        }
    }

    @Override
    @Deprecated
    public void setTitleColor(int textColor) {
        if (mTitleView != null) {
            mTitleView.setTextColor(textColor);
        }
        mTitleColor = textColor;
    }

    /**
     * Prepares the panel to either be opened or chorded. This creates the Menu
     * instance for the panel and populates it via the Activity callbacks.
     *
     * @param st The panel state to prepare.
     * @param event The event that triggered the preparing of the panel.
     * @return Whether the panel was prepared. If the panel should not be shown,
     *         returns false.
     */
    public final boolean preparePanel(PanelFeatureState st, KeyEvent event) {
        if (isDestroyed()) {
            return false;
        }

        // Already prepared (isPrepared will be reset to false later)
        if (st.isPrepared) {
            return true;
        }

        if ((mPreparedPanel != null) && (mPreparedPanel != st)) {
            // Another Panel is prepared and possibly open, so close it
            closePanel(mPreparedPanel, false);
        }

        final Callback cb = getCallback();

        if (cb != null) {
            st.createdPanelView = cb.onCreatePanelView(st.featureId);
        }

        final boolean isActionBarMenu =
                (st.featureId == FEATURE_OPTIONS_PANEL || st.featureId == FEATURE_ACTION_BAR);

        if (isActionBarMenu && mDecorContentParent != null) {
            // Enforce ordering guarantees around events so that the action bar never
            // dispatches menu-related events before the panel is prepared.
            mDecorContentParent.setMenuPrepared();
        }

        if (st.createdPanelView == null) {
            // Init the panel state's menu--return false if init failed
            if (st.menu == null || st.refreshMenuContent) {
                if (st.menu == null) {
                    if (!initializePanelMenu(st) || (st.menu == null)) {
                        return false;
                    }
                }

                if (isActionBarMenu && mDecorContentParent != null) {
                    if (mActionMenuPresenterCallback == null) {
                        mActionMenuPresenterCallback = new ActionMenuPresenterCallback();
                    }
                    mDecorContentParent.setMenu(st.menu, mActionMenuPresenterCallback);
                }

                // Call callback, and return if it doesn't want to display menu.

                // Creating the panel menu will involve a lot of manipulation;
                // don't dispatch change events to presenters until we're done.
                st.menu.stopDispatchingItemsChanged();
                if ((cb == null) || !cb.onCreatePanelMenu(st.featureId, st.menu)) {
                    // Ditch the menu created above
                    st.setMenu(null);

                    if (isActionBarMenu && mDecorContentParent != null) {
                        // Don't show it in the action bar either
                        mDecorContentParent.setMenu(null, mActionMenuPresenterCallback);
                    }

                    return false;
                }

                st.refreshMenuContent = false;
            }

            // Callback and return if the callback does not want to show the menu

            // Preparing the panel menu can involve a lot of manipulation;
            // don't dispatch change events to presenters until we're done.
            st.menu.stopDispatchingItemsChanged();

            // Restore action view state before we prepare. This gives apps
            // an opportunity to override frozen/restored state in onPrepare.
            if (st.frozenActionViewState != null) {
                st.menu.restoreActionViewStates(st.frozenActionViewState);
                st.frozenActionViewState = null;
            }

            if (!cb.onPreparePanel(st.featureId, st.createdPanelView, st.menu)) {
                if (isActionBarMenu && mDecorContentParent != null) {
                    // The app didn't want to show the menu for now but it still exists.
                    // Clear it out of the action bar.
                    mDecorContentParent.setMenu(null, mActionMenuPresenterCallback);
                }
                st.menu.startDispatchingItemsChanged();
                return false;
            }

            // Set the proper keymap
            KeyCharacterMap kmap = KeyCharacterMap.load(
                    event != null ? event.getDeviceId() : KeyCharacterMap.VIRTUAL_KEYBOARD);
            st.qwertyMode = kmap.getKeyboardType() != KeyCharacterMap.NUMERIC;
            st.menu.setQwertyMode(st.qwertyMode);
            st.menu.startDispatchingItemsChanged();
        }

        // Set other state
        st.isPrepared = true;
        st.isHandled = false;
        mPreparedPanel = st;

        return true;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // Action bars handle their own menu state
        if (mDecorContentParent == null) {
            PanelFeatureState st = getPanelState(FEATURE_OPTIONS_PANEL, false);
            if ((st != null) && (st.menu != null)) {
                if (st.isOpen) {
                    // Freeze state
                    final Bundle state = new Bundle();
                    if (st.iconMenuPresenter != null) {
                        st.iconMenuPresenter.saveHierarchyState(state);
                    }
                    if (st.listMenuPresenter != null) {
                        st.listMenuPresenter.saveHierarchyState(state);
                    }

                    // Remove the menu views since they need to be recreated
                    // according to the new configuration
                    clearMenuViews(st);

                    // Re-open the same menu
                    reopenMenu(false);

                    // Restore state
                    if (st.iconMenuPresenter != null) {
                        st.iconMenuPresenter.restoreHierarchyState(state);
                    }
                    if (st.listMenuPresenter != null) {
                        st.listMenuPresenter.restoreHierarchyState(state);
                    }

                } else {
                    // Clear menu views so on next menu opening, it will use
                    // the proper layout
                    clearMenuViews(st);
                }
            }
        }
    }

    @Override
    public void onMultiWindowModeChanged() {
        if (mDecor != null) {
            mDecor.onConfigurationChanged(getContext().getResources().getConfiguration());
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        if (mDecor != null) {
            mDecor.updatePictureInPictureOutlineProvider(isInPictureInPictureMode);
        }
    }

    @Override
    public void reportActivityRelaunched() {
        if (mDecor != null && mDecor.getViewRootImpl() != null) {
            mDecor.getViewRootImpl().reportActivityRelaunched();
        }
    }

    private static void clearMenuViews(PanelFeatureState st) {
        // This can be called on config changes, so we should make sure
        // the views will be reconstructed based on the new orientation, etc.

        // Allow the callback to create a new panel view
        st.createdPanelView = null;

        // Causes the decor view to be recreated
        st.refreshDecorView = true;

        st.clearMenuPresenters();
    }

    @Override
    public final void openPanel(int featureId, KeyEvent event) {
        if (featureId == FEATURE_OPTIONS_PANEL && mDecorContentParent != null &&
                mDecorContentParent.canShowOverflowMenu() &&
                !ViewConfiguration.get(getContext()).hasPermanentMenuKey()) {
            mDecorContentParent.showOverflowMenu();
        } else {
            openPanel(getPanelState(featureId, true), event);
        }
    }

    private void openPanel(final PanelFeatureState st, KeyEvent event) {
        // System.out.println("Open panel: isOpen=" + st.isOpen);

        // Already open, return
        if (st.isOpen || isDestroyed()) {
            return;
        }

        // Don't open an options panel for honeycomb apps on xlarge devices.
        // (The app should be using an action bar for menu items.)
        if (st.featureId == FEATURE_OPTIONS_PANEL) {
            Context context = getContext();
            Configuration config = context.getResources().getConfiguration();
            boolean isXLarge = (config.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) ==
                    Configuration.SCREENLAYOUT_SIZE_XLARGE;
            boolean isHoneycombApp = context.getApplicationInfo().targetSdkVersion >=
                    android.os.Build.VERSION_CODES.HONEYCOMB;

            if (isXLarge && isHoneycombApp) {
                return;
            }
        }

        Callback cb = getCallback();
        if ((cb != null) && (!cb.onMenuOpened(st.featureId, st.menu))) {
            // Callback doesn't want the menu to open, reset any state
            closePanel(st, true);
            return;
        }

        final WindowManager wm = getWindowManager();
        if (wm == null) {
            return;
        }

        // Prepare panel (should have been done before, but just in case)
        if (!preparePanel(st, event)) {
            return;
        }

        int width = WRAP_CONTENT;
        if (st.decorView == null || st.refreshDecorView) {
            if (st.decorView == null) {
                // Initialize the panel decor, this will populate st.decorView
                if (!initializePanelDecor(st) || (st.decorView == null))
                    return;
            } else if (st.refreshDecorView && (st.decorView.getChildCount() > 0)) {
                // Decor needs refreshing, so remove its views
                st.decorView.removeAllViews();
            }

            // This will populate st.shownPanelView
            if (!initializePanelContent(st) || !st.hasPanelItems()) {
                return;
            }

            ViewGroup.LayoutParams lp = st.shownPanelView.getLayoutParams();
            if (lp == null) {
                lp = new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            }

            int backgroundResId;
            if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT) {
                // If the contents is fill parent for the width, set the
                // corresponding background
                backgroundResId = st.fullBackground;
                width = MATCH_PARENT;
            } else {
                // Otherwise, set the normal panel background
                backgroundResId = st.background;
            }
            st.decorView.setWindowBackground(getContext().getDrawable(
                    backgroundResId));

            ViewParent shownPanelParent = st.shownPanelView.getParent();
            if (shownPanelParent != null && shownPanelParent instanceof ViewGroup) {
                ((ViewGroup) shownPanelParent).removeView(st.shownPanelView);
            }
            st.decorView.addView(st.shownPanelView, lp);

            /*
             * Give focus to the view, if it or one of its children does not
             * already have it.
             */
            if (!st.shownPanelView.hasFocus()) {
                st.shownPanelView.requestFocus();
            }
        } else if (!st.isInListMode()) {
            width = MATCH_PARENT;
        } else if (st.createdPanelView != null) {
            // If we already had a panel view, carry width=MATCH_PARENT through
            // as we did above when it was created.
            ViewGroup.LayoutParams lp = st.createdPanelView.getLayoutParams();
            if (lp != null && lp.width == ViewGroup.LayoutParams.MATCH_PARENT) {
                width = MATCH_PARENT;
            }
        }

        st.isHandled = false;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width, WRAP_CONTENT,
                st.x, st.y, WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG,
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                st.decorView.mDefaultOpacity);

        if (st.isCompact) {
            lp.gravity = getOptionsPanelGravity();
            sRotationWatcher.addWindow(this);
        } else {
            lp.gravity = st.gravity;
        }

        lp.windowAnimations = st.windowAnimations;

        wm.addView(st.decorView, lp);
        st.isOpen = true;
        // Log.v(TAG, "Adding main menu to window manager.");
    }

    @Override
    public final void closePanel(int featureId) {
        if (featureId == FEATURE_OPTIONS_PANEL && mDecorContentParent != null &&
                mDecorContentParent.canShowOverflowMenu() &&
                !ViewConfiguration.get(getContext()).hasPermanentMenuKey()) {
            mDecorContentParent.hideOverflowMenu();
        } else if (featureId == FEATURE_CONTEXT_MENU) {
            closeContextMenu();
        } else {
            closePanel(getPanelState(featureId, true), true);
        }
    }

    /**
     * Closes the given panel.
     *
     * @param st The panel to be closed.
     * @param doCallback Whether to notify the callback that the panel was
     *            closed. If the panel is in the process of re-opening or
     *            opening another panel (e.g., menu opening a sub menu), the
     *            callback should not happen and this variable should be false.
     *            In addition, this method internally will only perform the
     *            callback if the panel is open.
     */
    public final void closePanel(PanelFeatureState st, boolean doCallback) {
        // System.out.println("Close panel: isOpen=" + st.isOpen);
        if (doCallback && st.featureId == FEATURE_OPTIONS_PANEL &&
                mDecorContentParent != null && mDecorContentParent.isOverflowMenuShowing()) {
            checkCloseActionMenu(st.menu);
            return;
        }

        final ViewManager wm = getWindowManager();
        if ((wm != null) && st.isOpen) {
            if (st.decorView != null) {
                wm.removeView(st.decorView);
                // Log.v(TAG, "Removing main menu from window manager.");
                if (st.isCompact) {
                    sRotationWatcher.removeWindow(this);
                }
            }

            if (doCallback) {
                callOnPanelClosed(st.featureId, st, null);
            }
        }

        st.isPrepared = false;
        st.isHandled = false;
        st.isOpen = false;

        // This view is no longer shown, so null it out
        st.shownPanelView = null;

        if (st.isInExpandedMode) {
            // Next time the menu opens, it should not be in expanded mode, so
            // force a refresh of the decor
            st.refreshDecorView = true;
            st.isInExpandedMode = false;
        }

        if (mPreparedPanel == st) {
            mPreparedPanel = null;
            mPanelChordingKey = 0;
        }
    }

    void checkCloseActionMenu(Menu menu) {
        if (mClosingActionMenu) {
            return;
        }

        mClosingActionMenu = true;
        mDecorContentParent.dismissPopups();
        Callback cb = getCallback();
        if (cb != null && !isDestroyed()) {
            cb.onPanelClosed(FEATURE_ACTION_BAR, menu);
        }
        mClosingActionMenu = false;
    }

    @Override
    public final void togglePanel(int featureId, KeyEvent event) {
        PanelFeatureState st = getPanelState(featureId, true);
        if (st.isOpen) {
            closePanel(st, true);
        } else {
            openPanel(st, event);
        }
    }

    @Override
    public void invalidatePanelMenu(int featureId) {
        mInvalidatePanelMenuFeatures |= 1 << featureId;

        if (!mInvalidatePanelMenuPosted && mDecor != null) {
            mDecor.postOnAnimation(mInvalidatePanelMenuRunnable);
            mInvalidatePanelMenuPosted = true;
        }
    }

    void doPendingInvalidatePanelMenu() {
        if (mInvalidatePanelMenuPosted) {
            mDecor.removeCallbacks(mInvalidatePanelMenuRunnable);
            mInvalidatePanelMenuRunnable.run();
        }
    }

    void doInvalidatePanelMenu(int featureId) {
        PanelFeatureState st = getPanelState(featureId, false);
        if (st == null) {
            return;
        }
        Bundle savedActionViewStates = null;
        if (st.menu != null) {
            savedActionViewStates = new Bundle();
            st.menu.saveActionViewStates(savedActionViewStates);
            if (savedActionViewStates.size() > 0) {
                st.frozenActionViewState = savedActionViewStates;
            }
            // This will be started again when the panel is prepared.
            st.menu.stopDispatchingItemsChanged();
            st.menu.clear();
        }
        st.refreshMenuContent = true;
        st.refreshDecorView = true;

        // Prepare the options panel if we have an action bar
        if ((featureId == FEATURE_ACTION_BAR || featureId == FEATURE_OPTIONS_PANEL)
                && mDecorContentParent != null) {
            st = getPanelState(Window.FEATURE_OPTIONS_PANEL, false);
            if (st != null) {
                st.isPrepared = false;
                preparePanel(st, null);
            }
        }
    }

    /**
     * Called when the panel key is pushed down.
     * @param featureId The feature ID of the relevant panel (defaults to FEATURE_OPTIONS_PANEL}.
     * @param event The key event.
     * @return Whether the key was handled.
     */
    public final boolean onKeyDownPanel(int featureId, KeyEvent event) {
        final int keyCode = event.getKeyCode();

        if (event.getRepeatCount() == 0) {
            // The panel key was pushed, so set the chording key
            mPanelChordingKey = keyCode;

            PanelFeatureState st = getPanelState(featureId, false);
            if (st != null && !st.isOpen) {
                return preparePanel(st, event);
            }
        }

        return false;
    }

    /**
     * Called when the panel key is released.
     * @param featureId The feature ID of the relevant panel (defaults to FEATURE_OPTIONS_PANEL}.
     * @param event The key event.
     */
    public final void onKeyUpPanel(int featureId, KeyEvent event) {
        // The panel key was released, so clear the chording key
        if (mPanelChordingKey != 0) {
            mPanelChordingKey = 0;

            final PanelFeatureState st = getPanelState(featureId, false);

            if (event.isCanceled() || (mDecor != null && mDecor.mPrimaryActionMode != null) ||
                    (st == null)) {
                return;
            }

            boolean playSoundEffect = false;
            if (featureId == FEATURE_OPTIONS_PANEL && mDecorContentParent != null &&
                    mDecorContentParent.canShowOverflowMenu() &&
                    !ViewConfiguration.get(getContext()).hasPermanentMenuKey()) {
                if (!mDecorContentParent.isOverflowMenuShowing()) {
                    if (!isDestroyed() && preparePanel(st, event)) {
                        playSoundEffect = mDecorContentParent.showOverflowMenu();
                    }
                } else {
                    playSoundEffect = mDecorContentParent.hideOverflowMenu();
                }
            } else {
                if (st.isOpen || st.isHandled) {

                    // Play the sound effect if the user closed an open menu (and not if
                    // they just released a menu shortcut)
                    playSoundEffect = st.isOpen;

                    // Close menu
                    closePanel(st, true);

                } else if (st.isPrepared) {
                    boolean show = true;
                    if (st.refreshMenuContent) {
                        // Something may have invalidated the menu since we prepared it.
                        // Re-prepare it to refresh.
                        st.isPrepared = false;
                        show = preparePanel(st, event);
                    }

                    if (show) {
                        // Write 'menu opened' to event log
                        EventLog.writeEvent(50001, 0);

                        // Show menu
                        openPanel(st, event);

                        playSoundEffect = true;
                    }
                }
            }

            if (playSoundEffect) {
                AudioManager audioManager = (AudioManager) getContext().getSystemService(
                        Context.AUDIO_SERVICE);
                if (audioManager != null) {
                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK);
                } else {
                    Log.w(TAG, "Couldn't get audio manager");
                }
            }
        }
    }

    @Override
    public final void closeAllPanels() {
        final ViewManager wm = getWindowManager();
        if (wm == null) {
            return;
        }

        final PanelFeatureState[] panels = mPanels;
        final int N = panels != null ? panels.length : 0;
        for (int i = 0; i < N; i++) {
            final PanelFeatureState panel = panels[i];
            if (panel != null) {
                closePanel(panel, true);
            }
        }

        closeContextMenu();
    }

    /**
     * Closes the context menu. This notifies the menu logic of the close, along
     * with dismissing it from the UI.
     */
    private synchronized void closeContextMenu() {
        if (mContextMenu != null) {
            mContextMenu.close();
            dismissContextMenu();
        }
    }

    /**
     * Dismisses just the context menu UI. To close the context menu, use
     * {@link #closeContextMenu()}.
     */
    private synchronized void dismissContextMenu() {
        mContextMenu = null;

        if (mContextMenuHelper != null) {
            mContextMenuHelper.dismiss();
            mContextMenuHelper = null;
        }
    }

    @Override
    public boolean performPanelShortcut(int featureId, int keyCode, KeyEvent event, int flags) {
        return performPanelShortcut(getPanelState(featureId, false), keyCode, event, flags);
    }

    boolean performPanelShortcut(PanelFeatureState st, int keyCode, KeyEvent event,
            int flags) {
        if (event.isSystem() || (st == null)) {
            return false;
        }

        boolean handled = false;

        // Only try to perform menu shortcuts if preparePanel returned true (possible false
        // return value from application not wanting to show the menu).
        if ((st.isPrepared || preparePanel(st, event)) && st.menu != null) {
            // The menu is prepared now, perform the shortcut on it
            handled = st.menu.performShortcut(keyCode, event, flags);
        }

        if (handled) {
            // Mark as handled
            st.isHandled = true;

            // Only close down the menu if we don't have an action bar keeping it open.
            if ((flags & Menu.FLAG_PERFORM_NO_CLOSE) == 0 && mDecorContentParent == null) {
                closePanel(st, true);
            }
        }

        return handled;
    }

    @Override
    public boolean performPanelIdentifierAction(int featureId, int id, int flags) {

        PanelFeatureState st = getPanelState(featureId, true);
        if (!preparePanel(st, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU))) {
            return false;
        }
        if (st.menu == null) {
            return false;
        }

        boolean res = st.menu.performIdentifierAction(id, flags);

        // Only close down the menu if we don't have an action bar keeping it open.
        if (mDecorContentParent == null) {
            closePanel(st, true);
        }

        return res;
    }

    public PanelFeatureState findMenuPanel(Menu menu) {
        final PanelFeatureState[] panels = mPanels;
        final int N = panels != null ? panels.length : 0;
        for (int i = 0; i < N; i++) {
            final PanelFeatureState panel = panels[i];
            if (panel != null && panel.menu == menu) {
                return panel;
            }
        }
        return null;
    }

    public boolean onMenuItemSelected(MenuBuilder menu, MenuItem item) {
        final Callback cb = getCallback();
        if (cb != null && !isDestroyed()) {
            final PanelFeatureState panel = findMenuPanel(menu.getRootMenu());
            if (panel != null) {
                return cb.onMenuItemSelected(panel.featureId, item);
            }
        }
        return false;
    }

    public void onMenuModeChange(MenuBuilder menu) {
        reopenMenu(true);
    }

    private void reopenMenu(boolean toggleMenuMode) {
        if (mDecorContentParent != null && mDecorContentParent.canShowOverflowMenu() &&
                (!ViewConfiguration.get(getContext()).hasPermanentMenuKey() ||
                        mDecorContentParent.isOverflowMenuShowPending())) {
            final Callback cb = getCallback();
            if (!mDecorContentParent.isOverflowMenuShowing() || !toggleMenuMode) {
                if (cb != null && !isDestroyed()) {
                    // If we have a menu invalidation pending, do it now.
                    if (mInvalidatePanelMenuPosted &&
                            (mInvalidatePanelMenuFeatures & (1 << FEATURE_OPTIONS_PANEL)) != 0) {
                        mDecor.removeCallbacks(mInvalidatePanelMenuRunnable);
                        mInvalidatePanelMenuRunnable.run();
                    }

                    final PanelFeatureState st = getPanelState(FEATURE_OPTIONS_PANEL, false);

                    // If we don't have a menu or we're waiting for a full content refresh,
                    // forget it. This is a lingering event that no longer matters.
                    if (st != null && st.menu != null && !st.refreshMenuContent &&
                            cb.onPreparePanel(FEATURE_OPTIONS_PANEL, st.createdPanelView, st.menu)) {
                        cb.onMenuOpened(FEATURE_ACTION_BAR, st.menu);
                        mDecorContentParent.showOverflowMenu();
                    }
                }
            } else {
                mDecorContentParent.hideOverflowMenu();
                final PanelFeatureState st = getPanelState(FEATURE_OPTIONS_PANEL, false);
                if (st != null && cb != null && !isDestroyed()) {
                    cb.onPanelClosed(FEATURE_ACTION_BAR, st.menu);
                }
            }
            return;
        }

        PanelFeatureState st = getPanelState(FEATURE_OPTIONS_PANEL, false);

        if (st == null) {
            return;
        }

        // Save the future expanded mode state since closePanel will reset it
        boolean newExpandedMode = toggleMenuMode ? !st.isInExpandedMode : st.isInExpandedMode;

        st.refreshDecorView = true;
        closePanel(st, false);

        // Set the expanded mode state
        st.isInExpandedMode = newExpandedMode;

        openPanel(st, null);
    }

    /**
     * Initializes the menu associated with the given panel feature state. You
     * must at the very least set PanelFeatureState.menu to the Menu to be
     * associated with the given panel state. The default implementation creates
     * a new menu for the panel state.
     *
     * @param st The panel whose menu is being initialized.
     * @return Whether the initialization was successful.
     */
    protected boolean initializePanelMenu(final PanelFeatureState st) {
        Context context = getContext();

        // If we have an action bar, initialize the menu with the right theme.
        if ((st.featureId == FEATURE_OPTIONS_PANEL || st.featureId == FEATURE_ACTION_BAR) &&
                mDecorContentParent != null) {
            final TypedValue outValue = new TypedValue();
            final Theme baseTheme = context.getTheme();
            baseTheme.resolveAttribute(R.attr.actionBarTheme, outValue, true);

            Theme widgetTheme = null;
            if (outValue.resourceId != 0) {
                widgetTheme = context.getResources().newTheme();
                widgetTheme.setTo(baseTheme);
                widgetTheme.applyStyle(outValue.resourceId, true);
                widgetTheme.resolveAttribute(
                        R.attr.actionBarWidgetTheme, outValue, true);
            } else {
                baseTheme.resolveAttribute(
                        R.attr.actionBarWidgetTheme, outValue, true);
            }

            if (outValue.resourceId != 0) {
                if (widgetTheme == null) {
                    widgetTheme = context.getResources().newTheme();
                    widgetTheme.setTo(baseTheme);
                }
                widgetTheme.applyStyle(outValue.resourceId, true);
            }

            if (widgetTheme != null) {
                context = new ContextThemeWrapper(context, 0);
                context.getTheme().setTo(widgetTheme);
            }
        }

        final MenuBuilder menu = new MenuBuilder(context);
        menu.setCallback(this);
        st.setMenu(menu);

        return true;
    }

    /**
     * Perform initial setup of a panel. This should at the very least set the
     * style information in the PanelFeatureState and must set
     * PanelFeatureState.decor to the panel's window decor view.
     *
     * @param st The panel being initialized.
     */
    protected boolean initializePanelDecor(PanelFeatureState st) {
        st.decorView = generateDecor(st.featureId);
        st.gravity = Gravity.CENTER | Gravity.BOTTOM;
        st.setStyle(getContext());
        TypedArray a = getContext().obtainStyledAttributes(null,
                R.styleable.Window, 0, st.listPresenterTheme);
        final float elevation = a.getDimension(R.styleable.Window_windowElevation, 0);
        if (elevation != 0) {
            st.decorView.setElevation(elevation);
        }
        a.recycle();

        return true;
    }

    /**
     * Determine the gravity value for the options panel. This can
     * differ in compact mode.
     *
     * @return gravity value to use for the panel window
     */
    private int getOptionsPanelGravity() {
        try {
            return WindowManagerHolder.sWindowManager.getPreferredOptionsPanelGravity(
                    getContext().getDisplayId());
        } catch (RemoteException ex) {
            Log.e(TAG, "Couldn't getOptionsPanelGravity; using default", ex);
            return Gravity.CENTER | Gravity.BOTTOM;
        }
    }

    void onOptionsPanelRotationChanged() {
        final PanelFeatureState st = getPanelState(FEATURE_OPTIONS_PANEL, false);
        if (st == null) return;

        final WindowManager.LayoutParams lp = st.decorView != null ?
                (WindowManager.LayoutParams) st.decorView.getLayoutParams() : null;
        if (lp != null) {
            lp.gravity = getOptionsPanelGravity();
            final ViewManager wm = getWindowManager();
            if (wm != null) {
                wm.updateViewLayout(st.decorView, lp);
            }
        }
    }

    /**
     * Initializes the panel associated with the panel feature state. You must
     * at the very least set PanelFeatureState.panel to the View implementing
     * its contents. The default implementation gets the panel from the menu.
     *
     * @param st The panel state being initialized.
     * @return Whether the initialization was successful.
     */
    protected boolean initializePanelContent(PanelFeatureState st) {
        if (st.createdPanelView != null) {
            st.shownPanelView = st.createdPanelView;
            return true;
        }

        if (st.menu == null) {
            return false;
        }

        if (mPanelMenuPresenterCallback == null) {
            mPanelMenuPresenterCallback = new PanelMenuPresenterCallback();
        }

        MenuView menuView = st.isInListMode()
                ? st.getListMenuView(getContext(), mPanelMenuPresenterCallback)
                : st.getIconMenuView(getContext(), mPanelMenuPresenterCallback);

        st.shownPanelView = (View) menuView;

        if (st.shownPanelView != null) {
            // Use the menu View's default animations if it has any
            final int defaultAnimations = menuView.getWindowAnimations();
            if (defaultAnimations != 0) {
                st.windowAnimations = defaultAnimations;
            }
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean performContextMenuIdentifierAction(int id, int flags) {
        return (mContextMenu != null) ? mContextMenu.performIdentifierAction(id, flags) : false;
    }

    @Override
    public final void setElevation(float elevation) {
        mElevation = elevation;
        final WindowManager.LayoutParams attrs = getAttributes();
        if (mDecor != null) {
            mDecor.setElevation(elevation);
            attrs.setSurfaceInsets(mDecor, true /*manual*/, false /*preservePrevious*/);
        }
        dispatchWindowAttributesChanged(attrs);
    }

    @Override
    public float getElevation() {
        return mElevation;
    }

    @Override
    public final void setClipToOutline(boolean clipToOutline) {
        mClipToOutline = clipToOutline;
        if (mDecor != null) {
            mDecor.setClipToOutline(clipToOutline);
        }
    }

    @Override
    public final void setBackgroundDrawable(Drawable drawable) {
        if (drawable != mBackgroundDrawable) {
            mBackgroundDrawable = drawable;
            if (mDecor != null) {
                mDecor.startChanging();
                mDecor.setWindowBackground(drawable);
                if (mBackgroundFallbackDrawable != null) {
                    mDecor.setBackgroundFallback(drawable != null ? null :
                            mBackgroundFallbackDrawable);
                }
                mDecor.finishChanging();
            }
        }
    }

    @Override
    public final void setBackgroundBlurRadius(int blurRadius) {
        super.setBackgroundBlurRadius(blurRadius);
        if (getContext().getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_CROSS_LAYER_BLUR)) {
            mBackgroundBlurRadius = Math.max(blurRadius, 0);
        }
    }

    @Override
    public final void setFeatureDrawableResource(int featureId, int resId) {
        if (resId != 0) {
            DrawableFeatureState st = getDrawableState(featureId, true);
            if (st.resid != resId) {
                st.resid = resId;
                st.uri = null;
                st.local = getContext().getDrawable(resId);
                updateDrawable(featureId, st, false);
            }
        } else {
            setFeatureDrawable(featureId, null);
        }
    }

    @Override
    public final void setFeatureDrawableUri(int featureId, Uri uri) {
        if (uri != null) {
            DrawableFeatureState st = getDrawableState(featureId, true);
            if (st.uri == null || !st.uri.equals(uri)) {
                st.resid = 0;
                st.uri = uri;
                st.local = loadImageURI(uri);
                updateDrawable(featureId, st, false);
            }
        } else {
            setFeatureDrawable(featureId, null);
        }
    }

    @Override
    public final void setFeatureDrawable(int featureId, Drawable drawable) {
        DrawableFeatureState st = getDrawableState(featureId, true);
        st.resid = 0;
        st.uri = null;
        if (st.local != drawable) {
            st.local = drawable;
            updateDrawable(featureId, st, false);
        }
    }

    @Override
    public void setFeatureDrawableAlpha(int featureId, int alpha) {
        DrawableFeatureState st = getDrawableState(featureId, true);
        if (st.alpha != alpha) {
            st.alpha = alpha;
            updateDrawable(featureId, st, false);
        }
    }

    protected final void setFeatureDefaultDrawable(int featureId, Drawable drawable) {
        DrawableFeatureState st = getDrawableState(featureId, true);
        if (st.def != drawable) {
            st.def = drawable;
            updateDrawable(featureId, st, false);
        }
    }

    @Override
    public final void setFeatureInt(int featureId, int value) {
        // XXX Should do more management (as with drawable features) to
        // deal with interactions between multiple window policies.
        updateInt(featureId, value, false);
    }

    /**
     * Update the state of a drawable feature. This should be called, for every
     * drawable feature supported, as part of onActive(), to make sure that the
     * contents of a containing window is properly updated.
     *
     * @see #onActive
     * @param featureId The desired drawable feature to change.
     * @param fromActive Always true when called from onActive().
     */
    protected final void updateDrawable(int featureId, boolean fromActive) {
        final DrawableFeatureState st = getDrawableState(featureId, false);
        if (st != null) {
            updateDrawable(featureId, st, fromActive);
        }
    }

    /**
     * Called when a Drawable feature changes, for the window to update its
     * graphics.
     *
     * @param featureId The feature being changed.
     * @param drawable The new Drawable to show, or null if none.
     * @param alpha The new alpha blending of the Drawable.
     */
    protected void onDrawableChanged(int featureId, Drawable drawable, int alpha) {
        ImageView view;
        if (featureId == FEATURE_LEFT_ICON) {
            view = getLeftIconView();
        } else if (featureId == FEATURE_RIGHT_ICON) {
            view = getRightIconView();
        } else {
            return;
        }

        if (drawable != null) {
            drawable.setAlpha(alpha);
            view.setImageDrawable(drawable);
            view.setVisibility(View.VISIBLE);
        } else {
            view.setVisibility(View.GONE);
        }
    }

    /**
     * Called when an int feature changes, for the window to update its
     * graphics.
     *
     * @param featureId The feature being changed.
     * @param value The new integer value.
     */
    protected void onIntChanged(int featureId, int value) {
        if (featureId == FEATURE_PROGRESS || featureId == FEATURE_INDETERMINATE_PROGRESS) {
            updateProgressBars(value);
        } else if (featureId == FEATURE_CUSTOM_TITLE) {
            FrameLayout titleContainer = findViewById(R.id.title_container);
            if (titleContainer != null) {
                mLayoutInflater.inflate(value, titleContainer);
            }
        }
    }

    /**
     * Updates the progress bars that are shown in the title bar.
     *
     * @param value Can be one of {@link Window#PROGRESS_VISIBILITY_ON},
     *            {@link Window#PROGRESS_VISIBILITY_OFF},
     *            {@link Window#PROGRESS_INDETERMINATE_ON},
     *            {@link Window#PROGRESS_INDETERMINATE_OFF}, or a value
     *            starting at {@link Window#PROGRESS_START} through
     *            {@link Window#PROGRESS_END} for setting the default
     *            progress (if {@link Window#PROGRESS_END} is given,
     *            the progress bar widgets in the title will be hidden after an
     *            animation), a value between
     *            {@link Window#PROGRESS_SECONDARY_START} -
     *            {@link Window#PROGRESS_SECONDARY_END} for the
     *            secondary progress (if
     *            {@link Window#PROGRESS_SECONDARY_END} is given, the
     *            progress bar widgets will still be shown with the secondary
     *            progress bar will be completely filled in.)
     */
    private void updateProgressBars(int value) {
        ProgressBar circularProgressBar = getCircularProgressBar(true);
        ProgressBar horizontalProgressBar = getHorizontalProgressBar(true);

        final int features = getLocalFeatures();
        if (value == PROGRESS_VISIBILITY_ON) {
            if ((features & (1 << FEATURE_PROGRESS)) != 0) {
                if (horizontalProgressBar != null) {
                    int level = horizontalProgressBar.getProgress();
                    int visibility = (horizontalProgressBar.isIndeterminate() || level < 10000) ?
                            View.VISIBLE : View.INVISIBLE;
                    horizontalProgressBar.setVisibility(visibility);
                } else {
                    Log.e(TAG, "Horizontal progress bar not located in current window decor");
                }
            }
            if ((features & (1 << FEATURE_INDETERMINATE_PROGRESS)) != 0) {
                if (circularProgressBar != null) {
                    circularProgressBar.setVisibility(View.VISIBLE);
                } else {
                    Log.e(TAG, "Circular progress bar not located in current window decor");
                }
            }
        } else if (value == PROGRESS_VISIBILITY_OFF) {
            if ((features & (1 << FEATURE_PROGRESS)) != 0) {
                if (horizontalProgressBar != null) {
                    horizontalProgressBar.setVisibility(View.GONE);
                } else {
                    Log.e(TAG, "Horizontal progress bar not located in current window decor");
                }
            }
            if ((features & (1 << FEATURE_INDETERMINATE_PROGRESS)) != 0) {
                if (circularProgressBar != null) {
                    circularProgressBar.setVisibility(View.GONE);
                } else {
                    Log.e(TAG, "Circular progress bar not located in current window decor");
                }
            }
        } else if (value == PROGRESS_INDETERMINATE_ON) {
            if (horizontalProgressBar != null) {
                horizontalProgressBar.setIndeterminate(true);
            } else {
                Log.e(TAG, "Horizontal progress bar not located in current window decor");
            }
        } else if (value == PROGRESS_INDETERMINATE_OFF) {
            if (horizontalProgressBar != null) {
                horizontalProgressBar.setIndeterminate(false);
            } else {
                Log.e(TAG, "Horizontal progress bar not located in current window decor");
            }
        } else if (PROGRESS_START <= value && value <= PROGRESS_END) {
            // We want to set the progress value before testing for visibility
            // so that when the progress bar becomes visible again, it has the
            // correct level.
            if (horizontalProgressBar != null) {
                horizontalProgressBar.setProgress(value - PROGRESS_START);
            } else {
                Log.e(TAG, "Horizontal progress bar not located in current window decor");
            }

            if (value < PROGRESS_END) {
                showProgressBars(horizontalProgressBar, circularProgressBar);
            } else {
                hideProgressBars(horizontalProgressBar, circularProgressBar);
            }
        } else if (PROGRESS_SECONDARY_START <= value && value <= PROGRESS_SECONDARY_END) {
            if (horizontalProgressBar != null) {
                horizontalProgressBar.setSecondaryProgress(value - PROGRESS_SECONDARY_START);
            } else {
                Log.e(TAG, "Horizontal progress bar not located in current window decor");
            }

            showProgressBars(horizontalProgressBar, circularProgressBar);
        }

    }

    private void showProgressBars(ProgressBar horizontalProgressBar, ProgressBar spinnyProgressBar) {
        final int features = getLocalFeatures();
        if ((features & (1 << FEATURE_INDETERMINATE_PROGRESS)) != 0 &&
                spinnyProgressBar != null && spinnyProgressBar.getVisibility() == View.INVISIBLE) {
            spinnyProgressBar.setVisibility(View.VISIBLE);
        }
        // Only show the progress bars if the primary progress is not complete
        if ((features & (1 << FEATURE_PROGRESS)) != 0 && horizontalProgressBar != null &&
                horizontalProgressBar.getProgress() < 10000) {
            horizontalProgressBar.setVisibility(View.VISIBLE);
        }
    }

    private void hideProgressBars(ProgressBar horizontalProgressBar, ProgressBar spinnyProgressBar) {
        final int features = getLocalFeatures();
        Animation anim = AnimationUtils.loadAnimation(getContext(), R.anim.fade_out);
        anim.setDuration(1000);
        if ((features & (1 << FEATURE_INDETERMINATE_PROGRESS)) != 0 &&
                spinnyProgressBar != null &&
                spinnyProgressBar.getVisibility() == View.VISIBLE) {
            spinnyProgressBar.startAnimation(anim);
            spinnyProgressBar.setVisibility(View.INVISIBLE);
        }
        if ((features & (1 << FEATURE_PROGRESS)) != 0 && horizontalProgressBar != null &&
                horizontalProgressBar.getVisibility() == View.VISIBLE) {
            horizontalProgressBar.startAnimation(anim);
            horizontalProgressBar.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    public void setIcon(int resId) {
        mIconRes = resId;
        mResourcesSetFlags |= FLAG_RESOURCE_SET_ICON;
        mResourcesSetFlags &= ~FLAG_RESOURCE_SET_ICON_FALLBACK;
        if (mDecorContentParent != null) {
            mDecorContentParent.setIcon(resId);
        }
    }

    @Override
    public void setDefaultIcon(int resId) {
        if ((mResourcesSetFlags & FLAG_RESOURCE_SET_ICON) != 0) {
            return;
        }
        mIconRes = resId;
        if (mDecorContentParent != null && (!mDecorContentParent.hasIcon() ||
                (mResourcesSetFlags & FLAG_RESOURCE_SET_ICON_FALLBACK) != 0)) {
            if (resId != 0) {
                mDecorContentParent.setIcon(resId);
                mResourcesSetFlags &= ~FLAG_RESOURCE_SET_ICON_FALLBACK;
            } else {
                mDecorContentParent.setIcon(
                        getContext().getPackageManager().getDefaultActivityIcon());
                mResourcesSetFlags |= FLAG_RESOURCE_SET_ICON_FALLBACK;
            }
        }
    }

    @Override
    public void setLogo(int resId) {
        mLogoRes = resId;
        mResourcesSetFlags |= FLAG_RESOURCE_SET_LOGO;
        if (mDecorContentParent != null) {
            mDecorContentParent.setLogo(resId);
        }
    }

    @Override
    public void setDefaultLogo(int resId) {
        if ((mResourcesSetFlags & FLAG_RESOURCE_SET_LOGO) != 0) {
            return;
        }
        mLogoRes = resId;
        if (mDecorContentParent != null && !mDecorContentParent.hasLogo()) {
            mDecorContentParent.setLogo(resId);
        }
    }

    @Override
    public void setLocalFocus(boolean hasFocus, boolean inTouchMode) {
        getViewRootImpl().windowFocusChanged(hasFocus, inTouchMode);

    }

    @Override
    public void injectInputEvent(InputEvent event) {
        getViewRootImpl().dispatchInputEvent(event);
    }

    private ViewRootImpl getViewRootImpl() {
        ViewRootImpl viewRootImpl = getViewRootImplOrNull();
        if (viewRootImpl != null) {
            return viewRootImpl;
        }
        throw new IllegalStateException("view not added");
    }

    private ViewRootImpl getViewRootImplOrNull() {
        if (mDecor == null) {
            return null;
        }
        return mDecor.getViewRootImpl();
    }

    /**
     * Request that key events come to this activity. Use this if your activity
     * has no views with focus, but the activity still wants a chance to process
     * key events.
     */
    @Override
    public void takeKeyEvents(boolean get) {
        mDecor.setFocusable(get);
    }

    @Override
    public boolean superDispatchKeyEvent(KeyEvent event) {
        return mDecor.superDispatchKeyEvent(event);
    }

    @Override
    public boolean superDispatchKeyShortcutEvent(KeyEvent event) {
        return mDecor.superDispatchKeyShortcutEvent(event);
    }

    @Override
    public boolean superDispatchTouchEvent(MotionEvent event) {
        return mDecor.superDispatchTouchEvent(event);
    }

    @Override
    public boolean superDispatchTrackballEvent(MotionEvent event) {
        return mDecor.superDispatchTrackballEvent(event);
    }

    @Override
    public boolean superDispatchGenericMotionEvent(MotionEvent event) {
        return mDecor.superDispatchGenericMotionEvent(event);
    }

    /**
     * A key was pressed down and not handled by anything else in the window.
     *
     * @see #onKeyUp
     * @see android.view.KeyEvent
     */
    protected boolean onKeyDown(int featureId, int keyCode, KeyEvent event) {
        /* ****************************************************************************
         * HOW TO DECIDE WHERE YOUR KEY HANDLING GOES.
         *
         * If your key handling must happen before the app gets a crack at the event,
         * it goes in PhoneWindowManager.
         *
         * If your key handling should happen in all windows, and does not depend on
         * the state of the current application, other than that the current
         * application can override the behavior by handling the event itself, it
         * should go in PhoneFallbackEventHandler.
         *
         * Only if your handling depends on the window, and the fact that it has
         * a DecorView, should it go here.
         * ****************************************************************************/

        final KeyEvent.DispatcherState dispatcher =
                mDecor != null ? mDecor.getKeyDispatcherState() : null;
        //Log.i(TAG, "Key down: repeat=" + event.getRepeatCount()
        //        + " flags=0x" + Integer.toHexString(event.getFlags()));

        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE: {
                // If we have a session send it the volume command, otherwise
                // use the suggested stream.
                if (mMediaController != null) {
                    getMediaSessionManager().dispatchVolumeKeyEventToSessionAsSystemService(event,
                            mMediaController.getSessionToken());
                } else {
                    getMediaSessionManager().dispatchVolumeKeyEventAsSystemService(event,
                            mVolumeControlStreamType);
                }
                return true;
            }
            // These are all the recognized media key codes in
            // KeyEvent.isMediaSessionKey()
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: {
                if (mMediaController != null) {
                    if (getMediaSessionManager().dispatchMediaKeyEventToSessionAsSystemService(
                            event, mMediaController.getSessionToken())) {
                        return true;
                    }
                }
                return false;
            }

            case KeyEvent.KEYCODE_MENU: {
                onKeyDownPanel((featureId < 0) ? FEATURE_OPTIONS_PANEL : featureId, event);
                return true;
            }

            case KeyEvent.KEYCODE_BACK: {
                if (event.getRepeatCount() > 0) break;
                if (featureId < 0) break;
                // Currently don't do anything with long press.
                if (dispatcher != null) {
                    dispatcher.startTracking(event, this);
                }
                return true;
            }

        }

        return false;
    }

    private KeyguardManager getKeyguardManager() {
        if (mKeyguardManager == null) {
            mKeyguardManager = (KeyguardManager) getContext().getSystemService(
                    Context.KEYGUARD_SERVICE);
        }
        return mKeyguardManager;
    }

    AudioManager getAudioManager() {
        if (mAudioManager == null) {
            mAudioManager = (AudioManager)getContext().getSystemService(Context.AUDIO_SERVICE);
        }
        return mAudioManager;
    }

    private MediaSessionManager getMediaSessionManager() {
        if (mMediaSessionManager == null) {
            mMediaSessionManager = (MediaSessionManager) getContext().getSystemService(
                    Context.MEDIA_SESSION_SERVICE);
        }
        return mMediaSessionManager;
    }

    /**
     * A key was released and not handled by anything else in the window.
     *
     * @see #onKeyDown
     * @see android.view.KeyEvent
     */
    protected boolean onKeyUp(int featureId, int keyCode, KeyEvent event) {
        final KeyEvent.DispatcherState dispatcher =
                mDecor != null ? mDecor.getKeyDispatcherState() : null;
        if (dispatcher != null) {
            dispatcher.handleUpEvent(event);
        }
        //Log.i(TAG, "Key up: repeat=" + event.getRepeatCount()
        //        + " flags=0x" + Integer.toHexString(event.getFlags()));

        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN: {
                // If we have a session send it the volume command, otherwise
                // use the suggested stream.
                if (mMediaController != null) {
                    getMediaSessionManager().dispatchVolumeKeyEventToSessionAsSystemService(event,
                            mMediaController.getSessionToken());
                } else {
                    getMediaSessionManager().dispatchVolumeKeyEventAsSystemService(
                            event, mVolumeControlStreamType);
                }
                return true;
            }
            case KeyEvent.KEYCODE_VOLUME_MUTE: {
                // Similar code is in PhoneFallbackEventHandler in case the window
                // doesn't have one of these.  In this case, we execute it here and
                // eat the event instead, because we have mVolumeControlStreamType
                // and they don't.
                getMediaSessionManager().dispatchVolumeKeyEventAsSystemService(
                        event, AudioManager.USE_DEFAULT_STREAM_TYPE);
                return true;
            }
            // These are all the recognized media key codes in
            // KeyEvent.isMediaSessionKey()
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: {
                if (mMediaController != null) {
                    if (getMediaSessionManager().dispatchMediaKeyEventToSessionAsSystemService(
                            event, mMediaController.getSessionToken())) {
                        return true;
                    }
                }
                return false;
            }

            case KeyEvent.KEYCODE_MENU: {
                onKeyUpPanel(featureId < 0 ? FEATURE_OPTIONS_PANEL : featureId,
                        event);
                return true;
            }

            case KeyEvent.KEYCODE_BACK: {
                if (featureId < 0) break;
                if (event.isTracking() && !event.isCanceled()) {
                    if (featureId == FEATURE_OPTIONS_PANEL) {
                        PanelFeatureState st = getPanelState(featureId, false);
                        if (st != null && st.isInExpandedMode) {
                            // If the user is in an expanded menu and hits back, it
                            // should go back to the icon menu
                            reopenMenu(true);
                            return true;
                        }
                    }
                    closePanel(featureId);
                    return true;
                }
                break;
            }

            case KeyEvent.KEYCODE_SEARCH: {
                /*
                 * Do this in onKeyUp since the Search key is also used for
                 * chording quick launch shortcuts.
                 */
                if (isNotInstantAppAndKeyguardRestricted()) {
                    break;
                }
                if ((getContext().getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_WATCH) {
                    break;
                }
                if (event.isTracking() && !event.isCanceled()) {
                    launchDefaultSearch(event);
                }
                return true;
            }

            case KeyEvent.KEYCODE_WINDOW: {
                if (mSupportsPictureInPicture && !event.isCanceled()) {
                    getWindowControllerCallback().enterPictureInPictureModeIfPossible();
                }
                return true;
            }
        }

        return false;
    }

    private boolean isNotInstantAppAndKeyguardRestricted() {
        return !getContext().getPackageManager().isInstantApp()
            && getKeyguardManager().inKeyguardRestrictedInputMode();
    }

    @Override
    protected void onActive() {
    }

    @Override
    public final @NonNull View getDecorView() {
        if (mDecor == null || mForceDecorInstall) {
            installDecor();
        }
        return mDecor;
    }

    @Override
    public final View peekDecorView() {
        return mDecor;
    }

    /** Notify when decor view is attached to window and {@link ViewRootImpl} is available. */
    void onViewRootImplSet(ViewRootImpl viewRoot) {
        viewRoot.setActivityConfigCallback(mActivityConfigCallback);
        applyDecorFitsSystemWindows();
    }

    static private final String FOCUSED_ID_TAG = "android:focusedViewId";
    static private final String VIEWS_TAG = "android:views";
    static private final String PANELS_TAG = "android:Panels";
    static private final String ACTION_BAR_TAG = "android:ActionBar";

    /** {@inheritDoc} */
    @Override
    public Bundle saveHierarchyState() {
        Bundle outState = new Bundle();
        if (mContentParent == null) {
            return outState;
        }

        SparseArray<Parcelable> states = new SparseArray<Parcelable>();
        mContentParent.saveHierarchyState(states);
        outState.putSparseParcelableArray(VIEWS_TAG, states);

        // Save the focused view ID.
        final View focusedView = mContentParent.findFocus();
        if (focusedView != null && focusedView.getId() != View.NO_ID) {
            outState.putInt(FOCUSED_ID_TAG, focusedView.getId());
        }

        // save the panels
        SparseArray<Parcelable> panelStates = new SparseArray<Parcelable>();
        savePanelState(panelStates);
        if (panelStates.size() > 0) {
            outState.putSparseParcelableArray(PANELS_TAG, panelStates);
        }

        if (mDecorContentParent != null) {
            SparseArray<Parcelable> actionBarStates = new SparseArray<Parcelable>();
            mDecorContentParent.saveToolbarHierarchyState(actionBarStates);
            outState.putSparseParcelableArray(ACTION_BAR_TAG, actionBarStates);
        }

        return outState;
    }

    /** {@inheritDoc} */
    @Override
    public void restoreHierarchyState(Bundle savedInstanceState) {
        if (mContentParent == null) {
            return;
        }

        SparseArray<Parcelable> savedStates
                = savedInstanceState.getSparseParcelableArray(VIEWS_TAG);
        if (savedStates != null) {
            mContentParent.restoreHierarchyState(savedStates);
        }

        // restore the focused view
        int focusedViewId = savedInstanceState.getInt(FOCUSED_ID_TAG, View.NO_ID);
        if (focusedViewId != View.NO_ID) {
            View needsFocus = mContentParent.findViewById(focusedViewId);
            if (needsFocus != null) {
                needsFocus.requestFocus();
            } else {
                Log.w(TAG,
                        "Previously focused view reported id " + focusedViewId
                                + " during save, but can't be found during restore.");
            }
        }

        // Restore the panels.
        SparseArray<Parcelable> panelStates = savedInstanceState.getSparseParcelableArray(PANELS_TAG);
        if (panelStates != null) {
            restorePanelState(panelStates);
        }

        if (mDecorContentParent != null) {
            SparseArray<Parcelable> actionBarStates =
                    savedInstanceState.getSparseParcelableArray(ACTION_BAR_TAG);
            if (actionBarStates != null) {
                doPendingInvalidatePanelMenu();
                mDecorContentParent.restoreToolbarHierarchyState(actionBarStates);
            } else {
                Log.w(TAG, "Missing saved instance states for action bar views! " +
                        "State will not be restored.");
            }
        }
    }

    /**
     * Invoked when the panels should freeze their state.
     *
     * @param icicles Save state into this. This is usually indexed by the
     *            featureId. This will be given to {@link #restorePanelState} in the
     *            future.
     */
    private void savePanelState(SparseArray<Parcelable> icicles) {
        PanelFeatureState[] panels = mPanels;
        if (panels == null) {
            return;
        }

        for (int curFeatureId = panels.length - 1; curFeatureId >= 0; curFeatureId--) {
            if (panels[curFeatureId] != null) {
                icicles.put(curFeatureId, panels[curFeatureId].onSaveInstanceState());
            }
        }
    }

    /**
     * Invoked when the panels should thaw their state from a previously frozen state.
     *
     * @param icicles The state saved by {@link #savePanelState} that needs to be thawed.
     */
    private void restorePanelState(SparseArray<Parcelable> icicles) {
        PanelFeatureState st;
        int curFeatureId;
        for (int i = icicles.size() - 1; i >= 0; i--) {
            curFeatureId = icicles.keyAt(i);
            st = getPanelState(curFeatureId, false /* required */);
            if (st == null) {
                // The panel must not have been required, and is currently not around, skip it
                continue;
            }

            st.onRestoreInstanceState(icicles.get(curFeatureId));
            invalidatePanelMenu(curFeatureId);
        }

        /*
         * Implementation note: call openPanelsAfterRestore later to actually open the
         * restored panels.
         */
    }

    /**
     * Opens the panels that have had their state restored. This should be
     * called sometime after {@link #restorePanelState} when it is safe to add
     * to the window manager.
     */
    void openPanelsAfterRestore() {
        PanelFeatureState[] panels = mPanels;

        if (panels == null) {
            return;
        }

        PanelFeatureState st;
        for (int i = panels.length - 1; i >= 0; i--) {
            st = panels[i];
            // We restore the panel if it was last open; we skip it if it
            // now is open, to avoid a race condition if the user immediately
            // opens it when we are resuming.
            if (st != null) {
                st.applyFrozenState();
                if (!st.isOpen && st.wasLastOpen) {
                    st.isInExpandedMode = st.wasLastExpanded;
                    openPanel(st, null);
                }
            }
        }
    }

    private class PanelMenuPresenterCallback implements MenuPresenter.Callback {
        @Override
        public void onCloseMenu(MenuBuilder menu, boolean allMenusAreClosing) {
            final Menu parentMenu = menu.getRootMenu();
            final boolean isSubMenu = parentMenu != menu;
            final PanelFeatureState panel = findMenuPanel(isSubMenu ? parentMenu : menu);
            if (panel != null) {
                if (isSubMenu) {
                    callOnPanelClosed(panel.featureId, panel, parentMenu);
                    closePanel(panel, true);
                } else {
                    // Close the panel and only do the callback if the menu is being
                    // closed completely, not if opening a sub menu
                    closePanel(panel, allMenusAreClosing);
                }
            }
        }

        @Override
        public boolean onOpenSubMenu(MenuBuilder subMenu) {
            if (subMenu == null && hasFeature(FEATURE_ACTION_BAR)) {
                Callback cb = getCallback();
                if (cb != null && !isDestroyed()) {
                    cb.onMenuOpened(FEATURE_ACTION_BAR, subMenu);
                }
            }

            return true;
        }
    }

    private final class ActionMenuPresenterCallback implements MenuPresenter.Callback {
        @Override
        public boolean onOpenSubMenu(MenuBuilder subMenu) {
            Callback cb = getCallback();
            if (cb != null) {
                cb.onMenuOpened(FEATURE_ACTION_BAR, subMenu);
                return true;
            }
            return false;
        }

        @Override
        public void onCloseMenu(MenuBuilder menu, boolean allMenusAreClosing) {
            checkCloseActionMenu(menu);
        }
    }

    protected DecorView generateDecor(int featureId) {
        // System process doesn't have application context and in that case we need to directly use
        // the context we have. Otherwise we want the application context, so we don't cling to the
        // activity.
        Context context;
        if (mUseDecorContext) {
            Context applicationContext = getContext().getApplicationContext();
            if (applicationContext == null) {
                context = getContext();
            } else {
                context = new DecorContext(applicationContext, this);
                if (mTheme != -1) {
                    context.setTheme(mTheme);
                }
            }
        } else {
            context = getContext();
        }
        return new DecorView(context, featureId, this, getAttributes());
    }

    protected ViewGroup generateLayout(DecorView decor) {
        // Apply data from current theme.

        TypedArray a = getWindowStyle();

        if (false) {
            System.out.println("From style:");
            String s = "Attrs:";
            for (int i = 0; i < R.styleable.Window.length; i++) {
                s = s + " " + Integer.toHexString(R.styleable.Window[i]) + "="
                        + a.getString(i);
            }
            System.out.println(s);
        }

        mIsFloating = a.getBoolean(R.styleable.Window_windowIsFloating, false);
        int flagsToUpdate = (FLAG_LAYOUT_IN_SCREEN|FLAG_LAYOUT_INSET_DECOR)
                & (~getForcedWindowFlags());
        if (mIsFloating) {
            setLayout(WRAP_CONTENT, WRAP_CONTENT);
            setFlags(0, flagsToUpdate);
        } else {
            setFlags(FLAG_LAYOUT_IN_SCREEN|FLAG_LAYOUT_INSET_DECOR, flagsToUpdate);
            getAttributes().setFitInsetsSides(0);
            getAttributes().setFitInsetsTypes(0);
        }

        if (a.getBoolean(R.styleable.Window_windowNoTitle, false)) {
            requestFeature(FEATURE_NO_TITLE);
        } else if (a.getBoolean(R.styleable.Window_windowActionBar, false)) {
            // Don't allow an action bar if there is no title.
            requestFeature(FEATURE_ACTION_BAR);
        }

        if (a.getBoolean(R.styleable.Window_windowActionBarOverlay, false)) {
            requestFeature(FEATURE_ACTION_BAR_OVERLAY);
        }

        if (a.getBoolean(R.styleable.Window_windowActionModeOverlay, false)) {
            requestFeature(FEATURE_ACTION_MODE_OVERLAY);
        }

        if (a.getBoolean(R.styleable.Window_windowFullscreen, false)) {
            setFlags(FLAG_FULLSCREEN, FLAG_FULLSCREEN & (~getForcedWindowFlags()));
        }

        if (a.getBoolean(R.styleable.Window_windowTranslucentStatus,
                false)) {
            setFlags(FLAG_TRANSLUCENT_STATUS, FLAG_TRANSLUCENT_STATUS
                    & (~getForcedWindowFlags()));
        }

        if (a.getBoolean(R.styleable.Window_windowTranslucentNavigation,
                false)) {
            setFlags(FLAG_TRANSLUCENT_NAVIGATION, FLAG_TRANSLUCENT_NAVIGATION
                    & (~getForcedWindowFlags()));
        }

        if (a.getBoolean(R.styleable.Window_windowShowWallpaper, false)) {
            setFlags(FLAG_SHOW_WALLPAPER, FLAG_SHOW_WALLPAPER&(~getForcedWindowFlags()));
        }

        if (a.getBoolean(R.styleable.Window_windowEnableSplitTouch,
                getContext().getApplicationInfo().targetSdkVersion
                        >= android.os.Build.VERSION_CODES.HONEYCOMB)) {
            setFlags(FLAG_SPLIT_TOUCH, FLAG_SPLIT_TOUCH&(~getForcedWindowFlags()));
        }

        a.getValue(R.styleable.Window_windowMinWidthMajor, mMinWidthMajor);
        a.getValue(R.styleable.Window_windowMinWidthMinor, mMinWidthMinor);
        if (DEBUG) Log.d(TAG, "Min width minor: " + mMinWidthMinor.coerceToString()
                + ", major: " + mMinWidthMajor.coerceToString());
        if (a.hasValue(R.styleable.Window_windowFixedWidthMajor)) {
            if (mFixedWidthMajor == null) mFixedWidthMajor = new TypedValue();
            a.getValue(R.styleable.Window_windowFixedWidthMajor,
                    mFixedWidthMajor);
        }
        if (a.hasValue(R.styleable.Window_windowFixedWidthMinor)) {
            if (mFixedWidthMinor == null) mFixedWidthMinor = new TypedValue();
            a.getValue(R.styleable.Window_windowFixedWidthMinor,
                    mFixedWidthMinor);
        }
        if (a.hasValue(R.styleable.Window_windowFixedHeightMajor)) {
            if (mFixedHeightMajor == null) mFixedHeightMajor = new TypedValue();
            a.getValue(R.styleable.Window_windowFixedHeightMajor,
                    mFixedHeightMajor);
        }
        if (a.hasValue(R.styleable.Window_windowFixedHeightMinor)) {
            if (mFixedHeightMinor == null) mFixedHeightMinor = new TypedValue();
            a.getValue(R.styleable.Window_windowFixedHeightMinor,
                    mFixedHeightMinor);
        }
        if (a.getBoolean(R.styleable.Window_windowContentTransitions, false)) {
            requestFeature(FEATURE_CONTENT_TRANSITIONS);
        }
        if (a.getBoolean(R.styleable.Window_windowActivityTransitions, false)) {
            requestFeature(FEATURE_ACTIVITY_TRANSITIONS);
        }

        mIsTranslucent = a.getBoolean(R.styleable.Window_windowIsTranslucent, false);

        final Context context = getContext();
        final int targetSdk = context.getApplicationInfo().targetSdkVersion;
        final boolean targetPreL = targetSdk < android.os.Build.VERSION_CODES.LOLLIPOP;
        final boolean targetPreQ = targetSdk < Build.VERSION_CODES.Q;

        if (!mForcedStatusBarColor) {
            mStatusBarColor = a.getColor(R.styleable.Window_statusBarColor, 0xFF000000);
        }
        if (!mForcedNavigationBarColor) {
            mNavigationBarColor = a.getColor(R.styleable.Window_navigationBarColor, 0xFF000000);
            mNavigationBarDividerColor = a.getColor(R.styleable.Window_navigationBarDividerColor,
                    0x00000000);
        }
        if (!targetPreQ) {
            mEnsureStatusBarContrastWhenTransparent = a.getBoolean(
                    R.styleable.Window_enforceStatusBarContrast, false);
            mEnsureNavigationBarContrastWhenTransparent = a.getBoolean(
                    R.styleable.Window_enforceNavigationBarContrast, true);
        }

        WindowManager.LayoutParams params = getAttributes();

        // Non-floating windows on high end devices must put up decor beneath the system bars and
        // therefore must know about visibility changes of those.
        if (!mIsFloating) {
            if (!targetPreL && a.getBoolean(
                    R.styleable.Window_windowDrawsSystemBarBackgrounds,
                    false)) {
                setFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                        FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS & ~getForcedWindowFlags());
            }
            if (mDecor.mForceWindowDrawsBarBackgrounds) {
                params.privateFlags |= PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS;
            }
        }
        if (a.getBoolean(R.styleable.Window_windowLightStatusBar, false)) {
            decor.setSystemUiVisibility(
                    decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        if (a.getBoolean(R.styleable.Window_windowLightNavigationBar, false)) {
            decor.setSystemUiVisibility(
                    decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        if (a.hasValue(R.styleable.Window_windowLayoutInDisplayCutoutMode)) {
            int mode = a.getInt(R.styleable.Window_windowLayoutInDisplayCutoutMode, -1);
            if (mode < LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    || mode > LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS) {
                throw new UnsupportedOperationException("Unknown windowLayoutInDisplayCutoutMode: "
                        + a.getString(R.styleable.Window_windowLayoutInDisplayCutoutMode));
            }
            params.layoutInDisplayCutoutMode = mode;
        }

        if (mAlwaysReadCloseOnTouchAttr || getContext().getApplicationInfo().targetSdkVersion
                >= android.os.Build.VERSION_CODES.HONEYCOMB) {
            if (a.getBoolean(
                    R.styleable.Window_windowCloseOnTouchOutside,
                    false)) {
                setCloseOnTouchOutsideIfNotSet(true);
            }
        }

        if (!hasSoftInputMode()) {
            params.softInputMode = a.getInt(
                    R.styleable.Window_windowSoftInputMode,
                    params.softInputMode);
        }

        if (a.getBoolean(R.styleable.Window_backgroundDimEnabled,
                mIsFloating)) {
            /* All dialogs should have the window dimmed */
            if ((getForcedWindowFlags()&WindowManager.LayoutParams.FLAG_DIM_BEHIND) == 0) {
                params.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            }
            if (!haveDimAmount()) {
                params.dimAmount = a.getFloat(
                        android.R.styleable.Window_backgroundDimAmount, 0.5f);
            }
        }

        if (a.getBoolean(R.styleable.Window_windowBlurBehindEnabled, false)) {
            if ((getForcedWindowFlags() & WindowManager.LayoutParams.FLAG_BLUR_BEHIND) == 0) {
                params.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            }

            params.setBlurBehindRadius(a.getDimensionPixelSize(
                    android.R.styleable.Window_windowBlurBehindRadius, 0));
        }

        setBackgroundBlurRadius(a.getDimensionPixelSize(
                R.styleable.Window_windowBackgroundBlurRadius, 0));


        if (params.windowAnimations == 0) {
            params.windowAnimations = a.getResourceId(
                    R.styleable.Window_windowAnimationStyle, 0);
        }

        // The rest are only done if this window is not embedded; otherwise,
        // the values are inherited from our container.
        if (getContainer() == null) {
            if (mBackgroundDrawable == null) {

                if (mFrameResource == 0) {
                    mFrameResource = a.getResourceId(R.styleable.Window_windowFrame, 0);
                }

                if (a.hasValue(R.styleable.Window_windowBackground)) {
                    mBackgroundDrawable = a.getDrawable(R.styleable.Window_windowBackground);
                }
            }
            if (a.hasValue(R.styleable.Window_windowBackgroundFallback)) {
                mBackgroundFallbackDrawable =
                        a.getDrawable(R.styleable.Window_windowBackgroundFallback);
            }
            if (mLoadElevation) {
                mElevation = a.getDimension(R.styleable.Window_windowElevation, 0);
            }
            mClipToOutline = a.getBoolean(R.styleable.Window_windowClipToOutline, false);
            mTextColor = a.getColor(R.styleable.Window_textColor, Color.TRANSPARENT);
        }

        // Inflate the window decor.

        int layoutResource;
        int features = getLocalFeatures();
        // System.out.println("Features: 0x" + Integer.toHexString(features));
        if ((features & ((1 << FEATURE_LEFT_ICON) | (1 << FEATURE_RIGHT_ICON))) != 0) {
            if (mIsFloating) {
                TypedValue res = new TypedValue();
                getContext().getTheme().resolveAttribute(
                        R.attr.dialogTitleIconsDecorLayout, res, true);
                layoutResource = res.resourceId;
            } else {
                layoutResource = R.layout.screen_title_icons;
            }
            // XXX Remove this once action bar supports these features.
            removeFeature(FEATURE_ACTION_BAR);
            // System.out.println("Title Icons!");
        } else if ((features & ((1 << FEATURE_PROGRESS) | (1 << FEATURE_INDETERMINATE_PROGRESS))) != 0
                && (features & (1 << FEATURE_ACTION_BAR)) == 0) {
            // Special case for a window with only a progress bar (and title).
            // XXX Need to have a no-title version of embedded windows.
            layoutResource = R.layout.screen_progress;
            // System.out.println("Progress!");
        } else if ((features & (1 << FEATURE_CUSTOM_TITLE)) != 0) {
            // Special case for a window with a custom title.
            // If the window is floating, we need a dialog layout
            if (mIsFloating) {
                TypedValue res = new TypedValue();
                getContext().getTheme().resolveAttribute(
                        R.attr.dialogCustomTitleDecorLayout, res, true);
                layoutResource = res.resourceId;
            } else {
                layoutResource = R.layout.screen_custom_title;
            }
            // XXX Remove this once action bar supports these features.
            removeFeature(FEATURE_ACTION_BAR);
        } else if ((features & (1 << FEATURE_NO_TITLE)) == 0) {
            // If no other features and not embedded, only need a title.
            // If the window is floating, we need a dialog layout
            if (mIsFloating) {
                TypedValue res = new TypedValue();
                getContext().getTheme().resolveAttribute(
                        R.attr.dialogTitleDecorLayout, res, true);
                layoutResource = res.resourceId;
            } else if ((features & (1 << FEATURE_ACTION_BAR)) != 0) {
                layoutResource = a.getResourceId(
                        R.styleable.Window_windowActionBarFullscreenDecorLayout,
                        R.layout.screen_action_bar);
            } else {
                layoutResource = R.layout.screen_title;
            }
            // System.out.println("Title!");
        } else if ((features & (1 << FEATURE_ACTION_MODE_OVERLAY)) != 0) {
            layoutResource = R.layout.screen_simple_overlay_action_mode;
        } else {
            // Embedded, so no decoration is needed.
            layoutResource = R.layout.screen_simple;
            // System.out.println("Simple!");
        }

        mDecor.startChanging();
        mDecor.onResourcesLoaded(mLayoutInflater, layoutResource);

        ViewGroup contentParent = (ViewGroup)findViewById(ID_ANDROID_CONTENT);
        if (contentParent == null) {
            throw new RuntimeException("Window couldn't find content container view");
        }

        if ((features & (1 << FEATURE_INDETERMINATE_PROGRESS)) != 0) {
            ProgressBar progress = getCircularProgressBar(false);
            if (progress != null) {
                progress.setIndeterminate(true);
            }
        }

        // Remaining setup -- of background and title -- that only applies
        // to top-level windows.
        if (getContainer() == null) {
            mDecor.setWindowBackground(mBackgroundDrawable);

            final Drawable frame;
            if (mFrameResource != 0) {
                frame = getContext().getDrawable(mFrameResource);
            } else {
                frame = null;
            }
            mDecor.setWindowFrame(frame);

            mDecor.setElevation(mElevation);
            mDecor.setClipToOutline(mClipToOutline);

            if (mTitle != null) {
                setTitle(mTitle);
            }

            if (mTitleColor == 0) {
                mTitleColor = mTextColor;
            }
            setTitleColor(mTitleColor);
        }

        mDecor.finishChanging();

        return contentParent;
    }

    /** @hide */
    public void alwaysReadCloseOnTouchAttr() {
        mAlwaysReadCloseOnTouchAttr = true;
    }

    private void installDecor() {
        mForceDecorInstall = false;
        if (mDecor == null) {
            mDecor = generateDecor(-1);
            mDecor.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            mDecor.setIsRootNamespace(true);
            if (!mInvalidatePanelMenuPosted && mInvalidatePanelMenuFeatures != 0) {
                mDecor.postOnAnimation(mInvalidatePanelMenuRunnable);
            }
        } else {
            mDecor.setWindow(this);
        }
        if (mContentParent == null) {
            mContentParent = generateLayout(mDecor);

            // Set up decor part of UI to ignore fitsSystemWindows if appropriate.
            mDecor.makeFrameworkOptionalFitsSystemWindows();

            final DecorContentParent decorContentParent = (DecorContentParent) mDecor.findViewById(
                    R.id.decor_content_parent);

            if (decorContentParent != null) {
                mDecorContentParent = decorContentParent;
                mDecorContentParent.setWindowCallback(getCallback());
                if (mDecorContentParent.getTitle() == null) {
                    mDecorContentParent.setWindowTitle(mTitle);
                }

                final int localFeatures = getLocalFeatures();
                for (int i = 0; i < FEATURE_MAX; i++) {
                    if ((localFeatures & (1 << i)) != 0) {
                        mDecorContentParent.initFeature(i);
                    }
                }

                mDecorContentParent.setUiOptions(mUiOptions);

                if ((mResourcesSetFlags & FLAG_RESOURCE_SET_ICON) != 0 ||
                        (mIconRes != 0 && !mDecorContentParent.hasIcon())) {
                    mDecorContentParent.setIcon(mIconRes);
                } else if ((mResourcesSetFlags & FLAG_RESOURCE_SET_ICON) == 0 &&
                        mIconRes == 0 && !mDecorContentParent.hasIcon()) {
                    mDecorContentParent.setIcon(
                            getContext().getPackageManager().getDefaultActivityIcon());
                    mResourcesSetFlags |= FLAG_RESOURCE_SET_ICON_FALLBACK;
                }
                if ((mResourcesSetFlags & FLAG_RESOURCE_SET_LOGO) != 0 ||
                        (mLogoRes != 0 && !mDecorContentParent.hasLogo())) {
                    mDecorContentParent.setLogo(mLogoRes);
                }

                // Invalidate if the panel menu hasn't been created before this.
                // Panel menu invalidation is deferred avoiding application onCreateOptionsMenu
                // being called in the middle of onCreate or similar.
                // A pending invalidation will typically be resolved before the posted message
                // would run normally in order to satisfy instance state restoration.
                PanelFeatureState st = getPanelState(FEATURE_OPTIONS_PANEL, false);
                if (!isDestroyed() && (st == null || st.menu == null) && !mIsStartingWindow) {
                    invalidatePanelMenu(FEATURE_ACTION_BAR);
                }
            } else {
                mTitleView = findViewById(R.id.title);
                if (mTitleView != null) {
                    if ((getLocalFeatures() & (1 << FEATURE_NO_TITLE)) != 0) {
                        final View titleContainer = findViewById(R.id.title_container);
                        if (titleContainer != null) {
                            titleContainer.setVisibility(View.GONE);
                        } else {
                            mTitleView.setVisibility(View.GONE);
                        }
                        mContentParent.setForeground(null);
                    } else {
                        mTitleView.setText(mTitle);
                    }
                }
            }

            if (mDecor.getBackground() == null && mBackgroundFallbackDrawable != null) {
                mDecor.setBackgroundFallback(mBackgroundFallbackDrawable);
            }

            // Only inflate or create a new TransitionManager if the caller hasn't
            // already set a custom one.
            if (hasFeature(FEATURE_ACTIVITY_TRANSITIONS)) {
                if (mTransitionManager == null) {
                    final int transitionRes = getWindowStyle().getResourceId(
                            R.styleable.Window_windowContentTransitionManager,
                            0);
                    if (transitionRes != 0) {
                        final TransitionInflater inflater = TransitionInflater.from(getContext());
                        mTransitionManager = inflater.inflateTransitionManager(transitionRes,
                                mContentParent);
                    } else {
                        mTransitionManager = new TransitionManager();
                    }
                }

                mEnterTransition = getTransition(mEnterTransition, null,
                        R.styleable.Window_windowEnterTransition);
                mReturnTransition = getTransition(mReturnTransition, USE_DEFAULT_TRANSITION,
                        R.styleable.Window_windowReturnTransition);
                mExitTransition = getTransition(mExitTransition, null,
                        R.styleable.Window_windowExitTransition);
                mReenterTransition = getTransition(mReenterTransition, USE_DEFAULT_TRANSITION,
                        R.styleable.Window_windowReenterTransition);
                mSharedElementEnterTransition = getTransition(mSharedElementEnterTransition, null,
                        R.styleable.Window_windowSharedElementEnterTransition);
                mSharedElementReturnTransition = getTransition(mSharedElementReturnTransition,
                        USE_DEFAULT_TRANSITION,
                        R.styleable.Window_windowSharedElementReturnTransition);
                mSharedElementExitTransition = getTransition(mSharedElementExitTransition, null,
                        R.styleable.Window_windowSharedElementExitTransition);
                mSharedElementReenterTransition = getTransition(mSharedElementReenterTransition,
                        USE_DEFAULT_TRANSITION,
                        R.styleable.Window_windowSharedElementReenterTransition);
                if (mAllowEnterTransitionOverlap == null) {
                    mAllowEnterTransitionOverlap = getWindowStyle().getBoolean(
                            R.styleable.Window_windowAllowEnterTransitionOverlap, true);
                }
                if (mAllowReturnTransitionOverlap == null) {
                    mAllowReturnTransitionOverlap = getWindowStyle().getBoolean(
                            R.styleable.Window_windowAllowReturnTransitionOverlap, true);
                }
                if (mBackgroundFadeDurationMillis < 0) {
                    mBackgroundFadeDurationMillis = getWindowStyle().getInteger(
                            R.styleable.Window_windowTransitionBackgroundFadeDuration,
                            DEFAULT_BACKGROUND_FADE_DURATION_MS);
                }
                if (mSharedElementsUseOverlay == null) {
                    mSharedElementsUseOverlay = getWindowStyle().getBoolean(
                            R.styleable.Window_windowSharedElementsUseOverlay, true);
                }
            }
        }
    }

    private Transition getTransition(Transition currentValue, Transition defaultValue, int id) {
        if (currentValue != defaultValue) {
            return currentValue;
        }
        int transitionId = getWindowStyle().getResourceId(id, -1);
        Transition transition = defaultValue;
        if (transitionId != -1 && transitionId != R.transition.no_transition) {
            TransitionInflater inflater = TransitionInflater.from(getContext());
            transition = inflater.inflateTransition(transitionId);
            if (transition instanceof TransitionSet &&
                    ((TransitionSet)transition).getTransitionCount() == 0) {
                transition = null;
            }
        }
        return transition;
    }

    private Drawable loadImageURI(Uri uri) {
        try {
            return Drawable.createFromStream(
                    getContext().getContentResolver().openInputStream(uri), null);
        } catch (Exception e) {
            Log.w(TAG, "Unable to open content: " + uri);
        }
        return null;
    }

    private DrawableFeatureState getDrawableState(int featureId, boolean required) {
        if ((getFeatures() & (1 << featureId)) == 0) {
            if (!required) {
                return null;
            }
            throw new RuntimeException("The feature has not been requested");
        }

        DrawableFeatureState[] ar;
        if ((ar = mDrawables) == null || ar.length <= featureId) {
            DrawableFeatureState[] nar = new DrawableFeatureState[featureId + 1];
            if (ar != null) {
                System.arraycopy(ar, 0, nar, 0, ar.length);
            }
            mDrawables = ar = nar;
        }

        DrawableFeatureState st = ar[featureId];
        if (st == null) {
            ar[featureId] = st = new DrawableFeatureState(featureId);
        }
        return st;
    }

    /**
     * Gets a panel's state based on its feature ID.
     *
     * @param featureId The feature ID of the panel.
     * @param required Whether the panel is required (if it is required and it
     *            isn't in our features, this throws an exception).
     * @return The panel state.
     */
    PanelFeatureState getPanelState(int featureId, boolean required) {
        return getPanelState(featureId, required, null);
    }

    /**
     * Gets a panel's state based on its feature ID.
     *
     * @param featureId The feature ID of the panel.
     * @param required Whether the panel is required (if it is required and it
     *            isn't in our features, this throws an exception).
     * @param convertPanelState Optional: If the panel state does not exist, use
     *            this as the panel state.
     * @return The panel state.
     */
    private PanelFeatureState getPanelState(int featureId, boolean required,
            PanelFeatureState convertPanelState) {
        if ((getFeatures() & (1 << featureId)) == 0) {
            if (!required) {
                return null;
            }
            throw new RuntimeException("The feature has not been requested");
        }

        PanelFeatureState[] ar;
        if ((ar = mPanels) == null || ar.length <= featureId) {
            PanelFeatureState[] nar = new PanelFeatureState[featureId + 1];
            if (ar != null) {
                System.arraycopy(ar, 0, nar, 0, ar.length);
            }
            mPanels = ar = nar;
        }

        PanelFeatureState st = ar[featureId];
        if (st == null) {
            ar[featureId] = st = (convertPanelState != null)
                    ? convertPanelState
                    : new PanelFeatureState(featureId);
        }
        return st;
    }

    @Override
    public final void setChildDrawable(int featureId, Drawable drawable) {
        DrawableFeatureState st = getDrawableState(featureId, true);
        st.child = drawable;
        updateDrawable(featureId, st, false);
    }

    @Override
    public final void setChildInt(int featureId, int value) {
        updateInt(featureId, value, false);
    }

    @Override
    public boolean isShortcutKey(int keyCode, KeyEvent event) {
        PanelFeatureState st = getPanelState(FEATURE_OPTIONS_PANEL, false);
        return st != null && st.menu != null && st.menu.isShortcutKey(keyCode, event);
    }

    private void updateDrawable(int featureId, DrawableFeatureState st, boolean fromResume) {
        // Do nothing if the decor is not yet installed... an update will
        // need to be forced when we eventually become active.
        if (mContentParent == null) {
            return;
        }

        final int featureMask = 1 << featureId;

        if ((getFeatures() & featureMask) == 0 && !fromResume) {
            return;
        }

        Drawable drawable = null;
        if (st != null) {
            drawable = st.child;
            if (drawable == null)
                drawable = st.local;
            if (drawable == null)
                drawable = st.def;
        }
        if ((getLocalFeatures() & featureMask) == 0) {
            if (getContainer() != null) {
                if (isActive() || fromResume) {
                    getContainer().setChildDrawable(featureId, drawable);
                }
            }
        } else if (st != null && (st.cur != drawable || st.curAlpha != st.alpha)) {
            // System.out.println("Drawable changed: old=" + st.cur
            // + ", new=" + drawable);
            st.cur = drawable;
            st.curAlpha = st.alpha;
            onDrawableChanged(featureId, drawable, st.alpha);
        }
    }

    private void updateInt(int featureId, int value, boolean fromResume) {

        // Do nothing if the decor is not yet installed... an update will
        // need to be forced when we eventually become active.
        if (mContentParent == null) {
            return;
        }

        final int featureMask = 1 << featureId;

        if ((getFeatures() & featureMask) == 0 && !fromResume) {
            return;
        }

        if ((getLocalFeatures() & featureMask) == 0) {
            if (getContainer() != null) {
                getContainer().setChildInt(featureId, value);
            }
        } else {
            onIntChanged(featureId, value);
        }
    }

    private ImageView getLeftIconView() {
        if (mLeftIconView != null) {
            return mLeftIconView;
        }
        if (mContentParent == null) {
            installDecor();
        }
        return (mLeftIconView = (ImageView)findViewById(R.id.left_icon));
    }

    @Override
    protected void dispatchWindowAttributesChanged(WindowManager.LayoutParams attrs) {
        super.dispatchWindowAttributesChanged(attrs);
        if (mDecor != null) {
            mDecor.updateColorViews(null /* insets */, true /* animate */);
        }
    }

    private ProgressBar getCircularProgressBar(boolean shouldInstallDecor) {
        if (mCircularProgressBar != null) {
            return mCircularProgressBar;
        }
        if (mContentParent == null && shouldInstallDecor) {
            installDecor();
        }
        mCircularProgressBar = findViewById(R.id.progress_circular);
        if (mCircularProgressBar != null) {
            mCircularProgressBar.setVisibility(View.INVISIBLE);
        }
        return mCircularProgressBar;
    }

    private ProgressBar getHorizontalProgressBar(boolean shouldInstallDecor) {
        if (mHorizontalProgressBar != null) {
            return mHorizontalProgressBar;
        }
        if (mContentParent == null && shouldInstallDecor) {
            installDecor();
        }
        mHorizontalProgressBar = findViewById(R.id.progress_horizontal);
        if (mHorizontalProgressBar != null) {
            mHorizontalProgressBar.setVisibility(View.INVISIBLE);
        }
        return mHorizontalProgressBar;
    }

    private ImageView getRightIconView() {
        if (mRightIconView != null) {
            return mRightIconView;
        }
        if (mContentParent == null) {
            installDecor();
        }
        return (mRightIconView = (ImageView)findViewById(R.id.right_icon));
    }

    /**
     * Helper method for calling the {@link Callback#onPanelClosed(int, Menu)}
     * callback. This method will grab whatever extra state is needed for the
     * callback that isn't given in the parameters. If the panel is not open,
     * this will not perform the callback.
     *
     * @param featureId Feature ID of the panel that was closed. Must be given.
     * @param panel Panel that was closed. Optional but useful if there is no
     *            menu given.
     * @param menu The menu that was closed. Optional, but give if you have.
     */
    private void callOnPanelClosed(int featureId, PanelFeatureState panel, Menu menu) {
        final Callback cb = getCallback();
        if (cb == null)
            return;

        // Try to get a menu
        if (menu == null) {
            // Need a panel to grab the menu, so try to get that
            if (panel == null) {
                if ((featureId >= 0) && (featureId < mPanels.length)) {
                    panel = mPanels[featureId];
                }
            }

            if (panel != null) {
                // menu still may be null, which is okay--we tried our best
                menu = panel.menu;
            }
        }

        // If the panel is not open, do not callback
        if ((panel != null) && (!panel.isOpen))
            return;

        if (!isDestroyed()) {
            cb.onPanelClosed(featureId, menu);
        }
    }

    /**
     * Check if Setup or Post-Setup update is completed on TV
     * @return true if completed
     */
    private boolean isTvUserSetupComplete() {
        boolean isTvSetupComplete = Settings.Secure.getInt(getContext().getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0) != 0;
        isTvSetupComplete &= Settings.Secure.getInt(getContext().getContentResolver(),
                Settings.Secure.TV_USER_SETUP_COMPLETE, 0) != 0;
        return isTvSetupComplete;
    }

    /**
     * Helper method for adding launch-search to most applications. Opens the
     * search window using default settings.
     *
     * @return true if search window opened
     */
    private boolean launchDefaultSearch(KeyEvent event) {
        if (getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                && !isTvUserSetupComplete()) {
            // If we are in Setup or Post-Setup update mode on TV, consume the search key
            return false;
        }
        boolean result;
        final Callback cb = getCallback();
        if (cb == null || isDestroyed()) {
            result = false;
        } else {
            sendCloseSystemWindows("search");
            int deviceId = event.getDeviceId();
            SearchEvent searchEvent = null;
            if (deviceId != 0) {
                searchEvent = new SearchEvent(InputDevice.getDevice(deviceId));
            }
            try {
                result = cb.onSearchRequested(searchEvent);
            } catch (AbstractMethodError e) {
                Log.e(TAG, "WindowCallback " + cb.getClass().getName() + " does not implement"
                        + " method onSearchRequested(SearchEvent); fa", e);
                result = cb.onSearchRequested();
            }
        }
        if (!result && (getContext().getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION) {
            // On TVs, if the app doesn't implement search, we want to launch assist.
            Bundle args = new Bundle();
            args.putInt(Intent.EXTRA_ASSIST_INPUT_DEVICE_ID, event.getDeviceId());
            args.putLong(Intent.EXTRA_TIME, event.getEventTime());
            ((SearchManager) getContext().getSystemService(Context.SEARCH_SERVICE))
                    .launchAssist(args);
            return true;
        }
        return result;
    }

    @Override
    public void setVolumeControlStream(int streamType) {
        mVolumeControlStreamType = streamType;
    }

    @Override
    public int getVolumeControlStream() {
        return mVolumeControlStreamType;
    }

    @Override
    public void setMediaController(MediaController controller) {
        mMediaController = controller;
    }

    @Override
    public MediaController getMediaController() {
        return mMediaController;
    }

    @Override
    public void setEnterTransition(Transition enterTransition) {
        mEnterTransition = enterTransition;
    }

    @Override
    public void setReturnTransition(Transition transition) {
        mReturnTransition = transition;
    }

    @Override
    public void setExitTransition(Transition exitTransition) {
        mExitTransition = exitTransition;
    }

    @Override
    public void setReenterTransition(Transition transition) {
        mReenterTransition = transition;
    }

    @Override
    public void setSharedElementEnterTransition(Transition sharedElementEnterTransition) {
        mSharedElementEnterTransition = sharedElementEnterTransition;
    }

    @Override
    public void setSharedElementReturnTransition(Transition transition) {
        mSharedElementReturnTransition = transition;
    }

    @Override
    public void setSharedElementExitTransition(Transition sharedElementExitTransition) {
        mSharedElementExitTransition = sharedElementExitTransition;
    }

    @Override
    public void setSharedElementReenterTransition(Transition transition) {
        mSharedElementReenterTransition = transition;
    }

    @Override
    public Transition getEnterTransition() {
        return mEnterTransition;
    }

    @Override
    public Transition getReturnTransition() {
        return mReturnTransition == USE_DEFAULT_TRANSITION ? getEnterTransition()
                : mReturnTransition;
    }

    @Override
    public Transition getExitTransition() {
        return mExitTransition;
    }

    @Override
    public Transition getReenterTransition() {
        return mReenterTransition == USE_DEFAULT_TRANSITION ? getExitTransition()
                : mReenterTransition;
    }

    @Override
    public Transition getSharedElementEnterTransition() {
        return mSharedElementEnterTransition;
    }

    @Override
    public Transition getSharedElementReturnTransition() {
        return mSharedElementReturnTransition == USE_DEFAULT_TRANSITION
                ? getSharedElementEnterTransition() : mSharedElementReturnTransition;
    }

    @Override
    public Transition getSharedElementExitTransition() {
        return mSharedElementExitTransition;
    }

    @Override
    public Transition getSharedElementReenterTransition() {
        return mSharedElementReenterTransition == USE_DEFAULT_TRANSITION
                ? getSharedElementExitTransition() : mSharedElementReenterTransition;
    }

    @Override
    public void setAllowEnterTransitionOverlap(boolean allow) {
        mAllowEnterTransitionOverlap = allow;
    }

    @Override
    public boolean getAllowEnterTransitionOverlap() {
        return (mAllowEnterTransitionOverlap == null) ? true : mAllowEnterTransitionOverlap;
    }

    @Override
    public void setAllowReturnTransitionOverlap(boolean allowExitTransitionOverlap) {
        mAllowReturnTransitionOverlap = allowExitTransitionOverlap;
    }

    @Override
    public boolean getAllowReturnTransitionOverlap() {
        return (mAllowReturnTransitionOverlap == null) ? true : mAllowReturnTransitionOverlap;
    }

    @Override
    public long getTransitionBackgroundFadeDuration() {
        return (mBackgroundFadeDurationMillis < 0) ? DEFAULT_BACKGROUND_FADE_DURATION_MS
                : mBackgroundFadeDurationMillis;
    }

    @Override
    public void setTransitionBackgroundFadeDuration(long fadeDurationMillis) {
        if (fadeDurationMillis < 0) {
            throw new IllegalArgumentException("negative durations are not allowed");
        }
        mBackgroundFadeDurationMillis = fadeDurationMillis;
    }

    @Override
    public void setSharedElementsUseOverlay(boolean sharedElementsUseOverlay) {
        mSharedElementsUseOverlay = sharedElementsUseOverlay;
    }

    @Override
    public boolean getSharedElementsUseOverlay() {
        return (mSharedElementsUseOverlay == null) ? true : mSharedElementsUseOverlay;
    }

    private static final class DrawableFeatureState {
        DrawableFeatureState(int _featureId) {
            featureId = _featureId;
        }

        final int featureId;

        int resid;

        Uri uri;

        Drawable local;

        Drawable child;

        Drawable def;

        Drawable cur;

        int alpha = 255;

        int curAlpha = 255;
    }

    static final class PanelFeatureState {

        /** Feature ID for this panel. */
        int featureId;

        // Information pulled from the style for this panel.

        int background;

        /** The background when the panel spans the entire available width. */
        int fullBackground;

        int gravity;

        int x;

        int y;

        int windowAnimations;

        /** Dynamic state of the panel. */
        DecorView decorView;

        /** The panel that was returned by onCreatePanelView(). */
        View createdPanelView;

        /** The panel that we are actually showing. */
        View shownPanelView;

        /** Use {@link #setMenu} to set this. */
        MenuBuilder menu;

        IconMenuPresenter iconMenuPresenter;
        ListMenuPresenter listMenuPresenter;

        /** true if this menu will show in single-list compact mode */
        boolean isCompact;

        /** Theme resource ID for list elements of the panel menu */
        int listPresenterTheme;

        /**
         * Whether the panel has been prepared (see
         * {@link PhoneWindow#preparePanel}).
         */
        boolean isPrepared;

        /**
         * Whether an item's action has been performed. This happens in obvious
         * scenarios (user clicks on menu item), but can also happen with
         * chording menu+(shortcut key).
         */
        boolean isHandled;

        boolean isOpen;

        /**
         * True if the menu is in expanded mode, false if the menu is in icon
         * mode
         */
        boolean isInExpandedMode;

        public boolean qwertyMode;

        boolean refreshDecorView;

        boolean refreshMenuContent;

        boolean wasLastOpen;

        boolean wasLastExpanded;

        /**
         * Contains the state of the menu when told to freeze.
         */
        Bundle frozenMenuState;

        /**
         * Contains the state of associated action views when told to freeze.
         * These are saved across invalidations.
         */
        Bundle frozenActionViewState;

        PanelFeatureState(int featureId) {
            this.featureId = featureId;

            refreshDecorView = false;
        }

        public boolean isInListMode() {
            return isInExpandedMode || isCompact;
        }

        public boolean hasPanelItems() {
            if (shownPanelView == null) return false;
            if (createdPanelView != null) return true;

            if (isCompact || isInExpandedMode) {
                return listMenuPresenter.getAdapter().getCount() > 0;
            } else {
                return ((ViewGroup) shownPanelView).getChildCount() > 0;
            }
        }

        /**
         * Unregister and free attached MenuPresenters. They will be recreated as needed.
         */
        public void clearMenuPresenters() {
            if (menu != null) {
                menu.removeMenuPresenter(iconMenuPresenter);
                menu.removeMenuPresenter(listMenuPresenter);
            }
            iconMenuPresenter = null;
            listMenuPresenter = null;
        }

        void setStyle(Context context) {
            TypedArray a = context.obtainStyledAttributes(R.styleable.Theme);
            background = a.getResourceId(
                    R.styleable.Theme_panelBackground, 0);
            fullBackground = a.getResourceId(
                    R.styleable.Theme_panelFullBackground, 0);
            windowAnimations = a.getResourceId(
                    R.styleable.Theme_windowAnimationStyle, 0);
            isCompact = a.getBoolean(
                    R.styleable.Theme_panelMenuIsCompact, false);
            listPresenterTheme = a.getResourceId(
                    R.styleable.Theme_panelMenuListTheme,
                    R.style.Theme_ExpandedMenu);
            a.recycle();
        }

        void setMenu(MenuBuilder menu) {
            if (menu == this.menu) return;

            if (this.menu != null) {
                this.menu.removeMenuPresenter(iconMenuPresenter);
                this.menu.removeMenuPresenter(listMenuPresenter);
            }
            this.menu = menu;
            if (menu != null) {
                if (iconMenuPresenter != null) menu.addMenuPresenter(iconMenuPresenter);
                if (listMenuPresenter != null) menu.addMenuPresenter(listMenuPresenter);
            }
        }

        MenuView getListMenuView(Context context, MenuPresenter.Callback cb) {
            if (menu == null) return null;

            if (!isCompact) {
                getIconMenuView(context, cb); // Need this initialized to know where our offset goes
            }

            if (listMenuPresenter == null) {
                listMenuPresenter = new ListMenuPresenter(
                        R.layout.list_menu_item_layout, listPresenterTheme);
                listMenuPresenter.setCallback(cb);
                listMenuPresenter.setId(R.id.list_menu_presenter);
                menu.addMenuPresenter(listMenuPresenter);
            }

            if (iconMenuPresenter != null) {
                listMenuPresenter.setItemIndexOffset(
                        iconMenuPresenter.getNumActualItemsShown());
            }
            MenuView result = listMenuPresenter.getMenuView(decorView);

            return result;
        }

        MenuView getIconMenuView(Context context, MenuPresenter.Callback cb) {
            if (menu == null) return null;

            if (iconMenuPresenter == null) {
                iconMenuPresenter = new IconMenuPresenter(context);
                iconMenuPresenter.setCallback(cb);
                iconMenuPresenter.setId(R.id.icon_menu_presenter);
                menu.addMenuPresenter(iconMenuPresenter);
            }

            MenuView result = iconMenuPresenter.getMenuView(decorView);

            return result;
        }

        Parcelable onSaveInstanceState() {
            SavedState savedState = new SavedState();
            savedState.featureId = featureId;
            savedState.isOpen = isOpen;
            savedState.isInExpandedMode = isInExpandedMode;

            if (menu != null) {
                savedState.menuState = new Bundle();
                menu.savePresenterStates(savedState.menuState);
            }

            return savedState;
        }

        void onRestoreInstanceState(Parcelable state) {
            SavedState savedState = (SavedState) state;
            featureId = savedState.featureId;
            wasLastOpen = savedState.isOpen;
            wasLastExpanded = savedState.isInExpandedMode;
            frozenMenuState = savedState.menuState;

            /*
             * A LocalActivityManager keeps the same instance of this class around.
             * The first time the menu is being shown after restoring, the
             * Activity.onCreateOptionsMenu should be called. But, if it is the
             * same instance then menu != null and we won't call that method.
             * We clear any cached views here. The caller should invalidatePanelMenu.
             */
            createdPanelView = null;
            shownPanelView = null;
            decorView = null;
        }

        void applyFrozenState() {
            if (menu != null && frozenMenuState != null) {
                menu.restorePresenterStates(frozenMenuState);
                frozenMenuState = null;
            }
        }

        private static class SavedState implements Parcelable {
            int featureId;
            boolean isOpen;
            boolean isInExpandedMode;
            Bundle menuState;

            public int describeContents() {
                return 0;
            }

            public void writeToParcel(Parcel dest, int flags) {
                dest.writeInt(featureId);
                dest.writeInt(isOpen ? 1 : 0);
                dest.writeInt(isInExpandedMode ? 1 : 0);

                if (isOpen) {
                    dest.writeBundle(menuState);
                }
            }

            private static SavedState readFromParcel(Parcel source) {
                SavedState savedState = new SavedState();
                savedState.featureId = source.readInt();
                savedState.isOpen = source.readInt() == 1;
                savedState.isInExpandedMode = source.readInt() == 1;

                if (savedState.isOpen) {
                    savedState.menuState = source.readBundle();
                }

                return savedState;
            }

            public static final Parcelable.Creator<SavedState> CREATOR
                    = new Parcelable.Creator<SavedState>() {
                public SavedState createFromParcel(Parcel in) {
                    return readFromParcel(in);
                }

                public SavedState[] newArray(int size) {
                    return new SavedState[size];
                }
            };
        }

    }

    static class RotationWatcher extends Stub {
        private Handler mHandler;
        private final Runnable mRotationChanged = new Runnable() {
            public void run() {
                dispatchRotationChanged();
            }
        };
        private final ArrayList<WeakReference<PhoneWindow>> mWindows =
                new ArrayList<WeakReference<PhoneWindow>>();
        private boolean mIsWatching;

        @Override
        public void onRotationChanged(int rotation) throws RemoteException {
            mHandler.post(mRotationChanged);
        }

        public void addWindow(PhoneWindow phoneWindow) {
            synchronized (mWindows) {
                if (!mIsWatching) {
                    try {
                        WindowManagerHolder.sWindowManager.watchRotation(this,
                                phoneWindow.getContext().getDisplayId());
                        mHandler = new Handler();
                        mIsWatching = true;
                    } catch (RemoteException ex) {
                        Log.e(TAG, "Couldn't start watching for device rotation", ex);
                    }
                }
                mWindows.add(new WeakReference<PhoneWindow>(phoneWindow));
            }
        }

        public void removeWindow(PhoneWindow phoneWindow) {
            synchronized (mWindows) {
                int i = 0;
                while (i < mWindows.size()) {
                    final WeakReference<PhoneWindow> ref = mWindows.get(i);
                    final PhoneWindow win = ref.get();
                    if (win == null || win == phoneWindow) {
                        mWindows.remove(i);
                    } else {
                        i++;
                    }
                }
            }
        }

        void dispatchRotationChanged() {
            synchronized (mWindows) {
                int i = 0;
                while (i < mWindows.size()) {
                    final WeakReference<PhoneWindow> ref = mWindows.get(i);
                    final PhoneWindow win = ref.get();
                    if (win != null) {
                        win.onOptionsPanelRotationChanged();
                        i++;
                    } else {
                        mWindows.remove(i);
                    }
                }
            }
        }
    }

    /**
     * Simple implementation of MenuBuilder.Callback that:
     * <li> Opens a submenu when selected.
     * <li> Calls back to the callback's onMenuItemSelected when an item is
     * selected.
     */
    public static final class PhoneWindowMenuCallback
            implements MenuBuilder.Callback, MenuPresenter.Callback {
        private static final int FEATURE_ID = FEATURE_CONTEXT_MENU;

        private final PhoneWindow mWindow;

        private MenuDialogHelper mSubMenuHelper;

        private boolean mShowDialogForSubmenu;

        public PhoneWindowMenuCallback(PhoneWindow window) {
            mWindow = window;
        }

        @Override
        public void onCloseMenu(MenuBuilder menu, boolean allMenusAreClosing) {
            if (menu.getRootMenu() != menu) {
                onCloseSubMenu(menu);
            }

            if (allMenusAreClosing) {
                final Callback callback = mWindow.getCallback();
                if (callback != null && !mWindow.isDestroyed()) {
                    callback.onPanelClosed(FEATURE_ID, menu);
                }

                if (menu == mWindow.mContextMenu) {
                    mWindow.dismissContextMenu();
                }

                // Dismiss the submenu, if it is showing
                if (mSubMenuHelper != null) {
                    mSubMenuHelper.dismiss();
                    mSubMenuHelper = null;
                }
            }
        }

        private void onCloseSubMenu(MenuBuilder menu) {
            final Callback callback = mWindow.getCallback();
            if (callback != null && !mWindow.isDestroyed()) {
                callback.onPanelClosed(FEATURE_ID, menu.getRootMenu());
            }
        }

        @Override
        public boolean onMenuItemSelected(MenuBuilder menu, MenuItem item) {
            final Callback callback = mWindow.getCallback();
            return callback != null && !mWindow.isDestroyed()
                    && callback.onMenuItemSelected(FEATURE_ID, item);
        }

        @Override
        public void onMenuModeChange(MenuBuilder menu) {
        }

        @Override
        public boolean onOpenSubMenu(MenuBuilder subMenu) {
            if (subMenu == null) {
                return false;
            }

            // Set a simple callback for the submenu
            subMenu.setCallback(this);

            if (mShowDialogForSubmenu) {
                // The window manager will give us a valid window token
                mSubMenuHelper = new MenuDialogHelper(subMenu);
                mSubMenuHelper.show(null);
                return true;
            }

            return false;
        }

        public void setShowDialogForSubmenu(boolean enabled) {
            mShowDialogForSubmenu = enabled;
        }
    }

    int getLocalFeaturesPrivate() {
        return super.getLocalFeatures();
    }

    protected void setDefaultWindowFormat(int format) {
        super.setDefaultWindowFormat(format);
    }

    void sendCloseSystemWindows() {
        sendCloseSystemWindows(getContext(), null);
    }

    void sendCloseSystemWindows(String reason) {
        sendCloseSystemWindows(getContext(), reason);
    }

    public static void sendCloseSystemWindows(Context context, String reason) {
        if (ActivityManager.isSystemReady()) {
            try {
                ActivityManager.getService().closeSystemDialogs(reason);
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public int getStatusBarColor() {
        return mStatusBarColor;
    }

    @Override
    public void setStatusBarColor(int color) {
        mStatusBarColor = color;
        mForcedStatusBarColor = true;
        if (mDecor != null) {
            mDecor.updateColorViews(null, false /* animate */);
        }
        final WindowControllerCallback callback = getWindowControllerCallback();
        if (callback != null) {
            getWindowControllerCallback().updateStatusBarColor(color);
        }
    }

    @Override
    public int getNavigationBarColor() {
        return mNavigationBarColor;
    }

    @Override
    public void setNavigationBarColor(int color) {
        mNavigationBarColor = color;
        mForcedNavigationBarColor = true;
        if (mDecor != null) {
            mDecor.updateColorViews(null, false /* animate */);
        }
        final WindowControllerCallback callback = getWindowControllerCallback();
        if (callback != null) {
            getWindowControllerCallback().updateNavigationBarColor(color);
        }
    }

    @Override
    public void setNavigationBarDividerColor(int navigationBarDividerColor) {
        mNavigationBarDividerColor = navigationBarDividerColor;
        if (mDecor != null) {
            mDecor.updateColorViews(null, false /* animate */);
        }
    }

    @Override
    public int getNavigationBarDividerColor() {
        return mNavigationBarDividerColor;
    }

    @Override
    public void setStatusBarContrastEnforced(boolean ensureContrast) {
        mEnsureStatusBarContrastWhenTransparent = ensureContrast;
        if (mDecor != null) {
            mDecor.updateColorViews(null, false /* animate */);
        }
    }

    @Override
    public boolean isStatusBarContrastEnforced() {
        return mEnsureStatusBarContrastWhenTransparent;
    }

    @Override
    public void setNavigationBarContrastEnforced(boolean enforceContrast) {
        mEnsureNavigationBarContrastWhenTransparent = enforceContrast;
        if (mDecor != null) {
            mDecor.updateColorViews(null, false /* animate */);
        }
    }

    @Override
    public boolean isNavigationBarContrastEnforced() {
        return mEnsureNavigationBarContrastWhenTransparent;
    }

    public void setIsStartingWindow(boolean isStartingWindow) {
        mIsStartingWindow = isStartingWindow;
    }

    @Override
    public void setTheme(int resid) {
        mTheme = resid;
        if (mDecor != null) {
            Context context = mDecor.getContext();
            if (context instanceof DecorContext) {
                context.setTheme(resid);
            }
        }
    }

    @Override
    public void setResizingCaptionDrawable(Drawable drawable) {
        mDecor.setUserCaptionBackgroundDrawable(drawable);
    }

    @Override
    public void setDecorCaptionShade(int decorCaptionShade) {
        mDecorCaptionShade = decorCaptionShade;
        if (mDecor != null) {
            mDecor.updateDecorCaptionShade();
        }
    }

    int getDecorCaptionShade() {
        return mDecorCaptionShade;
    }

    @Override
    public void setAttributes(WindowManager.LayoutParams params) {
        super.setAttributes(params);
        if (mDecor != null) {
            mDecor.updateLogTag(params);
        }
    }

    @Override
    public WindowInsetsController getInsetsController() {
        return mDecor.getWindowInsetsController();
    }

    @Override
    public void setSystemGestureExclusionRects(@NonNull List<Rect> rects) {
        getViewRootImpl().setRootSystemGestureExclusionRects(rects);
    }

    @Override
    @NonNull
    public List<Rect> getSystemGestureExclusionRects() {
        return getViewRootImpl().getRootSystemGestureExclusionRects();
    }

    @Override
    public void setDecorFitsSystemWindows(boolean decorFitsSystemWindows) {
        mDecorFitsSystemWindows = decorFitsSystemWindows;
        applyDecorFitsSystemWindows();
    }

    private void applyDecorFitsSystemWindows() {
        ViewRootImpl impl = getViewRootImplOrNull();
        if (impl != null) {
            impl.setOnContentApplyWindowInsetsListener(mDecorFitsSystemWindows
                    ? sDefaultContentInsetsApplier
                    : null);
        }
    }

    /**
     * System request to begin scroll capture.
     *
     * @param callbacks to receive responses
     * @hide
     */
    @Override
    public void requestScrollCapture(IScrollCaptureCallbacks callbacks) {
        getViewRootImpl().dispatchScrollCaptureRequest(callbacks);
    }

    /**
     * Registers a handler providing scrolling capture support for window content.
     *
     * @param callback the callback to add
     */
    @Override
    public void registerScrollCaptureCallback(@NonNull ScrollCaptureCallback callback) {
        getViewRootImpl().addScrollCaptureCallback(callback);
    }

    /**
     * Unregisters the given {@link ScrollCaptureCallback}.
     *
     * @param callback the callback to remove
     */
    @Override
    public void unregisterScrollCaptureCallback(@NonNull ScrollCaptureCallback callback) {
        getViewRootImpl().removeScrollCaptureCallback(callback);
    }

    @Override
    @Nullable
    public View getStatusBarBackgroundView() {
        return mDecor != null ? mDecor.getStatusBarBackgroundView() : null;
    }

    @Override
    @Nullable
    public View getNavigationBarBackgroundView() {
        return mDecor != null ? mDecor.getNavigationBarBackgroundView() : null;
    }
}
