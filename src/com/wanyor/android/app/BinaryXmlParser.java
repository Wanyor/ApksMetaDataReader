package com.wanyor.android.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BinaryXmlParser {

    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_XML_START_NAMESPACE_TYPE = 0x0100;
    private static final int RES_XML_END_NAMESPACE_TYPE = 0x0101;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
    private static final int RES_XML_END_ELEMENT_TYPE = 0x0103;
    private static final int RES_XML_CDATA_TYPE = 0x0104;
    private static final int RES_XML_RESOURCE_MAP_TYPE = 0x0180;

    private static final int TYPE_NULL = 0x00;
    private static final int TYPE_REFERENCE = 0x01;
    private static final int TYPE_ATTRIBUTE = 0x02;
    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_FLOAT = 0x04;
    private static final int TYPE_DIMENSION = 0x05;
    private static final int TYPE_FRACTION = 0x06;
    private static final int TYPE_INT_DEC = 0x10;
    private static final int TYPE_INT_HEX = 0x11;
    private static final int TYPE_INT_BOOLEAN = 0x12;
    private static final int TYPE_INT_COLOR_ARGB8 = 0x1c;
    private static final int TYPE_INT_COLOR_RGB8 = 0x1d;
    private static final int TYPE_INT_COLOR_ARGB4 = 0x1e;
    private static final int TYPE_INT_COLOR_RGB4 = 0x1f;

    private String[] stringPool;
    private int[] resourceIds;
    private Map<Integer, String> externalResourceMap;

    public Map<String, List<XmlAttribute>> parse(byte[] data) {
        return parse(data, null);
    }

    public Map<String, List<XmlAttribute>> parse(byte[] data, Map<Integer, String> resourceMap) {
        this.externalResourceMap = resourceMap;
        Map<String, List<XmlAttribute>> elements = new HashMap<>();
        if (data == null || data.length < 8) return elements;

        int offset = 0;
        int type = FileUtils.readUShort(data, offset);
        int headerSize = FileUtils.readUShort(data, offset + 2);
        int size = FileUtils.readInt(data, offset + 4);

        if (type != RES_XML_TYPE) return elements;
        offset += headerSize;

        if (offset >= data.length) return elements;
        type = FileUtils.readUShort(data, offset);
        if (type == RES_STRING_POOL_TYPE) {
            parseStringPool(data, offset);
            offset += FileUtils.readInt(data, offset + 4);
        }

        while (offset < data.length - 8) {
            type = FileUtils.readUShort(data, offset);
            int chunkSize = FileUtils.readInt(data, offset + 4);
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

    private void parseStringPool(byte[] data, int offset) {
        int type = FileUtils.readUShort(data, offset);
        int headerSize = FileUtils.readUShort(data, offset + 2);
        int chunkSize = FileUtils.readInt(data, offset + 4);
        if (type != RES_STRING_POOL_TYPE) return;

        int stringCount = FileUtils.readInt(data, offset + 8);
        if (stringCount <= 0 || stringCount > 65536) return;
        int flags = FileUtils.readInt(data, offset + 16);
        int stringsStart = FileUtils.readInt(data, offset + 20);

        boolean isUtf8 = (flags & 0x0100) != 0;

        int[] offsetsArray = new int[stringCount];
        for (int i = 0; i < stringCount; i++) {
            int offsetPos = offset + 28 + i * 4;
            if (offsetPos + 4 > data.length) {
                stringCount = i;
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

    private String readUtf8String(byte[] data, int offset) {
        try {
            int charLen = data[offset] & 0xFF;
            int bytePos = offset + 1;
            if ((charLen & 0x80) != 0) {
                charLen = ((charLen & 0x7F) << 8) | (data[bytePos] & 0xFF);
                bytePos++;
            }
            int byteLen = data[bytePos] & 0xFF;
            bytePos++;
            if ((byteLen & 0x80) != 0) {
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

    private String readUtf16String(byte[] data, int offset) {
        try {
            int charLen = FileUtils.readUShort(data, offset);
            int strPos = offset + 2;
            if ((charLen & 0x8000) != 0) {
                charLen = ((charLen & 0x7FFF) << 16) | FileUtils.readUShort(data, strPos);
                strPos += 2;
            }
            int byteLen = (int) Math.min((long) charLen * 2, data.length - strPos);
            if (byteLen <= 0) return "";
            byte[] strBytes = new byte[byteLen];
            System.arraycopy(data, strPos, strBytes, 0, byteLen);
            return new String(strBytes, "UTF-16LE");
        } catch (Exception e) {
            return "";
        }
    }

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

    private void parseStartElement(byte[] data, int offset, Map<String, List<XmlAttribute>> elements) {
        int headerSize = FileUtils.readUShort(data, offset + 2);
        // ResXMLTree_node (16 bytes): header(8) + lineNumber(4) + comment(4)
        // ResXMLTree_attrExt starts at offset+16: ns(4) + name(4) + attrStart(2) + attrSize(2) + attrCount(2) ...
        int nameIndex = FileUtils.readInt(data, offset + 20); // offset+16 = ns URI, offset+20 = element name

        String elementName = getString(nameIndex);
        if (elementName == null) return;

        int attrStart = FileUtils.readUShort(data, offset + 24);
        int attrSize  = FileUtils.readUShort(data, offset + 26);
        int attrCount = FileUtils.readUShort(data, offset + 28);

        List<XmlAttribute> attrs = new ArrayList<>();
        // ResXMLTree_node is always 16 bytes (header+lineNumber+comment).
        // attrStart is the byte offset within ResXMLTree_attrExt to the first attribute.
        int attrOffset = offset + 16 + attrStart;
        for (int i = 0; i < attrCount; i++) {
            int aOff = attrOffset + i * attrSize;
            if (aOff + attrSize > data.length) break;

            int nsIdx = FileUtils.readInt(data, aOff);
            int nameIdx = FileUtils.readInt(data, aOff + 4);
            int rawValueIdx = FileUtils.readInt(data, aOff + 8);
            int valueType = data[aOff + 15] & 0xFF;
            int valueData = FileUtils.readInt(data, aOff + 16);

            String ns = getString(nsIdx);
            String attrName = getString(nameIdx);
            String rawValue = getString(rawValueIdx);
            String value = resolveAttributeValue(rawValue, valueType, valueData);

            XmlAttribute attr = new XmlAttribute();
            attr.namespace = ns;
            attr.name = attrName;
            attr.rawValue = rawValue;
            attr.valueType = valueType;
            attr.valueData = valueData;
            attr.resolvedValue = value;
            attrs.add(attr);
        }

        if (!elements.containsKey(elementName)) {
            elements.put(elementName, new ArrayList<>());
        }
        elements.get(elementName).addAll(attrs);
    }

    private String resolveAttributeValue(String rawValue, int valueType, int valueData) {
        if (rawValue != null && !rawValue.isEmpty()) {
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
                return getString(valueData);
            case TYPE_INT_DEC:
                return String.valueOf(valueData);
            case TYPE_INT_HEX:
                return "0x" + Integer.toHexString(valueData);
            case TYPE_INT_BOOLEAN:
                return valueData != 0 ? "true" : "false";
            case TYPE_REFERENCE:
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

    private String resolveResourceRef(int refId) {
        if (resourceIds != null) {
            for (int i = 0; i < resourceIds.length; i++) {
                if (resourceIds[i] == refId) {
                    return getString(i);
                }
            }
        }
        if (externalResourceMap != null) {
            String val = externalResourceMap.get(refId);
            if (val != null) return val;
        }
        return null;
    }

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

    String getString(int index) {
        if (stringPool == null || index < 0 || index >= stringPool.length) return null;
        return stringPool[index];
    }

    int getResourceId(int index) {
        if (resourceIds == null || index < 0 || index >= resourceIds.length) return 0;
        return resourceIds[index];
    }

    public static class XmlAttribute {
        String namespace;
        String name;
        String rawValue;
        int valueType;
        int valueData;
        String resolvedValue;

        public String getNamespace() { return namespace; }
        public String getName() { return name; }
        public String getRawValue() { return rawValue; }
        public int getValueType() { return valueType; }
        public int getValueData() { return valueData; }
        public String getResolvedValue() { return resolvedValue; }

        public boolean isAndroidNamespace() {
            return namespace != null && namespace.contains("android");
        }
    }
}