/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "DominatorTree.h"
#include "test/Test.h"
#include "util/Util.h"

#include <sstream>
#include <string>
#include <vector>

namespace aapt {

namespace {

class PrettyPrinter : public DominatorTree::Visitor {
public:
    explicit PrettyPrinter(const int indent = 2) : mIndent(indent) {
    }

    void visitTree(const std::string& product, DominatorTree::Node* root) override {
        for (auto& child : root->children()) {
            visitNode(child.get(), 0);
        }
    }

    std::string toString(DominatorTree* tree) {
        mBuffer.str("");
        mBuffer.clear();
        tree->accept(this);
        return mBuffer.str();
    }

private:
    void visitConfig(const DominatorTree::Node* node, const int indent) {
        auto configString = node->value()->config.toString();
        mBuffer << std::string(indent, ' ')
                << (configString.isEmpty() ? "<default>" : configString)
                << std::endl;
    }

    void visitNode(const DominatorTree::Node* node, const int indent) {
        visitConfig(node, indent);
        for (const auto& child : node->children()) {
            visitNode(child.get(), indent + mIndent);
        }
    }

    std::stringstream mBuffer;
    const int mIndent = 2;
};

} // namespace

TEST(DominatorTreeTest, DefaultDominatesEverything) {
    const ConfigDescription defaultConfig = {};
    const ConfigDescription landConfig = test::parseConfigOrDie("land");
    const ConfigDescription sw600dpLandConfig = test::parseConfigOrDie("sw600dp-land-v13");

    std::vector<std::unique_ptr<ResourceConfigValue>> configs;
    configs.push_back(util::make_unique<ResourceConfigValue>(defaultConfig, ""));
    configs.push_back(util::make_unique<ResourceConfigValue>(landConfig, ""));
    configs.push_back(util::make_unique<ResourceConfigValue>(sw600dpLandConfig, ""));

    DominatorTree tree(configs);
    PrettyPrinter printer;

    std::string expected =
            "<default>\n"
            "  land\n"
            "  sw600dp-land-v13\n";
    EXPECT_EQ(expected, printer.toString(&tree));
}

TEST(DominatorTreeTest, ProductsAreDominatedSeparately) {
    const ConfigDescription defaultConfig = {};
    const ConfigDescription landConfig = test::parseConfigOrDie("land");
    const ConfigDescription sw600dpLandConfig = test::parseConfigOrDie("sw600dp-land-v13");

    std::vector<std::unique_ptr<ResourceConfigValue>> configs;
    configs.push_back(util::make_unique<ResourceConfigValue>(defaultConfig, ""));
    configs.push_back(util::make_unique<ResourceConfigValue>(landConfig, ""));
    configs.push_back(util::make_unique<ResourceConfigValue>(defaultConfig, "phablet"));
    configs.push_back(util::make_unique<ResourceConfigValue>(sw600dpLandConfig, "phablet"));

    DominatorTree tree(configs);
    PrettyPrinter printer;

    std::string expected =
            "<default>\n"
            "  land\n"
            "<default>\n"
            "  sw600dp-land-v13\n";
    EXPECT_EQ(expected, printer.toString(&tree));
}

TEST(DominatorTreeTest, MoreSpecificConfigurationsAreDominated) {
    const ConfigDescription defaultConfig = {};
    const ConfigDescription enConfig = test::parseConfigOrDie("en");
    const ConfigDescription enV21Config = test::parseConfigOrDie("en-v21");
    const ConfigDescription ldrtlConfig = test::parseConfigOrDie("ldrtl-v4");
    const ConfigDescription ldrtlXhdpiConfig = test::parseConfigOrDie("ldrtl-xhdpi-v4");
    const ConfigDescription sw300dpConfig = test::parseConfigOrDie("sw300dp-v13");
    const ConfigDescription sw540dpConfig = test::parseConfigOrDie("sw540dp-v14");
    const ConfigDescription sw600dpConfig = test::parseConfigOrDie("sw600dp-v14");
    const ConfigDescription sw720dpConfig = test::parseConfigOrDie("sw720dp-v13");
    const ConfigDescription v20Config = test::parseConfigOrDie("v20");

    std::vector<std::unique_ptr<ResourceConfigValue>> configs;
    configs.push_back(util::make_unique<ResourceConfigValue>(defaultConfig, ""));
    configs.push_back(util::make_unique<ResourceConfigValue>(enConfig, ""));
    configs.push_back(util::make_unique<ResourceConfigValue>(enV21Config, ""));
    configs.push_back(util::make_unique<ResourceConfigValue>(ldrtlConfig, ""));
    configs.push_back(util::make_unique<ResourceConfigValue>(ldrtlXhdpiConfig, ""));
    configs.push_back(util::make_unique<ResourceConfigValue>(sw300dpConfig, ""));
    configs.push_back(util::make_unique<ResourceConfigValue>(sw540dpConfig, ""));
    configs.push_back(util::make_unique<ResourceConfigValue>(sw600dpConfig, ""));
    configs.push_back(util::make_unique<ResourceConfigValue>(sw720dpConfig, ""));
    configs.push_back(util::make_unique<ResourceConfigValue>(v20Config, ""));

    DominatorTree tree(configs);
    PrettyPrinter printer;

    std::string expected =
            "<default>\n"
            "  en\n"
            "    en-v21\n"
            "  ldrtl-v4\n"
            "    ldrtl-xhdpi-v4\n"
            "  sw300dp-v13\n"
            "    sw540dp-v14\n"
            "      sw600dp-v14\n"
            "    sw720dp-v13\n"
            "  v20\n";
    EXPECT_EQ(expected, printer.toString(&tree));
}

} // namespace aapt
