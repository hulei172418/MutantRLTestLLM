package org.astjimple;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.comments.Comment;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fast version for mutation-testing scenario:
 * 1) diff only the target callable (method / constructor), not the whole file
 * 2) cache ONLY original source parsing
 * 3) cache ONLY original callable slices
 * 4) mutant side is never cached
 * 5) diff result is never cached
 *
 * Suitable for:
 * - original source repeats frequently
 * - mutant source is mostly unique
 */
public class DiffWithLineRanges1 {

    /**
     * Cache 1: original parsed source cache
     * key = absolutePath|length|lastModified
     */
    private static final Map<String, ParsedSource> ORIGINAL_SOURCE_CACHE = new ConcurrentHashMap<>();

    /**
     * Cache 2: original method slice cache
     * key = sourceKey || classPath || callableSig
     */
    private static final Map<String, MethodSlice> ORIGINAL_METHOD_SLICE_CACHE = new ConcurrentHashMap<>();

    /**
     * Backward-compatible fallback:
     * if only fileP/fileM are provided, do a fast whole-file line diff (no cache
     * for mutant side).
     * Here we only cache original(fileP) parsing, and parse fileM directly each
     * time.
     */
    public static List<ChangeRange> diffWithLineRanges(String fileP, String fileM) throws Exception {
        ParsedSource p = getOriginalSourceCached(fileP);
        ParsedSource m = parseSourceDirect(fileM);

        List<RelativeHunk> hunks = computeRelativeDiff(
                splitLines(p.text),
                splitLines(m.text));

        return mapRelativeHunksToAbsolute(
                hunks,
                1,
                1,
                splitLines(p.text),
                splitLines(m.text));
    }

    /**
     * Recommended API for your scenario:
     * diff only the target callable.
     *
     * @param fileP           original source file
     * @param fileM           mutant source file
     * @param classPathOrNull class path, e.g. "ParserBase" or "Outer.Inner"
     * @param callableSig     soot-like signature, e.g. "NumberType_getNumberType()"
     *                        or "DfpField(int,boolean)"
     */
    public static List<ChangeRange> diffWithLineRanges(
            String fileP,
            String fileM,
            String classPathOrNull,
            String callableSig) throws Exception {

        MethodSlice p = getOriginalMethodSliceCached(fileP, classPathOrNull, callableSig);
        MethodSlice m = getMutantMethodSliceNoCache(fileM, classPathOrNull, callableSig);

        List<RelativeHunk> hunks = computeRelativeDiff(p.lines, m.lines);
        return mapRelativeHunksToAbsolute(
                hunks,
                p.startLine,
                m.startLine,
                p.lines,
                m.lines);
    }

    private static ParsedSource getOriginalSourceCached(String file) throws Exception {
        String key = buildSourceKey(file);

        ParsedSource hit = ORIGINAL_SOURCE_CACHE.get(key);
        if (hit != null) {
            return hit;
        }

        ParsedSource parsed = parseSourceDirect(file);
        ORIGINAL_SOURCE_CACHE.put(key, parsed);
        return parsed;
    }

    private static MethodSlice getOriginalMethodSliceCached(
            String file,
            String classPathOrNull,
            String callableSig) throws Exception {

        String sourceKey = buildSourceKey(file);
        String methodKey = sourceKey + "||" + nullSafe(classPathOrNull) + "||" + callableSig;

        MethodSlice hit = ORIGINAL_METHOD_SLICE_CACHE.get(methodKey);
        if (hit != null) {
            return hit;
        }

        ParsedSource ps = getOriginalSourceCached(file);
        MethodSlice slice = extractMethodSliceFromParsedSource(ps, file, classPathOrNull, callableSig);

        ORIGINAL_METHOD_SLICE_CACHE.put(methodKey, slice);
        return slice;
    }

    private static MethodSlice getMutantMethodSliceNoCache(
            String file,
            String classPathOrNull,
            String callableSig) throws Exception {
        ParsedSource ps = parseSourceDirect(file);
        return extractMethodSliceFromParsedSource(ps, file, classPathOrNull, callableSig);
    }

