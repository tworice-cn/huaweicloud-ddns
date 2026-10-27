package cn.tworice.huaweiddns.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 配置加载器：
 * 1. 优先读取系统属性 -Dconfig.file=路径 指定的外部文件；
 * 2. 否则读取 classpath 下的 config.properties；
 * 3. 单个配置项优先级：环境变量 > 配置文件 > 默认值。
 */
public final class ConfigLoader {

    private ConfigLoader() {
    }

    public static AppConfig load() throws IOException {
        Properties props = new Properties();

        String external = System.getProperty("config.file");
        if (external != null && Files.exists(Paths.get(external))) {
            try (InputStream in = Files.newInputStream(Paths.get(external))) {
                props.load(in);
            }
        } else {
            try (InputStream in = ConfigLoader.class.getResourceAsStream("/config.properties")) {
                if (in == null) {
                    throw new IOException("未找到 classpath 下的 config.properties，"
                            + "且未通过 -Dconfig.file 指定外部文件。");
                }
                props.load(in);
            }
        }

        String ak = resolve(props, "huawei.cloud.ak", "HUAWEI_CLOUD_AK", "");
        String sk = resolve(props, "huawei.cloud.sk", "HUAWEI_CLOUD_SK", "");
        String region = resolve(props, "huawei.cloud.region", "HUAWEI_CLOUD_REGION", "cn-north-4");

        String ddnsZoneId = resolve(props, "ddns.zone.id", "DDNS_ZONE_ID", "");
        String ddnsRecordName = resolve(props, "ddns.record.name", "DDNS_RECORD_NAME", "");
        String ddnsRecordType = resolve(props, "ddns.record.type", "DDNS_RECORD_TYPE", "A");

        return new AppConfig(ak, sk, region, ddnsZoneId, ddnsRecordName, ddnsRecordType);
    }

    private static String resolve(Properties props, String key, String envKey, String def) {
        String env = System.getenv(envKey);
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        String prop = props.getProperty(key);
        if (prop != null && !prop.trim().isEmpty() && !prop.startsWith("YOUR_")) {
            return prop.trim();
        }
        return def;
    }
}
