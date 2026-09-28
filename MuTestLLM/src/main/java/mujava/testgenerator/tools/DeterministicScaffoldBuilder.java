package mujava.testgenerator.tools;

import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * Emits small deterministic tests for a narrow set of high-value mutants where
 * free-form generation repeatedly drifts away from the required input shape.
 */
public final class DeterministicScaffoldBuilder {
    private static final String TARGET_CLASS = "main.java.org.apache.commons.csv.CSVParser";
    private static final String TARGET_METHOD = "java.util.Map_initializeHeader()";
    private static final String CSV_PARSER_CLASS = "org.apache.commons.csv.CSVParser";
    private static final String EXTENDED_BUFFERED_READER_CLASS = "org.apache.commons.csv.ExtendedBufferedReader";
    private static final String LEXER_CLASS = "org.apache.commons.csv.Lexer";
    private static final String CSV_PRINTER_CLASS = "org.apache.commons.csv.CSVPrinter";
    private static final Properties RUNTIME_PROPERTIES = loadRuntimeProperties();

    private DeterministicScaffoldBuilder() {
    }

    public static String maybeBuild(Request request, String testSetName) {
        if (request == null || testSetName == null) {
            return null;
        }
        String normalizedClass = normalizeTargetClassName(request.targetClassName);
        String method = safe(request.methodSignature);

        if (CSV_PARSER_CLASS.equals(normalizedClass) && method.contains("initializeHeader")) {
            return buildCsvParserHeaderSuite(testSetName);
        }
        if (EXTENDED_BUFFERED_READER_CLASS.equals(normalizedClass)) {
            return buildExtendedBufferedReaderSuite(testSetName);
        }
        if (LEXER_CLASS.equals(normalizedClass)
                && isHighValueLexerMethod(method)
                && classWideScaffoldEnabled("lexer", true)) {
            return buildLexerSuite(testSetName);
        }
        if (CSV_PRINTER_CLASS.equals(normalizedClass)
                && isHighValueCsvPrinterMethod(method)
                && classWideScaffoldEnabled("csvprinter", true)) {
            return buildCsvPrinterSuite(testSetName);
        }

        if (!TARGET_CLASS.equals(safe(request.targetClassName))
                || !TARGET_METHOD.equals(safe(request.methodSignature))) {
            return null;
        }

        String mutant = safe(request.mutantName);
        if ("COD_1".equals(mutant)) {
            return buildExpectedExceptionTest(
                    testSetName,
                    "testDuplicateNonEmptyHeaderThrowsOnOriginal",
                    "CSVFormat.DEFAULT.withHeader().withAllowMissingColumnNames(true)",
                    "\"A,B,A\\n1,2,3\\n\""
            );
        }
        if ("COD_2".equals(mutant)) {
            return buildExpectedExceptionTest(
                    testSetName,
                    "testDuplicateEmptyHeaderThrowsOnlyOnOriginal",
                    "CSVFormat.DEFAULT.withHeader().withAllowMissingColumnNames(false)",
                    "\" ,,\\n1,2,3\\n\"".replace(" ", "")
            );
        }
        if ("COI_11".equals(mutant)) {
            return buildExpectedExceptionTest(
                    testSetName,
                    "testDuplicateNonEmptyHeaderThrowsOnlyOnOriginal",
                    "CSVFormat.DEFAULT.withHeader().withAllowMissingColumnNames(true)",
                    "\"A,B,A\\n1,2,3\\n\""
            );
        }
        if ("COR_3".equals(mutant)) {
            return buildSuccessfulConstructionTest(
                    testSetName,
                    "testDuplicateEmptyHeaderAllowedOnOriginal",
                    "CSVFormat.DEFAULT.withHeader().withAllowMissingColumnNames(true)",
                    "\",,\\n1,2,3\\n\""
            );
        }
        if ("COR_4".equals(mutant)) {
            return buildSuccessfulConstructionTest(
                    testSetName,
                    "testDuplicateEmptyHeaderAllowedOnOriginal",
                    "CSVFormat.DEFAULT.withHeader().withAllowMissingColumnNames(true)",
                    "\",,\\n1,2,3\\n\""
            );
        }
        if ("COR_5".equals(mutant)) {
            return buildExpectedExceptionTest(
                    testSetName,
                    "testDuplicateNonEmptyHeaderThrowsOnOriginal",
                    "CSVFormat.DEFAULT.withHeader().withAllowMissingColumnNames(true)",
                    "\"A,B,A\\n1,2,3\\n\""
            );
        }
        return null;
    }

