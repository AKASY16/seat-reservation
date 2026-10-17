#!/bin/sh
# k6 컨테이너 안에서 실행한다. 앞뒤로 이 컨테이너의 CPU 사용 시간을 남긴다.
cpu() {
    cat /sys/fs/cgroup/cpu.stat 2>/dev/null || echo "cpuacct_ns $(cat /sys/fs/cgroup/cpuacct/cpuacct.usage 2>/dev/null)"
}

cpu > /out/k6-cpu-before.txt
k6 run /scripts/hold-load.js
rc=$?
cpu > /out/k6-cpu-after.txt
exit $rc
