# 华为云 DNS 工具（huaweicloud-ddns）

## 快速开始

### 第一种通过脚本（推荐）

① 将脚本文件`ddns_api.sh`传输到服务器。

② 设置华为云的`AK`和`SK`。

```shell

chmod +x ddns_api.sh
# 配置（环境变量，或写入同目录 ddns.conf 的 KEY=VALUE）
export HW_AK=你的AK
export HW_SK=你的SK
```

③ 运行。

```shell
nohup ./ddns_api.sh >/dev/null 2>&1 & 	# 后台运行
./ddns_api.sh            				# 守护模式：每 300s 检测公网 IP，变化才更新
./ddns_api.sh once       				# 仅更新一次
./ddns_api.sh status     				# 查看当前公网 IP 与记录集现状
```

④ 查看日志。

```shell
tail -f ddns_api.log
```



### 第二种通过服务

① 打包当前Java项目为jar。

② 设置华为云的`AK`和`SK`。

```shell
huawei.cloud.ak=你的AK
huawei.cloud.sk=你的SK
huawei.cloud.region=cn-north-4
```

③ 运行。

```shell
java -jar target/huawei-ddns.jar list-zones
```