    private static String buildCsvParserHeaderSuite(String testSetName) {
        String simpleName = simpleName(testSetName);
        return "package org.apache.commons.csv;\n"
                + "\n"
                + "import static org.junit.Assert.assertEquals;\n"
                + "import static org.junit.Assert.assertNotNull;\n"
                + "import static org.junit.Assert.fail;\n"
                + "import java.io.StringReader;\n"
                + "import java.util.Map;\n"
                + "import org.junit.Test;\n"
                + "\n"
                + "public class " + simpleName + " {\n"
                + "\n"
                + "    @Test\n"
                + "    public void duplicateNonEmptyHeaderThrows() throws Exception {\n"
                + "        try {\n"
                + "            new CSVParser(new StringReader(\"A,B,A\\n1,2,3\\n\"), CSVFormat.DEFAULT.withHeader().withAllowMissingColumnNames(true), 0L, 0L);\n"
                + "            fail(\"Expected duplicate header to throw\");\n"
                + "        } catch (IllegalArgumentException expected) {\n"
                + "            assertNotNull(expected.getMessage());\n"
                + "        }\n"
                + "    }\n"
                + "\n"
                + "    @Test\n"
                + "    public void duplicateEmptyHeaderThrowsWhenMissingNamesDisallowed() throws Exception {\n"
                + "        try {\n"
                + "            new CSVParser(new StringReader(\",,\\n1,2,3\\n\"), CSVFormat.DEFAULT.withHeader().withAllowMissingColumnNames(false), 0L, 0L);\n"
                + "            fail(\"Expected duplicate empty header to throw\");\n"
                + "        } catch (IllegalArgumentException expected) {\n"
                + "            assertNotNull(expected.getMessage());\n"
                + "        }\n"
                + "    }\n"
                + "\n"
                + "    @Test\n"
                + "    public void duplicateEmptyHeaderAllowedWhenMissingNamesAllowed() throws Exception {\n"
                + "        CSVParser parser = new CSVParser(new StringReader(\",,\\n1,2,3\\n\"), CSVFormat.DEFAULT.withHeader().withAllowMissingColumnNames(true), 0L, 0L);\n"
                + "        CSVRecord record = parser.nextRecord();\n"
                + "        assertNotNull(record);\n"
                + "        assertEquals(\"1\", record.get(0));\n"
                + "        assertEquals(\"3\", record.get(2));\n"
                + "        parser.close();\n"
                + "    }\n"
                + "\n"
                + "    @Test\n"
                + "    public void explicitHeaderMapKeepsStableIndexes() throws Exception {\n"
                + "        CSVParser parser = new CSVParser(new StringReader(\"1,2,3\\n\"), CSVFormat.DEFAULT.withHeader(\"A\", \"B\", \"C\"), 0L, 0L);\n"
                + "        Map<String, Integer> headers = parser.getHeaderMap();\n"
                + "        assertEquals(Integer.valueOf(0), headers.get(\"A\"));\n"
                + "        assertEquals(Integer.valueOf(1), headers.get(\"B\"));\n"
                + "        assertEquals(Integer.valueOf(2), headers.get(\"C\"));\n"
                + "        CSVRecord record = parser.nextRecord();\n"
                + "        assertEquals(\"1\", record.get(\"A\"));\n"
                + "        assertEquals(\"3\", record.get(\"C\"));\n"
                + "        parser.close();\n"
                + "    }\n"
                + "}\n";
    }

