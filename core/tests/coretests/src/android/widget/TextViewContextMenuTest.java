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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.textclassifier.TextClassification;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.verification.VerificationMode;

/**
 * TextViewTest tests {@link TextView}.
 */
@RunWith(AndroidJUnit4.class)
public class TextViewContextMenuTest {
    private static final String INTENT_ACTION_MOCK_ACTION_TEXT_CLASSIFICATION =
            "android.text.coretest.textclassifiation";
    private static final String ACTION_TITLE = "ACTION_TITLE";
    private static final String ACTION_DESCRIPTION = "ACTION_DESCRIPTION";

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

    private RemoteAction createRemoteAction(Context context) {
        Intent intent = new Intent(INTENT_ACTION_MOCK_ACTION_TEXT_CLASSIFICATION)
                .setPackage(context.getPackageName());
        PendingIntent pIntent = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_MUTABLE);
        return new RemoteAction(
                Icon.createWithResource(context, android.R.drawable.btn_star),
                ACTION_TITLE, ACTION_DESCRIPTION, pIntent);
    }

    private SelectionActionModeHelper mMockHelper;

    @ClassRule public static final SetFlagsRule.ClassRule SET_FLAGS_CLASS_RULE =
            new SetFlagsRule.ClassRule();
    @Rule public final SetFlagsRule mSetFlagsRule = SET_FLAGS_CLASS_RULE.createSetFlagsRule();

    @Before
    public void setUp() {
        mMockHelper = mock(SelectionActionModeHelper.class);
    }

    @Test
    public void testNoMenuInteraction_noTextClassification() {
        when(mMockHelper.getTextClassification()).thenReturn(null);
        ContextMenu menu = mock(ContextMenu.class);
        EditText et = new EditText(getInstrumentation().getContext());
        Editor.AssistantCallbackHelper cbh =
                et.getEditorForTesting().new AssistantCallbackHelper(mMockHelper);
        cbh.updateAssistMenuItems(menu, null);
        verifyNoMoreInteractions(menu);
    }

    @Test
    public void testAddMenuForTextClassification() {
        // Setup
        Context context = getInstrumentation().getContext();
        RemoteAction action = createRemoteAction(context);
        TextClassification classification = new TextClassification.Builder()
                .addAction(action).build();
        when(mMockHelper.getTextClassification()).thenReturn(classification);

        ContextMenu menu = mock(ContextMenu.class);
        MenuItem mockMenuItem = newMockMenuItem();
        when(menu.add(anyInt(), anyInt(), anyInt(), any())).thenReturn(mockMenuItem);

        // Execute
        EditText et = new EditText(context);
        Editor.AssistantCallbackHelper cbh =
                et.getEditorForTesting().new AssistantCallbackHelper(mMockHelper);
        cbh.updateAssistMenuItems(menu, null);

        // Verify
        ArgumentCaptor<Integer> idCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<CharSequence> titleCaptor = ArgumentCaptor.forClass(CharSequence.class);

        verify(menu, times(1)).add(anyInt(), idCaptor.capture(), anyInt(), titleCaptor.capture());

        assertThat(idCaptor.getValue()).isEqualTo(TextView.ID_ASSIST);
        assertThat(titleCaptor.getValue().toString()).isEqualTo(ACTION_TITLE);
        verify(mockMenuItem, times(1)).setContentDescription(eq(ACTION_DESCRIPTION));
    }

    @Test
    public void testAddMenuForLegacyTextClassification() {
        // Setup
        Context context = getInstrumentation().getContext();
        Intent intent = new Intent(INTENT_ACTION_MOCK_ACTION_TEXT_CLASSIFICATION)
                .setPackage(context.getPackageName());
        TextClassification classification = new TextClassification.Builder()
                .setIcon(context.getResources().getDrawable(android.R.drawable.star_on))
                .setLabel(ACTION_TITLE)
                .setIntent(intent)
                .build();
        when(mMockHelper.getTextClassification()).thenReturn(classification);

        ContextMenu menu = mock(ContextMenu.class);
        MenuItem mockMenuItem = newMockMenuItem();
        when(menu.add(anyInt(), anyInt(), anyInt(), any())).thenReturn(mockMenuItem);

        // Execute
        EditText et = new EditText(context);
        Editor.AssistantCallbackHelper cbh =
                et.getEditorForTesting().new AssistantCallbackHelper(mMockHelper);
        cbh.updateAssistMenuItems(menu, null);

        // Verify
        ArgumentCaptor<Integer> idCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<CharSequence> titleCaptor = ArgumentCaptor.forClass(CharSequence.class);

        verify(menu, times(1)).add(anyInt(), idCaptor.capture(), anyInt(), titleCaptor.capture());

        assertThat(idCaptor.getValue()).isEqualTo(TextView.ID_ASSIST);
        assertThat(titleCaptor.getValue().toString()).isEqualTo(ACTION_TITLE);
    }

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
        EditText et = new EditText(getInstrumentation().getContext());
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
        EditText et = new EditText(getInstrumentation().getContext());
        Editor editor = et.getEditorForTesting();
        editor.adjustIconSpacing(menu);

        // Verify
        verify(mockNoIconMenu, times(0)).setIcon(any());
        verify(mockNoIconMenu2, times(0)).setIcon(any());
    }

    @Test
    @DisableFlags(com.android.text.flags.Flags.FLAG_CONTEXT_MENU_HIDE_UNAVAILABLE_ITEMS)
    public void testAutofillMenuItemEnabledWhenNoTextSelected() {
        ContextMenu menu = mock(ContextMenu.class);
        MenuItem mockMenuItem = newMockMenuItem();
        when(menu.add(anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(mockMenuItem);
        MenuItem mockAutofillMenuItem = newMockMenuItem();
        when(menu.add(anyInt(), eq(TextView.ID_AUTOFILL), anyInt(), anyInt()))
                .thenReturn(mockAutofillMenuItem);

        EditText et = spy(new EditText(getInstrumentation().getContext()));
        doReturn(true).when(et).canRequestAutofill();
        doReturn(null).when(et).getSelectedText();

        Editor editor = new Editor(et);
        editor.setTextContextMenuItems(menu);

        verify(menu).add(anyInt(), eq(TextView.ID_AUTOFILL), anyInt(), anyInt());
        verify(mockAutofillMenuItem).setEnabled(true);
    }

    @Test
    @DisableFlags(com.android.text.flags.Flags.FLAG_CONTEXT_MENU_HIDE_UNAVAILABLE_ITEMS)
    public void testAutofillMenuItemNotEnabledWhenTextSelected() {
        ContextMenu menu = mock(ContextMenu.class);
        MenuItem mockMenuItem = newMockMenuItem();
        when(menu.add(anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(mockMenuItem);
        MenuItem mockAutofillMenuItem = newMockMenuItem();
        when(menu.add(anyInt(), eq(TextView.ID_AUTOFILL), anyInt(), anyInt()))
                .thenReturn(mockAutofillMenuItem);

        EditText et = spy(new EditText(getInstrumentation().getContext()));
        doReturn(true).when(et).canRequestAutofill();
        doReturn("test").when(et).getSelectedText();
        Editor editor = new Editor(et);
        editor.setTextContextMenuItems(menu);

        verify(menu).add(anyInt(), eq(TextView.ID_AUTOFILL), anyInt(), anyInt());
        verify(mockAutofillMenuItem).setEnabled(false);
    }

    private interface EditTextSetup {
        void run(EditText et);
    }

    private void verifyMenuItemNotAdded(EditTextSetup setup, int id, VerificationMode times) {
        ContextMenu menu = mock(ContextMenu.class);
        MenuItem mockMenuItem = newMockMenuItem();
        when(menu.add(anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(mockMenuItem);
        EditText et = spy(new EditText(getInstrumentation().getContext()));
        setup.run(et);
        Editor editor = new Editor(et);
        editor.setTextContextMenuItems(menu);
        verify(menu, times).add(anyInt(), eq(id), anyInt(), anyInt());
    }

    @Test
    @EnableFlags(com.android.text.flags.Flags.FLAG_CONTEXT_MENU_HIDE_UNAVAILABLE_ITEMS)
    public void testContextMenuUndoNotAddedWhenUnavailable() {
        verifyMenuItemNotAdded((spy) -> doReturn(false).when(spy).canUndo(),
                TextView.ID_UNDO, never());
    }

    @Test
    @EnableFlags(com.android.text.flags.Flags.FLAG_CONTEXT_MENU_HIDE_UNAVAILABLE_ITEMS)
    public void testContextMenuUndoAddedWhenAvailable() {
        verifyMenuItemNotAdded((spy) -> doReturn(true).when(spy).canUndo(), TextView.ID_UNDO,
                times(1));
    }

    @Test
    @EnableFlags(com.android.text.flags.Flags.FLAG_CONTEXT_MENU_HIDE_UNAVAILABLE_ITEMS)
    public void testContextMenuRedoNotAddedWhenUnavailable() {
        verifyMenuItemNotAdded((spy) -> doReturn(false).when(spy).canRedo(), TextView.ID_REDO,
                never());
    }

    @Test
    @EnableFlags(com.android.text.flags.Flags.FLAG_CONTEXT_MENU_HIDE_UNAVAILABLE_ITEMS)
    public void testContextMenuRedoAddedWhenUnavailable() {
        verifyMenuItemNotAdded((spy) -> doReturn(true).when(spy).canRedo(), TextView.ID_REDO,
                times(1));
    }

    @Test
    @EnableFlags(com.android.text.flags.Flags.FLAG_CONTEXT_MENU_HIDE_UNAVAILABLE_ITEMS)
    public void testContextMenuCutNotAddedWhenUnavailable() {
        verifyMenuItemNotAdded((spy) -> doReturn(false).when(spy).canCut(), TextView.ID_CUT,
                never());
    }

    @Test
    @EnableFlags(com.android.text.flags.Flags.FLAG_CONTEXT_MENU_HIDE_UNAVAILABLE_ITEMS)
    public void testContextMenuCutAddedWhenAvailable() {
        verifyMenuItemNotAdded((spy) -> doReturn(true).when(spy).canCut(), TextView.ID_CUT,
                times(1));
    }

    @Test
    @EnableFlags(com.android.text.flags.Flags.FLAG_CONTEXT_MENU_HIDE_UNAVAILABLE_ITEMS)
    public void testContextMenuCopyNotAddedWhenUnavailable() {
        verifyMenuItemNotAdded((spy) -> doReturn(false).when(spy).canCopy(), TextView.ID_COPY,
                never());
    }

    @Test
    @EnableFlags(com.android.text.flags.Flags.FLAG_CONTEXT_MENU_HIDE_UNAVAILABLE_ITEMS)
    public void testContextMenuCopyAddedWhenAvailable() {
        verifyMenuItemNotAdded((spy) -> doReturn(true).when(spy).canCopy(), TextView.ID_COPY,
                times(1));
    }

    @Test
    @EnableFlags(com.android.text.flags.Flags.FLAG_CONTEXT_MENU_HIDE_UNAVAILABLE_ITEMS)
    public void testContextMenuPasteNotAddedWhenUnavailable() {
        verifyMenuItemNotAdded((spy) -> doReturn(false).when(spy).canPaste(), TextView.ID_PASTE,
                never());
    }

    @Test
    @EnableFlags(com.android.text.flags.Flags.FLAG_CONTEXT_MENU_HIDE_UNAVAILABLE_ITEMS)
    public void testContextMenuPasteAddedWhenAvailable() {
        verifyMenuItemNotAdded((spy) -> doReturn(true).when(spy).canPaste(), TextView.ID_PASTE,
                times(1));
    }

    @Test
    @EnableFlags(com.android.text.flags.Flags.FLAG_CONTEXT_MENU_HIDE_UNAVAILABLE_ITEMS)
    public void testContextMenuPasteAsPlaintextNotAddedWhenUnavailable() {
        verifyMenuItemNotAdded((spy) -> doReturn(false).when(spy).canPasteAsPlainText(),
                        TextView.ID_PASTE_AS_PLAIN_TEXT, never());
    }

    @Test
    @EnableFlags(com.android.text.flags.Flags.FLAG_CONTEXT_MENU_HIDE_UNAVAILABLE_ITEMS)
    public void testContextMenuPasteAsPlaintextAddedWhenAvailable() {
        verifyMenuItemNotAdded((spy) -> doReturn(true).when(spy).canPasteAsPlainText(),
                        TextView.ID_PASTE_AS_PLAIN_TEXT, times(1));
    }

    @Test
    @EnableFlags(com.android.text.flags.Flags.FLAG_CONTEXT_MENU_HIDE_UNAVAILABLE_ITEMS)
    public void testContextMenuSelectAllNotAddedWhenUnavailable() {
        verifyMenuItemNotAdded((spy) -> doReturn(false).when(spy).canSelectAllText(),
                        TextView.ID_SELECT_ALL, never());
    }

    @Test
    @EnableFlags(com.android.text.flags.Flags.FLAG_CONTEXT_MENU_HIDE_UNAVAILABLE_ITEMS)
    public void testContextMenuSelectAllAddedWhenAvailable() {
        verifyMenuItemNotAdded((spy) -> doReturn(true).when(spy).canSelectAllText(),
                        TextView.ID_SELECT_ALL, times(1));
    }

    @Test
    @EnableFlags(com.android.text.flags.Flags.FLAG_CONTEXT_MENU_HIDE_UNAVAILABLE_ITEMS)
    public void testContextMenuShareNotAddedWhenUnavailable() {
        verifyMenuItemNotAdded((spy) -> doReturn(false).when(spy).canShare(), TextView.ID_SHARE,
                never());
    }

    @Test
    @EnableFlags(com.android.text.flags.Flags.FLAG_CONTEXT_MENU_HIDE_UNAVAILABLE_ITEMS)
    public void testContextMenuShareAddedWhenAvailable() {
        verifyMenuItemNotAdded((spy) -> doReturn(true).when(spy).canShare(), TextView.ID_SHARE,
                times(1));
    }

    @Test
    @EnableFlags(com.android.text.flags.Flags.FLAG_CONTEXT_MENU_HIDE_UNAVAILABLE_ITEMS)
    public void testContextMenuAutofillNotAddedWhenUnavailable() {
        verifyMenuItemNotAdded((spy) -> doReturn(false).when(spy).canRequestAutofill(),
                TextView.ID_AUTOFILL, never());
    }

    @Test
    @EnableFlags(com.android.text.flags.Flags.FLAG_CONTEXT_MENU_HIDE_UNAVAILABLE_ITEMS)
    public void testContextMenuAutofillNotAddedWhenUnavailableBecauseTextSelected() {
        verifyMenuItemNotAdded((spy) -> {
            doReturn(true).when(spy).canRequestAutofill();
            doReturn("test").when(spy).getSelectedText();
        }, TextView.ID_AUTOFILL, never());
    }
}
