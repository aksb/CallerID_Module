#!/system/bin/sh
# 故意放在 service.sh（late_start service 阶段）而不是 post-fs-data.sh：
# post-fs-data.sh 执行得非常早，这时候 pm/appops/dumpsys 依赖的系统服务
# 大概率还没就绪，命令大概率会失败；service.sh 阶段系统已经基本起来了，
# 这些命令才能正常工作。这个阶段本身是异步执行的，不会拖慢开机速度。

MODDIR=${0%/*}
. "$MODDIR/common/keepalive.sh"

LOG="$MODDIR/keepalive.log"

# 开机时 pm 服务就绪的时间点不完全固定，这里做一个简单的等待重试，
# 最多等 60 秒；60 秒后不管探测到没有都会往下走一次（run_keepalive 内部
# 自己会再判断一次包名存不存在，探测失败的话直接跳过，不会出错）。
i=0
while [ $i -lt 60 ]; do
    if pm path "com.aksb2026.callerid.module" >/dev/null 2>&1; then
        break
    fi
    sleep 1
    i=$((i + 1))
done

run_keepalive "$LOG"
