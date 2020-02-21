/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.view;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link CutoutSpecification} used by {@link DisplayCutout}.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:CutoutSpecificationTest
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class CutoutSpecificationTest {
    private static final String WITHOUT_BIND_CUTOUT_SPECIFICATION = "M 0,0\n"
            + "h 48\n"
            + "v 48\n"
            + "h -48\n"
            + "z\n"
            + "@left\n"
            + "@center_vertical\n"
            + "M 0,0\n"
            + "h 48\n"
            + "v 48\n"
            + "h -48\n"
            + "z\n"
            + "@left\n"
            + "@center_vertical\n"
            + "M 0,0\n"
            + "h -48\n"
            + "v 48\n"
            + "h 48\n"
            + "z\n"
            + "@right\n"
            + "@dp";
    private static final String WITH_BIND_CUTOUT_SPECIFICATION = "M 0,0\n"
            + "h 48\n"
            + "v 48\n"
            + "h -48\n"
            + "z\n"
            + "@left\n"
            + "@center_vertical\n"
            + "M 0,0\n"
            + "h 48\n"
            + "v 48\n"
            + "h -48\n"
            + "z\n"
            + "@left\n"
            + "@bind_left_cutout\n"
            + "@center_vertical\n"
            + "M 0,0\n"
            + "h -48\n"
            + "v 48\n"
            + "h 48\n"
            + "z\n"
            + "@right\n"
            + "@bind_right_cutout\n"
            + "@bottom\n"
            + "M 0,0\n"
            + "h -24\n"
            + "v -48\n"
            + "h 48\n"
            + "v 48\n"
            + "z\n"
            + "@dp";
    private static final String CORNER_CUTOUT_SPECIFICATION = "M 0,0\n"
            + "h 1\n"
            + "v 1\n"
            + "h -1\n"
            + "z\n"
            + "@left\n"
            + "@cutout\n"
            + "M 0, 0\n"
            + "h -2\n"
            + "v 2\n"
            + "h 2\n"
            + "z\n"
            + "@right\n"
            + "@bind_right_cutout\n"
            + "@cutout\n"
            + "M 0, 200\n"
            + "h 3\n"
            + "v -3\n"
            + "h -3\n"
            + "z\n"
            + "@left\n"
            + "@bind_left_cutout\n"
            + "@bottom\n"
            + "M 0, 0\n"
            + "h -4\n"
            + "v -4\n"
            + "h 4\n"
            + "z\n"
            + "@right\n"
            + "@dp";

    private CutoutSpecification.Parser mParser;

    /**
     * Setup the necessary member field used by test methods.
     */
    @Before
    public void setUp() {
        mParser = new CutoutSpecification.Parser(3.5f, 1080, 1920);
    }

    @Test
    public void parse_nullString_shouldTriggerException() {
        assertThrows(NullPointerException.class, () -> mParser.parse(null));
    }

    @Test
    public void parse_emptyString_pathShouldBeNull() {
        CutoutSpecification cutoutSpecification = mParser.parse("");
        assertThat(cutoutSpecification.getPath()).isNull();
    }

    @Test
    public void parse_withoutBindMarker_shouldHaveNoLeftBound() {
        CutoutSpecification cutoutSpecification = mParser.parse(WITHOUT_BIND_CUTOUT_SPECIFICATION);
        assertThat(cutoutSpecification.getLeftBound()).isNull();
    }

    @Test
    public void parse_withoutBindMarker_shouldHaveNoRightBound() {
        CutoutSpecification cutoutSpecification = mParser.parse(WITHOUT_BIND_CUTOUT_SPECIFICATION);
        assertThat(cutoutSpecification.getRightBound()).isNull();
    }

    @Test
    public void parse_withBindMarker_shouldHaveLeftBound() {
        CutoutSpecification cutoutSpecification = mParser.parse(WITH_BIND_CUTOUT_SPECIFICATION);
        assertThat(cutoutSpecification.getLeftBound()).isEqualTo(new Rect(0, 960, 168, 1128));
    }

    @Test
    public void parse_withBindMarker_shouldHaveTopBound() {
        CutoutSpecification cutoutSpecification = mParser.parse(WITH_BIND_CUTOUT_SPECIFICATION);
        assertThat(cutoutSpecification.getTopBound()).isEqualTo(new Rect(0, 0, 168, 168));
    }

    @Test
    public void parse_withBindMarker_shouldHaveRightBound() {
        CutoutSpecification cutoutSpecification = mParser.parse(WITH_BIND_CUTOUT_SPECIFICATION);
        assertThat(cutoutSpecification.getRightBound()).isEqualTo(new Rect(912, 960, 1080, 1128));
    }

    @Test
    public void parse_withBindMarker_shouldHaveBottomBound() {
        CutoutSpecification cutoutSpecification = mParser.parse(WITH_BIND_CUTOUT_SPECIFICATION);
        assertThat(cutoutSpecification.getBottomBound()).isEqualTo(new Rect(456, 1752, 624, 1920));
    }

    @Test
    public void parse_withBindMarker_shouldMatchExpectedSafeInset() {
        CutoutSpecification cutoutSpecification = mParser.parse(WITH_BIND_CUTOUT_SPECIFICATION);
        assertThat(cutoutSpecification.getSafeInset()).isEqualTo(new Rect(168, 168, 168, 168));
    }

    @Test
    public void parse_withBindMarker_tabletLikeDevice_shouldHaveLeftBound() {
        CutoutSpecification cutoutSpecification = new CutoutSpecification.Parser(3.5f, 1920, 1080)
                .parse(WITH_BIND_CUTOUT_SPECIFICATION);
        assertThat(cutoutSpecification.getLeftBound()).isEqualTo(new Rect(0, 540, 168, 708));
    }

    @Test
    public void parse_withBindMarker_tabletLikeDevice_shouldHaveTopBound() {
        CutoutSpecification cutoutSpecification = new CutoutSpecification.Parser(3.5f, 1920, 1080)
                .parse(WITH_BIND_CUTOUT_SPECIFICATION);
        assertThat(cutoutSpecification.getTopBound()).isEqualTo(new Rect(0, 0, 168, 168));
    }

    @Test
    public void parse_withBindMarker_tabletLikeDevice_shouldHaveRightBound() {
        CutoutSpecification cutoutSpecification = new CutoutSpecification.Parser(3.5f, 1920, 1080)
                .parse(WITH_BIND_CUTOUT_SPECIFICATION);
        assertThat(cutoutSpecification.getRightBound()).isEqualTo(new Rect(1752, 540, 1920, 708));
    }

    @Test
    public void parse_withBindMarker_tabletLikeDevice_shouldHaveBottomBound() {
        CutoutSpecification cutoutSpecification = new CutoutSpecification.Parser(3.5f, 1920, 1080)
                .parse(WITH_BIND_CUTOUT_SPECIFICATION);
        assertThat(cutoutSpecification.getBottomBound()).isEqualTo(new Rect(876, 912, 1044, 1080));
    }

    @Test
    public void parse_withBindMarker_tabletLikeDevice_shouldMatchExpectedSafeInset() {
        CutoutSpecification cutoutSpecification = new CutoutSpecification.Parser(3.5f, 1920, 1080)
                .parse(WITH_BIND_CUTOUT_SPECIFICATION);
        assertThat(cutoutSpecification.getSafeInset()).isEqualTo(new Rect(168, 0, 168, 168));
    }

    @Test
    public void parse_tallCutout_topBoundShouldMatchExpectedHeight() {
        CutoutSpecification cutoutSpecification = mParser.parse("M 0,0\n"
                + "L -48, 0\n"
                + "L -44.3940446283, 36.0595537175\n"
                + "C -43.5582133885, 44.4178661152 -39.6, 48.0 -31.2, 48.0\n"
                + "L 31.2, 48.0\n"
                + "C 39.6, 48.0 43.5582133885, 44.4178661152 44.3940446283, 36.0595537175\n"
                + "L 48, 0\n"
                + "Z\n"
                + "@dp");

        assertThat(cutoutSpecification.getTopBound().height()).isEqualTo(168);
    }

    @Test
    public void parse_wideCutout_topBoundShouldMatchExpectedWidth() {
        CutoutSpecification cutoutSpecification = mParser.parse("M 0,0\n"
                + "L -72, 0\n"
                + "L -69.9940446283, 20.0595537175\n"
                + "C -69.1582133885, 28.4178661152 -65.2, 32.0 -56.8, 32.0\n"
                + "L 56.8, 32.0\n"
                + "C 65.2, 32.0 69.1582133885, 28.4178661152 69.9940446283, 20.0595537175\n"
                + "L 72, 0\n"
                + "Z\n"
                + "@dp");

        assertThat(cutoutSpecification.getTopBound().width()).isEqualTo(504);
    }

    @Test
    public void parse_narrowCutout_topBoundShouldHaveExpectedWidth() {
        CutoutSpecification cutoutSpecification = mParser.parse("M 0,0\n"
                + "L -24, 0\n"
                + "L -21.9940446283, 20.0595537175\n"
                + "C -21.1582133885, 28.4178661152 -17.2, 32.0 -8.8, 32.0\n"
                + "L 8.8, 32.0\n"
                + "C 17.2, 32.0 21.1582133885, 28.4178661152 21.9940446283, 20.0595537175\n"
                + "L 24, 0\n"
                + "Z\n"
                + "@dp");

        assertThat(cutoutSpecification.getTopBound().width()).isEqualTo(168);
    }

    @Test
    public void parse_doubleCutout_topBoundShouldHaveExpectedHeight() {
        CutoutSpecification cutoutSpecification = mParser.parse("M 0,0\n"
                + "L -72, 0\n"
                + "L -69.9940446283, 20.0595537175\n"
                + "C -69.1582133885, 28.4178661152 -65.2, 32.0 -56.8, 32.0\n"
                + "L 56.8, 32.0\n"
                + "C 65.2, 32.0 69.1582133885, 28.4178661152 69.9940446283, 20.0595537175\n"
                + "L 72, 0\n"
                + "Z\n"
                + "@bottom\n"
                + "M 0,0\n"
                + "L -72, 0\n"
                + "L -69.9940446283, -20.0595537175\n"
                + "C -69.1582133885, -28.4178661152 -65.2, -32.0 -56.8, -32.0\n"
                + "L 56.8, -32.0\n"
                + "C 65.2, -32.0 69.1582133885, -28.4178661152 69.9940446283, -20"
                + ".0595537175\n"
                + "L 72, 0\n"
                + "Z\n"
                + "@dp");

        assertThat(cutoutSpecification.getTopBound().height()).isEqualTo(112);
    }

    @Test
    public void parse_cornerCutout_topBoundShouldHaveExpectedHeight() {
        CutoutSpecification cutoutSpecification = mParser.parse("M 0,0\n"
                + "L -48, 0\n"
                + "C -48,48 -48,48 0,48\n"
                + "Z\n"
                + "@dp\n"
                + "@right");

        assertThat(cutoutSpecification.getTopBound().height()).isEqualTo(168);
    }

    @Test
    public void parse_holeCutout_shouldMatchExpectedInset() {
        CutoutSpecification cutoutSpecification = mParser.parse("M 20.0,20.0\n"
                + "h 136\n"
                + "v 136\n"
                + "h -136\n"
                + "Z\n"
                + "@left");

        assertThat(cutoutSpecification.getSafeInset()).isEqualTo(new Rect(0, 156, 0, 0));
    }

    @Test
    public void getSafeInset_shortEdgeIsTopBottom_shouldMatchExpectedInset() {
        CutoutSpecification cutoutSpecification =
                new CutoutSpecification.Parser(2f, 200, 400)
                        .parse(CORNER_CUTOUT_SPECIFICATION);

        assertThat(cutoutSpecification.getSafeInset())
                .isEqualTo(new Rect(0, 4, 0, 8));
    }

    @Test
    public void getSafeInset_shortEdgeIsLeftRight_shouldMatchExpectedInset() {
        CutoutSpecification cutoutSpecification =
                new CutoutSpecification.Parser(2f, 400, 200)
                        .parse(CORNER_CUTOUT_SPECIFICATION);

        assertThat(cutoutSpecification.getSafeInset())
                .isEqualTo(new Rect(6, 0, 8, 0));
    }

    @Test
    public void parse_bottomLeftSpec_withBindLeftMarker_shouldBeLeftBound() {
        CutoutSpecification cutoutSpecification =
                new CutoutSpecification.Parser(2f, 400, 200)
                        .parse("@bottom"
                                + "M 0,0\n"
                                + "v -10\n"
                                + "h 10\n"
                                + "v 10\n"
                                + "z\n"
                                + "@left\n"
                                + "@bind_left_cutout");

        assertThat(cutoutSpecification.getLeftBound())
                .isEqualTo(new Rect(0, 190, 10, 200));
    }

    @Test
    public void parse_bottomRightSpec_withBindRightMarker_shouldBeRightBound() {
        CutoutSpecification cutoutSpecification =
                new CutoutSpecification.Parser(2f, 400, 200)
                        .parse("@bottom"
                                + "M 0,0\n"
                                + "v -10\n"
                                + "h -10\n"
                                + "v 10\n"
                                + "z\n"
                                + "@right\n"
                                + "@bind_right_cutout");

        assertThat(cutoutSpecification.getRightBound())
                .isEqualTo(new Rect(390, 190, 400, 200));
    }
}
