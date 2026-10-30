#!/usr/bin/env bash
# 本地演示数据（只在 dev / H2 内存库上有意义，重启即清空）
# 用法： BASE=http://127.0.0.1:18088 APK=../dist/xxx.apk ./scripts/seed-demo.sh
set -u

BASE="${BASE:-http://127.0.0.1:18088}"
APK="${APK:-}"
# 本机有 HTTP_PROXY 环境变量时，curl 打 127.0.0.1 会被劫持，必须 --noproxy
cget() { curl -s --noproxy '*' "$@"; }

jq_get() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

echo "== 管理员登录 =="
TOK=$(cget -X POST "$BASE/admin/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | jq_get "['data']['token']")
[ -z "$TOK" ] && { echo "登录失败，先确认服务在跑、管理员密码是 admin123"; exit 1; }
AUTH="Authorization: Bearer $TOK"

echo "== 注册演示用户 =="
for u in demo1 demo2 demo3; do
  r=$(cget -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$u\",\"password\":\"demo1234\",\"deviceId\":\"dev-$u\"}")
  echo "  $u -> $(echo "$r" | head -c 80)"
done

echo "== 造一组同网段登录（验证在线数与设备数） =="
for u in demo1 demo2 demo3; do
  cget -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$u\",\"password\":\"demo1234\",\"deviceId\":\"dev-$u\",\"platform\":\"phone\"}" >/dev/null
done

echo "== 演示用户写一点同步数据 =="
UTOK=$(cget -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"demo1","password":"demo1234","deviceId":"dev-demo1","platform":"phone"}' | jq_get "['data']['token']")
NOW=$(python3 -c 'import time;print(int(time.time()*1000))')
cget -X POST "$BASE/api/sync/push" -H "Authorization: Bearer $UTOK" -H 'Content-Type: application/json' -d "{
  \"collect\":[{\"sourceKey\":\"csp_Demo\",\"vodId\":\"1001\",\"vodName\":\"流浪地球3\",\"updatedAt\":$NOW}],
  \"history\":[{\"sourceKey\":\"csp_Demo\",\"vodId\":\"1001\",\"dataJson\":\"{}\",\"playFlag\":\"1\",\"playIndex\":0,\"positionMs\":1200000,\"durationMs\":7200000,\"updatedAt\":$NOW}],
  \"setting\":[{\"key\":\"theme\",\"value\":\"dark\",\"updatedAt\":$NOW}]
}" | head -c 120; echo

echo "== 官方片源 =="
cget -X POST "$BASE/admin/sources" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"name":"官方主源","url":"https://example.com/config.json","weight":100,"isDefault":1,"enabled":1,"remark":"演示用"}' | head -c 120; echo

if [ -n "$APK" ] && [ -f "$APK" ]; then
  echo "== 上传安装包 $APK =="
  cget -X POST "$BASE/admin/versions" -H "$AUTH" \
    -F "file=@$APK" \
    -F "versionCode=1010" -F "versionName=1.0.10" -F "platform=tv" -F "abi=arm64" \
    -F "packageName=com.github.tvbox.osc.tk" -F "status=published" \
    -F "changelog=演示数据" | head -c 200; echo
else
  echo "-- 未给 APK，跳过版本上传（APK=path 可开启）"
fi

echo "== 完成 =="
cget "$BASE/admin/stats/overview" -H "$AUTH" | head -c 300; echo