    private static ParsedSource parseSourceDirect(String file) throws Exception {
        ParserConfiguration cfg = new ParserConfiguration().setAttributeComments(false);
        JavaParser parser = new JavaParser(cfg);
        com.github.javaparser.ParseResult<CompilationUnit> result = parser.parse(new File(file).toPath());
        if (!result.getResult().isPresent()) {
            throw new Exception("Parse failed: " + result.getProblems());
        }

        CompilationUnit cu = result.getResult().get();
        cu.getAllContainedComments().forEach(Comment::remove);

        String text = readUtf8(file);
        return new ParsedSource(cu, text);
    }

    private static MethodSlice extractMethodSliceFromParsedSource(
            ParsedSource ps,
            String file,
            String classPathOrNull,
            String callableSig) throws Exception {

        Optional<CallableDeclaration<?>> cdOpt = MethodContent.findCallableBySignature(ps.cu, callableSig,
                classPathOrNull);

        if (!cdOpt.isPresent()) {
            throw new IllegalArgumentException(
                    "No callable matched signature: " + callableSig
                            + (classPathOrNull == null ? "" : (" within class " + classPathOrNull))
                            + " in file: " + file);
        }

        CallableDeclaration<?> cd = cdOpt.get();
        Range r = cd.getRange().orElseThrow(
                () -> new IllegalStateException("Callable has no source range: " + callableSig + " in " + file));

        String code = cd.toString();
        List<String> lines = splitLines(code);

        return new MethodSlice(
                file,
                classPathOrNull,
                callableSig,
                r.begin.line,
                r.end.line,
                code,
                lines);
    }

    private static List<RelativeHunk> computeRelativeDiff(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();

        int[][] dp = new int[n + 1][m + 1];

        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (Objects.equals(a.get(i), b.get(j))) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }

        List<RelativeHunk> hunks = new ArrayList<>();

        int i = 0, j = 0;
        int srcLine = 1, dstLine = 1;
        HunkBuilder current = null;

        while (i < n || j < m) {
            boolean equal = (i < n && j < m && Objects.equals(a.get(i), b.get(j)));

            if (equal) {
                if (current != null) {
                    hunks.add(current.build());
                    current = null;
                }
                i++;
                j++;
                srcLine++;
                dstLine++;
                continue;
            }

            boolean takeDelete;
            if (j >= m) {
                takeDelete = true;
            } else if (i >= n) {
                takeDelete = false;
            } else {
                takeDelete = dp[i + 1][j] >= dp[i][j + 1];
            }

            if (current == null) {
                current = new HunkBuilder();
            }

            if (takeDelete) {
                current.touchSrc(srcLine);
                i++;
                srcLine++;
            } else {
                current.touchDst(dstLine);
                j++;
                dstLine++;
            }
        }

        if (current != null) {
            hunks.add(current.build());
        }

