package com.wanyor.android.app;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 标准 APK 解析器：同时解析 AndroidManifest.xml 和 resources.arsc，
 * 以获得真实的应用名称（通过资源引用查表）。
 */
public class ApkParser {

    private static final int TYPE_REFERENCE = 0x01;

    private static final Set<String> MANIFEST_TARGETS;
    static {
        Set<String> s = new HashSet<>();
        s.add("manifest");
        s.add("uses-sdk");
        s.add("application");
        MANIFEST_TARGETS = Collections.unmodifiableSet(s);
    }

    /**
     * 解析 APK 文件路径。
     * APK 本质是 ZIP：先读 AndroidManifest.xml（二进制 XML），
     * 再读 resources.arsc 构建资源表，最后提取元数据。
     */
    public ApkMetaInfo parse(String apkPath) {
        ApkMetaInfo info = new ApkMetaInfo();
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(apkPath);
            byte[] manifestData = readZipEntry(zipFile, "AndroidManifest.xml");
            if (manifestData == null) return info;

            // 尝试读取资源表；部分极简 APK 可能没有 resources.arsc
            Map<Integer, String> resourceMap = null;
            byte[] arscData = readZipEntry(zipFile, "resources.arsc");
            if (arscData != null) {
                ResourceTableParser arscParser = new ResourceTableParser();
                resourceMap = arscParser.parse(arscData);
            }

            BinaryXmlParser xmlParser = new BinaryXmlParser();
            Map<String, List<BinaryXmlParser.XmlAttribute>> elements = xmlParser.parse(manifestData, resourceMap, MANIFEST_TARGETS);
            extractMetadata(elements, resourceMap, info);
        } catch (Exception e) {
            // 静默失败，调用方视返回的 info 字段是否为 null 决定是否回退到 ApkParser2
        } finally {
            if (zipFile != null) {
                try { zipFile.close(); } catch (Exception ignored) {}
            }
        }
        return info;
    }

    /**
     * 从内存字节解析 APK，直接用 ZipInputStream 扫描，避免写临时文件。
     */
    public ApkMetaInfo parseFromBytes(byte[] apkData) {
        try {
            byte[] manifestData = null;
            byte[] arscData = null;
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(apkData))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if ("AndroidManifest.xml".equals(name)) {
                        manifestData = FileUtils.readFromZipInputStream(zis, entry);
                    } else if ("resources.arsc".equals(name)) {
                        arscData = FileUtils.readFromZipInputStream(zis, entry);
                    }
                    if (manifestData != null && arscData != null) break;
                }
            }
            if (manifestData == null) return new ApkMetaInfo();
            Map<Integer, String> resourceMap = null;
            if (arscData != null) {
                ResourceTableParser arscParser = new ResourceTableParser();
                resourceMap = arscParser.parse(arscData);
            }
            BinaryXmlParser xmlParser = new BinaryXmlParser();
            Map<String, List<BinaryXmlParser.XmlAttribute>> elements = xmlParser.parse(manifestData, resourceMap, MANIFEST_TARGETS);
            ApkMetaInfo info = new ApkMetaInfo();
            extractMetadata(elements, resourceMap, info);
            return info;
        } catch (Exception e) {
            return new ApkMetaInfo();
        }
    }

    /**
     * 从解析出的 XML 元素 + 资源表中填充 ApkMetaInfo 各字段。
     * 查找顺序：<manifest> → <uses-sdk> → <application>。
     */
    private void extractMetadata(Map<String, List<BinaryXmlParser.XmlAttribute>> elements,
                                  Map<Integer, String> resourceMap, ApkMetaInfo info) {

        // <manifest package="..." android:versionCode="..." android:versionName="...">
        List<BinaryXmlParser.XmlAttribute> manifestAttrs = elements.get("manifest");
        if (manifestAttrs != null) {
            for (BinaryXmlParser.XmlAttribute attr : manifestAttrs) {
                String name = attr.getName();
                if (name == null) continue;
                switch (name) {
                    case "package":
                        info.setPackageName(attr.getResolvedValue());
                        break;
                    case "versionCode":
                        String vc = attr.getResolvedValue();
                        if (vc != null) {
                            try {
                                if (vc.startsWith("0x")) {
                                    info.setVersionCode(Long.parseLong(vc.substring(2), 16));
                                } else {
                                    info.setVersionCode(Long.parseLong(vc));
                                }
                            } catch (NumberFormatException e) {
                                info.setVersionCode(null);
                            }
                        }
                        break;
                    case "versionName":
                        info.setVersionName(resolveValue(attr, resourceMap));
                        break;
                    case "compileSdkVersion":
                        info.setCompileSdkVersion(attr.getResolvedValue());
                        break;
                    case "compileSdkVersionCodename":
                        break; // 仅记录数字版本，codename 忽略
                }
            }
        }

        // <uses-sdk android:minSdkVersion="..." android:targetSdkVersion="...">
        List<BinaryXmlParser.XmlAttribute> usesSdkAttrs = elements.get("uses-sdk");
        if (usesSdkAttrs != null) {
            for (BinaryXmlParser.XmlAttribute attr : usesSdkAttrs) {
                String name = attr.getName();
                if (name == null) continue;
                switch (name) {
                    case "minSdkVersion":
                        info.setMinSdkVersion(attr.getResolvedValue());
                        break;
                    case "targetSdkVersion":
                        info.setTargetSdkVersion(attr.getResolvedValue());
                        break;
                }
            }
        } else {
            // 少数 APK 的字符串池使用带 NUL 结尾的名称，做兼容处理
            List<BinaryXmlParser.XmlAttribute> usesSdkAttrs2 = elements.get("uses-sdk\0");
            if (usesSdkAttrs2 != null) {
                for (BinaryXmlParser.XmlAttribute attr : usesSdkAttrs2) {
                    String name = attr.getName();
                    if (name == null) continue;
                    String cleanName = name.replace("\0", "");
                    switch (cleanName) {
                        case "minSdkVersion":
                            info.setMinSdkVersion(attr.getResolvedValue());
                            break;
                        case "targetSdkVersion":
                            info.setTargetSdkVersion(attr.getResolvedValue());
                            break;
                    }
                }
            }
        }

        // <application android:label="..." ...>
        List<BinaryXmlParser.XmlAttribute> appAttrs = elements.get("application");
        if (appAttrs != null) {
            for (BinaryXmlParser.XmlAttribute attr : appAttrs) {
                String name = attr.getName();
                if (name == null) continue;
                String cleanName = name.replace("\0", "");
                switch (cleanName) {
                    case "label":
                        info.setAppName(resolveValue(attr, resourceMap));
                        break;
                    case "versionCode":
                        // 备用：部分 APK 把 versionCode 放在 application 而非 manifest
                        if (info.getVersionCode() == null) {
                            String vc = attr.getResolvedValue();
                            if (vc != null) {
                                try {
                                    if (vc.startsWith("0x")) {
                                        info.setVersionCode(Long.parseLong(vc.substring(2), 16));
                                    } else {
                                        info.setVersionCode(Long.parseLong(vc));
                                    }
                                } catch (NumberFormatException e) {
                                    info.setVersionCode(null);
                                }
                            }
                        }
                        break;
                    case "versionName":
                        if (info.getVersionName() == null) {
                            info.setVersionName(resolveValue(attr, resourceMap));
                        }
                        break;
                }
            }
        }

        // 最后兜底：部分 APK 把 label 写在 <manifest> 而非 <application>
        if (info.getAppName() == null && manifestAttrs != null) {
            for (BinaryXmlParser.XmlAttribute attr : manifestAttrs) {
                if ("label".equals(attr.getName())) {
                    info.setAppName(resolveValue(attr, resourceMap));
                    break;
                }
            }
        }
    }

    /**
     * 将属性值解析为可读字符串，支持资源引用跳转。
     * 优先使用已解析值；若为资源引用（@0x...）则查资源表；支持一级间接引用。
     */
    private String resolveValue(BinaryXmlParser.XmlAttribute attr, Map<Integer, String> resourceMap) {
        String resolved = attr.getResolvedValue();
        // 已有可读值（非引用占位符）直接返回
        if (resolved != null && !resolved.isEmpty() && !resolved.startsWith("@0x")) {
            return resolved;
        }
        // 类型为资源引用时，查资源表获取真实字符串
        if (attr.getValueType() == TYPE_REFERENCE && resourceMap != null) {
            int refId = attr.getValueData();
            String resourceValue = resourceMap.get(refId);
            if (resourceValue != null) {
                // 资源表中的值也可能是另一个引用（一级间接），继续跳转一次
                if (resourceValue.startsWith("@0x")) {
                    try {
                        int nestedRef = Integer.parseInt(resourceValue.substring(3), 16);
                        String nestedValue = resourceMap.get(nestedRef);
                        if (nestedValue != null) return nestedValue;
                    } catch (NumberFormatException ignored) {}
                }
                return resourceValue;
            }
        }
        if (resolved != null) return resolved;
        return attr.getRawValue();
    }

    /** 按名称读取 ZIP 条目字节，委托给 FileUtils。 */
    static byte[] readZipEntry(ZipFile zipFile, String entryName) {
        return FileUtils.readZipEntry(zipFile, entryName);
    }

}
