#!/bin/sh
# 出包前跑一次：版本号 +1（1.0.1 -> 1.0.2），两端 build.gradle 与手机版"关于"页 3 处同步改。
#
#   ./bump_version.sh          # 自动 +1（每次打包默认用这个）
#   ./bump_version.sh 1.0.5    # 指定版本
#   ./bump_version.sh -n       # 只看会改成什么，不动文件（dry-run）
#
# 规则：versionCode = "1"+"0"+"2" = 102，必须比旧包大才能覆盖安装。

cd "$(dirname "$0")" || exit 1
exec python3 - "$@" <<'PY'
import io, re, sys

TV = "pig-tv-tv/app/build.gradle"
PHONE = "pig-tv-phone/app/build.gradle"
HTML = "pig-tv-phone/app/src/main/assets/phone/index.html"
RE_VER = re.compile(r'versionName\s+"(\d+)\.(\d+)\.(\d+)"')
RE_CODE = re.compile(r'versionCode\s+(\d+)')

args = [a for a in sys.argv[1:] if a]
dry = any(a in ("-n", "--dry-run", "--dry") for a in args)
args = [a for a in args if not a.startswith("-")]

def read(p):
    return io.open(p, encoding="utf-8").read()

tv, ph = read(TV), read(PHONE)
mtv, mph = RE_VER.search(tv), RE_VER.search(ph)
if not (mtv and mph):
    sys.exit("找不到 versionName，检查 build.gradle 结构")
old = mtv.group(0).split('"')[1]
if mph.group(0).split('"')[1] != old:
    sys.exit("两端版本号不一致：TV=%s 手机=%s，先手动对齐" % (old, mph.group(0).split('"')[1]))
mc = RE_CODE.search(tv)
old_code = int(mc.group(1))

if args:
    parts = args[0].lstrip("vV").split(".")
    if len(parts) != 3 or not all(p.isdigit() for p in parts):
        sys.exit("版本号格式应为 1.0.2，收到：%s" % args[0])
    major, minor, patch = (int(x) for x in parts)
else:
    major, minor, patch = (int(x) for x in mtv.groups())
    patch += 1
new = "%d.%d.%d" % (major, minor, patch)
new_code = int("%d%d%d" % (major, minor, patch))
if new_code <= old_code:
    sys.exit("versionCode 没有变大（%d -> %d），不能覆盖安装" % (old_code, new_code))
if new == old:
    sys.exit("版本号没变：%s" % old)

print("版本：%s (%d) -> %s (%d)" % (old, old_code, new, new_code))
if dry:
    print("dry-run：未修改任何文件")
    sys.exit(0)

for p in (TV, PHONE):
    s = read(p)
    s = RE_VER.sub('versionName "%s"' % new, s, count=1)
    s = RE_CODE.sub("versionCode %d" % new_code, s, count=1)
    # 注释里那句 "versionCode 对应 1.0.1 = 101" 一起更新
    s = s.replace("versionCode 对应 %s = %d" % (old, old_code),
                  "versionCode 对应 %s = %d" % (new, new_code))
    io.open(p, "w", encoding="utf-8").write(s)
    print("  写入 %s" % p)

s = read(HTML)
before = s
s = s.replace('id="verHint">v%s<' % old, 'id="verHint">v%s<' % new)
s = s.replace("d.ver || '%s'" % old, "d.ver || '%s'" % new)
if s == before:
    print("  注意：%s 里没找到 v%s，人工核一下" % (HTML, old))
else:
    io.open(HTML, "w", encoding="utf-8").write(s)
    print("  写入 %s（关于页静态值 + 兜底值）" % HTML)

print("完事：重编两端 → zipalign → apksigner → verify")
PY
