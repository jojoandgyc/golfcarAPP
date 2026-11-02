# 国内 FollowingCar 中转容器

本目录只管理 `/data/golfcar`。它保留原协议和车端停止规则，将入口改为
`world-agents.com` (`117.50.34.249`)。控制为 `2443/TCP`，视频网页及 WHEP
为 `9443/TCP`，frp 为 `7443/TCP+UDP`，TURN 为 `3478/TCP+UDP`、`5349/TCP`
及 `62000–62099/UDP`。宿主已使用的 `8443` 不参与本部署。

三个容器使用专属 `172.28.240.0/24` bridge；frps 内部代理监听
`172.28.240.11:22022/18889`，仅 Nginx 所在的同一 Compose 网络可达，
没有映射到宿主。Coturn 使用固定容器地址 `172.28.240.10`，向 WebRTC
公布 `117.50.34.249`。不要运行旧的 `relay/provision.py prepare-server`
或 `activate-server`：它们会写宿主 systemd 和 `/etc`。

## 准备和启动

1. 确认 `world-agents.com` 解析到 `117.50.34.249`，上述所有端口空闲，
   `172.28.240.0/24` 与现有 Docker 网络和宿主路由不冲突。确认公开 TLS
   证书位于 `/www/server/panel/vhost/cert/world-agents.com/`，文件名为
   `fullchain.pem`、`privkey.pem`。证书必须包含 `DNS:world-agents.com` SAN。
2. 将仓库 `relay/` 作为临时、只读源码放到国内服务器。以 root 执行
   `python3 relay/domestic/provision.py prepare`。脚本拒绝 `/data/golfcar`
   中已有非本项目文件；它验证证书域名、有效期和公私钥匹配，然后只在
   `/data/golfcar` 下生成配置、独立 frp/TURN 密钥、客户端 CA、容器文件
   和播放器。已有密钥和已签发身份不会自动轮换。
3. 将海外服务器当前运行的 frps 0.71.1 二进制复制到
   `/data/golfcar/bin/frps`。容器构建时核验该二进制 SHA-256：
   `2322762939e66e66850bbc9103b6d73fec39e83bd8ad6c71ebeeb120d56d6ab3`。
   文件不进入 Git；校验不通过时构建立即失败。
   `.dockerignore` 只允许两个 Dockerfile 和 `bin/frps` 进入构建上下文；
   私钥、导入包和配置不会被发送给 Docker 构建器。
4. 在 `/data/golfcar` 执行 `docker compose config --quiet`，然后
   `docker compose build`。先在容器内核验 Nginx 的 stream 模块和配置，
   再执行 `docker compose up -d`。这是独立 Compose 项目
   `followingcar-domestic`，不会安装或修改宿主 systemd unit。

```sh
cd /data/golfcar
docker compose config --quiet
docker compose build
docker compose run --rm --no-deps --entrypoint /usr/sbin/nginx nginx \
  -t -c /etc/followingcar-relay/nginx.conf
docker compose up -d
docker compose ps
```

配置与密钥位置：

| 路径 | 用途 |
|---|---|
| `/data/golfcar/compose.yaml` | 专属容器、网络、端口、资源上限 |
| `/data/golfcar/config/` | frps、Nginx、Coturn 配置；Coturn 文件含私有 TURN secret |
| `/data/golfcar/secrets/` | 三个容器按需只读挂载的证书、CA 和 frp token |
| `/data/golfcar/private/` | 独立主密钥、CA 私钥、导入包和板端 bundle，容器不挂载 |
| `/data/golfcar/web/` | 播放器静态文件 |
| `/data/golfcar/bin/frps` | 经 SHA-256 校验的海外同版本 frps 二进制 |
| `/data/golfcar/.dockerignore` | 只允许 Dockerfile 和 frps 二进制进入构建上下文 |

签发 App 与板端的专属身份：

```sh
python3 relay/domestic/provision.py issue-client --name phone-001
python3 relay/domestic/provision.py issue-client --name emu-001
python3 relay/domestic/provision.py export-board
```

输出分别位于 `/data/golfcar/private/clients/<名称>/<名称>.carremote` 和
`/data/golfcar/private/board-001.boardremote`。客户端包采用原 App 导入协议，
但视频端口为 `9443`；板端包采用原 schema 1，含新域名和新的 frp/TURN
密钥。不要把这些文件、私钥或 Coturn 配置提交到 Git、贴到聊天或放在
公开目录。板端需要**独立的国内 frpc / 视频网关实例**，不要覆盖正在运行的
海外实例或跟随程序；旧海外连接可保留作为回退。

## 验证与回退

先检查 `docker compose ps`、三个容器的限量日志和监听端口。未提供客户端
证书时，`9443` 的 HTTPS 握手必须被拒绝；导入 `phone-001.carremote`
后，再验证 App 的只读连接和视频 WHEP/TURN。车端联调只用模拟跟随
`dryRun=true`，不得自动执行电机测试。WebRTC 媒体需要实际验证候选类型
为 relay、解码帧连续增长；仅有 `9443` 可访问不足以证明 UDP 转发正常。

```sh
cd /data/golfcar
docker compose ps
docker compose logs --tail=100 frps nginx turn
docker compose down
```

`docker compose down` 仅停止本项目容器和专属网络，不删除
`/data/golfcar/private` 或服务器现有服务。容器使用只读根文件系统、非 root
UID、零额外 capability、进程/CPU/内存限制和轮转日志。Docker 发布端口
仍会向宿主机添加自身的转发规则，部署前后需要核对原服务端口和防火墙规则。
宝塔证书续期后，在控制空闲时以 root 显式执行
`python3 relay/domestic/provision.py refresh-cert`。脚本先验证新的
`world-agents.com` SAN、至少 24 小时有效期和公私钥匹配；若内容有变化，
将旧证书和私钥完整备份到 `/data/golfcar/private/cert-backups/<UTC时间>/`，
再分别使用临时文件和原子重命名更新容器使用的 `server.crt`、`server.key`。
如果第二次替换失败，脚本恢复原证书对并保留备份。重复执行且内容未变化
时不产生新备份。

三个容器只读挂载整个 `secrets/tls/` **目录**，因此原子替换后容器能看到
新 inode；不要把 `server.crt`、`server.key` 改成单文件 bind mount，
否则运行中容器可能一直读取旧 inode，即使发送 HUP 也无法加载新证书。

证书复制**不会**自动重载或重启任何容器。确认没有控制会话、视频会话且
维护期间不接入新会话后，显式检查新证书与三个容器配置，然后按顺序
重载 Nginx、维护 frps 和 Coturn：

```sh
cd /data/golfcar
docker compose run --rm --no-deps --entrypoint /usr/sbin/nginx nginx \
  -t -c /etc/followingcar-relay/nginx.conf
docker compose kill -s HUP nginx
docker compose restart frps turn
```

这两份证书文件各自原子替换；文件系统不支持把两个路径作为一笔事务同时
提交。因此脚本执行期间不要启动或重启容器，等它完成后再执行上述维护。

本地测试：`python3 -m unittest discover -s relay/domestic -p test_domestic.py -v`。
