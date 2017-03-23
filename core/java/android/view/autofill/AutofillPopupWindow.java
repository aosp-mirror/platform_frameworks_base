/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.view.autofill;

import android.annotation.NonNull;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.transition.Transition;
import android.util.Log;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.PopupWindow;

/**
 * Custom {@link PopupWindow} used to isolate its content from the autofilled app - the
 * UI is rendered in a framework process, but it's controlled by the app.
 *
 * TODO(b/34943932): use an app surface control solution.
 *
 * @hide
 */
public class AutofillPopupWindow extends PopupWindow {

    private static final String TAG = "AutofillPopupWindow";

    private final WindowPresenter mWindowPresenter;
    private WindowManager.LayoutParams mWindowLayoutParams;

    /**
     * Creates a popup window with a presenter owning the window and responsible for
     * showing/hiding/updating the backing window. This can be useful of the window is
     * being shown by another process while the popup logic is in the process hosting
     * the anchor view.
     * <p>
     * Using this constructor means that the presenter completely owns the content of
     * the window and the following methods manipulating the window content shouldn't
     * be used: {@link #getEnterTransition()}, {@link #setEnterTransition(Transition)},
     * {@link #getExitTransition()}, {@link #setExitTransition(Transition)},
     * {@link #getContentView()}, {@link #setContentView(View)}, {@link #getBackground()},
     * {@link #setBackgroundDrawable(Drawable)}, {@link #getElevation()},
     * {@link #setElevation(float)}, ({@link #getAnimationStyle()},
     * {@link #setAnimationStyle(int)}, {@link #setTouchInterceptor(OnTouchListener)}.</p>
     */
    public AutofillPopupWindow(@NonNull IAutofillWindowPresenter presenter) {
        mWindowPresenter = new WindowPresenter(presenter);

        setOutsideTouchable(true);
        setInputMethodMode(INPUT_METHOD_NEEDED);
    }

    @Override
    protected boolean hasContentView() {
        return true;
    }

    @Override
    protected boolean hasDecorView() {
        return true;
    }

    @Override
    protected LayoutParams getDecorViewLayoutParams() {
        return mWindowLayoutParams;
    }

    /**
     * The effective {@code update} method that should be called by its clients.
     */
    public void update(View anchor, int offsetX, int offsetY, int width, int height,
            Rect anchorBounds, Rect actualAnchorBounds) {
        if (!isShowing()) {
            setWidth(width);
            setHeight(height);
            showAsDropDown(anchor, offsetX, offsetY);
        } else {
            update(anchor, offsetX, offsetY, width, height);
        }

        if (anchorBounds != null && mWindowLayoutParams.y > anchorBounds.bottom) {
            offsetY = anchorBounds.bottom - actualAnchorBounds.bottom;
            update(anchor, offsetX, offsetY, width, height);
        }
    }

    @Override
    protected void update(View anchor, WindowManager.LayoutParams params) {
        final int layoutDirection = anchor != null ? anchor.getLayoutDirection()
                : View.LAYOUT_DIRECTION_LOCALE;
        mWindowPresenter.show(params, getTransitionEpicenter(), isLayoutInsetDecor(),
                layoutDirection);
    }

    @Override
    public void showAsDropDown(View anchor, int xoff, int yoff, int gravity) {
        if (isShowing()) {
            return;
        }

        setShowing(true);
        setDropDown(true);
        attachToAnchor(anchor, xoff, yoff, gravity);
        final WindowManager.LayoutParams p = mWindowLayoutParams = createPopupLayoutParams(
                anchor.getWindowToken());
        final boolean aboveAnchor = findDropDownPosition(anchor, p, xoff, yoff,
                p.width, p.height, gravity, getAllowScrollingAnchorParent());
        updateAboveAnchor(aboveAnchor);
        p.accessibilityIdOfAnchor = anchor.getAccessibilityViewId();
        p.packageName = anchor.getContext().getPackageName();
        mWindowPresenter.show(p, getTransitionEpicenter(), isLayoutInsetDecor(),
                anchor.getLayoutDirection());
        return;
    }

    @Override
    public void dismiss() {
        if (!isShowing() || isTransitioningToDismiss()) {
            return;
        }

        setShowing(false);
        setTransitioningToDismiss(true);

        mWindowPresenter.hide(getTransitionEpicenter());
        detachFromAnchor();
        if (getOnDismissListener() != null) {
            getOnDismissListener().onDismiss();
        }
    }

    @Override
    public int getAnimationStyle() {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public Drawable getBackground() {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public View getContentView() {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public float getElevation() {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public Transition getEnterTransition() {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public Transition getExitTransition() {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public void setAnimationStyle(int animationStyle) {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public void setBackgroundDrawable(Drawable background) {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public void setContentView(View contentView) {
        if (contentView != null) {
            throw new IllegalStateException("You can't call this!");
        }
    }

    @Override
    public void setElevation(float elevation) {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public void setEnterTransition(Transition enterTransition) {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public void setExitTransition(Transition exitTransition) {
        throw new IllegalStateException("You can't call this!");
    }

    @Override
    public void setTouchInterceptor(OnTouchListener l) {
        throw new IllegalStateException("You can't call this!");
    }

    /**
     * Contract between the popup window and a presenter that is responsible for
     * showing/hiding/updating the actual window.
     *
     * <p>This can be useful if the anchor is in one process and the backing window is owned by
     * another process.
     */
    private class WindowPresenter {
        final IAutofillWindowPresenter mPresenter;

        WindowPresenter(IAutofillWindowPresenter presenter) {
            mPresenter = presenter;
        }

        /**
         * Shows the backing window.
         *
         * @param p The window layout params.
         * @param transitionEpicenter The transition epicenter if animating.
         * @param fitsSystemWindows Whether the content view should account for system decorations.
         * @param layoutDirection The content layout direction to be consistent with the anchor.
         */
        void show(WindowManager.LayoutParams p, Rect transitionEpicenter, boolean fitsSystemWindows,
                int layoutDirection) {
            try {
                mPresenter.show(p, transitionEpicenter, fitsSystemWindows, layoutDirection);
            } catch (RemoteException e) {
                Log.w(TAG, "Error showing fill window", e);
                e.rethrowFromSystemServer();
            }
        }

        /**
         * Hides the backing window.
         *
         * @param transitionEpicenter The transition epicenter if animating.
         */
        void hide(Rect transitionEpicenter) {
            try {
                mPresenter.hide(transitionEpicenter);
            } catch (RemoteException e) {
                Log.w(TAG, "Error hiding fill window", e);
                e.rethrowFromSystemServer();
            }
        }
    }
}
