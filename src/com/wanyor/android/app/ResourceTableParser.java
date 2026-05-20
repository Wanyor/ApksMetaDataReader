package com.wanyor.android.app;

import java.util.HashMap;
import java.util.Map;

/**
 * Android 资源表（resources.arsc）解析器。
 *
 * resources.arsc 存储 APK 中所有资源的值，本实现只提取字符串类型的值
 * 以支持 appName、versionName 等字段的资源引用解析。
 *
 * 格式规范参见：
 * frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h
 *
 * 文件整体结构：
 *   ResTable_header（0x0002）
 *   ResStringPool（全局字符串池，TYPE_STRING 类型值均引用此池）
 *   ResTable_package（0x0200，一个 APK 通常只有一个包）
 *     ResTable_typeSpec（0x0202，类型规范，此处跳过）
 *     ResTable_type（0x0201，某一类型的所有配置值）
 *       entry-offset 数组 + ResTable_entry + Res_value
 */
public class ResourceTableParser {

    // -------------------------------------------------------------------------
    // Chunk 类型常量
    // -------------------------------------------------------------------------
    private static final int RES_STRING_POOL_TYPE   = 0x0001; // 字符串池
    private static final int RES_TABLE_TYPE         = 0x0002; // 资源表文件头
    private static final int RES_TABLE_PACKAGE_TYPE = 0x0200; // 包块
    private static final int RES_TABLE_TYPE_TYPE    = 0x0201; // 类型块（含具体资源值）
    // 0x0202 TYPE_SPEC、0x0203+ LIBRARY/OVERLAYABLE 等不需要，跳过

    // -------------------------------------------------------------------------
    // Res_value.dataType 常量（只处理本库需要的三种类型）
    // -------------------------------------------------------------------------
    private static final int TYPE_REFERENCE = 0x01; // 引用另一个资源 ID
    private static final int TYPE_STRING    = 0x03; // 全局字符串池索引
    private static final int TYPE_INT_DEC   = 0x10; // 十进制整数

    // -------------------------------------------------------------------------
    // ResTable_type.flags：决定条目偏移数组的编码格式
    // -------------------------------------------------------------------------
    private static final int TYPE_FLAG_SPARSE   = 0x01; // 稀疏格式（只存非空条目）
    private static final int TYPE_FLAG_OFFSET16 = 0x02; // 偏移以 uint16×4 表示

    // -------------------------------------------------------------------------
    // ResTable_entry.flags
    // -------------------------------------------------------------------------
    private static final int ENTRY_FLAG_COMPLEX = 0x0001; // 复合条目（map/bag），跳过
    private static final int ENTRY_FLAG_COMPACT = 0x0008; // 紧凑格式（无独立 Res_value）

    private static final int NO_ENTRY   = -1;     // uint32 0xFFFFFFFF 的有符号表示
    private static final int NO_ENTRY16 = 0xFFFF; // uint16 空条目标记

    /** 全局字符串池，所有 TYPE_STRING 值均引用此池中的字符串。 */
    private String[] globalStringPool;
    /** 解析结果：资源 ID → 字符串值。 */
    private Map<Integer, String> resourceMap;

    /**
     * 解析 resources.arsc 字节，返回资源 ID → 字符串的映射。
     * 仅提取字符串、整数和引用类型的值；图片、颜色等其他类型忽略。
     */
    public Map<Integer, String> parse(byte[] data) {
        resourceMap = new HashMap<>();
        if (data == null || data.length < 12) return resourceMap;

        int offset = 0;
        if (FileUtils.readUShort(data, offset) != RES_TABLE_TYPE) return resourceMap;
        int headerSize = FileUtils.readUShort(data, offset + 2);
        offset += headerSize;

        // 全局字符串池紧跟 ResTable_header 之后
        if (offset < data.length && FileUtils.readUShort(data, offset) == RES_STRING_POOL_TYPE) {
            globalStringPool = parseStringPool(data, offset);
            offset += FileUtils.readInt(data, offset + 4);
        }

        // 遍历包块
        while (offset < data.length - 8) {
            int chunkType = FileUtils.readUShort(data, offset);
            int chunkSize = FileUtils.readInt(data, offset + 4);
            if (chunkSize < 8 || offset + chunkSize > data.length) break;

            if (chunkType == RES_TABLE_PACKAGE_TYPE) {
                parsePackage(data, offset, chunkSize);
            }
            offset += chunkSize;
        }

        return resourceMap;
    }

