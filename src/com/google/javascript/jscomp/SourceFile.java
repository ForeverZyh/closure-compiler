/*
 * Copyright 2009 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.GwtIncompatible;
import com.google.common.io.CharStreams;
import com.google.javascript.jscomp.serialization.SourceFileProto;
import com.google.javascript.jscomp.serialization.SourceFileProto.FileOnDisk;
import com.google.javascript.jscomp.serialization.SourceFileProto.ZipEntryOnDisk;
import com.google.javascript.rhino.StaticSourceFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Reader;
import java.io.Serializable;
import java.io.StringReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.annotation.Nullable;

/**
 * An abstract representation of a source file that provides access to language-neutral features.
 *
 * <p>The source file can be loaded from various locations, such as from disk or from a preloaded
 * string. Loading is done as lazily as possible to minimize IO delays and memory cost of source
 * text.
 */
public final class SourceFile implements StaticSourceFile, Serializable {

  private static final long serialVersionUID = 1L;
  private static final String UTF8_BOM = "\uFEFF";

  private static final String BANG_SLASH = "!" + Platform.getFileSeperator();

  /**
   * Number of lines in the region returned by {@link #getRegion(int)}.
   * This length must be odd.
   */
  private static final int SOURCE_EXCERPT_REGION_LENGTH = 5;

  /**
   * The file name of the source file.
   *
   * <p>It does not necessarily need to correspond to a real path. But it should be unique. Will
   * appear in warning messages emitted by the compiler.
   */
  private final String fileName;

  private SourceKind kind;

  private final CodeLoader loader;

  // Source Line Information
  private transient int[] lineOffsets = null;

  private transient volatile String code = null;

  private SourceFile(CodeLoader loader, String fileName, SourceKind kind) {
    if (isNullOrEmpty(fileName)) {
      throw new IllegalArgumentException("a source must have a name");
    }

    if (!"/".equals(Platform.getFileSeperator())) {
      fileName = fileName.replace(Platform.getFileSeperator(), "/");
    }

    this.loader = loader;
    this.fileName = fileName;
    this.kind = kind;
  }

  @Override
  public int getLineOffset(int lineno) {
    findLineOffsets();
    if (lineno < 1 || lineno > lineOffsets.length) {
      throw new IllegalArgumentException(
          "Expected line number between 1 and " + lineOffsets.length +
          "\nActual: " + lineno);
    }
    return lineOffsets[lineno - 1];
  }

  private void findLineOffsets() {
    if (this.lineOffsets != null) {
      return;
    }

    String localCode = this.code;
    if (localCode == null) {
      try {
        localCode = this.getCode();
      } catch (IOException e) {
        localCode = "";
      }
    }

    int[] offsets = new int[256]; // default is arbitrary. Assume most files are around 200 lines
    int index = 1; // start at 1 since the offset for line 0 is always at byte 0
    int offset = 0;
    while ((offset = localCode.indexOf('\n', offset)) != -1) {
      // +1 because this is the offset of the next line which is one past the newline
      offset++;
      offsets[index++] = offset;
      if (index == offsets.length) {
        offsets = Arrays.copyOf(offsets, offsets.length * 2);
      }
    }
    this.lineOffsets = index == offsets.length ? offsets : Arrays.copyOf(offsets, index);
  }

  /** Gets all the code in this source file. */
  public final String getCode() throws IOException {
    if (this.code == null) {
      // Only synchronize if we need to
      synchronized (this) {
        // Make sure another thread hasn't loaded the code while we waited.
        if (this.code == null) {
          this.setCodeAndDoBookkeeping(this.loader.loadUncachedCode());
        }
      }
    }

    return this.code;
  }

  @Deprecated
  final void setCodeDeprecated(String code) {
    this.setCodeAndDoBookkeeping(code);
  }

  /**
   * Gets a reader for the code in this source file.
   */
  @GwtIncompatible("java.io.Reader")
  public Reader getCodeReader() throws IOException {
    // Only synchronize if we need to
    if (this.code == null) {
      synchronized (this) {
        // Make sure another thread hasn't loaded the code while we waited.
        if (this.code == null) {
          Reader uncachedReader = this.loader.openUncachedReader();
          if (uncachedReader != null) {
            return uncachedReader;
          }
        }
      }
    }

    return new StringReader(this.getCode());
  }

