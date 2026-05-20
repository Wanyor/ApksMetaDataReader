package com.wanyor.android.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Android 二进制 XML（AXML）解析器。
 *
 * Android 工具链将 AndroidManifest.xml 编译为二进制格式以减小体积。
 * 格式规范参见 Android 源码：
 * frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h
 *
 * 文件整体结构：
 *   ResXMLTree_header（RES_XML_TYPE = 0x0003）
 *   ResStringPool_header（字符串池）
 *   ResXMLTree_node（各 chunk：资源映射、命名空间、元素…）
 *
 * 本实现只关注 START_ELEMENT chunk 以提取元素名与属性值。
 */
public class BinaryXmlParser {

    // -------------------------------------------------------------------------
    // Chunk 类型常量（ResChunk_header.type）
    // -------------------------------------------------------------------------
    private static final int RES_STRING_POOL_TYPE        = 0x0001; // 字符串池
    private static final int RES_XML_TYPE                = 0x0003; // AXML 文件头
    private static final int RES_XML_START_NAMESPACE_TYPE= 0x0100; // 命名空间开始
    private static final int RES_XML_END_NAMESPACE_TYPE  = 0x0101; // 命名空间结束
    private static final int RES_XML_START_ELEMENT_TYPE  = 0x0102; // 元素开始标签
    private static final int RES_XML_END_ELEMENT_TYPE    = 0x0103; // 元素结束标签
    private static final int RES_XML_CDATA_TYPE          = 0x0104; // 文本节点
    private static final int RES_XML_RESOURCE_MAP_TYPE   = 0x0180; // 属性→资源 ID 映射

    // -------------------------------------------------------------------------
    // Res_value.dataType 常量（属性值类型）
    // -------------------------------------------------------------------------
    private static final int TYPE_NULL          = 0x00; // 空
    private static final int TYPE_REFERENCE     = 0x01; // 资源引用（@res/xxx）
    private static final int TYPE_ATTRIBUTE     = 0x02; // 属性引用（?attr/xxx）
    private static final int TYPE_STRING        = 0x03; // 字符串池索引
    private static final int TYPE_FLOAT         = 0x04; // 浮点数
    private static final int TYPE_DIMENSION     = 0x05; // 带单位尺寸（dp/sp/px…）
    private static final int TYPE_FRACTION      = 0x06; // 百分比
    private static final int TYPE_INT_DEC       = 0x10; // 十进制整数
    private static final int TYPE_INT_HEX       = 0x11; // 十六进制整数
    private static final int TYPE_INT_BOOLEAN   = 0x12; // 布尔值
    private static final int TYPE_INT_COLOR_ARGB8 = 0x1c;
    private static final int TYPE_INT_COLOR_RGB8  = 0x1d;
    private static final int TYPE_INT_COLOR_ARGB4 = 0x1e;
    private static final int TYPE_INT_COLOR_RGB4  = 0x1f;

    /** 当前文件的字符串池，由 parseStringPool 填充。 */
    private String[] stringPool;
    /** 属性索引→资源 ID 映射，由 parseResourceMap 填充。 */
    private int[] resourceIds;
    /** 外部资源表（来自 resources.arsc），用于解析资源引用。 */
    private Map<Integer, String> externalResourceMap;

    /**
     * 解析 AXML 字节，不使用外部资源表。
     * 返回：元素名 → 该元素所有属性的列表。
     */
    public Map<String, List<XmlAttribute>> parse(byte[] data) {
        return parse(data, null);
    }

