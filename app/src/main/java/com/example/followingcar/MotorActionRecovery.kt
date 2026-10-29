package com.example.followingcar

/** Recovery decisions are independent from UI visibility and the SSH connection lifecycle. */
internal const val MOTOR_ACTION_RECOVERY_KEY = "action_unconfirmed"

internal fun motorActionCompletionConfirmed(plan: MotorActionPlan, reply: MotorActionReply): Boolean {
    val result = reply.actionResult ?: return false
    return reply.ok && reply.error == null && !reply.reconnectRequired && !result.reconnectRequired &&
        result.action == plan.action && result.value == plan.value && result.durationMs == plan.durationMs &&
        result.outcome == MotorActionOutcome.ACKNOWLEDGED && result.error == null && result.cleanupConfirmed != false &&
        (plan.durationMs == 0 || result.cleanupConfirmed == true)
}

/** writeAllowed is only decoded after the board's idle, zero RPM/PWM and source checks. */
internal fun motorActionRecoveryReadConfirmed(reply: MotorConfigurationReply): Boolean =
    reply.ok && reply.error == null && !reply.reconnectRequired && reply.snapshot?.writeAllowed == true

internal fun motorActionNeedsVisibility(plan: MotorActionPlan?): Boolean =
    plan != null && plan.durationMs > 0 && plan.action.requiresDuration
