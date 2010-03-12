/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.google.android.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.List;

/**
 *
 * Logic for parsing a text message typed by the user looking for smileys,
 * urls, acronyms,formatting (e.g., '*'s for bold), me commands
 * (e.g., "/me is asleep"), and punctuation.
 *
 * It constructs an array, which breaks the text up into its
 * constituent pieces, which we return to the client.
 *
 */
public abstract class AbstractMessageParser {
/**
 * Interface representing the set of resources needed by a message parser
 *
 * @author jessan (Jessan Hutchison-Quillian)
 */
  public static interface Resources {

    /** Get the known set of URL schemes. */
    public Set<String> getSchemes();

    /** Get the possible values for the last part of a domain name.
     *  Values are expected to be reversed in the Trie.
     */
    public TrieNode getDomainSuffixes();

    /** Get the smileys accepted by the parser. */
    public TrieNode getSmileys();

    /** Get the acronyms accepted by the parser. */
    public TrieNode getAcronyms();
  }

  /**
   * Subclasses must define the schemes, domains, smileys and acronyms
   * that are necessary for parsing
   */
  protected abstract Resources getResources();

  /** Music note that indicates user is listening to a music track. */
  public static final String musicNote = "\u266B ";

  private String text;
  private int nextChar;
  private int nextClass;
  private ArrayList<Part> parts;
  private ArrayList<Token> tokens;
  private HashMap<Character,Format> formatStart;
  private boolean parseSmilies;
  private boolean parseAcronyms;
  private boolean parseFormatting;
  private boolean parseUrls;
  private boolean parseMeText;
  private boolean parseMusic;

  /**
   * Create a message parser to parse urls, formatting, acronyms, smileys,
   * /me text and  music
   *
   * @param text the text to parse
   */
  public AbstractMessageParser(String text) {
    this(text, true, true, true, true, true, true);
  }

  /**
   * Create a message parser, specifying the kinds of text to parse
   *
   * @param text the text to parse
   *
   */
  public AbstractMessageParser(String text, boolean parseSmilies,
      boolean parseAcronyms, boolean parseFormatting, boolean parseUrls,
      boolean parseMusic, boolean parseMeText) {
    this.text = text;
    this.nextChar = 0;
    this.nextClass = 10;
    this.parts = new ArrayList<Part>();
    this.tokens = new ArrayList<Token>();
    this.formatStart = new HashMap<Character,Format>();
    this.parseSmilies = parseSmilies;
    this.parseAcronyms = parseAcronyms;
    this.parseFormatting = parseFormatting;
    this.parseUrls = parseUrls;
    this.parseMusic = parseMusic;
    this.parseMeText = parseMeText;
  }

  /** Returns the raw text being parsed. */
  public final String getRawText() { return text; }

  /** Return the number of parts. */
  public final int getPartCount() { return parts.size(); }

  /** Return the part at the given index. */
  public final Part getPart(int index) { return parts.get(index); }

  /** Return the list of parts from the parsed text */
  public final List<Part> getParts() { return parts; }

  /** Parses the text string into an internal representation. */
  public void parse() {
    // Look for music track (of which there would be only one and it'll be the
    // first token)
    if (parseMusicTrack()) {
      buildParts(null);
      return;
    }

    // Look for me commands.
    String meText = null;
    if (parseMeText && text.startsWith("/me") && (text.length() > 3) &&
        Character.isWhitespace(text.charAt(3))) {
      meText = text.substring(0, 4);
      text = text.substring(4);
    }

    // Break the text into tokens.
    boolean wasSmiley = false;
    while (nextChar < text.length()) {
      if (!isWordBreak(nextChar)) {
        if (!wasSmiley || !isSmileyBreak(nextChar)) {
          throw new AssertionError("last chunk did not end at word break");
        }
      }

      if (parseSmiley()) {
        wasSmiley = true;
      } else {
        wasSmiley = false;

        if (!parseAcronym() && !parseURL() && !parseFormatting()) {
          parseText();
        }
      }
    }

    // Trim the whitespace before and after media components.
    for (int i = 0; i < tokens.size(); ++i) {
      if (tokens.get(i).isMedia()) {
        if ((i > 0) && (tokens.get(i - 1) instanceof Html)) {
          ((Html)tokens.get(i - 1)).trimLeadingWhitespace();
        }
        if ((i + 1 < tokens.size()) && (tokens.get(i + 1) instanceof Html)) {
          ((Html)tokens.get(i + 1)).trimTrailingWhitespace();
        }
      }
    }

    // Remove any empty html tokens.
    for (int i = 0; i < tokens.size(); ++i) {
      if (tokens.get(i).isHtml() &&
          (tokens.get(i).toHtml(true).length() == 0)) {
        tokens.remove(i);
        --i;  // visit this index again
      }
    }

    buildParts(meText);
  }

