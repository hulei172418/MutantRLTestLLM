package org.utils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Java8Compat {
    private Java8Compat() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    @SafeVarargs
    public static <T> List<T> listOf(T... items) {
        if (items == null || items.length == 0) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(Arrays.asList(items.clone()));
    }

    @SafeVarargs
    public static <T> Set<T> setOf(T... items) {
        if (items == null || items.length == 0) {
            return Collections.emptySet();
        }
        LinkedHashSet<T> set = new LinkedHashSet<T>(Arrays.asList(items.clone()));
        return Collections.unmodifiableSet(set);
    }

    public static Map<String, Object> mapOf(Object... entries) {
        if (entries == null || entries.length == 0) {
            return Collections.emptyMap();
        }
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("entries length must be even");
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

    public static String readString(Path path) throws IOException {
        return readString(path, StandardCharsets.UTF_8);
    }

    public static String readString(Path path, Charset charset) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return new String(bytes, charset);
    }

    public static void writeString(Path path, String content, Charset charset, StandardOpenOption... options)
            throws IOException {
        byte[] bytes = (content == null ? "" : content).getBytes(charset);
        Files.write(path, bytes, options);
    }
}


