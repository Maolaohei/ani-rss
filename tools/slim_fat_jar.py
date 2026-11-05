#!/usr/bin/env python3
"""产出单架构的精简 fat jar（供 Docker 镜像使用）。

默认构建（不带 -Dnative.platform，见 ani-rss-application/pom.xml）产出通用 jar，
要同时支持 Windows / Linux / macOS 三端下载，因此必须带全部平台的原生库；
而**镜像**只跑在一个架构上，其余平台的原生库是纯浪费：

    sqlite-jdbc   12.0 MB  →  内嵌 20 个平台，只用 1 个
    jlibtorrent   17.1 MB  →  4 个平台包，只用 1 个

本脚本做两件事：
  1) 把 BOOT-INF/lib/jlibtorrent-*.jar 里与目标架构无关的平台包删掉；
  2) 把 BOOT-INF/lib/sqlite-jdbc-*.jar 内的 org/sqlite/native/** 只保留目标平台，
     并把那一个原生库抽到 jar 外面（运行时用 -Dorg.sqlite.lib.path 指过去，
     见 docker/run.sh 的 SQLITE_LIB_PATH 分支）。

不修改原 jar，输出到 --out；原始产物保持可用（发布链路不变）。

用法：
    python tools/slim_fat_jar.py --jar target/ani-rss.jar --platform linux-x86_64 \
        --out target/ani-rss-slim.jar --native-out target/native

平台取值：linux-x86_64 / linux-arm64 / linux-armv7 / linux-musl-x86_64 /
         linux-musl-arm64 / macosx-arm64 / macosx-x86_64 / windows / windows-arm64
"""

from __future__ import annotations

import argparse
import io
import sys
import zipfile

# 平台 → (sqlite-jdbc 内的原生库目录, 文件名)
SQLITE_NATIVE = {
    "linux-x86_64": ("Linux/x86_64", "libsqlitejdbc.so"),
    "linux-arm64": ("Linux/aarch64", "libsqlitejdbc.so"),
    "linux-armv7": ("Linux/armv7", "libsqlitejdbc.so"),
    "linux-armv6": ("Linux/armv6", "libsqlitejdbc.so"),
    "linux-musl-x86_64": ("Linux-Musl/x86_64", "libsqlitejdbc.so"),
    "linux-musl-arm64": ("Linux-Musl/aarch64", "libsqlitejdbc.so"),
    "macosx-arm64": ("Mac/aarch64", "libsqlitejdbc.dylib"),
    "macosx-x86_64": ("Mac/x86_64", "libsqlitejdbc.dylib"),
    "windows": ("Windows/x86_64", "sqlitejdbc.dll"),
    "windows-arm64": ("Windows/aarch64", "sqlitejdbc.dll"),
}

# jlibtorrent 的平台包后缀（artifactId = jlibtorrent-<suffix>）
JLIBTORRENT_SUFFIXES = (
    "linux-x86",
    "linux-x86_64",
    "linux-arm64",
    "linux-arm",
    "macosx-arm64",
    "macosx-x86_64",
    "windows",
    "windows-x86",
    "android-arm",
    "android-arm64",
    "android-x86",
    "android-x86_64",
)

# 平台 → 需要保留的 jlibtorrent 后缀；None 表示全部平台包都不要（musl 等无可用原生库的场景）
JLIBTORRENT_KEEP = {
    "linux-x86_64": {"linux-x86_64"},
    "linux-arm64": {"linux-arm64"},
    "macosx-arm64": {"macosx-arm64"},
    "macosx-x86_64": {"macosx-x86_64"},
    "windows": {"windows"},
    "windows-arm64": {"windows"},
    "linux-musl-x86_64": set(),  # jlibtorrent 无 musl 构建
    "linux-musl-arm64": set(),
    "linux-armv7": set(),  # 32 位 arm 上游未发布
    "linux-armv6": set(),
}

SIG_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC")


def split_jlibtorrent(name: str) -> str | None:
    """BOOT-INF/lib/jlibtorrent-linux-x86_64-2.0.12.9.jar → 'linux-x86_64'；核心包返回 None。

    注意 linux-x86 是 linux-x86_64 的前缀，必须按"最长的先匹配"，否则会把
    jlibtorrent-linux-x86_64 误判成 linux-x86 平台包。
    """
    if not name.startswith("jlibtorrent-") or not name.endswith(".jar"):
        return None
    body = name[len("jlibtorrent-"):-len(".jar")]
    for suffix in sorted(JLIBTORRENT_SUFFIXES, key=len, reverse=True):
        if body.startswith(suffix + "-"):
            return suffix
    return None


