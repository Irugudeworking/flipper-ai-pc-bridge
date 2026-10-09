package ai.bridge

enum class AgentState {
    DISCONNECTED, CONNECTING, READY, REQUESTING_SCREENSHOT, WAITING_SCREENSHOT, THINKING,
    VALIDATING, SENDING_ACTIONS, EXECUTING, WAITING_RESULT, PAUSED, FINISHED, ERROR, STOPPED
}

/** Enforces safe, explicit transitions for the remote-control loop. */
class AgentStateMachine(initial: AgentState = AgentState.DISCONNECTED) {
    @Volatile private var current = initial
    fun current(): AgentState = current

    @Synchronized fun transition(next: AgentState): AgentState {
        if(next == current) return current
        require(next in transitions.getValue(current)) { "Illegal agent transition: $current → $next" }
        current = next
        return current
    }

    companion object {
        private val transitions = mapOf(
            AgentState.DISCONNECTED to setOf(AgentState.CONNECTING, AgentState.READY),
            AgentState.CONNECTING to setOf(AgentState.DISCONNECTED, AgentState.READY, AgentState.ERROR),
            AgentState.READY to setOf(AgentState.CONNECTING, AgentState.REQUESTING_SCREENSHOT, AgentState.SENDING_ACTIONS, AgentState.STOPPED, AgentState.ERROR, AgentState.DISCONNECTED),
            AgentState.REQUESTING_SCREENSHOT to setOf(AgentState.WAITING_SCREENSHOT, AgentState.THINKING, AgentState.PAUSED, AgentState.ERROR, AgentState.STOPPED, AgentState.DISCONNECTED),
            AgentState.WAITING_SCREENSHOT to setOf(AgentState.THINKING, AgentState.PAUSED, AgentState.ERROR, AgentState.STOPPED, AgentState.DISCONNECTED),
            AgentState.THINKING to setOf(AgentState.VALIDATING, AgentState.PAUSED, AgentState.ERROR, AgentState.STOPPED, AgentState.DISCONNECTED),
            AgentState.VALIDATING to setOf(AgentState.SENDING_ACTIONS, AgentState.REQUESTING_SCREENSHOT, AgentState.PAUSED, AgentState.FINISHED, AgentState.ERROR, AgentState.STOPPED, AgentState.DISCONNECTED),
            AgentState.SENDING_ACTIONS to setOf(AgentState.EXECUTING, AgentState.WAITING_RESULT, AgentState.ERROR, AgentState.STOPPED, AgentState.DISCONNECTED),
            AgentState.EXECUTING to setOf(AgentState.WAITING_RESULT, AgentState.REQUESTING_SCREENSHOT, AgentState.PAUSED, AgentState.FINISHED, AgentState.ERROR, AgentState.STOPPED, AgentState.DISCONNECTED),
            AgentState.WAITING_RESULT to setOf(AgentState.EXECUTING, AgentState.REQUESTING_SCREENSHOT, AgentState.PAUSED, AgentState.FINISHED, AgentState.ERROR, AgentState.STOPPED, AgentState.DISCONNECTED),
            AgentState.PAUSED to setOf(AgentState.READY, AgentState.REQUESTING_SCREENSHOT, AgentState.WAITING_SCREENSHOT, AgentState.THINKING, AgentState.VALIDATING, AgentState.SENDING_ACTIONS, AgentState.EXECUTING, AgentState.WAITING_RESULT, AgentState.STOPPED, AgentState.ERROR, AgentState.DISCONNECTED),
            AgentState.FINISHED to setOf(AgentState.REQUESTING_SCREENSHOT, AgentState.READY, AgentState.STOPPED, AgentState.DISCONNECTED),
            AgentState.ERROR to setOf(AgentState.REQUESTING_SCREENSHOT, AgentState.READY, AgentState.CONNECTING, AgentState.DISCONNECTED, AgentState.STOPPED),
            AgentState.STOPPED to setOf(AgentState.REQUESTING_SCREENSHOT, AgentState.READY, AgentState.CONNECTING, AgentState.DISCONNECTED)
        )
    }
}
