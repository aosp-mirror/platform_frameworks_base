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

/**
  Generates arrays for non-linear font scaling, to be pasted into
  frameworks/base/core/java/android/content/res/FontScaleConverterFactory.java

  To use:
    `node font-scaling-array-generator.js`
    or just open a browser, open DevTools, and paste into the Console.
*/

/**
 * Modify this to match your
 * frameworks/base/packages/SettingsLib/res/values/arrays.xml#entryvalues_font_size
 * array so that all possible scales are generated.
 */
const scales = [1.15, 1.30, 1.5, 1.8, 2];

const commonSpSizes = [8, 10, 12, 14, 18, 20, 24, 30, 100];

/**
 * Enum for GENERATION_STYLE which determines how to generate the arrays.
 */
const GenerationStyle = {
  /**
   * Interpolates between hand-tweaked curves. This is the best option and
   * shouldn't require any additional tweaking.
   */
  CUSTOM_TWEAKED: 'CUSTOM_TWEAKED',

  /**
   * Uses a curve equation that is mostly correct, but will need manual tweaking
   * at some scales.
   */
  CURVE: 'CURVE',

  /**
   * Uses straight linear multiplication. Good starting point for manual
   * tweaking.
   */
  LINEAR: 'LINEAR'
}

/**
 * Determines how arrays are generated. Must be one of the GenerationStyle
 * values.
 */
const GENERATION_STYLE = GenerationStyle.CUSTOM_TWEAKED;

// These are hand-tweaked curves from which we will derive the other
// interstitial curves using linear interpolation, in the case of using
// GenerationStyle.CUSTOM_TWEAKED.
const interpolationTargets = {
  1.0: commonSpSizes,
  1.5: [12, 15, 18, 22, 24, 26, 28, 30, 100],
  2.0: [16, 20, 24, 26, 30, 34, 36, 38, 100]
};

/**
 * Interpolate a value with specified extrema, to a new value between new
 * extrema.
 *
 * @param value the current value
 * @param inputMin minimum the input value can reach
 * @param inputMax maximum the input value can reach
 * @param outputMin minimum the output value can reach
 * @param outputMax maximum the output value can reach
 */
function map(value, inputMin, inputMax, outputMin, outputMax) {
  return outputMin + (outputMax - outputMin) * ((value - inputMin) / (inputMax - inputMin));
}

/***
 * Interpolate between values a and b.
 */
function lerp(a, b, fraction) {
  return (a * (1.0 - fraction)) + (b * fraction);
}

function generateRatios(scale) {
  // Find the best two arrays to interpolate between.
  let startTarget, endTarget;
  let startTargetScale, endTargetScale;
  const targetScales = Object.keys(interpolationTargets).sort();
  for (let i = 0; i < targetScales.length - 1; i++) {
    const targetScaleKey = targetScales[i];
    const targetScale = parseFloat(targetScaleKey, 10);
    const startTargetScaleKey = targetScaleKey;
    const endTargetScaleKey = targetScales[i + 1];

    if (scale < parseFloat(startTargetScaleKey, 10)) {
      break;
    }

    startTargetScale = parseFloat(startTargetScaleKey, 10);
    endTargetScale = parseFloat(endTargetScaleKey, 10);
    startTarget = interpolationTargets[startTargetScaleKey];
    endTarget = interpolationTargets[endTargetScaleKey];
  }
  const interpolationProgress = map(scale, startTargetScale, endTargetScale, 0, 1);

  return commonSpSizes.map((sp, i) => {
    const originalSizeDp = sp;
    let newSizeDp;
    switch (GENERATION_STYLE) {
      case GenerationStyle.CUSTOM_TWEAKED:
        newSizeDp = lerp(startTarget[i], endTarget[i], interpolationProgress);
        break;
      case GenerationStyle.CURVE: {
        let coeff1;
        let coeff2;
        if (scale < 1) {
          // \left(1.22^{-\left(x+5\right)}+0.5\right)\cdot x
          coeff1 = -5;
          coeff2 = scale;
        } else {
          // (1.22^{-\left(x-10\right)}+1\right)\cdot x
          coeff1 = map(scale, 1, 2, 2, 8);
          coeff2 = 1;
        }
        newSizeDp = ((Math.pow(1.22, (-(originalSizeDp - coeff1))) + coeff2) * originalSizeDp);
        break;
      }
      case GenerationStyle.LINEAR:
        newSizeDp = originalSizeDp * scale;
        break;
      default:
        throw new Error('Invalid GENERATION_STYLE');
    }
    return {
      fromSp: sp,
      toDp: newSizeDp
    }
  });
}

const scaleArrays =
    scales
        .map(scale => {
          const scaleString = (scale * 100).toFixed(0);
          return {
            scale,
            name: `font_size_original_sp_to_scaled_dp_${scaleString}_percent`
          }
        })
        .map(scaleArray => {
          const items = generateRatios(scaleArray.scale);

          return {
            ...scaleArray,
            items
          }
        });

function formatDigit(d) {
  const twoSignificantDigits = Math.round(d * 100) / 100;
  return String(twoSignificantDigits).padStart(4, ' ');
}

console.log(
    '' +
    scaleArrays.reduce(
        (previousScaleArray, currentScaleArray) => {
          const itemsFromSp = currentScaleArray.items.map(d => d.fromSp)
                                .map(formatDigit)
                                .join('f, ');
          const itemsToDp = currentScaleArray.items.map(d => d.toDp)
                                .map(formatDigit)
                                .join('f, ');

          return previousScaleArray + `
        put(
                /* scaleKey= */ ${currentScaleArray.scale}f,
                new FontScaleConverter(
                        /* fromSp= */
                        new float[] {${itemsFromSp}},
                        /* toDp=   */
                        new float[] {${itemsToDp}})
        );
     `;
        },
        ''));
