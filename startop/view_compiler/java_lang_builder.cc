/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "java_lang_builder.h"

#include "android-base/stringprintf.h"

using android::base::StringPrintf;
using std::string;

void JavaLangViewBuilder::Start() const {
  out_ << StringPrintf("package %s;\n", package_.c_str())
       << "import android.content.Context;\n"
          "import android.content.res.Resources;\n"
          "import android.content.res.XmlResourceParser;\n"
          "import android.util.AttributeSet;\n"
          "import android.util.Xml;\n"
          "import android.view.*;\n"
          "import android.widget.*;\n"
          "\n"
          "public final class CompiledView {\n"
          "\n"
          "static <T extends View> T createView(Context context, AttributeSet attrs, View parent, "
          "String name, LayoutInflater.Factory factory, LayoutInflater.Factory2 factory2) {"
          "\n"
          "  if (factory2 != null) {\n"
          "    return (T)factory2.onCreateView(parent, name, context, attrs);\n"
          "  } else if (factory != null) {\n"
          "    return (T)factory.onCreateView(name, context, attrs);\n"
          "  }\n"
          // TODO: find a way to call the private factory
          "  return null;\n"
          "}\n"
          "\n"
          "  public static View inflate(Context context) {\n"
          "    try {\n"
          "      LayoutInflater inflater = LayoutInflater.from(context);\n"
          "      LayoutInflater.Factory factory = inflater.getFactory();\n"
          "      LayoutInflater.Factory2 factory2 = inflater.getFactory2();\n"
          "      Resources res = context.getResources();\n"
       << StringPrintf("      XmlResourceParser xml = res.getLayout(%s.R.layout.%s);\n",
                       package_.c_str(),
                       layout_name_.c_str())
       << "      AttributeSet attrs = Xml.asAttributeSet(xml);\n"
          // The Java-language XmlPullParser needs a call to next to find the start document tag.
          "      xml.next(); // start document\n";
}

void JavaLangViewBuilder::Finish() const {
  out_ << "    } catch (Exception e) {\n"
          "      return null;\n"
          "    }\n"  // end try
          "  }\n"    // end inflate
          "}\n";     // end CompiledView
}

void JavaLangViewBuilder::StartView(const string& class_name, bool /*is_viewgroup*/) {
  const string view_var = MakeVar("view");
  const string layout_var = MakeVar("layout");
  std::string parent = "null";
  if (!view_stack_.empty()) {
    const StackEntry& parent_entry = view_stack_.back();
    parent = parent_entry.view_var;
  }
  out_ << "      xml.next(); // <" << class_name << ">\n"
       << StringPrintf("      %s %s = createView(context, attrs, %s, \"%s\", factory, factory2);\n",
                       class_name.c_str(),
                       view_var.c_str(),
                       parent.c_str(),
                       class_name.c_str())
       << StringPrintf("      if (%s == null) %s = new %s(context, attrs);\n",
                       view_var.c_str(),
                       view_var.c_str(),
                       class_name.c_str());
  if (!view_stack_.empty()) {
    out_ << StringPrintf("      ViewGroup.LayoutParams %s = %s.generateLayoutParams(attrs);\n",
                         layout_var.c_str(),
                         parent.c_str());
  }
  view_stack_.push_back({class_name, view_var, layout_var});
}

void JavaLangViewBuilder::FinishView() {
  const StackEntry var = view_stack_.back();
  view_stack_.pop_back();
  if (!view_stack_.empty()) {
    const string& parent = view_stack_.back().view_var;
    out_ << StringPrintf("      xml.next(); // </%s>\n", var.class_name.c_str())
         << StringPrintf("      %s.addView(%s, %s);\n",
                         parent.c_str(),
                         var.view_var.c_str(),
                         var.layout_params_var.c_str());
  } else {
    out_ << StringPrintf("      return %s;\n", var.view_var.c_str());
  }
}

const std::string JavaLangViewBuilder::MakeVar(std::string prefix) {
  std::stringstream v;
  v << prefix << view_id_++;
  return v.str();
}