  private void setCodeAndDoBookkeeping(String sourceCode) {
    this.code = null;
    this.lineOffsets = null;

    if (sourceCode != null) {
      if (sourceCode.startsWith(UTF8_BOM)) {
        sourceCode = sourceCode.substring(UTF8_BOM.length());
      }

      this.code = sourceCode;
    }
  }

  /** @deprecated alias of {@link #getName()}. Use that instead */
  @Deprecated
  public String getOriginalPath() {
    return this.getName();
  }

  /**
   * For SourceFile types which cache source code that can be regenerated easily, flush the cache.
   *
   * <p>We maintain the cache mostly to speed up generating source when displaying error messages,
   * so dumping the file contents after the compile is a fine thing to do.
   */
  public void clearCachedSource() {
    this.setCodeAndDoBookkeeping(null);
  }

  boolean hasSourceInMemory() {
    return code != null;
  }

  /**
   * Returns a unique name for the source file.
   *
   * <p>This name is not required to be an actual file path on disk.
   */
  @Override
  public String getName() {
    return fileName;
  }

  /** Returns the source kind. */
  @Override
  public SourceKind getKind() {
    return kind;
  }

  /** Sets the source kind. */
  public void setKind(SourceKind kind) {
    this.kind = kind;
  }

  @Override
  public int getLineOfOffset(int offset) {
    findLineOffsets();
    int search = Arrays.binarySearch(lineOffsets, offset);
    if (search >= 0) {
      return search + 1; // lines are 1-based.
    } else {
      int insertionPoint = -1 * (search + 1);
      return min(insertionPoint - 1, lineOffsets.length - 1) + 1;
    }
  }

  @Override
  public int getColumnOfOffset(int offset) {
    int line = getLineOfOffset(offset);
    return offset - lineOffsets[line - 1];
  }

  /**
   * Gets the source line for the indicated line number.
   *
   * @param lineNumber the line number, 1 being the first line of the file.
   * @return The line indicated. Does not include the newline at the end
   *     of the file. Returns {@code null} if it does not exist,
   *     or if there was an IO exception.
   */
  public String getLine(int lineNumber) {
    findLineOffsets();
    if (lineNumber > lineOffsets.length) {
      return null;
    }

    if (lineNumber < 1) {
      lineNumber = 1;
    }

    int pos = lineOffsets[lineNumber - 1];
    String js = "";
    try {
      // NOTE(nicksantos): Right now, this is optimized for few warnings.
      // This is probably the right trade-off, but will be slow if there
      // are lots of warnings in one file.
      js = getCode();
    } catch (IOException e) {
      return null;
    }

    if (js.indexOf('\n', pos) == -1) {
      // If next new line cannot be found, there are two cases
      // 1. pos already reaches the end of file, then null should be returned
      // 2. otherwise, return the contents between pos and the end of file.
      if (pos >= js.length()) {
        return null;
      } else {
        return js.substring(pos);
      }
    } else {
      return js.substring(pos, js.indexOf('\n', pos));
    }
  }

  /**
   * Gets the source lines starting at `lineNumber` and continuing until `length`. Omits any
   * trailing newlines.
   *
   * @param lineNumber the line number, 1 being the first line of the file.
   * @param length the number of characters desired, starting at the 0th character of the specified
   *     line. If negative or 0, returns a single line.
   * @return The line(s) indicated. Returns {@code null} if it does not exist or if there was an IO
   *     exception.
   */
  public Region getLines(int lineNumber, int length) {
    findLineOffsets();
    if (lineNumber > lineOffsets.length) {
      return null;
    }

    if (lineNumber < 1) {
      lineNumber = 1;
    }
    if (length <= 0) {
      length = 1;
    }

    String js = "";
    try {
      js = getCode();
    } catch (IOException e) {
      return null;
    }

    int pos = lineOffsets[lineNumber - 1];
    if (pos == js.length()) {
      return new SimpleRegion(
          lineNumber, lineNumber, ""); // Happens when asking for the last empty line in a file.
    }
    int endChar = pos;
    int endLine = lineNumber;
    // go through lines until we've reached the end of the file or met the specified length.
    for (; endChar < pos + length && endLine <= lineOffsets.length; endLine++) {
      endChar = (endLine < lineOffsets.length) ? lineOffsets[endLine] : js.length();
    }

    if (js.charAt(endChar - 1) == '\n') {
      return new SimpleRegion(lineNumber, endLine, js.substring(pos, endChar - 1));
    }
    return new SimpleRegion(lineNumber, endLine, js.substring(pos, endChar));
  }

