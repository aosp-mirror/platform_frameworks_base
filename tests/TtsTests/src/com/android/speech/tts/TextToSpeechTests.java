/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.speech.tts;

import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.test.InstrumentationTestCase;

import com.android.speech.tts.MockableTextToSpeechService.IDelegate;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.internal.stubbing.StubberImpl;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.mockito.stubbing.Stubber;
import junit.framework.Assert;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TextToSpeechTests extends InstrumentationTestCase {
    private static final String MOCK_ENGINE = "com.android.speech.tts";
    private static final String MOCK_PACKAGE = "com.android.speech.tts.__testpackage__";

    private TextToSpeech mTts;

    @Override
    public void setUp() throws Exception {
        IDelegate passThrough = Mockito.mock(IDelegate.class);
        MockableTextToSpeechService.setMocker(passThrough);

        // For the default voice selection
        Mockito.doReturn(TextToSpeech.LANG_COUNTRY_AVAILABLE).when(passThrough)
            .onIsLanguageAvailable(
                    Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
        Mockito.doReturn(TextToSpeech.LANG_COUNTRY_AVAILABLE).when(passThrough)
            .onLoadLanguage(
                    Mockito.anyString(), Mockito.anyString(), Mockito.anyString());

        blockingInitAndVerify(MOCK_ENGINE, TextToSpeech.SUCCESS);
        assertEquals(MOCK_ENGINE, mTts.getCurrentEngine());
    }

    @Override
    public void tearDown() {
        if (mTts != null) {
            mTts.shutdown();
        }
    }

    public void testEngineInitialized() throws Exception {
        // Fail on an engine that doesn't exist.
        blockingInitAndVerify("__DOES_NOT_EXIST__", TextToSpeech.ERROR);

        // Also, the "current engine" must be null
        assertNull(mTts.getCurrentEngine());
    }

    public void testSetLanguage_delegation() {
        IDelegate delegate = Mockito.mock(IDelegate.class);
        MockableTextToSpeechService.setMocker(delegate);

        Mockito.doReturn(TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE).when(delegate).onIsLanguageAvailable(
                "eng", "USA", "variant");
        Mockito.doReturn(TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE).when(delegate).onLoadLanguage(
                "eng", "USA", "variant");

        // Test 1 :Tests that calls to onLoadLanguage( ) are delegated through to the
        // service without any caching or intermediate steps.
        assertEquals(TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE, mTts.setLanguage(new Locale("eng", "USA", "variant")));
        Mockito.verify(delegate, Mockito.atLeast(0)).onIsLanguageAvailable(
            "eng", "USA", "variant");
        Mockito.verify(delegate, Mockito.atLeast(0)).onLoadLanguage(
            "eng", "USA", "variant");
    }

    public void testSetLanguage_availableLanguage() throws Exception {
        IDelegate delegate = Mockito.mock(IDelegate.class);
        MockableTextToSpeechService.setMocker(delegate);

        // ---------------------------------------------------------
        // Test 2 : Tests that when the language is successfully set
        // like above (returns LANG_COUNTRY_AVAILABLE). That the
        // request language changes from that point on.
        Mockito.doReturn(TextToSpeech.LANG_COUNTRY_AVAILABLE).when(delegate).onIsLanguageAvailable(
                "eng", "USA", "variant");
        Mockito.doReturn(TextToSpeech.LANG_COUNTRY_AVAILABLE).when(delegate).onIsLanguageAvailable(
                "eng", "USA", "");
        Mockito.doReturn(TextToSpeech.LANG_COUNTRY_AVAILABLE).when(delegate).onLoadLanguage(
                "eng", "USA", "");
        mTts.setLanguage(new Locale("eng", "USA", "variant"));
        blockingCallSpeak("foo bar", delegate);
        ArgumentCaptor<SynthesisRequest> req = ArgumentCaptor.forClass(SynthesisRequest.class);
        Mockito.verify(delegate, Mockito.times(1)).onSynthesizeText(req.capture(),
                Mockito.<SynthesisCallback>anyObject());

        assertEquals("eng", req.getValue().getLanguage());
        assertEquals("USA", req.getValue().getCountry());
        assertEquals("", req.getValue().getVariant());
        assertEquals("en-US", req.getValue().getVoiceName());
    }

    public void testSetLanguage_unavailableLanguage() throws Exception {
        IDelegate delegate = Mockito.mock(IDelegate.class);
        MockableTextToSpeechService.setMocker(delegate);

        // ---------------------------------------------------------
        // TEST 3 : Tests that the language that is set does not change when the
        // engine reports it could not load the specified language.
        Mockito.doReturn(TextToSpeech.LANG_NOT_SUPPORTED).when(
                delegate).onIsLanguageAvailable("fra", "FRA", "");
        Mockito.doReturn(TextToSpeech.LANG_NOT_SUPPORTED).when(
                delegate).onLoadLanguage("fra", "FRA", "");
        mTts.setLanguage(Locale.FRANCE);
        blockingCallSpeak("le fou barre", delegate);
        ArgumentCaptor<SynthesisRequest> req2 = ArgumentCaptor.forClass(SynthesisRequest.class);
        Mockito.verify(delegate, Mockito.times(1)).onSynthesizeText(req2.capture(),
                        Mockito.<SynthesisCallback>anyObject());

        // The params are basically unchanged.
        assertEquals("eng", req2.getValue().getLanguage());
        assertEquals("USA", req2.getValue().getCountry());
        assertEquals("", req2.getValue().getVariant());
        assertEquals("en-US", req2.getValue().getVoiceName());
    }

    public void testIsLanguageAvailable() {
        IDelegate delegate = Mockito.mock(IDelegate.class);
        MockableTextToSpeechService.setMocker(delegate);

        // Test1: Simple end to end test.
        Mockito.doReturn(TextToSpeech.LANG_COUNTRY_AVAILABLE).when(
                delegate).onIsLanguageAvailable("eng", "USA", "");

        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, mTts.isLanguageAvailable(Locale.US));
        Mockito.verify(delegate, Mockito.times(1)).onIsLanguageAvailable(
                "eng", "USA", "");
    }

    public void testDefaultLanguage_setsVoiceName() throws Exception {
        IDelegate delegate = Mockito.mock(IDelegate.class);
        MockableTextToSpeechService.setMocker(delegate);
        Locale defaultLocale = Locale.getDefault();

        // ---------------------------------------------------------
        // Test that default language also sets the default voice
        // name
        Mockito.doReturn(TextToSpeech.LANG_COUNTRY_AVAILABLE).
            when(delegate).onIsLanguageAvailable(
                defaultLocale.getISO3Language(),
                defaultLocale.getISO3Country().toUpperCase(),
                defaultLocale.getVariant());
        Mockito.doReturn(TextToSpeech.LANG_COUNTRY_AVAILABLE).
            when(delegate).onLoadLanguage(
                defaultLocale.getISO3Language(),
                defaultLocale.getISO3Country(),
                defaultLocale.getVariant());

        blockingCallSpeak("foo bar", delegate);
        ArgumentCaptor<SynthesisRequest> req = ArgumentCaptor.forClass(SynthesisRequest.class);
        Mockito.verify(delegate, Mockito.times(1)).onSynthesizeText(req.capture(),
                Mockito.<SynthesisCallback>anyObject());

        assertEquals(defaultLocale.getISO3Language(), req.getValue().getLanguage());
        assertEquals(defaultLocale.getISO3Country(), req.getValue().getCountry());
        assertEquals("", req.getValue().getVariant());
        assertEquals(defaultLocale.toLanguageTag(), req.getValue().getVoiceName());
    }


    private void blockingCallSpeak(String speech, IDelegate mock) throws
            InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        doCountDown(latch).when(mock).onSynthesizeText(Mockito.<SynthesisRequest>anyObject(),
                Mockito.<SynthesisCallback>anyObject());
        mTts.speak(speech, TextToSpeech.QUEUE_ADD, null);

        awaitCountDown(latch, 5, TimeUnit.SECONDS);
    }

    private void blockingInitAndVerify(final String engine, int errorCode) throws
            InterruptedException {
        TextToSpeech.OnInitListener listener = Mockito.mock(
                TextToSpeech.OnInitListener.class);

        final CountDownLatch latch = new CountDownLatch(1);
        doCountDown(latch).when(listener).onInit(errorCode);

        mTts = new TextToSpeech(getInstrumentation().getTargetContext(),
                listener, engine, MOCK_PACKAGE, false /* use fallback package */);

        awaitCountDown(latch, 5, TimeUnit.SECONDS);
    }

    public static abstract class CountDownBehaviour extends StubberImpl {
        /** Used to mock methods that return a result. */
        public abstract Stubber andReturn(Object result);
    }

    public static CountDownBehaviour doCountDown(final CountDownLatch latch) {
        return new CountDownBehaviour() {
            @Override
            public <T> T when(T mock) {
                return Mockito.doAnswer(new Answer<Void>() {
                    @Override
                    public Void answer(InvocationOnMock invocation) throws Exception {
                        latch.countDown();
                        return null;
                    }
                }).when(mock);
            }

            @Override
            public Stubber andReturn(final Object result) {
                return new StubberImpl() {
                    @Override
                    public <T> T when(T mock) {
                        return Mockito.doAnswer(new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock invocation) throws Exception {
                                latch.countDown();
                                return result;
                            }
                        }).when(mock);
                    }
                };
            }
        };
    }

    public static void awaitCountDown(CountDownLatch latch, long timeout, TimeUnit unit)
            throws InterruptedException {
        Assert.assertTrue("Waited too long for method call", latch.await(timeout, unit));
    }
}
