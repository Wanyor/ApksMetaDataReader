package com.wanyor.android.app;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ApkParser2 {

    public ApkMetaInfo parse(String apkPath) {
        ApkMetaInfo info = new ApkMetaInfo();
        ZipFile zf = null;
        try {
            zf = new ZipFile(apkPath);
            byte[] manifestData = readEntry(zf, "AndroidManifest.xml");
            if (manifestData == null) return info;

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

    public ApkMetaInfo parseFromBytes(byte[] apkData) {
        try {
            File tempFile = File.createTempFile("apk_parse2_", ".apk");
            tempFile.deleteOnExit();
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(apkData);
            }
            ApkMetaInfo result = parse(tempFile.getAbsolutePath());
            tempFile.delete();
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return new ApkMetaInfo();
        }
    }

    private void extractMetadata(Map<String, List<BinaryXmlParser.XmlAttribute>> elements, ApkMetaInfo info) {
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

        List<BinaryXmlParser.XmlAttribute> appAttrs = elements.get("application");
        if (appAttrs != null) {
            for (BinaryXmlParser.XmlAttribute attr : appAttrs) {
                if ("label".equals(attr.getName()) && info.getAppName() == null) {
                    info.setAppName(attr.getResolvedValue());
                }
            }
        }

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

    private byte[] readEntry(ZipFile zf, String name) {
        try {
            ZipEntry entry = zf.getEntry(name);
            if (entry == null) return null;
            InputStream is = zf.getInputStream(entry);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            is.close();
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