  /**
   * Get a region around the indicated line number. The exact definition of a region is
   * implementation specific, but it must contain the line indicated by the line number. A region
   * must not start or end by a carriage return.
   *
   * @param lineNumber the line number, 1 being the first line of the file.
   * @return The line indicated. Returns {@code null} if it does not exist, or if there was an IO
   *     exception.
   */
  public Region getRegion(int lineNumber) {
    String js = "";
    try {
      js = getCode();
    } catch (IOException e) {
      return null;
    }
    int pos = 0;
    int startLine = max(1, lineNumber - (SOURCE_EXCERPT_REGION_LENGTH + 1) / 2 + 1);
    for (int n = 1; n < startLine; n++) {
      int nextpos = js.indexOf('\n', pos);
      if (nextpos == -1) {
        break;
      }
      pos = nextpos + 1;
    }
    int end = pos;
    int endLine = startLine;
    for (int n = 0; n < SOURCE_EXCERPT_REGION_LENGTH; n++, endLine++) {
      end = js.indexOf('\n', end);
      if (end == -1) {
        break;
      }
      end++;
    }
    if (lineNumber >= endLine) {
      return null;
    }
    if (end == -1) {
      int last = js.length() - 1;
      if (js.charAt(last) == '\n') {
        return
            new SimpleRegion(startLine, endLine, js.substring(pos, last));
      } else {
        return new SimpleRegion(startLine, endLine, js.substring(pos));
      }
    } else {
      return new SimpleRegion(startLine, endLine, js.substring(pos, end));
    }
  }

  @Override
  public String toString() {
    return fileName;
  }

  @GwtIncompatible("fromZipInput")
  public static List<SourceFile> fromZipFile(String zipName, Charset inputCharset)
      throws IOException {
    try (InputStream input = new FileInputStream(zipName)) {
      return fromZipInput(zipName, input, inputCharset);
    }
  }

  @GwtIncompatible("java.util.zip.ZipInputStream")
  public static List<SourceFile> fromZipInput(
      String zipName, InputStream input, Charset inputCharset) throws IOException {
    final String absoluteZipPath = new File(zipName).getAbsolutePath();
    List<SourceFile> sourceFiles = new ArrayList<>();

    try (ZipInputStream in = new ZipInputStream(input, inputCharset)) {
      ZipEntry zipEntry;
      while ((zipEntry = in.getNextEntry()) != null) {
        String entryName = zipEntry.getName();
        if (!entryName.endsWith(".js")) { // Only accept js files
          continue;
        }
        sourceFiles.add(
            builder()
                .withCharset(inputCharset)
                .withOriginalPath(zipName + BANG_SLASH + entryName)
                .withZipEntryPath(absoluteZipPath, entryName)
                .build());
      }
    }
    return sourceFiles;
  }

  @GwtIncompatible("java.io.File")
  public static SourceFile fromFile(String fileName, Charset charset) {
    return builder().withPath(fileName).withCharset(charset).build();
  }

  @GwtIncompatible("java.io.File")
  public static SourceFile fromFile(String fileName) {
    return builder().withPath(fileName).build();
  }

  @GwtIncompatible("java.io.File")
  public static SourceFile fromPath(Path path, Charset charset) {
    return builder().withPath(path).withCharset(charset).build();
  }

  public static SourceFile fromCode(String fileName, String code, SourceKind kind) {
    return builder().withPath(fileName).withKind(kind).withContent(code).build();
  }

  public static SourceFile fromCode(String fileName, String code) {
    return builder().withPath(fileName).withContent(code).build();
  }

