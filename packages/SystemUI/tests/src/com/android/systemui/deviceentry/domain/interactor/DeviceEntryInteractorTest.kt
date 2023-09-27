package com.android.systemui.deviceentry.domain.interactor

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.FakeDeviceEntryRepository
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class DeviceEntryInteractorTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val repository: FakeDeviceEntryRepository = utils.deviceEntryRepository
    private val sceneInteractor = utils.sceneInteractor()
    private val authenticationInteractor = utils.authenticationInteractor()
    private val underTest =
        utils.deviceEntryInteractor(
            repository = repository,
            authenticationInteractor = authenticationInteractor,
            sceneInteractor = sceneInteractor,
        )

    @Test
    fun isUnlocked_whenAuthMethodIsNoneAndLockscreenDisabled_isTrue() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            utils.deviceEntryRepository.apply {
                setInsecureLockscreenEnabled(false)

                // Toggle isUnlocked, twice.
                //
                // This is done because the underTest.isUnlocked flow doesn't receive values from
                // just changing the state above; the actual isUnlocked state needs to change to
                // cause the logic under test to "pick up" the current state again.
                //
                // It is done twice to make sure that we don't actually change the isUnlocked state
                // from what it originally was.
                setUnlocked(!isUnlocked.value)
                runCurrent()
                setUnlocked(!isUnlocked.value)
                runCurrent()
            }

            val isUnlocked by collectLastValue(underTest.isUnlocked)
            assertThat(isUnlocked).isTrue()
        }

    @Test
    fun isUnlocked_whenAuthMethodIsNoneAndLockscreenEnabled_isTrue() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            utils.deviceEntryRepository.setInsecureLockscreenEnabled(true)

            val isUnlocked by collectLastValue(underTest.isUnlocked)
            assertThat(isUnlocked).isTrue()
        }

    @Test
    fun isDeviceEntered_onLockscreenWithSwipe_isFalse() =
        testScope.runTest {
            val isDeviceEntered by collectLastValue(underTest.isDeviceEntered)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            utils.deviceEntryRepository.setInsecureLockscreenEnabled(true)
            switchToScene(SceneKey.Lockscreen)

            assertThat(isDeviceEntered).isFalse()
        }

    @Test
    fun isDeviceEntered_onShadeBeforeDismissingLockscreenWithSwipe_isFalse() =
        testScope.runTest {
            val isDeviceEntered by collectLastValue(underTest.isDeviceEntered)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            utils.deviceEntryRepository.setInsecureLockscreenEnabled(true)
            switchToScene(SceneKey.Lockscreen)
            runCurrent()
            switchToScene(SceneKey.Shade)

            assertThat(isDeviceEntered).isFalse()
        }

    @Test
    fun isDeviceEntered_afterDismissingLockscreenWithSwipe_isTrue() =
        testScope.runTest {
            val isDeviceEntered by collectLastValue(underTest.isDeviceEntered)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            utils.deviceEntryRepository.setInsecureLockscreenEnabled(true)
            switchToScene(SceneKey.Lockscreen)
            runCurrent()
            switchToScene(SceneKey.Gone)

            assertThat(isDeviceEntered).isTrue()
        }

    @Test
    fun isDeviceEntered_onShadeAfterDismissingLockscreenWithSwipe_isTrue() =
        testScope.runTest {
            val isDeviceEntered by collectLastValue(underTest.isDeviceEntered)
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            utils.deviceEntryRepository.setInsecureLockscreenEnabled(true)
            switchToScene(SceneKey.Lockscreen)
            runCurrent()
            switchToScene(SceneKey.Gone)
            runCurrent()
            switchToScene(SceneKey.Shade)

            assertThat(isDeviceEntered).isTrue()
        }

    @Test
    fun isDeviceEntered_onBouncer_isFalse() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern
            )
            utils.deviceEntryRepository.setInsecureLockscreenEnabled(true)
            switchToScene(SceneKey.Lockscreen)
            runCurrent()
            switchToScene(SceneKey.Bouncer)

            val isDeviceEntered by collectLastValue(underTest.isDeviceEntered)
            assertThat(isDeviceEntered).isFalse()
        }

    @Test
    fun canSwipeToEnter_onLockscreenWithSwipe_isTrue() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            utils.deviceEntryRepository.setInsecureLockscreenEnabled(true)
            switchToScene(SceneKey.Lockscreen)

            val canSwipeToEnter by collectLastValue(underTest.canSwipeToEnter)
            assertThat(canSwipeToEnter).isTrue()
        }

    @Test
    fun canSwipeToEnter_onLockscreenWithPin_isFalse() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            utils.deviceEntryRepository.setInsecureLockscreenEnabled(true)
            switchToScene(SceneKey.Lockscreen)

            val canSwipeToEnter by collectLastValue(underTest.canSwipeToEnter)
            assertThat(canSwipeToEnter).isFalse()
        }

    @Test
    fun canSwipeToEnter_afterLockscreenDismissedInSwipeMode_isFalse() =
        testScope.runTest {
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
            utils.deviceEntryRepository.setInsecureLockscreenEnabled(true)
            switchToScene(SceneKey.Lockscreen)
            runCurrent()
            switchToScene(SceneKey.Gone)

            val canSwipeToEnter by collectLastValue(underTest.canSwipeToEnter)
            assertThat(canSwipeToEnter).isFalse()
        }

    @Test
    fun isAuthenticationRequired_lockedAndSecured_true() =
        testScope.runTest {
            utils.deviceEntryRepository.setUnlocked(false)
            runCurrent()
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )

            assertThat(underTest.isAuthenticationRequired()).isTrue()
        }

    @Test
    fun isAuthenticationRequired_lockedAndNotSecured_false() =
        testScope.runTest {
            utils.deviceEntryRepository.setUnlocked(false)
            runCurrent()
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)

            assertThat(underTest.isAuthenticationRequired()).isFalse()
        }

    @Test
    fun isAuthenticationRequired_unlockedAndSecured_false() =
        testScope.runTest {
            utils.deviceEntryRepository.setUnlocked(true)
            runCurrent()
            utils.authenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )

            assertThat(underTest.isAuthenticationRequired()).isFalse()
        }

    @Test
    fun isAuthenticationRequired_unlockedAndNotSecured_false() =
        testScope.runTest {
            utils.deviceEntryRepository.setUnlocked(true)
            runCurrent()
            utils.authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)

            assertThat(underTest.isAuthenticationRequired()).isFalse()
        }

    @Test
    fun isBypassEnabled_enabledInRepository_true() =
        testScope.runTest {
            utils.deviceEntryRepository.setBypassEnabled(true)
            assertThat(underTest.isBypassEnabled()).isTrue()
        }

    @Test
    fun isBypassEnabled_disabledInRepository_false() =
        testScope.runTest {
            utils.deviceEntryRepository.setBypassEnabled(false)
            assertThat(underTest.isBypassEnabled()).isFalse()
        }

    private fun switchToScene(sceneKey: SceneKey) {
        sceneInteractor.changeScene(SceneModel(sceneKey), "reason")
    }
}
