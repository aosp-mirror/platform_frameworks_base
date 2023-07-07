/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.view.contentcapture;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Looper;
import android.os.RemoteCallback;
import android.view.Choreographer;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.perftests.contentcapture.R;

/**
 * A simple activity used for testing, e.g. performance of activity switching, or as a base
 * container of testing view.
 */
public class CustomTestActivity extends Activity {
    public static final String INTENT_EXTRA_LAYOUT_ID = "layout_id";
    public static final String INTENT_EXTRA_CUSTOM_VIEWS = "custom_view_number";
    static final String INTENT_EXTRA_FINISH_ON_IDLE = "finish";
    static final String INTENT_EXTRA_DRAW_CALLBACK = "draw_callback";
    public static final int MAX_VIEWS = 500;
    private static final int CUSTOM_CONTAINER_LAYOUT_ID = R.layout.test_container_activity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getIntent().hasExtra(INTENT_EXTRA_LAYOUT_ID)) {
            final int layoutId = getIntent().getIntExtra(INTENT_EXTRA_LAYOUT_ID,
                    /* defaultValue= */0);
            setContentView(layoutId);
            if (layoutId == CUSTOM_CONTAINER_LAYOUT_ID) {
                createCustomViews(findViewById(R.id.root_view),
                        getIntent().getIntExtra(INTENT_EXTRA_CUSTOM_VIEWS, MAX_VIEWS));
            }
        }

        final RemoteCallback drawCallback = getIntent().getParcelableExtra(
                INTENT_EXTRA_DRAW_CALLBACK, RemoteCallback.class);
        if (drawCallback != null) {
            getWindow().getDecorView().addOnAttachStateChangeListener(
                    new View.OnAttachStateChangeListener() {
                        @Override
                        public void onViewAttachedToWindow(View v) {
                            Choreographer.getInstance().postCallback(
                                    Choreographer.CALLBACK_COMMIT,
                                    // Report that the first frame is drawn.
                                    () -> drawCallback.sendResult(null), null /* token */);
                        }

                        @Override
                        public void onViewDetachedFromWindow(View v) {
                        }
                    });
        }

        if (getIntent().getBooleanExtra(INTENT_EXTRA_FINISH_ON_IDLE, false)) {
            Looper.myQueue().addIdleHandler(() -> {
                // Finish without animation.
                finish();
                overridePendingTransition(0 /* enterAnim */, 0 /* exitAnim */);
                return false;
            });
        }
    }

    private void createCustomViews(LinearLayout root, int number) {
        LinearLayout horizontalLayout = null;
        for (int i = 0; i < number; i++) {
            final int j = i % 8;
            if (horizontalLayout != null && j == 0) {
                root.addView(horizontalLayout);
                horizontalLayout = null;
            }
            if (horizontalLayout == null) {
                horizontalLayout = createHorizontalLayout();
            }
            horizontalLayout.addView(createItem(null, i));
        }
        if (horizontalLayout != null) {
            root.addView(horizontalLayout);
        }
    }

    private LinearLayout createHorizontalLayout() {
        final LinearLayout layout = new LinearLayout(getApplicationContext());
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout createItem(Drawable drawable, int index) {
        final LinearLayout group = new LinearLayout(getApplicationContext());
        group.setOrientation(LinearLayout.VERTICAL);
        group.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, /* weight= */ 1.0f));

        final TextView text = new TextView(this);
        text.setText("i = " + index);
        group.addView(text);

        return group;
    }
}