    // -------------------------------------------------------------------------
    // 包块（ResTable_package, 0x0200）
    // -------------------------------------------------------------------------

    /**
     * 解析包块，遍历其中所有类型块。
     * 包块内的字符串池（类型名池、键名池）被跳过——
     * TYPE_STRING 值引用的是文件顶层的全局字符串池，而非包内子池。
     */
    private void parsePackage(byte[] data, int pkgStart, int pkgChunkSize) {
        int pkgHdrSize = FileUtils.readUShort(data, pkgStart + 2);
        int pkgEnd     = pkgStart + pkgChunkSize;
        int offset     = pkgStart + pkgHdrSize;

        while (offset < pkgEnd - 8) {
            int subType = FileUtils.readUShort(data, offset);
            int subSize = FileUtils.readInt(data, offset + 4);
            if (subSize < 8 || offset + subSize > pkgEnd) break;

            if (subType == RES_TABLE_TYPE_TYPE) {
                int packageId = FileUtils.readInt(data, pkgStart + 8);
                parseTypeChunk(data, offset, packageId);
            }
            offset += subSize;
        }
    }

    // -------------------------------------------------------------------------
    // 类型块（ResTable_type, 0x0201）
    //
    // 内存布局：
    //   [0-1]   chunk type (0x0201)
    //   [2-3]   headerSize
    //   [4-7]   chunk size
    //   [8]     id（类型 ID，从 1 开始）
    //   [9]     flags（FLAG_SPARSE=0x01, FLAG_OFFSET16=0x02）
    //   [10-11] reserved
    //   [12-15] entryCount（条目总数或稀疏条目数）
    //   [16-19] entriesStart（从 chunk 起始到第一个 ResTable_entry 的偏移）
    //   [20+]   ResTable_config（变长，首 uint32 为 config 自身大小）
    //   header 之后：条目偏移数组，然后是条目数据区
    // -------------------------------------------------------------------------

    private void parseTypeChunk(byte[] data, int typeStart, int packageId) {
        int headerSize   = FileUtils.readUShort(data, typeStart + 2);
        int typeId       = data[typeStart + 8] & 0xFF;
        int typeFlags    = data[typeStart + 9] & 0xFF;
        int entryCount   = FileUtils.readInt(data, typeStart + 12);
        int entriesStart = FileUtils.readInt(data, typeStart + 16);

        // 安全边界：防止畸形数据触发超大循环
        if (entryCount <= 0 || entryCount > 65536 || entriesStart <= 0) return;

        // 判断是否为默认配置（无语言/屏幕密度等限定符）
        int configStart = typeStart + 20;
        if (configStart + 4 > data.length) return;
        int configSize = FileUtils.readInt(data, configStart);
        boolean isDefault = isDefaultConfig(data, configStart, configSize);

        int offsetsStart     = typeStart + headerSize;   // 条目偏移数组起始
        int entriesDataStart = typeStart + entriesStart; // 条目数据区起始

        boolean sparse   = (typeFlags & TYPE_FLAG_SPARSE)   != 0;
        boolean offset16 = (typeFlags & TYPE_FLAG_OFFSET16) != 0;

        if (sparse) {
            parseSparse(data, packageId, typeId, entryCount, offsetsStart, entriesDataStart, isDefault);
        } else if (offset16) {
            parseOffset16(data, packageId, typeId, entryCount, offsetsStart, entriesDataStart, isDefault);
        } else {
            parseStandard(data, packageId, typeId, entryCount, offsetsStart, entriesDataStart, isDefault);
        }
    }

    /**
     * 标准格式：偏移数组为 uint32[entryCount]。
     * 值为 0xFFFFFFFF（NO_ENTRY）表示该条目在此配置下不存在。
     */
    private void parseStandard(byte[] data, int packageId, int typeId, int entryCount,
                                int offsetsStart, int entriesDataStart, boolean isDefault) {
        for (int i = 0; i < entryCount; i++) {
            int offsetPos = offsetsStart + i * 4;
            if (offsetPos + 4 > data.length) break;
            int off = FileUtils.readInt(data, offsetPos);
            if (off == NO_ENTRY) continue;
            putEntry(data, entriesDataStart + off, buildId(packageId, typeId, i), isDefault);
        }
    }

