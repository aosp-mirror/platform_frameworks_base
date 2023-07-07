/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.textclassifier.TextClassification;

import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

/**
 * TextViewTest tests {@link TextView}.
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class TextViewContextMenuTest {
    private static final String INTENT_ACTION_MOCK_ACTION_TEXT_CLASSIFICATION =
            "android.text.coretest.textclassifiation";
    private static final String ACTION_TITLE = "ACTION_TITLE";
    private static final String ACTION_DESCRIPTION = "ACTION_DESCRIPTION";

    @Rule
    public final ActivityTestRule<TextViewContextMenuActivity> mActivityRule =
            new ActivityTestRule<>(TextViewContextMenuActivity.class);

    // Setup MenuItem mock with chaining.
    private MenuItem newMockMenuItem() {
        MenuItem mockItem = mock(MenuItem.class);
        when(mockItem.setAlphabeticShortcut(anyChar())).thenReturn(mockItem);
        when(mockItem.setAlphabeticShortcut(anyChar(), anyInt())).thenReturn(mockItem);
        when(mockItem.setOnMenuItemClickListener(any())).thenReturn(mockItem);
        when(mockItem.setEnabled(anyBoolean())).thenReturn(mockItem);
        when(mockItem.setIcon(any())).thenReturn(mockItem);
        when(mockItem.setIntent(any())).thenReturn(mockItem);
        when(mockItem.setContentDescription(any())).thenReturn(mockItem);
        return mockItem;
    }

    private RemoteAction createRemoteAction() {
        Intent intent = new Intent(INTENT_ACTION_MOCK_ACTION_TEXT_CLASSIFICATION)
                .setPackage(mActivity.getPackageName());
        PendingIntent pIntent = PendingIntent.getBroadcast(mActivity, 0, intent,
                PendingIntent.FLAG_MUTABLE);
        return new RemoteAction(
                Icon.createWithResource(mActivity, android.R.drawable.btn_star),
                ACTION_TITLE, ACTION_DESCRIPTION, pIntent);
    }

    private Activity mActivity;
    private SelectionActionModeHelper mMockHelper;
    private Editor.AssistantCallbackHelper mCallbackHelper;

    @Before
    public void setUp() {
        mActivity = mActivityRule.getActivity();
        EditText et = mActivity.findViewById(R.id.editText);

        mMockHelper = mock(SelectionActionModeHelper.class);
        mCallbackHelper = et.getEditorForTesting().new AssistantCallbackHelper(mMockHelper);
    }

    @UiThreadTest
    @Test
    public void testNoMenuInteraction_noTextClassification() {
        when(mMockHelper.getTextClassification()).thenReturn(null);
        ContextMenu menu = mock(ContextMenu.class);
        mCallbackHelper.updateAssistMenuItems(menu, null);
        verifyNoMoreInteractions(menu);
    }

    @UiThreadTest
    @Test
    public void testAddMenuForTextClassification() {
        // Setup
        RemoteAction action = createRemoteAction();
        TextClassification classification = new TextClassification.Builder()
                .addAction(action).build();
        when(mMockHelper.getTextClassification()).thenReturn(classification);

        ContextMenu menu = mock(ContextMenu.class);
        MenuItem mockMenuItem = newMockMenuItem();
        when(menu.add(anyInt(), anyInt(), anyInt(), any())).thenReturn(mockMenuItem);

        // Execute
        mCallbackHelper.updateAssistMenuItems(menu, null);

        // Verify
        ArgumentCaptor<Integer> idCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<CharSequence> titleCaptor = ArgumentCaptor.forClass(CharSequence.class);

        verify(menu, times(1)).add(anyInt(), idCaptor.capture(), anyInt(), titleCaptor.capture());

        assertThat(idCaptor.getValue()).isEqualTo(TextView.ID_ASSIST);
        assertThat(titleCaptor.getValue().toString()).isEqualTo(ACTION_TITLE);
        verify(mockMenuItem, times(1)).setContentDescription(eq(ACTION_DESCRIPTION));
    }

    @UiThreadTest
    @Test
    public void testAddMenuForLegacyTextClassification() {
        // Setup
        Intent intent = new Intent(INTENT_ACTION_MOCK_ACTION_TEXT_CLASSIFICATION)
                .setPackage(mActivity.getPackageName());
        TextClassification classification = new TextClassification.Builder()
                .setIcon(mActivity.getResources().getDrawable(android.R.drawable.star_on))
                .setLabel(ACTION_TITLE)
                .setIntent(intent)
                .build();
        when(mMockHelper.getTextClassification()).thenReturn(classification);

        ContextMenu menu = mock(ContextMenu.class);
        MenuItem mockMenuItem = newMockMenuItem();
        when(menu.add(anyInt(), anyInt(), anyInt(), any())).thenReturn(mockMenuItem);

        // Execute
        mCallbackHelper.updateAssistMenuItems(menu, null);

        // Verify
        ArgumentCaptor<Integer> idCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<CharSequence> titleCaptor = ArgumentCaptor.forClass(CharSequence.class);

        verify(menu, times(1)).add(anyInt(), idCaptor.capture(), anyInt(), titleCaptor.capture());

        assertThat(idCaptor.getValue()).isEqualTo(TextView.ID_ASSIST);
        assertThat(titleCaptor.getValue().toString()).isEqualTo(ACTION_TITLE);
    }

    @UiThreadTest
    @Test
    public void testAdjustIconSpaces() {
        GradientDrawable gd = new GradientDrawable();
        gd.setSize(128, 256);

        // Setup mocks
        ContextMenu menu = mock(ContextMenu.class);

        MenuItem mockIconMenu = newMockMenuItem();
        when(mockIconMenu.getIcon()).thenReturn(gd);

        MenuItem mockNoIconMenu = newMockMenuItem();
        when(mockNoIconMenu.getIcon()).thenReturn(null);

        MenuItem mockNoIconMenu2 = newMockMenuItem();
        when(mockNoIconMenu2.getIcon()).thenReturn(null);

        when(menu.size()).thenReturn(3);
        when(menu.getItem(0)).thenReturn(mockIconMenu);
        when(menu.getItem(1)).thenReturn(mockNoIconMenu);
        when(menu.getItem(2)).thenReturn(mockNoIconMenu2);


        // Execute the test method
        EditText et = mActivity.findViewById(R.id.editText);
        Editor editor = et.getEditorForTesting();
        editor.adjustIconSpacing(menu);

        // Verify
        ArgumentCaptor<Drawable> drawableCaptor = ArgumentCaptor.forClass(Drawable.class);
        verify(mockNoIconMenu).setIcon(drawableCaptor.capture());

        Drawable paddingDrawable = drawableCaptor.getValue();
        assertThat(paddingDrawable).isNotNull();
        assertThat(paddingDrawable.getIntrinsicWidth()).isEqualTo(128);
        assertThat(paddingDrawable.getIntrinsicHeight()).isEqualTo(256);

        ArgumentCaptor<Drawable> drawableCaptor2 = ArgumentCaptor.forClass(Drawable.class);
        verify(mockNoIconMenu2).setIcon(drawableCaptor2.capture());

        Drawable paddingDrawable2 = drawableCaptor2.getValue();
        assertThat(paddingDrawable2).isSameInstanceAs(paddingDrawable);
    }

    @UiThreadTest
    @Test
    public void testAdjustIconSpacesNoIconCase() {
        // Setup mocks
        ContextMenu menu = mock(ContextMenu.class);

        MenuItem mockNoIconMenu = newMockMenuItem();
        when(mockNoIconMenu.getIcon()).thenReturn(null);

        MenuItem mockNoIconMenu2 = newMockMenuItem();
        when(mockNoIconMenu2.getIcon()).thenReturn(null);

        when(menu.size()).thenReturn(2);
        when(menu.getItem(0)).thenReturn(mockNoIconMenu);
        when(menu.getItem(1)).thenReturn(mockNoIconMenu2);

        // Execute the test method
        EditText et = mActivity.findViewById(R.id.editText);
        Editor editor = et.getEditorForTesting();
        editor.adjustIconSpacing(menu);

        // Verify
        verify(mockNoIconMenu, times(0)).setIcon(any());
        verify(mockNoIconMenu2, times(0)).setIcon(any());
    }
}
