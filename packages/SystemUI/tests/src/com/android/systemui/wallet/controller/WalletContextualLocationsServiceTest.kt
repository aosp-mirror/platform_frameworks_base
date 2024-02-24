package com.android.systemui.wallet.controller

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Looper
import android.service.quickaccesswallet.WalletCard
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.anySet
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(JUnit4::class)
@SmallTest
@kotlinx.coroutines.ExperimentalCoroutinesApi
class WalletContextualLocationsServiceTest : SysuiTestCase() {
    @Mock private lateinit var controller: WalletContextualSuggestionsController
    private var featureFlags = FakeFeatureFlags()
    private lateinit var underTest: WalletContextualLocationsService
    private lateinit var testScope: TestScope
    private lateinit var testDispatcher: CoroutineDispatcher
    private var listenerRegisteredCount: Int = 0
    private val listener: IWalletCardsUpdatedListener.Stub =
        object : IWalletCardsUpdatedListener.Stub() {
            override fun registerNewWalletCards(cards: List<WalletCard?>) {
                listenerRegisteredCount++
            }
        }

    @Before
    @kotlinx.coroutines.ExperimentalCoroutinesApi
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        doReturn(fakeWalletCards).whenever(controller).allWalletCards
        doNothing().whenever(controller).setSuggestionCardIds(anySet())

        if (Looper.myLooper() == null) Looper.prepare()
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        featureFlags.set(Flags.ENABLE_WALLET_CONTEXTUAL_LOYALTY_CARDS, true)
        listenerRegisteredCount = 0
        underTest =
            WalletContextualLocationsService(
                testDispatcher,
                controller,
                featureFlags,
                testScope.backgroundScope
            )
    }

    @Test
    @kotlinx.coroutines.ExperimentalCoroutinesApi
    fun addListener() =
        testScope.runTest {
            underTest.addWalletCardsUpdatedListenerInternal(listener)
            assertThat(listenerRegisteredCount).isEqualTo(1)
        }

    @Test
    @kotlinx.coroutines.ExperimentalCoroutinesApi
    fun addStoreLocations() =
        testScope.runTest {
            underTest.onWalletContextualLocationsStateUpdatedInternal(ArrayList<String>())
            verify(controller, times(1)).setSuggestionCardIds(anySet())
        }

    @Test
    @kotlinx.coroutines.ExperimentalCoroutinesApi
    fun updateListenerAndLocationsState() =
        testScope.runTest {
            // binds to the service and adds a listener
            val underTestStub = getInterface
            underTestStub.addWalletCardsUpdatedListener(listener)
            assertThat(listenerRegisteredCount).isEqualTo(1)

            // sends a list of card IDs to the controller
            underTestStub.onWalletContextualLocationsStateUpdated(ArrayList<String>())
            verify(controller, times(1)).setSuggestionCardIds(anySet())

            // adds another listener
            fakeWalletCards.update { updatedFakeWalletCards }
            runCurrent()
            assertThat(listenerRegisteredCount).isEqualTo(2)

            // sends another list of card IDs to the controller
            underTestStub.onWalletContextualLocationsStateUpdated(ArrayList<String>())
            verify(controller, times(2)).setSuggestionCardIds(anySet())
        }

    private val fakeWalletCards: MutableStateFlow<List<WalletCard>>
        get() {
            val intent = Intent(getContext(), WalletContextualLocationsService::class.java)
            val pi: PendingIntent =
                PendingIntent.getActivity(getContext(), 0, intent, PendingIntent.FLAG_IMMUTABLE)
            val icon: Icon =
                Icon.createWithBitmap(Bitmap.createBitmap(70, 50, Bitmap.Config.ARGB_8888))
            val walletCards: ArrayList<WalletCard> = ArrayList<WalletCard>()
            walletCards.add(WalletCard.Builder("card1", icon, "card", pi).build())
            walletCards.add(WalletCard.Builder("card2", icon, "card", pi).build())
            return MutableStateFlow<List<WalletCard>>(walletCards)
        }

    private val updatedFakeWalletCards: List<WalletCard>
        get() {
            val intent = Intent(getContext(), WalletContextualLocationsService::class.java)
            val pi: PendingIntent =
                PendingIntent.getActivity(getContext(), 0, intent, PendingIntent.FLAG_IMMUTABLE)
            val icon: Icon =
                Icon.createWithBitmap(Bitmap.createBitmap(70, 50, Bitmap.Config.ARGB_8888))
            val walletCards: ArrayList<WalletCard> = ArrayList<WalletCard>()
            walletCards.add(WalletCard.Builder("card3", icon, "card", pi).build())
            return walletCards
        }

    private val getInterface: IWalletContextualLocationsService
        get() {
            val intent = Intent()
            return IWalletContextualLocationsService.Stub.asInterface(underTest.onBind(intent))
        }
}
