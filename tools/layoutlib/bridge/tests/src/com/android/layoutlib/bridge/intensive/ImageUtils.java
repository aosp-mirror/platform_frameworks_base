/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.layoutlib.bridge.intensive;

import android.annotation.NonNull;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

import static java.awt.RenderingHints.*;
import static java.awt.image.BufferedImage.TYPE_INT_ARGB;
import static java.io.File.separatorChar;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;


// Adapted by taking the relevant pieces of code from the following classes:
//
// com.android.tools.idea.rendering.ImageUtils,
// com.android.tools.idea.tests.gui.framework.fixture.layout.ImageFixture and
// com.android.tools.idea.rendering.RenderTestBase
/**
 * Utilities related to image processing.
 */
public class ImageUtils {
    /**
     * Normally, this test will fail when there is a missing thumbnail. However, when
     * you create creating a new test, it's useful to be able to turn this off such that
     * you can generate all the missing thumbnails in one go, rather than having to run
     * the test repeatedly to get to each new render assertion generating its thumbnail.
     */
    private static final boolean FAIL_ON_MISSING_THUMBNAIL = true;

    private static final int THUMBNAIL_SIZE = 250;

    private static final double MAX_PERCENT_DIFFERENCE = 0.3;

    public static void requireSimilar(@NonNull String relativePath, @NonNull BufferedImage image)
            throws IOException {
        int maxDimension = Math.max(image.getWidth(), image.getHeight());
        double scale = THUMBNAIL_SIZE / (double)maxDimension;
        BufferedImage thumbnail = scale(image, scale, scale);

        InputStream is = ImageUtils.class.getResourceAsStream(relativePath);
        if (is == null) {
            String message = "Unable to load golden thumbnail: " + relativePath + "\n";
            message = saveImageAndAppendMessage(thumbnail, message, relativePath);
            if (FAIL_ON_MISSING_THUMBNAIL) {
                fail(message);
            } else {
                System.out.println(message);
            }
        }
        else {
            BufferedImage goldenImage = ImageIO.read(is);
            assertImageSimilar(relativePath, goldenImage, thumbnail, MAX_PERCENT_DIFFERENCE);
        }
    }

