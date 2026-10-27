#!/usr/bin/env bash
#
# 纯 shell 版华为云 DDNS 更新脚本（不依赖 Java / Python）
# 直接调用华为云 DNS OpenAPI，使用 AK/SK 签名（SDK-HMAC-SHA256）发起请求。
#
# 依赖：bash(4+)、curl、openssl、jq、date(coreutils)
#
# 用法：
#   ./ddns_api.sh            # 守护模式：每 INTERVAL 秒检测公网 IP，变化时才更新
#   ./ddns_api.sh once       # 仅执行一次更新（无论 IP 是否变化）
#   ./ddns_api.sh status     # 打印当前公网 IP 与记录集现状
#
# 配置（优先级：环境变量 > 下方默认值；也可放到 ddns.conf 中 KEY=VALUE）：
#   HW_AK / HW_SK            访问密钥（必填）
#   HW_REGION                区域，默认 cn-north-4
#   HW_ZONE_ID               主域名 Zone ID（缺省时按 HW_DOMAIN 自动查询）
#   HW_DOMAIN                主域名，如 tworice.cn（用于自动查询 Zone ID）
#   HW_RECORD_NAME           子域名记录集名称，如 cloud.tworice.cn
#   HW_RECORD_TYPE           记录类型，默认 A

set -uo pipefail

# ===================== 可调参数 =====================
REGION="${HW_REGION:-cn-north-4}"  # 区号，域名托管在哪个区域
ZONE_ID="${HW_ZONE_ID:-8aace3ba78033e08017830f3b7ad2dc3}"  # 域名ID，可通过cn.tworice.huaweiddns.service.DnsService.listPublicZones方法获取
DOMAIN="${HW_DOMAIN:-tworice.cn}"  # 主域名
RECORD_NAME="${HW_RECORD_NAME:-cloud.tworice.cn}" # 解析记录名（子域名）
RECORD_TYPE="${HW_RECORD_TYPE:-A}" # IP地址类型，IPv4不要动，如果是IPv6修改为AAAA
AK="${HW_AK:-}" # 华为云的授权AK
SK="${HW_SK:-}" # 华为云的授权SK

INTERVAL=300
IP_CACHE="$(dirname "${BASH_SOURCE[0]}")/.last_ip"
LOG_FILE="$(dirname "${BASH_SOURCE[0]}")/ddns_api.log"
ENDPOINT="https://dns.${REGION}.myhuaweicloud.com"
SERVICE="dns"
DEBUG="${DEBUG:-0}"

# 若同目录存在 ddns.conf，则加载其中的 KEY=VALUE（环境变量优先）
CONF="$(dirname "${BASH_SOURCE[0]}")/ddns.conf"
if [ -f "$CONF" ]; then
  while IFS='=' read -r k v; do
    [ -z "$k" ] && continue; [ "${k#\#}" != "$k" ] && continue
    case "$k" in
      HW_AK) [ -z "$AK" ] && AK="$v" ;;
      HW_SK) [ -z "$SK" ] && SK="$v" ;;
      HW_REGION) [ -z "$REGION" ] || REGION="$v" ;;
      HW_ZONE_ID) [ -z "$ZONE_ID" ] || ZONE_ID="$v" ;;
      HW_DOMAIN) [ -z "$DOMAIN" ] || DOMAIN="$v" ;;
      HW_RECORD_NAME) [ -z "$RECORD_NAME" ] || RECORD_NAME="$v" ;;
      HW_RECORD_TYPE) [ -z "$RECORD_TYPE" ] || RECORD_TYPE="$v" ;;
    esac
  done < "$CONF"
fi

# ===================== 工具函数 =====================
log() {
  local ts; ts="$(date '+%Y-%m-%d %H:%M:%S')"
  local line="[$ts] $*"
  # 始终写入日志文件；仅当 stdout 为终端时才回显，便于后台静默运行
  echo "$line" >> "$LOG_FILE"
  if [ -t 1 ]; then echo "$line"; fi
}

die() { log "错误：$*"; exit 1; }

sha256_hex() { printf '%s' "$1" | openssl dgst -sha256 | awk '{print $NF}'; }
hmac_sha256_hex() { printf '%s' "$2" | openssl dgst -sha256 -hmac "$1" | awk '{print $NF}'; }

