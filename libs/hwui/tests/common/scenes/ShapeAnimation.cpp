/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "TestSceneBase.h"
#include "utils/Color.h"

#include <cstdio>

class ShapeAnimation;

static TestScene::Registrar _Shapes(TestScene::Info{"shapes", "A grid of shape drawing test cases.",
                                                    TestScene::simpleCreateScene<ShapeAnimation>});

class ShapeAnimation : public TestScene {
public:
    sp<RenderNode> card;
    void createContent(int width, int height, Canvas& canvas) override {
        card = TestUtils::createNode(
                0, 0, width, height, [width](RenderProperties& props, Canvas& canvas) {
                    std::function<void(Canvas&, float, const SkPaint&)> ops[] = {
                            [](Canvas& canvas, float size, const SkPaint& paint) {
                                canvas.drawArc(0, 0, size, size, 50, 189, true, paint);
                            },
                            [](Canvas& canvas, float size, const SkPaint& paint) {
                                canvas.drawOval(0, 0, size, size, paint);
                            },
                            [](Canvas& canvas, float size, const SkPaint& paint) {
                                SkPath diamondPath;
                                diamondPath.moveTo(size / 2, 0);
                                diamondPath.lineTo(size, size / 2);
                                diamondPath.lineTo(size / 2, size);
                                diamondPath.lineTo(0, size / 2);
                                diamondPath.close();
                                canvas.drawPath(diamondPath, paint);
                            },
                            [](Canvas& canvas, float size, const SkPaint& paint) {
                                float data[] = {0, 0, size, size, 0, size, size, 0};
                                canvas.drawLines(data, sizeof(data) / sizeof(float), paint);
                            },
                            [](Canvas& canvas, float size, const SkPaint& paint) {
                                float data[] = {0, 0, size, size, 0, size, size, 0};
                                canvas.drawPoints(data, sizeof(data) / sizeof(float), paint);
                            },
                            [](Canvas& canvas, float size, const SkPaint& paint) {
                                canvas.drawRect(0, 0, size, size, paint);
                            },
                            [](Canvas& canvas, float size, const SkPaint& paint) {
                                float rad = size / 4;
                                canvas.drawRoundRect(0, 0, size, size, rad, rad, paint);
                            }};
                    float cellSpace = dp(4);
                    float cellSize = floorf(width / 7 - cellSpace);

                    // each combination of strokeWidth + style gets a column
                    int outerCount = canvas.save(SaveFlags::MatrixClip);
                    SkPaint paint;
                    paint.setAntiAlias(true);
                    SkPaint::Style styles[] = {SkPaint::kStroke_Style, SkPaint::kFill_Style,
                                               SkPaint::kStrokeAndFill_Style};
                    for (auto style : styles) {
                        paint.setStyle(style);
                        for (auto strokeWidth : {0.0f, 0.5f, 8.0f}) {
                            paint.setStrokeWidth(strokeWidth);
                            // fill column with each op
                            int middleCount = canvas.save(SaveFlags::MatrixClip);
                            for (auto op : ops) {
                                int innerCount = canvas.save(SaveFlags::MatrixClip);
                                canvas.clipRect(0, 0, cellSize, cellSize, SkClipOp::kIntersect);
                                canvas.drawColor(Color::White, SkBlendMode::kSrcOver);
                                op(canvas, cellSize, paint);
                                canvas.restoreToCount(innerCount);
                                canvas.translate(cellSize + cellSpace, 0);
                            }
                            canvas.restoreToCount(middleCount);
                            canvas.translate(0, cellSize + cellSpace);
                        }
                    }
                    canvas.restoreToCount(outerCount);
                });
        canvas.drawColor(Color::Grey_500, SkBlendMode::kSrcOver);
        canvas.drawRenderNode(card.get());
    }

    void doFrame(int frameNr) override {
        card->mutateStagingProperties().setTranslationY(frameNr % 150);
        card->setPropertyFieldsDirty(RenderNode::Y);
    }
};
