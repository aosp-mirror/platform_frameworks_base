package com.android.server.location;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.Looper;
import android.platform.test.annotations.Presubmit;

import com.android.server.testing.FrameworkRobolectricTestRunner;
import com.android.server.testing.SystemLoaderPackages;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Unit tests for {@link GnssMeasurementsProvider}.
 */
@RunWith(FrameworkRobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 27
)
@SystemLoaderPackages({"com.android.server.location"})
@Presubmit
public class GnssMeasurementsProviderTest {
    @Mock
    private GnssMeasurementsProvider.GnssMeasurementProviderNative mMockNative;
    private GnssMeasurementsProvider mTestProvider;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockNative.startMeasurementCollection(anyBoolean())).thenReturn(true);
        when(mMockNative.stopMeasurementCollection()).thenReturn(true);

        mTestProvider = new GnssMeasurementsProvider(RuntimeEnvironment.application,
                new Handler(Looper.myLooper()), mMockNative) {
            @Override
            public boolean isGpsEnabled() {
                return true;
            }
        };
    }

    @Test
    public void register_nativeStarted() {
        mTestProvider.registerWithService();
        verify(mMockNative).startMeasurementCollection(anyBoolean());
    }

    @Test
    public void unregister_nativeStopped() {
        mTestProvider.registerWithService();
        mTestProvider.unregisterFromService();
        verify(mMockNative).stopMeasurementCollection();
    }

    @Test
    public void isSupported_nativeIsSupported() {
        when(mMockNative.isMeasurementSupported()).thenReturn(true);
        assertThat(mTestProvider.isAvailableInPlatform()).isTrue();

        when(mMockNative.isMeasurementSupported()).thenReturn(false);
        assertThat(mTestProvider.isAvailableInPlatform()).isFalse();
    }

    @Test
    public void register_resume_started() {
        mTestProvider.registerWithService();
        mTestProvider.resumeIfStarted();
        verify(mMockNative, times(2)).startMeasurementCollection(anyBoolean());
    }

    @Test
    public void unregister_resume_notStarted() {
        mTestProvider.registerWithService();
        mTestProvider.unregisterFromService();
        mTestProvider.resumeIfStarted();
        verify(mMockNative, times(1)).startMeasurementCollection(anyBoolean());
    }
}
