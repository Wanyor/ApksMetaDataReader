package com.wanyor.android.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class AaptTool {

    public static ApkMetaInfo parseWithAapt(String apkPath) {
        ApkMetaInfo info = new ApkMetaInfo();
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(apkPath);
            byte[] manifestData = readZipEntry(zipFile, "AndroidManifest.xml");
            if (manifestData == null) return info;

            AxmlPrinter printer = new AxmlPrinter();
            printer.print(manifestData);
            String xml = printer.getXmlText();
            parseXmlText(xml, info);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (zipFile != null) {
                try { zipFile.close(); } catch (IOException ignored) {}
            }
        }
        return info;
    }

    private static void parseXmlText(String xml, ApkMetaInfo info) {
        String[] lines = xml.split("\n");
        for (String line : lines) {
            if (line.contains("package=")) {
                info.setPackageName(extractQuoted(line, "package"));
            } else if (line.contains("versionCode=")) {
                String vc = extractQuoted(line, "versionCode");
                if (vc != null) {
                    try { info.setVersionCode(Long.parseLong(vc)); } catch (Exception e) {}
                }
            } else if (line.contains("versionName=")) {
                info.setVersionName(extractQuoted(line, "versionName"));
            } else if (line.contains("minSdkVersion=")) {
                info.setMinSdkVersion(extractQuoted(line, "minSdkVersion"));
            } else if (line.contains("targetSdkVersion=")) {
                info.setTargetSdkVersion(extractQuoted(line, "targetSdkVersion"));
            } else if (line.contains("compileSdkVersion=")) {
                info.setCompileSdkVersion(extractQuoted(line, "compileSdkVersion"));
            } else if (line.contains("label=")) {
                String label = extractQuoted(line, "label");
                if (label != null && !label.startsWith("@")) {
                    info.setAppName(label);
                }
            }
        }
    }

    private static String extractQuoted(String line, String attr) {
        int idx = line.indexOf(attr + "=\"");
        if (idx < 0) return null;
        int start = idx + attr.length() + 2;
        int end = line.indexOf('"', start);
        if (end < 0) return null;
        return line.substring(start, end);
    }

    private static byte[] readZipEntry(ZipFile zipFile, String name) {
        try {
            ZipEntry entry = zipFile.getEntry(name);
            if (entry == null) return null;
            InputStream is = zipFile.getInputStream(entry);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
            is.close();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    static class AxmlPrinter {
        private static final int CHUNK_AXML_FILE = 0x00080003;
        private static final int CHUNK_RESOURCEIDS = 0x00080180;
        private static final int CHUNK_START_NAMESPACE = 0x00000100;
        private static final int CHUNK_END_NAMESPACE = 0x00000101;
        private static final int CHUNK_START_TAG = 0x00000102;
        private static final int CHUNK_END_TAG = 0x00000103;
        private static final int CHUNK_TEXT = 0x00000104;
        private static final int CHUNK_STRING_POOL = 0x000001;
        private static final int TYPE_DIMENSION_UNITS = 0x05;
        private static final int TYPE_FRACTION_UNITS = 0x06;

        private StringBuilder out = new StringBuilder();
        private int[] resourceIds;
        private String[] strings;
        private String[] nameSpaces;
        private int lineNumber = 0;

        void print(byte[] data) {
            if (data == null || data.length < 8) return;
            parse(data, 0);
        }

        String getXmlText() {
            return out.toString();
        }

        private void parse(byte[] data, int start) {
            int offset = start;

            if (offset + 28 > data.length) return;
            int stringCount = FileUtils.readInt(data, offset + 8);
            int styleCount = FileUtils.readInt(data, offset + 12);
            int flags = FileUtils.readInt(data, offset + 16);
            int stringsStart = FileUtils.readInt(data, offset + 20);
            int stylesStart = FileUtils.readInt(data, offset + 24);

            if (stringCount <= 0) return;

            boolean isUtf8 = (flags & 0x100) != 0;
            int stringsPoolStart = start + 28 + stringCount * 4;

            strings = new String[stringCount];
            for (int i = 0; i < stringCount; i++) {
                int strOffset = stringsPoolStart + FileUtils.readInt(data, start + 28 + i * 4);
                if (strOffset >= 0 && strOffset < data.length) {
                    strings[i] = readString(data, strOffset, isUtf8);
                } else {
                    strings[i] = "";
                }
            }

            offset = start + FileUtils.readUShort(data, 6);

            while (offset < data.length - 8) {
                int chunkType = FileUtils.readUShort(data, offset);
                int chunkSize = FileUtils.readInt(data, offset + 4);

                if (chunkSize <= 0 || offset + chunkSize > data.length) break;

                switch (chunkType) {
                    case CHUNK_RESOURCEIDS:
                        int count = (chunkSize - 8) / 4;
                        resourceIds = new int[count];
                        for (int i = 0; i < count; i++) {
                            resourceIds[i] = FileUtils.readInt(data, offset + 8 + i * 4);
                        }
                        break;
                    case CHUNK_START_NAMESPACE:
                        out.append(" namespaces:\n");
                        offset += chunkSize;
                        continue;
                    case CHUNK_END_NAMESPACE:
                        offset += chunkSize;
                        continue;
                    case CHUNK_START_TAG:
                        printStartTag(data, offset);
                        break;
                    case CHUNK_END_TAG:
                        offset += chunkSize;
                        continue;
                    case CHUNK_TEXT:
                        String text = getString(FileUtils.readInt(data, offset + 8));
                        out.append(text);
                        break;
                }
                offset += chunkSize;
            }
        }

        private void printStartTag(byte[] data, int offset) {
            int nameIndex = FileUtils.readInt(data, offset + 20);
            String tagName = getString(nameIndex);
            if (tagName == null) tagName = "unknown";

            int attrCount = FileUtils.readUShort(data, offset + 26);
            int attrStart = FileUtils.readUShort(data, offset + 28);
            int attrSize = FileUtils.readUShort(data, offset + 30);

            out.append("<").append(tagName);

            int attrsOffset = offset + 32;
            for (int i = 0; i < attrCount; i++) {
                int aOff = attrsOffset + i * attrSize;
                if (aOff + attrSize > data.length) break;

                int nsIdx = FileUtils.readInt(data, aOff);
                int nameIdx = FileUtils.readInt(data, aOff + 4);
                int valIdx = FileUtils.readInt(data, aOff + 8);
                int valType = data[aOff + 15] & 0xFF;
                int valData = FileUtils.readInt(data, aOff + 16);

                String attrName = getString(nameIdx);
                String attrNs = getString(nsIdx);
                String attrValue = formatValue(valType, valData, valIdx);

                if (attrName != null) {
                    out.append(" ").append(attrName).append("=\"").append(attrValue).append("\"");
                }
            }

            out.append(">\n");
        }

        private String formatValue(int type, int data, int strIdx) {
            if (strIdx != -1) {
                String s = getString(strIdx);
                if (s != null && !s.isEmpty()) return s;
            }

            switch (type) {
                case 0x03: return getString(data);
                case 0x10: return String.valueOf(data);
                case 0x11: return "0x" + Integer.toHexString(data);
                case 0x12: return data != 0 ? "true" : "false";
                case TYPE_DIMENSION_UNITS:
                    return Float.toString(Float.intBitsToFloat(data)) + getDimensionUnit(data);
                case TYPE_FRACTION_UNITS:
                    return Float.toString(Float.intBitsToFloat(data)) + getFractionUnit(data);
                default: return "#" + Integer.toHexString(data);
            }
        }

        private String getDimensionUnit(int value) {
            switch (value & 0xFF) {
                case 0: return "px";
                case 1: return "dip";
                case 2: return "sp";
                case 3: return "pt";
                case 4: return "in";
                case 5: return "mm";
                default: return "";
            }
        }

        private String getFractionUnit(int value) {
            switch (value & 0xFF) {
                case 0: return "%";
                case 1: return "%p";
                default: return "";
            }
        }

        private String getString(int index) {
            if (strings == null || index < 0 || index >= strings.length) return null;
            return strings[index];
        }

        private String readString(byte[] data, int offset, boolean isUtf8) {
            try {
                int length;
                if (offset + 1 >= data.length) return "";
                if (isUtf8) {
                    int byteLen = data[offset] & 0xFF;
                    int start = offset + 1;
                    int end = start + byteLen;
                    if (end > data.length) end = data.length;
                    if (byteLen > 0 && data[start] == 0) {
                        start++;
                        end = start + (data[offset] & 0x7F);
                    }
                    return new String(data, start, end - start, "UTF-8");
                } else {
                    length = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
                    int start = offset + 2;
                    int end = start + length * 2;
                    if (end > data.length) end = data.length;
                    return new String(data, start, end - start, "UTF-16LE");
                }
            } catch (Exception e) {
                return "";
            }
        }
    }
}