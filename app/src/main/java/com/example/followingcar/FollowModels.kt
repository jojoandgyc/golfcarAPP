package com.example.followingcar

import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class FollowCamera(val argument: String, val label: String) {
    CONFIG("config", "沿用车端配置"),
    ASTRA_PRO("astra-pro", "Astra Pro"),
    GEMINI335("gemini335", "Gemini 335"),
}

enum class FollowResumeGesture(val argument: String, val label: String) {
    CLOSED_FIST("Closed_Fist", "握拳"),
    THUMB_UP("Thumb_Up", "点赞"),
}

/** Only fixed, validated switches and numeric settings are sent to the board. */
data class FollowLaunchOptions(
    val targetDistanceMeters: Double? = null,
    val gesturesEnabled: Boolean = false,
    val resumeGesture: FollowResumeGesture = FollowResumeGesture.CLOSED_FIST,
    val avoidanceEnabled: Boolean = false,
    val reverseEnabled: Boolean = false,
    val reverseDistanceMeters: Double? = null,
) {
    fun validate(camera: FollowCamera) {
        require(targetDistanceMeters == null || targetDistanceMeters.isFinite() && targetDistanceMeters in 0.5..5.0) {
            "跟随目标距离须在 0.5–5.0 米之间"
        }
        require(reverseDistanceMeters == null || reverseDistanceMeters.isFinite() && reverseDistanceMeters in 0.5..5.0) {
            "倒车目标距离须在 0.5–5.0 米之间"
        }
        require(!avoidanceEnabled || camera == FollowCamera.ASTRA_PRO) {
            "启用实验避障时必须明确选择 Astra Pro 相机"
        }
    }
}

data class FollowConfiguration(
    val cameraProfile: FollowCamera,
    val targetDistanceMeters: Double,
    val maxRpm: Double,
    val forwardMaxRpm: Double,
    val parkingCurrentAmps: Double,
    val motorPort: String,
    val depthBackend: String,
    val models: List<String>,
    val cameraDetails: Map<String, String>,
    val rawJson: String,
    val execute: Boolean = false,
)

/** Board-reported phase timings, not a percentage or a promise of remaining time. */
data class FollowStartup(
    val stage: String,
    val stageElapsedMs: Long,
    val totalElapsedMs: Long,
    val stageTimeoutMs: Long,
    val totalTimeoutMs: Long,
    val completed: Boolean,
)

/** Null telemetry is unknown/unavailable, never an implied zero or a successful stop. */
data class FollowStatus(
    val runState: String,
    val execute: Boolean,
    val camera: FollowCamera,
    val behavior: String? = null,
    val reason: String? = null,
    val targetTrackId: String? = null,
    val targetAgeMs: Long? = null,
    val distanceMeters: Double? = null,
    val rangeAgeMs: Long? = null,
    val commandLeftRpm: Double? = null,
    val commandRightRpm: Double? = null,
    val feedbackLeftRpm: Double? = null,
    val feedbackRightRpm: Double? = null,
    val feedbackAgeMs: Long? = null,
    val stopRequested: Boolean = false,
    val stopAcknowledged: Boolean? = null,
    val physicalStopped: Boolean? = null,
    val statusAgeMs: Long? = null,
    val fault: String? = null,
    val processExited: Boolean = false,
    val targetDetected: Boolean? = null,
    val finalStatusPending: Boolean = false,
    val startup: FollowStartup? = null,
    val logDir: String? = null,
    val exitCode: Int? = null,
    val commandStop: Boolean? = null,
    val commandSimulated: Boolean? = null,
)

data class FollowReply(val status: FollowStatus, val terminal: Boolean)
data class FollowReady(val runId: String, val dryRun: Boolean, val execute: Boolean, val camera: FollowCamera)
data class FollowAvailability(val controlAvailable: Boolean, val reason: String?)

