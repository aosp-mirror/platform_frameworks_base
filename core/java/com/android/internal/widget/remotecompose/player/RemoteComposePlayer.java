/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.player;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

import com.android.internal.widget.remotecompose.core.operations.RootContentBehavior;
import com.android.internal.widget.remotecompose.player.platform.RemoteComposeCanvas;

/**
 * A view to to display and play RemoteCompose documents
 */
public class RemoteComposePlayer extends FrameLayout {
    private RemoteComposeCanvas mInner;

    private static final int MAX_SUPPORTED_MAJOR_VERSION = 0;
    private static final int MAX_SUPPORTED_MINOR_VERSION = 1;

    public RemoteComposePlayer(Context context) {
        super(context);
        init(context, null, 0);
    }

    public RemoteComposePlayer(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs, 0);
    }

    public RemoteComposePlayer(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs, defStyleAttr);
    }

    /**
     * Turn on debug information
     *
     * @param debugFlags 1 to set debug on
     */
    public void setDebug(int debugFlags) {
        if (debugFlags == 1) {
            mInner.setDebug(true);
        } else {
            mInner.setDebug(false);
        }
    }

    public void setDocument(RemoteComposeDocument value) {
        if (value != null) {
            if (value.canBeDisplayed(
                    MAX_SUPPORTED_MAJOR_VERSION,
                    MAX_SUPPORTED_MINOR_VERSION, 0L
            )
            ) {
                mInner.setDocument(value);
                int contentBehavior = value.getDocument().getContentScroll();
                applyContentBehavior(contentBehavior);
            } else {
                Log.e("RemoteComposePlayer", "Unsupported document ");
            }
        } else {
            mInner.setDocument(null);
        }
        mapColors();
    }

    /**
     * Apply the content behavior (NONE|SCROLL_HORIZONTAL|SCROLL_VERTICAL) to the player,
     * adding or removing scrollviews as needed.
     *
     * @param contentBehavior document content behavior (NONE|SCROLL_HORIZONTAL|SCROLL_VERTICAL)
     */
    private void applyContentBehavior(int contentBehavior) {
        switch (contentBehavior) {
            case RootContentBehavior.SCROLL_HORIZONTAL: {
                if (!(mInner.getParent() instanceof HorizontalScrollView)) {
                    ((ViewGroup) mInner.getParent()).removeView(mInner);
                    removeAllViews();
                    LayoutParams layoutParamsInner = new LayoutParams(
                            LayoutParams.WRAP_CONTENT,
                            LayoutParams.MATCH_PARENT);
                    HorizontalScrollView horizontalScrollView =
                            new HorizontalScrollView(getContext());
                    horizontalScrollView.setBackgroundColor(Color.TRANSPARENT);
                    horizontalScrollView.setFillViewport(true);
                    horizontalScrollView.addView(mInner, layoutParamsInner);
                    LayoutParams layoutParams = new LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            LayoutParams.MATCH_PARENT);
                    addView(horizontalScrollView, layoutParams);
                }
            }
            break;
            case RootContentBehavior.SCROLL_VERTICAL: {
                if (!(mInner.getParent() instanceof ScrollView)) {
                    ((ViewGroup) mInner.getParent()).removeView(mInner);
                    removeAllViews();
                    LayoutParams layoutParamsInner = new LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            LayoutParams.WRAP_CONTENT);
                    ScrollView scrollView = new ScrollView(getContext());
                    scrollView.setBackgroundColor(Color.TRANSPARENT);
                    scrollView.setFillViewport(true);
                    scrollView.addView(mInner, layoutParamsInner);
                    LayoutParams layoutParams = new LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            LayoutParams.MATCH_PARENT);
                    addView(scrollView, layoutParams);
                }
            }
            break;
            default:
                if (mInner.getParent() != this) {
                    ((ViewGroup) mInner.getParent()).removeView(mInner);
                    removeAllViews();
                    LayoutParams layoutParams = new LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            LayoutParams.MATCH_PARENT);
                    addView(mInner, layoutParams);
                }
        }
    }

    private void init(Context context, AttributeSet attrs, int defStyleAttr) {
        LayoutParams layoutParams = new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT);
        setBackgroundColor(Color.TRANSPARENT);
        mInner = new RemoteComposeCanvas(context, attrs, defStyleAttr);
        mInner.setBackgroundColor(Color.TRANSPARENT);
        addView(mInner, layoutParams);
    }

    public interface ClickCallbacks {
        void click(int id, String metadata);
    }

    /**
     * Add a callback for handling click events on the document
     *
     * @param callback the callback lambda that will be used when a click is detected
     *                 <p>
     *                 The parameter of the callback are:
     *                 id : the id of the clicked area
     *                 metadata: a client provided unstructured string associated with that area
     */
    public void addClickListener(ClickCallbacks callback) {
        mInner.addClickListener((id, metadata) -> callback.click(id, metadata));
    }

    /**
     * Set the playback theme for the document. This allows to filter operations in order
     * to have the document adapt to the given theme. This method is intended to be used
     * to support night/light themes (system or app level), not custom themes.
     *
     * @param theme the theme used for playing the document. Possible values for theme are:
     *              - Theme.UNSPECIFIED -- all instructions in the document will be executed
     *              - Theme.DARK -- only executed NON Light theme instructions
     *              - Theme.LIGHT -- only executed NON Dark theme instructions
     */
    public void setTheme(int theme) {
        if (mInner.getTheme() != theme) {
            mInner.setTheme(theme);
            mInner.invalidate();
        }
    }

    /**
     * This returns a list of colors that have names in the Document.
     *
     * @return
     */
    public String[] getNamedColors() {
        return mInner.getNamedColors();
    }

    /**
     * This sets a color based on its name. Overriding the color set in
     * the document.
     *
     * @param colorName Name of the color
     * @param colorValue The new color value
     */
    public void setColor(String colorName, int colorValue) {
        mInner.setColor(colorName, colorValue);
    }

    private void mapColors() {
        String[] name = getNamedColors();

        // make every effort to terminate early
        if (name == null) {
            return;
        }
        boolean found = false;
        for (int i = 0; i < name.length; i++) {
            if (name[i].startsWith("android.")) {
                found = true;
                break;
            }
        }
        if (!found) {
            return;
        }

        for (int i = 0; i < name.length; i++) {
            String s = name[i];
            if (!s.startsWith("android.")) {
                continue;
            }
            String sub = s.substring("android.".length());
            switch (sub) {
                case "actionBarItemBackground":
                    setRColor(s, android.R.attr.actionBarItemBackground);
                    break;
                case "actionModeBackground":
                    setRColor(s, android.R.attr.actionModeBackground);
                    break;
                case "actionModeSplitBackground":
                    setRColor(s, android.R.attr.actionModeSplitBackground);
                    break;
                case "activatedBackgroundIndicator":
                    setRColor(s, android.R.attr.activatedBackgroundIndicator);
                    break;
                case "colorAccent": // Highlight color for interactive elements
                    setRColor(s, android.R.attr.colorAccent);
                    break;
                case "colorActivatedHighlight":
                    setRColor(s, android.R.attr.colorActivatedHighlight);
                    break;
                case "colorBackground": // background color for the app’s window
                    setRColor(s, android.R.attr.colorBackground);
                    break;
                case "colorBackgroundCacheHint":
                    setRColor(s, android.R.attr.colorBackgroundCacheHint);
                    break;
                //  Background color for floating elements
                case "colorBackgroundFloating":
                    setRColor(s, android.R.attr.colorBackgroundFloating);
                    break;
                case "colorButtonNormal": // The default color for buttons
                    setRColor(s, android.R.attr.colorButtonNormal);
                    break;
                // Color for activated (checked) state of controls.
                case "colorControlActivated":
                    setRColor(s, android.R.attr.colorControlActivated);
                    break;
                case "colorControlHighlight": // Color for highlights on controls
                    setRColor(s, android.R.attr.colorControlHighlight);
                    break;
                // Default color for controls in their normal state.
                case "colorControlNormal":
                    setRColor(s, android.R.attr.colorControlNormal);
                    break;
                // Color for edge effects (e.g., overscroll glow)
                case "colorEdgeEffect":
                    setRColor(s, android.R.attr.colorEdgeEffect);
                    break;
                case "colorError":
                    setRColor(s, android.R.attr.colorError);
                    break;
                case "colorFocusedHighlight":
                    setRColor(s, android.R.attr.colorFocusedHighlight);
                    break;
                case "colorForeground":   // General foreground color for views.
                    setRColor(s, android.R.attr.colorForeground);
                    break;
                // Foreground color for inverse backgrounds.
                case "colorForegroundInverse":
                    setRColor(s, android.R.attr.colorForegroundInverse);
                    break;
                case "colorLongPressedHighlight":
                    setRColor(s, android.R.attr.colorLongPressedHighlight);
                    break;
                case "colorMultiSelectHighlight":
                    setRColor(s, android.R.attr.colorMultiSelectHighlight);
                    break;
                case "colorPressedHighlight":
                    setRColor(s, android.R.attr.colorPressedHighlight);
                    break;
                case "colorPrimary": // The primary branding color for the app.
                    setRColor(s, android.R.attr.colorPrimary);
                    break;
                case "colorPrimaryDark": // darker variant of the primary color
                    setRColor(s, android.R.attr.colorPrimaryDark);
                    break;
                case "colorSecondary":
                    setRColor(s, android.R.attr.colorSecondary);
                    break;
                case "detailsElementBackground":
                    setRColor(s, android.R.attr.detailsElementBackground);
                    break;
                case "editTextBackground":
                    setRColor(s, android.R.attr.editTextBackground);
                    break;
                case "galleryItemBackground":
                    setRColor(s, android.R.attr.galleryItemBackground);
                    break;
                case "headerBackground":
                    setRColor(s, android.R.attr.headerBackground);
                    break;
                case "itemBackground":
                    setRColor(s, android.R.attr.itemBackground);
                    break;
                case "numbersBackgroundColor":
                    setRColor(s, android.R.attr.numbersBackgroundColor);
                    break;
                case "panelBackground":
                    setRColor(s, android.R.attr.panelBackground);
                    break;
                case "panelColorBackground":
                    setRColor(s, android.R.attr.panelColorBackground);
                    break;
                case "panelFullBackground":
                    setRColor(s, android.R.attr.panelFullBackground);
                    break;
                case "popupBackground":
                    setRColor(s, android.R.attr.popupBackground);
                    break;
                case "queryBackground":
                    setRColor(s, android.R.attr.queryBackground);
                    break;
                case "selectableItemBackground":
                    setRColor(s, android.R.attr.selectableItemBackground);
                    break;
                case "submitBackground":
                    setRColor(s, android.R.attr.submitBackground);
                    break;
                case "textColor":
                    setRColor(s, android.R.attr.textColor);
                    break;
                case "windowBackground":
                    setRColor(s, android.R.attr.windowBackground);
                    break;
                case "windowBackgroundFallback":
                    setRColor(s, android.R.attr.windowBackgroundFallback);
                    break;
                // Primary text color for inverse backgrounds
                case "textColorPrimaryInverse":
                    setRColor(s, android.R.attr.textColorPrimaryInverse);
                    break;
                // Secondary text color for inverse backgrounds
                case "textColorSecondaryInverse":
                    setRColor(s, android.R.attr.textColorSecondaryInverse);
                    break;
                // Tertiary text color for less important text.
                case "textColorTertiary":
                    setRColor(s, android.R.attr.textColorTertiary);
                    break;
                // Tertiary text color for inverse backgrounds
                case "textColorTertiaryInverse":
                    setRColor(s, android.R.attr.textColorTertiaryInverse);
                    break;
                // Text highlight color (e.g., selected text background).
                case "textColorHighlight":
                    setRColor(s, android.R.attr.textColorHighlight);
                    break;
                // Color for hyperlinks.
                case "textColorLink":
                    setRColor(s, android.R.attr.textColorLink);
                    break;
                //  Color for hint text.
                case "textColorHint":
                    setRColor(s, android.R.attr.textColorHint);
                    break;
                // text color for inverse backgrounds..
                case "textColorHintInverse":
                    setRColor(s, android.R.attr.textColorHintInverse);
                    break;
                // Default color for the thumb of switches.
                case "colorSwitchThumbNormal":
                    setRColor(s, android.R.attr.colorControlNormal);
                    break;
            }
        }
    }

    private void setRColor(String name, int id) {
        int color = getColorFromResource(id);
        setColor(name, color);
    }

    private int getColorFromResource(int id) {
        TypedValue typedValue = new TypedValue();
        try (TypedArray arr = getContext()
                .getApplicationContext()
                .obtainStyledAttributes(typedValue.data, new int[]{id})) {
            int color = arr.getColor(0, -1);
            return color;
        }
    }
}