# 对路径/查询做最小化 URL 编码（保留字母数字 . _ - ~ 以及路径分隔符 /），其余百分号编码
urlencode() {
  local out="" c
  local s="$1"
  local i
  for ((i=0; i<${#s}; i++)); do
    c="${s:$i:1}"
    case "$c" in
      [A-Za-z0-9._~/]) out="${out}${c}" ;;
      *) printf -v c '%%%02X' "'$c"; out="${out}${c}" ;;
    esac
  done
  printf '%s' "$out"
}

get_public_ip() {
  local ip=""
  for url in "https://api.ipify.org" "https://ifconfig.me/ip" "https://myip.ipip.net"; do
    ip=$(curl -s --max-time 10 "$url" 2>/dev/null | grep -oE '([0-9]{1,3}\.){3}[0-9]{1,3}' | head -n1)
    [ -n "$ip" ] && { echo "$ip"; return 0; }
  done
  return 1
}

#
# 通用签名 + 请求函数
# 参数：method path [query] [body]
# 返回：响应体（JSON）输出到 stdout
#
hw_call() {
  local method="$1" path="$2" query="${3:-}" body="${4:-}"

  # 计算 host（去掉协议头）
  local host="${ENDPOINT#https://}"; host="${host#http://}"

  local sdk_date; sdk_date="$(date -u +%Y%m%dT%H%M%SZ)"
  # 负载哈希：SDK 用 sha256(body)；GET 无 body 时为 sha256("")
  local payload_hash; payload_hash="$(sha256_hex "$body")"

  # 仅对 host 与 x-sdk-date 签名（与 SDK 一致：不含 x-sdk-content-sha256 / content-type）
  local -A hdr
  hdr[host]="$host"
  hdr[x-sdk-date]="$sdk_date"

  # 按字母序生成 canonical headers 与 signed headers
  local signed="" canonical="" n
  for n in $(for k in "${!hdr[@]}"; do echo "$k"; done | sort); do
    signed="${signed}${n};"
    canonical="${canonical}${n}:${hdr[$n]}"$'\n'
  done
  signed="${signed%;}"

  # canonical uri：SDK 规范要求结尾补 “/”（仅用于签名；实际请求 URL 不带该斜杠）
  local canon_uri; canon_uri="/$(urlencode "${path#/}")"
  [ "${canon_uri: -1}" != "/" ] && canon_uri="${canon_uri}/"

  # canonical query（按 key 排序后逐个编码）
  local canon_query=""
  if [ -n "$query" ]; then
    local q="" kv k v ek ev
    while [ -n "$query" ]; do
      kv="${query%%&*}"
      query="${query#*&}"
      [ "$query" = "$kv" ] && query=""
      k="${kv%%=*}"; v="${kv#*=}"
      ek="$(urlencode "$k")"; ev="$(urlencode "$v")"
      q="${q}${ek}=${ev}&"
    done
    canon_query="${q%;}"
  fi

  local canon_req="${method}"$'\n'"${canon_uri}"$'\n'"${canon_query}"$'\n'"${canonical}"$'\n'"${signed}"$'\n'"${payload_hash}"
  [ "${DEBUG}" = "1" ] && log "CanonicalRequest:\n${canon_req}"

  local hash_cr; hash_cr="$(sha256_hex "$canon_req")"
  local string_to_sign="SDK-HMAC-SHA256"$'\n'"${sdk_date}"$'\n'"${hash_cr}"
  local signature; signature="$(hmac_sha256_hex "$SK" "$string_to_sign")"
  local authorization="SDK-HMAC-SHA256 Access=${AK}, SignedHeaders=${signed}, Signature=${signature}"

  # 组装 curl 参数（实际请求 URL 不带结尾 “/”）
  local -a args=(-sS -X "$method" "${ENDPOINT}${path}")
  [ -n "$canon_query" ] && args+=("?${canon_query}")
  args+=(-H "Host: ${hdr[host]}")
  args+=(-H "X-Sdk-Date: ${sdk_date}")
  args+=(-H "Authorization: ${authorization}")
  if [ -n "$body" ]; then
    # Content-Type 发送但不参与签名（与 SDK 行为一致）
    args+=(-H "Content-Type: application/json")
    args+=(-d "$body")
  fi

  curl "${args[@]}"
}

# 自动查询主域名 Zone ID
resolve_zone_id() {
  [ -n "$ZONE_ID" ] && { echo "$ZONE_ID"; return 0; }
  local resp; resp="$(hw_call GET "/v2.1/zones" "type=public" "")"
  if printf '%s' "$resp" | jq -e '.error_code // .error_msg' >/dev/null 2>&1; then
    local em; em="$(printf '%s' "$resp" | jq -r '.error_msg // .error_code')"
    die "查询域名列表失败：${em}（原始响应：${resp}）"
  fi
  local id; id="$(printf '%s' "$resp" | jq -r --arg d "$DOMAIN" '.zones[] | select(.name==$d) | .id' | head -n1)"
  [ -n "$id" ] && { echo "$id"; return 0; }
  return 1
}

# 在 zone 内查找目标记录集，输出 "id|ttl|records_json"
find_recordset() {
  local zone_id="$1"
  local resp; resp="$(hw_call GET "/v2.1/zones/${zone_id}/recordsets" "" "")"

  # 若返回鉴权/接口错误，给出明确提示（避免 jq 因 recordsets 为 null 而报错）
  if printf '%s' "$resp" | jq -e '.error_code // .error_msg' >/dev/null 2>&1; then
    local em; em="$(printf '%s' "$resp" | jq -r '.error_msg // .error_code')"
    die "查询记录集失败：${em}（原始响应：${resp}）"
  fi

  # 归一化名称（去掉结尾的点）
  local target; target="$(printf '%s' "$RECORD_NAME" | sed 's/\.$//')"
  printf '%s' "$resp" | jq -r --arg t "$target" --arg ty "$RECORD_TYPE" '
    .recordsets[]
    | select((.name | rtrimstr(".")) == $t and (.type == $ty))
    | "\(.id)|\(.ttl)|\(.records | tojson)"'
}

do_update_once() {
  [ -z "$AK" ] && die "未配置 HW_AK"
  [ -z "$SK" ] && die "未配置 HW_SK"
  command -v jq >/dev/null 2>&1 || die "缺少 jq，请先安装（apt install jq / yum install jq）"
  command -v openssl >/dev/null 2>&1 || die "缺少 openssl"
  command -v curl >/dev/null 2>&1 || die "缺少 curl"

  local zone_id; zone_id="$(resolve_zone_id)" || die "无法获取 Zone ID（请确认 HW_ZONE_ID 或 HW_DOMAIN 正确）"
  log "使用 Zone ID: $zone_id"

  local ip; ip="$(get_public_ip)" || die "获取公网 IP 失败"
  log "当前公网 IP: $ip"

  local found; found="$(find_recordset "$zone_id")"
  [ -z "$found" ] && die "在 zone $zone_id 下未找到名称为 $RECORD_NAME 类型为 $RECORD_TYPE 的记录集"
  local rs_id="${found%%|*}"
  local rest="${found#*|}"
  local ttl="${rest%%|*}"
  local records_json="${rest#*|}"

  local current; current="$(printf '%s' "$records_json" | jq -r '.[0] // empty')"
  log "记录集现状：records=$records_json ttl=$ttl"

  if [ "$current" = "$ip" ]; then
    log "公网 IP($ip) 与记录集现有值一致，无需更新。"
    echo "$ip" > "$IP_CACHE"
    return 0
  fi

  local new_records; new_records="$(jq -nc --arg ip "$ip" '[$ip]')"
  local body="$(jq -nc --arg name "${RECORD_NAME}." --arg ty "$RECORD_TYPE" --argjson rec "$new_records" --argjson ttl "$ttl" \
    '{name:$name, type:$ty, records:$rec, ttl:$ttl}')"

  local resp; resp="$(hw_call PUT "/v2.1/zones/${zone_id}/recordsets/${rs_id}" "" "$body")"
  log "更新响应：${resp}"
  echo "$ip" > "$IP_CACHE"
  log "已将 $RECORD_NAME 更新为 $ip"
}

main() {
  local mode="${1:-daemon}"
  case "$mode" in
    once) do_update_once || exit 1 ;;
    status)
      local zone_id; zone_id="$(resolve_zone_id)" || die "无法获取 Zone ID"
      local found; found="$(find_recordset "$zone_id")"
      local ip; ip="$(get_public_ip)" || die "获取公网 IP 失败"
      local last="(无缓存)"; [ -f "$IP_CACHE" ] && last="$(cat "$IP_CACHE")"
      log "Zone=$zone_id 当前公网IP=$ip 缓存=$last 记录集=[$found]"
      ;;
    daemon|"")
      log "进入守护模式，间隔 ${INTERVAL}s，目标：${RECORD_NAME}"
      trap 'log "守护进程退出。"; exit 0' INT TERM
      while true; do
        ip="$(get_public_ip)" || { log "获取公网 IP 失败，跳过本次。"; sleep "$INTERVAL"; continue; }
        last=""; [ -f "$IP_CACHE" ] && last="$(cat "$IP_CACHE")"
        if [ "$ip" != "$last" ]; then
          log "公网 IP 变化：${last:-无} -> $ip，执行更新..."
          do_update_once && log "更新完成。" || log "更新失败，下次重试。"
        else
          log "公网 IP 未变化（$ip），跳过。"
        fi
        sleep "$INTERVAL"
      done
      ;;
    *) echo "未知参数：$mode（可用：daemon | once | status）"; exit 1 ;;
  esac
}

main "$@"
