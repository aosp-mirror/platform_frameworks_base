#!/usr/bin/env python

#
# Copyright (C) 2024 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""Build XML."""

import dataclasses
import functools
from xml.dom import minidom
from xml.etree import ElementTree
from alias_builder import Alias
from commandline import CommandlineArgs
from fallback_builder import FallbackEntry
from family_builder import Family
from font_builder import Font


@dataclasses.dataclass
class XmlFont:
  """Class used for writing XML. All elements are str or None."""

  file: str
  weight: str | None
  style: str | None
  index: str | None
  supported_axes: str | None
  post_script_name: str | None
  fallback_for: str | None
  axes: dict[str | str]


def font_to_xml_font(font: Font, fallback_for=None) -> XmlFont:
  axes = None
  if font.axes:
    axes = {key: str(value) for key, value in font.axes.items()}
  return XmlFont(
      file=font.file,
      weight=str(font.weight) if font.weight is not None else None,
      style=font.style,
      index=str(font.index) if font.index is not None else None,
      supported_axes=font.supported_axes,
      post_script_name=font.post_script_name,
      fallback_for=fallback_for,
      axes=axes,
  )


@dataclasses.dataclass
class XmlFamily:
  """Class used for writing XML. All elements are str or None."""

  name: str | None
  lang: str | None
  variant: str | None
  fonts: [XmlFont]


def family_to_xml_family(family: Family) -> XmlFamily:
  return XmlFamily(
      name=family.name,
      lang=family.lang,
      variant=family.variant,
      fonts=[font_to_xml_font(f) for f in family.fonts],
  )


@dataclasses.dataclass
class XmlAlias:
  """Class used for writing XML. All elements are str or None."""

  name: str
  to: str
  weight: str | None


def alias_to_xml_alias(alias: Alias) -> XmlAlias:
  return XmlAlias(
      name=alias.name,
      to=alias.to,
      weight=str(alias.weight) if alias.weight is not None else None,
  )


@dataclasses.dataclass
class FallbackXml:
  families: [XmlFamily]
  aliases: [XmlAlias]


class FallbackOrder:
  """Provides a ordering of the family."""

  def __init__(self, fallback: [FallbackEntry]):
    # Preprocess fallbacks from flatten key to priority value.
    # The priority is a index appeared the fallback entry.
    # The key will be lang or file prefixed string, e.g. "lang:und-Arab" -> 0,
    # "file:Roboto-Regular.ttf" -> 10, etc.
    fallback_priority = {}
    for priority, fallback in enumerate(fallback):
      if fallback.lang:
        fallback_priority['lang:%s' % fallback.lang] = priority
      else:  # fallback.file is not None
        fallback_priority['id:%s' % fallback.id] = priority

    self.priority = fallback_priority

  def __call__(self, family: Family):
    """Returns priority of the family. Lower value means higher priority."""
    priority = None
    if family.id:
      priority = self.priority.get('id:%s' % family.id)
    if not priority and family.lang:
      priority = self.priority.get('lang:%s' % family.lang)

    assert priority is not None, 'Unknown priority for %s' % family

    # Priority adjustments.
    # First, give extra score to compact for compatibility.
    priority = priority * 10
    if family.variant == 'compact':
      priority = priority + 5

    # Next, give extra priority score. The priority is -100 to 100,
    # Not to mixed in other scores, shift this range to 0 to 200 and give it
    # to current priority.
    priority = priority * 1000
    custom_priority = family.priority if family.priority else 0
    priority = priority + custom_priority + 100

    return priority


