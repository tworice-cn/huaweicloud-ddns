package cn.tworice.huaweiddns.service;

import cn.tworice.huaweiddns.config.AppConfig;
import com.huaweicloud.sdk.core.auth.BasicCredentials;
import com.huaweicloud.sdk.core.auth.ICredential;
import com.huaweicloud.sdk.dns.v2.DnsClient;
import com.huaweicloud.sdk.dns.v2.model.ListPublicZonesRequest;
import com.huaweicloud.sdk.dns.v2.model.ListPublicZonesResponse;
import com.huaweicloud.sdk.dns.v2.model.ListRecordSets;
import com.huaweicloud.sdk.dns.v2.model.ListRecordSetsByZoneRequest;
import com.huaweicloud.sdk.dns.v2.model.ListRecordSetsByZoneResponse;
import com.huaweicloud.sdk.dns.v2.model.PublicZoneResp;
import com.huaweicloud.sdk.dns.v2.model.UpdateRecordSetsRequest;
import com.huaweicloud.sdk.dns.v2.model.UpdateRecordSetsReq;
import com.huaweicloud.sdk.dns.v2.model.UpdateRecordSetsResponse;
import com.huaweicloud.sdk.dns.v2.region.DnsRegion;

import java.util.List;

/**
 * 华为云 DNS 服务封装：
 * - 查询公网域名（Zone）列表
 * - 查询指定域名下的记录集（Record Set）
 * - 更新记录集（如 A 记录指向的 IP）
 */
public class DnsService {

    private final DnsClient client;

    public DnsService(AppConfig config) {
        ICredential credential = new BasicCredentials()
                .withAk(config.getAk())
                .withSk(config.getSk());

        this.client = DnsClient.newBuilder()
                .withCredential(credential)
                .withRegion(DnsRegion.valueOf(config.getRegion()))
                .build();
    }

    /**
     * 查询公网域名列表。
     *
     * @return 公网域名（Zone）列表
     */
    public List<PublicZoneResp> listPublicZones() {
        ListPublicZonesRequest request = new ListPublicZonesRequest();
        request.withType("public");
        ListPublicZonesResponse response = client.listPublicZones(request);
        return response.getZones();
    }

    /**
     * 查询指定域名下的记录集列表。
     *
     * @param zoneId 域名（Zone）ID
     * @return 记录集列表
     */
    public List<ListRecordSets> listRecordSets(String zoneId) {
        ListRecordSetsByZoneRequest request = new ListRecordSetsByZoneRequest();
        request.withZoneId(zoneId);
        ListRecordSetsByZoneResponse response = client.listRecordSetsByZone(request);
        return response.getRecordsets();
    }

    /**
     * 在指定域名下按名称和类型查找记录集。
     * <p>
     * 匹配采用名称完全相等（归一化后）：记录集名称为完整域名（如 cloud.example.cn.），
     * 不会误匹配父域名（如 example.cn）。
     *
     * @param zoneId 域名（Zone）ID
     * @param name   记录集名称（如 cloud.example.cn 或 cloud.example.cn.）
     * @param type   记录类型，如 A / AAAA / CNAME
     * @return 匹配的记录集，未找到返回 null
     */
    public ListRecordSets findRecordSet(String zoneId, String name, String type) {
        List<ListRecordSets> all = listRecordSets(zoneId);
        String target = normalize(name);
        for (ListRecordSets r : all) {
            if (!type.equalsIgnoreCase(r.getType())) {
                continue;
            }
            String recNorm = normalize(r.getName());
            // 仅完全相等匹配，避免 cloud.example.cn 误匹配父域名 example.cn
            if (recNorm.equals(target)) {
                return r;
            }
        }
        return null;
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().toLowerCase();
        while (t.endsWith(".")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /**
     * 更新记录集（例如将 A 记录指向新的 IP）。
     *
     * @param zoneId      域名（Zone）ID
     * @param recordsetId 记录集 ID
     * @param name        记录集名称（FQDN，建议以 '.' 结尾，如 www.example.com.）
     * @param type        记录类型，如 A / AAAA / CNAME
     * @param records     记录值列表（如 ["1.2.3.4"]）
     * @param ttl         缓存时间，传 null 时使用原值
     * @return 更新响应
     */
    public UpdateRecordSetsResponse updateRecordSet(String zoneId,
                                                    String recordsetId,
                                                    String name,
                                                    String type,
                                                    List<String> records,
                                                    Integer ttl) {
        UpdateRecordSetsRequest request = new UpdateRecordSetsRequest();
        request.withZoneId(zoneId);
        request.withRecordsetId(recordsetId);

        UpdateRecordSetsReq body = new UpdateRecordSetsReq();
        body.withType(type);
        body.withRecords(records);
        body.withName(name);
        if (ttl != null) {
            body.withTtl(ttl);
        }
        request.withBody(body);

        return client.updateRecordSets(request);
    }
}
