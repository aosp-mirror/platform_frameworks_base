/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.clipboardoverlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import android.app.RemoteAction;
import android.content.ClipData;
import android.content.ClipDescription;
import android.os.PersistableBundle;
import android.testing.TestableResources;
import android.util.ArrayMap;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLinks;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import com.google.android.collect.Lists;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Map;
import java.util.Optional;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ClipboardOverlayUtilsTest extends SysuiTestCase {

    private ClipboardOverlayUtils mClipboardUtils;
    @Mock
    private TextClassificationManager mTextClassificationManager;
    @Mock
    private TextClassifier mTextClassifier;

    @Mock
    private ClipData.Item mClipDataItem;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mTextClassificationManager.getTextClassifier()).thenReturn(mTextClassifier);
        mClipboardUtils = new ClipboardOverlayUtils(mTextClassificationManager);
    }

    @Test
    public void test_getAction_noLinks_returnsEmptyOptional() {
        Optional<RemoteAction> action =
                mClipboardUtils.getAction(Mockito.mock(TextLinks.class), "abc");

        assertTrue(action.isEmpty());
    }

    @Test
    public void test_getAction_returnsFirstLink() {
        TextLinks links = getFakeTextLinksBuilder().build();
        RemoteAction actionA = constructRemoteAction("abc");
        RemoteAction actionB = constructRemoteAction("def");
        TextClassification classificationA = Mockito.mock(TextClassification.class);
        when(classificationA.getActions()).thenReturn(Lists.newArrayList(actionA));
        TextClassification classificationB = Mockito.mock(TextClassification.class);
        when(classificationB.getActions()).thenReturn(Lists.newArrayList(actionB));
        when(mTextClassifier.classifyText(anyString(), anyInt(), anyInt(), isNull())).thenReturn(
                classificationA, classificationB);

        RemoteAction result = mClipboardUtils.getAction(links, "test").orElse(null);

        assertEquals(actionA, result);
    }

    @Test
    public void test_getAction_skipsMatchingComponent() {
        TextLinks links = getFakeTextLinksBuilder().build();
        RemoteAction actionA = constructRemoteAction("abc");
        RemoteAction actionB = constructRemoteAction("def");
        TextClassification classificationA = Mockito.mock(TextClassification.class);
        when(classificationA.getActions()).thenReturn(Lists.newArrayList(actionA));
        TextClassification classificationB = Mockito.mock(TextClassification.class);
        when(classificationB.getActions()).thenReturn(Lists.newArrayList(actionB));
        when(mTextClassifier.classifyText(anyString(), anyInt(), anyInt(), isNull())).thenReturn(
                classificationA, classificationB);

        RemoteAction result = mClipboardUtils.getAction(links, "abc").orElse(null);

        assertEquals(actionB, result);
    }

    @Test
    public void test_getAction_skipsShortEntity() {
        TextLinks.Builder textLinks = new TextLinks.Builder("test text of length 22");
        final Map<String, Float> scores = new ArrayMap<>();
        scores.put(TextClassifier.TYPE_EMAIL, 1f);
        textLinks.addLink(20, 22, scores);
        textLinks.addLink(0, 22, scores);

        RemoteAction actionA = constructRemoteAction("abc");
        RemoteAction actionB = constructRemoteAction("def");
        TextClassification classificationA = Mockito.mock(TextClassification.class);
        when(classificationA.getActions()).thenReturn(Lists.newArrayList(actionA));
        TextClassification classificationB = Mockito.mock(TextClassification.class);
        when(classificationB.getActions()).thenReturn(Lists.newArrayList(actionB));
        when(mTextClassifier.classifyText(anyString(), eq(20), eq(22), isNull())).thenReturn(
                classificationA);
        when(mTextClassifier.classifyText(anyString(), eq(0), eq(22), isNull())).thenReturn(
                classificationB);

        RemoteAction result = mClipboardUtils.getAction(textLinks.build(), "test").orElse(null);

        assertEquals(actionB, result);
    }

    // TODO(b/267162944): Next four tests (marked "legacy") are obsolete once
    //  CLIPBOARD_MINIMIZED_LAYOUT flag is released and removed
    @Test
    public void test_getAction_noLinks_returnsEmptyOptional_legacy() {
        ClipData.Item item = new ClipData.Item("no text links");
        item.setTextLinks(Mockito.mock(TextLinks.class));

        Optional<RemoteAction> action = mClipboardUtils.getAction(item, "");

        assertTrue(action.isEmpty());
    }

    @Test
    public void test_getAction_returnsFirstLink_legacy() {
        when(mClipDataItem.getTextLinks()).thenReturn(getFakeTextLinksBuilder().build());
        when(mClipDataItem.getText()).thenReturn("");
        RemoteAction actionA = constructRemoteAction("abc");
        RemoteAction actionB = constructRemoteAction("def");
        TextClassification classificationA = Mockito.mock(TextClassification.class);
        when(classificationA.getActions()).thenReturn(Lists.newArrayList(actionA));
        TextClassification classificationB = Mockito.mock(TextClassification.class);
        when(classificationB.getActions()).thenReturn(Lists.newArrayList(actionB));
        when(mTextClassifier.classifyText(anyString(), anyInt(), anyInt(), isNull())).thenReturn(
                classificationA, classificationB);

        RemoteAction result = mClipboardUtils.getAction(mClipDataItem, "test").orElse(null);

        assertEquals(actionA, result);
    }

    @Test
    public void test_getAction_skipsMatchingComponent_legacy() {
        when(mClipDataItem.getTextLinks()).thenReturn(getFakeTextLinksBuilder().build());
        when(mClipDataItem.getText()).thenReturn("");
        RemoteAction actionA = constructRemoteAction("abc");
        RemoteAction actionB = constructRemoteAction("def");
        TextClassification classificationA = Mockito.mock(TextClassification.class);
        when(classificationA.getActions()).thenReturn(Lists.newArrayList(actionA));
        TextClassification classificationB = Mockito.mock(TextClassification.class);
        when(classificationB.getActions()).thenReturn(Lists.newArrayList(actionB));
        when(mTextClassifier.classifyText(anyString(), anyInt(), anyInt(), isNull())).thenReturn(
                classificationA, classificationB);

        RemoteAction result = mClipboardUtils.getAction(mClipDataItem, "abc").orElse(null);

        assertEquals(actionB, result);
    }

    @Test
    public void test_getAction_skipsShortEntity_legacy() {
        TextLinks.Builder textLinks = new TextLinks.Builder("test text of length 22");
        final Map<String, Float> scores = new ArrayMap<>();
        scores.put(TextClassifier.TYPE_EMAIL, 1f);
        textLinks.addLink(20, 22, scores);
        textLinks.addLink(0, 22, scores);

        when(mClipDataItem.getTextLinks()).thenReturn(textLinks.build());
        when(mClipDataItem.getText()).thenReturn(textLinks.build().getText());

        RemoteAction actionA = constructRemoteAction("abc");
        RemoteAction actionB = constructRemoteAction("def");
        TextClassification classificationA = Mockito.mock(TextClassification.class);
        when(classificationA.getActions()).thenReturn(Lists.newArrayList(actionA));
        TextClassification classificationB = Mockito.mock(TextClassification.class);
        when(classificationB.getActions()).thenReturn(Lists.newArrayList(actionB));
        when(mTextClassifier.classifyText(anyString(), eq(20), eq(22), isNull())).thenReturn(
                classificationA);
        when(mTextClassifier.classifyText(anyString(), eq(0), eq(22), isNull())).thenReturn(
                classificationB);

        RemoteAction result = mClipboardUtils.getAction(mClipDataItem, "test").orElse(null);

        assertEquals(actionB, result);
    }

    @Test
    public void test_extra_withPackage_returnsTrue() {
        PersistableBundle b = new PersistableBundle();
        b.putBoolean(ClipDescription.EXTRA_IS_REMOTE_DEVICE, true);
        ClipData data = constructClipData(
                new String[]{"text/plain"}, new ClipData.Item("6175550000"), b);
        TestableResources res = mContext.getOrCreateTestableResources();
        res.addOverride(
                R.string.config_remoteCopyPackage, "com.android.remote/.RemoteActivity");

        assertTrue(mClipboardUtils.isRemoteCopy(mContext, data, "com.android.remote"));
    }

    @Test
    public void test_noExtra_returnsFalse() {
        ClipData data = constructClipData(
                new String[]{"text/plain"}, new ClipData.Item("6175550000"), null);
        TestableResources res = mContext.getOrCreateTestableResources();
        res.addOverride(
                R.string.config_remoteCopyPackage, "com.android.remote/.RemoteActivity");

        assertFalse(mClipboardUtils.isRemoteCopy(mContext, data, "com.android.remote"));
    }

    @Test
    public void test_falseExtra_returnsFalse() {
        PersistableBundle b = new PersistableBundle();
        b.putBoolean(ClipDescription.EXTRA_IS_REMOTE_DEVICE, false);
        ClipData data = constructClipData(
                new String[]{"text/plain"}, new ClipData.Item("6175550000"), b);
        TestableResources res = mContext.getOrCreateTestableResources();
        res.addOverride(
                R.string.config_remoteCopyPackage, "com.android.remote/.RemoteActivity");

        assertFalse(mClipboardUtils.isRemoteCopy(mContext, data, "com.android.remote"));
    }

    @Test
    public void test_wrongPackage_returnsFalse() {
        PersistableBundle b = new PersistableBundle();
        b.putBoolean(ClipDescription.EXTRA_IS_REMOTE_DEVICE, true);
        ClipData data = constructClipData(
                new String[]{"text/plain"}, new ClipData.Item("6175550000"), b);

        assertFalse(mClipboardUtils.isRemoteCopy(mContext, data, ""));
    }

    private static ClipData constructClipData(String[] mimeTypes, ClipData.Item item,
            PersistableBundle extras) {
        ClipDescription description = new ClipDescription("Test", mimeTypes);
        if (extras != null) {
            description.setExtras(extras);
        }
        return new ClipData(description, item);
    }

    private static RemoteAction constructRemoteAction(String packageName) {
        RemoteAction action = Mockito.mock(RemoteAction.class, Answers.RETURNS_DEEP_STUBS);
        when(action.getActionIntent().getIntent().getComponent().getPackageName())
                .thenReturn(packageName);
        return action;
    }

    private static TextLinks.Builder getFakeTextLinksBuilder() {
        TextLinks.Builder textLinks = new TextLinks.Builder("test text of length 22");
        final Map<String, Float> scores = new ArrayMap<>();
        scores.put(TextClassifier.TYPE_EMAIL, 1f);
        textLinks.addLink(0, 22, scores);
        textLinks.addLink(0, 22, scores);
        return textLinks;
    }
}