  /**
   * Get a the appropriate Token for a given URL
   *
   * @param text the anchor text
   * @param url the url
   *
   */
  public static Token tokenForUrl(String url, String text) {
    if(url == null) {
      return null;
    }

    //Look for video links
    Video video = Video.matchURL(url, text);
    if (video != null) {
      return video;
    }

    // Look for video links.
    YouTubeVideo ytVideo = YouTubeVideo.matchURL(url, text);
    if (ytVideo != null) {
      return ytVideo;
    }

    // Look for photo links.
    Photo photo = Photo.matchURL(url, text);
    if (photo != null) {
      return photo;
    }

    // Look for photo links.
    FlickrPhoto flickrPhoto = FlickrPhoto.matchURL(url, text);
    if (flickrPhoto != null) {
      return flickrPhoto;
    }

    //Not media, so must be a regular URL
    return new Link(url, text);
  }

  /**
   * Builds the parts list.
   *
   * @param meText any meText parsed from the message
   */
  private void buildParts(String meText) {
    for (int i = 0; i < tokens.size(); ++i) {
      Token token = tokens.get(i);
      if (token.isMedia() || (parts.size() == 0) || lastPart().isMedia()) {
        parts.add(new Part());
      }
      lastPart().add(token);
    }

    // The first part inherits the meText of the line.
    if (parts.size() > 0) {
      parts.get(0).setMeText(meText);
    }
  }

  /** Returns the last part in the list. */
  private Part lastPart() { return parts.get(parts.size() - 1); }

  /**
   * Looks for a music track (\u266B is first character, everything else is
   * track info).
   */
  private boolean parseMusicTrack() {

    if (parseMusic && text.startsWith(musicNote)) {
      addToken(new MusicTrack(text.substring(musicNote.length())));
      nextChar = text.length();
      return true;
    }
    return false;
  }

  /** Consumes all of the text in the next word . */
  private void parseText() {
    StringBuilder buf = new StringBuilder();
    int start = nextChar;
    do {
      char ch = text.charAt(nextChar++);
      switch (ch) {
        case '<':  buf.append("&lt;"); break;
        case '>':  buf.append("&gt;"); break;
        case '&':  buf.append("&amp;"); break;
        case '"':  buf.append("&quot;"); break;
        case '\'':  buf.append("&apos;"); break;
        case '\n':  buf.append("<br>"); break;
        default:  buf.append(ch); break;
      }
    } while (!isWordBreak(nextChar));

    addToken(new Html(text.substring(start, nextChar), buf.toString()));
  }

  /**
   * Looks for smileys (e.g., ":)") in the text.  The set of known smileys is
   * loaded from a file into a trie at server start.
   */
  private boolean parseSmiley() {
    if(!parseSmilies) {
      return false;
    }
    TrieNode match = longestMatch(getResources().getSmileys(), this, nextChar,
                                  true);
    if (match == null) {
      return false;
    } else {
      int previousCharClass = getCharClass(nextChar - 1);
      int nextCharClass = getCharClass(nextChar + match.getText().length());
      if ((previousCharClass == 2 || previousCharClass == 3)
          && (nextCharClass == 2 || nextCharClass == 3)) {
        return false;
      }
      addToken(new Smiley(match.getText()));
      nextChar += match.getText().length();
      return true;
    }
  }

  /** Looks for acronyms (e.g., "lol") in the text.
   */
  private boolean parseAcronym() {
    if(!parseAcronyms) {
      return false;
    }
    TrieNode match = longestMatch(getResources().getAcronyms(), this, nextChar);
    if (match == null) {
      return false;
    } else {
      addToken(new Acronym(match.getText(), match.getValue()));
      nextChar += match.getText().length();
      return true;
    }
  }

  /** Determines if this is an allowable domain character. */
  private boolean isDomainChar(char c) {
    return c == '-' || Character.isLetter(c) || Character.isDigit(c);
  }

  /** Determines if the given string is a valid domain. */
  private boolean isValidDomain(String domain) {
    // For hostnames, check that it ends with a known domain suffix
    if (matches(getResources().getDomainSuffixes(), reverse(domain))) {
      return true;
    }
    return false;
  }

