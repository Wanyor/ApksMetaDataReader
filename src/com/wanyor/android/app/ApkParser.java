package com.wanyor.android.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ApkParser {

    private static final int TYPE_REFERENCE = 0x01;

    public ApkMetaInfo parse(String apkPath) {
        ApkMetaInfo info = new ApkMetaInfo();
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(apkPath);
            byte[] manifestData = readZipEntry(zipFile, "AndroidManifest.xml");
            if (manifestData == null) return info;

            Map<Integer, String> resourceMap = null;
            byte[] arscData = readZipEntry(zipFile, "resources.arsc");
            if (arscData != null) {
                ResourceTableParser arscParser = new ResourceTableParser();
                resourceMap = arscParser.parse(arscData);
            }

            BinaryXmlParser xmlParser = new BinaryXmlParser();
            Map<String, List<BinaryXmlParser.XmlAttribute>> elements = xmlParser.parse(manifestData, resourceMap);
            extractMetadata(elements, resourceMap, info);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (zipFile != null) {
                try { zipFile.close(); } catch (Exception ignored) {}
            }
        }
        return info;
    }

    public ApkMetaInfo parseFromBytes(byte[] apkData) {
        java.io.File tempFile = null;
        try {
            tempFile = java.io.File.createTempFile("apk_parse_", ".apk");
            tempFile.deleteOnExit();
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                fos.write(apkData);
            }
            return parse(tempFile.getAbsolutePath());
        } catch (Exception e) {
            return new ApkMetaInfo();
        } finally {
            if (tempFile != null) tempFile.delete();
        }
    }

    private void extractMetadata(Map<String, List<BinaryXmlParser.XmlAttribute>> elements,
                                  Map<Integer, String> resourceMap, ApkMetaInfo info) {

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
                        break;
                }
            }
        }

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

        if (info.getAppName() == null && manifestAttrs != null) {
            for (BinaryXmlParser.XmlAttribute attr : manifestAttrs) {
                if ("label".equals(attr.getName())) {
                    info.setAppName(resolveValue(attr, resourceMap));
                    break;
                }
            }
        }
    }

    private String resolveValue(BinaryXmlParser.XmlAttribute attr, Map<Integer, String> resourceMap) {
        String resolved = attr.getResolvedValue();
        if (resolved != null && !resolved.isEmpty() && !resolved.startsWith("@0x")) {
            return resolved;
        }
        if (attr.getValueType() == TYPE_REFERENCE && resourceMap != null) {
            int refId = attr.getValueData();
            String resourceValue = resourceMap.get(refId);
            if (resourceValue != null) {
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

    static byte[] readZipEntry(ZipFile zipFile, String entryName) {
        return FileUtils.readZipEntry(zipFile, entryName);
    }

    static byte[] readFirstMatchingEntry(ZipFile zipFile, String namePattern) {
        try {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.contains(namePattern) && name.endsWith(".apk")) {
                    InputStream is = zipFile.getInputStream(entry);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        bos.write(buffer, 0, read);
                    }
                    is.close();
                    return bos.toByteArray();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}