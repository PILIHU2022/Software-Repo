# 签名密钥（测试用途）

**警告：`patchwork-test.jks` 是故意提交到仓库里的测试密钥。**

- 别名（alias）：`patchwork`
- 密钥库密码（store password）：`patchwork123`
- 密钥密码（key password）：`patchwork123`
- 证书 SHA-256 指纹：
  `D3:34:88:79:40:65:C9:2E:37:0D:24:A1:0E:61:4E:1E:64:E3:19:B7:5E:6F:8B:B7:C4:AA:D6:7F:3A:5B:5E:01`

把测试密钥提交到仓库，是为了让 CI 在零 secrets 的情况下也能产出签名 APK。
任何能读取本仓库的人都能用同一把密钥签署更新包。

> **生产环境请勿沿用这把密钥。** 请生成新的 keystore，把 base64 内容放进仓库
> secret `PATCHWORK_KEYSTORE_BASE64`，并把 `PATCHWORK_STORE_PASSWORD` /
> `PATCHWORK_KEY_ALIAS` / `PATCHWORK_KEY_PASSWORD` 配置为 secrets。
> 同时把本文件替换为不含密码的说明。

`fdroid/keystore.jks` 是 `publish-fdroid.sh` 在运行时复制出来的副本，已在
`.gitignore` 中忽略，不要提交。