    /**
     * FLAG_OFFSET16 格式：偏移数组为 uint16[entryCount]，实际偏移 = 值 × 4。
     * 值为 0xFFFF（NO_ENTRY16）表示该条目不存在。
     */
    private void parseOffset16(byte[] data, int packageId, int typeId, int entryCount,
                                int offsetsStart, int entriesDataStart, boolean isDefault) {
        for (int i = 0; i < entryCount; i++) {
            int offsetPos = offsetsStart + i * 2;
            if (offsetPos + 2 > data.length) break;
            int raw = FileUtils.readUShort(data, offsetPos);
            if (raw == NO_ENTRY16) continue;
            putEntry(data, entriesDataStart + raw * 4, buildId(packageId, typeId, i), isDefault);
        }
    }

    /**
     * FLAG_SPARSE 格式：偏移数组为 uint32[sparseCount]，每项打包了索引和偏移：
     *   entryIdx = packed & 0xFFFF
     *   offset   = ((packed >> 16) & 0xFFFF) * 4
     * sparseCount 即 entryCount，只存储非空条目。
     */
    private void parseSparse(byte[] data, int packageId, int typeId, int sparseCount,
                             int offsetsStart, int entriesDataStart, boolean isDefault) {
        for (int s = 0; s < sparseCount; s++) {
            int pos = offsetsStart + s * 4;
            if (pos + 4 > data.length) break;
            int packed   = FileUtils.readInt(data, pos);
            int entryIdx = packed & 0xFFFF;
            int off      = ((packed >> 16) & 0xFFFF) * 4;
            putEntry(data, entriesDataStart + off, buildId(packageId, typeId, entryIdx), isDefault);
        }
    }

    // -------------------------------------------------------------------------
    // 条目解析（ResTable_entry + Res_value，或紧凑变体）
    // -------------------------------------------------------------------------

    /**
     * 读取单个条目并将其字符串值存入 resourceMap。
     *
     * 两种格式：
     *   紧凑格式（ENTRY_FLAG_COMPACT）：8 字节，dataType 编码在 entryFlags 高字节。
     *   标准格式：ResTable_entry（entrySize 字节）+ Res_value（8 字节）。
     *
     * 优先存储默认配置的值；非默认配置只在该资源 ID 尚无记录时才写入。
     */
    private void putEntry(byte[] data, int entryPos, int resourceId, boolean isDefault) {
        if (entryPos + 4 > data.length) return;

        int entrySize  = FileUtils.readUShort(data, entryPos);
        int entryFlags = FileUtils.readUShort(data, entryPos + 2);

        if ((entryFlags & ENTRY_FLAG_COMPLEX) != 0) return; // 复合条目（map/bag），不含简单值

        String value;
        if ((entryFlags & ENTRY_FLAG_COMPACT) != 0) {
            // 紧凑格式：无独立 Res_value 结构，dataType 在 flags 高字节
            if (entryPos + 8 > data.length) return;
            int dataType = (entryFlags >> 8) & 0xFF;
            int valData  = FileUtils.readInt(data, entryPos + 4);
            value = resolveValue(dataType, valData);
        } else {
            // 标准格式：Res_value 紧跟 ResTable_entry 之后
            // Res_value 布局：size(2) res0(1) dataType(1) data(4)
            if (entryPos + entrySize + 8 > data.length) return;
            int valBase  = entryPos + entrySize;
            int dataType = data[valBase + 3] & 0xFF;
            int valData  = FileUtils.readInt(data, valBase + 4);
            value = resolveValue(dataType, valData);
        }

        if (value == null) return;
        // 默认配置优先；非默认配置只填空缺
        if (isDefault || !resourceMap.containsKey(resourceId)) {
            resourceMap.put(resourceId, value);
        }
    }

    /**
     * 根据数据类型将值转换为字符串。
     * 只处理字符串、整数、引用三种类型；其他类型返回 null（不放入 resourceMap）。
     */
    private String resolveValue(int dataType, int data) {
        switch (dataType) {
            case TYPE_STRING:
                // 值为全局字符串池的索引
                return getFromPool(globalStringPool, data);
            case TYPE_INT_DEC:
                return String.valueOf(data);
            case TYPE_REFERENCE:
                // 保留引用格式，由调用方（ApkParser.resolveValue）继续跳转
                return "@0x" + Integer.toHexString(data);
            default:
                return null;
        }
    }

