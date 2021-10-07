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

#include <sstream>
#include <string>
#include <vector>

#include "test/Test.h"
#include "util/Util.h"

using ::android::ConfigDescription;

namespace aapt {

namespace {

class PrettyPrinter : public DominatorTree::Visitor {
 public:
  explicit PrettyPrinter(const int indent = 2) : indent_(indent) {}

  void VisitTree(const std::string& product,
                 DominatorTree::Node* root) override {
    for (auto& child : root->children()) {
      VisitNode(child.get(), 0);
    }
  }

  std::string ToString(DominatorTree* tree) {
    buffer_.str("");
    buffer_.clear();
    tree->Accept(this);
    return buffer_.str();
  }

 private:
  void VisitConfig(const DominatorTree::Node* node, const int indent) {
    auto config_string = node->value()->config.toString();
    buffer_ << std::string(indent, ' ')
            << (config_string.isEmpty() ? "<default>" : config_string)
            << std::endl;
  }

  void VisitNode(const DominatorTree::Node* node, const int indent) {
    VisitConfig(node, indent);
    for (const auto& child : node->children()) {
      VisitNode(child.get(), indent + indent_);
    }
  }

  std::stringstream buffer_;
  const int indent_ = 2;
};

}  // namespace

TEST(DominatorTreeTest, DefaultDominatesEverything) {
  const ConfigDescription default_config = {};
  const ConfigDescription land_config = test::ParseConfigOrDie("land");
  const ConfigDescription sw600dp_land_config = test::ParseConfigOrDie("sw600dp-land-v13");

  std::vector<std::unique_ptr<ResourceConfigValue>> configs;
  configs.push_back(util::make_unique<ResourceConfigValue>(default_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(land_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(sw600dp_land_config, ""));

  DominatorTree tree(configs);
  PrettyPrinter printer;

  std::string expected =
      "<default>\n"
      "  land\n"
      "  sw600dp-land-v13\n";
  EXPECT_EQ(expected, printer.ToString(&tree));
}

TEST(DominatorTreeTest, ProductsAreDominatedSeparately) {
  const ConfigDescription default_config = {};
  const ConfigDescription land_config = test::ParseConfigOrDie("land");
  const ConfigDescription sw600dp_land_config = test::ParseConfigOrDie("sw600dp-land-v13");

  std::vector<std::unique_ptr<ResourceConfigValue>> configs;
  configs.push_back(util::make_unique<ResourceConfigValue>(default_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(land_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(default_config, "phablet"));
  configs.push_back(util::make_unique<ResourceConfigValue>(sw600dp_land_config, "phablet"));

  DominatorTree tree(configs);
  PrettyPrinter printer;

  std::string expected =
      "<default>\n"
      "  land\n"
      "<default>\n"
      "  sw600dp-land-v13\n";
  EXPECT_EQ(expected, printer.ToString(&tree));
}

TEST(DominatorTreeTest, MoreSpecificConfigurationsAreDominated) {
  const ConfigDescription default_config = {};
  const ConfigDescription en_config = test::ParseConfigOrDie("en");
  const ConfigDescription en_v21_config = test::ParseConfigOrDie("en-v21");
  const ConfigDescription ldrtl_config = test::ParseConfigOrDie("ldrtl-v4");
  const ConfigDescription ldrtl_xhdpi_config = test::ParseConfigOrDie("ldrtl-xhdpi-v4");
  const ConfigDescription sw300dp_config = test::ParseConfigOrDie("sw300dp-v13");
  const ConfigDescription sw540dp_config = test::ParseConfigOrDie("sw540dp-v14");
  const ConfigDescription sw600dp_config = test::ParseConfigOrDie("sw600dp-v14");
  const ConfigDescription sw720dp_config = test::ParseConfigOrDie("sw720dp-v13");
  const ConfigDescription v20_config = test::ParseConfigOrDie("v20");

  std::vector<std::unique_ptr<ResourceConfigValue>> configs;
  configs.push_back(util::make_unique<ResourceConfigValue>(default_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(en_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(en_v21_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(ldrtl_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(ldrtl_xhdpi_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(sw300dp_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(sw540dp_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(sw600dp_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(sw720dp_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(v20_config, ""));

  DominatorTree tree(configs);
  PrettyPrinter printer;

  std::string expected =
      "<default>\n"
      "  ldrtl-v4\n"
      "    ldrtl-xhdpi-v4\n"
      "  sw300dp-v13\n"
      "    sw540dp-v14\n"
      "      sw600dp-v14\n"
      "    sw720dp-v13\n"
      "  v20\n"
      "en\n"
      "  en-v21\n";
  EXPECT_EQ(expected, printer.ToString(&tree));
}

TEST(DominatorTreeTest, LocalesAreNeverDominated) {
  const ConfigDescription fr_config = test::ParseConfigOrDie("fr");
  const ConfigDescription fr_rCA_config = test::ParseConfigOrDie("fr-rCA");
  const ConfigDescription fr_rFR_config = test::ParseConfigOrDie("fr-rFR");

  std::vector<std::unique_ptr<ResourceConfigValue>> configs;
  configs.push_back(util::make_unique<ResourceConfigValue>(ConfigDescription::DefaultConfig(), ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(fr_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(fr_rCA_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(fr_rFR_config, ""));

  DominatorTree tree(configs);
  PrettyPrinter printer;

  std::string expected =
      "<default>\n"
      "fr\n"
      "fr-rCA\n"
      "fr-rFR\n";
  EXPECT_EQ(expected, printer.ToString(&tree));
}

TEST(DominatorTreeTest, NonZeroDensitiesMatch) {
  const ConfigDescription sw600_config = test::ParseConfigOrDie("sw600dp");
  const ConfigDescription sw600_hdpi_config = test::ParseConfigOrDie("sw600dp-hdpi");
  const ConfigDescription sw800_hdpi_config = test::ParseConfigOrDie("sw800dp-hdpi");
  const ConfigDescription sw800_xxhdpi_config = test::ParseConfigOrDie("sw800dp-xxhdpi");

  std::vector<std::unique_ptr<ResourceConfigValue>> configs;
  configs.push_back(util::make_unique<ResourceConfigValue>(ConfigDescription::DefaultConfig(), ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(sw600_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(sw600_hdpi_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(sw800_hdpi_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(sw800_xxhdpi_config, ""));

  DominatorTree tree(configs);
  PrettyPrinter printer;

  std::string expected =
      "<default>\n"
      "  sw600dp-v13\n"
      "    sw600dp-hdpi-v13\n"
      "      sw800dp-hdpi-v13\n"
      "      sw800dp-xxhdpi-v13\n";
  EXPECT_EQ(expected, printer.ToString(&tree));
}

TEST(DominatorTreeTest, MccMncIsPeertoLocale) {
  const ConfigDescription default_config = {};
  const ConfigDescription de_config = test::ParseConfigOrDie("de");
  const ConfigDescription fr_config = test::ParseConfigOrDie("fr");
  const ConfigDescription mcc_config = test::ParseConfigOrDie("mcc262");
  const ConfigDescription mcc_fr_config = test::ParseConfigOrDie("mcc262-fr");
  const ConfigDescription mnc_config = test::ParseConfigOrDie("mnc2");
  const ConfigDescription mnc_fr_config = test::ParseConfigOrDie("mnc2-fr");
  std::vector<std::unique_ptr<ResourceConfigValue>> configs;
  configs.push_back(util::make_unique<ResourceConfigValue>(default_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(de_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(fr_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(mcc_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(mcc_fr_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(mnc_config, ""));
  configs.push_back(util::make_unique<ResourceConfigValue>(mnc_fr_config, ""));
  DominatorTree tree(configs);
  PrettyPrinter printer;
  std::string expected =
      "<default>\n"
      "de\n"
      "fr\n"
      "mcc262\n"
      "mcc262-fr\n"
      "mnc2\n"
      "mnc2-fr\n";
  EXPECT_EQ(expected, printer.ToString(&tree));
}

}  // namespace aapt