        return hunks;
    }

    private static List<ChangeRange> mapRelativeHunksToAbsolute(
            List<RelativeHunk> hunks,
            int baseLineP,
            int baseLineM,
            List<String> srcLines,
            List<String> dstLines) {

        List<ChangeRange> out = new ArrayList<>();

        for (RelativeHunk h : hunks) {
            boolean hasSrc = h.srcStart > 0;
            boolean hasDst = h.dstStart > 0;

            if (hasSrc && hasDst) {
                String srcSnippet = previewLines(srcLines, h.srcStart, h.srcEnd);
                String dstSnippet = previewLines(dstLines, h.dstStart, h.dstEnd);

                out.add(new ChangeRange(
                        "Update",
                        true,
                        baseLineP + h.srcStart - 1,
                        baseLineP + h.srcEnd - 1,
                        "- :: " + srcSnippet));

                out.add(new ChangeRange(
                        "Update",
                        false,
                        baseLineM + h.dstStart - 1,
                        baseLineM + h.dstEnd - 1,
                        "+ :: " + dstSnippet));

            } else if (hasSrc) {
                String srcSnippet = previewLines(srcLines, h.srcStart, h.srcEnd);

                out.add(new ChangeRange(
                        "Delete",
                        true,
                        baseLineP + h.srcStart - 1,
                        baseLineP + h.srcEnd - 1,
                        "- :: " + srcSnippet));

            } else if (hasDst) {
                String dstSnippet = previewLines(dstLines, h.dstStart, h.dstEnd);

                out.add(new ChangeRange(
                        "Insert",
                        false,
                        baseLineM + h.dstStart - 1,
                        baseLineM + h.dstEnd - 1,
                        "+ :: " + dstSnippet));
            }
        }

        return out;
    }

    private static String previewLines(List<String> lines, int start, int end) {
        if (lines == null || lines.isEmpty() || start <= 0 || end < start) {
            return "";
        }

        int from = Math.max(1, start);
        int to = Math.min(lines.size(), end);

        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            String line = compressWhitespace(lines.get(i - 1));
            if (line.isEmpty()) {
                continue;
            }

            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(line);

            if (sb.length() >= 1024) {
                break;
            }
        }

        String s = sb.toString();
        if (s.length() > 1024) {
            s = s.substring(0, 1021) + "...";
        }
        return s;
    }

    private static String compressWhitespace(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String buildSourceKey(String file) {
        File f = new File(file);
        return f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String readUtf8(String file) throws Exception {
        return new String(Files.readAllBytes(new File(file).toPath()), StandardCharsets.UTF_8);
    }

    private static String normalizeNewlines(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static List<String> splitLines(String s) {
        String normalized = normalizeNewlines(s);
        List<String> lines = new ArrayList<>(Arrays.asList(normalized.split("\n", -1)));

        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    public static void clearCache() {
        ORIGINAL_SOURCE_CACHE.clear();
        ORIGINAL_METHOD_SLICE_CACHE.clear();
    }

    public static void clearSourceCache() {
        ORIGINAL_SOURCE_CACHE.clear();
    }

    public static void clearMethodSliceCache() {
        ORIGINAL_METHOD_SLICE_CACHE.clear();
    }

    public static int sourceCacheSize() {
        return ORIGINAL_SOURCE_CACHE.size();
    }

    public static int methodSliceCacheSize() {
        return ORIGINAL_METHOD_SLICE_CACHE.size();
    }

    public static int cacheSize() {
        return ORIGINAL_METHOD_SLICE_CACHE.size();
    }

    private static class ParsedSource {
        final CompilationUnit cu;
        final String text;

        ParsedSource(CompilationUnit cu, String text) {
            this.cu = cu;
            this.text = text;
        }
    }

    private static class MethodSlice {
        final String file;
        final String classPath;
        final String callableSig;
        final int startLine;
        final int endLine;
        final String code;
        final List<String> lines;

        MethodSlice(
                String file,
                String classPath,
                String callableSig,
                int startLine,
                int endLine,
                String code,
                List<String> lines) {
            this.file = file;
            this.classPath = classPath;
            this.callableSig = callableSig;
            this.startLine = startLine;
            this.endLine = endLine;
            this.code = code;
            this.lines = lines;
        }
    }

    private static class RelativeHunk {
        final int srcStart;
        final int srcEnd;
        final int dstStart;
        final int dstEnd;

        RelativeHunk(int srcStart, int srcEnd, int dstStart, int dstEnd) {
            this.srcStart = srcStart;
            this.srcEnd = srcEnd;
            this.dstStart = dstStart;
            this.dstEnd = dstEnd;
        }
    }

    private static class HunkBuilder {
        int srcStart = 0;
        int srcEnd = 0;
        int dstStart = 0;
        int dstEnd = 0;

        void touchSrc(int line) {
            if (srcStart == 0) {
                srcStart = line;
            }
            srcEnd = line;
        }

        void touchDst(int line) {
            if (dstStart == 0) {
                dstStart = line;
            }
            dstEnd = line;
        }

        RelativeHunk build() {
            return new RelativeHunk(srcStart, srcEnd, dstStart, dstEnd);
        }
    }

    public static void main(String[] args) throws Exception {
        String fileP = "src/main/java/demo/origin/ConstantPoolEntry.java";
        String fileM = "src/main/java/demo/m4/ConstantPoolEntry.java";
        String className = "ConstantPoolEntry";
        String sig = "boolean_PoolEntry(int,int)";

        List<ChangeRange> rs = diffWithLineRanges(fileP, fileM, className, sig);

        System.out.println("=== Changes ===");
        rs.forEach(System.out::println);
        System.out.println("sourceCacheSize      = " + sourceCacheSize());
        System.out.println("methodSliceCacheSize = " + methodSliceCacheSize());
        System.out.println("result size          = " + rs.size());
    }
}