def generate_xml(
    fallback: [FallbackEntry], aliases: [Alias], families: [Family]
) -> FallbackXml:
  """Generats FallbackXML objects."""

  # Step 1. Categorize families into following three.

  # The named family is converted to XmlFamily in this step.
  named_families: [str | XmlFamily] = {}
  # The list of Families used for locale fallback.
  fallback_families: [Family] = []
  # The list of Families that has fallbackFor attribute.
  font_fallback_families: [Family] = []

  for family in families:
    if family.name:  # process named family
      assert family.name not in named_families, (
          'Duplicated named family entry: %s' % family.name
      )
      named_families[family.name] = family_to_xml_family(family)
    elif family.fallback_for:
      font_fallback_families.append(family)
    else:
      fallback_families.append(family)

  # Step 2. Convert Alias to XmlAlias with validation.
  xml_aliases = []
  available_names = set(named_families.keys())
  for alias in aliases:
    assert alias.name not in available_names, (
        'duplicated name alias: %s' % alias
    )
    available_names.add(alias.name)

  for alias in aliases:
    assert alias.to in available_names, 'unknown alias to: %s' % alias
    xml_aliases.append(alias_to_xml_alias(alias))

  # Step 3. Reorder the fallback families with fallback priority.
  order = FallbackOrder(fallback)
  fallback_families.sort(
      key=functools.cmp_to_key(lambda l, r: order(l) - order(r))
  )
  for i, j in zip(fallback_families, fallback_families[1:]):
    assert order(i) != order(j), 'Same priority: %s vs %s' % (i, j)

  # Step 4. Place named families first.
  # Place sans-serif at the top of family list.
  assert 'sans-serif' in named_families, 'sans-serif family must exists'
  xml_families = [family_to_xml_family(named_families.pop('sans-serif'))]
  xml_families = xml_families + list(named_families.values())

  # Step 5. Convert fallback_families from Family to XmlFamily.
  # Also create ID to XmlFamily map which is used for resolving fallbackFor
  # attributes.
  id_to_family: [str | XmlFamily] = {}
  for family in fallback_families:
    xml_family = family_to_xml_family(family)
    xml_families.append(xml_family)
    if family.id:
      id_to_family[family.id] = xml_family

  # Step 6. Add font fallback to the target XmlFamily
  for family in font_fallback_families:
    assert family.fallback_for in named_families, (
        'Unknown fallback for: %s' % family
    )
    assert family.target in id_to_family, 'Unknown target for %s' % family

    xml_family = id_to_family[family.target]
    xml_family.fonts = xml_family.fonts + [
        font_to_xml_font(f, family.fallback_for) for f in family.fonts
    ]

  # Step 7. Build output
  return FallbackXml(aliases=xml_aliases, families=xml_families)


def write_xml(outfile: str, xml: FallbackXml):
  """Writes given xml object into into outfile as XML."""
  familyset = ElementTree.Element('familyset')

  for family in xml.families:
    family_node = ElementTree.SubElement(familyset, 'family')
    if family.lang:
      family_node.set('lang', family.lang)
    if family.name:
      family_node.set('name', family.name)
    if family.variant:
      family_node.set('variant', family.variant)

    for font in family.fonts:
      font_node = ElementTree.SubElement(family_node, 'font')
      if font.weight:
        font_node.set('weight', font.weight)
      if font.style:
        font_node.set('style', font.style)
      if font.index:
        font_node.set('index', font.index)
      if font.supported_axes:
        font_node.set('supportedAxes', font.supported_axes)
      if font.fallback_for:
        font_node.set('fallbackFor', font.fallback_for)
      if font.post_script_name:
        font_node.set('postScriptName', font.post_script_name)

      font_node.text = font.file

      if font.axes:
        for tag, value in font.axes.items():
          axis_node = ElementTree.SubElement(font_node, 'axis')
          axis_node.set('tag', tag)
          axis_node.set('stylevalue', value)

  for alias in xml.aliases:
    alias_node = ElementTree.SubElement(familyset, 'alias')
    alias_node.set('name', alias.name)
    alias_node.set('to', alias.to)
    if alias.weight:
      alias_node.set('weight', alias.weight)

  doc = minidom.parseString(ElementTree.tostring(familyset, 'utf-8'))
  with open(outfile, 'w') as f:
    doc.writexml(f, encoding='utf-8', newl='\n', indent='', addindent='  ')


def main(args: CommandlineArgs):
  xml = generate_xml(args.fallback, args.aliases, args.families)
  write_xml(args.outfile, xml)