  /**
   * Looks for a URL in two possible forms:  either a proper URL with a known
   * scheme or a domain name optionally followed by a path, query, or query.
   */
  private boolean parseURL() {
    // Make sure this is a valid place to start a URL.
    if (!parseUrls || !isURLBreak(nextChar)) {
      return false;
    }

    int start = nextChar;

    // Search for the first block of letters.
    int index = start;
    while ((index < text.length()) && isDomainChar(text.charAt(index))) {
      index += 1;
    }

    String url = "";
    boolean done = false;

    if (index == text.length()) {
      return false;
    } else if (text.charAt(index) == ':') {
      // Make sure this is a known scheme.
      String scheme = text.substring(nextChar, index);
      if (!getResources().getSchemes().contains(scheme)) {
        return false;
      }
    } else if (text.charAt(index) == '.') {
      // Search for the end of the domain name.
      while (index < text.length()) {
        char ch = text.charAt(index);
        if ((ch != '.') && !isDomainChar(ch)) {
          break;
        } else {
          index += 1;
        }
      }

      // Make sure the domain name has a valid suffix.  Since tries look for
      // prefix matches, we reverse all the strings to get suffix comparisons.
      String domain = text.substring(nextChar, index);
      if (!isValidDomain(domain)) {
        return false;
      }

      // Search for a port.  We deal with this specially because a colon can
      // also be a punctuation character.
      if ((index + 1 < text.length()) && (text.charAt(index) == ':')) {
        char ch = text.charAt(index + 1);
        if (Character.isDigit(ch)) {
          index += 1;
          while ((index < text.length()) &&
                 Character.isDigit(text.charAt(index))) {
            index += 1;
          }
        }
      }

      // The domain name should be followed by end of line, whitespace,
      // punctuation, or a colon, slash, question, or hash character.  The
      // tricky part here is that some URL characters are also punctuation, so
      // we need to distinguish them.  Since we looked for ports above, a colon
      // is always punctuation here.  To distinguish '?' cases, we look at the
      // character that follows it.
      if (index == text.length()) {
        done = true;
      } else {
        char ch = text.charAt(index);
        if (ch == '?') {
          // If the next character is whitespace or punctuation (or missing),
          // then this question mark looks like punctuation.
          if (index + 1 == text.length()) {
            done = true;
          } else {
            char ch2 = text.charAt(index + 1);
            if (Character.isWhitespace(ch2) || isPunctuation(ch2)) {
              done = true;
            }
          }
        } else if (isPunctuation(ch)) {
          done = true;
        } else if (Character.isWhitespace(ch)) {
          done = true;
        } else if ((ch == '/') || (ch == '#')) {
          // In this case, the URL is not done.  We will search for the end of
          // it below.
        } else {
          return false;
        }
      }

      // We will assume the user meant HTTP.  (One weird case is where they
      // type a port of 443.  That could mean HTTPS, but they might also want
      // HTTP.  We'll let them specify if they don't want HTTP.)
      url = "http://";
    } else {
      return false;
    }

    // If the URL is not done, search for the end, which is just before the
    // next whitespace character.
    if (!done) {
      while ((index < text.length()) &&
             !Character.isWhitespace(text.charAt(index))) {
        index += 1;
      }
    }

    String urlText = text.substring(start, index);
    url += urlText;

    // Figure out the appropriate token type.
    addURLToken(url, urlText);

    nextChar = index;
    return true;
  }

  /**
   * Adds the appropriate token for the given URL.  This might be a simple
   * link or it might be a recognized media type.
   */
  private void addURLToken(String url, String text) {
     addToken(tokenForUrl(url, text));
  }

  /**
   * Deal with formatting characters.
   *
   * Parsing is as follows:
   *  - Treat all contiguous strings of formatting characters as one block.
   *    (This method processes one block.)
   *  - Only a single instance of a particular format character within a block
   *    is used to determine whether to turn on/off that type of formatting;
   *    other instances simply print the character itself.
   *  - If the format is to be turned on, we use the _first_ instance; if it
   *    is to be turned off, we use the _last_ instance (by appending the
   *    format.)
   *
   * Example:
   *   **string** turns into <b>*string*</b>
   */
  private boolean parseFormatting() {
    if(!parseFormatting) {
      return false;
    }
    int endChar = nextChar;
    while ((endChar < text.length()) && isFormatChar(text.charAt(endChar))) {
      endChar += 1;
    }

    if ((endChar == nextChar) || !isWordBreak(endChar)) {
      return false;
    }

    // Keeps track of whether we've seen a character (in map if we've seen it)
    // and whether we should append a closing format token (if value in
    // map is TRUE).  Linked hashmap for consistent ordering.
    LinkedHashMap<Character, Boolean> seenCharacters =
        new LinkedHashMap<Character, Boolean>();

    for (int index = nextChar; index < endChar; ++index) {
      char ch = text.charAt(index);
      Character key = Character.valueOf(ch);
      if (seenCharacters.containsKey(key)) {
        // Already seen this character, just append an unmatched token, which
        // will print plaintext character
        addToken(new Format(ch, false));
      } else {
        Format start = formatStart.get(key);
        if (start != null) {
          // Match the start token, and ask an end token to be appended
          start.setMatched(true);
          formatStart.remove(key);
          seenCharacters.put(key, Boolean.TRUE);
        } else {
          // Append start token
          start = new Format(ch, true);
          formatStart.put(key, start);
          addToken(start);
          seenCharacters.put(key, Boolean.FALSE);
        }
      }
    }

    // Append any necessary end tokens
    for (Character key : seenCharacters.keySet()) {
      if (seenCharacters.get(key) == Boolean.TRUE) {
        Format end = new Format(key.charValue(), false);
        end.setMatched(true);
        addToken(end);
      }
    }

    nextChar = endChar;
    return true;
  }

  /** Determines whether the given index could be a possible word break. */
  private boolean isWordBreak(int index) {
    return getCharClass(index - 1) != getCharClass(index);
  }

  /** Determines whether the given index could be a possible smiley break. */
  private boolean isSmileyBreak(int index) {
    if (index > 0 && index < text.length()) {
      if (isSmileyBreak(text.charAt(index - 1), text.charAt(index))) {
        return true;
      }
    }

    return false;
  }

