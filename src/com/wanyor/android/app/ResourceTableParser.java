package com.wanyor.android.app;

import java.util.HashMap;
import java.util.Map;

public class ResourceTableParser {

    // Chunk types (from ResourceTypes.h)
    private static final int RES_STRING_POOL_TYPE    = 0x0001;
    private static final int RES_TABLE_TYPE          = 0x0002;
    private static final int RES_TABLE_PACKAGE_TYPE  = 0x0200;
    private static final int RES_TABLE_TYPE_TYPE     = 0x0201;
    // 0x0202 (TYPE_SPEC), 0x0203+ (LIBRARY, OVERLAYABLE, …) are skipped – not needed

    // Res_value dataType constants
    private static final int TYPE_REFERENCE = 0x01;
    private static final int TYPE_STRING    = 0x03;
    private static final int TYPE_INT_DEC   = 0x10;

    // ResTable_type.flags
    private static final int TYPE_FLAG_SPARSE    = 0x01;
    private static final int TYPE_FLAG_OFFSET16  = 0x02;

    // ResTable_entry.flags
    private static final int ENTRY_FLAG_COMPLEX = 0x0001; // map entry, skip
    private static final int ENTRY_FLAG_COMPACT = 0x0008;

    private static final int NO_ENTRY    = -1;     // 0xFFFFFFFF as signed int
    private static final int NO_ENTRY16  = 0xFFFF;

    private String[] globalStringPool;
    private Map<Integer, String> resourceMap;