    public static void assertImageSimilar(String relativePath, BufferedImage goldenImage,
            BufferedImage image, double maxPercentDifferent) throws IOException {
        assertEquals("Only TYPE_INT_ARGB image types are supported",  TYPE_INT_ARGB, image.getType());

        if (goldenImage.getType() != TYPE_INT_ARGB) {
            BufferedImage temp = new BufferedImage(goldenImage.getWidth(), goldenImage.getHeight(),
                    TYPE_INT_ARGB);
            temp.getGraphics().drawImage(goldenImage, 0, 0, null);
            goldenImage = temp;
        }
        assertEquals(TYPE_INT_ARGB, goldenImage.getType());

        int imageWidth = Math.min(goldenImage.getWidth(), image.getWidth());
        int imageHeight = Math.min(goldenImage.getHeight(), image.getHeight());

        // Blur the images to account for the scenarios where there are pixel
        // differences
        // in where a sharp edge occurs
        // goldenImage = blur(goldenImage, 6);
        // image = blur(image, 6);

        int width = 3 * imageWidth;
        @SuppressWarnings("UnnecessaryLocalVariable")
        int height = imageHeight; // makes code more readable
        BufferedImage deltaImage = new BufferedImage(width, height, TYPE_INT_ARGB);
        Graphics g = deltaImage.getGraphics();

        // Compute delta map
        long delta = 0;
        for (int y = 0; y < imageHeight; y++) {
            for (int x = 0; x < imageWidth; x++) {
                int goldenRgb = goldenImage.getRGB(x, y);
                int rgb = image.getRGB(x, y);
                if (goldenRgb == rgb) {
                    deltaImage.setRGB(imageWidth + x, y, 0x00808080);
                    continue;
                }

                // If the pixels have no opacity, don't delta colors at all
                if (((goldenRgb & 0xFF000000) == 0) && (rgb & 0xFF000000) == 0) {
                    deltaImage.setRGB(imageWidth + x, y, 0x00808080);
                    continue;
                }

                int deltaR = ((rgb & 0xFF0000) >>> 16) - ((goldenRgb & 0xFF0000) >>> 16);
                int newR = 128 + deltaR & 0xFF;
                int deltaG = ((rgb & 0x00FF00) >>> 8) - ((goldenRgb & 0x00FF00) >>> 8);
                int newG = 128 + deltaG & 0xFF;
                int deltaB = (rgb & 0x0000FF) - (goldenRgb & 0x0000FF);
                int newB = 128 + deltaB & 0xFF;

                int avgAlpha = ((((goldenRgb & 0xFF000000) >>> 24)
                        + ((rgb & 0xFF000000) >>> 24)) / 2) << 24;

                int newRGB = avgAlpha | newR << 16 | newG << 8 | newB;
                deltaImage.setRGB(imageWidth + x, y, newRGB);

                delta += Math.abs(deltaR);
                delta += Math.abs(deltaG);
                delta += Math.abs(deltaB);
            }
        }

        // 3 different colors, 256 color levels
        long total = imageHeight * imageWidth * 3L * 256L;
        float percentDifference = (float) (delta * 100 / (double) total);

        String error = null;
        String imageName = getName(relativePath);
        if (percentDifference > maxPercentDifferent) {
            error = String.format("Images differ (by %.1f%%)", percentDifference);
        } else if (Math.abs(goldenImage.getWidth() - image.getWidth()) >= 2) {
            error = "Widths differ too much for " + imageName + ": " +
                    goldenImage.getWidth() + "x" + goldenImage.getHeight() +
                    "vs" + image.getWidth() + "x" + image.getHeight();
        } else if (Math.abs(goldenImage.getHeight() - image.getHeight()) >= 2) {
            error = "Heights differ too much for " + imageName + ": " +
                    goldenImage.getWidth() + "x" + goldenImage.getHeight() +
                    "vs" + image.getWidth() + "x" + image.getHeight();
        }

        assertEquals(TYPE_INT_ARGB, image.getType());
        if (error != null) {
            // Expected on the left
            // Golden on the right
            g.drawImage(goldenImage, 0, 0, null);
            g.drawImage(image, 2 * imageWidth, 0, null);

            // Labels
            if (imageWidth > 80) {
                g.setColor(Color.RED);
                g.drawString("Expected", 10, 20);
                g.drawString("Actual", 2 * imageWidth + 10, 20);
            }

            File output = new File(getTempDir(), "delta-" + imageName);
            if (output.exists()) {
                boolean deleted = output.delete();
                assertTrue(deleted);
            }
            ImageIO.write(deltaImage, "PNG", output);
            error += " - see details in " + output.getPath() + "\n";
            error = saveImageAndAppendMessage(image, error, relativePath);
            System.out.println(error);
            fail(error);
        }

        g.dispose();
    }