  @GwtIncompatible("java.io.Reader")
  public static SourceFile fromProto(SourceFileProto protoSourceFile) {
    SourceKind sourceKind = getSourceKindFromProto(protoSourceFile);
    switch (protoSourceFile.getLoaderCase()) {
      case PRELOADED_CONTENTS:
        return SourceFile.fromCode(
            protoSourceFile.getFilename(), protoSourceFile.getPreloadedContents(), sourceKind);
      case FILE_ON_DISK:
        String pathOnDisk =
            protoSourceFile.getFileOnDisk().getActualPath().isEmpty()
                ? protoSourceFile.getFilename()
                : protoSourceFile.getFileOnDisk().getActualPath();
        return SourceFile.builder()
            .withCharset(toCharset(protoSourceFile.getFileOnDisk().getCharset()))
            .withOriginalPath(protoSourceFile.getFilename())
            .withKind(sourceKind)
            .withPath(pathOnDisk)
            .build();
      case ZIP_ENTRY:
        {
          SourceFileProto.ZipEntryOnDisk zipEntry = protoSourceFile.getZipEntry();
          return SourceFile.builder()
              .withKind(sourceKind)
              .withOriginalPath(protoSourceFile.getFilename())
              .withCharset(toCharset(zipEntry.getCharset()))
              .withZipEntryPath(zipEntry.getZipPath(), zipEntry.getEntryName())
              .build();
        }
      case LOADER_NOT_SET:
        break;
    }
    throw new AssertionError();
  }

  private static SourceKind getSourceKindFromProto(SourceFileProto protoSourceFile) {
    switch (protoSourceFile.getSourceKind()) {
      case EXTERN:
        return SourceKind.EXTERN;
      case CODE:
        return SourceKind.STRONG;
      case NOT_SPECIFIED:
      case UNRECOGNIZED:
        break;
    }
    throw new AssertionError();
  }

  private static Charset toCharset(String protoCharset) {
    if (protoCharset.isEmpty()) {
      return UTF_8;
    }
    return Charset.forName(protoCharset);
  }

  /** Create a new builder for source files. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder interface for source files.
   *
   * <p>Allows users to customize the Charset, and the original path of the source file (if it
   * differs from the path on disk).
   */
  public static final class Builder {
    private SourceKind kind = SourceKind.STRONG;
    private Charset charset = UTF_8;
    private String originalPath = null;

    private String path = null;
    private Path pathWithFilesystem = null;
    private String zipEntryPath = null;

    private Supplier<String> lazyContent = null;

    private Builder() {}

    /** Set the source kind. */
    public Builder withKind(SourceKind kind) {
      this.kind = kind;
      return this;
    }

    /** Set the charset to use when reading from an input stream or file. */
    public Builder withCharset(Charset charset) {
      this.charset = charset;
      return this;
    }

    public Builder withPath(String path) {
      return this.withPathInternal(path, null);
    }

    public Builder withPath(Path path) {
      return this.withPathInternal(path.toString(), path);
    }

    public Builder withContent(String x) {
      this.lazyContent = x::toString;
      return this;
    }

    @GwtIncompatible
    public Builder withContent(InputStream x) {
      this.lazyContent =
          () -> {
            checkState(this.charset != null);
            try {
              return CharStreams.toString(new InputStreamReader(x, this.charset));
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          };
      return this;
    }

    public Builder withZipEntryPath(String zipPath, String entryPath) {
      this.path = zipPath;
      this.zipEntryPath = entryPath;

      return this;
    }

    /**
     * Sets a name for this source file that does not need to correspond to a path on disk.
     *
     * <p>Allow passing a reasonable human-readable name in cases like for zip files and for
     * generated files with unstable artifact prefixes.
     *
     * <p>The name must still be unique.
     */
    public Builder withOriginalPath(String originalPath) {
      this.originalPath = originalPath;
      return this;
    }

    public SourceFile build() {
      String displayPath =
          (this.originalPath != null)
              ? this.originalPath
              : ((this.zipEntryPath == null)
                  ? this.path
                  : this.path + BANG_SLASH + this.zipEntryPath);

      if (this.lazyContent != null) {
        return new SourceFile(
            new CodeLoader.Preloaded(this.lazyContent.get()), displayPath, this.kind);
      }

      if (this.zipEntryPath != null) {
        return new SourceFile(
            new CodeLoader.AtZip(this.path, this.zipEntryPath, this.charset),
            displayPath,
            this.kind);
      }

      return new SourceFile(
          new CodeLoader.OnDisk(
              (this.pathWithFilesystem != null) ? this.pathWithFilesystem : Paths.get(this.path),
              this.charset),
          displayPath,
          this.kind);
    }

    private Builder withPathInternal(String path, @Nullable Path pathWithFilesystem) {
      // Check if this path should be inferred as a ZIP entry path.
      int bangSlashIndex = path.indexOf(BANG_SLASH);
      if (bangSlashIndex >= 0) {
        String zipPath = path.substring(0, bangSlashIndex);
        String entryPath = path.substring(bangSlashIndex + BANG_SLASH.length());

        if (zipPath.endsWith(".zip")
            && (entryPath.endsWith(".js") || entryPath.endsWith(".js.map"))) {
          return this.withZipEntryPath(zipPath, entryPath);
        }
      }

      // Path instances have an implicit reference to a FileSystem. Make sure to preserve it.
      this.path = path;
      this.pathWithFilesystem = pathWithFilesystem;

      return this;
    }
  }