    /**
     * 构建 32 位资源 ID：
     *   高 8 位 = packageId，次 8 位 = typeId，低 16 位 = entryIndex。
     */
    private int buildId(int packageId, int typeId, int entryIndex) {
        return (packageId << 24) | (typeId << 16) | entryIndex;
    }

    // -------------------------------------------------------------------------
    // 字符串池（ResStringPool_header, 0x0001）
    //
    // 布局：
    //   [0-1]   chunk type (0x0001)
    //   [2-3]   headerSize
    //   [4-7]   chunk size
    //   [8-11]  stringCount
    //   [12-15] styleCount
    //   [16-19] flags（UTF8_FLAG = 0x100）
    //   [20-23] stringsStart（相对 chunk 起始的字符串数据偏移）
    //   [24-27] stylesStart
    //   [28+]   uint32[stringCount] 偏移数组
    //   [stringsStart+] 字符串数据
    // -------------------------------------------------------------------------

    /**
     * 解析字符串池，返回字符串数组。
     * 安全限制：stringCount 超过 500000 时拒绝，防止 OOM。
     */
    private String[] parseStringPool(byte[] data, int offset) {
        int stringCount  = FileUtils.readInt(data, offset + 8);
        if (stringCount <= 0 || stringCount > 500_000) return new String[0];
        int flags        = FileUtils.readInt(data, offset + 16);
        int stringsStart = FileUtils.readInt(data, offset + 20);
        boolean isUtf8   = (flags & 0x0100) != 0;

        String[] pool = new String[stringCount];
        for (int i = 0; i < stringCount; i++) {
            int offsetPos = offset + 28 + i * 4;
            if (offsetPos + 4 > data.length) break; // 数据不足，截断
            int strOff = offset + stringsStart + FileUtils.readInt(data, offsetPos);
            if (strOff < 0 || strOff >= data.length) {
                pool[i] = "";
                continue;
            }
            pool[i] = isUtf8 ? readUtf8String(data, strOff) : readUtf16String(data, strOff);
        }
        return pool;
    }

    /**
     * 读取 UTF-8 编码字符串。
     * 格式：[charLen（变长）] [byteLen（变长）] [UTF-8 字节]
     * 高位为 1 时表示两字节长度编码。
     */
    private String readUtf8String(byte[] data, int offset) {
        try {
            int pos = offset;
            int charLen = data[pos++] & 0xFF;
            if ((charLen & 0x80) != 0) pos++; // 跳过两字节字符数的第二字节
            int byteLen = data[pos++] & 0xFF;
            if ((byteLen & 0x80) != 0) {
                byteLen = ((byteLen & 0x7F) << 8) | (data[pos++] & 0xFF);
            }
            int end = Math.min(pos + byteLen, data.length);
            return new String(data, pos, end - pos, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 读取 UTF-16LE 编码字符串。
     * 格式：[charLen（uint16，高位为 1 时扩展为 uint32）] [UTF-16LE 字节]
     * 使用 long 算术避免 charLen * 2 的整数溢出。
     */
    private String readUtf16String(byte[] data, int offset) {
        try {
            int charLen = FileUtils.readUShort(data, offset);
            int strPos  = offset + 2;
            if ((charLen & 0x8000) != 0) {
                // 高位为 1：扩展为 32 位字符数
                charLen = ((charLen & 0x7FFF) << 16) | FileUtils.readUShort(data, strPos);
                strPos += 2;
            }
            // 用 long 防止 charLen * 2 溢出
            int byteLen = (int) Math.min((long) charLen * 2, data.length - strPos);
            if (byteLen <= 0) return "";
            return new String(data, strPos, byteLen, "UTF-16LE");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 判断该配置是否为默认配置（无语言、屏幕密度等限定符）。
     * 默认配置的 size 字段之后的所有字节均为 0。
     * 只检查前 64 字节以覆盖常见配置字段，避免读取过长。
     */
    private boolean isDefaultConfig(byte[] data, int configStart, int configSize) {
        for (int i = 4; i < configSize && i < 64; i++) {
            int pos = configStart + i;
            if (pos >= data.length) break;
            if (data[pos] != 0) return false;
        }
        return true;
    }

    /** 从字符串池中按索引取值；越界或池为 null 时返回 null。 */
    private String getFromPool(String[] pool, int index) {
        if (pool == null || index < 0 || index >= pool.length) return null;
        return pool[index];
    }
}