  /**
   * Verifies that the character before the given index is end of line,
   * whitespace, or punctuation.
   */
  private boolean isURLBreak(int index) {
    switch (getCharClass(index - 1)) {
      case 2:
      case 3:
      case 4:
        return false;

      case 0:
      case 1:
      default:
        return true;
    }
  }

  /** Returns the class for the character at the given index. */
  private int getCharClass(int index) {
    if ((index < 0) || (text.length() <= index)) {
      return 0;
    }

    char ch = text.charAt(index);
    if (Character.isWhitespace(ch)) {
      return 1;
    } else if (Character.isLetter(ch)) {
      return 2;
    } else if (Character.isDigit(ch)) {
      return 3;
    } else if (isPunctuation(ch)) {
      // For punctuation, we return a unique value every time so that they are
      // always different from any other character.  Punctuation should always
      // be considered a possible word break.
      return ++nextClass;
    } else {
      return 4;
    }
  }

  /**
   * Returns true if <code>c1</code> could be the last character of
   * a smiley and <code>c2</code> could be the first character of
   * a different smiley, if {@link #isWordBreak} would not already
   * recognize that this is possible.
   */
  private static boolean isSmileyBreak(char c1, char c2) {
    switch (c1) {
      /*    
       * These characters can end smileys, but don't normally end words.
       */
      case '$': case '&': case '*': case '+': case '-':
      case '/': case '<': case '=': case '>': case '@':
      case '[': case '\\': case ']': case '^': case '|':
      case '}': case '~':
        switch (c2) {
          /*
           * These characters can begin smileys, but don't normally
           * begin words.
           */
          case '#': case '$': case '%': case '*': case '/':
          case '<': case '=': case '>': case '@': case '[':
          case '\\': case '^': case '~':
            return true;
        }
    }

    return false;
  }

  /** Determines whether the given character is punctuation. */
  private static boolean isPunctuation(char ch) {
    switch (ch) {
      case '.': case ',': case '"': case ':': case ';':
      case '?': case '!': case '(': case ')':
        return true;

      default:
        return false;
    }
  }

  /**
   * Determines whether the given character is the beginning or end of a
   * section with special formatting.
   */
  private static boolean isFormatChar(char ch) {
    switch (ch) {
      case '*': case '_': case '^':
        return true;

      default:
        return false;
    }
  }

  /** Represents a unit of parsed output. */
  public static abstract class Token {
    public enum Type {

      HTML ("html"),
      FORMAT ("format"),  // subtype of HTML
      LINK ("l"),
      SMILEY ("e"),
      ACRONYM ("a"),
      MUSIC ("m"),
      GOOGLE_VIDEO ("v"),
      YOUTUBE_VIDEO ("yt"),
      PHOTO ("p"),
      FLICKR ("f");

      //stringreps for HTML and FORMAT don't really matter
      //because they don't define getInfo(), which is where it is used
      //For the other types, code depends on their stringreps
      private String stringRep;

      Type(String stringRep) {
        this.stringRep = stringRep;
      }

      /** {@inheritDoc} */
      public String toString() {
        return this.stringRep;
      }
    }

    protected Type type;
    protected String text;

    protected Token(Type type, String text) {
      this.type = type;
      this.text = text;
    }

    /** Returns the type of the token. */
    public Type getType() { return type; }

    /**
     * Get the relevant information about a token
     *
     * @return a list of strings representing the token, not null
     *         The first item is always a string representation of the type
     */
    public List<String> getInfo() {
      List<String> info = new ArrayList<String>();
      info.add(getType().toString());
      return info;
    }

    /** Returns the raw text of the token. */
    public String getRawText() { return text; }

    public boolean isMedia() { return false; }
    public abstract boolean isHtml();
    public boolean isArray() { return !isHtml(); }

    public String toHtml(boolean caps) { throw new AssertionError("not html"); }

    // The token can change the caps of the text after that point.
    public boolean controlCaps() { return false; }
    public boolean setCaps() { return false; }
  }

  /** Represents a simple string of html text. */
  public static class Html extends Token {
    private String html;

    public Html(String text, String html) {
      super(Type.HTML, text);
      this.html = html;
    }

    public boolean isHtml() { return true; }
    public String toHtml(boolean caps) {
      return caps ? html.toUpperCase() : html;
    }
    /**
     * Not supported. Info should not be needed for this type
     */
    public List<String> getInfo() {
      throw new UnsupportedOperationException();
    }

    public void trimLeadingWhitespace() {
      text = trimLeadingWhitespace(text);
      html = trimLeadingWhitespace(html);
    }

    public void trimTrailingWhitespace() {
      text = trimTrailingWhitespace(text);
      html = trimTrailingWhitespace(html);
    }

    private static String trimLeadingWhitespace(String text) {
      int index = 0;
      while ((index < text.length()) &&
             Character.isWhitespace(text.charAt(index))) {
        ++index;
      }
      return text.substring(index);
    }

    public static String trimTrailingWhitespace(String text) {
      int index = text.length();
      while ((index > 0) && Character.isWhitespace(text.charAt(index - 1))) {
        --index;
      }
      return text.substring(0, index);
    }
  }

