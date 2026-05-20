package com.wanyor.android.app;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * ApkParser 的备用解析器：仅解析 AndroidManifest.xml，不读取 resources.arsc。
 * 当 ApkParser 无法提取 packageName 或 appName 时作为回退使用。
 * appName 在不查资源表的情况下可能得到资源引用（如 @0x7f040001）而非真实字符串。
 */
public class ApkParser2 {

    /** 解析 APK 文件路径，返回提取到的元数据。 */
    public ApkMetaInfo parse(String apkPath) {
        ApkMetaInfo info = new ApkMetaInfo();
        ZipFile zf = null;
        try {
            zf = new ZipFile(apkPath);
            byte[] manifestData = readEntry(zf, "AndroidManifest.xml");
            if (manifestData == null) return info;
            // 仅用 BinaryXmlParser 解析清单，不提供资源表（appName 可能为引用字符串）
            BinaryXmlParser xmlParser = new BinaryXmlParser();
            Map<String, List<BinaryXmlParser.XmlAttribute>> elements = xmlParser.parse(manifestData);
            extractMetadata(elements, info);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (zf != null) try { zf.close(); } catch (Exception ignored) {}
        }
        return info;
    }

    /**
     * 从内存字节解析 APK，直接用 ZipInputStream 扫描，避免写临时文件。
     */
    public ApkMetaInfo parseFromBytes(byte[] apkData) {
        try {
            byte[] manifestData = null;
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(apkData))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if ("AndroidManifest.xml".equals(entry.getName())) {
                        manifestData = FileUtils.readFromZipInputStream(zis, entry);
                        break;
                    }
                }
            }
            if (manifestData == null) return new ApkMetaInfo();
            BinaryXmlParser xmlParser = new BinaryXmlParser();
            Map<String, List<BinaryXmlParser.XmlAttribute>> elements = xmlParser.parse(manifestData);
            ApkMetaInfo info = new ApkMetaInfo();
            extractMetadata(elements, info);
            return info;
        } catch (Exception e) {
            return new ApkMetaInfo();
        }
    }

    /** 从解析出的 XML 元素中提取 packageName、versionCode、versionName、SDK 版本等字段。 */
    private void extractMetadata(Map<String, List<BinaryXmlParser.XmlAttribute>> elements, ApkMetaInfo info) {
        // <manifest> 元素携带包名和版本信息
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
                                info.setVersionCode(vc.startsWith("0x")
                                        ? Long.parseLong(vc.substring(2), 16)
                                        : Long.parseLong(vc));
                            } catch (NumberFormatException ignored) {}
                        }
                        break;
                    case "versionName":
                        info.setVersionName(attr.getResolvedValue());
                        break;
                }
            }
        }

        // <application> 元素携带 label（应用名称）
        List<BinaryXmlParser.XmlAttribute> appAttrs = elements.get("application");
        if (appAttrs != null) {
            for (BinaryXmlParser.XmlAttribute attr : appAttrs) {
                if ("label".equals(attr.getName()) && info.getAppName() == null) {
                    info.setAppName(attr.getResolvedValue());
                }
            }
        }

        // <uses-sdk> 元素携带 SDK 版本
        List<BinaryXmlParser.XmlAttribute> sdkAttrs = elements.get("uses-sdk");
        if (sdkAttrs != null) {
            for (BinaryXmlParser.XmlAttribute attr : sdkAttrs) {
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
        }
    }

    /** 从 ZIP 中读取指定名称条目的字节，委托给 FileUtils。 */
    private byte[] readEntry(ZipFile zf, String name) {
        return FileUtils.readZipEntry(zf, name);
    }
}
