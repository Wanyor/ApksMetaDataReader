package com.wanyor.android.app;

import java.util.HashMap;
import java.util.Map;

public class SimpleJsonParser {

    public static Map<String, Object> parse(String json) {
        Map<String, Object> result = new HashMap<>();
        if (json == null || json.isEmpty()) return result;
        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) return result;
        json = json.substring(1, json.length() - 1).trim();
        if (json.isEmpty()) return result;
        int i = 0;
        int len = json.length();
        while (i < len) {
            i = skipWhitespace(json, i);
            if (i >= len) break;
            if (json.charAt(i) != '"') break;
            int keyStart = i + 1;
            int keyEnd = findStringEnd(json, keyStart);
            String key = json.substring(keyStart, keyEnd);
            i = keyEnd + 1;
            i = skipWhitespace(json, i);
            if (i >= len || json.charAt(i) != ':') break;
            i++;
            i = skipWhitespace(json, i);
            if (i >= len) break;
            char c = json.charAt(i);
            if (c == '"') {
                int valStart = i + 1;
                int valEnd = findStringEnd(json, valStart);
                result.put(key, json.substring(valStart, valEnd));
                i = valEnd + 1;
            } else if (c == '-' || Character.isDigit(c)) {
                int numStart = i;
                boolean isFloat = false;
                while (i < len) {
                    char nc = json.charAt(i);
                    if (nc == '.' || nc == 'e' || nc == 'E') isFloat = true;
                    if (Character.isDigit(nc) || nc == '-' || nc == '+' || nc == '.' || nc == 'e' || nc == 'E') {
                        i++;
                    } else {
                        break;
                    }
                }
                String numStr = json.substring(numStart, i);
                if (isFloat) {
                    result.put(key, Double.parseDouble(numStr));
                } else {
                    try {
                        result.put(key, Long.parseLong(numStr));
                    } catch (NumberFormatException e) {
                        result.put(key, numStr);
                    }
                }
            } else if (c == 't') {
                if (json.substring(i).startsWith("true")) {
                    result.put(key, true);
                    i += 4;
                } else {
                    i++;
                }
            } else if (c == 'f') {
                if (json.substring(i).startsWith("false")) {
                    result.put(key, false);
                    i += 5;
                } else {
                    i++;
                }
            } else if (c == 'n') {
                if (json.substring(i).startsWith("null")) {
                    result.put(key, null);
                    i += 4;
                } else {
                    i++;
                }
            } else if (c == '[' || c == '{') {
                i = skipNested(json, i);
            } else {
                i++;
            }
            i = skipWhitespace(json, i);
            if (i < len && json.charAt(i) == ',') i++;
        }
        return result;
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private static int skipNested(String s, int start) {
        char open = s.charAt(start);
        char close = open == '[' ? ']' : '}';
        int depth = 1;
        int i = start + 1;
        while (i < s.length() && depth > 0) {
            char c = s.charAt(i);
            if (c == '"') {
                i = findStringEnd(s, i + 1) + 1;
            } else if (c == open) {
                depth++;
                i++;
            } else if (c == close) {
                depth--;
                i++;
            } else {
                i++;
            }
        }
        return i;
    }

    private static int findStringEnd(String s, int start) {
        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '"') {
                return i;
            } else {
                i++;
            }
        }
        return i;
    }
}