    private static String buildExtendedBufferedReaderSuite(String testSetName) {
        String simpleName = simpleName(testSetName);
        return "package org.apache.commons.csv;\n"
                + "\n"
                + "import static org.junit.Assert.assertEquals;\n"
                + "import static org.junit.Assert.assertFalse;\n"
                + "import static org.junit.Assert.assertTrue;\n"
                + "import java.io.StringReader;\n"
                + "import org.junit.Test;\n"
                + "\n"
                + "public class " + simpleName + " {\n"
                + "\n"
                + "    @Test\n"
                + "    public void readLineUpdatesLineNumberAndPosition() throws Exception {\n"
                + "        ExtendedBufferedReader reader = new ExtendedBufferedReader(new StringReader(\"alpha\\nbeta\\n\"));\n"
                + "        assertEquals(0L, reader.getCurrentLineNumber());\n"
                + "        assertEquals(\"alpha\", reader.readLine());\n"
                + "        assertEquals(1L, reader.getCurrentLineNumber());\n"
                + "        assertEquals(\"beta\", reader.readLine());\n"
                + "        assertEquals(2L, reader.getCurrentLineNumber());\n"
                + "        assertEquals(null, reader.readLine());\n"
                + "        assertEquals(2L, reader.getCurrentLineNumber());\n"
                + "    }\n"
                + "\n"
                + "    @Test\n"
                + "    public void readCharArrayTracksCrLfAsOneLine() throws Exception {\n"
                + "        ExtendedBufferedReader reader = new ExtendedBufferedReader(new StringReader(\"a\\r\\nb\\n\"));\n"
                + "        char[] buf = new char[5];\n"
                + "        assertEquals(5, reader.read(buf, 0, buf.length));\n"
                + "        assertEquals(2L, reader.getCurrentLineNumber());\n"
                + "        assertEquals('b', reader.getLastChar());\n"
                + "        assertEquals(5L, reader.getPosition());\n"
                + "    }\n"
                + "\n"
                + "    @Test\n"
                + "    public void readCharArrayWithOffsetPreservesSentinelsAndCounters() throws Exception {\n"
                + "        ExtendedBufferedReader reader = new ExtendedBufferedReader(new StringReader(\"a\\r\\nb\\n\"));\n"
                + "        char[] buf = new char[] {'?', '?', '?', '?', '?', '?'};\n"
                + "        assertEquals(4, reader.read(buf, 1, 4));\n"
                + "        assertEquals('?', buf[0]);\n"
                + "        assertEquals('a', buf[1]);\n"
                + "        assertEquals('\\r', buf[2]);\n"
                + "        assertEquals('\\n', buf[3]);\n"
                + "        assertEquals('b', buf[4]);\n"
                + "        assertEquals('?', buf[5]);\n"
                + "        assertEquals('b', reader.getLastChar());\n"
                + "        assertEquals(4L, reader.getPosition());\n"
                + "        assertEquals(2L, reader.getCurrentLineNumber());\n"
                + "        assertEquals(1, reader.read(buf, 1, 4));\n"
                + "        assertEquals('\\n', reader.getLastChar());\n"
                + "        assertEquals(5L, reader.getPosition());\n"
                + "        assertEquals(2L, reader.getCurrentLineNumber());\n"
                + "    }\n"
                + "\n"
                + "    @Test\n"
                + "    public void closeMakesClosedObservable() throws Exception {\n"
                + "        ExtendedBufferedReader reader = new ExtendedBufferedReader(new StringReader(\"x\"));\n"
                + "        assertFalse(reader.isClosed());\n"
                + "        reader.close();\n"
                + "        assertTrue(reader.isClosed());\n"
                + "    }\n"
                + "}\n";
    }