    public Map<Integer, String> parse(byte[] data) {
        resourceMap = new HashMap<>();
        if (data == null || data.length < 12) return resourceMap;

        int offset = 0;
        if (FileUtils.readUShort(data, offset) != RES_TABLE_TYPE) return resourceMap;
        int headerSize = FileUtils.readUShort(data, offset + 2);
        offset += headerSize;

        // Global string pool immediately follows the ResTable_header
        if (offset < data.length && FileUtils.readUShort(data, offset) == RES_STRING_POOL_TYPE) {
            globalStringPool = parseStringPool(data, offset);
            offset += FileUtils.readInt(data, offset + 4);
        }

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
    // Package
    // -------------------------------------------------------------------------

    private void parsePackage(byte[] data, int pkgStart, int pkgChunkSize) {
        int packageId   = FileUtils.readInt(data, pkgStart + 8);
        int pkgHdrSize  = FileUtils.readUShort(data, pkgStart + 2);
        int pkgEnd      = pkgStart + pkgChunkSize;
        int offset      = pkgStart + pkgHdrSize;

        while (offset < pkgEnd - 8) {
            int subType     = FileUtils.readUShort(data, offset);
            int subSize     = FileUtils.readInt(data, offset + 4);
            if (subSize < 8 || offset + subSize > pkgEnd) break;

            if (subType == RES_TABLE_TYPE_TYPE) {
                parseTypeChunk(data, offset, packageId);
            }
            // RES_STRING_POOL_TYPE (type/key pools) and all other sub-chunks are skipped –
            // TYPE_STRING values reference the global pool, not the package sub-pools.
            offset += subSize;
        }
    }

    // -------------------------------------------------------------------------
    // Type chunk (ResTable_type, chunk type 0x0201)
    // Layout:
    //   [0-1]  chunk type
    //   [2-3]  headerSize
    //   [4-7]  chunk size
    //   [8]    id  (uint8)
    //   [9]    flags (FLAG_SPARSE=0x01, FLAG_OFFSET16=0x02)
    //   [10-11] reserved
    //   [12-15] entryCount
    //   [16-19] entriesStart  (offset from chunk start to first ResTable_entry)
    //   [20+]   ResTable_config  (variable size; first uint32 is the config's own size)
    //   After header: entry-offset array, then entry data at entriesStart
    // -------------------------------------------------------------------------

    private void parseTypeChunk(byte[] data, int typeStart, int packageId) {
        int headerSize  = FileUtils.readUShort(data, typeStart + 2);
        int typeId      = data[typeStart + 8] & 0xFF;
        int typeFlags   = data[typeStart + 9] & 0xFF;
        int entryCount  = FileUtils.readInt(data, typeStart + 12);
        int entriesStart= FileUtils.readInt(data, typeStart + 16);

        if (entryCount <= 0 || entriesStart <= 0) return;

        // Determine if this is the default (no-qualifier) config
        int configStart = typeStart + 20;
        if (configStart + 4 > data.length) return;
        int configSize  = FileUtils.readInt(data, configStart);
        boolean isDefault = isDefaultConfig(data, configStart, configSize);

        // Entry-offset array immediately follows the header (= typeStart + headerSize)
        int offsetsStart    = typeStart + headerSize;
        int entriesDataStart= typeStart + entriesStart;

        boolean sparse   = (typeFlags & TYPE_FLAG_SPARSE)   != 0;
        boolean offset16 = (typeFlags & TYPE_FLAG_OFFSET16) != 0;

        if (sparse) {
            parseSparse(data, packageId, typeId, entryCount,
                        offsetsStart, entriesDataStart, isDefault);
        } else if (offset16) {
            parseOffset16(data, packageId, typeId, entryCount,
                          offsetsStart, entriesDataStart, isDefault);
        } else {
            parseStandard(data, packageId, typeId, entryCount,
                          offsetsStart, entriesDataStart, isDefault);
        }
    }

    // Standard format: offset array is uint32[entryCount]; NO_ENTRY = 0xFFFFFFFF
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

    // FLAG_OFFSET16: offset array is uint16[entryCount]; real_offset = value * 4; NO_ENTRY = 0xFFFF
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

    // FLAG_SPARSE: offset array is uint32[sparseCount]; each = (offset16 << 16) | idx16
    //   idx    = packed & 0xFFFF
    //   offset = ((packed >> 16) & 0xFFFF) * 4
    // sparseCount == entryCount in the header (actual non-empty entry count).
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
    // Entry parsing  (ResTable_entry + Res_value, or compact variant)
    // -------------------------------------------------------------------------

    private void putEntry(byte[] data, int entryPos, int resourceId, boolean isDefault) {
        if (entryPos + 4 > data.length) return;

        int entrySize  = FileUtils.readUShort(data, entryPos);
        int entryFlags = FileUtils.readUShort(data, entryPos + 2);

        if ((entryFlags & ENTRY_FLAG_COMPLEX) != 0) return; // map (bag) entry – skip

        String value;
        if ((entryFlags & ENTRY_FLAG_COMPACT) != 0) {
            // Compact format (8 bytes total, no separate Res_value):
            //   [0-1]  key index (uint16)
            //   [2-3]  flags: high byte = dataType, low byte = entry flags
            //   [4-7]  data
            if (entryPos + 8 > data.length) return;
            int dataType = (entryFlags >> 8) & 0xFF;
            int valData  = FileUtils.readInt(data, entryPos + 4);
            value = resolveValue(dataType, valData);
        } else {
            // Standard format: ResTable_entry header (entrySize bytes) + Res_value (8 bytes)
            //   Res_value layout: size(2) res0(1) dataType(1) data(4)
            if (entryPos + entrySize + 8 > data.length) return;
            int valBase  = entryPos + entrySize;
            int dataType = data[valBase + 3] & 0xFF;
            int valData  = FileUtils.readInt(data, valBase + 4);
            value = resolveValue(dataType, valData);
        }

        if (value == null) return;
        if (isDefault || !resourceMap.containsKey(resourceId)) {
            resourceMap.put(resourceId, value);
        }
    }

    private String resolveValue(int dataType, int data) {
        switch (dataType) {
            case TYPE_STRING:
                return getFromPool(globalStringPool, data);
            case TYPE_INT_DEC:
                return String.valueOf(data);
            case TYPE_REFERENCE:
                return "@0x" + Integer.toHexString(data);
            default:
                return null;
        }
    }

    private int buildId(int packageId, int typeId, int entryIndex) {
        return (packageId << 24) | (typeId << 16) | entryIndex;
    }

    // -------------------------------------------------------------------------
    // String pool (ResStringPool_header)
    // Layout:
    //   [0-1]   chunk type (0x0001)
    //   [2-3]   headerSize
    //   [4-7]   chunk size
    //   [8-11]  stringCount
    //   [12-15] styleCount
    //   [16-19] flags  (UTF8_FLAG = 0x100)
    //   [20-23] stringsStart  (offset from chunk start)
    //   [24-27] stylesStart
    //   [28+]   string offset array (uint32[stringCount])
    //   [stringsStart+] string data
    // -------------------------------------------------------------------------

    private String[] parseStringPool(byte[] data, int offset) {
        int stringCount  = FileUtils.readInt(data, offset + 8);
        int flags        = FileUtils.readInt(data, offset + 16);
        int stringsStart = FileUtils.readInt(data, offset + 20);
        boolean isUtf8   = (flags & 0x0100) != 0;

        String[] pool = new String[stringCount];
        for (int i = 0; i < stringCount; i++) {
            int strOff = offset + stringsStart + FileUtils.readInt(data, offset + 28 + i * 4);
            if (strOff < 0 || strOff >= data.length) {
                pool[i] = "";
                continue;
            }
            pool[i] = isUtf8 ? readUtf8String(data, strOff) : readUtf16String(data, strOff);
        }
        return pool;
    }

    // UTF-8 string: [charLen(variable)] [byteLen(variable)] [utf8 bytes]
    // Variable-length encoding: if high bit set, it's a two-byte length.
    private String readUtf8String(byte[] data, int offset) {
        try {
            int pos = offset;
            // Skip character count (not needed for decoding)
            int charLen = data[pos++] & 0xFF;
            if ((charLen & 0x80) != 0) pos++; // two-byte char length
            // Read byte length
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

    // UTF-16LE string: [charLen(uint16 or uint32 if high bit set)] [utf16le bytes]
    private String readUtf16String(byte[] data, int offset) {
        try {
            int charLen = FileUtils.readUShort(data, offset);
            int strPos  = offset + 2;
            if ((charLen & 0x8000) != 0) {
                charLen = ((charLen & 0x7FFF) << 16) | FileUtils.readUShort(data, strPos);
                strPos += 2;
            }
            int byteLen = Math.min(charLen * 2, data.length - strPos);
            return new String(data, strPos, byteLen, "UTF-16LE");
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isDefaultConfig(byte[] data, int configStart, int configSize) {
        // A default config has all bytes (after the size field) equal to zero
        for (int i = 4; i < configSize && i < 64; i++) {
            int pos = configStart + i;
            if (pos >= data.length) break;
            if (data[pos] != 0) return false;
        }
        return true;
    }

    private String getFromPool(String[] pool, int index) {
        if (pool == null || index < 0 || index >= pool.length) return null;
        return pool[index];
    }
}