  /** Represents a music track token at the beginning. */
  public static class MusicTrack extends Token {
    private String track;

    public MusicTrack(String track) {
      super(Type.MUSIC, track);
      this.track = track;
    }

    public String getTrack() { return track; }

    public boolean isHtml() { return false; }

    public List<String> getInfo() {
      List<String> info = super.getInfo();
      info.add(getTrack());
      return info;
    }
  }

  /** Represents a link that was found in the input. */
  public static class Link extends Token {
    private String url;

    public Link(String url, String text) {
      super(Type.LINK, text);
      this.url = url;
    }

    public String getURL() { return url; }

    public boolean isHtml() { return false; }

    public List<String> getInfo() {
      List<String> info = super.getInfo();
      info.add(getURL());
      info.add(getRawText());
      return info;
    }
  }

  /** Represents a link to a Google Video. */
  public static class Video extends Token {
    /** Pattern for a video URL. */
    private static final Pattern URL_PATTERN = Pattern.compile(
        "(?i)http://video\\.google\\.[a-z0-9]+(?:\\.[a-z0-9]+)?/videoplay\\?"
        + ".*?\\bdocid=(-?\\d+).*");

    private String docid;

    public Video(String docid, String text) {
      super(Type.GOOGLE_VIDEO, text);
      this.docid = docid;
    }

    public String getDocID() { return docid; }

    public boolean isHtml() { return false; }
    public boolean isMedia() { return true; }

    /** Returns a Video object if the given url is to a video. */
    public static Video matchURL(String url, String text) {
      Matcher m = URL_PATTERN.matcher(url);
      if (m.matches()) {
        return new Video(m.group(1), text);
      } else {
        return null;
      }
    }

    public List<String> getInfo() {
      List<String> info = super.getInfo();
      info.add(getRssUrl(docid));
      info.add(getURL(docid));
      return info;
    }

    /** Returns the URL for the RSS description of the given video. */
    public static String getRssUrl(String docid) {
      return "http://video.google.com/videofeed"
             + "?type=docid&output=rss&sourceid=gtalk&docid=" + docid;
    }

    /** (For testing purposes:) Returns a video URL with the given parts.  */
    public static String getURL(String docid) {
      return getURL(docid, null);
    }

    /** (For testing purposes:) Returns a video URL with the given parts.  */
    public static String getURL(String docid, String extraParams) {
      if (extraParams == null) {
        extraParams = "";
      } else if (extraParams.length() > 0) {
        extraParams += "&";
      }
      return "http://video.google.com/videoplay?" + extraParams
             + "docid=" + docid;
    }
  }

  /** Represents a link to a YouTube video. */
  public static class YouTubeVideo extends Token {
    /** Pattern for a video URL. */
    private static final Pattern URL_PATTERN = Pattern.compile(
        "(?i)http://(?:[a-z0-9]+\\.)?youtube\\.[a-z0-9]+(?:\\.[a-z0-9]+)?/watch\\?"
        + ".*\\bv=([-_a-zA-Z0-9=]+).*");

    private String docid;

    public YouTubeVideo(String docid, String text) {
      super(Type.YOUTUBE_VIDEO, text);
      this.docid = docid;
    }

    public String getDocID() { return docid; }

    public boolean isHtml() { return false; }
    public boolean isMedia() { return true; }

    /** Returns a Video object if the given url is to a video. */
    public static YouTubeVideo matchURL(String url, String text) {
      Matcher m = URL_PATTERN.matcher(url);
      if (m.matches()) {
        return new YouTubeVideo(m.group(1), text);
      } else {
        return null;
      }
    }

    public List<String> getInfo() {
      List<String> info = super.getInfo();
      info.add(getRssUrl(docid));
      info.add(getURL(docid));
      return info;
    }

    /** Returns the URL for the RSS description of the given video. */
    public static String getRssUrl(String docid) {
      return "http://youtube.com/watch?v=" + docid;
    }

    /** (For testing purposes:) Returns a video URL with the given parts.  */
    public static String getURL(String docid) {
      return getURL(docid, null);
    }

    /** (For testing purposes:) Returns a video URL with the given parts.  */
    public static String getURL(String docid, String extraParams) {
      if (extraParams == null) {
        extraParams = "";
      } else if (extraParams.length() > 0) {
        extraParams += "&";
      }
      return "http://youtube.com/watch?" + extraParams + "v=" + docid;
    }

    /** (For testing purposes:) Returns a video URL with the given parts.
      * @param http If true, includes http://
      * @param prefix If non-null/non-blank, adds to URL before youtube.com.
      *   (e.g., prefix="br." --> "br.youtube.com")
      */
    public static String getPrefixedURL(boolean http, String prefix,
                                        String docid, String extraParams) {
      String protocol = "";

      if (http) {
        protocol = "http://";
      }

      if (prefix == null) {
        prefix = "";
      }

      if (extraParams == null) {
        extraParams = "";
      } else if (extraParams.length() > 0) {
        extraParams += "&";
      }

      return protocol + prefix + "youtube.com/watch?" + extraParams + "v=" +
              docid;
    }
  }