    /**
     * Resize the given image
     *
     * @param source the image to be scaled
     * @param xScale x scale
     * @param yScale y scale
     * @return the scaled image
     */
    @NonNull
    public static BufferedImage scale(@NonNull BufferedImage source, double xScale, double yScale) {

        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int destWidth = Math.max(1, (int) (xScale * sourceWidth));
        int destHeight = Math.max(1, (int) (yScale * sourceHeight));
        int imageType = source.getType();
        if (imageType == BufferedImage.TYPE_CUSTOM) {
            imageType = BufferedImage.TYPE_INT_ARGB;
        }
        if (xScale > 0.5 && yScale > 0.5) {
            BufferedImage scaled =
                    new BufferedImage(destWidth, destHeight, imageType);
            Graphics2D g2 = scaled.createGraphics();
            g2.setComposite(AlphaComposite.Src);
            g2.setColor(new Color(0, true));
            g2.fillRect(0, 0, destWidth, destHeight);
            if (xScale == 1 && yScale == 1) {
                g2.drawImage(source, 0, 0, null);
            } else {
                setRenderingHints(g2);
                g2.drawImage(source, 0, 0, destWidth, destHeight, 0, 0, sourceWidth, sourceHeight,
                        null);
            }
            g2.dispose();
            return scaled;
        } else {
            // When creating a thumbnail, using the above code doesn't work very well;
            // you get some visible artifacts, especially for text. Instead use the
            // technique of repeatedly scaling the image into half; this will cause
            // proper averaging of neighboring pixels, and will typically (for the kinds
            // of screen sizes used by this utility method in the layout editor) take
            // about 3-4 iterations to get the result since we are logarithmically reducing
            // the size. Besides, each successive pass in operating on much fewer pixels
            // (a reduction of 4 in each pass).
            //
            // However, we may not be resizing to a size that can be reached exactly by
            // successively diving in half. Therefore, once we're within a factor of 2 of
            // the final size, we can do a resize to the exact target size.
            // However, we can get even better results if we perform this final resize
            // up front. Let's say we're going from width 1000 to a destination width of 85.
            // The first approach would cause a resize from 1000 to 500 to 250 to 125, and
            // then a resize from 125 to 85. That last resize can distort/blur a lot.
            // Instead, we can start with the destination width, 85, and double it
            // successfully until we're close to the initial size: 85, then 170,
            // then 340, and finally 680. (The next one, 1360, is larger than 1000).
            // So, now we *start* the thumbnail operation by resizing from width 1000 to
            // width 680, which will preserve a lot of visual details such as text.
            // Then we can successively resize the image in half, 680 to 340 to 170 to 85.
            // We end up with the expected final size, but we've been doing an exact
            // divide-in-half resizing operation at the end so there is less distortion.

            int iterations = 0; // Number of halving operations to perform after the initial resize
            int nearestWidth = destWidth; // Width closest to source width that = 2^x, x is integer
            int nearestHeight = destHeight;
            while (nearestWidth < sourceWidth / 2) {
                nearestWidth *= 2;
                nearestHeight *= 2;
                iterations++;
            }

            BufferedImage scaled = new BufferedImage(nearestWidth, nearestHeight, imageType);

            Graphics2D g2 = scaled.createGraphics();
            setRenderingHints(g2);
            g2.drawImage(source, 0, 0, nearestWidth, nearestHeight, 0, 0, sourceWidth, sourceHeight,
                    null);
            g2.dispose();

            sourceWidth = nearestWidth;
            sourceHeight = nearestHeight;
            source = scaled;

            for (int iteration = iterations - 1; iteration >= 0; iteration--) {
                int halfWidth = sourceWidth / 2;
                int halfHeight = sourceHeight / 2;
                scaled = new BufferedImage(halfWidth, halfHeight, imageType);
                g2 = scaled.createGraphics();
                setRenderingHints(g2);
                g2.drawImage(source, 0, 0, halfWidth, halfHeight, 0, 0, sourceWidth, sourceHeight,
                        null);
                g2.dispose();

                sourceWidth = halfWidth;
                sourceHeight = halfHeight;
                source = scaled;
                iterations--;
            }
            return scaled;
        }
    }

    private static void setRenderingHints(@NonNull Graphics2D g2) {
        g2.setRenderingHint(KEY_INTERPOLATION,VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(KEY_RENDERING, VALUE_RENDER_QUALITY);
        g2.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
    }

    /**
     * Temp directory where to write the thumbnails and deltas.
     */
    @NonNull
    private static File getTempDir() {
        if (System.getProperty("os.name").equals("Mac OS X")) {
            return new File("/tmp"); //$NON-NLS-1$
        }

        return new File(System.getProperty("java.io.tmpdir")); //$NON-NLS-1$
    }

    /**
     * Saves the generated thumbnail image and appends the info message to an initial message
     */
    @NonNull
    private static String saveImageAndAppendMessage(@NonNull BufferedImage image,
            @NonNull String initialMessage, @NonNull String relativePath) throws IOException {
        File output = new File(getTempDir(), getName(relativePath));
        if (output.exists()) {
            boolean deleted = output.delete();
            assertTrue(deleted);
        }
        ImageIO.write(image, "PNG", output);
        initialMessage += "Thumbnail for current rendering stored at " + output.getPath();
//        initialMessage += "\nRun the following command to accept the changes:\n";
//        initialMessage += String.format("mv %1$s %2$s", output.getPath(),
//                ImageUtils.class.getResource(relativePath).getPath());
        // The above has been commented out, since the destination path returned is in out dir
        // and it makes the tests pass without the code being actually checked in.
        return initialMessage;
    }

    private static String getName(@NonNull String relativePath) {
        return relativePath.substring(relativePath.lastIndexOf(separatorChar) + 1);
    }
}