/** The launcher may print camera diagnostics before its one, complete configuration object. */
internal fun parseFollowConfiguration(
    stdout: String, camera: FollowCamera, expectedExecute: Boolean = false,
): FollowConfiguration {
    val json = followJsonObject(stdout, allowPrefix = true)
    followRequire(json.strictString("chain") == "continuous", "配置不是 continuous 跟随链")
    followRequire(!json.strictBoolean("hardware_opened"), "配置检查意外打开了硬件")
    val execute = json.strictBoolean("execute")
    followRequire(execute == expectedExecute, "配置检查与请求的实车/影子模式不一致")
    val controller = json.strictObject("controller")
    val targetDistance = json.strictNumber("effective_target_distance_m")
    val maxRpm = controller.strictNumber("max_rpm")
    val forwardMaxRpm = controller.strictNumber("forward_max_rpm")
    val parkingCurrent = json.strictNumber("motor_startup_parking_current_a")
    followRequire(targetDistance > 0, "目标跟随距离必须大于 0")
    followRequire(maxRpm > 0 && forwardMaxRpm in 0.0..maxRpm, "跟随转速上限无效")
    followRequire(parkingCurrent in 0.0..60.0, "跟随驻车电流无效")
    val modelArray = json.valueOrNull("models") as? JSONArray
        ?: throw IOException("跟随配置缺少模型列表")
    followRequire(modelArray.length() in 1..16, "跟随配置模型列表无效")
    val models = (0 until modelArray.length()).map { index ->
        val value = modelArray.opt(index)
        followRequire(value is String && value.isNotBlank(), "跟随模型路径无效")
        value as String
    }
    val cameraObject = json.strictObject("camera")
    val cameraDetails = linkedMapOf<String, String>()
    val keys = cameraObject.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = cameraObject.valueOrNull(key)
        followRequire(value is String, "相机配置字段 $key 类型无效")
        cameraDetails[key] = value as String
    }
    followRequire(cameraDetails.isNotEmpty(), "跟随配置缺少相机参数")
    return FollowConfiguration(
        cameraProfile = camera,
        targetDistanceMeters = targetDistance,
        maxRpm = maxRpm,
        forwardMaxRpm = forwardMaxRpm,
        parkingCurrentAmps = parkingCurrent,
        motorPort = json.strictString("motor_port"),
        depthBackend = json.strictString("depth_backend"),
        models = models,
        cameraDetails = cameraDetails,
        rawJson = json.toString(2),
        execute = execute,
    )
}

internal fun parseFollowStatus(json: JSONObject): FollowStatus {
    val runState = json.strictString("runState")
    followRequire(runState in setOf("STOPPED", "STARTING", "RUNNING", "STOPPING", "FAULT"), "跟随运行阶段无效")
    val camera = FollowCamera.entries.find { it.argument == json.strictString("camera") }
        ?: throw IOException("跟随相机类型无效")
    val target = json.optionalObject("target")
    val range = json.optionalObject("range")
    val command = json.optionalObject("command")
    val feedback = json.optionalObject("feedback")
    val stop = json.strictObject("stop")
    // The current motor owner reports communication ACKs, but has no physical-stop contract.
    followRequire(stop.valueOrNull("physicalStopped") == null, "开发板没有提供可验证的车轮停稳依据")
    val trackId = target?.let {
        when (val id = it.valueOrNull("trackId")) {
            null -> null // A fresh SEARCH observation can have no selected person.
            is String -> id.takeIf(String::isNotBlank)?.let { Diagnostics.safeText(it, 200) }
                ?: throw IOException("跟随目标编号无效")
            is Int -> id.toString()
            is Long -> id.toString()
            else -> throw IOException("跟随目标编号无效")
        }
    }
    val distance = range?.strictNumber("distanceM")
    followRequire(distance == null || distance >= 0, "跟随目标距离无效")
    val startup = json.optionalObject("startup")?.let {
        FollowStartup(
            stage = Diagnostics.safeText(it.strictString("stage"), 120),
            stageElapsedMs = it.strictAge("stageElapsedMs"),
            totalElapsedMs = it.strictAge("totalElapsedMs"),
            stageTimeoutMs = it.strictAge("stageTimeoutMs"),
            totalTimeoutMs = it.strictAge("totalTimeoutMs"),
            completed = it.strictBoolean("completed"),
        )
    }
    return FollowStatus(
        runState = runState,
        execute = json.strictBoolean("execute"),
        camera = camera,
        behavior = json.optionalString("behavior")?.let { Diagnostics.safeText(it, 200) },
        reason = json.optionalString("reason")?.let { Diagnostics.safeText(it, 600) },
        targetTrackId = trackId,
        targetAgeMs = target?.strictAge("ageMs"),
        distanceMeters = distance,
        rangeAgeMs = range?.strictAge("ageMs"),
        commandLeftRpm = command?.strictNumber("leftRpm"),
        commandRightRpm = command?.strictNumber("rightRpm"),
        feedbackLeftRpm = feedback?.strictNumber("leftRpm"),
        feedbackRightRpm = feedback?.strictNumber("rightRpm"),
        feedbackAgeMs = feedback?.strictAge("ageMs"),
        stopRequested = stop.strictBoolean("requested"),
        stopAcknowledged = stop.optionalBoolean("acknowledged"),
        physicalStopped = null,
        statusAgeMs = if (json.valueOrNull("statusAgeMs") == null) null else json.strictAge("statusAgeMs"),
        fault = json.optionalString("fault")?.let { Diagnostics.safeText(it, 600) },
        processExited = json.strictBoolean("processExited"),
        targetDetected = target?.optionalBoolean("detected"),
        finalStatusPending = json.optionalBoolean("finalStatusPending") ?: false,
        startup = startup,
        logDir = json.optionalString("logDir")?.let { Diagnostics.safeText(it, 600) },
        exitCode = json.optionalInteger("exitCode"),
        commandStop = command?.optionalBoolean("stop"),
        commandSimulated = command?.strictBoolean("simulated"),
    )
}