  //////////////////////////////////////////////////////////////////////////////
  // Implementations

  private abstract static class CodeLoader implements Serializable {
    /**
     * Return the source text of this file from its original storage.
     *
     * <p>The implementation may be a slow operation such as reading from a file. SourceFile
     * guarantees that this method is only called under synchronization.
     */
    String loadUncachedCode() throws IOException {
      throw new AssertionError();
    }

    /**
     * Return a Reader for the source text of this file from its original storage.
     *
     * <p>The implementation may be a slow operation such as reading from a file. SourceFile
     * guarantees that this method is only called under synchronization.
     */
    Reader openUncachedReader() throws IOException {
      return null;
    }

    /**
     * Returns a representation of this loader that can be serialized/deserialized to reconstruct
     * this SourceFile
     */
    abstract SourceFileProto.Builder toProtoLocationBuilder(String fileName);

    static final class Preloaded extends CodeLoader {
      private static final long serialVersionUID = 2L;
      private final String preloadedCode;

      Preloaded(String preloadedCode) {
        super();
        this.preloadedCode = checkNotNull(preloadedCode);
      }

      @Override
      String loadUncachedCode() {
        return this.preloadedCode;
      }

      @Override
      SourceFileProto.Builder toProtoLocationBuilder(String fileName) {
        return SourceFileProto.newBuilder().setPreloadedContents(this.preloadedCode);
      }
    }

    static final class OnDisk extends CodeLoader {
      private static final long serialVersionUID = 1L;

      private final String serializableCharset;
      // TODO(b/180553215): We shouldn't store this Path. We already have to reconstruct it from a
      // string during deserialization.
      private transient Path relativePath;

      OnDisk(Path relativePath, Charset c) {
        super();
        this.serializableCharset = c.name();
        this.relativePath = relativePath;
      }

      @Override
      @GwtIncompatible
      String loadUncachedCode() throws IOException {
        try {
          return Files.readString(this.relativePath, this.getCharset());
        } catch (CharacterCodingException e) {
          throw new IOException(
              "Failed to read: " + this.relativePath + ", is this input UTF-8 encoded?", e);
        }
      }

      @Override
      @GwtIncompatible
      Reader openUncachedReader() throws IOException {
        return Files.newBufferedReader(this.relativePath, this.getCharset());
      }

      @GwtIncompatible
      private void writeObject(ObjectOutputStream out) throws Exception {
        out.defaultWriteObject();
        out.writeObject(this.relativePath.toString());
      }

      @GwtIncompatible
      private void readObject(ObjectInputStream in) throws Exception {
        in.defaultReadObject();
        this.relativePath = Paths.get((String) in.readObject());
      }

      private Charset getCharset() {
        return Charset.forName(this.serializableCharset);
      }

      @Override
      SourceFileProto.Builder toProtoLocationBuilder(String fileName) {
        String actualPath = this.relativePath.toString();
        return SourceFileProto.newBuilder()
            .setFileOnDisk(
                FileOnDisk.newBuilder()
                    .setActualPath(
                        // to save space, don't serialize the path if equal to the fileName.
                        fileName.equals(actualPath) ? "" : actualPath)
                    // save space by not serializing UTF_8 (the default charset)
                    .setCharset(this.getCharset().equals(UTF_8) ? "" : this.serializableCharset));
      }
    }

