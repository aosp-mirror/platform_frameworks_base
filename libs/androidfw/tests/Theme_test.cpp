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

#include <androidfw/ResourceTypes.h>

#include <utils/String8.h>
#include <utils/String16.h>
#include "TestHelpers.h"
#include "data/system/R.h"
#include "data/app/R.h"

#include <gtest/gtest.h>

using namespace android;

namespace {

#include "data/system/system_arsc.h"
#include "data/app/app_arsc.h"

enum { MAY_NOT_BE_BAG = false };

/**
 * TODO(adamlesinski): Enable when fixed.
 */
TEST(ThemeTest, DISABLED_shouldCopyThemeFromDifferentResTable) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(system_arsc, system_arsc_len));
    ASSERT_EQ(NO_ERROR, table.add(app_arsc, app_arsc_len));

    ResTable::Theme theme1(table);
    ASSERT_EQ(NO_ERROR, theme1.applyStyle(app::R::style::Theme_One));
    Res_value val;
    ASSERT_GE(theme1.getAttribute(android::R::attr::background, &val), 0);
    ASSERT_EQ(Res_value::TYPE_INT_COLOR_RGB8, val.dataType);
    ASSERT_EQ(uint32_t(0xffff0000), val.data);
    ASSERT_GE(theme1.getAttribute(app::R::attr::number, &val), 0);
    ASSERT_EQ(Res_value::TYPE_INT_DEC, val.dataType);
    ASSERT_EQ(uint32_t(1), val.data);

    ResTable table2;
    ASSERT_EQ(NO_ERROR, table2.add(system_arsc, system_arsc_len));
    ASSERT_EQ(NO_ERROR, table2.add(app_arsc, app_arsc_len));

    ResTable::Theme theme2(table2);
    ASSERT_EQ(NO_ERROR, theme2.setTo(theme1));
    ASSERT_GE(theme2.getAttribute(android::R::attr::background, &val), 0);
    ASSERT_EQ(Res_value::TYPE_INT_COLOR_RGB8, val.dataType);
    ASSERT_EQ(uint32_t(0xffff0000), val.data);
    ASSERT_GE(theme2.getAttribute(app::R::attr::number, &val), 0);
    ASSERT_EQ(Res_value::TYPE_INT_DEC, val.dataType);
    ASSERT_EQ(uint32_t(1), val.data);
}

}