  /** Represents a link to a Picasa photo or album. */
  public static class Photo extends Token {
    /** Pattern for an album or photo URL. */
    // TODO (katyarogers) searchbrowse includes search lists and tags,
    // it follows a different pattern than albums - would be nice to add later
    private static final Pattern URL_PATTERN = Pattern.compile(
        "http://picasaweb.google.com/([^/?#&]+)/+((?!searchbrowse)[^/?#&]+)(?:/|/photo)?(?:\\?[^#]*)?(?:#(.*))?");

    private String user;
    private String album;
    private String photo;  // null for albums

    public Photo(String user, String album, String photo, String text) {
      super(Type.PHOTO, text);
      this.user = user;
      this.album = album;
      this.photo = photo;
    }

    public String getUser() { return user; }
    public String getAlbum() { return album; }
    public String getPhoto() { return photo; }

    public boolean isHtml() { return false; }
    public boolean isMedia() { return true; }

    /** Returns a Photo object if the given url is to a photo or album. */
    public static Photo matchURL(String url, String text) {
      Matcher m = URL_PATTERN.matcher(url);
      if (m.matches()) {
        return new Photo(m.group(1), m.group(2), m.group(3), text);
      } else {
        return null;
      }
    }

    public List<String> getInfo() {
      List<String> info = super.getInfo();
      info.add(getRssUrl(getUser()));
      info.add(getAlbumURL(getUser(), getAlbum()));
      if (getPhoto() != null) {
        info.add(getPhotoURL(getUser(), getAlbum(), getPhoto()));
      } else {
        info.add((String)null);
      }
      return info;
    }

    /** Returns the URL for the RSS description of the user's albums. */
    public static String getRssUrl(String user) {
      return "http://picasaweb.google.com/data/feed/api/user/" + user +
        "?category=album&alt=rss";
    }

    /** Returns the URL for an album. */
    public static String getAlbumURL(String user, String album) {
      return "http://picasaweb.google.com/" + user + "/" + album;
    }

    /** Returns the URL for a particular photo. */
    public static String getPhotoURL(String user, String album, String photo) {
      return "http://picasaweb.google.com/" + user + "/" + album + "/photo#"
             + photo;
    }
  }

  /** Represents a link to a Flickr photo or album. */
  public static class FlickrPhoto extends Token {
    /** Pattern for a user album or photo URL. */
    private static final Pattern URL_PATTERN = Pattern.compile(
        "http://(?:www.)?flickr.com/photos/([^/?#&]+)/?([^/?#&]+)?/?.*");
    private static final Pattern GROUPING_PATTERN = Pattern.compile(
        "http://(?:www.)?flickr.com/photos/([^/?#&]+)/(tags|sets)/" +
        "([^/?#&]+)/?");

    private static final String SETS = "sets";
    private static final String TAGS = "tags";

    private String user;
    private String photo;      // null for user album
    private String grouping;   // either "tags" or "sets"
    private String groupingId; // sets or tags identifier

    public FlickrPhoto(String user, String photo, String grouping,
                       String groupingId, String text) {
      super(Type.FLICKR, text);

      /* System wide tags look like the URL to a Flickr user. */
      if (!TAGS.equals(user)) {
        this.user = user;
        // Don't consider slide show URL a photo
        this.photo = (!"show".equals(photo) ? photo : null);
        this.grouping = grouping;
        this.groupingId = groupingId;
      } else {
        this.user = null;
        this.photo = null;
        this.grouping = TAGS;
        this.groupingId = photo;
      }
    }

    public String getUser() { return user; }
    public String getPhoto() { return photo; }
    public String getGrouping() { return grouping; }
    public String getGroupingId() { return groupingId; }

    public boolean isHtml() { return false; }
    public boolean isMedia() { return true; }

    /**
     * Returns a FlickrPhoto object if the given url is to a photo or Flickr
     * user.
     */
    public static FlickrPhoto matchURL(String url, String text) {
      Matcher m = GROUPING_PATTERN.matcher(url);
      if (m.matches()) {
        return new FlickrPhoto(m.group(1), null, m.group(2), m.group(3), text);
      }

      m = URL_PATTERN.matcher(url);
      if (m.matches()) {
        return new FlickrPhoto(m.group(1), m.group(2), null, null, text);
      } else {
        return null;
      }
    }

    public List<String> getInfo() {
      List<String> info = super.getInfo();
      info.add(getUrl());
      info.add(getUser() != null ? getUser() : "");
      info.add(getPhoto() != null ? getPhoto() : "");
      info.add(getGrouping() != null ? getGrouping() : "");
      info.add(getGroupingId() != null ? getGroupingId() : "");
      return info;
    }

    public String getUrl() {
      if (SETS.equals(grouping)) {
        return getUserSetsURL(user, groupingId);
      } else if (TAGS.equals(grouping)) {
        if (user != null) {
          return getUserTagsURL(user, groupingId);
        } else {
          return getTagsURL(groupingId);
        }
      } else if (photo != null) {
        return getPhotoURL(user, photo);
      } else {
        return getUserURL(user);
      }
    }

