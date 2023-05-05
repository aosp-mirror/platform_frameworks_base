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

package com.android.server.wm.flicker.activityembedding

import android.platform.test.annotations.Presubmit
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.FlickerTest
import android.tools.device.flicker.legacy.FlickerTestFactory
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.helpers.ActivityEmbeddingAppHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test launching a placeholder split over a normal split, both splits are configured in RTL.
 *
 * Setup: From A launch a split in RTL - resulting in B|A.
 * Transitions:
 * From A start PlaceholderPrimary, which is configured to launch with PlaceholderSecondary in RTL.
 * Expect split PlaceholderSecondary|PlaceholderPrimary covering split B|A.
 *
 * To run this test: `atest FlickerTests:RTLStartSecondaryWithPlaceholderTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RTLStartSecondaryWithPlaceholderTest(flicker: FlickerTest) :
  ActivityEmbeddingTestBase(flicker) {

  /** {@inheritDoc} */
  override val transition: FlickerBuilder.() -> Unit = {
    setup {
      tapl.setExpectedRotationCheckEnabled(false)
      testApp.launchViaIntent(wmHelper)
      testApp.launchSecondaryActivityRTL(wmHelper)
    }
    transitions {
      testApp.launchPlaceholderSplitRTL(wmHelper)
    }
    teardown {
      tapl.goHome()
      testApp.exit(wmHelper)
    }
  }

  /**
   * Main activity and Secondary activity will become invisible because they are covered by
   * PlaceholderPrimary activity and PlaceholderSecondary activity.
   */
  @Presubmit
  @Test
  fun assertWindowVisibilities() {
    flicker.assertWm {
      isAppWindowVisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
              .then()
              .isAppWindowInvisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
    }
    flicker.assertWm {
      isAppWindowVisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
              .then()
              .isAppWindowInvisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
    }
    flicker.assertWm {
      isAppWindowInvisible(ActivityEmbeddingAppHelper.PLACEHOLDER_PRIMARY_COMPONENT)
              .then()
              .isAppWindowVisible(ActivityEmbeddingAppHelper.PLACEHOLDER_PRIMARY_COMPONENT)
    }
    flicker.assertWm {
      isAppWindowInvisible(ActivityEmbeddingAppHelper.PLACEHOLDER_SECONDARY_COMPONENT)
              .then()
              .isAppWindowVisible(ActivityEmbeddingAppHelper.PLACEHOLDER_SECONDARY_COMPONENT)
    }
  }

  /**
   * Main activity and Secondary activity will become invisible because they are covered by
   * PlaceholderPrimary activity and PlaceholderSecondary activity.
   */
  @Presubmit
  @Test
  fun assertLayerVisibilities() {
    flicker.assertLayers {
      this.isVisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
              .then()
              .isInvisible(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
    }
    flicker.assertLayers {
      this.isVisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
              .then()
              .isInvisible(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
    }
    flicker.assertLayers {
      isInvisible(ActivityEmbeddingAppHelper.PLACEHOLDER_PRIMARY_COMPONENT)
              .then()
              .isVisible(ActivityEmbeddingAppHelper.PLACEHOLDER_PRIMARY_COMPONENT)
    }
    flicker.assertLayers {
      isInvisible(ActivityEmbeddingAppHelper.PLACEHOLDER_SECONDARY_COMPONENT)
              .then()
              .isVisible(ActivityEmbeddingAppHelper.PLACEHOLDER_SECONDARY_COMPONENT)
    }
  }

  /** Main activity and Secondary activity split is in right-to-left layout direction. */
  @Presubmit
  @Test
  fun assertWMRTLBeforeTransition() {
    flicker.assertWmStart {
      val mainActivityRegion =
              this.visibleRegion(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
      val secondaryActivityRegion =
              this.visibleRegion(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
      mainActivityRegion.notOverlaps(secondaryActivityRegion.region)
      // secondary activity is on the left, main activity is on the right.
      check { "isRTLBeforeTransition" }
              .that(mainActivityRegion.region.bounds.left)
              .isEqual(secondaryActivityRegion.region.bounds.right)
    }
  }

  /** Main activity and Secondary activity split is in right-to-left layout direction. */
  @Presubmit
  @Test
  fun assertLayerRTLBeforeTransition() {
    flicker.assertLayersStart {
      val mainActivityRegion =
              this.visibleRegion(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
      val secondaryActivityRegion =
              this.visibleRegion(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
      mainActivityRegion.notOverlaps(secondaryActivityRegion.region)
      // secondary activity is on the left, main activity is on the right.
      check { "isRTLBeforeTransition" }
              .that(mainActivityRegion.region.bounds.left)
              .isEqual(secondaryActivityRegion.region.bounds.right)
    }
  }

  /**
   * PlaceholderPrimary activity and PlaceholderSecondary activity split are in right-to-left
   * layout direction.
   */
  @Presubmit
  @Test
  fun assertWMRTLAfterTransition() {
    flicker.assertWmEnd {
      val mainActivityRegion =
              this.visibleRegion(ActivityEmbeddingAppHelper.MAIN_ACTIVITY_COMPONENT)
      val secondaryActivityRegion =
              this.visibleRegion(ActivityEmbeddingAppHelper.SECONDARY_ACTIVITY_COMPONENT)
      mainActivityRegion.notOverlaps(secondaryActivityRegion.region)
      // secondary activity is on the left, main activity is on the right.
      check { "isRTLBeforeTransition" }
              .that(mainActivityRegion.region.bounds.left)
              .isEqual(secondaryActivityRegion.region.bounds.right)
    }
  }

  /**
   * PlaceholderPrimary activity and PlaceholderSecondary activity split are in right-to-left
   * layout direction.
   */
  @Presubmit
  @Test
  fun assertLayerRTLAfterTransition() {
    flicker.assertLayersEnd {
      val mainActivityRegion =
              this.visibleRegion(ActivityEmbeddingAppHelper.PLACEHOLDER_PRIMARY_COMPONENT)
      val secondaryActivityRegion =
              this.visibleRegion(ActivityEmbeddingAppHelper.PLACEHOLDER_SECONDARY_COMPONENT)
      mainActivityRegion.notOverlaps(secondaryActivityRegion.region)
      // Placeholder secondary activity is on the left, placeholder primary activity is on the
      // right.
      check { "isRTLAfterTransition" }
              .that(mainActivityRegion.region.bounds.left)
              .isEqual(secondaryActivityRegion.region.bounds.right)
    }
  }

  companion object {
    /**
     * Creates the test configurations.
     *
     * See [FlickerTestFactory.nonRotationTests] for configuring screen orientation and
     * navigation modes.
     */
    @Parameterized.Parameters(name = "{0}")
    @JvmStatic
    fun getParams(): Collection<FlickerTest> {
      return FlickerTestFactory.nonRotationTests()
    }
  }
}