    static final class AtZip extends CodeLoader {
      private static final long serialVersionUID = 1L;
      private final String zipName;
      private final String entryName;
      private final String serializableCharset;

      AtZip(String zipName, String entryName, Charset c) {
        super();
        this.zipName = zipName;
        this.entryName = entryName;
        this.serializableCharset = c.name();
      }

      @Override
      @GwtIncompatible
      String loadUncachedCode() throws IOException {
        return CharStreams.toString(this.openUncachedReader());
      }

      @Override
      @GwtIncompatible
      Reader openUncachedReader() throws IOException {
        return new InputStreamReader(
            JSCompZipFileCache.getEntryStream(this.zipName, this.entryName), this.getCharset());
      }

      private Charset getCharset() {
        return Charset.forName(this.serializableCharset);
      }

      @Override
      SourceFileProto.Builder toProtoLocationBuilder(String fileName) {
        return SourceFileProto.newBuilder()
            .setFilename(fileName)
            .setZipEntry(
                ZipEntryOnDisk.newBuilder()
                    .setEntryName(this.entryName)
                    .setZipPath(this.zipName)
                    // save space by not serializing UTF_8 (the default charset)
                    .setCharset(this.getCharset().equals(UTF_8) ? "" : this.serializableCharset)
                    .build());
      }
    }
  }

  @GwtIncompatible("ObjectOutputStream")
  private void writeObject(ObjectOutputStream os) throws Exception {
    checkState(
        this.getKind().equals(SourceKind.NON_CODE),
        "JS SourceFiles must not be serialized and are reconstructed by TypedAstDeserializer. "
            + "\nHit on:  %s",
        this);
    os.defaultWriteObject();
    this.serializeLineOffsetsToVarintDeltas(os);
  }

  @GwtIncompatible("ObjectInputStream")
  private void readObject(ObjectInputStream in) throws Exception {
    in.defaultReadObject();
    this.deserializeVarintDeltasToLineOffsets(in);
  }

  private static final int SEVEN_BITS = 0b01111111;

  @GwtIncompatible("ObjectOutputStream")
  private void serializeLineOffsetsToVarintDeltas(ObjectOutputStream os) throws Exception {
    if (this.lineOffsets == null) {
      os.writeInt(-1);
      return;
    }

    os.writeInt(this.lineOffsets.length);

    // The first offset is always 0.
    for (int intIndex = 1; intIndex < this.lineOffsets.length; intIndex++) {
      int delta = this.lineOffsets[intIndex] - this.lineOffsets[intIndex - 1];
      while (delta > SEVEN_BITS) {
        os.writeByte(delta | ~SEVEN_BITS);
        delta = delta >>> 7;
      }
      os.writeByte(delta);
    }
  }

  @GwtIncompatible("ObjectInputStream")
  private void deserializeVarintDeltasToLineOffsets(ObjectInputStream in) throws Exception {
    int lineCount = in.readInt();
    if (lineCount == -1) {
      this.lineOffsets = null;
      return;
    }

    int[] lineOffsets = new int[lineCount];

    // The first offset is always 0.
    for (int intIndex = 1; intIndex < lineCount; intIndex++) {
      int delta = 0;
      int shift = 0;

      byte segment = in.readByte();
      for (; segment < 0; segment = in.readByte()) {
        delta |= (segment & SEVEN_BITS) << shift;
        shift += 7;
      }
      delta |= segment << shift;

      lineOffsets[intIndex] = delta + lineOffsets[intIndex - 1];
    }

    this.lineOffsets = lineOffsets;
  }

  public SourceFileProto getProto() {
    return this.loader
        .toProtoLocationBuilder(this.getName())
        .setFilename(this.getName())
        .setSourceKind(sourceKindToProto(this.getKind()))
        .build();
  }

  private static SourceFileProto.SourceKind sourceKindToProto(SourceKind sourceKind) {
    switch (sourceKind) {
      case EXTERN:
        return SourceFileProto.SourceKind.EXTERN;
      case STRONG:
      case WEAK:
        return SourceFileProto.SourceKind.CODE;
      case NON_CODE:
        break;
    }
    throw new AssertionError();
  }
}
