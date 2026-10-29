package com.example.followingcar

import java.io.IOException

/** UNKNOWN includes a partially started/stopped service; it must never imply camera release. */
enum class VideoServiceState { UNKNOWN, STOPPED, RUNNING }

/**
 * Run the board's existing videoctl script, then inspect both systemd units and camera health.
 * Type=simple makes `systemctl start` return before the first camera frame, so its exit code
 * alone cannot establish readiness. A stopped unit also must have no remaining cgroup tasks.
 */
internal fun videoServiceCommand(enabled: Boolean? = null): String {
    val action = when (enabled) {
        true -> "start"
        false -> "stop"
        null -> "inspect"
    }
    return """
        python3 - <<'FOLLOWINGCAR_VIDEO'
        import json
        import pathlib
        import subprocess
        import sys
        import time
        import urllib.request

        ACTION = '$action'
        UNITS = ('rgbd-video.service', 'rgbd-mediamtx.service')
        HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def group_state(unit, group):
            # This board uses cgroup v2; support a systemd v1 mount as well.
            roots = [pathlib.Path('/sys/fs/cgroup'), pathlib.Path('/sys/fs/cgroup/systemd')]
            roots = [root for root in roots if (root / 'cgroup.procs').exists()]
            if not roots:
                return 'unknown'
            relative = (group or '/system.slice/' + unit).lstrip('/')
            if '..' in pathlib.PurePosixPath(relative).parts:
                return 'unknown'
            try:
                for root in roots:
                    folder = root / relative
                    if not folder.exists():
                        continue
                    for tasks in folder.rglob('cgroup.procs'):
                        try:
                            if tasks.read_text().strip():
                                return 'populated'
                        except FileNotFoundError:
                            pass  # The service finished exiting during inspection.
                return 'empty'
            except OSError:
                return 'unknown'

        def inspect_unit(unit):
            result = subprocess.run(
                ['systemctl', 'show', unit, '--property=LoadState,ActiveState,MainPID,ControlPID,ControlGroup'],
                capture_output=True, text=True, timeout=2,
            )
            if result.returncode:
                raise RuntimeError('Cannot inspect ' + unit + ': ' + result.stderr.strip())
            values = dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)
            return [values.get('LoadState', 'unknown'), values.get('ActiveState', 'unknown'),
                    values.get('MainPID', '-1'), values.get('ControlPID', '-1'),
                    group_state(unit, values.get('ControlGroup', ''))]

        def health():
            try:
                with HTTP.open('http://127.0.0.1:8080/api/health', timeout=1) as response:
                    data = json.load(response)
                    return 'ready' if response.status == 200 and data.get('ok') is True else 'not-ready'
            except Exception:
                return 'not-ready'

        def inspect():
            return [inspect_unit(unit) for unit in UNITS], health()

        try:
            if ACTION != 'inspect':
                # sudo is non-interactive on the configured board (NOPASSWD). stdin is closed
                # so a changed sudo policy fails instead of asking the App for another password.
                result = subprocess.run(
                    ['./scripts/videoctl.sh', ACTION], cwd='/home/topeet/Desktop/video',
                    stdin=subprocess.DEVNULL, capture_output=True, text=True,
                    timeout=25 if ACTION == 'start' else 36,
                )
                if result.returncode:
                    raise RuntimeError('videoctl ' + ACTION + ' failed: ' +
                                       (result.stderr or result.stdout).strip()[:1200])
            deadline = time.monotonic() + (20 if ACTION == 'start' else 0)
            while True:
                units, ready = inspect()
                running = all(unit[0] == 'loaded' and unit[1] == 'active' and
                              int(unit[2]) > 0 and int(unit[3]) == 0 and
                              unit[4] == 'populated' for unit in units) and ready == 'ready'
                if ACTION != 'start' or running or time.monotonic() >= deadline:
                    break
                time.sleep(0.4)
            print('\t'.join(['VIDEO_SERVICE_V1'] + units[0] + units[1] + [ready]))
        except Exception as error:
            print('Video service ' + ACTION + ': ' + str(error), file=sys.stderr)
            sys.exit(1)
        FOLLOWINGCAR_VIDEO
    """.trimIndent()
}

internal fun parseVideoServiceState(result: CommandResult): VideoServiceState {
    if (result.exitCode != 0) {
        val detail = result.stderr.ifBlank { result.stdout }.trim().take(600)
        throw IOException("视频服务操作失败（退出码 ${result.exitCode}）：$detail")
    }
    if (result.truncated) throw IOException("视频服务状态返回不完整，请重新检查")
    val fields = result.stdout.trim().split('\t')
    if (fields.size != 12 || fields[0] != "VIDEO_SERVICE_V1") {
        throw IOException("无法识别视频服务状态，请重新检查")
    }
    val units = listOf(fields.subList(1, 6), fields.subList(6, 11))
    val valid = units.all { unit ->
        unit[2].toLongOrNull()?.let { it >= 0 } == true &&
            unit[3].toLongOrNull()?.let { it >= 0 } == true &&
            unit[4] in setOf("empty", "populated", "unknown")
    } && fields[11] in setOf("ready", "not-ready")
    if (!valid) throw IOException("视频服务状态无效，请重新检查")
    if (units.all {
        // systemd may retain "failed" after all processes have exited; this still releases
        // the camera, provided neither cgroup has tasks and no healthy stream remains.
        it[0] == "loaded" && it[1] in setOf("inactive", "failed") &&
            it[2].toLong() == 0L && it[3].toLong() == 0L && it[4] == "empty"
    } && fields[11] == "not-ready") return VideoServiceState.STOPPED
    if (units.all {
        it[0] == "loaded" && it[1] == "active" &&
            it[2].toLong() > 0L && it[3].toLong() == 0L && it[4] == "populated"
    } && fields[11] == "ready") return VideoServiceState.RUNNING
    return VideoServiceState.UNKNOWN
}

internal fun requireVideoServiceState(result: CommandResult, enabled: Boolean): VideoServiceState {
    val actual = parseVideoServiceState(result)
    val expected = if (enabled) VideoServiceState.RUNNING else VideoServiceState.STOPPED
    if (actual != expected) {
        throw IOException(if (enabled) {
            "视频服务尚未就绪，请检查摄像头占用或板端视频日志；可先关闭推流再重试"
        } else {
            "未确认视频服务完全停止，摄像头可能仍被占用；请重试关闭推流"
        })
    }
    return actual
}