    /**
     * 解析 AXML 字节，并使用外部资源表解析资源引用。
     * @param data        AndroidManifest.xml 的原始字节
     * @param resourceMap resources.arsc 解析出的资源 ID→字符串映射，可为 null
     * @return 元素名 → 属性列表的映射
     */
    public Map<String, List<XmlAttribute>> parse(byte[] data, Map<Integer, String> resourceMap) {
        this.externalResourceMap = resourceMap;
        Map<String, List<XmlAttribute>> elements = new HashMap<>();
        if (data == null || data.length < 8) return elements;

        int offset = 0;
        int type = FileUtils.readUShort(data, offset);
        int headerSize = FileUtils.readUShort(data, offset + 2);

        // 验证文件头类型
        if (type != RES_XML_TYPE) return elements;
        offset += headerSize;

        // 紧跟文件头的通常是字符串池
        if (offset >= data.length) return elements;
        type = FileUtils.readUShort(data, offset);
        if (type == RES_STRING_POOL_TYPE) {
            parseStringPool(data, offset);
            offset += FileUtils.readInt(data, offset + 4);
        }

        // 遍历剩余所有 chunk
        while (offset < data.length - 8) {
            type = FileUtils.readUShort(data, offset);
            int chunkSize = FileUtils.readInt(data, offset + 4);
            // chunkSize < 8：小于最小合法 chunk header 大小，拒绝以防解析错位
            if (chunkSize < 8 || offset + chunkSize > data.length) break;

            if (type == RES_XML_RESOURCE_MAP_TYPE) {
                parseResourceMap(data, offset);
            } else if (type == RES_XML_START_ELEMENT_TYPE) {
                parseStartElement(data, offset, elements);
            } else if (type == RES_STRING_POOL_TYPE) {
                parseStringPool(data, offset);
            }

            offset += chunkSize;
        }

        return elements;
    }

    /**
     * 解析字符串池 chunk（ResStringPool_header）。
     * 支持 UTF-8（flag 0x100）和 UTF-16LE 两种编码。
     * 安全限制：stringCount 超过 65536 时拒绝解析，防止 OOM。
     */
    private void parseStringPool(byte[] data, int offset) {
        int type = FileUtils.readUShort(data, offset);
        int headerSize = FileUtils.readUShort(data, offset + 2);
        int chunkSize = FileUtils.readInt(data, offset + 4);
        if (type != RES_STRING_POOL_TYPE) return;

        int stringCount = FileUtils.readInt(data, offset + 8);
        // 安全边界：防止畸形数据触发超大数组分配
        if (stringCount <= 0 || stringCount > 65536) return;
        int flags = FileUtils.readInt(data, offset + 16);
        int stringsStart = FileUtils.readInt(data, offset + 20);

        boolean isUtf8 = (flags & 0x0100) != 0;

        // 读取字符串偏移数组（每项 4 字节，相对 stringsStart 的偏移）
        int[] offsetsArray = new int[stringCount];
        for (int i = 0; i < stringCount; i++) {
            int offsetPos = offset + 28 + i * 4;
            if (offsetPos + 4 > data.length) {
                stringCount = i; // 数据不足，截断
                break;
            }
            offsetsArray[i] = FileUtils.readInt(data, offsetPos);
        }

        stringPool = new String[stringCount];
        for (int i = 0; i < stringCount; i++) {
            int strOffset = offset + stringsStart + offsetsArray[i];
            if (strOffset < 0 || strOffset >= data.length) {
                stringPool[i] = "";
                continue;
            }
            stringPool[i] = isUtf8 ? readUtf8String(data, strOffset) : readUtf16String(data, strOffset);
        }
    }

