package com.wanyor.android.app;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
     * 从内存字节解析 APK。
     * 将字节写入临时文件后复用 parse()，临时文件在 finally 中保证删除。
     */
    public ApkMetaInfo parseFromBytes(byte[] apkData) {
        File tempFile = null;
        try {
            tempFile = File.createTempFile("apk_parse2_", ".apk");
            tempFile.deleteOnExit();
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(apkData);
            }
            return parse(tempFile.getAbsolutePath());
        } catch (Exception e) {
            return new ApkMetaInfo();
        } finally {
            if (tempFile != null) tempFile.delete();
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
