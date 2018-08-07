package com.android.server.location;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Looper;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;

import com.android.server.testing.FrameworkRobolectricTestRunner;
import com.android.server.testing.SystemLoaderPackages;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.Collection;
import java.util.List;

/**
 * Unit tests for {@link GnssSatelliteBlacklistHelper}.
 */
@RunWith(FrameworkRobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        shadows = {
        },
        sdk = 27
)
@SystemLoaderPackages({"com.android.server.location"})
@Presubmit
public class GnssSatelliteBlacklistHelperTest {

    private Context mContext;
    private ContentResolver mContentResolver;
    @Mock
    private GnssSatelliteBlacklistHelper.GnssSatelliteBlacklistCallback mCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        mContentResolver = mContext.getContentResolver();
        new GnssSatelliteBlacklistHelper(mContext, Looper.myLooper(), mCallback);
    }

    @Test
    public void blacklistOf2Satellites_callbackIsCalled() {
        String blacklist = "3,0,5,24";
        updateBlacklistAndVerifyCallbackIsCalled(blacklist);
    }

    @Test
    public void blacklistWithSpaces_callbackIsCalled() {
        String blacklist = "3, 11";
        updateBlacklistAndVerifyCallbackIsCalled(blacklist);
    }

    @Test
    public void emptyBlacklist_callbackIsCalled() {
        String blacklist = "";
        updateBlacklistAndVerifyCallbackIsCalled(blacklist);
    }

    @Test
    public void blacklistWithOddNumberOfValues_callbackIsNotCalled() {
        String blacklist = "3,0,5";
        updateBlacklistAndNotifyContentObserver(blacklist);
        verify(mCallback, never()).onUpdateSatelliteBlacklist(any(int[].class), any(int[].class));
    }

    @Test
    public void blacklistWithNegativeValue_callbackIsNotCalled() {
        String blacklist = "3,-11";
        updateBlacklistAndNotifyContentObserver(blacklist);
        verify(mCallback, never()).onUpdateSatelliteBlacklist(any(int[].class), any(int[].class));
    }

    @Test
    public void blacklistWithNonDigitCharacter_callbackIsNotCalled() {
        String blacklist = "3,1a,5,11";
        updateBlacklistAndNotifyContentObserver(blacklist);
        verify(mCallback, never()).onUpdateSatelliteBlacklist(any(int[].class), any(int[].class));
    }

    private void updateBlacklistAndNotifyContentObserver(String blacklist) {
        Settings.Global.putString(mContentResolver,
                Settings.Global.GNSS_SATELLITE_BLACKLIST, blacklist);
        notifyContentObserverFor(Settings.Global.GNSS_SATELLITE_BLACKLIST);
    }

    private void updateBlacklistAndVerifyCallbackIsCalled(String blacklist) {
        updateBlacklistAndNotifyContentObserver(blacklist);

        ArgumentCaptor<int[]> constellationsCaptor = ArgumentCaptor.forClass(int[].class);
        ArgumentCaptor<int[]> svIdsCaptor = ArgumentCaptor.forClass(int[].class);
        verify(mCallback).onUpdateSatelliteBlacklist(constellationsCaptor.capture(),
                svIdsCaptor.capture());

        int[] constellations = constellationsCaptor.getValue();
        int[] svIds = svIdsCaptor.getValue();
        List<Integer> values = GnssSatelliteBlacklistHelper.parseSatelliteBlacklist(blacklist);
        assertThat(values.size()).isEqualTo(constellations.length * 2);
        assertThat(svIds.length).isEqualTo(constellations.length);
        for (int i = 0; i < constellations.length; i++) {
            assertThat(constellations[i]).isEqualTo(values.get(i * 2));
            assertThat(svIds[i]).isEqualTo(values.get(i * 2 + 1));
        }
    }

    private static void notifyContentObserverFor(String globalSetting) {
        Collection<ContentObserver> contentObservers =
                Shadows.shadowOf(RuntimeEnvironment.application.getContentResolver())
                        .getContentObservers(Settings.Global.getUriFor(globalSetting));
        assertThat(contentObservers).isNotEmpty();
        contentObservers.iterator().next().onChange(false /* selfChange */);
    }
}