    /**
     * 读取 UTF-8 编码字符串。
     * 格式：[charLen(变长)] [byteLen(变长)] [UTF-8 字节]
     * 变长编码：高位为 1 时表示两字节长度。
     */
    private String readUtf8String(byte[] data, int offset) {
        try {
            int charLen = data[offset] & 0xFF;
            int bytePos = offset + 1;
            if ((charLen & 0x80) != 0) {
                // 两字节字符数长度编码
                charLen = ((charLen & 0x7F) << 8) | (data[bytePos] & 0xFF);
                bytePos++;
            }
            int byteLen = data[bytePos] & 0xFF;
            bytePos++;
            if ((byteLen & 0x80) != 0) {
                // 两字节字节数长度编码
                byteLen = ((byteLen & 0x7F) << 8) | (data[bytePos] & 0xFF);
                bytePos++;
            }
            int end = bytePos + byteLen;
            if (end > data.length) end = data.length;
            byte[] strBytes = new byte[byteLen];
            System.arraycopy(data, bytePos, strBytes, 0, Math.min(byteLen, data.length - bytePos));
            return new String(strBytes, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 读取 UTF-16LE 编码字符串。
     * 格式：[charLen(uint16，高位为 1 时扩展为 uint32)] [UTF-16LE 字节]
     * 使用 long 算术避免 charLen * 2 的整数溢出。
     */
    private String readUtf16String(byte[] data, int offset) {
        try {
            int charLen = FileUtils.readUShort(data, offset);
            int strPos = offset + 2;
            if ((charLen & 0x8000) != 0) {
                // 高位为 1：扩展为 32 位字符数
                charLen = ((charLen & 0x7FFF) << 16) | FileUtils.readUShort(data, strPos);
                strPos += 2;
            }
            // 用 long 乘法防止 charLen * 2 溢出后截断为负值
            int byteLen = (int) Math.min((long) charLen * 2, data.length - strPos);
            if (byteLen <= 0) return "";
            byte[] strBytes = new byte[byteLen];
            System.arraycopy(data, strPos, strBytes, 0, byteLen);
            return new String(strBytes, "UTF-16LE");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 解析资源映射 chunk（RES_XML_RESOURCE_MAP_TYPE）。
     * 建立属性字符串池索引 → 资源 ID 的对应关系，用于查找属性名的规范形式。
     */
    private void parseResourceMap(byte[] data, int offset) {
        int headerSize = FileUtils.readUShort(data, offset + 2);
        int chunkSize = FileUtils.readInt(data, offset + 4);
        int count = (chunkSize - headerSize) / 4;
        if (count <= 0) return;
        resourceIds = new int[count];
        for (int i = 0; i < count; i++) {
            resourceIds[i] = FileUtils.readInt(data, offset + headerSize + i * 4);
        }
    }

    /**
     * 解析 START_ELEMENT chunk（ResXMLTree_node + ResXMLTree_attrExt）。
     *
     * 内存布局：
     *   ResXMLTree_node（16 字节）：header(8) + lineNumber(4) + comment(4)
     *   ResXMLTree_attrExt（从 offset+16 开始）：
     *     ns(4) + name(4) + attrStart(2) + attrSize(2) + attrCount(2) + …
     *   属性数组（每项 attrSize 字节，通常 20 字节）：
     *     ns(4) + name(4) + rawValue(4) + valueSize(2) + res0(1) + dataType(1) + data(4)
     */
    private void parseStartElement(byte[] data, int offset, Map<String, List<XmlAttribute>> elements) {
        // offset+16 = ResXMLTree_attrExt 起始
        // offset+20 = 元素名称在字符串池中的索引（offset+16 是命名空间 URI，此处跳过）
        int nameIndex = FileUtils.readInt(data, offset + 20);

        String elementName = getString(nameIndex);
        if (elementName == null) return;

        int attrStart = FileUtils.readUShort(data, offset + 24); // 属性区域相对 attrExt 起始的偏移
        int attrSize  = FileUtils.readUShort(data, offset + 26); // 单个属性的字节大小（通常 20）
        int attrCount = FileUtils.readUShort(data, offset + 28); // 属性个数

        List<XmlAttribute> attrs = new ArrayList<>();
        // 属性数组起始 = ResXMLTree_node(16字节) + attrStart
        int attrOffset = offset + 16 + attrStart;
        for (int i = 0; i < attrCount; i++) {
            int aOff = attrOffset + i * attrSize;
            if (aOff + attrSize > data.length) break;

            int nsIdx       = FileUtils.readInt(data, aOff);      // 命名空间 URI 的字符串池索引
            int nameIdx     = FileUtils.readInt(data, aOff + 4);  // 属性名的字符串池索引
            int rawValueIdx = FileUtils.readInt(data, aOff + 8);  // 原始字符串值的字符串池索引（-1 表示无）
            int valueType   = data[aOff + 15] & 0xFF;             // Res_value.dataType
            int valueData   = FileUtils.readInt(data, aOff + 16); // Res_value.data

            String ns       = getString(nsIdx);
            String attrName = getString(nameIdx);
            String rawValue = getString(rawValueIdx);
            String value    = resolveAttributeValue(rawValue, valueType, valueData);

            XmlAttribute attr = new XmlAttribute();
            attr.namespace     = ns;
            attr.name          = attrName;
            attr.rawValue      = rawValue;
            attr.valueType     = valueType;
            attr.valueData     = valueData;
            attr.resolvedValue = value;
            attrs.add(attr);
        }

        if (!elements.containsKey(elementName)) {
            elements.put(elementName, new ArrayList<>());
        }
        elements.get(elementName).addAll(attrs);
    }

    /**
     * 将属性的原始值和类型信息转换为可读字符串。
     * rawValue 优先：若非空且不是资源引用占位符，直接返回。
     * 否则根据 valueType 解析 valueData。
     */
    private String resolveAttributeValue(String rawValue, int valueType, int valueData) {
        if (rawValue != null && !rawValue.isEmpty()) {
            // 检查是否为资源引用格式（"@RRGGBBAA"）
            if (rawValue.startsWith("@")) {
                String stripped = rawValue.substring(1);
                if (stripped.length() > 0 && stripped.matches("[0-9a-fA-F]+")) {
                    try {
                        int refId = Integer.parseInt(stripped, 16);
                        String resolved = resolveResourceRef(refId);
                        if (resolved != null) return resolved;
                    } catch (NumberFormatException ignored) {}
                }
            }
            return rawValue;
        }
        switch (valueType) {
            case TYPE_STRING:
                // TYPE_STRING：valueData 是字符串池索引，直接取值
                return getString(valueData);
            case TYPE_INT_DEC:
                return String.valueOf(valueData);
            case TYPE_INT_HEX:
                return "0x" + Integer.toHexString(valueData);
            case TYPE_INT_BOOLEAN:
                return valueData != 0 ? "true" : "false";
            case TYPE_REFERENCE:
                // 资源引用：通过资源表或资源映射查找真实字符串
                return resolveResourceRef(valueData);
            case TYPE_FLOAT:
                return String.valueOf(Float.intBitsToFloat(valueData));
            case TYPE_DIMENSION:
                return decodeDimension(valueData);
            case TYPE_NULL:
                return null;
            default:
                if (valueType >= TYPE_INT_COLOR_ARGB8 && valueType <= TYPE_INT_COLOR_RGB4) {
                    return "#" + Integer.toHexString(valueData);
                }
                return String.valueOf(valueData);
        }
    }

    /**
     * 通过资源 ID 在资源映射中查找对应的字符串值。
     * 先查当前文件的资源映射（resourceIds），再查外部资源表（resources.arsc）。
     */
    private String resolveResourceRef(int refId) {
        // 在本文件的资源映射中查找（resourceIds[i] == refId → 返回字符串池第 i 个字符串）
        if (resourceIds != null) {
            for (int i = 0; i < resourceIds.length; i++) {
                if (resourceIds[i] == refId) {
                    return getString(i);
                }
            }
        }
        // 在外部资源表中查找
        if (externalResourceMap != null) {
            String val = externalResourceMap.get(refId);
            if (val != null) return val;
        }
        return null;
    }

    /**
     * 解码 TYPE_DIMENSION 值。
     * 低 8 位为单位，高 24 位为数值。
     */
    private String decodeDimension(int valueData) {
        int unit = (valueData & 0xFF);
        int val = valueData >> 8;
        String unitStr;
        switch (unit) {
            case 0: unitStr = "px"; break;
            case 1: unitStr = "dp"; break;
            case 2: unitStr = "sp"; break;
            case 3: unitStr = "pt"; break;
            case 4: unitStr = "in"; break;
            case 5: unitStr = "mm"; break;
            default: unitStr = ""; break;
        }
        return val + unitStr;
    }

    /** 从字符串池中按索引取字符串；索引越界或池未初始化时返回 null。 */
    String getString(int index) {
        if (stringPool == null || index < 0 || index >= stringPool.length) return null;
        return stringPool[index];
    }

    /** 从资源 ID 映射中按索引取资源 ID；越界时返回 0。 */
    int getResourceId(int index) {
        if (resourceIds == null || index < 0 || index >= resourceIds.length) return 0;
        return resourceIds[index];
    }

    /** 表示 XML 元素的单个属性（名称 + 值 + 类型信息）。 */
    public static class XmlAttribute {
        String namespace;    // 命名空间 URI（如 "http://schemas.android.com/apk/res/android"）
        String name;         // 属性名
        String rawValue;     // 原始字符串值（字符串池中的文本），可为 null
        int valueType;       // Res_value.dataType
        int valueData;       // Res_value.data
        String resolvedValue;// 解析后的可读字符串

        public String getNamespace()     { return namespace; }
        public String getName()          { return name; }
        public String getRawValue()      { return rawValue; }
        public int getValueType()        { return valueType; }
        public int getValueData()        { return valueData; }
        public String getResolvedValue() { return resolvedValue; }

        /** 判断该属性是否属于 Android 命名空间。 */
        public boolean isAndroidNamespace() {
            return namespace != null && namespace.contains("android");
        }
    }
}
