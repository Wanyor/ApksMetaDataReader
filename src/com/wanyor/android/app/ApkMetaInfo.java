package com.wanyor.android.app;

/**
 * 保存从 Android 安装包中提取的元数据。
 * 所有字段均可为 null，表示该字段在安装包中未找到。
 */
public class ApkMetaInfo {

    private String appName;          // 应用名称（来自 application.label）
    private String minSdkVersion;    // 最低支持的 SDK 版本
    private String targetSdkVersion; // 目标 SDK 版本
    private String compileSdkVersion;// 编译时使用的 SDK 版本
    private String versionName;      // 版本名称字符串，如 "1.2.3"
    private Long versionCode;        // 版本号整数，用 Long 以兼容超过 2^31 的值
    private String packageName;      // 应用包名，如 "com.example.app"
    private long fileSize;           // 安装包文件大小（字节）
    private String fileMd5;          // 安装包文件的 MD5 十六进制字符串

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getMinSdkVersion() {
        return minSdkVersion;
    }

    public void setMinSdkVersion(String minSdkVersion) {
        this.minSdkVersion = minSdkVersion;
    }

    public String getTargetSdkVersion() {
        return targetSdkVersion;
    }

    public void setTargetSdkVersion(String targetSdkVersion) {
        this.targetSdkVersion = targetSdkVersion;
    }

    public String getCompileSdkVersion() {
        return compileSdkVersion;
    }

    public void setCompileSdkVersion(String compileSdkVersion) {
        this.compileSdkVersion = compileSdkVersion;
    }

    public String getVersionName() {
        return versionName;
    }

    public void setVersionName(String versionName) {
        this.versionName = versionName;
    }

    public Long getVersionCode() {
        return versionCode;
    }

    public void setVersionCode(Long versionCode) {
        this.versionCode = versionCode;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFileMd5() {
        return fileMd5;
    }

    public void setFileMd5(String fileMd5) {
        this.fileMd5 = fileMd5;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ApkMetaInfo{").append('\n');
        sb.append("  appName          = ").append(appName).append('\n');
        sb.append("  packageName      = ").append(packageName).append('\n');
        sb.append("  versionCode      = ").append(versionCode).append('\n');
        sb.append("  versionName      = ").append(versionName).append('\n');
        sb.append("  minSdkVersion    = ").append(minSdkVersion).append('\n');
        sb.append("  targetSdkVersion = ").append(targetSdkVersion).append('\n');
        sb.append("  compileSdkVersion= ").append(compileSdkVersion).append('\n');
        sb.append("  fileSize         = ").append(fileSize).append('\n');
        sb.append("  fileMd5          = ").append(fileMd5).append('\n');
        sb.append('}');
        return sb.toString();
    }

    /** 将元数据序列化为 JSON 对象字符串，包含 file 字段。 */
    public String toJson(String filePath) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"file\": ").append(jsonEscape(filePath)).append(",\n");
        sb.append("  \"appName\": ").append(jsonEscape(appName)).append(",\n");
        sb.append("  \"packageName\": ").append(jsonEscape(packageName)).append(",\n");
        sb.append("  \"versionCode\": ").append(versionCode == null ? "null" : String.valueOf(versionCode)).append(",\n");
        sb.append("  \"versionName\": ").append(jsonEscape(versionName)).append(",\n");
        sb.append("  \"minSdkVersion\": ").append(jsonEscape(minSdkVersion)).append(",\n");
        sb.append("  \"targetSdkVersion\": ").append(jsonEscape(targetSdkVersion)).append(",\n");
        sb.append("  \"compileSdkVersion\": ").append(jsonEscape(compileSdkVersion)).append(",\n");
        sb.append("  \"fileSize\": ").append(fileSize).append(",\n");
        sb.append("  \"fileMd5\": ").append(jsonEscape(fileMd5)).append("\n");
        sb.append("}");
        return sb.toString();
    }

    /** 生成表示解析错误的 JSON 对象，包含 file 和 error 字段。 */
    static String errorJson(String filePath, String message) {
        return "{\n" +
               "  \"file\": " + jsonEscape(filePath) + ",\n" +
               "  \"error\": " + jsonEscape(message) + "\n" +
               "}";
    }

    /** JSON 字符串转义：处理引号、反斜杠、控制字符。 */
    static String jsonEscape(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if      (c == '"')  sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20)  sb.append(String.format("\\u%04x", (int) c));
            else                sb.append(c);
        }
        return sb.append('"').toString();
    }
}