def trim_sqlite(jar_bytes: bytes, platform: str) -> tuple[bytes, bytes | None, str | None]:
    """返回 (精简后的 sqlite jar, 抽出的原生库字节, 原生库文件名)。"""
    keep_dir, lib_name = SQLITE_NATIVE.get(platform, (None, None))
    src = zipfile.ZipFile(io.BytesIO(jar_bytes))
    out = io.BytesIO()
    native = None

    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            name = info.filename
            if name.startswith("META-INF/") and name.endswith(SIG_SUFFIXES):
                continue  # 改了内容，签名字节必然对不上，留着只会让类加载器报签名错误
            if name.startswith("org/sqlite/native/"):
                if keep_dir and name == f"org/sqlite/native/{keep_dir}/{lib_name}":
                    native = src.read(name)
                    dst.writestr(info, native)
                continue
            dst.writestr(info, src.read(name))

    if keep_dir and native is None:
        raise SystemExit(
            f"[slim] sqlite-jdbc 内找不到 {keep_dir}/{lib_name}，平台参数可能写错了（platform={platform}）"
        )
    return out.getvalue(), native, lib_name


def main() -> int:
    ap = argparse.ArgumentParser(description="产出单架构精简 fat jar")
    ap.add_argument("--jar", required=True, help="原始 Spring Boot fat jar")
    ap.add_argument("--platform", required=True, help="目标平台，见模块 docstring")
    ap.add_argument("--out", required=True, help="输出 jar")
    ap.add_argument("--native-out", help="把抽出的 sqlite 原生库写到这个目录")
    args = ap.parse_args()

    platform = args.platform
    if platform not in SQLITE_NATIVE:
        raise SystemExit(f"[slim] 未知平台 {platform!r}，可选：{', '.join(SQLITE_NATIVE)}")

    keep_jl = JLIBTORRENT_KEEP.get(platform, set())

    with zipfile.ZipFile(args.jar) as src:
        infos = src.infolist()
        before = sum(i.file_size for i in infos)
        dropped_jl: list[str] = []
        sqlite_name = None
        sqlite_bytes = None

        for info in infos:
            base = info.filename.rsplit("/", 1)[-1]
            suffix = split_jlibtorrent(base)
            if suffix is not None and suffix not in keep_jl:
                dropped_jl.append(info.filename)
            if info.filename.startswith("BOOT-INF/lib/sqlite-jdbc-") and info.filename.endswith(".jar"):
                sqlite_name = info.filename
                sqlite_bytes = src.read(info)

        if sqlite_bytes is None:
            raise SystemExit("[slim] jar 里没有 BOOT-INF/lib/sqlite-jdbc-*.jar，无需精简或路径不对")

        slim_sqlite, native, lib_name = trim_sqlite(sqlite_bytes, platform)

        with zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED) as dst:
            for info in infos:
                base = info.filename.rsplit("/", 1)[-1]
                if info.filename in dropped_jl:
                    continue
                if info.filename == sqlite_name:
                    # Spring Boot 的嵌套 jar 用 STORED，避免每次启动都解压整包
                    zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
                    zi.compress_type = zipfile.ZIP_STORED
                    zi.external_attr = info.external_attr
                    dst.writestr(zi, slim_sqlite)
                    continue
                zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
                zi.compress_type = info.compress_type
                zi.external_attr = info.external_attr
                dst.writestr(zi, src.read(info))

    after = None
    with zipfile.ZipFile(args.out) as out:
        after = sum(i.file_size for i in out.infolist())

    if args.native_out and native is not None:
        import os

        os.makedirs(args.native_out, exist_ok=True)
        target = os.path.join(args.native_out, lib_name)
        with open(target, "wb") as fh:
            fh.write(native)
        print(f"[slim] 原生库 → {target} ({len(native)} bytes)")

    print(f"[slim] platform      : {platform}")
    print(f"[slim] sqlite natives: 20 个平台 → {1 if native else 0}")
    print(f"[slim] jlibtorrent   : 删除 {len(dropped_jl)} 个平台包")
    for name in dropped_jl:
        print(f"[slim]   - {name.rsplit('/', 1)[-1]}")
    print(f"[slim] 未压缩字节    : {before} → {after}  (省 {(before - after) / 1048576:.1f} MB)")
    print(f"[slim] 输出          : {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