    /** Returns the URL for the RSS description. */
    public static String getRssUrl(String user) {
      return null;
    }

    /** Returns the URL for a particular tag. */
    public static String getTagsURL(String tag) {
      return "http://flickr.com/photos/tags/" + tag;
    }

    /** Returns the URL to the user's Flickr homepage. */
    public static String getUserURL(String user) {
      return "http://flickr.com/photos/" + user;
    }

    /** Returns the URL for a particular photo. */
    public static String getPhotoURL(String user, String photo) {
      return "http://flickr.com/photos/" + user + "/" + photo;
    }

    /** Returns the URL for a user tag photo set. */
    public static String getUserTagsURL(String user, String tagId) {
      return "http://flickr.com/photos/" + user + "/tags/" + tagId;
    }

    /** Returns the URL for user set. */
    public static String getUserSetsURL(String user, String setId) {
      return "http://flickr.com/photos/" + user + "/sets/" + setId;
    }
  }

  /** Represents a smiley that was found in the input. */
  public static class Smiley extends Token {
    // TODO: Pass the SWF URL down to the client.

    public Smiley(String text) {
      super(Type.SMILEY, text);
    }

    public boolean isHtml() { return false; }

    public List<String> getInfo() {
      List<String> info = super.getInfo();
      info.add(getRawText());
      return info;
    }
  }

  /** Represents an acronym that was found in the input. */
  public static class Acronym extends Token {
    private String value;
    // TODO: SWF

    public Acronym(String text, String value) {
      super(Type.ACRONYM, text);
      this.value = value;
    }

    public String getValue() { return value; }

    public boolean isHtml() { return false; }

    public List<String> getInfo() {
      List<String> info = super.getInfo();
      info.add(getRawText());
      info.add(getValue());
      return info;
    }
  }

  /** Represents a character that changes formatting. */
  public static class Format extends Token {
    private char ch;
    private boolean start;
    private boolean matched;

    public Format(char ch, boolean start) {
      super(Type.FORMAT, String.valueOf(ch));
      this.ch = ch;
      this.start = start;
    }

    public void setMatched(boolean matched) { this.matched = matched; }

    public boolean isHtml() { return true; }

    public String toHtml(boolean caps) {
      // This character only implies special formatting if it was matched.
      // Otherwise, it was just a plain old character.
      if (matched) {
        return start ? getFormatStart(ch) : getFormatEnd(ch);
      } else {
        // We have to make sure we escape HTML characters as usual.
        return (ch == '"') ? "&quot;" : String.valueOf(ch);
      }
    }

    /**
     * Not supported. Info should not be needed for this type
     */
    public List<String> getInfo() {
      throw new UnsupportedOperationException();
    }

    public boolean controlCaps() { return (ch == '^'); }
    public boolean setCaps() { return start; }

    private String getFormatStart(char ch) {
      switch (ch) {
        case '*': return "<b>";
        case '_': return "<i>";
        case '^': return "<b><font color=\"#005FFF\">"; // TODO: all caps
        case '"': return "<font color=\"#999999\">\u201c";
        default: throw new AssertionError("unknown format '" + ch + "'");
      }
    }

    private String getFormatEnd(char ch) {
      switch (ch) {
        case '*': return "</b>";
        case '_': return "</i>";
        case '^': return "</font></b>"; // TODO: all caps
        case '"': return "\u201d</font>";
        default: throw new AssertionError("unknown format '" + ch + "'");
      }
    }
  }

  /** Adds the given token to the parsed output. */
  private void addToken(Token token) {
    tokens.add(token);
  }

  /** Converts the entire message into a single HTML display string. */
  public String toHtml() {
    StringBuilder html = new StringBuilder();

    for (Part part : parts) {
      boolean caps = false;

      html.append("<p>");
      for (Token token : part.getTokens()) {
        if (token.isHtml()) {
          html.append(token.toHtml(caps));
        } else {
          switch (token.getType()) {
          case LINK:
            html.append("<a href=\"");
            html.append(((Link)token).getURL());
            html.append("\">");
            html.append(token.getRawText());
            html.append("</a>");
            break;

          case SMILEY:
            // TODO: link to an appropriate image
            html.append(token.getRawText());
            break;

          case ACRONYM:
            html.append(token.getRawText());
            break;

          case MUSIC:
            // TODO: include a music glyph
            html.append(((MusicTrack)token).getTrack());
            break;

          case GOOGLE_VIDEO:
            // TODO: include a Google Video icon
            html.append("<a href=\"");
            html.append(((Video)token).getURL(((Video)token).getDocID()));
            html.append("\">");
            html.append(token.getRawText());
            html.append("</a>");
            break;

          case YOUTUBE_VIDEO:
            // TODO: include a YouTube icon
            html.append("<a href=\"");
            html.append(((YouTubeVideo)token).getURL(
                ((YouTubeVideo)token).getDocID()));
            html.append("\">");
            html.append(token.getRawText());
            html.append("</a>");
            break;

          case PHOTO: {
            // TODO: include a Picasa Web icon
            html.append("<a href=\"");
            html.append(Photo.getAlbumURL(
                ((Photo)token).getUser(), ((Photo)token).getAlbum()));
            html.append("\">");
            html.append(token.getRawText());
            html.append("</a>");
            break;
          }

          case FLICKR:
            // TODO: include a Flickr icon
            Photo p = (Photo) token;
            html.append("<a href=\"");
            html.append(((FlickrPhoto)token).getUrl());
            html.append("\">");
            html.append(token.getRawText());
            html.append("</a>");
            break;

          default:
            throw new AssertionError("unknown token type: " + token.getType());
          }
        }

        if (token.controlCaps()) {
          caps = token.setCaps();
        }
      }
      html.append("</p>\n");
    }

    return html.toString();
  }

