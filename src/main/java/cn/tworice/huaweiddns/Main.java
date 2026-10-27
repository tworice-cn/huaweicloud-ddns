package cn.tworice.huaweiddns;

import cn.tworice.huaweiddns.service.DnsService;
import cn.tworice.huaweiddns.util.DdnsUpdater;
import cn.tworice.huaweiddns.config.AppConfig;
import cn.tworice.huaweiddns.config.ConfigLoader;
import com.huaweicloud.sdk.core.exception.ConnectionException;
import com.huaweicloud.sdk.core.exception.RequestTimeoutException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.dns.v2.model.ListRecordSets;
import com.huaweicloud.sdk.dns.v2.model.PublicZoneResp;
import com.huaweicloud.sdk.dns.v2.model.UpdateRecordSetsResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 主程序：演示华为云 DNS 的常用操作。
 * <p>
 * 用法：
 *   list-zones
 *      列出当前账号下的公网域名（Zone）列表
 *
 *   list-records &lt;zoneId&gt;
 *      列出指定域名下的记录集
 *
 *   update &lt;zoneId&gt; &lt;recordsetId&gt; &lt;name&gt; &lt;type&gt; &lt;ttl&gt; &lt;ip1&gt; [ip2 ...]
 *      更新记录集（如把 A 记录指向新的 IP）
 *      示例：update zone-xxxx rs-yyyy www.example.com. A 300 1.2.3.4
 *
 *   ddns [zoneId] [recordName] [type]
 *      将记录集更新为当前主机最新公网 IP（DDNS）
 *      缺省参数取自 config.properties 的 ddns.* 配置
 *
 * 运行方式：
 *   mvn compile exec:java
 *   mvn package && java -jar target/huawei-ddns.jar list-zones
 */
public class Main {

    public static void main(String[] args) {
        AppConfig config;
        try {
            config = ConfigLoader.load();
            config.validate();
        } catch (IOException e) {
            System.err.println("加载配置失败：" + e.getMessage());
            System.exit(1);
            return;
        }

        DnsService service = new DnsService(config);

        try {
//            if (args.length == 0) {
//                printUsage();
//                // 默认演示：列出域名列表
//                listZones(service);
//                return;
//            }

//            String cmd = args[0];
            String cmd = "ddns";
            switch (cmd) {
                case "list-zones":
                    listZones(service);
                    break;
                case "list-records":
                    requireArgs(args, 2, "list-records <zoneId>");
                    listRecords(service, args[1]);
                    break;
                case "update":
                    // update <zoneId> <recordsetId> <name> <type> <ttl> <ip1> [ip2...]
                    requireArgs(args, 7, "update <zoneId> <recordsetId> <name> <type> <ttl> <ip1> [ip2...]");
                    updateRecord(service, args);
                    break;
                case "ddns":
                    // ddns [zoneId] [recordName] [type]，缺省取 config.properties 的 ddns.* 配置
                    runDdns(config, args);
                    break;
                default:
                    System.err.println("未知命令：" + cmd);
                    printUsage();
                    System.exit(1);
            }
        } catch (ConnectionException | RequestTimeoutException e) {
            System.err.println("网络异常：" + e.getMessage());
            System.exit(1);
        } catch (ServiceResponseException e) {
            System.err.println("服务返回错误：" + e.getHttpStatusCode()
                    + " | " + e.getErrorCode() + " | " + e.getErrorMsg());
            System.exit(1);
        }
    }

    private static void listZones(DnsService service) {
        List<PublicZoneResp> zones = service.listPublicZones();
        System.out.println("===== 公网域名列表（共 " + zones.size() + " 个）=====");
        for (PublicZoneResp z : zones) {
            System.out.printf("ID=%-36s Name=%-30s Type=%-7s Status=%s TTL=%d%n",
                    z.getId(), z.getName(), z.getZoneType(), z.getStatus(), z.getTtl());
        }
    }

    private static void listRecords(DnsService service, String zoneId) {
        List<ListRecordSets> records = service.listRecordSets(zoneId);
        System.out.println("===== 记录集列表（zoneId=" + zoneId + "，共 " + records.size() + " 条）=====");
        for (ListRecordSets r : records) {
            System.out.printf("ID=%-36s Name=%-30s Type=%-6s TTL=%d Records=%s%n",
                    r.getId(), r.getName(), r.getType(), r.getTtl(), r.getRecords());
        }
    }

    private static void updateRecord(DnsService service, String[] args) {
        String zoneId = args[1];
        String recordsetId = args[2];
        String name = args[3];
        String type = args[4];
        Integer ttl = Integer.valueOf(args[5]);

        List<String> records = new ArrayList<>();
        for (int i = 6; i < args.length; i++) {
            records.add(args[i]);
        }

        UpdateRecordSetsResponse response =
                service.updateRecordSet(zoneId, recordsetId, name, type, records, ttl);
        System.out.println("===== 更新成功 =====");
        System.out.println(response.toString());
    }

    private static void runDdns(AppConfig config, String[] args) {
        String zoneId = args.length > 1 ? args[1] : config.getDdnsZoneId();
        String recordName = args.length > 2 ? args[2] : config.getDdnsRecordName();
        String type = args.length > 3 ? args[3] : config.getDdnsRecordType();

        if (zoneId == null || zoneId.trim().isEmpty()
                || recordName == null || recordName.trim().isEmpty()
                || type == null || type.trim().isEmpty()) {
            System.err.println("缺少 DDNS 目标参数，请在 config.properties 配置 ddns.zone.id / "
                    + "ddns.record.name / ddns.record.type，或传入参数：ddns [zoneId] [recordName] [type]");
            System.exit(1);
            return;
        }

        DdnsUpdater updater = new DdnsUpdater(config);
        String result = updater.updateToCurrentIp(zoneId, recordName, type);
        System.out.println(result);
    }

    private static void requireArgs(String[] args, int min, String usage) {
        if (args.length < min) {
            System.err.println("参数不足。用法：" + usage);
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("用法：");
        System.out.println("  list-zones");
        System.out.println("  list-records <zoneId>");
        System.out.println("  update <zoneId> <recordsetId> <name> <type> <ttl> <ip1> [ip2 ...]");
        System.out.println("  ddns [zoneId] [recordName] [type]   # DDNS：更新为当前公网 IP");
    }
}