internal fun parseFollowAvailability(stdout: String): FollowAvailability {
    val json = followJsonObject(stdout, allowPrefix = false)
    followRequire(json.strictString("type") == "availability", "开发板控制占用响应无效")
    return FollowAvailability(json.strictBoolean("controlAvailable"),
        json.optionalString("reason")?.let { Diagnostics.safeText(it, 600) })
}

private fun followJsonObject(output: String, allowPrefix: Boolean): JSONObject {
    val text = output.trimStart('\uFEFF')
    val start = if (allowPrefix) {
        // Hints are complete lines; do not mistake braces embedded in a diagnostic for JSON.
        Regex("(?m)^[\\t ]*\\{").find(text)?.let { match -> text.indexOf('{', match.range.first) }
            ?: throw IOException("开发板未返回完整的跟随配置 JSON")
    } else {
        text.indexOfFirst { !it.isWhitespace() }.also {
            followRequire(it >= 0 && text[it] == '{', "开发板未返回有效 JSON 对象")
        }
    }
    val nesting = ArrayDeque<Char>()
    var quoted = false
    var escaped = false
    var end = -1
    for (index in start until text.length) {
        val character = text[index]
        if (quoted) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> quoted = false
            }
            continue
        }
        when (character) {
            '"' -> quoted = true
            '{', '[' -> nesting.addLast(character)
            '}', ']' -> {
                val expected = if (character == '}') '{' else '['
                followRequire(nesting.removeLastOrNull() == expected, "开发板 JSON 括号不匹配")
                if (nesting.isEmpty()) {
                    end = index + 1
                    break
                }
            }
        }
    }
    followRequire(end > start && !quoted && nesting.isEmpty(), "开发板 JSON 响应不完整")
    followRequire(text.substring(end).isBlank(), "开发板 JSON 后包含额外内容")
    return try {
        JSONObject(text.substring(start, end))
    } catch (failure: JSONException) {
        throw IOException("开发板返回了无效 JSON", failure)
    }
}

private fun JSONObject.valueOrNull(key: String): Any? = opt(key).takeUnless { it == JSONObject.NULL }

private fun JSONObject.strictString(key: String): String {
    val value = valueOrNull(key)
    followRequire(value is String && value.isNotBlank(), "跟随字段 $key 缺失或不是有效文本")
    return value as String
}

private fun JSONObject.optionalString(key: String): String? {
    val value = valueOrNull(key) ?: return null
    followRequire(value is String, "跟随字段 $key 不是文本")
    return value as String
}

private fun JSONObject.strictBoolean(key: String): Boolean = optionalBoolean(key)
    ?: throw IOException("跟随字段 $key 缺失")

private fun JSONObject.optionalBoolean(key: String): Boolean? {
    val value = valueOrNull(key) ?: return null
    followRequire(value is Boolean, "跟随字段 $key 不是布尔值")
    return value as Boolean
}

private fun JSONObject.strictNumber(key: String): Double {
    val value = valueOrNull(key)
    followRequire(value is Number && value.toDouble().isFinite(), "跟随字段 $key 不是有限数值")
    return (value as Number).toDouble()
}

private fun JSONObject.strictAge(key: String): Long {
    val value = valueOrNull(key)
    followRequire(value is Int || value is Long, "跟随字段 $key 不是整数毫秒")
    return (value as Number).toLong().also { followRequire(it >= 0, "跟随数据年龄不能为负数") }
}

private fun JSONObject.optionalInteger(key: String): Int? {
    val value = valueOrNull(key) ?: return null
    followRequire(value is Int || value is Long, "跟随字段 $key 不是整数")
    val number = (value as Number).toLong()
    followRequire(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong(), "跟随字段 $key 超出整数范围")
    return number.toInt()
}

private fun JSONObject.strictObject(key: String): JSONObject = optionalObject(key)
    ?: throw IOException("跟随字段 $key 缺失")

private fun JSONObject.optionalObject(key: String): JSONObject? {
    val value = valueOrNull(key) ?: return null
    followRequire(value is JSONObject, "跟随字段 $key 不是 JSON 对象")
    return value as JSONObject
}

private fun followRequire(condition: Boolean, message: String) {
    if (!condition) throw IOException(message)
}