  /** Returns the reverse of the given string. */
  protected static String reverse(String str) {
    StringBuilder buf = new StringBuilder();
    for (int i = str.length() - 1; i >= 0; --i) {
      buf.append(str.charAt(i));
    }
    return buf.toString();
  }

  public static class TrieNode {
    private final HashMap<Character,TrieNode> children =
        new HashMap<Character,TrieNode>();
    private String text;
    private String value;

    public TrieNode() { this(""); }
    public TrieNode(String text) {
      this.text = text;
    }

    public final boolean exists() { return value != null; }
    public final String getText() { return text; }
    public final String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public TrieNode getChild(char ch) {
      return children.get(Character.valueOf(ch));
    }

    public TrieNode getOrCreateChild(char ch) {
      Character key = Character.valueOf(ch);
      TrieNode node = children.get(key);
      if (node == null) {
        node = new TrieNode(text + String.valueOf(ch));
        children.put(key, node);
      }
      return node;
    }

    /** Adds the given string into the trie. */
    public static  void addToTrie(TrieNode root, String str, String value) {
      int index = 0;
      while (index < str.length()) {
        root = root.getOrCreateChild(str.charAt(index++));
      }
      root.setValue(value);
    }
  }



  /** Determines whether the given string is in the given trie. */
  private static boolean matches(TrieNode root, String str) {
    int index = 0;
    while (index < str.length()) {
      root = root.getChild(str.charAt(index++));
      if (root == null) {
        break;
      } else if (root.exists()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the longest substring of the given string, starting at the given
   * index, that exists in the trie.
   */
  private static TrieNode longestMatch(
      TrieNode root, AbstractMessageParser p, int start) {
    return longestMatch(root, p, start, false);
  }

  /**
   * Returns the longest substring of the given string, starting at the given
   * index, that exists in the trie, with a special tokenizing case for
   * smileys if specified.
   */
  private static TrieNode longestMatch(
      TrieNode root, AbstractMessageParser p, int start, boolean smiley) {
    int index = start;
    TrieNode bestMatch = null;
    while (index < p.getRawText().length()) {
      root = root.getChild(p.getRawText().charAt(index++));
      if (root == null) {
        break;
      } else if (root.exists()) {
        if (p.isWordBreak(index)) {
          bestMatch = root;
        } else if (smiley && p.isSmileyBreak(index)) {
          bestMatch = root;
        }
      }
    }
    return bestMatch;
  }


  /** Represents set of tokens that are delivered as a single message. */
  public static class Part {
    private String meText;
    private ArrayList<Token> tokens;

    public Part() {
      this.tokens = new ArrayList<Token>();
    }

    public String getType(boolean isSend) {
      return (isSend ? "s" : "r") + getPartType();
    }

    private String getPartType() {
      if (isMedia()) {
        return "d";
      } else if (meText != null) {
        return "m";
      } else {
        return "";
      }
    }

    public boolean isMedia() {
      return (tokens.size() == 1) && tokens.get(0).isMedia();
    }
    /**
     * Convenience method for getting the Token of a Part that represents
     * a media Token. Parts of this kind will always only have a single Token
     *
     * @return if this.isMedia(),
     *         returns the Token representing the media contained in this Part,
     *         otherwise returns null;
     */
    public Token getMediaToken() {
      if(isMedia()) {
        return tokens.get(0);
      }
      return null;
    }

    /** Adds the given token to this part. */
    public void add(Token token) {
      if (isMedia()) {
        throw new AssertionError("media ");
      }
       tokens.add(token);
    }

    public void setMeText(String meText) {
      this.meText = meText;
    }

    /** Returns the original text of this part. */
    public String getRawText() {
      StringBuilder buf = new StringBuilder();
      if (meText != null) {
        buf.append(meText);
      }
      for (int i = 0; i < tokens.size(); ++i) {
        buf.append(tokens.get(i).getRawText());
      }
      return buf.toString();
    }

    /** Returns the tokens in this part. */
    public ArrayList<Token> getTokens() { return tokens; }

    /** Adds the tokens into the given builder as an array. */
//    public void toArray(JSArrayBuilder array) {
//      if (isMedia()) {
//        // For media, we send its array (i.e., we don't wrap this in another
//        // array as we do for non-media parts).
//        tokens.get(0).toArray(array);
//      } else {
//        array.beginArray();
//        addToArray(array);
//        array.endArray();
//      }
//    }
  }
}
