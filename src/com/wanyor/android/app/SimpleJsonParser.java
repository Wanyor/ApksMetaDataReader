package com.wanyor.android.app;

import java.util.HashMap;
import java.util.Map;

/**
 * 轻量级 JSON 顶层对象解析器，专为解析 XAPK 的 manifest.json 设计。
 * 只支持顶层 key-value；嵌套的数组和对象会被跳过（不放入结果 Map）。
 * 值类型映射：字符串→String，整数→Long，浮点数→Double，布尔→Boolean，null→null。
 */
public class SimpleJsonParser {

    /**
     * 解析 JSON 对象字符串，返回顶层字段的 key-value 映射。
     * 非对象格式（不以 { 开头）或格式错误时返回空 Map。
     */
    public static Map<String, Object> parse(String json) {
        Map<String, Object> result = new HashMap<>();
        if (json == null || json.isEmpty()) return result;
        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) return result;
        // 去掉最外层花括号
        json = json.substring(1, json.length() - 1).trim();
        if (json.isEmpty()) return result;
        int i = 0;
        int len = json.length();
        while (i < len) {
            i = skipWhitespace(json, i);
            if (i >= len) break;
            // 每个 key 必须是双引号字符串
            if (json.charAt(i) != '"') break;
            int keyStart = i + 1;
            int keyEnd = findStringEnd(json, keyStart);
            String key = json.substring(keyStart, keyEnd);
            i = keyEnd + 1; // 跳过结束引号
            i = skipWhitespace(json, i);
            if (i >= len || json.charAt(i) != ':') break;
            i++; // 跳过冒号
            i = skipWhitespace(json, i);
            if (i >= len) break;
            char c = json.charAt(i);
            if (c == '"') {
                // 字符串值
                int valStart = i + 1;
                int valEnd = findStringEnd(json, valStart);
                result.put(key, json.substring(valStart, valEnd));
                i = valEnd + 1;
            } else if (c == '-' || Character.isDigit(c)) {
                // 数值：判断是整数还是浮点数
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
                // 嵌套数组或对象：跳过整个结构，不放入结果
                i = skipNested(json, i);
            } else {
                i++;
            }
            i = skipWhitespace(json, i);
            if (i < len && json.charAt(i) == ',') i++; // 跳过分隔逗号
        }
        return result;
    }

    /** 跳过空白字符，返回下一个非空白字符的位置。 */
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

    /**
     * 跳过以 start 位置的括号（[ 或 {）开头的嵌套结构，
     * 返回结构结束后的下一个字符位置。
     * 使用 depth 计数而非递归，避免深度嵌套导致栈溢出。
     */
    private static int skipNested(String s, int start) {
        char open = s.charAt(start);
        char close = open == '[' ? ']' : '}';
        int depth = 1;
        int i = start + 1;
        while (i < s.length() && depth > 0) {
            char c = s.charAt(i);
            if (c == '"') {
                // 字符串内的括号不计入深度
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

    /**
     * 从 start 位置查找 JSON 字符串的结束引号位置（含转义处理）。
     * 返回结束引号的索引；未找到时返回字符串末尾。
     */
    private static int findStringEnd(String s, int start) {
        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2; // 跳过转义字符（如 \" \\ \n）
            } else if (c == '"') {
                return i;
            } else {
                i++;
            }
        }
        return i;
    }
}
