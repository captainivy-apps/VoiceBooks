#!/usr/bin/env bash
#
# 管理镜像模型：自动下载全部 Sherpa-ONNX TTS 模型，并通过 rsync 上传到远程目录。
#
# 使用前请先配置 SSH 免登录（只需一次）：
#   ssh-copy-id [-p PORT] USER@SERVER
#   验证：ssh [-p PORT] USER@SERVER true   # 无密码提示即成功
#
# 模型清单自动从 app/.../tts/BuiltInTtsModels.kt 解析，新增模型无需改本脚本。
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODELS_SRC="$SCRIPT_DIR/../app/src/main/java/com/dafei/voicebook/tts/BuiltInTtsModels.kt"

TARBALL_BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
VOCODER_BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models"

SERVER=""
USER_NAME="${USER:-root}"
REMOTE_DIR=""
SSH_PORT="22"
WORKDIR="${TMPDIR:-/tmp}/tts-mirrors"
DRY_RUN=0
FORCE=0

usage() {
  cat <<EOF
用法: $(basename "$0") [选项]

选项:
  -s, --server HOST    rsync/ssh 服务器地址（必填，如 es.example.com）
  -u, --user USER      SSH 用户名（默认 root）
  -d, --dir PATH      远程目录绝对路径（必填，如 /var/www/mirrors）
  -p, --port PORT      SSH 端口（默认 22）
  -w, --workdir PATH   本地下载缓存目录（默认 \$TMPDIR/tts-mirrors）
  -f, --force          重新下载已存在的文件
  -n, --dry-run        仅打印将执行的操作，不下载/上传
  -h, --help           显示本帮助

示例:
  $(basename "$0") -s es.example.com -u root -d /var/www/mirrors
  $(basename "$0") -s es.example.com -u deploy -d /srv/mirrors -p 2222

提示: 运行前请先设置 SSH 免登录：
  ssh-copy-id -p PORT USER@SERVER
EOF
}

log() { printf '==> %s\n' "$*"; }
die() { printf '错误: %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--server) SERVER="${2:-}"; shift 2 ;;
    -u|--user)   USER_NAME="${2:-}"; shift 2 ;;
    -d|--dir)    REMOTE_DIR="${2:-}"; shift 2 ;;
    -p|--port)   SSH_PORT="${2:-}"; shift 2 ;;
    -w|--workdir) WORKDIR="${2:-}"; shift 2 ;;
    -f|--force)  FORCE=1; shift ;;
    -n|--dry-run) DRY_RUN=1; shift ;;
    -h|--help)   usage; exit 0 ;;
    *) die "未知参数: $1（使用 -h 查看帮助）" ;;
  esac
done

[[ -n "$SERVER" ]]     || die "缺少服务器地址，请用 -s/--server 指定"
[[ -n "$REMOTE_DIR" ]] || die "缺少远程目录，请用 -d/--dir 指定"
REMOTE_DIR="${REMOTE_DIR%/}"
REMOTE="${USER_NAME}@${SERVER}"

[[ -f "$MODELS_SRC" ]] || die "找不到模型清单源文件: $MODELS_SRC"

# 从 Kotlin 源码解析全部模型文件（.tar.bz2 / .tar.gz / .onnx）
MODELS=()
while IFS= read -r file; do
  [[ -n "$file" ]] && MODELS+=("$file")
done < <(grep -oE '[A-Za-z0-9._-]+\.(tar\.bz2|tar\.gz|onnx)' "$MODELS_SRC" | sort -u)

[[ ${#MODELS[@]} -gt 0 ]] || die "未能从 $MODELS_SRC 解析出模型文件"

url_for() {
  case "$1" in
    *.onnx) printf '%s/%s' "$VOCODER_BASE" "$1" ;;
    *)      printf '%s/%s' "$TARBALL_BASE" "$1" ;;
  esac
}

cat <<EOF
==> 目标服务器: $REMOTE:$REMOTE_DIR (端口 $SSH_PORT)
==> 本地缓存:   $WORKDIR
==> 模型数量:   ${#MODELS[@]}

请确认已提前配置 SSH 免登录：
  ssh-copy-id -p $SSH_PORT $REMOTE
若尚未配置，命令会反复提示输入密码。
EOF

if [[ $DRY_RUN -eq 1 ]]; then
  log "dry-run: 将下载并上传以下文件"
  for f in "${MODELS[@]}"; do printf '  %s\n' "$f"; done
  exit 0
fi

mkdir -p "$WORKDIR"

log "检查 SSH 连接"
ssh -p "$SSH_PORT" -o BatchMode=yes "$REMOTE" true \
  || die "无法免密登录 $REMOTE，请先执行: ssh-copy-id -p $SSH_PORT $REMOTE"

log "下载模型 (GitHub -> $WORKDIR)"
for file in "${MODELS[@]}"; do
  dest="$WORKDIR/$file"
  if [[ -s "$dest" && $FORCE -eq 0 ]]; then
    printf '  跳过(已存在) %s\n' "$file"
    continue
  fi
  printf '  下载 %s\n' "$file"
  curl -L --fail --retry 3 -C - -o "$dest" "$(url_for "$file")"
done

log "创建远程目录 $REMOTE_DIR"
ssh -p "$SSH_PORT" "$REMOTE" "mkdir -p '$REMOTE_DIR'"

log "rsync 上传到 $REMOTE:$REMOTE_DIR"
printf '%s\n' "${MODELS[@]}" > "$WORKDIR/.filelist"
rsync -avz --partial --progress --files-from="$WORKDIR/.filelist" \
  -e "ssh -p $SSH_PORT" "$WORKDIR/" "$REMOTE:$REMOTE_DIR/"

log "校验远程文件大小"
failed=0
for file in "${MODELS[@]}"; do
  local_size=$(wc -c < "$WORKDIR/$file" | tr -d ' ')
  remote_size=$(ssh -p "$SSH_PORT" "$REMOTE" "wc -c < '$REMOTE_DIR/$file' 2>/dev/null | tr -d ' '" || true)
  if [[ "$local_size" != "$remote_size" ]]; then
    printf '  FAIL %s: local=%s remote=%s\n' "$file" "$local_size" "$remote_size"
    failed=1
  else
    printf '  OK   %s (%s bytes)\n' "$file" "$local_size"
  fi
done

[[ $failed -eq 0 ]] || die "部分文件校验失败"
log "完成。共同步 ${#MODELS[@]} 个模型。"
