package cn.tworice.huaweiddns.config;

/**
 * 应用配置：封装华为云认证所需的 AK / SK 与 region。
 */
public class AppConfig {

    private final String ak;
    private final String sk;
    private final String region;

    private final String ddnsZoneId;
    private final String ddnsRecordName;
    private final String ddnsRecordType;

    public AppConfig(String ak, String sk, String region,
                     String ddnsZoneId, String ddnsRecordName, String ddnsRecordType) {
        this.ak = ak;
        this.sk = sk;
        this.region = region;
        this.ddnsZoneId = ddnsZoneId;
        this.ddnsRecordName = ddnsRecordName;
        this.ddnsRecordType = ddnsRecordType;
    }

    public String getAk() {
        return ak;
    }

    public String getSk() {
        return sk;
    }

    public String getRegion() {
        return region;
    }

    public String getDdnsZoneId() {
        return ddnsZoneId;
    }

    public String getDdnsRecordName() {
        return ddnsRecordName;
    }

    public String getDdnsRecordType() {
        return ddnsRecordType;
    }

    /**
     * 校验必要参数是否已配置。
     */
    public void validate() {
        if (ak == null || ak.trim().isEmpty() || ak.startsWith("YOUR_")) {
            throw new IllegalStateException("AK 未正确配置，请在 config.properties 或环境变量 HUAWEI_CLOUD_AK 中设置。");
        }
        if (sk == null || sk.trim().isEmpty() || sk.startsWith("YOUR_")) {
            throw new IllegalStateException("SK 未正确配置，请在 config.properties 或环境变量 HUAWEI_CLOUD_SK 中设置。");
        }
        if (region == null || region.trim().isEmpty()) {
            throw new IllegalStateException("region 未配置，请设置 huawei.cloud.region 或环境变量 HUAWEI_CLOUD_REGION。");
        }
    }
}