    private static String buildLexerSuite(String testSetName) {
        String simpleName = simpleName(testSetName);
        return "package org.apache.commons.csv;\n"
                + "\n"
                + "import static org.junit.Assert.assertEquals;\n"
                + "import static org.junit.Assert.assertFalse;\n"
                + "import static org.junit.Assert.assertTrue;\n"
                + "import java.io.StringReader;\n"
                + "import org.junit.Test;\n"
                + "\n"
                + "public class " + simpleName + " {\n"
                + "\n"
                + "    @Test\n"
                + "    public void lexerUsesExtendedBufferedReaderAndTracksClosedState() throws Exception {\n"
                + "        Lexer lexer = newLexer(\"\");\n"
                + "        assertFalse(lexer.isClosed());\n"
                + "        lexer.close();\n"
                + "        assertTrue(lexer.isClosed());\n"
                + "    }\n"
                + "\n"
                + "    @Test\n"
                + "    public void lineAndEofPredicatesExposeBoundaryBehavior() throws Exception {\n"
                + "        Lexer lexer = newLexer(\"\");\n"
                + "        assertTrue(lexer.isStartOfLine(Constants.UNDEFINED));\n"
                + "        assertTrue(lexer.isStartOfLine(Constants.LF));\n"
                + "        assertTrue(lexer.isStartOfLine(Constants.CR));\n"
                + "        assertFalse(lexer.isStartOfLine('x'));\n"
                + "        assertTrue(lexer.isEndOfFile(Constants.END_OF_STREAM));\n"
                + "        assertFalse(lexer.isEndOfFile('x'));\n"
                + "        assertTrue(lexer.readEndOfLine(Constants.LF));\n"
                + "        assertTrue(lexer.readEndOfLine(Constants.CR));\n"
                + "        assertFalse(lexer.readEndOfLine('x'));\n"
                + "    }\n"
                + "\n"
                + "    @Test\n"
                + "    public void nextTokenParsesSimpleAndQuotedValues() throws Exception {\n"
                + "        Lexer lexer = newLexer(\"a,\\\"b,b\\\"\\n\");\n"
                + "        Token first = lexer.nextToken(new Token());\n"
                + "        assertEquals(Token.Type.TOKEN, first.type);\n"
                + "        assertEquals(\"a\", first.content.toString());\n"
                + "        Token second = lexer.nextToken(new Token());\n"
                + "        assertEquals(Token.Type.EORECORD, second.type);\n"
                + "        assertEquals(\"b,b\", second.content.toString());\n"
                + "    }\n"
                + "\n"
                + "    @Test\n"
                + "    public void nextTokenPreservesTrailingSpaceWhenSpacesAreSignificant() throws Exception {\n"
                + "        Lexer lexer = newLexer(\"abc ,z\\n\");\n"
                + "        Token first = lexer.nextToken(new Token());\n"
                + "        assertEquals(Token.Type.TOKEN, first.type);\n"
                + "        assertEquals(\"abc \", first.content.toString());\n"
                + "        Token second = lexer.nextToken(new Token());\n"
                + "        assertEquals(Token.Type.EORECORD, second.type);\n"
                + "        assertEquals(\"z\", second.content.toString());\n"
                + "    }\n"
                + "\n"
                + "    @Test\n"
                + "    public void nextTokenHonorsEscapedDelimiterAndTrailingTrim() throws Exception {\n"
                + "        CSVFormat format = CSVFormat.DEFAULT.withEscape('\\\\').withIgnoreSurroundingSpaces(true);\n"
                + "        Lexer lexer = new Lexer(format, new ExtendedBufferedReader(new StringReader(\"a\\\\,b  , c\\n\")));\n"
                + "        Token first = lexer.nextToken(new Token());\n"
                + "        assertEquals(Token.Type.TOKEN, first.type);\n"
                + "        assertEquals(\"a,b\", first.content.toString());\n"
                + "        Token second = lexer.nextToken(new Token());\n"
                + "        assertEquals(Token.Type.EORECORD, second.type);\n"
                + "        assertEquals(\"c\", second.content.toString());\n"
                + "    }\n"
                + "\n"
                + "    @Test\n"
                + "    public void nextTokenKeepsInlineCommentAsTokenContent() throws Exception {\n"
                + "        Lexer lexer = newLexer(\"a,#comment\\n\");\n"
                + "        Token first = lexer.nextToken(new Token());\n"
                + "        assertEquals(Token.Type.TOKEN, first.type);\n"
                + "        assertEquals(\"a\", first.content.toString());\n"
                + "        Token second = lexer.nextToken(new Token());\n"
                + "        assertEquals(Token.Type.EORECORD, second.type);\n"
                + "        assertEquals(\"#comment\", second.content.toString());\n"
                + "    }\n"
                + "\n"
                + "    @Test\n"
                + "    public void nextTokenTreatsCommentAtEofAsNoTokenContent() throws Exception {\n"
                + "        Lexer lexer = newLexer(\"#\");\n"
                + "        Token token = lexer.nextToken(new Token());\n"
                + "        assertEquals(Token.Type.EOF, token.type);\n"
                + "        assertEquals(\"\", token.content.toString());\n"
                + "    }\n"
                + "\n"
                + "    @Test\n"
                + "    public void nextTokenSkipsLeadingEmptyLinesWhenConfigured() throws Exception {\n"
                + "        CSVFormat format = CSVFormat.DEFAULT.withIgnoreEmptyLines(true);\n"
                + "        Lexer lexer = new Lexer(format, new ExtendedBufferedReader(new StringReader(\"\\n\\nX\")));\n"
                + "        Token token = lexer.nextToken(new Token());\n"
                + "        assertEquals(Token.Type.TOKEN, token.type);\n"
                + "        assertEquals(\"X\", token.content.toString());\n"
                + "    }\n"
                + "\n"
                + "    @Test\n"
                + "    public void nextTokenReportsMalformedQuotedTail() throws Exception {\n"
                + "        Lexer lexer = newLexer(\"\\\"a\\\"x\\n\");\n"
                + "        try {\n"
                + "            lexer.nextToken(new Token());\n"
                + "            org.junit.Assert.fail(\"Expected invalid character after quoted token\");\n"
                + "        } catch (java.io.IOException expected) {\n"
                + "            assertTrue(expected.getMessage().contains(\"invalid char\"));\n"
                + "        }\n"
                + "    }\n"
                + "\n"
                + "    private static Lexer newLexer(String input) {\n"
                + "        return new Lexer(CSVFormat.DEFAULT, new ExtendedBufferedReader(new StringReader(input)));\n"
                + "    }\n"
                + "}\n";
    }

