package cn.tworice.huaweiddns.util;

import cn.tworice.huaweiddns.service.DnsService;
import cn.tworice.huaweiddns.config.AppConfig;
import cn.tworice.huaweiddns.config.ConfigLoader;
import com.huaweicloud.sdk.dns.v2.model.ListRecordSets;
import com.huaweicloud.sdk.dns.v2.model.UpdateRecordSetsResponse;

import java.io.IOException;
import java.util.Arrays;

/**
 * DDNS 工具类：将指定域名记录集更新为当前主机最新公网 IPv4 地址。
 * <p>
 * 可直接运行：
 *   mvn compile exec:java -Dexec.mainClass=com.example.huaweiddns.util.DdnsUpdater
 *   java -cp target/classes com.example.huaweiddns.util.DdnsUpdater [zoneId] [recordName] [type]
 * 若不传参数，则使用 config.properties 中的 ddns.* 配置。
 */
public class DdnsUpdater {

    private final DnsService dnsService;

    public DdnsUpdater(AppConfig config) {
        this.dnsService = new DnsService(config);
    }

    /**
     * 获取当前公网 IP 并更新指定记录集；若与现有值一致则跳过更新（减少无意义写操作）。
     *
     * @param zoneId     域名（Zone）ID
     * @param recordName 记录集名称，如 cloud.example.cn
     * @param type       记录类型，如 A
     * @return 操作结果描述
     */
    public String updateToCurrentIp(String zoneId, String recordName, String type) {
        String currentIp = PublicIpFetcher.getPublicIp();

        ListRecordSets rs = dnsService.findRecordSet(zoneId, recordName, type);
        if (rs == null) {
            throw new IllegalStateException("在 zone " + zoneId + " 下未找到名称为 " + recordName
                    + "、类型为 " + type + " 的记录集");
        }

        var existing = rs.getRecords();
        if (existing != null && existing.size() == 1 && currentIp.equals(existing.get(0))) {
            return "当前公网 IP(" + currentIp + ") 与记录集现有值一致，无需更新。记录集ID=" + rs.getId();
        }

        Integer ttl = rs.getTtl();
        UpdateRecordSetsResponse resp = dnsService.updateRecordSet(
                zoneId, rs.getId(), rs.getName(), type, Arrays.asList(currentIp), ttl);
        return "已更新 " + recordName + " -> " + currentIp + "（TTL=" + ttl + "）| 响应：" + resp.toString();
    }

    public static void main(String[] args) throws IOException {
        AppConfig config = ConfigLoader.load();
        config.validate();

        String zoneId = args.length > 0 ? args[0] : config.getDdnsZoneId();
        String recordName = args.length > 1 ? args[1] : config.getDdnsRecordName();
        String type = args.length > 2 ? args[2] : config.getDdnsRecordType();

        if (zoneId == null || zoneId.trim().isEmpty()
                || recordName == null || recordName.trim().isEmpty()
                || type == null || type.trim().isEmpty()) {
            System.err.println("缺少 DDNS 目标参数。可在 config.properties 配置 "
                    + "ddns.zone.id / ddns.record.name / ddns.record.type，"
                    + "或传入参数：DdnsUpdater [zoneId] [recordName] [type]");
            System.exit(1);
            return;
        }

        DdnsUpdater updater = new DdnsUpdater(config);
        String result = updater.updateToCurrentIp(zoneId, recordName, type);
        System.out.println(result);
    }
}
