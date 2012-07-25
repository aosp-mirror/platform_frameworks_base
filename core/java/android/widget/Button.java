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

package android.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.RemoteViews.RemoteView;


/**
 * Represents a push-button widget. Push-buttons can be
 * pressed, or clicked, by the user to perform an action.

 * <p>A typical use of a push-button in an activity would be the following:
 * </p>
 *
 * <pre>
 * public class MyActivity extends Activity {
 *     protected void onCreate(Bundle icicle) {
 *         super.onCreate(icicle);
 *
 *         setContentView(R.layout.content_layout_id);
 *
 *         final Button button = (Button) findViewById(R.id.button_id);
 *         button.setOnClickListener(new View.OnClickListener() {
 *             public void onClick(View v) {
 *                 // Perform action on click
 *             }
 *         });
 *     }
 * }</pre>
 *
 * <p>However, instead of applying an {@link android.view.View.OnClickListener OnClickListener} to
 * the button in your activity, you can assign a method to your button in the XML layout,
 * using the {@link android.R.attr#onClick android:onClick} attribute. For example:</p>
 *
 * <pre>
 * &lt;Button
 *     android:layout_height="wrap_content"
 *     android:layout_width="wrap_content"
 *     android:text="@string/self_destruct"
 *     android:onClick="selfDestruct" /&gt;</pre>
 *
 * <p>Now, when a user clicks the button, the Android system calls the activity's {@code
 * selfDestruct(View)} method. In order for this to work, the method must be public and accept
 * a {@link android.view.View} as its only parameter. For example:</p>
 *
 * <pre>
 * public void selfDestruct(View view) {
 *     // Kabloey
 * }</pre>
 *
 * <p>The {@link android.view.View} passed into the method is a reference to the widget
 * that was clicked.</p>
 *
 * <h3>Button style</h3>
 *
 * <p>Every Button is styled using the system's default button background, which is often different
 * from one device to another and from one version of the platform to another. If you're not
 * satisfied with the default button style and want to customize it to match the design of your
 * application, then you can replace the button's background image with a <a
 * href="{@docRoot}guide/topics/resources/drawable-resource.html#StateList">state list drawable</a>.
 * A state list drawable is a drawable resource defined in XML that changes its image based on
 * the current state of the button. Once you've defined a state list drawable in XML, you can apply
 * it to your Button with the {@link android.R.attr#background android:background}
 * attribute. For more information and an example, see <a
 * href="{@docRoot}guide/topics/resources/drawable-resource.html#StateList">State List
 * Drawable</a>.</p>
 *
 * <p>See the <a href="{@docRoot}guide/topics/ui/controls/button.html">Buttons</a>
 * guide.</p>
 *
 * <p><strong>XML attributes</strong></p>
 * <p>
 * See {@link android.R.styleable#Button Button Attributes},
 * {@link android.R.styleable#TextView TextView Attributes},
 * {@link android.R.styleable#View View Attributes}
 * </p>
 */
@RemoteView
public class Button extends TextView {
    public Button(Context context) {
        this(context, null);
    }

    public Button(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.buttonStyle);
    }

    public Button(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setClassName(Button.class.getName());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(Button.class.getName());
    }
}