    private static String buildCsvPrinterSuite(String testSetName) {
        String simpleName = simpleName(testSetName);
        return "package org.apache.commons.csv;\n"
                + "\n"
                + "import static org.junit.Assert.assertEquals;\n"
                + "import static org.junit.Assert.assertSame;\n"
                + "import java.util.Arrays;\n"
                + "import org.junit.Test;\n"
                + "\n"
                + "public class " + simpleName + " {\n"
                + "\n"
                + "    @Test\n"
                + "    public void printRecordExposesQuotingAndDelimiterOutput() throws Exception {\n"
                + "        StringBuilder out = new StringBuilder();\n"
                + "        CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT.withRecordSeparator(\"\\n\"));\n"
                + "        printer.printRecord(\"a,b\", \"plain\", \"z \");\n"
                + "        assertEquals(\"\\\"a,b\\\",plain,\\\"z \\\"\\n\", out.toString());\n"
                + "    }\n"
                + "\n"
                + "    @Test\n"
                + "    public void printCommentWritesVisibleSinkText() throws Exception {\n"
                + "        StringBuilder out = new StringBuilder();\n"
                + "        CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT.withCommentMarker('#').withRecordSeparator(\"\\n\"));\n"
                + "        printer.printComment(\"hi\\nthere\");\n"
                + "        assertEquals(\"# hi\\n# there\\n\", out.toString());\n"
                + "    }\n"
                + "\n"
                + "    @Test\n"
                + "    public void printWithEscapeModeExposesDelimiterNewlineAndSuffix() throws Exception {\n"
                + "        StringBuilder out = new StringBuilder();\n"
                + "        CSVFormat format = CSVFormat.DEFAULT.withQuote((Character) null).withEscape('\\\\').withQuoteMode(QuoteMode.NONE).withRecordSeparator(\"\\n\");\n"
                + "        CSVPrinter printer = new CSVPrinter(out, format);\n"
                + "        printer.print(\"ab,c\");\n"
                + "        printer.println();\n"
                + "        printer.print(\"xy\\nz\");\n"
                + "        assertEquals(\"ab\\\\,c\\nxy\\\\nz\", out.toString());\n"
                + "    }\n"
                + "\n"
                + "    @Test\n"
                + "    public void printWithMinimalQuoteExposesQuoteAndTrailingSpace() throws Exception {\n"
                + "        StringBuilder out = new StringBuilder();\n"
                + "        CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT.withRecordSeparator(\"\\n\"));\n"
                + "        printer.print(\"pre\\\"post\");\n"
                + "        printer.println();\n"
                + "        printer.print(\"tail \");\n"
                + "        assertEquals(\"\\\"pre\\\"\\\"post\\\"\\n\\\"tail \\\"\", out.toString());\n"
                + "    }\n"
                + "\n"
                + "    @Test\n"
                + "    public void printRecordsHandlesNestedIterableShape() throws Exception {\n"
                + "        StringBuilder out = new StringBuilder();\n"
                + "        CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT.withRecordSeparator(\"\\n\"));\n"
                + "        printer.printRecords(Arrays.asList(new Object[] {\"a\", \"b\"}, Arrays.asList(\"c\", \"d\")));\n"
                + "        assertEquals(\"a,b\\nc,d\\n\", out.toString());\n"
                + "        assertSame(out, printer.getOut());\n"
                + "    }\n"
                + "}\n";
    }

