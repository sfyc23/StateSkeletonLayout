package com.sfyc.demo.network

/** 当前 View 的动画令牌；消费后先解绑，StateFlow 重放不能重复 finish。 */
internal class FeedGestureTracker {
    private val bindings = mutableMapOf<RequestKind, RequestToken>()

    fun isBound(kind: RequestKind) = bindings.containsKey(kind)
    fun bind(token: RequestToken) { bindings[token.kind] = token }

    fun takeFinished(ui: FeedUiState, modelsReady: Boolean): List<RequestCompletion> = buildList {
        bindings.toMap().forEach { (kind, token) ->
            val completion = ui.lastCompletion?.takeIf { it.token == token }
            val result = when {
                completion != null && (completion.status != CompletionStatus.SUCCESS || modelsReady) -> completion
                completion != null -> null
                ui.activeRequest != token -> RequestCompletion(token, CompletionStatus.CANCELLED)
                else -> null
            }
            if (result != null) {
                bindings.remove(kind)
                add(result)
            }
        }
    }

    fun clear(): List<RequestToken> = bindings.values.toList().also { bindings.clear() }
}