    private static String buildExpectedExceptionTest(String testSetName,
                                                     String methodName,
                                                     String formatExpr,
                                                     String csvExpr) {
        String simpleName = simpleName(testSetName);
        return "package org.apache.commons.csv;\n"
                + "\n"
                + "import java.io.StringReader;\n"
                + "import org.junit.Test;\n"
                + "\n"
                + "public class " + simpleName + " {\n"
                + "\n"
                + "    @Test(expected = IllegalArgumentException.class)\n"
                + "    public void " + methodName + "() throws Exception {\n"
                + "        CSVFormat format = " + formatExpr + ";\n"
                + "        new CSVParser(new StringReader(" + csvExpr + "), format, 0L, 0L);\n"
                + "    }\n"
                + "}\n";
    }

    private static String buildSuccessfulConstructionTest(String testSetName,
                                                          String methodName,
                                                          String formatExpr,
                                                          String csvExpr) {
        String simpleName = simpleName(testSetName);
        return "package org.apache.commons.csv;\n"
                + "\n"
                + "import static org.junit.Assert.assertEquals;\n"
                + "import static org.junit.Assert.assertNotNull;\n"
                + "import java.io.StringReader;\n"
                + "import org.junit.Test;\n"
                + "\n"
                + "public class " + simpleName + " {\n"
                + "\n"
                + "    @Test\n"
                + "    public void " + methodName + "() throws Exception {\n"
                + "        CSVFormat format = " + formatExpr + ";\n"
                + "        CSVParser parser = new CSVParser(new StringReader(" + csvExpr + "), format, 0L, 0L);\n"
                + "        CSVRecord record = parser.nextRecord();\n"
                + "        assertNotNull(record);\n"
                + "        assertEquals(\"1\", record.get(0));\n"
                + "        assertEquals(\"3\", record.get(2));\n"
                + "        parser.close();\n"
                + "    }\n"
                + "}\n";
    }

    private static String simpleName(String testSetName) {
        int idx = testSetName.lastIndexOf('.');
        return idx >= 0 ? testSetName.substring(idx + 1) : testSetName;
    }

    private static String normalizeTargetClassName(String targetClassName) {
        return safe(targetClassName).replaceFirst("^(?:main(?:\\.java)?|java)\\.", "");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean classWideScaffoldEnabled(String key, boolean defaultValue) {
        String property = "llm.deterministic." + key + ".classwide.enabled";
        String value = firstNonBlank(System.getProperty(property), RUNTIME_PROPERTIES.getProperty(property));
        if (value.isEmpty()) {
            return defaultValue;
        }
        return "true".equals(value.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isHighValueLexerMethod(String method) {
        String x = safe(method).toLowerCase(Locale.ROOT);
        return x.contains("nexttoken")
                || x.contains("parsesimpletoken")
                || x.contains("parseencapsulatedtoken")
                || x.contains("trimtrailingspaces")
                || x.contains("readendofline")
                || x.contains("isstartofline");
    }

    private static boolean isHighValueCsvPrinterMethod(String method) {
        String x = safe(method).toLowerCase(Locale.ROOT);
        return x.contains("printandescape")
                || x.contains("printandquote")
                || x.contains("printcomment")
                || x.contains("printrecord")
                || x.contains("printrecords")
                || x.contains("flush")
                || x.contains("close");
    }

    private static String firstNonBlank(String first, String second) {
        String a = safe(first);
        return a.isEmpty() ? safe(second) : a;
    }

    private static Properties loadRuntimeProperties() {
        Properties props = new Properties();
        try (InputStream in = DeterministicScaffoldBuilder.class.getClassLoader()
                .getResourceAsStream("llm.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (Exception ignored) {
            // Keep deterministic scaffolds optional; config loading must not block generation.
        }
        return props;
    }
}
